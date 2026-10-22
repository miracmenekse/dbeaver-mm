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
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * dbeaver-mm K68: data_tp column pair recognition and connection target mapping store.
 * <p>
 * Saf Java: yalnız {@code java.*} kullanır, böylece Eclipse olmadan test edilebilir.
 * Kayıt dosyası satır biçimi: {@code ANAHTAR=bağlantıId|şema}.
 */
public final class DataTpStore {

    public static final String DATA_TP = "data_tp";
    public static final String ROW_ID = "row_id";
    public static final String TYPE_TABLE = "etiya_type_value";
    public static final String TYPE_ID = "etiya_type_value_id";
    public static final String TYPE_NAME = "table_name";

    private DataTpStore() {
    }

    public record Target(String connectionId, String schema) {
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

    public static Path defaultFile() {
        return Paths.get(System.getProperty("user.home"), ".dbeaver-mm", "data-tp.properties");
    }

    /**
     * Kayıtları okur. Dosya yok, okunamaz ya da bozuksa boş harita döner; asla fırlatmaz.
     */
    public static Map<String, Target> load(Path file) {
        Map<String, Target> result = new HashMap<>();
        if (file == null) {
            return result;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            return new HashMap<>();
        }
        for (String name : properties.stringPropertyNames()) {
            String value = properties.getProperty(name, "");
            if (value.isBlank()) {
                continue;
            }
            int separator = value.indexOf('|');
            String connectionId = separator < 0 ? value : value.substring(0, separator);
            String schema = separator < 0 ? "" : value.substring(separator + 1);
            if (name.isBlank() || connectionId.isBlank()) {
                continue;
            }
            result.put(tableKey(name), new Target(connectionId, schema));
        }
        return result;
    }

    /**
     * Kayıtları atomik olarak yazar: önce aynı dizinde geçici dosya, sonra yerine taşıma.
     */
    public static void save(Path file, Map<String, Target> targets) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = Files.createTempFile(parent, "data-tp", ".tmp");
        try {
            Map<String, Target> sorted = new TreeMap<>();
            for (Map.Entry<String, Target> entry : targets.entrySet()) {
                sorted.put(tableKey(entry.getKey()), entry.getValue());
            }
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                for (Map.Entry<String, Target> entry : sorted.entrySet()) {
                    Target target = entry.getValue();
                    String schema = target.schema() == null ? "" : target.schema();
                    String connectionId = target.connectionId() == null ? "" : target.connectionId();
                    writer.write(entry.getKey() + "=" + connectionId + "|" + schema + "\n");
                }
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
}
