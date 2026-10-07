package io.tokenpilot.client.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One HTTP exchange with the batch ingestion endpoint, classified into what the dispatcher must do next.
 *
 * <p>Only the status code and the server's error code are surfaced, never the response body as a whole, so
 * nothing the server echoes can leak into logs.
 */
public final class BatchSender {

    static final String BATCH_PATH = "/api/ingestion/events/batch";
    private static final String USER_AGENT = "token-pilot-client-java";

    private final HttpClient httpClient;
    private final URI batchUri;
    private final String apiKey;
    private final Duration requestTimeout;

    public BatchSender(URI endpoint, String apiKey, Duration connectTimeout, Duration requestTimeout) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        this.batchUri = resolve(endpoint);
        this.apiKey = apiKey;
        this.requestTimeout = requestTimeout;
    }

    static URI resolve(URI endpoint) {
        String base = endpoint.toString();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + BATCH_PATH);
    }

    /** Outcome of one send. */
    public sealed interface Outcome {

        /** HTTP 200: one result per item, in request order. */
        record Items(List<ItemResult> items) implements Outcome {
        }

        /** Network error, timeout, 5xx, 429 or an unreadable 200: send the same items again later. */
        record Retry(String reason) implements Outcome {
        }

        /** HTTP 413: the body was too large. */
        record TooLarge() implements Outcome {
        }

        /** Any other 4xx: the whole request is refused and retrying will not help. */
        record Refused(int status, String code) implements Outcome {
        }
    }

    public enum ItemStatus {
        CREATED,
        DUPLICATE,
        REJECTED
    }

    public record ItemResult(ItemStatus status, String code, boolean retryable) {
    }

    public Outcome send(byte[] body, int itemCount) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(batchUri)
            .timeout(requestTimeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-API-Key", apiKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new Outcome.Retry(exception.getClass().getSimpleName());
        }

        int status = response.statusCode();
        if (status == 200) {
            return parseItems(response.body(), itemCount);
        }
        if (status == 413) {
            return new Outcome.TooLarge();
        }
        if (status >= 500 || status == 429 || status == 408) {
            return new Outcome.Retry("HTTP " + status + errorCodeSuffix(response.body()));
        }
        return new Outcome.Refused(status, errorCode(response.body()));
    }

    /**
     * Reads {@code data.items}; servers older than the idempotent ingestion only return {@code rejectedItems}, so
     * every item not listed there counts as created. An unreadable body is retried: the server deduplicates.
     */
    static Outcome parseItems(String body, int itemCount) {
        Map<?, ?> data;
        try {
            Object root = Json.parse(body);
            if (!(root instanceof Map<?, ?> rootObject) || !(rootObject.get("data") instanceof Map<?, ?> dataObject)) {
                return new Outcome.Retry("unreadable 200 response");
            }
            data = dataObject;
        } catch (IllegalArgumentException exception) {
            return new Outcome.Retry("unreadable 200 response");
        }

        // Anything the server does not report on is resent; a replay is answered as DUPLICATE.
        List<ItemResult> results = new ArrayList<>(itemCount);
        for (int i = 0; i < itemCount; i++) {
            results.add(new ItemResult(ItemStatus.REJECTED, null, true));
        }

        if (data.get("items") instanceof List<?> items && !items.isEmpty()) {
            for (Object entry : items) {
                if (entry instanceof Map<?, ?> item && index(item, itemCount) >= 0) {
                    results.set(index(item, itemCount), new ItemResult(
                        status(item.get("status")),
                        item.get("code") instanceof String code ? code : null,
                        Boolean.TRUE.equals(item.get("retryable"))));
                }
            }
            return new Outcome.Items(results);
        }

        for (int i = 0; i < itemCount; i++) {
            results.set(i, new ItemResult(ItemStatus.CREATED, null, false));
        }
        if (data.get("rejectedItems") instanceof List<?> rejected) {
            for (Object entry : rejected) {
                if (entry instanceof Map<?, ?> item && index(item, itemCount) >= 0) {
                    results.set(index(item, itemCount), new ItemResult(
                        ItemStatus.REJECTED,
                        item.get("code") instanceof String code ? code : null,
                        Boolean.TRUE.equals(item.get("retryable"))));
                }
            }
        }
        return new Outcome.Items(results);
    }

    private static int index(Map<?, ?> item, int itemCount) {
        if (item.get("index") instanceof Number number) {
            int index = number.intValue();
            if (index >= 0 && index < itemCount) {
                return index;
            }
        }
        return -1;
    }

    private static ItemStatus status(Object value) {
        if ("CREATED".equals(value)) {
            return ItemStatus.CREATED;
        }
        if ("DUPLICATE".equals(value)) {
            return ItemStatus.DUPLICATE;
        }
        return ItemStatus.REJECTED;
    }

    private static String errorCode(String body) {
        try {
            if (Json.parse(body) instanceof Map<?, ?> root && root.get("code") instanceof String code) {
                return code;
            }
        } catch (IllegalArgumentException ignored) {
            // Not a JSON error body (e.g. a proxy page); the status code is enough.
        }
        return null;
    }

    private static String errorCodeSuffix(String body) {
        String code = errorCode(body);
        return code == null ? "" : " " + code;
    }
}
