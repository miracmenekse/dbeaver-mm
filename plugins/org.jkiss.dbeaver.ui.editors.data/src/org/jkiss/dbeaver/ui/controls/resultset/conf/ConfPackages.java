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
package org.jkiss.dbeaver.ui.controls.resultset.conf;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * dbeaver-mm K25: conf packages. A package is one task: {@code Scripts/conf/<task id>.sql} in the
 * active project, its first line is {@code -- <description>} and the rest is the SQL body that is
 * pasted into the deployment site. Being plain scripts they show up in the navigator and in the
 * Recent SQL Scripts panel (title = task id, description = first comment line).
 */
public final class ConfPackages {

    private static final Log log = Log.getLog(ConfPackages.class);

    private ConfPackages() {
    }

    /** One inserted row of a package: raw column values, NULL as null. */
    public record InsertedRow(@NotNull String task, @NotNull Map<String, String> values) {
    }

    /**
     * One parsed INSERT: bare table name as written, its column -> value map and where the statement
     * (with its ';' and line end) sits in the package text.
     */
    public record Insert(@NotNull String table, @NotNull Map<String, String> values, int start, int end) {
    }

    // ponytail: fixed Scripts/conf below the project; a custom scripts root setting is ignored
    @Nullable
    public static Path folder() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? null : project.getAbsolutePath().resolve("Scripts").resolve("conf");
    }

    /** Task ids, most recently changed first. */
    @NotNull
    public static List<String> listTasks() {
        Path folder = folder();
        if (folder == null || !Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(f -> f.getFileName().toString().endsWith(".sql"))
                .sorted(Comparator.comparing(ConfPackages::lastModified).reversed())
                .map(f -> f.getFileName().toString().replaceFirst("\\.sql$", ""))
                .toList();
        } catch (IOException e) {
            log.debug("Can't list conf packages", e);
            return List.of();
        }
    }

    /** The package's description (its first comment line), empty when there is none. */
    @NotNull
    public static String readDescription(@NotNull String task) {
        Path file = file(task);
        if (file == null || !Files.isRegularFile(file)) {
            return "";
        }
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return lines.isEmpty() || !lines.get(0).startsWith("--") ? "" : lines.get(0).substring(2).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /** The package's whole text, empty when it can't be read. */
    @NotNull
    public static String read(@NotNull String task) {
        Path file = file(task);
        try {
            return file == null ? "" : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    /** Appends {@code sql} to the task's package, creating it or updating its description. */
    @NotNull
    public static Path append(@NotNull String task, @NotNull String description, @NotNull String sql) throws IOException {
        Path file = file(task);
        if (file == null) {
            throw new IOException("No active project");
        }
        Files.createDirectories(file.getParent());
        String body = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (body.startsWith("--")) {
            int eol = body.indexOf('\n');
            body = eol < 0 ? "" : body.substring(eol + 1);
        }
        StringBuilder text = new StringBuilder("-- ").append(description.strip().replace('\n', ' ')).append('\n');
        text.append(body.stripTrailing().isEmpty() ? "\n" : body.stripTrailing() + "\n\n");
        text.append(sql.strip()).append('\n');
        Files.writeString(file, text, StandardCharsets.UTF_8);
        refresh(file.getParent());
        return file;
    }

    /**
     * dbeaver-mm K30: removes the {@code inserts} (parsed from the task's current text) from the
     * package. Fails instead of guessing when the file changed since it was parsed.
     */
    public static void remove(@NotNull String task, @NotNull String parsedText, @NotNull Collection<Insert> inserts) throws IOException {
        Path file = file(task);
        if (file == null || !Files.readString(file, StandardCharsets.UTF_8).equals(parsedText)) {
            throw new IOException("Package " + task + " changed meanwhile, nothing deleted");
        }
        StringBuilder text = new StringBuilder(parsedText);
        inserts.stream().sorted(Comparator.comparingInt(Insert::start).reversed())
            .forEach(insert -> text.delete(insert.start(), insert.end()));
        Files.writeString(file, text.toString().replaceAll("\n{3,}", "\n\n"), StandardCharsets.UTF_8);
        refresh(file.getParent());
    }

    /** Rows inserted into {@code table} by any package. */
    @NotNull
    public static List<InsertedRow> findInserts(@NotNull String table) {
        List<InsertedRow> rows = new ArrayList<>();
        for (String task : listTasks()) {
            Path file = file(task);
            try {
                for (Insert insert : parseInserts(Files.readString(file, StandardCharsets.UTF_8), table)) {
                    rows.add(new InsertedRow(task, insert.values()));
                }
            } catch (IOException e) {
                log.debug("Can't read conf package " + file, e);
            }
        }
        return rows;
    }

    /** Task id as a safe file name: TASK-123 stays, anything else odd becomes _ */
    @NotNull
    static String fileName(@NotNull String task) {
        return task.strip().replaceAll("[^A-Za-z0-9_.-]", "_") + ".sql";
    }

    @Nullable
    private static Path file(@NotNull String task) {
        Path folder = folder();
        return folder == null ? null : folder.resolve(fileName(task));
    }

    private static long lastModified(@NotNull Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Lets Eclipse (navigator, Recent SQL Scripts panel) see files written with java.nio */
    private static void refresh(@NotNull Path folder) {
        try {
            IContainer container = ResourcesPlugin.getWorkspace().getRoot()
                .getContainerForLocation(org.eclipse.core.runtime.Path.fromOSString(folder.getParent().toString()));
            if (container != null) {
                container.refreshLocal(IResource.DEPTH_INFINITE, null);
            }
        } catch (Exception e) {
            log.debug("Can't refresh conf package folder", e);
        }
    }

    /**
     * {@code INSERT INTO [schema.]table (a, b) VALUES (1, 'x');} statements of {@code table}
     * (matched without schema and quotes, ignoring case; null = every table) as column -> value maps. Quoted values
     * are unquoted, NULL becomes null, anything else (numbers, function calls) is kept as written.
     */
    @NotNull
    public static List<Insert> parseInserts(@NotNull String sql, @Nullable String table) {
        List<Insert> rows = new ArrayList<>();
        String upper = sql.toUpperCase(Locale.ROOT);
        int pos = 0;
        while ((pos = upper.indexOf("INSERT", pos)) >= 0) {
            int start = pos;
            int into = skipSpaces(upper, pos + 6);
            pos += 6;
            if (!upper.startsWith("INTO", into)) {
                continue;
            }
            int nameStart = skipSpaces(sql, into + 4);
            int open = sql.indexOf('(', nameStart);
            int valuesKw = upper.indexOf("VALUES", nameStart);
            if (open < 0 || valuesKw < 0 || valuesKw < open) {
                continue;
            }
            String name = sql.substring(nameStart, open).strip();
            int close = closingParen(sql, open);
            int valuesOpen = sql.indexOf('(', valuesKw);
            int valuesClose = valuesOpen < 0 ? -1 : closingParen(sql, valuesOpen);
            if (close < 0 || valuesClose < 0) {
                break;
            }
            pos = valuesClose;
            if (table != null && !bareName(name).equalsIgnoreCase(table)) {
                continue;
            }
            List<String> columns = splitTopLevel(sql.substring(open + 1, close));
            List<String> values = splitTopLevel(sql.substring(valuesOpen + 1, valuesClose));
            if (columns.size() != values.size()) {
                continue;
            }
            Map<String, String> row = new LinkedHashMap<>();
            for (int i = 0; i < columns.size(); i++) {
                row.put(bareName(columns.get(i)).toLowerCase(Locale.ROOT), unquote(values.get(i)));
            }
            int end = skipSpaces(sql, valuesClose + 1);
            if (end < sql.length() && sql.charAt(end) == ';') {
                end++;
            }
            end = sql.indexOf('\n', end) < 0 ? sql.length() : sql.indexOf('\n', end) + 1;
            rows.add(new Insert(bareName(name), row, start, end));
        }
        return rows;
    }

    private static int skipSpaces(@NotNull String text, int pos) {
        while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
            pos++;
        }
        return pos;
    }

    /** Index of the ')' closing the '(' at {@code open}, skipping quoted text; -1 if none. */
    private static int closingParen(@NotNull String text, int open) {
        int depth = 0;
        boolean quoted = false;
        for (int i = open; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted; // '' inside a literal flips twice
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    /** Splits on commas outside quotes and parentheses. */
    @NotNull
    private static List<String> splitTopLevel(@NotNull String text) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        boolean quoted = false;
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == '(') {
                depth++;
            } else if (!quoted && c == ')') {
                depth--;
            } else if (!quoted && depth == 0 && c == ',') {
                parts.add(text.substring(start, i).strip());
                start = i + 1;
            }
        }
        parts.add(text.substring(start).strip());
        return parts;
    }

    /** {@code "SCHEMA"."Table"} -> {@code Table} */
    @NotNull
    private static String bareName(@NotNull String name) {
        String last = name.substring(name.lastIndexOf('.') + 1).strip();
        return last.replace("\"", "").replace("`", "").replace("[", "").replace("]", "");
    }

    @Nullable
    private static String unquote(@NotNull String value) {
        if (value.equalsIgnoreCase("NULL")) {
            return null;
        }
        if (value.length() >= 2 && value.startsWith("'") && value.endsWith("'")) {
            return value.substring(1, value.length() - 1).replace("''", "'");
        }
        return value;
    }
}
