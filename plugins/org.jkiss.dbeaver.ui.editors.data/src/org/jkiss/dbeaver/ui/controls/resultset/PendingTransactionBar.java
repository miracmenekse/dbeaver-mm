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
package org.jkiss.dbeaver.ui.controls.resultset;

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSavepoint;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.qm.QMTransactionState;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.runtime.qm.DefaultExecutionHandler;
import org.jkiss.dbeaver.ui.ActionUtils;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.internal.ResultSetMessages;

/**
 * dbeaver-mm Öneri 3/B (UI_UX, user decision 2026-09-18): Commit / Rollback left the main toolbar.
 * This strip under the result grid brings them back only while they matter - the connection is in
 * manual-commit mode and has modifying statements that are not committed yet (production
 * connections start in manual mode). Otherwise it takes no space at all.
 * <p>
 * Same source of truth as the toolbar transaction monitor: the query manager's transaction state
 * for the viewer's execution context, refreshed on QM events. The buttons run the regular
 * Commit / Rollback commands, so confirmations and smart-commit behave as before.
 */
class PendingTransactionBar extends Composite {

    private static final String CMD_COMMIT = "org.jkiss.dbeaver.core.commit";
    private static final String CMD_ROLLBACK = "org.jkiss.dbeaver.core.rollback";
    /** Coalesce bursts of QM events (a script run fires many) into one refresh. */
    private static final int REFRESH_DELAY_MS = 250;

    private final ResultSetViewer viewer;
    private final Label messageLabel;
    private final QMHandler qmHandler = new QMHandler();
    private boolean refreshScheduled;

    PendingTransactionBar(@NotNull Composite parent, @NotNull ResultSetViewer viewer) {
        super(parent, SWT.NONE);
        this.viewer = viewer;

        GridLayout layout = new GridLayout(4, false);
        layout.marginHeight = 3;
        layout.marginWidth = 8;
        setLayout(layout);
        GridData data = new GridData(GridData.FILL_HORIZONTAL);
        data.exclude = true;
        setLayoutData(data);
        setVisible(false);

        Label icon = new Label(this, SWT.NONE);
        icon.setImage(DBeaverIcons.getImage(DBIcon.SMALL_WARNING));
        messageLabel = new Label(this, SWT.NONE);
        messageLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        createButton(ResultSetMessages.controls_resultset_viewer_txn_commit, CMD_COMMIT);
        createButton(ResultSetMessages.controls_resultset_viewer_txn_rollback, CMD_ROLLBACK);

        QMUtils.registerHandler(qmHandler);
        addDisposeListener(e -> QMUtils.unregisterHandler(qmHandler));
    }

    private void createButton(@NotNull String text, @NotNull String commandId) {
        Button button = new Button(this, SWT.PUSH);
        button.setText(text);
        button.addSelectionListener(SelectionListener.widgetSelectedAdapter(
            e -> ActionUtils.runCommand(commandId, viewer.getSite())));
    }

    /**
     * Re-reads the transaction state; safe to call from any thread.
     */
    void scheduleRefresh() {
        UIUtils.asyncExec(() -> {
            if (isDisposed() || refreshScheduled) {
                return;
            }
            refreshScheduled = true;
            getDisplay().timerExec(REFRESH_DELAY_MS, () -> {
                refreshScheduled = false;
                refresh();
            });
        });
    }

    private void refresh() {
        if (isDisposed()) {
            return;
        }
        DBCExecutionContext context = viewer.getExecutionContext();
        QMTransactionState state = context == null ? null : QMUtils.getTransactionState(context);
        int pending = state != null && state.isTransactionMode() ? state.getUpdateCount() : 0;
        boolean show = pending > 0;
        if (show) {
            messageLabel.setText(NLS.bind(ResultSetMessages.controls_resultset_viewer_txn_pending, pending));
        }
        GridData data = (GridData) getLayoutData();
        if (data.exclude == show) {
            data.exclude = !show;
            setVisible(show);
            // Showing / hiding changes the height available to the grid
            viewer.getControl().layout(true, true);
        } else if (show) {
            layout(true, true);
        }
    }

    private class QMHandler extends DefaultExecutionHandler {
        @NotNull
        @Override
        public String getHandlerName() {
            return "Pending transaction bar";
        }

        @Override
        public void handleTransactionAutocommit(@NotNull DBCExecutionContext context, boolean autoCommit) {
            scheduleRefresh();
        }

        @Override
        public void handleTransactionCommit(@NotNull DBCExecutionContext context) {
            scheduleRefresh();
        }

        @Override
        public void handleTransactionRollback(@NotNull DBCExecutionContext context, @Nullable DBCSavepoint savepoint) {
            scheduleRefresh();
        }

        @Override
        public void handleStatementExecuteEnd(@NotNull DBCStatement statement, long rows, @Nullable Throwable error) {
            scheduleRefresh();
        }

        @Override
        public void handleContextClose(@NotNull DBCExecutionContext context) {
            scheduleRefresh();
        }
    }
}
