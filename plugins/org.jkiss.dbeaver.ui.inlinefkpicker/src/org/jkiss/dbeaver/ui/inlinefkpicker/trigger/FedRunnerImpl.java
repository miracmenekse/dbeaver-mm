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
package org.jkiss.dbeaver.ui.inlinefkpicker.trigger;

import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCAttributeMetaData;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLFederation;
import org.jkiss.dbeaver.ui.editors.sql.SqlConnectionsBar;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;
import org.jkiss.dbeaver.ui.inlinefkpicker.fed.FedExecutor;
import org.jkiss.dbeaver.ui.inlinefkpicker.fed.FedPlanner;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dbeaver-mm K123: federe sorguyu bağlantılara dağıtan ve sonucu seçim listesine indirgeyen köprü çalıştırıcısı.
 */
public class FedRunnerImpl implements SQLFederation.Runner {

    private static final long MAX_ROWS = 100000L;
    private static final String LABEL_PREFIX = "federated: ";

    @Nullable
    @Override
    public SQLFederation.Result tryRun(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull String sql
    ) throws DBException {
        FedPlanner.FedPlan plan = FedPlanner.parse(sql);
        if (plan == null) {
            return null;
        }

        List<String> tableRefs = new ArrayList<>();
        for (FedPlanner.Table table : plan.tables()) {
            String ref = tableRef(table);
            if (!tableRefs.contains(ref)) {
                tableRefs.add(ref);
            }
        }
        Map<String, List<DBPDataSourceContainer>> resolved =
            InlineFkService.resolveTableConnections(monitor, tableRefs, candidatePool());

        Map<String, DBPDataSourceContainer> byAlias = new LinkedHashMap<>();
        Set<DBPDataSourceContainer> distinct = new LinkedHashSet<>();
        String currentId = context.getDataSource() == null ? null : context.getDataSource().getContainer().getId();
        for (FedPlanner.Table table : plan.tables()) {
            List<DBPDataSourceContainer> candidates = resolved.get(tableRef(table));
            if (candidates == null || candidates.isEmpty()) {
                return null;
            }
            DBPDataSourceContainer chosen = candidates.get(0);
            for (DBPDataSourceContainer candidate : candidates) {
                if (candidate.getId().equals(currentId)) {
                    chosen = candidate;
                    break;
                }
            }
            byAlias.put(table.alias(), chosen);
            distinct.add(chosen);
        }
        if (distinct.size() < 2 || hasBackslashTextKey(new ArrayList<>(distinct), sql)) {
            return null;
        }

        Map<String, FedExecutor.Source> sources = new LinkedHashMap<>();
        for (Map.Entry<String, DBPDataSourceContainer> entry : byAlias.entrySet()) {
            DBPDataSourceContainer container = entry.getValue();
            if (!container.isConnected()) {
                throw new DBException("bağlantı kapalı: " + container.getName());
            }
            DBCExecutionContext sourceContext = DBUtils.getDefaultContext(container.getDataSource(), false);
            // dbeaver-mm K130: kaynak, satır çekmenin yanında kolon keşfini de sağlar
            sources.put(entry.getKey(), new FedExecutor.Source() {
                @Override
                public List<Object[]> fetch(String sourceSql, long maxRows) throws Exception {
                    return fetchRows(monitor, sourceContext, sourceSql, maxRows);
                }

                @Override
                public List<String> columns(FedPlanner.Table table) throws Exception {
                    return fetchColumns(monitor, sourceContext, table);
                }
            });
        }
        return runPlan(plan, sources, codes(plan));
    }

    @Nullable
    private SQLFederation.Result runPlan(
        @NotNull FedPlanner.FedPlan plan,
        @NotNull Map<String, FedExecutor.Source> sources,
        @NotNull List<String> codes
    ) throws DBException {
        FedPlanner.FedPlan effective = plan;
        boolean expanded = false;
        // dbeaver-mm K130: yıldız seçimde kolonları kaynaktan keşfedip planı genişletir
        if (FedExecutor.hasStarSelection(plan)) {
            Map<String, List<String>> columnsByAlias = new LinkedHashMap<>();
            try {
                for (FedPlanner.Table table : plan.tables()) {
                    FedExecutor.Source source = sources.get(table.alias());
                    if (source == null) {
                        return null;
                    }
                    List<String> found = source.columns(table);
                    if (found == null || found.isEmpty()) {
                        return null;
                    }
                    columnsByAlias.put(table.alias(), found);
                }
            } catch (Exception e) {
                return null;
            }
            effective = FedExecutor.expandStar(plan, columnsByAlias);
            if (effective == null) {
                return null;
            }
            expanded = true;
        }
        if (!federable(effective)) {
            return null;
        }
        List<Object[]> combined;
        try {
            // dbeaver-mm K130: genişletilmiş plan aynı yoldan, anahtar itimi olmadan çalışır
            combined = expanded
                ? FedExecutor.run(effective, sources, MAX_ROWS, false)
                : FedExecutor.run(effective, sources, MAX_ROWS);
        } catch (FedExecutor.FedLimitException e) {
            throw new DBException(e.getMessage(), e);
        } catch (Exception e) {
            throw new DBException("Federe yürütme başarısız: " + e.getMessage(), e);
        }

        List<String> columns = effective.selection();
        // dbeaver-mm K127: toplamalı sonuç FedExecutor tarafından seçim sırasında üretilir
        int[] positions = FedExecutor.selectionPositions(effective);
        List<String> display = new ArrayList<>(columns.size());
        for (String column : columns) {
            display.add(displayName(column));
        }
        List<Object[]> rows = new ArrayList<>(combined.size());
        for (Object[] joined : combined) {
            Object[] row = new Object[columns.size()];
            for (int i = 0; i < columns.size(); i++) {
                int index = positions[i];
                row[i] = index >= 0 && index < joined.length ? joined[index] : null;
            }
            rows.add(row);
        }
        return new SQLFederation.Result(display, rows, LABEL_PREFIX + String.join(", ", codes));
    }

