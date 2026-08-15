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

import org.eclipse.osgi.util.NLS;
import org.eclipse.swt.SWT;
import org.eclipse.swt.accessibility.ACC;
import org.eclipse.swt.accessibility.AccessibleAdapter;
import org.eclipse.swt.accessibility.AccessibleControlAdapter;
import org.eclipse.swt.accessibility.AccessibleControlEvent;
import org.eclipse.swt.accessibility.AccessibleEvent;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.BaseThemeSettings;
import org.jkiss.dbeaver.ui.UITextUtils;
import org.jkiss.dbeaver.ui.recentscripts.core.RecentScriptItem;
import org.jkiss.dbeaver.ui.recentscripts.internal.RecentScriptsMessages;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/**
 * One owner drawn card: bold title, muted description, monospaced SQL preview.
 * <p>
 * A single {@link Canvas} is used instead of a composite full of labels because the card has
 * to be focusable and take part in tab traversal, and because a label would swallow the mouse
 * events needed for the hover state.
 */
public class RecentScriptCard extends Canvas {

    private static final int MARGIN_X = 8;
    private static final int MARGIN_Y = 6;
    private static final int LINE_GAP = 2;
    private static final int BLOCK_GAP = 5;
    private static final int ARC = 6;
    private static final int DEFAULT_WIDTH = 260;
    private static final int STAR_SIZE = 16;
    private static final int STAR_RADIUS = 7;

    private final RecentScriptsPanel panel;
    private final RecentScriptItem item;
    private final int index;

    private boolean hover;

    public RecentScriptCard(
        @NotNull Composite parent,
        @NotNull RecentScriptsPanel panel,
        @NotNull RecentScriptItem item,
        int index
    ) {
        super(parent, SWT.DOUBLE_BUFFERED);
        this.panel = panel;
        this.item = item;
        this.index = index;

        setBackground(panel.getCardColors().background);
        setToolTipText(buildTooltip());

        addPaintListener(this::onPaint);
        addListener(SWT.MouseEnter, e -> setHover(true));
        addListener(SWT.MouseExit, e -> setHover(false));
        addListener(SWT.MouseDown, this::onMouseDown);
        addListener(SWT.MouseUp, this::onMouseUp);
        addListener(SWT.KeyDown, this::onKeyDown);
        addListener(SWT.FocusIn, e -> onFocusChanged(true));
        addListener(SWT.FocusOut, e -> onFocusChanged(false));
        // Let Tab and Shift+Tab move between cards, and Esc leave the panel
        addListener(SWT.Traverse, e -> e.doit = true);

        initAccessibility();
    }

    @NotNull
    public RecentScriptItem getItem() {
        return item;
    }

    public int getIndex() {
        return index;
    }

    /**
     * A bare {@link Canvas} reports 64x64, so this override is what gives the card its height.
     */
    @Override
    public Point computeSize(int wHint, int hHint, boolean changed) {
        int height = MARGIN_Y * 2;
        GC gc = new GC(this);
        try {
            gc.setFont(BaseThemeSettings.instance.baseFontBold);
            height += gc.getFontMetrics().getHeight();
            if (item.description() != null) {
                gc.setFont(BaseThemeSettings.instance.baseFont);
                height += LINE_GAP + gc.getFontMetrics().getHeight();
            }
            List<String> previewLines = item.previewLines();
            if (!previewLines.isEmpty()) {
                gc.setFont(BaseThemeSettings.instance.monospaceFont);
                height += BLOCK_GAP + previewLines.size() * gc.getFontMetrics().getHeight();
            }
        } finally {
            gc.dispose();
        }
        int width = wHint == SWT.DEFAULT ? DEFAULT_WIDTH : wHint;
        return new Point(Math.max(width, 0), hHint == SWT.DEFAULT ? height : hHint);
    }

    private void onPaint(@NotNull org.eclipse.swt.events.PaintEvent event) {
        GC gc = event.gc;
        CardColors colors = panel.getCardColors();
        Rectangle area = getClientArea();
        boolean selected = panel.getSelectedIndex() == index;

        gc.setAntialias(SWT.ON);
        gc.setTextAntialias(SWT.ON);

        gc.setBackground(colors.background);
        gc.fillRectangle(area);

        Color fill;
        if (selected) {
            fill = colors.selectionBackground;
        } else if (hover || isFocusControl()) {
            fill = colors.hoverBackground;
        } else {
            fill = colors.background;
        }
        gc.setBackground(fill);
        gc.fillRoundRectangle(0, 0, area.width - 1, area.height - 1, ARC, ARC);
        gc.setForeground(colors.border);
        gc.drawRoundRectangle(0, 0, area.width - 1, area.height - 1, ARC, ARC);

        int textWidth = area.width - MARGIN_X * 2;
        if (textWidth <= 0) {
            return;
        }
        drawStar(gc, colors, area);

        int y = MARGIN_Y;

        // The title shares its line with the star, so it must not run underneath it
        int titleWidth = textWidth - STAR_SIZE - MARGIN_X;
        y = drawLine(gc, item.title(), BaseThemeSettings.instance.baseFontBold,
            selected ? colors.selectionForeground : colors.title, Math.max(titleWidth, 0), y);

        if (item.description() != null) {
            y += LINE_GAP;
            y = drawLine(gc, item.description(), BaseThemeSettings.instance.baseFont,
                selected ? colors.selectionForeground : colors.description, textWidth, y);
        }
        List<String> previewLines = item.previewLines();
        if (!previewLines.isEmpty()) {
            y += BLOCK_GAP;
            for (String line : previewLines) {
                y = drawLine(gc, line, BaseThemeSettings.instance.monospaceFont,
                    selected ? colors.selectionForeground : colors.preview, textWidth, y);
            }
        }

        if (isFocusControl()) {
            gc.drawFocus(2, 2, area.width - 5, area.height - 5);
        }
    }

