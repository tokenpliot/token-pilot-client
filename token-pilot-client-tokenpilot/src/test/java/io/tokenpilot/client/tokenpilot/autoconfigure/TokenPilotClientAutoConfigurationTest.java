package io.tokenpilot.client.tokenpilot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.Cost;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.TokenUsage;

class TokenPilotClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TokenPilotClientAutoConfiguration.class));

    @Test
    void staysOffWithoutAnEndpoint() {
        runner.run(context -> assertThat(context).doesNotHaveBean(TokenPilotClient.class)
            .doesNotHaveBean(LedgerListener.class));
    }

    @Test
    void wiresALedgerListenerThatDeliversAndFlushesOnContextClose() throws Exception {
        try (FakeControlPlane server = FakeControlPlane.start(FakeControlPlane.deduplicating())) {
            AtomicReference<TokenPilotClient> client = new AtomicReference<>();
            runner.withPropertyValues(
                    "token-pilot.client.endpoint=" + server.uri(),
                    "token-pilot.client.api-key=tpk_live_test_secret",
                    "token-pilot.client.project-key=support-copilot",
                    "token-pilot.client.environment=prod",
                    "token-pilot.client.provider=openai",
                    "token-pilot.client.flush-interval=1h",
                    "token-pilot.client.forwarded-tags.tenant=tenant_id")
                .run(context -> {
                    assertThat(context).hasSingleBean(TokenPilotLedgerForwarder.class);
                    client.set(context.getBean(TokenPilotClient.class));
                    context.getBean(LedgerListener.class).onRecord(new CostRecordedEvent("gpt-4o-mini",
                        TokenUsage.from(10, 5), Cost.of(new BigDecimal("0.001"), Currency.getInstance("USD")),
                        Map.of("tenant", "acme")));
                });

            // Closing the context closed the client, which delivered the queued event first.
            assertThat(client.get().stats().created()).isEqualTo(1);
            assertThat(server.requests().get(0).items().get(0).get("metadata"))
                .isEqualTo(Map.of("usage_source", "provider_reported", "tenant_id", "acme"));
            assertThat(client.get().flush(Duration.ofMillis(10))).isTrue();
        }
    }
}
