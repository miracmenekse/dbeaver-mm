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
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.events.TreeAdapter;
import org.eclipse.swt.events.TreeEvent;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.swt.widgets.MenuItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.eclipse.swt.widgets.Tree;
import org.eclipse.swt.widgets.TreeColumn;
import org.eclipse.swt.widgets.TreeItem;
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
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvent;
import org.jkiss.dbeaver.model.DBPEventListener;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.DBValueFormatting;
import org.jkiss.dbeaver.model.app.DBPDataSourceRegistry;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressListener;
import org.jkiss.dbeaver.model.runtime.VoidProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.rdb.DBSSchema;
import org.jkiss.dbeaver.model.struct.rdb.DBSTable;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.runtime.ui.UIServiceConnections;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.mm.workbench.navpane.TableSearch.Entry;
import org.jkiss.dbeaver.ui.mm.workbench.navpane.TableSearch.Hit;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * dbeaver-mm K138: connected connections and their tables in one flat, fuzzy-searchable
 * list. Only already connected data sources are read; the view never opens a connection.
 */
// dbeaver-mm K142: görünüm gerçek SWT ağacına çevrildi (özel Canvas çizimi kaldırıldı)
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

    private final Map<String, List<Entry>> cache = new ConcurrentHashMap<>();
    private final Map<String, DBSEntity> entityIndex = new ConcurrentHashMap<>();
    private final List<ISelectionChangedListener> selectionListeners = new ArrayList<>();
    private boolean connectionRefreshPending;
    private final Set<String> userExpanded = new LinkedHashSet<>();
    private final Set<String> connecting = new HashSet<>();
    private final Set<String> refreshing = new LinkedHashSet<>();
    private final Map<String, String> connectionErrors = new HashMap<>();
    // dbeaver-mm K144: seçili banner (tenant) süzgeci; boşken tüm bağlantılar görünür
    private final Set<String> selectedBanners = new LinkedHashSet<>();

    private Display display;
    // dbeaver-mm K161: yalnız bağlantı ekle/sil/bağlan/kop olaylarında 300 ms birleştirilmiş ağaç yenileme
    private final DBPEventListener dataSourceListener = event -> {
        if (!(event.getObject() instanceof DBPDataSourceContainer)) {
            return;
        }
        final Display current = display;
        if (current == null || current.isDisposed()) {
            return;
        }
        // olaylar UI dışı thread'den gelebilir; timerExec yalnız UI thread'inde çağrılabilir
        current.asyncExec(() -> {
            if (connectionRefreshPending || current.isDisposed()) {
                return;
            }
            connectionRefreshPending = true;
            current.timerExec(300, () -> {
                connectionRefreshPending = false;
                rebuildTree();
            });
        });
    };
    private Text searchText;
    private Label countLabel;
    private ToolBar bannerBar;
    private List<String> bannerTenants = List.of();
    private Tree tree;
    private TreeColumn nameColumn;
    private TreeColumn detailColumn;
    private DBPDataSourceRegistry registry;
    private Map<String, DBPDataSourceContainer> containersByName = Map.of();

    private LoadJob loadJob;
    private Job searchJob;
    private int searchGeneration;

    private List<Entry> index = List.of();
    private Map<String, Integer> connectionTotals = Map.of();
    private List<Hit> hits = List.of();
    private Hit selectedHit;
    private int connectedCount;
    private boolean loading;
    private boolean rebuilding;
    private String query = "";

    @Override
    public void createPartControl(Composite parent) {
        display = parent.getDisplay();

        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite bar = new Composite(root, SWT.NONE);
        GridLayout barLayout = new GridLayout(3, false);
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
                        tree.setFocus();
                    }
                }
            }
        });

        ToolBar refreshBar = new ToolBar(bar, SWT.FLAT);
        refreshBar.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));
        ToolItem refreshItem = new ToolItem(refreshBar, SWT.PUSH);
        refreshItem.setText("⟳");
        refreshItem.setToolTipText("Tabloları yenile (seçili ortamlar)");
        refreshItem.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                refreshSelectedBanners();
            }
        });

        countLabel = new Label(bar, SWT.RIGHT);
        countLabel.setLayoutData(new GridData(SWT.END, SWT.CENTER, false, false));

        // dbeaver-mm K144: arama kutusunun altında banner (tenant) şeridi
        bannerBar = new ToolBar(root, SWT.FLAT | SWT.WRAP);
        bannerBar.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        tree = new Tree(root, SWT.V_SCROLL | SWT.H_SCROLL | SWT.FULL_SELECTION);
        tree.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        tree.setHeaderVisible(false);
        tree.setLinesVisible(false);
        nameColumn = new TreeColumn(tree, SWT.LEFT);
        nameColumn.setText("Bağlantı / tablo");
        detailColumn = new TreeColumn(tree, SWT.RIGHT);
        detailColumn.setText("Sayaç / şema");
        tree.addControlListener(new ControlAdapter() {
            @Override
            public void controlResized(ControlEvent event) {
                updateColumnWidths();
            }
        });
        tree.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                TreeItem item = selectedItem();
                selectedHit = item != null && item.getData() instanceof Hit hit ? hit : null;
                fireSelection();
            }
        });
        tree.addTreeListener(new TreeAdapter() {
            @Override
            public void treeExpanded(TreeEvent event) {
                onGroupExpanded(event.item);
            }

            @Override
            public void treeCollapsed(TreeEvent event) {
                onGroupCollapsed(event.item);
            }
        });

        // dbeaver-mm K139: ok/Enter/Esc/Ctrl+Enter ve çift tık
        tree.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                switch (event.keyCode) {
                    case SWT.CR, SWT.KEYPAD_CR -> {
                        if ((event.stateMask & SWT.CTRL) != 0) {
                            openNewSqlEditor();
                        } else {
                            TreeItem item = selectedItem();
                            if (item != null && item.getData() instanceof Hit) {
                                openSelectedTable();
                            } else if (item != null && item.getData() instanceof String) {
                                item.setExpanded(!item.getExpanded());
                            }
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
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent event) {
                TreeItem item = selectedItem();
                if (item != null && item.getData() instanceof Hit) {
                    openSelectedTable();
                } else if (item != null && item.getData() instanceof String && item.getParentItem() == null) {
                    // dbeaver-mm K144: bağlantıya tıklayınca aç (kapalıysa bağlanır)
                    item.setExpanded(true);
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

        // dbeaver-mm K147: seçim sağlayıcı olarak kaydet (open komutu seçili tabloyu görsün)
        getSite().setSelectionProvider(this);
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
        if (selectedHit == null) {
            return StructuredSelection.EMPTY;
        }
        DBNNode node = nodeFor(selectedHit.entry());
        return node == null ? StructuredSelection.EMPTY : new StructuredSelection(node);
    }

    @Override
    public void setSelection(ISelection selection) {
        selectedHit = null;
        if (tree != null && !tree.isDisposed()) {
            tree.deselectAll();
        }
    }

    private void fireSelection() {
        ISelection selection = getSelection();
        SelectionChangedEvent event = new SelectionChangedEvent(this, selection);
        for (ISelectionChangedListener listener : selectionListeners) {
            listener.selectionChanged(event);
        }
    }

    private TreeItem selectedItem() {
        if (tree == null || tree.isDisposed()) {
            return null;
        }
        TreeItem[] selection = tree.getSelection();
        return selection.length == 1 ? selection[0] : null;
    }

    private void createContextMenu() {
        Menu menu = new Menu(tree);
        MenuItem refreshConnection = new MenuItem(menu, SWT.PUSH);
        refreshConnection.setText("Tabloları yenile");
        refreshConnection.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                refreshConnectionRow();
            }
        });
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
                boolean enabled = selectedHit != null;
                selectTemplate.setEnabled(enabled);
                showInNavigator.setEnabled(enabled);
                TreeItem item = selectedItem();
                refreshConnection.setEnabled(item != null && item.getData() instanceof String);
            }
        });
        tree.setMenu(menu);
    }

    private void clearSearch() {
        if (searchText != null && !searchText.isDisposed()) {
            searchText.setText("");
            startSearch("");
            searchText.setFocus();
        }
    }

    // dbeaver-mm K161: seçili ortamlardaki tüm bağlantıların tablo listesini elle yeniler
    private void refreshSelectedBanners() {
        List<DBPDataSourceContainer> targets = new ArrayList<>();
        for (DBPDataSourceContainer container : currentContainers()) {
            String tenant = new NavTreeModel.Conn(container.getName(), false).tenant();
            if (selectedBanners.contains(tenant)) {
                targets.add(container);
            }
        }
        startRefresh(targets);
    }

    // dbeaver-mm K161: bağlam menüsünden yalnız seçili bağlantıyı yeniler
    private void refreshConnectionRow() {
        TreeItem item = selectedItem();
        if (item == null || !(item.getData() instanceof String name)) {
            return;
        }
        DBPDataSourceContainer container = containersByName.get(name);
        if (container != null) {
            startRefresh(List.of(container));
        }
    }

    private void startRefresh(List<DBPDataSourceContainer> targets) {
        if (targets.isEmpty() || display == null || display.isDisposed()) {
            return;
        }
        for (DBPDataSourceContainer container : targets) {
            refreshing.add(container.getName());
        }
        rebuildTree();
        new RefreshJob(targets).schedule();
    }

    // dbeaver-mm K161: tablo açmadan önce bağlantıyı garanti eder, varlığı gerekirse yoldan çözer
    private void openSelectedTable() {
        if (selectedHit == null) {
            return;
        }
        final Entry entry = selectedHit.entry();
        final DBPDataSourceContainer container = containersByName.get(entry.connection());
        if (container == null) {
            showTableError("Tablo bulunamadı, listeyi yenileyin");
            return;
        }
        org.eclipse.core.runtime.jobs.Job job = new org.eclipse.core.runtime.jobs.Job("Tablo aç: " + entry.table()) {
            @Override
            protected org.eclipse.core.runtime.IStatus run(org.eclipse.core.runtime.IProgressMonitor monitor) {
                try {
                    if (!container.isConnected()) {
                        container.connect(RuntimeUtils.makeMonitor(monitor), true, true);
                    }
                    DBSEntity entity = resolveEntity(monitor, entry, container);
                    if (entity == null) {
                        showTableError("Tablo bulunamadı, listeyi yenileyin");
                        return org.eclipse.core.runtime.Status.OK_STATUS;
                    }
                    if (DBWorkbench.getPlatform().getNavigatorModel() != null) {
                        DBWorkbench.getPlatform().getNavigatorModel().getNodeByObject(
                            RuntimeUtils.makeMonitor(monitor), entity, true);
                    }
                } catch (Throwable e) {
                    log.debug("Tablo açılamadı: " + entry.table(), e);
                    showTableError(e.getMessage() == null ? "Tablo açılamadı" : e.getMessage());
                    return org.eclipse.core.runtime.Status.OK_STATUS;
                }
                Display.getDefault().asyncExec(() -> {
                    if (tree != null && !tree.isDisposed() && nodeFor(entry) != null) {
                        fireSelection();
                        runCommand(CMD_OBJECT_OPEN);
                    } else {
                        showTableError("Tablo bulunamadı, listeyi yenileyin");
                    }
                });
                return org.eclipse.core.runtime.Status.OK_STATUS;
            }
        };
        job.schedule();
    }

    private DBSEntity resolveEntity(IProgressMonitor monitor, Entry entry, DBPDataSourceContainer container) {
        DBSEntity entity = entityFor(entry);
        if (entity != null) {
            return entity;
        }
        DBPDataSource dataSource = container.getDataSource();
        if (dataSource == null) {
            return null;
        }
        DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, dataSource);
        if (root == null) {
            return null;
        }
        String schema = entry.schema() == null || entry.schema().isEmpty() ? null : entry.schema();
        try {
            DBCExecutionContext context = DBUtils.getDefaultContext(dataSource, false);
            if (context == null) {
                return null;
            }
            DBSObject found = DBUtils.getObjectByPath(
                RuntimeUtils.makeMonitor(monitor), context, root, null, schema, entry.table());
            if (found instanceof DBSEntity foundEntity) {
                entityIndex.putIfAbsent(entityKey(entry.connection(), entry.schema(), entry.table()), foundEntity);
                return foundEntity;
            }
        } catch (DBException e) {
            log.debug("Tablo yolu çözülemedi: " + entry.table(), e);
        }
        return null;
    }

    private DBSEntity entityFor(Entry entry) {
        return entityIndex.get(entityKey(entry.connection(), entry.schema(), entry.table()));
    }

    private void showTableError(String message) {
        if (display == null || display.isDisposed()) {
            return;
        }
        display.asyncExec(() -> DBWorkbench.getPlatformUI().showError("Navigation Pane", message));
    }

    private void openNewSqlEditor() {
        if (selectedHit != null) {
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
        if (selectedHit == null) {
            return;
        }
        Entry entry = selectedHit.entry();
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
        if (selectedHit == null) {
            return;
        }
        DBNNode node = nodeFor(selectedHit.entry());
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
        DBSEntity entity = entityFor(entry);
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
            if (tree == null || tree.isDisposed()) {
                return;
            }
            loading = true;
            hits = List.of();
            if (loadJob != null) {
                loadJob.cancel();
            }
            loadJob = new LoadJob();
            loadJob.schedule();
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
        if (display == null || display.isDisposed() || tree == null || tree.isDisposed()) {
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
                    if (generation != searchGeneration || tree == null || tree.isDisposed()) {
                        return;
                    }
                    hits = result;
                    rebuildTree();
                });
                return Status.OK_STATUS;
            }
        };
        searchJob.setSystem(true);
        searchJob.schedule();
    }

    private void applyLoad(List<Entry> entries, int connected) {
        if (tree == null || tree.isDisposed()) {
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
        hits = List.of();
        rebuildTree();
        startSearch(searchText == null || searchText.isDisposed() ? "" : searchText.getText());
    }

    private void rebuildTree() {
        if (tree == null || tree.isDisposed()) {
            return;
        }
        rebuilding = true;
        selectedHit = null;
        tree.setRedraw(false);
        try {
            tree.removeAll();
            List<DBPDataSourceContainer> containers = currentContainers();
            updateBannerBar(containers);
            // dbeaver-mm K145: banner seçilmeden liste boş kalır, arama da etkisizdir
            if (selectedBanners.isEmpty()) {
                String tenantList = String.join(", ", bannerTenants);
                addTreeMessage(tenantList.isEmpty()
                    ? "En az 1 ortam seçiniz"
                    : "En az 1 ortam (" + tenantList + ") seçiniz");
                return;
            }
            List<NavTreeModel.Conn> conns = new ArrayList<>(containers.size());
            Map<String, DBPDataSourceContainer> byName = new LinkedHashMap<>();
            for (DBPDataSourceContainer container : containers) {
                String name = container.getName();
                boolean hasTables = container.isConnected() || cache.containsKey(container.getId());
                conns.add(new NavTreeModel.Conn(name, hasTables));
                byName.putIfAbsent(name, container);
            }
            containersByName = byName;
            List<NavTreeModel.Group> groups = NavTreeModel.build(conns, hits, query, connectionTotals, selectedBanners);
            for (NavTreeModel.Group group : groups) {
                buildGroupItem(group, byName.get(group.conn().name()));
            }
        } finally {
            tree.setRedraw(true);
            rebuilding = false;
        }
        updateCountLabel();
        updateColumnWidths();
    }

    private List<DBPDataSourceContainer> currentContainers() {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        if (registry == null) {
            return result;
        }
        for (DBPDataSourceContainer container : registry.getDataSources()) {
            if (container != null) {
                result.add(container);
            }
        }
        return result;
    }

    private void buildGroupItem(NavTreeModel.Group group, DBPDataSourceContainer container) {
        String name = group.conn().name();
        TreeItem item = new TreeItem(tree, SWT.NONE);
        boolean connected = container != null && container.isConnected();
        boolean hasList = container != null && cache.containsKey(container.getId());
        applyConnectionStatus(item, name, connected);
        item.setData(name);
        if (container != null) {
            item.setImage(connectionIcon(container));
        }
        String detail = query.isEmpty()
            ? String.valueOf(group.total())
            : group.hits().size() + "/" + group.total();
        item.setText(1, detail);

        String error = connectionErrors.get(name);
        if (refreshing.contains(name) && !hasList) {
            addMessageItem(item, "Tablolar yükleniyor…");
        } else if (!connected && !hasList) {
            if (connecting.contains(name)) {
                addMessageItem(item, "bağlanıyor…");
            } else if (error != null && !error.isEmpty()) {
                addMessageItem(item, "bağlanamadı: " + error);
            } else {
                addMessageItem(item, "Bağlanmak için açın");
            }
        } else if (loading && index.isEmpty()) {
            addMessageItem(item, "Tablolar yükleniyor…");
        } else {
            for (Hit hit : group.hits()) {
                buildTableItem(item, hit);
            }
        }

        boolean expanded = query.isEmpty() ? userExpanded.contains(name) : group.expanded();
        item.setExpanded(expanded);
    }

    private void buildTableItem(TreeItem parent, Hit hit) {
        TreeItem item = new TreeItem(parent, SWT.NONE);
        item.setText(0, hit.entry().table());
        String schema = hit.entry().schema();
        if (schema != null && !schema.isEmpty()) {
            item.setText(1, schema);
            item.setForeground(1, display.getSystemColor(SWT.COLOR_DARK_GRAY));
        }
        item.setImage(tableIcon(hit));
        item.setData(hit);
    }

    private void addMessageItem(TreeItem parent, String text) {
        TreeItem item = new TreeItem(parent, SWT.NONE);
        item.setText(0, text);
        item.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
    }

    // dbeaver-mm K145: ağaç kökünde tıklanamaz bilgi satırı
    private void addTreeMessage(String text) {
        TreeItem item = new TreeItem(tree, SWT.NONE);
        item.setText(0, text);
        item.setForeground(display.getSystemColor(SWT.COLOR_DARK_GRAY));
    }

    // dbeaver-mm K145: bağlantı durum simgesi (yeşil bağlı, sarı bağlanıyor, gri kapalı)
    private void applyConnectionStatus(TreeItem item, String name, boolean connected) {
        if (connected) {
            item.setText(0, "● " + name);
            item.setForeground(0, display.getSystemColor(SWT.COLOR_GREEN));
        } else if (connecting.contains(name)) {
            item.setText(0, "● " + name);
            item.setForeground(0, display.getSystemColor(SWT.COLOR_YELLOW));
        } else {
            item.setText(0, "○ " + name);
            item.setForeground(0, display.getSystemColor(SWT.COLOR_DARK_GRAY));
        }
    }

    private Image connectionIcon(DBPDataSourceContainer container) {
        return DBeaverIcons.getImage(container.getDriver().getIcon());
    }

    private Image tableIcon(Hit hit) {
        Entry entry = hit.entry();
        DBSEntity entity = entityIndex.get(entityKey(entry.connection(), entry.schema(), entry.table()));
        if (entity != null) {
            return DBeaverIcons.getImage(DBValueFormatting.getObjectImage(entity));
        }
        return DBeaverIcons.getImage("VIEW".equals(entry.kind()) ? DBIcon.TREE_VIEW : DBIcon.TREE_TABLE);
    }

    private void onGroupExpanded(Object widget) {
        if (rebuilding || !(widget instanceof TreeItem item) || item.getParentItem() != null) {
            return;
        }
        if (!(item.getData() instanceof String name) || !query.isEmpty()) {
            return;
        }
        userExpanded.add(name);
        DBPDataSourceContainer container = containersByName.get(name);
        if (container == null || cache.containsKey(container.getId())) {
            return;
        }
        refreshing.add(name);
        rebuildTree();
        new RefreshJob(List.of(container)).schedule();
    }

    private void onGroupCollapsed(Object widget) {
        if (rebuilding || !(widget instanceof TreeItem item) || item.getParentItem() != null) {
            return;
        }
        if (item.getData() instanceof String name && query.isEmpty()) {
            userExpanded.remove(name);
        }
    }

    // dbeaver-mm K144: banner düğmeleri tenant listesinden üretilir, seçim korunur
    private void updateBannerBar(List<DBPDataSourceContainer> containers) {
        if (bannerBar == null || bannerBar.isDisposed()) {
            return;
        }
        Set<String> tenants = new LinkedHashSet<>();
        for (DBPDataSourceContainer container : containers) {
            String tenant = new NavTreeModel.Conn(container.getName(), false).tenant();
            if (!tenant.isEmpty()) {
                tenants.add(tenant);
            }
        }
        List<String> list = new ArrayList<>(tenants);
        selectedBanners.retainAll(tenants);
        if (list.equals(bannerTenants)) {
            return;
        }
        bannerTenants = list;
        bannerBar.setRedraw(false);
        for (ToolItem item : bannerBar.getItems()) {
            item.dispose();
        }
        for (String tenant : list) {
            ToolItem item = new ToolItem(bannerBar, SWT.CHECK);
            item.setText(tenant);
            item.setData(tenant);
            item.setSelection(selectedBanners.contains(tenant));
            item.addSelectionListener(new SelectionAdapter() {
                @Override
                public void widgetSelected(SelectionEvent event) {
                    onBannerToggled((ToolItem) event.widget);
                }
            });
        }
        bannerBar.setRedraw(true);
        bannerBar.getParent().layout();
    }

    // dbeaver-mm K161: banner seçimi yalnız süzer, bağlantı açmaz
    private void onBannerToggled(ToolItem item) {
        if (item == null || item.isDisposed() || !(item.getData() instanceof String tenant)) {
            return;
        }
        if (item.getSelection()) {
            selectedBanners.add(tenant);
        } else {
            selectedBanners.remove(tenant);
        }
        rebuildTree();
    }

    private void connectContainer(String name, DBPDataSourceContainer container) {
        connecting.add(name);
        connectionErrors.remove(name);
        display.asyncExec(this::rebuildTree);

        DBRProgressListener onFinish = status -> display.asyncExec(() -> {
            connecting.remove(name);
            boolean ok = status != null && status.isOK() && container.isConnected();
            if (!ok) {
                String message = status == null ? null : status.getMessage();
                connectionErrors.put(name, message == null || message.isEmpty() ? "bağlantı kurulamadı" : message);
            }
            boolean needsRefresh = ok && !cache.containsKey(container.getId());
            if (needsRefresh) {
                refreshing.add(name);
            }
            rebuildTree();
            if (needsRefresh) {
                new RefreshJob(List.of(container)).schedule();
            }
        });

        UIServiceConnections connections = DBWorkbench.getService(UIServiceConnections.class);
        if (connections != null) {
            try {
                connections.connectDataSource(container, onFinish);
            } catch (Throwable e) {
                connecting.remove(name);
                connectionErrors.put(name, e.getMessage() == null ? "bağlantı kurulamadı" : e.getMessage());
                display.asyncExec(this::rebuildTree);
            }
        } else {
            new Job("Bağlantı: " + name) {
                @Override
                protected IStatus run(IProgressMonitor monitor) {
                    try {
                        container.connect(RuntimeUtils.makeMonitor(monitor), true, true);
                        onFinish.onTaskFinished(Status.OK_STATUS);
                        return Status.OK_STATUS;
                    } catch (DBException e) {
                        Status status = new Status(IStatus.ERROR, VIEW_ID, e.getMessage(), e);
                        onFinish.onTaskFinished(status);
                        return status;
                    }
                }
            }.schedule();
        }
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

    private void updateColumnWidths() {
        if (tree == null || tree.isDisposed() || nameColumn == null || detailColumn == null) {
            return;
        }
        int width = tree.getClientArea().width;
        int detail = Math.max(80, Math.min(140, width / 3));
        detailColumn.setWidth(detail);
        nameColumn.setWidth(Math.max(100, width - detail));
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
            NavTableCache disk = NavTableCache.defaultCache();
            List<Entry> loaded = new ArrayList<>();
            int connected = 0;
            try {
                DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
                if (project != null) {
                    for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                        if (monitor.isCanceled()) {
                            return Status.CANCEL_STATUS;
                        }
                        if (container == null) {
                            continue;
                        }
                        if (container.isConnected()) {
                            connected++;
                        }
                        String id = container.getId();
                        List<Entry> entries = disk.load(id, container.getName());
                        if (entries != null) {
                            cache.put(id, entries);
                            loaded.addAll(entries);
                        }
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

    private final class RefreshJob extends Job {

        private final List<DBPDataSourceContainer> targets;

        RefreshJob(List<DBPDataSourceContainer> targets) {
            super("Tabloları yenile");
            this.targets = targets;
            setSystem(true);
        }

        @Override
        protected IStatus run(IProgressMonitor monitor) {
            NavTableCache disk = NavTableCache.defaultCache();
            Map<String, String> errors = new HashMap<>();
            for (DBPDataSourceContainer container : targets) {
                if (monitor.isCanceled()) {
                    return Status.CANCEL_STATUS;
                }
                String name = container.getName();
                try {
                    if (!container.isConnected()) {
                        container.connect(RuntimeUtils.makeMonitor(monitor), true, true);
                    }
                    List<Entry> entries = collectEntries(container);
                    disk.save(container.getId(), entries);
                    cache.put(container.getId(), entries);
                } catch (Throwable e) {
                    String message = e.getMessage();
                    errors.put(name, message == null || message.isEmpty() ? "yenilenemedi" : message);
                    log.debug("Tablo listesi yenilenemedi: " + name, e);
                }
            }
            List<Entry> result = new ArrayList<>();
            int connected = 0;
            try {
                DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
                if (project != null) {
                    for (DBPDataSourceContainer container : project.getDataSourceRegistry().getDataSources()) {
                        if (container == null) {
                            continue;
                        }
                        if (container.isConnected()) {
                            connected++;
                        }
                        List<Entry> entries = cache.get(container.getId());
                        if (entries != null) {
                            result.addAll(entries);
                        }
                    }
                }
            } catch (Throwable e) {
                log.debug("Tablo listesi derlenemedi", e);
            }
            final List<Entry> loaded = result;
            final int count = connected;
            final Map<String, String> errorResult = errors;
            display.asyncExec(() -> {
                for (DBPDataSourceContainer container : targets) {
                    String name = container.getName();
                    refreshing.remove(name);
                    if (!errorResult.containsKey(name)) {
                        connectionErrors.remove(name);
                    }
                }
                connectionErrors.putAll(errorResult);
                applyLoad(loaded, count);
            });
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
}
