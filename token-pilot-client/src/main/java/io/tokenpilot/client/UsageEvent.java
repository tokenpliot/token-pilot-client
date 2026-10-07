package io.tokenpilot.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One LLM call's usage, in the shape of the Control Plane ingestion contract ({@code schemaVersion 2026-10-01}).
 *
 * <p>Instances are immutable, so a retried batch carries byte-identical items and the same {@code eventId}: the
 * server answers a replay with {@code DUPLICATE} instead of counting it twice. The constructor enforces the
 * contract limits, so an invalid event fails here, on the caller's side, rather than as a rejected item later.
 *
 * <p>Raw prompt or completion text must never be sent. Metadata keys the server treats as raw text
 * ({@link #FORBIDDEN_METADATA_KEYS}) are removed here, before anything leaves the process.
 */
public record UsageEvent(
    String eventId,
    String requestId,
    String provider,
    String model,
    long promptTokens,
    long completionTokens,
    Long reasoningTokens,
    Long cachedPromptTokens,
    Long totalTokens,
    BigDecimal promptCostUsd,
    BigDecimal completionCostUsd,
    BigDecimal reasoningCostUsd,
    BigDecimal cachedPromptCostUsd,
    BigDecimal totalCostUsd,
    String pricingPlanId,
    String pricingVersion,
    String sourceType,
    Map<String, String> metadata,
    Instant occurredAt
) {

    /** Largest value of the server's {@code decimal(18,6)} USD columns. */
    public static final BigDecimal MAX_USD = new BigDecimal("999999999999.999999");

    /** Metadata keys that would carry raw prompt or completion text. */
    public static final Set<String> FORBIDDEN_METADATA_KEYS =
        Set.of("prompt", "system_prompt", "completion", "messages", "content", "response");

    public static final int MAX_METADATA_ENTRIES = 16;
    public static final int MAX_METADATA_VALUE_LENGTH = 256;

    private static final Pattern METADATA_KEY = Pattern.compile("^[a-z][a-z0-9_.-]{0,63}$");
    private static final String DEFAULT_SOURCE_TYPE = "sdk-java";

    public UsageEvent {
        requireText("eventId", eventId, 100);
        requireText("requestId", requestId, 100);
        requireText("provider", provider, 50);
        requireText("model", model, 100);
        requireText("pricingVersion", pricingVersion, 50);
        requireText("sourceType", sourceType, 30);
        optionalText("pricingPlanId", pricingPlanId, 36);
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");

        requireTokens("promptTokens", promptTokens);
        requireTokens("completionTokens", completionTokens);
        optionalTokens("reasoningTokens", reasoningTokens);
        optionalTokens("cachedPromptTokens", cachedPromptTokens);
        optionalTokens("totalTokens", totalTokens);
        if (totalTokens == null) {
            // The server derives the total from the parts; make sure that sum is representable.
            try {
                Math.addExact(Math.addExact(promptTokens, completionTokens),
                    Math.addExact(zero(reasoningTokens), zero(cachedPromptTokens)));
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("token counts overflow when summed", exception);
            }
        }

        requireUsd("promptCostUsd", promptCostUsd);
        requireUsd("completionCostUsd", completionCostUsd);
        requireUsd("reasoningCostUsd", reasoningCostUsd);
        requireUsd("cachedPromptCostUsd", cachedPromptCostUsd);
        requireUsd("totalCostUsd", totalCostUsd);
        if (totalCostUsd == null) {
            requireUsd("derived totalCostUsd", zero(promptCostUsd).add(zero(completionCostUsd))
                .add(zero(reasoningCostUsd)).add(zero(cachedPromptCostUsd)));
        }

        metadata = sanitizedMetadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static Map<String, String> sanitizedMetadata(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, String> kept = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "metadata key must not be null");
            if (FORBIDDEN_METADATA_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (!METADATA_KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("metadata key must match " + METADATA_KEY.pattern());
            }
            String value = Objects.requireNonNull(entry.getValue(), "metadata value must not be null");
            if (value.length() > MAX_METADATA_VALUE_LENGTH) {
                throw new IllegalArgumentException(
                    "metadata value must be at most " + MAX_METADATA_VALUE_LENGTH + " characters");
            }
            kept.put(key, value);
        }
        if (kept.size() > MAX_METADATA_ENTRIES) {
            throw new IllegalArgumentException("metadata must have at most " + MAX_METADATA_ENTRIES + " entries");
        }
        return Collections.unmodifiableMap(kept);
    }

    private static void requireText(String field, String value, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        optionalText(field, value, maxLength);
    }

    private static void optionalText(String field, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
    }

    private static void requireTokens(String field, long value) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
    }

    private static void optionalTokens(String field, Long value) {
        if (value != null) {
            requireTokens(field, value);
        }
    }

    private static void requireUsd(String field, BigDecimal value) {
        if (value == null) {
            return;
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " must not be negative");
        }
        if (value.compareTo(MAX_USD) > 0) {
            throw new IllegalArgumentException(field + " must be at most " + MAX_USD.toPlainString());
        }
    }

    private static long zero(Long value) {
        return value == null ? 0L : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    /** Fills {@code eventId}, {@code requestId}, {@code occurredAt} and {@code sourceType} when left unset. */
    public static final class Builder {

        private String eventId;
        private String requestId;
        private String provider;
        private String model;
        private long promptTokens;
        private long completionTokens;
        private Long reasoningTokens;
        private Long cachedPromptTokens;
        private Long totalTokens;
        private BigDecimal promptCostUsd;
        private BigDecimal completionCostUsd;
        private BigDecimal reasoningCostUsd;
        private BigDecimal cachedPromptCostUsd;
        private BigDecimal totalCostUsd;
        private String pricingPlanId;
        private String pricingVersion;
        private String sourceType = DEFAULT_SOURCE_TYPE;
        private final Map<String, String> metadata = new LinkedHashMap<>();
        private Instant occurredAt;

        private Builder() {
        }

        /** Idempotency key. Defaults to a random UUID; keep it stable when the same call is reported again. */
        public Builder eventId(String eventId) {
            this.eventId = eventId;
            return this;
        }

        /** Defaults to {@link #eventId}. Must be unique per project and environment on the server. */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder promptTokens(long promptTokens) {
            this.promptTokens = promptTokens;
            return this;
        }

        public Builder completionTokens(long completionTokens) {
            this.completionTokens = completionTokens;
            return this;
        }

        public Builder reasoningTokens(Long reasoningTokens) {
            this.reasoningTokens = reasoningTokens;
            return this;
        }

        public Builder cachedPromptTokens(Long cachedPromptTokens) {
            this.cachedPromptTokens = cachedPromptTokens;
            return this;
        }

        /**
         * Explicit total. Without it the server adds prompt, completion, reasoning and cached tokens, which double
         * counts when the prompt/completion counts already include the reasoning and cached parts.
         */
        public Builder totalTokens(Long totalTokens) {
            this.totalTokens = totalTokens;
            return this;
        }

        public Builder promptCostUsd(BigDecimal promptCostUsd) {
            this.promptCostUsd = promptCostUsd;
            return this;
        }

        public Builder completionCostUsd(BigDecimal completionCostUsd) {
            this.completionCostUsd = completionCostUsd;
            return this;
        }

        public Builder reasoningCostUsd(BigDecimal reasoningCostUsd) {
            this.reasoningCostUsd = reasoningCostUsd;
            return this;
        }

        public Builder cachedPromptCostUsd(BigDecimal cachedPromptCostUsd) {
            this.cachedPromptCostUsd = cachedPromptCostUsd;
            return this;
        }

        public Builder totalCostUsd(BigDecimal totalCostUsd) {
            this.totalCostUsd = totalCostUsd;
            return this;
        }

        public Builder pricingPlanId(String pricingPlanId) {
            this.pricingPlanId = pricingPlanId;
            return this;
        }

        public Builder pricingVersion(String pricingVersion) {
            this.pricingVersion = pricingVersion;
            return this;
        }

        public Builder sourceType(String sourceType) {
            this.sourceType = sourceType;
            return this;
        }

        public Builder metadata(String key, String value) {
            this.metadata.put(key, value);
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata.putAll(metadata);
            return this;
        }

        /** Correlates the event with a distributed trace; stored as the {@code trace_id} metadata entry. */
        public Builder traceId(String traceId) {
            if (traceId != null && !traceId.isBlank()) {
                this.metadata.put("trace_id", traceId);
            }
            return this;
        }

        public Builder occurredAt(Instant occurredAt) {
            this.occurredAt = occurredAt;
            return this;
        }

        public UsageEvent build() {
            String id = eventId != null ? eventId : UUID.randomUUID().toString();
            return new UsageEvent(
                id,
                requestId != null ? requestId : id,
                provider,
                model,
                promptTokens,
                completionTokens,
                reasoningTokens,
                cachedPromptTokens,
                totalTokens,
                promptCostUsd,
                completionCostUsd,
                reasoningCostUsd,
                cachedPromptCostUsd,
                totalCostUsd,
                pricingPlanId,
                pricingVersion,
                sourceType,
                metadata,
                occurredAt != null ? occurredAt : Instant.now()
            );
        }
    }
}
