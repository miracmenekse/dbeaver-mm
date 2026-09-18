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
package org.jkiss.dbeaver.ui.mm.workbench;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;

/**
 * B2 focus mode: toggles the active editor between maximized and restored.
 * <p>
 * In DBeaver the result set lives inside the SQL editor, so maximizing the editor is exactly
 * "only the SQL editor and its results". Uses the public {@link IWorkbenchPage#setPartState}
 * API only - no internal workbench classes.
 */
public class FocusModeHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
        IWorkbenchPage page = window == null ? null : window.getActivePage();
        IEditorPart editor = page == null ? null : page.getActiveEditor();
        if (editor == null) {
            return null;
        }
        IWorkbenchPartReference ref = page.getReference(editor);
        boolean maximized = page.getPartState(ref) == IWorkbenchPage.STATE_MAXIMIZED;
        page.setPartState(ref, maximized ? IWorkbenchPage.STATE_RESTORED : IWorkbenchPage.STATE_MAXIMIZED);
        // Keep keyboard focus in the editor so the shortcut can be pressed again right away
        page.activate(editor);
        return null;
    }
}
