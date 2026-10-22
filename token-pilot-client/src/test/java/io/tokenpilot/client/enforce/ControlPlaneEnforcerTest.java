package io.tokenpilot.client.enforce;

import static io.tokenpilot.client.enforce.FakeDecisionServer.approval;
import static io.tokenpilot.client.enforce.FakeDecisionServer.decides;
import static io.tokenpilot.client.enforce.FakeDecisionServer.error;
import static io.tokenpilot.client.enforce.FakeDecisionServer.reasons;
import static io.tokenpilot.client.enforce.FakeDecisionServer.reservation;
import static io.tokenpilot.client.enforce.FakeDecisionServer.settled;
import static io.tokenpilot.client.enforce.FakeDecisionServer.stateFor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import io.tokenpilot.client.enforce.FakeDecisionServer.Reply;

class ControlPlaneEnforcerTest {

    private static final String API_KEY = "tpk_live_test_secret";
    private static final Instant NOW = Instant.parse("2026-10-22T01:02:03Z");
    private static final EnforcementPolicy ENFORCE_ALL = EnforcementPolicy.builder()
        .defaults(EnforcementSettings.enforce())
        .build();

    private FakeDecisionServer server;
    private ControlPlaneEnforcer enforcer;

    private final AtomicInteger providerCalls = new AtomicInteger();
    private final ProviderCall<String> provider = () -> {
        providerCalls.incrementAndGet();
        return "completion";
    };
    private static final UsageReader<String> PRICED_USAGE = response -> new ActualUsage(2950, 720, 1000L, 100L,
        new BigDecimal("0.007375"), new BigDecimal("0.0072"), new BigDecimal("0.014575"));

    private final List<String> decisions = new CopyOnWriteArrayList<>();
    private final List<String> retries = new CopyOnWriteArrayList<>();
    /** onSettled and onSettlementFailed, in order; each settlement ends in exactly one of them. */
    private final BlockingQueue<String> settlementResults = new LinkedBlockingQueue<>();

    private final EnforcementListener listener = new EnforcementListener() {
        @Override
        public void onDecision(CallRequest request, Decision decision, EnforcementAction action) {
            decisions.add(request.requestId() + " " + decision.outcome() + " " + action);
        }

        @Override
        public void onSettled(String reservationId, SettlementType type, String state, boolean duplicate) {
            settlementResults.add("settled " + reservationId + " " + type + " " + state + " duplicate=" + duplicate);
        }

        @Override
        public void onSettlementRetry(String reservationId, SettlementType type, int attempt, String reason) {
            retries.add(reservationId + " " + type + " #" + attempt + " " + reason);
        }

        @Override
        public void onSettlementFailed(String reservationId, SettlementType type, String detail) {
            settlementResults.add("failed " + reservationId + " " + type + " " + detail);
        }
    };

    @BeforeEach
    void startServer() throws IOException {
        server = FakeDecisionServer.start();
    }

    @AfterEach
    void tearDown() {
        if (enforcer != null) {
            enforcer.close();
        }
        server.close();
    }

    private EnforcerConfig.Builder config(EnforcementPolicy policy) {
        return config(server.uri(), policy);
    }

