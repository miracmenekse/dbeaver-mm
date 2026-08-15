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
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, Eclipse-free analysis of the SQL text around the caret.
 * <p>
 * Detects a value-editing context by scanning backwards from the caret:
 * <ul>
 *     <li>{@code &lt;column&gt; = &lt;caret&gt;} (optionally qualified {@code alias.column}, optional partial value)</li>
 *     <li>{@code &lt;column&gt; IN (&lt;caret&gt;} and continuations {@code IN (1, 2, &lt;caret&gt;}</li>
 * </ul>
 * The table the column belongs to is NOT derived from the column name. Instead the FROM/UPDATE
 * list of the current statement is parsed into {@link FkColumnRef.TableRef}s so the caller
 * ({@link InlineFkService}) can resolve the real table and column via metadata.
 * <p>
 * Dependency-free so it can be unit-tested with plain strings.
 */
public final class SqlCaretAnalyzer {

    private static final Set<String> KEYWORDS = new HashSet<>(Arrays.asList(
        "join", "inner", "left", "right", "full", "outer", "cross", "natural",
        "on", "using", "as", "where", "group", "order", "having", "limit",
        "union", "and", "or", "set", "select", "from", "update", "into", "values", "by"
    ));

    private SqlCaretAnalyzer() {
    }

    /** Convenience overload treating the whole text as one statement (used by tests). */
    public static FkColumnRef analyze(String text, int caret) {
        return analyze(text, caret, 0, text == null ? 0 : text.length());
    }

    /**
     * Analyze {@code text} at {@code caret}, restricting statement-level parsing (FROM list) to the
     * range {@code [stmtStart, stmtEnd)}. Returns an {@link FkColumnRef} when the caret sits in a
     * supported value context of a statement that has a FROM/UPDATE table, or {@code null}.
     */
    public static FkColumnRef analyze(String text, int caret, int stmtStart, int stmtEnd) {
        if (text == null || caret < 0 || caret > text.length()) {
            return null;
        }
        if (stmtStart < 0) {
            stmtStart = 0;
        }
        if (stmtEnd > text.length()) {
            stmtEnd = text.length();
        }
        if (inLineComment(text, caret, stmtStart)) {
            return null;
        }

        int i = caret;

        // 1. Read the (possibly empty) partial value token ending at the caret.
        int partialEnd = caret;
        while (i > stmtStart && isValueChar(text.charAt(i - 1))) {
            i--;
        }
        int partialStart = i;
        String rawPartial = text.substring(partialStart, partialEnd);

        // 2. Skip whitespace between the operator/delimiter and the partial value.
        i = skipSpacesLeft(text, partialStart, stmtStart);
        if (i <= stmtStart) {
            return null;
        }

        char delimiter = text.charAt(i - 1);
        FkColumnRef.Mode mode;
        int columnEnd;

        if (delimiter == '=') {
            if (i >= 2) {
                char prev = text.charAt(i - 2);
                if (prev == '<' || prev == '>' || prev == '!' || prev == '=') {
                    return null;
                }
            }
            mode = FkColumnRef.Mode.EQUALS;
            columnEnd = skipSpacesLeft(text, i - 1, stmtStart);
        } else if (delimiter == '(' || delimiter == ',') {
            int openParen = (delimiter == '(') ? (i - 1) : findListOpenParen(text, i - 1, stmtStart);
            if (openParen < 0) {
                return null;
            }
            int kwEnd = skipSpacesLeft(text, openParen, stmtStart);
            int kwStart = readIdentifierLeft(text, kwEnd, stmtStart);
            if (kwStart == kwEnd || !text.substring(kwStart, kwEnd).equalsIgnoreCase("IN")) {
                return null;
            }
            mode = FkColumnRef.Mode.IN_LIST;
            columnEnd = skipSpacesLeft(text, kwStart, stmtStart);
        } else {
            return null;
        }

        // 3. Read the column identifier and optional qualifier (alias.column).
        int columnStart = readIdentifierLeft(text, columnEnd, stmtStart);
        if (columnStart == columnEnd) {
            return null;
        }
        String column = text.substring(columnStart, columnEnd);
        String qualifier = null;
        if (columnStart > stmtStart && text.charAt(columnStart - 1) == '.') {
            int qEnd = columnStart - 1;
            int qStart = readIdentifierLeft(text, qEnd, stmtStart);
            if (qStart < qEnd) {
                qualifier = text.substring(qStart, qEnd);
            }
        }

        // 4. Parse the FROM/UPDATE table references of the current statement.
        List<FkColumnRef.TableRef> fromTables = parseTables(text.substring(stmtStart, stmtEnd));
        if (fromTables.isEmpty()) {
            return null;
        }

        String prefix = stripQuotes(rawPartial);
        boolean needsLeadingSpace = partialStart > 0
            && (text.charAt(partialStart - 1) == '=' || text.charAt(partialStart - 1) == ',');

        return new FkColumnRef(column, qualifier, fromTables, mode, partialStart, partialEnd, prefix, needsLeadingSpace);
    }

    // ---------------------------------------------------------------- FROM parsing

    /**
     * Parse table references from a statement: everything named after FROM/JOIN/UPDATE/INTO,
     * with an optional alias. ON/USING conditions and other keywords are skipped.
     */
    static List<FkColumnRef.TableRef> parseTables(String stmt) {
        List<FkColumnRef.TableRef> refs = new ArrayList<>();
        List<String> tokens = tokenize(stmt);
        boolean expectTable = false;
        for (int idx = 0; idx < tokens.size(); idx++) {
            String tok = tokens.get(idx);
            // Locale.ROOT is critical: the default (e.g. Turkish) locale lowercases 'I' to a
            // dotless 'ı', so "JOIN"/"IN"/"UNION" would never match their keyword forms.
            String lower = tok.toLowerCase(Locale.ROOT);
            if (tok.equals(",")) {
                // Only meaningful right after we already started a table list.
                if (!refs.isEmpty()) {
                    expectTable = true;
                }
                continue;
            }
            if (lower.equals("from") || lower.equals("update") || lower.equals("join") || lower.equals("into")) {
                expectTable = true;
                continue;
            }
            if (KEYWORDS.contains(lower)) {
                // Any other keyword ends a table-expectation window (e.g. ON, WHERE, AS handled below).
                if (!lower.equals("as")) {
                    expectTable = false;
                }
                continue;
            }
            if (!isIdentifierToken(tok)) {
                continue;
            }
            if (expectTable) {
                String name = lastSegment(tok);
                String alias = null;
                // Look for an alias: optional AS then a non-keyword identifier.
                int peek = idx + 1;
                if (peek < tokens.size() && tokens.get(peek).equalsIgnoreCase("as")) {
                    peek++;
                }
                if (peek < tokens.size()) {
                    String cand = tokens.get(peek);
                    if (isIdentifierToken(cand) && !KEYWORDS.contains(cand.toLowerCase(Locale.ROOT)) && !cand.equals(",")) {
                        alias = cand;
                    }
                }
                refs.add(new FkColumnRef.TableRef(name, alias));
                expectTable = false;
            }
        }
        return refs;
    }

    private static List<String> tokenize(String s) {
        List<String> tokens = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (isIdentifierChar(c) || c == '.') {
                int start = i;
                while (i < n && (isIdentifierChar(s.charAt(i)) || s.charAt(i) == '.')) {
                    i++;
                }
                tokens.add(s.substring(start, i));
            } else {
                // Single-char punctuation token (comma is significant; others are separators).
                tokens.add(String.valueOf(c));
                i++;
            }
        }
        return tokens;
    }

    private static boolean isIdentifierToken(String tok) {
        if (tok.isEmpty()) {
            return false;
        }
        char c0 = tok.charAt(0);
        return Character.isLetter(c0) || c0 == '_' || c0 == '"' || c0 == '`';
    }

    private static String lastSegment(String dotted) {
        int dot = dotted.lastIndexOf('.');
        String seg = dot >= 0 ? dotted.substring(dot + 1) : dotted;
        // Strip quoted-identifier delimiters.
        return seg.replace("\"", "").replace("`", "");
    }

    // ---------------------------------------------------------------- low-level scanning

    private static boolean isValueChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '\'' || c == '-';
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int skipSpacesLeft(String text, int pos, int floor) {
        int i = pos;
        while (i > floor) {
            char c = text.charAt(i - 1);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i--;
            } else {
                break;
            }
        }
        return i;
    }

    private static int readIdentifierLeft(String text, int end, int floor) {
        int i = end;
        while (i > floor && isIdentifierChar(text.charAt(i - 1))) {
            i--;
        }
        return i;
    }

    private static int findListOpenParen(String text, int fromExclusive, int floor) {
        int depth = 0;
        for (int i = fromExclusive - 1; i >= floor; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (c == ';') {
                return -1;
            }
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        String r = s;
        if (r.startsWith("'")) {
            r = r.substring(1);
        }
        if (r.endsWith("'")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static boolean inLineComment(String text, int caret, int floor) {
        int lineStart = caret;
        while (lineStart > floor && text.charAt(lineStart - 1) != '\n') {
            lineStart--;
        }
        for (int i = lineStart; i < caret - 1; i++) {
            if (text.charAt(i) == '-' && text.charAt(i + 1) == '-') {
                return true;
            }
        }
        return false;
    }
}
