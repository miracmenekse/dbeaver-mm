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
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployCalls;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployLedger;
import org.jkiss.dbeaver.ui.controls.resultset.ids.DeployRequest;

import java.io.IOException;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * dbeaver-mm K101: sends one schema group of a conf package to its repository. Fields are filled from
 * the package and can be changed for this send only; the package file is never written. Send uploads
 * in a job, the result comes back on the UI thread. The window stays open on an error, so the user can
 * retry or change the text.
 */
public class ConfDeployDialog extends Dialog {

    private static final Log log = Log.getLog(ConfDeployDialog.class);

    private static final int SKIP_ID = IDialogConstants.CLIENT_ID;
    private static final int TEXT_WIDTH = 560;
    private static final int SQL_LINES = 15;
    private static final int ERROR_LIMIT = 500;
    private static final RGB NOTE_RGB = new RGB(255, 243, 205);
    private static final RGB ERROR_RGB = new RGB(183, 28, 28);

    public enum Outcome { SENT, SKIPPED, CANCELLED }

    /**
     * One schema group to send: {@code index} of {@code total} (1-based), the values the fields start with,
     * the deploy version, the repository choices, the warning text, the earlier send of the same content
     * (or null), the settings and the file locations.
     */
    public record Draft(String schema, int index, int total, String task, String repo, String description,
                        String sql, String version, List<String> repoOptions, String note, boolean fkOrderKnown,
                        DeployLedger.Sent sentBefore, Properties config, Path tokenFile, Path ledgerFile) {
    }

    /** What the send job reports back: the upload result, or the reason no request went out. */
    private record Reply(DeployCalls.Result result, String failure) {
    }

    @NotNull
    private final Draft draft;
    private Composite area;
    private Text taskText;
    private Combo repoCombo;
    private Text descriptionText;
    private Label sentLabel;
    private Label errorLabel;
    private Text sqlText;
    private Button skipButton;
    private Button sendButton;
    private Button cancelButton;
    private Outcome outcome = Outcome.CANCELLED;
    private String sentRepo = "";
    private int sentStatus;
    private boolean sending;
    private String sendFailure = "";

    public ConfDeployDialog(@NotNull Shell parentShell, @NotNull Draft draft) {
        super(parentShell);
        this.draft = draft;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    /** Repository the package was sent to; empty unless the outcome is SENT. */
    public String getSentRepo() {
        return sentRepo;
    }

    /** HTTP status of the send; 0 unless the outcome is SENT. */
    public int getSentStatus() {
        return sentStatus;
    }

    @Override
    protected int getShellStyle() {
        return super.getShellStyle() | SWT.RESIZE;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Send to repository (" + draft.index() + "/" + draft.total() + ")");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        area = (Composite) super.createDialogArea(parent);

        Composite form = new Composite(area, SWT.NONE);
        form.setLayout(new GridLayout(2, false));
        form.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));

        new Label(form, SWT.NONE).setText("Jira ID");
        taskText = new Text(form, SWT.BORDER);
        taskText.setText(draft.task());
        taskText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(form, SWT.NONE).setText("Repository");
        repoCombo = new Combo(form, SWT.DROP_DOWN);
        repoCombo.setItems(draft.repoOptions().toArray(new String[0]));
        repoCombo.setText(draft.repo());
        repoCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        new Label(form, SWT.NONE).setText("Description");
        descriptionText = new Text(form, SWT.BORDER);
        descriptionText.setText(draft.description());
        descriptionText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        if (!draft.note().isBlank()) {
            Label note = new Label(area, SWT.WRAP);
            note.setText(draft.note());
            note.setBackground(UIUtils.getSharedColor(NOTE_RGB));
            note.setLayoutData(wrapData());
        }
        Label version = new Label(area, SWT.NONE);
        version.setText("Version: " + draft.version());
        version.setLayoutData(wrapData());

        sentLabel = new Label(area, SWT.WRAP);
        if (draft.sentBefore() != null) {
            sentLabel.setText("Already sent on " + draft.sentBefore().when() + " (" + draft.sentBefore().version()
                + ") with the same content");
        }
        sentLabel.setLayoutData(wrapData());

        if (!draft.fkOrderKnown()) {
            Label fk = new Label(area, SWT.WRAP);
            fk.setText("FK order unknown: statements are in file order");
            fk.setLayoutData(wrapData());
        }

        new Label(area, SWT.NONE).setText("SQL (editable, only for this send, not written to the package)");
        sqlText = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        sqlText.setFont(JFaceResources.getTextFont());
        sqlText.setText(draft.sql());
        GridData sqlData = new GridData(SWT.FILL, SWT.FILL, true, true);
        sqlData.widthHint = TEXT_WIDTH;
        sqlData.heightHint = SQL_LINES * sqlText.getLineHeight();
        sqlText.setLayoutData(sqlData);

        errorLabel = new Label(area, SWT.WRAP);
        errorLabel.setForeground(UIUtils.getSharedColor(ERROR_RGB));
        errorLabel.setLayoutData(wrapData());