    private EnforcerConfig.Builder config(URI endpoint, EnforcementPolicy policy) {
        return EnforcerConfig.builder()
            .endpoint(endpoint)
            .apiKey(API_KEY)
            .environment("prod")
            .policy(policy)
            .decisionTimeout(Duration.ofSeconds(2))
            .initialBackoff(Duration.ofMillis(5))
            .maxBackoff(Duration.ofMillis(20))
            .shutdownTimeout(Duration.ofSeconds(2))
            .listener(listener)
            .clock(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ControlPlaneEnforcer start(EnforcementPolicy policy) {
        enforcer = ControlPlaneEnforcer.create(config(policy).build());
        return enforcer;
    }

    private static CallRequest request(String requestId) {
        return CallRequest.builder()
            .requestId(requestId)
            .provider("openai")
            .model("gpt-4o")
            .estimatedInputTokens(3000)
            .maxOutputTokens(1000)
            .estimatedCostUsd(new BigDecimal("0.017500"))
            .pricingVersion("openai-2026-08-14")
            .metadata("feature", "support-summary")
            .build();
    }

    private String nextSettlementResult() throws InterruptedException {
        String result = settlementResults.poll(5, TimeUnit.SECONDS);
        assertThat(result).as("settlement result within 5s").isNotNull();
        return result;
    }

    // --- allow and settle ---

    @Test
    void allowedCallRunsOnceAndCommitsTheReservationWithUsageAndCost() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-90ab12")));
        start(ENFORCE_ALL);

        EnforcedResult<String> result = enforcer.execute(request("req-allow"), provider, PRICED_USAGE);

        assertThat(result.response()).isEqualTo("completion");
        assertThat(result.action()).isEqualTo(EnforcementAction.PROCEED);
        assertThat(result.advisory()).isFalse();
        assertThat(result.decision().evaluated()).isTrue();
        assertThat(result.decision().decisionId()).isEqualTo("dec-1");
        assertThat(result.decision().reservation().reservationId()).isEqualTo("rsv-90ab12");
        assertThat(providerCalls).hasValue(1);
        assertThat(nextSettlementResult()).isEqualTo("settled rsv-90ab12 COMMIT COMMITTED duplicate=false");

        FakeDecisionServer.Request decide = server.decisionRequests().get(0);
        assertThat(decide.header("X-API-Key")).isEqualTo(API_KEY);
        assertThat(decide.header("Idempotency-Key")).isEqualTo("dec:req-allow");
        assertThat(decide.header("Content-Type")).isEqualTo("application/json");
        assertThat(decide.header("Accept")).isEqualTo("application/json");
        assertThat(decide.header("User-Agent")).isEqualTo("token-pilot-client-java");
        assertThat(decide.body()).isEqualTo("{\"schemaVersion\":\"2026-10-01\",\"requestId\":\"req-allow\","
            + "\"environment\":\"prod\",\"provider\":\"openai\",\"model\":\"gpt-4o\",\"clientMode\":\"ENFORCE\","
            + "\"estimatedInputTokens\":3000,\"maxOutputTokens\":1000,\"estimatedCostUsd\":\"0.0175\","
            + "\"pricingVersion\":\"openai-2026-08-14\",\"metadata\":{\"feature\":\"support-summary\"}}");

        FakeDecisionServer.Request settle = server.settlementRequests().get(0);
        assertThat(settle.path()).isEqualTo("/api/control/v1/reservations/rsv-90ab12/settlements");
        assertThat(settle.header("Idempotency-Key")).isEqualTo("stl:rsv-90ab12:COMMIT");
        assertThat(settle.header("X-API-Key")).isEqualTo(API_KEY);
        assertThat(settle.body()).isEqualTo("{\"schemaVersion\":\"2026-10-01\",\"requestId\":\"req-allow\","
            + "\"result\":\"COMMIT\",\"usage\":{\"promptTokens\":2950,\"completionTokens\":720,"
            + "\"cachedPromptTokens\":1000,\"reasoningTokens\":100},\"cost\":{\"promptCostUsd\":\"0.007375\","
            + "\"completionCostUsd\":\"0.0072\",\"totalCostUsd\":\"0.014575\"},\"occurredAt\":\"2026-10-22T01:02:03Z\"}");
        assertThat(decisions).containsExactly("req-allow ALLOW PROCEED");
    }

    @Test
    void allowWithoutReservationSettlesNothing() throws Exception {
        start(EnforcementPolicy.observeOnly());

        enforcer.execute(request("req-observe"), provider, PRICED_USAGE);
        enforcer.close();

        assertThat(providerCalls).hasValue(1);
        assertThat(server.settlementRequests()).isEmpty();
        assertThat(settlementResults).isEmpty();
    }

    // --- policy denials by mode ---

    @Test
    void enforcedBudgetDenialBlocksWithoutCallingTheProvider() throws Exception {
        server.decisions(decides("DENY_BUDGET", reasons("MONTHLY_BUDGET_EXCEEDED")));
        start(ENFORCE_ALL);

        EnforcementException blocked = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(request("req-deny"), provider, PRICED_USAGE));

        assertThat(blocked.action()).isEqualTo(EnforcementAction.BLOCK);
        assertThat(blocked.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(blocked.getMessage()).contains("BLOCK", "DENY_BUDGET", "MONTHLY_BUDGET_EXCEEDED")
            .doesNotContain("support-summary", API_KEY);
        assertThat(providerCalls).hasValue(0);
        enforcer.close();
        assertThat(server.settlementRequests()).isEmpty();
        assertThat(decisions).containsExactly("req-deny DENY_BUDGET BLOCK");
    }

    @Test
    void advisoryDenialStillCallsTheProvider() throws Exception {
        server.decisions(decides("DENY_BUDGET", reasons("MONTHLY_BUDGET_EXCEEDED")));
        start(EnforcementPolicy.builder().defaults(EnforcementSettings.advisory()).build());

        EnforcedResult<String> result = enforcer.execute(request("req-advisory"), provider, PRICED_USAGE);

        assertThat(providerCalls).hasValue(1);
        assertThat(result.advisory()).isTrue();
        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(server.decisionRequests().get(0).json()).containsEntry("clientMode", "ADVISORY");
    }

    @Test
    void withoutOptInADenialIsOnlyObserved() throws Exception {
        server.decisions(decides("DENY_POLICY", reasons("MODEL_NOT_ALLOWED")));
        start(EnforcementPolicy.observeOnly());

        EnforcedResult<String> result = enforcer.execute(request("req-observe"), provider, PRICED_USAGE);

        assertThat(providerCalls).hasValue(1);
        assertThat(result.action()).isEqualTo(EnforcementAction.PROCEED);
        assertThat(result.advisory()).isFalse();
        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.DENY_POLICY);
        assertThat(server.decisionRequests().get(0).json()).containsEntry("clientMode", "OBSERVE");
    }

