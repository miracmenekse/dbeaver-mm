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
package org.jkiss.dbeaver.ui.controls.resultset.translate;

import java.io.IOException;
// dbeaver-mm K158: sistem proxy'sini kullanmak için ProxySelector
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Google web translate client with pure url/parse logic and a single HTTP call.
 */
// dbeaver-mm K154: Google web çeviri istemcisi (saf mantık)
public class GoogleTranslate {

    private static final String BASE_URL = "https://translate.googleapis.com/translate_a/single";
    private static final Pattern LANGUAGE_CODE = Pattern.compile("[a-z]{2,3}(-[A-Za-z]{2,4})?");
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    // dbeaver-mm K158: tek HttpClient; sistem proxy'si ve NORMAL yönlendirme
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(TIMEOUT)
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();
    // dbeaver-mm K158: Java varsayılanı yerine gerçek tarayıcı User-Agent'ı
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; dbeaver-mm translate)";

    public static String url(String text, String from, String to) {
        validateLanguage(from);
        validateLanguage(to);
        return BASE_URL
            + "?client=gtx&sl=" + from
            + "&tl=" + to
            + "&dt=t&q=" + URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    // dbeaver-mm K158: isteği ortak başlıklarla kuran yardımcı yöntem
    static HttpRequest buildRequest(String text, String from, String to) {
        return HttpRequest.newBuilder(URI.create(url(text, from, to)))
            .timeout(TIMEOUT)
            .header("Accept", "application/json")
            .header("Accept-Language", "en")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build();
    }

    // dbeaver-mm K158: 429/503 için bekleme süresi; attempt >= 2 ise vazgeç
    static long backoffMillis(int attempt) {
        if (attempt < 0 || attempt >= 2) {
            return -1;
        }
        return 1000L * (1L << attempt);
    }

    // dbeaver-mm K158: 429/503 yanıtlarında en fazla iki kez yeniden dene
    public String translate(String text, String from, String to) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        HttpRequest request = buildRequest(text, from, to);
        int attempt = 0;
        while (true) {
            HttpResponse<String> response;
            try {
                response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Google translate request interrupted", e);
            } catch (IOException e) {
                throw new IllegalStateException("Google translate request failed", e);
            }
            int statusCode = response.statusCode();
            if (statusCode != 200) {
                if (statusCode == 429 || statusCode == 503) {
                    long delay = backoffMillis(attempt);
                    if (delay < 0) {
                        throw new IllegalStateException("Google rate-limited the request (after 3 tries)");
                    }
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Google translate request interrupted", e);
                    }
                    attempt++;
                    continue;
                }
                throw new IllegalStateException("Google translate HTTP status " + statusCode);
            }
            return parse(response.body());
        }
    }

    public static String parse(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("Empty translation response");
        }
        int i = skipWhitespace(json, 0);
        if (i >= json.length() || json.charAt(i) != '[') {
            throw new IllegalArgumentException("Invalid translation response");
        }
        i = skipWhitespace(json, i + 1);
        if (i >= json.length() || json.charAt(i) != '[') {
            throw new IllegalArgumentException("Invalid translation response");
        }
        i++;
        StringBuilder result = new StringBuilder();
        while (true) {
            i = skipWhitespace(json, i);
            if (i >= json.length()) {
                throw new IllegalArgumentException("Unterminated translation response");
            }
            char c = json.charAt(i);
            if (c == ']') {
                i++;
                break;
            }
            if (c != '[') {
                throw new IllegalArgumentException("Invalid translation chunk");
            }
            i = skipWhitespace(json, i + 1);
            if (i >= json.length() || json.charAt(i) != '"') {
                throw new IllegalArgumentException("Invalid translation chunk");
            }
            StringResult chunk = readString(json, i);
            result.append(chunk.value);
            i = skipChunkRemainder(json, chunk.next);
            i = skipWhitespace(json, i);
            if (i < json.length() && json.charAt(i) == ',') {
                i++;
            }
        }
        while (true) {
            i = skipWhitespace(json, i);
            if (i >= json.length()) {
                throw new IllegalArgumentException("Unterminated translation response");
            }
            char c = json.charAt(i);
            if (c == ']') {
                return result.toString();
            }
            if (c != ',') {
                throw new IllegalArgumentException("Invalid translation response");
            }
            i = skipValue(json, i + 1);
        }
    }

    private static void validateLanguage(String language) {
        if (language == null || !LANGUAGE_CODE.matcher(language).matches()) {
            throw new IllegalArgumentException("Invalid language code: " + language);
        }
    }

    private static int skipWhitespace(String text, int index) {
        int i = index;
        while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipChunkRemainder(String text, int index) {
        int i = index;
        while (true) {
            i = skipWhitespace(text, i);
            if (i >= text.length()) {
                throw new IllegalArgumentException("Unterminated translation chunk");
            }
            char c = text.charAt(i);
            if (c == ']') {
                return i + 1;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Invalid translation chunk");
            }
            i = skipValue(text, i + 1);
        }
    }

    private static int skipValue(String text, int index) {
        int i = skipWhitespace(text, index);
        if (i >= text.length()) {
            throw new IllegalArgumentException("Unexpected end of JSON");
        }
        char c = text.charAt(i);
        if (c == '"') {
            return readString(text, i).next;
        }
        if (c == '[') {
            i++;
            while (true) {
                i = skipWhitespace(text, i);
                if (i >= text.length()) {
                    throw new IllegalArgumentException("Unterminated JSON array");
                }
                char d = text.charAt(i);
                if (d == ']') {
                    return i + 1;
                }
                if (d == ',') {
                    i++;
                    continue;
                }
                i = skipValue(text, i);
            }
        }
        if (c == '{') {
            i++;
            while (true) {
                i = skipWhitespace(text, i);
                if (i >= text.length()) {
                    throw new IllegalArgumentException("Unterminated JSON object");
                }
                char d = text.charAt(i);
                if (d == '}') {
                    return i + 1;
                }
                if (d == ',') {
                    i++;
                    continue;
                }
                if (d != '"') {
                    throw new IllegalArgumentException("Invalid JSON object");
                }
                i = skipWhitespace(text, readString(text, i).next);
                if (i >= text.length() || text.charAt(i) != ':') {
                    throw new IllegalArgumentException("Invalid JSON object");
                }
                i = skipValue(text, i + 1);
            }
        }
        int start = i;
        while (i < text.length()) {
            char d = text.charAt(i);
            if (d == ',' || d == ']' || d == '}' || Character.isWhitespace(d)) {
                break;
            }
            i++;
        }
        if (i == start) {
            throw new IllegalArgumentException("Invalid JSON value");
        }
        return i;
    }

    private static StringResult readString(String text, int index) {
        int i = index;
        if (i >= text.length() || text.charAt(i) != '"') {
            throw new IllegalArgumentException("Invalid JSON string");
        }
        i++;
        StringBuilder value = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '"') {
                return new StringResult(value.toString(), i);
            }
            if (c != '\\') {
                value.append(c);
                continue;
            }
            if (i >= text.length()) {
                throw new IllegalArgumentException("Invalid JSON escape");
            }
            char escape = text.charAt(i++);
            switch (escape) {
                case '"':
                    value.append('"');
                    break;
                case '\\':
                    value.append('\\');
                    break;
                case '/':
                    value.append('/');
                    break;
                case 'b':
                    value.append('\b');
                    break;
                case 'f':
                    value.append('\f');
                    break;
                case 'n':
                    value.append('\n');
                    break;
                case 'r':
                    value.append('\r');
                    break;
                case 't':
                    value.append('\t');
                    break;
                case 'u':
                    if (i + 4 > text.length()) {
                        throw new IllegalArgumentException("Invalid unicode escape");
                    }
                    int code = 0;
                    for (int k = 0; k < 4; k++) {
                        int digit = Character.digit(text.charAt(i++), 16);
                        if (digit < 0) {
                            throw new IllegalArgumentException("Invalid unicode escape");
                        }
                        code = code * 16 + digit;
                    }
                    value.append((char) code);
                    break;
                default:
                    throw new IllegalArgumentException("Invalid JSON escape");
            }
        }
        throw new IllegalArgumentException("Unterminated JSON string");
    }

    private static final class StringResult {
        private final String value;
        private final int next;

        private StringResult(String value, int next) {
            this.value = value;
            this.next = next;
        }
    }
}
