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
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K98: shared token handling for the ID API and the deploy service.
 * The token is kept in the given file; on 401/403 the client logs in again and retries once.
 */
public final class ApiAuth {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private static final Set<String> RESTRICTED_HEADERS = Set.of("connection", "content-length", "expect", "host", "upgrade");

    private ApiAuth() {
    }

    /**
     * Sends the request built from the current token. Logs in first when there is no token,
     * and on 401/403 logs in once more and retries. The function gets the trimmed token.
     */
    public static <T> HttpResponse<T> send(Properties config, Path tokenFile,
                                           Function<String, HttpRequest> request,
                                           HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        if (!Files.isRegularFile(tokenFile) || Files.size(tokenFile) == 0) {
            login(config, tokenFile);
        }
        HttpResponse<T> response = HTTP.send(request.apply(Files.readString(tokenFile).strip()), handler);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            login(config, tokenFile);
            response = HTTP.send(request.apply(Files.readString(tokenFile).strip()), handler);
        }
        return response;
    }

    /** Login body with {user} and {password} replaced; JSON bodies get escaped values. */
    public static String loginBody(Properties config) throws IOException {
        boolean json = config.getProperty("login.contentType", "").contains("json");
        String body = require(config, "login.body");
        for (String name : new String[]{"user", "password"}) {
            String value = config.getProperty("login." + name, "");
            body = body.replace("{" + name + "}", json ? escapeJson(value) : URLEncoder.encode(value, StandardCharsets.UTF_8));
        }
        return body;
    }

    /** Value of a top level string field: {"access_token":"abc"} gives abc, null when missing. */
    public static String jsonString(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Required, non-blank property; the error names the config file. */
    public static String require(Properties config, String key) throws IOException {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(key + " is missing in id-api.properties (next to dbeaver.exe or in "
                + Path.of(System.getProperty("user.home"), ".dbeaver-mm") + ")");
        }
        return value.strip();
    }

    /** Adds the headers of the prefix (prefix + name = value); restricted and skipped names are left out. */
    public static void addHeaders(Properties config, String prefix, HttpRequest.Builder request, String... skip) {
        for (String key : config.stringPropertyNames()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String name = key.substring(prefix.length());
            // dbeaver-mm K56: Java's HttpClient sets these itself and throws on them (copied from browser requests)
            if (RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT)) || isSkipped(name, skip)) {
                continue;
            }
            request.header(name, config.getProperty(key).strip());
        }
    }

    private static boolean isSkipped(String name, String... skip) {
        for (String s : skip) {
            if (s.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static void login(Properties config, Path tokenFile) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(require(config, "login.url"))).timeout(TIMEOUT)
            .header("Content-Type", config.getProperty("login.contentType", "application/x-www-form-urlencoded").strip())
            .POST(HttpRequest.BodyPublishers.ofString(loginBody(config)));
        addHeaders(config, "login.header.", request);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String field = config.getProperty("token.field", "access_token").strip();
        String token = response.statusCode() == 200 ? jsonString(response.body(), field) : null;
        if (token == null) {
            throw new IOException("Login failed (HTTP " + response.statusCode() + "), check user name / password under Plus Settings > ID API account" + "\n" + response.body());
        }
        Files.writeString(tokenFile, token);
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
