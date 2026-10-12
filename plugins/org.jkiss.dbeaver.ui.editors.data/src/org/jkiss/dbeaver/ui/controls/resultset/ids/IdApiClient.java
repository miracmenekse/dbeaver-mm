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

import org.jkiss.code.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * dbeaver-mm K39: calls the id API without any script, for machines where bash/PowerShell can't run.
 * Everything user specific (urls, headers, login body with the user's own credentials) is read from
 * {@code ~/.dbeaver-mm/id-api.properties}, template in docs/id-api.properties. Same flow as id-api.sh:
 * the token is kept in {@code ~/.dbeaver-mm/token}; on 401/403 the client logs in again and retries once.
 */
class IdApiClient {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".dbeaver-mm");
    static final Path CONFIG = DIR.resolve("id-api.properties");
    private static final Path TOKEN = DIR.resolve("token");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Returns the generate response body (JSON with "ids"). */
    @NotNull
    static String generate(int count, @NotNull String table, @NotNull String domain) throws IOException, InterruptedException {
        Properties config = new Properties();
        try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8)) {
            config.load(reader);
        }
        String url = require(config, "generate.url")
            .replace("{count}", String.valueOf(count))
            .replace("{table}", URLEncoder.encode(table, StandardCharsets.UTF_8))
            .replace("{domain}", URLEncoder.encode(domain, StandardCharsets.UTF_8));

        if (!Files.isRegularFile(TOKEN) || Files.size(TOKEN) == 0) {
            login(config);
        }
        HttpResponse<String> response = call(config, url);
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            login(config);
            response = call(config, url);
        }
        if (response.statusCode() != 200) {
            throw new IOException("ID API HTTP " + response.statusCode() + "\n" + response.body());
        }
        return response.body();
    }

    @NotNull
    private static HttpResponse<String> call(@NotNull Properties config, @NotNull String url) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
            .header("Authorization", "Bearer " + Files.readString(TOKEN).strip());
        addHeaders(config, "generate.header.", request);
        String method = config.getProperty("generate.method", "GET").strip().toUpperCase();
        String body = config.getProperty("generate.body");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void login(@NotNull Properties config) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(require(config, "login.url"))).timeout(TIMEOUT)
            .header("Content-Type", config.getProperty("login.contentType", "application/x-www-form-urlencoded").strip())
            .POST(HttpRequest.BodyPublishers.ofString(require(config, "login.body")));
        addHeaders(config, "login.header.", request);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String field = config.getProperty("token.field", "access_token").strip();
        String token = response.statusCode() == 200 ? jsonString(response.body(), field) : null;
        if (token == null) {
            throw new IOException("Login failed (HTTP " + response.statusCode() + "), check login.* in " + CONFIG + "\n" + response.body());
        }
        Files.writeString(TOKEN, token);
    }

    private static void addHeaders(@NotNull Properties config, @NotNull String prefix, @NotNull HttpRequest.Builder request) {
        for (String key : config.stringPropertyNames()) {
            if (key.startsWith(prefix)) {
                request.header(key.substring(prefix.length()), config.getProperty(key).strip());
            }
        }
    }

    @NotNull
    private static String require(@NotNull Properties config, @NotNull String key) throws IOException {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException(key + " is missing in " + CONFIG);
        }
        return value.strip();
    }

    /** {@code {"access_token":"abc",...}} -> abc; null when the field isn't there. */
    static String jsonString(@NotNull String json, @NotNull String field) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }
}
