package io.tokenpilot.client.enforce;

/**
 * Observes decisions and settlements. {@link #onDecision} runs on the calling thread before the provider call,
 * the settlement callbacks on the enforcer's settlement thread; keep implementations fast. Exceptions thrown here
 * are logged and ignored. No callback receives the API key or metadata values.
 */
public interface EnforcementListener {

    EnforcementListener NONE = new EnforcementListener() {
    };

    /** A decision was obtained (or mapped from a failure) and turned into {@code action}. */
    default void onDecision(CallRequest request, Decision decision, EnforcementAction action) {
    }

    /** The server applied the settlement, or already had it ({@code duplicate}); {@code state} is the new state. */
    default void onSettled(String reservationId, SettlementType type, String state, boolean duplicate) {
    }

    /** The settlement will be sent again after a backoff; {@code attempt} is the attempt that just failed. */
    default void onSettlementRetry(String reservationId, SettlementType type, int attempt, String reason) {
    }

    /** The settlement will not be delivered; the server expires or reconciles the reservation on its own. */
    default void onSettlementFailed(String reservationId, SettlementType type, String detail) {
    }
}
