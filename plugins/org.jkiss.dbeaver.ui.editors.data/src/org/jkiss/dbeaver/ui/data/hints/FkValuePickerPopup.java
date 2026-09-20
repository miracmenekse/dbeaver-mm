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

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.*;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.DBDAttributeBinding;
import org.jkiss.dbeaver.model.data.DBDLabelValuePair;
import org.jkiss.dbeaver.model.runtime.SystemJob;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.utils.CommonUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * dbeaver-mm K8: the value list behind the in-cell FK button. Same shape as the Value panel's
 * dictionary list - a search box over a Value / Description table - so it stays usable on big
 * tables: only {@link #MAX_VALUES} rows are read and the search runs in the database, not here.
 */
class FkValuePickerPopup {

    private static final Log log = Log.getLog(FkValuePickerPopup.class);

    private static final int MAX_VALUES = 50;
    private static final int SEARCH_DELAY_MS = 300;
    private static final int LIST_WIDTH = 320;
    private static final int LIST_HEIGHT = 200;

    private final DBDAttributeBinding attribute;
    private final Object currentValue;
    private final Consumer<Object> onPick;

    private Shell shell;
    private Text searchText;
    private Table table;
    private List<DBDLabelValuePair> rows = new ArrayList<>();
    private int searchSeq;

    FkValuePickerPopup(@NotNull DBDAttributeBinding attribute, @Nullable Object currentValue, @NotNull Consumer<Object> onPick) {
        this.attribute = attribute;
        this.currentValue = currentValue;
        this.onPick = onPick;
    }

    void open(@NotNull Control parent, @NotNull Point location) {
        shell = new Shell(parent.getShell(), SWT.ON_TOP | SWT.TOOL | SWT.BORDER);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 2;
        layout.marginHeight = 2;
        layout.verticalSpacing = 2;
        shell.setLayout(layout);

        searchText = new Text(shell, SWT.BORDER | SWT.SEARCH | SWT.ICON_SEARCH);
        searchText.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        searchText.setMessage("Type part of value to search");

        table = new Table(shell, SWT.FULL_SELECTION | SWT.V_SCROLL | SWT.BORDER | SWT.SINGLE);
        GridData tableData = new GridData(SWT.FILL, SWT.FILL, true, true);
        tableData.widthHint = LIST_WIDTH;
        tableData.heightHint = LIST_HEIGHT;
        table.setLayoutData(tableData);
        table.setHeaderVisible(true);
        UIUtils.createTableColumn(table, SWT.LEFT, "Value");
        UIUtils.createTableColumn(table, SWT.LEFT, "Description");

        searchText.addModifyListener(e -> scheduleSearch());
        searchText.addListener(SWT.KeyDown, this::onKey);
        table.addListener(SWT.MouseDoubleClick, e -> accept());
        table.addListener(SWT.KeyDown, this::onKey);
        shell.addListener(SWT.Deactivate, e -> close());
        shell.addListener(SWT.Traverse, e -> {
            if (e.detail == SWT.TRAVERSE_ESCAPE) {
                e.doit = false;
                close();
            }
        });

        search(null);
        shell.pack();
        shell.setLocation(location);
        shell.open();
        searchText.setFocus();
    }

    private void onKey(Event e) {
        switch (e.keyCode) {
            case SWT.ARROW_DOWN -> move(1, e);
            case SWT.ARROW_UP -> move(-1, e);
            case SWT.CR, SWT.KEYPAD_CR -> {
                accept();
                e.doit = false;
            }
            default -> {
            }
        }
    }

    private void move(int delta, Event e) {
        if (e.widget == table) {
            return; // the table moves the selection itself
        }
        int count = table.getItemCount();
        if (count > 0) {
            int idx = Math.max(0, Math.min(count - 1, table.getSelectionIndex() + delta));
            table.setSelection(idx);
            table.showSelection();
        }
        e.doit = false;
    }

    private void scheduleSearch() {
        Display display = shell.getDisplay();
        display.timerExec(-1, this::runSearch);
        display.timerExec(SEARCH_DELAY_MS, this::runSearch);
    }

    private void runSearch() {
        if (shell != null && !shell.isDisposed()) {
            search(searchText.getText());
        }
    }

    private void search(@Nullable String filter) {
        int seq = ++searchSeq;
        List<DBDLabelValuePair> result = new ArrayList<>();
        SystemJob job = new SystemJob("Read column values", monitor -> {
            try {
                result.addAll(FkDictionaryLabels.listValues(monitor, attribute, CommonUtils.nullIfEmpty(filter), MAX_VALUES));
            } catch (Exception e) {
                log.debug("Error reading values for the cell picker", e);
            }
        });
        job.schedule();
        UIUtils.waitJobCompletion(job);
        if (seq != searchSeq || shell == null || shell.isDisposed()) {
            return;
        }
        rows = result;
        table.removeAll();
        String current = CommonUtils.toString(currentValue);
        int selection = -1;
        for (int i = 0; i < rows.size(); i++) {
            DBDLabelValuePair pair = rows.get(i);
            TableItem item = new TableItem(table, SWT.NONE);
            item.setText(0, CommonUtils.toString(pair.getValue()));
            item.setText(1, CommonUtils.notEmpty(pair.getLabel()));
            if (item.getText(0).equals(current)) {
                selection = i;
            }
        }
        UIUtils.packColumns(table, true);
        if (table.getItemCount() > 0) {
            table.setSelection(Math.max(selection, 0));
        }
    }

    private void accept() {
        int idx = table.getSelectionIndex();
        if (idx >= 0 && idx < rows.size()) {
            Object value = rows.get(idx).getValue();
            close();
            onPick.accept(value);
        } else {
            close();
        }
    }

    private void close() {
        if (shell != null && !shell.isDisposed()) {
            shell.dispose();
        }
    }
}
