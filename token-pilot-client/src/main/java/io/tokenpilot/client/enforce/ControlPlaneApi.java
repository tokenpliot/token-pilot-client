package io.tokenpilot.client.enforce;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.tokenpilot.client.internal.Json;

/**
 * HTTP exchanges with the decision and settlement endpoints, and the request bodies they carry.
 *
 * <p>Only status codes, server error codes and reason codes leave this class; request and response bodies are
 * never logged or put in messages, so neither metadata nor anything the server echoes can leak.
 */
final class ControlPlaneApi {

    static final String DECISIONS_PATH = "/api/control/v1/decisions";
    private static final String RESERVATIONS_PATH = "/api/control/v1/reservations/";
    private static final String USER_AGENT = "token-pilot-client-java";

    static final String UNREADABLE_RESPONSE = "UNREADABLE_RESPONSE";
    static final String UNEXPECTED_STATUS = "UNEXPECTED_STATUS";

    /** Outcomes a 200 may carry; CONTROL_PLANE_UNAVAILABLE in a 200 is a contract violation. */
    private static final Set<String> EVALUATED_OUTCOMES =
        Set.of("ALLOW", "DENY_POLICY", "DENY_BUDGET", "REQUIRE_APPROVAL", "INDETERMINATE");

    private final HttpClient httpClient;
    private final String base;
    private final String apiKey;
    private final String environment;
    private final Duration decisionTimeout;
    private final Duration settlementTimeout;

