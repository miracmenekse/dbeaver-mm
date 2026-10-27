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

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * dbeaver-mm K68: data_tp column pair recognition and connection target mapping store.
 * dbeaver-mm K74: global table mapping (Target, load, save, defaultFile) removed; tenant files only.
 * dbeaver-mm K72: tenant başına data_tp eşleme dosyası (saf Java).
 * <p>
 * Saf Java: yalnız {@code java.*} kullanır, böylece Eclipse olmadan test edilebilir.
 * Tenant file line format: {@code tp.<data_tp>=TABLE|connectionId|schema}.
 */
public final class DataTpStore {

    public static final String DATA_TP = "data_tp";
    public static final String ROW_ID = "row_id";
    public static final String TYPE_TABLE = "etiya_type_value";
    public static final String TYPE_ID = "etiya_type_value_id";
    public static final String TYPE_NAME = "table_name";

    private DataTpStore() {
    }

    // dbeaver-mm K72: eşlenen hedef tablo (tablo, bağlantı ve şema)
    public record Entry(String table, String connectionId, String schema) {
    }

    // dbeaver-mm K72: tenant dosyasının içeriği
    // dbeaver-mm K86: schemas = aranacak şemalar (boş = hepsi)
    public record TenantMap(String typeConnectionId, String typeSchema, Map<String, Entry> types, String built,
                            List<String> schemas) {
        public TenantMap {
            types = types == null ? Map.of() : Map.copyOf(types);
            // dbeaver-mm K86: kırpılır, boşlar atlanır, harf duyarsız tekrardan ilk yazım kalır
            List<String> clean = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            if (schemas != null) {
                for (String schema : schemas) {
                    String stripped = schema == null ? "" : schema.strip();
                    if (!stripped.isEmpty() && seen.add(stripped.toLowerCase(Locale.ROOT))) {
                        clean.add(stripped);
                    }
                }
            }
            schemas = List.copyOf(clean);
        }

        // dbeaver-mm K86: eski 4 parçalı kurucu; şema listesi boş (= hepsi)
        public TenantMap(String typeConnectionId, String typeSchema, Map<String, Entry> types, String built) {
            this(typeConnectionId, typeSchema, types, built, List.of());
        }
    }

    // dbeaver-mm K86: kayıt boşsa hepsi; değilse kayıttakiler (harf duyarsız), all'ın sırası ve yazımıyla
    public static List<String> checkedSchemas(List<String> all, List<String> saved) {
        if (all == null) {
            return List.of();
        }
        if (saved == null || saved.isEmpty()) {
            return new ArrayList<>(all);
        }
        Set<String> keys = new HashSet<>();
        for (String schema : saved) {
            if (schema != null) {
                keys.add(schema.strip().toLowerCase(Locale.ROOT));
            }
        }
        List<String> checked = new ArrayList<>();
        for (String schema : all) {
            if (schema != null && keys.contains(schema.strip().toLowerCase(Locale.ROOT))) {
                checked.add(schema);
            }
        }
        return checked;
    }

