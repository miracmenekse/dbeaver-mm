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
package org.jkiss.dbeaver.ui.inlinefkpicker.trigger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;

import java.util.Map;

/**
 * dbeaver-mm K6: navigator context menu toggle "Use for SQL auto connection" on a connection.
 * Only tagged connections are searched when the SQL editor picks a connection by table names.
 */
public class ToggleAutoConnectHandler extends AbstractHandler implements IElementUpdater {

    @Override
    public Object execute(ExecutionEvent event) {
        DBPDataSourceContainer container = selectedContainer(HandlerUtil.getCurrentSelection(event));
        if (container != null) {
            InlineFkService.setAutoConnect(container, !InlineFkService.isAutoConnect(container));
        }
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        ISelection selection = PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null ? null
            : PlatformUI.getWorkbench().getActiveWorkbenchWindow().getSelectionService().getSelection();
        DBPDataSourceContainer container = selectedContainer(selection);
        element.setChecked(container != null && InlineFkService.isAutoConnect(container));
    }

    @Nullable
    private static DBPDataSourceContainer selectedContainer(@Nullable ISelection selection) {
        if (selection instanceof IStructuredSelection ss && ss.getFirstElement() instanceof DBNDataSource node) {
            return node.getDataSourceContainer();
        }
        return null;
    }
}
