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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetCellLocation;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetModel;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetValueController;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.data.IValueController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K24: fills the key column of the grid's new rows with ids from the id API.
 * The API is called through the user's own script {@code ~/.dbeaver-mm/id-api.sh <count> <table> <domain>},
 * so its URL, headers and token never live in DBeaver. The domain is asked once per connection and
 * kept as a connection tag. Rows are not saved; the user copies the ids into related tables.
 */
public class FetchIdsAction extends Action {

    static final String[] DOMAINS = {"domain_config", "pcm", "ntf_engine", "crm_customer_information"};
    private static final String DOMAIN_TAG = "mm.idDomain";
    private static final Path SCRIPT = Path.of(System.getProperty("user.home"), ".dbeaver-mm", "id-api.sh");
    private static final Pattern IDS = Pattern.compile("\"ids\"\\s*:\\s*\\[([^\\]]*)]");
    private static final long TIMEOUT_SEC = 45; // the script may log in and retry, each curl call has 15s

    @NotNull
    private final ResultSetViewer viewer;
    @NotNull
    private final DBPDataSourceContainer container;
    @NotNull
    private final DBDAttributeBinding keyAttr;
    @NotNull
    private final String table;
    @NotNull
    private final List<ResultSetRow> rows;

    private FetchIdsAction(
        @NotNull ResultSetViewer viewer,
        @NotNull DBPDataSourceContainer container,
        @NotNull DBDAttributeBinding keyAttr,
        @NotNull String table,
        @NotNull List<ResultSetRow> rows
    ) {
        super("Fetch IDs for new rows (" + rows.size() + ")");
        this.viewer = viewer;
        this.container = container;
        this.keyAttr = keyAttr;
        this.table = table;
        this.rows = rows;
        setEnabled(!rows.isEmpty());
    }

    /** Adds "Fetch IDs" and "Change ID domain" when the grid shows a table with a one-column key. */
    public static void contribute(@NotNull IMenuManager menu, @NotNull ResultSetViewer viewer) {
        DBSDataContainer dataContainer = viewer.getDataContainer();
        ResultSetModel model = viewer.getModel();
        DBDRowIdentifier identifier = model.getDefaultRowIdentifier();
        if (dataContainer == null || dataContainer.getDataSource() == null || identifier == null
            || identifier.getAttributes().size() != 1) {
            return;
        }
        DBPDataSourceContainer container = dataContainer.getDataSource().getContainer();
        DBDAttributeBinding keyAttr = identifier.getAttributes().get(0);
        List<ResultSetRow> rows = new ArrayList<>();
        for (ResultSetRow row : model.getAllRows()) {
            if (row.getState() == ResultSetRow.STATE_ADDED && DBUtils.isNullValue(model.getCellValue(keyAttr, row))) {
                rows.add(row);
            }
        }
        String table = identifier.getEntity().getName().toLowerCase(Locale.ROOT);
        menu.add(new FetchIdsAction(viewer, container, keyAttr, table, rows));
        String domain = getDomain(container);
        menu.add(new Action("Change ID domain (" + (domain == null ? "not set" : domain) + ") ...") {
            @Override
            public void run() {
                askDomain(container);
            }
        });
    }

    @Override
    public void run() {
        String domain = getDomain(container);
        if (domain == null && (domain = askDomain(container)) == null) {
            return;
        }
        String finalDomain = domain;
        int count = rows.size();
        new AbstractJob("Fetch IDs for " + table) {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    List<Long> ids = fetchIds(count, table, finalDomain);
                    UIUtils.asyncExec(() -> apply(ids));
                } catch (Exception e) {
                    DBWorkbench.getPlatformUI().showError("Fetch IDs",
                        "IDs for " + table + " (" + finalDomain + ") could not be fetched.\n" + e.getMessage(), e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void apply(@NotNull List<Long> ids) {
        ResultSetModel model = viewer.getModel();
        int filled = 0;
        for (int i = 0; i < rows.size() && i < ids.size(); i++) {
            ResultSetRow row = rows.get(i);
            // The user may have deleted the row or typed an id while the script was running
            if (!model.getAllRows().contains(row) || !DBUtils.isNullValue(model.getCellValue(keyAttr, row))) {
                continue;
            }
            new ResultSetValueController(viewer, new ResultSetCellLocation(keyAttr, row), IValueController.EditType.NONE, null)
                .updateValue(ids.get(i), true);
            filled++;
        }
        if (ids.size() < rows.size()) {
            viewer.setStatus("Only " + ids.size() + " of " + rows.size() + " IDs came back, " + filled + " filled",
                DBPMessageType.ERROR);
        } else {
            viewer.setStatus(filled + " IDs filled: " + ids, DBPMessageType.INFORMATION);
        }
    }

    @Nullable
    private static String getDomain(@NotNull DBPDataSourceContainer container) {
        String domain = container.getTags().get(DOMAIN_TAG);
        return domain == null || domain.isEmpty() ? null : domain;
    }

    /** Asks for the connection's id domain and keeps it; null when cancelled. */
    @Nullable
    private static String askDomain(@NotNull DBPDataSourceContainer container) {
        String[] buttons = new String[DOMAINS.length + 1];
        System.arraycopy(DOMAINS, 0, buttons, 0, DOMAINS.length);
        buttons[DOMAINS.length] = "Cancel";
        int choice = new MessageDialog(
            UIUtils.getActiveWorkbenchShell(),
            "ID domain",
            null,
            "Which ID domain does " + container.getName() + " use?\nThe choice is kept for this connection.",
            MessageDialog.QUESTION,
            DOMAINS.length,
            buttons).open();
        if (choice < 0 || choice >= DOMAINS.length) {
            return null;
        }
        container.setTagValue(DOMAIN_TAG, DOMAINS[choice]);
        container.persistConfiguration();
        return DOMAINS[choice];
    }

    @NotNull
    static List<Long> fetchIds(int count, @NotNull String table, @NotNull String domain) throws IOException, InterruptedException {
        if (!Files.isRegularFile(SCRIPT)) {
            throw new IOException(SCRIPT + " not found");
        }
        Process process = new ProcessBuilder("bash", SCRIPT.toString(), String.valueOf(count), table, domain).start();
        // The output is one short JSON line, it fits in the pipe buffer until the process ends
        if (!process.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("id-api.sh did not finish in " + TIMEOUT_SEC + " seconds");
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.exitValue() == 2) {
            throw new IOException("Login failed, check the login request in id-api.sh.\n" + err);
        }
        if (process.exitValue() != 0) {
            throw new IOException(err.isEmpty() ? "id-api.sh exited with " + process.exitValue() : err);
        }
        return parseIds(out);
    }

    /** {@code {"ids":[6005170,6005171],...}} -> [6005170, 6005171] */
    @NotNull
    static List<Long> parseIds(@NotNull String json) throws IOException {
        Matcher matcher = IDS.matcher(json);
        if (!matcher.find()) {
            throw new IOException("No \"ids\" in the response: " + json.strip());
        }
        List<Long> ids = new ArrayList<>();
        for (String id : matcher.group(1).split(",")) {
            String value = id.strip().replace("\"", "");
            if (!value.isEmpty()) {
                ids.add(Long.parseLong(value));
            }
        }
        return ids;
    }
}