    // dbeaver-mm K86: SQL IN listesi; tek tırnak kaçar, null/boş atlanır
    public static String sqlList(Collection<String> values) {
        if (values == null) {
            return "";
        }
        StringBuilder sql = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append('\'').append(value.replace("'", "''")).append('\'');
        }
        return sql.toString();
    }

    public static boolean isDataTp(String columnName) {
        return DATA_TP.equalsIgnoreCase(columnName);
    }

    public static boolean isRowId(String columnName) {
        return ROW_ID.equalsIgnoreCase(columnName);
    }

    /**
     * Sütun listesinde hem data_tp hem row_id varsa data_tp'nin indeksini, yoksa -1 döner.
     */
    public static int dataTpIndex(List<String> columnNames) {
        if (columnNames == null) {
            return -1;
        }
        int dataTpIndex = -1;
        boolean hasRowId = false;
        for (int i = 0; i < columnNames.size(); i++) {
            String columnName = columnNames.get(i);
            if (isDataTp(columnName)) {
                dataTpIndex = i;
            } else if (isRowId(columnName)) {
                hasRowId = true;
            }
        }
        return hasRowId ? dataTpIndex : -1;
    }

    public static String tableKey(String tableName) {
        return tableName.strip().toUpperCase(Locale.ROOT);
    }

    // dbeaver-mm K72: bağlantı adının ilk kelimesi tenant kimliğidir (T3 CRM -> T3)
    public static String tenantOf(String connectionName) {
        if (connectionName == null) {
            return "";
        }
        String stripped = connectionName.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        int end = 0;
        while (end < stripped.length() && !Character.isWhitespace(stripped.charAt(end))) {
            end++;
        }
        return stripped.substring(0, end).toUpperCase(Locale.ROOT);
    }

    // dbeaver-mm K72: sayılar tek biçime indirilir, sayı olmayan metin yalnız kırpılır
    public static String typeKey(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof CharSequence ? value.toString().strip() : value.toString();
        try {
            return new BigDecimal(text).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return text.strip();
        }
    }

    // dbeaver-mm K72: tenant eşleme dosyalarının dizini
    public static Path tenantDir() {
        return Paths.get(System.getProperty("user.home"), ".dbeaver-mm", "data-tp");
    }

    // dbeaver-mm K72: tenant adı güvenli dosya adına çevrilir (a/b -> A_B)
    public static Path tenantFile(Path dir, String tenant) {
        String upper = tenant == null ? "" : tenant.toUpperCase(Locale.ROOT);
        StringBuilder name = new StringBuilder(upper.length());
        for (int i = 0; i < upper.length(); i++) {
            char c = upper.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            name.append(safe ? c : '_');
        }
        if (name.length() == 0) {
            name.append('_');
        }
        return dir.resolve(name + ".properties");
    }

    // dbeaver-mm K72: tenant dosyasını okur; yok ya da okunamazsa boş eşleme, asla fırlatmaz
    public static TenantMap loadTenant(Path file) {
        if (file == null) {
            return new TenantMap("", "", Map.of(), "");
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            return new TenantMap("", "", Map.of(), "");
        }
        Map<String, Entry> types = new HashMap<>();
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith("tp.")) {
                continue;
            }
            String dataTp = typeKey(name.substring(3));
            if (dataTp == null || dataTp.isEmpty()) {
                continue;
            }
            String value = properties.getProperty(name, "");
            int first = value.indexOf('|');
            if (first < 0) {
                continue;
            }
            int second = value.indexOf('|', first + 1);
            String table = value.substring(0, first).strip();
            String connectionId = (second < 0 ? value.substring(first + 1) : value.substring(first + 1, second)).strip();
            String schema = second < 0 ? "" : value.substring(second + 1).strip();
            if (table.isEmpty() || connectionId.isEmpty()) {
                continue;
            }
            types.put(dataTp, new Entry(table, connectionId, schema));
        }
        return new TenantMap(
            properties.getProperty("type.connection", ""),
            properties.getProperty("type.schema", ""),
            types,
            properties.getProperty("built", ""),
            // dbeaver-mm K86: search.schemas=a,b; temizlik kurucuda
            Arrays.asList(properties.getProperty("search.schemas", "").split(",")));
    }

    // dbeaver-mm K72: tenant dosyasını atomik yazar; loadTenant ile birebir geri okunur
    public static void saveTenant(Path file, TenantMap map) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "data-tp", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("type.connection", emptyIfNull(map.typeConnectionId()));
            properties.setProperty("type.schema", emptyIfNull(map.typeSchema()));
            properties.setProperty("built", emptyIfNull(map.built()));
            properties.setProperty("search.schemas", String.join(",", map.schemas())); // dbeaver-mm K86
            for (Map.Entry<String, Entry> e : map.types().entrySet()) {
                String key = typeKey(e.getKey());
                Entry entry = e.getValue();
                if (key == null || key.isEmpty() || entry == null
                    || entry.table() == null || entry.table().isBlank()
                    || entry.connectionId() == null || entry.connectionId().isBlank()) {
                    continue;
                }
                properties.setProperty("tp." + key,
                    entry.table().strip() + "|" + entry.connectionId().strip() + "|" + emptyIfNull(entry.schema()).strip());
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "dbeaver-mm data_tp tenant mapping");
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String emptyIfNull(String value) {
        return value == null ? "" : value;
    }
}
