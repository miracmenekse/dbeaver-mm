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
import org.eclipse.core.runtime.IPath;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Produces the "N most recently saved SQL scripts" list.
 * <p>
 * Ordering uses the same criterion as DBeaver's own "Recent SQL Script" command, namely the
 * file modification timestamp - see {@link DBeaverScriptsBridge}.
 * <p>
 * All methods except the accessors run off the UI thread.
 */
public final class RecentScriptsService {

    private static final Log log = Log.getLog(RecentScriptsService.class);

    public static final int DEFAULT_LIMIT = 10;

    /** Upper bound on favorites shown, so a huge favorite set cannot flood the panel. */
    private static final int MAX_FAVORITES = 50;

    private static final RecentScriptsService INSTANCE = new RecentScriptsService();

    /** Survives view close/reopen, which is what makes re-opening the panel free. */
    private final ScriptPreviewCache previewCache = new ScriptPreviewCache();

    private RecentScriptsService() {
    }

    @NotNull
    public static RecentScriptsService getInstance() {
        return INSTANCE;
    }

    /**
     * Loads the scripts to show, favorites first (newest first within each group).
     * <p>
     * Favorites are pinned to the top and are <b>not</b> subject to the recency limit - a
     * favorite stays visible even when it is no longer among the most recently saved scripts.
     * The {@code limit} applies only to the non-favorite "recent" group.
     *
     * @param monitor cancellation source; a cancelled load returns an empty list
     * @param limit   maximum number of non-favorite recent items to return
     */
    @NotNull
    public List<RecentScriptItem> loadRecentScripts(@NotNull DBRProgressMonitor monitor, int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        List<DBeaverScriptsBridge.ScriptRef> refs = DBeaverScriptsBridge.collectScriptFiles();
        if (refs.isEmpty()) {
            return Collections.emptyList();
        }
        String scriptExtension = DBeaverScriptsBridge.getScriptFileExtension();

        List<Candidate> favorites = new ArrayList<>();
        // Bounded selection for the recent group: whatever the number of scripts, only the best
        // `limit` non-favorite candidates are ever kept, and only shown items get parsed.
        PriorityQueue<Candidate> recent = new PriorityQueue<>(
            limit, Comparator.comparingLong(Candidate::lastModified));
        for (DBeaverScriptsBridge.ScriptRef ref : refs) {
            if (monitor.isCanceled()) {
                return Collections.emptyList();
            }
            IFile file = ref.file();
            // DBeaver's enumeration returns every file under the Scripts folder, not just SQL
            if (!scriptExtension.equalsIgnoreCase(file.getFileExtension()) || !file.exists()) {
                continue;
            }
            long lastModified = DBeaverScriptsBridge.getLastModified(file);
            if (lastModified <= 0) {
                continue;
            }
            Candidate candidate = new Candidate(ref, lastModified);
            if (ref.favorite()) {
                favorites.add(candidate);
            } else if (recent.size() < limit) {
                recent.add(candidate);
            } else if (recent.peek() != null && recent.peek().lastModified() < lastModified) {
                recent.poll();
                recent.add(candidate);
            }
        }

        Comparator<Candidate> byRecency = Comparator.comparingLong(Candidate::lastModified).reversed();
        favorites.sort(byRecency);
        if (favorites.size() > MAX_FAVORITES) {
            favorites = favorites.subList(0, MAX_FAVORITES);
        }
        List<Candidate> recentOrdered = new ArrayList<>(recent);
        recentOrdered.sort(byRecency);

        List<Candidate> ordered = new ArrayList<>(favorites.size() + recentOrdered.size());
        ordered.addAll(favorites);
        ordered.addAll(recentOrdered);

        List<RecentScriptItem> result = new ArrayList<>(ordered.size());
        for (Candidate candidate : ordered) {
            if (monitor.isCanceled()) {
                return Collections.emptyList();
            }
            result.add(toItem(candidate));
        }
        return result;
    }

    /**
     * Drops the cached header of a single script. Safe to call from any thread.
     */
    public void invalidate(@NotNull IPath path) {
        previewCache.invalidate(path);
    }

    /**
     * Drops every cached header, so the next load re-reads all files. Used by the manual
     * refresh action, which also has to pick up changes made outside the workbench.
     */
    public void clearCache() {
        previewCache.clear();
    }

    @NotNull
    private RecentScriptItem toItem(@NotNull Candidate candidate) {
        IFile file = candidate.ref().file();
        long size = DBeaverScriptsBridge.getFileSize(file);
        ScriptPreviewParser.ParsedPreview preview = previewCache.get(file, candidate.lastModified(), size);
        if (preview == null) {
            try {
                preview = ScriptPreviewParser.parse(file);
            } catch (Exception e) {
                log.debug("Cannot read SQL script '" + file.getFullPath() + "'", e);
                preview = ScriptPreviewParser.ParsedPreview.EMPTY;
            }
            previewCache.put(file, candidate.lastModified(), size, preview);
        }
        return new RecentScriptItem(
            file,
            stripExtension(file.getName()),
            preview.description(),
            preview.previewLines(),
            candidate.lastModified(),
            candidate.ref().dataSourceName(),
            candidate.ref().resourcePath(),
            candidate.ref().favorite());
    }

    @NotNull
    private static String stripExtension(@NotNull String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private record Candidate(@NotNull DBeaverScriptsBridge.ScriptRef ref, long lastModified) {
    }
}
