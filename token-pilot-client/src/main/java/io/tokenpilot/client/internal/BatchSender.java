package io.tokenpilot.client.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.tokenpilot.client.UsageEvent;

/** {@code POST /api/ingestion/events/batch}: up to 100 events per request, one result per item. */
public final class BatchSender implements Transport {

    static final String BATCH_PATH = "/api/ingestion/events/batch";

    private final ControlPlaneHttp http;
    private final URI batchUri;
    private final BatchEncoder encoder;
    private final int maxBatchBytes;

    public BatchSender(URI endpoint, String apiKey, String projectKey, String environment, int maxBatchBytes,
        Duration connectTimeout, Duration requestTimeout) {
        this.http = new ControlPlaneHttp(apiKey, connectTimeout, requestTimeout);
        this.batchUri = ControlPlaneHttp.resolve(endpoint, BATCH_PATH);
        this.encoder = new BatchEncoder(projectKey, environment);
        this.maxBatchBytes = maxBatchBytes;
    }

    @Override
    public Outcome send(List<UsageEvent> events) throws InterruptedException {
        byte[] body = encoder.encode(events);
        if (body.length > maxBatchBytes) {
            return new Outcome.TooLarge(body.length + " bytes");
        }

        HttpResponse<String> response;
        try {
            response = http.post(batchUri, body);
        } catch (IOException exception) {
            return new Outcome.Retry(exception.getClass().getSimpleName());
        }

        int status = response.statusCode();
        if (status == 200) {
            return parseItems(response.body(), events.size());
        }
        if (status == 413) {
            return new Outcome.TooLarge("HTTP 413");
        }
        if (ControlPlaneHttp.isRetryable(status)) {
            return new Outcome.Retry(ControlPlaneHttp.describe(status, response.body()));
        }
        return new Outcome.Refused(status, ControlPlaneHttp.errorCode(response.body()));
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
}
