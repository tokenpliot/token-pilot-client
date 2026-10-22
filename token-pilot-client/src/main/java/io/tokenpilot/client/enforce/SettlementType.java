package io.tokenpilot.client.enforce;

/** How a reservation is settled after the provider call (SettlementRequest {@code result}). */
public enum SettlementType {
    /** The call ran and its priced usage is known. */
    COMMIT,
    /** The provider was never reached, so nothing was spent. */
    RELEASE,
    /** The call may have spent money but its cost is unknown; the reserved amount stays until reconciled. */
    RECONCILIATION_REQUIRED
}
