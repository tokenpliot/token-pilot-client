package io.tokenpilot.client.enforce;

/**
 * Enforcement mode chosen by the customer. Only the customer's configuration changes it: the Control Plane never
 * raises or lowers the mode through a response, so rolling back from {@link #ENFORCE} is always a config change.
 */
public enum EnforcementMode {
    /** Decisions are recorded for audit and shadow comparison; the provider call always proceeds. */
    OBSERVE,
    /** Decisions are surfaced as advisories but never stop the provider call. */
    ADVISORY,
    /** Explicit opt-in: decisions in the customer's enforced scope block or hold the provider call. */
    ENFORCE
}
