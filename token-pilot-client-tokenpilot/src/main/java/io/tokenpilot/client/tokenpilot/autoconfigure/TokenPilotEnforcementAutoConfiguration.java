package io.tokenpilot.client.tokenpilot.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.tokenpilot.client.enforce.ControlPlaneEnforcer;
import io.tokenpilot.client.enforce.EnforcerConfig;
import io.tokenpilot.client.tokenpilot.springai.ControlPlaneEnforcementAdvisor;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.springai.UsageExtractor;

/**
 * Puts a {@link ControlPlaneEnforcementAdvisor} on every {@code ChatClient} when
 * {@code token-pilot.client.enforcement.enabled=true}. Nothing is registered otherwise: the default connection is
 * Observe-only and never waits for a decision. Runs after Token Pilot's auto-configuration so its usage extractor,
 * estimator and pricing catalog are reused.
 */
@AutoConfiguration(afterName = {
    "io.tokenpilot.autoconfigure.TokenPilotAutoConfiguration",
    "io.tokenpilot.autoconfigure.TokenPilotSpringAiAutoConfiguration"
})
@ConditionalOnClass({ChatClient.class, UsageExtractor.class, ControlPlaneEnforcer.class})
@ConditionalOnProperty(prefix = "token-pilot.client.enforcement", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(TokenPilotClientProperties.class)
public class TokenPilotEnforcementAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ControlPlaneEnforcer tokenPilotEnforcer(TokenPilotClientProperties properties) {
        TokenPilotClientProperties.Enforcement enforcement = properties.getEnforcement();
        return ControlPlaneEnforcer.create(EnforcerConfig.builder()
            .endpoint(properties.getEndpoint())
            .apiKey(properties.getApiKey())
            .environment(properties.getEnvironment())
            .policy(enforcement.toPolicy())
            .decisionTimeout(enforcement.getDecisionTimeout())
            .shutdownTimeout(properties.getShutdownTimeout())
            .build());
    }

    @Bean
    @ConditionalOnMissingBean
    public ControlPlaneEnforcementAdvisor controlPlaneEnforcementAdvisor(
        ControlPlaneEnforcer enforcer,
        TokenPilotClientProperties properties,
        UsageExtractor usageExtractor,
        ObjectProvider<TokenEstimator> tokenEstimator,
        ObjectProvider<PricingRegistry> pricingRegistry,
        ObjectProvider<CostCalculator> costCalculator
    ) {
        TokenPilotClientProperties.Enforcement enforcement = properties.getEnforcement();
        return ControlPlaneEnforcementAdvisor.builder(enforcer, usageExtractor)
            .provider(properties.getProvider())
            .defaultModel(enforcement.getDefaultModel())
            .defaultMaxOutputTokens(enforcement.getDefaultMaxOutputTokens())
            .tokenEstimator(tokenEstimator.getIfAvailable())
            .pricing(pricingRegistry.getIfAvailable(), costCalculator.getIfAvailable())
            .build();
    }

    @Bean
    public ChatClientBuilderCustomizer controlPlaneEnforcementCustomizer(ControlPlaneEnforcementAdvisor advisor) {
        return builder -> builder.defaultAdvisors(advisor);
    }
}
