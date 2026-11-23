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
package org.jkiss.dbeaver.ui.controls.resultset.audit;

/**
 * dbeaver-mm K150: audit columns of a row. cdate/sdate hold the change time (now()),
 * cuser holds the user name; the comparison is case-insensitive.
 */
public final class AuditFields {

    private AuditFields() {
    }

    public enum Kind {
        NOW,
        USER
    }

    /**
     * dbeaver-mm K150: the audit kind of a column, or null when the column is not an audit field.
     */
    public static Kind kindOf(String column) {
        if (column == null) {
            return null;
        }
        if ("cdate".equalsIgnoreCase(column) || "sdate".equalsIgnoreCase(column)) {
            return Kind.NOW;
        }
        if ("cuser".equalsIgnoreCase(column)) {
            return Kind.USER;
        }
        return null;
    }

    /**
     * dbeaver-mm K150: the value to write into an audit column. NOW is always now(),
     * USER carries the configured cuser (empty string when none is set).
     */
    public static String valueOf(Kind kind, String cuser) {
        if (kind == Kind.NOW) {
            return "now()";
        }
        if (kind == Kind.USER) {
            return cuser == null ? "" : cuser;
        }
        return "";
    }
}
