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

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure helpers for the package deploy request (K97). No UI or network code.
 */
public final class DeployRequest {
    private static final Pattern TASK = Pattern.compile("[A-Za-z][A-Za-z0-9_]*-\\d+");
    private static final Pattern UNSAFE_FILE_CHARS = Pattern.compile("[^A-Za-z0-9_.-]");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]*)\"");
    private static final Set<String> DML = Set.of("INSERT", "UPDATE", "DELETE", "MERGE");
    private static final Set<String> DDL = Set.of(
        "CREATE", "ALTER", "DROP", "TRUNCATE", "COMMENT", "GRANT", "REVOKE", "RENAME");

    private DeployRequest() {
    }

    /** Fills {repo}, {task} and {version} with URL-encoded values; a space becomes %20. */
    public static String fillUrl(String template, String repo, String task, String version) {
        return template.replace("{repo}", encode(repo))
            .replace("{task}", encode(task))
            .replace("{version}", encode(version));
    }

    /** Returns the first quoted string of a JSON array, or null when the body is not such an array. */
    public static String parseVersion(String json) {
        String text = json.strip();
        if (!text.startsWith("[")) {
            return null;
        }
        Matcher m = QUOTED.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Repository for a schema: the exact deploy.repo.&lt;schema&gt; first, otherwise the longest
     * deploy.repo.&lt;key&gt; whose key is a prefix of the schema followed by "_". Blank values never match.
     */
    // dbeaver-mm K103: repository mapping also matches a schema name prefix (tenant suffix)
    public static String repoFor(Properties config, String schema) {
        if (schema == null || schema.isEmpty()) {
            return "";
        }
        String exact = config.getProperty("deploy.repo." + schema);
        if (exact != null && !exact.isBlank()) {
            return exact.strip();
        }
        String best = "";
        int bestLength = 0;
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith("deploy.repo.")) {
                continue;
            }
            String name = key.substring("deploy.repo.".length());
            String value = config.getProperty(key);
            if (name.isEmpty() || value == null || value.isBlank()) {
                continue;
            }
            if (schema.startsWith(name + "_") && name.length() > bestLength) {
                best = value.strip();
                bestLength = name.length();
            }
        }
        return best;
    }

    /** All repository choices: deploy.repo.* values and deploy.repos, unique and sorted. */
    public static List<String> repoOptions(Properties config) {
        Set<String> options = new TreeSet<>();
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith("deploy.repo.")) {
                addOption(options, config.getProperty(key));
            }
        }
        String extra = config.getProperty("deploy.repos");
        if (extra != null) {
            for (String part : extra.split(",")) {
                addOption(options, part);
            }
        }
        return List.copyOf(options);
    }

    /** File name for a task and repository, unsafe characters replaced by underscores. */
    public static String fileName(String task, String repo) {
        return UNSAFE_FILE_CHARS.matcher(task + "_" + repo + ".sql").replaceAll("_");
    }

    /** True for a Jira-style task id such as AB-12. */
    public static boolean validTask(String task) {
        return task != null && TASK.matcher(task).matches();
    }

    /** True when the statements contain both DML and DDL. Leading comment lines are skipped. */
    public static boolean mixesDdlAndDml(List<String> statements) {
        boolean dml = false;
        boolean ddl = false;
        for (String statement : statements) {
            String word = firstWord(statement);
            if (DML.contains(word)) {
                dml = true;
            } else if (DDL.contains(word)) {
                ddl = true;
            }
        }
        return dml && ddl;
    }

    /** Multipart body with a "description" field and a "file" field holding the SQL, UTF-8. */
    public static byte[] multipart(String boundary, String description, String fileName, String sql) {
        String delimiter = "--" + boundary + "\r\n";
        String body = delimiter
            + "Content-Disposition: form-data; name=\"description\"\r\n\r\n" + description + "\r\n"
            + delimiter
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
            + "Content-Type: application/sql\r\n\r\n" + sql + "\r\n"
            + "--" + boundary + "--\r\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /** SHA-256 of the UTF-8 text as lower case hex. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void addOption(Set<String> options, String value) {
        if (value != null && !value.isBlank()) {
            options.add(value.strip());
        }
    }

    private static String firstWord(String sql) {
        StringBuilder body = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                body.append(line).append('\n');
            }
        }
        String text = body.toString().strip();
        int end = 0;
        while (end < text.length() && Character.isLetter(text.charAt(end))) {
            end++;
        }
        return text.substring(0, end).toUpperCase(Locale.ROOT);
    }
}
