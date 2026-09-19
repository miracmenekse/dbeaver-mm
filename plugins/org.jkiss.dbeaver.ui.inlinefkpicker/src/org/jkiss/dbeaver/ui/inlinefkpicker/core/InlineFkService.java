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
package org.jkiss.dbeaver.ui.inlinefkpicker.core;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.exec.DBCException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSDictionary;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.rdb.DBSCatalog;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.virtual.DBVEntity;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The single class that touches DBeaver metadata / query APIs for the inline FK picker.
 * Everything above it (analyzer, popup, triggers) stays free of DBeaver internals so that
 * upstream API changes only affect this file. Modelled after {@code DBeaverScriptsBridge}.
 */
public final class InlineFkService {

    private static final Log log = Log.getLog(InlineFkService.class);

    public static final int DEFAULT_MAX_RESULTS = 50;

    private InlineFkService() {
    }

    /**
     * Resolve a table by name (naming convention already applied) within the active schema/catalog
     * of the given execution context. Returns {@code null} when there is no live connection or the
     * table does not exist - callers use that to keep the widget from opening.
     */
    @Nullable
    public static DBSEntity resolveEntity(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBCExecutionContext context,
        @NotNull String tableName
    ) {
        if (context == null || !context.isConnected()) {
            return null;
        }
        try {
            DBSObjectContainer container = getActiveContainer(context);
            if (container == null) {
                return null;
            }
            return findEntity(monitor, container, tableName);
        } catch (DBException e) {
            log.debug("Failed to resolve FK target table '" + tableName + "'", e);
            return null;
        }
    }

    @Nullable
    private static DBSObjectContainer getActiveContainer(@NotNull DBCExecutionContext context) {
        DBCExecutionContextDefaults defaults = context.getContextDefaults();
        if (defaults != null) {
            DBSSchema schema = defaults.getDefaultSchema();
            if (schema instanceof DBSObjectContainer sc) {
                return sc;
            }
            DBSCatalog catalog = defaults.getDefaultCatalog();
            if (catalog instanceof DBSObjectContainer cc) {
                return cc;
            }
        }
        return DBUtils.getAdapter(DBSObjectContainer.class, context.getDataSource());
    }

    @Nullable
    private static DBSEntity findEntity(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        @NotNull String tableName
    ) throws DBException {
        // Fast path: exact child lookup.
        DBSObject child = container.getChild(monitor, tableName);
        if (child instanceof DBSEntity entity) {
            return entity;
        }
        // Case-insensitive scan of direct children.
        Collection<? extends DBSObject> children = container.getChildren(monitor);
        if (children != null) {
            DBSObject match = DBUtils.findObject(children, tableName, true);
            if (match instanceof DBSEntity entity) {
                return entity;
            }
        }
        return null;
    }

    /** Resolved (table, column) pair for the value being edited. */
    public static final class ResolvedColumn {
        private final DBSEntity entity;
        private final DBSEntityAttribute column;

        public ResolvedColumn(DBSEntity entity, DBSEntityAttribute column) {
            this.entity = entity;
            this.column = column;
        }

        public DBSEntity getEntity() {
            return entity;
        }

        public DBSEntityAttribute getColumn() {
            return column;
        }
    }

    /**
     * Resolve the real table and the compared column from the caret context. The column is matched
     * against the FROM/UPDATE table it belongs to (honouring an alias qualifier). Returns
     * {@code null} when there is no live connection or the column cannot be located in any candidate
     * table - callers use that to keep the widget from opening.
     */
    @Nullable
    public static ResolvedColumn resolveTarget(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBCExecutionContext context,
        @NotNull FkColumnRef ref
    ) {
        if (context == null || !context.isConnected()) {
            return null;
        }
        List<String> candidates = new ArrayList<>();
        String qualifier = ref.getQualifier();
        if (qualifier != null) {
            String byAlias = aliasToTable(ref.getFromTables(), qualifier);
            candidates.add(byAlias != null ? byAlias : qualifier);
        } else {
            for (FkColumnRef.TableRef t : ref.getFromTables()) {
                candidates.add(t.getName());
            }
        }
        for (String tableName : candidates) {
            DBSEntity entity = resolveEntity(monitor, context, tableName);
            if (entity == null) {
                continue;
            }
            DBSEntityAttribute column = findColumn(monitor, entity, ref.getColumnName());
            if (column != null) {
                return new ResolvedColumn(entity, column);
            }
        }
        return null;
    }

    @Nullable
    private static String aliasToTable(@NotNull List<FkColumnRef.TableRef> tables, @NotNull String qualifier) {
        for (FkColumnRef.TableRef t : tables) {
            if (t.getAlias() != null && t.getAlias().equalsIgnoreCase(qualifier)) {
                return t.getName();
            }
        }
        // The qualifier may be the table name itself (no alias used).
        for (FkColumnRef.TableRef t : tables) {
            if (t.getName().equalsIgnoreCase(qualifier)) {
                return t.getName();
            }
        }
        return null;
    }

    @Nullable
    private static DBSEntityAttribute findColumn(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity entity,
        @NotNull String columnName
    ) {
        try {
            Collection<? extends DBSEntityAttribute> attrs = entity.getAttributes(monitor);
            return DBUtils.findObject(attrs, columnName, true);
        } catch (DBException e) {
            log.debug("Failed to resolve column '" + columnName + "' on " + entity.getName(), e);
            return null;
        }
    }

