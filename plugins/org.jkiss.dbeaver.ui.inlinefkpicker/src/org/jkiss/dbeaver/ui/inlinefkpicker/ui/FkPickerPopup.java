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
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkColumnRef;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkRow;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;

import java.util.ArrayList;
import java.util.List;

/**
 * The compact inline popup that lists reference-table rows and inserts the chosen id literal at the
 * caret. Pure UI: it receives already-resolved metadata/rows and re-queries on filter changes.
 * Navigation: arrow keys / scroll, type to filter (server-side, debounced), Enter/Tab to accept,
 * Esc to cancel. In {@link FkColumnRef.Mode#IN_LIST} mode rows are checkable for multi-select.
 */
public final class FkPickerPopup {

    private static final Log log = Log.getLog(FkPickerPopup.class);

    private static final int FILTER_DELAY_MS = 250;
    private static final int LIST_HEIGHT = 200;
    private static final int LIST_WIDTH = 340;

    // Only one popup at a time.
    private static FkPickerPopup instance;

    private final ITextViewer viewer;
    private final StyledText styledText;
    private final IDocument document;
    private final DBCExecutionContext context;
    private final DBSEntity entity;
    private final DBSEntityAttribute keyColumn;
    private final FkColumnRef ref;

    private Shell shell;
    private Text filterText;
    private Table table;
    private Button labelButton;
    private boolean menuOpen;
    private List<FkRow> rows;
    private int filterSeq;
    private final Runnable filterRunnable = this::runFilter;

    private FkPickerPopup(
        ITextViewer viewer,
        DBCExecutionContext context,
        DBSEntity entity,
        DBSEntityAttribute keyColumn,
        FkColumnRef ref,
        List<FkRow> initialRows
    ) {
        this.viewer = viewer;
        this.styledText = viewer.getTextWidget();
        this.document = viewer.getDocument();
        this.context = context;
        this.entity = entity;
        this.keyColumn = keyColumn;
        this.ref = ref;
        this.rows = initialRows;
    }

    /**
     * Entry point used by both the manual command handler and the auto-trigger. Resolves the target
     * entity, key column and initial rows in a background job, then opens the popup on the UI thread.
     * Silently does nothing when there is no live connection or the table/key cannot be resolved.
     */
    public static void trigger(
        ITextViewer viewer,
        DBCExecutionContext context,
        FkColumnRef ref
    ) {
        if (viewer == null || context == null || ref == null) {
            return;
        }
        final StyledText styledText = viewer.getTextWidget();
        if (styledText == null || styledText.isDisposed() || viewer.getDocument() == null) {
            return;
        }
        AbstractJob job = new AbstractJob("Resolve inline FK picker data") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                try {
                    InlineFkService.ResolvedColumn target = InlineFkService.resolveTarget(monitor, context, ref);
                    if (target == null) {
                        return Status.OK_STATUS;
                    }
                    final DBSEntity entity = target.getEntity();
                    final DBSEntityAttribute key = target.getColumn();
                    // The values come from the referenced table, which may live in another connection
                    final DBCExecutionContext valueContext = target.getContext();
                    List<FkRow> initialRows = InlineFkService.enumerate(
                        monitor, valueContext, entity, key, ref.getPrefix(), InlineFkService.DEFAULT_MAX_RESULTS);
                    UIUtils.asyncExec(() -> {
                        if (styledText.isDisposed()) {
                            return;
                        }
                        closeCurrent();
                        new FkPickerPopup(viewer, valueContext, entity, key, ref, initialRows).open();
                    });
                } catch (Throwable e) {
                    log.debug("Inline FK picker trigger failed", e);
                }
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setUser(false);
        job.schedule();
    }

