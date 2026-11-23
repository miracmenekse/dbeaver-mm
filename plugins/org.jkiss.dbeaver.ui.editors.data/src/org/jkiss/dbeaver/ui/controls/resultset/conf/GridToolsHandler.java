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
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetContainer;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetSelection;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.controls.resultset.audit.AuditFields;
import org.jkiss.dbeaver.ui.controls.resultset.audit.AuditSettings;
import org.jkiss.dbeaver.ui.controls.resultset.handler.ResultSetHandlerMain;
import org.jkiss.dbeaver.ui.controls.resultset.ids.FetchIdsAction;

import java.sql.Timestamp;

/**
 * dbeaver-mm K37: the buttons next to the table editor's Properties / Data / Diagram tabs, the same
 * actions as the grid's Edit menu items "Fetch IDs for new rows" and "Add changes to conf package".
 * dbeaver-mm K152: "Now" and "cuser" buttons write an audit value into the selected cell(s).
 */
public class GridToolsHandler extends AbstractHandler {

    private static final Log log = Log.getLog(GridToolsHandler.class);

    public static final String CMD_FETCH_IDS = "org.jkiss.dbeaver.mm.grid.fetchIds";
    public static final String CMD_ADD_TO_CONF = "org.jkiss.dbeaver.mm.grid.addToConfPackage";
    public static final String CMD_SET_NOW = "org.jkiss.dbeaver.mm.grid.setNow";
    public static final String CMD_SET_CUSER = "org.jkiss.dbeaver.mm.grid.setCuser";

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchPart part = HandlerUtil.getActivePart(event);
        ResultSetViewer viewer = findViewer(part);
        String commandId = event.getCommand().getId();
        String title = CMD_FETCH_IDS.equals(commandId) ? "Fetch IDs"
            : CMD_ADD_TO_CONF.equals(commandId) ? "Add to conf package"
            : CMD_SET_NOW.equals(commandId) ? "Now" : "cuser";
        if (viewer == null) {
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title, "Open the Data tab first.", SWT.ICON_INFORMATION);
            return null;
        }
        if (CMD_SET_NOW.equals(commandId) || CMD_SET_CUSER.equals(commandId)) {
            setAuditValue(event, viewer, title, CMD_SET_NOW.equals(commandId));
            return null;
        }
        if (CMD_FETCH_IDS.equals(commandId)) {
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

    /**
     * dbeaver-mm K152: writes now() or the configured cuser into the selected cell(s), reusing the
     * audit values of K151 (a real timestamp for NOW, because string cells are quoted in SQL).
     */
    private static void setAuditValue(ExecutionEvent event, ResultSetViewer viewer, String title, boolean now) {
        String cuser = AuditSettings.cuser();
        if (!now && cuser.isEmpty()) {
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title,
                "Set cuser under Plus Settings › Audit user.", SWT.ICON_INFORMATION);
            return;
        }
        IResultSetSelection selection = viewer.getSelection();
        if (selection.isEmpty()) {
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title, "Select a cell.", SWT.ICON_INFORMATION);
            return;
        }
        Object value = now ? new Timestamp(System.currentTimeMillis()) : AuditFields.valueOf(AuditFields.Kind.USER, cuser);
        try {
            for (Object element : selection) {
                DBDAttributeBinding attribute = selection.getElementAttribute(element);
                ResultSetRow row = selection.getElementRow(element);
                if (attribute != null && row != null) {
                    viewer.updateCellValue(attribute, row, selection.getElementRowIndexes(element), value, true);
                }
            }
        } catch (DBException e) {
            log.error(e);
            UIUtils.showMessageBox(HandlerUtil.getActiveShell(event), title, "Can't update the cell: " + e.getMessage(), SWT.ICON_ERROR);
        }
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
