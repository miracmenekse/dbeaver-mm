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
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;

import java.math.BigDecimal;
import java.util.*;
import java.util.List;

/**
 * dbeaver-mm K30: delete a conf package row, alone or with the rows below it. "Below" = rows of the
 * same package whose FK points to it, level by level. The tree shows the row and its dependents;
 * the level combo checks the rows to delete, single rows can be (un)checked by hand. Rows left in
 * the package while a row they reference is deleted are listed as a warning.
 */
public class ConfDeleteDialog extends Dialog {

    private static final int MAX_LISTED = 10;

    @NotNull
    private final ConfPackages.Insert root;
    @NotNull
    private final List<ConfPackages.Insert> all;
    @NotNull
    private final Map<String, String> links;
    private final Set<ConfPackages.Insert> toDelete = new LinkedHashSet<>();

    private Tree tree;
    private Label summary;
    private Text warning;

    /** {@code links}: "table.column" of an FK column -> "table.column" it references (lower case) */
    public ConfDeleteDialog(
        @NotNull Shell shell,
        @NotNull ConfPackages.Insert root,
        @NotNull List<ConfPackages.Insert> all,
        @NotNull Map<String, String> links
    ) {
        super(shell);
        this.root = root;
        this.all = all;
        this.links = links;
    }

    @NotNull
    public Set<ConfPackages.Insert> getToDelete() {
        return toDelete;
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Delete from conf package");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        new Label(area, SWT.NONE).setText("Delete:");
        Combo depthCombo = new Combo(area, SWT.READ_ONLY | SWT.DROP_DOWN);

        tree = new Tree(area, SWT.BORDER | SWT.CHECK | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData treeData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        treeData.widthHint = 700;
        treeData.heightHint = 250;
        tree.setLayoutData(treeData);
        int maxDepth = addItem(null, root, 0, new HashSet<>());
        for (TreeItem item : allItems()) {
            item.setExpanded(true);
        }

        depthCombo.add("Only this row");
        for (int level = 1; level <= maxDepth; level++) {
            depthCombo.add(level == maxDepth ? "This row and all rows below (" + level + " levels)" : "Down to level " + level);
        }
        depthCombo.select(0);
        depthCombo.addListener(SWT.Selection, e -> checkToDepth(depthCombo.getSelectionIndex()));

        tree.addListener(SWT.Selection, e -> {
            if (e.detail == SWT.CHECK) {
                if (e.item.getData("depth").equals(0)) {
                    ((TreeItem) e.item).setChecked(true);
                }
                update();
            }
        });

        summary = new Label(area, SWT.NONE);
        summary.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        warning = new Text(area, SWT.MULTI | SWT.READ_ONLY | SWT.WRAP);
        warning.setForeground(area.getDisplay().getSystemColor(SWT.COLOR_RED));
        warning.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        if (links.isEmpty()) {
            Label note = new Label(area, SWT.WRAP);
            note.setText("FK information is not loaded (is the table's connection open?), rows below can't be found.");
            note.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        }
        checkToDepth(0);
        return area;
    }

    /** Adds {@code row} and, recursively, the rows referencing it; returns the deepest level reached. */
    private int addItem(TreeItem parent, @NotNull ConfPackages.Insert row, int depth, @NotNull Set<ConfPackages.Insert> path) {
        TreeItem item = parent == null ? new TreeItem(tree, SWT.NONE) : new TreeItem(parent, SWT.NONE);
        item.setText(describe(row));
        item.setData(row);
        item.setData("depth", depth);
        int deepest = depth;
        path.add(row);
        for (ConfPackages.Insert child : referencing(row)) {
            if (!path.contains(child)) {
                deepest = Math.max(deepest, addItem(item, child, depth + 1, path));
            }
        }
        path.remove(row);
        return deepest;
    }

    private void checkToDepth(int depth) {
        for (TreeItem item : allItems()) {
            item.setChecked((Integer) item.getData("depth") <= depth);
        }
        update();
    }

    /** Recomputes the rows to delete and the rows that would be left pointing at deleted ones. */
    private void update() {
        toDelete.clear();
        for (TreeItem item : allItems()) {
            if (item.getChecked()) {
                toDelete.add((ConfPackages.Insert) item.getData());
            }
        }
        summary.setText(toDelete.size() + " row(s) will be removed from the package.");
        List<String> orphans = new ArrayList<>();
        for (ConfPackages.Insert deleted : toDelete) {
            for (ConfPackages.Insert child : referencing(deleted)) {
                if (!toDelete.contains(child)) {
                    orphans.add(describe(child));
                }
            }
        }
        if (orphans.isEmpty()) {
            warning.setText("");
        } else {
            StringBuilder text = new StringBuilder("Warning: " + orphans.size()
                + " row(s) use a deleted row and stay in the package:");
            orphans.stream().limit(MAX_LISTED).forEach(o -> text.append("\n  ").append(o));
            if (orphans.size() > MAX_LISTED) {
                text.append("\n  ... +").append(orphans.size() - MAX_LISTED);
            }
            warning.setText(text.toString());
        }
        warning.getParent().layout(true, true);
    }

    /** Rows of the package whose FK value points to {@code row}'s referenced key column. */
    @NotNull
    private List<ConfPackages.Insert> referencing(@NotNull ConfPackages.Insert row) {
        List<ConfPackages.Insert> result = new ArrayList<>();
        String table = row.table().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> link : links.entrySet()) {
            String target = link.getValue();
            if (!target.startsWith(table + ".")) {
                continue;
            }
            String key = row.values().get(target.substring(table.length() + 1));
            if (key == null) {
                continue;
            }
            int dot = link.getKey().lastIndexOf('.');
            String fkTable = link.getKey().substring(0, dot);
            String fkColumn = link.getKey().substring(dot + 1);
            for (ConfPackages.Insert other : all) {
                String value = other.values().get(fkColumn);
                if (other != row && value != null && other.table().equalsIgnoreCase(fkTable)
                    && plainNumber(value).equals(plainNumber(key)) && !result.contains(other)) {
                    result.add(other);
                }
            }
        }
        return result;
    }

    @NotNull
    private List<TreeItem> allItems() {
        List<TreeItem> items = new ArrayList<>();
        Deque<TreeItem> todo = new ArrayDeque<>(Arrays.asList(tree.getItems()));
        while (!todo.isEmpty()) {
            TreeItem item = todo.pop();
            items.add(item);
            todo.addAll(Arrays.asList(item.getItems()));
        }
        return items;
    }

    @NotNull
    private static String describe(@NotNull ConfPackages.Insert row) {
        StringJoiner text = new StringJoiner(", ", row.table() + "  ", "");
        row.values().entrySet().stream().limit(4)
            .forEach(e -> text.add(e.getKey() + "=" + (e.getValue() == null ? "NULL" : e.getValue())));
        return row.values().size() > 4 ? text + ", ..." : text.toString();
    }

    @NotNull
    private static String plainNumber(@NotNull String value) {
        try {
            return new BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }
}
