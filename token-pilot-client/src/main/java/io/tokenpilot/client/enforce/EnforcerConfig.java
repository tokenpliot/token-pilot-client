package io.tokenpilot.client.enforce;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Settings of a {@link ControlPlaneEnforcer}. The decision call sits in front of every guarded provider call, so
 * its timeout is short by default and capped at {@link #MAX_DECISION_TIMEOUT}: a hung Control Plane can never hold
 * a provider call longer than that. Settlement runs in the background and may take longer.
 */
public final class EnforcerConfig {

    /** Upper bound for {@link #decisionTimeout()}. */
    public static final Duration MAX_DECISION_TIMEOUT = Duration.ofSeconds(5);

    private static final int MAX_ENVIRONMENT_LENGTH = 50;

    private final URI endpoint;
    private final String apiKey;
    private final String environment;
    private final EnforcementPolicy policy;
    private final Duration connectTimeout;
    private final Duration decisionTimeout;
    private final Duration settlementTimeout;
    private final int settlementMaxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int settlementQueueCapacity;
    private final Duration shutdownTimeout;
    private final EnforcementListener listener;
    private final Clock clock;

    private EnforcerConfig(Builder builder) {
        this.endpoint = requireEndpoint(builder.endpoint);
        if (builder.apiKey == null || builder.apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        if (!builder.apiKey.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException("apiKey must be printable ASCII without spaces");
        }
        this.apiKey = builder.apiKey;
        this.environment = Contract.requireIdentifier("environment", builder.environment);
        if (environment.length() > MAX_ENVIRONMENT_LENGTH) {
            throw new IllegalArgumentException("environment must be at most " + MAX_ENVIRONMENT_LENGTH + " characters");
        }
        this.policy = Objects.requireNonNull(builder.policy, "policy must not be null");
        this.connectTimeout = requirePositive("connectTimeout", builder.connectTimeout);
        this.decisionTimeout = requirePositive("decisionTimeout", builder.decisionTimeout);
        if (decisionTimeout.compareTo(MAX_DECISION_TIMEOUT) > 0) {
            throw new IllegalArgumentException("decisionTimeout must be at most " + MAX_DECISION_TIMEOUT);
        }
        this.settlementTimeout = requirePositive("settlementTimeout", builder.settlementTimeout);
        this.settlementMaxAttempts = requireRange("settlementMaxAttempts", builder.settlementMaxAttempts, 1, 100);
        this.initialBackoff = requirePositive("initialBackoff", builder.initialBackoff);
        this.maxBackoff = requirePositive("maxBackoff", builder.maxBackoff);
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        this.settlementQueueCapacity =
            requireRange("settlementQueueCapacity", builder.settlementQueueCapacity, 1, Integer.MAX_VALUE);
        this.shutdownTimeout = requirePositive("shutdownTimeout", builder.shutdownTimeout);
        this.listener = Objects.requireNonNull(builder.listener, "listener must not be null");
        this.clock = Objects.requireNonNull(builder.clock, "clock must not be null");
    }

    public static Builder builder() {
        return new Builder();
    }

    public URI endpoint() {
        return endpoint;
    }

    public String apiKey() {
        return apiKey;
    }

    public String environment() {
        return environment;
    }

    public EnforcementPolicy policy() {
        return policy;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration decisionTimeout() {
        return decisionTimeout;
    }

    public Duration settlementTimeout() {
        return settlementTimeout;
    }

    public int settlementMaxAttempts() {
        return settlementMaxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public int settlementQueueCapacity() {
        return settlementQueueCapacity;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    public EnforcementListener listener() {
        return listener;
    }

    public Clock clock() {
        return clock;
    }

    /** The API key is never printed. */
    @Override
    public String toString() {
        return "EnforcerConfig[endpoint=" + endpoint + ", environment=" + environment + ", policy=" + policy
            + ", decisionTimeout=" + decisionTimeout + ", settlementMaxAttempts=" + settlementMaxAttempts
            + ", settlementQueueCapacity=" + settlementQueueCapacity + "]";
    }

    private static URI requireEndpoint(URI endpoint) {
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("endpoint must be an http or https URI");
        }
        if (endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must have a host");
        }
        return endpoint;
    }

    private static int requireRange(String name, int value, int min, int max) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(name + " must be between " + min + " and " + max);
        }
        return value;
    }

    private static Duration requirePositive(String name, Duration value) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public static final class Builder {

        private URI endpoint;
        private String apiKey;
        private String environment;
        private EnforcementPolicy policy = EnforcementPolicy.observeOnly();
        private Duration connectTimeout = Duration.ofMillis(500);
        private Duration decisionTimeout = Duration.ofSeconds(1);
        private Duration settlementTimeout = Duration.ofSeconds(5);
        private int settlementMaxAttempts = 5;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private int settlementQueueCapacity = 10_000;
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private EnforcementListener listener = EnforcementListener.NONE;
        private Clock clock = Clock.systemUTC();

        private Builder() {
        }

        /** Base URL of the Control Plane API, e.g. {@code https://api.token-pilot.cloud}. */
        public Builder endpoint(URI endpoint) {
            this.endpoint = endpoint;
            return this;
        }

        public Builder endpoint(String endpoint) {
            return endpoint(URI.create(endpoint));
        }

        /** Project API key, sent as the {@code X-API-Key} header. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        /** Where enforcement is on. Defaults to {@link EnforcementPolicy#observeOnly()}. */
        public Builder policy(EnforcementPolicy policy) {
            this.policy = policy;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        /**
         * Hard limit for one decision call, at most {@link EnforcerConfig#MAX_DECISION_TIMEOUT}; past it the
         * Control Plane counts as unavailable.
         */
        public Builder decisionTimeout(Duration decisionTimeout) {
            this.decisionTimeout = decisionTimeout;
            return this;
        }

        public Builder settlementTimeout(Duration settlementTimeout) {
            this.settlementTimeout = settlementTimeout;
            return this;
        }

        /** Sends per settlement, including the first. */
        public Builder settlementMaxAttempts(int settlementMaxAttempts) {
            this.settlementMaxAttempts = settlementMaxAttempts;
            return this;
        }

        public Builder initialBackoff(Duration initialBackoff) {
            this.initialBackoff = initialBackoff;
            return this;
        }

        public Builder maxBackoff(Duration maxBackoff) {
            this.maxBackoff = maxBackoff;
            return this;
        }

        /** Settlements beyond this many waiting are reported as failed instead of blocking the caller. */
        public Builder settlementQueueCapacity(int settlementQueueCapacity) {
            this.settlementQueueCapacity = settlementQueueCapacity;
            return this;
        }

        /** How long {@link ControlPlaneEnforcer#close()} keeps delivering queued settlements. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        public Builder listener(EnforcementListener listener) {
            this.listener = listener;
            return this;
        }

        /** Source of settlement {@code occurredAt}. */
        public Builder clock(Clock clock) {
            this.clock = clock;
            return this;
        }

        public EnforcerConfig build() {
            return new EnforcerConfig(this);
        }
    }
}