    /**
     * Enumerate up to {@code maxResults} rows of the target table as (id, label) pairs, filtered
     * server-side by {@code filter} (LIKE on the description column) and ordered by the label.
     * Prefers DBeaver's dictionary enumeration; falls back to a light key-only SELECT.
     */
    @NotNull
    public static List<FkRow> enumerate(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull DBSEntity entity,
        @NotNull DBSEntityAttribute keyColumn,
        @Nullable String filter,
        int maxResults
    ) {
        DBPDataSource dataSource = entity.getDataSource();
        String searchText = (filter == null || filter.trim().isEmpty()) ? null : filter.trim();
        try {
            if (entity instanceof DBSDictionary dict && dict.supportsDictionaryEnumeration()) {
                List<DBDLabelValuePair> pairs = dict.getDictionaryEnumeration(
                    monitor,
                    keyColumn,
                    searchText,    // keyPattern - filter on the compared column's own value
                    null,          // no separate description search
                    null,          // preceding keys
                    true,          // case-insensitive search
                    true,          // ascending
                    true,          // sort by the compared column's value
                    0,
                    maxResults
                );
                List<FkRow> rows = new ArrayList<>(pairs.size());
                for (DBDLabelValuePair pair : pairs) {
                    rows.add(toRow(dataSource, keyColumn, pair.getValue(), pair.getLabel()));
                }
                return rows;
            }
            // Fallback for non-dictionary entities: list distinct key values only (no server filter).
            return enumerateFallback(monitor, context, entity, keyColumn, searchText, maxResults);
        } catch (DBException e) {
            log.debug("FK enumeration failed for " + entity.getName(), e);
            return Collections.emptyList();
        }
    }

    /**
     * Column names of {@code entity} that can be shown as the label, plus the current choice
     * (first element of the virtual model's description columns, or null for DBeaver's default).
     * Same setting as the result grid's FK header "..." button, so both stay in sync.
     */
    @NotNull
    public static List<String> listLabelColumns(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) {
        List<String> names = new ArrayList<>();
        try {
            for (DBSEntityAttribute attr : CommonUtils.safeCollection(entity.getAttributes(monitor))) {
                if (!DBUtils.isHiddenObject(attr)) {
                    names.add(attr.getName());
                }
            }
        } catch (DBException e) {
            log.debug("Failed to list columns of " + entity.getName(), e);
        }
        return names;
    }

    @Nullable
    public static String getLabelColumn(@NotNull DBSEntity entity) {
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, false);
        String names = vEntity == null ? null : vEntity.getDescriptionColumnNames();
        return CommonUtils.isEmpty(names) ? null : names.split(",")[0].trim();
    }

    public static void setLabelColumn(@NotNull DBSEntity entity, @NotNull String columnName) {
        DBVEntity vEntity = DBVUtils.getVirtualEntity(entity, true);
        if (vEntity != null) {
            vEntity.setDescriptionColumnNames(columnName);
            vEntity.persistConfiguration();
        }
    }

    @NotNull
    private static FkRow toRow(
        @NotNull DBPDataSource dataSource,
        @NotNull DBSEntityAttribute keyColumn,
        @Nullable Object value,
        @Nullable String label
    ) {
        String idText = value == null ? "" : String.valueOf(value);
        String literal = formatLiteral(dataSource, keyColumn, value);
        String lbl = label == null ? "" : label;
        // When the dictionary has no description column the label equals the key - avoid duplication.
        if (lbl.equals(idText)) {
            lbl = "";
        }
        return new FkRow(idText, lbl, literal);
    }

    /**
     * Type-aware SQL literal for the given key value (numbers bare, strings/UUIDs quoted),
     * produced by DBeaver's own value-to-SQL conversion so dialect quoting is respected.
     */
    @NotNull
    public static String formatLiteral(
        @NotNull DBPDataSource dataSource,
        @NotNull DBSEntityAttribute keyColumn,
        @Nullable Object value
    ) {
        if (value == null) {
            return "NULL";
        }
        try {
            String sql = SQLUtils.convertValueToSQL(dataSource, keyColumn, value);
            if (!CommonUtils.isEmpty(sql)) {
                return sql;
            }
        } catch (Exception e) {
            log.debug("Value-to-SQL conversion failed, using raw text", e);
        }
        return String.valueOf(value);
    }

    @NotNull
    private static List<FkRow> enumerateFallback(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull DBSEntity entity,
        @NotNull DBSEntityAttribute keyColumn,
        @Nullable String searchText,
        int maxResults
    ) throws DBException {
        DBPDataSource dataSource = entity.getDataSource();
        String keyId = DBUtils.getQuotedIdentifier(keyColumn);
        StringBuilder query = new StringBuilder("SELECT ").append(keyId)
            .append(" FROM ").append(DBUtils.getObjectFullName(entity, org.jkiss.dbeaver.model.DBPEvaluationContext.DML))
            .append(" ORDER BY ").append(keyId);
        List<FkRow> rows = new ArrayList<>();
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read FK id candidates")) {
            try (DBCStatement dbStat = session.prepareStatement(DBCStatementType.QUERY, query.toString(), false, false, false)) {
                dbStat.setLimit(0, maxResults);
                if (dbStat.executeStatement()) {
                    try (DBCResultSet rs = dbStat.openResultSet()) {
                        if (rs != null) {
                            while (rs.nextRow() && rows.size() < maxResults) {
                                Object value = rs.getAttributeValue(0);
                                String idText = value == null ? "" : String.valueOf(value);
                                if (searchText != null && !idText.toLowerCase(java.util.Locale.ROOT).contains(searchText.toLowerCase(java.util.Locale.ROOT))) {
                                    continue;
                                }
                                rows.add(toRow(dataSource, keyColumn, value, null));
                            }
                        }
                    }
                }
            }
        } catch (DBCException e) {
            log.debug("FK fallback enumeration failed", e);
        }
        return rows;
    }
}
