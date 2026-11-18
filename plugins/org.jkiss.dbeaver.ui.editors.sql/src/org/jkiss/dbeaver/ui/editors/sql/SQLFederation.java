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
package org.jkiss.dbeaver.ui.editors.sql;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.DBException;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.runtime.DBRProgressMonitor;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Federe sorgu köprüsü.
 *
 * <p>Bu sınıf, SQL editörünün dış bir modülden (örneğin satır içi yabancı anahtar
 * seçicisi) gelen sonuçları yerel sorgu akışına sokmasını sağlar. Bağımlılık
 * yönü tek yönlüdür: köprüyü kullanan modül bu eklentiye bağlıdır; bu eklenti
 * ise köprüyü kullanan modülün sınıflarını asla içe aktarmaz.</p>
 *
 * <p>Köprü, kayıtlı bir {@link Runner} olmadığında tamamen şeffaftır; mevcut
 * sorgu yürütme akışı hiçbir şekilde değişmez.</p>
 */
// dbeaver-mm K122: dış modüle bağımlı olmayan tek yönlü köprü arayüzü
public final class SQLFederation {

    private static volatile Runner runner;

    private SQLFederation() {
    }

    /**
     * Federe çalıştırıcı sözleşmesi.
     *
     * <p>Çalıştırıcı, zaten arka planda çalışan sorgu işinin içinde çağrılır;
     * bu nedenle UI iş parçacığında çağrılması gerekmez.</p>
     */
    public interface Runner {

        /**
         * SELECT sorgusunu federe ortamda dener.
         *
         * @return federe sonuç, ya da sorgu federe değilse {@code null}
         * @throws DBException federe yürütme sırasında oluşan hatalar
         */
        @Nullable
        Result tryRun(
            @NotNull DBRProgressMonitor monitor,
            @NotNull DBCExecutionContext context,
            @NotNull String sql
        ) throws DBException;
    }

    /**
     * Federe sonucun sütun adları, satırları, görünen etiketi ve sütun tipleri.
     * dbeaver-mm K134: tipler bilinmiyorsa STRING kabul edilir.
     */
    public record Result(
        List<String> columns,
        List<Object[]> rows,
        String label,
        List<Integer> sqlTypes,
        List<String> typeNames
    ) {
        public Result {
            int size = columns == null ? 0 : columns.size();
            sqlTypes = normalizeTypes(sqlTypes, size);
            typeNames = normalizeNames(typeNames, size);
        }

        /** dbeaver-mm K134: tipleri taşımayan eski çağrılar için geriye uyumlu kurucu. */
        public Result(List<String> columns, List<?> rows, String label) {
            this(columns, toRows(rows), label, null, null);
        }

        private static List<Object[]> toRows(List<?> rows) {
            if (rows == null) {
                return List.of();
            }
            List<Object[]> result = new ArrayList<>(rows.size());
            for (Object row : rows) {
                result.add(row instanceof Object[] array ? array : new Object[]{row});
            }
            return result;
        }

        private static List<Integer> normalizeTypes(List<Integer> types, int size) {
            List<Integer> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                Integer type = types != null && i < types.size() ? types.get(i) : null;
                result.add(type == null ? Types.VARCHAR : type);
            }
            return List.copyOf(result);
        }

        private static List<String> normalizeNames(List<String> names, int size) {
            List<String> result = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                String name = names != null && i < names.size() ? names.get(i) : null;
                result.add(name == null ? "VARCHAR" : name);
            }
            return List.copyOf(result);
        }
    }

    /**
     * Etkin çalıştırıcıyı kaydeder. {@code null} kaydı köprüyü kapatır.
     */
    public static void register(@Nullable Runner r) {
        runner = r;
    }

    /**
     * Etkin çalıştırıcıyı döndürür; kayıtlı yoksa {@code null}.
     */
    @Nullable
    public static Runner get() {
        return runner;
    }
}
