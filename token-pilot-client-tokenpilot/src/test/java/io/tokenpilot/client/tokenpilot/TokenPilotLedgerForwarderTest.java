package io.tokenpilot.client.tokenpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.tokenpilot.client.DropReason;
import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.UsageEvent;
import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;
import io.tokenpilot.core.internal.LedgerComponents;

class TokenPilotLedgerForwarderTest {

    private static final Currency USD = Currency.getInstance("USD");

    private FakeControlPlane server;
    private TokenPilotClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private TokenPilotClientConfig.Builder config(String endpoint) {
        return TokenPilotClientConfig.builder()
            .endpoint(endpoint)
            .apiKey("tpk_live_test_secret")
            .projectKey("support-copilot")
            .environment("prod")
            .flushInterval(Duration.ofHours(1))
            .initialBackoff(Duration.ofMillis(5))
            .maxBackoff(Duration.ofMillis(10))
            .shutdownTimeout(Duration.ofSeconds(1));
    }

    private TokenPilotLedgerForwarder.Builder forwarder() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        client = TokenPilotClient.create(config(server.uri().toString()).build());
        return TokenPilotLedgerForwarder.builder(client).provider("openai")
            .clock(Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC));
    }

    private static TokenUsage usage(long input, long output, Long cacheRead, Long reasoning) {
        return new TokenUsage(input, output, new TokenUsageDetails(cacheRead, null, reasoning),
            UsageSource.PROVIDER_REPORTED, Map.of());
    }

    @Test
    void inclusiveTokenPilotTotalsAreSentAsAnExplicitTotal() throws Exception {
        UsageEvent event = forwarder().build().toUsageEvent(new CostRecordedEvent("gpt-4o-mini",
            usage(1000, 300, 200L, 100L), Cost.of(new BigDecimal("0.0123"), USD), Map.of()));

        assertThat(event.promptTokens()).isEqualTo(1000);
        assertThat(event.completionTokens()).isEqualTo(300);
        assertThat(event.cachedPromptTokens()).isEqualTo(200);
        assertThat(event.reasoningTokens()).isEqualTo(100);
        // Not 1600: the cached and reasoning tokens are already inside the input and output totals.
        assertThat(event.totalTokens()).isEqualTo(1300);
        assertThat(event.totalCostUsd()).isEqualByComparingTo("0.0123");
        assertThat(event.provider()).isEqualTo("openai");
        assertThat(event.model()).isEqualTo("gpt-4o-mini");
        assertThat(event.sourceType()).isEqualTo("tokenpilot-java");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-10-07T00:00:00Z"));
        assertThat(event.metadata()).containsEntry("usage_source", "provider_reported");
    }

    @Test
    void aNonUsdCostIsNotSentAsUsd() throws Exception {
        UsageEvent event = forwarder().build().toUsageEvent(new CostRecordedEvent("m", usage(1, 1, null, null),
            Cost.of(new BigDecimal("15"), Currency.getInstance("KRW")), Map.of()));

        assertThat(event.totalCostUsd()).isNull();
        assertThat(event.metadata()).containsEntry("cost_currency", "KRW");
    }

    @Test
    void onlyTheRequestIdConfiguredTagsAndTraceIdBecomeMetadata() throws Exception {
        UsageEvent event = forwarder()
            .forwardTag("tenant", "tenant_id")
            .traceIdSupplier(() -> "4bf92f3577b34da6a3ce929d0e0e4736")
            .build()
            .toUsageEvent(new CostRecordedEvent("m", usage(1, 1, null, null), Cost.zero(USD), Map.of(
                TokenPilotLedgerForwarder.REQUEST_ID_TAG, "req-42",
                "tenant", "acme",
                "chat_memory_conversation_id", "SECRET context value")));

        assertThat(event.metadata())
            .containsEntry("app_request_id", "req-42")
            .containsEntry("tenant_id", "acme")
            .containsEntry("trace_id", "4bf92f3577b34da6a3ce929d0e0e4736")
            .doesNotContainValue("SECRET context value");
        // One request can produce several ledger records, so the server key is per event.
        assertThat(event.requestId()).isEqualTo(event.eventId()).isNotEqualTo("req-42");
    }

    @Test
    void ledgerRecordsFlowFromTheTokenPilotLedgerToTheControlPlane() throws Exception {
        TokenPilotLedgerForwarder forwarder = forwarder().build();
        LedgerManager ledger = LedgerComponents.defaultLedgerManager(
            LedgerComponents.inMemoryPricingRegistry(List.of(() -> List.of(new PricingPlan(
                "gpt-4o-mini", PricingPlan.DEFAULT_PRICING_POLICY_ID,
                Map.of(TokenType.PROMPT, new BigDecimal("0.00015"), TokenType.COMPLETION, new BigDecimal("0.0006")),
                USD)))),
            LedgerComponents.defaultCostCalculator(),
            List.of(forwarder));

        Cost cost = ledger.record("gpt-4o-mini", usage(1000, 500, null, null), Map.of());
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(1);
        Map<String, Object> item = server.requests().get(0).items().get(0);
        assertThat(item).containsEntry("model", "gpt-4o-mini").containsEntry("totalTokens", new BigDecimal("1500"));
        assertThat(new BigDecimal(item.get("totalCostUsd").toString())).isEqualByComparingTo(cost.value());
        assertThat(client.stats().created()).isEqualTo(1);
    }

    @Test
    void ledgerRecordingIsUnaffectedWhenTheControlPlaneIsDown() throws Exception {
        client = TokenPilotClient.create(config(FakeControlPlane.UNREACHABLE.toString()).maxAttempts(2).build());
        LedgerManager ledger = LedgerComponents.defaultLedgerManager(
            LedgerComponents.inMemoryPricingRegistry(List.of()),
            LedgerComponents.defaultCostCalculator(),
            List.of(TokenPilotLedgerForwarder.builder(client).provider("openai").build()));

        long start = System.nanoTime();
        for (int i = 0; i < 200; i++) {
            assertThat(ledger.record("gpt-4o-mini", usage(10, 5, null, null), Map.of())).isNotNull();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(500));

        assertThat(client.flush(Duration.ofSeconds(10))).isTrue();
        assertThat(client.stats().dropped()).as(client.stats().toString())
            .containsEntry(DropReason.RETRIES_EXHAUSTED, 200L);
    }

    @Test
    void aRecordThatCannotBeSentIsCountedNotThrown() throws Exception {
        TokenPilotLedgerForwarder forwarder = forwarder().build();

        forwarder.onRecord(new CostRecordedEvent("m".repeat(101), usage(1, 1, null, null), Cost.zero(USD), Map.of()));

        assertThat(forwarder.invalidEvents()).isEqualTo(1);
        assertThat(client.stats().recorded()).isZero();
    }
}
