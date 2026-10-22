package io.tokenpilot.client.tokenpilot.autoconfigure;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.client.tokenpilot.springai.UnreconciledUsageAdvisor;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.springai.UsageExtractor;

/**
 * Registers a {@link TokenPilotClient} and a {@link TokenPilotLedgerForwarder} when
 * {@code token-pilot.client.endpoint} is set. Token Pilot's own auto-configuration collects every
 * {@link LedgerListener} bean, so ledger records start flowing without further wiring. With Spring AI, an
 * {@link UnreconciledUsageAdvisor} also forwards the calls Token Pilot leaves out of its ledger. The client is
 * closed with the context, delivering what is queued for up to {@code token-pilot.client.shutdown-timeout}.
 */
@AutoConfiguration(afterName = "io.tokenpilot.autoconfigure.TokenPilotSpringAiAutoConfiguration")
@ConditionalOnClass({LedgerListener.class, TokenPilotClient.class})
@ConditionalOnProperty(prefix = "token-pilot.client", name = "endpoint")
@EnableConfigurationProperties(TokenPilotClientProperties.class)
public class TokenPilotClientAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TokenPilotClient tokenPilotClient(TokenPilotClientProperties properties) {
        return TokenPilotClient.create(TokenPilotClientConfig.builder()
            .endpoint(properties.getEndpoint())
            .ingestionApi(properties.getIngestionApi())
            .apiKey(properties.getApiKey())
            .projectKey(properties.getProjectKey())
            .environment(properties.getEnvironment())
            .maxBatchSize(properties.getMaxBatchSize())
            .queueCapacity(properties.getQueueCapacity())
            .flushInterval(properties.getFlushInterval())
            .requestTimeout(properties.getRequestTimeout())
            .maxAttempts(properties.getMaxAttempts())
            .shutdownTimeout(properties.getShutdownTimeout())
            .build());
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenPilotLedgerForwarder tokenPilotLedgerForwarder(
        TokenPilotClient client,
        TokenPilotClientProperties properties
    ) {
        TokenPilotLedgerForwarder.Builder builder = TokenPilotLedgerForwarder.builder(client)
            .provider(properties.getProvider())
            .pricingVersion(properties.getPricingVersion());
        properties.getForwardedTags().forEach(builder::forwardTag);
        return builder.build();
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ChatClient.class, UsageExtractor.class})
    @ConditionalOnBean(UsageExtractor.class)
    static class SpringAiConfiguration {

        @Bean
        @ConditionalOnMissingBean
        UnreconciledUsageAdvisor unreconciledUsageAdvisor(TokenPilotLedgerForwarder forwarder,
            UsageExtractor usageExtractor) {
            return new UnreconciledUsageAdvisor(forwarder, usageExtractor);
        }

        @Bean
        ChatClientBuilderCustomizer unreconciledUsageCustomizer(UnreconciledUsageAdvisor advisor) {
            return builder -> builder.defaultAdvisors(advisor);
        }
    }
}
