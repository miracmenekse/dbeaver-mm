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
package org.jkiss.dbeaver.ui.controls.resultset.undo;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/**
 * dbeaver-mm K75: builds a plain SQL undo script from changed result set rows, stores
 * scripts in a local folder, lists them and prunes old ones.
 * <p>
 * Saf Java: yalnız {@code java.*} kullanır, böylece Eclipse olmadan test edilebilir.
 */
public final class UndoScript {

    public static final String FIRST_LINE = "-- dbeaver-mm undo script";
    public static final String TIME_PREFIX = "-- time: ";
    public static final String CONNECTION_PREFIX = "-- connection: ";
    public static final String CONNECTION_ID_PREFIX = "-- connection-id: ";
    public static final String TABLE_PREFIX = "-- table: ";
    public static final String CHANGES_PREFIX = "-- changes: ";
    public static final String WARNING_PREFIX = "-- warning: ";
    public static final String REVIEW_LINE = "-- Review before running, in the same connection.";

    private static final String ENABLED_KEY = "enabled";

    private UndoScript() {
    }

    public enum Kind {
        DELETED,
        UPDATED,
        INSERTED
    }

    // dbeaver-mm K75: hazır SQL metni; Java null değeri SQL NULL'a karşılık gelir
    public record Column(String name, String literal) {
    }

    // dbeaver-mm K75: DELETED -> values tüm satır; UPDATED -> values eski, current yeni;
    // INSERTED/UPDATED -> keys WHERE koşulu
    public record Change(Kind kind, List<Column> values, List<Column> current, List<Column> keys) {
    }

    public record Header(String time, String connectionId, String connectionName, String table, List<String> warnings) {
    }

    public record Entry(
        Path file,
        String time,
        String connectionId,
        String connectionName,
        String table,
        int deleted,
        int updated,
        int inserted) {
    }

    // dbeaver-mm K75: ters SQL betiğini üretir
    public static String build(Header header, List<Change> changes) {
        List<Change> items = changes == null ? List.of() : changes;
        int deleted = 0;
        int updated = 0;
        int inserted = 0;
        int skipped = 0;
        for (Change change : items) {
            if (change == null || change.kind() == null) {
                continue;
            }
            switch (change.kind()) {
                case DELETED -> deleted++;
                case UPDATED -> {
                    updated++;
                    if (isEmpty(change.keys())) {
                        skipped++;
                    }
                }
                case INSERTED -> {
                    inserted++;
                    if (isEmpty(change.keys())) {
                        skipped++;
                    }
                }
            }
        }

        StringBuilder script = new StringBuilder();
        script.append(FIRST_LINE).append('\n');
        script.append(TIME_PREFIX).append(clean(header == null ? null : header.time())).append('\n');
        script.append(CONNECTION_PREFIX).append(clean(header == null ? null : header.connectionName())).append('\n');
        script.append(CONNECTION_ID_PREFIX).append(clean(header == null ? null : header.connectionId())).append('\n');
        script.append(TABLE_PREFIX).append(clean(header == null ? null : header.table())).append('\n');
        script.append(CHANGES_PREFIX)
            .append("deleted=").append(deleted)
            .append(" updated=").append(updated)
            .append(" inserted=").append(inserted)
            .append('\n');
        if (header != null && header.warnings() != null) {
            for (String warning : header.warnings()) {
                script.append(WARNING_PREFIX).append(clean(warning)).append('\n');
            }
        }
        if (skipped > 0) {
            script.append(WARNING_PREFIX).append(skipped).append(" row(s) skipped (no key)\n");
        }
        script.append(REVIEW_LINE).append('\n');

        String table = clean(header == null ? null : header.table());

        appendInsertedUndo(script, table, items);
        appendUpdatedUndo(script, table, items);
        appendDeletedUndo(script, table, items);

        return script.toString();
    }

