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
package org.jkiss.dbeaver.ui.controls.resultset;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.MouseAdapter;
import org.eclipse.swt.events.MouseEvent;
import org.eclipse.swt.events.SelectionListener;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.ToolBar;
import org.eclipse.swt.widgets.ToolItem;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.model.DBIcon;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.model.data.DBDDataReceiver;
import org.jkiss.dbeaver.ui.ConComposite;
import org.jkiss.dbeaver.ui.DBeaverIcons;
import org.jkiss.dbeaver.ui.UIIcon;
import org.jkiss.dbeaver.ui.UIStyles;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.controls.resultset.internal.ResultSetMessages;
import org.jkiss.dbeaver.ui.css.CSSUtils;
import org.jkiss.dbeaver.ui.editors.TextEditorUtils;
import org.jkiss.utils.CommonUtils;

import java.util.Collections;
import java.util.List;

/**
 * Status label
 */
class StatusLabel extends ConComposite {

    private final IResultSetController viewer;
    private final Label statusText;
    private DBPMessageType messageType;
    private final ToolItem detailsIcon;

    // dbeaver-mm E1: structured "rows fetched" summary. Three labels with falling contrast -
    // row count (full; warning color when the fetch limit cut the result), duration (medium),
    // end time (lowest). statusText keeps the full plain message for getMessage()/details.
    // Owner-drawn: the CSS engine re-applies styles to labels at arbitrary times (connection color
    // refresh, first show) and overwrote their colors, so all three items came out the same grey.
    // CSS still paints the canvas background (connection color); the text colors are ours.
    private final Canvas fetchSummary;
    private final String[] summaryParts = {"", "", ""};
    private boolean rowsLimited;
    private String timingTooltip;

    // Fixed per theme and measured against the real status bar background (#2F2F2F / white).
    // Deriving them from the label's reported foreground did not work: GTK reports a color that
    // is not what gets painted, and the time ended up at 2.8:1.
    private static final RGB ROWS_DARK = new RGB(230, 230, 230);     // 10.7:1
    private static final RGB ROWS_LIGHT = new RGB(20, 20, 20);       // 18.4:1
    private static final RGB LIMIT_DARK = new RGB(232, 170, 60);     // 6.5:1
    private static final RGB LIMIT_LIGHT = new RGB(160, 96, 0);      // 5.0:1
    private static final RGB DURATION_DARK = new RGB(170, 170, 170); // 5.8:1
    private static final RGB DURATION_LIGHT = new RGB(85, 85, 85);   // 7.5:1
    private static final RGB TIME_DARK = new RGB(138, 138, 138);     // 3.9:1 (dimmed floor 3:1)
    private static final RGB TIME_LIGHT = new RGB(118, 118, 118);    // 4.5:1

