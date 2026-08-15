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
import org.jkiss.code.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers parsed script headers so re-opening the panel does not re-read any file.
 * <p>
 * Entries are validated on the pair (last modified, size) rather than the timestamp alone:
 * some filesystems report modification times with one second granularity, which would make
 * two saves within the same second indistinguishable.
 * <p>
 * Correctness never depends on invalidation - a stale entry simply fails the validation check
 * on the next load. {@link #invalidate(IPath)} is only an optimisation.
 */
final class ScriptPreviewCache {

    private static final int MAX_ENTRIES = 300;

    private record Entry(long lastModified, long size, ScriptPreviewParser.ParsedPreview preview) {
    }

    private final Map<String, Entry> cache = Collections.synchronizedMap(
        new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    @Nullable
    ScriptPreviewParser.ParsedPreview get(@NotNull IFile file, long lastModified, long size) {
        Entry entry = cache.get(key(file));
        if (entry != null && entry.lastModified() == lastModified && entry.size() == size) {
            return entry.preview();
        }
        return null;
    }

    void put(@NotNull IFile file, long lastModified, long size, @NotNull ScriptPreviewParser.ParsedPreview preview) {
        cache.put(key(file), new Entry(lastModified, size, preview));
    }

    void invalidate(@NotNull IPath path) {
        cache.remove(path.toPortableString());
    }

    void clear() {
        cache.clear();
    }

    @NotNull
    private static String key(@NotNull IFile file) {
        return file.getFullPath().toPortableString();
    }
}
