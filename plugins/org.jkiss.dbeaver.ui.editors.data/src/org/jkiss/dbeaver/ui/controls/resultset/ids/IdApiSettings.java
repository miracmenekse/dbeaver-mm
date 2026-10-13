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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.handlers.HandlerUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * dbeaver-mm K52/K53: Plus Settings > ID API account... The API itself (urls, body, headers) comes
 * from the id-api.properties handed out next to dbeaver.exe; the user only types their user name and
 * password, which go to their own {@code ~/.dbeaver-mm/id-api.properties}.
 */
public class IdApiSettings extends AbstractHandler {

    @Override
    public Object execute(ExecutionEvent event) {
        new AccountDialog(HandlerUtil.getActiveShell(event)).open();
        return null;
    }

    private static Properties own() {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(IdApiClient.CONFIG, StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (IOException ignored) {
            // first time
        }
        return config;
    }

    private static class AccountDialog extends Dialog {
        private Text user;
        private Text password;

        AccountDialog(Shell shell) {
            super(shell);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("ID API account");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            ((GridLayout) area.getLayout()).numColumns = 2;
            Path shared = IdApiClient.sharedConfig();
            Label info = new Label(area, SWT.WRAP);
            info.setText(IdApiClient.isConfigured()
                ? "API settings: " + (shared != null ? shared : IdApiClient.CONFIG) + "\nEnter your own user name and password for the ID API."
                : "No filled id-api.properties found next to dbeaver.exe. Ask for the file and put it into\n"
                    + (shared != null ? shared.getParent() : "the dbeaver folder (where dbeaver.exe is)") + ", or use Import... below.");
            info.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
            Properties config = own();
            new Label(area, SWT.NONE).setText("User name:");
            user = new Text(area, SWT.BORDER);
            user.setText(config.getProperty("login.user", ""));
            user.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            new Label(area, SWT.NONE).setText("Password:");
            password = new Text(area, SWT.BORDER | SWT.PASSWORD);
            password.setText(config.getProperty("login.password", ""));
            GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
            data.widthHint = 300;
            password.setLayoutData(data);
            return area;
        }

        @Override
        protected void createButtonsForButtonBar(Composite parent) {
            createButton(parent, 2, "Import...", false);
            super.createButtonsForButtonBar(parent);
        }

        @Override
        protected void buttonPressed(int id) {
            if (id != 2) {
                super.buttonPressed(id);
                return;
            }
            // when the dbeaver folder isn't writable: take the handed-out file into the user's own one
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[]{"*.properties;*.txt", "*.*"});
            String file = dialog.open();
            if (file == null) {
                return;
            }
            Properties imported = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
                imported.load(reader);
                save(imported);
            } catch (IOException e) {
                MessageDialog.openError(getShell(), "ID API account", "Can't import " + file + ":\n" + e.getMessage());
            }
        }

        @Override
        protected void okPressed() {
            try {
                save(own());
            } catch (IOException e) {
                MessageDialog.openError(getShell(), "ID API account", "Can't save " + IdApiClient.CONFIG + ":\n" + e.getMessage());
                return;
            }
            super.okPressed();
        }

        private void save(Properties config) throws IOException {
            config.setProperty("login.user", user.getText().strip());
            config.setProperty("login.password", password.getText());
            Files.createDirectories(IdApiClient.CONFIG.getParent());
            try (Writer writer = Files.newBufferedWriter(IdApiClient.CONFIG, StandardCharsets.UTF_8)) {
                config.store(writer, "dbeaver-mm ID API account (Plus Settings > ID API account...)");
            }
            // the old token belongs to the old account
            Files.deleteIfExists(IdApiClient.CONFIG.resolveSibling("token"));
        }
    }
}
