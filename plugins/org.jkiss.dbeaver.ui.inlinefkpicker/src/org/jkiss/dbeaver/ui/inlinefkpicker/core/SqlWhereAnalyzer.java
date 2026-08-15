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
package org.jkiss.dbeaver.ui.inlinefkpicker.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure, Eclipse-free extraction of the editable filter conditions of a SQL statement.
 * <p>
 * Finds every {@code [alias.]column = <literal|?>} comparison at the statement's top nesting
 * level, so a saved script's filters can be listed and re-filled without the user hunting
 * through the text. Sibling of {@link SqlCaretAnalyzer}, which solves the caret-context problem;
 * this one solves the whole-statement problem. Table resolution is shared: both hand
 * {@link FkColumnRef.TableRef}s to {@link InlineFkService} for metadata lookup.
 * <p>
 * Deliberately reported:
 * <ul>
 *     <li>{@code bfs.shrt_code = ?} - not filled in yet</li>
 *     <li>{@code cc.is_actv = 1} and {@code x.code = 'ABC'} - already filled, still editable</li>
 * </ul>
 * Deliberately skipped: join predicates ({@code a.id = b.id}), anything inside parentheses
 * (subqueries), comparisons other than {@code =}, and text inside comments or string literals.
 */
public final class SqlWhereAnalyzer {

    /** Keywords that terminate the WHERE clause at the top nesting level. */
    private static final List<String> CLAUSE_END_KEYWORDS = List.of(
        "group", "order", "having", "limit", "offset", "union", "except", "intersect", "window", "fetch");

    /** Result of a scan: the statement's tables plus its editable conditions, in document order. */
    public static final class Result {
        private final List<FkColumnRef.TableRef> fromTables;
        private final List<WhereCondition> conditions;

        Result(List<FkColumnRef.TableRef> fromTables, List<WhereCondition> conditions) {
            this.fromTables = fromTables;
            this.conditions = conditions;
        }

        public List<FkColumnRef.TableRef> getFromTables() {
            return fromTables;
        }

        /** All editable conditions, WHERE-clause ones and JOIN ... ON ones alike. */
        public List<WhereCondition> getConditions() {
            return conditions;
        }

        /** Only the conditions that sit in the WHERE clause. */
        public List<WhereCondition> getWhereConditions() {
            List<WhereCondition> result = new ArrayList<>();
            for (WhereCondition condition : conditions) {
                if (condition.isInWhereClause()) {
                    result.add(condition);
                }
            }
            return result;
        }

        public boolean isEmpty() {
            return conditions.isEmpty();
        }
    }

    private static final Result EMPTY = new Result(Collections.emptyList(), Collections.emptyList());

    private SqlWhereAnalyzer() {
    }

    /** Convenience overload treating the whole text as one statement. */
    public static Result analyze(String text) {
        return analyze(text, 0, text == null ? 0 : text.length());
    }

    /**
     * Scans {@code [stmtStart, stmtEnd)} of {@code text} for editable filter conditions.
     * Offsets in the result are absolute document offsets.
     */
    public static Result analyze(String text, int stmtStart, int stmtEnd) {
        if (text == null) {
            return EMPTY;
        }
        int start = Math.max(0, stmtStart);
        int end = Math.min(text.length(), stmtEnd);
        if (start >= end) {
            return EMPTY;
        }

        // Comments and string literals are blanked so that operators, keywords and parentheses
        // inside them cannot be mistaken for structure. Offsets are preserved, so values are
        // still sliced out of the original text.
        char[] masked = mask(text, start, end);

        int whereStart = findTopLevelKeyword(masked, start, end, "where");
        int whereEnd = end;
        if (whereStart >= 0) {
            for (String keyword : CLAUSE_END_KEYWORDS) {
                int pos = findTopLevelKeyword(masked, whereStart + 5, end, keyword);
                if (pos >= 0 && pos < whereEnd) {
                    whereEnd = pos;
                }
            }
        }

        List<WhereCondition> conditions = new ArrayList<>();
        int depth = 0;
        for (int i = start; i < end; i++) {
            char c = masked[i];
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                continue;
            }
            if (depth != 0 || c != '=') {
                continue;
            }
            // Reject compound operators: <=, >=, !=, ==, =>
            if (i > start) {
                char prev = masked[i - 1];
                if (prev == '<' || prev == '>' || prev == '!' || prev == '=') {
                    continue;
                }
            }
            if (i + 1 < end && (masked[i + 1] == '=' || masked[i + 1] == '>')) {
                continue;
            }
            WhereCondition condition = readCondition(text, masked, start, end, i, whereStart, whereEnd);
            if (condition != null) {
                conditions.add(condition);
            }
        }

