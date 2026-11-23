package io.tokenpilot.client.enforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * The enforcer against a real Control Plane (token-pilot-cloud-api with Enforce reservations on), not the fake.
 * Checks the contract points the fake cannot: real reservation ids, server-side budget counters, and that
 * settlements are accepted and land in the expected reservation state.
 *
 * <p>Skipped unless {@code TOKEN_PILOT_E2E_ENDPOINT} is set. Start and seed a server with
 * {@code scripts/e2e/enforce-pilot.sh up} in token-pilot-cloud-api, then {@code source /tmp/tp-e2e/env}. The seed
 * has a 1000 USD project budget and a 1 USD budget on environment {@code e2e-hold}; request ids are unique per run,
 * so the test can be rerun against the same server.
 */
@EnabledIfEnvironmentVariable(named = "TOKEN_PILOT_E2E_ENDPOINT", matches = ".+")
class RealServerEnforceE2ETest {

    private static final String ENDPOINT = System.getenv("TOKEN_PILOT_E2E_ENDPOINT");
    private static final String API_KEY = System.getenv("TOKEN_PILOT_E2E_API_KEY");
    private static final String MODEL = System.getenv().getOrDefault("TOKEN_PILOT_E2E_MODEL", "gpt-e2e");
    private static final String RUN = UUID.randomUUID().toString().substring(0, 8);

    // Seeded price: prompt 2, completion 8 USD per 1M tokens.
    private static final UsageReader<String> PRICED = response ->
        ActualUsage.priced(10, 5, new BigDecimal("0.000020"), new BigDecimal("0.000040"));

    private final AtomicInteger providerCalls = new AtomicInteger();
    private final ProviderCall<String> provider = () -> {
        providerCalls.incrementAndGet();
        return "completion";
    };
    private final BlockingQueue<String> settlements = new LinkedBlockingQueue<>();
    private final EnforcementListener listener = new EnforcementListener() {
        @Override
        public void onSettled(String reservationId, SettlementType type, String state, boolean duplicate) {
            settlements.add(type + " " + state);
        }

        @Override
        public void onSettlementFailed(String reservationId, SettlementType type, String detail) {
            settlements.add("failed " + type + " " + detail);
        }
    };

    private ControlPlaneEnforcer enforcer;

    @AfterEach
    void close() {
        if (enforcer != null) {
            enforcer.close();
        }
    }

    private ControlPlaneEnforcer start(String endpoint, String environment, EnforcementSettings settings) {
        enforcer = ControlPlaneEnforcer.create(EnforcerConfig.builder()
            .endpoint(URI.create(endpoint))
            .apiKey(API_KEY)
            .environment(environment)
            .policy(EnforcementPolicy.builder().defaults(settings).build())
            .decisionTimeout(Duration.ofSeconds(5))
            .shutdownTimeout(Duration.ofSeconds(5))
            .listener(listener)
            .build());
        return enforcer;
    }

    private ControlPlaneEnforcer start(EnforcementSettings settings) {
        return start(ENDPOINT, "prod", settings);
    }

    /** Input 1000 + output {@code maxOutputTokens} at the seeded price. */
    private static CallRequest request(String name, long maxOutputTokens) {
        return CallRequest.builder()
            .requestId("e2e-" + RUN + "-" + name)
            .provider("openai")
            .model(MODEL)
            .estimatedInputTokens(1000)
            .maxOutputTokens(maxOutputTokens)
            .build();
    }

    private String nextSettlement() throws InterruptedException {
        String result = settlements.poll(10, TimeUnit.SECONDS);
        assertThat(result).as("settlement within 10s").isNotNull();
        return result;
    }

