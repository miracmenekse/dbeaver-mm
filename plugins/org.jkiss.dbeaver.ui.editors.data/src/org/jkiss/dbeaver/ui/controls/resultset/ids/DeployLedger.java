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
package org.jkiss.dbeaver.ui.controls.resultset.ids;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * dbeaver-mm K100: remembers which task, repository and SQL were sent and when.
 * Only a SHA-256 of the SQL is stored, never the SQL text.
 */
public final class DeployLedger {

    /** A record of an earlier send: deploy version and time. */
    public record Sent(String version, String when) {
    }

    private DeployLedger() {
    }

    /** The earlier send of the same task, repository and SQL, or null. Never throws. */
    public static Sent find(Path file, String task, String repo, String sql) {
        try {
            String value = read(file).getProperty(key(task, repo));
            if (value == null) {
                return null;
            }
            String[] parts = value.split("\\|", 3);
            if (parts.length != 3 || !parts[0].equals(DeployRequest.sha256(sql))) {
                return null;
            }
            return new Sent(parts[1], parts[2]);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Stores the send; other records are kept, the same task and repository is replaced. */
    public static void record(Path file, String task, String repo, String sql, String version, String when)
        throws IOException {
        Properties ledger = read(file);
        ledger.setProperty(key(task, repo), DeployRequest.sha256(sql) + "|" + version + "|" + when);
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            ledger.store(writer, "dbeaver-mm deploy ledger");
        }
    }

    private static String key(String task, String repo) {
        return task + "|" + repo;
    }

    /** Missing file gives no records; a broken file is treated as empty. */
    private static Properties read(Path file) throws IOException {
        Properties ledger = new Properties();
        if (!Files.isRegularFile(file)) {
            return ledger;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            ledger.load(reader);
        } catch (IllegalArgumentException e) {
            return new Properties();
        }
        return ledger;
    }
}
