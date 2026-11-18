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
package org.jkiss.dbeaver.ui.mm.workbench.navpane;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvent;
import org.jkiss.dbeaver.model.DBPEventListener;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.mm.workbench.navpane.TableSearch.Entry;
import org.jkiss.dbeaver.ui.mm.workbench.navpane.TableSearch.Hit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dbeaver-mm K138: connected connections and their tables in one flat, fuzzy-searchable
 * list. Only already connected data sources are read; the view never opens a connection.
 */
public class NavigationPaneView extends ViewPart {

    private static final Log log = Log.getLog(NavigationPaneView.class);

    private static final int SEARCH_DELAY_MS = 100;
    private static final int MATCH_LIMIT = 500;
    private static final int MAX_DEPTH = 8;
    private static final int HEADER_HEIGHT = 24;
    private static final int ITEM_HEIGHT = 22;
    private static final int MESSAGE_HEIGHT = 24;

    private final Map<String, List<Entry>> cache = new ConcurrentHashMap<>();
    private final DBPEventListener dataSourceListener = event -> scheduleReload();

    private Display display;
    private Text searchText;
    private Label countLabel;
    private Canvas list;
    private DBPDataSourceRegistry registry;

    private LoadJob loadJob;
    private Job searchJob;
    private int searchGeneration;

    private List<Entry> index = List.of();
    private Map<String, Integer> connectionTotals = Map.of();
    private List<Hit> hits = List.of();
    private List<Row> rows = List.of();
    private int connectedCount;
    private boolean loading;
    private String query = "";

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();

        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite bar = new Composite(root, SWT.NONE);
        GridLayout barLayout = new GridLayout(2, false);
        barLayout.marginWidth = 0;
        barLayout.marginHeight = 0;
        bar.setLayout(barLayout);

