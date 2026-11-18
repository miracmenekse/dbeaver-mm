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
package org.jkiss.dbeaver.ui.mm.workbench.navpane;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

// dbeaver-mm K137: tablo/nesne bulanık arama (saf mantık, UI yok)
public final class TableSearch {

    private TableSearch() {
    }

    public record Entry(String connection, String schema, String table, String kind) {
    }

    public record Hit(Entry entry, int score, int[] matched) {
    }

    public static List<Hit> search(List<Entry> index, String query, int limit) {
        int lim = limit <= 0 ? 500 : limit;
        if (query == null || query.isEmpty()) {
            List<Entry> all = new ArrayList<>(index);
            all.sort(Comparator.comparing((Entry e) -> safe(e.connection()))
                .thenComparing(e -> safe(e.table())));
            List<Hit> out = new ArrayList<>(Math.min(lim, all.size()));
            for (int i = 0; i < all.size() && i < lim; i++) {
                out.add(new Hit(all.get(i), 0, new int[0]));
            }
            return out;
        }
        String q = query.toLowerCase(Locale.ROOT);
        List<Hit> hits = new ArrayList<>();
        for (Entry e : index) {
            Match m = match(e.table(), q);
            if (m != null) {
                hits.add(new Hit(e, m.score, m.positions));
            }
        }
        hits.sort(Comparator.comparingInt((Hit h) -> -h.score())
            .thenComparing(h -> safe(h.entry().connection()))
            .thenComparing(h -> safe(h.entry().table())));
        if (hits.size() > lim) {
            return new ArrayList<>(hits.subList(0, lim));
        }
        return hits;
    }