    private static void appendInsertedUndo(StringBuilder script, String table, List<Change> items) {
        boolean started = false;
        for (Change change : items) {
            if (change == null || change.kind() != Kind.INSERTED) {
                continue;
            }
            if (!started) {
                script.append("-- undo insert\n");
                started = true;
            }
            if (isEmpty(change.keys())) {
                script.append("-- skipped: no key to find the row\n");
            } else {
                script.append("DELETE FROM ").append(table)
                    .append(" WHERE ").append(whereClause(change.keys())).append(";\n");
            }
        }
    }

    private static void appendUpdatedUndo(StringBuilder script, String table, List<Change> items) {
        for (Change change : items) {
            if (change == null || change.kind() != Kind.UPDATED) {
                continue;
            }
            // dbeaver-mm K75: every row shows its current values, so the reviewer sees what gets overwritten
            script.append("-- undo update (now: ").append(assignments(change.current())).append(")\n");
            if (isEmpty(change.keys())) {
                script.append("-- skipped: no key to find the row\n");
            } else {
                script.append("UPDATE ").append(table)
                    .append(" SET ").append(assignments(change.values()))
                    .append(" WHERE ").append(whereClause(change.keys())).append(";\n");
            }
        }
    }

    private static void appendDeletedUndo(StringBuilder script, String table, List<Change> items) {
        boolean started = false;
        for (Change change : items) {
            if (change == null || change.kind() != Kind.DELETED) {
                continue;
            }
            if (!started) {
                script.append("-- undo delete\n");
                started = true;
            }
            List<Column> values = columns(change.values());
            script.append("INSERT INTO ").append(table).append(" (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    script.append(", ");
                }
                script.append(values.get(i).name());
            }
            script.append(") VALUES (");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) {
                    script.append(", ");
                }
                script.append(literal(values.get(i)));
            }
            script.append(");\n");
        }
    }

    private static String assignments(List<Column> columns) {
        List<Column> items = columns(columns);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(items.get(i).name()).append(" = ").append(literal(items.get(i)));
        }
        return text.toString();
    }

    private static String whereClause(List<Column> keys) {
        List<Column> items = columns(keys);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                text.append(" AND ");
            }
            Column key = items.get(i);
            if (isNullLiteral(key.literal())) {
                text.append(key.name()).append(" IS NULL");
            } else {
                text.append(key.name()).append(" = ").append(literal(key));
            }
        }
        return text.toString();
    }

    private static List<Column> columns(List<Column> columns) {
        return columns == null ? List.of() : columns;
    }

    private static boolean isEmpty(List<Column> columns) {
        return columns == null || columns.isEmpty();
    }

    private static boolean isNullLiteral(String literal) {
        return literal == null || literal.strip().equalsIgnoreCase("NULL");
    }

    private static String literal(Column column) {
        return isNullLiteral(column.literal()) ? "NULL" : column.literal();
    }

    // dbeaver-mm K75: başlık değerlerindeki satır sonları boşluk olur
    private static String clean(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
    }

    // dbeaver-mm K75: betiklerin kayıt klasörü
    public static Path folder() {
        return Paths.get(System.getProperty("user.home"), ".dbeaver-mm", "geri-al");
    }

    // dbeaver-mm K75: açma/kapama ayar dosyası
    public static Path settingsFile() {
        return Paths.get(System.getProperty("user.home"), ".dbeaver-mm", "geri-al.properties");
    }

    // dbeaver-mm K75: betiği klasöre atomik yazar
    public static Path write(Path folder, Header header, String script) throws IOException {
        Files.createDirectories(folder);
        Path target = uniqueTarget(folder, header == null ? null : header.time(), header == null ? null : header.table());
        Path temp = Files.createTempFile(folder, ".undo", ".tmp");
        try {
            Files.writeString(temp, script == null ? "" : script, StandardCharsets.UTF_8);
            move(temp, target);
        } finally {
            Files.deleteIfExists(temp);
        }
        return target;
    }

    private static Path uniqueTarget(Path folder, String time, String table) {
        String base = baseName(time, table);
        Path target = folder.resolve(base + ".sql");
        int counter = 2;
        while (Files.exists(target)) {
            target = folder.resolve(base + "-" + counter + ".sql");
            counter++;
        }
        return target;
    }

    private static String baseName(String time, String table) {
        StringBuilder digits = new StringBuilder();
        if (time != null) {
            for (int i = 0; i < time.length(); i++) {
                char c = time.charAt(i);
                if (Character.isDigit(c)) {
                    digits.append(c);
                }
            }
        }
        return digits + "-" + safeName(table);
    }

    private static String safeName(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder name = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '_' || c == '.' || c == '-';
            name.append(safe ? c : '_');
        }
        return name.toString();
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // dbeaver-mm K75: kayıt klasöründeki betikleri en yeni önce listeler, asla fırlatmaz
    public static List<Entry> list(Path folder) {
        List<Entry> entries = new ArrayList<>();
        if (folder == null) {
            return entries;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.sql")) {
            for (Path file : stream) {
                Entry entry = readEntry(file);
                if (entry != null) {
                    entries.add(entry);
                }
            }
        } catch (Exception e) {
            return entries;
        }
        entries.sort(Comparator
            .comparing((Entry entry) -> entry.time() == null ? "" : entry.time())
            .thenComparing(entry -> entry.file().getFileName().toString())
            .reversed());
        return entries;
    }

    private static Entry readEntry(Path file) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            if (lines.isEmpty() || !FIRST_LINE.equals(lines.get(0))) {
                return null;
            }
            String time = findValue(lines, TIME_PREFIX);
            String connectionId = findValue(lines, CONNECTION_ID_PREFIX);
            String connectionName = findValue(lines, CONNECTION_PREFIX);
            String table = findValue(lines, TABLE_PREFIX);
            int deleted = 0;
            int updated = 0;
            int inserted = 0;
            String changes = findValue(lines, CHANGES_PREFIX);
            if (changes != null) {
                for (String token : changes.split("\\s+")) {
                    if (token.startsWith("deleted=")) {
                        deleted = parseInt(token.substring("deleted=".length()));
                    } else if (token.startsWith("updated=")) {
                        updated = parseInt(token.substring("updated=".length()));
                    } else if (token.startsWith("inserted=")) {
                        inserted = parseInt(token.substring("inserted=".length()));
                    }
                }
            }
            return new Entry(file, time, connectionId, connectionName, table, deleted, updated, inserted);
        } catch (Exception e) {
            return null;
        }
    }

    private static String findValue(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        return null;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    // dbeaver-mm K75: gün sayısından eski betikleri siler, asla fırlatmaz
    public static int prune(Path folder, Instant now, int days) {
        if (folder == null || now == null) {
            return 0;
        }
        Instant cutoff = now.minus(days, ChronoUnit.DAYS);
        int deleted = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.sql")) {
            for (Path file : stream) {
                try {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    FileTime modified = Files.getLastModifiedTime(file);
                    if (modified.toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                        deleted++;
                    }
                } catch (Exception ignored) {
                    // dbeaver-mm K75: tek dosya hatası temizliği durdurmaz
                }
            }
        } catch (Exception e) {
            return deleted;
        }
        return deleted;
    }

    // dbeaver-mm K75: dosya yok/bozuk ise açık kabul edilir
    public static boolean isEnabled(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return true;
        }
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (Exception e) {
            return true;
        }
        return !"false".equalsIgnoreCase(properties.getProperty(ENABLED_KEY, "true").strip());
    }

    // dbeaver-mm K75: açma/kapama ayarını atomik yazar
    public static void setEnabled(Path file, boolean enabled) throws IOException {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        if (Files.isRegularFile(absolute)) {
            try (Reader reader = Files.newBufferedReader(absolute, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (Exception ignored) {
                // dbeaver-mm K75: bozuk ayar dosyası sıfırdan yazılır
            }
        }
        properties.setProperty(ENABLED_KEY, Boolean.toString(enabled));
        Path temp = parent != null
            ? Files.createTempFile(parent, "geri-al", ".tmp")
            : Files.createTempFile("geri-al", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, null);
            }
            move(temp, absolute);
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
