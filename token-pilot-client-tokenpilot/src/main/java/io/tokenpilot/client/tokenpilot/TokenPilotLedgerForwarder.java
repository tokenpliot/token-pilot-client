package io.tokenpilot.client.tokenpilot;

import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.UsageEvent;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;

/**
 * Sends every Token Pilot ledger record to the Control Plane through a {@link TokenPilotClient}.
 *
 * <p>Runs on the thread that recorded the cost, right after the LLM call. It only builds an event and queues it;
 * an event that cannot be built is counted ({@link #invalidEvents()}) and logged, never thrown, so the recorded
 * call is unaffected.
 *
 * <p>Mapping notes:
 * <ul>
 *   <li>Token Pilot input/output totals already include cached and reasoning tokens, so {@code totalTokens} is
 *       sent explicitly; letting the server add the parts would double count them.</li>
 *   <li>Only a USD cost is sent as {@code totalCostUsd}. Other currencies are not converted; the currency is kept
 *       in metadata ({@code cost_currency}) and the server stores no cost for the event.</li>
 *   <li>Ledger tags hold every String value of the call context, so they are not forwarded wholesale: only
 *       the request id and explicitly configured tags become metadata.</li>
 *   <li>Each ledger record gets its own {@code eventId}/{@code requestId}; the application's request id goes to
 *       {@code app_request_id} metadata because one request can produce several ledger records.</li>
 * </ul>
 */
public final class TokenPilotLedgerForwarder implements LedgerListener {

    /** Ledger tag the Spring AI adapter fills with the call's request id. */
    public static final String REQUEST_ID_TAG = "tokenpilot.request.id";
    public static final String DEFAULT_PRICING_VERSION = "tokenpilot-local";
    public static final String SOURCE_TYPE = "tokenpilot-java";

    private static final System.Logger LOG = System.getLogger(TokenPilotLedgerForwarder.class.getName());
    private static final Currency USD = Currency.getInstance("USD");

    private final TokenPilotClient client;
    private final String provider;
    private final String pricingVersion;
    private final Map<String, String> forwardedTags;
    private final Supplier<String> traceIdSupplier;
    private final Clock clock;
    private final LongAdder invalidEvents = new LongAdder();

    private TokenPilotLedgerForwarder(Builder builder) {
        this.client = Objects.requireNonNull(builder.client, "client must not be null");
        if (builder.provider == null || builder.provider.isBlank()) {
            throw new IllegalArgumentException("provider must not be blank");
        }
        this.provider = builder.provider;
        this.pricingVersion = builder.pricingVersion;
        this.forwardedTags = Map.copyOf(builder.forwardedTags);
        this.traceIdSupplier = builder.traceIdSupplier;
        this.clock = builder.clock;
    }

    public static Builder builder(TokenPilotClient client) {
        return new Builder(client);
    }

    @Override
    public void onRecord(CostRecordedEvent event) {
        try {
            client.record(toUsageEvent(event));
        } catch (RuntimeException exception) {
            invalidEvents.increment();
            LOG.log(Level.WARNING, "Token Pilot ledger record not sent to the Control Plane: {0}",
                exception.getMessage());
        }
    }

    /** Ledger records that could not be turned into a valid event (and so were never queued). */
    public long invalidEvents() {
        return invalidEvents.sum();
    }

    UsageEvent toUsageEvent(CostRecordedEvent event) {
        TokenUsage usage = event.usage();
        TokenUsageDetails details = usage.details();
        Map<String, String> tags = event.tags() == null ? Map.of() : event.tags();

        UsageEvent.Builder builder = UsageEvent.builder()
            .provider(provider)
            .model(event.modelId())
            .promptTokens(usage.inputTokens())
            .completionTokens(usage.outputTokens())
            .reasoningTokens(details.reasoningOutputTokens())
            .cachedPromptTokens(details.cacheReadInputTokens())
            .totalTokens(Math.addExact(usage.inputTokens(), usage.outputTokens()))
            .pricingVersion(pricingVersion)
            .sourceType(SOURCE_TYPE)
            .occurredAt(clock.instant());

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("usage_source", usage.source().name().toLowerCase(Locale.ROOT));
        if (event.cost() != null) {
            if (USD.equals(event.cost().currency())) {
                builder.totalCostUsd(event.cost().value());
            } else {
                metadata.put("cost_currency", event.cost().currency().getCurrencyCode());
            }
        }
        putIfFits(metadata, "app_request_id", tags.get(REQUEST_ID_TAG));
        forwardedTags.forEach((tag, key) -> putIfFits(metadata, key, tags.get(tag)));
        if (traceIdSupplier != null) {
            putIfFits(metadata, "trace_id", traceIdSupplier.get());
        }
        return builder.metadata(metadata).build();
    }

    private static void putIfFits(Map<String, String> metadata, String key, String value) {
        if (value != null && !value.isBlank() && value.length() <= UsageEvent.MAX_METADATA_VALUE_LENGTH) {
            metadata.put(key, value);
        }
    }

    public static final class Builder {

        private final TokenPilotClient client;
        private String provider;
        private String pricingVersion = DEFAULT_PRICING_VERSION;
        private final Map<String, String> forwardedTags = new LinkedHashMap<>();
        private Supplier<String> traceIdSupplier;
        private Clock clock = Clock.systemUTC();

        private Builder(TokenPilotClient client) {
            this.client = client;
        }

        /** LLM provider name, e.g. {@code openai}. Ledger records do not carry it. */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /** Label of the price table the local ledger used; defaults to {@code tokenpilot-local}. */
        public Builder pricingVersion(String pricingVersion) {
            this.pricingVersion = pricingVersion;
            return this;
        }

        /**
         * Forwards ledger tag {@code tag} as metadata entry {@code metadataKey} (pattern {@code [a-z][a-z0-9_.-]*}).
         * Only forward tags that never contain prompt or completion text.
         */
        public Builder forwardTag(String tag, String metadataKey) {
            forwardedTags.put(Objects.requireNonNull(tag, "tag must not be null"),
                Objects.requireNonNull(metadataKey, "metadataKey must not be null"));
            return this;
        }

        /** Called on the recording thread, so thread-bound trace context (e.g. the current span) is visible. */
        public Builder traceIdSupplier(Supplier<String> traceIdSupplier) {
            this.traceIdSupplier = traceIdSupplier;
            return this;
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock, "clock must not be null");
            return this;
        }

        public TokenPilotLedgerForwarder build() {
            return new TokenPilotLedgerForwarder(this);
        }
    }
}
