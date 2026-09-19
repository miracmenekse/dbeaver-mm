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

import org.eclipse.jface.text.ITextViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IEditorReference;
import org.eclipse.ui.IPartListener2;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.IWindowListener;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;
import org.eclipse.ui.IWorkbenchPartReference;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.sql.SQLScriptElement;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkColumnRef;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.SqlCaretAnalyzer;
import org.jkiss.dbeaver.ui.inlinefkpicker.ui.FkPickerPopup;

/**
 * Wires the automatic trigger. On workbench startup it attaches a lightweight key listener to the
 * text widget of every SQL editor (existing and future). When the user types {@code =} or
 * {@code (}, the caret context is re-checked and the picker opens if it matches an FK-id position.
 * The manual command works independently of this class.
 */
public class InlineFkStartup implements IStartup {

    private static final Log log = Log.getLog(InlineFkStartup.class);

    private static final String INSTALLED_KEY = "org.jkiss.dbeaver.ui.inlinefkpicker.installed";

    @Override
    public void earlyStartup() {
        UIUtils.asyncExec(this::hookWorkbench);
    }

    private void hookWorkbench() {
        try {
            IWorkbench workbench = PlatformUI.getWorkbench();
            workbench.addWindowListener(new WindowListener());
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                hookWindow(window);
            }
        } catch (Throwable e) {
            log.debug("Failed to hook inline FK auto-trigger", e);
        }
    }

    private void hookWindow(IWorkbenchWindow window) {
        if (window == null) {
            return;
        }
        window.getPartService().addPartListener(new PartListener());
        for (IWorkbenchPage page : window.getPages()) {
            for (IEditorReference ref : page.getEditorReferences()) {
                IEditorPart part = ref.getEditor(false);
                if (part != null) {
                    install(part);
                }
            }
        }
    }

    private void install(IWorkbenchPart part) {
        if (!(part instanceof SQLEditorBase editor)) {
            return;
        }
        ITextViewer viewer = editor.getTextViewer();
        if (viewer == null) {
            return;
        }
        StyledText widget = viewer.getTextWidget();
        if (widget == null || widget.isDisposed() || widget.getData(INSTALLED_KEY) != null) {
            return;
        }
        widget.setData(INSTALLED_KEY, Boolean.TRUE);
        Listener listener = e -> onKeyDown(editor, e);
        widget.addListener(SWT.KeyDown, listener);
    }

    private void onKeyDown(SQLEditorBase editor, Event e) {
        char c = e.character;
        if (c != '=' && c != '(') {
            return;
        }
        Display display = e.display;
        // Defer until after the typed character has been inserted into the document.
        display.asyncExec(() -> {
            try {
                ITextViewer viewer = editor.getTextViewer();
                if (viewer == null || viewer.getDocument() == null) {
                    return;
                }
                StyledText widget = viewer.getTextWidget();
                if (widget == null || widget.isDisposed() || !widget.isFocusControl()) {
                    return;
                }
                Point sel = viewer.getSelectedRange();
                String docText = viewer.getDocument().get();
                SQLScriptElement stmt = editor.extractQueryAtPos(sel.x);
                int stmtStart = stmt != null ? stmt.getOffset() : 0;
                int stmtEnd = stmt != null ? stmt.getOffset() + stmt.getLength() : docText.length();
                FkColumnRef ref = SqlCaretAnalyzer.analyze(docText, sel.x, stmtStart, stmtEnd);
                if (ref == null) {
                    return;
                }
                // dbeaver-mm K6: pick the connection the statement's tables live in first
                AutoConnectionSelector.resolveThen(editor, ref, context -> FkPickerPopup.trigger(viewer, context, ref));
            } catch (Throwable ex) {
                log.debug("Inline FK auto-trigger evaluation failed", ex);
            }
        });
    }

    private final class WindowListener implements IWindowListener {
        @Override
        public void windowActivated(IWorkbenchWindow window) {
        }

        @Override
        public void windowDeactivated(IWorkbenchWindow window) {
        }

        @Override
        public void windowClosed(IWorkbenchWindow window) {
        }

        @Override
        public void windowOpened(IWorkbenchWindow window) {
            hookWindow(window);
        }
    }

    private final class PartListener implements IPartListener2 {
        @Override
        public void partOpened(IWorkbenchPartReference partRef) {
            install(partRef.getPart(false));
        }

        @Override
        public void partActivated(IWorkbenchPartReference partRef) {
            install(partRef.getPart(false));
        }

        @Override
        public void partBroughtToTop(IWorkbenchPartReference partRef) {
        }

        @Override
        public void partClosed(IWorkbenchPartReference partRef) {
        }

        @Override
        public void partDeactivated(IWorkbenchPartReference partRef) {
        }

        @Override
        public void partHidden(IWorkbenchPartReference partRef) {
        }

        @Override
        public void partVisible(IWorkbenchPartReference partRef) {
        }

        @Override
        public void partInputChanged(IWorkbenchPartReference partRef) {
        }
    }
}
