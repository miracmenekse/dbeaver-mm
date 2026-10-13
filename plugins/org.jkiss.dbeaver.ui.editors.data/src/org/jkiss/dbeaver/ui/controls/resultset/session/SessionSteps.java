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
package org.jkiss.dbeaver.ui.controls.resultset.session;

import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.commands.IExecutionListener;
import org.eclipse.core.commands.NotHandledException;
import org.eclipse.jface.dialogs.IPageChangeProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.ui.*;
import org.eclipse.ui.commands.ICommandService;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDDataFilter;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseNode;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.qm.QMMetaEvent;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.qm.QMEventAction;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementExecuteInfo;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.sql.SQLUtils;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.ui.controls.resultset.IResultSetController;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.editors.IDatabaseEditorInput;
import org.jkiss.dbeaver.ui.navigator.database.DatabaseNavigatorView;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K47: what feeds {@link SessionRecorder}. Every hook only reads names already in memory
 * (editor input, data filter, QM event text) and never resolves metadata or touches the database,
 * so a hook can't add a round trip to the UI thread (test/perf/RAPOR.md B1/B3). Scrolling, hover
 * and painting are never hooked.
 */
public final class SessionSteps implements IStartup {

    private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT");
    private static final Set<String> DDL = Set.of("CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "RENAME", "GRANT", "REVOKE");
    private static final Pattern FIRST_WORD = Pattern.compile("^\\s*(\\w+)");
    private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:from|into|update|join|table)\\s+([\\w.\"`\\[\\]]+)");

    @Override
    public void earlyStartup() {
        QMUtils.registerMetaListener(SessionSteps::queries);
        IWorkbench workbench = PlatformUI.getWorkbench();
        workbench.getDisplay().asyncExec(() -> {
            workbench.getService(ICommandService.class).addExecutionListener(new Commands());
            IWorkbenchWindow active = workbench.getActiveWorkbenchWindow();
            RecordingConsent.askOnce(active == null ? null : active.getShell());
            for (IWorkbenchWindow window : workbench.getWorkbenchWindows()) {
                hook(window);
            }
            workbench.addWindowListener(new IWindowListener() {
                @Override
                public void windowOpened(IWorkbenchWindow window) {
                    hook(window);
                }

                @Override
                public void windowActivated(IWorkbenchWindow window) {
                }

                @Override
                public void windowDeactivated(IWorkbenchWindow window) {
                }

                @Override
                public void windowClosed(IWorkbenchWindow window) {
                }
            });
        });
    }

    private static void hook(@NotNull IWorkbenchWindow window) {
        window.getPartService().addPartListener(new Parts());
        // post selection: Eclipse already waits for the user to settle, arrow-key scrolling isn't recorded per row
        window.getSelectionService().addPostSelectionListener(DatabaseNavigatorView.VIEW_ID, (part, selection) -> {
            if (SessionRecorder.isRecording() && selection instanceof IStructuredSelection s && s.getFirstElement() instanceof DBNNode node) {
                DBSObject object = node instanceof DBNDatabaseNode db ? db.getObject() : null;
                SessionRecorder.step("gezin", conn(object), object != null ? name(object) : node.getNodeDisplayName(), null);
            }
        });
    }

    /** The result set viewer finished loading (not scrolling) a table's data. */
    public static void dataLoaded(@NotNull IResultSetController viewer) {
        if (!(container(viewer) instanceof DBSEntity entity)) {
            return; // SQL editor results: the query itself comes through QM
        }
        DBDDataFilter filter = viewer.getModel().getDataFilter();
        boolean searched = filter.hasFilters() || filter.hasOrdering();
        if (!searched && !SessionRecorder.isRecording()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        DBPDataSource dataSource = entity.getDataSource();
        if (searched && dataSource != null) {
            try {
                SQLUtils.appendQueryConditions(dataSource, text, null, filter);
            } catch (DBException e) {
                text.append(filter.getWhere());
            }
            SQLUtils.appendQueryOrder(dataSource, text, null, filter);
        }
        if (searched) {
            SessionRecorder.light("ara", conn(entity), name(entity), text.toString().strip());
        } else {
            SessionRecorder.step("veri_gor", conn(entity), name(entity), null);
        }
    }

    /** Ctrl+F filter in the grid. */
    public static void found(@NotNull IResultSetController viewer, @NotNull String text) {
        if (SessionRecorder.isRecording()) {
            DBSObject container = container(viewer);
            SessionRecorder.step("bul", conn(container), container instanceof DBSEntity e ? name(e) : null, text);
        }
    }

    private static void queries(@NotNull DBRProgressMonitor monitor, @NotNull List<QMMetaEvent> events) {
        for (QMMetaEvent event : events) {
            if (event.getAction() != QMEventAction.END || !(event.getObject() instanceof QMMStatementExecuteInfo exec)) {
                continue;
            }
            DBCExecutionPurpose purpose = exec.getStatement().getPurpose();
            String sql = exec.getQueryString();
            if (purpose == null || !purpose.isUser() || sql == null) {
                continue;
            }
            Matcher word = FIRST_WORD.matcher(sql);
            String kind = word.find() ? word.group(1).toUpperCase(Locale.ROOT) : "";
            Matcher table = TABLE.matcher(sql);
            String target = table.find() ? table.group(1) : null;
            String result = exec.hasError() ? " -> ERROR " + exec.getErrorMessage() : " -> " + exec.getUpdateRowCount() + " rows";
            String conn = exec.getConnection().getContainerName();
            if (DML.contains(kind)) {
                SessionRecorder.light("dml", conn, target, sql + result);
            } else if (DDL.contains(kind)) {
                SessionRecorder.light("ddl", conn, target, sql + result);
            } else {
                SessionRecorder.step("sql", conn, target, sql + result);
            }
        }
    }

    @Nullable
    private static DBSObject container(@NotNull IResultSetController viewer) {
        // the viewer's own state follows "navigate to reference" inside the same editor
        return viewer instanceof ResultSetViewer rsv ? rsv.getDataContainer() : viewer.getContainer().getDataContainer();
    }

    @Nullable
    private static String conn(@Nullable DBSObject object) {
        DBPDataSource dataSource = object == null ? null : object.getDataSource();
        return dataSource == null ? null : dataSource.getContainer().getName();
    }

    @NotNull
    private static String name(@NotNull DBSObject object) {
        return DBUtils.getObjectFullName(object, DBPEvaluationContext.UI);
    }

    private static class Parts implements IPartListener2 {
        @Override
        public void partOpened(IWorkbenchPartReference ref) {
            IWorkbenchPart part = ref.getPart(false);
            DBSObject object = part instanceof IEditorPart editor && editor.getEditorInput() instanceof IDatabaseEditorInput input
                ? input.getDatabaseObject() : null;
            if (object instanceof DBSEntity) {
                SessionRecorder.light("tablo_ac", conn(object), name(object), null);
            } else {
                SessionRecorder.step("ac", null, ref.getId(), ref.getPartName());
            }
            if (part instanceof IPageChangeProvider pages && object != null) {
                // Properties / Data / ER Diagram tabs of a table editor
                pages.addPageChangedListener(e ->
                    SessionRecorder.step("sekme", conn(object), name(object), e.getSelectedPage() == null ? null : e.getSelectedPage().getClass().getSimpleName()));
            }
        }

        @Override
        public void partActivated(IWorkbenchPartReference ref) {
            SessionRecorder.step("aktif", null, ref.getId(), ref.getPartName());
        }
    }

    private static class Commands implements IExecutionListener {
        @Override
        public void postExecuteSuccess(String commandId, Object returnValue) {
            SessionRecorder.step("komut", null, commandId, null);
        }

        @Override
        public void preExecute(String commandId, ExecutionEvent event) {
        }

        @Override
        public void notHandled(String commandId, NotHandledException exception) {
        }

        @Override
        public void postExecuteFailure(String commandId, ExecutionException exception) {
        }
    }
}
