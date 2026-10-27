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

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * dbeaver-mm K78: yakın ad sıralaması ve yer anahtarı (saf Java).
 * <p>
 * Saf Java: yalnız {@code java.*} kullanır, böylece Eclipse olmadan test edilebilir.
 */
public final class DataTpMatch {

    private DataTpMatch() {
    }

    public static int distance(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            char lc = left.charAt(i - 1);
            for (int j = 1; j <= right.length(); j++) {
                int cost = (lc == right.charAt(j - 1)) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    public static List<String> closest(String name, Collection<String> names, int limit) {
        if (names == null || limit <= 0) {
            return List.of();
        }
        // case-insensitive duplicates keep the first spelling; each distance is computed once
        Map<String, String> unique = new LinkedHashMap<>();
        for (String candidate : names) {
            if (candidate != null && !candidate.isBlank()) {
                unique.putIfAbsent(normalize(candidate), candidate);
            }
        }
        Map<String, Integer> distances = new HashMap<>();
        unique.keySet().forEach(key -> distances.put(key, distance(key, name)));
        return unique.entrySet().stream()
            .sorted(Comparator.comparing((Map.Entry<String, String> e) -> distances.get(e.getKey())).thenComparing(Map.Entry::getKey))
            .limit(limit)
            .map(Map.Entry::getValue)
            .toList();
    }

    public static String placeKey(String database, String schema, String table) {
        return normalize(database) + "|" + normalize(schema) + "|" + normalize(table);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
