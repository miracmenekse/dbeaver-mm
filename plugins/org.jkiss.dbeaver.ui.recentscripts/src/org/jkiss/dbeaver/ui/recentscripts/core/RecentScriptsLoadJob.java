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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.List;
import java.util.function.Consumer;

/**
 * Loads the recent scripts off the UI thread.
 * <p>
 * Enumerating scripts and reading their headers hits the filesystem, which can be slow on
 * network or cloud-synced paths, so it must never happen on the UI thread. The job takes no
 * scheduling rule: reading file contents does not need one, and taking the workspace rule
 * would make the panel wait behind builds.
 */
public class RecentScriptsLoadJob extends AbstractJob {

    private final int limit;
    private final Consumer<List<RecentScriptItem>> uiConsumer;

    /**
     * @param limit      maximum number of scripts to load
     * @param uiConsumer receives the result on the UI thread; not called if the job was cancelled
     */
    public RecentScriptsLoadJob(int limit, @NotNull Consumer<List<RecentScriptItem>> uiConsumer) {
        super("Load recent SQL scripts");
        this.limit = limit;
        this.uiConsumer = uiConsumer;
        setUser(false);
        setSystem(false);
        setPriority(Job.SHORT);
    }

    @Override
    protected IStatus run(@NotNull DBRProgressMonitor monitor) {
        List<RecentScriptItem> items = RecentScriptsService.getInstance().loadRecentScripts(monitor, limit);
        if (monitor.isCanceled()) {
            return Status.CANCEL_STATUS;
        }
        // asyncExec, never syncExec: the UI thread may be joining this job
        UIUtils.asyncExec(() -> uiConsumer.accept(items));
        return Status.OK_STATUS;
    }
}
