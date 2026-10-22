package io.tokenpilot.client.enforce;

/**
 * Extracts the actual usage from a provider response. Returns {@code null} when the response carries no usage;
 * the reservation is then settled as {@link SettlementType#RECONCILIATION_REQUIRED}.
 */
@FunctionalInterface
public interface UsageReader<T> {

    ActualUsage read(T response);
}
