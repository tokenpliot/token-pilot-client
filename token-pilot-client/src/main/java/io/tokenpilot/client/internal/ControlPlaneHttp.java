package io.tokenpilot.client.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** JSON POSTs to the Control Plane with the project API key. Shared by the ingestion transports. */
final class ControlPlaneHttp {

    private static final String USER_AGENT = "token-pilot-client-java";

    private final HttpClient httpClient;
    private final String apiKey;
    private final Duration requestTimeout;

    ControlPlaneHttp(String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
    }

    static URI resolve(URI endpoint, String path) {
        String base = endpoint.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    HttpResponse<String> post(URI uri, byte[] body) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-API-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    static boolean isRetryable(int status) {
        return status >= 500 || status == 429 || status == 408;
    }

    /** The server's error code ({@code COMMON-401}, {@code INGESTION-409}, ...), or null for a non-JSON body. */
    static String errorCode(String body) {
        try {
            if (Json.parse(body) instanceof Map<?, ?> root && root.get("code") instanceof String code) {
                return code;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a JSON error body (e.g. a proxy page); the status code is enough.
        }
        return null;
    }

    static String describe(int status, String body) {
        String code = errorCode(body);
        return "HTTP " + status + (code == null ? "" : " " + code);
    }
}
