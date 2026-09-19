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
package org.jkiss.dbeaver.ui.data.hints;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDResultSetModel;
import org.jkiss.dbeaver.model.data.DBDValueRow;
import org.jkiss.dbeaver.model.data.hints.DBDCellHintProvider;
import org.jkiss.dbeaver.model.data.hints.DBDValueHint;
import org.jkiss.dbeaver.model.data.hints.DBDValueHintContext;
import org.jkiss.dbeaver.model.data.hints.ValueHintText;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;

/**
 * dbeaver-mm FK dictionary PoC, A2: shows the dictionary description next to a foreign key value
 * as a cell hint ({@code 2  Aktif}) instead of appending it to the cell text ({@code 2 | Aktif}).
 * <p>
 * The grid draws hints in the dimmed hint color, so the id keeps full contrast; copy, filter and
 * sort see the real value only; and the hint can be switched off in the result set hint settings.
 * <p>
 * Registered with {@code association="false"}: association providers are skipped when the
 * binding's referrers are not loaded yet, which is exactly the lazy-metadata case the PoC had to
 * work around. {@link FkDictionaryLabels} does its own FK detection instead.
 */
public class FkDictionaryHintProvider implements DBDCellHintProvider {

    /** Chars reserved for the label when a column is packed; the grid caps this at 16 anyway. */
    private static final int LABEL_HINT_SIZE = 16;

    @Nullable
    @Override
    public DBDValueHint[] getCellHints(
        @NotNull DBDResultSetModel model,
        @NotNull DBDAttributeBinding attribute,
        @NotNull DBDValueRow row,
        @Nullable Object value,
        @NotNull EnumSet<DBDValueHint.HintType> types,
        int options
    ) {
        if (!types.contains(DBDValueHint.HintType.STRING)) {
            return null;
        }
        String label = FkDictionaryLabels.getLabel(attribute, value);
        if (CommonUtils.isEmpty(label)) {
            return null;
        }
        if (FkDictionaryLabels.isExternal(attribute)) {
            return new DBDValueHint[]{new ExternalHint(label)};
        }
        return new DBDValueHint[]{new ValueHintText(label, label, null)};
    }

    /**
     * dbeaver-mm K1: label resolved through a virtual FK into another connection; the grid paints it
     * in its own color so it is not mistaken for data of this database.
     */
    public static class ExternalHint extends ValueHintText {
        ExternalHint(@NotNull String label) {
            super(label, label, null);
        }
    }

    @Override
    public int getAttributeHintSize(@NotNull DBDValueHintContext context, @NotNull DBDAttributeBinding attribute) {
        return FkDictionaryLabels.getAssociation(attribute) != null ? LABEL_HINT_SIZE : 0;
    }

    @Override
    public void cacheRequiredData(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBDValueHintContext context,
        @NotNull Collection<DBDAttributeBinding> attributes,
        @NotNull Collection<? extends DBDValueRow> rows,
        boolean cleanupCache
    ) {
        if (cleanupCache) {
            FkDictionaryLabels.invalidateLabels();
        }
        for (DBDAttributeBinding attr : attributes) {
            // Nested attributes (documents, arrays) are not table foreign keys
            if (attr.getParentObject() != null || FkDictionaryLabels.getAssociation(attr) == null) {
                continue;
            }
            int index = attr.getOrdinalPosition();
            List<Object> values = new ArrayList<>(rows.size());
            for (DBDValueRow row : rows) {
                Object[] rowValues = row.getValues();
                if (index >= 0 && index < rowValues.length) {
                    values.add(rowValues[index]);
                }
            }
            FkDictionaryLabels.prefetch(monitor, attr, values);
        }
    }
}
