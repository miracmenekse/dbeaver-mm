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
package org.jkiss.dbeaver.ui.mm.workbench.navpane;

import java.util.regex.Pattern;

// dbeaver-mm K139: tablo adından SELECT şablonu üretir (saf mantık, UI yok)
public final class SelectTemplate {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[a-z_][a-z0-9_$]*");

    private SelectTemplate() {
    }

    public static String select(String schema, String table) {
        String quotedTable = quote(table);
        if (schema == null || schema.isEmpty()) {
            return "SELECT * FROM " + quotedTable;
        }
        return "SELECT * FROM " + quote(schema) + "." + quotedTable;
    }

    private static String quote(String name) {
        String value = name == null ? "" : name;
        if (SIMPLE_NAME.matcher(value).matches()) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
