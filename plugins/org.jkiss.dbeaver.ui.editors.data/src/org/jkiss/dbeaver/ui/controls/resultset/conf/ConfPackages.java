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

    private static final java.util.regex.Pattern TARGET = java.util.regex.Pattern.compile(
        "(?is)^(?:\\s*--[^\n]*\n)*\\s*(INSERT\\s+INTO|UPDATE|DELETE\\s+FROM|MERGE\\s+INTO)\\s+([^\\s(]+)");

    /** One statement of a package: its kind (INSERT, UPDATE, DELETE, MERGE or ""), target and INSERT values */
    private record Statement(@NotNull String text, @NotNull String kind, @NotNull String table,
                             @NotNull String schema, @NotNull Map<String, String> values) {
    }

    /**
     * dbeaver-mm K31/K32: the package's statements grouped by the schema of the table they write to
     * ({@code pcm.x} -> "pcm", unqualified -> ""), each group a script that runs without FK errors:
     * DELETEs first, child tables before parents; then INSERTs, a referenced row before the rows
     * pointing at it; then the rest in file order. Groups come in the order of their first statement,
     * so the schema holding parent rows is first. {@code links}: "table.column" of an FK column ->
     * "table.column" it references; empty = file order. The description line is left out.
     */
    // ponytail: schema read from the statement text only; unqualified names aren't resolved via the connection
    @NotNull
    public static Map<String, String> scriptsBySchema(@NotNull String sql, @NotNull Map<String, String> links) {
        if (sql.startsWith("--")) {
            int eol = sql.indexOf('\n');
            sql = eol < 0 ? "" : sql.substring(eol + 1);
        }
        List<Statement> statements = new ArrayList<>();
        for (String text : splitStatements(sql)) {
            java.util.regex.Matcher m = TARGET.matcher(text);
            String kind = m.find() ? m.group(1).toUpperCase(Locale.ROOT).split("\\s+")[0] : "";
            String name = kind.isEmpty() ? "" : m.group(2);
            String schema = name.contains(".") ? bareName(name.substring(0, name.lastIndexOf('.'))).toLowerCase(Locale.ROOT) : "";
            List<Insert> insert = kind.equals("INSERT") ? parseInserts(text, null) : List.of();
            statements.add(new Statement(text, kind, bareName(name).toLowerCase(Locale.ROOT), schema,
                insert.isEmpty() ? Map.of() : insert.getFirst().values()));
        }
        Map<String, Integer> depths = new HashMap<>();
        List<Statement> ordered = new ArrayList<>();
        statements.stream().filter(st -> st.kind().equals("DELETE"))
            .sorted(Comparator.comparingInt((Statement st) -> -tableDepth(st.table(), links, depths, new HashSet<>())))
            .forEach(ordered::add);
        ordered.addAll(parentsFirst(statements.stream().filter(st -> st.kind().equals("INSERT")).toList(), links));
        statements.stream().filter(st -> !st.kind().equals("DELETE") && !st.kind().equals("INSERT")).forEach(ordered::add);

        Map<String, StringBuilder> groups = new LinkedHashMap<>();
        for (Statement st : ordered) {
            groups.computeIfAbsent(st.schema(), k -> new StringBuilder()).append(st.text()).append(";\n");
        }
        Map<String, String> result = new LinkedHashMap<>();
        groups.forEach((schema, text) -> result.put(schema, text.toString()));
        return result;
    }

    /** 0 for a table without FKs, else 1 + the deepest table it references (cycles cut). */
    private static int tableDepth(@NotNull String table, @NotNull Map<String, String> links,
                                  @NotNull Map<String, Integer> depths, @NotNull Set<String> visiting) {
        Integer known = depths.get(table);
        if (known != null) {
            return known;
        }
        if (!visiting.add(table)) {
            return 0;
        }
        int depth = 0;
        for (Map.Entry<String, String> link : links.entrySet()) {
            String fkTable = link.getKey().substring(0, link.getKey().lastIndexOf('.'));
            String refTable = link.getValue().substring(0, link.getValue().lastIndexOf('.'));
            if (fkTable.equals(table) && !refTable.equals(table)) {
                depth = Math.max(depth, 1 + tableDepth(refTable, links, depths, visiting));
            }
        }
        depths.put(table, depth);
        return depth;
    }

    // ponytail: O(n^3) on the package's INSERT count, fine for hand-made packages of tens of rows
    /** INSERTs reordered so a row comes after the package rows it references; a cycle keeps file order. */
    @NotNull
    private static List<Statement> parentsFirst(@NotNull List<Statement> inserts, @NotNull Map<String, String> links) {
        List<Statement> left = new ArrayList<>(inserts);
        List<Statement> result = new ArrayList<>();
        while (!left.isEmpty()) {
            int next = 0;
            for (int i = 0; i < left.size(); i++) {
                Statement candidate = left.get(i);
                if (left.stream().noneMatch(other -> other != candidate && references(candidate, other, links))) {
                    next = i;
                    break;
                }
            }
            result.add(left.remove(next));
        }
        return result;
    }

    private static boolean references(@NotNull Statement child, @NotNull Statement parent, @NotNull Map<String, String> links) {
        for (Map.Entry<String, String> link : links.entrySet()) {
            int fkDot = link.getKey().lastIndexOf('.');
            int refDot = link.getValue().lastIndexOf('.');
            if (child.table().equals(link.getKey().substring(0, fkDot))
                && parent.table().equals(link.getValue().substring(0, refDot))) {
                String value = child.values().get(link.getKey().substring(fkDot + 1));
                String key = parent.values().get(link.getValue().substring(refDot + 1));
                if (value != null && key != null && plainNumber(value).equals(plainNumber(key))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** dbeaver-mm K30: rows of {@code all} whose FK value points to {@code row} (see {@link #scriptsBySchema} for links). */
    @NotNull
    public static List<Insert> referencing(@NotNull Insert row, @NotNull List<Insert> all, @NotNull Map<String, String> links) {
        List<Insert> result = new ArrayList<>();
        String table = row.table().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> link : links.entrySet()) {
            int refDot = link.getValue().lastIndexOf('.');
            String key = link.getValue().substring(0, refDot).equals(table)
                ? row.values().get(link.getValue().substring(refDot + 1)) : null;
            if (key == null) {
                continue;
            }
            int fkDot = link.getKey().lastIndexOf('.');
            for (Insert other : all) {
                String value = other.values().get(link.getKey().substring(fkDot + 1));
                if (other != row && value != null && other.table().equalsIgnoreCase(link.getKey().substring(0, fkDot))
                    && plainNumber(value).equals(plainNumber(key)) && !result.contains(other)) {
                    result.add(other);
                }
            }
        }
        return result;
    }

    /** dbeaver-mm K33: {@code root} (level 0) and the rows below it, each at its nearest level. */
    @NotNull
    public static Map<Insert, Integer> levelsBelow(@NotNull Insert root, @NotNull List<Insert> all, @NotNull Map<String, String> links) {
        Map<Insert, Integer> levels = new LinkedHashMap<>();
        levels.put(root, 0);
        Deque<Insert> todo = new ArrayDeque<>(List.of(root));
        while (!todo.isEmpty()) {
            Insert row = todo.poll();
            for (Insert child : referencing(row, all, links)) {
                if (!levels.containsKey(child)) {
                    levels.put(child, levels.get(row) + 1);
                    todo.add(child);
                }
            }
        }
        return levels;
    }

    /** "40.0" and "40" are the same key */
    @NotNull
    private static String plainNumber(@NotNull String value) {
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** Statements split on ';' outside quotes, stripped; blank and comment-only pieces dropped. */
    @NotNull
    public static List<String> splitStatements(@NotNull String sql) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int eol = sql.indexOf('\n', i);
                eol = eol < 0 ? sql.length() : eol;
                current.append(sql, i, eol);
                i = eol - 1;
                continue;
            } else if (c == ';') {
                addStatement(result, current);
                continue;
            }
            current.append(c);
        }
        addStatement(result, current);
        return result;
    }

    private static void addStatement(@NotNull List<String> result, @NotNull StringBuilder current) {
        String statement = current.toString().strip();
        current.setLength(0);
        if (!statement.replaceAll("(?m)^\\s*--.*$", "").isBlank()) {
            result.add(statement);
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
