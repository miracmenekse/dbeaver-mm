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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDAttributeValue;
import org.jkiss.dbeaver.model.data.DBDContent;
import org.jkiss.dbeaver.model.data.resultset.DBDDataStatementInfo;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSAttributeBase;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.runtime.DBeaverNotifications;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceSQL;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * dbeaver-mm K76: collects executed grid changes during Save and writes a local undo script.
 * <p>
 * One instance per {@code ResultSetPersister}; no static state.
 */
public class UndoCapture {

    private static final Log log = Log.getLog(UndoCapture.class);

    private static final int PRUNE_DAYS = 30;

    private final Map<DBSEntity, TableChanges> tables = new LinkedHashMap<>();
    private final Map<String, Integer> cascadeDeletes = new LinkedHashMap<>();

    private DBSEntity primaryEntity;
    private boolean flushed;

    // dbeaver-mm K76: capture a deleted row before its values are released
    public void deleted(@NotNull DBDDataStatementInfo stat, @NotNull ResultSetRow row, @NotNull ResultSetViewer viewer) {
        if (!stat.isExecuted()) {
            return;
        }
        DBSEntity entity = stat.getEntity();
        if (!isPrimary(entity, viewer)) {
            cascadeDeletes.merge(tableName(entity), 1, Integer::sum);
            return;
        }
        primaryEntity = entity;

        DBPDataSource dataSource = viewer.getDataSource();
        TableChanges table = tableChanges(entity);
        List<UndoScript.Column> values = new ArrayList<>();
        for (DBDAttributeBinding binding : viewer.getModel().getAttributes()) {
            if (binding.getParentObject() != null || !belongsTo(binding, entity)) {
                continue;
            }
            Object[] rowValues = row.getValues();
            int ordinal = binding.getOrdinalPosition();
            if (ordinal < 0 || ordinal >= rowValues.length) {
                continue;
            }
            values.add(column(dataSource, binding, rowValues[ordinal], table.warnings));
        }
        if (values.isEmpty()) {
            return;
        }
        table.changes.add(new UndoScript.Change(UndoScript.Kind.DELETED, values, null, null));
        table.deleted++;
    }

    // dbeaver-mm K76: capture old and new values before row.clearChanges() releases them
    public void updated(@NotNull DBDDataStatementInfo stat, @NotNull ResultSetRow row, @NotNull ResultSetViewer viewer) {
        if (!stat.isExecuted()) {
            return;
        }
        DBSEntity entity = stat.getEntity();
        DBPDataSource dataSource = viewer.getDataSource();
        TableChanges table = tableChanges(entity);
        List<UndoScript.Column> oldValues = new ArrayList<>();
        List<UndoScript.Column> currentValues = new ArrayList<>();
        for (Map.Entry<DBDAttributeBinding, Object> change : row.getChanges()) {
            DBDAttributeBinding binding = change.getKey();
            if (binding.getParentObject() != null || !belongsTo(binding, entity)) {
                continue;
            }
            oldValues.add(column(dataSource, binding, change.getValue(), table.warnings));
            Object[] rowValues = row.getValues();
            int ordinal = binding.getOrdinalPosition();
            Object current = ordinal >= 0 && ordinal < rowValues.length ? rowValues[ordinal] : null;
            currentValues.add(column(dataSource, binding, current, table.warnings));
        }
        if (oldValues.isEmpty()) {
            return;
        }
        List<UndoScript.Column> keys = keys(stat, row, dataSource, table.warnings, false);
        table.changes.add(new UndoScript.Change(UndoScript.Kind.UPDATED, oldValues, currentValues, keys));
        table.updated++;
    }

    // dbeaver-mm K76: capture the inserted row key after generated keys were reflected into the row
    public void inserted(@NotNull DBDDataStatementInfo stat, @NotNull ResultSetRow row, @NotNull ResultSetViewer viewer) {
        if (!stat.isExecuted()) {
            return;
        }
        DBPDataSource dataSource = viewer.getDataSource();
        TableChanges table = tableChanges(stat.getEntity());
        List<UndoScript.Column> keys = keys(stat, row, dataSource, table.warnings, true);
        table.changes.add(new UndoScript.Change(UndoScript.Kind.INSERTED, null, null, keys));
        table.inserted++;
    }

    // dbeaver-mm K76: build and write scripts in the background, then offer to open them
    public void flush(@NotNull ResultSetViewer viewer) {
        if (flushed || tables.isEmpty()) {
            return;
        }
        flushed = true;

        DBPDataSource dataSource = viewer.getDataSource();
        DBPDataSourceContainer container = dataSource == null ? null : dataSource.getContainer();
        String connectionName = container == null ? "" : container.getName();
        String connectionId = container == null ? "" : container.getId();
        String time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString();

        if (primaryEntity != null) {
            TableChanges primary = tables.get(primaryEntity);
            if (primary != null) {
                for (Map.Entry<String, Integer> entry : cascadeDeletes.entrySet()) {
                    primary.warnings.add(entry.getValue() + " cascade-deleted row(s) in "
                        + entry.getKey() + " are not in this script");
                }
            }
        }

        List<TableSnapshot> snapshots = new ArrayList<>();
        for (TableChanges table : tables.values()) {
            snapshots.add(new TableSnapshot(
                table.entity,
                tableName(table.entity),
                new ArrayList<>(table.changes),
                new ArrayList<>(table.warnings),
                table.deleted,
                table.updated,
                table.inserted));
        }
        tables.clear();
        cascadeDeletes.clear();

        try {
            new UndoWriteJob(dataSource, container, connectionName, connectionId, time, snapshots).schedule();
        } catch (Exception e) {
            log.debug("Can't schedule undo script job", e);
        }
    }

