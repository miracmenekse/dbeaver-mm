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
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.dialogs.BaseDialog;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkColumnRef;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkRow;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.SqlWhereAnalyzer;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.WhereCondition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Centered form listing every editable filter condition of a statement, so a saved script can be
 * re-parameterised in one place instead of hunting through the text.
 * <p>
 * Conditions that already carry a value show it pre-filled and stay editable; {@code ?}
 * placeholders start empty. Values are chosen from the same enumeration the inline picker uses
 * ({@link InlineFkService#enumerate}), or typed by hand when a column cannot be resolved.
 * <p>
 * The dialog only rewrites the document - it never executes the query.
 */
public class WhereParamsDialog extends BaseDialog {

    private static final Log log = Log.getLog(WhereParamsDialog.class);

    private static final int FILTER_DELAY_MS = 250;
    private static final String EMPTY_VALUE_HINT = "<secilmedi>";

    private final List<WhereCondition> conditions;
    private final List<FkColumnRef.TableRef> fromTables;
    private final DBCExecutionContext context;

    /** Current (possibly edited) literal per condition; starts from what the script already has. */
    private final Map<WhereCondition, String> values = new HashMap<>();
    /** Enumerated rows per condition, so re-selecting a row does not re-query. */
    private final Map<WhereCondition, List<FkRow>> rowCache = new HashMap<>();

    private Tree conditionTree;
    private Label detailHeader;
    private Text valueText;
    private Text filterText;
    private Table valueTable;

    private WhereCondition current;
    private List<FkRow> currentRows = new ArrayList<>();
    private int filterSeq;
    private boolean updatingValueText;
    private final Runnable filterRunnable = this::runFilter;

    public WhereParamsDialog(
        @NotNull Shell parentShell,
        @NotNull String scriptName,
        @NotNull SqlWhereAnalyzer.Result scan,
        @Nullable DBCExecutionContext context
    ) {
        super(parentShell, "Script parametreleri - " + scriptName, null);
        this.conditions = scan.getConditions();
        this.fromTables = scan.getFromTables();
        this.context = context;
        for (WhereCondition condition : conditions) {
            values.put(condition, condition.isPlaceholder() ? "" : condition.getValueText());
        }
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Composite createDialogArea(@NotNull Composite parent) {
        Composite area = super.createDialogArea(parent);
        Composite root = UIUtils.createComposite(area, 1);
        root.setLayoutData(new GridData(GridData.FILL_BOTH));

        UIUtils.createLabel(root, "Kosullari doldurun; hazir degerler degistirilebilir. Sorgu calistirilmaz.");

        createConditionTree(root);
        createDetailArea(root);

        selectFirstEditable();
        return area;
    }

    private void createConditionTree(@NotNull Composite parent) {
        conditionTree = new Tree(parent, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE);
        GridData layoutData = new GridData(GridData.FILL_HORIZONTAL);
        layoutData.heightHint = 190;
        conditionTree.setLayoutData(layoutData);
        conditionTree.setHeaderVisible(true);
        conditionTree.setLinesVisible(false);

        TreeColumn columnColumn = new TreeColumn(conditionTree, SWT.LEFT);
        columnColumn.setText("Kosul");
        TreeColumn valueColumn = new TreeColumn(conditionTree, SWT.LEFT);
        valueColumn.setText("Deger");

        // WHERE conditions first - those are what the user came for; the JOIN ON ones are real
        // filters too (e.g. sale_cnl_id = 4) so they are offered, just below and clearly labelled.
        addGroup("WHERE kosullari", true);
        addGroup("JOIN kosullari", false);

        for (TreeColumn column : conditionTree.getColumns()) {
            column.pack();
        }
        conditionTree.addListener(SWT.Selection, e -> onConditionSelected());
    }

    private void addGroup(@NotNull String label, boolean whereClause) {
        List<WhereCondition> group = new ArrayList<>();
        for (WhereCondition condition : conditions) {
            if (condition.isInWhereClause() == whereClause) {
                group.add(condition);
            }
        }
        if (group.isEmpty()) {
            return;
        }
        TreeItem groupItem = new TreeItem(conditionTree, SWT.NONE);
        groupItem.setText(0, label);
        for (WhereCondition condition : group) {
            TreeItem item = new TreeItem(groupItem, SWT.NONE);
            item.setData(condition);
            refreshItem(item);
        }
        groupItem.setExpanded(true);
    }

    private void createDetailArea(@NotNull Composite parent) {
        Composite detail = UIUtils.createComposite(parent, 2);
        detail.setLayoutData(new GridData(GridData.FILL_BOTH));

        detailHeader = UIUtils.createLabel(detail, "");
        detailHeader.setLayoutData(spanBoth());

        UIUtils.createControlLabel(detail, "Deger");
        valueText = new Text(detail, SWT.BORDER);
        valueText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        valueText.addModifyListener(e -> onValueTyped());

        UIUtils.createControlLabel(detail, "Ara");
        filterText = new Text(detail, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filterText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        filterText.setMessage("Yazarak filtrele...");
        filterText.addModifyListener(e -> scheduleFilter());

        valueTable = new Table(detail, SWT.BORDER | SWT.FULL_SELECTION | SWT.SINGLE | SWT.V_SCROLL);
        GridData tableData = new GridData(GridData.FILL_BOTH);
        tableData.horizontalSpan = 2;
        tableData.heightHint = 160;
        valueTable.setLayoutData(tableData);
        new TableColumn(valueTable, SWT.LEFT);
        new TableColumn(valueTable, SWT.LEFT);
        valueTable.addListener(SWT.Selection, e -> applySelectedRow());
        valueTable.addListener(SWT.DefaultSelection, e -> applySelectedRow());
    }

    @NotNull
    private GridData spanBoth() {
        GridData layoutData = new GridData(GridData.FILL_HORIZONTAL);
        layoutData.horizontalSpan = 2;
        return layoutData;
    }

    // ---------------------------------------------------------------- selection / values

    private void selectFirstEditable() {
        // Start on the first unfilled placeholder, since that is what blocks execution
        TreeItem target = null;
        for (TreeItem group : conditionTree.getItems()) {
            for (TreeItem item : group.getItems()) {
                if (target == null) {
                    target = item;
                }
                if (item.getData() instanceof WhereCondition condition && condition.isPlaceholder()) {
                    target = item;
                    break;
                }
            }
            if (target != null && target.getData() instanceof WhereCondition c && c.isPlaceholder()) {
                break;
            }
        }
        if (target != null) {
            conditionTree.setSelection(target);
            onConditionSelected();
        }
    }

    private void onConditionSelected() {
        TreeItem[] selection = conditionTree.getSelection();
        if (selection.length == 0 || !(selection[0].getData() instanceof WhereCondition condition)) {
            return;
        }
        current = condition;
        detailHeader.setText(condition.getDisplayColumn()
            + (condition.isInWhereClause() ? "  (WHERE)" : "  (JOIN)"));
        updatingValueText = true;
        valueText.setText(values.getOrDefault(condition, ""));
        updatingValueText = false;
        filterText.setText("");

        List<FkRow> cached = rowCache.get(condition);
        if (cached != null) {
            currentRows = cached;
            populateValues(cached);
        } else {
            valueTable.removeAll();
            loadValues(condition, "");
        }
    }

    private void onValueTyped() {
        if (!updatingValueText && current != null) {
            values.put(current, valueText.getText());
            refreshCurrentItem();
        }
    }

    private void applySelectedRow() {
        int index = valueTable.getSelectionIndex();
        if (current == null || index < 0 || index >= currentRows.size()) {
            return;
        }
        String literal = currentRows.get(index).getLiteral();
        values.put(current, literal);
        updatingValueText = true;
        valueText.setText(literal);
        updatingValueText = false;
        refreshCurrentItem();
    }

    private void refreshCurrentItem() {
        for (TreeItem group : conditionTree.getItems()) {
            for (TreeItem item : group.getItems()) {
                if (item.getData() == current) {
                    refreshItem(item);
                    return;
                }
            }
        }
    }

    private void refreshItem(@NotNull TreeItem item) {
        WhereCondition condition = (WhereCondition) item.getData();
        String value = values.getOrDefault(condition, "");
        item.setText(0, condition.getDisplayColumn());
        item.setText(1, value.isEmpty() ? EMPTY_VALUE_HINT : value);
    }

    // ---------------------------------------------------------------- enumeration

    private void scheduleFilter() {
        Display display = filterText.getDisplay();
        display.timerExec(-1, filterRunnable);
        display.timerExec(FILTER_DELAY_MS, filterRunnable);
    }

    private void runFilter() {
        if (current != null && !filterText.isDisposed()) {
            loadValues(current, filterText.getText());
        }
    }

    /**
     * Resolves the condition's column to a real table column and lists its values in the
     * background. A column that cannot be resolved (no connection, computed expression, ...)
     * simply yields an empty list - the user can still type a value by hand.
     */
    private void loadValues(@NotNull WhereCondition condition, @NotNull String filter) {
        if (context == null) {
            return;
        }
        final int seq = ++filterSeq;
        FkColumnRef ref = new FkColumnRef(
            condition.getColumnName(),
            condition.getQualifier(),
            fromTables,
            FkColumnRef.Mode.EQUALS,
            condition.getValueStart(),
            condition.getValueEnd(),
            filter,
            false);
        AbstractJob job = new AbstractJob("List values of " + condition.getDisplayColumn()) {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                List<FkRow> rows = List.of();
                try {
                    InlineFkService.ResolvedColumn target = InlineFkService.resolveTarget(monitor, context, ref);
                    if (target != null) {
                        rows = InlineFkService.enumerate(
                            monitor, context, target.getEntity(), target.getColumn(),
                            filter, InlineFkService.DEFAULT_MAX_RESULTS);
                    }
                } catch (Throwable e) {
                    log.debug("Cannot list values for " + condition.getDisplayColumn(), e);
                }
                final List<FkRow> result = rows;
                UIUtils.asyncExec(() -> {
                    if (valueTable.isDisposed() || seq != filterSeq || current != condition) {
                        return; // superseded by a newer selection or keystroke
                    }
                    rowCache.put(condition, result);
                    currentRows = result;
                    populateValues(result);
                });
                return Status.OK_STATUS;
            }
        };
        job.setSystem(true);
        job.setUser(false);
        job.schedule();
    }

    private void populateValues(@NotNull List<FkRow> rows) {
        valueTable.removeAll();
        for (FkRow row : rows) {
            TableItem item = new TableItem(valueTable, SWT.NONE);
            item.setText(0, row.getIdText());
            item.setText(1, row.getLabel());
        }
        valueTable.getColumn(0).pack();
        valueTable.getColumn(1).pack();
    }

    // ---------------------------------------------------------------- result

    @Override
    protected void createButtonsForButtonBar(@NotNull Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Doldur", true);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    /**
     * Writes the chosen values back into the document.
     * <p>
     * Replacements run from the end of the document backwards so that each edit cannot shift the
     * offsets of the ones still to be applied.
     *
     * @return number of conditions actually rewritten
     */
    public int applyTo(@NotNull IDocument document) {
        List<WhereCondition> changed = new ArrayList<>();
        for (WhereCondition condition : conditions) {
            String value = values.getOrDefault(condition, "");
            if (!value.isEmpty() && !value.equals(condition.getValueText())) {
                changed.add(condition);
            }
        }
        changed.sort(Comparator.comparingInt(WhereCondition::getValueStart).reversed());

        int applied = 0;
        for (WhereCondition condition : changed) {
            int start = condition.getValueStart();
            int length = condition.getValueEnd() - start;
            if (start < 0 || start + length > document.getLength()) {
                continue;
            }
            try {
                document.replace(start, length, values.get(condition));
                applied++;
            } catch (BadLocationException e) {
                log.debug("Cannot write value for " + condition.getDisplayColumn(), e);
            }
        }
        return applied;
    }

    @Override
    protected Control createContents(@NotNull Composite parent) {
        Control contents = super.createContents(parent);
        UIUtils.centerShell(getParentShell(), getShell());
        return contents;
    }
}
