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
package org.jkiss.dbeaver.ui.controls.resultset.ids;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.TableEditor;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * dbeaver-mm K107: Plus Settings > Send repositories... A list of connection names (after the tenant) with
 * the repository for each. Only the deploy.repo.* entries of the user's own id-api.properties change; the
 * account and the saved sign-in are left alone. Rows come from the project's connections, open or not.
 */
public class DeployRepoSettings extends AbstractHandler {

    private static final String ADDED = "dbeaver-mm.added";

    @Override
    public Object execute(ExecutionEvent event) {
        new RepoDialog(HandlerUtil.getActiveShell(event)).open();
        return null;
    }

    /** Connection parts of the active project's connections; empty when there is no active project. */
    private static List<String> connectionParts() {
        List<String> parts = new ArrayList<>();
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project == null) {
            return parts;
        }
        for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
            parts.add(DeployRequest.connectionPart(container.getName()));
        }
        return parts;
    }

    private static class RepoDialog extends Dialog {
        private Table table;
        private Runnable commitEdit;

        RepoDialog(Shell shell) {
            super(shell);
        }

        @Override
        protected int getShellStyle() {
            return super.getShellStyle() | SWT.RESIZE;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Send repositories");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            Label info = new Label(area, SWT.WRAP);
            info.setText("A connection's repository is picked by the name after its tenant. Leave Repository empty for no entry.");
            info.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

            table = new Table(area, SWT.BORDER | SWT.FULL_SELECTION);
            table.setHeaderVisible(true);
            table.setLinesVisible(true);
            TableColumn connection = new TableColumn(table, SWT.NONE);
            connection.setText("Connection (after tenant)");
            connection.setWidth(260);
            TableColumn repository = new TableColumn(table, SWT.NONE);
            repository.setText("Repository");
            repository.setWidth(260);
            GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
            tableData.widthHint = 540;
            tableData.heightHint = 260;
            table.setLayoutData(tableData);

            Map<String, String> rows = DeployRequest.repoRows(IdApiClient.load(), connectionParts());
            for (Map.Entry<String, String> row : rows.entrySet()) {
                addItem(row.getKey(), row.getValue());
            }
            // dbeaver-mm K107: double click edits a cell in place; the name is typed only for added rows
            table.addListener(SWT.MouseDoubleClick, e -> {
                TableItem item = table.getItem(new Point(e.x, e.y));
                int column = item == null ? -1 : columnAt(item, e.x);
                if (column == 0 && item.getData(ADDED) == null) {
                    return;
                }
                if (column >= 0) {
                    editCell(item, column);
                }
            });

            Button add = new Button(area, SWT.PUSH);
            add.setText("Add row");
            add.addListener(SWT.Selection, e -> {
                TableItem item = new TableItem(table, SWT.NONE);
                item.setData(ADDED, Boolean.TRUE);
                table.setSelection(item);
                editCell(item, 0);
            });
            return area;
        }

        private void addItem(String key, String repo) {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(new String[]{key, repo});
        }

        private int columnAt(TableItem item, int x) {
            for (int i = 0; i < table.getColumnCount(); i++) {
                Rectangle bounds = item.getBounds(i);
                if (x >= bounds.x && x < bounds.x + bounds.width) {
                    return i;
                }
            }
            return -1;
        }

        /** Enter or leaving the cell keeps the value, Esc drops it. */
        private void editCell(TableItem item, int column) {
            if (commitEdit != null) {
                commitEdit.run();
            }
            TableEditor editor = new TableEditor(table);
            editor.grabHorizontal = true;
            editor.grabVertical = true;
            Text text = new Text(table, SWT.SINGLE);
            text.setText(item.getText(column));
            text.selectAll();
            boolean[] done = {false};
            Runnable finish = () -> {
                if (done[0]) {
                    return;
                }
                done[0] = true;
                commitEdit = null;
                String value = text.getText().strip();
                text.dispose();
                editor.dispose();
                item.setText(column, value);
            };
            commitEdit = finish;
            text.addListener(SWT.FocusOut, e -> finish.run());
            text.addListener(SWT.Traverse, e -> {
                // Enter finishes the cell; without doit = false the dialog's default button (OK) would close it
                if (e.detail == SWT.TRAVERSE_RETURN) {
                    e.doit = false;
                    finish.run();
                } else if (e.detail == SWT.TRAVERSE_ESCAPE) {
                    e.doit = false;
                    done[0] = true;
                    commitEdit = null;
                    text.dispose();
                    editor.dispose();
                }
            });
            editor.setEditor(text, item, column);
            text.setFocus();
        }

        @Override
        protected void okPressed() {
            // an open cell is finished first, so its typed value is saved too
            if (commitEdit != null) {
                commitEdit.run();
            }
            try {
                save();
            } catch (IOException e) {
                MessageDialog.openError(getShell(), "Send repositories",
                    "Can't save " + IdApiClient.CONFIG + ":\n" + e.getMessage());
                return;
            }
            super.okPressed();
        }

        private void save() throws IOException {
            Properties own = new Properties();
            try (Reader reader = Files.newBufferedReader(IdApiClient.CONFIG, StandardCharsets.UTF_8)) {
                own.load(reader);
            } catch (IOException ignored) {
                // first time
            }
            Map<String, String> rows = new LinkedHashMap<>();
            for (TableItem item : table.getItems()) {
                rows.put(item.getText(0).strip(), item.getText(1));
            }
            DeployRequest.applyRepoRows(own, IdApiClient.load(), rows);
            Files.createDirectories(IdApiClient.CONFIG.getParent());
            try (Writer writer = Files.newBufferedWriter(IdApiClient.CONFIG, StandardCharsets.UTF_8)) {
                own.store(writer, "dbeaver-mm ID API account (Plus Settings > ID API account...)");
            }
        }
    }
}
