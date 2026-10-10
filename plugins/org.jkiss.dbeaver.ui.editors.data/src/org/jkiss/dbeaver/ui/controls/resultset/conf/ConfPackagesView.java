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

import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionContextDefaults;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.model.struct.*;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.data.hints.FkDictionaryLabels;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DateFormat;
import java.util.*;
import java.util.List;

/**
 * dbeaver-mm K27: bottom panel listing conf packages (newest first). The selected package's
 * INSERTs are shown as one row grid per table, its whole text on the SQL tab (UPDATE/DELETE live
 * there). Refreshes itself whenever a file below {@code Scripts/conf} changes.
 */
public class ConfPackagesView extends ViewPart implements IResourceChangeListener {

    private static final Log log = Log.getLog(ConfPackagesView.class);

    private static final int REFRESH_DELAY_MS = 200;

    private Table taskTable;
    private ScrolledComposite rowsScroll;
    private Composite rowsArea;
    private Text sqlText;
    private Composite copyBar;
    private static final String INSERT_KEY = "insert";

    // The shown package: its text and rows as parsed, FK links once loaded (K30 delete)
    private String currentTask;
    private String currentSql = "";
    private List<ConfPackages.Insert> currentInserts = List.of();
    private Map<String, String> currentLinks = Map.of();
    // Bumped on every package switch so a slow label lookup can't paint over a newer package
    private int labelGeneration;
    // timerExec reschedules the same instance, which debounces bursts of resource deltas
    private final Runnable refresher = this::refresh;

    @Override
    public void createPartControl(@NotNull Composite parent) {
        SashForm sash = new SashForm(parent, SWT.HORIZONTAL);

        taskTable = new Table(sash, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER);
        taskTable.setHeaderVisible(true);
        for (String title : new String[]{"Task", "Description", "Changed"}) {
            new TableColumn(taskTable, SWT.LEFT).setText(title);
        }
        taskTable.addListener(SWT.Selection, e -> showSelected());

        Composite right = new Composite(sash, SWT.NONE);
        GridLayout rightLayout = new GridLayout(1, false);
        rightLayout.marginWidth = rightLayout.marginHeight = 0;
        right.setLayout(rightLayout);
        copyBar = new Composite(right, SWT.NONE);
        copyBar.setLayout(new org.eclipse.swt.layout.RowLayout(SWT.HORIZONTAL));
        copyBar.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
        CTabFolder tabs = new CTabFolder(right, SWT.BORDER | SWT.BOTTOM);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        rowsScroll = new ScrolledComposite(tabs, SWT.V_SCROLL | SWT.H_SCROLL);
        rowsScroll.setExpandHorizontal(true);
        rowsScroll.setExpandVertical(true);
        rowsArea = new Composite(rowsScroll, SWT.NONE);
        rowsArea.setLayout(new GridLayout(1, false));
        rowsScroll.setContent(rowsArea);
        CTabItem rowsTab = new CTabItem(tabs, SWT.NONE);
        rowsTab.setText("Rows");
        rowsTab.setControl(rowsScroll);

        sqlText = new Text(tabs, SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL | SWT.H_SCROLL);
        sqlText.setFont(JFaceResources.getTextFont());
        CTabItem sqlTab = new CTabItem(tabs, SWT.NONE);
        sqlTab.setText("SQL");
        sqlTab.setControl(sqlText);
        tabs.setSelection(rowsTab);

        sash.setWeights(new int[]{30, 70});

        Action refreshAction = new Action("Refresh", DBeaverIcons.getImageDescriptor(UIIcon.REFRESH)) {
            @Override
            public void run() {
                refresh();
            }
        };
        getViewSite().getActionBars().getToolBarManager().add(refreshAction);

        ResourcesPlugin.getWorkspace().addResourceChangeListener(this, IResourceChangeEvent.POST_CHANGE);
        refresh();
    }

    @Override
    public void setFocus() {
        taskTable.setFocus();
    }

    @Override
    public void dispose() {
        ResourcesPlugin.getWorkspace().removeResourceChangeListener(this);
        super.dispose();
    }

