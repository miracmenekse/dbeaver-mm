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
package org.jkiss.dbeaver.ui.controls.resultset.session;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.Nullable;

import java.util.Map;

/**
 * dbeaver-mm K49: asks once, on first start, whether to record and under which nickname, and again
 * from Plus > Automatic recording. Records only ever carry the nickname, so they stay anonymous.
 */
public class RecordingConsent extends AbstractHandler implements IElementUpdater {

    public static final String COMMAND_ID = "org.jkiss.dbeaver.mm.session.consent";

    // The Fellowship of the Ring (2001)
    private static final String[] NICKNAMES = {
        "Frodo", "Sam", "Merry", "Pippin", "Bilbo", "Gandalf", "Aragorn", "Legolas", "Gimli", "Boromir",
        "Elrond", "Galadriel", "Arwen", "Celeborn", "Haldir", "Saruman", "Gollum", "Isildur", "Lurtz", "Butterbur"
    };

    private static final String INFO =
        "dbeaver-mm can record what you do (tables opened, searches, SQL, conf package changes) to\n" +
        "~/.dbeaver-mm/kayit. Purpose: to collect data so these systems can be run by automation in the future.\n\n" +
        "Records are kept and published only under the nickname below, never under your name.\n" +
        "Pick a Fellowship name or type your own. You can change this any time under Plus > Automatic recording.";

    /** First start: ask only if the user was never asked. */
    public static void askOnce(@Nullable Shell shell) {
        if (!SessionRecorder.isAsked()) {
            ask(shell);
        }
    }

    public static void ask(@Nullable Shell shell) {
        new ConsentDialog(shell).open();
        SessionToggleHandler.showIndicator(shell); // turning off may have stopped a session
        PlatformUI.getWorkbench().getService(ICommandService.class).refreshElements(COMMAND_ID, null);
        PlatformUI.getWorkbench().getService(ICommandService.class).refreshElements(SessionToggleHandler.COMMAND_ID, null);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        ask(HandlerUtil.getActiveShell(event));
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        element.setChecked(SessionRecorder.isEnabled());
        element.setText(SessionRecorder.isEnabled()
            ? "Automatic recording (on, as " + SessionRecorder.nickname() + ")..."
            : "Automatic recording (off)...");
    }

    private static class ConsentDialog extends Dialog {
        private Combo nick;
        private Button on;

        ConsentDialog(@Nullable Shell shell) {
            super(shell);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Automatic recording");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            ((GridLayout) area.getLayout()).numColumns = 2;
            Label info = new Label(area, SWT.WRAP);
            info.setText(INFO);
            info.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
            new Label(area, SWT.NONE).setText("Nickname:");
            nick = new Combo(area, SWT.DROP_DOWN);
            nick.setItems(NICKNAMES);
            nick.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            String current = SessionRecorder.nickname();
            nick.setText(current != null ? current : NICKNAMES[(int) (Math.random() * NICKNAMES.length)]);
            nick.addModifyListener(e -> getButton(IDialogConstants.OK_ID).setEnabled(!nick.getText().isBlank()));
            on = new Button(area, SWT.CHECK);
            on.setText("Record automatically");
            on.setSelection(SessionRecorder.isEnabled());
            on.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
            return area;
        }

        @Override
        protected void okPressed() {
            SessionRecorder.configure(nick.getText(), on.getSelection());
            super.okPressed();
        }
    }
}
