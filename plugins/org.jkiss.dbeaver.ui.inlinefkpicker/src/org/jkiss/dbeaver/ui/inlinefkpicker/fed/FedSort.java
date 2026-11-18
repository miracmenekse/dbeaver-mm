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
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * dbeaver-mm K125: federe sonuç kümesine bellek içinde uygulanan ORDER BY, LIMIT/OFFSET ve DISTINCT.
 * <p>
 * Metin karşılaştırması Türkçe locale'den bağımsız ve harf duyarsızdır; sayılar sayısal olarak
 * karşılaştırılır. Boş değerler artan sıralamada en sonda yer alır.
 */
public final class FedSort {

    private FedSort() {
    }

    /** Satırları verilen sütunlara göre sıralar; girdi listesi değiştirilmez. */
    public static List<Object[]> sort(List<Object[]> rows, int[] cols, boolean[] desc) {
        List<Object[]> result = new ArrayList<>();
        if (rows != null) {
            for (Object[] row : rows) {
                result.add(row == null ? new Object[0] : row);
            }
        }
        if (cols == null || cols.length == 0) {
            return result;
        }
        Collator collator = Collator.getInstance(Locale.ROOT);
        collator.setStrength(Collator.PRIMARY);
        result.sort((left, right) -> compareRows(left, right, cols, desc, collator));
        return result;
    }

    /** {@code offset} satır atlayıp en fazla {@code n} satır döndürür. */
    public static List<Object[]> limit(List<Object[]> rows, int n, int offset) {
        if (rows == null || n <= 0 || offset < 0 || offset >= rows.size()) {
            return new ArrayList<>();
        }
        long end = Math.min(rows.size(), (long) offset + n);
        return new ArrayList<>(rows.subList(offset, (int) end));
    }

    /** Yinelenen satırları atar; ilk görülme sırasını korur. */
    public static List<Object[]> distinct(List<Object[]> rows) {
        LinkedHashSet<List<Object>> seen = new LinkedHashSet<>();
        List<Object[]> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Object[] row : rows) {
            List<Object> key = row == null ? null : Arrays.asList(row);
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    /** dbeaver-mm K126: yalnız verilen sütunların değerlerine göre yinelenenleri atar; ilk satırı korur. */
    public static List<Object[]> distinct(List<Object[]> rows, int[] columns) {
        LinkedHashSet<List<Object>> seen = new LinkedHashSet<>();
        List<Object[]> result = new ArrayList<>();
        if (rows == null) {
            return result;
        }
        for (Object[] row : rows) {
            List<Object> key = new ArrayList<>(columns.length);
            for (int column : columns) {
                key.add(cell(row, column));
            }
            if (seen.add(key)) {
                result.add(row);
            }
        }
        return result;
    }

    private static int compareRows(Object[] left, Object[] right, int[] cols, boolean[] desc, Collator collator) {
        for (int i = 0; i < cols.length; i++) {
            Object a = cell(left, cols[i]);
            Object b = cell(right, cols[i]);
            int cmp = compareValues(a, b, collator);
            if (cmp != 0) {
                return i < desc.length && desc[i] ? -cmp : cmp;
            }
        }
        return 0;
    }

    private static Object cell(Object[] row, int index) {
        return row != null && index >= 0 && index < row.length ? row[index] : null;
    }

    private static int compareValues(Object a, Object b, Collator collator) {
        if (a == b) {
            return 0;
        }
        if (a == null) {
            return 1;
        }
        if (b == null) {
            return -1;
        }
        if (a instanceof Number left && b instanceof Number right) {
            BigDecimal leftValue = new BigDecimal(left.toString());
            BigDecimal rightValue = new BigDecimal(right.toString());
            return leftValue.compareTo(rightValue);
        }
        return collator.compare(String.valueOf(a), String.valueOf(b));
    }
}
