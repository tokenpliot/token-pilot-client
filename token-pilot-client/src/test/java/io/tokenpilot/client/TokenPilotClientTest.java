package io.tokenpilot.client;

import static io.tokenpilot.client.testing.FakeControlPlane.batchReply;
import static io.tokenpilot.client.testing.FakeControlPlane.error;
import static io.tokenpilot.client.testing.FakeControlPlane.itemResult;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.client.testing.FakeControlPlane.Reply;

class TokenPilotClientTest {

    private FakeControlPlane server;
    private TokenPilotClient client;
    private final List<String> drops = new CopyOnWriteArrayList<>();

    private final DeliveryListener recordingListener = new DeliveryListener() {
        @Override
        public void onDropped(UsageEvent event, DropReason reason, String detail) {
            drops.add(event.eventId() + ":" + reason + ":" + detail);
        }
    };

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private TokenPilotClientConfig.Builder config() {
        return TokenPilotClientConfig.builder()
            .endpoint(server.uri())
            .apiKey("tpk_live_test_secret")
            .projectKey("support-copilot")
            .environment("prod")
            .flushInterval(Duration.ofHours(1))
            .initialBackoff(Duration.ofMillis(5))
            .maxBackoff(Duration.ofMillis(20))
            .shutdownTimeout(Duration.ofSeconds(2))
            .deliveryListener(recordingListener);
    }

    private static UsageEvent event(String id) {
        return UsageEvent.builder()
            .eventId(id)
            .provider("openai")
            .model("gpt-4o-mini")
            .promptTokens(1200)
            .completionTokens(400)
            .totalTokens(1600L)
            .totalCostUsd(new BigDecimal("0.00042"))
            .pricingVersion("2026-05-01")
            .metadata("feature", "요약 \"quoted\"\n")
            .occurredAt(Instant.parse("2026-10-07T01:02:03.123456Z"))
            .build();
    }

    @Test
    void sendsQueuedEventsAsOneBatchInTheIngestionContractShape() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        client = TokenPilotClient.create(config().build());

