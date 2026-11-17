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
package org.jkiss.dbeaver.ui.navigator.database;

/**
 * dbeaver-mm K113: pure decision helper for auto-expanding connections while searching the navigator.
 */
public final class SearchAutoExpand {

    private static final int MIN_PATTERN_LENGTH = 2;

    private SearchAutoExpand() {
    }

    // dbeaver-mm K113
    public static boolean shouldExpand(String pattern, boolean connectionFilterType, boolean connected, boolean expanded) {
        if (pattern == null) {
            return false;
        }
        return pattern.trim().length() >= MIN_PATTERN_LENGTH
            && !connectionFilterType
            && connected
            && !expanded;
    }
}