    private static void closeCurrent() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }

    private void open() {
        boolean multi = ref.getMode() == FkColumnRef.Mode.IN_LIST;

        shell = new Shell(styledText.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.BORDER);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        layout.verticalSpacing = 2;
        shell.setLayout(layout);

        Composite headerRow = new Composite(shell, SWT.NONE);
        GridLayout headerLayout = new GridLayout(2, false);
        headerLayout.marginWidth = 0;
        headerLayout.marginHeight = 0;
        headerRow.setLayout(headerLayout);
        headerRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        Label header = new Label(headerRow, SWT.NONE);
        header.setText(entity.getName() + " → " + keyColumn.getName()
            + (multi ? "  (Space: toggle, Enter: insert list)" : ""));
        header.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        // dbeaver-mm: pick which column of the table is shown next to the id
        labelButton = new Button(headerRow, SWT.PUSH | SWT.FLAT);
        updateLabelButton();
        labelButton.addListener(SWT.Selection, e -> showLabelColumnMenu());

        filterText = new Text(shell, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filterText.setMessage("Type to filter…");
        if (ref.getPrefix() != null && !ref.getPrefix().isEmpty()) {
            filterText.setText(ref.getPrefix());
            filterText.setSelection(filterText.getCharCount());
        }

        int tableStyle = SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER | (multi ? SWT.CHECK : SWT.SINGLE);
        table = new Table(shell, tableStyle);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.heightHint = LIST_HEIGHT;
        tableData.widthHint = LIST_WIDTH;
        table.setLayoutData(tableData);
        table.setHeaderVisible(false);
        table.setLinesVisible(false);
        new TableColumn(table, SWT.LEFT);
        new TableColumn(table, SWT.LEFT);

        populate(rows);

        // Keyboard: drive table selection from the filter field, accept/cancel.
        filterText.addListener(SWT.KeyDown, e -> onFilterKey(e, multi));
        filterText.addModifyListener(e -> scheduleFilter());

        table.addListener(SWT.MouseDoubleClick, e -> accept(multi));

        shell.addListener(SWT.Deactivate, e -> {
            if (!menuOpen) {
                close();
            }
        });
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

    private void populate(List<FkRow> newRows) {
        this.rows = newRows;
        table.removeAll();
        for (FkRow row : newRows) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, row.getIdText());
            item.setText(1, row.getLabel());
        }
        table.getColumn(0).pack();
        table.getColumn(1).pack();
        if (table.getItemCount() > 0) {
            table.setSelection(0);
        }
    }

    private void updateLabelButton() {
        String current = InlineFkService.getLabelColumn(entity);
        labelButton.setText((current == null ? "Label" : current) + " ▾");
        labelButton.setToolTipText("Column of " + entity.getName() + " shown next to the value");
        labelButton.getParent().layout(true);
    }

    private void showLabelColumnMenu() {
        String current = InlineFkService.getLabelColumn(entity);
        Menu menu = new Menu(shell, SWT.POP_UP);
        for (String column : InlineFkService.listLabelColumns(new VoidProgressMonitor(), entity)) {
            MenuItem item = new MenuItem(menu, SWT.RADIO);
            item.setText(column);
            item.setSelection(column.equalsIgnoreCase(current));
            item.addListener(SWT.Selection, e -> {
                if (item.getSelection()) {
                    InlineFkService.setLabelColumn(entity, column);
                    updateLabelButton();
                    runFilter();
                }
            });
        }
        // The menu takes focus from the popup shell; don't let that close the popup
        menuOpen = true;
        menu.addListener(SWT.Hide, e -> shell.getDisplay().asyncExec(() -> {
            menuOpen = false;
            if (!shell.isDisposed()) {
                shell.setActive();
                filterText.setFocus();
            }
            menu.dispose();
        }));
        Rectangle bounds = labelButton.getBounds();
        menu.setLocation(labelButton.getParent().toDisplay(bounds.x, bounds.y + bounds.height));
        menu.setVisible(true);
    }

    private void onFilterKey(org.eclipse.swt.widgets.Event e, boolean multi) {
        switch (e.keyCode) {
            case SWT.ARROW_DOWN:
                moveSelection(1);
                e.doit = false;
                break;
            case SWT.ARROW_UP:
                moveSelection(-1);
                e.doit = false;
                break;
            case SWT.PAGE_DOWN:
                moveSelection(10);
                e.doit = false;
                break;
            case SWT.PAGE_UP:
                moveSelection(-10);
                e.doit = false;
                break;
            case SWT.CR:
            case SWT.KEYPAD_CR:
            case SWT.TAB:
                accept(multi);
                e.doit = false;
                break;
            default:
                if (multi && e.keyCode == ' ' && table.getSelectionIndex() >= 0) {
                    // Space toggles the current row's check without typing a space into the filter.
                    TableItem item = table.getItem(table.getSelectionIndex());
                    item.setChecked(!item.getChecked());
                    e.doit = false;
                }
                break;
        }
    }

    private void moveSelection(int delta) {
        int count = table.getItemCount();
        if (count == 0) {
            return;
        }
        int idx = table.getSelectionIndex();
        int next = Math.max(0, Math.min(count - 1, (idx < 0 ? 0 : idx) + delta));
        table.setSelection(next);
        table.showSelection();
    }

    private void scheduleFilter() {
        Display display = shell.getDisplay();
        display.timerExec(-1, filterRunnable);
        display.timerExec(FILTER_DELAY_MS, filterRunnable);
    }

    private void runFilter() {
        if (shell == null || shell.isDisposed()) {
            return;
        }
        final String filter = filterText.getText();
        final int seq = ++filterSeq;
        AbstractJob job = new AbstractJob("Filter inline FK rows") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                final List<FkRow> result = InlineFkService.enumerate(
                    monitor, context, entity, keyColumn, filter, InlineFkService.DEFAULT_MAX_RESULTS);
                UIUtils.asyncExec(() -> {
                    if (shell == null || shell.isDisposed() || seq != filterSeq) {
                        return; // superseded by a newer keystroke
                    }
                    populate(result);
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setUser(false);
        job.schedule();
    }

    private void accept(boolean multi) {
        List<String> literals = new ArrayList<>();
        if (multi) {
            for (TableItem item : table.getItems()) {
                if (item.getChecked()) {
                    FkRow row = rowFor(item);
                    if (row != null) {
                        literals.add(row.getLiteral());
                    }
                }
            }
        }
        if (literals.isEmpty()) {
            int idx = table.getSelectionIndex();
            if (idx >= 0 && idx < rows.size()) {
                literals.add(rows.get(idx).getLiteral());
            }
        }
        if (literals.isEmpty()) {
            close();
            return;
        }
        insert(literals);
        close();
    }

    private FkRow rowFor(TableItem item) {
        int idx = table.indexOf(item);
        return (idx >= 0 && idx < rows.size()) ? rows.get(idx) : null;
    }

    private void insert(List<String> literals) {
        StringBuilder sb = new StringBuilder();
        if (ref.isNeedsLeadingSpace()) {
            sb.append(' ');
        }
        sb.append(String.join(", ", literals));
        try {
            int start = ref.getReplaceStart();
            int length = ref.getReplaceEnd() - ref.getReplaceStart();
            if (start < 0 || start + length > document.getLength()) {
                return;
            }
            document.replace(start, length, sb.toString());
            int newCaret = start + sb.length();
            // Use model coordinates via the viewer so folding/projection offsets stay correct.
            viewer.setSelectedRange(newCaret, 0);
            viewer.revealRange(newCaret, 0);
            if (!styledText.isDisposed()) {
                styledText.setFocus();
            }
        } catch (BadLocationException e) {
            log.debug("Failed to insert FK id literal", e);
        }
    }

    private void positionBelowCaret() {
        try {
            int caretOffset = styledText.getCaretOffset();
            Point loc = styledText.getLocationAtOffset(caretOffset);
            Point disp = styledText.toDisplay(loc);
            int lineHeight = styledText.getLineHeight();
            Point size = shell.getSize();
            org.eclipse.swt.graphics.Rectangle screen = styledText.getMonitor().getClientArea();
            int x = Math.min(disp.x, screen.x + screen.width - size.x);
            int y = disp.y + lineHeight;
            if (y + size.y > screen.y + screen.height) {
                // Not enough room below - place above the caret line.
                y = disp.y - size.y;
            }
            shell.setLocation(Math.max(screen.x, x), Math.max(screen.y, y));
        } catch (Exception e) {
            log.debug("Failed to position FK picker", e);
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
