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
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * One card of the Recent SQL Scripts panel.
 *
 * @param file          the script file; always re-check {@code exists()} before using it,
 *                      a card may outlive its file
 * @param title         file name without the ".sql" extension
 * @param description   first "--" comment line of the file, or null when the file has none
 * @param previewLines  up to three code lines following the header comment
 * @param lastModified  epoch millis used for ordering
 * @param dataSourceName connection the script is bound to, or null
 * @param resourcePath  logical path inside the project, shown in the tooltip
 * @param favorite      whether the user pinned this script as a favorite
 */
public record RecentScriptItem(
    @NotNull IFile file,
    @NotNull String title,
    @Nullable String description,
    @NotNull List<String> previewLines,
    long lastModified,
    @Nullable String dataSourceName,
    @NotNull String resourcePath,
    boolean favorite
) {
}
