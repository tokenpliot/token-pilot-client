package io.tokenpilot.client.internal;

import java.util.List;

import io.tokenpilot.client.UsageEvent;

/**
 * One attempt at delivering a list of events, classified into what the dispatcher must do next. Implementations
 * never surface a response body as a whole, so nothing the server echoes can leak into logs.
 */
public interface Transport {

    Outcome send(List<UsageEvent> events) throws InterruptedException;

    /** Outcome of one send. */
    sealed interface Outcome {

        /** One result per event, in request order. */
        record Items(List<ItemResult> items) implements Outcome {
        }

        /** Network error, timeout, 5xx, 429 or an unreadable reply: send the same events again later. */
        record Retry(String reason) implements Outcome {
        }

        /** The request body was too large; the dispatcher splits the events or drops a lone one. */
        record TooLarge(String detail) implements Outcome {
        }

        /** Any other 4xx for the whole request: retrying will not help. */
        record Refused(int status, String code) implements Outcome {
        }
    }

    enum ItemStatus {
        CREATED,
        DUPLICATE,
        REJECTED
    }

    record ItemResult(ItemStatus status, String code, boolean retryable) {
    }
}
