package io.tokenpilot.client;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/**
 * Connection and delivery settings. The defaults favour the Observe mode: a bounded in-memory queue, small batches
 * sent every second, and a few retries before an event is dropped. Nothing here can block or fail the caller's
 * LLM call.
 */
public final class TokenPilotClientConfig {

    /** Server limit on items per batch request. */
    public static final int MAX_BATCH_SIZE = 100;
    /** Server limit on a batch request body (1 MiB). */
    public static final int MAX_BATCH_BYTES = 1024 * 1024;

    private final URI endpoint;
    private final String apiKey;
    private final String projectKey;
    private final String environment;
    private final int maxBatchSize;
    private final int maxBatchBytes;
    private final int queueCapacity;
    private final Duration flushInterval;
    private final Duration connectTimeout;
    private final Duration requestTimeout;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration shutdownTimeout;
    private final DeliveryListener deliveryListener;

    private TokenPilotClientConfig(Builder builder) {
        this.endpoint = requireEndpoint(builder.endpoint);
        this.apiKey = requireText("apiKey", builder.apiKey);
        if (!apiKey.chars().allMatch(c -> c > 0x20 && c < 0x7f)) {
            throw new IllegalArgumentException("apiKey must be printable ASCII without spaces");
        }
        this.projectKey = requireText("projectKey", builder.projectKey);
        this.environment = requireText("environment", builder.environment);
        if (environment.length() > 20) {
            throw new IllegalArgumentException("environment must be at most 20 characters");
        }
        this.maxBatchSize = requireRange("maxBatchSize", builder.maxBatchSize, 1, MAX_BATCH_SIZE);
        this.maxBatchBytes = requireRange("maxBatchBytes", builder.maxBatchBytes, 1024, MAX_BATCH_BYTES);
        this.queueCapacity = requireRange("queueCapacity", builder.queueCapacity, 1, Integer.MAX_VALUE);
        this.flushInterval = requirePositive("flushInterval", builder.flushInterval);
        this.connectTimeout = requirePositive("connectTimeout", builder.connectTimeout);
        this.requestTimeout = requirePositive("requestTimeout", builder.requestTimeout);
        this.maxAttempts = requireRange("maxAttempts", builder.maxAttempts, 1, 100);
        this.initialBackoff = requirePositive("initialBackoff", builder.initialBackoff);
        this.maxBackoff = requirePositive("maxBackoff", builder.maxBackoff);
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be shorter than initialBackoff");
        }
        this.shutdownTimeout = requirePositive("shutdownTimeout", builder.shutdownTimeout);
        this.deliveryListener = Objects.requireNonNull(builder.deliveryListener, "deliveryListener must not be null");
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

    public String projectKey() {
        return projectKey;
    }

    public String environment() {
        return environment;
    }

    public int maxBatchSize() {
        return maxBatchSize;
    }

    public int maxBatchBytes() {
        return maxBatchBytes;
    }

    public int queueCapacity() {
        return queueCapacity;
    }

    public Duration flushInterval() {
        return flushInterval;
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration requestTimeout() {
        return requestTimeout;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public Duration initialBackoff() {
        return initialBackoff;
    }

    public Duration maxBackoff() {
        return maxBackoff;
    }

    public Duration shutdownTimeout() {
        return shutdownTimeout;
    }

    public DeliveryListener deliveryListener() {
        return deliveryListener;
    }

    /** The API key is never printed. */
    @Override
    public String toString() {
        return "TokenPilotClientConfig[endpoint=" + endpoint + ", projectKey=" + projectKey
            + ", environment=" + environment + ", maxBatchSize=" + maxBatchSize + ", queueCapacity=" + queueCapacity
            + ", flushInterval=" + flushInterval + ", maxAttempts=" + maxAttempts + "]";
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

    private static String requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
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
        private String projectKey;
        private String environment;
        private int maxBatchSize = MAX_BATCH_SIZE;
        private int maxBatchBytes = 512 * 1024;
        private int queueCapacity = 10_000;
        private Duration flushInterval = Duration.ofSeconds(1);
        private Duration connectTimeout = Duration.ofSeconds(2);
        private Duration requestTimeout = Duration.ofSeconds(5);
        private int maxAttempts = 5;
        private Duration initialBackoff = Duration.ofMillis(200);
        private Duration maxBackoff = Duration.ofSeconds(10);
        private Duration shutdownTimeout = Duration.ofSeconds(5);
        private DeliveryListener deliveryListener = DeliveryListener.NONE;

        private Builder() {
        }

        /** Base URL of the Control Plane API, e.g. {@code https://api.example.com}. */
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

        public Builder projectKey(String projectKey) {
            this.projectKey = projectKey;
            return this;
        }

        public Builder environment(String environment) {
            this.environment = environment;
            return this;
        }

        public Builder maxBatchSize(int maxBatchSize) {
            this.maxBatchSize = maxBatchSize;
            return this;
        }

        /** Batches whose encoded body exceeds this are split before sending. */
        public Builder maxBatchBytes(int maxBatchBytes) {
            this.maxBatchBytes = maxBatchBytes;
            return this;
        }

        /** Events beyond this many waiting are dropped ({@link DropReason#QUEUE_FULL}) instead of blocking. */
        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
            return this;
        }

        public Builder flushInterval(Duration flushInterval) {
            this.flushInterval = flushInterval;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        /** Sends per batch, including the first; after the last one the batch is dropped. */
        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
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

        /** How long {@link TokenPilotClient#close()} keeps delivering what is queued. */
        public Builder shutdownTimeout(Duration shutdownTimeout) {
            this.shutdownTimeout = shutdownTimeout;
            return this;
        }

        public Builder deliveryListener(DeliveryListener deliveryListener) {
            this.deliveryListener = deliveryListener;
            return this;
        }

        public TokenPilotClientConfig build() {
            return new TokenPilotClientConfig(this);
        }
    }
}
