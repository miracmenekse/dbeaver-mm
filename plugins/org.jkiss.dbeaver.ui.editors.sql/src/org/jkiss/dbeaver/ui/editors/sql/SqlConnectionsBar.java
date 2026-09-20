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
package org.jkiss.dbeaver.ui.editors.sql;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.utils.CommonUtils;

import java.util.*;

/**
 * dbeaver-mm K10: thin row on top of the SQL editor showing which connections this editor may use.
 * The button opens a filterable, multi-select list of the project's connections; the checked ones
 * are the candidates for automatic connection selection (see the inline FK picker's selector).
 * Nothing is checked by default, and then the connections tagged "Use for SQL auto connection"
 * in the navigator are used instead.
 */
public class SqlConnectionsBar {

    /** Same tag as the navigator's "Use for SQL auto connection" toggle. */
    public static final String AUTO_CONNECT_TAG = "mm.auto-connect";

    private static final int POPUP_WIDTH = 280;
    private static final int POPUP_HEIGHT = 240;

    private final Set<String> selectedIds = new LinkedHashSet<>();
    private Button button;

    public void createControl(@NotNull Composite parent) {
        Composite bar = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 0;
        layout.marginWidth = 2;
        bar.setLayout(layout);
        GridData barData = new GridData(SWT.BEGINNING, SWT.CENTER, true, false);
        barData.horizontalSpan = 3;
        bar.setLayoutData(barData);

        button = new Button(bar, SWT.PUSH | SWT.FLAT);
        button.setToolTipText("Connections this editor may run against (used to pick one automatically)");
        button.addListener(SWT.Selection, e -> showPopup());
        updateText();
    }

    /**
     * Ids of the connections checked for this editor; empty means "use the tagged ones".
     */
    @NotNull
    public Set<String> getSelectedIds() {
        return selectedIds;
    }

    private void updateText() {
        List<String> names = new ArrayList<>();
        for (DBPDataSourceContainer container : projectConnections()) {
            if (selectedIds.contains(container.getId())) {
                names.add(container.getName());
            }
        }
        button.setText(names.isEmpty() ? "Connections: tagged ▾" : "Connections: " + String.join(", ", names) + " ▾");
        button.getParent().getParent().layout(true, true);
    }

    @NotNull
    private static List<DBPDataSourceContainer> projectConnections() {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        return project == null ? List.of() : new ArrayList<>(project.getDataSourceRegistry().getDataSources());
    }

    private void showPopup() {
        Shell shell = new Shell(button.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.BORDER);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        shell.setLayout(layout);

        Text filter = new Text(shell, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        filter.setMessage("Type to filter connections");

        Table table = new Table(shell, SWT.CHECK | SWT.BORDER | SWT.V_SCROLL | SWT.FULL_SELECTION);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.widthHint = POPUP_WIDTH;
        tableData.heightHint = POPUP_HEIGHT;
        table.setLayoutData(tableData);

        Runnable fill = () -> {
            String pattern = filter.getText().toLowerCase(Locale.ROOT);
            table.removeAll();
            for (DBPDataSourceContainer container : projectConnections()) {
                if (!pattern.isEmpty() && !container.getName().toLowerCase(Locale.ROOT).contains(pattern)) {
                    continue;
                }
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(container.getName()
                    + ("true".equals(container.getTags().get(AUTO_CONNECT_TAG)) ? "  (tagged)" : ""));
                item.setData(container);
                item.setChecked(selectedIds.contains(container.getId()));
            }
        };
        fill.run();

        filter.addModifyListener(e -> fill.run());
        table.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK && e.item instanceof TableItem item
                && item.getData() instanceof DBPDataSourceContainer container) {
                if (item.getChecked()) {
                    selectedIds.add(container.getId());
                } else {
                    selectedIds.remove(container.getId());
                }
                updateText();
            }
        });
        shell.addListener(SWT.Deactivate, e -> shell.dispose());
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
                shell.dispose();
            }
        });

        shell.pack();
        Rectangle bounds = button.getBounds();
        Point location = button.getParent().toDisplay(bounds.x, bounds.y + bounds.height);
        shell.setLocation(location);
        shell.open();
        filter.setFocus();
    }

    @NotNull
    public static List<DBPDataSourceContainer> candidates(@NotNull Set<String> selectedIds) {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPDataSourceContainer container : projectConnections()) {
            boolean selected = selectedIds.isEmpty()
                ? "true".equals(container.getTags().get(AUTO_CONNECT_TAG))
                : selectedIds.contains(container.getId());
            if (selected && !CommonUtils.isEmpty(container.getId())) {
                result.add(container);
            }
        }
        return result;
    }
}
