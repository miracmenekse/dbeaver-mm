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
import org.eclipse.jface.dialogs.IInputValidator;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.navigator.DBNDataSource;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;
import org.jkiss.utils.CommonUtils;

/**
 * dbeaver-mm K13: sets the connection's short code. Typing {@code <code>.} in a SQL editor then
 * lists that connection's tables only - cheaper than searching every connection.
 */
public class SetShortCodeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        DBPDataSourceContainer container = selectedContainer(HandlerUtil.getCurrentSelection(event));
        if (container == null) {
            return null;
        }
        IInputValidator validator = value -> CommonUtils.isEmpty(value) || value.matches("[A-Za-z0-9_]+")
            ? null
            : "Use letters, digits and _ only";
        InputDialog dialog = new InputDialog(
            HandlerUtil.getActiveShell(event),
            "SQL short code",
            "Short code for " + container.getName() + " (type \"<code>.\" in SQL to list its tables).\nLeave empty to remove.",
            CommonUtils.notEmpty(InlineFkService.getShortCode(container)),
            validator);
        if (dialog.open() == InputDialog.OK) {
            InlineFkService.setShortCode(container, dialog.getValue());
        }
        return null;
    }

    @Nullable
    private static DBPDataSourceContainer selectedContainer(@Nullable ISelection selection) {
        if (selection instanceof IStructuredSelection ss && ss.getFirstElement() instanceof DBNDataSource node) {
            return node.getDataSourceContainer();
        }
        return null;
    }
}
