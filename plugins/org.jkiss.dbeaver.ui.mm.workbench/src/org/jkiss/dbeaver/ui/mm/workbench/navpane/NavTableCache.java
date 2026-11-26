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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

// dbeaver-mm K160: bağlantı başına tablo listesini diskte tutan saf Java önbellek
public final class NavTableCache {

    private static final String HEADER_PREFIX = "# saved ";
    private static final String DEFAULT_DIR = ".dbeaver-mm";
    private static final String CACHE_DIR = "navpane";

    private final Path dir;

    public NavTableCache(Path dir) {
        this.dir = dir;
    }

    public static NavTableCache defaultCache() {
        Path home = Paths.get(System.getProperty("user.home"));
        return new NavTableCache(home.resolve(DEFAULT_DIR).resolve(CACHE_DIR));
    }

    public static String fileName(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            throw new IllegalArgumentException("connectionId boş olamaz");
        }
        StringBuilder sb = new StringBuilder(connectionId.length() + 4);
        for (int i = 0; i < connectionId.length(); i++) {
            char c = connectionId.charAt(i);
            boolean allowed = Character.isLetterOrDigit(c) || c == '-' || c == '_' || c == '.';
            sb.append(allowed ? c : '_');
        }
        return sb.append(".tsv").toString();
    }

    public static String format(List<TableSearch.Entry> entries, long savedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append(HEADER_PREFIX).append(savedAt).append('\n');
        if (entries != null) {
            for (TableSearch.Entry entry : entries) {
                sb.append(escape(entry.schema())).append('\t')
                    .append(escape(entry.table())).append('\t')
                    .append(escape(entry.kind())).append('\n');
            }
        }
        return sb.toString();
    }

    public static List<TableSearch.Entry> parse(String text, String connectionName) {
        List<TableSearch.Entry> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        for (String raw : text.split("\n", -1)) {
            String line = stripCr(raw);
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length != 3) {
                continue;
            }
            result.add(new TableSearch.Entry(connectionName,
                unescape(parts[0]), unescape(parts[1]), unescape(parts[2])));
        }
        return result;
    }

    public static long savedAt(String text) {
        if (text == null || text.isEmpty()) {
            return 0L;
        }
        for (String raw : text.split("\n", -1)) {
            String line = stripCr(raw);
            if (line.startsWith(HEADER_PREFIX)) {
                try {
                    return Long.parseLong(line.substring(HEADER_PREFIX.length()).trim());
                } catch (NumberFormatException e) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    public void save(String connectionId, List<TableSearch.Entry> entries) throws IOException {
        Files.createDirectories(dir);
        Path target = file(connectionId);
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, format(entries, System.currentTimeMillis()), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public List<TableSearch.Entry> load(String connectionId, String connectionName) {
        Path target = file(connectionId);
        if (!Files.isRegularFile(target)) {
            return null;
        }
        try {
            String text = Files.readString(target, StandardCharsets.UTF_8);
            return parse(text, connectionName);
        } catch (IOException e) {
            return null;
        }
    }

    public boolean has(String connectionId) {
        return Files.isRegularFile(file(connectionId));
    }

    public void delete(String connectionId) {
        try {
            Files.deleteIfExists(file(connectionId));
        } catch (IOException e) {
            // Silinemeyen dosya yok sayılır; önbellek en iyi çaba ile temizlenir
        }
    }

    private Path file(String connectionId) {
        return dir.resolve(fileName(connectionId));
    }

    private static String stripCr(String line) {
        return line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String unescape(String value) {
        if (value == null || value.indexOf('\\') < 0) {
            return value == null ? "" : value;
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' && i + 1 < value.length()) {
                char next = value.charAt(++i);
                switch (next) {
                    case '\\' -> sb.append('\\');
                    case 't' -> sb.append('\t');
                    case 'n' -> sb.append('\n');
                    default -> sb.append(next);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