    @Test
    void allowReservesAndCommitsTheActualCost() throws Exception {
        EnforcedResult<String> result = start(EnforcementSettings.enforce())
            .execute(request("commit", 500), provider, PRICED);

        Decision decision = result.decision();
        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.ALLOW);
        assertThat(decision.effectiveMode()).isEqualTo(EnforcementMode.ENFORCE);
        assertThat(decision.reservation()).isNotNull();
        assertThat(decision.reservation().reservedCostUsd()).isEqualByComparingTo("0.006");
        assertThat(result.action()).isEqualTo(EnforcementAction.PROCEED);
        assertThat(providerCalls).hasValue(1);
        assertThat(nextSettlement()).isEqualTo("COMMIT COMMITTED");
    }

    @Test
    void providerFailureKeepsTheReservedLiability() throws Exception {
        ControlPlaneEnforcer enforcer = start(EnforcementSettings.enforce());

        assertThatThrownBy(() -> enforcer.execute(request("failure", 500), () -> {
            throw new IllegalStateException("provider timeout");
        }, PRICED)).isInstanceOf(IllegalStateException.class);

        assertThat(nextSettlement()).isEqualTo("RECONCILIATION_REQUIRED RECONCILIATION_REQUIRED");
    }

    @Test
    void callNeverSentReleasesTheReservation() throws Exception {
        ControlPlaneEnforcer enforcer = start(EnforcementSettings.enforce());

        assertThatThrownBy(() -> enforcer.execute(request("not-sent", 500), () -> {
            throw new CallNotDispatchedException("connection refused before sending");
        }, PRICED)).isInstanceOf(CallNotDispatchedException.class);

        assertThat(nextSettlement()).isEqualTo("RELEASE RELEASED");
    }

    @Test
    void budgetDenialBlocksAndRetriesGetTheSameDecision() throws Exception {
        ControlPlaneEnforcer enforcer = start(EnforcementSettings.enforce());
        // 200M output tokens cost 1600 USD, over the 1000 USD project budget.
        CallRequest tooExpensive = request("deny", 200_000_000L);

        EnforcementException first = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(tooExpensive, provider, PRICED));
        EnforcementException retry = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(tooExpensive, provider, PRICED));

        assertThat(first.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(first.decision().reservation()).isNull();
        assertThat(first.action()).isEqualTo(EnforcementAction.BLOCK);
        assertThat(retry.decision().decisionId()).isEqualTo(first.decision().decisionId());
        assertThat(providerCalls).hasValue(0);
        assertThat(settlements.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void unsettledReservationsCountAgainstTheBudget() throws Exception {
        // e2e-hold has a 1 USD budget; 75k output tokens cost 0.602 USD, so two of them cannot be held at once.
        ControlPlaneEnforcer enforcer = start(ENDPOINT, "e2e-hold", EnforcementSettings.enforce());
        CountDownLatch inFlight = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        CompletableFuture<EnforcedResult<String>> first = CompletableFuture.supplyAsync(() -> {
            try {
                return enforcer.execute(request("hold-a", 75_000), () -> {
                    inFlight.countDown();
                    assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
                    return "completion";
                }, PRICED);
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
        assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();

        EnforcementException second = catchThrowableOfType(EnforcementException.class,
            () -> enforcer.execute(request("hold-b", 75_000), provider, PRICED));
        finish.countDown();

        assertThat(second.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(second.decision().reasons()).extracting(DecisionReason::code)
            .containsExactly("RESERVED_BUDGET_EXCEEDED");
        assertThat(first.get(10, TimeUnit.SECONDS).decision().reservation()).isNotNull();
        assertThat(nextSettlement()).isEqualTo("COMMIT COMMITTED");
        assertThat(providerCalls).hasValue(0);
    }

    @Test
    void advisoryDenialStillCallsTheProvider() throws Exception {
        EnforcedResult<String> result = start(EnforcementSettings.advisory())
            .execute(request("advisory", 200_000_000L), provider, PRICED);

        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
        assertThat(result.decision().effectiveMode()).isEqualTo(EnforcementMode.ADVISORY);
        assertThat(result.decision().reservation()).isNull();
        assertThat(result.action()).isEqualTo(EnforcementAction.PROCEED_WITH_ADVISORY);
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void unreachableServerIsUnavailableNotAPolicyDenial() throws Exception {
        // Port 1 refuses connections; with FAIL_OPEN the call goes through.
        EnforcedResult<String> result = start("http://127.0.0.1:1", "prod", EnforcementSettings.enforce())
            .execute(request("unavailable", 500), provider, PRICED);

        assertThat(result.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(result.decision().reservation()).isNull();
        assertThat(providerCalls).hasValue(1);
    }
}
