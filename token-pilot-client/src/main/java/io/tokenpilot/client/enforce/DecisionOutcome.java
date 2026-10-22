package io.tokenpilot.client.enforce;

/**
 * Result of a Control Plane decision (ADR 0001 section 3). Policy judgments ({@code ALLOW}, {@code DENY_*},
 * {@code REQUIRE_APPROVAL}, {@code INDETERMINATE}) arrive as HTTP 200; {@link #CONTROL_PLANE_UNAVAILABLE} and every
 * failure the client maps itself never become a policy denial.
 */
public enum DecisionOutcome {
    ALLOW,
    DENY_POLICY,
    DENY_BUDGET,
    REQUIRE_APPROVAL,
    INDETERMINATE,
    CONTROL_PLANE_UNAVAILABLE;

    public boolean isPolicyDenial() {
        return this == DENY_POLICY || this == DENY_BUDGET;
    }

    /**
     * Maps an HTTP error status of the decision endpoint. Overload and server errors mean the Control Plane could
     * not judge; any other client error (including a revoked key) means the request could not be judged. No error
     * is ever a policy denial.
     */
    public static DecisionOutcome forErrorStatus(int status) {
        if (status < 400 || status > 599) {
            throw new IllegalArgumentException("Not an HTTP error status: " + status);
        }
        if (status == 408 || status == 429 || status >= 500) {
            return CONTROL_PLANE_UNAVAILABLE;
        }
        return INDETERMINATE;
    }

    /** No HTTP response at all: connection refused, timeout, reset. */
    public static DecisionOutcome forTransportFailure() {
        return CONTROL_PLANE_UNAVAILABLE;
    }
}
