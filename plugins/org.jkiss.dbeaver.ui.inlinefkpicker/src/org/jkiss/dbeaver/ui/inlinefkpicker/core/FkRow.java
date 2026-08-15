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
 * A single candidate row shown in the picker: the primary-key id, a human-readable label, and the
 * type-aware SQL literal that will be inserted into the editor if this row is chosen.
 */
public final class FkRow {

    private final String idText;
    private final String label;
    private final String literal;

    public FkRow(String idText, String label, String literal) {
        this.idText = idText;
        this.label = label;
        this.literal = literal;
    }

    /** The primary-key value rendered as text (left column). */
    public String getIdText() {
        return idText;
    }

    /** The meaningful/display column value (right column). May be empty. */
    public String getLabel() {
        return label;
    }

    /** Type-aware SQL literal to insert (e.g. {@code 42} or {@code 'abc'}). */
    public String getLiteral() {
        return literal;
    }
}
