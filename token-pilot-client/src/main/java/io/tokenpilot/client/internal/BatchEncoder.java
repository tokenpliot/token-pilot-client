package io.tokenpilot.client.internal;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import io.tokenpilot.client.UsageEvent;

/** Encodes {@code POST /api/ingestion/events/batch} bodies. */
public final class BatchEncoder {

    /** The only schema version the server accepts besides an absent one. */
    public static final String SCHEMA_VERSION = "2026-10-01";

    private final String projectKey;
    private final String environment;

    public BatchEncoder(String projectKey, String environment) {
        this.projectKey = projectKey;
        this.environment = environment;
    }

    public byte[] encode(List<UsageEvent> events) {
        StringBuilder out = new StringBuilder(256 + events.size() * 512);
        out.append('{');
        field(out, "projectKey", projectKey, true);
        field(out, "environment", environment, false);
        field(out, "schemaVersion", SCHEMA_VERSION, false);
        out.append(",\"items\":[");
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            item(out, events.get(i));
        }
        out.append("]}");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void item(StringBuilder out, UsageEvent event) {
        out.append('{');
        field(out, "eventId", event.eventId(), true);
        field(out, "requestId", event.requestId(), false);
        field(out, "provider", event.provider(), false);
        field(out, "model", event.model(), false);
        number(out, "promptTokens", event.promptTokens());
        number(out, "completionTokens", event.completionTokens());
        number(out, "reasoningTokens", event.reasoningTokens());
        number(out, "cachedPromptTokens", event.cachedPromptTokens());
        number(out, "totalTokens", event.totalTokens());
        decimal(out, "promptCostUsd", event.promptCostUsd());
        decimal(out, "completionCostUsd", event.completionCostUsd());
        decimal(out, "reasoningCostUsd", event.reasoningCostUsd());
        decimal(out, "cachedPromptCostUsd", event.cachedPromptCostUsd());
        decimal(out, "totalCostUsd", event.totalCostUsd());
        field(out, "pricingPlanId", event.pricingPlanId(), false);
        field(out, "pricingVersion", event.pricingVersion(), false);
        field(out, "sourceType", event.sourceType(), false);
        if (!event.metadata().isEmpty()) {
            out.append(",\"metadata\":{");
            boolean first = true;
            for (Map.Entry<String, String> entry : event.metadata().entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                Json.writeString(out, entry.getKey());
                out.append(':');
                Json.writeString(out, entry.getValue());
            }
            out.append('}');
        }
        field(out, "occurredAt", event.occurredAt().toString(), false);
        out.append('}');
    }

    private static void field(StringBuilder out, String name, String value, boolean first) {
        if (value == null) {
            return;
        }
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

    private static void decimal(StringBuilder out, String name, BigDecimal value) {
        if (value != null) {
            out.append(",\"").append(name).append("\":").append(value.toPlainString());
        }
    }
}
