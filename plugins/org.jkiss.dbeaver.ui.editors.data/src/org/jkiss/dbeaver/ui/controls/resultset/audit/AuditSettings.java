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
package org.jkiss.dbeaver.ui.controls.resultset.audit;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.handlers.HandlerUtil;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * dbeaver-mm K150: Plus Settings > Audit user (cuser)... The user name written into cuser
 * columns of audit fields. Kept in the user's own {@code ~/.dbeaver-mm/audit.properties}.
 */
public class AuditSettings extends AbstractHandler {

    private static final Path CONFIG = Path.of(System.getProperty("user.home"), ".dbeaver-mm", "audit.properties");

    @Override
    public Object execute(ExecutionEvent event) {
        new AuditDialog(HandlerUtil.getActiveShell(event)).open();
        return null;
    }

    /**
     * dbeaver-mm K150: the configured cuser, or an empty string when nothing is configured yet.
     */
    public static String cuser() {
        return load().getProperty("cuser", "");
    }

    private static Properties load() {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            config.load(reader);
        } catch (IOException ignored) {
            // first time: no file yet
        }
        return config;
    }

    private static class AuditDialog extends Dialog {
        private Text userField;

        AuditDialog(Shell shell) {
            super(shell);
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Audit user (cuser)...");
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            ((GridLayout) area.getLayout()).numColumns = 2;
            new Label(area, SWT.NONE).setText("Audit user:");
            userField = new Text(area, SWT.BORDER);
            userField.setText(cuser());
            userField.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            return area;
        }

        @Override
        protected void okPressed() {
            try {
                save();
            } catch (IOException e) {
                MessageDialog.openError(getShell(), "Audit user (cuser)...", "Can't save " + CONFIG + ":\n" + e.getMessage());
                return;
            }
            super.okPressed();
        }

        private void save() throws IOException {
            Properties config = load();
            config.setProperty("cuser", userField.getText().strip());
            Files.createDirectories(CONFIG.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG, StandardCharsets.UTF_8)) {
                config.store(writer, "dbeaver-mm audit user (Plus Settings > Audit user (cuser)...)");
            }
        }
    }
}
