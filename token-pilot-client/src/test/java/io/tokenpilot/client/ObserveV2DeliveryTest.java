package io.tokenpilot.client;

import static io.tokenpilot.client.testing.FakeControlPlane.error;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.client.testing.FakeControlPlane.Responder;

class ObserveV2DeliveryTest {

    private FakeControlPlane server;
    private TokenPilotClient client;
    private final List<String> drops = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private TokenPilotClient client() {
        return TokenPilotClient.create(TokenPilotClientConfig.builder()
            .endpoint(server.uri())
            .ingestionApi(IngestionApi.OBSERVE_V2)
            .apiKey("tpk_live_test_secret")
            .projectKey("support-copilot")
            .environment("prod")
            .flushInterval(Duration.ofHours(1))
            .initialBackoff(Duration.ofMillis(5))
            .maxBackoff(Duration.ofMillis(20))
            .shutdownTimeout(Duration.ofSeconds(2))
            .deliveryListener(new DeliveryListener() {
                @Override
                public void onDropped(UsageEvent event, DropReason reason, String detail) {
                    drops.add(event.eventId() + ":" + reason + ":" + detail);
                }
            })
            .build());
    }

    private static UsageEvent event(String id) {
        return UsageEvent.builder()
            .eventId(id)
            .requestId("req-" + id)
            .provider("openai")
            .model("gpt-4o-mini")
            .promptTokens(1200)
            .completionTokens(400)
            .cachedPromptTokens(200L)
            .reasoningTokens(50L)
            .totalTokens(1600L)
            .totalCostUsd(new BigDecimal("0.00042"))
            .pricingVersion("2026-05-01")
            .traceId("trace-1")
            .occurredAt(Instant.parse("2026-10-07T01:02:03.123456Z"))
            .build();
    }

    @Test
    void sendsEachEventInTheObserveContractShapeWithMetadataButWithoutClientCosts() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.observeDeduplicating());
        client = client();

        client.record(event("evt-1"));
        client.record(event("evt-2"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(2)
            .allSatisfy(request -> assertThat(request.path()).isEqualTo("/api/v2/observe/events"));
        FakeControlPlane.Request first = server.requests().get(0);
        assertThat(first.header("X-API-Key")).isEqualTo("tpk_live_test_secret");
        assertThat(first.json()).containsOnlyKeys("projectKey", "environment", "eventId", "requestId",
                "schemaVersion", "tokenSemantics", "provider", "model", "callStatus", "promptTokens",
                "completionTokens", "cachedPromptTokens", "reasoningTokens", "metadata", "occurredAt")
            .containsEntry("projectKey", "support-copilot")
            .containsEntry("environment", "prod")
            .containsEntry("eventId", "evt-1")
            .containsEntry("requestId", "req-evt-1")
            .containsEntry("schemaVersion", "observe-v1")
            .containsEntry("tokenSemantics", "INCLUSIVE_V1")
            .containsEntry("callStatus", "SUCCEEDED")
            .containsEntry("occurredAt", "2026-10-07T01:02:03.123456Z")
            .containsEntry("metadata", java.util.Map.of("trace_id", "trace-1"));
        assertThat(first.json().get("promptTokens").toString()).isEqualTo("1200");
        assertThat(first.json().get("cachedPromptTokens").toString()).isEqualTo("200");
        assertThat(client.stats().created()).isEqualTo(2);
        assertThat(drops).isEmpty();
    }

    @Test
    void aLostResponseIsResentByteForByteAndTheReplayCountsAsDuplicate() throws Exception {
        Responder dedup = FakeControlPlane.observeDeduplicating();
        server = FakeControlPlane.start((request, number) -> {
            FakeControlPlane.Reply reply = dedup.reply(request, number);
            return number == 1 ? error(502, "BAD_GATEWAY") : reply;
        });
        client = client();

        client.record(event("evt-1"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(2);
        assertThat(server.requests().get(1).body()).isEqualTo(server.requests().get(0).body());
        assertThat(client.stats().duplicates()).isEqualTo(1);
        assertThat(client.stats().created()).isZero();
        assertThat(drops).isEmpty();
    }

    @Test
    void aServerFailureMidBatchStopsTheRoundAndResendsOnlyWhatWasNotStored() throws Exception {
        Responder dedup = FakeControlPlane.observeDeduplicating();
        server = FakeControlPlane.start((request, number) -> number == 2
            ? error(503, "INGESTION-503")
            : dedup.reply(request, number));
        client = client();

        client.record(event("evt-1"));
        client.record(event("evt-2"));
        client.record(event("evt-3"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        // Round 1: evt-1 stored, evt-2 failed, evt-3 not attempted. Round 2: evt-2 and evt-3 only.
        assertThat(server.requests()).extracting(request -> request.json().get("eventId"))
            .containsExactly("evt-1", "evt-2", "evt-2", "evt-3");
        assertThat(client.stats().created()).isEqualTo(3);
        assertThat(client.stats().retried()).isEqualTo(2);
        assertThat(drops).isEmpty();
    }

    @Test
    void aConflictDropsOnlyThatEvent() throws Exception {
        server = FakeControlPlane.start((request, number) -> "evt-conflict".equals(request.json().get("eventId"))
            ? error(409, "INGESTION-409")
            : FakeControlPlane.observeReply(201, request.json(), false));
        client = client();

        client.record(event("evt-conflict"));
        client.record(event("evt-ok"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(2);
        assertThat(client.stats().created()).isEqualTo(1);
        assertThat(drops).containsExactly("evt-conflict:REJECTED:HTTP 409 INGESTION-409");
    }

    @Test
    void aDisabledEndpointRefusesTheRoundWithoutRetrying() throws Exception {
        server = FakeControlPlane.start((request, number) -> error(404, "COMMON-404"));
        client = client();

        client.record(event("evt-1"));
        client.record(event("evt-2"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(1);
        assertThat(client.stats().dropped()).containsEntry(DropReason.REQUEST_REFUSED, 2L);
        assertThat(client.stats().retried()).isZero();
    }
}
