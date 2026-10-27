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
package org.jkiss.dbeaver;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K83: replaces secrets (connection names, users, hosts, passwords, IPs, e-mails)
 * in log text with stable per-session aliases such as user#1. Pure Java, thread-safe.
 */
public final class LogMask {

    private static final Pattern PASSWORD = Pattern.compile(
        "(password|passwd|pwd)(\\s*[=:]\\s*)[^\\s&;,'\"]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern IPV4 = Pattern.compile("(?<![\\d.])(\\d{1,3}\\.){3}\\d{1,3}(?![\\d.])");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");

    private record Secret(Pattern pattern, String alias, int length) {
    }

    // key: lower-cased (Locale.ROOT) stripped value
    private static final Map<String, Secret> secrets = new HashMap<>();
    private static final Map<String, Integer> kindCounters = new HashMap<>();
    private static final Map<String, String> ips = new HashMap<>();
    private static final Map<String, String> emails = new HashMap<>();
    private static List<Secret> ordered = List.of();

    private LogMask() {
    }

    public static synchronized void register(String kind, String secret) {
        if (secret == null) {
            return;
        }
        String value = secret.strip();
        if (value.length() < 3) {
            return;
        }
        String key = value.toLowerCase(Locale.ROOT);
        if (secrets.containsKey(key)) {
            return;
        }
        String alias;
        if ("password".equals(kind)) {
            alias = "***";
        } else {
            int n = kindCounters.merge(kind, 1, Integer::sum);
            alias = kind + "#" + n;
        }
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(value) + "(?![\\p{L}\\p{N}])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        secrets.put(key, new Secret(p, alias, value.length()));
        List<Secret> list = new ArrayList<>(secrets.values());
        list.sort((a, b) -> Integer.compare(b.length, a.length));
        ordered = list;
    }

    public static synchronized String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String s = text;
        for (Secret secret : ordered) {
            s = secret.pattern.matcher(s).replaceAll(Matcher.quoteReplacement(secret.alias));
        }
        s = PASSWORD.matcher(s).replaceAll("$1$2***");
        s = IPV4.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
            ips.computeIfAbsent(m.group(), k -> "ip#" + (ips.size() + 1))));
        s = EMAIL.matcher(s).replaceAll(m -> Matcher.quoteReplacement(
            emails.computeIfAbsent(m.group().toLowerCase(Locale.ROOT), k -> "email#" + (emails.size() + 1))));
        return s;
    }

    /**
     * Buffers bytes up to '\n' and writes each line masked. flush() never writes a partial line
     * (PrintStream autoflush would otherwise split a secret in two); close() writes the rest.
     */
    public static OutputStream lineMasking(OutputStream out) {
        return new OutputStream() {
            private final ByteArrayOutputStream line = new ByteArrayOutputStream();

            @Override
            public synchronized void write(int b) throws IOException {
                if (b == '\n') {
                    writeLine();
                    out.write('\n');
                } else {
                    line.write(b);
                }
            }

            @Override
            public synchronized void write(byte[] b, int off, int len) throws IOException {
                for (int i = off; i < off + len; i++) {
                    write(b[i]);
                }
            }

            private void writeLine() throws IOException {
                out.write(mask(line.toString(StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8));
                line.reset();
            }

            @Override
            public synchronized void flush() throws IOException {
                out.flush();
            }

            @Override
            public synchronized void close() throws IOException {
                if (line.size() > 0) {
                    writeLine();
                }
                out.close();
            }
        };
    }
}