    @Test
    void endpointOverrideEnforcesOnlyThatCallSite() throws Exception {
        server.decisions(decides("DENY_BUDGET", reasons("MONTHLY_BUDGET_EXCEEDED")));
        start(EnforcementPolicy.builder().endpoint("checkout", EnforcementSettings.enforce()).build());

        CallRequest checkout = CallRequest.builder().requestId("req-checkout").endpoint("checkout")
            .provider("openai").model("gpt-4o").build();
        CallRequest search = CallRequest.builder().requestId("req-search").endpoint("search")
            .provider("openai").model("gpt-4o").build();
        CallRequest unnamed = CallRequest.builder().requestId("req-unnamed").provider("openai").model("gpt-4o")
            .build();

        assertThatThrownBy(() -> enforcer.execute(checkout, provider, PRICED_USAGE))
            .isInstanceOf(EnforcementException.class);
        assertThat(enforcer.execute(search, provider, PRICED_USAGE).action()).isEqualTo(EnforcementAction.PROCEED);
        assertThat(enforcer.execute(unnamed, provider, PRICED_USAGE).action()).isEqualTo(EnforcementAction.PROCEED);
        assertThat(providerCalls).hasValue(2);
        assertThat(server.decisionRequests()).extracting(request -> request.json().get("clientMode"))
            .containsExactly("ENFORCE", "OBSERVE", "OBSERVE");
    }

    @Test
    void blockedDecisionReleasesAReservationIssuedAnyway() throws Exception {
        server.decisions(decides("DENY_POLICY", reservation("rsv-odd")));
        start(ENFORCE_ALL);

        assertThatThrownBy(() -> enforcer.execute(request("req-odd"), provider, PRICED_USAGE))
            .isInstanceOf(EnforcementException.class);

        assertThat(providerCalls).hasValue(0);
        assertThat(nextSettlementResult()).isEqualTo("settled rsv-odd RELEASE RELEASED duplicate=false");
    }

