package io.tokenpilot.client.tokenpilot.autoconfigure;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;

/** {@code token-pilot.client.*}: sending ledger records to the Control Plane. Enabled once an endpoint is set. */
@ConfigurationProperties(prefix = "token-pilot.client")
public class TokenPilotClientProperties {

    /** Base URL of the Control Plane API. Setting it turns the client on. */
    private String endpoint;
    /** Project API key ({@code X-API-Key}). */
    private String apiKey;
    private String projectKey;
    private String environment;
    /** LLM provider name sent with every event, e.g. {@code openai}. */
    private String provider;
    private String pricingVersion = TokenPilotLedgerForwarder.DEFAULT_PRICING_VERSION;
    /** Ledger tag name to metadata key; forward only tags that never hold prompt or completion text. */
    private Map<String, String> forwardedTags = new LinkedHashMap<>();
    private int maxBatchSize = TokenPilotClientConfig.MAX_BATCH_SIZE;
    private int queueCapacity = 10_000;
    private Duration flushInterval = Duration.ofSeconds(1);
    private Duration requestTimeout = Duration.ofSeconds(5);
    private int maxAttempts = 5;
    private Duration shutdownTimeout = Duration.ofSeconds(5);

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getEnvironment() {
        return environment;
    }

    public void setEnvironment(String environment) {
        this.environment = environment;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getPricingVersion() {
        return pricingVersion;
    }

    public void setPricingVersion(String pricingVersion) {
        this.pricingVersion = pricingVersion;
    }

    public Map<String, String> getForwardedTags() {
        return forwardedTags;
    }

    public void setForwardedTags(Map<String, String> forwardedTags) {
        this.forwardedTags = forwardedTags;
    }

    public int getMaxBatchSize() {
        return maxBatchSize;
    }

    public void setMaxBatchSize(int maxBatchSize) {
        this.maxBatchSize = maxBatchSize;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getFlushInterval() {
        return flushInterval;
    }

    public void setFlushInterval(Duration flushInterval) {
        this.flushInterval = flushInterval;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requestTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    public void setShutdownTimeout(Duration shutdownTimeout) {
        this.shutdownTimeout = shutdownTimeout;
    }
}
