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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.model.data.json.JSONUtils;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * dbeaver-mm K47: records what the user does, to learn the configuration steps behind each kind of work.
 * Light events (table opened, searched in, DML/DDL, conf package changes, fetched IDs) always go to
 * {@code ~/.dbeaver-mm/kayit/surekli.jsonl} (rotated at 2 MB). While the user runs a named session,
 * those and every other step also go to {@code ~/.dbeaver-mm/kayit/<yyyyMMdd-HHmm>-<name>.jsonl}.
 * Callers only queue a line: no I/O and no metadata reads on the caller's thread (test/perf/RAPOR.md B1/B3);
 * one daemon thread writes.
 * K49: nothing is recorded unless the user agreed ({@link #isEnabled()}); lines carry only the
 * nickname the user picked, never the OS user name.
 */
public final class SessionRecorder {

    private static final Log log = Log.getLog(SessionRecorder.class);

    public static final Path FOLDER = Path.of(System.getProperty("user.home"), ".dbeaver-mm", "kayit");
    private static final Path ALWAYS = FOLDER.resolve("surekli.jsonl");
    private static final long MAX_ALWAYS_BYTES = 2L << 20;
    private static final int MAX_DETAIL = 4000;
    private static final Path SETTINGS = FOLDER.resolveSibling("kayit.properties");

    private static volatile boolean enabled;
    @Nullable
    private static volatile String nickname;

    private record Line(@NotNull String json, boolean light, @Nullable Path session) {
    }

    private static final BlockingQueue<Line> QUEUE = new LinkedBlockingQueue<>(10_000);
    private static final AtomicInteger DROPPED = new AtomicInteger();

    @Nullable
    private static volatile Path sessionFile;
    @Nullable
    private static volatile String sessionName;
    // last queued event, so repaints / refreshes of the same thing are written once
    private static String last = "";

    static {
        Properties props = new Properties();
        try (Reader in = Files.newBufferedReader(SETTINGS, StandardCharsets.UTF_8)) {
            props.load(in);
        } catch (IOException ignored) {
            // not asked yet
        }
        nickname = props.getProperty("nickname");
        enabled = nickname != null && Boolean.parseBoolean(props.getProperty("enabled"));
        Thread writer = new Thread(SessionRecorder::write, "dbeaver-mm session writer");
        writer.setDaemon(true);
        writer.start();
    }

    private SessionRecorder() {
    }

    /** The user was asked at least once (a nickname is saved). */
    public static boolean isAsked() {
        return nickname != null;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    @Nullable
    public static String nickname() {
        return nickname;
    }

    /** Saves the user's choice; turning recording off also stops a running session. */
    public static synchronized void configure(@NotNull String nick, boolean on) {
        if (!on) {
            stop();
        }
        nickname = nick.strip();
        enabled = on;
        Properties props = new Properties();
        props.setProperty("nickname", nickname);
        props.setProperty("enabled", Boolean.toString(on));
        try {
            Files.createDirectories(SETTINGS.getParent());
            try (Writer out = Files.newBufferedWriter(SETTINGS, StandardCharsets.UTF_8)) {
                props.store(out, "dbeaver-mm session recording consent");
            }
        } catch (IOException e) {
            log.debug("Can't save recording settings: " + e.getMessage());
        }
    }

    public static boolean isRecording() {
        return sessionFile != null;
    }

    @Nullable
    public static String sessionName() {
        return sessionName;
    }

    /** Always recorded. */
    public static void light(@NotNull String type, @Nullable String conn, @Nullable String target, @Nullable String detail) {
        queue(type, conn, target, detail, true);
    }

    /** Recorded only inside a named session. */
    public static void step(@NotNull String type, @Nullable String conn, @Nullable String target, @Nullable String detail) {
        if (sessionFile != null) {
            queue(type, conn, target, detail, false);
        }
    }

    @NotNull
    public static synchronized Path start(@NotNull String name) {
        if (!enabled) {
            throw new IllegalStateException("Recording is off");
        }
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        sessionFile = FOLDER.resolve(stamp + "-" + name.strip().replaceAll("[^\\p{L}\\p{N}_.-]", "_") + ".jsonl");
        sessionName = name.strip();
        light("oturum_basla", null, sessionName, null);
        return sessionFile;
    }

    @Nullable
    public static synchronized Path stop() {
        Path file = sessionFile;
        if (file != null) {
            light("oturum_bitir", null, sessionName, null);
            sessionFile = null;
            sessionName = null;
        }
        return file;
    }

    /** Waits (off the UI thread) until queued lines are on disk, so a just-stopped session can be zipped. */
    public static void awaitWritten() throws InterruptedException {
        // ponytail: polls the queue, plus one pause for the batch already taken; a real flush latch if lines go missing
        for (int i = 0; i < 50 && !QUEUE.isEmpty(); i++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
    }

    private static void queue(String type, String conn, String target, String detail, boolean light) {
        if (!enabled) {
            return;
        }
        String event = type + '\0' + conn + '\0' + target + '\0' + detail;
        Path session;
        String name;
        synchronized (SessionRecorder.class) {
            if (event.equals(last)) {
                return;
            }
            last = event;
            session = sessionFile;
            name = sessionName;
        }
        if (detail != null && detail.length() > MAX_DETAIL) {
            detail = detail.substring(0, MAX_DETAIL) + "...";
        }
        String json = "{\"t\":" + str(LocalDateTime.now().toString()) + ",\"user\":" + str(nickname) + ",\"session\":" + str(name) + ",\"type\":" + str(type) +
            ",\"conn\":" + str(conn) + ",\"target\":" + str(target) + ",\"detail\":" + str(detail) + "}\n";
        // dbeaver-mm K85: registered secrets (connection names, hosts, users...) are masked in records
        if (!QUEUE.offer(new Line(org.jkiss.dbeaver.LogMask.mask(json), light, session))) {
            DROPPED.incrementAndGet();
        }
    }

    private static String str(@Nullable String value) {
        return value == null ? "null" : '"' + JSONUtils.escapeJsonString(value) + '"';
    }

    private static void write() {
        List<Line> batch = new ArrayList<>();
        while (true) {
            try {
                batch.add(QUEUE.take());
            } catch (InterruptedException e) {
                return;
            }
            QUEUE.drainTo(batch);
            StringBuilder always = new StringBuilder();
            Map<Path, StringBuilder> sessions = new LinkedHashMap<>();
            int dropped = DROPPED.getAndSet(0);
            if (dropped > 0) {
                always.append("{\"t\":").append(str(LocalDateTime.now().toString()))
                    .append(",\"type\":\"dusen\",\"detail\":\"").append(dropped).append(" events dropped\"}\n");
            }
            for (Line line : batch) {
                if (line.light) {
                    always.append(line.json);
                }
                if (line.session != null) {
                    sessions.computeIfAbsent(line.session, p -> new StringBuilder()).append(line.json);
                }
            }
            batch.clear();
            try {
                Files.createDirectories(FOLDER);
                if (!always.isEmpty()) {
                    if (Files.exists(ALWAYS) && Files.size(ALWAYS) > MAX_ALWAYS_BYTES) {
                        Files.move(ALWAYS, ALWAYS.resolveSibling("surekli.jsonl.1"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    append(ALWAYS, always);
                }
                for (Map.Entry<Path, StringBuilder> entry : sessions.entrySet()) {
                    append(entry.getKey(), entry.getValue());
                }
            } catch (IOException e) {
                log.debug("Can't write session log: " + e.getMessage());
            }
        }
    }

    private static void append(Path file, CharSequence text) throws IOException {
        Files.writeString(file, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
