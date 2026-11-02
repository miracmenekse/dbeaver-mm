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

import org.eclipse.core.runtime.Platform;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.dbeaver.utils.RuntimeUtils;

import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * dbeaver-mm K39: calls the id API without any script, for machines where bash/PowerShell can't run.
 * Everything user specific (urls, headers, login body with the user's own credentials) is read from
 * {@code ~/.dbeaver-mm/id-api.properties}. Same flow as id-api.sh:
 * the token is kept in {@code ~/.dbeaver-mm/token}; on 401/403 the client logs in again and retries once.
 */
public class IdApiClient {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".dbeaver-mm");
    // K53: the user's own file holds login.user / login.password (and may override anything shared)
    static final Path CONFIG = DIR.resolve("id-api.properties");
    private static final Path TOKEN = DIR.resolve("token");
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    /** K53: the file handed out with the install, next to dbeaver.exe; null when there is none. */
    @Nullable
    static Path sharedConfig() {
        try {
            Path file = RuntimeUtils.getLocalFileFromURL(Platform.getInstallLocation().getURL()).resolve("id-api.properties");
            return Files.isRegularFile(file) ? file : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Shared file first, then the user's own on top of it. */
    @NotNull
    public static Properties load() {
        Properties config = new Properties();
        Path shared = sharedConfig();
        for (Path file : shared == null ? List.of(CONFIG) : List.of(shared, CONFIG)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                config.load(reader);
            } catch (IOException ignored) {
                // missing file: nothing from it
            }
        }
        return config;
    }

    /** dbeaver-mm K99: the token file shared by the ID API and the deploy calls. */
    public static Path tokenFile() {
        return TOKEN;
    }

    /** The handed-out file is in place (it has generate.url). */
    static boolean isConfigured() {
        String url = load().getProperty("generate.url", "");
        return !url.isBlank();
    }

    /** Returns the generate response body (JSON with "ids"). */
    @NotNull
    static String generate(int count, @NotNull String table, @NotNull String domain) throws IOException, InterruptedException {
        Properties config = load();
        String url = ApiAuth.require(config, "generate.url")
            .replace("{count}", String.valueOf(count))
            .replace("{table}", URLEncoder.encode(table, StandardCharsets.UTF_8))
            .replace("{domain}", URLEncoder.encode(domain, StandardCharsets.UTF_8));

        // dbeaver-mm K98: token handling (login, 401/403 retry) is shared in ApiAuth
        HttpResponse<String> response = ApiAuth.send(config, TOKEN, token -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token);
            ApiAuth.addHeaders(config, "generate.header.", request);
            String method = config.getProperty("generate.method", "GET").strip().toUpperCase(Locale.ROOT);
            String body = config.getProperty("generate.body");
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            return request.build();
        }, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("ID API HTTP " + response.statusCode() + "\n" + response.body());
        }
        return response.body();
    }
}
