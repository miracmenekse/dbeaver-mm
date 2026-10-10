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
package org.jkiss.dbeaver.ui.controls.resultset.conf;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.swt.SWT;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetContainer;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.controls.resultset.handler.ResultSetHandlerMain;
import org.jkiss.dbeaver.ui.controls.resultset.ids.FetchIdsAction;

/**
 * dbeaver-mm K37: the buttons next to the table editor's Properties / Data / Diagram tabs, the same
 * actions as the grid's Edit menu items "Fetch IDs for new rows" and "Add changes to conf package".
 */
public class GridToolsHandler extends AbstractHandler {

    public static final String CMD_FETCH_IDS = "org.jkiss.dbeaver.mm.grid.fetchIds";
    public static final String CMD_ADD_TO_CONF = "org.jkiss.dbeaver.mm.grid.addToConfPackage";

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ResultSetViewer viewer = findViewer(part);
        String title = event.getCommand().getId().equals(CMD_FETCH_IDS) ? "Fetch IDs" : "Add to conf package";
        if (viewer == null) {
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title, "Open the Data tab first.", SWT.ICON_INFORMATION);
            return null;
        }
        if (event.getCommand().getId().equals(CMD_FETCH_IDS)) {
            FetchIdsAction action = FetchIdsAction.create(viewer);
            if (action == null || !action.isEnabled()) {
                UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title,
                    action == null ? "This table has no single-column key." : "No new row with an empty key.", SWT.ICON_INFORMATION);
                return null;
            }
            action.run();
        } else {
            AddToConfPackageAction action = new AddToConfPackageAction(viewer);
            if (!action.isEnabled()) {
                UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title, "The grid has no unsaved changes.", SWT.ICON_INFORMATION);
                return null;
            }
            action.run();
        }
        return null;
    }

    /** The grid with focus, else the Data tab of the active table editor. */
    @Nullable
    private static ResultSetViewer findViewer(@Nullable IWorkbenchPart part) {
        if (ResultSetHandlerMain.getActiveResultSet(part) instanceof ResultSetViewer viewer) {
            return viewer;
        }
        if (part instanceof MultiPageEditorPart editor && editor.getSelectedPage() instanceof IResultSetContainer container
            && container.getResultSetController() instanceof ResultSetViewer viewer) {
            return viewer;
        }
        return null;
    }
}
