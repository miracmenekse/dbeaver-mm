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

/**
 * One editable filter condition found by {@link SqlWhereAnalyzer}: a comparison of a column
 * against a literal or a {@code ?} placeholder, e.g. {@code bfs.shrt_code = ?} or
 * {@code cc.is_actv = 1}.
 * <p>
 * Column-to-column comparisons (join predicates such as {@code cc.cmd_def_id = cd.cmd_def_id})
 * are not conditions in this sense and are never reported.
 * <p>
 * Pure data, no Eclipse/DBeaver dependencies.
 */
public final class WhereCondition {

    private final String qualifier;
    private final String columnName;
    private final int valueStart;
    private final int valueEnd;
    private final String valueText;
    private final boolean inWhereClause;

    public WhereCondition(
        String qualifier,
        String columnName,
        int valueStart,
        int valueEnd,
        String valueText,
        boolean inWhereClause
    ) {
        this.qualifier = qualifier;
        this.columnName = columnName;
        this.valueStart = valueStart;
        this.valueEnd = valueEnd;
        this.valueText = valueText;
        this.inWhereClause = inWhereClause;
    }

    /** Optional {@code alias.} prefix as written in the script; {@code null} when absent. */
    public String getQualifier() {
        return qualifier;
    }

    /** The compared column as written, e.g. {@code shrt_code}. */
    public String getColumnName() {
        return columnName;
    }

    /** Document offset where the current value starts. */
    public int getValueStart() {
        return valueStart;
    }

    /** Document offset just past the current value. */
    public int getValueEnd() {
        return valueEnd;
    }

    /** Current value exactly as written: {@code ?}, {@code 1} or {@code 'ABC'}. */
    public String getValueText() {
        return valueText;
    }

    /** True when the script has no value yet, i.e. the value is a {@code ?} placeholder. */
    public boolean isPlaceholder() {
        return "?".equals(valueText);
    }

    /** True when the condition sits in the WHERE clause, false when it is in a JOIN ... ON. */
    public boolean isInWhereClause() {
        return inWhereClause;
    }

    /** {@code alias.column} when qualified, otherwise just the column name. */
    public String getDisplayColumn() {
        return qualifier == null ? columnName : qualifier + "." + columnName;
    }

    /** Human readable form used in previews, e.g. {@code bfs.shrt_code = ?}. */
    public String getDisplayText() {
        return getDisplayColumn() + " = " + valueText;
    }

    @Override
    public String toString() {
        return "WhereCondition{" + getDisplayText()
            + ", value=[" + valueStart + "," + valueEnd + "]"
            + (inWhereClause ? ", WHERE" : ", ON") + "}";
    }
}