    public StatusLabel(@NotNull Composite parent, int style, @Nullable final IResultSetController viewer) {
        super(parent);
        this.viewer = viewer;

        final GridLayout layout = new GridLayout(3, false);
        layout.marginHeight = 0;
        layout.marginWidth = 2;
        layout.horizontalSpacing = 3;
        setLayout(layout);

        final ToolBar tb = new ToolBar(this, SWT.FLAT | SWT.HORIZONTAL);

        detailsIcon = new ToolItem(tb, SWT.NONE);
        detailsIcon.setImage(DBeaverIcons.getImage(UIIcon.DOTS_BUTTON));
        tb.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_BEGINNING));

        detailsIcon.addSelectionListener(SelectionListener.widgetSelectedAdapter(e -> showDetails()));

        statusText = new Label(this, SWT.NONE);
        CSSUtils.markConnectionTypeColor(statusText);
        GridData gd = new GridData(GridData.FILL_HORIZONTAL);
        statusText.setLayoutData(gd);
        statusText.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                showDetails();
            }
        });

        fetchSummary = new Canvas(this, SWT.NONE) {
            @Override
            public Point computeSize(int wHint, int hHint, boolean changed) {
                GC gc = new GC(this);
                try {
                    gc.setFont(getFont());
                    int width = 0;
                    for (String part : summaryParts) {
                        if (!part.isEmpty()) {
                            width += (width > 0 ? getSummaryGap(gc) : 0) + gc.textExtent(part).x;
                        }
                    }
                    return new Point(Math.max(width, 1), gc.getFontMetrics().getHeight());
                } finally {
                    gc.dispose();
                }
            }
        };
        CSSUtils.markConnectionTypeColor(fetchSummary);
        GridData summaryData = new GridData(GridData.FILL_HORIZONTAL);
        summaryData.exclude = true;
        fetchSummary.setLayoutData(summaryData);
        fetchSummary.setVisible(false);
        fetchSummary.addPaintListener(e -> paintFetchSummary(e.gc));
        fetchSummary.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseDoubleClick(MouseEvent e) {
                showDetails();
            }
        });

        if (viewer != null) {
            TextEditorUtils.enableHostEditorKeyBindingsSupport(viewer.getSite(), this.statusText);
            UIUtils.addDefaultEditActionsSupport(viewer.getSite(), this.statusText);
        }
    }

    /**
     * dbeaver-mm E1: shows a successful fetch as separate row count / duration / time items.
     *
     * @param plainMessage the classic one-line message, kept for {@link #getMessage()} and details
     * @param limited      true if the fetch size cut the result ("200+"), drawn in a warning color
     */
    void setFetchSummary(
        @NotNull String plainMessage,
        @NotNull String rows,
        boolean limited,
        @NotNull String duration,
        @NotNull String time
    ) {
        if (statusText.isDisposed()) {
            return;
        }
        setStatus(plainMessage, DBPMessageType.INFORMATION);

        summaryParts[0] = rows;
        summaryParts[1] = duration;
        summaryParts[2] = time;
        rowsLimited = limited;
        updateSummaryTooltip();

        showFetchSummary(true);
        fetchSummary.redraw();
    }

    private void paintFetchSummary(@NotNull GC gc) {
        // Colors come from UIUtils.getSharedColor (interned), nothing is allocated while painting
        boolean dark = UIStyles.isDarkTheme();
        RGB[] colors = {
            rowsLimited ? (dark ? LIMIT_DARK : LIMIT_LIGHT) : (dark ? ROWS_DARK : ROWS_LIGHT),
            dark ? DURATION_DARK : DURATION_LIGHT,
            dark ? TIME_DARK : TIME_LIGHT
        };
        gc.setFont(fetchSummary.getFont());
        int gap = getSummaryGap(gc);
        int y = Math.max(0, (fetchSummary.getSize().y - gc.getFontMetrics().getHeight()) / 2);
        int x = 0;
        for (int i = 0; i < summaryParts.length; i++) {
            if (summaryParts[i].isEmpty()) {
                continue;
            }
            gc.setForeground(UIUtils.getSharedColor(colors[i]));
            gc.drawString(summaryParts[i], x, y, true);
            x += gc.textExtent(summaryParts[i]).x + gap;
        }
    }

    private static int getSummaryGap(@NotNull GC gc) {
        return gc.getFontMetrics().getHeight();
    }

    private void updateSummaryTooltip() {
        String tooltip = timingTooltip;
        if (rowsLimited) {
            tooltip = ResultSetMessages.controls_resultset_viewer_status_rows_limited_tip +
                (CommonUtils.isEmpty(timingTooltip) ? "" : "\n\n" + timingTooltip);
        }
        fetchSummary.setToolTipText(tooltip);
    }

    private void showFetchSummary(boolean show) {
        if (((GridData) fetchSummary.getLayoutData()).exclude != !show) {
            ((GridData) fetchSummary.getLayoutData()).exclude = !show;
            ((GridData) statusText.getLayoutData()).exclude = show;
            fetchSummary.setVisible(show);
            statusText.setVisible(!show);
            layout(true, true);
        }
    }

    protected void showDetails() {
        DBDDataReceiver dataReceiver = viewer.getDataReceiver();
        if (dataReceiver instanceof ResultSetDataReceiver rsdr) {
            List<Throwable> errorList = rsdr.getErrorList();
            if (errorList.isEmpty()) {
                if (viewer.getModel().getStatistics() != null && viewer.getModel().getStatistics().getError() != null) {
                    errorList = Collections.singletonList(viewer.getModel().getStatistics().getError());
                }
            }
            StatusDetailsDialog dialog = new StatusDetailsDialog(
                viewer.getSite().getShell(),
                getMessage(),
                errorList
            );
            dialog.open();
        }
    }

    public void setStatus(String message) {
        this.setStatus(message, DBPMessageType.INFORMATION);
    }

    public void setStatusTooltip(String message) {
        this.statusText.setToolTipText(message);
        this.timingTooltip = message;
        updateSummaryTooltip();
    }

    public void setStatus(String message, DBPMessageType messageType) {
        if (statusText.isDisposed()) {
            return;
        }
        this.messageType = messageType;

        DBIcon statusIcon = switch (messageType) {
            case ERROR -> DBIcon.SMALL_ERROR;
            case WARNING -> DBIcon.SMALL_WARNING;
            default -> null;
        };

        if (message == null) {
            message = "???"; //$NON-NLS-1$
        }
        if (statusIcon != null) {
            detailsIcon.setImage(DBeaverIcons.getImage(statusIcon));
        } else {
            detailsIcon.setImage(DBeaverIcons.getImage(UIIcon.DOTS_BUTTON));
        }
        statusText.setText(CommonUtils.getSingleLineString(message));
        showFetchSummary(false);
        if (messageType != DBPMessageType.INFORMATION) {
            statusText.setToolTipText(message);
        } else {
            statusText.setToolTipText(null);
        }
    }

    public String getMessage() {
        return statusText.getText();
    }

    public DBPMessageType getMessageType() {
        return messageType;
    }

    public void setUpdateListener(Runnable runnable) {

    }
}
