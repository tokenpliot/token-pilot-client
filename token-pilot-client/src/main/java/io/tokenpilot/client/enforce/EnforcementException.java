package io.tokenpilot.client.enforce;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Thrown by {@link ControlPlaneEnforcer#execute} instead of calling the provider ({@link EnforcementAction#BLOCK}
 * or {@link EnforcementAction#HOLD_FOR_APPROVAL}). The outcome tells a policy denial ({@code DENY_*}) apart from a
 * fail-closed block on {@code INDETERMINATE} or {@code CONTROL_PLANE_UNAVAILABLE}.
 *
 * <p>The message carries only the action, the outcome and reason codes: never metadata, request content or the
 * API key, so it is safe to log.
 */
public class EnforcementException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient Decision decision;
    private final EnforcementAction action;

    public EnforcementException(Decision decision, EnforcementAction action) {
        super(message(decision, action));
        this.decision = decision;
        this.action = action;
    }

    public Decision decision() {
        return decision;
    }

    public EnforcementAction action() {
        return action;
    }

    /** Id to poll for the approval when the call is {@link EnforcementAction#HOLD_FOR_APPROVAL}; else null. */
    public String approvalId() {
        return decision.approvalId();
    }

    private static String message(Decision decision, EnforcementAction action) {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(action, "action must not be null");
        String reasons = decision.reasons().stream().map(DecisionReason::code).collect(Collectors.joining(","));
        return "Provider call stopped: " + action + " (outcome " + decision.outcome()
            + (reasons.isEmpty() ? "" : ", reasons " + reasons) + ")";
    }
}
