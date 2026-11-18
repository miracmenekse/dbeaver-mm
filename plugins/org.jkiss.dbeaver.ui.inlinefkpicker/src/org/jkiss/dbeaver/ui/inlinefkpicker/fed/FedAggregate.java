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
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbeaver-mm K127: bellekte GROUP BY ve toplama işlemleri.
 * <p>
 * Satırlar grup sütunlarına göre gruplanır; her toplama için null değerler yok sayılır.
 * {@code COUNT(*)} tüm satırları sayar, boş küme için {@code SUM}/{@code MIN}/{@code MAX}/{@code AVG}
 * {@code null}, {@code COUNT} ise 0 döner. Grup anahtarı null ise ayrı bir grup olur.
 * Sonuç satırı önce grup anahtarlarını, ardından toplamaları taşır.
 */
public final class FedAggregate {

    private static final int AVG_SCALE = 10;

    private FedAggregate() {
    }

    /** Toplama türü. */
    public enum AggType {
        COUNT_STAR,
        COUNT,
        SUM,
        MIN,
        MAX,
        AVG
    }

    /** Bir toplama: hedef sütun (COUNT(*) için -1) ve tür. */
    public record AggSpec(int column, AggType type) {
    }

    /**
     * Satırları gruplar ve toplamaları hesaplar.
     *
     * @param rows kaynak satırlar
     * @param groupCols grup anahtarı sütun konumları; boşsa tek global grup
     * @param aggs uygulanacak toplamalar
     * @return grup anahtarları ve toplama sonuçlarından oluşan satırlar
     */
    public static List<Object[]> group(List<Object[]> rows, int[] groupCols, AggSpec[] aggs) {
        List<Object[]> source = rows == null ? List.of() : rows;
        AggSpec[] specs = aggs == null ? new AggSpec[0] : aggs;
        int[] keys = groupCols == null ? new int[0] : groupCols;
        Map<List<Object>, Group> groups = new LinkedHashMap<>();
        if (keys.length == 0) {
            groups.put(List.of(), new Group(new Object[0], specs));
        }
        for (Object[] row : source) {
            Object[] key = new Object[keys.length];
            for (int i = 0; i < keys.length; i++) {
                key[i] = keys[i] >= 0 && keys[i] < row.length ? row[keys[i]] : null;
            }
            List<Object> lookup = Arrays.asList(key);
            Group group = groups.get(lookup);
            if (group == null) {
                group = new Group(key, specs);
                groups.put(lookup, group);
            }
            group.add(row);
        }
        List<Object[]> result = new ArrayList<>(groups.size());
        for (Group group : groups.values()) {
            result.add(group.result());
        }
        return result;
    }

    private static final class Group {
        private final Object[] keys;
        private final Accumulator[] accumulators;

        private Group(Object[] keys, AggSpec[] specs) {
            this.keys = keys;
            this.accumulators = new Accumulator[specs.length];
            for (int i = 0; i < specs.length; i++) {
                accumulators[i] = new Accumulator(specs[i]);
            }
        }

        private void add(Object[] row) {
            for (Accumulator accumulator : accumulators) {
                accumulator.add(row);
            }
        }

        private Object[] result() {
            Object[] row = new Object[keys.length + accumulators.length];
            System.arraycopy(keys, 0, row, 0, keys.length);
            for (int i = 0; i < accumulators.length; i++) {
                row[keys.length + i] = accumulators[i].result();
            }
            return row;
        }
    }

    private static final class Accumulator {
        private final int column;
        private final AggType type;
        private long count;
        private BigDecimal sum;
        private Object min;
        private Object max;

        private Accumulator(AggSpec spec) {
            this.column = spec.column();
            this.type = spec.type();
        }

        private void add(Object[] row) {
            if (type == AggType.COUNT_STAR) {
                count++;
                return;
            }
            Object value = column >= 0 && column < row.length ? row[column] : null;
            if (value == null) {
                return;
            }
            count++;
            switch (type) {
                case COUNT:
                    break;
                case SUM:
                case AVG:
                    BigDecimal number = decimal(value);
                    sum = sum == null ? number : sum.add(number);
                    break;
                case MIN:
                    min = min == null || compare(value, min) < 0 ? value : min;
                    break;
                case MAX:
                    max = max == null || compare(value, max) > 0 ? value : max;
                    break;
                default:
                    break;
            }
        }

        private Object result() {
            switch (type) {
                case COUNT_STAR:
                case COUNT:
                    return count;
                case SUM:
                    return sum;
                case MIN:
                    return min;
                case MAX:
                    return max;
                case AVG:
                    return sum == null ? null : sum.divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_UP);
                default:
                    return null;
            }
        }
    }

    private static BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("sayısal olmayan toplama değeri: " + value, e);
        }
    }

    private static int compare(Object left, Object right) {
        if (left instanceof Number && right instanceof Number) {
            return decimal(left).compareTo(decimal(right));
        }
        if (left instanceof Comparable<?> && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) left;
            return comparable.compareTo(right);
        }
        return String.valueOf(left).compareTo(String.valueOf(right));
    }
}
