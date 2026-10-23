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
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
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
import org.jkiss.dbeaver.model.virtual.DBVEntity;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.ui.UIUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * dbeaver-mm K71: the "..." button of a {@code row_id} column header. Lists the target tables the
 * {@code data_tp} values of this result point to; for the selected one the user picks the description
 * columns of its label, with a live preview of the first row.
 * dbeaver-mm K74: the connection and schema come only from the tenant's data_tp mapping and are shown
 * read only; the mapping itself is built in Plus Settings > data_tp mapping...
 * <p>
 * Every metadata read and query runs in a background job; results come back with
 * {@link UIUtils#asyncExec}. Save writes the description columns (virtual model), then reloads the
 * labels and calls {@code afterSave} on the UI thread.
 */
public class DataTpDialog extends Dialog {

    private static final Log log = Log.getLog(DataTpDialog.class);

    /** dbeaver-mm K71: what the dialog knows about one target table; touched on the UI thread only */
    private static final class TableState {
        final String table;
        // dbeaver-mm K74: null when the data_tp value is not in the tenant mapping
        final DataTpStore.Entry entry;
        final String where;
        int rows;
        Object sampleRowId;
        boolean loaded;
        boolean columnsChanged;
        int version;
        DBSEntity entity;
        List<DBSEntityAttribute> columns = List.of();
        List<DBSEntityAttribute> checked = new ArrayList<>();
        String preview = "";
        String message = "";

        TableState(String table, DataTpStore.Entry entry, String where) {
            this.table = table;
            this.entry = entry;
            this.where = where;
        }
    }

    /** dbeaver-mm K71: what the background load produced, applied on the UI thread */
    private record Loaded(DBSEntity entity, List<DBSEntityAttribute> columns,
                          List<DBSEntityAttribute> checked, String preview, String message) {
    }

    private final DBDAttributeBinding dataTp;
    private final DBDAttributeBinding rowId;
    private final List<? extends DBDValueRow> rows;
    private Runnable afterSave;
    private final List<TableState> states = new ArrayList<>();

    private org.eclipse.swt.widgets.List tableList;
    private Label connectionLabel;
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
        // dbeaver-mm K74: cache and registry reads only (no query, no file), so this is fine on the UI thread
        String tenant = DataTpLabels.getTenant(dataTp);
        Map<Object, TableState> byTarget = new LinkedHashMap<>();
        int tpIndex = dataTp.getOrdinalPosition();
        int idIndex = rowId.getOrdinalPosition();
        for (DBDValueRow row : rows) {
            Object[] values = row.getValues();
            if (tpIndex < 0 || tpIndex >= values.length || DBUtils.isNullValue(values[tpIndex])) {
                continue;
            }
            DataTpStore.Entry entry = DataTpLabels.getEntry(dataTp, values[tpIndex]);
            String typeKey = DataTpStore.typeKey(values[tpIndex]);
            TableState state = byTarget.computeIfAbsent(entry != null ? entry : "data_tp " + typeKey,
                k -> entry == null
                    ? new TableState("data_tp " + typeKey, null,
                        "Not in the data_tp mapping of " + tenant + " — Plus Settings › data_tp mapping...")
                    : new TableState(entry.table(), entry, "Connection: " + connectionName(dataTp, entry)
                        + (entry.schema().isEmpty() ? "" : " · schema " + entry.schema()) + " (data_tp mapping)"));
            state.rows++;
            if (state.sampleRowId == null && idIndex >= 0 && idIndex < values.length && !DBUtils.isNullValue(values[idIndex])) {
                state.sampleRowId = values[idIndex];
            }
        }
        // dbeaver-mm K74: values missing from the mapping first, they need the user's attention
        byTarget.values().stream().filter(s -> s.entry == null).forEach(states::add);
        byTarget.values().stream().filter(s -> s.entry != null).forEach(states::add);
    }

    @NotNull
    private static String connectionName(@NotNull DBDAttributeBinding binding, @NotNull DataTpStore.Entry entry) {
        DBPDataSourceContainer container = binding.getDataSource() == null ? null
            : binding.getDataSource().getContainer().getRegistry().getDataSource(entry.connectionId());
        return container == null ? entry.connectionId() : container.getName();
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
            tableList.add((state.entry == null ? "⚠ " : "") + state.table + " (" + state.rows + ")");
        }

        Composite right = new Composite(area, SWT.NONE);
        right.setLayout(new GridLayout(1, false));
        GridData rightData = new GridData(SWT.FILL, SWT.FILL, true, true);
        rightData.widthHint = 380;
        right.setLayoutData(rightData);

        // dbeaver-mm K74: read only, the mapping is edited in Plus Settings > data_tp mapping...
        connectionLabel = new Label(right, SWT.WRAP);
        connectionLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        messageLabel = new Label(right, SWT.WRAP);
        messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Label columnsLabel = new Label(right, SWT.NONE);
        columnsLabel.setText("Columns shown:");
        columnsLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        columnTable = new Table(right, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL);
        columnTable.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));

        previewLabel = new Label(right, SWT.WRAP);
        previewLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        tableList.addListener(SWT.Selection, e -> {
            int index = tableList.getSelectionIndex();
            show(index < 0 ? null : states.get(index));
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
            load(current, false);
        });

        if (states.isEmpty()) {
            columnTable.setEnabled(false);
            messageLabel.setText("No data_tp values in these rows.");
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
        if (state != null && state.entry != null && !state.loaded) {
            connectionLabel.setText(state.where);
            columnTable.removeAll();
            columnTable.setEnabled(false);
            messageLabel.setText("Loading...");
            previewLabel.setText("");
            load(state, true);
            return;
        }
        render();
    }

    private void render() {
        TableState state = current;
        if (state == null || connectionLabel.isDisposed()) {
            return;
        }
        connectionLabel.setText(state.where);
        messageLabel.setText(state.message);
        columnTable.removeAll();
        for (DBSEntityAttribute column : state.columns) {
            TableItem item = new TableItem(columnTable, SWT.NONE);
            item.setText(column.getName());
            item.setData(column);
            item.setChecked(state.checked.contains(column));
        }
        // dbeaver-mm K74: not mapped, closed or missing table: nothing to pick
        columnTable.setEnabled(state.entity != null);
        previewLabel.setText(state.preview.isEmpty() ? "" : "Preview: " + state.preview);
        messageLabel.getParent().layout(true, true);
    }

    /**
     * dbeaver-mm K71: reads the columns and the preview of {@code state} in a job.
     * Inputs are copied here on the UI thread; a result older than the state's last request is dropped.
     */
    private void load(@NotNull TableState state, boolean columnsToo) {
        int version = ++state.version;
        DBSEntity entity = state.entity;
        List<DBSEntityAttribute> columns = state.columns;
        List<DBSEntityAttribute> checked = List.copyOf(state.checked);
        Object sample = state.sampleRowId;
        DBPDataSourceContainer grid = dataTp.getDataSource() == null ? null : dataTp.getDataSource().getContainer();
        new AbstractJob("Load data_tp target " + state.table) {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Loaded result = read(monitor, grid, state.entry, entity, columnsToo, columns, checked, sample);
                UIUtils.asyncExec(() -> {
                    if (version != state.version || tableList == null || tableList.isDisposed()) {
                        return;
                    }
                    state.loaded = true;
                    state.entity = result.entity();
                    state.columns = result.columns();
                    state.checked = new ArrayList<>(result.checked());
                    state.preview = result.preview();
                    state.message = result.message();
                    if (state != current) {
                        return;
                    }
                    if (columnsToo) {
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
        @Nullable DBPDataSourceContainer grid,
        @NotNull DataTpStore.Entry target,
        @Nullable DBSEntity entity,
        boolean columnsToo,
        @NotNull List<DBSEntityAttribute> columns,
        @NotNull List<DBSEntityAttribute> checked,
        @Nullable Object sample
    ) {
        try {
            if (entity == null) {
                // dbeaver-mm K74: straight to the mapped connection, schema and table; a closed connection is not opened
                DBPDataSourceContainer container = grid == null ? null : grid.getRegistry().getDataSource(target.connectionId());
                if (container == null) {
                    return new Loaded(null, List.of(), List.of(), "",
                        "Connection " + target.connectionId() + " of the data_tp mapping is not in this project.");
                }
                if (!container.isConnected()) {
                    return new Loaded(null, List.of(), List.of(), "", "Connection " + container.getName() + " is closed.");
                }
                entity = DataTpLabels.findMappedTable(monitor, grid, target);
                if (entity == null) {
                    return new Loaded(null, List.of(), List.of(), "",
                        "Table " + target.table() + " is not in " + container.getName()
                            + (target.schema().isEmpty() ? "" : " · schema " + target.schema())
                            + ". Build the data_tp mapping again.");
                }
            }
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
            return new Loaded(entity, columns, checked, preview, "");
        } catch (Throwable e) {
            log.debug("Can't load data_tp target " + target.table(), e);
            return new Loaded(entity, columns, checked, "", "Can't read " + target.table() + ": " + e.getMessage());
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

    @Override
    protected void okPressed() {
        // dbeaver-mm K71: only what the user changed on screen is saved
        // dbeaver-mm K74: description columns only, the connection mapping belongs to Plus Settings
        Map<DBSEntity, String> newColumns = new LinkedHashMap<>();
        for (TableState state : states) {
            if (state.loaded && state.entity != null && state.columnsChanged && !state.checked.isEmpty()) {
                StringJoiner names = new StringJoiner(",");
                state.checked.forEach(c -> names.add(DBUtils.getQuotedIdentifier(c)));
                newColumns.put(state.entity, names.toString());
            }
        }
        super.okPressed();
        if (newColumns.isEmpty()) {
            return;
        }
        new AbstractJob("Save data_tp labels") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                save(monitor, newColumns);
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /** dbeaver-mm K71: background part of Save: virtual model, then the labels again */
    private void save(@NotNull DBRProgressMonitor monitor, @NotNull Map<DBSEntity, String> newColumns) {
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
