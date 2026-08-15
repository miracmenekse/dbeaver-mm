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

import java.util.List;

/**
 * Result of {@link SqlCaretAnalyzer}: a column-value editing context detected around the caret.
 * <p>
 * The picker shows the values of {@link #getColumnName() the compared column} taken from the table
 * that column belongs to. The table is not derived from the column name; it is resolved from the
 * statement's FROM/UPDATE list ({@link #getFromTables()}), honouring an optional
 * {@link #getQualifier() alias/table qualifier}. Pure data, no Eclipse/DBeaver dependencies.
 */
public final class FkColumnRef {

    /** Syntactic context in which the value is being written. */
    public enum Mode {
        /** {@code column = <caret>} - single value expected. */
        EQUALS,
        /** {@code column IN (<caret>} or {@code IN (1, 2, <caret>} - list of values. */
        IN_LIST
    }

    /** A table reference parsed from the FROM/UPDATE clause: table name and optional alias. */
    public static final class TableRef {
        private final String name;
        private final String alias;

        public TableRef(String name, String alias) {
            this.name = name;
            this.alias = alias;
        }

        public String getName() {
            return name;
        }

        public String getAlias() {
            return alias;
        }

        @Override
        public String toString() {
            return alias == null ? name : name + " " + alias;
        }
    }

    private final String columnName;
    private final String qualifier;
    private final List<TableRef> fromTables;
    private final Mode mode;
    private final int replaceStart;
    private final int replaceEnd;
    private final String prefix;
    private final boolean needsLeadingSpace;

    public FkColumnRef(
        String columnName,
        String qualifier,
        List<TableRef> fromTables,
        Mode mode,
        int replaceStart,
        int replaceEnd,
        String prefix,
        boolean needsLeadingSpace
    ) {
        this.columnName = columnName;
        this.qualifier = qualifier;
        this.fromTables = fromTables;
        this.mode = mode;
        this.replaceStart = replaceStart;
        this.replaceEnd = replaceEnd;
        this.prefix = prefix;
        this.needsLeadingSpace = needsLeadingSpace;
    }

    /** The compared column as written in the editor, e.g. {@code short_code}. */
    public String getColumnName() {
        return columnName;
    }

    /** Optional table/alias qualifier written as {@code qualifier.column}; {@code null} if absent. */
    public String getQualifier() {
        return qualifier;
    }

    /** Table references parsed from the current statement's FROM/UPDATE list (never null). */
    public List<TableRef> getFromTables() {
        return fromTables;
    }

    public Mode getMode() {
        return mode;
    }

    /** Document offset where the (partial) value the user started typing begins. */
    public int getReplaceStart() {
        return replaceStart;
    }

    /** Document offset where the caret currently is (end of the partial value). */
    public int getReplaceEnd() {
        return replaceEnd;
    }

    /** Partial value already typed after the operator, used as the initial search filter. */
    public String getPrefix() {
        return prefix;
    }

    /** True when a leading space should be prepended to the inserted literal for readability. */
    public boolean isNeedsLeadingSpace() {
        return needsLeadingSpace;
    }

    @Override
    public String toString() {
        return "FkColumnRef{" + (qualifier == null ? "" : qualifier + ".") + columnName
            + ", " + mode + ", from=" + fromTables
            + ", replace=[" + replaceStart + "," + replaceEnd + "], prefix='" + prefix + "'}";
    }
}