    // --- approvals ---

    @Test
    void requiredApprovalInScopeHoldsTheCallWithTheApprovalId() throws Exception {
        server.decisions(decides("REQUIRE_APPROVAL", reasons("MODEL_REQUIRES_APPROVAL"), approval("apr-55c2")));
        start(EnforcementPolicy.builder().defaults(EnforcementSettings.enforce()
            .withEnforcedOutcomes(EnumSet.of(DecisionOutcome.REQUIRE_APPROVAL))).build());

        EnforcementException held = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(request("req-approval"), provider, PRICED_USAGE));

        assertThat(held.action()).isEqualTo(EnforcementAction.HOLD_FOR_APPROVAL);
        assertThat(held.approvalId()).isEqualTo("apr-55c2");
        assertThat(held.decision().outcome()).isEqualTo(DecisionOutcome.REQUIRE_APPROVAL);
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void requiredApprovalOutsideScopeIsAnAdvisory() throws Exception {
        server.decisions(decides("REQUIRE_APPROVAL", approval("apr-55c2")));
        start(ENFORCE_ALL);

        EnforcedResult<String> result = enforcer.execute(request("req-approval"), provider, PRICED_USAGE);

        assertThat(result.advisory()).isTrue();
        assertThat(result.decision().approvalId()).isEqualTo("apr-55c2");
        assertThat(providerCalls).hasValue(1);
    }

    // --- provider failures and missing usage ---

    @Test
    void providerFailureKeepsTheReservationForReconciliationAndRethrows() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-fail")));
        start(ENFORCE_ALL);
        IOException failure = new IOException("provider reset the connection");

        assertThatThrownBy(() -> enforcer.execute(request("req-fail"), () -> {
            throw failure;
        }, PRICED_USAGE)).isSameAs(failure);

