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
package org.jkiss.dbeaver.ui.inlinefkpicker.trigger;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditor;
import org.jkiss.dbeaver.ui.editors.sql.SQLEditorBase;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.FkColumnRef;
import org.jkiss.dbeaver.ui.inlinefkpicker.core.InlineFkService;

import java.util.*;
import java.util.function.Consumer;

/**
 * dbeaver-mm K6: before the FK picker opens, find which connection the statement's tables live in
 * and switch the SQL editor to it. Only connections tagged for auto connection (navigator context
 * menu) and already connected are searched, and only their cached table lists are used, so large
 * databases cost at most one table-list read per schema. Several matches: the user picks from a
 * menu at the caret; the pick is remembered for this editor and table set.
 */
final class AutoConnectionSelector {

    private static final Log log = Log.getLog(AutoConnectionSelector.class);

    private static final Map<SQLEditorBase, Map<String, DBPDataSourceContainer>> PICKS = new WeakHashMap<>();

    private AutoConnectionSelector() {
    }

    static void resolveThen(@NotNull SQLEditorBase editor, @NotNull FkColumnRef ref, @NotNull Consumer<DBCExecutionContext> then) {
        List<String> tables = new ArrayList<>();
        for (FkColumnRef.TableRef t : ref.getFromTables()) {
            tables.add(t.getName());
        }
        resolveTables(editor, tables, then);
    }

    /** dbeaver-mm K9: same, for a statement's table names (used before content assist). */
    static void resolveTables(@NotNull SQLEditorBase editor, @NotNull List<String> tables, @NotNull Consumer<DBCExecutionContext> then) {
        if (tables.isEmpty()) {
            then.accept(editor.getExecutionContext());
            return;
        }
        String key = String.join(",", new TreeSet<>(tables)).toLowerCase(Locale.ROOT);
        new AbstractJob("Find connection for SQL tables") {
            @Override
            protected IStatus run(DBRProgressMonitor monitor) {
                // dbeaver-mm K10: the editor's connections bar wins; empty means the tagged ones
                List<DBPDataSourceContainer> pool = org.jkiss.dbeaver.ui.editors.sql.SqlConnectionsBar.candidates(
                    editor instanceof SQLEditor sqlEditor ? sqlEditor.getMmAutoConnectionIds() : java.util.Set.of());
                List<DBPDataSourceContainer> candidates = InlineFkService.findConnectionsWithTables(monitor, tables, pool);
                UIUtils.asyncExec(() -> choose(editor, key, candidates, then));
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private static void choose(
        @NotNull SQLEditorBase editor,
        @NotNull String key,
        @NotNull List<DBPDataSourceContainer> candidates,
        @NotNull Consumer<DBCExecutionContext> then
    ) {
        if (candidates.isEmpty()) {
            // No tagged connection has these tables: keep the editor's own connection
            then.accept(editor.getExecutionContext());
            return;
        }
        DBPDataSourceContainer remembered = PICKS.getOrDefault(editor, Map.of()).get(key);
        if (remembered != null && candidates.contains(remembered)) {
            use(editor, remembered, then);
        } else if (candidates.size() == 1) {
            use(editor, candidates.getFirst(), then);
        } else {
            askUser(editor, key, candidates, then);
        }
    }

    private static void askUser(
        @NotNull SQLEditorBase editor,
        @NotNull String key,
        @NotNull List<DBPDataSourceContainer> candidates,
        @NotNull Consumer<DBCExecutionContext> then
    ) {
        StyledText text = editor.getTextViewer() == null ? null : editor.getTextViewer().getTextWidget();
        if (text == null || text.isDisposed()) {
            return;
        }
        Menu menu = new Menu(text.getShell(), SWT.POP_UP);
        MenuItem title = new MenuItem(menu, SWT.PUSH);
        title.setText("Run against which connection?");
        title.setEnabled(false);
        new MenuItem(menu, SWT.SEPARATOR);
        for (DBPDataSourceContainer container : candidates) {
            MenuItem item = new MenuItem(menu, SWT.PUSH);
            item.setText(container.getName() + (container == currentContainer(editor) ? "  (current)" : ""));
            item.addListener(SWT.Selection, e -> {
                PICKS.computeIfAbsent(editor, k -> new HashMap<>()).put(key, container);
                use(editor, container, then);
            });
        }
        menu.addListener(SWT.Hide, e -> UIUtils.asyncExec(menu::dispose));
        Point caret = text.getLocationAtOffset(text.getCaretOffset());
        menu.setLocation(text.toDisplay(caret.x, caret.y + text.getLineHeight()));
        menu.setVisible(true);
    }

    @org.jkiss.code.Nullable
    private static DBPDataSourceContainer currentContainer(@NotNull SQLEditorBase editor) {
        return editor instanceof org.jkiss.dbeaver.model.DBPDataSourceContainerProvider p ? p.getDataSourceContainer() : null;
    }

    private static void use(
        @NotNull SQLEditorBase editor,
        @NotNull DBPDataSourceContainer container,
        @NotNull Consumer<DBCExecutionContext> then
    ) {
        if (container != currentContainer(editor) && editor instanceof SQLEditor sqlEditor) {
            log.debug("SQL auto connection: " + container.getName());
            sqlEditor.setDataSourceContainer(container);
        }
        then.accept(InlineFkService.getDefaultContext(container));
    }
}
