package io.tokenpilot.client;

import java.util.Map;

/**
 * Point-in-time delivery counters.
 *
 * @param recorded   events accepted into the queue
 * @param created    events the server stored
 * @param duplicates events the server already had (replays after a lost response)
 * @param retried    event sends that failed with a retryable error and were scheduled again
 * @param dropped    events given up on, by reason
 * @param queued     events waiting to be sent or in flight
 */
public record ClientStats(
    long recorded,
    long created,
    long duplicates,
    long retried,
    Map<DropReason, Long> dropped,
    long queued
) {

    public ClientStats {
        dropped = Map.copyOf(dropped);
    }

    public long droppedTotal() {
        return dropped.values().stream().mapToLong(Long::longValue).sum();
    }
}
