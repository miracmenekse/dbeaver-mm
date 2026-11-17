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
import java.util.List;
import java.util.Map;

/**
 * dbeaver-mm K118: iki satır kümesini eşitlik anahtarlarıyla birleştiren saf hash join.
 * Sağ taraf hash tablosuna alınır, sol taraf üzerinden sorgulanır; sonuç satırı sol
 * sütunların ardına sağ sütunlar eklenerek oluşturulur. Sol tarafın sırası korunur.
 */
public final class FedJoin {

    private FedJoin() {
    }

    /**
     * Satırları anahtar eşitliğine göre birleştirir.
     *
     * @param left sol satırlar (sırası korunur)
     * @param right sağ satırlar (hash tablosuna alınır)
     * @param leftKeys sol satırdaki anahtar sütun konumları
     * @param rightKeys sağ satırdaki anahtar sütun konumları
     * @param leftOuter eşleşmeyen sol satırlar da sonuca eklenir mi
     * @param rightWidth eşleşmeyen satırlarda eklenecek sağ null sütun sayısı
     * @return sol sütunlar + sağ sütunlar biçiminde birleşik satırlar
     */
    public static List<Object[]> join(
        List<Object[]> left,
        List<Object[]> right,
        int[] leftKeys,
        int[] rightKeys,
        boolean leftOuter,
        int rightWidth
    ) {
        Map<Key, List<Object[]>> table = new HashMap<>();
        for (Object[] rightRow : right) {
            Object[] parts = buildKey(rightRow, rightKeys);
            if (parts != null) {
                table.computeIfAbsent(new Key(parts), k -> new ArrayList<>()).add(rightRow);
            }
        }

        List<Object[]> result = new ArrayList<>();
        for (Object[] leftRow : left) {
            Object[] parts = buildKey(leftRow, leftKeys);
            List<Object[]> matches = parts == null ? null : table.get(new Key(parts));
            if (matches != null) {
                for (Object[] rightRow : matches) {
                    result.add(combine(leftRow, rightRow));
                }
            } else if (leftOuter) {
                result.add(combineLeftOnly(leftRow, rightWidth));
            }
        }
        return result;
    }

    private static Object[] buildKey(Object[] row, int[] keys) {
        Object[] parts = new Object[keys.length];
        for (int i = 0; i < keys.length; i++) {
            int index = keys[i];
            Object value = index >= 0 && index < row.length ? row[index] : null;
            if (value == null) {
                // NULL anahtar hiçbir satırla eşleşmez
                return null;
            }
            parts[i] = normalize(value);
        }
        return parts;
    }

    /**
     * Sayısal değerleri tür farkından bağımsız karşılaştırmak için BigDecimal'e çevirir.
     * Böylece Integer 1, Long 1 ve BigDecimal 1.0 aynı anahtarı üretir.
     */
    private static Object normalize(Object value) {
        if (value instanceof BigDecimal decimal) {
            return stripScale(decimal);
        }
        if (value instanceof Number number) {
            return stripScale(new BigDecimal(number.toString()));
        }
        return value;
    }

    private static BigDecimal stripScale(BigDecimal decimal) {
        return decimal.signum() == 0 ? BigDecimal.ZERO : decimal.stripTrailingZeros();
    }

    private static Object[] combine(Object[] leftRow, Object[] rightRow) {
        Object[] out = new Object[leftRow.length + rightRow.length];
        System.arraycopy(leftRow, 0, out, 0, leftRow.length);
        System.arraycopy(rightRow, 0, out, leftRow.length, rightRow.length);
        return out;
    }

    private static Object[] combineLeftOnly(Object[] leftRow, int rightWidth) {
        Object[] out = new Object[leftRow.length + Math.max(rightWidth, 0)];
        System.arraycopy(leftRow, 0, out, 0, leftRow.length);
        return out;
    }

    private static final class Key {
        private final Object[] parts;

        private Key(Object[] parts) {
            this.parts = parts;
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof Key other && Arrays.equals(parts, other.parts);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(parts);
        }
    }
}
