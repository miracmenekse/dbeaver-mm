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
package org.jkiss.dbeaver.ui.inlinefkpicker.ui;

import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextViewer;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.SqlWhereAnalyzer;

/**
 * Opens the {@link WhereParamsDialog} for a SQL editor.
 * <p>
 * Entry point shared by the keyboard command and by the Recent SQL Scripts panel, which calls it
 * right after opening a saved script so its filters can be filled in immediately.
 */
public final class WhereParamsAction {

    private static final Log log = Log.getLog(WhereParamsAction.class);

    private WhereParamsAction() {
    }

    /**
     * Analyses the editor's first statement and, when it has editable conditions, shows the
     * parameter form. Does nothing (silently) when there is nothing to fill in.
     *
     * @param scriptName name shown in the dialog title
     * @return true when the dialog was shown and the user confirmed it
     */
    public static boolean openFor(@Nullable SQLEditorBase editor, @NotNull String scriptName) {
        if (editor == null) {
            return false;
        }
        try {
            ITextViewer viewer = editor.getTextViewer();
            if (viewer == null || viewer.getDocument() == null) {
                return false;
            }
            IDocument document = viewer.getDocument();
            String text = document.get();
            int[] range = firstStatementRange(editor, text);

            SqlWhereAnalyzer.Result scan = SqlWhereAnalyzer.analyze(text, range[0], range[1]);
            if (scan.isEmpty()) {
                return false;
            }
            WhereParamsDialog dialog = new WhereParamsDialog(
                editor.getSite().getShell(), scriptName, scan, editor.getExecutionContext());
            if (dialog.open() != IDialogConstants.OK_ID) {
                return false;
            }
            int applied = dialog.applyTo(document);
            if (applied > 0) {
                // Leave the caret where the user can immediately run the query
                viewer.setSelectedRange(range[0], 0);
                viewer.revealRange(range[0], 0);
            }
            return true;
        } catch (Throwable e) {
            log.debug("Cannot open script parameters form", e);
            return false;
        }
    }

    /**
     * Range of the first real statement, so a multi-statement script does not get its conditions
     * and FROM tables merged. Falls back to the whole document when the range cannot be found.
     */
    private static int[] firstStatementRange(@NotNull SQLEditorBase editor, @NotNull String text) {
        int probe = 0;
        while (probe < text.length() && Character.isWhitespace(text.charAt(probe))) {
            probe++;
        }
        try {
            SQLScriptElement statement = editor.extractQueryAtPos(probe);
            if (statement != null && statement.getLength() > 0) {
                return new int[]{statement.getOffset(), statement.getOffset() + statement.getLength()};
            }
        } catch (Throwable e) {
            log.debug("Cannot extract first statement", e);
        }
        return new int[]{0, text.length()};
    }
}
