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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.commands.ExecutionException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.jface.resource.JFaceResources;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.ISelectionChangedListener;
import org.eclipse.jface.viewers.ISelectionProvider;
import org.eclipse.jface.viewers.SelectionChangedEvent;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ControlAdapter;
import org.eclipse.swt.events.ControlEvent;
import org.eclipse.swt.events.KeyAdapter;
import org.eclipse.swt.events.KeyEvent;
import org.eclipse.swt.events.MenuAdapter;
import org.eclipse.swt.events.MenuEvent;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
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
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.ScrollBar;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IViewPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.handlers.HandlerUtil;
import org.eclipse.ui.handlers.IHandlerService;
import org.eclipse.ui.part.ViewPart;
import org.eclipse.ui.texteditor.ITextEditor;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvent;
import org.jkiss.dbeaver.model.DBPEventListener;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.navigator.DBNNode;
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
public class NavigationPaneView extends ViewPart implements ISelectionProvider {

    // dbeaver-mm K139: satır etkileşimleri, kısayollar ve sağ tık menüsü
    public static final String VIEW_ID = "org.jkiss.dbeaver.ui.mm.navpane";
    private static final String CMD_OBJECT_OPEN = "org.jkiss.dbeaver.core.object.open";
    private static final String CMD_SQL_EDITOR_CREATE = "org.jkiss.dbeaver.core.sql.editor.create";
    private static final String DATABASE_NAVIGATOR_VIEW_ID = "org.jkiss.dbeaver.core.databaseNavigator";

    private static final Log log = Log.getLog(NavigationPaneView.class);

    private static final int SEARCH_DELAY_MS = 100;
    private static final int MATCH_LIMIT = 500;
    private static final int MAX_DEPTH = 8;
    private static final int HEADER_HEIGHT = 24;
    private static final int ITEM_HEIGHT = 22;
    private static final int MESSAGE_HEIGHT = 24;

    private final Map<String, List<Entry>> cache = new ConcurrentHashMap<>();
    private final Map<String, DBSEntity> entityIndex = new ConcurrentHashMap<>();
    private final List<ISelectionChangedListener> selectionListeners = new ArrayList<>();
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
    private Row selectedRow;
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

