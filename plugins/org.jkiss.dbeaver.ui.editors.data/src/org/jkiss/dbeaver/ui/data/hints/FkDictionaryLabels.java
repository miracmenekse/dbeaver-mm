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
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dbeaver-mm FK dictionary PoC: resolves the dictionary (description) label for a value of a
 * foreign key column, e.g. {@code order_type_id = 2} -> {@code MAIN_ORDER}.
 * <p>
 * Shared by {@link FkDictionaryHintProvider} (draws the label as a dimmed cell hint) and the
 * spreadsheet's FK column header button. Moved here from SpreadsheetPresentation (A2); the lookup
 * logic is unchanged:
 * <ul>
 *     <li>The FK is found with {@link DBUtils#getAttributeReferrers}, not
 *     {@code ResultSetUtils.getEnumerableConstraint()}, which can return null at render time.</li>
 *     <li>A null result is never cached: metadata may not be loaded yet on the first paint, and a
 *     cached null would keep the grid from ever updating.</li>
 * </ul>
 * Caches are keyed by binding in weak maps, so they go away with the result set they belong to.
 */
public final class FkDictionaryLabels {

    private static final Log log = Log.getLog(FkDictionaryLabels.class);

    private static final int PREFETCH_CHUNK = 100;

    private static final Map<DBDAttributeBinding, DBSEntityAssociation> ASSOCIATIONS =
        Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<DBDAttributeBinding, Map<Object, String>> LABELS =
        Collections.synchronizedMap(new WeakHashMap<>());

    private FkDictionaryLabels() {
    }

    /**
     * Returns the association if {@code attr} is a foreign key to a dictionary table, otherwise null.
     */
    @Nullable
    public static DBSEntityAssociation getAssociation(@Nullable DBDAttributeBinding attr) {
        if (attr == null) {
            return null;
        }
        DBSEntityAssociation cached = ASSOCIATIONS.get(attr);
        if (cached != null) {
            return cached;
        }
        DBSEntityAssociation result = null;
        try {
            DBSEntityAttribute tableColumn = attr.getEntityAttribute();
            if (tableColumn != null) {
                for (DBSEntityReferrer ref : DBUtils.getAttributeReferrers(new VoidProgressMonitor(), tableColumn, true)) {
                    if (ref instanceof DBSEntityAssociation association) {
                        DBSEntityConstraint refConstraint = association.getReferencedConstraint();
                        if (refConstraint != null && refConstraint.getParentObject() instanceof DBSDictionary) {
                            result = association;
                            break;
                        }
                    }
                }
            }
        } catch (Throwable e) {
            log.debug("FK dictionary association lookup failed", e);
        }
        if (result != null) {
            ASSOCIATIONS.put(attr, result);
        }
        return result;
    }

    /**
     * dbeaver-mm K1: true if the dictionary FK of {@code attr} points into another connection
     * (only possible with a virtual FK; physical FKs never leave their database).
     */
    public static boolean isExternal(@Nullable DBDAttributeBinding attr) {
        DBSEntityAssociation association = getAssociation(attr);
        DBSEntityConstraint refConstraint = association == null ? null : association.getReferencedConstraint();
        return refConstraint != null && attr.getDataSource() != null
            && refConstraint.getDataSource() != null
            && refConstraint.getDataSource().getContainer() != attr.getDataSource().getContainer();
    }

    /**
     * dbeaver-mm K2/K5: the referenced dictionary's values with their labels (same description
     * column as the grid), first {@code maxResults} ordered by value. Used by the filter box
     * {@code column =} proposals and the in-cell value picker.
     */
    @NotNull
    public static List<DBDLabelValuePair> listValues(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDAttributeBinding attr,
        int maxResults
    ) throws Exception {
        DBSEntityAssociation association = getAssociation(attr);
        if (association == null) {
            return Collections.emptyList();
        }
        DBSEntityAttribute refColumn = DBUtils.getReferenceAttribute(monitor, association, attr.getEntityAttribute(), false);
        DBSEntityConstraint refConstraint = association.getReferencedConstraint();
        if (refColumn == null || refConstraint == null || !(refConstraint.getParentObject() instanceof DBSDictionary dictionary)) {
            return Collections.emptyList();
        }
        return dictionary.getDictionaryEnumeration(monitor, refColumn, null, null, null, false, true, true, 0, maxResults);
    }

    /**
     * Returns the dictionary label for {@code value}, or null if the column is not a dictionary FK
     * or the label is not available (yet).
     */
    @Nullable
    public static String getLabel(@Nullable DBDAttributeBinding attr, @Nullable Object value) {
        if (attr == null || DBUtils.isNullValue(value)) {
            return null;
        }
        DBSEntityAssociation association = getAssociation(attr);
        if (association == null) {
            return null;
        }
        Map<Object, String> columnLabels = LABELS.computeIfAbsent(attr, a -> new ConcurrentHashMap<>());
        String cached = columnLabels.get(keyOf(value));
        if (cached != null) {
            return cached;
        }
        // Not prefetched (e.g. a value edited in the grid): fall back to a single lookup
        String label = null;
        try {
            VoidProgressMonitor monitor = new VoidProgressMonitor();
            DBSEntityAttribute refColumn = DBUtils.getReferenceAttribute(monitor, association, attr.getEntityAttribute(), false);
            DBSEntityConstraint refConstraint = association.getReferencedConstraint();
            if (refColumn != null && refConstraint != null && refConstraint.getParentObject() instanceof DBSDictionary dictionary) {
                List<DBDLabelValuePair> pairs = dictionary.getDictionaryValues(
                    monitor,
                    Collections.singletonList(refColumn),
                    Collections.singletonList(new Object[]{value}),
                    null, false, true, false);
                if (!pairs.isEmpty()) {
                    label = pairs.getFirst().getLabel();
                }
            }
        } catch (Throwable e) {
            log.debug("FK dictionary label lookup failed", e);
        }
        if (label != null) {
            columnLabels.put(keyOf(value), label);
        }
        return label;
    }

    /**
     * Loads the labels of all {@code values} with one dictionary query (IN list) per chunk.
     * Called from the result set fetch job, so the grid paints from the cache instead of querying
     * the database once per visible cell on the UI thread.
     */
    public static void prefetch(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDAttributeBinding attr,
        @NotNull Collection<Object> values
    ) {
        DBSEntityAssociation association = getAssociation(attr);
        if (association == null || values.isEmpty()) {
            return;
        }
        Map<Object, String> columnLabels = LABELS.computeIfAbsent(attr, a -> new ConcurrentHashMap<>());
        List<Object[]> missing = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (Object value : values) {
            if (!DBUtils.isNullValue(value) && seen.add(keyOf(value)) && !columnLabels.containsKey(keyOf(value))) {
                missing.add(new Object[]{value});
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        try {
            DBSEntityAttribute refColumn = DBUtils.getReferenceAttribute(monitor, association, attr.getEntityAttribute(), false);
            DBSEntityConstraint refConstraint = association.getReferencedConstraint();
            if (refColumn == null || refConstraint == null || !(refConstraint.getParentObject() instanceof DBSDictionary dictionary)) {
                return;
            }
            for (int from = 0; from < missing.size() && !monitor.isCanceled(); from += PREFETCH_CHUNK) {
                List<Object[]> chunk = missing.subList(from, Math.min(from + PREFETCH_CHUNK, missing.size()));
                for (DBDLabelValuePair pair : dictionary.getDictionaryValues(
                    monitor, Collections.singletonList(refColumn), chunk, null, false, true, false)) {
                    Object key = keyOf(pair.getValue());
                    if (key != null && pair.getLabel() != null) {
                        columnLabels.put(key, pair.getLabel());
                    }
                }
            }
        } catch (Throwable e) {
            log.debug("FK dictionary label prefetch failed", e);
        }
    }

    /**
     * The FK column and the dictionary's key column can come back as different Java types
     * (Integer vs Long vs BigDecimal), so numbers are compared by their numeric value.
     */
    @Nullable
    private static Object keyOf(@Nullable Object value) {
        if (value instanceof Number number) {
            try {
                return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return number.toString();
            }
        }
        return value;
    }

    /**
     * Drops cached labels, e.g. after the user picked another description column.
     */
    public static void invalidateLabels() {
        LABELS.clear();
    }
}
