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
package org.jkiss.dbeaver.ui.controls.resultset.conf;

import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.UIUtils;

import java.util.*;
import java.util.List;
import java.util.function.Consumer;

/**
 * dbeaver-mm K34: delete a conf package row. One flat table: the rows above it (the package rows it
 * uses, unchecked), the row itself (always deleted) and the rows below it (rows pointing at it,
 * checked down to the level picked in the combo). Checkbox, Space or double click (un)checks a row.
 * Every change is passed to {@code preview} so the panel shows the choice in red meanwhile.
 */
public class ConfDeleteDialog extends Dialog {

    private static final RGB DELETE_RGB = new RGB(255, 205, 210);
    private static final int MAX_LISTED = 10;

    @NotNull
    private final String task;
    @NotNull
    private final ConfPackages.Insert root;
    @NotNull
    private final List<ConfPackages.Insert> all;
    @NotNull
    private final Map<String, String> links;
    @NotNull
    private final Consumer<Set<ConfPackages.Insert>> preview;
    // above (most distant first), the row, below by level
    private final Map<ConfPackages.Insert, Integer> levels = new LinkedHashMap<>();
    private final Set<ConfPackages.Insert> toDelete = new LinkedHashSet<>();

    private Table table;
    private Label summary;
    private Text warning;

    public ConfDeleteDialog(
        @NotNull Shell shell,
        @NotNull String task,
        @NotNull ConfPackages.Insert root,
        @NotNull List<ConfPackages.Insert> all,
        @NotNull Map<String, String> links,
        @NotNull Consumer<Set<ConfPackages.Insert>> preview
    ) {
        super(shell);
        this.task = task;
        this.root = root;
        this.all = all;
        this.links = links;
        this.preview = preview;
        List<Map.Entry<ConfPackages.Insert, Integer>> above = new ArrayList<>(ConfPackages.levelsAbove(root, all, links).entrySet());
        above.sort(Map.Entry.comparingByValue());
        above.forEach(e -> levels.put(e.getKey(), e.getValue()));
        levels.putAll(ConfPackages.levelsBelow(root, all, links));
    }

    @NotNull
    public Set<ConfPackages.Insert> getToDelete() {
        return toDelete;
    }

    /** Rows not deleted that point at a deleted row. */
    @NotNull
    static Set<ConfPackages.Insert> orphans(
        @NotNull Set<ConfPackages.Insert> deleted,
        @NotNull List<ConfPackages.Insert> all,
        @NotNull Map<String, String> links
    ) {
        Set<ConfPackages.Insert> result = new LinkedHashSet<>();
        for (ConfPackages.Insert row : deleted) {
            for (ConfPackages.Insert child : ConfPackages.referencing(row, all, links)) {
                if (!deleted.contains(child)) {
                    result.add(child);
                }
            }
        }
        return result;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Delete from conf package " + task);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        new Label(area, SWT.NONE).setText("Rows below:");
        Combo depthCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);
        int maxLevel = Collections.max(levels.values());
        depthCombo.add("Keep (delete only this row)");
        for (int level = 1; level <= maxLevel; level++) {
            depthCombo.add(level == maxLevel ? "Delete all (" + level + " levels)" : "Delete down to level " + level);
        }
        depthCombo.select(0);
        depthCombo.addListener(SWT.Selection, e -> checkToDepth(depthCombo.getSelectionIndex()));

        table = new Table(area, SWT.CHECK | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        table.setHeaderVisible(true);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        tableData.widthHint = 720;
        tableData.heightHint = Math.min(12, levels.size() + 1) * table.getItemHeight() + table.getHeaderHeight();
        table.setLayoutData(tableData);
        for (String title : new String[]{"Delete", "Level", "Table", "Row"}) {
            new TableColumn(table, SWT.LEFT).setText(title);
        }
        levels.forEach((row, level) -> {
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(1, level == 0 ? "this row" : level < 0 ? "above " + -level + " (uses)" : "below " + level);
            item.setText(2, row.table());
            item.setText(3, ConfPackagesView.describe(row).substring(row.table().length()).strip());
            item.setData(row);
        });
        for (TableColumn column : table.getColumns()) {
            column.pack();
        }
        table.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK) {
                changed();
            }
        });
        table.addListener(SWT.DefaultSelection, e -> {
            if (e.item instanceof TableItem item) {
                item.setChecked(!item.getChecked());
                changed();
            }
        });

        summary = new Label(area, SWT.NONE);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        warning = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        warning.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_DARK_RED));
        warning.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        if (links.isEmpty()) {
            Label note = new Label(area, SWT.WRAP);
            note.setText("FK information is not loaded (is the table's connection open?), related rows can't be found.");
            note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        }
        checkToDepth(0);
        return area;
    }

    private void checkToDepth(int depth) {
        for (TableItem item : table.getItems()) {
            int level = levels.get((ConfPackages.Insert) item.getData());
            item.setChecked(level >= 0 && level <= depth);
        }
        changed();
    }

    /** Reads the checks (the row itself stays checked), paints them, updates summary, warning and the panel. */
    private void changed() {
        toDelete.clear();
        for (TableItem item : table.getItems()) {
            if (item.getData() == root) {
                item.setChecked(true);
            }
            item.setBackground(item.getChecked() ? UIUtils.getSharedColor(DELETE_RGB) : null);
            item.setForeground(item.getChecked() ? table.getDisplay().getSystemColor(SWT.COLOR_BLACK) : null);
            if (item.getChecked()) {
                toDelete.add((ConfPackages.Insert) item.getData());
            }
        }
        summary.setText(toDelete.size() + " row(s) will be removed from the package (red in the panel too).");
        Set<ConfPackages.Insert> left = orphans(toDelete, all, links);
        if (left.isEmpty()) {
            warning.setText("");
        } else {
            StringBuilder text = new StringBuilder("Warning: " + left.size()
                + " row(s) use a deleted row and stay in the package (amber in the panel):");
            left.stream().limit(MAX_LISTED).forEach(o -> text.append("\n  ").append(ConfPackagesView.describe(o)));
            if (left.size() > MAX_LISTED) {
                text.append("\n  ... +").append(left.size() - MAX_LISTED);
            }
            warning.setText(text.toString());
        }
        warning.getParent().layout(true, true);
        preview.accept(Set.copyOf(toDelete));
    }
}
