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
package org.jkiss.dbeaver.ui.mm.workbench;

import org.eclipse.core.runtime.jobs.IJobChangeEvent;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.core.runtime.jobs.JobChangeAdapter;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.PlatformUI;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dbeaver-mm K43: the status bar (and its progress indicator) is hidden since Öneri 3/B, so a slow
 * background job (connect, metadata read, query) looked like a freeze. This shows a small
 * "Working: <job>" label in the window's bottom-right corner while a user job has been running
 * for more than a second, and hides it when none is left.
 */
final class BackgroundJobIndicator {

    private static final int SHOW_AFTER_MS = 1000;

    private final Display display;
    // Job -> start time; touched on the UI thread only
    private final Map<Job, Long> running = new LinkedHashMap<>();
    private Shell popup;
    private Label label;

    private BackgroundJobIndicator(Display display) {
        this.display = display;
    }

    static void install(Display display) {
        BackgroundJobIndicator indicator = new BackgroundJobIndicator(display);
        Job.getJobManager().addJobChangeListener(new JobChangeAdapter() {
            @Override
            public void running(IJobChangeEvent event) {
                Job job = event.getJob();
                if (!job.isSystem()) {
                    indicator.post(() -> {
                        indicator.running.put(job, System.currentTimeMillis());
                        display.timerExec(SHOW_AFTER_MS, indicator::refresh);
                    });
                }
            }

            @Override
            public void done(IJobChangeEvent event) {
                Job job = event.getJob();
                if (!job.isSystem()) {
                    indicator.post(() -> {
                        indicator.running.remove(job);
                        indicator.refresh();
                    });
                }
            }
        });
    }

    private void post(Runnable runnable) {
        if (!display.isDisposed()) {
            display.asyncExec(runnable);
        }
    }

    private void refresh() {
        long now = System.currentTimeMillis();
        running.keySet().removeIf(job -> job.getState() != Job.RUNNING);
        List<Job> slow = running.entrySet().stream().filter(e -> now - e.getValue() >= SHOW_AFTER_MS).map(Map.Entry::getKey).toList();
        IWorkbenchWindow window = PlatformUI.getWorkbench().getActiveWorkbenchWindow();
        Shell parent = window == null ? null : window.getShell();
        if (slow.isEmpty() || parent == null || parent.getMinimized()) {
            if (popup != null && !popup.isDisposed()) {
                popup.setVisible(false);
            }
            return;
        }
        if (popup == null || popup.isDisposed() || popup.getParent() != parent) {
            popup = new Shell(parent, SWT.NO_TRIM | SWT.TOOL | SWT.NO_FOCUS);
            popup.setLayout(new FillLayout());
            popup.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
            label = new Label(popup, SWT.NONE);
            label.setBackground(display.getSystemColor(SWT.COLOR_INFO_BACKGROUND));
            label.setForeground(display.getSystemColor(SWT.COLOR_INFO_FOREGROUND));
        }
        String more = slow.size() > 1 ? "  (+" + (slow.size() - 1) + ")" : "";
        label.setText("  Working: " + slow.getFirst().getName() + more + "  ");
        Point size = popup.computeSize(SWT.DEFAULT, SWT.DEFAULT);
        Rectangle area = parent.getBounds();
        popup.setBounds(area.x + area.width - size.x - 24, area.y + area.height - size.y - 12, size.x, size.y);
        if (!popup.isVisible()) {
            popup.setVisible(true);
        }
    }
}
