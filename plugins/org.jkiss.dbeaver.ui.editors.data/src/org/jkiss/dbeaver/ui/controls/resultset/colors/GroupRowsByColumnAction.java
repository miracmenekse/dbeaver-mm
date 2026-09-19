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
package org.jkiss.dbeaver.ui.controls.resultset.colors;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.StringConverter;
import org.eclipse.swt.graphics.RGB;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.model.virtual.DBVEntity;
import org.jkiss.dbeaver.model.virtual.DBVGroupRowStriping;
import org.jkiss.dbeaver.model.virtual.DBVUtils;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;

import java.util.List;

/**
 * dbeaver-mm K3: one click "group rows by this column" on top of upstream's group row striping.
 * Rows are sorted by the column and consecutive groups get two alternating matte colors, so it is
 * obvious where a group starts and ends. Clicking again on the same column turns it off.
 * Colors and columns can still be tuned in the "Group row striping ..." dialog.
 */
public class GroupRowsByColumnAction extends Action {

    // Matte, low-saturation pair: cool slate / warm taupe, close to the grid background
    private static final RGB[] SOFT_DARK = {new RGB(47, 54, 61), new RGB(61, 54, 47)};
    private static final RGB[] SOFT_LIGHT = {new RGB(233, 239, 245), new RGB(245, 238, 229)};

    @NotNull
    private final ResultSetViewer viewer;
    @NotNull
    private final DBDAttributeBinding attr;
    private final boolean active;

    public GroupRowsByColumnAction(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attr) {
        super(null, AS_CHECK_BOX);
        this.viewer = viewer;
        this.attr = attr;
        this.active = isGroupedBy(viewer, attr);
        setText("Group rows by " + attr.getName());
        setChecked(active);
    }

    /** True if rows of this viewer are currently grouped by {@code attr} alone. */
    public static boolean isGroupedBy(@NotNull ResultSetViewer viewer, @NotNull DBDAttributeBinding attr) {
        DBSDataContainer dataContainer = viewer.getDataContainer();
        DBVEntity vEntity = dataContainer == null ? null : DBVUtils.getVirtualEntity(dataContainer, false);
        DBVGroupRowStriping grs = vEntity == null ? null : vEntity.getGroupRowStriping();
        return grs != null && grs.isEnabled() && grs.getColumnNames().equals(List.of(attr.getName()));
    }

    /** Default stripe colors for the current theme. */
    @NotNull
    static RGB[] softColors() {
        return UIStyles.isDarkTheme() ? SOFT_DARK : SOFT_LIGHT;
    }

    @Override
    public void run() {
        DBSDataContainer dataContainer = viewer.getDataContainer();
        if (dataContainer == null) {
            return;
        }
        DBVEntity vEntity = DBVUtils.getVirtualEntity(dataContainer, true);
        if (active) {
            vEntity.setGroupRowStriping(null);
        } else {
            RGB[] colors = softColors();
            DBVGroupRowStriping grs = new DBVGroupRowStriping();
            grs.setEnabled(true);
            grs.setSortByGroupColumns(true);
            grs.setColumnNames(List.of(attr.getName()));
            grs.setBackgroundColor1(StringConverter.asString(colors[0]));
            grs.setBackgroundColor2(StringConverter.asString(colors[1]));
            vEntity.setGroupRowStriping(grs);
        }
        vEntity.persistConfiguration();
        viewer.getModel().updateColorMapping(vEntity, true);
        // Re-fetch so rows come back sorted by the group column
        viewer.refreshData(null);
    }
}
