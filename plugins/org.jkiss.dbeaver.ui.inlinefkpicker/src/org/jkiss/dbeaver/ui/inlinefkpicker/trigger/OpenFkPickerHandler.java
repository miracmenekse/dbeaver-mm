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
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.graphics.Point;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkColumnRef;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.SqlCaretAnalyzer;
import org.jkiss.dbeaver.ui.inlinefkpicker.ui.FkPickerPopup;

/**
 * Manual trigger (Ctrl+Alt+Space by default): opens the inline FK picker when the caret sits in a
 * supported {@code xxx_id =} / {@code xxx_id IN (} context of the active SQL editor.
 */
public class OpenFkPickerHandler extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        IEditorPart editorPart = HandlerUtil.getActiveEditor(event);
        if (!(editorPart instanceof SQLEditorBase editor)) {
            return null;
        }
        ITextViewer viewer = editor.getTextViewer();
        if (viewer == null || viewer.getDocument() == null) {
            return null;
        }
        Point sel = viewer.getSelectedRange();
        String docText = viewer.getDocument().get();
        SQLScriptElement stmt = editor.extractQueryAtPos(sel.x);
        int stmtStart = stmt != null ? stmt.getOffset() : 0;
        int stmtEnd = stmt != null ? stmt.getOffset() + stmt.getLength() : docText.length();
        FkColumnRef ref = SqlCaretAnalyzer.analyze(docText, sel.x, stmtStart, stmtEnd);
        if (ref == null) {
            return null;
        }
        DBCExecutionContext context = editor.getExecutionContext();
        FkPickerPopup.trigger(viewer, context, ref);
        return null;
    }
}
