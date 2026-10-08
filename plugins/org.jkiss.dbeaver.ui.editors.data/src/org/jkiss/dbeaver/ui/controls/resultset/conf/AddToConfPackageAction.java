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
package org.jkiss.dbeaver.ui.controls.resultset.conf;

import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.data.resultset.ResultSetSaveSettings;
import org.jkiss.dbeaver.model.edit.DBEPersistAction;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * dbeaver-mm K25: "Add changes to conf package ..." - the grid's unsaved changes become SQL (the same
 * script as "Generate SQL") appended to a task's package file. The changes can then be discarded so
 * the tab closes without saving anything to the database.
 */
public class AddToConfPackageAction extends Action {

    @NotNull
    private final ResultSetViewer viewer;

    public AddToConfPackageAction(@NotNull ResultSetViewer viewer) {
        super("Add changes to conf package ...");
        this.viewer = viewer;
        setEnabled(viewer.isDirty());
    }

    @Override
    public void run() {
        PackageDialog dialog = new PackageDialog(viewer.getControl().getShell());
        if (dialog.open() != IDialogConstants.OK_ID) {
            return;
        }
        try {
            List<DBEPersistAction> actions = new ArrayList<>();
            UIUtils.runInProgressService(monitor -> actions.addAll(viewer.generateChangesScript(monitor, new ResultSetSaveSettings())));
            if (actions.isEmpty()) {
                viewer.setStatus("No changes to add", DBPMessageType.ERROR);
                return;
            }
            String sql = SQLUtils.generateScript(viewer.getDataSource(), actions.toArray(new DBEPersistAction[0]), false);
            Path file = ConfPackages.append(dialog.task, dialog.description, sql);
            if (dialog.discard) {
                viewer.rejectChanges();
            }
            viewer.setStatus(actions.size() + " statements added to " + file.getFileName(), DBPMessageType.INFORMATION);
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError("Conf package", "Changes could not be added to the conf package", e);
        }
    }

    private static class PackageDialog extends Dialog {
        // Kept for the next use in this session: one task usually spans several tables
        private static String lastTask = "";
        private static boolean lastDiscard = true;

        private Combo taskCombo;
        private Text descText;
        private Button discardCheck;
        String task;
        String description;
        boolean discard;

        PackageDialog(Shell shell) {
            super(shell);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Add changes to conf package");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(2, false));

            UIUtils.createControlLabel(area, "Task ID");
            taskCombo = new Combo(area, SWT.DROP_DOWN);
            taskCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            taskCombo.setItems(ConfPackages.listTasks().toArray(new String[0]));
            taskCombo.setText(lastTask);

            UIUtils.createControlLabel(area, "Description");
            descText = new Text(area, SWT.BORDER);
            GridData descData = new GridData(SWT.FILL, SWT.CENTER, true, false);
            descData.widthHint = 360;
            descText.setLayoutData(descData);
            descText.setText(ConfPackages.readDescription(lastTask));
            taskCombo.addModifyListener(e -> {
                // An existing package brings its description; a new task id starts empty
                if (taskCombo.indexOf(taskCombo.getText()) >= 0) {
                    descText.setText(ConfPackages.readDescription(taskCombo.getText()));
                }
                updateOk();
            });

            discardCheck = new Button(area, SWT.CHECK);
            discardCheck.setText("Discard the changes in the grid afterwards (nothing is saved to the database)");
            discardCheck.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
            discardCheck.setSelection(lastDiscard);
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            super.createButtonsForButtonBar(parent);
            updateOk();
        }

        private void updateOk() {
            Button ok = getButton(IDialogConstants.OK_ID);
            if (ok != null) {
                ok.setEnabled(!taskCombo.getText().isBlank());
            }
        }

        @Override
        protected void okPressed() {
            task = taskCombo.getText().strip();
            description = descText.getText().strip();
            discard = discardCheck.getSelection();
            lastTask = task;
            lastDiscard = discard;
            super.okPressed();
        }
    }
}
