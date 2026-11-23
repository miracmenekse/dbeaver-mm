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

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.dialogs.MessageDialog;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
// dbeaver-mm K159: satır kimliğinden grup anahtarı sütunlarını okumak için
import org.jkiss.dbeaver.model.data.DBDRowIdentifier;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSDataContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetCellLocation;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetModel;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetRow;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetValueController;
import org.jkiss.dbeaver.ui.controls.resultset.ResultSetViewer;
import org.jkiss.dbeaver.ui.controls.resultset.session.SessionRecorder;
import org.jkiss.dbeaver.ui.data.IValueController;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * dbeaver-mm K156: translates the text columns of language rows through Google Translate.
 * The grid must show a {@code lang} column plus {@code name} or {@code descr}; an English row is the
 * source for the rows below it. Texts leave the machine only after the one-time consent stored in
 * {@code ~/.dbeaver-mm/translate.properties}; a preview is shown and nothing is written before Apply.
 */
public class TranslateByLangAction extends Action {

    private static final Path CONSENT_FILE =
        Path.of(System.getProperty("user.home"), ".dbeaver-mm", "translate.properties");
    private static final String SOURCE_LANG = "en";
    private static final String COLUMN_NAME = "name";
    private static final String COLUMN_DESCR = "descr";

    @NotNull
    private final ResultSetViewer viewer;

    private TranslateByLangAction(@NotNull ResultSetViewer viewer, @NotNull String label) {
        super(label);
        this.viewer = viewer;
    }

    /** dbeaver-mm K156: adds the action only when the grid has a language and a text column. */
    public static void contribute(@NotNull IMenuManager menu, @NotNull ResultSetViewer viewer) {
        DBDAttributeBinding[] attributes = viewer.getModel().getAttributes();
        String[] columns = new String[attributes.length];
        for (int i = 0; i < attributes.length; i++) {
            columns[i] = attributes[i].getName();
        }
        if (!hasTranslateColumns(columns)) {
            return;
        }
        String label = menuLabel(buildPlan(viewer).jobs.size());
        if (label == null) {
            return;
        }
        menu.add(new TranslateByLangAction(viewer, label));
    }

    static boolean hasTranslateColumns(@Nullable String[] columns) {
        if (columns == null) {
            return false;
        }
        boolean lang = false;
        boolean text = false;
        for (String column : columns) {
            if (column == null) {
                continue;
            }
            if ("lang".equalsIgnoreCase(column)) {
                lang = true;
            } else if (COLUMN_NAME.equalsIgnoreCase(column) || COLUMN_DESCR.equalsIgnoreCase(column)) {
                text = true;
            }
        }
        return lang && text;
    }

    @Nullable
    static String menuLabel(int rows) {
        return rows <= 0 ? null : "Translate by lang (" + rows + " rows)";
    }

    static String statusText(int cells) {
        return cells + " cells translated";
    }

