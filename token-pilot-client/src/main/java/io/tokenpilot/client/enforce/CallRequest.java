package io.tokenpilot.client.enforce;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One LLM call to be judged before it is made, in the shape of the DecisionRequest contract. The constructor
 * enforces the contract limits so an invalid request fails here, not as an {@code INDETERMINATE} later.
 *
 * <p>{@code requestId} is the idempotency anchor: decision, reservation and settlement all hang off it. When the
 * same command is retried (e.g. a job re-run), reuse the same {@code requestId}: the Control Plane then returns
 * the original decision and reservation instead of reserving budget twice.
 *
 * <p>{@code estimatedCostUsd} is rounded UP to 6 decimals (the contract's {@code UsdAmount} precision); a
 * slightly high estimate is the safe side of a budget check. Metadata keys that carry raw text
 * ({@link io.tokenpilot.client.UsageEvent#FORBIDDEN_METADATA_KEYS}) are removed.
 *
 * @param endpoint call-site name used to pick per-endpoint {@link EnforcementPolicy} settings; not sent
 */
public record CallRequest(
    String requestId,
    String endpoint,
    String provider,
    String model,
    long estimatedInputTokens,
    long maxOutputTokens,
    BigDecimal estimatedCostUsd,
    String pricingVersion,
    Map<String, String> metadata
) {

    public CallRequest {
        Contract.requireIdentifier("requestId", requestId);
        Contract.optionalIdentifier("endpoint", endpoint);
        Contract.requireIdentifier("provider", provider);
        Contract.requireIdentifier("model", model);
        Contract.requireTokens("estimatedInputTokens", estimatedInputTokens);
        Contract.requireTokens("maxOutputTokens", maxOutputTokens);
        estimatedCostUsd = Contract.usd("estimatedCostUsd", estimatedCostUsd);
        Contract.optionalIdentifier("pricingVersion", pricingVersion);
        metadata = Contract.metadata(metadata);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Fills {@code requestId} with a random UUID when left unset. */
    public static final class Builder {

        private String requestId;
        private String endpoint;
        private String provider;
        private String model;
        private long estimatedInputTokens;
        private long maxOutputTokens;
        private BigDecimal estimatedCostUsd;
        private String pricingVersion;
        private final Map<String, String> metadata = new LinkedHashMap<>();

        private Builder() {
        }

        /** Keep it stable when the same command is retried; defaults to a random UUID. */
        public Builder requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        /** Call-site name for per-endpoint enforcement settings, e.g. {@code checkout-summary}. */
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
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

        public Builder estimatedInputTokens(long estimatedInputTokens) {
            this.estimatedInputTokens = estimatedInputTokens;
            return this;
        }

        public Builder maxOutputTokens(long maxOutputTokens) {
            this.maxOutputTokens = maxOutputTokens;
            return this;
        }

        public Builder estimatedCostUsd(BigDecimal estimatedCostUsd) {
            this.estimatedCostUsd = estimatedCostUsd;
            return this;
        }

        public Builder pricingVersion(String pricingVersion) {
            this.pricingVersion = pricingVersion;
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

        public CallRequest build() {
            return new CallRequest(
                requestId != null ? requestId : UUID.randomUUID().toString(),
                endpoint,
                provider,
                model,
                estimatedInputTokens,
                maxOutputTokens,
                estimatedCostUsd,
                pricingVersion,
                metadata
            );
        }
    }
}
