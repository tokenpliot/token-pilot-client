package io.tokenpilot.client.enforce;

/**
 * What {@link EnforcementMode#ENFORCE} does when no policy judgment is available. Defaults to {@link #FAIL_OPEN}
 * so an outage of the Control Plane never becomes an outage of the customer's product unless chosen.
 */
public enum FailureBehavior {
    FAIL_OPEN,
    FAIL_CLOSED
}
