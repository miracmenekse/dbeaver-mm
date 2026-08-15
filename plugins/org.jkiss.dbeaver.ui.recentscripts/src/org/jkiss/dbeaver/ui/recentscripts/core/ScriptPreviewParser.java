/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.ui.recentscripts.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import org.jkiss.dbeaver.ui.inlinefkpicker.core.SqlWhereAnalyzer;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.WhereCondition;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Extracts the card description and the SQL preview from a script file.
 * <p>
 * Rules, per the feature spec:
 * <ul>
 * <li><b>Description</b> = the first {@code --} comment line that appears before the first
 * code line. A line consisting only of dashes (a ruler) is skipped and the search
 * continues.</li>
 * <li><b>Preview</b> = the first three <i>code</i> lines. Comment lines never appear in the
 * preview: DBeaver's own new-script template starts with a multi-line {@code --} header,
 * so without this rule the preview would show three more comment lines and no SQL.</li>
 * </ul>
 * Only a bounded prefix of the file is read, so an oversized {@code .sql} file cannot stall
 * the panel.
 */
public final class ScriptPreviewParser {

    private static final int MAX_LINES_SCANNED = 200;
    private static final int MAX_CHARS_SCANNED = 64 * 1024;
    private static final int PREVIEW_LINE_COUNT = 3;
    /** Cap on WHERE condition lines per card, so one card cannot fill the whole panel. */
    private static final int MAX_CONDITION_LINES = 6;
    private static final int MAX_DESC_LENGTH = 160;
    private static final int MAX_PREVIEW_LINE_LENGTH = 200;
    private static final int TAB_WIDTH = 4;
    /** Byte order mark, written as an int to keep this source file plain ASCII. */
    private static final int BOM = 0xFEFF;
    private static final String ELLIPSIS = "...";

    /**
     * Result of parsing a script header.
     */
    public record ParsedPreview(@Nullable String description, @NotNull List<String> previewLines) {

        public static final ParsedPreview EMPTY = new ParsedPreview(null, Collections.emptyList());
    }

    private ScriptPreviewParser() {
        // Utility class
    }