    private static boolean federable(@NotNull FedPlanner.FedPlan plan) {
        if (plan.tables().size() < 2 || (plan.where() != null && !plan.where().isBlank())) {
            return false;
        }
        if (plan.joins().size() != plan.tables().size() - 1) {
            return false;
        }
        // dbeaver-mm K131: LEFT/RIGHT join'ler de tek anahtarlıysa federe edilebilir
        Set<String> nullableAliases = new LinkedHashSet<>();
        for (int j = 0; j < plan.joins().size(); j++) {
            FedPlanner.Join join = plan.joins().get(j);
            String type = join.type();
            boolean inner = "INNER".equalsIgnoreCase(type);
            boolean left = "LEFT".equalsIgnoreCase(type);
            boolean right = "RIGHT".equalsIgnoreCase(type);
            if (join.keys().size() != 1 || !(inner || left || right)) {
                return false;
            }
            if (!inner) {
                String newAlias = plan.tables().get(j + 1).alias();
                FedPlanner.Key key = join.keys().get(0);
                String keyLeft = key.left();
                String keyRight = key.right();
                String leftAlias = keyLeft.substring(0, keyLeft.indexOf('.'));
                String rightAlias = keyRight.substring(0, keyRight.indexOf('.'));
                // dbeaver-mm K131: null tarafın WHERE'i kaynağa itilemez; federe değil
                boolean newIsRight = rightAlias.equalsIgnoreCase(newAlias);
                nullableAliases.add(left == newIsRight ? rightAlias : leftAlias);
            }
        }
        for (FedPlanner.Table table : plan.tables()) {
            String pushed = table.pushedWhere();
            if (nullableAliases.contains(table.alias()) && pushed != null && !pushed.isBlank()) {
                return false;
            }
        }
        if (!federableSelection(plan)) {
            return false;
        }
        return federableDistinct(plan);
    }

    /**
     * dbeaver-mm K126: DISTINCT ile ORDER BY yalnız seçilmiş kolonlara bakabilir; standart SQL'de
     * seçilmemiş kolona göre sıralama geçersizdir.
     */
    private static boolean federableDistinct(@NotNull FedPlanner.FedPlan plan) {
        if (!plan.distinct()) {
            return true;
        }
        for (FedPlanner.Order order : plan.orderBy()) {
            boolean selected = false;
            for (String selection : plan.selection()) {
                String base = baseColumn(selection);
                if (base.equalsIgnoreCase(order.column())) {
                    selected = true;
                    break;
                }
            }
            if (!selected) {
                return false;
            }
        }
        return true;
    }

