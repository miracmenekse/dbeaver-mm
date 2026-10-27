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
package org.jkiss.dbeaver.ui.data.hints;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPDataSource;
import org.jkiss.dbeaver.model.DBPDataSourceContainer;
import org.jkiss.dbeaver.model.DBPEvaluationContext;
import org.jkiss.dbeaver.model.DBUtils;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCExecutionPurpose;
import org.jkiss.dbeaver.model.exec.DBCResultSet;
import org.jkiss.dbeaver.model.exec.DBCSession;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.exec.DBCStatementType;
import org.jkiss.dbeaver.model.runtime.AbstractJob;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.DBSObject;
import org.jkiss.dbeaver.model.struct.DBSObjectContainer;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.ui.UIUtils;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * dbeaver-mm K73: Plus Settings &gt; data_tp mapping... For the selected tenant the user says which
 * connection and schema hold {@code etiya_type_value}, then "Build mapping" finds every target table
 * of every {@code data_tp} id in the tenant's connected connections. Rows with one place or a picked
 * ambiguous place are saved to the tenant's own mapping file.
 * <p>
 * All queries, connection opens and file reads/writes run in background jobs; the UI thread only
 * builds widgets and applies job results with {@link UIUtils#asyncExec}.
 */
public class DataTpMapDialog extends Dialog {

    private static final Log log = Log.getLog(DataTpMapDialog.class);

    private static final DateTimeFormatter BUILT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");

    /** dbeaver-mm K73: one candidate location of a target table */
    private record Place(String connectionId, String connectionName, String schema, String table) {
    }

    /** dbeaver-mm K73: a data_tp row of the mapping table; touched on the UI thread only */
    private static final class Row {
        final String dataTp;
        final String table;
        List<Place> candidates = List.of();
        Place chosen;

        Row(String dataTp, String table, Place chosen) {
            this.dataTp = dataTp;
            this.table = table;
            this.chosen = chosen;
        }
    }

    /** dbeaver-mm K73: background build result, applied on the UI thread */
    private record Built(List<Row> rows, List<Place> allPlaces, String message) { // dbeaver-mm K79: allPlaces
    }

    /** dbeaver-mm K73: background read of a tenant file */
    private record Loaded(DataTpStore.TenantMap map) {
    }

    private final List<DBPDataSourceContainer> allConnections = new ArrayList<>();

    private Combo tenantCombo;
    private Combo typeConnectionCombo;
    private Combo typeSchemaCombo;
    private Button buildButton;
    private Label builtLabel;
    private Label messageLabel;
    private ScrolledComposite rowsScroll;
    private Composite rowsArea;

    private List<DBPDataSourceContainer> tenantConnections = List.of();
    private DataTpStore.TenantMap map = new DataTpStore.TenantMap("", "", Map.of(), "");
    private List<Row> rows = new ArrayList<>();
    /** dbeaver-mm K79: every table found by the last build (one per database/schema/table) */
    private List<Place> allPlaces = List.of();
    private String tenant = "";
    private int loadGeneration;
    private boolean building;
    /** dbeaver-mm K81: the running build, stopped by the Stop button or by closing the dialog */
    private AbstractJob buildJob;

    public DataTpMapDialog(@NotNull Shell shell) {
        super(shell);
        DBPProject project = DBWorkbench.getPlatform().getWorkspace().getActiveProject();
        if (project != null) {
            allConnections.addAll(project.getDataSourceRegistry().getDataSources());
        }
    }

    /** dbeaver-mm K73: Plus Settings menu entry */
    public static class Handler extends AbstractHandler {
        @Override
        public Object execute(ExecutionEvent event) {
            new DataTpMapDialog(HandlerUtil.getActiveShell(event)).open();
            return null;
        }
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("data_tp mapping");
    }

    @Override
    protected boolean isResizable() {
        return true;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));
        GridData areaData = new GridData(SWT.FILL, SWT.FILL, true, true);
        areaData.widthHint = 720;
        areaData.heightHint = 440;
        area.setLayoutData(areaData);

        new Label(area, SWT.NONE).setText("Tenant:");
        tenantCombo = new Combo(area, SWT.DROP_DOWN | SWT.READ_ONLY);
        tenantCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite typeRow = new Composite(area, SWT.NONE);
        typeRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        typeRow.setLayout(new GridLayout(4, false));
        new Label(typeRow, SWT.NONE).setText(DataTpStore.TYPE_TABLE + ":");
        typeConnectionCombo = new Combo(typeRow, SWT.DROP_DOWN | SWT.READ_ONLY);
        GridData connectionData = new GridData(SWT.FILL, SWT.CENTER, true, false);
        connectionData.widthHint = 220;
        typeConnectionCombo.setLayoutData(connectionData);
        typeSchemaCombo = new Combo(typeRow, SWT.DROP_DOWN);
        typeSchemaCombo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

        Composite buildRow = new Composite(area, SWT.NONE);
        buildRow.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        buildRow.setLayout(new GridLayout(2, false));
        buildButton = new Button(buildRow, SWT.PUSH);
        buildButton.setText("Build mapping");
        buildButton.setEnabled(false);
        builtLabel = new Label(buildRow, SWT.NONE);
        builtLabel.setText("last built: -");

        messageLabel = new Label(area, SWT.WRAP);
        messageLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));

        rowsScroll = new ScrolledComposite(area, SWT.V_SCROLL | SWT.BORDER);
        GridData scrollData = new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1);
        scrollData.heightHint = 280;
        rowsScroll.setLayoutData(scrollData);
        rowsScroll.setExpandHorizontal(true);
        rowsScroll.setExpandVertical(true);
        rowsArea = new Composite(rowsScroll, SWT.NONE);
        rowsArea.setLayout(new GridLayout(1, false));
        rowsScroll.setContent(rowsArea);

        tenantCombo.addListener(SWT.Selection, e -> {
            int index = tenantCombo.getSelectionIndex();
            if (index >= 0) {
                refreshTenant(tenantCombo.getItem(index));
            }
        });
        typeConnectionCombo.addListener(SWT.Selection, e -> loadSchemas());
        buildButton.addListener(SWT.Selection, e -> build());

        TreeSet<String> tenants = new TreeSet<>();
        for (DBPDataSourceContainer container : allConnections) {
            String id = DataTpStore.tenantOf(container.getName());
            if (!id.isEmpty()) {
                tenants.add(id);
            }
        }
        tenantCombo.setItems(tenants.toArray(new String[0]));
        if (!tenants.isEmpty()) {
            tenantCombo.select(0);
            refreshTenant(tenantCombo.getItem(0));
        } else {
            messageLabel.setText("No connections in the active project.");
        }
        return area;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
        createButton(parent, IDialogConstants.OK_ID, "Save", true);
    }

    private List<DBPDataSourceContainer> connectionsOf(String tenantId) {
        List<DBPDataSourceContainer> result = new ArrayList<>();
        for (DBPDataSourceContainer container : allConnections) {
            if (DataTpStore.tenantOf(container.getName()).equals(tenantId)) {
                result.add(container);
            }
        }
        return result;
    }

    /** dbeaver-mm K73: shows a tenant: its file is read in the background, then the widgets are filled */
    private void refreshTenant(@NotNull String tenantId) {
        tenant = tenantId;
        tenantConnections = connectionsOf(tenantId);
        int generation = ++loadGeneration;
        Path file = DataTpStore.tenantFile(DataTpStore.tenantDir(), tenantId);
        messageLabel.setText("Loading " + tenantId + "...");
        new AbstractJob("Read data_tp mapping of " + tenantId) {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                Loaded loaded = new Loaded(DataTpStore.loadTenant(file));
                UIUtils.asyncExec(() -> {
                    if (generation != loadGeneration || tenantCombo == null || tenantCombo.isDisposed()) {
                        return;
                    }
                    applyTenant(loaded.map());
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    private void applyTenant(@NotNull DataTpStore.TenantMap loaded) {
        map = loaded;
        typeConnectionCombo.removeAll();
        for (DBPDataSourceContainer container : tenantConnections) {
            typeConnectionCombo.add(container.getName());
        }
        int selected = -1;
        for (int i = 0; i < tenantConnections.size(); i++) {
            if (tenantConnections.get(i).getId().equals(loaded.typeConnectionId())) {
                selected = i;
            }
        }
        if (selected >= 0) {
            typeConnectionCombo.select(selected);
        } else if (!tenantConnections.isEmpty()) {
            typeConnectionCombo.select(0);
        }
        typeSchemaCombo.removeAll();
        typeSchemaCombo.setText(loaded.typeSchema() == null ? "" : loaded.typeSchema());
        builtLabel.setText("last built: " + (loaded.built().isEmpty() ? "-" : loaded.built()));

        rows = new ArrayList<>();
        allPlaces = List.of(); // dbeaver-mm K79
        for (Map.Entry<String, DataTpStore.Entry> entry : loaded.types().entrySet()) {
            DataTpStore.Entry value = entry.getValue();
            DBPDataSourceContainer container = containerById(value.connectionId());
            Place place = new Place(value.connectionId(),
                container == null ? value.connectionId() : container.getName(),
                value.schema() == null ? "" : value.schema(), value.table());
            rows.add(new Row(entry.getKey(), value.table(), place));
        }
        renderRows();
        messageLabel.setText(rows.isEmpty()
            ? "Pick the connection and schema of " + DataTpStore.TYPE_TABLE + ", then Build mapping."
            : rows.size() + " saved types. Build mapping refreshes them.");
        buildButton.setEnabled(!tenantConnections.isEmpty());
        loadSchemas();
    }

    @Nullable
    private DBPDataSourceContainer containerById(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (DBPDataSourceContainer container : allConnections) {
            if (id.equals(container.getId())) {
                return container;
            }
        }
        return null;
    }

    /** dbeaver-mm K73: schema names of the type connection, read in the background when it is connected */
    private void loadSchemas() {
        if (typeConnectionCombo == null || typeConnectionCombo.isDisposed()) {
            return;
        }
        int index = typeConnectionCombo.getSelectionIndex();
        DBPDataSourceContainer container = index < 0 || index >= tenantConnections.size() ? null : tenantConnections.get(index);
        String current = typeSchemaCombo.getText();
        if (container == null || !container.isConnected() || container.getDataSource() == null) {
            typeSchemaCombo.setItems(new String[0]);
            typeSchemaCombo.setText(current);
            return;
        }
        new AbstractJob("Read schemas of " + container.getName()) {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                List<String> names = new ArrayList<>();
                try {
                    DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
                    Collection<? extends DBSObject> children = root == null ? null : root.getChildren(monitor);
                    if (children != null) {
                        for (DBSObject child : children) {
                            if (child instanceof DBSObjectContainer && !(child instanceof DBSEntity)) {
                                names.add(child.getName());
                            }
                        }
                    }
                } catch (Exception e) {
                    log.debug("Can't read schemas of " + container.getName(), e);
                }
                UIUtils.asyncExec(() -> {
                    if (typeSchemaCombo == null || typeSchemaCombo.isDisposed()
                        || typeConnectionCombo.getSelectionIndex() != index) {
                        return;
                    }
                    typeSchemaCombo.setItems(names.toArray(new String[0]));
                    typeSchemaCombo.setText(current);
                });
                return Status.OK_STATUS;
            }
        }.schedule();
    }

    /** dbeaver-mm K73: Build mapping in a background job; the type connection is opened if needed */
    private void build() {
        if (building) {
            if (buildJob != null) {
                buildJob.cancel(); // dbeaver-mm K81
                messageLabel.setText("Stopping...");
            }
            return;
        }
        int index = typeConnectionCombo.getSelectionIndex();
        if (building || index < 0 || index >= tenantConnections.size()) {
            messageLabel.setText("Pick the connection that holds " + DataTpStore.TYPE_TABLE + ".");
            return;
        }
        final DBPDataSourceContainer typeConnection = tenantConnections.get(index);
        final String typeSchema = typeSchemaCombo.getText().strip();
        final List<DBPDataSourceContainer> connections = List.copyOf(tenantConnections);
        final Map<String, DataTpStore.Entry> saved = new HashMap<>(map.types());
        final int generation = loadGeneration;
        building = true;
        buildButton.setText("Stop");
        messageLabel.setText("Building mapping...");
        buildJob = new AbstractJob("Build data_tp mapping") {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                // dbeaver-mm K81: show which step and connection the build is on
                Consumer<String> progress = text -> UIUtils.asyncExec(() -> {
                    if (generation == loadGeneration && building && messageLabel != null && !messageLabel.isDisposed()) {
                        messageLabel.setText(text);
                    }
                });
                Built result = doBuild(monitor, typeConnection, typeSchema, connections, saved, progress);
                UIUtils.asyncExec(() -> {
                    if (buildButton == null || buildButton.isDisposed()) {
                        return;
                    }
                    building = false;
                    buildJob = null;
                    buildButton.setText("Build mapping");
                    buildButton.setEnabled(!tenantConnections.isEmpty());
                    if (generation != loadGeneration) {
                        return; // dbeaver-mm K81: tenant changed meanwhile; drop the result, keep the button usable
                    }
                    if (result.rows() != null) {
                        rows = result.rows();
                        allPlaces = result.allPlaces();
                        renderRows();
                    }
                    messageLabel.setText(result.message());
                });
                return Status.OK_STATUS;
            }
        };
        buildJob.schedule();
    }

    @Override
    public boolean close() {
        if (buildJob != null) {
            buildJob.cancel(); // dbeaver-mm K81: don't keep opening connections after the dialog is gone
        }
        return super.close();
    }

    @NotNull
    private Built doBuild(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer typeConnection,
        @NotNull String typeSchema,
        @NotNull List<DBPDataSourceContainer> connections,
        @NotNull Map<String, DataTpStore.Entry> saved,
        @NotNull Consumer<String> progress
    ) {
        try {
            if (!typeConnection.isConnected()) {
                progress.accept("Opening " + typeConnection.getName() + "...");
                typeConnection.connect(monitor, true, true);
            }
            if (!typeConnection.isConnected() || typeConnection.getDataSource() == null) {
                return new Built(null, null, "Connection " + typeConnection.getName() + " is not connected.");
            }
            DBSObjectContainer root = DBUtils.getAdapter(DBSObjectContainer.class, typeConnection.getDataSource());
            DBSEntity typeTable = root == null ? null : findTypeTable(monitor, root, typeSchema);
            if (typeTable == null) {
                return new Built(null, null, "Can't find " + DataTpStore.TYPE_TABLE + " in "
                    + (typeSchema.isEmpty() ? typeConnection.getName() : typeConnection.getName() + " / " + typeSchema) + ".");
            }
            progress.accept("Reading " + DataTpStore.TYPE_TABLE + "...");
            Map<String, String> types = readTypes(monitor, typeTable);
            // dbeaver-mm K79: open closed tenant connections; a connection that stays closed is reported
            List<String> failed = new ArrayList<>();
            List<DBPDataSourceContainer> open = new ArrayList<>();
            for (int i = 0; i < connections.size(); i++) {
                DBPDataSourceContainer container = connections.get(i);
                if (monitor.isCanceled()) {
                    return new Built(null, null, "Build canceled.");
                }
                try {
                    if (!container.isConnected()) {
                        progress.accept("Opening " + container.getName() + " (" + (i + 1) + "/" + connections.size() + ")...");
                        container.connect(monitor, true, true);
                    }
                } catch (Exception e) {
                    log.debug("Can't connect to " + container.getName(), e);
                }
                if (container.isConnected() && container.getDataSource() != null) {
                    open.add(container);
                } else {
                    failed.add(container.getName());
                }
            }
            // dbeaver-mm K79: walk every connection once; the same database/schema/table counts once
            Map<String, Place> unique = new LinkedHashMap<>();
            int searched = 0;
            for (DBPDataSourceContainer container : open) {
                if (monitor.isCanceled()) {
                    return new Built(null, null, "Build canceled.");
                }
                DBSObjectContainer containerRoot = DBUtils.getAdapter(DBSObjectContainer.class, container.getDataSource());
                if (containerRoot == null) {
                    failed.add(container.getName()); // dbeaver-mm K79: no silent skip
                    continue;
                }
                searched++;
                progress.accept("Reading tables of " + container.getName() + " (" + searched + "/" + open.size() + ")...");
                List<Place> found = new ArrayList<>();
                // dbeaver-mm K82: one catalog query per connection; walking every schema over SSH took hours
                if (!queryAllPlaces(monitor, container, found)) {
                    collectAllPlaces(monitor, containerRoot, 2, container, found);
                }
                String database = databaseIdentity(container);
                for (Place place : found) {
                    unique.putIfAbsent(DataTpMatch.placeKey(database, place.schema(), place.table()), place);
                }
            }
            if (monitor.isCanceled()) {
                return new Built(null, null, "Build canceled.");
            }
            List<Place> allPlaces = List.copyOf(unique.values());
            Map<String, List<Place>> byTable = new HashMap<>();
            for (Place place : allPlaces) {
                byTable.computeIfAbsent(place.table().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(place);
            }
            List<Row> built = new ArrayList<>();
            for (Map.Entry<String, String> type : types.entrySet()) {
                String table = type.getValue();
                List<Place> candidates = table == null
                    ? List.of() : byTable.getOrDefault(table.toLowerCase(Locale.ROOT), List.of());
                Row row = new Row(type.getKey(), table, null);
                row.candidates = candidates;
                if (candidates.size() == 1) {
                    row.chosen = candidates.get(0);
                } else if (candidates.size() > 1) {
                    DataTpStore.Entry previous = saved.get(type.getKey());
                    row.chosen = previous == null ? null : findPlace(candidates, previous);
                } else {
                    // dbeaver-mm K80: previous pick that still exists in allPlaces stays selected
                    DataTpStore.Entry previous = saved.get(type.getKey());
                    row.chosen = previous == null ? null : findPlace(allPlaces, previous);
                }
                built.add(row);
            }
            long mapped = built.stream().filter(r -> r.chosen != null).count();
            String message = "Built " + built.size() + " data_tp ids, " + mapped
                + " mapped in 1 place or by an earlier choice. Searched " + searched + " connections.";
            if (!failed.isEmpty()) {
                message += " Couldn't open: " + String.join(", ", failed) + ".";
            }
            return new Built(built, allPlaces, message);
        } catch (DBException e) {
            log.debug("Can't build data_tp mapping", e);
            return new Built(null, null, "Can't connect to " + typeConnection.getName() + ": " + e.getMessage());
        } catch (Throwable e) {
            log.debug("Can't build data_tp mapping", e);
            return new Built(null, null, "Build failed: " + e.getMessage());
        }
    }

    @Nullable
    private static Place findPlace(@NotNull List<Place> candidates, @NotNull DataTpStore.Entry entry) {
        for (Place place : candidates) {
            if (place.connectionId().equals(entry.connectionId())
                && place.schema().equalsIgnoreCase(entry.schema() == null ? "" : entry.schema())
                && place.table().equalsIgnoreCase(entry.table())) {
                return place;
            }
        }
        return null;
    }

    /** dbeaver-mm K82: every table of the connection from information_schema; false when the query can't run */
    private static boolean queryAllPlaces(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBPDataSourceContainer connection,
        @NotNull List<Place> found
    ) {
        DBCExecutionContext context = DBUtils.getDefaultContext(connection.getDataSource(), true);
        if (context == null) {
            return false;
        }
        String query = "SELECT table_schema, table_name FROM information_schema.tables"
            + " WHERE table_schema NOT IN ('pg_catalog', 'information_schema')";
        List<Place> rows = new ArrayList<>();
        try (DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read table names");
             DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, query, false, false, false)) {
            if (!statement.executeStatement()) {
                return false;
            }
            try (DBCResultSet resultSet = statement.openResultSet()) {
                while (resultSet != null && !monitor.isCanceled() && resultSet.nextRow()) {
                    Object schema = resultSet.getAttributeValue(0);
                    Object table = resultSet.getAttributeValue(1);
                    if (table != null) {
                        rows.add(new Place(connection.getId(), connection.getName(),
                            schema == null ? "" : String.valueOf(schema), String.valueOf(table)));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't query table names of " + connection.getName(), e);
            return false;
        }
        found.addAll(rows);
        return true;
    }

    /** dbeaver-mm K79: every table up to two container levels below {@code container} */
    private static void collectAllPlaces(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        int depth,
        @NotNull DBPDataSourceContainer connection,
        @NotNull List<Place> found
    ) {
        try {
            Collection<? extends DBSObject> children = container.getChildren(monitor);
            if (children == null) {
                return;
            }
            for (DBSObject child : children) {
                if (monitor.isCanceled()) {
                    return;
                }
                if (child instanceof DBSEntity entity) {
                    found.add(placeOf(entity, connection));
                } else if (depth > 0 && child instanceof DBSObjectContainer sub) {
                    collectAllPlaces(monitor, sub, depth - 1, connection, found);
                }
            }
        } catch (Exception e) {
            log.debug("Can't read tables of " + connection.getName(), e);
        }
    }

    /** dbeaver-mm K79: the database a connection points to (URL, else host:port/database) */
    @NotNull
    private static String databaseIdentity(@NotNull DBPDataSourceContainer connection) {
        var config = connection.getConnectionConfiguration();
        String url = config.getUrl();
        if (url != null && !url.isBlank()) {
            return url;
        }
        return config.getHostName() + ":" + config.getHostPort() + "/" + config.getDatabaseName();
    }

    @NotNull
    private static Place placeOf(@NotNull DBSEntity entity, @NotNull DBPDataSourceContainer connection) {
        DBSObject parent = entity.getParentObject();
        String schema = parent == null || parent instanceof DBPDataSource ? "" : parent.getName();
        return new Place(connection.getId(), connection.getName(), schema, entity.getName());
    }

    /** dbeaver-mm K73: the type table in {@code schema} (or below the root when the schema is empty) */
    @Nullable
    private static DBSEntity findTypeTable(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer root,
        @NotNull String schema
    ) throws Exception {
        if (!schema.isEmpty()) {
            Collection<? extends DBSObject> children = root.getChildren(monitor);
            if (children != null) {
                for (DBSObject child : children) {
                    if (child instanceof DBSObjectContainer container && child.getName().equalsIgnoreCase(schema)) {
                        DBSEntity entity = findTable(monitor, container, DataTpStore.TYPE_TABLE, 2);
                        if (entity != null) {
                            return entity;
                        }
                    }
                }
            }
        }
        return findTable(monitor, root, DataTpStore.TYPE_TABLE, 2);
    }

    @Nullable
    private static DBSEntity findTable(
        @NotNull DBRProgressMonitor monitor,
        @NotNull DBSObjectContainer container,
        @NotNull String table,
        int depth
    ) {
        try {
            Collection<? extends DBSObject> children = container.getChildren(monitor);
            if (children == null) {
                return null;
            }
            for (DBSObject child : children) {
                if (child instanceof DBSEntity entity && entity.getName().equalsIgnoreCase(table)) {
                    return entity;
                }
            }
            if (depth > 0) {
                for (DBSObject child : children) {
                    if (monitor.isCanceled()) {
                        return null;
                    }
                    if (child instanceof DBSObjectContainer sub && !(child instanceof DBSEntity)) {
                        DBSEntity entity = findTable(monitor, sub, table, depth - 1);
                        if (entity != null) {
                            return entity;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Can't look up " + table, e);
        }
        return null;
    }

    /** dbeaver-mm K73: TYPE_ID -> table_name, like {@code DataTpLabels.loadTypes} */
    @NotNull
    private static Map<String, String> readTypes(@NotNull DBRProgressMonitor monitor, @NotNull DBSEntity entity) throws Exception {
        Map<String, String> types = new LinkedHashMap<>();
        DBCExecutionContext context = DBUtils.getDefaultContext(entity.getDataSource(), true);
        if (context == null) {
            return types;
        }
        DBCSession session = context.openSession(monitor, DBCExecutionPurpose.UTIL, "Read data_tp type names");
        try (session) {
            String query = "SELECT " + DataTpStore.TYPE_ID + ", " + DataTpStore.TYPE_NAME
                + " FROM " + DBUtils.getObjectFullName(entity, DBPEvaluationContext.DML);
            try (DBCStatement statement = session.prepareStatement(DBCStatementType.QUERY, query, false, false, false)) {
                if (!statement.executeStatement()) {
                    return types;
                }
                try (DBCResultSet resultSet = statement.openResultSet()) {
                    while (resultSet != null && !monitor.isCanceled() && resultSet.nextRow()) {
                        String key = DataTpStore.typeKey(resultSet.getAttributeValue(0));
                        Object name = resultSet.getAttributeValue(1);
                        if (key != null && !key.isEmpty() && name != null) {
                            types.put(key, String.valueOf(name));
                        }
                    }
                }
            }
        }
        return types;
    }

    private void renderRows() {
        if (rowsArea == null || rowsArea.isDisposed()) {
            return;
        }
        for (Control child : rowsArea.getChildren()) {
            child.dispose();
        }
        // dbeaver-mm K80: map a table name to its first place in allPlaces (case-insensitive)
        Map<String, Place> placeByName = new LinkedHashMap<>();
        for (Place place : allPlaces) {
            placeByName.putIfAbsent(place.table().toLowerCase(Locale.ROOT), place);
        }
        List<String> allTableNames = allPlaces.stream().map(Place::table).toList();
        for (Row row : rows) {
            Composite line = new Composite(rowsArea, SWT.NONE);
            line.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            line.setLayout(new GridLayout(5, false));
            label(line, row.dataTp, 60);
            label(line, row.table == null ? "-" : row.table, 150);
            Label schemaLabel;
            if (row.candidates.size() > 1) {
                Combo combo = new Combo(line, SWT.DROP_DOWN | SWT.READ_ONLY);
                combo.add("- pick a place -");
                for (Place place : row.candidates) {
                    combo.add(place.connectionName() + " / " + (place.schema().isEmpty() ? "(default)" : place.schema()));
                }
                int selected = 0;
                for (int i = 0; i < row.candidates.size(); i++) {
                    if (row.candidates.get(i).equals(row.chosen)) {
                        selected = i + 1;
                    }
                }
                combo.select(selected);
                GridData comboData = new GridData(SWT.FILL, SWT.CENTER, false, false);
                comboData.widthHint = 240;
                combo.setLayoutData(comboData);
                String initialSchema = row.chosen == null ? "-" : row.chosen.schema();
                schemaLabel = label(line, initialSchema, 110);
                Label statusLabel = label(line, status(row), 110);
                combo.addListener(SWT.Selection, e -> {
                    int picked = combo.getSelectionIndex();
                    row.chosen = picked <= 0 ? null : row.candidates.get(picked - 1);
                    schemaLabel.setText(row.chosen == null ? "-" : row.chosen.schema());
                    statusLabel.setText(status(row));
                });
            } else if (row.candidates.isEmpty()) {
                // dbeaver-mm K80: not-found row gets a combo with close names + "Choose a table..."
                Combo combo = new Combo(line, SWT.DROP_DOWN | SWT.READ_ONLY);
                combo.add("- not found, pick a close table -");
                List<String> closeNames = DataTpMatch.closest(
                    row.table == null ? "" : row.table,
                    allTableNames,
                    10
                );
                List<Place> closePlaces = new ArrayList<>();
                for (String name : closeNames) {
                    Place place = placeByName.get(name.toLowerCase(Locale.ROOT));
                    if (place == null) {
                        continue;
                    }
                    closePlaces.add(place);
                    combo.add(placeText(place));
                }
                // dbeaver-mm K80: a table picked with "Choose a table..." stays visible in the combo
                if (row.chosen != null && !closePlaces.contains(row.chosen)) {
                    closePlaces.add(0, row.chosen);
                    combo.add(placeText(row.chosen), 1);
                }
                combo.add("Choose a table...");
                int selected = 0;
                for (int i = 0; i < closePlaces.size(); i++) {
                    if (closePlaces.get(i).equals(row.chosen)) {
                        selected = i + 1;
                    }
                }
                combo.select(selected);
                GridData comboData = new GridData(SWT.FILL, SWT.CENTER, false, false);
                comboData.widthHint = 240;
                combo.setLayoutData(comboData);
                String initialSchema = row.chosen == null ? "-" : row.chosen.schema();
                schemaLabel = label(line, initialSchema, 110);
                Label statusLabel = label(line, status(row), 110);
                combo.addListener(SWT.Selection, e -> {
                    int picked = combo.getSelectionIndex();
                    if (picked <= 0) {
                        row.chosen = null;
                    } else if (picked <= closePlaces.size()) {
                        row.chosen = closePlaces.get(picked - 1);
                    } else {
                        // "Choose a table..."
                        ChooseTableDialog dialog = new ChooseTableDialog(getShell(), allPlaces, row.chosen);
                        if (dialog.open() == IDialogConstants.OK_ID) {
                            Place pickedPlace = dialog.getSelected();
                            if (pickedPlace != null) {
                                row.chosen = pickedPlace;
                                if (!closePlaces.contains(pickedPlace)) {
                                    closePlaces.add(0, pickedPlace);
                                    combo.add(placeText(pickedPlace), 1);
                                }
                            }
                        }
                        int reset = 0;
                        for (int i = 0; i < closePlaces.size(); i++) {
                            if (closePlaces.get(i).equals(row.chosen)) {
                                reset = i + 1;
                            }
                        }
                        combo.select(reset);
                    }
                    schemaLabel.setText(row.chosen == null ? "-" : row.chosen.schema());
                    statusLabel.setText(status(row));
                });
            } else {
                label(line, row.chosen == null ? "-" : row.chosen.connectionName(), 240);
                label(line, row.chosen == null ? "-" : row.chosen.schema(), 110);
                label(line, status(row), 110);
            }
        }
        rowsArea.layout(true, true);
        rowsScroll.setMinSize(rowsArea.computeSize(SWT.DEFAULT, SWT.DEFAULT));
    }

    @NotNull
    private static Label label(@NotNull Composite parent, @Nullable String text, int width) {
        Label label = new Label(parent, SWT.NONE);
        label.setText(text == null ? "-" : text);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, false, false);
        data.widthHint = width;
        label.setLayoutData(data);
        return label;
    }

    @NotNull
    private static String status(@NotNull Row row) {
        if (row.candidates.size() > 1) {
            return "⚠ " + row.candidates.size() + " places";
        }
        if (row.candidates.isEmpty()) {
            return row.chosen == null ? "✕ not found" : "✎ picked";
        }
        return "✓";
    }

    @NotNull
    private static String placeText(@NotNull Place place) {
        return place.table() + " · " + place.connectionName() + " / " + place.schema();
    }

    /** dbeaver-mm K80: small dialog to pick any table from allPlaces */
    private static final class ChooseTableDialog extends Dialog {
        private final List<Place> places;
        private final Place initial;
        private Place selected;
        private Text filterText;
        private Table table;
        private Label countLabel;

        ChooseTableDialog(@NotNull Shell parent, @NotNull List<Place> places, @Nullable Place initial) {
            super(parent);
            this.places = places;
            this.initial = initial;
            this.selected = initial;
        }

        @Override
        protected void configureShell(Shell shell) {
            super.configureShell(shell);
            shell.setText("Choose a table");
        }

        @Override
        protected boolean isResizable() {
            return true;
        }

        @Override
        protected Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(1, false));
            GridData areaData = new GridData(SWT.FILL, SWT.FILL, true, true);
            areaData.widthHint = 520;
            areaData.heightHint = 360;
            area.setLayoutData(areaData);

            filterText = new Text(area, SWT.SEARCH | SWT.BORDER);
            filterText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
            filterText.setMessage("type to filter");

            table = new Table(area, SWT.SINGLE | SWT.FULL_SELECTION | SWT.BORDER | SWT.V_SCROLL);
            GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
            tableData.heightHint = 260;
            table.setLayoutData(tableData);
            table.setHeaderVisible(true);
            table.setLinesVisible(true);
            TableColumn c1 = new TableColumn(table, SWT.NONE);
            c1.setText("Table");
            c1.setWidth(200);
            TableColumn c2 = new TableColumn(table, SWT.NONE);
            c2.setText("Connection");
            c2.setWidth(180);
            TableColumn c3 = new TableColumn(table, SWT.NONE);
            c3.setText("Schema");
            c3.setWidth(120);

            countLabel = new Label(area, SWT.NONE);
            countLabel.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));

            filterText.addListener(SWT.Modify, e -> populate(filterText.getText()));
            table.addListener(SWT.MouseDoubleClick, e -> {
                if (table.getSelectionIndex() >= 0) {
                    okPressed();
                }
            });
            populate("");
            return area;
        }

        private void populate(@Nullable String filter) {
            table.removeAll();
            String needle = filter == null ? "" : filter.strip().toLowerCase(Locale.ROOT);
            List<Place> matches = new ArrayList<>();
            for (Place place : places) {
                if (needle.isEmpty()
                    || place.table().toLowerCase(Locale.ROOT).contains(needle)
                    || place.connectionName().toLowerCase(Locale.ROOT).contains(needle)
                    || place.schema().toLowerCase(Locale.ROOT).contains(needle)) {
                    matches.add(place);
                }
            }
            int total = matches.size();
            int shown = Math.min(total, 500);
            for (int i = 0; i < shown; i++) {
                Place place = matches.get(i);
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(0, place.table());
                item.setText(1, place.connectionName());
                item.setText(2, place.schema());
                item.setData(place);
            }
            if (total > 500) {
                countLabel.setText("Showing 500 of " + total + ", type to narrow");
            } else {
                countLabel.setText(total + " of " + places.size());
            }
            if (selected != null) {
                for (int i = 0; i < table.getItemCount(); i++) {
                    if (selected.equals(table.getItem(i).getData())) {
                        table.select(i);
                        table.showSelection();
                        break;
                    }
                }
            }
        }

        @Override
        protected void okPressed() {
            int index = table.getSelectionIndex();
            if (index >= 0) {
                selected = (Place) table.getItem(index).getData();
            }
            super.okPressed();
        }

        @Override
        protected void cancelPressed() {
            selected = initial;
            super.cancelPressed();
        }

        @Nullable
        Place getSelected() {
            return selected;
        }
    }

    @Override
    protected void okPressed() {
        final String savedTenant = tenant;
        int index = typeConnectionCombo == null || typeConnectionCombo.isDisposed() ? -1 : typeConnectionCombo.getSelectionIndex();
        final String typeConnectionId = index < 0 || index >= tenantConnections.size()
            ? "" : tenantConnections.get(index).getId();
        final String typeSchema = typeSchemaCombo == null || typeSchemaCombo.isDisposed() ? "" : typeSchemaCombo.getText().strip();
        final Map<String, DataTpStore.Entry> types = new LinkedHashMap<>();
        for (Row row : rows) {
            if (row.chosen != null) {
                types.put(row.dataTp, new DataTpStore.Entry(row.chosen.table(), row.chosen.connectionId(), row.chosen.schema()));
            }
        }
        final String built = LocalDateTime.now().format(BUILT_FORMAT);
        super.okPressed();
        if (savedTenant.isEmpty()) {
            return;
        }
        final Path file = DataTpStore.tenantFile(DataTpStore.tenantDir(), savedTenant);
        final DataTpStore.TenantMap tenantMap = new DataTpStore.TenantMap(typeConnectionId, typeSchema, types, built);
        new AbstractJob("Save data_tp mapping of " + savedTenant) {
            @Override
            protected IStatus run(@NotNull DBRProgressMonitor monitor) {
                try {
                    DataTpStore.saveTenant(file, tenantMap);
                    DataTpLabels.invalidate();
                } catch (Exception e) {
                    log.error("Can't save data_tp mapping of " + savedTenant, e);
                }
                return Status.OK_STATUS;
            }
        }.schedule();
    }
}
