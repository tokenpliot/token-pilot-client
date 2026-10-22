package io.tokenpilot.client.enforce;

import java.math.BigDecimal;

/**
 * Usage the provider reported for a call, used to settle its reservation.
 *
 * <p>Token counts are inclusive: {@code promptTokens} already contains {@code cachedPromptTokens} and
 * {@code completionTokens} already contains {@code reasoningTokens}, as most provider APIs report them. Costs are
 * optional; only a fully {@link #priced()} usage commits the reservation, anything else asks the server to
 * reconcile. Costs are rounded UP to 6 decimals, the contract's {@code UsdAmount} precision.
 */
public record ActualUsage(
    long promptTokens,
    long completionTokens,
    Long cachedPromptTokens,
    Long reasoningTokens,
    BigDecimal promptCostUsd,
    BigDecimal completionCostUsd,
    BigDecimal totalCostUsd
) {

    public ActualUsage {
        Contract.requireTokens("promptTokens", promptTokens);
        Contract.requireTokens("completionTokens", completionTokens);
        if (cachedPromptTokens != null) {
            Contract.requireTokens("cachedPromptTokens", cachedPromptTokens);
            if (cachedPromptTokens > promptTokens) {
                throw new IllegalArgumentException("cachedPromptTokens is part of promptTokens and cannot exceed it");
            }
        }
        if (reasoningTokens != null) {
            Contract.requireTokens("reasoningTokens", reasoningTokens);
            if (reasoningTokens > completionTokens) {
                throw new IllegalArgumentException(
                    "reasoningTokens is part of completionTokens and cannot exceed it");
            }
        }
        promptCostUsd = Contract.usd("promptCostUsd", promptCostUsd);
        completionCostUsd = Contract.usd("completionCostUsd", completionCostUsd);
        totalCostUsd = Contract.usd("totalCostUsd", totalCostUsd);
    }

    /** Token counts only; the reservation is settled as {@link SettlementType#RECONCILIATION_REQUIRED}. */
    public static ActualUsage of(long promptTokens, long completionTokens) {
        return new ActualUsage(promptTokens, completionTokens, null, null, null, null, null);
    }

    /** Token counts and costs; the total is the sum of the (rounded) parts. */
    public static ActualUsage priced(long promptTokens, long completionTokens, BigDecimal promptCostUsd,
                                     BigDecimal completionCostUsd) {
        BigDecimal prompt = Contract.usd("promptCostUsd", promptCostUsd);
        BigDecimal completion = Contract.usd("completionCostUsd", completionCostUsd);
        if (prompt == null || completion == null) {
            throw new IllegalArgumentException("promptCostUsd and completionCostUsd must not be null");
        }
        return new ActualUsage(promptTokens, completionTokens, null, null, prompt, completion, prompt.add(completion));
    }

    /** Whether every cost is known, which is what a COMMIT settlement requires. */
    public boolean priced() {
        return promptCostUsd != null && completionCostUsd != null && totalCostUsd != null;
    }
}
