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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDictionary;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dbeaver-mm K69: {@code data_tp} type id -> target table name, read once per connection from
 * {@code etiya_type_value}.
 * <p>
 * Follows the {@link FkDictionaryLabels} pattern: values are prefetched in the result set fetch
 * job, and {@link #getTypeTable} only reads the cache, so painting a cell never queries.
 * A connection whose lookup found nothing keeps an empty map, so it is not retried until
 * {@link #invalidate}.
 */
public final class DataTpLabels {

    private static final Log log = Log.getLog(DataTpLabels.class);

    // dbeaver-mm K69: connection id -> (type id -> table name)
    private static final Map<String, Map<String, String>> TYPES = new ConcurrentHashMap<>();

    private static final int PREFETCH_CHUNK = 100;
    // dbeaver-mm K70: tableKey + "\u0000" + keyOf(row_id) -> description of the target row
    private static final Map<String, String> ROWS = new ConcurrentHashMap<>();
    // dbeaver-mm K70: "looked up, has no label"; compared by identity, like FkDictionaryLabels.MISSING
    private static final String MISSING = new String("");
    // dbeaver-mm K70: tableKey -> resolved target table
    private static final Map<String, DBSEntity> TARGETS = new ConcurrentHashMap<>();
    // dbeaver-mm K70: tableKeys with no saved mapping and zero or several candidate tables (K71 asks)
    private static final Set<String> UNRESOLVED = ConcurrentHashMap.newKeySet();
    // dbeaver-mm K70: saved mappings, read at most once until invalidate(); guarded by DataTpLabels.class
    private static Map<String, DataTpStore.Target> mappings;

    private DataTpLabels() {
    }

    /**
     * dbeaver-mm K69: loads the type table of {@code dataTp}'s connection, once per connection.
     * Called from the fetch job, so a missing or unreachable table is remembered as empty.
     */
    public static void prefetchTypes(@NotNull DBRProgressMonitor monitor, @Nullable DBDAttributeBinding dataTp) {
        if (dataTp == null || dataTp.getDataSource() == null || dataTp.getDataSource().getContainer() == null) {
            return;
        }
        String cacheKey = keyOf(dataTp.getDataSource().getContainer().getId());
        if (cacheKey == null || TYPES.containsKey(cacheKey)) {
            return;
        }
        Map<String, String> types;
        try {
            types = new HashMap<>();
            DBSEntity entity = findTypeTable(monitor, dataTp);
            if (entity != null) {
                loadTypes(monitor, entity, types);
            }
        } catch (Exception e) {
            log.debug("data_tp type names lookup failed", e);
            types = new HashMap<>();
        }
        // dbeaver-mm K69: an empty map is kept too, so a missing table is looked up only once
        TYPES.putIfAbsent(cacheKey, new ConcurrentHashMap<>(types));
    }

    /**
     * dbeaver-mm K69: the target table name of {@code value}, or null if not prefetched or unknown.
     * Never queries: this runs while the grid paints.
     */
    @Nullable
    public static String getTypeTable(@Nullable DBDAttributeBinding dataTp, @Nullable Object value) {
        if (dataTp == null || dataTp.getDataSource() == null || dataTp.getDataSource().getContainer() == null
            || DBUtils.isNullValue(value)) {
            return null;
        }
        Map<String, String> types = TYPES.get(keyOf(dataTp.getDataSource().getContainer().getId()));
        return types == null ? null : types.get(keyOf(value));
    }

    /**
     * dbeaver-mm K69: forgets the loaded type tables, e.g. after the type table was edited.
     * dbeaver-mm K70: also the row labels, resolved targets and the saved mappings (re-read on next use).
     */
    public static void invalidate() {
        TYPES.clear();
        ROWS.clear();
        TARGETS.clear();
        UNRESOLVED.clear();
        synchronized (DataTpLabels.class) {
            mappings = null;
        }
    }

    /**
     * dbeaver-mm K70: description of the row {@code rowIdValue} of the table {@code dataTpValue} points to,
     * or null if not prefetched or unknown. Never queries: this runs while the grid paints.
     * {@code rowId} gives the connection whose type table maps {@code dataTpValue}.
     */
    @Nullable
    public static String getRowLabel(@Nullable DBDAttributeBinding rowId, @Nullable Object dataTpValue, @Nullable Object rowIdValue) {
        String table = getTypeTable(rowId, dataTpValue);
        if (table == null || DBUtils.isNullValue(rowIdValue)) {
            return null;
        }
        String label = ROWS.get(rowKey(table, rowIdValue));
        return label == null || label == MISSING ? null : label;
    }

    /** dbeaver-mm K70: true if the target table of {@code dataTpValue} lives in another connection than the grid. */
    public static boolean isExternal(@Nullable DBDAttributeBinding rowId, @Nullable Object dataTpValue) {
        String table = getTypeTable(rowId, dataTpValue);
        DBSEntity target = table == null ? null : TARGETS.get(DataTpStore.tableKey(table));
        return target != null && target.getDataSource() != null && rowId.getDataSource() != null
            && target.getDataSource().getContainer() != rowId.getDataSource().getContainer();
    }

    /** dbeaver-mm K70: target table names the {@code data_tp} values of {@code rows} point to (cache only). */
    @NotNull
    public static Set<String> getTypeTables(@NotNull DBDAttributeBinding dataTp, @NotNull Collection<? extends DBDValueRow> rows) {
        Set<String> tables = new TreeSet<>();
        int index = dataTp.getOrdinalPosition();
        for (DBDValueRow row : rows) {
            Object[] values = row.getValues();
            String table = index >= 0 && index < values.length ? getTypeTable(dataTp, values[index]) : null;
            if (table != null) {
                tables.add(table);
            }
        }
        return tables;
    }

    /** dbeaver-mm K70: target table names that could not be resolved automatically (none or several candidates). */
    @NotNull
    public static Set<String> unresolved() {
        return Collections.unmodifiableSet(UNRESOLVED);
    }

    /**
     * dbeaver-mm K70: loads the descriptions of the target rows of {@code rows}, one dictionary query
     * (IN list) per target table and chunk. Called from the fetch job after {@link #prefetchTypes}.
     */
    public static void prefetchRows(
        @NotNull DBRProgressMonitor monitor,
        @Nullable DBDAttributeBinding dataTp,
        @Nullable DBDAttributeBinding rowId,
        @NotNull Collection<? extends DBDValueRow> rows
    ) {
        if (dataTp == null || rowId == null) {
            return;
        }
        int tpIndex = dataTp.getOrdinalPosition();
        int idIndex = rowId.getOrdinalPosition();
        Map<String, List<Object[]>> byTable = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (DBDValueRow row : rows) {
            Object[] values = row.getValues();
            if (tpIndex < 0 || idIndex < 0 || tpIndex >= values.length || idIndex >= values.length
                || DBUtils.isNullValue(values[idIndex])) {
                continue;
            }
            String table = getTypeTable(dataTp, values[tpIndex]);
            if (table == null) {
                continue;
            }
            String key = rowKey(table, values[idIndex]);
            if (!ROWS.containsKey(key) && seen.add(key)) {
                byTable.computeIfAbsent(table, t -> new ArrayList<>()).add(new Object[]{values[idIndex]});
            }
        }
        for (Map.Entry<String, List<Object[]>> entry : byTable.entrySet()) {
            if (monitor.isCanceled()) {
                return;
            }
            DBSEntity entity = resolveTarget(monitor, entry.getKey());
            if (entity == null) {
                // no target yet: not marked missing, so a mapping made later (K71) brings the labels
                continue;
            }
            List<Object[]> missing = entry.getValue();
            try {
                List<? extends DBSEntityAttribute> identifier = DBUtils.getBestTableIdentifier(monitor, entity);
                if (entity instanceof DBSDictionary dictionary && !identifier.isEmpty()) {
                    // ponytail: first identifier column only, composite keys of the target are not matched
                    List<DBSEntityAttribute> keyColumns = Collections.singletonList(identifier.get(0));
                    // dbeaver-mm K70: row_id is bound with the key column's type (VARCHAR row_id vs NUMBER key and back)
                    boolean numericKey = identifier.get(0).getDataKind() == org.jkiss.dbeaver.model.DBPDataKind.NUMERIC;
                    missing.replaceAll(v -> new Object[]{asKeyType(v[0], numericKey)});
                    for (int from = 0; from < missing.size() && !monitor.isCanceled(); from += PREFETCH_CHUNK) {
                        List<Object[]> chunk = missing.subList(from, Math.min(from + PREFETCH_CHUNK, missing.size()));
                        for (DBDLabelValuePair pair : dictionary.getDictionaryValues(
                            monitor, keyColumns, chunk, null, false, true, false)) {
                            if (pair.getValue() != null && pair.getLabel() != null) {
                                ROWS.put(rowKey(entry.getKey(), pair.getValue()), pair.getLabel());
                            }
                        }
                    }
                }
            } catch (Throwable e) {
                log.debug("data_tp row labels lookup failed for " + entry.getKey(), e);
            }
            // dbeaver-mm K70: asked and got no label stays without one until a refresh (K44); a canceled fetch is asked again
            if (!monitor.isCanceled()) {
                missing.forEach(v -> ROWS.putIfAbsent(rowKey(entry.getKey(), v[0]), MISSING));
            }
        }
    }

    /**
     * dbeaver-mm K70: target table of {@code tableName}: the saved mapping if any (null while its connection is
     * not connected); else the only table of that name in the open connections, which is then saved;
     * else null and the name goes to {@link #unresolved()}. Queries metadata and may write the mapping
     * file, so call it from a background job, never while painting.
     */
    @Nullable
    public static DBSEntity resolveTarget(@NotNull DBRProgressMonitor monitor, @NotNull String tableName) {
        String key = DataTpStore.tableKey(tableName);
        DBSEntity cached = TARGETS.get(key);
        // dbeaver-mm K70: a reconnect replaces the data source, so a table of the old one is resolved again
        if (cached != null && (cached.getDataSource() == null
            || cached.getDataSource().getContainer().getDataSource() != cached.getDataSource())) {
            TARGETS.remove(key);
            cached = null;
        }
        if (cached != null || UNRESOLVED.contains(key)) {
            return cached;
        }
        // ponytail: one lock for resolving and saving, fine while only fetch jobs and the K71 dialog call it
        synchronized (DataTpLabels.class) {
            if (mappings == null) {
                mappings = new HashMap<>(DataTpStore.load(DataTpStore.defaultFile()));
            }
            DataTpStore.Target target = mappings.get(key);
            DBSEntity entity;
            if (target != null) {
                entity = findMapped(monitor, target, tableName);
            } else {
                List<DBSEntity> found = findTables(monitor, tableName);
                if (found.size() != 1) {
                    UNRESOLVED.add(key);
                    return null;
                }
                entity = found.get(0);
                DBSObject parent = entity.getParentObject();
                mappings.put(key, new DataTpStore.Target(
                    entity.getDataSource().getContainer().getId(),
                    parent == null || parent instanceof DBPDataSource ? "" : parent.getName()));
                try {
                    DataTpStore.save(DataTpStore.defaultFile(), mappings);
                } catch (IOException e) {
                    log.debug("Can't save data_tp mapping of " + tableName, e);
                }
            }
            if (entity != null) {
                TARGETS.put(key, entity);
            }
            return entity;
        }
    }

    /**
     * dbeaver-mm K70: every table named {@code table} in the open connections of the active project:
     * per connection the default schema first, then two levels below the root (catalog > schema).
     * Like {@code ConfPackagesView.findTable}, but collects all matches.
     */
    @NotNull
    public static List<DBSEntity> findTables(@NotNull DBRProgressMonitor monitor, @NotNull String table) {
        List<DBSEntity> result = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project == null) {
            return result;
        }
        for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
            if (!container.isConnected() || container.getDataSource() == null) {
                continue;
            }
            try {
                DBCExecutionContext context = DBUtils.getDefaultContext(container.getDataSource(), true);
                DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
                DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
                DBSObjectContainer schema = defaults == null ? null
                    : defaults.getDefaultSchema() instanceof DBSObjectContainer s ? s
                    : defaults.getDefaultCatalog() instanceof DBSObjectContainer c ? c : null;
                if (schema != null && DBUtils.findObject(schema.getChildren(monitor), table, true) instanceof DBSEntity entity) {
                    result.add(entity);
                } else if (root != null) {
                    collectInChildren(monitor, root, table, 2, result, Integer.MAX_VALUE);
                }
            } catch (Exception e) {
                log.debug("Can't look up " + table + " in " + container.getName(), e);
            }
        }
        return result;
    }

    /** dbeaver-mm K70: the saved target's table, or null if its connection is gone or not connected. */
    @Nullable
    private static DBSEntity findMapped(@NotNull DBRProgressMonitor monitor, @NotNull DataTpStore.Target target, @NotNull String table) {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        DBPDataSourceContainer container = project == null ? null : project.getDataSourceRegistry().getDataSource(target.connectionId());
        if (container == null || !container.isConnected() || container.getDataSource() == null) {
            return null;
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
        if (root == null) {
            return null;
        }
        try {
            if (target.schema() == null || target.schema().isEmpty()) {
                return findInChildren(monitor, root, table, 2);
            }
            DBCExecutionContext context = DBUtils.getDefaultContext(container.getDataSource(), true);
            if (context != null
                && DBUtils.getObjectByPath(monitor, context, root, null, target.schema(), table) instanceof DBSEntity entity) {
                return entity;
            }
        } catch (Exception e) {
            log.debug("Can't look up " + target.schema() + "." + table + " in " + container.getName(), e);
        }
        return null;
    }

    @NotNull
    private static String rowKey(@NotNull String table, @Nullable Object rowIdValue) {
        return DataTpStore.tableKey(table) + "\u0000" + keyOf(rowIdValue);
    }

    /**
     * dbeaver-mm K69: the type table is searched in the column's own schema first, then two levels
     * below the data source root (catalog > schema), like {@code ConfPackagesView.findInChildren}.
     */
    @Nullable
    private static DBSEntity findTypeTable(@NotNull DBRProgressMonitor monitor, @NotNull DBDAttributeBinding dataTp) {
        DBSEntityAttribute column = dataTp.getEntityAttribute();
        DBSObject table = column == null ? null : column.getParentObject();
        DBSObject schema = table == null ? null : table.getParentObject();
        if (schema instanceof DBSObjectContainer container) {
            try {
                if (DBUtils.findObject(container.getChildren(monitor), DataTpStore.TYPE_TABLE, true) instanceof DBSEntity entity) {
                    return entity;
                }
            } catch (Exception e) {
                log.debug("Can't look up " + DataTpStore.TYPE_TABLE + " in " + container.getName(), e);
            }
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataTp.getDataSource());
        return root == null ? null : findInChildren(monitor, root, DataTpStore.TYPE_TABLE, 2);
    }

    /** dbeaver-mm K69: {@code table} in {@code container} or its sub-containers, {@code depth} levels down. */
    @Nullable
    private static DBSEntity findInChildren(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        @NotNull String table,
        int depth
    ) {
        List<DBSEntity> found = new ArrayList<>(1);
        collectInChildren(monitor, container, table, depth, found, 1);
        return found.isEmpty() ? null : found.get(0);
    }

    /** dbeaver-mm K70: adds matches of {@code table} below {@code container} to {@code found} until it has {@code limit}. */
    private static void collectInChildren(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        @NotNull String table,
        int depth,
        @NotNull List<DBSEntity> found,
        int limit
    ) {
        try {
            Collection<? extends DBSObject> children = container.getChildren(monitor);
            if (DBUtils.findObject(children, table, true) instanceof DBSEntity entity) {
                found.add(entity);
            }
            if (depth > 0 && children != null) {
                for (DBSObject child : children) {
                    if (found.size() >= limit || monitor.isCanceled()) {
                        return;
                    }
                    if (child instanceof DBSObjectContainer sub && !(child instanceof DBSEntity)) {
                        collectInChildren(monitor, sub, table, depth - 1, found, limit);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't look up " + table + " in " + container.getName(), e);
        }
    }

    /** dbeaver-mm K69: reads the whole type table; a failure is handled by {@link #prefetchTypes}. */
    private static void loadTypes(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntity entity,
        @NotNull Map<String, String> types
    ) throws Exception {
        DBCExecutionContext context = DBUtils.getDefaultContext(entity.getDataSource(), true);
        if (context == null) {
            return;
        }
        DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read data_tp type names");
        try (session) {
            String query = "SELECT " + DataTpStore.TYPE_ID + ", " + DataTpStore.TYPE_NAME
                + " FROM " + DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML);
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, query, false, false, false)) {
                if (!statement.executeStatement()) {
                    return;
                }
                try (DBCResultSet resultSet = statement.openResultSet()) {
                    while (resultSet != null && !monitor.isCanceled() && resultSet.nextRow()) {
                        String key = keyOf(resultSet.getAttributeValue(0));
                        Object name = resultSet.getAttributeValue(1);
                        if (key != null && name != null) {
                            types.put(key, String.valueOf(name));
                        }
                    }
                }
            }
        }
    }

    /**
     * dbeaver-mm K69: the same type id can come back as Integer, Long or BigDecimal, so numbers are
     * compared by their plain numeric value.
     */
    @Nullable
    private static String keyOf(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return number.toString();
            }
        }
        return String.valueOf(value);
    }

    /** "20" -> 20 for a numeric key, 20 -> "20" for a text key; anything unparsable stays as it is */
    @Nullable
    private static Object asKeyType(@Nullable Object value, boolean numericKey) {
        if (numericKey && value instanceof String text) {
            try {
                return text.strip().contains(".") ? new BigDecimal(text.strip()) : (Object) Long.valueOf(text.strip());
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return !numericKey && value instanceof Number ? keyOf(value) : value;
    }

}