        List<FkColumnRef.TableRef> tables = SqlCaretAnalyzer.parseTables(text.substring(start, end));
        return new Result(tables, conditions);
    }

    /**
     * Reads the column on the left and the value on the right of the {@code =} at {@code eq}.
     * Returns null when either side does not qualify (e.g. a join predicate).
     */
    private static WhereCondition readCondition(
        String text,
        char[] masked,
        int start,
        int end,
        int eq,
        int whereStart,
        int whereEnd
    ) {
        int columnEnd = skipSpacesLeft(masked, eq, start);
        int columnStart = readIdentifierLeft(masked, columnEnd, start);
        if (columnStart == columnEnd) {
            return null;
        }
        String column = text.substring(columnStart, columnEnd);
        String qualifier = null;
        if (columnStart > start && masked[columnStart - 1] == '.') {
            int qEnd = columnStart - 1;
            int qStart = readIdentifierLeft(masked, qEnd, start);
            if (qStart < qEnd) {
                qualifier = text.substring(qStart, qEnd);
            }
        }

        int valueStart = skipSpacesRight(masked, eq + 1, end);
        if (valueStart >= end) {
            return null;
        }
        int valueEnd = readValueRight(text, masked, valueStart, end);
        if (valueEnd <= valueStart) {
            return null;
        }
        String value = text.substring(valueStart, valueEnd);

        boolean inWhere = whereStart >= 0 && eq >= whereStart && eq < whereEnd;
        return new WhereCondition(qualifier, unquote(column), valueStart, valueEnd, value, inWhere);
    }

    /**
     * Reads the value token to the right. Accepts {@code ?}, numbers and quoted strings only -
     * a bare identifier means the right side is another column, i.e. a join predicate.
     *
     * @return end offset of the value, or {@code valueStart} when it does not qualify
     */
    private static int readValueRight(String text, char[] masked, int valueStart, int end) {
        char c = text.charAt(valueStart);
        if (c == '?') {
            return valueStart + 1;
        }
        if (c == '\'') {
            // The mask blanked the literal, so its extent is read from the original text,
            // honouring the doubled-quote escape.
            int i = valueStart + 1;
            while (i < end) {
                if (text.charAt(i) == '\'') {
                    if (i + 1 < end && text.charAt(i + 1) == '\'') {
                        i += 2;
                        continue;
                    }
                    return i + 1;
                }
                i++;
            }
            return valueStart;
        }
        if (Character.isDigit(c) || ((c == '-' || c == '+') && valueStart + 1 < end
            && Character.isDigit(text.charAt(valueStart + 1)))) {
            int i = valueStart + 1;
            while (i < end && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                i++;
            }
            // A digit run directly glued to letters is not a plain number
            if (i < end && isIdentifierChar(masked[i])) {
                return valueStart;
            }
            return i;
        }
        return valueStart;
    }

    // ---------------------------------------------------------------- masking

    /**
     * Copies the region, replacing line comments, block comments and single-quoted strings with
     * spaces so structural scanning cannot be fooled by their contents.
     */
    private static char[] mask(String text, int start, int end) {
        char[] masked = text.toCharArray();
        int i = start;
        while (i < end) {
            char c = masked[i];
            if (c == '-' && i + 1 < end && masked[i + 1] == '-') {
                while (i < end && masked[i] != '\n') {
                    masked[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < end && masked[i + 1] == '*') {
                masked[i++] = ' ';
                masked[i++] = ' ';
                while (i < end) {
                    boolean closing = masked[i] == '*' && i + 1 < end && masked[i + 1] == '/';
                    masked[i++] = ' ';
                    if (closing) {
                        if (i < end) {
                            masked[i++] = ' ';
                        }
                        break;
                    }
                }
            } else if (c == '\'') {
                // Only the contents are blanked - the delimiting quotes stay so that a string
                // is still visible as a value token when scanning right of an operator.
                i++;
                while (i < end) {
                    if (masked[i] == '\'') {
                        if (i + 1 < end && masked[i + 1] == '\'') {
                            masked[i] = ' ';
                            masked[i + 1] = ' ';
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    masked[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return masked;
    }

    // ---------------------------------------------------------------- scanning helpers

    /** Finds a keyword at parenthesis depth 0, as a whole word. Returns -1 when absent. */
    private static int findTopLevelKeyword(char[] masked, int start, int end, String keyword) {
        int depth = 0;
        int length = keyword.length();
        for (int i = start; i < end; i++) {
            char c = masked[i];
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0 && i + length <= end && matchesWord(masked, i, end, keyword)) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matchesWord(char[] masked, int pos, int end, String keyword) {
        if (pos > 0 && isIdentifierChar(masked[pos - 1])) {
            return false;
        }
        int length = keyword.length();
        if (pos + length > end) {
            return false;
        }
        for (int k = 0; k < length; k++) {
            // Character.toLowerCase is locale independent, unlike String.toLowerCase()
            if (Character.toLowerCase(masked[pos + k]) != keyword.charAt(k)) {
                return false;
            }
        }
        return pos + length >= end || !isIdentifierChar(masked[pos + length]);
    }

    private static int skipSpacesLeft(char[] masked, int from, int limit) {
        int i = from;
        while (i > limit && Character.isWhitespace(masked[i - 1])) {
            i--;
        }
        return i;
    }

    private static int skipSpacesRight(char[] masked, int from, int limit) {
        int i = from;
        while (i < limit && Character.isWhitespace(masked[i])) {
            i++;
        }
        return i;
    }

    private static int readIdentifierLeft(char[] masked, int from, int limit) {
        int i = from;
        while (i > limit && isIdentifierChar(masked[i - 1])) {
            i--;
        }
        return i;
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '#'
            || c == '"' || c == '`';
    }

    private static String unquote(String identifier) {
        return identifier.replace("\"", "").replace("`", "");
    }
}
