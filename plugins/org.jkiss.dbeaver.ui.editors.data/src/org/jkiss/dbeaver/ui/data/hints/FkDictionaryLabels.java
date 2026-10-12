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

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Control;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import org.jkiss.dbeaver.model.virtual.DBVUtils;

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
 *     <li>dbeaver-mm K44: "not a dictionary FK" is cached once the column's metadata was read
 *     (a binding without its table column yet is not), and a value without a label is cached as
 *     {@link #MISSING}: before that every paint and hover re-ran the lookup on the UI thread, one
 *     database round trip per cell (seconds of freeze over an SSH tunnel). {@link #invalidateLabels}
 *     (refresh) forgets both.</li>
 *     <li>dbeaver-mm K44: {@link #getLabel} never queries; a value missing from the cache is loaded
 *     by one background job together with the others and the grids are redrawn.</li>
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
    // dbeaver-mm K44: "looked up, has no label" (orphan FK value, NULL description); compared by identity
    private static final String MISSING = new String("");
    private static final Set<DBDAttributeBinding> NOT_FK =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Map<DBDAttributeBinding, Boolean> EXTERNAL =
        Collections.synchronizedMap(new WeakHashMap<>());
    // Values asked for while painting that were not prefetched, loaded by LOADER; guarded by itself
    private static final Map<DBDAttributeBinding, Set<Object>> PENDING = new WeakHashMap<>();
    // Grids to redraw when LOADER brings labels
    private static final Set<Control> REDRAW =
        Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Job LOADER = Job.create("FK dictionary labels", progress -> {
        Map<DBDAttributeBinding, Set<Object>> work;
        synchronized (PENDING) {
            work = new HashMap<>(PENDING);
            PENDING.clear();
        }
        DBRProgressMonitor monitor = new DefaultProgressMonitor(progress);
        work.forEach((attr, values) -> prefetch(monitor, attr, values));
        List<Control> grids;
        synchronized (REDRAW) {
            grids = new ArrayList<>(REDRAW);
        }
        UIUtils.asyncExec(() -> grids.stream().filter(c -> !c.isDisposed()).forEach(Control::redraw));
        return Status.OK_STATUS;
    });

    static {
        LOADER.setSystem(true);
    }

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
        if (cached != null || NOT_FK.contains(attr)) {
            return cached;
        }
        DBSEntityAssociation result = null;
        boolean known = false;
        try {
            DBSEntityAttribute tableColumn = attr.getEntityAttribute();
            if (tableColumn != null) {
                for (DBSEntityReferrer ref : DBUtils.getAttributeReferrers(new VoidProgressMonitor(), tableColumn, true)) {
                    if (ref instanceof DBSEntityAssociation association) {
                        DBSEntityConstraint refConstraint = association.getReferencedConstraint();
                        if (refConstraint != null && refConstraint.getParentObject() instanceof DBSDictionary) {
                            result = association;
                            // K1: resolving a virtual FK walks the navigator, so do it once here, not per cell
                            EXTERNAL.put(attr, attr.getDataSource() != null && refConstraint.getDataSource() != null
                                && refConstraint.getDataSource().getContainer() != attr.getDataSource().getContainer());
                            break;
                        }
                    }
                }
                known = true;
            }
        } catch (Throwable e) {
            log.debug("FK dictionary association lookup failed", e);
        }
        if (result != null) {
            ASSOCIATIONS.put(attr, result);
        } else if (known) {
            NOT_FK.add(attr);
        }
        return result;
    }

    /**
     * dbeaver-mm K1: true if the dictionary FK of {@code attr} points into another connection
     * (only possible with a virtual FK; physical FKs never leave their database).
     */
    public static boolean isExternal(@Nullable DBDAttributeBinding attr) {
        return getAssociation(attr) != null && Boolean.TRUE.equals(EXTERNAL.get(attr));
    }

    /**
     * dbeaver-mm K12: see {@link DBVUtils#foldForSearch}.
     */
    @NotNull
    public static String foldForSearch(@Nullable String text) {
        return DBVUtils.foldForSearch(text);
    }

    /**
     * dbeaver-mm K26: rows of the referenced table that exist only in conf packages (K25), so a new
     * parent row can be picked before it reaches the database. Label = description column value
     * plus the task id. {@code search} matches value or label, accent and case blind.
     */
    @NotNull
    public static List<DBDLabelValuePair> listConfPackageValues(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDAttributeBinding attr,
        @Nullable String search
    ) throws Exception {
        DBSEntityAssociation association = getAssociation(attr);
        DBSEntityAttribute keyColumn = association == null || attr.getEntityAttribute() == null
            ? null
            : DBUtils.getReferenceAttribute(monitor, association, attr.getEntityAttribute(), false);
        if (keyColumn == null) {
            return Collections.emptyList();
        }
        String key = keyColumn.getName().toLowerCase(Locale.ROOT);
        String descColumns = DBVUtils.getDictionaryDescriptionColumns(monitor, keyColumn);
        String desc = descColumns == null ? null : descColumns.split(",")[0].strip().toLowerCase(Locale.ROOT);
        String folded = CommonUtils.isEmpty(search) ? null : foldForSearch(search);
        List<DBDLabelValuePair> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (org.jkiss.dbeaver.ui.controls.resultset.conf.ConfPackages.InsertedRow row :
            org.jkiss.dbeaver.ui.controls.resultset.conf.ConfPackages.findInserts(keyColumn.getParentObject().getName())) {
            String value = row.values().get(key);
            if (value == null || !seen.add(value)) {
                continue;
            }
            String label = CommonUtils.notEmpty(desc == null ? null : row.values().get(desc)) + "  [conf " + row.task() + "]";
            if (folded == null || foldForSearch(value).contains(folded) || foldForSearch(label).contains(folded)) {
                result.add(new DBDLabelValuePair(label.strip(), toKeyValue(keyColumn, value)));
            }
        }
        return result;
    }

    /**
     * dbeaver-mm K28: dictionary labels of {@code values} (as written in a conf package) of a table
     * column that is a dictionary FK, physical or virtual. Rows that exist only in conf packages are
     * labelled from there, marked with the task id. Key = value as text, numbers in plain form.
     */
    @NotNull
    public static Map<String, String> labelsOf(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSEntityAttribute column,
        @NotNull Collection<String> values
    ) throws Exception {
        Map<String, String> result = new HashMap<>();
        for (DBSEntityReferrer ref : DBUtils.getAttributeReferrers(monitor, column, true)) {
            if (!(ref instanceof DBSEntityAssociation association)
                || !(association.getReferencedConstraint() != null
                    && association.getReferencedConstraint().getParentObject() instanceof DBSDictionary dictionary)) {
                continue;
            }
            DBSEntityAttribute keyColumn = DBUtils.getReferenceAttribute(monitor, association, column, false);
            if (keyColumn == null) {
                continue;
            }
            List<Object[]> keys = new ArrayList<>();
            values.forEach(v -> keys.add(new Object[]{toKeyValue(keyColumn, v)}));
            for (DBDLabelValuePair pair : dictionary.getDictionaryValues(
                monitor, Collections.singletonList(keyColumn), keys, null, false, true, false)) {
                if (pair.getLabel() != null && pair.getValue() != null) {
                    result.put(String.valueOf(keyOf(pair.getValue())), pair.getLabel());
                }
            }
            String key = keyColumn.getName().toLowerCase(Locale.ROOT);
            String descColumns = DBVUtils.getDictionaryDescriptionColumns(monitor, keyColumn);
            String desc = descColumns == null ? null : descColumns.split(",")[0].strip().toLowerCase(Locale.ROOT);
            for (org.jkiss.dbeaver.ui.controls.resultset.conf.ConfPackages.InsertedRow row :
                org.jkiss.dbeaver.ui.controls.resultset.conf.ConfPackages.findInserts(keyColumn.getParentObject().getName())) {
                String value = row.values().get(key);
                if (value != null) {
                    String label = CommonUtils.notEmpty(desc == null ? null : row.values().get(desc));
                    result.putIfAbsent(String.valueOf(keyOf(toKeyValue(keyColumn, value))), (label + "  [conf " + row.task() + "]").strip());
                }
            }
            break;
        }
        return result;
    }

    /** "40" -> 40 for numeric keys, so the picked value has the column's type like database values */
    @NotNull
    private static Object toKeyValue(@NotNull DBSEntityAttribute keyColumn, @NotNull String value) {
        if (keyColumn.getDataKind() == org.jkiss.dbeaver.model.DBPDataKind.NUMERIC) {
            try {
                return value.contains(".") ? new BigDecimal(value) : (Object) Long.valueOf(value);
            } catch (NumberFormatException ignored) {
                // a function call or expression: keep the text
            }
        }
        return value;
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
        @Nullable String search,
        int maxResults
    ) throws Exception {
        DBSEntityAttribute keyColumn;
        DBSDictionary dictionary;
        DBSEntityAssociation association = getAssociation(attr);
        if (association != null) {
            // Dictionary FK: values of the referenced table
            keyColumn = DBUtils.getReferenceAttribute(monitor, association, attr.getEntityAttribute(), false);
            DBSEntityConstraint refConstraint = association.getReferencedConstraint();
            dictionary = refConstraint != null && refConstraint.getParentObject() instanceof DBSDictionary d ? d : null;
        } else {
            // dbeaver-mm K8: plain column - its own distinct values (e.g. "label =")
            keyColumn = attr.getEntityAttribute();
            dictionary = keyColumn != null && keyColumn.getParentObject() instanceof DBSDictionary d ? d : null;
        }
        if (keyColumn == null || dictionary == null || !dictionary.supportsDictionaryEnumeration()) {
            return Collections.emptyList();
        }
        // Numbers are matched against the value, text against the description column
        boolean numeric = search != null && search.matches("-?\\d+(\\.\\d+)?");
        return dictionary.getDictionaryEnumeration(
            monitor, keyColumn,
            numeric ? search : null,
            numeric || CommonUtils.isEmpty(search) ? null : search,
            null, true, true, true, 0, maxResults);
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
        String cached = LABELS.computeIfAbsent(attr, a -> new ConcurrentHashMap<>()).get(keyOf(value));
        if (cached != null) {
            return cached == MISSING ? null : cached;
        }
        // Not prefetched (e.g. a value edited in the grid). This runs while the grid paints, so no
        // query here: queue it, LOADER fetches all queued values together and redraws the grids.
        synchronized (PENDING) {
            PENDING.computeIfAbsent(attr, a -> new LinkedHashSet<>()).add(value);
        }
        LOADER.schedule(100);
        return null;
    }

    /** dbeaver-mm K44: {@code grid} is redrawn when labels it asked for arrive in the background. */
    public static void redrawOnLabels(@NotNull Control grid) {
        REDRAW.add(grid);
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
                markMissing(monitor, columnLabels, missing);
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
        markMissing(monitor, columnLabels, missing);
    }

    /**
     * dbeaver-mm K44: values that were asked for and got no label stay without one until a refresh,
     * a failed lookup included (retrying it on every paint is what froze the grid). A canceled fetch
     * marks nothing, so it is asked again.
     */
    private static void markMissing(@NotNull DBRProgressMonitor monitor, @NotNull Map<Object, String> columnLabels, @NotNull List<Object[]> asked) {
        if (!monitor.isCanceled()) {
            asked.forEach(v -> columnLabels.putIfAbsent(keyOf(v[0]), MISSING));
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
        NOT_FK.clear();
    }
}
