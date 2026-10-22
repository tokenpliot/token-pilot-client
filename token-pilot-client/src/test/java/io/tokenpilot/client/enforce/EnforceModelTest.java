package io.tokenpilot.client.enforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EnforceModelTest {

    private static CallRequest.Builder request() {
        return CallRequest.builder()
            .requestId("req-1")
            .provider("openai")
            .model("gpt-4o")
            .estimatedInputTokens(3000)
            .maxOutputTokens(1000);
    }

    @Test
    void callRequestDefaultsRequestIdAndDropsRawTextMetadata() {
        CallRequest request = CallRequest.builder()
            .provider("openai")
            .model("gpt-4o")
            .metadata("feature", "support-summary")
            .metadata("prompt", "raw user text")
            .metadata("Content", "raw completion")
            .build();

        assertThat(request.requestId()).isNotBlank();
        assertThat(request.metadata()).containsExactly(Map.entry("feature", "support-summary"));
        assertThat(request.endpoint()).isNull();
        assertThat(request.estimatedCostUsd()).isNull();
    }

    @Test
    void callRequestRoundsEstimatedCostUpToMicroDollars() {
        assertThat(request().estimatedCostUsd(new BigDecimal("0.0000001")).build().estimatedCostUsd())
            .isEqualByComparingTo("0.000001");
        assertThat(request().estimatedCostUsd(new BigDecimal("0.017500")).build().estimatedCostUsd())
            .isEqualByComparingTo("0.0175");
    }

    @Test
    void callRequestRejectsValuesOutsideTheContract() {
        assertThatThrownBy(() -> request().requestId("req 1").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().requestId("r".repeat(129)).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().provider(null).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().endpoint("check/out").build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().estimatedInputTokens(-1).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().estimatedCostUsd(new BigDecimal("-0.01")).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().metadata("Feature", "x").build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> request().metadata("feature", "x".repeat(257)).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actualUsageIsPricedOnlyWithEveryCost() {
        ActualUsage priced = ActualUsage.priced(2950, 720, new BigDecimal("0.007375"), new BigDecimal("0.0072"));

        assertThat(priced.priced()).isTrue();
        assertThat(priced.totalCostUsd()).isEqualByComparingTo("0.014575");
        assertThat(ActualUsage.of(2950, 720).priced()).isFalse();
        assertThat(new ActualUsage(1, 1, null, null, BigDecimal.ONE, null, BigDecimal.ONE).priced()).isFalse();
    }

    @Test
    void actualUsageTokensAreInclusive() {
        assertThatThrownBy(() -> new ActualUsage(10, 5, 11L, null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ActualUsage(10, 5, null, 6L, null, null, null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ActualUsage.of(-1, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idempotencyKeysAreDeterministicAndWithinHeaderLimits() {
        assertThat(ControlPlaneApi.decisionKey("req-1")).isEqualTo("dec:req-1");
        assertThat(ControlPlaneApi.settlementKey("rsv-1", SettlementType.COMMIT)).isEqualTo("stl:rsv-1:COMMIT");

        String longId = "r".repeat(128);
        assertThat(ControlPlaneApi.decisionKey(longId))
            .isEqualTo(ControlPlaneApi.decisionKey(longId))
            .matches("dec:[0-9a-f]{64}");
        assertThat(ControlPlaneApi.settlementKey(longId, SettlementType.RELEASE)).matches("stl:[0-9a-f]{64}");
        // The header needs at least 8 characters.
        assertThat(ControlPlaneApi.decisionKey("r1")).matches("dec:[0-9a-f]{64}");
    }

    @Test
    void decisionBodyFollowsTheContractAndOmitsEmptyOptionals() {
        String minimal = new String(ControlPlaneApi.decisionBody(request().build(), "prod", EnforcementMode.OBSERVE),
            StandardCharsets.UTF_8);

        assertThat(minimal).isEqualTo("{\"schemaVersion\":\"2026-10-01\",\"requestId\":\"req-1\","
            + "\"environment\":\"prod\",\"provider\":\"openai\",\"model\":\"gpt-4o\",\"clientMode\":\"OBSERVE\","
            + "\"estimatedInputTokens\":3000,\"maxOutputTokens\":1000}");

        String zeroCost = new String(ControlPlaneApi.decisionBody(
            request().estimatedCostUsd(new BigDecimal("0.000000")).build(), "prod", EnforcementMode.ENFORCE),
            StandardCharsets.UTF_8);
        assertThat(zeroCost).contains("\"estimatedCostUsd\":\"0\"");

        String large = new String(ControlPlaneApi.decisionBody(
            request().estimatedCostUsd(new BigDecimal("1E+3")).build(), "prod", EnforcementMode.ENFORCE),
            StandardCharsets.UTF_8);
        assertThat(large).contains("\"estimatedCostUsd\":\"1000\"");
    }

    @Test
    void settlementBodyCarriesCostOnlyForCommitAndNoUsageForRelease() {
        Instant at = Instant.parse("2026-10-22T01:02:03Z");
        ActualUsage usage = ActualUsage.priced(10, 5, new BigDecimal("0.1"), new BigDecimal("0.2"));

        assertThat(new String(ControlPlaneApi.settlementBody("req-1", SettlementType.RELEASE, usage, at),
            StandardCharsets.UTF_8)).isEqualTo("{\"schemaVersion\":\"2026-10-01\",\"requestId\":\"req-1\","
            + "\"result\":\"RELEASE\",\"occurredAt\":\"2026-10-22T01:02:03Z\"}");
        assertThat(new String(ControlPlaneApi.settlementBody("req-1", SettlementType.RECONCILIATION_REQUIRED, usage,
            at), StandardCharsets.UTF_8)).isEqualTo("{\"schemaVersion\":\"2026-10-01\",\"requestId\":\"req-1\","
            + "\"result\":\"RECONCILIATION_REQUIRED\",\"usage\":{\"promptTokens\":10,\"completionTokens\":5},"
            + "\"occurredAt\":\"2026-10-22T01:02:03Z\"}");
        assertThat(new String(ControlPlaneApi.settlementBody("req-1", SettlementType.COMMIT, usage, at),
            StandardCharsets.UTF_8)).contains(
            "\"cost\":{\"promptCostUsd\":\"0.1\",\"completionCostUsd\":\"0.2\",\"totalCostUsd\":\"0.3\"}");
    }

    @Test
    void evaluatedDecisionParsesEveryField() {
        String body = "{\"success\":true,\"code\":\"SUCCESS\",\"data\":{\"schemaVersion\":\"2026-10-01\","
            + "\"decisionId\":\"dec-1\",\"requestId\":\"req-1\",\"outcome\":\"ALLOW\",\"effectiveMode\":\"ENFORCE\","
            + "\"reasons\":[{\"code\":\"WITHIN_BUDGET\",\"message\":\"ok\"}],\"policyVersion\":\"pol-v3\","
            + "\"evaluatedAt\":\"2026-10-02T12:00:01Z\",\"reservation\":{\"reservationId\":\"rsv-1\","
            + "\"state\":\"RESERVED\",\"reservedCostUsd\":\"0.017500\",\"expiresAt\":\"2026-10-02T12:05:01Z\"}}}";

        Decision decision = ControlPlaneApi.interpret("req-1", 200, body, null);

        assertThat(decision.evaluated()).isTrue();
        assertThat(decision).isEqualTo(new Decision(DecisionOutcome.ALLOW, "dec-1", "req-1", EnforcementMode.ENFORCE,
            List.of(new DecisionReason("WITHIN_BUDGET", "ok")), "pol-v3",
            new Reservation("rsv-1", "RESERVED", new BigDecimal("0.017500"), Instant.parse("2026-10-02T12:05:01Z")),
            null, 200, null, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not json",
        "",
        "{\"success\":true}",
        "{\"data\":{\"requestId\":\"req-1\",\"outcome\":\"MAYBE\"}}",
        "{\"data\":{\"requestId\":\"req-1\",\"outcome\":\"CONTROL_PLANE_UNAVAILABLE\"}}",
        "{\"data\":{\"requestId\":\"req-other\",\"outcome\":\"DENY_POLICY\"}}",
        "{\"data\":{\"outcome\":\"DENY_BUDGET\"}}",
        "{\"data\":{\"requestId\":\"req-1\",\"outcome\":\"ALLOW\",\"reservation\":{\"state\":\"RESERVED\"}}}"
    })
    void unreadableJudgmentIsIndeterminateNeverDeny(String body) {
        Decision decision = ControlPlaneApi.interpret("req-1", 200, body, null);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(decision.errorCode()).isEqualTo("UNREADABLE_RESPONSE");
        assertThat(decision.evaluated()).isFalse();
        assertThat(decision.reservation()).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 204, 302, 304})
    void unexpectedStatusIsIndeterminate(int status) {
        Decision decision = ControlPlaneApi.interpret("req-1", status, "{\"data\":{\"outcome\":\"DENY_POLICY\"}}", null);

        assertThat(decision.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(decision.errorCode()).isEqualTo("UNEXPECTED_STATUS");
    }

    @Test
    void errorResponseIgnoresClaimedOutcomeAndReadsRetryAfter() {
        String claimsDeny = "{\"success\":false,\"code\":\"RATE_LIMITED\",\"data\":{\"outcome\":\"DENY_POLICY\","
            + "\"retryAfterSeconds\":2}}";

        Decision fromBody = ControlPlaneApi.interpret("req-1", 429, claimsDeny, "9");
        assertThat(fromBody.outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(fromBody.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(fromBody.retryAfter()).isEqualTo(Duration.ofSeconds(2));

        Decision proxyPage = ControlPlaneApi.interpret("req-1", 502, "<html>bad gateway</html>", "3");
        assertThat(proxyPage.outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(proxyPage.errorCode()).isEqualTo("HTTP_502");
        assertThat(proxyPage.retryAfter()).isEqualTo(Duration.ofSeconds(3));

        Decision forbidden = ControlPlaneApi.interpret("req-1", 403, claimsDeny, null);
        assertThat(forbidden.outcome()).isEqualTo(DecisionOutcome.INDETERMINATE);
        assertThat(forbidden.decisionId()).isNull();
        assertThat(forbidden.reasons()).isEmpty();
    }

    @Test
    void enforcementExceptionMessageNamesActionOutcomeAndReasonCodesOnly() {
        Decision decision = new Decision(DecisionOutcome.DENY_BUDGET, "dec-1", "req-1", EnforcementMode.ENFORCE,
            List.of(new DecisionReason("MONTHLY_BUDGET_EXCEEDED", "secret-ish text")), "pol-v3", null, null, 200,
            null, null);

        EnforcementException exception = new EnforcementException(decision, EnforcementAction.BLOCK);

        assertThat(exception.getMessage())
            .isEqualTo("Provider call stopped: BLOCK (outcome DENY_BUDGET, reasons MONTHLY_BUDGET_EXCEEDED)");
        assertThat(exception.decision()).isSameAs(decision);
        assertThat(exception.action()).isEqualTo(EnforcementAction.BLOCK);
        assertThat(exception.approvalId()).isNull();
    }
}
