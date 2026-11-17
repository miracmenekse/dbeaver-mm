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
import java.util.HashMap;
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
    }

    /** Bir tablonun çekilen satır sayısı üst sınırı aşarsa fırlatılır. */
    public static final class FedLimitException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FedLimitException(String message) {
            super(message);
        }
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
        List<FedPlanner.Table> tables = plan.tables();
        List<FedPlanner.Join> joins = plan.joins();
        if (tables.size() < 2 || joins.size() != tables.size() - 1) {
            throw new IllegalArgumentException("federe plan en az iki tablo ve tablo sayısı eksi bir join içermeli");
        }

        List<Object[]> acc = null;
        int accWidth = 0;
        Map<String, Integer> offsets = new HashMap<>();
        Map<String, String> keyColumns = new HashMap<>();

        for (int j = 0; j < joins.size(); j++) {
            FedPlanner.Join join = joins.get(j);
            if (!"INNER".equalsIgnoreCase(join.type()) || join.keys().size() != 1) {
                throw new IllegalArgumentException("yalnız tek anahtarlı INNER join desteklenir: " + join.type());
            }
            FedPlanner.Key key = join.keys().get(0);
            String leftAlias = aliasOf(key.left());
            String rightAlias = aliasOf(key.right());

            FedPlanner.Table right = tables.get(j + 1);
            if (!right.alias().equals(rightAlias)) {
                throw new IllegalArgumentException("join sırası tablo sırasıyla uyuşmuyor: " + rightAlias);
            }

            if (acc == null) {
                FedPlanner.Table first = tables.get(0);
                if (!first.alias().equals(leftAlias)) {
                    throw new IllegalArgumentException("ilk join ilk tabloyla başlamalı: " + leftAlias);
                }
                if (blank(first.pushedWhere()) && !blank(right.pushedWhere())) {
                    // filtreli taraf sürücü: önce o çekilir, anahtarlarıyla ilk tablo IN ile sorgulanır
                    List<Object[]> driverRows = fetchTable(source(sourceByAlias, right.alias()), right, key.right(), maxRows);
                    if (driverRows.isEmpty()) {
                        return new ArrayList<>();
                    }
                    List<Object[]> probeRows = fetchKeys(
                        source(sourceByAlias, first.alias()),
                        first,
                        key.left(),
                        keyValues(driverRows, 0),
                        maxRows
                    );
                    if (probeRows.isEmpty()) {
                        return new ArrayList<>();
                    }
                    acc = FedJoin.join(probeRows, driverRows, new int[] {0}, new int[] {0}, false, 0);
                    offsets.put(leftAlias, 0);
                    keyColumns.put(leftAlias, key.left());
                    offsets.put(rightAlias, probeRows.get(0).length);
                    keyColumns.put(rightAlias, key.right());
                    accWidth = probeRows.get(0).length + driverRows.get(0).length;
                    if (acc.isEmpty()) {
                        return acc;
                    }
                    continue;
                }
                List<Object[]> rows = fetchTable(source(sourceByAlias, first.alias()), first, key.left(), maxRows);
                if (rows.isEmpty()) {
                    return new ArrayList<>();
                }
                acc = rows;
                accWidth = rows.get(0).length;
                offsets.put(leftAlias, 0);
                keyColumns.put(leftAlias, key.left());
            }

            Integer leftOffset = offsets.get(leftAlias);
            if (leftOffset == null || !key.left().equals(keyColumns.get(leftAlias))) {
                throw new IllegalArgumentException("tablo tek bir anahtar sütunuyla bağlanabilir: " + key.left());
            }

            List<Object[]> rightRows = fetchKeys(
                source(sourceByAlias, right.alias()),
                right,
                key.right(),
                keyValues(acc, leftOffset),
                maxRows
            );
            if (rightRows.isEmpty()) {
                return new ArrayList<>();
            }

            acc = FedJoin.join(acc, rightRows, new int[] {leftOffset}, new int[] {0}, false, 0);
            offsets.put(rightAlias, accWidth);
            keyColumns.put(rightAlias, key.right());
            accWidth += rightRows.get(0).length;
            if (acc.isEmpty()) {
                return acc;
            }
        }
        return acc;
    }

    private static List<Object[]> fetchTable(Source source, FedPlanner.Table table, String keyColumn, long maxRows)
        throws Exception {
        String sql = selectPrefix(table, keyColumn) + where(table.pushedWhere());
        List<Object[]> rows = nonNull(source.fetch(sql, probe(maxRows)));
        check(table, rows.size(), maxRows);
        return rows;
    }

    private static List<Object[]> fetchKeys(
        Source source,
        FedPlanner.Table table,
        String keyColumn,
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
            String sql = selectPrefix(table, keyColumn) + " WHERE " + filter;
            out.addAll(nonNull(source.fetch(sql, probe(maxRows - out.size()))));
            check(table, out.size(), maxRows);
        }
        return out;
    }

    private static String selectPrefix(FedPlanner.Table table, String keyColumn) {
        return "SELECT " + keyColumn + ", " + table.alias() + ".* FROM " + table.name() + " " + table.alias();
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