    private static boolean federableSelection(@NotNull FedPlanner.FedPlan plan) {
        // dbeaver-mm K127: GROUP BY ve toplama seçimi FedPlanner tarafından doğrulanır.
        // GROUP BY olmadan toplama (ör. tek başına COUNT(*)) eski davranışla federe değildir.
        // dbeaver-mm K130: genişletilmemiş yıldız seçimi federe edilemez.
        if (FedExecutor.hasStarSelection(plan)) {
            return false;
        }
        if (!plan.groupColumns().isEmpty()) {
            return true;
        }
        for (String selection : plan.selection()) {
            String[] parts = selectionParts(selection);
            if (parts == null || !columnForm(parts[0]) || !knownAlias(parts[0], plan)) {
                return false;
            }
            if (parts[1] != null && !identifier(parts[1])) {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static String[] selectionParts(@NotNull String selection) {
        String text = selection.trim();
        if (text.isEmpty()) {
            return null;
        }
        int space = text.indexOf(' ');
        if (space < 0) {
            return new String[] {text, null};
        }
        String base = text.substring(0, space).trim();
        String rest = text.substring(space + 1).trim();
        int second = rest.indexOf(' ');
        String as = second < 0 ? rest : rest.substring(0, second);
        if (!"AS".equalsIgnoreCase(as)) {
            return null;
        }
        String alias = second < 0 ? "" : rest.substring(second + 1).trim();
        if (alias.isEmpty() || alias.indexOf(' ') >= 0) {
            return null;
        }
        return new String[] {base, alias};
    }

    private static boolean columnForm(@NotNull String base) {
        int dot = base.indexOf('.');
        if (dot <= 0 || dot != base.lastIndexOf('.') || dot == base.length() - 1) {
            return false;
        }
        return identifier(base.substring(0, dot)) && identifier(base.substring(dot + 1));
    }

    private static boolean identifier(@NotNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        char first = text.charAt(0);
        if (!Character.isLetter(first) && first != '_') {
            return false;
        }
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_' && c != '$') {
                return false;
            }
        }
        return true;
    }

    private static boolean knownAlias(@NotNull String qualifiedColumn, @NotNull FedPlanner.FedPlan plan) {
        int dot = qualifiedColumn.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String alias = qualifiedColumn.substring(0, dot);
        for (FedPlanner.Table table : plan.tables()) {
            if (table.alias().equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    @NotNull
    private static String baseColumn(@NotNull String selection) {
        String text = selection.trim();
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space).trim();
    }

    @NotNull
    private static String displayName(@NotNull String selection) {
        String[] parts = selectionParts(selection);
        if (parts != null && parts[1] != null) {
            return parts[1];
        }
        String base = parts == null ? selection.trim() : parts[0];
        int dot = base.lastIndexOf('.');
        return dot >= 0 ? base.substring(dot + 1) : base;
    }

    @NotNull
    private static String tableRef(@NotNull FedPlanner.Table table) {
        return table.code() == null || table.code().isBlank()
            ? table.name()
            : table.code() + "." + table.name();
    }

    @NotNull
    private static List<String> codes(@NotNull FedPlanner.FedPlan plan) {
        List<String> result = new ArrayList<>();
        for (FedPlanner.Table table : plan.tables()) {
            if (table.code() != null && !result.contains(table.code())) {
                result.add(table.code());
            }
        }
        return result;
    }

    @NotNull
    private static List<DBPDataSourceContainer> candidatePool() {
        Set<String> selected = Set.of();
        try {
            IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
            IEditorPart editor = window == null || window.getActivePage() == null
                ? null : window.getActivePage().getActiveEditor();
            if (editor instanceof SQLEditor sqlEditor) {
                selected = sqlEditor.getMmAutoConnectionIds();
            }
        } catch (Throwable ignored) {
            // çalışma tezgâhı yoksa havuz tüm bağlı bağlantılardır
        }
        if (!selected.isEmpty()) {
            return SqlConnectionsBar.candidates(selected);
        }
        List<DBPDataSourceContainer> pool = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                if (container.isConnected()) {
                    pool.add(container);
                }
            }
        }
        return pool;
    }

    private static boolean hasBackslashTextKey(
        @NotNull List<DBPDataSourceContainer> containers,
        @NotNull String sql
    ) {
        if (!sql.contains("\\'")) {
            return false;
        }
        for (DBPDataSourceContainer container : containers) {
            if (container.getDriver() == null || container.getDriver().getId() == null) {
                continue;
            }
            if (container.getDriver().getId().toLowerCase(Locale.ROOT).contains("mysql")) {
                return true;
            }
        }
        return false;
    }

    // dbeaver-mm K130: boş sonuç sorgusunun meta verisinden kolon adlarını okur
    @NotNull
    private static List<String> fetchColumns(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull FedPlanner.Table table
    ) throws Exception {
        String ref = table.code() == null || table.code().isBlank()
            ? table.name()
            : table.code() + "." + table.name();
        String sql = "SELECT * FROM " + ref + " WHERE 1=0";
        List<String> names = new ArrayList<>();
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Federe kolon keşfi")) {
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false)) {
                statement.setLimit(0, 0);
                if (statement.executeStatement()) {
                    try (DBCResultSet rs = statement.openResultSet()) {
                        if (rs != null) {
                            for (DBCAttributeMetaData attribute : rs.getMeta().getAttributes()) {
                                names.add(attribute.getName());
                            }
                        }
                    }
                }
            }
        }
        return names;
    }

    @NotNull
    private static List<Object[]> fetchRows(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBCExecutionContext context,
        @NotNull String sql,
        long maxRows
    ) throws Exception {
        List<Object[]> rows = new ArrayList<>();
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Federe sorgu")) {
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, sql, false, false, false)) {
                statement.setLimit(0, maxRows > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) maxRows);
                if (statement.executeStatement()) {
                    try (DBCResultSet rs = statement.openResultSet()) {
                        if (rs != null) {
                            int width = rs.getMeta().getAttributes().size();
                            while (rows.size() < maxRows && rs.nextRow()) {
                                Object[] row = new Object[width];
                                for (int i = 0; i < width; i++) {
                                    row[i] = rs.getAttributeValue(i);
                                }
                                rows.add(row);
                            }
                        }
                    }
                }
            }
        }
        return rows;
    }
}
