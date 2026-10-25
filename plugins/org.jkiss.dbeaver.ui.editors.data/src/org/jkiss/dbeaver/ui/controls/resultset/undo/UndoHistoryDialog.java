/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp and others
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.controls.resultset.undo;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceSQL;
import org.jkiss.dbeaver.ui.UIUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * dbeaver-mm K77: Plus &gt; Change history... Lists the local undo scripts written on grid Saves,
 * previews the selected script and opens it in a SQL editor for the connection it was written for.
 * <p>
 * Listing, reading a script and toggling the setting all run in background jobs; the UI thread only
 * builds widgets and applies job results with {@link UIUtils#asyncExec}.
 */
public class UndoHistoryDialog extends Dialog {

    private static final Log log = Log.getLog(UndoHistoryDialog.class);

    private static final String EMPTY_TEXT =
        "No grid changes saved yet. Each Save in a table grid keeps an undo script here.";

    private static final int OPEN_BUTTON_ID = IDialogConstants.CLIENT_ID;
    private static final int COPY_BUTTON_ID = IDialogConstants.CLIENT_ID + 1;

    private Table table;
    private StyledText preview;
    private Label messageLabel;
    private Button keepCheck;
    private Button openButton;
    private Button copyButton;

    private List<UndoScript.Entry> entries = new ArrayList<>();
    private UndoScript.Entry currentEntry;
    private String currentScript = "";
    private boolean scriptLoaded;
    private boolean updatingTable;
    private int previewGeneration;

    public UndoHistoryDialog(@NotNull Shell shell) {
        super(shell);
    }

    /** dbeaver-mm K77: Plus menu entry */
    public static class Handler extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            new UndoHistoryDialog(HandlerUtil.getActiveShell(event)).open();
            return null;
        }
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Change history");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));
        GridData areaData = new GridData(SWT.FILL, SWT.FILL, true, true);
        areaData.widthHint = 980;
        areaData.heightHint = 520;
        area.setLayoutData(areaData);

        table = new Table(area, SWT.BORDER | SWT.SINGLE | SWT.FULL_SELECTION);
        table.setHeaderVisible(true);
        table.setLinesVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.widthHint = 460;
        tableData.heightHint = 400;
        table.setLayoutData(tableData);
        new TableColumn(table, SWT.LEFT).setText("Time");
        new TableColumn(table, SWT.LEFT).setText("Connection");
        new TableColumn(table, SWT.LEFT).setText("Table");
        new TableColumn(table, SWT.LEFT).setText("Change");
        int[] widths = {170, 130, 110, 150};
        for (int i = 0; i < widths.length; i++) {
            table.getColumn(i).setWidth(widths[i]);
        }
        table.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            if (!updatingTable) {
                onSelectionChanged();
            }
        }));

        preview = new StyledText(area, SWT.BORDER | SWT.READ_ONLY | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        preview.setFont(UIUtils.getMonospaceFont());
        preview.setMargins(4, 4, 4, 4);
        preview.setText(EMPTY_TEXT);

        messageLabel = new Label(area, SWT.WRAP);
        messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        keepCheck = new Button(area, SWT.CHECK);
        keepCheck.setText("Keep an undo script after each grid Save (local only, deleted after 30 days)");
        keepCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        keepCheck.addListener(SWT.Selection, e -> saveKeepSetting(keepCheck.getSelection()));

        readHistory();
        readKeepSetting();
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        openButton = createButton(parent, OPEN_BUTTON_ID, "Open in SQL editor", false);
        copyButton = createButton(parent, COPY_BUTTON_ID, "Copy", false);
        createButton(parent, IDialogConstants.CLOSE_ID, IDialogConstants.CLOSE_LABEL, true);
        openButton.setEnabled(false);
        copyButton.setEnabled(false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == IDialogConstants.CLOSE_ID) {
            close();
        } else if (buttonId == OPEN_BUTTON_ID) {
            openInSqlEditor();
        } else if (buttonId == COPY_BUTTON_ID) {
            copyScript();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    /** dbeaver-mm K77: the folder is read in a background job */
    private void readHistory() {
        new AbstractJob("Read change history") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<UndoScript.Entry> list = UndoScript.list(UndoScript.folder());
                UIUtils.asyncExec(() -> applyHistory(list));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /** dbeaver-mm K77: the setting file is read in a background job */
    private void readKeepSetting() {
        new AbstractJob("Read change history setting") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                boolean enabled = UndoScript.isEnabled(UndoScript.settingsFile());
                UIUtils.asyncExec(() -> {
                    if (keepCheck != null && !keepCheck.isDisposed()) {
                        keepCheck.setSelection(enabled);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void applyHistory(@NotNull List<UndoScript.Entry> loaded) {
        if (table == null || table.isDisposed()) {
            return;
        }
        entries = loaded;
        updatingTable = true;
        try {
            table.removeAll();
            for (UndoScript.Entry entry : entries) {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[] {
                    displayTime(entry.time()),
                    display(entry.connectionName(), entry.connectionId()),
                    display(entry.table(), ""),
                    changeText(entry)});
            }
            if (!entries.isEmpty()) {
                table.select(0);
            }
        } finally {
            updatingTable = false;
        }
        if (entries.isEmpty()) {
            currentEntry = null;
            currentScript = "";
            scriptLoaded = false;
            preview.setText(EMPTY_TEXT);
            updateButtons();
        } else {
            onSelectionChanged();
        }
    }

    /** dbeaver-mm K77: the selected script is read in a background job */
    private void onSelectionChanged() {
        int index = table.getSelectionIndex();
        if (index < 0 || index >= entries.size()) {
            currentEntry = null;
            currentScript = "";
            scriptLoaded = false;
            preview.setText(EMPTY_TEXT);
            updateButtons();
            return;
        }
        UndoScript.Entry entry = entries.get(index);
        currentEntry = entry;
        currentScript = "";
        scriptLoaded = false;
        messageLabel.setText("");
        preview.setText("Loading...");
        updateButtons();

        int generation = ++previewGeneration;
        new AbstractJob("Read undo script") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                String text = "";
                try {
                    text = Files.readString(entry.file(), StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.debug("Can't read undo script " + entry.file(), e);
                }
                String script = text;
                UIUtils.asyncExec(() -> {
                    if (generation != previewGeneration || preview == null || preview.isDisposed()) {
                        return;
                    }
                    currentEntry = entry;
                    currentScript = script;
                    scriptLoaded = true;
                    preview.setText(script);
                    preview.setTopIndex(0);
                    updateButtons();
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void updateButtons() {
        boolean ready = scriptLoaded && currentEntry != null;
        if (openButton != null && !openButton.isDisposed()) {
            openButton.setEnabled(ready);
        }
        if (copyButton != null && !copyButton.isDisposed()) {
            copyButton.setEnabled(ready);
        }
    }

    private void openInSqlEditor() {
        UndoScript.Entry entry = currentEntry;
        if (entry == null || !scriptLoaded) {
            return;
        }
        DBPDataSourceContainer container = findConnection(entry.connectionId());
        if (container == null) {
            messageLabel.setText("Connection " + display(entry.connectionName(), entry.connectionId())
                + " not found — copy the script and run it where the table is.");
            return;
        }
        UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
        if (serviceSQL == null) {
            messageLabel.setText("SQL editor is not available — copy the script and run it where the table is.");
            return;
        }
        serviceSQL.openSQLConsole(container, null, null, "Undo " + entry.table(), currentScript);
    }

    private void copyScript() {
        if (!scriptLoaded) {
            return;
        }
        UIUtils.setClipboardContents(preview.getDisplay(), TextTransfer.getInstance(), currentScript);
        messageLabel.setText("Script copied to the clipboard.");
    }

    /** dbeaver-mm K77: the setting file is written in a background job */
    private void saveKeepSetting(boolean enabled) {
        new AbstractJob("Save change history setting") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    UndoScript.setEnabled(UndoScript.settingsFile(), enabled);
                } catch (Exception e) {
                    log.debug("Can't save change history setting", e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    @Nullable
    private static DBPDataSourceContainer findConnection(@Nullable String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            return null;
        }
        for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
            DBPDataSourceContainer container = project.getDataSourceRegistry().getDataSource(connectionId);
            if (container != null) {
                return container;
            }
        }
        return null;
    }

    @NotNull
    private static String displayTime(@Nullable String time) {
        return time == null ? "" : time.replace('T', ' ');
    }

    @NotNull
    private static String display(@Nullable String value, @NotNull String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    @NotNull
    private static String changeText(@NotNull UndoScript.Entry entry) {
        List<String> parts = new ArrayList<>();
        if (entry.deleted() > 0) {
            parts.add(entry.deleted() + " deleted");
        }
        if (entry.updated() > 0) {
            parts.add(entry.updated() + " updated");
        }
        if (entry.inserted() > 0) {
            parts.add(entry.inserted() + " inserted");
        }
        return String.join(", ", parts);
    }
}
