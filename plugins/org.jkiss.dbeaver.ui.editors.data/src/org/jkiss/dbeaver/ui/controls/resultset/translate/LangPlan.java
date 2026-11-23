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
package org.jkiss.dbeaver.ui.controls.resultset.translate;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups consecutive translation rows and builds a pure translation plan.
 */
// dbeaver-mm K155: dil satırlarını gruplama ve çeviri planı (saf mantık)
public final class LangPlan {

    private static final String ENGLISH = "en";
    private static final String NO_EN_ROW = "no en row";

    public final List<Job> jobs;
    public final List<Skipped> skipped;

    private LangPlan(List<Job> jobs, List<Skipped> skipped) {
        this.jobs = List.copyOf(jobs);
        this.skipped = List.copyOf(skipped);
    }

    /**
     * Builds the plan. Rows keep their order; an English row opens a group and the
     * following non-English rows belong to it. Rows before the first English row have
     * no source.
     */
    public static LangPlan plan(String[] langs, String[] names, String[] descrs, int[] selection) {
        int length = langs == null ? 0 : langs.length;
        int[] header = new int[length];
        int currentHeader = -1;
        for (int i = 0; i < length; i++) {
            if (isEnglish(element(langs, i))) {
                currentHeader = i;
            }
            header[i] = currentHeader;
        }

        boolean[] include = new boolean[length];
        if (selection != null) {
            for (int selected : selection) {
                if (selected < 0 || selected >= length) {
                    continue;
                }
                if (isEnglish(element(langs, selected))) {
                    for (int i = 0; i < length; i++) {
                        if (header[i] == selected && !isEnglish(element(langs, i))) {
                            include[i] = true;
                        }
                    }
                } else {
                    include[selected] = true;
                }
            }
        }

        List<Job> jobs = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            if (!include[i] || isEnglish(element(langs, i))) {
                continue;
            }
            int source = header[i];
            if (source < 0) {
                skipped.add(new Skipped(i, element(langs, i), NO_EN_ROW));
                continue;
            }
            jobs.add(new Job(
                i,
                element(langs, i),
                emptyToNull(element(names, source)),
                emptyToNull(element(descrs, source))));
        }
        return new LangPlan(jobs, skipped);
    }

    /**
     * A cell may be written when it is empty or still holds the source text.
     * The proposed translation does not change this safety check.
     */
    public static boolean writable(String current, String source, String translated) {
        if (current == null || current.isEmpty()) {
            return true;
        }
        return current.equals(source);
    }

    private static boolean isEnglish(String lang) {
        return lang != null && ENGLISH.equalsIgnoreCase(lang);
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static String element(String[] values, int index) {
        return values != null && index >= 0 && index < values.length ? values[index] : null;
    }

    public static final class Job {
        public final int rowIndex;
        public final String targetLang;
        public final String sourceName;
        public final String sourceDescr;
        public final String newName;
        public final String newDescr;

        private Job(int rowIndex, String targetLang, String sourceName, String sourceDescr) {
            this(rowIndex, targetLang, sourceName, sourceDescr, null, null);
        }

        private Job(
            int rowIndex,
            String targetLang,
            String sourceName,
            String sourceDescr,
            String newName,
            String newDescr
        ) {
            this.rowIndex = rowIndex;
            this.targetLang = targetLang;
            this.sourceName = sourceName;
            this.sourceDescr = sourceDescr;
            this.newName = newName;
            this.newDescr = newDescr;
        }

        public Job withTranslations(String newName, String newDescr) {
            return new Job(rowIndex, targetLang, sourceName, sourceDescr, newName, newDescr);
        }
    }

    public static final class Skipped {
        public final int rowIndex;
        public final String lang;
        public final String reason;

        private Skipped(int rowIndex, String lang, String reason) {
            this.rowIndex = rowIndex;
            this.lang = lang;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "Skipped{rowIndex=" + rowIndex + ", lang='" + lang + "', reason='" + reason + "'}";
        }
    }
}
