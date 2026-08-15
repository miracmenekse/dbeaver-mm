/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.ui.recentscripts.core;

import org.eclipse.core.resources.IFile;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchWindow;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPPlatformDesktop;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.rcp.RCPProject;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorUtils;
import org.jkiss.dbeaver.ui.editors.sql.handlers.SQLEditorHandlerOpenEditor;
import org.jkiss.dbeaver.utils.ResourceUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single point where this plugin touches DBeaver-specific API.
 * <p>
 * Everything else in the plugin works with plain Eclipse resources and SWT, so if the
 * upstream DBeaver API changes, only this class has to be adjusted.
 * <p>
 * Two DBeaver facts drive the implementation:
 * <ul>
 * <li>DBeaver does not keep a "recent scripts" list. Its own "Recent SQL Script" command
 * ({@code SQLEditorUtils.findRecentScript}) picks the script with the highest
 * {@link ResourceUtils#getResourceLastModified} among the scripts bound to the active
 * connection. So recency is simply the file modification time, and the connection is only
 * a filter. We use the very same criterion without the connection filter.</li>
 * <li>{@code SQLEditorHandlerOpenEditor.openResource(IResource)} is exactly where the
 * Ctrl+Enter command ends up, so clicking a card behaves identically.</li>
 * </ul>
 */
public final class DBeaverScriptsBridge {

    private static final Log log = Log.getLog(DBeaverScriptsBridge.class);

    /**
     * Project metadata key under which a script's "favorite" flag is stored. This lives in the
     * project's {@code .dbeaver/project-metadata.json}, the same store DBeaver uses for a
     * script's default connection, so it travels with the project and needs no sidecar file.
     */
    private static final String PROP_FAVORITE = "recent-scripts.favorite"; //$NON-NLS-1$
    private static final String PROP_VALUE_TRUE = "true"; //$NON-NLS-1$

    /**
     * A script file found in the active project, together with the connection it is bound to.
     */
    public record ScriptRef(
        @NotNull IFile file,
        @Nullable String dataSourceName,
        @NotNull String resourcePath,
        boolean favorite
    ) {
    }

    private DBeaverScriptsBridge() {
        // Utility class
    }

    /**
     * Collects every file below the active project's "Scripts" folder.
     * <p>
     * Note that the returned list is <b>not</b> filtered by file extension - DBeaver's own
     * enumeration returns every file it finds there. Callers must filter.
     *
     * @return script references, never null; empty when there is no accessible active project
     */
    @NotNull
    public static List<ScriptRef> collectScriptFiles() {
        RCPProject project = getActiveRcpProject();
        if (project == null) {
            return Collections.emptyList();
        }
        List<SQLEditorUtils.ResourceInfo> scripts;
        try {
            scripts = SQLEditorUtils.getScriptsFromProject(project);
        } catch (Exception e) {
            // A missing or inaccessible Scripts folder must not blank out the panel
            log.debug("Cannot enumerate SQL scripts of project '" + project.getName() + "'", e);
            return Collections.emptyList();
        }
        List<ScriptRef> result = new ArrayList<>(scripts.size());
        for (SQLEditorUtils.ResourceInfo info : scripts) {
            // ResourceInfo must not escape this method: its constructors are package private
            // and its getDescription() uses DBeaver's comment-skipping rule, which is the
            // opposite of what this feature needs.
            if (info.isDirectory() || !(info.getResource() instanceof IFile file)) {
                continue;
            }
            DBPDataSourceContainer container = info.getDataSource();
            String resourcePath = project.getResourcePath(file);
            result.add(new ScriptRef(
                file,
                container == null ? null : container.getName(),
                resourcePath,
                isFavorite(project, resourcePath)));
        }
        return result;
    }

    /**
     * Marks or unmarks a script as favorite. Persisted in the active project's metadata.
     */
    public static void setFavorite(@NotNull IFile file, boolean favorite) {
        RCPProject project = getActiveRcpProject();
        if (project == null) {
            return;
        }
        try {
            String resourcePath = project.getResourcePath(file);
            project.setResourceProperty(resourcePath, PROP_FAVORITE, favorite ? PROP_VALUE_TRUE : null);
        } catch (Exception e) {
            log.debug("Cannot store favorite flag for '" + file.getName() + "'", e);
        }
    }

    private static boolean isFavorite(@NotNull RCPProject project, @NotNull String resourcePath) {
        try {
            return PROP_VALUE_TRUE.equals(project.getResourceProperty(resourcePath, PROP_FAVORITE));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Last modification time of a script, using the same call DBeaver's own
     * "Recent SQL Script" command uses for ranking.
     *
     * @return epoch millis, or a non-positive value when unknown
     */
    public static long getLastModified(@NotNull IFile file) {
        long lastModified = ResourceUtils.getResourceLastModified(file);
        if (lastModified <= 0) {
            // EFS could not tell us; fall back to the workspace-cached timestamp
            lastModified = file.getLocalTimeStamp();
        }
        return lastModified;
    }

    /**
     * Size of a script in bytes. Used together with the timestamp to validate cache entries,
     * so it is only queried for the handful of scripts that actually make it into the panel.
     *
     * @return size in bytes, or a non-positive value when unknown
     */
    public static long getFileSize(@NotNull IFile file) {
        return ResourceUtils.getFileLength(file);
    }

    /**
     * Opens a script in the SQL editor, exactly like the "Recent SQL Script" command does.
     * <p>
     * The single argument overload passes an empty navigator context, so the file's stored
     * connection association is left untouched.
     *
     * @return false when the file is gone, in which case nothing was opened
     */
    public static boolean openInSqlEditor(@NotNull IFile file) {
        if (!file.exists()) {
            return false;
        }
        SQLEditorHandlerOpenEditor.openResource(file);
        return true;
    }

    /**
     * The SQL editor that is active right now, or null when the active editor is something else.
     * Used straight after {@link #openInSqlEditor} to offer the script's parameter form.
     */
    @Nullable
    public static SQLEditorBase getActiveSqlEditor() {
        try {
            IWorkbenchWindow window = UIUtils.getActiveWorkbenchWindow();
            if (window == null || window.getActivePage() == null) {
                return null;
            }
            IEditorPart editor = window.getActivePage().getActiveEditor();
            return editor instanceof SQLEditorBase sqlEditor ? sqlEditor : null;
        } catch (Exception e) {
            log.debug("Cannot resolve active SQL editor", e);
            return null;
        }
    }

    /**
     * File extension of SQL scripts ("sql").
     */
    @NotNull
    public static String getScriptFileExtension() {
        return SQLEditorUtils.SCRIPT_FILE_EXTENSION;
    }

    @Nullable
    private static RCPProject getActiveRcpProject() {
        try {
            DBPProject project = DBPPlatformDesktop.getInstance().getWorkspace().getActiveProject();
            if (project instanceof RCPProject rcpProject
                && project.isOpen()
                && !project.isVirtual()
                && !project.isInMemory()
                && rcpProject.getRootResource() != null
                && rcpProject.getRootResource().isAccessible()
            ) {
                return rcpProject;
            }
        } catch (Exception e) {
            log.debug("Cannot resolve active project", e);
        }
        return null;
    }
}
