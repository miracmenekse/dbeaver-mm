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
package org.jkiss.dbeaver.ui.inlinefkpicker.trigger;

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.commands.ICommandService;
import org.eclipse.ui.commands.IElementUpdater;
import org.eclipse.ui.menus.UIElement;
import org.jkiss.dbeaver.model.preferences.DBPPreferenceStore;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.PrefUtils;

import java.util.Map;

/**
 * dbeaver-mm K61: Plus Settings > "Value picker opens on '='". When off, the picker only opens
 * with Ctrl+Space (K60) at a value position, so fast typists are not interrupted.
 */
public class ToggleAutoOpenHandler extends AbstractHandler implements IElementUpdater {

    public static final String COMMAND_ID = "org.jkiss.dbeaver.ui.inlinefkpicker.toggleAutoOpen";
    // Stored inverted: a boolean preference equal to its default (false) is removed from the store, so
    // "off" must be the true value or switching it off would be forgotten.
    private static final String PREF_OFF = "mm.sqlPicker.autoOpenOff";

    /** On unless the user switched it off. */
    public static boolean isAutoOpen() {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        return !store.getBoolean(PREF_OFF);
    }

    @Override
    public Object execute(ExecutionEvent event) {
        DBPPreferenceStore store = DBWorkbench.getPlatform().getPreferenceStore();
        store.setValue(PREF_OFF, isAutoOpen());
        PrefUtils.savePreferenceStore(store);
        PlatformUI.getWorkbench().getService(ICommandService.class).refreshElements(COMMAND_ID, null);
        return null;
    }

    @Override
    public void updateElement(UIElement element, Map parameters) {
        element.setChecked(isAutoOpen());
    }
}