    ControlPlaneApi(EnforcerConfig config) {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout())
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        String endpoint = config.endpoint().toString();
        while (endpoint.endsWith("/")) {
            endpoint = endpoint.substring(0, endpoint.length() - 1);
        }
        this.base = endpoint;
        this.apiKey = config.apiKey();
        this.environment = config.environment();
        this.decisionTimeout = config.decisionTimeout();
        this.settlementTimeout = config.settlementTimeout();
    }

    // --- decisions ---

    static String decisionKey(String requestId) {
        return Contract.idempotencyKey("dec:", requestId);
    }

    /**
     * Never takes longer than the decision timeout: the request timeout alone does not bound connecting plus
     * reading the body, so the caller also waits on the future with the same limit and abandons it.
     */
    Decision decide(CallRequest request, EnforcementMode mode) throws InterruptedException {
        HttpRequest http = request(URI.create(base + DECISIONS_PATH), decisionTimeout,
            decisionKey(request.requestId()), decisionBody(request, environment, mode));
        CompletableFuture<HttpResponse<String>> future =
            httpClient.sendAsync(http, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        HttpResponse<String> response;
        try {
            response = future.get(decisionTimeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            return transportFailure(request.requestId(), "HttpTimeoutException");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause() != null ? exception.getCause() : exception;
            return transportFailure(request.requestId(), cause.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            future.cancel(true);
            throw exception;
        }
        return interpret(request.requestId(), response.statusCode(), response.body(),
            response.headers().firstValue("Retry-After").orElse(null));
    }

    private static Decision transportFailure(String requestId, String errorCode) {
        return Decision.failure(requestId, DecisionOutcome.forTransportFailure(), 0, errorCode, null);
    }

    /** Turns a decision response into a Decision. Error responses can only map to INDETERMINATE/UNAVAILABLE. */
    static Decision interpret(String requestId, int status, String body, String retryAfterHeader) {
        if (status == 200) {
            Decision decision = evaluated(requestId, body);
            return decision != null
                ? decision
                : Decision.failure(requestId, DecisionOutcome.INDETERMINATE, status, UNREADABLE_RESPONSE, null);
        }
        if (status < 400 || status > 599) {
            return Decision.failure(requestId, DecisionOutcome.INDETERMINATE, status, UNEXPECTED_STATUS, null);
        }
        // Whatever the body claims (even a DENY_* outcome), only the status decides the outcome.
        Map<?, ?> root = object(parseQuietly(body));
        String code = root != null && root.get("code") instanceof String text ? text : "HTTP_" + status;
        Duration retryAfter = root != null ? seconds(object(root.get("data")), "retryAfterSeconds") : null;
        if (retryAfter == null) {
            retryAfter = headerSeconds(retryAfterHeader);
        }
        return Decision.failure(requestId, DecisionOutcome.forErrorStatus(status), status, code, retryAfter);
    }

    /** Null when the 200 body is not a decision for this request. */
    private static Decision evaluated(String requestId, String body) {
        Map<?, ?> root = object(parseQuietly(body));
        Map<?, ?> data = root == null ? null : object(root.get("data"));
        if (data == null
            || !(data.get("outcome") instanceof String outcome) || !EVALUATED_OUTCOMES.contains(outcome)
            || !requestId.equals(data.get("requestId"))) {
            return null;
        }
        Reservation reservation = null;
        if (data.get("reservation") != null) {
            reservation = reservation(object(data.get("reservation")));
            if (reservation == null) {
                return null;
            }
        }
        List<DecisionReason> reasons = new ArrayList<>();
        if (data.get("reasons") instanceof List<?> entries) {
            for (Object entry : entries) {
                Map<?, ?> reason = object(entry);
                if (reason != null && reason.get("code") instanceof String code) {
                    reasons.add(new DecisionReason(code, text(reason, "message")));
                }
            }
        }
        return new Decision(
            DecisionOutcome.valueOf(outcome),
            text(data, "decisionId"),
            requestId,
            mode(data.get("effectiveMode")),
            reasons,
            text(data, "policyVersion"),
            reservation,
            text(data, "approvalId"),
            200,
            null,
            null
        );
    }

    /** A reservation must be identifiable to be settled; the rest is informational. */
    private static Reservation reservation(Map<?, ?> value) {
        if (value == null || !(value.get("reservationId") instanceof String id) || !Contract.isIdentifier(id)) {
            return null;
        }
        BigDecimal reserved = null;
        if (value.get("reservedCostUsd") instanceof String amount) {
            try {
                reserved = new BigDecimal(amount);
            } catch (NumberFormatException ignored) {
                // Informational only.
            }
        }
        Instant expiresAt = null;
        if (value.get("expiresAt") instanceof String instant) {
            try {
                expiresAt = Instant.parse(instant);
            } catch (DateTimeParseException ignored) {
                // Informational only.
            }
        }
        return new Reservation(id, text(value, "state"), reserved, expiresAt);
    }

    private static EnforcementMode mode(Object value) {
        if (value instanceof String name) {
            for (EnforcementMode mode : EnforcementMode.values()) {
                if (mode.name().equals(name)) {
                    return mode;
                }
            }
        }
        return null;
    }

    static byte[] decisionBody(CallRequest request, String environment, EnforcementMode mode) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "schemaVersion", Contract.SCHEMA_VERSION);
        out.append(',');
        field(out, "requestId", request.requestId());
        out.append(',');
        field(out, "environment", environment);
        out.append(',');
        field(out, "provider", request.provider());
        out.append(',');
        field(out, "model", request.model());
        out.append(',');
        field(out, "clientMode", mode.name());
        out.append(",\"estimatedInputTokens\":").append(request.estimatedInputTokens());
        out.append(",\"maxOutputTokens\":").append(request.maxOutputTokens());
        if (request.estimatedCostUsd() != null) {
            out.append(',');
            field(out, "estimatedCostUsd", Contract.usdString(request.estimatedCostUsd()));
        }
        if (request.pricingVersion() != null) {
            out.append(',');
            field(out, "pricingVersion", request.pricingVersion());
        }
        if (!request.metadata().isEmpty()) {
            out.append(",\"metadata\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : request.metadata().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                field(out, entry.getKey(), entry.getValue());
            }
            out.append('}');
        }
        out.append('}');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- settlements ---

    /** Result of one settlement send. */
    sealed interface SettlementReply {

        /** HTTP 200: the reservation is now in {@code state}. */
        record Settled(String state, boolean duplicate) implements SettlementReply {
        }

        /** Network error, timeout, 408, 429, 5xx or an unreadable 200: send the same body again later. */
        record Retry(String reason) implements SettlementReply {
        }

        /** Any other status: retrying the same body cannot succeed. */
        record Refused(int status, String code) implements SettlementReply {
        }
    }

    static String settlementKey(String reservationId, SettlementType type) {
        return Contract.idempotencyKey("stl:", reservationId + ":" + type.name());
    }

    SettlementReply settle(String reservationId, String idempotencyKey, byte[] body) throws InterruptedException {
        URI uri = URI.create(base + RESERVATIONS_PATH
            + URLEncoder.encode(reservationId, StandardCharsets.UTF_8) + "/settlements");
        HttpResponse<String> response;
        try {
            response = httpClient.send(request(uri, settlementTimeout, idempotencyKey, body),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            return new SettlementReply.Retry(exception.getClass().getSimpleName());
        }
        int status = response.statusCode();
        Map<?, ?> root = object(parseQuietly(response.body()));
        String code = root != null && root.get("code") instanceof String text ? text : null;
        if (status == 200) {
            Map<?, ?> data = root == null ? null : object(root.get("data"));
            if (data == null || !(data.get("state") instanceof String state)) {
                return new SettlementReply.Retry("unreadable 200 response");
            }
            return new SettlementReply.Settled(state, Boolean.TRUE.equals(data.get("duplicate")));
        }
        if (status == 408 || status == 429 || status >= 500) {
            return new SettlementReply.Retry("HTTP " + status + (code == null ? "" : " " + code));
        }
        return new SettlementReply.Refused(status, code);
    }

    /**
     * Built once per settlement so every retry is byte-identical: the server deduplicates on key plus body and
     * answers a changed body with 409.
     */
    static byte[] settlementBody(String requestId, SettlementType type, ActualUsage usage, Instant occurredAt) {
        StringBuilder out = new StringBuilder(256);
        out.append('{');
        field(out, "schemaVersion", Contract.SCHEMA_VERSION);
        out.append(',');
        field(out, "requestId", requestId);
        out.append(',');
        field(out, "result", type.name());
        if (usage != null && type != SettlementType.RELEASE) {
            out.append(",\"usage\":{\"promptTokens\":").append(usage.promptTokens())
                .append(",\"completionTokens\":").append(usage.completionTokens());
            if (usage.cachedPromptTokens() != null) {
                out.append(",\"cachedPromptTokens\":").append(usage.cachedPromptTokens().longValue());
            }
            if (usage.reasoningTokens() != null) {
                out.append(",\"reasoningTokens\":").append(usage.reasoningTokens().longValue());
            }
            out.append('}');
            if (type == SettlementType.COMMIT) {
                out.append(",\"cost\":{");
                field(out, "promptCostUsd", Contract.usdString(usage.promptCostUsd()));
                out.append(',');
                field(out, "completionCostUsd", Contract.usdString(usage.completionCostUsd()));
                out.append(',');
                field(out, "totalCostUsd", Contract.usdString(usage.totalCostUsd()));
                out.append('}');
            }
        }
        out.append(',');
        field(out, "occurredAt", occurredAt.toString());
        out.append('}');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    // --- helpers ---

    private HttpRequest request(URI uri, Duration timeout, String idempotencyKey, byte[] body) {
        return HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("X-API-Key", apiKey)
            .header("Idempotency-Key", idempotencyKey)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();
    }

    private static void field(StringBuilder out, String name, String value) {
        Json.writeString(out, name);
        out.append(':');
        Json.writeString(out, value);
    }

    private static Object parseQuietly(String body) {
        if (body == null || body.isEmpty()) {
            return null;
        }
        try {
            return Json.parse(body);
        } catch (IllegalArgumentException exception) {
            // Not JSON (e.g. a proxy error page); callers fall back to the status code.
            return null;
        }
    }

    private static Map<?, ?> object(Object value) {
        return value instanceof Map<?, ?> map ? map : null;
    }

    private static String text(Map<?, ?> object, String name) {
        return object.get(name) instanceof String value ? value : null;
    }

    private static Duration seconds(Map<?, ?> object, String name) {
        if (object != null && object.get(name) instanceof BigDecimal value && value.signum() >= 0) {
            try {
                return Duration.ofSeconds(value.longValueExact());
            } catch (ArithmeticException ignored) {
                // Fractional or huge: not a usable hint.
            }
        }
        return null;
    }

    /** Retry-After in its delta-seconds form; the HTTP-date form is not used by the Control Plane. */
    private static Duration headerSeconds(String header) {
        if (header == null) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
