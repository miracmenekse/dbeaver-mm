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
package org.jkiss.dbeaver.ui.data.hints;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ModelPreferences;
import org.jkiss.dbeaver.model.DBPDataKind;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.virtual.DBVEntity;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.ui.UIUtils;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * dbeaver-mm K71: the "..." button of a {@code row_id} column header. Lists the target tables the
 * {@code data_tp} values of this result point to; for the selected one the user picks the connection
 * that holds it and the description columns of its label, with a live preview of the first row.
 * <p>
 * Every metadata read, query and file write runs in a background job; results come back with
 * {@link UIUtils#asyncExec}. Save writes the connection mapping ({@link DataTpStore#save}) and the
 * description columns (virtual model), then reloads the labels and calls {@code afterSave} on the UI thread.
 */
public class DataTpDialog extends Dialog {

    private static final Log log = Log.getLog(DataTpDialog.class);

    /** dbeaver-mm K71: what the dialog knows about one target table; touched on the UI thread only */
    private static final class TableState {
        final String table;
        final boolean unresolved;
        int rows;
        Object sampleRowId;
        boolean loaded;
        boolean mappingChanged;
        boolean columnsChanged;
        int version;
        List<DBSEntity> candidates = List.of();
        int chosen = -1;
        List<DBSEntityAttribute> columns = List.of();
        List<DBSEntityAttribute> checked = new ArrayList<>();
        String preview = "";
        String message = "";

        TableState(String table, boolean unresolved) {
            this.table = table;
            this.unresolved = unresolved;
        }
    }

    /** dbeaver-mm K71: what a background load produced, applied on the UI thread */
    private record Loaded(List<DBSEntity> candidates, int chosen, List<DBSEntityAttribute> columns,
                          List<DBSEntityAttribute> checked, String preview, String message) {
    }

    private final DBDAttributeBinding dataTp;
    private final DBDAttributeBinding rowId;
    private final List<? extends DBDValueRow> rows;
    private final Runnable afterSave;
    private final List<TableState> states = new ArrayList<>();

    private org.eclipse.swt.widgets.List tableList;
    private Combo connectionCombo;
    private Label messageLabel;
    private Table columnTable;
    private Label previewLabel;
    private TableState current;

    public DataTpDialog(
        @NotNull Shell shell,
        @NotNull DBDAttributeBinding dataTp,
        @NotNull DBDAttributeBinding rowId,
        @NotNull List<? extends DBDValueRow> rows,
        @NotNull Runnable afterSave
    ) {
        super(shell);
        this.dataTp = dataTp;
        this.rowId = rowId;
        this.rows = rows;
        this.afterSave = afterSave;
        // dbeaver-mm K71: cache reads only (no query), so building the list on the UI thread is fine
        Map<String, TableState> byTable = new LinkedHashMap<>();
        int tpIndex = dataTp.getOrdinalPosition();
        int idIndex = rowId.getOrdinalPosition();
        for (DBDValueRow row : rows) {
            Object[] values = row.getValues();
            if (tpIndex < 0 || tpIndex >= values.length) {
                continue;
            }
            String table = DataTpLabels.getTypeTable(dataTp, values[tpIndex]);
            if (table == null) {
                continue;
            }
            TableState state = byTable.computeIfAbsent(DataTpStore.tableKey(table),
                k -> new TableState(table, DataTpLabels.unresolved().contains(k)));
            state.rows++;
            if (state.sampleRowId == null && idIndex >= 0 && idIndex < values.length && !DBUtils.isNullValue(values[idIndex])) {
                state.sampleRowId = values[idIndex];
            }
        }
        // dbeaver-mm K71: tables still missing a connection come first
        byTable.values().stream().filter(s -> s.unresolved).forEach(states::add);
        byTable.values().stream().filter(s -> !s.unresolved).forEach(states::add);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("data_tp / row_id labels");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        tableList = new org.eclipse.swt.widgets.List(area, SWT.SINGLE | SWT.BORDER | SWT.V_SCROLL);
        GridData listData = new GridData(SWT.FILL, SWT.FILL, false, true);
        listData.widthHint = 220;
        listData.heightHint = 260;
        tableList.setLayoutData(listData);
        for (TableState state : states) {
            tableList.add((state.unresolved ? "⚠ " : "") + state.table + " (" + state.rows + ")"
                + (state.unresolved ? "  connection?" : ""));
        }

        Composite right = new Composite(area, SWT.NONE);
        right.setLayout(new GridLayout(2, false));
        GridData rightData = new GridData(SWT.FILL, SWT.FILL, true, true);
        rightData.widthHint = 380;
        right.setLayoutData(rightData);

        new Label(right, SWT.NONE).setText("Connection:");
        connectionCombo = new Combo(right, SWT.DROP_DOWN | SWT.READ_ONLY);
        connectionCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        messageLabel = new Label(right, SWT.WRAP);
        messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        Label columnsLabel = new Label(right, SWT.NONE);
        columnsLabel.setText("Columns shown:");
        columnsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        columnTable = new Table(right, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        columnTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));

        previewLabel = new Label(right, SWT.WRAP);
        previewLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        tableList.addListener(SWT.Selection, e -> {
            int index = tableList.getSelectionIndex();
            show(index < 0 ? null : states.get(index));
        });
        connectionCombo.addListener(SWT.Selection, e -> {
            if (current != null && current.loaded && connectionCombo.getSelectionIndex() != current.chosen) {
                current.chosen = connectionCombo.getSelectionIndex();
                current.mappingChanged = true;
                current.checked = new ArrayList<>();
                load(current, false, true);
            }
        });
        columnTable.addListener(SWT.Selection, e -> {
            if (e.detail != SWT.CHECK || current == null || !current.loaded) {
                return;
            }
            List<DBSEntityAttribute> checked = new ArrayList<>();
            for (TableItem item : columnTable.getItems()) {
                if (item.getChecked()) {
                    checked.add((DBSEntityAttribute) item.getData());
                }
            }
            current.checked = checked;
            current.columnsChanged = true;
            load(current, false, false);
        });

        if (states.isEmpty()) {
            connectionCombo.setEnabled(false);
            messageLabel.setText("No data_tp target tables are known for these rows yet.");
        } else {
            tableList.select(0);
            show(states.get(0));
        }
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Save", true);
    }

    /** dbeaver-mm K71: shows {@code state}, loading it in the background on first view */
    private void show(@Nullable TableState state) {
        current = state;
        if (state != null && !state.loaded) {
            connectionCombo.removeAll();
            columnTable.removeAll();
            messageLabel.setText("Loading...");
            previewLabel.setText("");
            load(state, true, true);
            return;
        }
        render();
    }

    private void render() {
        TableState state = current;
        if (state == null || connectionCombo.isDisposed()) {
            return;
        }
        connectionCombo.removeAll();
        for (DBSEntity candidate : state.candidates) {
            DBSObject parent = candidate.getParentObject();
            connectionCombo.add(candidate.getDataSource().getContainer().getName()
                + (parent == null || parent instanceof DBPDataSource ? "" : " / " + parent.getName()));
        }
        if (state.chosen >= 0) {
            connectionCombo.select(state.chosen);
        }
        connectionCombo.setEnabled(!state.candidates.isEmpty());
        messageLabel.setText(state.message);
        columnTable.removeAll();
        for (DBSEntityAttribute column : state.columns) {
            TableItem item = new TableItem(columnTable, SWT.NONE);
            item.setText(column.getName());
            item.setData(column);
            item.setChecked(state.checked.contains(column));
        }
        previewLabel.setText(state.preview.isEmpty() ? "" : "Preview: " + state.preview);
        messageLabel.getParent().layout(true, true);
    }

    /**
     * dbeaver-mm K71: reads candidates (connections), columns and the preview of {@code state} in a job.
     * Inputs are copied here on the UI thread; a result older than the state's last request is dropped.
     */
    private void load(@NotNull TableState state, boolean candidatesToo, boolean columnsToo) {
        int version = ++state.version;
        List<DBSEntity> candidates = state.candidates;
        int chosen = state.chosen;
        List<DBSEntityAttribute> columns = state.columns;
        List<DBSEntityAttribute> checked = List.copyOf(state.checked);
        Object sample = state.sampleRowId;
        new AbstractJob("Load data_tp target " + state.table) {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Loaded result = read(monitor, state.table, candidatesToo, columnsToo, candidates, chosen, columns, checked, sample);
                UIUtils.asyncExec(() -> {
                    if (version != state.version || tableList == null || tableList.isDisposed()) {
                        return;
                    }
                    state.loaded = true;
                    state.candidates = result.candidates();
                    state.chosen = result.chosen();
                    state.columns = result.columns();
                    state.checked = new ArrayList<>(result.checked());
                    state.preview = result.preview();
                    state.message = result.message();
                    if (state != current) {
                        return;
                    }
                    if (candidatesToo || columnsToo) {
                        render();
                    } else {
                        // dbeaver-mm K71: preview-only load, keep the column list where it is
                        previewLabel.setText(state.preview.isEmpty() ? "" : "Preview: " + state.preview);
                        previewLabel.getParent().layout(true, true);
                    }
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /** dbeaver-mm K71: background part of {@link #load}; never throws, a failure becomes the message */
    @NotNull
    private static Loaded read(
        @NotNull DBRProgressMonitor monitor,
        @NotNull String table,
        boolean candidatesToo,
        boolean columnsToo,
        @NotNull List<DBSEntity> candidates,
        int chosen,
        @NotNull List<DBSEntityAttribute> columns,
        @NotNull List<DBSEntityAttribute> checked,
        @Nullable Object sample
    ) {
        String message = "";
        try {
            if (candidatesToo) {
                candidates = DataTpLabels.findTables(monitor, table);
                DBSEntity target = DataTpLabels.resolveTarget(monitor, table);
                if (target == null && candidates.size() > 1) {
                    // dbeaver-mm K71: ambiguous and not mapped: nothing preselected, the user must pick
                    return new Loaded(candidates, -1, List.of(), List.of(), "",
                        "Found in " + candidates.size() + " connections, pick one.");
                }
                chosen = candidates.isEmpty() ? -1 : 0;
                for (int i = 0; target != null && i < candidates.size(); i++) {
                    if (sameTable(candidates.get(i), target)) {
                        chosen = i;
                    }
                }
            }
            if (candidates.isEmpty()) {
                return new Loaded(candidates, -1, List.of(), List.of(), "",
                    "Table " + table + " is not in any open connection. Connect the database that holds it and open this dialog again.");
            }
            if (candidates.size() > 1) {
                message = "Found in " + candidates.size() + " connections, pick one.";
            }
            if (chosen < 0 || chosen >= candidates.size()) {
                chosen = 0;
            }
            DBSEntity entity = candidates.get(chosen);
            List<? extends DBSEntityAttribute> key = DBUtils.getBestTableIdentifier(monitor, entity);
            if (columnsToo) {
                Collection<? extends DBSEntityAttribute> attributes = entity.getAttributes(monitor);
                columns = attributes == null ? List.of() : List.copyOf(attributes);
                if (checked.isEmpty()) {
                    // dbeaver-mm K71: the saved description columns, else DBeaver's own pick
                    DBVEntity virtual = DBVUtils.getVirtualEntity(entity, false);
                    String names = virtual == null ? null : virtual.getDescriptionColumnNames();
                    if (names == null && !key.isEmpty()) {
                        names = DBVEntity.getDefaultDescriptionColumn(monitor, key.get(0));
                    }
                    checked = names == null ? List.of() : List.copyOf(DBVEntity.getDescriptionColumns(columns, names));
                }
            }
            String preview = key.isEmpty() || sample == null ? "" : preview(monitor, entity, key.get(0), checked, sample);
            return new Loaded(candidates, chosen, columns, checked, preview, message);
        } catch (Throwable e) {
            log.debug("Can't load data_tp target " + table, e);
            return new Loaded(candidates, chosen, columns, checked, "", "Can't read " + table + ": " + e.getMessage());
        }
    }

    /** dbeaver-mm K71: "20 | Ahmet Y. AKTIF" for the sample row, columns joined like DBeaver's dictionary labels */
    @NotNull
    private static String preview(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity entity,
        @NotNull DBSEntityAttribute key,
        @NotNull List<DBSEntityAttribute> columns,
        @NotNull Object sample
    ) throws Exception {
        if (columns.isEmpty()) {
            return sample + " | (no columns)";
        }
        DBCExecutionContext context = DBUtils.getDefaultContext(entity.getDataSource(), true);
        if (context == null) {
            return "";
        }
        // dbeaver-mm K71: the value is inlined as a literal (number checked, text quoted), DBCStatement has no binding
        Object keyValue = DataTpLabels.asKeyType(sample, key.getDataKind() == DBPDataKind.NUMERIC);
        String literal = keyValue instanceof Number
            ? new BigDecimal(keyValue.toString()).toPlainString()
            : SQLUtils.quoteString(entity, String.valueOf(keyValue));
        StringJoiner select = new StringJoiner(", ");
        columns.forEach(c -> select.add(DBUtils.getQuotedIdentifier(c)));
        String query = "SELECT " + select + " FROM " + DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML)
            + " WHERE " + DBUtils.getQuotedIdentifier(key) + " = " + literal;
        String divider = entity.getDataSource().getContainer().getPreferenceStore()
            .getString(ModelPreferences.DICTIONARY_COLUMN_DIVIDER);
        DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Preview data_tp label");
        try (session; DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, query, false, false, false)) {
            if (statement.executeStatement()) {
                try (DBCResultSet resultSet = statement.openResultSet()) {
                    if (resultSet != null && resultSet.nextRow()) {
                        StringJoiner label = new StringJoiner(divider == null ? " " : divider);
                        for (int i = 0; i < columns.size(); i++) {
                            Object value = resultSet.getAttributeValue(i);
                            if (!DBUtils.isNullValue(value)) {
                                label.add(String.valueOf(value));
                            }
                        }
                        return sample + " | " + label;
                    }
                }
            }
        }
        return sample + " | (row not found)";
    }

    private static boolean sameTable(@NotNull DBSEntity a, @NotNull DBSEntity b) {
        return a == b || (a.getDataSource() != null && b.getDataSource() != null
            && a.getDataSource().getContainer() == b.getDataSource().getContainer()
            && Objects.equals(DBUtils.getObjectFullName(a, DBPEvaluationContext.UI), DBUtils.getObjectFullName(b, DBPEvaluationContext.UI)));
    }

    @Override
    protected void okPressed() {
        // dbeaver-mm K71: only what the user changed or confirmed on screen is saved
        Map<String, DataTpStore.Target> newMappings = new HashMap<>();
        Map<DBSEntity, String> newColumns = new LinkedHashMap<>();
        for (TableState state : states) {
            if (!state.loaded || state.chosen < 0 || state.chosen >= state.candidates.size()) {
                continue;
            }
            DBSEntity entity = state.candidates.get(state.chosen);
            if (state.mappingChanged || state.unresolved) {
                DBSObject parent = entity.getParentObject();
                newMappings.put(DataTpStore.tableKey(state.table), new DataTpStore.Target(
                    entity.getDataSource().getContainer().getId(),
                    parent == null || parent instanceof DBPDataSource ? "" : parent.getName()));
            }
            if (state.columnsChanged && !state.checked.isEmpty()) {
                StringJoiner names = new StringJoiner(",");
                state.checked.forEach(c -> names.add(DBUtils.getQuotedIdentifier(c)));
                newColumns.put(entity, names.toString());
            }
        }
        super.okPressed();
        if (newMappings.isEmpty() && newColumns.isEmpty()) {
            return;
        }
        new AbstractJob("Save data_tp labels") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                save(monitor, newMappings, newColumns);
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /** dbeaver-mm K71: background part of Save: mapping file, virtual model, then the labels again */
    private void save(
        @NotNull DBRProgressMonitor monitor,
        @NotNull Map<String, DataTpStore.Target> newMappings,
        @NotNull Map<DBSEntity, String> newColumns
    ) {
        if (!newMappings.isEmpty()) {
            Path file = DataTpStore.defaultFile();
            // same lock as DataTpLabels.resolveTarget, which also writes this file
            synchronized (DataTpLabels.class) {
                try {
                    Map<String, DataTpStore.Target> all = new HashMap<>(DataTpStore.load(file));
                    all.putAll(newMappings);
                    DataTpStore.save(file, all);
                    // under the same lock, so no fetch job re-saves its stale mappings over this file
                    DataTpLabels.invalidate();
                } catch (Exception e) {
                    log.error("Can't save data_tp mappings", e);
                }
            }
        }
        for (Map.Entry<DBSEntity, String> entry : newColumns.entrySet()) {
            try {
                DBSEntity entity = entry.getKey();
                DBVEntity virtual = DBVUtils.getVirtualEntity(entity, true);
                if (virtual != null) {
                    virtual.setDescriptionColumnNames(entry.getValue());
                    virtual.persistConfiguration();
                }
            } catch (Exception e) {
                log.error("Can't save description columns of " + entry.getKey().getName(), e);
            }
        }
        DataTpLabels.invalidate();
        FkDictionaryLabels.invalidateLabels();
        // dbeaver-mm K71: the caches are empty now and painting never queries, so load this result's labels
        // again here (what cacheRequiredData does in the fetch job), then let the grid redraw
        try {
            DataTpLabels.prefetchTypes(monitor, dataTp);
            DataTpLabels.prefetchRows(monitor, dataTp, rowId, rows);
        } catch (Throwable e) {
            log.debug("data_tp labels reload failed", e);
        }
        UIUtils.asyncExec(afterSave);
    }
}
