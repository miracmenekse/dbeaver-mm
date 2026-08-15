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
package org.jkiss.dbeaver.ui.recentscripts;

import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.IViewSite;
import org.eclipse.ui.IWorkbenchCommandConstants;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.actions.ActionFactory;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.recentscripts.core.DBeaverScriptsBridge;
import org.jkiss.dbeaver.ui.recentscripts.core.RecentScriptItem;
import org.jkiss.dbeaver.ui.recentscripts.core.RecentScriptsLoadJob;
import org.jkiss.dbeaver.ui.recentscripts.core.RecentScriptsService;
import org.jkiss.dbeaver.ui.inlinefkpicker.ui.WhereParamsAction;
import org.jkiss.dbeaver.ui.recentscripts.internal.RecentScriptsMessages;
import org.jkiss.dbeaver.ui.recentscripts.ui.RecentScriptsPanel;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Right-hand panel listing the most recently saved SQL scripts of the active project.
 * <p>
 * The view is toggled by the shared command {@code org.jkiss.dbeaver.core.view.toggle},
 * contributed to the right-most main toolbar in plugin.xml, so this class carries no
 * command handling of its own.
 */
public class RecentScriptsView extends ViewPart implements IResourceChangeListener {

    private static final Log log = Log.getLog(RecentScriptsView.class);

    public static final String VIEW_ID = "org.jkiss.dbeaver.ui.recentscripts.view"; //$NON-NLS-1$

    /**
     * Debounce for resource driven refreshes. A single editor save produces a burst of deltas,
     * and without this the panel would re-read files several times per save.
     */
    private static final long REFRESH_DELAY_MS = 200;

    private static final int RELEVANT_CHANGE_FLAGS =
        IResourceDelta.CONTENT | IResourceDelta.REPLACED | IResourceDelta.LOCAL_CHANGED;

    private RecentScriptsPanel panel;
    private RecentScriptsLoadJob loadJob;

    /**
     * Guards against a slow earlier load overwriting the result of a later one: a job that has
     * already passed its cancellation check still delivers, so results carry the generation
     * they were started for and stale ones are dropped.
     */
    private final AtomicLong loadGeneration = new AtomicLong();

    @Override
    public void init(@NotNull IViewSite site) throws PartInitException {
        super.init(site);
        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
    }

    @Override
    public void createPartControl(@NotNull Composite parent) {
        panel = new RecentScriptsPanel(parent);
        panel.setOpenHandler(this::openScript);
        panel.setFavoriteHandler(this::toggleFavorite);
        createActions();
        panel.showLoading();
        scheduleRefresh(0);
    }

    @Override
    public void setFocus() {
        if (panel != null && !panel.isDisposed()) {
            panel.setFocus();
        }
    }

    @Override
    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        cancelPendingLoad();
        super.dispose();
    }

    /**
     * Watches for SQL script changes.
     * <p>
     * This runs off the UI thread inside the workspace notification manager, so it must not
     * touch SWT and must not read file contents - it only invalidates cache entries and asks
     * for a (debounced) reload.
     */
    @Override
    public void resourceChanged(@NotNull IResourceChangeEvent event) {
        IResourceDelta rootDelta = event.getDelta();
        if (rootDelta == null) {
            return;
        }
        String scriptExtension = DBeaverScriptsBridge.getScriptFileExtension();
        boolean[] dirty = {false};
        try {
            rootDelta.accept(delta -> {
                IResource resource = delta.getResource();
                if (resource.getType() != IResource.FILE) {
                    return true;
                }
                if (!scriptExtension.equalsIgnoreCase(resource.getFileExtension())) {
                    return false;
                }
                int kind = delta.getKind();
                boolean relevant = kind == IResourceDelta.ADDED
                    || kind == IResourceDelta.REMOVED
                    || (kind == IResourceDelta.CHANGED && (delta.getFlags() & RELEVANT_CHANGE_FLAGS) != 0);
                if (relevant) {
                    RecentScriptsService.getInstance().invalidate(resource.getFullPath());
                    dirty[0] = true;
                }
                return false;
            });
        } catch (CoreException e) {
            log.debug("Cannot process resource change", e);
            return;
        }
        if (dirty[0]) {
            scheduleRefresh(REFRESH_DELAY_MS);
        }
    }

    private void createActions() {
        Action refreshAction = new Action(
            RecentScriptsMessages.action_refresh_text,
            DBeaverIcons.getImageDescriptor(UIIcon.REFRESH)
        ) {
            @Override
            public void run() {
                // Also drops the cache, so changes made outside the workbench are picked up
                RecentScriptsService.getInstance().clearCache();
                scheduleRefresh(0);
            }
        };
        refreshAction.setToolTipText(RecentScriptsMessages.action_refresh_tooltip);
        refreshAction.setActionDefinitionId(IWorkbenchCommandConstants.FILE_REFRESH);

        IActionBars actionBars = getViewSite().getActionBars();
        actionBars.getToolBarManager().add(refreshAction);
        // Gives F5 inside the view without declaring a command or a key binding
        actionBars.setGlobalActionHandler(ActionFactory.REFRESH.getId(), refreshAction);
        actionBars.updateActionBars();
    }

    /**
     * Restarts the background load. Safe to call from any thread.
     */
    private synchronized void scheduleRefresh(long delayMs) {
        cancelPendingLoad();
        long generation = loadGeneration.incrementAndGet();
        loadJob = new RecentScriptsLoadJob(
            RecentScriptsService.DEFAULT_LIMIT,
            items -> applyItems(generation, items));
        loadJob.schedule(delayMs);
    }

    private synchronized void cancelPendingLoad() {
        if (loadJob != null) {
            loadJob.cancel();
            loadJob = null;
        }
    }

    private void applyItems(long generation, @NotNull List<RecentScriptItem> items) {
        if (generation == loadGeneration.get() && panel != null && !panel.isDisposed()) {
            panel.setItems(items);
        }
    }

    private void toggleFavorite(@NotNull RecentScriptItem item) {
        DBeaverScriptsBridge.setFavorite(item.file(), !item.favorite());
        // Favorite state changes ordering and grouping, so reload; previews stay cached
        scheduleRefresh(0);
    }

    private void openScript(@NotNull RecentScriptItem item) {
        if (DBeaverScriptsBridge.openInSqlEditor(item.file())) {
            // Offer the script's filter conditions right away, so the user does not have to hunt
            // for the WHERE clause. Silently skipped when the script has nothing to fill in.
            WhereParamsAction.openFor(DBeaverScriptsBridge.getActiveSqlEditor(), item.title());
            return;
        }
        // The card outlived its file - tell the user and drop the stale entry
        MessageDialog.openWarning(
            getSite().getShell(),
            RecentScriptsMessages.error_script_missing_title,
            NLS.bind(RecentScriptsMessages.error_script_missing_message, item.title()));
        RecentScriptsService.getInstance().clearCache();
        scheduleRefresh(0);
    }
}
