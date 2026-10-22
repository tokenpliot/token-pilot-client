package io.tokenpilot.client.enforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EnforcementSettingsTest {

    private static final Set<DecisionOutcome> DEFAULT_SCOPE =
        EnumSet.of(DecisionOutcome.DENY_POLICY, DecisionOutcome.DENY_BUDGET);
    private static final Set<DecisionOutcome> FULL_SCOPE =
        EnumSet.of(DecisionOutcome.DENY_POLICY, DecisionOutcome.DENY_BUDGET, DecisionOutcome.REQUIRE_APPROVAL);
    private static final Set<DecisionOutcome> APPROVAL_ONLY = EnumSet.of(DecisionOutcome.REQUIRE_APPROVAL);

    private static final FailureBehavior OPEN = FailureBehavior.FAIL_OPEN;
    private static final FailureBehavior CLOSED = FailureBehavior.FAIL_CLOSED;

    /**
     * ADR 0001 section 5, written out by hand. Expected actions are in outcome order: ALLOW, DENY_POLICY,
     * DENY_BUDGET, REQUIRE_APPROVAL, INDETERMINATE, CONTROL_PLANE_UNAVAILABLE.
     */
    static Stream<Arguments> matrix() {
        List<Arguments> rows = new ArrayList<>();
        row(rows, EnforcementMode.OBSERVE, FULL_SCOPE, CLOSED, CLOSED,
            "PROCEED PROCEED PROCEED PROCEED PROCEED PROCEED");
        row(rows, EnforcementMode.OBSERVE, DEFAULT_SCOPE, OPEN, OPEN,
            "PROCEED PROCEED PROCEED PROCEED PROCEED PROCEED");
        row(rows, EnforcementMode.ADVISORY, FULL_SCOPE, CLOSED, CLOSED,
            "PROCEED PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY"
                + " PROCEED_WITH_ADVISORY");
        row(rows, EnforcementMode.ENFORCE, DEFAULT_SCOPE, OPEN, OPEN,
            "PROCEED BLOCK BLOCK PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY");
        row(rows, EnforcementMode.ENFORCE, FULL_SCOPE, CLOSED, CLOSED,
            "PROCEED BLOCK BLOCK HOLD_FOR_APPROVAL BLOCK BLOCK");
        row(rows, EnforcementMode.ENFORCE, APPROVAL_ONLY, OPEN, CLOSED,
            "PROCEED PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY HOLD_FOR_APPROVAL PROCEED_WITH_ADVISORY BLOCK");
        row(rows, EnforcementMode.ENFORCE, Set.of(), CLOSED, OPEN,
            "PROCEED PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY PROCEED_WITH_ADVISORY BLOCK"
                + " PROCEED_WITH_ADVISORY");
        return rows.stream();
    }

    private static void row(List<Arguments> rows, EnforcementMode mode, Set<DecisionOutcome> scope,
                            FailureBehavior onIndeterminate, FailureBehavior onUnavailable, String actions) {
        String[] expected = actions.split(" ");
        DecisionOutcome[] outcomes = DecisionOutcome.values();
        assertThat(expected).hasSameSizeAs(outcomes);
        for (int i = 0; i < outcomes.length; i++) {
            rows.add(Arguments.of(new EnforcementSettings(mode, scope, onIndeterminate, onUnavailable), outcomes[i],
                EnforcementAction.valueOf(expected[i])));
        }
    }

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @MethodSource("matrix")
    void actionFollowsTheEnforcementMatrix(EnforcementSettings settings, DecisionOutcome outcome,
                                           EnforcementAction expected) {
        assertThat(settings.actionFor(outcome)).isEqualTo(expected);
    }

    @ParameterizedTest
    @EnumSource(DecisionOutcome.class)
    void defaultsObserveAndNeverStopTheCall(DecisionOutcome outcome) {
        EnforcementSettings defaults = EnforcementSettings.defaults();

        assertThat(defaults.mode()).isEqualTo(EnforcementMode.OBSERVE);
        assertThat(defaults.enforcedOutcomes()).isEqualTo(DEFAULT_SCOPE);
        assertThat(defaults.onIndeterminate()).isEqualTo(FailureBehavior.FAIL_OPEN);
        assertThat(defaults.onUnavailable()).isEqualTo(FailureBehavior.FAIL_OPEN);
        assertThat(defaults.actionFor(outcome)).isEqualTo(EnforcementAction.PROCEED);
    }

    @Test
    void factoriesAndWithersBuildReadableSettings() {
        assertThat(EnforcementSettings.enforce())
            .isEqualTo(new EnforcementSettings(EnforcementMode.ENFORCE, DEFAULT_SCOPE, OPEN, OPEN));
        assertThat(EnforcementSettings.advisory())
            .isEqualTo(new EnforcementSettings(EnforcementMode.ADVISORY, DEFAULT_SCOPE, OPEN, OPEN));
        assertThat(EnforcementSettings.enforce()
            .withEnforcedOutcomes(FULL_SCOPE)
            .withOnIndeterminate(CLOSED)
            .withOnUnavailable(CLOSED))
            .isEqualTo(new EnforcementSettings(EnforcementMode.ENFORCE, FULL_SCOPE, CLOSED, CLOSED));
    }

    @ParameterizedTest
    @EnumSource(value = DecisionOutcome.class, names = {"ALLOW", "INDETERMINATE", "CONTROL_PLANE_UNAVAILABLE"})
    void scopeCannotContainNonPolicyOutcomes(DecisionOutcome outcome) {
        assertThatThrownBy(() -> EnforcementSettings.enforce().withEnforcedOutcomes(EnumSet.of(outcome)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 409, 422})
    void clientErrorsMeanIndeterminate(int status) {
        assertThat(DecisionOutcome.forErrorStatus(status)).isEqualTo(DecisionOutcome.INDETERMINATE);
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 502, 503, 504})
    void overloadAndServerErrorsMeanUnavailable(int status) {
        assertThat(DecisionOutcome.forErrorStatus(status)).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
    }

    @Test
    void noErrorStatusOrTransportFailureIsEverAPolicyDenial() {
        for (int status = 400; status <= 599; status++) {
            assertThat(DecisionOutcome.forErrorStatus(status).isPolicyDenial()).as("status %d", status).isFalse();
        }
        assertThat(DecisionOutcome.forTransportFailure()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE);
        assertThat(DecisionOutcome.DENY_POLICY.isPolicyDenial()).isTrue();
        assertThat(DecisionOutcome.DENY_BUDGET.isPolicyDenial()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 302, 399, 600})
    void nonErrorStatusIsRejected(int status) {
        assertThatThrownBy(() -> DecisionOutcome.forErrorStatus(status)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policyDefaultsToObserveAndOverridesOnlyNamedEndpoints() {
        assertThat(EnforcementPolicy.observeOnly().settingsFor("checkout")).isEqualTo(EnforcementSettings.defaults());

        EnforcementPolicy policy = EnforcementPolicy.builder()
            .endpoint("checkout", EnforcementSettings.enforce())
            .build();

        assertThat(policy.defaults()).isEqualTo(EnforcementSettings.defaults());
        assertThat(policy.settingsFor("checkout")).isEqualTo(EnforcementSettings.enforce());
        assertThat(policy.settingsFor("search")).isEqualTo(EnforcementSettings.defaults());
        assertThat(policy.settingsFor(null)).isEqualTo(EnforcementSettings.defaults());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "check out", "결제"})
    void policyEndpointNamesMustBeIdentifiers(String endpoint) {
        assertThatThrownBy(() -> EnforcementPolicy.builder().endpoint(endpoint, EnforcementSettings.enforce()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void actionStopsProviderCallOnlyForBlockAndHold() {
        assertThat(EnforcementAction.BLOCK.stopsProviderCall()).isTrue();
        assertThat(EnforcementAction.HOLD_FOR_APPROVAL.stopsProviderCall()).isTrue();
        assertThat(EnforcementAction.PROCEED.stopsProviderCall()).isFalse();
        assertThat(EnforcementAction.PROCEED_WITH_ADVISORY.stopsProviderCall()).isFalse();
    }
}
