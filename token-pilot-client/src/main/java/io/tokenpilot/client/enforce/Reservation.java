package io.tokenpilot.client.enforce;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Budget reserved by an {@code ALLOW} decision in {@link EnforcementMode#ENFORCE}. The enforcer settles it after
 * the provider call; {@code reservedCostUsd} and {@code expiresAt} are informational and may be null when the
 * server omitted or malformed them.
 */
public record Reservation(String reservationId, String state, BigDecimal reservedCostUsd, Instant expiresAt) {

    public Reservation {
        Objects.requireNonNull(reservationId, "reservationId must not be null");
    }
}
