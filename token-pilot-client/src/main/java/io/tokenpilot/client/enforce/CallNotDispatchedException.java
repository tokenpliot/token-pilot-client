package io.tokenpilot.client.enforce;

/**
 * Thrown from a {@link ProviderCall} to state that the provider was never reached (e.g. the request could not be
 * built, or a local rate limiter refused it). The enforcer then releases the reservation; any other exception
 * keeps it for reconciliation because the provider may have charged for the call.
 */
public class CallNotDispatchedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CallNotDispatchedException(String message) {
        super(message);
    }

    public CallNotDispatchedException(String message, Throwable cause) {
        super(message, cause);
    }
}
