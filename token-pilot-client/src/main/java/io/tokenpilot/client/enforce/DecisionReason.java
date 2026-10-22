package io.tokenpilot.client.enforce;

/** One reason the Control Plane gave for a decision, e.g. {@code MONTHLY_BUDGET_EXCEEDED}. */
public record DecisionReason(String code, String message) {
}
