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
package org.jkiss.dbeaver.ui.navigator.database;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPImage;
import org.jkiss.dbeaver.model.navigator.DBNDatabaseFolder;
import org.jkiss.dbeaver.model.navigator.DBNNode;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;
import org.jkiss.dbeaver.model.struct.DBSEntity;
import org.jkiss.dbeaver.model.struct.rdb.DBSView;

import java.util.ArrayList;
import java.util.List;

/**
 * dbeaver-mm K4: tree-only grouping node. Under a connection / schema the tables are shown directly
 * and every other folder (Views, Indexes, Sequences, Triggers, ...) goes into this one node, which
 * starts collapsed. The folders keep their real parent in the model; only the tree shows them here.
 */
public class NavigatorOtherObjectsNode extends DBNNode {

    private final DBNNode[] folders;

    private NavigatorOtherObjectsNode(@NotNull DBNNode parent, @NotNull DBNNode[] folders) {
        super(parent);
        this.folders = folders;
    }

    /**
     * Returns the children the tree should show under {@code parent}: the Tables folder's children
     * followed by one "Other objects" node; or {@code children} unchanged if there is no such folder.
     * ponytail: the Tables folder is loaded here, on the caller's thread; fine for local/fast
     * connections, move it into TreeLoadService if a slow server ever freezes the tree.
     */
    @NotNull
    static DBNNode[] flattenTables(@NotNull DBNNode parent, @NotNull DBNNode[] children, @NotNull DBRProgressMonitor monitor) {
        DBNDatabaseFolder tablesFolder = null;
        List<DBNNode> others = new ArrayList<>();
        for (DBNNode child : children) {
            if (tablesFolder == null && child instanceof DBNDatabaseFolder folder && isTablesFolder(folder)) {
                tablesFolder = folder;
            } else {
                others.add(child);
            }
        }
        if (tablesFolder == null || others.isEmpty()) {
            return children;
        }
        DBNNode[] tables;
        try {
            tables = tablesFolder.getChildren(monitor);
        } catch (Exception e) {
            return children;
        }
        List<DBNNode> result = new ArrayList<>();
        if (tables != null) {
            result.addAll(List.of(tables));
        }
        result.add(new NavigatorOtherObjectsNode(parent, others.toArray(new DBNNode[0])));
        return result.toArray(new DBNNode[0]);
    }

    private static boolean isTablesFolder(@NotNull DBNDatabaseFolder folder) {
        Class<?> childrenClass = folder.getChildrenClass();
        return childrenClass != null
            && DBSEntity.class.isAssignableFrom(childrenClass)
            && !DBSView.class.isAssignableFrom(childrenClass);
    }

    @NotNull
    @Override
    public String getNodeType() {
        return "other-objects";
    }

    @NotNull
    @Override
    public String getNodeDisplayName() {
        return "Other objects";
    }

    @Nullable
    @Override
    public String getNodeDescription() {
        return "Views, indexes, sequences, triggers and other objects";
    }

    @Nullable
    @Override
    public DBPImage getNodeIcon() {
        return DBIcon.TREE_FOLDER;
    }

    @Override
    protected boolean allowsChildren() {
        return true;
    }

    @Override
    public DBNNode[] getChildren(@NotNull DBRProgressMonitor monitor) {
        return folders;
    }

    @Override
    public boolean allowsOpen() {
        return false;
    }
}
