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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * dbeaver-mm K52: Plus > ID API settings... edits {@code ~/.dbeaver-mm/id-api.properties} in a form,
 * so nobody has to find a hidden folder or fight Notepad's .txt on Windows. "Import..." loads a file
 * someone handed out (urls, headers); the user then types only their own user name and password.
 * Keys the form doesn't show (login.header.*, generate.header.*, generate.body) are kept as they are.
 */
public class IdApiSettings extends AbstractHandler {

    // key -> label; login.password is shown masked
    private static final Map<String, String> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("login.url", "Login URL:");
        FIELDS.put("login.user", "User name:");
        FIELDS.put("login.password", "Password:");
        FIELDS.put("login.contentType", "Login content type:");
        FIELDS.put("login.body", "Login body:");
        FIELDS.put("token.field", "Token field:");
        FIELDS.put("generate.url", "Generate URL:");
        FIELDS.put("generate.method", "Generate method:");
    }

    private static final Map<String, String> DEFAULTS = Map.of(
        "login.contentType", "application/x-www-form-urlencoded",
        "login.body", "username={user}&password={password}",
        "token.field", "access_token",
        "generate.url", "https://SERVER/generate?count={count}&table={table}&domain={domain}",
        "generate.method", "GET");

    @Override
    public Object execute(ExecutionEvent event) {
        Shell shell = HandlerUtil.getActiveShell(event);
        Properties config = load(IdApiClient.CONFIG);
        new SettingsDialog(shell, config).open();
        return null;
    }

    private static Properties load(Path file) {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (IOException ignored) {
            // first time: defaults
        }
        return config;
    }

    private static class SettingsDialog extends Dialog {
        private final Properties config;
        private final Map<String, Text> texts = new LinkedHashMap<>();

        SettingsDialog(Shell shell, Properties config) {
            super(shell);
            this.config = config;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("ID API settings");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            ((GridLayout) area.getLayout()).numColumns = 2;
            Label info = new Label(area, SWT.WRAP);
            info.setText("Saved to " + IdApiClient.CONFIG + ". {user} and {password} in the login body are filled from the fields above it.\n"
                + "The password stays on this computer only, in that file; don't share the file once it has your password.");
            GridData infoData = new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1);
            infoData.widthHint = 560;
            info.setLayoutData(infoData);
            for (Map.Entry<String, String> field : FIELDS.entrySet()) {
                new Label(area, SWT.NONE).setText(field.getValue());
                Text text = new Text(area, SWT.BORDER | (field.getKey().equals("login.password") ? SWT.PASSWORD : SWT.NONE));
                text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
                texts.put(field.getKey(), text);
            }
            fill(config);
            return area;
        }

        private void fill(Properties from) {
            texts.forEach((key, text) -> text.setText(from.getProperty(key, DEFAULTS.getOrDefault(key, ""))));
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
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[]{"*.properties;*.txt", "*.*"});
            String file = dialog.open();
            if (file != null) {
                Properties imported = load(Path.of(file));
                // keep what the user already typed for themselves
                for (String key : new String[]{"login.user", "login.password"}) {
                    if (!texts.get(key).getText().isEmpty() && imported.getProperty(key, "").isEmpty()) {
                        imported.setProperty(key, texts.get(key).getText());
                    }
                }
                config.putAll(imported);
                fill(config);
            }
        }

        @Override
        protected void okPressed() {
            texts.forEach((key, text) -> config.setProperty(key, text.getText().strip()));
            try {
                Files.createDirectories(IdApiClient.CONFIG.getParent());
                try (Writer writer = Files.newBufferedWriter(IdApiClient.CONFIG, StandardCharsets.UTF_8)) {
                    config.store(writer, "dbeaver-mm Fetch IDs settings (Plus > ID API settings...)");
                }
                // old token belongs to the old settings
                Files.deleteIfExists(IdApiClient.CONFIG.resolveSibling("token"));
            } catch (IOException e) {
                MessageDialog.openError(getShell(), "ID API settings", "Can't save " + IdApiClient.CONFIG + ":\n" + e.getMessage());
                return;
            }
            super.okPressed();
        }
    }
}
