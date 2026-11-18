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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

// dbeaver-mm K141: gezgin ağaç modeli (saf mantık, UI yok)
public final class NavTreeModel {

    private NavTreeModel() {
    }

    public record Conn(String name, boolean connected) {
    }

    public record Group(Conn conn, List<TableSearch.Hit> hits, boolean expanded, int total) {
    }

    public static List<Group> build(List<Conn> conns, List<TableSearch.Hit> hits, String query, Map<String, Integer> totals) {
        String q = query == null ? "" : query.trim();
        boolean emptyQuery = q.isEmpty();
        Map<String, Integer> t = totals == null ? Map.of() : totals;

        // Aynı adlı iki bağlantı karışmaz (ilk eşleşen sırayla): hit'ler ilk bağlantıya atanır
        List<List<TableSearch.Hit>> perConn = new ArrayList<>(conns.size());
        Map<String, Integer> firstIndex = new LinkedHashMap<>();
        for (int i = 0; i < conns.size(); i++) {
            perConn.add(new ArrayList<>());
            String name = conns.get(i).name();
            if (!firstIndex.containsKey(name)) {
                firstIndex.put(name, i);
            }
        }
        if (hits != null) {
            for (TableSearch.Hit h : hits) {
                String cname = h.entry().connection();
                Integer idx = firstIndex.get(cname);
                if (idx != null) {
                    perConn.get(idx).add(h);
                }
            }
        }

        List<Group> out = new ArrayList<>();
        for (int i = 0; i < conns.size(); i++) {
            Conn c = conns.get(i);
            int total = t.getOrDefault(c.name(), 0);
            if (emptyQuery) {
                List<TableSearch.Hit> ch = c.connected() ? List.copyOf(perConn.get(i)) : List.of();
                out.add(new Group(c, ch, false, total));
            } else {
                if (!c.connected()) {
                    continue;
                }
                List<TableSearch.Hit> ch = perConn.get(i);
                if (!ch.isEmpty()) {
                    out.add(new Group(c, List.copyOf(ch), true, total));
                }
            }
        }
        return out;
    }
}
