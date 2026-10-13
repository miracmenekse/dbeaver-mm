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
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.ControlListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.code.Nullable;

import java.nio.file.Path;
import java.util.Map;

/**
 * dbeaver-mm K47: Plus > Start session... / Stop session. The user names the session (the task or
 * what they are about to do); every step until Stop goes to its own file. While recording, a small
 * "REC" label sits in the window's bottom left corner so nobody is recorded without seeing it.
 */
public class SessionToggleHandler extends AbstractHandler implements IElementUpdater {

    public static final String COMMAND_ID = "org.jkiss.dbeaver.mm.session.toggle";

    @Nullable
    private static Shell indicator;

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        if (SessionRecorder.isRecording()) {
            Path file = SessionRecorder.stop();
            showIndicator(shell);
            MessageDialog.openInformation(shell, "Session recording", "Session saved:\n" + file);
        } else {
            if (!SessionRecorder.isEnabled()) {
                RecordingConsent.ask(shell);
                if (!SessionRecorder.isEnabled()) {
                    return null;
                }
            }
            InputDialog dialog = new InputDialog(shell, "Start session",
                "Session name (task id or what you are about to do):", "",
                text -> text.isBlank() ? "Enter a name" : null);
            if (dialog.open() != Window.OK) {
                return null;
            }
            SessionRecorder.start(dialog.getValue());
        }
        PlatformUI.getWorkbench().getService(ICommandService.class).refreshElements(COMMAND_ID, null);
        showIndicator(shell);
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        String name = SessionRecorder.sessionName();
        element.setText(name == null ? "Start session..." : "Stop session (" + name + ")");
    }

    static void showIndicator(@Nullable Shell active) {
        if (indicator != null && !indicator.isDisposed()) {
            indicator.getParent().removeControlListener(FOLLOW);
            indicator.dispose();
            indicator = null;
        }
        String name = SessionRecorder.sessionName();
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        Shell parent = window == null ? active : window.getShell();
        if (name == null || parent == null) {
            return;
        }
        indicator = new Shell(parent, SWT.NO_TRIM | SWT.TOOL | SWT.NO_FOCUS);
        indicator.setLayout(new FillLayout());
        Label label = new Label(indicator, SWT.NONE);
        label.setBackground(indicator.getDisplay().getSystemColor(SWT.COLOR_RED));
        label.setForeground(indicator.getDisplay().getSystemColor(SWT.COLOR_WHITE));
        label.setText("  ● REC  " + name + "  ");
        parent.addControlListener(FOLLOW);
        place();
        indicator.setVisible(true);
    }

    private static final ControlListener FOLLOW = new ControlListener() {
        @Override
        public void controlMoved(ControlEvent e) {
            place();
        }

        @Override
        public void controlResized(ControlEvent e) {
            place();
        }
    };

    private static void place() {
        if (indicator == null || indicator.isDisposed()) {
            return;
        }
        Point size = indicator.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        Rectangle area = ((Shell) indicator.getParent()).getBounds();
        indicator.setBounds(area.x + 24, area.y + area.height - size.y - 12, size.x, size.y);
    }
}