        // Every field change re-checks what can be sent; the first check runs in create()
        taskText.addModifyListener(e -> validate());
        repoCombo.addModifyListener(e -> validate());
        descriptionText.addModifyListener(e -> validate());
        sqlText.addModifyListener(e -> validate());
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        cancelButton = createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        // A same-content send already exists: Skip is the default, so Enter does not send it again
        skipButton = createButton(parent, SKIP_ID, "Skip", draft.sentBefore() != null);
        sendButton = createButton(parent, IDialogConstants.OK_ID, "Send", draft.sentBefore() == null);
    }

    @Override
    public void create() {
        super.create();
        validate();
    }

    @Override
    protected void buttonPressed(int buttonId) {
        if (buttonId == SKIP_ID) {
            outcome = Outcome.SKIPPED;
            close();
        } else if (buttonId == IDialogConstants.OK_ID) {
            send();
        } else {
            super.buttonPressed(buttonId);
        }
    }

    /** Closing is refused while an upload is running. Default outcome CANCELLED (X and Esc too). */
    @Override
    public boolean close() {
        return !sending && super.close();
    }

    private static GridData wrapData() {
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false);
        data.widthHint = TEXT_WIDTH;
        return data;
    }

    /** Shows or hides a label and gives its space back when hidden. */
    private static void setShown(Label label, boolean shown) {
        if (label == null || label.isDisposed() || label.getVisible() == shown) {
            return;
        }
        label.setVisible(shown);
        ((GridData) label.getLayoutData()).exclude = !shown;
        label.getParent().layout(true, true);
    }

    /** SWT returns CRLF line ends on Windows; the package and its ledger hash use LF. */
    private String currentSql() {
        return sqlText.getText().replace("\r\n", "\n");
    }

    /** The reason Send is off, or null when the send may go. */
    private String problem() {
        if (!DeployRequest.validTask(taskText.getText().strip())) {
            return "Jira ID must look like ABC-123";
        }
        if (repoCombo.getText().isBlank()) {
            return "Choose a repository";
        }
        if (descriptionText.getText().isBlank()) {
            return "Description is empty";
        }
        String sql = currentSql();
        if (sql.isBlank()) {
            return "SQL is empty";
        }
        if (DeployRequest.mixesDdlAndDml(ConfPackages.splitStatements(sql))) {
            return "DDL and DML in one script: send is off";
        }
        return null;
    }

    private void validate() {
        if (sendButton == null || sendButton.isDisposed()) {
            return;
        }
        String problem = problem();
        String message = problem != null ? problem : sendFailure;
        errorLabel.setText(message);
        setShown(errorLabel, !message.isEmpty());
        // The earlier send refers to the package content it was sent with; once that is edited it no longer applies
        boolean sameContent = taskText.getText().strip().equals(draft.task())
            && repoCombo.getText().strip().equals(draft.repo())
            && currentSql().equals(draft.sql());
        setShown(sentLabel, draft.sentBefore() != null && sameContent);
        sendButton.setEnabled(problem == null && !sending);
    }

    private void setSending(boolean value) {
        sending = value;
        taskText.setEnabled(!value);
        repoCombo.setEnabled(!value);
        descriptionText.setEnabled(!value);
        sqlText.setEnabled(!value);
        skipButton.setEnabled(!value);
        cancelButton.setEnabled(!value);
        validate();
    }

    private void send() {
        String task = taskText.getText().strip();
        String repo = repoCombo.getText().strip();
        String description = descriptionText.getText().strip();
        String sql = currentSql();
        sendFailure = "";
        setSending(true);
        // Upload and ledger write run in the job; only the result is handled on the UI thread
        Job.create("Send conf package to repository", monitor -> {
            Reply reply = upload(task, repo, description, sql);
            if (reply.result() != null && reply.result().ok()) {
                recordSend(task, repo, sql);
            }
            UIUtils.asyncExec(() -> finish(repo, reply));
            return Status.OK_STATUS;
        }).schedule();
    }

    private Reply upload(String task, String repo, String description, String sql) {
        try {
            return new Reply(DeployCalls.upload(draft.config(), draft.tokenFile(), repo, task, draft.version(),
                description, sql), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Reply(null, "Interrupted");
        } catch (Exception e) {
            // A failure message can carry the address: it goes to the window only, never to the log
            return new Reply(null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void recordSend(String task, String repo, String sql) {
        try {
            String when = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date());
            DeployLedger.record(draft.ledgerFile(), task, repo, sql, draft.version(), when);
        } catch (IOException | RuntimeException e) {
            log.debug("Can't record the send in the ledger", e);
        }
    }

    private void finish(String repo, Reply reply) {
        Shell shell = getShell();
        if (shell == null || shell.isDisposed()) {
            return;
        }
        if (reply.result() != null && reply.result().ok()) {
            outcome = Outcome.SENT;
            sentRepo = repo;
            sentStatus = reply.result().status();
            sending = false;
            close();
            return;
        }
        if (reply.result() == null) {
            sendFailure = "Not sent: " + reply.failure();
        } else {
            String body = reply.result().body() == null ? "" : reply.result().body();
            if (body.length() > ERROR_LIMIT) {
                body = body.substring(0, ERROR_LIMIT) + "...";
            }
            sendFailure = "Not sent: HTTP " + reply.result().status() + "\n" + body;
        }
        setSending(false);
    }
}
