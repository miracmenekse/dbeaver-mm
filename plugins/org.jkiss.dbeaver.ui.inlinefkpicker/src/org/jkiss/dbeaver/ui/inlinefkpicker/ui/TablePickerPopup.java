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
package org.jkiss.dbeaver.ui.inlinefkpicker.ui;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * dbeaver-mm K11: table name completion across connections. After {@code from } / {@code join }
 * this lists the tables of the editor's candidate connections (connections bar, else the tagged
 * ones), filtered as you type. Accepting inserts the name and hands the connection to the caller,
 * which switches the editor to it - so a table of another connection can be typed without first
 * switching by hand. Only cached table lists are read, never data.
 */
public final class TablePickerPopup {

    private static final Log log = Log.getLog(TablePickerPopup.class);

    private static final int MAX_RESULTS = 200;
    private static final int FILTER_DELAY_MS = 250;
    private static final int LIST_WIDTH = 360;
    private static final int LIST_HEIGHT = 220;

    private static TablePickerPopup instance;

    private final ITextViewer viewer;
    private final StyledText styledText;
    private final List<DBPDataSourceContainer> candidates;
    private final BiConsumer<DBPDataSourceContainer, String> onPick;

    private Shell shell;
    private Text filterText;
    private Table table;
    private List<InlineFkService.TableRef> rows = new ArrayList<>();
    private int filterSeq;
    private int insertStart;

    private TablePickerPopup(
        ITextViewer viewer,
        List<DBPDataSourceContainer> candidates,
        BiConsumer<DBPDataSourceContainer, String> onPick
    ) {
        this.viewer = viewer;
        this.styledText = viewer.getTextWidget();
        this.candidates = candidates;
        this.onPick = onPick;
    }

    /**
     * Opens the picker at the caret. Does nothing when there is nothing to offer.
     */
    public static void trigger(
        ITextViewer viewer,
        List<DBPDataSourceContainer> candidates,
        BiConsumer<DBPDataSourceContainer, String> onPick
    ) {
        trigger(viewer, candidates, viewer == null ? 0 : viewer.getSelectedRange().x, onPick);
    }

    /**
     * dbeaver-mm K13: same, but the text from {@code replaceFrom} to the caret (e.g. the typed
     * {@code test.}) is replaced by the chosen table name.
     */
    public static void trigger(
        ITextViewer viewer,
        List<DBPDataSourceContainer> candidates,
        int replaceFrom,
        BiConsumer<DBPDataSourceContainer, String> onPick
    ) {
        if (viewer == null || viewer.getTextWidget() == null || candidates.isEmpty()) {
            return;
        }
        TablePickerPopup popup = new TablePickerPopup(viewer, candidates, onPick);
        popup.insertStart = replaceFrom;
        popup.load(null, popup::open);
    }

    private void load(String filter, Runnable then) {
        int seq = ++filterSeq;
        AbstractJob job = new AbstractJob("List tables for SQL completion") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                List<InlineFkService.TableRef> result =
                    InlineFkService.listTables(monitor, candidates, filter, MAX_RESULTS);
                UIUtils.asyncExec(() -> {
                    if (seq != filterSeq) {
                        return;
                    }
                    rows = result;
                    then.run();
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.schedule();
    }

    private void open() {
        if (rows.isEmpty() || styledText.isDisposed()) {
            return;
        }
        closeCurrent();

        shell = new Shell(styledText.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.BORDER);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        layout.verticalSpacing = 2;
        shell.setLayout(layout);

        filterText = new Text(shell, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.setMessage("Type to filter tables");

        table = new Table(shell, SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER | SWT.SINGLE);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.widthHint = LIST_WIDTH;
        tableData.heightHint = LIST_HEIGHT;
        table.setLayoutData(tableData);
        table.setHeaderVisible(true);
        UIUtils.createTableColumn(table, SWT.LEFT, "Table");
        UIUtils.createTableColumn(table, SWT.LEFT, "Connection");

        populate();

        filterText.addListener(SWT.KeyDown, this::onKey);
        filterText.addModifyListener(e -> scheduleFilter());
        table.addListener(SWT.MouseDoubleClick, e -> accept());
        shell.addListener(SWT.Deactivate, e -> close());
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
                close();
            }
        });

        shell.pack();
        positionBelowCaret();
        instance = this;
        shell.open();
        filterText.setFocus();
    }

    private void populate() {
        table.removeAll();
        for (InlineFkService.TableRef ref : rows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, ref.tableName());
            item.setText(1, ref.container().getName());
        }
        UIUtils.packColumns(table, true);
        if (table.getItemCount() > 0) {
            table.setSelection(0);
        }
    }

    private void onKey(Event e) {
        switch (e.keyCode) {
            case SWT.ARROW_DOWN -> move(1, e);
            case SWT.ARROW_UP -> move(-1, e);
            case SWT.PAGE_DOWN -> move(10, e);
            case SWT.PAGE_UP -> move(-10, e);
            case SWT.CR, SWT.KEYPAD_CR, SWT.TAB -> {
                accept();
                e.doit = false;
            }
            default -> {
            }
        }
    }

    private void move(int delta, Event e) {
        int count = table.getItemCount();
        if (count > 0) {
            int idx = Math.max(0, Math.min(count - 1, table.getSelectionIndex() + delta));
            table.setSelection(idx);
            table.showSelection();
        }
        e.doit = false;
    }

    private void scheduleFilter() {
        Display display = shell.getDisplay();
        display.timerExec(-1, this::runFilter);
        display.timerExec(FILTER_DELAY_MS, this::runFilter);
    }

    private void runFilter() {
        if (shell == null || shell.isDisposed()) {
            return;
        }
        load(filterText.getText(), () -> {
            if (shell != null && !shell.isDisposed()) {
                populate();
            }
        });
    }

    private void accept() {
        int idx = table.getSelectionIndex();
        if (idx < 0 || idx >= rows.size()) {
            close();
            return;
        }
        InlineFkService.TableRef ref = rows.get(idx);
        close();
        insert(ref.tableName());
        onPick.accept(ref.container(), ref.tableName());
    }

    private void insert(String tableName) {
        try {
            int caret = viewer.getSelectedRange().x;
            int start = Math.min(insertStart, caret);
            viewer.getDocument().replace(start, caret - start, tableName);
            int newCaret = start + tableName.length();
            viewer.setSelectedRange(newCaret, 0);
            viewer.revealRange(newCaret, 0);
            if (!styledText.isDisposed()) {
                styledText.setFocus();
            }
        } catch (BadLocationException e) {
            log.debug("Failed to insert table name", e);
        }
    }

    private void positionBelowCaret() {
        try {
            Point loc = styledText.getLocationAtOffset(styledText.getCaretOffset());
            Point disp = styledText.toDisplay(loc);
            Point size = shell.getSize();
            org.eclipse.swt.graphics.Rectangle screen = styledText.getMonitor().getClientArea();
            int x = Math.min(disp.x, screen.x + screen.width - size.x);
            int y = disp.y + styledText.getLineHeight();
            if (y + size.y > screen.y + screen.height) {
                y = disp.y - size.y;
            }
            shell.setLocation(Math.max(screen.x, x), Math.max(screen.y, y));
        } catch (Exception e) {
            log.debug("Failed to position the table picker", e);
        }
    }

    private static void closeCurrent() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private void close() {
        if (instance == this) {
            instance = null;
        }
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
    }
}