    public static String groupKey(Hit h) {
        return h.entry().connection();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    private static final class Match {
        final int score;
        final int[] positions;

        Match(int score, int[] positions) {
            this.score = score;
            this.positions = positions;
        }
    }

    private static Match match(String table, String queryLower) {
        if (table == null) {
            return null;
        }
        String t = table.toLowerCase(Locale.ROOT);
        int n = t.length();
        int m = queryLower.length();
        if (m == 0) {
            return new Match(0, new int[0]);
        }
        if (m > n) {
            return null;
        }
        // En iyi eşleşme konumlarını bul (skoru maksimize et)
        int[] bestPos = null;
        int bestScore = Integer.MIN_VALUE;
        // Basit geri izleme: her sorgu harfi için aday konumları dene
        int[] pos = new int[m];
        // Hızlı yol: alt dizi mümkün mü kontrolü
        if (!isSubsequence(t, queryLower)) {
            return null;
        }
        // Greedy aday + iyileştirme: ardışık/kelime başı tercihli arama
        bestPos = findPositions(t, queryLower);
        if (bestPos == null) {
            return null;
        }
        bestScore = score(t, bestPos, m);
        // Birkaç alternatif dene: farklı başlangıç/atlama seçimleri
        // (küçük girdi için yeterli sezgisel iyileştirme)
        int[] alt = findPositionsPreferringBoundaries(t, queryLower);
        if (alt != null) {
            int s = score(t, alt, m);
            if (s > bestScore) {
                bestScore = s;
                bestPos = alt;
            }
        }
        return new Match(bestScore, bestPos);
    }

    private static boolean isSubsequence(String t, String q) {
        int i = 0;
        int j = 0;
        while (i < t.length() && j < q.length()) {
            if (t.charAt(i) == q.charAt(j)) {
                j++;
            }
            i++;
        }
        return j == q.length();
    }

    private static int[] findPositions(String t, String q) {
        int n = t.length();
        int m = q.length();
        int[] pos = new int[m];
        int ti = 0;
        for (int qi = 0; qi < m; qi++) {
            char qc = q.charAt(qi);
            boolean found = false;
            // Önce ardışık eşleşme dene
            if (qi > 0 && pos[qi - 1] + 1 < n && t.charAt(pos[qi - 1] + 1) == qc) {
                pos[qi] = pos[qi - 1] + 1;
                ti = pos[qi] + 1;
                continue;
            }
            // Sonra kelime başı ara
            int start = qi == 0 ? 0 : pos[qi - 1] + 1;
            int boundary = -1;
            int first = -1;
            for (int i = start; i < n; i++) {
                if (t.charAt(i) == qc) {
                    if (first < 0) {
                        first = i;
                    }
                    if (isWordBoundary(t, i)) {
                        boundary = i;
                        break;
                    }
                }
            }
            if (boundary >= 0) {
                pos[qi] = boundary;
            } else if (first >= 0) {
                pos[qi] = first;
            } else {
                return null;
            }
            ti = pos[qi] + 1;
        }
        return pos;
    }

    private static int[] findPositionsPreferringBoundaries(String t, String q) {
        int n = t.length();
        int m = q.length();
        int[] pos = new int[m];
        Arrays.fill(pos, -1);
        int qi = 0;
        // Kelime başlarını ve ardışıkları tercih eden ileri geçiş
        for (int i = 0; i < n && qi < m; i++) {
            if (t.charAt(i) != q.charAt(qi)) {
                continue;
            }
            boolean take = false;
            if (qi == 0) {
                take = isWordBoundary(t, i) || i == 0 || willFit(t, q, i, 0);
            } else {
                if (i == pos[qi - 1] + 1) {
                    take = true;
                } else if (isWordBoundary(t, i)) {
                    take = true;
                } else if (pos[qi] < 0) {
                    // ilk uygun yeri sakla ama daha iyisini ara
                }
            }
            if (take) {
                pos[qi] = i;
                qi++;
            } else if (qi > 0 && pos[qi] < 0) {
                pos[qi] = i; // yedek
            }
        }
        // doldurulmamışları ileri taramayla tamamla
        if (qi < m) {
            int start = 0;
            for (int k = 0; k < m; k++) {
                if (pos[k] >= 0) {
                    start = pos[k] + 1;
                    continue;
                }
                boolean ok = false;
                for (int i = start; i < n; i++) {
                    if (t.charAt(i) == q.charAt(k)) {
                        pos[k] = i;
                        start = i + 1;
                        ok = true;
                        break;
                    }
                }
                if (!ok) {
                    return null;
                }
            }
        }
        // artan sıra garantisi
        for (int k = 1; k < m; k++) {
            if (pos[k] <= pos[k - 1]) {
                return findPositions(t, q);
            }
        }
        return pos;
    }

    private static boolean willFit(String t, String q, int ti, int qi) {
        int i = ti;
        int j = qi;
        while (i < t.length() && j < q.length()) {
            if (t.charAt(i) == q.charAt(j)) {
                j++;
            }
            i++;
        }
        return j == q.length();
    }

    private static boolean isWordBoundary(String t, int i) {
        if (i == 0) {
            return true;
        }
        char c = t.charAt(i);
        char p = t.charAt(i - 1);
        if (p == '_') {
            return true;
        }
        if (Character.isDigit(c) && !Character.isDigit(p)) {
            return true;
        }
        if (Character.isDigit(p) && !Character.isDigit(c)) {
            return true;
        }
        if (Character.isUpperCase(c) && !Character.isUpperCase(p)) {
            return true;
        }
        return false;
    }

    private static int score(String t, int[] pos, int m) {
        int s = 0;
        // ön ek bonusu
        if (pos[0] == 0) {
            s += 20;
        }
        for (int i = 0; i < m; i++) {
            int p = pos[i];
            // kelime başı bonusu
            if (isWordBoundary(t, p)) {
                s += 10;
            }
            // ardışık harf bonusu
            if (i > 0 && p == pos[i - 1] + 1) {
                s += 15;
            }
            // konum cezası (erken eşleşme daha iyi)
            s -= p;
        }
        // kısa ad bonusu
        s += Math.max(0, 40 - t.length());
        // tam ön ek/kompakt eşleşme
        if (pos[m - 1] - pos[0] + 1 == m) {
            s += 10;
        }
        return s;
    }
}
