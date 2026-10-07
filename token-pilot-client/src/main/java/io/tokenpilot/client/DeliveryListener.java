package io.tokenpilot.client;

import java.util.List;

/**
 * Observes delivery outcomes. Called on the client's sender thread (or the caller's thread for
 * {@link DropReason#QUEUE_FULL} and {@link DropReason#CLOSED}); keep implementations fast. Exceptions thrown here
 * are logged and ignored.
 */
public interface DeliveryListener {

    DeliveryListener NONE = new DeliveryListener() {
    };

    /** The server stored the event, or already had it ({@code duplicate}). */
    default void onDelivered(UsageEvent event, boolean duplicate) {
    }

    /** These events will be sent again after a backoff; {@code attempt} is the attempt that just failed. */
    default void onRetry(List<UsageEvent> events, int attempt, String reason) {
    }

    /** The event will not be delivered. {@code detail} never contains the API key or metadata values. */
    default void onDropped(UsageEvent event, DropReason reason, String detail) {
    }
}