    /** Not on the UI thread: only checks whether a conf package changed and asks for a refresh. */
    @Override
    public void resourceChanged(@NotNull IResourceChangeEvent event) {
        Path folder = ConfPackages.folder();
        IResourceDelta root = event.getDelta();
        if (folder == null || root == null) {
            return;
        }
        boolean[] changed = {false};
        try {
            root.accept(delta -> {
                IPath location = delta.getResource().getLocation();
                if (location != null && Path.of(location.toOSString()).startsWith(folder)) {
                    changed[0] = true;
                }
                return !changed[0];
            });
        } catch (CoreException e) {
            log.debug("Can't process resource change", e);
        }
        if (changed[0]) {
            UIUtils.asyncExec(() -> {
                if (!taskTable.isDisposed()) {
                    taskTable.getDisplay().timerExec(REFRESH_DELAY_MS, refresher);
                }
            });
        }
    }

    /** Reloads the package list, keeping the selected task (the newest one when none). */
    private void refresh() {
        if (taskTable.isDisposed()) {
            return;
        }
        String selected = selectedTask();
        Path folder = ConfPackages.folder();
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT);
        taskTable.removeAll();
        for (String task : ConfPackages.listTasks()) {
            String changed = "";
            try {
                changed = format.format(new Date(Files.getLastModifiedTime(folder.resolve(ConfPackages.fileName(task))).toMillis()));
            } catch (Exception e) {
                // gone in the meantime, the next refresh drops it
            }
            TableItem item = new TableItem(taskTable, SWT.NONE);
            item.setText(new String[]{task, ConfPackages.readDescription(task), changed});
            if (task.equals(selected)) {
                taskTable.setSelection(item);
            }
        }
        if (taskTable.getSelectionIndex() < 0 && taskTable.getItemCount() > 0) {
            taskTable.setSelection(0);
        }
        for (TableColumn column : taskTable.getColumns()) {
            column.pack();
        }
        showSelected();
    }

    private String selectedTask() {
        TableItem[] selection = taskTable.getSelection();
        return selection.length == 0 ? null : selection[0].getText(0);
    }

    private void showSelected() {
        String task = selectedTask();
        String sql = task == null ? "" : ConfPackages.read(task);
        sqlText.setText(sql);

        for (Control child : rowsArea.getChildren()) {
            child.dispose();
        }
        // Group rows by table in order of first appearance, columns = union of their keys
        currentTask = task;
        currentSql = sql;
        currentInserts = ConfPackages.parseInserts(sql, null);
        currentLinks = Map.of();
        fillCopyBar();
        Map<String, List<Map<String, String>>> byTable = new LinkedHashMap<>();
        Map<Map<String, String>, ConfPackages.Insert> insertOf = new IdentityHashMap<>();
        for (ConfPackages.Insert insert : currentInserts) {
            byTable.computeIfAbsent(insert.table().toLowerCase(Locale.ROOT), t -> new ArrayList<>()).add(insert.values());
            insertOf.put(insert.values(), insert);
        }
        if (byTable.isEmpty() && task != null) {
            new Label(rowsArea, SWT.NONE).setText("No INSERT in this package, see the SQL tab.");
        }
        for (Map.Entry<String, List<Map<String, String>>> entry : byTable.entrySet()) {
            Label title = new Label(rowsArea, SWT.NONE);
            title.setText(entry.getKey() + " (" + entry.getValue().size() + " rows)");
            title.setFont(JFaceResources.getBannerFont());

            Set<String> columns = new LinkedHashSet<>();
            entry.getValue().forEach(row -> columns.addAll(row.keySet()));
            Table rows = new Table(rowsArea, SWT.BORDER | SWT.FULL_SELECTION | SWT.MULTI);
            rows.setData(entry.getKey());
            addCopySupport(rows);
            rows.setHeaderVisible(true);
            rows.setLinesVisible(true);
            rows.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            for (String column : columns) {
                new TableColumn(rows, SWT.LEFT).setText(column);
            }
            for (Map<String, String> row : entry.getValue()) {
                TableItem item = new TableItem(rows, SWT.NONE);
                String[] raw = new String[columns.size()];
                int i = 0;
                for (String column : columns) {
                    raw[i] = row.containsKey(column) ? row.get(column) : "";
                    item.setText(i, raw[i] == null ? "[NULL]" : raw[i]);
                    i++;
                }
                item.setData(raw);
                item.setData(INSERT_KEY, insertOf.get(row));
            }
            for (TableColumn column : rows.getColumns()) {
                column.pack();
            }
        }
        relayoutRows();
        loadLabels(byTable);
    }

    /**
     * K31: one copy button per schema the package writes to (pcm, domain_config, ... are pasted into
     * the site separately), plus "Copy all" when there is more than one. Description line left out.
     */
    private void fillCopyBar() {
        for (Control child : copyBar.getChildren()) {
            child.dispose();
        }
        // Rebuilt once FK links are loaded, so the buttons copy in FK-safe order
        Map<String, String> scripts = ConfPackages.scriptsBySchema(currentSql, currentLinks);
        if (scripts.size() > 1) {
            addCopyButton("Copy all", String.join("\n", scripts.values()));
        }
        scripts.forEach((schema, script) -> addCopyButton(
            schema.isEmpty() ? (scripts.size() > 1 ? "Copy (no schema)" : "Copy script") : "Copy " + schema,
            script));
        copyBar.getParent().layout(true, true);
    }

    private void addCopyButton(@NotNull String text, @NotNull String script) {
        Button button = new Button(copyBar, SWT.PUSH);
        button.setText(text);
        long count = ConfPackages.splitStatements(script).size();
        button.setToolTipText(count + " statement(s) to the clipboard"
            + (currentLinks.isEmpty() ? ", file order (FK info not loaded yet)" : ", in FK order"));
        button.addListener(SWT.Selection, e ->
            UIUtils.setClipboardContents(copyBar.getDisplay(), TextTransfer.getInstance(), script));
    }

    private void relayoutRows() {
        rowsArea.layout(true, true);
        rowsScroll.setMinSize(rowsArea.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    /**
     * FK columns get {@code value | label} like the grid, labels read in the background. The table
     * is looked up in the connected connections of the active project, the first one that has it.
     */
    // ponytail: first connected connection with the table wins; a package doesn't record its connection
    private void loadLabels(@NotNull Map<String, List<Map<String, String>>> byTable) {
        int generation = ++labelGeneration;
        if (byTable.isEmpty()) {
            return;
        }
        Job job = Job.create("Conf package labels", progress -> {
            DBRProgressMonitor monitor = new DefaultProgressMonitor(progress);
            // table -> column -> value -> label
            Map<String, Map<String, Map<String, String>>> labels = new HashMap<>();
            // "table.column" of an FK column -> "table.column" it references
            Map<String, String> links = new HashMap<>();
            for (Map.Entry<String, List<Map<String, String>>> entry : byTable.entrySet()) {
                DBSEntity entity = findTable(monitor, entry.getKey());
                if (entity == null) {
                    continue;
                }
                Map<String, Set<String>> valuesByColumn = new LinkedHashMap<>();
                entry.getValue().forEach(row -> row.forEach((column, value) -> {
                    if (value != null) {
                        valuesByColumn.computeIfAbsent(column, c -> new LinkedHashSet<>()).add(value);
                    }
                }));
                for (Map.Entry<String, Set<String>> column : valuesByColumn.entrySet()) {
                    try {
                        DBSEntityAttribute attribute = DBUtils.findObject(entity.getAttributes(monitor), column.getKey(), true);
                        if (attribute != null) {
                            String target = referencedColumn(monitor, attribute);
                            if (target != null) {
                                links.put(entry.getKey() + "." + column.getKey(), target);
                            }
                            Map<String, String> found = FkDictionaryLabels.labelsOf(monitor, attribute, column.getValue());
                            if (!found.isEmpty()) {
                                labels.computeIfAbsent(entry.getKey(), t -> new HashMap<>()).put(column.getKey(), found);
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Can't read FK labels of " + entry.getKey() + "." + column.getKey(), e);
                    }
                }
            }
            if (!labels.isEmpty() || !links.isEmpty()) {
                UIUtils.asyncExec(() -> {
                    applyLabels(generation, labels);
                    applyLinkColors(generation, links);
                });
            }
            return Status.OK_STATUS;
        });
        job.setSystem(true);
        job.schedule();
    }

    private void applyLabels(int generation, @NotNull Map<String, Map<String, Map<String, String>>> labels) {
        if (generation != labelGeneration || rowsArea.isDisposed()) {
            return;
        }
        for (Control control : rowsArea.getChildren()) {
            if (!(control instanceof Table rows) || !labels.containsKey((String) rows.getData())) {
                continue;
            }
            Map<String, Map<String, String>> tableLabels = labels.get((String) rows.getData());
            TableColumn[] columns = rows.getColumns();
            for (int i = 0; i < columns.length; i++) {
                Map<String, String> columnLabels = tableLabels.get(columns[i].getText());
                if (columnLabels == null) {
                    continue;
                }
                for (TableItem item : rows.getItems()) {
                    String value = ((String[]) item.getData())[i];
                    String label = value == null ? null : columnLabels.get(plainNumber(value));
                    if (label != null) {
                        item.setText(i, value + " | " + label);
                    }
                }
                columns[i].pack();
            }
        }
        relayoutRows();
    }

    /** "table.column" (lower case) the FK column {@code attribute} references, physical or virtual FK */
    @Nullable
    private static String referencedColumn(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntityAttribute attribute) {
        try {
            for (DBSEntityReferrer ref : DBUtils.getAttributeReferrers(monitor, attribute, true)) {
                if (ref instanceof DBSEntityAssociation association && association.getReferencedConstraint() != null) {
                    DBSEntityAttribute key = DBUtils.getReferenceAttribute(monitor, association, attribute, false);
                    if (key != null) {
                        return (key.getParentObject().getName() + "." + key.getName()).toLowerCase(Locale.ROOT);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't read FK of " + attribute.getName(), e);
        }
        return null;
    }

    // Light enough for dark text, distinct enough to tell apart; repeats after 8 relations
    private static final RGB[] LINK_COLORS = {
        new RGB(255, 224, 178), new RGB(200, 230, 201), new RGB(187, 222, 251), new RGB(248, 187, 208),
        new RGB(225, 190, 231), new RGB(255, 245, 157), new RGB(178, 235, 242), new RGB(215, 204, 200),
    };

    /**
     * A row's FK value and the row it points to in the same package get the same background, one
     * color per referenced key value, so which rows belong together can be seen at a glance.
     */
    private void applyLinkColors(int generation, @NotNull Map<String, String> links) {
        if (generation != labelGeneration || rowsArea.isDisposed() || links.isEmpty()) {
            return;
        }
        currentLinks = links;
        fillCopyBar();
        // "table.column=value" of every referenced key cell in this package -> its cells
        Set<String> targets = new HashSet<>(links.values());
        Map<String, List<Object[]>> keyCells = new HashMap<>();
        List<Object[]> fkCells = new ArrayList<>();
        for (Control control : rowsArea.getChildren()) {
            if (!(control instanceof Table rows)) {
                continue;
            }
            TableColumn[] columns = rows.getColumns();
            for (int i = 0; i < columns.length; i++) {
                String column = rows.getData() + "." + columns[i].getText();
                for (TableItem item : rows.getItems()) {
                    String value = ((String[]) item.getData())[i];
                    if (value == null || value.isEmpty()) {
                        continue;
                    }
                    if (targets.contains(column)) {
                        keyCells.computeIfAbsent(column + "=" + plainNumber(value), k -> new ArrayList<>()).add(new Object[]{item, i});
                    }
                    if (links.containsKey(column)) {
                        fkCells.add(new Object[]{item, i, links.get(column) + "=" + plainNumber(value)});
                    }
                }
            }
        }
        Map<String, Color> colors = new HashMap<>();
        for (Object[] fk : fkCells) {
            List<Object[]> keys = keyCells.get((String) fk[2]);
            if (keys == null) {
                continue;
            }
            Color color = colors.computeIfAbsent((String) fk[2],
                k -> UIUtils.getSharedColor(LINK_COLORS[colors.size() % LINK_COLORS.length]));
            List<Object[]> cells = new ArrayList<>(keys);
            cells.add(fk);
            for (Object[] cell : cells) {
                TableItem item = (TableItem) cell[0];
                item.setBackground((Integer) cell[1], color);
                // dark theme: light text on a light background would be unreadable
                item.setForeground((Integer) cell[1], item.getDisplay().getSystemColor(SWT.COLOR_BLACK));
            }
        }
    }

    /** "40.0" and "40" are the same key, like the labels' keys */
    @NotNull
    private static String plainNumber(@NotNull String value) {
        try {
            return new java.math.BigDecimal(value).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            return value;
        }
    }

    @Nullable
    private static DBSEntity findTable(@NotNull DBRProgressMonitor monitor, @NotNull String table) {
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project == null) {
            return null;
        }
        for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
            if (!container.isConnected() || container.getDataSource() == null) {
                continue;
            }
            try {
                DBCExecutionContext context = DBUtils.getDefaultContext(container.getDataSource(), true);
                DBCExecutionContextDefaults<?, ?> defaults = context == null ? null : context.getContextDefaults();
                DBSObjectContainer schema = defaults == null ? null
                    : defaults.getDefaultSchema() instanceof DBSObjectContainer s ? s
                    : defaults.getDefaultCatalog() instanceof DBSObjectContainer c ? c : null;
                if (schema == null) {
                    schema = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
                }
                if (schema != null && DBUtils.findObject(schema.getChildren(monitor), table, true) instanceof DBSEntity entity) {
                    return entity;
                }
            } catch (Exception e) {
                log.debug("Can't look up " + table + " in " + container.getName(), e);
            }
        }
        return null;
    }

    /**
     * Right click / Ctrl+C copies the raw value (without the FK label) of the clicked column for the
     * selected rows; "Copy row" copies whole rows tab separated. Native selection, no extra widgets.
     */
    private void addCopySupport(@NotNull Table rows) {
        int[] column = {0};
        rows.addListener(SWT.MouseDown, e -> {
            TableItem item = rows.getItem(new Point(e.x, e.y));
            for (int i = 0; item != null && i < rows.getColumnCount(); i++) {
                if (item.getBounds(i).contains(e.x, e.y)) {
                    column[0] = i;
                }
            }
        });
        Runnable copyValue = () -> copy(rows, column[0]);
        rows.addListener(SWT.KeyDown, e -> {
            if ((e.stateMask & SWT.MOD1) != 0 && (e.keyCode == 'c' || e.keyCode == 'C')) {
                copyValue.run();
            }
        });
        Menu menu = new Menu(rows);
        MenuItem copyValueItem = new MenuItem(menu, SWT.PUSH);
        copyValueItem.setText("Copy value\tCtrl+C");
        copyValueItem.addListener(SWT.Selection, e -> copyValue.run());
        MenuItem copyRowItem = new MenuItem(menu, SWT.PUSH);
        copyRowItem.setText("Copy row");
        copyRowItem.addListener(SWT.Selection, e -> copy(rows, -1));
        new MenuItem(menu, SWT.SEPARATOR);
        MenuItem deleteItem = new MenuItem(menu, SWT.PUSH);
        deleteItem.setText("Delete row ...");
        deleteItem.addListener(SWT.Selection, e -> {
            TableItem[] selection = rows.getSelection();
            if (selection.length > 0 && selection[0].getData(INSERT_KEY) instanceof ConfPackages.Insert insert) {
                deleteRow(insert);
            }
        });
        rows.setMenu(menu);
    }

    /** K30: asks how deep to delete, then removes the chosen INSERTs from the package file. */
    private void deleteRow(@NotNull ConfPackages.Insert insert) {
        ConfDeleteDialog dialog = new ConfDeleteDialog(getSite().getShell(), insert, currentInserts, currentLinks);
        if (dialog.open() != ConfDeleteDialog.OK || dialog.getToDelete().isEmpty()) {
            return;
        }
        try {
            ConfPackages.remove(currentTask, currentSql, dialog.getToDelete());
        } catch (Exception e) {
            DBWorkbench.getPlatformUI().showError("Delete from conf package", e.getMessage(), e);
        }
        refresh();
    }

    /** {@code column} < 0 = whole rows, tab separated; one line per selected row */
    private void copy(@NotNull Table rows, int column) {
        StringJoiner text = new StringJoiner("\n");
        for (TableItem item : rows.getSelection()) {
            String[] raw = (String[]) item.getData();
            StringJoiner line = new StringJoiner("\t");
            for (int i = 0; i < raw.length; i++) {
                if (column < 0 || i == column) {
                    line.add(raw[i] == null ? "NULL" : raw[i]);
                }
            }
            text.add(line.toString());
        }
        if (text.length() > 0) {
            UIUtils.setClipboardContents(rows.getDisplay(), TextTransfer.getInstance(), text.toString());
        }
    }
}
