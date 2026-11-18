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
package org.jkiss.dbeaver.ui.inlinefkpicker.fed;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * dbeaver-mm K121: federe planı anahtar aktarımıyla çalıştıran saf yürütücü.
 * <p>
 * Sürücü tablo (ilk tablo, WHERE'i itilmiş olabilir) çekilir; join anahtar değerleri
 * tekilleştirilip {@value #CHUNK}'lık parçalar hâlinde diğer kaynağa
 * {@code anahtar IN (...)} koşulu olarak gönderilir ve sonuçlar {@link FedJoin} ile
 * birleştirilir. Her tablo bloğunun 0. sütunu o tablonun join anahtarıdır: sorgu
 * anahtar sütununu önce seçer, bu yüzden birleşik satırda anahtar her blokta ilk
 * sütundur. Yalnız tek anahtarlı INNER join zinciri desteklenir; sorgu metni
 * kaynağa aittir, bu sınıf yalnız {@link Source#fetch} çağırır.
 */
public final class FedExecutor {

    private static final int CHUNK = 1000;

    private FedExecutor() {
    }

    /** Bir bağlantıda SQL çalıştırıp satırları döndüren kaynak. */
    public interface Source {
        List<Object[]> fetch(String sql, long maxRows) throws Exception;

        // dbeaver-mm K130: yıldız genişletmesi için tablo kolon adlarını isteğe bağlı olarak sağlar
        default List<String> columns(FedPlanner.Table table) throws Exception {
            return List.of();
        }
    }

    /** Bir tablonun çekilen satır sayısı üst sınırı aşarsa fırlatılır. */
    public static final class FedLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FedLimitException(String message) {
            super(message);
        }
    }

    /** dbeaver-mm K128: ON ile bağlanmayan (kartezyen) ya da döngüsel tablo grafiği desteklenmez. */
    public static final class FedUnsupportedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FedUnsupportedException(String message) {
            super(message);
        }
    }

    /** dbeaver-mm K128: iki tabloyu ON eşitliğiyle bağlayan yönsüz kenar. */
    private record Edge(String leftAlias, String leftColumn, String rightAlias, String rightColumn) {
        String otherAlias(String alias) {
            return leftAlias.equalsIgnoreCase(alias) ? rightAlias : leftAlias;
        }

        String columnFor(String alias) {
            return leftAlias.equalsIgnoreCase(alias) ? leftColumn : rightColumn;
        }
    }

    /** dbeaver-mm K128: zincire eklenen tablo, bağlandığı tablo ve iki taraftaki anahtar sütunları. */
    private record Step(FedPlanner.Table table, String fromAlias, String fromColumn, String keyColumn) {
    }

    /**
     * Federe planı çalıştırır.
     *
     * @param plan federe plan (tablo zinciri ve tek anahtarlı INNER joinler)
     * @param sourceByAlias tablo alias'ından kaynağa eşleme
     * @param maxRows tek bir tablodan alınabilecek en fazla satır sayısı
     * @return birleşik satırlar; her tablo bloğunun 0. sütunu join anahtarıdır
     * @throws FedLimitException bir tablo {@code maxRows} satırı aşarsa
     */
    public static List<Object[]> run(
        FedPlanner.FedPlan plan,
        Map<String, Source> sourceByAlias,
        long maxRows
    ) throws Exception {
        return run(plan, sourceByAlias, maxRows, true);
    }

    // dbeaver-mm K130: genişletilmiş planda anahtarlar zaten bilindiğinden IN itimi yapılmaz
    public static List<Object[]> run(
        FedPlanner.FedPlan plan,
        Map<String, Source> sourceByAlias,
        long maxRows,
        boolean pushKeys
    ) throws Exception {
        List<FedPlanner.Table> tables = plan.tables();
        List<FedPlanner.Join> joins = plan.joins();
        if (tables.size() < 2) {
            throw new IllegalArgumentException("federe plan en az iki tablo içermeli");
        }
        if (joins.size() != tables.size() - 1) {
            // dbeaver-mm K128: döngüsel ya da bağsız tablo grafiği desteklenmez
            throw new FedUnsupportedException("ON koşulu olmayan tablo");
        }

        Map<String, FedPlanner.Table> tableByAlias = new LinkedHashMap<>();
        for (FedPlanner.Table table : tables) {
            tableByAlias.put(table.alias(), table);
        }
        Map<String, List<Edge>> graph = new LinkedHashMap<>();
        for (FedPlanner.Join join : joins) {
            if (!"INNER".equalsIgnoreCase(join.type()) || join.keys().size() != 1) {
                // dbeaver-mm K128: yalnız tek anahtarlı INNER join zinciri desteklenir
                throw new FedUnsupportedException("ON koşulu olmayan tablo");
            }
            FedPlanner.Key key = join.keys().get(0);
            String leftAlias = aliasOf(key.left());
            String rightAlias = aliasOf(key.right());
            if (!tableByAlias.containsKey(leftAlias) || !tableByAlias.containsKey(rightAlias)
                || leftAlias.equalsIgnoreCase(rightAlias)) {
                throw new FedUnsupportedException("ON koşulu olmayan tablo");
            }
            graph.computeIfAbsent(leftAlias, k -> new ArrayList<>())
                .add(new Edge(leftAlias, key.left(), rightAlias, key.right()));
            graph.computeIfAbsent(rightAlias, k -> new ArrayList<>())
                .add(new Edge(rightAlias, key.right(), leftAlias, key.left()));
        }

        // dbeaver-mm K128: filtreli tablo (varsa) sürücü olur, ardından ON komşuları genişlik öncelikli gezilir
        FedPlanner.Table start = tables.get(0);
        for (FedPlanner.Table table : tables) {
            if (!blank(table.pushedWhere())) {
                start = table;
                break;
            }
        }
        List<Step> steps = new ArrayList<>();
        steps.add(new Step(start, null, null, null));
        Set<String> included = new LinkedHashSet<>();
        included.add(start.alias());
        for (int i = 0; i < steps.size(); i++) {
            String current = steps.get(i).table().alias();
            for (Edge edge : graph.getOrDefault(current, List.of())) {
                String next = edge.otherAlias(current);
                if (!included.add(next)) {
                    continue;
                }
                steps.add(new Step(
                    tableByAlias.get(next),
                    current,
                    edge.columnFor(current),
                    edge.columnFor(next)
                ));
            }
        }
        if (included.size() != tables.size()) {
            // dbeaver-mm K128: bağsız (kartezyen) tablo grafiği desteklenmez
            throw new FedUnsupportedException("ON koşulu olmayan tablo");
        }

        // dbeaver-mm K128: birleşik satırlar tablo sırasını korur; blok sütunları blocks() ile aynıdır
        Map<String, List<String>> blockByAlias = blocks(plan);
        Map<String, Integer> starts = new HashMap<>();
        List<Object[]> acc = null;

        for (int s = 0; s < steps.size(); s++) {
            Step step = steps.get(s);
            FedPlanner.Table table = step.table();
            List<String> block = blockByAlias.get(table.alias());
            if (s == 0) {
                acc = fetchTable(source(sourceByAlias, table.alias()), table, block, maxRows);
                if (acc.isEmpty()) {
                    return new ArrayList<>();
                }
                starts.put(table.alias(), 0);
                continue;
            }

            Integer fromStart = starts.get(step.fromAlias());
            int fromIndex = indexOfIgnoreCase(blockByAlias.get(step.fromAlias()), step.fromColumn());
            int keyIndex = indexOfIgnoreCase(block, step.keyColumn());
            if (fromStart == null || fromIndex < 0 || keyIndex < 0) {
                // dbeaver-mm K128: bağlantı anahtarı blokta yoksa zincir kurulamaz
                throw new FedUnsupportedException("ON koşulu olmayan tablo: " + table.alias());
            }

            // dbeaver-mm K128: önceki ara sonucun anahtarları sıradaki kaynağa IN ile itilir
            Set<Object> keys = keyValues(acc, fromStart + fromIndex);
            if (keys.isEmpty()) {
                return new ArrayList<>();
            }
            // dbeaver-mm K130: yıldız genişletilmiş planda tüm satırlar çekilir, eşleşme bellekte kurulur
            List<Object[]> rows = pushKeys
                ? fetchKeys(
                    source(sourceByAlias, table.alias()),
                    table,
                    step.keyColumn(),
                    block,
                    keys,
                    maxRows
                )
                : fetchTable(source(sourceByAlias, table.alias()), table, block, maxRows);
            if (rows.isEmpty()) {
                return new ArrayList<>();
            }

            int width = rows.get(0).length;
            acc = FedJoin.join(acc, rows, new int[] {fromStart + fromIndex}, new int[] {keyIndex}, false, 0);
            if (acc.isEmpty()) {
                return acc;
            }
            int insertPos = tableOrderOffset(tables, blockByAlias, starts.keySet(), table.alias());
            acc = insertBlock(acc, insertPos, width);
            starts.put(table.alias(), insertPos);
            refreshStarts(tables, blockByAlias, starts);
        }
        // dbeaver-mm K127: GROUP BY ve toplamalar birleşimden sonra bellekte uygulanır
        if (aggregated(plan)) {
            acc = aggregate(plan, acc);
        }
        // dbeaver-mm K125: DISTINCT, ORDER BY ve LIMIT/OFFSET bellekte uygulanır
        // dbeaver-mm K126: DISTINCT yalnız seçilen kolonların birleşik satırdaki konumlarına bakar
        List<Object[]> result = plan.distinct() ? FedSort.distinct(acc, selectionPositions(plan)) : acc;
        if (!plan.orderBy().isEmpty()) {
            int[] sortColumns = sortColumns(plan);
            boolean[] descending = new boolean[plan.orderBy().size()];
            for (int i = 0; i < descending.length; i++) {
                descending[i] = plan.orderBy().get(i).descending();
            }
            result = FedSort.sort(result, sortColumns, descending);
        }
        if (plan.limit() != null || plan.offset() != null) {
            int limit = plan.limit() == null ? Integer.MAX_VALUE : clamp(plan.limit());
            int offset = plan.offset() == null ? 0 : clamp(plan.offset());
            result = FedSort.limit(result, limit, offset);
        }
        return result;
    }

    /** dbeaver-mm K128: bloktaki sütunun konumu, yoksa -1. */
    private static int indexOfIgnoreCase(List<String> columns, String column) {
        if (columns == null || column == null) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).equalsIgnoreCase(column)) {
                return i;
            }
        }
        return -1;
    }

    /** dbeaver-mm K128: yeni tablonun tablo sırasına göre ekleneceği sütun konumu. */
    private static int tableOrderOffset(
        List<FedPlanner.Table> tables,
        Map<String, List<String>> blockByAlias,
        Set<String> included,
        String alias
    ) {
        int cursor = 0;
        for (FedPlanner.Table table : tables) {
            if (table.alias().equals(alias)) {
                return cursor;
            }
            if (included.contains(table.alias())) {
                cursor += blockByAlias.get(table.alias()).size();
            }
        }
        throw new IllegalArgumentException("tablo bulunamadı: " + alias);
    }

    /** dbeaver-mm K128: dahil tabloların blok başlangıçlarını tablo sırasına göre tazeler. */
    private static void refreshStarts(
        List<FedPlanner.Table> tables,
        Map<String, List<String>> blockByAlias,
        Map<String, Integer> starts
    ) {
        int cursor = 0;
        for (FedPlanner.Table table : tables) {
            if (!starts.containsKey(table.alias())) {
                continue;
            }
            starts.put(table.alias(), cursor);
            cursor += blockByAlias.get(table.alias()).size();
        }
    }

    /** dbeaver-mm K128: join sonunda eklenen bloğu tablo sırasındaki yerine taşır. */
    private static List<Object[]> insertBlock(List<Object[]> rows, int insertPos, int blockWidth) {
        List<Object[]> result = new ArrayList<>(rows.size());
        for (Object[] row : rows) {
            int from = row.length - blockWidth;
            Object[] moved = new Object[row.length];
            System.arraycopy(row, 0, moved, 0, insertPos);
            System.arraycopy(row, from, moved, insertPos, blockWidth);
            System.arraycopy(row, insertPos, moved, insertPos + blockWidth, from - insertPos);
            result.add(moved);
        }
        return result;
    }

    private static int clamp(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    /** dbeaver-mm K127: planda GROUP BY veya toplama var mı. */
    public static boolean aggregated(FedPlanner.FedPlan plan) {
        return !plan.groupColumns().isEmpty() || !plan.aggregates().isEmpty();
    }

    private static List<Object[]> aggregate(FedPlanner.FedPlan plan, List<Object[]> rows) {
        int groupCount = plan.groupColumns().size();
        int[] groupPositions = new int[groupCount];
        for (int i = 0; i < groupCount; i++) {
            groupPositions[i] = positionOf(plan, plan.groupColumns().get(i));
        }
        FedAggregate.AggSpec[] specs = new FedAggregate.AggSpec[plan.aggregates().size()];
        for (int i = 0; i < specs.length; i++) {
            FedPlanner.Aggregate item = plan.aggregates().get(i);
            int column = item.column() == null ? -1 : positionOf(plan, item.column());
            specs[i] = new FedAggregate.AggSpec(column, FedAggregate.AggType.valueOf(item.function()));
        }
        List<Object[]> grouped = FedAggregate.group(rows, groupPositions, specs);
        int[] groupBySelection = new int[plan.selection().size()];
        int[] aggregateBySelection = new int[plan.selection().size()];
        Arrays.fill(groupBySelection, -1);
        Arrays.fill(aggregateBySelection, -1);
        for (int i = 0; i < specs.length; i++) {
            aggregateBySelection[plan.aggregates().get(i).selectionIndex()] = i;
        }
        for (int i = 0; i < plan.selection().size(); i++) {
            if (aggregateBySelection[i] >= 0) {
                continue;
            }
            String base = baseColumn(plan.selection().get(i));
            for (int g = 0; g < groupCount; g++) {
                if (plan.groupColumns().get(g).equalsIgnoreCase(base)) {
                    groupBySelection[i] = g;
                    break;
                }
            }
        }
        List<Object[]> result = new ArrayList<>(grouped.size());
        for (Object[] row : grouped) {
            Object[] out = new Object[plan.selection().size()];
            for (int i = 0; i < out.length; i++) {
                if (aggregateBySelection[i] >= 0) {
                    out[i] = row[groupCount + aggregateBySelection[i]];
                } else if (groupBySelection[i] >= 0) {
                    out[i] = row[groupBySelection[i]];
                }
            }
            result.add(out);
        }
        return result;
    }

    private static int positionOf(FedPlanner.FedPlan plan, String column) {
        Map<String, List<String>> blocks = blocks(plan);
        int cursor = 0;
        for (FedPlanner.Table table : plan.tables()) {
            List<String> block = blocks.getOrDefault(table.alias(), List.of());
            for (int i = 0; i < block.size(); i++) {
                if (block.get(i).equalsIgnoreCase(column)) {
                    return cursor + i;
                }
            }
            cursor += block.size();
        }
        throw new IllegalArgumentException("toplama sütunu bulunamadı: " + column);
    }

    private static int selectionIndex(FedPlanner.FedPlan plan, String column) {
        for (int i = 0; i < plan.selection().size(); i++) {
            if (baseColumn(plan.selection().get(i)).equalsIgnoreCase(column)) {
                return i;
            }
        }
        throw new IllegalArgumentException("ORDER BY seçim sütunu bulunamadı: " + column);
    }

    private static int[] sortColumns(FedPlanner.FedPlan plan) {
        if (aggregated(plan)) {
            int[] result = new int[plan.orderBy().size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = selectionIndex(plan, plan.orderBy().get(i).column());
            }
            return result;
        }
        Map<String, List<String>> blocks = blocks(plan);
        Map<String, Integer> starts = new HashMap<>();
        int cursor = 0;
        for (FedPlanner.Table table : plan.tables()) {
            starts.put(table.alias(), cursor);
            cursor += blocks.getOrDefault(table.alias(), List.of()).size();
        }
        int[] result = new int[plan.orderBy().size()];
        for (int i = 0; i < result.length; i++) {
            String column = plan.orderBy().get(i).column();
            int dot = column.indexOf('.');
            String alias = dot > 0 ? column.substring(0, dot) : column;
            Integer start = starts.get(alias);
            if (start == null) {
                throw new IllegalArgumentException("ORDER BY bilinmeyen tablo: " + column);
            }
            List<String> block = blocks.getOrDefault(alias, List.of());
            int index = -1;
            for (int j = 0; j < block.size(); j++) {
                if (block.get(j).equalsIgnoreCase(column)) {
                    index = j;
                    break;
                }
            }
            if (index < 0) {
                throw new IllegalArgumentException("ORDER BY sütunu bulunamadı: " + column);
            }
            result[i] = start + index;
        }
        return result;
    }

    /**
     * dbeaver-mm K124: bir tablo bloğunun seçim listesi. İlk sütun join anahtarıdır; ardından
     * plan seçiminde o tabloya ait kolonlar seçim sırasıyla, yinelenenler bir kez gelir.
     */
    public static List<String> blockColumns(FedPlanner.FedPlan plan, String alias, String keyColumn) {
        List<String> columns = new ArrayList<>();
        if (keyColumn != null && !keyColumn.isBlank()) {
            columns.add(keyColumn);
        }
        for (String selection : plan.selection()) {
            String column = baseColumn(selection);
            int dot = column.indexOf('.');
            if (dot <= 0 || !column.substring(0, dot).equalsIgnoreCase(alias)) {
                continue;
            }
            boolean exists = false;
            for (String existing : columns) {
                if (existing.equalsIgnoreCase(column)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                columns.add(column);
            }
        }
        // dbeaver-mm K127: GROUP BY ve toplama sütunları da blokta bulunmalı
        for (String groupColumn : plan.groupColumns()) {
            addColumn(columns, groupColumn, alias);
        }
        for (FedPlanner.Aggregate aggregate : plan.aggregates()) {
            if (aggregate.column() != null) {
                addColumn(columns, aggregate.column(), alias);
            }
        }
        // dbeaver-mm K125: ORDER BY sütunu seçilmemiş olsa da sıralama için çekilir
        for (FedPlanner.Order order : plan.orderBy()) {
            String column = order.column();
            int dot = column.indexOf('.');
            if (dot <= 0 || !column.substring(0, dot).equalsIgnoreCase(alias)) {
                continue;
            }
            boolean exists = false;
            for (String existing : columns) {
                if (existing.equalsIgnoreCase(column)) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                columns.add(column);
            }
        }
        return columns;
    }

    private static void addColumn(List<String> columns, String column, String alias) {
        int dot = column.indexOf('.');
        if (dot <= 0 || !column.substring(0, dot).equalsIgnoreCase(alias)) {
            return;
        }
        for (String existing : columns) {
            if (existing.equalsIgnoreCase(column)) {
                return;
            }
        }
        columns.add(column);
    }

    /** dbeaver-mm K124: tablo alias'ından blok seçim listesine eşleme. */
    public static Map<String, List<String>> blocks(FedPlanner.FedPlan plan) {
        // dbeaver-mm K128: her tablonun tüm join anahtarları blokta bulunur; ilki blok başıdır
        Map<String, List<String>> keys = new LinkedHashMap<>();
        for (FedPlanner.Join join : plan.joins()) {
            for (FedPlanner.Key key : join.keys()) {
                keys.computeIfAbsent(aliasOf(key.left()), k -> new ArrayList<>()).add(key.left());
                keys.computeIfAbsent(aliasOf(key.right()), k -> new ArrayList<>()).add(key.right());
            }
        }
        Map<String, List<String>> result = new LinkedHashMap<>();
        for (FedPlanner.Table table : plan.tables()) {
            List<String> tableKeys = keys.getOrDefault(table.alias(), List.of());
            String primary = tableKeys.isEmpty() ? null : tableKeys.get(0);
            List<String> block = blockColumns(plan, table.alias(), primary);
            for (int i = 1; i < tableKeys.size(); i++) {
                addColumn(block, tableKeys.get(i), table.alias());
            }
            result.put(table.alias(), block);
        }
        return result;
    }

    /** dbeaver-mm K126: seçim listesindeki kolonların birleşik satırdaki konumları. */
    public static int[] selectionPositions(FedPlanner.FedPlan plan) {
        // dbeaver-mm K127: toplamalı sonuç zaten seçim sırasında üretilir
        if (aggregated(plan)) {
            int[] identity = new int[plan.selection().size()];
            for (int i = 0; i < identity.length; i++) {
                identity[i] = i;
            }
            return identity;
        }
        Map<String, List<String>> blocks = blocks(plan);
        int[] result = new int[plan.selection().size()];
        for (int i = 0; i < result.length; i++) {
            String column = baseColumn(plan.selection().get(i));
            int dot = column.indexOf('.');
            String alias = dot > 0 ? column.substring(0, dot) : column;
            int cursor = 0;
            int position = -1;
            for (FedPlanner.Table table : plan.tables()) {
                List<String> block = blocks.getOrDefault(table.alias(), List.of());
                if (position < 0 && table.alias().equalsIgnoreCase(alias)) {
                    int index = -1;
                    for (int j = 0; j < block.size(); j++) {
                        if (block.get(j).equalsIgnoreCase(column)) {
                            index = j;
                            break;
                        }
                    }
                    if (index < 0) {
                        throw new IllegalArgumentException("seçim sütunu bulunamadı: " + column);
                    }
                    position = cursor + index;
                }
                cursor += block.size();
            }
            if (position < 0) {
                throw new IllegalArgumentException("seçim bilinmeyen tablo: " + column);
            }
            result[i] = position;
        }
        return result;
    }

    /** dbeaver-mm K130: seçimde genişletilmesi gereken yıldız öğesi var mı? */
    public static boolean hasStarSelection(FedPlanner.FedPlan plan) {
        for (String item : plan.selection()) {
            if (item.indexOf('(') < 0 && starAlias(baseColumn(item)) != null) {
                return true;
            }
        }
        return false;
    }

    /** dbeaver-mm K130: SELECT * ve alias.* seçimini açık kolon listesine genişletir. */
    public static FedPlanner.FedPlan expandStar(
        FedPlanner.FedPlan plan,
        Map<String, List<String>> columnsByAlias
    ) {
        List<String> selection = new ArrayList<>();
        int[] mapping = new int[plan.selection().size()];
        boolean changed = false;
        for (int i = 0; i < plan.selection().size(); i++) {
            String item = plan.selection().get(i);
            mapping[i] = selection.size();
            String alias = item.indexOf('(') < 0 ? starAlias(baseColumn(item)) : null;
            if (alias == null) {
                selection.add(item);
                continue;
            }
            List<String> aliases = new ArrayList<>();
            if ("*".equals(alias)) {
                for (FedPlanner.Table table : plan.tables()) {
                    aliases.add(table.alias());
                }
            } else {
                aliases.add(alias);
            }
            int added = 0;
            for (String tableAlias : aliases) {
                List<String> columns = columnsByAlias == null ? null : columnsByAlias.get(tableAlias);
                if (columns == null || columns.isEmpty()) {
                    return null;
                }
                for (String column : columns) {
                    selection.add(tableAlias + "." + column);
                    added++;
                }
            }
            if (added == 0) {
                return null;
            }
            changed = true;
        }
        if (!changed) {
            return plan;
        }
        List<FedPlanner.Aggregate> aggregates = new ArrayList<>();
        for (FedPlanner.Aggregate aggregate : plan.aggregates()) {
            int index = aggregate.selectionIndex();
            if (index < 0 || index >= mapping.length) {
                return null;
            }
            aggregates.add(new FedPlanner.Aggregate(aggregate.function(), aggregate.column(), mapping[index]));
        }
        return new FedPlanner.FedPlan(
            plan.tables(), plan.joins(), plan.where(), List.copyOf(selection), plan.orderBy(),
            plan.limit(), plan.offset(), plan.distinct(), plan.groupColumns(), List.copyOf(aggregates)
        );
    }

    private static String starAlias(String column) {
        if ("*".equals(column)) {
            return "*";
        }
        if (column.endsWith(".*")) {
            String alias = column.substring(0, column.length() - 2);
            if (!alias.isEmpty() && alias.indexOf('.') < 0) {
                return alias;
            }
        }
        return null;
    }

    private static String baseColumn(String selection) {
        String text = selection.trim();
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(0, space).trim();
    }

    private static List<Object[]> fetchTable(Source source, FedPlanner.Table table, List<String> columns, long maxRows)
        throws Exception {
        String sql = selectPrefix(table, columns) + where(table.pushedWhere());
        List<Object[]> rows = nonNull(source.fetch(sql, probe(maxRows)));
        check(table, rows.size(), maxRows);
        return rows;
    }

    private static List<Object[]> fetchKeys(
        Source source,
        FedPlanner.Table table,
        String keyColumn,
        List<String> columns,
        Set<Object> keys,
        long maxRows
    ) throws Exception {
        List<Object[]> out = new ArrayList<>();
        List<Object> list = new ArrayList<>(keys);
        for (int from = 0; from < list.size(); from += CHUNK) {
            List<String> literals = new ArrayList<>();
            for (Object value : list.subList(from, Math.min(from + CHUNK, list.size()))) {
                literals.add(literal(value));
            }
            String filter = keyColumn + " IN (" + String.join(", ", literals) + ")";
            if (!blank(table.pushedWhere())) {
                filter = "(" + table.pushedWhere() + ") AND " + filter;
            }
            String sql = selectPrefix(table, columns) + " WHERE " + filter;
            out.addAll(nonNull(source.fetch(sql, probe(maxRows - out.size()))));
            check(table, out.size(), maxRows);
        }
        return out;
    }

    private static String selectPrefix(FedPlanner.Table table, List<String> columns) {
        return "SELECT " + String.join(", ", columns) + " FROM " + table.name() + " " + table.alias();
    }

    private static String where(String pushed) {
        return blank(pushed) ? "" : " WHERE " + pushed;
    }

    /** Kaynak üst sınırı kendi keserse aşımı görebilmek için bir fazla satır ister. */
    private static long probe(long budget) {
        return budget == Long.MAX_VALUE ? budget : budget + 1;
    }

    private static Set<Object> keyValues(List<Object[]> rows, int offset) {
        Set<Object> keys = new LinkedHashSet<>();
        for (Object[] row : rows) {
            if (offset < row.length && row[offset] != null) {
                keys.add(row[offset]);
            }
        }
        return keys;
    }

    private static String literal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Number) {
            return value.toString();
        }
        return "'" + String.valueOf(value).replace("'", "''") + "'";
    }

    private static void check(FedPlanner.Table table, long count, long maxRows) {
        if (count > maxRows) {
            throw new FedLimitException(String.format(
                Locale.ROOT,
                "%s (%s) tablosu %d satır getirdi, üst sınır %d",
                table.name(),
                table.alias(),
                count,
                maxRows
            ));
        }
    }

    private static Source source(Map<String, Source> sourceByAlias, String alias) {
        Source source = sourceByAlias.get(alias);
        if (source == null) {
            throw new IllegalArgumentException("kaynak yok: " + alias);
        }
        return source;
    }

    private static String aliasOf(String qualifiedColumn) {
        int dot = qualifiedColumn.indexOf('.');
        if (dot <= 0) {
            throw new IllegalArgumentException("nitelikli sütun bekleniyor: " + qualifiedColumn);
        }
        return qualifiedColumn.substring(0, dot);
    }

    private static List<Object[]> nonNull(List<Object[]> rows) {
        return rows == null ? new ArrayList<>() : rows;
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }
}
