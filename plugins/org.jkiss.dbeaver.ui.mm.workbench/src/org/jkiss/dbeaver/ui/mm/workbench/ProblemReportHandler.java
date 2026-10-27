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

import org.eclipse.core.commands.AbstractHandler;
import org.eclipse.core.commands.ExecutionEvent;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.program.Program;
import org.eclipse.ui.handlers.HandlerUtil;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.LogMask;
import org.jkiss.dbeaver.model.app.DBPProject;
import org.jkiss.dbeaver.runtime.DBWorkbench;
import org.jkiss.dbeaver.utils.GeneralUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * dbeaver-mm K45: Plus > Create problem report. Zips what is needed to look into an error or a
 * freeze on someone else's machine onto their desktop: version / OS / Java / memory, the freeze log
 * (K43, {@code ~/.dbeaver-mm/donma.log}), the Eclipse error log and the DBeaver debug logs of the
 * last sessions (errors and their stack traces). Nothing is sent anywhere; the user passes the file on.
 */
public class ProblemReportHandler extends AbstractHandler {

    private static final Log log = Log.getLog(ProblemReportHandler.class); // dbeaver-mm K85

    private static final int PREVIOUS_SESSIONS = 3;

    @Override
    public Object execute(ExecutionEvent event) {
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        Path home = Path.of(System.getProperty("user.home"));
        Path folder = Files.isDirectory(home.resolve("Desktop")) ? home.resolve("Desktop") : home;
        Path zip = folder.resolve("dbeaver-mm-report-" + stamp + ".zip");
        registerSecrets();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            out.putNextEntry(new ZipEntry("info.txt"));
            out.write(info().getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
            Path freezes = home.resolve(".dbeaver-mm");
            for (String name : List.of("donma.log", "donma.log.1")) {
                add(out, freezes.resolve(name));
            }
            for (Path log : workspaceLogs()) {
                add(out, log);
            }
        } catch (IOException e) {
            MessageDialog.openError(HandlerUtil.getActiveShell(event), "Problem report", "Can't write " + zip + ":\n" + e.getMessage());
            return null;
        }
        MessageDialog.openInformation(HandlerUtil.getActiveShell(event), "Problem report",
            "Saved to\n" + zip + "\n\nSend this file to whoever looks into the problem. "
                + "It can contain SQL text, table names and server addresses from the logs.");
        Program.launch(folder.toString());
        return null;
    }

    private static String info() {
        Runtime runtime = Runtime.getRuntime();
        return "Product: " + GeneralUtils.getProductTitle() + " (" + GeneralUtils.getProductVersion() + ")\n"
            + "Built: " + GeneralUtils.getProductBuildTime() + "\n"
            + "OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\n"
            + "Java: " + System.getProperty("java.vendor") + " " + System.getProperty("java.version") + "\n"
            + "CPUs: " + runtime.availableProcessors() + "\n"
            + "Heap: used " + (runtime.totalMemory() - runtime.freeMemory()) / (1 << 20) + " MB, max " + runtime.maxMemory() / (1 << 20) + " MB\n"
            + "Report time: " + LocalDateTime.now() + "\n";
    }

    /** {@code .metadata/.log} (Eclipse errors) and this plus the previous sessions' {@code dbeaver-debug*.log}. */
    private static List<Path> workspaceLogs() throws IOException {
        List<Path> result = new ArrayList<>();
        Path metadata = Platform.getLogFileLocation().toFile().toPath().getParent();
        result.add(metadata.resolve(".log"));
        try (Stream<Path> files = Files.list(metadata)) {
            files.filter(p -> p.getFileName().toString().matches("dbeaver-debug.*\\.log"))
                .sorted(Comparator.comparing((Path p) -> p.toFile().lastModified()).reversed())
                .limit(1 + PREVIOUS_SESSIONS)
                .forEach(result::add);
        }
        return result;
    }

    private static void add(ZipOutputStream out, Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return;
        }
        out.putNextEntry(new ZipEntry(file.getFileName().toString()));
        // dbeaver-mm K85: lines are masked (aliases instead of connection names, users, hosts, passwords).
        // InputStreamReader replaces undecodable bytes instead of throwing, so a binary file can't break the report.
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                out.write((LogMask.mask(line) + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        out.closeEntry();
    }

    /** dbeaver-mm K85: loading every project's connections registers their names, hosts and users for masking. */
    private static void registerSecrets() {
        try {
            for (DBPProject project : DBWorkbench.getPlatform().getWorkspace().getProjects()) {
                project.getDataSourceRegistry().getDataSources();
            }
        } catch (Throwable e) {
            log.warn("Can't load connections for masking the problem report", e);
        }
    }
}
