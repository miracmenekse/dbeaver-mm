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

import java.util.List;

/**
 * dbeaver-mm K112: pure helper for the single-schema tree shortcut. It walks a small node abstraction
 * (databases folder -> catalog -> schemas folder -> schema) and returns the chain only when every link
 * has exactly one child of the expected kind.
 */
public final class SingleSchemaChain {

    // dbeaver-mm K112
    public interface Item {
        String kind();

        List<? extends Item> children();
    }

    private static final String KIND_DATABASES = "databases";
    private static final String KIND_CATALOG = "catalog";
    private static final String KIND_SCHEMAS = "schemas";
    private static final String KIND_SCHEMA = "schema";

    private SingleSchemaChain() {
    }

    public static <T extends Item> List<T> chain(T root) {
        T databases = findDatabases(root);
        if (databases == null) {
            return List.of();
        }
        T catalog = singleChild(databases, KIND_CATALOG);
        if (catalog == null) {
            return List.of();
        }
        T schemas = singleChild(catalog, KIND_SCHEMAS);
        if (schemas == null) {
            return List.of();
        }
        T schema = singleChild(schemas, KIND_SCHEMA);
        if (schema == null) {
            return List.of();
        }
        return List.of(databases, catalog, schemas, schema);
    }

    private static <T extends Item> T findDatabases(T root) {
        if (KIND_DATABASES.equals(root.kind())) {
            return root;
        }
        return singleChild(root, KIND_DATABASES);
    }

    private static <T extends Item> T singleChild(T parent, String kind) {
        List<? extends Item> children = parent.children();
        if (children == null || children.size() != 1) {
            return null;
        }
        Item child = children.get(0);
        if (child == null || !kind.equals(child.kind())) {
            return null;
        }
        @SuppressWarnings("unchecked")
        T result = (T) child;
        return result;
    }
}
