package io.tokenpilot.client.enforce;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;

import org.junit.jupiter.api.Test;

class EnforcerConfigTest {

    private static EnforcerConfig.Builder config() {
        return EnforcerConfig.builder()
            .endpoint("https://api.token-pilot.cloud")
            .apiKey("tpk_live_super_secret")
            .environment("prod");
    }

    @Test
    void defaultsAreObserveOnlyWithShortDecisionTimeout() {
        EnforcerConfig config = config().build();

        assertThat(config.policy()).isSameAs(EnforcementPolicy.observeOnly());
        assertThat(config.connectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(config.decisionTimeout()).isEqualTo(Duration.ofSeconds(1));
        assertThat(config.settlementTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.settlementMaxAttempts()).isEqualTo(5);
        assertThat(config.settlementQueueCapacity()).isEqualTo(10_000);
        assertThat(config.shutdownTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(config.listener()).isSameAs(EnforcementListener.NONE);
        assertThat(config.clock()).isEqualTo(Clock.systemUTC());
    }

    @Test
    void decisionTimeoutIsCappedSoAHungControlPlaneCannotHoldTheCall() {
        assertThat(config().decisionTimeout(EnforcerConfig.MAX_DECISION_TIMEOUT).build().decisionTimeout())
            .isEqualTo(Duration.ofSeconds(5));
        assertThatThrownBy(() -> config().decisionTimeout(Duration.ofMillis(5001)).build())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("decisionTimeout");
        assertThatThrownBy(() -> config().decisionTimeout(Duration.ZERO).build())
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void toStringNeverShowsTheApiKey() {
        assertThat(config().build().toString())
            .doesNotContain("tpk_live_super_secret")
            .contains("environment=prod");
    }

    @Test
    void rejectsInvalidSettings() {
        assertThatThrownBy(() -> config().endpoint("ftp://api.token-pilot.cloud").build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().apiKey("key with spaces").build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().apiKey(null).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().environment("pr od").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().environment("e".repeat(51)).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().settlementMaxAttempts(0).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().settlementMaxAttempts(101).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().initialBackoff(Duration.ofSeconds(2)).maxBackoff(Duration.ofSeconds(1))
            .build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().settlementQueueCapacity(0).build())
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> config().policy(null).build()).isInstanceOf(NullPointerException.class);
    }
}
