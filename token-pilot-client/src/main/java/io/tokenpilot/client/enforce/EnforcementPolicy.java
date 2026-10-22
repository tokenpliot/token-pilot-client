package io.tokenpilot.client.enforce;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Where enforcement is switched on. An enforcer is bound to one project API key and environment, so
 * {@link #defaults()} is the project/environment scope; {@link Builder#endpoint} overrides it for one call site
 * (a name the customer picks, e.g. {@code checkout-summary}, passed as {@link CallRequest#endpoint()}). This lets
 * a customer opt a single feature into ENFORCE, and roll it back, without touching the rest.
 */
public final class EnforcementPolicy {

    private static final EnforcementPolicy OBSERVE_ONLY = builder().build();

    private final EnforcementSettings defaults;
    private final Map<String, EnforcementSettings> endpoints;

    private EnforcementPolicy(Builder builder) {
        this.defaults = Objects.requireNonNull(builder.defaults, "defaults must not be null");
        this.endpoints = Map.copyOf(builder.endpoints);
    }

    /** Nothing enforced anywhere: every call site uses {@link EnforcementSettings#defaults()} (OBSERVE). */
    public static EnforcementPolicy observeOnly() {
        return OBSERVE_ONLY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public EnforcementSettings defaults() {
        return defaults;
    }

    /** Overrides by call-site name. */
    public Map<String, EnforcementSettings> endpoints() {
        return endpoints;
    }

    /** Settings for the call site; a null or unconfigured endpoint gets {@link #defaults()}. */
    public EnforcementSettings settingsFor(String endpoint) {
        if (endpoint == null) {
            return defaults;
        }
        return endpoints.getOrDefault(endpoint, defaults);
    }

    @Override
    public String toString() {
        return "EnforcementPolicy[defaults=" + defaults + ", endpoints=" + endpoints + "]";
    }

    public static final class Builder {

        private EnforcementSettings defaults = EnforcementSettings.defaults();
        private final Map<String, EnforcementSettings> endpoints = new LinkedHashMap<>();

        private Builder() {
        }

        /** Project/environment-wide settings. Defaults to {@link EnforcementSettings#defaults()} (OBSERVE). */
        public Builder defaults(EnforcementSettings defaults) {
            this.defaults = defaults;
            return this;
        }

        /** Settings for one call site; the name must be an Identifier ({@code ^[A-Za-z0-9._:-]+$}, 1..128). */
        public Builder endpoint(String endpoint, EnforcementSettings settings) {
            Contract.requireIdentifier("endpoint", endpoint);
            endpoints.put(endpoint, Objects.requireNonNull(settings, "settings must not be null"));
            return this;
        }

        public EnforcementPolicy build() {
            return new EnforcementPolicy(this);
        }
    }
}