        searchText = new Text(bar, SWT.SEARCH | SWT.ICON_CANCEL);
        searchText.setMessage("Tablo ara…");
        searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchText.addModifyListener(event -> scheduleSearch());
        searchText.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.keyCode == SWT.ESC) {
                    if (!searchText.getText().isEmpty()) {
                        searchText.setText("");
                        startSearch("");
                    } else {
                        list.setFocus();
                    }
                }
            }
        });

        countLabel = new Label(bar, SWT.RIGHT);
        countLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

        list = new Canvas(root, SWT.V_SCROLL);
        list.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        list.addPaintListener(new PaintListener() {
            @Override
            public void paintControl(PaintEvent event) {
                paint(event.gc, list.getClientArea());
            }
        });
        list.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent event) {
                updateScrollBar();
                list.redraw();
            }
        });
        ScrollBar scrollBar = list.getVerticalBar();
        if (scrollBar != null) {
            scrollBar.addListener(SWT.Selection, event -> list.redraw());
        }

        try {
            DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
            if (project != null) {
                registry = project.getDataSourceRegistry();
                registry.addDataSourceListener(dataSourceListener);
            }
        } catch (Throwable e) {
            log.debug("Bağlantı kaydı dinlenemedi", e);
        }

        loading = true;
        scheduleReload();
    }

    @Override
    public void setFocus() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setFocus();
        }
    }

    @Override
    public void dispose() {
        if (searchJob != null) {
            searchJob.cancel();
        }
        if (loadJob != null) {
            loadJob.cancel();
        }
        if (registry != null) {
            registry.removeDataSourceListener(dataSourceListener);
        }
        super.dispose();
    }

    private void scheduleReload() {
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> {
            if (list == null || list.isDisposed()) {
                return;
            }
            loading = true;
            cache.clear();
            if (loadJob != null) {
                loadJob.cancel();
            }
            loadJob = new LoadJob();
            loadJob.schedule();
            rebuildRows();
        });
    }

    private void scheduleSearch() {
        if (display == null || display.isDisposed() || searchText == null) {
            return;
        }
        final String text = searchText.getText();
        final int generation = ++searchGeneration;
        display.timerExec(SEARCH_DELAY_MS, () -> {
            if (generation == searchGeneration && searchText != null && !searchText.isDisposed()) {
                startSearch(text);
            }
        });
    }

    private void startSearch(String text) {
        if (display == null || display.isDisposed() || list == null || list.isDisposed()) {
            return;
        }
        query = text;
        final int generation = ++searchGeneration;
        final List<Entry> snapshot = index;
        if (searchJob != null) {
            searchJob.cancel();
        }
        searchJob = new Job("Tablo arama") {
            @Override
            protected IStatus run(IProgressMonitor monitor) {
                List<Hit> result = TableSearch.search(snapshot, text, MATCH_LIMIT);
                if (monitor.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }
                display.asyncExec(() -> {
                    if (generation != searchGeneration || list == null || list.isDisposed()) {
                        return;
                    }
                    hits = result;
                    rebuildRows();
                });
                return Status.OK_STATUS;
            }
        };
        searchJob.setSystem(true);
        searchJob.schedule();
    }

    private void applyLoad(List<Entry> entries, int connected) {
        if (list == null || list.isDisposed()) {
            return;
        }
        index = entries;
        connectedCount = connected;
        Map<String, Integer> totals = new HashMap<>();
        for (Entry entry : entries) {
            totals.merge(entry.connection(), 1, Integer::sum);
        }
        connectionTotals = totals;
        loading = false;
        startSearch(searchText == null || searchText.isDisposed() ? "" : searchText.getText());
    }

    private void rebuildRows() {
        List<Row> newRows = new ArrayList<>();
        int y = 2;
        if (loading && index.isEmpty()) {
            newRows.add(new Row(Row.MESSAGE, y, MESSAGE_HEIGHT, "Tablolar yükleniyor…", null, null));
            y += MESSAGE_HEIGHT;
        } else if (connectedCount == 0) {
            newRows.add(new Row(Row.MESSAGE, y, MESSAGE_HEIGHT, "Bağlı bağlantı yok.", null, null));
            y += MESSAGE_HEIGHT;
            newRows.add(new Row(Row.MESSAGE, y, MESSAGE_HEIGHT,
                "Database Navigator'da bağlanın; tablolar burada görünür.", null, null));
            y += MESSAGE_HEIGHT;
        } else if (hits.isEmpty()) {
            String message = query.isEmpty()
                ? "Tablo bulunamadı."
                : "\"" + query + "\" ile eşleşen tablo yok (" + connectedCount + " bağlı bağlantı).";
            newRows.add(new Row(Row.MESSAGE, y, MESSAGE_HEIGHT, message, null, null));
            y += MESSAGE_HEIGHT;
        } else {
            Map<String, List<Hit>> groups = new LinkedHashMap<>();
            for (Hit hit : hits) {
                groups.computeIfAbsent(TableSearch.groupKey(hit), key -> new ArrayList<>()).add(hit);
            }
            for (Map.Entry<String, List<Hit>> group : groups.entrySet()) {
                int total = connectionTotals.getOrDefault(group.getKey(), group.getValue().size());
                String right = group.getValue().size() + " / " + total;
                newRows.add(new Row(Row.HEADER, y, HEADER_HEIGHT, group.getKey(), right, null));
                y += HEADER_HEIGHT;
                for (Hit hit : group.getValue()) {
                    newRows.add(new Row(Row.ITEM, y, ITEM_HEIGHT, hit.entry().table(), hit.entry().schema(), hit));
                    y += ITEM_HEIGHT;
                }
            }
            if (hits.size() >= MATCH_LIMIT) {
                newRows.add(new Row(Row.MESSAGE, y, MESSAGE_HEIGHT * 2,
                    "İlk " + MATCH_LIMIT + " eşleşme gösteriliyor. Daraltmak için daha fazla yazın.", null, null));
                y += MESSAGE_HEIGHT * 2;
            }
        }
        rows = newRows;
        updateCountLabel();
        updateScrollBar();
        list.redraw();
    }

    private void updateCountLabel() {
        if (countLabel == null || countLabel.isDisposed()) {
            return;
        }
        if (loading && index.isEmpty()) {
            countLabel.setText("yükleniyor…");
        } else if (hits.size() >= MATCH_LIMIT) {
            countLabel.setText(MATCH_LIMIT + "+ tablo");
        } else {
            countLabel.setText(hits.size() + " tablo");
        }
    }

    private void updateScrollBar() {
        if (list == null || list.isDisposed()) {
            return;
        }
        ScrollBar scrollBar = list.getVerticalBar();
        if (scrollBar == null) {
            return;
        }
        Rectangle area = list.getClientArea();
        int total = rows.isEmpty() ? area.height : rows.getLast().y + rows.getLast().height + 2;
        int maximum = Math.max(total, area.height);
        scrollBar.setMinimum(0);
        scrollBar.setMaximum(maximum);
        scrollBar.setThumb(Math.min(area.height, total));
        scrollBar.setPageIncrement(area.height);
        scrollBar.setIncrement(ITEM_HEIGHT);
        if (scrollBar.getSelection() > maximum - area.height) {
            scrollBar.setSelection(Math.max(0, maximum - area.height));
        }
    }

    private void paint(GC gc, Rectangle area) {
        Color background = display.getSystemColor(SWT.COLOR_LIST_BACKGROUND);
        Color foreground = display.getSystemColor(SWT.COLOR_LIST_FOREGROUND);
        Color muted = display.getSystemColor(SWT.COLOR_DARK_GRAY);
        Font normal = JFaceResources.getFontRegistry().get(JFaceResources.DEFAULT_FONT);
        Font bold = JFaceResources.getFontRegistry().getBold(JFaceResources.DEFAULT_FONT);

        gc.setBackground(background);
        gc.fillRectangle(area);

        ScrollBar scrollBar = list.getVerticalBar();
        int offset = scrollBar == null ? 0 : scrollBar.getSelection();
        for (Row row : rows) {
            int y = row.y - offset;
            if (y + row.height < 0) {
                continue;
            }
            if (y > area.height) {
                break;
            }
            int textY = y + (row.height - gc.getFontMetrics().getHeight()) / 2 + 1;
            switch (row.type) {
                case Row.HEADER -> {
                    gc.setForeground(foreground);
                    gc.setFont(bold);
                    gc.drawString("▾ " + row.text, 10, textY, true);
                    if (row.right != null) {
                        gc.setForeground(muted);
                        gc.setFont(normal);
                        int width = gc.textExtent(row.right).x;
                        gc.drawString(row.right, area.width - width - 12, textY, true);
                    }
                }
                case Row.ITEM -> {
                    gc.setForeground(foreground);
                    int[] matched = row.hit == null ? new int[0] : row.hit.matched();
                    drawMatched(gc, row.text, matched, 24, textY, normal, bold);
                    if (row.right != null && !row.right.isEmpty()) {
                        gc.setForeground(muted);
                        gc.setFont(normal);
                        int width = gc.textExtent(row.right).x;
                        gc.drawString(row.right, area.width - width - 12, textY, true);
                    }
                }
                default -> {
                    gc.setForeground(muted);
                    gc.setFont(normal);
                    gc.drawString(row.text, 12, textY, true);
                }
            }
        }
        gc.setFont(normal);
    }

    private static void drawMatched(GC gc, String text, int[] matched, int x, int y, Font normal, Font bold) {
        int matchIndex = 0;
        int segmentStart = 0;
        boolean segmentBold = false;
        for (int i = 0; i <= text.length(); i++) {
            boolean isBold = i < text.length() && matchIndex < matched.length && matched[matchIndex] == i;
            if (isBold) {
                matchIndex++;
            }
            if (i == text.length() || isBold != segmentBold) {
                if (i > segmentStart) {
                    gc.setFont(segmentBold ? bold : normal);
                    String part = text.substring(segmentStart, i);
                    gc.drawString(part, x, y, true);
                    x += gc.textExtent(part).x;
                }
                segmentStart = i;
                segmentBold = isBold;
            }
        }
        gc.setFont(normal);
    }

    private static List<Entry> collectEntries(DBPDataSourceContainer container) {
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            return List.of();
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        if (root == null) {
            return List.of();
        }
        List<Entry> entries = new ArrayList<>();
        Set<DBSObject> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        readChildren(root, container.getName(), entries, visited, 0);
        return entries;
    }

    private static void readChildren(
        DBSObjectContainer container,
        String connection,
        List<Entry> entries,
        Set<DBSObject> visited,
        int depth
    ) {
        if (container == null || depth > MAX_DEPTH || !visited.add(container)) {
            return;
        }
        Collection<? extends DBSObject> children;
        try {
            children = container.getChildren(new VoidProgressMonitor());
        } catch (DBException e) {
            log.debug("Nesne listesi okunamadı: " + container.getName(), e);
            return;
        }
        if (children == null) {
            return;
        }
        for (DBSObject child : children) {
            if (child instanceof DBSEntity entity) {
                String schema = "";
                DBSSchema schemaObject = DBUtils.getParentOfType(DBSSchema.class, entity);
                if (schemaObject != null) {
                    schema = schemaObject.getName();
                } else if (entity.getParentObject() != null && entity.getParentObject().getName() != null) {
                    schema = entity.getParentObject().getName();
                }
                String kind = entity instanceof DBSTable table && table.isView() ? "VIEW" : "TABLE";
                entries.add(new Entry(connection, schema, entity.getName(), kind));
            } else if (child instanceof DBSObjectContainer childContainer) {
                readChildren(childContainer, connection, entries, visited, depth + 1);
            }
        }
    }

    private final class LoadJob extends Job {

        LoadJob() {
            super("Tablo listesi");
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            List<Entry> loaded = new ArrayList<>();
            int connected = 0;
            try {
                DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
                if (project != null) {
                    for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                        if (monitor.isCanceled()) {
                            return Status.CANCEL_STATUS;
                        }
                        if (container == null || !container.isConnected()) {
                            continue;
                        }
                        connected++;
                        List<Entry> entries = cache.get(container.getId());
                        if (entries == null) {
                            try {
                                entries = collectEntries(container);
                                cache.put(container.getId(), entries);
                            } catch (Throwable e) {
                                log.debug("Tablo listesi okunamadı: " + container.getName(), e);
                                entries = List.of();
                            }
                        }
                        loaded.addAll(entries);
                    }
                }
            } catch (Throwable e) {
                log.debug("Tablo listesi yüklenemedi", e);
            }
            final List<Entry> result = loaded;
            final int count = connected;
            display.asyncExec(() -> applyLoad(result, count));
            return Status.OK_STATUS;
        }
    }

    private static final class Row {
        static final int HEADER = 0;
        static final int ITEM = 1;
        static final int MESSAGE = 2;

        final int type;
        final int y;
        final int height;
        final String text;
        final String right;
        final Hit hit;

        Row(int type, int y, int height, String text, String right, Hit hit) {
            this.type = type;
            this.y = y;
            this.height = height;
            this.text = text;
            this.right = right;
            this.hit = hit;
        }
    }
}
