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

import org.eclipse.jface.util.IPropertyChangeListener;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.PlatformUI;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.recentscripts.core.RecentScriptItem;
import org.jkiss.dbeaver.ui.recentscripts.internal.RecentScriptsMessages;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Scrollable list of script cards, with a loading and an empty state.
 */
public class RecentScriptsPanel extends Composite {

    private static final int CARD_GAP = 4;
    private static final int PANEL_MARGIN = 6;

    private final StackLayout stackLayout = new StackLayout();
    private final Composite loadingPlaceholder;
    private final Composite emptyPlaceholder;
    private final ScrolledComposite viewport;
    private final Composite cardContainer;

    private final CardColors cardColors = new CardColors();
    private final IPropertyChangeListener themeListener;
    private final List<RecentScriptCard> cards = new ArrayList<>();

    private Consumer<RecentScriptItem> openHandler = item -> {
    };
    private Consumer<RecentScriptItem> favoriteHandler = item -> {
    };
    private final List<Control> separators = new ArrayList<>();
    private int selectedIndex = -1;

    public RecentScriptsPanel(@NotNull Composite parent) {
        super(parent, SWT.NONE);
        setLayout(stackLayout);

        loadingPlaceholder = createMessagePlaceholder(RecentScriptsMessages.panel_state_loading);
        emptyPlaceholder = createMessagePlaceholder(RecentScriptsMessages.panel_state_empty);

        viewport = UIUtils.createScrolledComposite(this, SWT.V_SCROLL);
        cardContainer = new Composite(viewport, SWT.NONE);
        GridLayout containerLayout = new GridLayout(1, false);
        containerLayout.marginWidth = PANEL_MARGIN;
        containerLayout.marginHeight = PANEL_MARGIN;
        containerLayout.verticalSpacing = CARD_GAP;
        cardContainer.setLayout(containerLayout);
        // Also installs a resize listener that keeps minSize in sync with the client width
        UIUtils.configureScrolledComposite(viewport, cardContainer);

        applyThemeColors();
        stackLayout.topControl = loadingPlaceholder;

        themeListener = event -> {
            if (!isDisposed()) {
                onThemeChanged();
            }
        };
        PlatformUI.getWorkbench().getThemeManager().addPropertyChangeListener(themeListener);
        addDisposeListener(e ->
            PlatformUI.getWorkbench().getThemeManager().removePropertyChangeListener(themeListener));
    }

    /**
     * Sets what happens when a card is activated.
     */
    public void setOpenHandler(@NotNull Consumer<RecentScriptItem> openHandler) {
        this.openHandler = openHandler;
    }

    /**
     * Sets what happens when a card's favorite star is toggled.
     */
    public void setFavoriteHandler(@NotNull Consumer<RecentScriptItem> favoriteHandler) {
        this.favoriteHandler = favoriteHandler;
    }

    @NotNull
    public CardColors getCardColors() {
        return cardColors;
    }

    public int getSelectedIndex() {
        return selectedIndex;
    }

    /**
     * Shows the loading state. Only meaningful before the first result arrives.
     */
    public void showLoading() {
        if (cards.isEmpty()) {
            stackLayout.topControl = loadingPlaceholder;
            layout(true);
        }
    }

    /**
     * Replaces the contents of the list.
     */
    public void setItems(@NotNull List<RecentScriptItem> items) {
        for (RecentScriptCard card : cards) {
            card.dispose();
        }
        for (Control separator : separators) {
            separator.dispose();
        }
        cards.clear();
        separators.clear();
        selectedIndex = -1;

        for (int i = 0; i < items.size(); i++) {
            RecentScriptItem item = items.get(i);
            // A thin divider marks the boundary between the favorites block and the recent block
            if (i > 0 && items.get(i - 1).favorite() && !item.favorite()) {
                separators.add(createSeparator());
            }
            RecentScriptCard card = new RecentScriptCard(cardContainer, this, item, i);
            card.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false));
            cards.add(card);
        }

        stackLayout.topControl = items.isEmpty() ? emptyPlaceholder : viewport;
        layout(true);
        cardContainer.layout(true, true);
        updateScrollSize();
    }

    /**
     * Moves the selection, clamping to the available cards.
     */
    public void select(int index) {
        if (cards.isEmpty()) {
            return;
        }
        int newIndex = Math.max(0, Math.min(index, cards.size() - 1));
        if (newIndex == selectedIndex) {
            return;
        }
        int oldIndex = selectedIndex;
        selectedIndex = newIndex;
        if (oldIndex >= 0 && oldIndex < cards.size()) {
            cards.get(oldIndex).redraw();
        }
        RecentScriptCard card = cards.get(newIndex);
        card.redraw();
        card.setFocus();
        revealCard(card);
    }

    public void selectFirst() {
        select(0);
    }

    public void selectLast() {
        select(cards.size() - 1);
    }

    /**
     * Scrolls a card into view, so keyboard navigation does not walk off screen.
     */
    public void revealCard(@NotNull Control card) {
        if (!viewport.isDisposed() && stackLayout.topControl == viewport) {
            viewport.showControl(card);
        }
    }

    public void openScript(@NotNull RecentScriptItem item) {
        openHandler.accept(item);
    }

    public void toggleFavorite(@NotNull RecentScriptItem item) {
        favoriteHandler.accept(item);
    }

    @NotNull
    private Control createSeparator() {
        Label separator = new Label(cardContainer, SWT.SEPARATOR | SWT.HORIZONTAL);
        GridData layoutData = new GridData(SWT.FILL, SWT.TOP, true, false);
        layoutData.verticalIndent = 2;
        separator.setLayoutData(layoutData);
        return separator;
    }

    @Override
    public boolean setFocus() {
        if (!cards.isEmpty()) {
            return cards.get(Math.max(selectedIndex, 0)).setFocus();
        }
        return super.setFocus();
    }

    private void onThemeChanged() {
        cardColors.refresh();
        applyThemeColors();
        for (RecentScriptCard card : cards) {
            // Fonts may have changed too, so the cards have to be re-measured, not just redrawn
            card.setBackground(cardColors.background);
            card.redraw();
        }
        cardContainer.layout(true, true);
        updateScrollSize();
    }

    private void applyThemeColors() {
        viewport.setBackground(cardColors.background);
        cardContainer.setBackground(cardColors.background);
        loadingPlaceholder.setBackground(cardColors.background);
        emptyPlaceholder.setBackground(cardColors.background);
        for (Control child : loadingPlaceholder.getChildren()) {
            child.setBackground(cardColors.background);
            child.setForeground(cardColors.description);
        }
        for (Control child : emptyPlaceholder.getChildren()) {
            child.setBackground(cardColors.background);
            child.setForeground(cardColors.description);
        }
    }

    private void updateScrollSize() {
        if (viewport.isDisposed()) {
            return;
        }
        int width = viewport.getClientArea().width;
        Point size = cardContainer.computeSize(width > 0 ? width : SWT.DEFAULT, SWT.DEFAULT);
        cardContainer.setSize(size);
        viewport.setMinSize(size);
    }

    @NotNull
    private Composite createMessagePlaceholder(@NotNull String message) {
        Composite placeholder = new Composite(this, SWT.NONE);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = PANEL_MARGIN * 2;
        layout.marginHeight = PANEL_MARGIN * 2;
        placeholder.setLayout(layout);
        Label label = new Label(placeholder, SWT.WRAP | SWT.CENTER);
        label.setText(message);
        label.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, true));
        return placeholder;
    }
}
