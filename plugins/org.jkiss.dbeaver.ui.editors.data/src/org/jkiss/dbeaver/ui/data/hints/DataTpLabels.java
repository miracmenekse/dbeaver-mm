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
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSEntityAttribute;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
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

    /** dbeaver-mm K69: forgets the loaded type tables, e.g. after the type table was edited. */
    public static void invalidate() {
        TYPES.clear();
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
        try {
            Collection<? extends DBSObject> children = container.getChildren(monitor);
            if (DBUtils.findObject(children, table, true) instanceof DBSEntity entity) {
                return entity;
            }
            if (depth > 0 && children != null) {
                for (DBSObject child : children) {
                    if (child instanceof DBSObjectContainer sub && !(child instanceof DBSEntity)) {
                        DBSEntity entity = findInChildren(monitor, sub, table, depth - 1);
                        if (entity != null) {
                            return entity;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't look up " + table + " in " + container.getName(), e);
        }
        return null;
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
}
