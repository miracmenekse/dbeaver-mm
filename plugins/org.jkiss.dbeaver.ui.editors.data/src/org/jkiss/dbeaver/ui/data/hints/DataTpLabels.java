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
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDictionary;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dbeaver-mm K69: {@code data_tp} type id -> target table, for the data_tp/row_id pair.
 * dbeaver-mm K74: read only from the tenant mapping file written by {@link DataTpMapDialog}
 * (tenant = first word of the grid connection's name). No type table query, no table search.
 * <p>
 * Follows the {@link FkDictionaryLabels} pattern: the mapping file and the row labels are loaded in
 * the result set fetch job, and {@link #getTypeTable} / {@link #getRowLabel} only read the caches,
 * so painting a cell never queries and never reads a file.
 */
public final class DataTpLabels {

    private static final Log log = Log.getLog(DataTpLabels.class);

    // dbeaver-mm K74: mapping file contents with the modification time they were read at
    private record Cached(long modified, DataTpStore.TenantMap map) {
    }

    // dbeaver-mm K74: tenant mapping file -> its contents (missing file = empty map)
    private static final Map<Path, Cached> MAPS = new ConcurrentHashMap<>();

    private static final int PREFETCH_CHUNK = 100;
    // dbeaver-mm K70: target key + "\u0000" + keyOf(row_id) -> description of the target row
    private static final Map<String, String> ROWS = new ConcurrentHashMap<>();
    // dbeaver-mm K70: "looked up, has no label"; compared by identity, like FkDictionaryLabels.MISSING
    private static final String MISSING = new String("");
    // dbeaver-mm K74: mapped target (connection, schema, table) -> resolved table
    private static final Map<DataTpStore.Entry, DBSEntity> TARGETS = new ConcurrentHashMap<>();

    private DataTpLabels() {
    }

    /**
     * dbeaver-mm K69: loads the mapping of {@code dataTp}'s connection.
     * dbeaver-mm K74: the tenant mapping file, re-read only when its modification time changed.
     * Called from the fetch job (file I/O), never while painting.
     */
    public static void prefetchTypes(@NotNull DBRProgressMonitor monitor, @Nullable DBDAttributeBinding dataTp) {
        Path file = mappingFile(dataTp);
        if (file == null) {
            return;
        }
        long modified;
        try {
            modified = Files.getLastModifiedTime(file).toMillis();
        } catch (Exception e) {
            modified = -1; // dbeaver-mm K74: no file yet, loadTenant gives an empty map
        }
        Cached cached = MAPS.get(file);
        if (cached != null && cached.modified() == modified) {
            return;
        }
        MAPS.put(file, new Cached(modified, DataTpStore.loadTenant(file)));
    }

    /**
     * dbeaver-mm K69: the target table name of {@code value}, or null if not prefetched or unknown.
     * Never queries: this runs while the grid paints.
     */
    @Nullable
    public static String getTypeTable(@Nullable DBDAttributeBinding dataTp, @Nullable Object value) {
        DataTpStore.Entry entry = getEntry(dataTp, value);
        return entry == null ? null : entry.table();
    }

    /**
     * dbeaver-mm K74: mapping entry of {@code value} in the tenant of {@code binding}'s connection,
     * or null if not prefetched or not mapped. Cache only.
     */
    @Nullable
    public static DataTpStore.Entry getEntry(@Nullable DBDAttributeBinding binding, @Nullable Object value) {
        if (DBUtils.isNullValue(value)) {
            return null;
        }
        Path file = mappingFile(binding);
        Cached cached = file == null ? null : MAPS.get(file);
        return cached == null ? null : cached.map().types().get(DataTpStore.typeKey(value));
    }

    /** dbeaver-mm K74: tenant of {@code binding}'s connection, "" if unknown. */
    @NotNull
    public static String getTenant(@Nullable DBDAttributeBinding binding) {
        DBPDataSourceContainer container = containerOf(binding);
        return container == null ? "" : DataTpStore.tenantOf(container.getName());
    }

    /**
     * dbeaver-mm K69: forgets loaded type tables, e.g. after the type table was edited.
     * dbeaver-mm K74: mapping files, row labels and resolved targets (re-read on next fetch).
     */
    public static void invalidate() {
        MAPS.clear();
        ROWS.clear();
        TARGETS.clear();
    }

    /**
     * dbeaver-mm K70: description of the row {@code rowIdValue} of the table {@code dataTpValue} points to,
     * or null if not prefetched or unknown. Never queries: this runs while the grid paints.
     */
    @Nullable
    public static String getRowLabel(@Nullable DBDAttributeBinding rowId, @Nullable Object dataTpValue, @Nullable Object rowIdValue) {
        DataTpStore.Entry entry = getEntry(rowId, dataTpValue);
        if (entry == null || DBUtils.isNullValue(rowIdValue)) {
            return null;
        }
        String label = ROWS.get(rowKey(entry, rowIdValue));
        return label == null || label == MISSING ? null : label;
    }

    /** dbeaver-mm K74: true if the mapped target connection of {@code dataTpValue} is not the grid's one. */
    public static boolean isExternal(@Nullable DBDAttributeBinding rowId, @Nullable Object dataTpValue) {
        DataTpStore.Entry entry = getEntry(rowId, dataTpValue);
        DBPDataSourceContainer container = containerOf(rowId);
        return entry != null && container != null && !entry.connectionId().equals(container.getId());
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
        Map<DataTpStore.Entry, List<Object[]>> byTarget = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (DBDValueRow row : rows) {
            Object[] values = row.getValues();
            if (tpIndex < 0 || idIndex < 0 || tpIndex >= values.length || idIndex >= values.length
                || DBUtils.isNullValue(values[idIndex])) {
                continue;
            }
            DataTpStore.Entry target = getEntry(dataTp, values[tpIndex]);
            if (target == null) {
                continue;
            }
            String key = rowKey(target, values[idIndex]);
            if (!ROWS.containsKey(key) && seen.add(key)) {
                byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(new Object[]{values[idIndex]});
            }
        }
        DBPDataSourceContainer grid = containerOf(dataTp);
        for (Map.Entry<DataTpStore.Entry, List<Object[]>> entry : byTarget.entrySet()) {
            if (monitor.isCanceled() || grid == null) {
                return;
            }
            DBSEntity entity = findMappedTable(monitor, grid, entry.getKey());
            if (entity == null) {
                // dbeaver-mm K74: closed connection or table not there: not marked missing, so connecting brings the labels
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
                log.debug("data_tp row labels lookup failed for " + entry.getKey().table(), e);
            }
            // dbeaver-mm K70: asked and got no label stays without one until refresh (K44); canceled fetch asked again
            if (!monitor.isCanceled()) {
                missing.forEach(v -> ROWS.putIfAbsent(rowKey(entry.getKey(), v[0]), MISSING));
            }
        }
    }

    /**
     * dbeaver-mm K74: the table {@code entry} names: its connection (looked up in the registry of
     * {@code grid}), schema and table. Null if the connection is unknown or not connected (it is never
     * opened here) or the table is not there. Reads metadata, so call it from a background job.
     */
    @Nullable
    public static DBSEntity findMappedTable(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer grid,
        @NotNull DataTpStore.Entry entry
    ) {
        DBSEntity cached = TARGETS.get(entry);
        // dbeaver-mm K70: a reconnect replaces the data source, so a table of the old one is resolved again
        if (cached != null && cached.getDataSource() != null
            && cached.getDataSource().getContainer().getDataSource() == cached.getDataSource()) {
            return cached;
        }
        TARGETS.remove(entry);
        DBPDataSourceContainer container = grid.getRegistry().getDataSource(entry.connectionId());
        if (container == null || !container.isConnected() || container.getDataSource() == null) {
            return null;
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
        if (root == null) {
            return null;
        }
        try {
            DBSObjectContainer schema = entry.schema().isEmpty() ? root : findSchema(monitor, root, entry.schema(), 1);
            if (schema != null
                && DBUtils.findObject(schema.getChildren(monitor), entry.table(), true) instanceof DBSEntity entity) {
                TARGETS.put(entry, entity);
                return entity;
            }
        } catch (Exception e) {
            log.debug("Can't look up " + entry.schema() + "." + entry.table() + " in " + container.getName(), e);
        }
        return null;
    }

    /** dbeaver-mm K74: schema named {@code name} below {@code container} (catalog > schema: one level deeper). */
    @Nullable
    private static DBSObjectContainer findSchema(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        @NotNull String name,
        int depth
    ) throws Exception {
        Collection<? extends DBSObject> children = container.getChildren(monitor);
        if (children == null) {
            return null;
        }
        if (DBUtils.findObject(children, name, true) instanceof DBSObjectContainer schema && !(schema instanceof DBSEntity)) {
            return schema;
        }
        if (depth > 0) {
            for (DBSObject child : children) {
                if (child instanceof DBSObjectContainer sub && !(child instanceof DBSEntity)) {
                    DBSObjectContainer schema = findSchema(monitor, sub, name, depth - 1);
                    if (schema != null) {
                        return schema;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    private static DBPDataSourceContainer containerOf(@Nullable DBDAttributeBinding binding) {
        return binding == null || binding.getDataSource() == null ? null : binding.getDataSource().getContainer();
    }

    /** dbeaver-mm K74: tenant mapping file of {@code binding}'s connection; path only, no file access. */
    @Nullable
    private static Path mappingFile(@Nullable DBDAttributeBinding binding) {
        DBPDataSourceContainer container = containerOf(binding);
        return container == null ? null
            : DataTpStore.tenantFile(DataTpStore.tenantDir(), DataTpStore.tenantOf(container.getName()));
    }

    @NotNull
    private static String rowKey(@NotNull DataTpStore.Entry target, @Nullable Object rowIdValue) {
        // dbeaver-mm K74: the same table name in two tenants is two different tables
        return target.connectionId() + "\u0000" + target.schema() + "\u0000" + DataTpStore.tableKey(target.table())
            + "\u0000" + keyOf(rowIdValue);
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

    /**
     * "20" -> 20 for a numeric key, 20 -> "20" for a text key; anything unparsable stays as it is.
     * dbeaver-mm K71: package-private, the K71 dialog's preview binds row_id the same way.
     */
    @Nullable
    static Object asKeyType(@Nullable Object value, boolean numericKey) {
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