        assertThat(client.record(event("evt-1"))).isTrue();
        assertThat(client.record(event("evt-2"))).isTrue();
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(1);
        FakeControlPlane.Request request = server.requests().get(0);
        assertThat(request.header("X-API-Key")).isEqualTo("tpk_live_test_secret");
        assertThat(request.json()).containsEntry("projectKey", "support-copilot")
            .containsEntry("environment", "prod").containsEntry("schemaVersion", "2026-10-01");
        Map<String, Object> item = request.items().get(0);
        assertThat(item).containsEntry("eventId", "evt-1").containsEntry("requestId", "evt-1")
            .containsEntry("provider", "openai").containsEntry("model", "gpt-4o-mini")
            .containsEntry("pricingVersion", "2026-05-01").containsEntry("sourceType", "sdk-java")
            .containsEntry("occurredAt", "2026-10-07T01:02:03.123456Z")
            .containsEntry("metadata", Map.of("feature", "요약 \"quoted\"\n"));
        assertThat(new BigDecimal(item.get("totalCostUsd").toString())).isEqualByComparingTo("0.00042");
        assertThat(client.stats().created()).isEqualTo(2);
        assertThat(client.stats().queued()).isZero();
    }

    @Test
    void aLostResponseIsResentByteForByteAndCountedAsDuplicate() throws Exception {
        Map<String, Map<String, Object>> stored = new ConcurrentHashMap<>();
        server = FakeControlPlane.start((request, number) -> {
            Reply reply = FakeControlPlane.deduplicate(stored, request);
            // The first response is lost after the server stored the events.
            return number == 1 ? error(502, "BAD_GATEWAY") : reply;
        });
        client = TokenPilotClient.create(config().build());

        client.record(event("evt-1"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(2);
        assertThat(server.requests().get(1).body()).isEqualTo(server.requests().get(0).body());
        assertThat(client.stats().duplicates()).isEqualTo(1);
        assertThat(client.stats().created()).isZero();
        assertThat(client.stats().retried()).isEqualTo(1);
        assertThat(drops).isEmpty();
    }

    @Test
    void onlyRetryableRejectedItemsAreResentAndPermanentRejectionsAreDropped() throws Exception {
        server = FakeControlPlane.start((request, number) -> number == 1
            ? batchReply(List.of(
                itemResult(0, "CREATED", null, false),
                itemResult(1, "REJECTED", "INGESTION-503", true),
                itemResult(2, "REJECTED", "INGESTION-409", false)))
            : batchReply(List.of(itemResult(0, "CREATED", null, false))));
        client = TokenPilotClient.create(config().build());

        client.record(event("evt-ok"));
        client.record(event("evt-retry"));
        client.record(event("evt-conflict"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(2);
        assertThat(server.requests().get(1).items()).extracting(item -> item.get("eventId"))
            .containsExactly("evt-retry");
        assertThat(client.stats().created()).isEqualTo(2);
        assertThat(drops).containsExactly("evt-conflict:REJECTED:INGESTION-409");
    }

    @Test
    void aRefusedRequestIsDroppedWithoutRetrying() throws Exception {
        server = FakeControlPlane.start((request, number) -> error(401, "COMMON-401"));
        client = TokenPilotClient.create(config().build());

        client.record(event("evt-1"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSize(1);
        assertThat(drops).containsExactly("evt-1:REQUEST_REFUSED:HTTP 401 COMMON-401");
        assertThat(client.stats().dropped()).containsEntry(DropReason.REQUEST_REFUSED, 1L);
    }

    @Test
    void anUnreachableControlPlaneExhaustsTheBoundedRetries() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        client = TokenPilotClient.create(config().endpoint(FakeControlPlane.UNREACHABLE).maxAttempts(3).build());

        client.record(event("evt-1"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(client.stats().dropped()).containsEntry(DropReason.RETRIES_EXHAUSTED, 1L);
        assertThat(client.stats().retried()).isEqualTo(2);
    }

    @Test
    void recordNeverBlocksWhileTheControlPlaneHangs() throws Exception {
        server = FakeControlPlane.start((request, number) -> {
            server.awaitRelease();
            return error(503, "INGESTION-503");
        });
        client = TokenPilotClient.create(config().queueCapacity(10).maxBatchSize(1)
            .requestTimeout(Duration.ofSeconds(30)).shutdownTimeout(Duration.ofMillis(200)).build());

        long start = System.nanoTime();
        int accepted = 0;
        for (int i = 0; i < 1000; i++) {
            if (client.record(event("evt-" + i))) {
                accepted++;
            }
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMillis).isLessThan(500);
        assertThat(accepted).isLessThanOrEqualTo(11);
        assertThat(client.stats().dropped().get(DropReason.QUEUE_FULL)).isEqualTo(1000L - accepted);

        client.close();
        assertThat(client.stats().queued()).isZero();
        assertThat(client.stats().dropped().get(DropReason.SHUTDOWN_TIMEOUT)).isEqualTo((long) accepted);
        assertThat(client.record(event("after-close"))).isFalse();
        assertThat(client.stats().dropped()).containsEntry(DropReason.CLOSED, 1L);
    }

    @Test
    void closeDeliversWhatIsStillQueued() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        client = TokenPilotClient.create(config().build());

        for (int i = 0; i < 250; i++) {
            client.record(event("evt-" + i));
        }
        client.close();

        assertThat(client.stats().created()).isEqualTo(250);
        assertThat(server.requests()).allSatisfy(request -> assertThat(request.items()).hasSizeLessThanOrEqualTo(100));
    }

    @Test
    void aTooLargeResponseSplitsTheBatchAndASingleOversizedEventIsDropped() throws Exception {
        Map<String, Map<String, Object>> stored = new ConcurrentHashMap<>();
        server = FakeControlPlane.start((request, number) -> request.items().size() > 1
            ? error(413, "INGESTION-413")
            : request.items().get(0).get("eventId").equals("evt-huge")
                ? error(413, "INGESTION-413")
                : FakeControlPlane.deduplicate(stored, request));
        client = TokenPilotClient.create(config().build());

        client.record(event("evt-1"));
        client.record(event("evt-2"));
        client.record(event("evt-huge"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(client.stats().created()).isEqualTo(2);
        assertThat(drops).containsExactly("evt-huge:PAYLOAD_TOO_LARGE:HTTP 413");
    }

    @Test
    void batchesAreSplitBeforeSendingToStayUnderTheByteLimit() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        client = TokenPilotClient.create(config().maxBatchBytes(4096).build());

        for (int i = 0; i < 20; i++) {
            client.record(UsageEvent.builder().eventId("evt-" + i).provider("openai").model("m")
                .pricingVersion("v").metadata("note", "x".repeat(250)).build());
        }
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(server.requests()).hasSizeGreaterThan(1)
            .allSatisfy(request -> assertThat(request.body().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .hasSizeLessThanOrEqualTo(4096));
        assertThat(client.stats().created()).isEqualTo(20);
    }

    @Test
    void anOlderServerWithoutItemResultsIsReadFromRejectedItems() throws Exception {
        server = FakeControlPlane.start((request, number) -> new Reply(200,
            "{\"data\":{\"acceptedCount\":1,\"rejectedCount\":1,\"rejectedItems\":"
                + "[{\"index\":1,\"code\":\"COMMON-400\",\"message\":\"x\"}]}}"));
        client = TokenPilotClient.create(config().build());

        client.record(event("evt-ok"));
        client.record(event("evt-bad"));
        assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

        assertThat(client.stats().created()).isEqualTo(1);
        assertThat(drops).containsExactly("evt-bad:REJECTED:COMMON-400");
    }

    @Test
    void theApiKeyIsNotPrintedByTheConfig() throws IOException {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());
        assertThat(config().build().toString()).doesNotContain("tpk_live_test_secret");
    }
}
