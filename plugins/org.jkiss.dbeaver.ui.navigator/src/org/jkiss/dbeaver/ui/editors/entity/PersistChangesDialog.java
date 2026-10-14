/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2025 DBeaver Corp and others
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
package org.jkiss.dbeaver.ui.editors.entity;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;

/**
 * dbeaver-mm K57: replaces the Yes/No/Cancel "Persist object changes" prompt.
 * Cancel ("Keep editing") is the default; writing requires typing the object name.
 */
class PersistChangesDialog extends Dialog {

    private final String objectName;
    private final String changes;
    @Nullable
    private final DBPDataSourceContainer container;
    private Button writeButton;

    PersistChangesDialog(Shell shell, String objectName, String changes, @Nullable DBPDataSourceContainer container) {
        super(shell);
        this.objectName = objectName;
        this.changes = changes;
        this.container = container;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Write to database?");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        GridLayoutFactory.swtDefaults().numColumns(2).spacing(10, 8).applyTo(area);
        Display display = area.getDisplay();

        Label icon = new Label(area, SWT.NONE);
        icon.setImage(display.getSystemImage(SWT.ICON_WARNING));
        GridDataFactory.swtDefaults().align(SWT.BEGINNING, SWT.BEGINNING).applyTo(icon);

        Label head = new Label(area, SWT.WRAP);
        head.setText("You are closing \"" + objectName + "\" with UNSAVED CHANGES.");
        head.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
        GridDataFactory.fillDefaults().grab(true, false).hint(460, SWT.DEFAULT).applyTo(head);

        Group details = new Group(area, SWT.NONE);
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(details);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(details);
        if (container != null) {
            boolean critical = container.getConnectionConfiguration().getConnectionType().isConfirmDataChange();
            Label conn = addRow(details, "Connection:",
                container.getName() + "  (" + container.getConnectionConfiguration().getConnectionType().getName() + ")");
            if (critical) {
                conn.setForeground(display.getSystemColor(SWT.COLOR_RED));
                conn.setFont(JFaceResources.getFontRegistry().getBold(JFaceResources.DIALOG_FONT));
            }
        }
        addRow(details, "Object:", objectName);
        addRow(details, "Changes:", changes.isEmpty() ? "-" : changes);

        Composite info = new Composite(area, SWT.BORDER);
        info.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
        GridLayoutFactory.swtDefaults().numColumns(2).applyTo(info);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(info);
        Label infoIcon = new Label(info, SWT.NONE);
        infoIcon.setImage(display.getSystemImage(SWT.ICON_INFORMATION));
        Label infoText = new Label(info, SWT.WRAP);
        infoText.setText("If you have added these changes to the conf package document, your data is being saved.");
        infoText.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
        GridDataFactory.fillDefaults().grab(true, false).hint(420, SWT.DEFAULT).applyTo(infoText);
        infoIcon.setBackground(info.getBackground());
        infoText.setBackground(info.getBackground());

        Label prompt = new Label(area, SWT.WRAP);
        prompt.setText("To write these changes, type the object name: " + objectName);
        GridDataFactory.fillDefaults().span(2, 1).applyTo(prompt);
        Text confirm = new Text(area, SWT.BORDER);
        GridDataFactory.fillDefaults().span(2, 1).grab(true, false).applyTo(confirm);
        confirm.addModifyListener(e -> writeButton.setEnabled(objectName.equals(confirm.getText().trim())));
        return area;
    }

    private static Label addRow(Composite parent, String name, String value) {
        new Label(parent, SWT.NONE).setText(name);
        Label v = new Label(parent, SWT.NONE);
        v.setText(value);
        return v;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, "Keep editing", true);
        createButton(parent, IDialogConstants.NO_ID, "Discard changes", false);
        writeButton = createButton(parent, IDialogConstants.YES_ID, "Write to DB", false);
        writeButton.setForeground(writeButton.getDisplay().getSystemColor(SWT.COLOR_RED));
        writeButton.setEnabled(false);
    }

    @Override
    protected void buttonPressed(int buttonId) {
        setReturnCode(buttonId);
        close();
    }
}