        assertThat(nextSettlementResult())
            .isEqualTo("settled rsv-fail RECONCILIATION_REQUIRED RECONCILIATION_REQUIRED duplicate=false");
        FakeDecisionServer.Request settle = server.settlementRequests().get(0);
        assertThat(settle.header("Idempotency-Key")).isEqualTo("stl:rsv-fail:RECONCILIATION_REQUIRED");
        assertThat(settle.json()).containsEntry("result", "RECONCILIATION_REQUIRED")
            .doesNotContainKeys("usage", "cost");
    }

    @Test
    void callThatNeverReachedTheProviderReleasesTheReservation() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-local")));
        start(ENFORCE_ALL);
        CallNotDispatchedException notDispatched = new CallNotDispatchedException("local rate limit");

        assertThatThrownBy(() -> enforcer.execute(request("req-local"), () -> {
            throw notDispatched;
        }, PRICED_USAGE)).isSameAs(notDispatched);

        assertThat(nextSettlementResult()).isEqualTo("settled rsv-local RELEASE RELEASED duplicate=false");
        assertThat(server.settlementRequests().get(0).json()).containsEntry("result", "RELEASE")
            .doesNotContainKeys("usage", "cost");
    }

    @Test
    void responseWithoutUsageNeedsReconciliation() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-nousage")));
        start(ENFORCE_ALL);

        enforcer.execute(request("req-nousage"), provider, response -> null);

        assertThat(nextSettlementResult()).startsWith("settled rsv-nousage RECONCILIATION_REQUIRED");
        assertThat(server.settlementRequests().get(0).json()).doesNotContainKeys("usage", "cost");
    }

    @Test
    void failingUsageReaderCountsAsNoUsage() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-reader")));
        start(ENFORCE_ALL);

        EnforcedResult<String> result = enforcer.execute(request("req-reader"), provider, response -> {
            throw new IllegalStateException("unexpected response shape");
        });

        assertThat(result.response()).isEqualTo("completion");
        assertThat(nextSettlementResult()).startsWith("settled rsv-reader RECONCILIATION_REQUIRED");
    }

    @Test
    void usageWithoutCostNeedsReconciliationButReportsTheTokens() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-unpriced")));
        start(ENFORCE_ALL);

        enforcer.execute(request("req-unpriced"), provider, response -> ActualUsage.of(2950, 720));

        assertThat(nextSettlementResult()).startsWith("settled rsv-unpriced RECONCILIATION_REQUIRED");
        assertThat(server.settlementRequests().get(0).json())
            .containsEntry("usage", Map.of("promptTokens", new BigDecimal("2950"),
                "completionTokens", new BigDecimal("720")))
            .doesNotContainKey("cost");
    }

    // --- idempotency and retries ---

    @Test
    void retriedCommandReusesTheDecisionKeyAndBody() throws Exception {
        server.decisions(decides("ALLOW"));
        start(ENFORCE_ALL);
        CallRequest request = request("req-retried");

        enforcer.execute(request, provider, PRICED_USAGE);
        enforcer.execute(request, provider, PRICED_USAGE);

        List<FakeDecisionServer.Request> sent = server.decisionRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).header("Idempotency-Key")).isEqualTo(sent.get(0).header("Idempotency-Key"))
            .isEqualTo("dec:req-retried");
        assertThat(sent.get(1).body()).isEqualTo(sent.get(0).body());
    }

    @Test
    void settlementIsRetriedWithTheSameKeyAndByteIdenticalBody() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-retry")));
        server.settlements((request, number) -> number == 1
            ? error(503, "CONTROL_PLANE_UNAVAILABLE", "CONTROL_PLANE_UNAVAILABLE")
            : settled(stateFor(request), true));
        start(ENFORCE_ALL);

        enforcer.execute(request("req-retry"), provider, PRICED_USAGE);

        assertThat(nextSettlementResult()).isEqualTo("settled rsv-retry COMMIT COMMITTED duplicate=true");
        List<FakeDecisionServer.Request> sent = server.settlementRequests();
        assertThat(sent).hasSize(2);
        assertThat(sent.get(1).header("Idempotency-Key")).isEqualTo(sent.get(0).header("Idempotency-Key"))
            .isEqualTo("stl:rsv-retry:COMMIT");
        assertThat(sent.get(1).body()).isEqualTo(sent.get(0).body());
        assertThat(retries).containsExactly("rsv-retry COMMIT #1 HTTP 503 CONTROL_PLANE_UNAVAILABLE");
    }

    @Test
    void conflictingSettlementFailsWithoutRetry() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-conflict")));
        server.settlements((request, number) -> error(409, "IDEMPOTENCY_CONFLICT", "INDETERMINATE"));
        start(ENFORCE_ALL);

        enforcer.execute(request("req-conflict"), provider, PRICED_USAGE);

        assertThat(nextSettlementResult()).isEqualTo("failed rsv-conflict COMMIT HTTP 409 IDEMPOTENCY_CONFLICT");
        assertThat(server.settlementRequests()).hasSize(1);
        assertThat(retries).isEmpty();
    }

    @Test
    void settlementGivesUpAfterMaxAttempts() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-down")));
        server.settlements((request, number) -> error(503, "CONTROL_PLANE_UNAVAILABLE", "CONTROL_PLANE_UNAVAILABLE"));
        enforcer = ControlPlaneEnforcer.create(config(ENFORCE_ALL).settlementMaxAttempts(3).build());

        enforcer.execute(request("req-down"), provider, PRICED_USAGE);

        assertThat(nextSettlementResult())
            .isEqualTo("failed rsv-down COMMIT retries exhausted: HTTP 503 CONTROL_PLANE_UNAVAILABLE");
        assertThat(server.settlementRequests()).hasSize(3);
        assertThat(retries).hasSize(2);
    }

    // --- Control Plane failures ---

    @Test
    void hungControlPlaneCountsAsUnavailableWithinTheTimeoutAndFailsOpen() throws Exception {
        server.decisions((request, number) -> {
            server.awaitRelease();
            return decides("DENY_POLICY").reply(request, number);
        });
        enforcer = ControlPlaneEnforcer.create(config(ENFORCE_ALL).decisionTimeout(Duration.ofMillis(300)).build());

        long started = System.nanoTime();
        EnforcedResult<String> result = enforcer.execute(request("req-hung"), provider, PRICED_USAGE);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(elapsedMillis).isLessThan(1500);
        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(result.decision().httpStatus()).isZero();
        assertThat(result.decision().errorCode()).isEqualTo("HttpTimeoutException");
        assertThat(result.decision().evaluated()).isFalse();
        assertThat(result.advisory()).isTrue();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void hungControlPlaneBlocksAsUnavailableWhenFailClosed() {
        server.decisions((request, number) -> {
            server.awaitRelease();
            return decides("ALLOW").reply(request, number);
        });
        EnforcementPolicy failClosed = EnforcementPolicy.builder()
            .defaults(EnforcementSettings.enforce().withOnUnavailable(FailureBehavior.FAIL_CLOSED))
            .build();
        enforcer = ControlPlaneEnforcer.create(config(failClosed).decisionTimeout(Duration.ofMillis(300)).build());

        EnforcementException blocked = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(request("req-hung"), provider, PRICED_USAGE));

        assertThat(blocked.action()).isEqualTo(EnforcementAction.BLOCK);
        assertThat(blocked.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE)
            .isNotEqualTo(DecisionOutcome.DENY_POLICY);
        assertThat(blocked.getMessage()).contains("CONTROL_PLANE_UNAVAILABLE");
        assertThat(providerCalls).hasValue(0);
    }

    @ParameterizedTest
    @EnumSource(FailureBehavior.class)
    void unreachableControlPlaneIsUnavailableNeverDenied(FailureBehavior onUnavailable) throws Exception {
        EnforcementPolicy policy = EnforcementPolicy.builder()
            .defaults(EnforcementSettings.enforce().withOnUnavailable(onUnavailable))
            .build();
        enforcer = ControlPlaneEnforcer.create(config(FakeDecisionServer.UNREACHABLE, policy).build());

        if (onUnavailable == FailureBehavior.FAIL_OPEN) {
            EnforcedResult<String> result = enforcer.execute(request("req-down"), provider, PRICED_USAGE);
            assertThat(result.advisory()).isTrue();
            assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
            assertThat(result.decision().httpStatus()).isZero();
            assertThat(result.decision().errorCode()).isNotBlank();
            assertThat(providerCalls).hasValue(1);
        } else {
            EnforcementException blocked = catchThrowableOfType(EnforcementException.class,
                () -> enforcer.execute(request("req-down"), provider, PRICED_USAGE));
            assertThat(blocked.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
            assertThat(providerCalls).hasValue(0);
        }
    }

    @Test
    void revokedKeyIsIndeterminateEvenIfTheBodyClaimsADenial() throws Exception {
        server.decisions((request, number) -> error(401, "UNAUTHORIZED", "DENY_POLICY"));
        start(EnforcementPolicy.builder()
            .defaults(EnforcementSettings.enforce().withOnUnavailable(FailureBehavior.FAIL_CLOSED))
            .build());

        EnforcedResult<String> result = enforcer.execute(request("req-401"), provider, PRICED_USAGE);

        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(result.decision().httpStatus()).isEqualTo(401);
        assertThat(result.decision().errorCode()).isEqualTo("UNAUTHORIZED");
        assertThat(result.advisory()).isTrue();
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void unavailableResponseIsNeverADenialAndCarriesRetryAfter() throws Exception {
        server.decisions((request, number) -> new Reply(503,
            error(503, "CONTROL_PLANE_UNAVAILABLE", "DENY_POLICY").body(), Map.of("Retry-After", "7")));
        start(ENFORCE_ALL);

        EnforcedResult<String> result = enforcer.execute(request("req-503"), provider, PRICED_USAGE);

        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(result.decision().outcome().isPolicyDenial()).isFalse();
        assertThat(result.decision().errorCode()).isEqualTo("CONTROL_PLANE_UNAVAILABLE");
        assertThat(result.decision().retryAfter()).isEqualTo(Duration.ofSeconds(7));
        assertThat(result.advisory()).isTrue();
    }

    @Test
    void unreadableJudgmentBlocksOnlyAsIndeterminateWhenFailClosed() {
        server.decisions((request, number) -> FakeDecisionServer.ok("{\"requestId\":\"req-garbled\","
            + "\"outcome\":\"DENY_EVERYTHING\"}"));
        start(EnforcementPolicy.builder()
            .defaults(EnforcementSettings.enforce().withOnIndeterminate(FailureBehavior.FAIL_CLOSED))
            .build());

        EnforcementException blocked = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(request("req-garbled"), provider, PRICED_USAGE));

        assertThat(blocked.decision().outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(blocked.decision().errorCode()).isEqualTo("UNREADABLE_RESPONSE");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void failingListenerDoesNotBreakTheCall() throws Exception {
        enforcer = ControlPlaneEnforcer.create(config(ENFORCE_ALL).listener(new EnforcementListener() {
            @Override
            public void onDecision(CallRequest request, Decision decision, EnforcementAction action) {
                throw new IllegalStateException("listener bug");
            }
        }).build());

        assertThat(enforcer.execute(request("req-listener"), provider, PRICED_USAGE).response())
            .isEqualTo("completion");
    }

    // --- close ---

    @Test
    void closeDeliversPendingSettlementsBeforeReturning() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-close")));
        server.settlements((request, number) -> {
            Thread.sleep(200);
            return settled(stateFor(request), false);
        });
        start(ENFORCE_ALL);

        enforcer.execute(request("req-close"), provider, PRICED_USAGE);
        enforcer.close();

        assertThat(settlementResults).containsExactly("settled rsv-close COMMIT COMMITTED duplicate=false");
        assertThatThrownBy(() -> enforcer.execute(request("req-late"), provider, PRICED_USAGE))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("enforcer is closed");
        assertThat(server.decisionRequests()).hasSize(1);
    }

    @Test
    void closeReportsSettlementsItCouldNotDeliverInTime() throws Exception {
        server.decisions(decides("ALLOW", reservation("rsv-stuck")));
        server.settlements((request, number) -> error(503, "CONTROL_PLANE_UNAVAILABLE", "CONTROL_PLANE_UNAVAILABLE"));
        enforcer = ControlPlaneEnforcer.create(config(ENFORCE_ALL)
            .settlementMaxAttempts(100)
            .initialBackoff(Duration.ofMillis(50))
            .maxBackoff(Duration.ofMillis(50))
            .shutdownTimeout(Duration.ofMillis(300))
            .build());

        enforcer.execute(request("req-stuck"), provider, PRICED_USAGE);
        long started = System.nanoTime();
        enforcer.close();

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(2000);
        assertThat(settlementResults).containsExactly("failed rsv-stuck COMMIT closed before settlement");
    }

    @Test
    void streamingIsRefusedOnlyInAnEnforceScopeAndNeverAsAPolicyDenial() {
        start(EnforcementPolicy.builder()
            .defaults(EnforcementSettings.defaults())
            .endpoint("checkout", EnforcementSettings.enforce())
            .build());

        enforcer.checkStreaming(request("req-stream-observe"));
        assertThatThrownBy(() -> enforcer.checkStreaming(CallRequest.builder().requestId("req-stream-enforce")
                .endpoint("checkout").provider("openai").model("gpt-4o").build()))
            .isInstanceOfSatisfying(EnforcementException.class, refused -> {
                assertThat(refused.action()).isEqualTo(EnforcementAction.BLOCK);
                assertThat(refused.decision().outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
                assertThat(refused.decision().errorCode()).isEqualTo(ControlPlaneEnforcer.STREAMING_NOT_ENFORCEABLE);
            });
        assertThat(server.decisionRequests()).isEmpty();
    }
}