    /**
     * Parses the beginning of a workspace script file.
     * <p>
     * The charset comes from {@link IFile#getCharset()}, which already resolves the
     * BOM / content type / container / workspace-default chain.
     */
    @NotNull
    public static ParsedPreview parse(@NotNull IFile file) throws CoreException, IOException {
        Charset charset = resolveCharset(file);
        // getContents(true) - without force a stale workspace tree raises OUT_OF_SYNC_LOCAL
        try (InputStream in = file.getContents(true);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))
        ) {
            return parse(reader);
        }
    }

    /**
     * Parses an already opened reader. Kept free of Eclipse types so it can be exercised directly.
     */
    @NotNull
    public static ParsedPreview parse(@NotNull BufferedReader reader) throws IOException {
        return parseText(readCapped(reader));
    }

    /**
     * Builds the card preview for a script.
     * <p>
     * The preview shows the statement's WHERE conditions, because for a saved script the useful
     * question is "what do I have to fill in to run this", not what it selects. Scripts without a
     * WHERE clause (inserts, plain selects) fall back to the first code lines so their cards stay
     * informative.
     */
    @NotNull
    public static ParsedPreview parseText(@NotNull String text) {
        ParsedPreview header = parseHeader(text);
        List<String> conditions = extractConditions(text);
        if (conditions.isEmpty()) {
            return header;
        }
        return new ParsedPreview(header.description(), conditions);
    }

    /**
     * WHERE-clause conditions rendered for display, capped so one card cannot fill the panel.
     */
    @NotNull
    private static List<String> extractConditions(@NotNull String text) {
        List<WhereCondition> conditions;
        try {
            conditions = SqlWhereAnalyzer.analyze(text).getWhereConditions();
        } catch (Throwable e) {
            return Collections.emptyList();
        }
        List<String> lines = new ArrayList<>(Math.min(conditions.size(), MAX_CONDITION_LINES));
        for (int i = 0; i < conditions.size(); i++) {
            if (i == MAX_CONDITION_LINES) {
                lines.add("... +" + (conditions.size() - MAX_CONDITION_LINES));
                break;
            }
            lines.add(truncate(conditions.get(i).getDisplayText(), MAX_PREVIEW_LINE_LENGTH));
        }
        return lines;
    }

    /** Reads at most {@link #MAX_CHARS_SCANNED} characters, so a huge file cannot stall the panel. */
    @NotNull
    private static String readCapped(@NotNull BufferedReader reader) throws IOException {
        char[] buffer = new char[8192];
        StringBuilder text = new StringBuilder();
        int read;
        while (text.length() < MAX_CHARS_SCANNED && (read = reader.read(buffer)) > 0) {
            text.append(buffer, 0, read);
        }
        return text.length() > MAX_CHARS_SCANNED ? text.substring(0, MAX_CHARS_SCANNED) : text.toString();
    }

    @NotNull
    private static ParsedPreview parseHeader(@NotNull String text) {
        try (BufferedReader reader = new BufferedReader(new StringReader(text))) {
            return parseHeader(reader);
        } catch (IOException e) {
            return ParsedPreview.EMPTY;
        }
    }

    @NotNull
    private static ParsedPreview parseHeader(@NotNull BufferedReader reader) throws IOException {
        String description = null;
        List<String> previewLines = new ArrayList<>(PREVIEW_LINE_COUNT);
        boolean[] inBlockComment = {false};
        int linesScanned = 0;
        int charsScanned = 0;

        String rawLine;
        while (previewLines.size() < PREVIEW_LINE_COUNT
            && linesScanned < MAX_LINES_SCANNED
            && charsScanned < MAX_CHARS_SCANNED
            && (rawLine = reader.readLine()) != null
        ) {
            if (linesScanned == 0 && !rawLine.isEmpty() && rawLine.charAt(0) == BOM) {
                // Some encodings leak the byte order mark through to the first line
                rawLine = rawLine.substring(1);
            }
            linesScanned++;
            charsScanned += rawLine.length() + 1;

            String code = stripBlockComments(rawLine, inBlockComment);
            String trimmed = code.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("--")) {
                if (description == null) {
                    String text = trimmed.replaceFirst("^-+\\s*", "").trim();
                    if (!text.isEmpty()) {
                        description = truncate(text, MAX_DESC_LENGTH);
                    }
                }
                // A comment line is never a preview line
                continue;
            }
            previewLines.add(expandTabs(stripTrailing(code)));
        }

        if (description == null && previewLines.isEmpty()) {
            return ParsedPreview.EMPTY;
        }
        return new ParsedPreview(description, dedent(previewLines));
    }

    @NotNull
    private static Charset resolveCharset(@NotNull IFile file) {
        String encoding = null;
        try {
            encoding = file.getCharset();
        } catch (CoreException e) {
            // Fall through to the default
        }
        if (encoding != null) {
            try {
                return Charset.forName(encoding);
            } catch (IllegalArgumentException e) {
                // Unknown or malformed charset name - fall through to the default
            }
        }
        return StandardCharsets.UTF_8;
    }

    /**
     * Removes block comment spans from a line, carrying the "inside a block comment" state
     * across lines. Text following a closing marker counts as code.
     */
    @NotNull
    private static String stripBlockComments(@NotNull String line, @NotNull boolean[] inBlockComment) {
        StringBuilder code = new StringBuilder(line.length());
        int pos = 0;
        while (pos < line.length()) {
            if (inBlockComment[0]) {
                int end = line.indexOf("*/", pos);
                if (end < 0) {
                    break;
                }
                inBlockComment[0] = false;
                pos = end + 2;
            } else {
                int start = line.indexOf("/*", pos);
                if (start < 0) {
                    code.append(line, pos, line.length());
                    break;
                }
                code.append(line, pos, start);
                inBlockComment[0] = true;
                pos = start + 2;
            }
        }
        return code.toString();
    }

    /**
     * Removes the common leading indentation so a preview does not start with a wide gutter.
     */
    @NotNull
    private static List<String> dedent(@NotNull List<String> lines) {
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            int indent = 0;
            while (indent < line.length() && line.charAt(indent) == ' ') {
                indent++;
            }
            if (indent < line.length()) {
                minIndent = Math.min(minIndent, indent);
            }
        }
        if (minIndent == Integer.MAX_VALUE || minIndent == 0) {
            return truncateAll(lines);
        }
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(line.length() > minIndent ? line.substring(minIndent) : line.trim());
        }
        return truncateAll(result);
    }

    @NotNull
    private static List<String> truncateAll(@NotNull List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(truncate(line, MAX_PREVIEW_LINE_LENGTH));
        }
        return result;
    }

    @NotNull
    private static String truncate(@NotNull String text, int maxLength) {
        return text.length() <= maxLength
            ? text
            : text.substring(0, maxLength - ELLIPSIS.length()) + ELLIPSIS;
    }

    @NotNull
    private static String stripTrailing(@NotNull String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    @NotNull
    private static String expandTabs(@NotNull String line) {
        if (line.indexOf('\t') < 0) {
            return line;
        }
        StringBuilder result = new StringBuilder(line.length() + TAB_WIDTH);
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\t') {
                do {
                    result.append(' ');
                } while (result.length() % TAB_WIDTH != 0);
            } else {
                result.append(c);
            }
        }
        return result.toString();
    }
}
