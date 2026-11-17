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
            sources.put(entry.getKey(), (sourceSql, maxRows) -> fetchRows(monitor, sourceContext, sourceSql, maxRows));
        }
        return runPlan(plan, sources, codes(plan));
    }

    @Nullable
    private SQLFederation.Result runPlan(
        @NotNull FedPlanner.FedPlan plan,
        @NotNull Map<String, FedExecutor.Source> sources,
        @NotNull List<String> codes
    ) throws DBException {
        if (!federable(plan)) {
            return null;
        }
        List<Object[]> combined;
        try {
            combined = FedExecutor.run(plan, sources, MAX_ROWS);
        } catch (FedExecutor.FedLimitException e) {
            throw new DBException(e.getMessage(), e);
        } catch (Exception e) {
            throw new DBException("Federe yürütme başarısız: " + e.getMessage(), e);
        }

        List<String> columns = plan.selection();
        Map<String, List<Integer>> byAlias = new LinkedHashMap<>();
        for (int i = 0; i < columns.size(); i++) {
            String alias = aliasOf(columns.get(i), plan);
            byAlias.computeIfAbsent(alias, key -> new ArrayList<>()).add(i);
        }
        List<Object[]> rows = new ArrayList<>(combined.size());
        for (Object[] joined : combined) {
            Object[] row = new Object[columns.size()];
            int offset = 0;
            for (FedPlanner.Table table : plan.tables()) {
                // K121: her blok anahtar sütunuyla başlar; anahtar seçim listesinde yoksa atlanır
                offset++;
                for (int index : byAlias.getOrDefault(table.alias(), List.of())) {
                    row[index] = offset < joined.length ? joined[offset] : null;
                    offset++;
                }
            }
            rows.add(row);
        }
        return new SQLFederation.Result(columns, rows, LABEL_PREFIX + String.join(", ", codes));
    }

    private static boolean federable(@NotNull FedPlanner.FedPlan plan) {
        if (plan.tables().size() < 2 || (plan.where() != null && !plan.where().isBlank())) {
            return false;
        }
        if (plan.joins().size() != plan.tables().size() - 1) {
            return false;
        }
        for (FedPlanner.Join join : plan.joins()) {
            if (!"INNER".equalsIgnoreCase(join.type()) || join.keys().size() != 1) {
                return false;
            }
        }
        return true;
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
    private static String aliasOf(@NotNull String selection, @NotNull FedPlanner.FedPlan plan) {
        int dot = selection.indexOf('.');
        if (dot > 0) {
            String prefix = selection.substring(0, dot);
            for (FedPlanner.Table table : plan.tables()) {
                if (table.alias().equalsIgnoreCase(prefix)) {
                    return table.alias();
                }
            }
            return prefix;
        }
        return plan.tables().isEmpty() ? "" : plan.tables().get(0).alias();
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