    private boolean isPrimary(@NotNull DBSEntity entity, @NotNull ResultSetViewer viewer) {
        DBSEntity primary = viewer.getModel().getSingleSource();
        if (primary == null && viewer.getModel().getDefaultRowIdentifier() != null) {
            primary = viewer.getModel().getDefaultRowIdentifier().getEntity();
        }
        return primary == null || primary.equals(entity);
    }

    private static boolean belongsTo(@NotNull DBDAttributeBinding binding, @NotNull DBSEntity entity) {
        DBSEntityAttribute attribute = binding.getEntityAttribute();
        return attribute != null && attribute.getParentObject() != null && attribute.getParentObject().equals(entity);
    }

    private TableChanges tableChanges(@NotNull DBSEntity entity) {
        return tables.computeIfAbsent(entity, TableChanges::new);
    }

    private static List<UndoScript.Column> keys(
        @NotNull DBDDataStatementInfo stat,
        @NotNull ResultSetRow row,
        DBPDataSource dataSource,
        @NotNull Set<String> warnings,
        boolean useGeneratedRowValues
    ) {
        List<UndoScript.Column> keys = new ArrayList<>();
        for (DBDAttributeValue key : stat.getKeyAttributes()) {
            DBSAttributeBase attribute = key.getAttribute();
            Object value = key.getValue();
            if (useGeneratedRowValues && DBUtils.isNullValue(value)
                && attribute instanceof DBDAttributeBinding binding && binding.isAutoGenerated()) {
                Object[] rowValues = row.getValues();
                int ordinal = binding.getOrdinalPosition();
                if (ordinal >= 0 && ordinal < rowValues.length) {
                    value = rowValues[ordinal];
                }
            }
            keys.add(column(dataSource, attribute, value, warnings));
        }
        return keys;
    }

    private static UndoScript.Column column(
        DBPDataSource dataSource,
        @NotNull DBSAttributeBase attribute,
        Object value,
        @NotNull Set<String> warnings
    ) {
        String name = DBUtils.getQuotedIdentifier(attribute);
        String literal = null;
        if (dataSource == null || value instanceof DBDContent) {
            warnings.add(name + ": value not captured");
        } else {
            try {
                literal = SQLUtils.convertValueToSQL(dataSource, attribute, value);
            } catch (Exception e) {
                warnings.add(name + ": value not captured");
            }
        }
        return new UndoScript.Column(name, literal);
    }

    @NotNull
    private static String tableName(@NotNull DBSEntity entity) {
        return DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML);
    }

    private static final class TableChanges {
        private final DBSEntity entity;
        private final List<UndoScript.Change> changes = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private int deleted;
        private int updated;
        private int inserted;

        private TableChanges(@NotNull DBSEntity entity) {
            this.entity = entity;
        }
    }

    private record TableSnapshot(
        DBSEntity entity,
        String table,
        List<UndoScript.Change> changes,
        List<String> warnings,
        int deleted,
        int updated,
        int inserted) {
    }

    private static final class UndoWriteJob extends AbstractJob {
        private final DBPDataSource dataSource;
        private final DBPDataSourceContainer container;
        private final String connectionName;
        private final String connectionId;
        private final String time;
        private final List<TableSnapshot> snapshots;

        private UndoWriteJob(
            DBPDataSource dataSource,
            DBPDataSourceContainer container,
            String connectionName,
            String connectionId,
            String time,
            List<TableSnapshot> snapshots
        ) {
            super("Write undo script");
            this.dataSource = dataSource;
            this.container = container;
            this.connectionName = connectionName;
            this.connectionId = connectionId;
            this.time = time;
            this.snapshots = snapshots;
        }

        @NotNull
        @Override
        protected IStatus run(@NotNull DBRProgressMonitor monitor) {
            try {
                if (!UndoScript.isEnabled(UndoScript.settingsFile())) {
                    return Status.OK_STATUS;
                }
                Instant now = Instant.now();
                for (TableSnapshot snapshot : snapshots) {
                    write(snapshot);
                }
                UndoScript.prune(UndoScript.folder(), now, PRUNE_DAYS);
            } catch (Exception e) {
                log.debug("Can't write undo script", e);
            }
            return Status.OK_STATUS;
        }

        private void write(@NotNull TableSnapshot snapshot) {
            UndoScript.Header header = new UndoScript.Header(
                time,
                connectionId,
                connectionName,
                snapshot.table(),
                snapshot.warnings());
            String script = UndoScript.build(header, snapshot.changes());
            try {
                UndoScript.write(UndoScript.folder(), header, script);
            } catch (Exception e) {
                log.debug("Can't write undo script for " + snapshot.table(), e);
                return;
            }
            String text = snapshot.deleted() + " deleted, " + snapshot.updated() + " updated, "
                + snapshot.inserted() + " inserted in " + snapshot.table()
                + " · click to open the undo script";
            UIUtils.asyncExec(() -> {
                if (dataSource == null) {
                    return;
                }
                DBeaverNotifications.showNotification(
                    dataSource,
                    "dbeaver-mm-undo",
                    text,
                    DBPMessageType.INFORMATION,
                    () -> UIUtils.asyncExec(() -> openScript(snapshot, script)));
            });
        }

        private void openScript(@NotNull TableSnapshot snapshot, @NotNull String script) {
            UIServiceSQL serviceSQL = DBWorkbench.getService(UIServiceSQL.class);
            if (serviceSQL != null && container != null) {
                serviceSQL.openSQLConsole(container, null, snapshot.entity(), "Undo " + snapshot.table(), script);
            }
        }
    }
}
