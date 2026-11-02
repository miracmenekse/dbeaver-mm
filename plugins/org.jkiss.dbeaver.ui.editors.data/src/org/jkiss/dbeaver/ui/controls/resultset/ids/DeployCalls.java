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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

/**
 * dbeaver-mm K99: the version and deploy calls of the package deploy. Settings come from
 * the properties (deploy.version.url, deploy.upload.url and their header prefixes).
 */
public final class DeployCalls {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Duration UPLOAD_TIMEOUT = Duration.ofSeconds(60);
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    /** Status and body of a deploy call; an HTTP error is a result, not an exception. */
    public record Result(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private DeployCalls() {
    }

    /** Both the version and the upload address are set. */
    public static boolean isConfigured(Properties config) {
        return !config.getProperty("deploy.version.url", "").isBlank()
            && !config.getProperty("deploy.upload.url", "").isBlank();
    }

    /** Fetches the version with a plain GET (no token). */
    public static String fetchVersion(Properties config) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(ApiAuth.require(config, "deploy.version.url")))
            .timeout(TIMEOUT)
            .GET();
        ApiAuth.addHeaders(config, "deploy.version.header.", request);
        HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Version request failed: HTTP " + response.statusCode() + "\n" + response.body());
        }
        String version = DeployRequest.parseVersion(response.body());
        if (version == null) {
            throw new IOException("Version response could not be read");
        }
        return version;
    }

    /** Uploads the SQL of one repository as multipart form data with a bearer token. */
    public static Result upload(Properties config, Path tokenFile, String repo, String task, String version,
                                String description, String sql) throws IOException, InterruptedException {
        String url = DeployRequest.fillUrl(ApiAuth.require(config, "deploy.upload.url"), repo, task, version);
        String boundary = UUID.randomUUID().toString().replace("-", "");
        byte[] body = DeployRequest.multipart(boundary, description, DeployRequest.fileName(task, repo), sql);
        HttpResponse<String> response = ApiAuth.send(config, tokenFile, token -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url))
                .timeout(UPLOAD_TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            ApiAuth.addHeaders(config, "deploy.upload.header.", request, "content-type", "authorization");
            return request.build();
        }, HttpResponse.BodyHandlers.ofString());
        return new Result(response.statusCode(), response.body());
    }
}
