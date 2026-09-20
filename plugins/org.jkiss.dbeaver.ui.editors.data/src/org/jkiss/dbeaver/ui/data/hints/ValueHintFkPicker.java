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

import org.eclipse.swt.graphics.Point;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.hints.DBDValueHint;
import org.jkiss.dbeaver.ui.data.DBDValueHintActionHandler;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetCellLocation;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetValueController;
import org.jkiss.dbeaver.ui.data.IValueController;


/**
 * dbeaver-mm K5: in-cell button on a dictionary FK cell. Click lists the referenced values with
 * their labels ({@code 2  Orta}) in a small searchable popup; picking one writes it into the cell
 * like a normal edit (Save / Cancel as usual). Same values as the filter box {@code column =} list.
 */
record ValueHintFkPicker(@NotNull DBDAttributeBinding attribute, @NotNull ResultSetRow row, @Nullable Object value)
    implements DBDValueHint, DBDValueHintActionHandler {

    @NotNull
    @Override
    public HintType getHintType() {
        return HintType.ACTION;
    }

    @Nullable
    @Override
    public String getHintText() {
        return null;
    }

    @NotNull
    @Override
    public String getHintDescription() {
        return "Pick a value from " + attribute.getName() + "'s referenced table";
    }

    @NotNull
    @Override
    public DBPImage getHintIcon() {
        return UIIcon.DROP_DOWN;
    }

    @NotNull
    @Override
    public String getActionText() {
        return "Pick value";
    }

    @Override
    public void performAction(@NotNull IResultSetController controller, @NotNull Point location, long state) {
        String readOnly = controller.getAttributeReadOnlyStatus(attribute, true, true);
        if (readOnly != null) {
            DBWorkbench.getPlatformUI().showMessageBox("Pick value", "Column is read-only: " + readOnly, false);
            return;
        }
        new FkValuePickerPopup(attribute, value, picked -> new ResultSetValueController(
            controller,
            new ResultSetCellLocation(attribute, row),
            IValueController.EditType.NONE,
            null
        ).updateValue(picked, true)).open(controller.getControl(), location);
    }
}
