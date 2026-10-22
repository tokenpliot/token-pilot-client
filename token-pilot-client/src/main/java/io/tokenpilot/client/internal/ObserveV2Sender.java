package io.tokenpilot.client.internal;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.tokenpilot.client.UsageEvent;

/**
 * {@code POST /api/v2/observe/events}: the Observe ledger endpoint. It takes one event per request and prices it
 * on the server, so costs, totals, pricing version and metadata are not sent.
 *
 * <p>Events are sent one after another on the caller's (sender) thread. The first network or server failure stops
 * the round: when the Control Plane is down, a batch costs one timeout rather than one per event.
 */
public final class ObserveV2Sender implements Transport {

    static final String OBSERVE_PATH = "/api/v2/observe/events";
    static final String SCHEMA_VERSION = "observe-v1";
    /** Token Pilot and provider-reported counts already include cached input and reasoning output tokens. */
    static final String TOKEN_SEMANTICS = "INCLUSIVE_V1";

    private final ControlPlaneHttp http;
    private final URI observeUri;
    private final String projectKey;
    private final String environment;

    public ObserveV2Sender(URI endpoint, String apiKey, String projectKey, String environment,
        Duration connectTimeout, Duration requestTimeout) {
        this.http = new ControlPlaneHttp(apiKey, connectTimeout, requestTimeout);
        this.observeUri = ControlPlaneHttp.resolve(endpoint, OBSERVE_PATH);
        this.projectKey = projectKey;
        this.environment = environment;
    }

    @Override
    public Outcome send(List<UsageEvent> events) throws InterruptedException {
        List<ItemResult> results = new ArrayList<>(events.size());
        for (UsageEvent event : events) {
            HttpResponse<String> response;
            try {
                response = http.post(observeUri, encode(event));
            } catch (IOException exception) {
                return stopAt(results, events.size(), new Outcome.Retry(exception.getClass().getSimpleName()));
            }

            int status = response.statusCode();
            String body = response.body();
            if (status == 201) {
                results.add(new ItemResult(ItemStatus.CREATED, null, false));
            } else if (status == 200) {
                results.add(new ItemResult(ItemStatus.DUPLICATE, null, false));
            } else if (status == 400 || status == 409 || status == 413) {
                // About this event only: invalid fields, same eventId with a different body, or too large.
                results.add(new ItemResult(ItemStatus.REJECTED, ControlPlaneHttp.describe(status, body), false));
            } else if (ControlPlaneHttp.isRetryable(status)) {
                return stopAt(results, events.size(), new Outcome.Retry(ControlPlaneHttp.describe(status, body)));
            } else {
                // 401/403 key problems, or 404 when the server has not enabled the endpoint.
                return stopAt(results, events.size(), new Outcome.Refused(status, ControlPlaneHttp.errorCode(body)));
            }
        }
        return new Outcome.Items(results);
    }

    /**
     * Ends the round at the first event that could not be sent. With nothing delivered yet, the outcome applies to
     * the whole list; otherwise the delivered results are kept and the rest is resent or rejected.
     */
    private static Outcome stopAt(List<ItemResult> results, int total, Outcome failure) {
        if (results.isEmpty()) {
            return failure;
        }
        ItemResult rest = failure instanceof Outcome.Refused refused
            ? new ItemResult(ItemStatus.REJECTED, "HTTP " + refused.status()
                + (refused.code() == null ? "" : " " + refused.code()), false)
            : new ItemResult(ItemStatus.REJECTED, null, true);
        while (results.size() < total) {
            results.add(rest);
        }
        return new Outcome.Items(results);
    }

    byte[] encode(UsageEvent event) {
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        string(out, "projectKey", projectKey, true);
        string(out, "environment", environment, false);
        string(out, "eventId", event.eventId(), false);
        string(out, "requestId", event.requestId(), false);
        string(out, "schemaVersion", SCHEMA_VERSION, false);
        string(out, "tokenSemantics", TOKEN_SEMANTICS, false);
        string(out, "provider", event.provider(), false);
        string(out, "model", event.model(), false);
        string(out, "callStatus", "SUCCEEDED", false);
        number(out, "promptTokens", event.promptTokens());
        number(out, "completionTokens", event.completionTokens());
        number(out, "cachedPromptTokens", event.cachedPromptTokens());
        number(out, "reasoningTokens", event.reasoningTokens());
        string(out, "occurredAt", event.occurredAt().toString(), false);
        out.append('}');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void string(StringBuilder out, String name, String value, boolean first) {
        if (!first) {
            out.append(',');
        }
        Json.writeString(out, name);
        out.append(':');
        Json.writeString(out, value);
    }

    private static void number(StringBuilder out, String name, Long value) {
        if (value != null) {
            out.append(",\"").append(name).append("\":").append(value.longValue());
        }
    }
}