    /**
     * dbeaver-mm K156: true when translation may run. A saved {@code consent=yes} wins; otherwise the
     * user's answer decides and is persisted only when they accepted.
     */
    static boolean consent(@NotNull Path file, boolean accepted) {
        Properties props = new Properties();
        if (Files.isRegularFile(file)) {
            try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                props.load(in);
            } catch (IOException ignored) {
                // an unreadable file counts as no consent
            }
        }
        if ("yes".equalsIgnoreCase(props.getProperty("consent"))) {
            return true;
        }
        if (!accepted) {
            return false;
        }
        props.setProperty("consent", "yes");
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer out = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                props.store(out, "dbeaver-mm translation consent");
            }
        } catch (IOException ignored) {
            // the answer still holds for this run
        }
        return true;
    }

    /**
     * dbeaver-mm K156: translates every planned cell first, so a failure leaves no cell touched; then
     * emits one preview line per cell. Returns the number of cells that would be written.
     */
    static int applyTranslations(
        @NotNull LangPlan plan,
        @NotNull BiFunction<String, String, String> translate,
        @NotNull BiFunction<Integer, String, String> cell,
        @NotNull Consumer<Object> output
    ) {
        List<PreviewLine> lines = new ArrayList<>();
        for (LangPlan.Job job : plan.jobs) {
            if (job.sourceName != null) {
                lines.add(new PreviewLine(job, COLUMN_NAME, job.sourceName,
                    translate.apply(job.sourceName, job.targetLang)));
            }
            if (job.sourceDescr != null) {
                lines.add(new PreviewLine(job, COLUMN_DESCR, job.sourceDescr,
                    translate.apply(job.sourceDescr, job.targetLang)));
            }
        }
        for (PreviewLine line : lines) {
            String current = cell.apply(line.job.rowIndex, line.column);
            boolean overwrites = !LangPlan.writable(current, line.source, line.translated);
            output.accept(line.job.targetLang + "  " + line.column + ": " + line.source + " -> " + line.translated
                + (overwrites ? " (overwrites)" : ""));
        }
        return lines.size();
    }

    @Override
    public void run() {
        LangPlan plan = buildPlan(viewer);
        if (plan.jobs.isEmpty()) {
            return;
        }
        if (!consent(CONSENT_FILE, false) && !consent(CONSENT_FILE, askConsent())) {
            return;
        }
        ResultSetModel model = viewer.getModel();
        List<ResultSetRow> rows = model.getAllRows();
        DBDAttributeBinding nameAttr = findAttribute(model, COLUMN_NAME);
        DBDAttributeBinding descrAttr = findAttribute(model, COLUMN_DESCR);
        Map<String, String> current = new HashMap<>();
        for (LangPlan.Job job : plan.jobs) {
            ResultSetRow row = rows.get(job.rowIndex);
            if (job.sourceName != null) {
                current.put(cellKey(job.rowIndex, COLUMN_NAME), text(nameAttr == null ? null : model.getCellValue(nameAttr, row)));
            }
            if (job.sourceDescr != null) {
                current.put(cellKey(job.rowIndex, COLUMN_DESCR), text(descrAttr == null ? null : model.getCellValue(descrAttr, row)));
            }
        }
        GoogleTranslate google = new GoogleTranslate();
        Map<String, String> cache = new HashMap<>();
        List<String> preview = new ArrayList<>();
        new AbstractJob("Translate by lang") {
            @NotNull
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    int cells = applyTranslations(
                        plan,
                        (source, lang) -> {
                            String translated = google.translate(source, SOURCE_LANG, lang);
                            cache.put(cellKey(lang, source), translated);
                            return translated;
                        },
                        (rowIndex, column) -> current.get(cellKey(rowIndex, column)),
                        line -> preview.add(String.valueOf(line)));
                    UIUtils.asyncExec(() -> confirmAndApply(plan, rows, cache, preview, cells));
                } catch (Exception e) {
                    UIUtils.asyncExec(() -> DBWorkbench.getPlatformUI().showError(
                        "Translate by lang",
                        "Nothing was translated, no cell was changed.\n" + e.getMessage(), e));
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void confirmAndApply(
        @NotNull LangPlan plan,
        @NotNull List<ResultSetRow> rows,
        @NotNull Map<String, String> cache,
        @NotNull List<String> preview,
        int cells
    ) {
        String message = String.join("\n", preview) + "\n\n" + statusText(cells) + ". Continue?";
        int answer = new MessageDialog(
            UIUtils.getActiveWorkbenchShell(),
            "Translate by lang",
            null,
            message,
            MessageDialog.QUESTION,
            0,
            "Apply", "Cancel").open();
        if (answer != 0) {
            return;
        }
        int written = writeTranslations(plan, rows, cache);
        viewer.setStatus(statusText(written), DBPMessageType.INFORMATION);
        SessionRecorder.light("ceviri", null, targetName(), written + " cells");
    }

    private int writeTranslations(
        @NotNull LangPlan plan,
        @NotNull List<ResultSetRow> rows,
        @NotNull Map<String, String> cache
    ) {
        ResultSetModel model = viewer.getModel();
        List<ResultSetRow> currentRows = model.getAllRows();
        DBDAttributeBinding nameAttr = findAttribute(model, COLUMN_NAME);
        DBDAttributeBinding descrAttr = findAttribute(model, COLUMN_DESCR);
        int written = 0;
        for (LangPlan.Job job : plan.jobs) {
            if (job.rowIndex >= rows.size()) {
                continue;
            }
            ResultSetRow row = rows.get(job.rowIndex);
            // the user may have deleted the row while the translation was running
            if (!currentRows.contains(row)) {
                continue;
            }
            if (job.sourceName != null && nameAttr != null) {
                written += writeCell(nameAttr, row, job.targetLang, job.sourceName, cache);
            }
            if (job.sourceDescr != null && descrAttr != null) {
                written += writeCell(descrAttr, row, job.targetLang, job.sourceDescr, cache);
            }
        }
        return written;
    }

    private int writeCell(
        @NotNull DBDAttributeBinding attr,
        @NotNull ResultSetRow row,
        @NotNull String lang,
        @NotNull String source,
        @NotNull Map<String, String> cache
    ) {
        String translated = cache.get(cellKey(lang, source));
        if (translated == null) {
            return 0;
        }
        new ResultSetValueController(viewer, new ResultSetCellLocation(attr, row), IValueController.EditType.NONE, null)
            .updateValue(translated, true);
        return 1;
    }

    private static boolean askConsent() {
        return MessageDialog.openQuestion(
            UIUtils.getActiveWorkbenchShell(),
            "Translate by lang",
            "Cell texts are sent to Google Translate. Allow translation?");
    }

    // dbeaver-mm K159: kimlik sütunlarından anahtar türetilir; kimlik yoksa eski ardışıklık kullanılır
    @NotNull
    private static LangPlan buildPlan(@NotNull ResultSetViewer viewer) {
        ResultSetModel model = viewer.getModel();
        List<ResultSetRow> rows = model.getAllRows();
        DBDAttributeBinding langAttr = findAttribute(model, "lang");
        DBDAttributeBinding nameAttr = findAttribute(model, COLUMN_NAME);
        DBDAttributeBinding descrAttr = findAttribute(model, COLUMN_DESCR);
        List<DBDAttributeBinding> keyAttrs = new ArrayList<>();
        DBDRowIdentifier identifier = model.getDefaultRowIdentifier();
        if (identifier != null) {
            for (DBDAttributeBinding attr : identifier.getAttributes()) {
                if (attr != null && !attr.getName().equalsIgnoreCase("lang")) {
                    keyAttrs.add(attr);
                }
            }
        }
        int count = rows.size();
        String[] langs = new String[count];
        String[] names = new String[count];
        String[] descrs = new String[count];
        String[] keys = null;
        if (!keyAttrs.isEmpty()) {
            keys = new String[count];
        }
        // dbeaver-mm K157: yalnız kullanıcının seçtiği satırlar çevrilir
        List<ResultSetRow> selectedRows = viewer.getSelectedRows();
        for (int i = 0; i < count; i++) {
            ResultSetRow row = rows.get(i);
            langs[i] = text(langAttr == null ? null : model.getCellValue(langAttr, row));
            names[i] = text(nameAttr == null ? null : model.getCellValue(nameAttr, row));
            descrs[i] = text(descrAttr == null ? null : model.getCellValue(descrAttr, row));
            if (keys != null) {
                StringBuilder key = new StringBuilder();
                boolean hasValue = false;
                for (int j = 0; j < keyAttrs.size(); j++) {
                    if (j > 0) {
                        key.append("\u0000");
                    }
                    String value = text(model.getCellValue(keyAttrs.get(j), row));
                    if (value == null) {
                        value = "";
                    }
                    if (!value.isEmpty()) {
                        hasValue = true;
                        key.append(value);
                    }
                }
                keys[i] = hasValue ? key.toString() : null;
            }
        }
        int[] selection = LangPlan.indexesOf(rows, selectedRows);
        return LangPlan.plan(langs, names, descrs, selection, keys);
    }

    @Nullable
    private static DBDAttributeBinding findAttribute(@NotNull ResultSetModel model, @NotNull String name) {
        for (DBDAttributeBinding attr : model.getAttributes()) {
            if (attr.getName().equalsIgnoreCase(name)) {
                return attr;
            }
        }
        return null;
    }

    @Nullable
    private static String text(@Nullable Object value) {
        if (value == null || DBUtils.isNullValue(value)) {
            return null;
        }
        return value.toString();
    }

    @NotNull
    private static String cellKey(@NotNull Object rowIndex, @NotNull Object column) {
        return rowIndex + "\u0000" + column;
    }

    @Nullable
    private String targetName() {
        DBSDataContainer container = viewer.getDataContainer();
        return container == null ? null : container.getName();
    }

    private static final class PreviewLine {
        private final LangPlan.Job job;
        private final String column;
        private final String source;
        private final String translated;

        private PreviewLine(LangPlan.Job job, String column, String source, String translated) {
            this.job = job;
            this.column = column;
            this.source = source;
            this.translated = translated;
        }
    }
}
