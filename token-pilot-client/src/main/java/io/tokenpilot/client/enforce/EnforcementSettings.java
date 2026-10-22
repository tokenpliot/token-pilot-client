package io.tokenpilot.client.enforce;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Customer-owned enforcement settings: the only thing that turns a decision into an action on the provider call.
 * {@link #actionFor} is the ADR 0001 matrix and must stay identical to the Control Plane's copy.
 *
 * @param mode             enforcement mode
 * @param enforcedOutcomes outcomes {@link EnforcementMode#ENFORCE} acts on (subset of {@code DENY_POLICY},
 *                         {@code DENY_BUDGET}, {@code REQUIRE_APPROVAL})
 * @param onIndeterminate  ENFORCE behavior when the request could not be judged
 * @param onUnavailable    ENFORCE behavior when the Control Plane could not be reached or failed
 */
public record EnforcementSettings(
    EnforcementMode mode,
    Set<DecisionOutcome> enforcedOutcomes,
    FailureBehavior onIndeterminate,
    FailureBehavior onUnavailable
) {

    private static final Set<DecisionOutcome> ENFORCEABLE = EnumSet.of(
        DecisionOutcome.DENY_POLICY,
        DecisionOutcome.DENY_BUDGET,
        DecisionOutcome.REQUIRE_APPROVAL
    );

    private static final Set<DecisionOutcome> DEFAULT_ENFORCED = EnumSet.of(
        DecisionOutcome.DENY_POLICY,
        DecisionOutcome.DENY_BUDGET
    );

    public EnforcementSettings {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(enforcedOutcomes, "enforcedOutcomes must not be null");
        Objects.requireNonNull(onIndeterminate, "onIndeterminate must not be null");
        Objects.requireNonNull(onUnavailable, "onUnavailable must not be null");
        if (!ENFORCEABLE.containsAll(enforcedOutcomes)) {
            throw new IllegalArgumentException("enforcedOutcomes may only contain " + ENFORCEABLE);
        }
        enforcedOutcomes = Set.copyOf(enforcedOutcomes);
    }

    /** Settings of a new connection: OBSERVE, default enforced scope, FAIL_OPEN for both failure kinds. */
    public static EnforcementSettings defaults() {
        return new EnforcementSettings(
            EnforcementMode.OBSERVE,
            DEFAULT_ENFORCED,
            FailureBehavior.FAIL_OPEN,
            FailureBehavior.FAIL_OPEN
        );
    }

    /**
     * Opt-in enforcement with the default scope ({@code DENY_POLICY}, {@code DENY_BUDGET}) and FAIL_OPEN, so only
     * real policy denials block; Control Plane failures still let the call through with an advisory.
     */
    public static EnforcementSettings enforce() {
        return defaults().withMode(EnforcementMode.ENFORCE);
    }

    /** Decisions are surfaced as advisories and never block. */
    public static EnforcementSettings advisory() {
        return defaults().withMode(EnforcementMode.ADVISORY);
    }

    public EnforcementSettings withMode(EnforcementMode mode) {
        return new EnforcementSettings(mode, enforcedOutcomes, onIndeterminate, onUnavailable);
    }

    public EnforcementSettings withEnforcedOutcomes(Set<DecisionOutcome> enforcedOutcomes) {
        return new EnforcementSettings(mode, enforcedOutcomes, onIndeterminate, onUnavailable);
    }

    public EnforcementSettings withOnIndeterminate(FailureBehavior onIndeterminate) {
        return new EnforcementSettings(mode, enforcedOutcomes, onIndeterminate, onUnavailable);
    }

    public EnforcementSettings withOnUnavailable(FailureBehavior onUnavailable) {
        return new EnforcementSettings(mode, enforcedOutcomes, onIndeterminate, onUnavailable);
    }

    /**
     * ADR 0001 matrix: OBSERVE always proceeds, ADVISORY never blocks, only ENFORCE acts, and only on the outcomes
     * and failure behaviors the customer chose.
     */
    public EnforcementAction actionFor(DecisionOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome must not be null");
        if (mode == EnforcementMode.OBSERVE || outcome == DecisionOutcome.ALLOW) {
            return EnforcementAction.PROCEED;
        }
        if (mode == EnforcementMode.ADVISORY) {
            return EnforcementAction.PROCEED_WITH_ADVISORY;
        }
        return switch (outcome) {
            case DENY_POLICY, DENY_BUDGET -> enforcedOutcomes.contains(outcome)
                ? EnforcementAction.BLOCK
                : EnforcementAction.PROCEED_WITH_ADVISORY;
            case REQUIRE_APPROVAL -> enforcedOutcomes.contains(outcome)
                ? EnforcementAction.HOLD_FOR_APPROVAL
                : EnforcementAction.PROCEED_WITH_ADVISORY;
            case INDETERMINATE -> onFailure(onIndeterminate);
            case CONTROL_PLANE_UNAVAILABLE -> onFailure(onUnavailable);
            case ALLOW -> EnforcementAction.PROCEED;
        };
    }

    private static EnforcementAction onFailure(FailureBehavior behavior) {
        return behavior == FailureBehavior.FAIL_CLOSED
            ? EnforcementAction.BLOCK
            : EnforcementAction.PROCEED_WITH_ADVISORY;
    }
}
