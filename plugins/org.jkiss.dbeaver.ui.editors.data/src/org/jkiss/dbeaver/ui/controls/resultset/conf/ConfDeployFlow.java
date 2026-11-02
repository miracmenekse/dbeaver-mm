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

import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Shell;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployCalls;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployLedger;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployRequest;
import org.jkiss.dbeaver.ui.controls.resultset.ids.IdApiClient;
import org.jkiss.dbeaver.ui.controls.resultset.session.SessionRecorder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * dbeaver-mm K102: the "Send..." button of the conf package panel. The deploy version and the settings
 * are read in a job; then one {@link ConfDeployDialog} per schema group, in package order. A window
 * ends with Send, Skip or Cancel; Cancel stops the rest. A summary lists what was sent, skipped and not sent.
 */
public final class ConfDeployFlow {

    // UI thread only: a second click must not open a second set of windows while one is running
    private static boolean running;

    private ConfDeployFlow() {
    }

    /** {@code scripts}: schema -> its statements, in the order the buttons show them. */
    public static void start(@NotNull Shell shell, @NotNull String task, @NotNull String description,
                             @NotNull Map<String, String> scripts, boolean fkOrderKnown) {
        if (running) {
            return;
        }
        running = true;
        Job.create("Prepare conf package send", monitor -> {
            // Settings file and ledger reads happen here, not on the UI thread
            Properties config = IdApiClient.load();
            Path tokenFile = IdApiClient.tokenFile();
            Path ledgerFile = tokenFile.resolveSibling("conf-sent.properties");
            List<ConfDeployDialog.Draft> drafts;
            try {
                String version = DeployCalls.fetchVersion(config);
                drafts = drafts(task, description, scripts, fkOrderKnown, version, config, tokenFile, ledgerFile);
            } catch (IOException | InterruptedException | RuntimeException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // Only the failure kind goes to the window: the message of other errors may carry the address
                String reason = e instanceof IOException && e.getMessage() != null
                    ? e.getMessage() : e.getClass().getSimpleName();
                UIUtils.asyncExec(() -> {
                    running = false;
                    DBWorkbench.getPlatformUI().showError("Send to repository",
                        "The deploy version could not be read. Nothing was sent.\n" + reason, (Throwable) null);
                });
                return Status.OK_STATUS;
            }
            UIUtils.asyncExec(() -> {
                try {
                    openWindows(shell, task, drafts);
                } finally {
                    running = false;
                }
            });
            return Status.OK_STATUS;
        }).schedule();
    }

    private static List<ConfDeployDialog.Draft> drafts(String task, String description, Map<String, String> scripts,
                                                        boolean fkOrderKnown, String version, Properties config,
                                                        Path tokenFile, Path ledgerFile) {
        List<ConfDeployDialog.Draft> drafts = new ArrayList<>();
        String note = config.getProperty("deploy.note", "").strip();
        List<String> repoOptions = DeployRequest.repoOptions(config);
        int total = scripts.size();
        int index = 0;
        for (Map.Entry<String, String> group : scripts.entrySet()) {
            index++;
            String repo = DeployRequest.repoFor(config, group.getKey());
            // LF only: the ledger hash and the uploaded file must match what the dialog sends
            String sql = group.getValue().replace("\r\n", "\n");
            drafts.add(new ConfDeployDialog.Draft(group.getKey(), index, total, task, repo, description,
                sql, version, repoOptions, note, fkOrderKnown,
                DeployLedger.find(ledgerFile, task, repo, sql), config, tokenFile, ledgerFile));
        }
        return drafts;
    }

    private static void openWindows(@NotNull Shell shell, @NotNull String task,
                                    @NotNull List<ConfDeployDialog.Draft> drafts) {
        List<String> sent = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> notSent = new ArrayList<>();
        boolean stopped = false;
        for (ConfDeployDialog.Draft draft : drafts) {
            String name = draft.schema().isEmpty() ? "(no schema)" : draft.schema();
            if (stopped || shell.isDisposed()) {
                stopped = true;
                notSent.add(name);
                continue;
            }
            ConfDeployDialog dialog = new ConfDeployDialog(shell, draft);
            dialog.open();
            switch (dialog.getOutcome()) {
                case SENT -> {
                    sent.add(name);
                    SessionRecorder.light("conf", null, task,
                        "deploy: " + draft.schema() + " HTTP " + dialog.getSentStatus() + " " + draft.version());
                }
                case SKIPPED -> skipped.add(name);
                case CANCELLED -> {
                    stopped = true;
                    notSent.add(name);
                }
            }
        }
        if (shell.isDisposed()) {
            return;
        }
        String summary = "Sent: " + (sent.isEmpty() ? "none" : String.join(", ", sent)) + "\n"
            + "Skipped: " + (skipped.isEmpty() ? "none" : String.join(", ", skipped)) + "\n"
            + "Not sent: " + (notSent.isEmpty() ? "none" : String.join(", ", notSent));
        MessageDialog.openInformation(shell, "Send to repository", summary);
    }
}
