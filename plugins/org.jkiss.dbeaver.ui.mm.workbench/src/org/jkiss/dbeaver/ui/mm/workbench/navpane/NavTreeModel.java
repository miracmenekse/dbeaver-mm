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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

// dbeaver-mm K141: gezgin ağaç modeli (saf mantık, UI yok)
public final class NavTreeModel {

    private NavTreeModel() {
    }

    public record Conn(String name, boolean connected) {
        // dbeaver-mm K143: tenant = bağlantı adının ilk kelimesi (kasa ve Türkçe karakter korunur)
        public String tenant() {
            String n = name == null ? "" : name.trim();
            if (n.isEmpty()) {
                return "";
            }
            int space = n.indexOf(' ');
            return space < 0 ? n : n.substring(0, space);
        }
    }

    // dbeaver-mm K143: çok şemalı bağlantıda şema alt grubu
    public record SchemaGroup(String schema, List<TableSearch.Hit> hits, boolean expanded) {
    }

    public record Group(Conn conn, List<TableSearch.Hit> hits, List<SchemaGroup> schemas, boolean expanded, int total) {
    }

    // dbeaver-mm K145: banner verilmeyen eski çağrı tüm bağlantıları korur
    public static List<Group> build(List<Conn> conns, List<TableSearch.Hit> hits, String query, Map<String, Integer> totals) {
        Set<String> allBanners = new LinkedHashSet<>();
        if (conns != null) {
            for (Conn conn : conns) {
                allBanners.add(conn.tenant());
            }
        }
        return build(conns, hits, query, totals, allBanners);
    }

    // dbeaver-mm K145: banners boş/null ise liste boş, doluysa tenant süzgeci
    public static List<Group> build(List<Conn> conns, List<TableSearch.Hit> hits, String query,
                                    Map<String, Integer> totals, Set<String> banners) {
        String q = query == null ? "" : query.trim();
        boolean emptyQuery = q.isEmpty();
        Map<String, Integer> t = totals == null ? Map.of() : totals;
        if (banners == null || banners.isEmpty()) {
            return List.of();
        }

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
            if (!banners.contains(c.tenant())) {
                continue;
            }
            int total = t.getOrDefault(c.name(), 0);
            if (emptyQuery) {
                List<TableSearch.Hit> ch = c.connected() ? List.copyOf(perConn.get(i)) : List.of();
                out.add(group(c, ch, false, total));
            } else {
                if (!c.connected()) {
                    continue;
                }
                List<TableSearch.Hit> ch = perConn.get(i);
                if (!ch.isEmpty()) {
                    out.add(group(c, List.copyOf(ch), true, total));
                }
            }
        }
        return out;
    }

    // dbeaver-mm K143: tek şemalı bağlantıda katman atlanır, çok şemalıda şema alt grupları üretilir
    private static Group group(Conn conn, List<TableSearch.Hit> hits, boolean expanded, int total) {
        Map<String, List<TableSearch.Hit>> bySchema = new LinkedHashMap<>();
        for (TableSearch.Hit h : hits) {
            String schema = h.entry().schema() == null ? "" : h.entry().schema();
            bySchema.computeIfAbsent(schema, k -> new ArrayList<>()).add(h);
        }
        List<SchemaGroup> schemas = new ArrayList<>();
        if (bySchema.size() > 1) {
            for (Map.Entry<String, List<TableSearch.Hit>> e : bySchema.entrySet()) {
                schemas.add(new SchemaGroup(e.getKey(), List.copyOf(e.getValue()), expanded));
            }
        }
        return new Group(conn, hits, List.copyOf(schemas), expanded, total);
    }
}
