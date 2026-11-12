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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * dbeaver-mm K108: splits SQL into statements and keeps only the ones a conf package lacks.
 */
public final class ConfDedupe {

    private ConfDedupe() {
    }

    /** Statements split on ';' outside quotes, stripped; blank and comment-only pieces dropped. */
    public static List<String> split(String sql) {
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

    private static void addStatement(List<String> result, StringBuilder current) {
        String statement = current.toString().strip();
        current.setLength(0);
        if (!statement.replaceAll("(?m)^\\s*--.*$", "").isBlank()) {
            result.add(statement);
        }
    }

    /**
     * Comparison form of a statement: {@code --} comment lines are dropped, whitespace outside
     * quotes collapses to one space and the result is stripped.
     */
    public static String normalize(String statement) {
        StringBuilder joined = new StringBuilder();
        for (String line : statement.split("\\R", -1)) {
            if (line.strip().startsWith("--")) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(line);
        }
        StringBuilder result = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < joined.length(); i++) {
            char c = joined.charAt(i);
            if (quote != 0) {
                result.append(c);
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
                result.append(c);
            } else if (Character.isWhitespace(c)) {
                if (result.length() > 0 && result.charAt(result.length() - 1) != ' ') {
                    result.append(' ');
                }
            } else {
                result.append(c);
            }
        }
        return result.toString().strip();
    }

    /**
     * Statements of {@code sql} whose normalized form is not in {@code packageText}, in input order,
     * duplicates inside {@code sql} returned once.
     */
    public static List<String> missing(String packageText, String sql) {
        Set<String> present = new HashSet<>();
        for (String statement : split(packageText)) {
            present.add(normalize(statement));
        }
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String statement : split(sql)) {
            String key = normalize(statement);
            if (present.contains(key) || !seen.add(key)) {
                continue;
            }
            result.add(statement);
        }
        return result;
    }
}