    private void drawStar(@NotNull GC gc, @NotNull CardColors colors, @NotNull Rectangle area) {
        Rectangle box = starBounds(area);
        int[] points = starPoints(box.x + box.width / 2, box.y + box.height / 2, STAR_RADIUS);
        if (item.favorite()) {
            gc.setBackground(colors.favoriteStar);
            gc.fillPolygon(points);
            gc.setForeground(colors.favoriteStar);
            gc.drawPolygon(points);
        } else {
            // Hollow outline: discoverable, but visually quiet until the user opts in
            gc.setForeground(hover || isFocusControl() ? colors.description : colors.border);
            gc.drawPolygon(points);
        }
    }

    @NotNull
    private static Rectangle starBounds(@NotNull Rectangle area) {
        return new Rectangle(area.width - MARGIN_X - STAR_SIZE, MARGIN_Y, STAR_SIZE, STAR_SIZE);
    }

    /**
     * Points of a five-pointed star, top vertex first, alternating outer and inner radius.
     */
    @NotNull
    private static int[] starPoints(int cx, int cy, int outer) {
        int inner = Math.round(outer * 0.42f);
        int[] points = new int[20];
        for (int i = 0; i < 10; i++) {
            double angle = Math.toRadians(-90 + i * 36);
            int radius = (i % 2 == 0) ? outer : inner;
            points[i * 2] = cx + (int) Math.round(radius * Math.cos(angle));
            points[i * 2 + 1] = cy + (int) Math.round(radius * Math.sin(angle));
        }
        return points;
    }

    /**
     * Draws one clipped line and returns the y coordinate of the next one.
     * <p>
     * Fonts are read from {@link BaseThemeSettings} on every paint on purpose: those fields are
     * swapped when the theme changes, and a cached reference would end up disposed.
     */
    private int drawLine(@NotNull GC gc, @NotNull String text, @NotNull Font font, @NotNull Color color, int width, int y) {
        gc.setFont(font);
        gc.setForeground(color);
        gc.drawText(UITextUtils.getShortText(gc, text, width), MARGIN_X, y, SWT.DRAW_TRANSPARENT);
        return y + gc.getFontMetrics().getHeight();
    }

    private void setHover(boolean hover) {
        if (this.hover != hover) {
            this.hover = hover;
            redraw();
        }
    }

    private void onFocusChanged(boolean focused) {
        if (focused) {
            panel.revealCard(this);
        }
        redraw();
    }

    private void onMouseDown(@NotNull Event event) {
        if (event.button == 1) {
            setFocus();
            panel.select(index);
        }
    }

    private void onMouseUp(@NotNull Event event) {
        if (event.button != 1 || !getClientArea().contains(event.x, event.y)) {
            return;
        }
        // A click on the star toggles favorite; it must not also open the script
        if (starHitBounds().contains(event.x, event.y)) {
            panel.toggleFavorite(item);
            return;
        }
        // Activating on mouse up (not down) lets a press-and-drag-away cancel the click
        panel.openScript(item);
    }

    @NotNull
    private Rectangle starHitBounds() {
        Rectangle box = starBounds(getClientArea());
        // A slightly forgiving target around the drawn glyph
        return new Rectangle(box.x - 3, box.y - 3, box.width + 6, box.height + 6);
    }

    private void onKeyDown(@NotNull Event event) {
        switch (event.keyCode) {
            case SWT.CR:
            case SWT.KEYPAD_CR:
                panel.openScript(item);
                break;
            case SWT.ARROW_UP:
                panel.select(index - 1);
                break;
            case SWT.ARROW_DOWN:
                panel.select(index + 1);
                break;
            case SWT.HOME:
                panel.selectFirst();
                break;
            case SWT.END:
                panel.selectLast();
                break;
            default:
                if (event.character == ' ') {
                    panel.openScript(item);
                } else if (event.character == 'f' || event.character == 'F') {
                    panel.toggleFavorite(item);
                }
                break;
        }
    }

    private void initAccessibility() {
        getAccessible().addAccessibleListener(new AccessibleAdapter() {
            @Override
            public void getName(AccessibleEvent e) {
                String name = item.description() == null
                    ? item.title()
                    : item.title() + ", " + item.description();
                e.result = item.favorite() ? name + ", favorite" : name;
            }
        });
        getAccessible().addAccessibleControlListener(new AccessibleControlAdapter() {
            @Override
            public void getRole(AccessibleControlEvent e) {
                e.detail = ACC.ROLE_PUSHBUTTON;
            }
        });
    }

    @NotNull
    private String buildTooltip() {
        StringBuilder tooltip = new StringBuilder(item.resourcePath());
        tooltip.append('\n').append(NLS.bind(
            RecentScriptsMessages.card_tooltip_modified,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(item.lastModified()))));
        if (item.dataSourceName() != null) {
            tooltip.append('\n').append(NLS.bind(
                RecentScriptsMessages.card_tooltip_connection, item.dataSourceName()));
        }
        return tooltip.toString();
    }
}
