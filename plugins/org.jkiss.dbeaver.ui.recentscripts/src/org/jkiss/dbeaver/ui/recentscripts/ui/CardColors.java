/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2024 DBeaver Corp and others
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
package org.jkiss.dbeaver.ui.recentscripts.ui;

import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.UIUtils;

/**
 * Theme derived palette for the script cards.
 * <p>
 * Every colour is derived from the current theme rather than hardcoded, so the panel reads
 * correctly in both the light and the dark theme. {@link UIStyles#mix} and friends allocate a
 * new {@link Color} on every call, so results are interned through
 * {@link UIUtils#getSharedColor} and the palette is recomputed only on theme changes - never
 * from a paint listener.
 * <p>
 * None of these colours may be disposed: they belong to the shared pool.
 */
public final class CardColors {

    public Color background;
    public Color title;
    public Color description;
    public Color preview;
    public Color border;
    public Color hoverBackground;
    public Color selectionBackground;
    public Color selectionForeground;
    /** Fill of a filled (active) favorite star - a theme-independent gold. */
    public Color favoriteStar;

    public CardColors() {
        refresh();
    }

    /**
     * Recomputes the palette from the current theme.
     */
    public void refresh() {
        background = UIStyles.getDefaultTextBackground();
        title = UIStyles.getDefaultTextForeground();
        description = intern(UIStyles.mix(title, background, 0.55f));
        preview = intern(UIStyles.mix(title, background, 0.80f));
        border = intern(UIStyles.mix(title, background, 0.20f));
        hoverBackground = intern(UIStyles.isDarkTheme()
            ? UIStyles.lighten(background, 0.05f)
            : UIStyles.darken(background, 0.035f));
        selectionBackground = UIStyles.getDefaultTextSelectionBackground();
        selectionForeground = UIStyles.getDefaultTextSelectionForeground();
        favoriteStar = UIUtils.getSharedColor(new RGB(0xF2, 0xB1, 0x2C));
    }

    @NotNull
    private static Color intern(@NotNull Color color) {
        return UIUtils.getSharedColor(color.getRGB());
    }
}
