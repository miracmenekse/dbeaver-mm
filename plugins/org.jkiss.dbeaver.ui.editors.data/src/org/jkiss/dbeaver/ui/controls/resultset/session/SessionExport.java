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
package org.jkiss.dbeaver.ui.controls.resultset.session;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Display;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.ui.controls.resultset.conf.ConfPackages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * dbeaver-mm K50: zips a stopped session onto the desktop, laid out like the K45 problem report:
 * the session's .jsonl and, when the session is named after a conf package (task id), that
 * package's SQL as {@code conf/<task>.sql}. Runs off the UI thread; nothing is sent anywhere.
 */
final class SessionExport {

    private SessionExport() {
    }

    static void zip(@Nullable Path session, @Nullable String name, @NotNull Display display) {
        if (session == null || name == null) {
            return;
        }
        Thread thread = new Thread(() -> {
            Path home = Path.of(System.getProperty("user.home"));
            Path folder = Files.isDirectory(home.resolve("Desktop")) ? home.resolve("Desktop") : home;
            Path zip = folder.resolve(session.getFileName().toString().replaceAll("\\.jsonl$", "") + ".zip");
            String message;
            try {
                SessionRecorder.awaitWritten();
                try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
                    out.putNextEntry(new ZipEntry(session.getFileName().toString()));
                    if (Files.isRegularFile(session)) {
                        Files.copy(session, out);
                    }
                    out.closeEntry();
                    String sql = ConfPackages.listTasks().contains(name) ? ConfPackages.read(name) : null;
                    if (sql != null) {
                        out.putNextEntry(new ZipEntry("conf/" + name + ".sql"));
                        out.write(sql.getBytes(StandardCharsets.UTF_8));
                        out.closeEntry();
                    }
                }
                message = "Saved to\n" + zip + "\n\nRecords carry only your nickname, but SQL text and values are kept as typed.";
            } catch (IOException | InterruptedException e) {
                message = "Can't write " + zip + ":\n" + e.getMessage();
            }
            String text = message;
            display.asyncExec(() -> {
                MessageDialog.openInformation(display.getActiveShell(), "Session export", text);
                Program.launch(folder.toString());
            });
        }, "dbeaver-mm session export");
        thread.setDaemon(true);
        thread.start();
    }
}
