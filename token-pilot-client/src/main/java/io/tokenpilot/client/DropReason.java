package io.tokenpilot.client;

/** Why an event was given up on. */
public enum DropReason {
    /** The in-memory queue was full when the event was recorded. */
    QUEUE_FULL,
    /** The event was recorded after {@link TokenPilotClient#close()}. */
    CLOSED,
    /** The server rejected this item and said a retry would not help (for example a payload conflict). */
    REJECTED,
    /** The server refused the whole request: bad API key, wrong project or environment, invalid request. */
    REQUEST_REFUSED,
    /** The event alone is larger than the batch body limit. */
    PAYLOAD_TOO_LARGE,
    /** Every attempt failed with a retryable error (network, timeout, 5xx). */
    RETRIES_EXHAUSTED,
    /** The client closed before the event could be delivered. */
    SHUTDOWN_TIMEOUT
}
