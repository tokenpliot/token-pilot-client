package io.tokenpilot.client.tokenpilot.autoconfigure;

import java.time.Duration;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.tokenpilot.client.IngestionApi;
import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.enforce.DecisionOutcome;
import io.tokenpilot.client.enforce.EnforcementMode;
import io.tokenpilot.client.enforce.EnforcementPolicy;
import io.tokenpilot.client.enforce.EnforcementSettings;
import io.tokenpilot.client.enforce.EnforcerConfig;
import io.tokenpilot.client.enforce.FailureBehavior;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;

/** {@code token-pilot.client.*}: sending ledger records to the Control Plane. Enabled once an endpoint is set. */
@ConfigurationProperties(prefix = "token-pilot.client")
public class TokenPilotClientProperties {

    /** Base URL of the Control Plane API. Setting it turns the client on. */
    private String endpoint;
    /** {@code ingestion-batch} (default) or {@code observe-v2} (the Observe ledger; the server must enable it). */
    private IngestionApi ingestionApi = IngestionApi.INGESTION_BATCH;
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
    /** Decisions before each ChatClient call; off unless {@code enforcement.enabled=true}. */
    private final Enforcement enforcement = new Enforcement();

    public Enforcement getEnforcement() {
        return enforcement;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public IngestionApi getIngestionApi() {
        return ingestionApi;
    }

    public void setIngestionApi(IngestionApi ingestionApi) {
        this.ingestionApi = ingestionApi;
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

    /**
     * {@code token-pilot.client.enforcement.*}. The top-level settings are the project/environment scope of the API
     * key; {@code endpoints.<name>} overrides them for calls that pass that name as the
     * {@code tokenpilot.enforcement.endpoint} advisor param. Unset endpoint fields inherit the top-level value.
     */
    public static class Enforcement extends Scope {

        /** Registers the ChatClient advisor. Even in OBSERVE mode every call then waits for a decision. */
        private boolean enabled;
        /** Upper bound on the wait for a decision, at most {@link EnforcerConfig#MAX_DECISION_TIMEOUT}. */
        private Duration decisionTimeout = Duration.ofSeconds(1);
        /** Model reported when the prompt options carry none. */
        private String defaultModel;
        /** Output reservation when the prompt options set no {@code maxTokens}. */
        private long defaultMaxOutputTokens = 1024;
        private final Map<String, Scope> endpoints = new LinkedHashMap<>();

        public Enforcement() {
            super(EnforcementMode.OBSERVE, EnumSet.of(DecisionOutcome.DENY_POLICY, DecisionOutcome.DENY_BUDGET),
                FailureBehavior.FAIL_OPEN, FailureBehavior.FAIL_OPEN);
        }

        public EnforcementPolicy toPolicy() {
            EnforcementSettings defaults = new EnforcementSettings(getMode(), getEnforcedOutcomes(),
                getOnIndeterminate(), getOnUnavailable());
            EnforcementPolicy.Builder policy = EnforcementPolicy.builder().defaults(defaults);
            endpoints.forEach((name, scope) -> policy.endpoint(name, new EnforcementSettings(
                scope.getMode() != null ? scope.getMode() : defaults.mode(),
                scope.getEnforcedOutcomes() != null ? scope.getEnforcedOutcomes() : defaults.enforcedOutcomes(),
                scope.getOnIndeterminate() != null ? scope.getOnIndeterminate() : defaults.onIndeterminate(),
                scope.getOnUnavailable() != null ? scope.getOnUnavailable() : defaults.onUnavailable())));
            return policy.build();
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getDecisionTimeout() {
            return decisionTimeout;
        }

        public void setDecisionTimeout(Duration decisionTimeout) {
            this.decisionTimeout = decisionTimeout;
        }

        public String getDefaultModel() {
            return defaultModel;
        }

        public void setDefaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
        }

        public long getDefaultMaxOutputTokens() {
            return defaultMaxOutputTokens;
        }

        public void setDefaultMaxOutputTokens(long defaultMaxOutputTokens) {
            this.defaultMaxOutputTokens = defaultMaxOutputTokens;
        }

        public Map<String, Scope> getEndpoints() {
            return endpoints;
        }
    }

    /** Enforcement settings of one scope (ADR 0001 {@code EnforcementSettings}). */
    public static class Scope {

        /** {@code observe}, {@code advisory} or {@code enforce}. */
        private EnforcementMode mode;
        /** Outcomes ENFORCE acts on: {@code DENY_POLICY}, {@code DENY_BUDGET}, {@code REQUIRE_APPROVAL}. */
        private Set<DecisionOutcome> enforcedOutcomes;
        /** ENFORCE only: {@code fail-closed} blocks when the Control Plane cannot judge the request. */
        private FailureBehavior onIndeterminate;
        /** ENFORCE only: {@code fail-closed} blocks while the Control Plane is unavailable. */
        private FailureBehavior onUnavailable;

        /** Endpoint scope: every field unset, inherited from the top-level settings. */
        public Scope() {
        }

        Scope(EnforcementMode mode, Set<DecisionOutcome> enforcedOutcomes, FailureBehavior onIndeterminate,
            FailureBehavior onUnavailable) {
            this.mode = mode;
            this.enforcedOutcomes = enforcedOutcomes;
            this.onIndeterminate = onIndeterminate;
            this.onUnavailable = onUnavailable;
        }

        public EnforcementMode getMode() {
            return mode;
        }

        public void setMode(EnforcementMode mode) {
            this.mode = mode;
        }

        public Set<DecisionOutcome> getEnforcedOutcomes() {
            return enforcedOutcomes;
        }

        public void setEnforcedOutcomes(Set<DecisionOutcome> enforcedOutcomes) {
            this.enforcedOutcomes = enforcedOutcomes;
        }

        public FailureBehavior getOnIndeterminate() {
            return onIndeterminate;
        }

        public void setOnIndeterminate(FailureBehavior onIndeterminate) {
            this.onIndeterminate = onIndeterminate;
        }

        public FailureBehavior getOnUnavailable() {
            return onUnavailable;
        }

        public void setOnUnavailable(FailureBehavior onUnavailable) {
            this.onUnavailable = onUnavailable;
        }
    }
}
