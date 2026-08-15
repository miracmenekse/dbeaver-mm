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
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.inlinefkpicker.ui.WhereParamsAction;

/**
 * Opens the script parameter form for the active SQL editor.
 * <p>
 * The Recent SQL Scripts panel opens the same form automatically after opening a script; this
 * command makes it reachable for scripts opened any other way.
 */
public class OpenWhereParamsHandler extends AbstractHandler {

    @Override
    public Object execute(@NotNull ExecutionEvent event) {
        IEditorPart editor = HandlerUtil.getActiveEditor(event);
        if (editor instanceof SQLEditorBase sqlEditor) {
            String name = editor.getEditorInput() == null ? "SQL" : editor.getEditorInput().getName();
            WhereParamsAction.openFor(sqlEditor, name);
        }
        return null;
    }
}
