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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K95: data_tp seçimlerini başka tenant'a taşıma kuralları (saf Java).
 * <p>
 * Saf Java: yalnız {@code java.*} kullanır, böylece Eclipse olmadan test edilebilir.
 * Eşleşme tablo adıyla yapılır; emin olunamayan durumda -1 döner (tahmin yok).
 */
public final class DataTpCarry {

    private DataTpCarry() {
    }

    // dbeaver-mm K95: bir bağlantıdaki yer (bağlantı adı, şema, tablo)
    public record Place(String connectionName, String schema, String table) {
    }

    // dbeaver-mm K95: bağlantı adının tenant dışındaki kısmı (T3 PCM -> PCM); yoksa ""
    public static String connectionSuffix(String connectionName) {
        if (connectionName == null) {
            return "";
        }
        String stripped = connectionName.strip();
        int i = 0;
        while (i < stripped.length() && !Character.isWhitespace(stripped.charAt(i))) {
            i++;
        }
        return i >= stripped.length() ? "" : stripped.substring(i).strip();
    }

    // dbeaver-mm K95: şemadaki tenant parçasını değiştirir (bss_pcm_t3 -> bss_pcm_t1); yalnız tam parça
    public static String swapTenant(String schema, String from, String to) {
        if (schema == null) {
            return "";
        }
        if (from == null || from.isBlank() || to == null) {
            return schema;
        }
        Pattern part = Pattern.compile("(?<![A-Za-z0-9])" + Pattern.quote(from) + "(?![A-Za-z0-9])",
            Pattern.CASE_INSENSITIVE);
        Matcher matcher = part.matcher(schema);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String matched = matcher.group();
            String replacement = matched.equals(matched.toLowerCase(Locale.ROOT))
                ? to.toLowerCase(Locale.ROOT) : to.toUpperCase(Locale.ROOT);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    // dbeaver-mm K95: donor bağlantı adıyla aynı sonekli TEK bağlantının indeksi; yoksa -1
    public static int mapConnection(String donorName, List<String> names) {
        String suffix = connectionSuffix(donorName);
        if (suffix.isEmpty() || names == null) {
            return -1;
        }
        int found = -1;
        int count = 0;
        for (int i = 0; i < names.size(); i++) {
            if (suffix.equalsIgnoreCase(connectionSuffix(names.get(i)))) {
                found = i;
                count++;
            }
        }
        return count == 1 ? found : -1;
    }

    // dbeaver-mm K95: donor yerine karşılık gelen yerin indeksi; yalnız tablo adıyla aday, emin değilse -1
    public static int pickIndex(Place donor, String donorTenant, String tenant, List<Place> places) {
        if (donor == null || donor.table() == null || places == null) {
            return -1;
        }
        String donorSuffix = connectionSuffix(donor.connectionName());
        String donorSchema = swapTenant(donor.schema(), donorTenant, tenant);
        List<Integer> sameTable = new ArrayList<>();
        for (int i = 0; i < places.size(); i++) {
            if (donor.table().equalsIgnoreCase(places.get(i).table())) {
                sameTable.add(i);
            }
        }
        List<Integer> sameSuffix = new ArrayList<>();
        for (int i : sameTable) {
            Place place = places.get(i);
            if (!donorSuffix.isEmpty() && donorSuffix.equalsIgnoreCase(connectionSuffix(place.connectionName()))) {
                if (donorSchema.equalsIgnoreCase(place.schema() == null ? "" : place.schema())) {
                    return i;
                }
                sameSuffix.add(i);
            }
        }
        if (sameSuffix.size() == 1) {
            return sameSuffix.get(0);
        }
        if (sameSuffix.isEmpty() && sameTable.size() == 1) {
            return sameTable.get(0);
        }
        return -1;
    }

    // dbeaver-mm K95: donor kaydı; önce kaynak tablo adı (source), yoksa eski kayıtta seçilen tablo adı
    public static DataTpStore.Entry donorEntry(DataTpStore.TenantMap map, String sourceTable) {
        if (map == null || sourceTable == null || sourceTable.isBlank()) {
            return null;
        }
        for (DataTpStore.Entry entry : map.types().values()) {
            if (sourceTable.equalsIgnoreCase(entry.source())) {
                return entry;
            }
        }
        for (DataTpStore.Entry entry : map.types().values()) {
            if (entry.source().isEmpty() && sourceTable.equalsIgnoreCase(entry.table())) {
                return entry;
            }
        }
        return null;
    }
}
