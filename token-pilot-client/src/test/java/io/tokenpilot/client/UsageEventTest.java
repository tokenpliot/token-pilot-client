package io.tokenpilot.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class UsageEventTest {

    private static UsageEvent.Builder valid() {
        return UsageEvent.builder().provider("openai").model("gpt-4o-mini").pricingVersion("2026-05-01");
    }

    @Test
    void rawTextMetadataKeysNeverLeaveTheProcess() {
        UsageEvent event = valid()
            .metadata("prompt", "SECRET")
            .metadata("Messages", "SECRET")
            .metadata("feature", "summary")
            .build();

        assertThat(event.metadata()).containsExactly(Map.entry("feature", "summary"));
    }

    @Test
    void idsDefaultToOneStableUuidSoRetriesKeepTheIdempotencyKey() {
        UsageEvent event = valid().build();

        assertThat(event.eventId()).hasSize(36);
        assertThat(event.requestId()).isEqualTo(event.eventId());
    }

    @Test
    void fieldLengthsFollowTheServerColumns() {
        assertThat(valid().requestId("r".repeat(100)).build().requestId()).hasSize(100);
        assertThatThrownBy(() -> valid().requestId("r".repeat(101)).build())
            .hasMessageContaining("requestId");
        assertThatThrownBy(() -> valid().provider("p".repeat(51)).build()).hasMessageContaining("provider");
        assertThatThrownBy(() -> valid().model(" ").build()).hasMessageContaining("model");
    }

    @Test
    void amountsMustFitTheServerDecimalColumnsIncludingTheDerivedTotal() {
        assertThat(valid().totalCostUsd(UsageEvent.MAX_USD).build().totalCostUsd()).isEqualTo(UsageEvent.MAX_USD);
        assertThatThrownBy(() -> valid().totalCostUsd(new BigDecimal("1000000000000")).build())
            .hasMessageContaining("totalCostUsd");
        assertThatThrownBy(() -> valid().promptCostUsd(UsageEvent.MAX_USD)
            .completionCostUsd(new BigDecimal("0.000001")).build())
            .hasMessageContaining("derived totalCostUsd");
        assertThatThrownBy(() -> valid().promptCostUsd(new BigDecimal("-0.1")).build())
            .hasMessageContaining("negative");
    }

    @Test
    void aTokenSumThatOverflowsIsRejectedUnlessTheTotalIsGiven() {
        assertThatThrownBy(() -> valid().promptTokens(Long.MAX_VALUE).completionTokens(1).build())
            .hasMessageContaining("overflow");
        assertThat(valid().promptTokens(Long.MAX_VALUE).completionTokens(1).totalTokens(Long.MAX_VALUE).build())
            .isNotNull();
    }

    @Test
    void metadataFollowsTheContractRules() {
        assertThatThrownBy(() -> valid().metadata("Feature", "x").build()).hasMessageContaining("metadata key");
        assertThatThrownBy(() -> valid().metadata("note", "x".repeat(257)).build())
            .hasMessageContaining("256");
        Map<String, String> seventeen = new LinkedHashMap<>();
        for (int i = 0; i < 17; i++) {
            seventeen.put("k" + i, "v");
        }
        assertThatThrownBy(() -> valid().metadata(seventeen).build()).hasMessageContaining("16");
        // Forbidden keys do not count towards the limit because they are removed first.
        Map<String, String> sixteenPlusPrompt = new LinkedHashMap<>(seventeen);
        sixteenPlusPrompt.remove("k16");
        sixteenPlusPrompt.put("prompt", "SECRET");
        assertThat(valid().metadata(sixteenPlusPrompt).build().metadata()).hasSize(16);
    }
}