        // dbeaver-mm K139: ok/Enter/Esc/Ctrl+Enter ve çift tık
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                switch (event.keyCode) {
                    case SWT.ARROW_DOWN -> {
                        moveSelection(1);
                        event.doit = false;
                    }
                    case SWT.ARROW_UP -> {
                        moveSelection(-1);
                        event.doit = false;
                    }
                    case SWT.CR, SWT.KEYPAD_CR -> {
                        if ((event.stateMask & SWT.CTRL) != 0) {
                            openNewSqlEditor();
                        } else {
                            openSelectedTable();
                        }
                        event.doit = false;
                    }
                    case SWT.ESC -> {
                        clearSearch();
                        event.doit = false;
                    }
                    default -> {
                    }
                }
            }
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDown(MouseEvent event) {
                Row row = rowAt(event.y);
                if (row != null && row.type == Row.ITEM) {
                    selectRow(row);
                }
            }

            @Override
            public void mouseDoubleClick(MouseEvent event) {
                Row row = rowAt(event.y);
                if (row != null && row.type == Row.ITEM) {
                    selectRow(row);
                    openSelectedTable();
                }
            }
        });
        createContextMenu();

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

    public void focusSearch() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setFocus();
            searchText.selectAll();
        }
    }

    @Override
    public void addSelectionChangedListener(ISelectionChangedListener listener) {
        selectionListeners.add(listener);
    }

    @Override
    public void removeSelectionChangedListener(ISelectionChangedListener listener) {
        selectionListeners.remove(listener);
    }

    @Override
    public ISelection getSelection() {
        if (selectedRow == null || selectedRow.hit == null) {
            return StructuredSelection.EMPTY;
        }
        DBNNode node = nodeFor(selectedRow.hit.entry());
        return node == null ? StructuredSelection.EMPTY : new StructuredSelection(node);
    }

    @Override
    public void setSelection(ISelection selection) {
        selectedRow = null;
        list.redraw();
    }

    private void createContextMenu() {
        Menu menu = new Menu(list);
        MenuItem selectTemplate = new MenuItem(menu, SWT.PUSH);
        selectTemplate.setText("SELECT şablonu");
        selectTemplate.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                insertSelectTemplate();
            }
        });
        MenuItem showInNavigator = new MenuItem(menu, SWT.PUSH);
        showInNavigator.setText("Show in Database Navigator");
        showInNavigator.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                showInDatabaseNavigator();
            }
        });
        menu.addMenuListener(new MenuAdapter() {
            @Override
            public void menuShown(MenuEvent event) {
                boolean enabled = selectedRow != null && selectedRow.hit != null;
                selectTemplate.setEnabled(enabled);
                showInNavigator.setEnabled(enabled);
            }
        });
        list.setMenu(menu);
    }

    private void clearSearch() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setText("");
            startSearch("");
            searchText.setFocus();
        }
    }

    private Row rowAt(int y) {
        ScrollBar scrollBar = list.getVerticalBar();
        int offset = scrollBar == null ? 0 : scrollBar.getSelection();
        int modelY = y + offset;
        for (Row row : rows) {
            if (modelY >= row.y && modelY < row.y + row.height) {
                return row;
            }
        }
        return null;
    }

    private void moveSelection(int delta) {
        int index = selectedRow == null ? -1 : rows.indexOf(selectedRow);
        int next = index + delta;
        while (next >= 0 && next < rows.size()) {
            Row row = rows.get(next);
            if (row.type == Row.ITEM) {
                selectRow(row);
                return;
            }
            next += delta;
        }
    }

    private void selectRow(Row row) {
        if (selectedRow == row) {
            return;
        }
        selectedRow = row;
        list.redraw();
        ISelection selection = getSelection();
        SelectionChangedEvent event = new SelectionChangedEvent(this, selection);
        for (ISelectionChangedListener listener : selectionListeners) {
            listener.selectionChanged(event);
        }
    }

    private void openSelectedTable() {
        if (selectedRow == null || selectedRow.hit == null) {
            return;
        }
        runCommand(CMD_OBJECT_OPEN);
    }

    private void openNewSqlEditor() {
        if (selectedRow != null && selectedRow.hit != null) {
            runCommand(CMD_SQL_EDITOR_CREATE);
        }
    }

    private void runCommand(String commandId) {
        IWorkbenchWindow window = getSite() == null ? null : getSite().getWorkbenchWindow();
        if (window == null) {
            return;
        }
        IHandlerService handlerService = window.getService(IHandlerService.class);
        if (handlerService == null) {
            return;
        }
        try {
            handlerService.executeCommand(commandId, null);
        } catch (Exception e) {
            log.debug("Komut çalıştırılamadı: " + commandId, e);
        }
    }

    private void insertSelectTemplate() {
        if (selectedRow == null || selectedRow.hit == null) {
            return;
        }
        Entry entry = selectedRow.hit.entry();
        String sql = SelectTemplate.select(entry.schema(), entry.table());
        IWorkbenchWindow window = getSite() == null ? null : getSite().getWorkbenchWindow();
        IEditorPart part = window == null || window.getActivePage() == null
            ? null : window.getActivePage().getActiveEditor();
        ITextEditor editor = part == null ? null : part.getAdapter(ITextEditor.class);
        if (editor == null) {
            return;
        }
        IDocument document = editor.getDocumentProvider().getDocument(editor.getEditorInput());
        if (document == null) {
            return;
        }
        ISelection selection = editor.getSelectionProvider().getSelection();
        int offset = selection instanceof ITextSelection textSelection
            ? textSelection.getOffset() : document.getLength();
        int length = selection instanceof ITextSelection textSelection ? textSelection.getLength() : 0;
        try {
            document.replace(offset, length, sql);
        } catch (BadLocationException e) {
            log.debug("SELECT şablonu eklenemedi", e);
        }
    }

    private void showInDatabaseNavigator() {
        if (selectedRow == null || selectedRow.hit == null) {
            return;
        }
        DBNNode node = nodeFor(selectedRow.hit.entry());
        IWorkbenchWindow window = getSite() == null ? null : getSite().getWorkbenchWindow();
        if (node == null || window == null || window.getActivePage() == null) {
            return;
        }
        try {
            IWorkbenchPage page = window.getActivePage();
            IViewPart view = page.showView(DATABASE_NAVIGATOR_VIEW_ID);
            page.activate(view);
            // dbeaver-mm K139: navigator eklentisine derleme bağımlılığı eklemeden mevcut seçme davranışı
            view.getClass().getMethod("showNode", DBNNode.class).invoke(view, node);
        } catch (Exception e) {
            log.debug("Database Navigator'da gösterilemedi", e);
        }
    }

    private DBNNode nodeFor(Entry entry) {
        DBSEntity entity = entityIndex.get(entityKey(entry.connection(), entry.schema(), entry.table()));
        if (entity == null || DBWorkbench.getPlatform().getNavigatorModel() == null) {
            return null;
        }
        return DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(entity);
    }

    private static String entityKey(String connection, String schema, String table) {
        return connection + '\u0000' + schema + '\u0000' + table;
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
            entityIndex.clear();
            selectedRow = null;
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
        Color selectionBackground = display.getSystemColor(SWT.COLOR_LIST_SELECTION);
        Color selectionForeground = display.getSystemColor(SWT.COLOR_LIST_SELECTION_TEXT);
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
            Color rowForeground = foreground;
            if (row == selectedRow && row.type == Row.ITEM) {
                gc.setBackground(selectionBackground);
                gc.fillRectangle(0, y, area.width, row.height);
                rowForeground = selectionForeground;
            }
            switch (row.type) {
                case Row.HEADER -> {
                    gc.setForeground(rowForeground);
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
                    gc.setForeground(rowForeground);
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

    private List<Entry> collectEntries(DBPDataSourceContainer container) {
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

    private void readChildren(
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
                entityIndex.putIfAbsent(entityKey(connection, schema, entity.getName()), entity);
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

    // dbeaver-mm K139: Ctrl+Alt+T görünümü açar ve aramaya odaklanır
    public static final class ShowHandler extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) throws ExecutionException {
            IWorkbenchWindow window = HandlerUtil.getActiveWorkbenchWindow(event);
            if (window == null || window.getActivePage() == null) {
                return null;
            }
            try {
                IWorkbenchPage page = window.getActivePage();
                IViewPart view = page.showView(VIEW_ID);
                page.activate(view);
                if (view instanceof NavigationPaneView navigationPane) {
                    navigationPane.focusSearch();
                }
            } catch (Exception e) {
                log.debug("Navigation Pane açılamadı", e);
            }
            return null;
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
