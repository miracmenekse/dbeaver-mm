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
package org.jkiss.dbeaver.ui.dialogs.connection;

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.model.connection.DBPConnectionType;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.CSmartCombo;

/**
 * dbeaver-mm G1 (UI_UX_MODERNIZASYON.md): puts the connection type (development / test /
 * production, with its color) on the main settings page of the connection wizard instead of
 * only on the General page, so the environment is chosen up front.
 * <p>
 * Both pages stay in sync through {@link ConnectionWizard#PROP_CONNECTION_TYPE}; the General
 * page remains the one that writes the type on save.
 */
final class ConnectionEnvironmentSelector {

    private ConnectionEnvironmentSelector() {
    }

    static void create(@NotNull Composite parent, @NotNull ConnectionWizard wizard) {
        Composite group = UIUtils.createComposite(parent, 2);
        ((GridLayout) group.getLayout()).marginHeight = 0;

        CSmartCombo<DBPConnectionType> combo = ConnectionPageGeneral.createConnectionTypeCombo(group);
        ConnectionPageGeneral.setConnectionType(combo, getCurrentType(wizard));

        combo.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> {
            DBPConnectionType type = combo.getSelectedItem();
            if (type != null) {
                // Old value null: always deliver, see ConnectionPageGeneral
                wizard.firePropertyChangeEvent(ConnectionWizard.PROP_CONNECTION_TYPE, null, type);
            }
        }));

        // The wizard has no removePropertyChangeListener and outlives this page (it is
        // recreated when the driver changes), so the listener just ignores a disposed combo.
        IPropertyChangeListener listener = event -> {
            if (ConnectionWizard.PROP_CONNECTION_TYPE.equals(event.getProperty())
                && event.getNewValue() instanceof DBPConnectionType type
                && !combo.isDisposed()
                && !type.equals(combo.getSelectedItem())) {
                ConnectionPageGeneral.setConnectionType(combo, type);
            }
        };
        wizard.addPropertyChangeListener(listener);
    }

    @NotNull
    private static DBPConnectionType getCurrentType(@NotNull ConnectionWizard wizard) {
        if (wizard.getPage(ConnectionPageGeneral.PAGE_NAME) instanceof ConnectionPageGeneral general
            && general.getSelectedConnectionType() != null) {
            return general.getSelectedConnectionType();
        }
        return wizard.getActiveDataSource().getConnectionConfiguration().getConnectionType();
    }
}
