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
import org.eclipse.jface.action.Action;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.CTabFolder;
import org.eclipse.swt.custom.CTabItem;
import org.eclipse.swt.custom.SashForm;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIUtils;

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

        CTabFolder tabs = new CTabFolder(sash, SWT.BORDER | SWT.BOTTOM);
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
        Map<String, List<Map<String, String>>> byTable = new LinkedHashMap<>();
        for (ConfPackages.Insert insert : ConfPackages.parseInserts(sql, null)) {
            byTable.computeIfAbsent(insert.table().toLowerCase(Locale.ROOT), t -> new ArrayList<>()).add(insert.values());
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
            rows.setHeaderVisible(true);
            rows.setLinesVisible(true);
            rows.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            for (String column : columns) {
                new TableColumn(rows, SWT.LEFT).setText(column);
            }
            for (Map<String, String> row : entry.getValue()) {
                TableItem item = new TableItem(rows, SWT.NONE);
                int i = 0;
                for (String column : columns) {
                    String value = row.containsKey(column) ? row.get(column) : "";
                    item.setText(i++, value == null ? "[NULL]" : value);
                }
            }
            for (TableColumn column : rows.getColumns()) {
                column.pack();
            }
        }
        rowsArea.layout(true, true);
        rowsScroll.setMinSize(rowsArea.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }
}
