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

import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IStartup;
import org.eclipse.ui.PlatformUI;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.DBPMessageType;
import org.jkiss.dbeaver.ui.notifications.NotificationUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * dbeaver-mm K43: UI freeze recorder. A daemon thread posts an empty task to the UI thread every
 * 250 ms; when the UI thread does not run it for 1 s it takes the UI thread's stack once a second,
 * and when the UI answers again the freeze (length + stacks) is appended to
 * {@code ~/.dbeaver-mm/donma.log}. Freezes of 2 s or more also show a notification afterwards
 * (nothing can be drawn during the freeze itself); it can be turned off in
 * Preferences > User Interface > Notifications ("UI freeze"). Cost while the UI is responsive:
 * one no-op runnable per 250 ms.
 */
public class UiFreezeWatchdog implements IStartup {

    private static final Log log = Log.getLog(UiFreezeWatchdog.class);

    static final String NOTIFICATION_ID = "mm.ui.freeze";
    private static final long TICK_MS = 250;
    private static final long FREEZE_MS = 1000;
    private static final long NOTIFY_MS = 2000;
    private static final int MAX_SAMPLES = 10;
    private static final int MAX_FRAMES = 60;
    // ponytail: one previous file kept, older freezes are dropped
    private static final long MAX_LOG_BYTES = 2L << 20;
    private static final Path LOG_FILE = Path.of(System.getProperty("user.home"), ".dbeaver-mm", "donma.log");

    private volatile long answeredAt;

    @Override
    public void earlyStartup() {
        Display display = PlatformUI.getWorkbench().getDisplay();
        Thread thread = new Thread(() -> watch(display), "dbeaver-mm UI freeze watchdog");
        thread.setDaemon(true);
        thread.start();
        display.asyncExec(() -> BackgroundJobIndicator.install(display));
    }

    private void watch(Display display) {
        Thread uiThread = display.getThread();
        long sentAt = 0;
        long lastTick = System.currentTimeMillis();
        long lastSample = 0;
        List<String> samples = new ArrayList<>();
        while (!display.isDisposed()) {
            try {
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastTick > 2 * FREEZE_MS) {
                // The machine slept (suspend, debugger pause): not a UI freeze
                sentAt = 0;
                samples.clear();
            }
            lastTick = now;
            if (sentAt != 0 && answeredAt >= sentAt) {
                if (!samples.isEmpty()) {
                    report(display, answeredAt - sentAt, samples);
                    samples = new ArrayList<>();
                }
                sentAt = 0;
            }
            if (sentAt == 0) {
                sentAt = now;
                try {
                    display.asyncExec(() -> answeredAt = System.currentTimeMillis());
                } catch (Exception e) {
                    return; // display disposed between the check and the call
                }
            } else if (now - sentAt >= FREEZE_MS && now - lastSample >= FREEZE_MS && samples.size() < MAX_SAMPLES) {
                samples.add("-- +" + (now - sentAt) + " ms\n" + stackOf(uiThread));
                lastSample = now;
            }
        }
    }

    private static String stackOf(Thread thread) {
        StringBuilder text = new StringBuilder();
        StackTraceElement[] frames = thread.getStackTrace();
        for (int i = 0; i < Math.min(frames.length, MAX_FRAMES); i++) {
            text.append("    at ").append(frames[i]).append('\n');
        }
        return text.toString();
    }

    private static void report(Display display, long millis, List<String> samples) {
        String time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String entry = "=== " + time + "  UI frozen " + millis + " ms\n" + String.join("", samples) + "\n";
        try {
            Files.createDirectories(LOG_FILE.getParent());
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_LOG_BYTES) {
                Files.move(LOG_FILE, LOG_FILE.resolveSibling("donma.log.1"), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(LOG_FILE, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.debug("Can't write " + LOG_FILE, e);
        }
        if (millis >= NOTIFY_MS) {
            String text = String.format("The window did not respond for %.1f s. Details: %s", millis / 1000.0, LOG_FILE);
            display.asyncExec(() -> NotificationUtils.sendNotification(
                NOTIFICATION_ID, "UI freeze", text, DBPMessageType.INFORMATION, null));
        }
    }
}
