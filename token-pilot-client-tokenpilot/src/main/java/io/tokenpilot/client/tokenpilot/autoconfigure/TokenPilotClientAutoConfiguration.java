package io.tokenpilot.client.tokenpilot.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.core.LedgerListener;

/**
 * Registers a {@link TokenPilotClient} and a {@link TokenPilotLedgerForwarder} when
 * {@code token-pilot.client.endpoint} is set. Token Pilot's own auto-configuration collects every
 * {@link LedgerListener} bean, so ledger records start flowing without further wiring. The client is closed with
 * the context, delivering what is queued for up to {@code token-pilot.client.shutdown-timeout}.
 */
@AutoConfiguration
@ConditionalOnClass({LedgerListener.class, TokenPilotClient.class})
@ConditionalOnProperty(prefix = "token-pilot.client", name = "endpoint")
@EnableConfigurationProperties(TokenPilotClientProperties.class)
public class TokenPilotClientAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TokenPilotClient tokenPilotClient(TokenPilotClientProperties properties) {
        return TokenPilotClient.create(TokenPilotClientConfig.builder()
            .endpoint(properties.getEndpoint())
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
}
