package example;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import io.tokenpilot.client.ClientStats;
import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.TokenPilotClientConfig;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.core.LedgerManager;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.TokenType;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.internal.LedgerComponents;

/**
 * Customer-app fixture: records LLM calls through the Token Pilot ledger and lets the SDK ship them.
 *
 * <p>Environment: {@code TP_ENDPOINT}, {@code TP_API_KEY}, {@code TP_PROJECT_KEY}, {@code TP_ENVIRONMENT},
 * optional {@code TP_CALLS} (default 5). Exits 0 when every call was delivered (or the endpoint was expected to
 * be down, {@code TP_EXPECT_DOWN=true}, and every ledger call still returned quickly).
 */
public final class ObserveFixture {

    public static void main(String[] args) throws Exception {
        int calls = Integer.parseInt(System.getenv().getOrDefault("TP_CALLS", "5"));
        boolean expectDown = Boolean.parseBoolean(System.getenv().getOrDefault("TP_EXPECT_DOWN", "false"));

        TokenPilotClient client = TokenPilotClient.create(TokenPilotClientConfig.builder()
            .endpoint(System.getenv("TP_ENDPOINT"))
            .apiKey(System.getenv("TP_API_KEY"))
            .projectKey(System.getenv("TP_PROJECT_KEY"))
            .environment(System.getenv("TP_ENVIRONMENT"))
            .maxAttempts(3)
            .shutdownTimeout(Duration.ofSeconds(10))
            .build());

        LedgerManager ledger = LedgerComponents.defaultLedgerManager(
            LedgerComponents.inMemoryPricingRegistry(List.of(() -> List.of(new PricingPlan(
                "gpt-4o-mini", PricingPlan.DEFAULT_PRICING_POLICY_ID,
                Map.of(TokenType.PROMPT, new BigDecimal("0.00015"), TokenType.COMPLETION, new BigDecimal("0.0006")),
                Currency.getInstance("USD"))))),
            LedgerComponents.defaultCostCalculator(),
            List.of(TokenPilotLedgerForwarder.builder(client).provider("openai").build()));

        long start = System.nanoTime();
        for (int i = 0; i < calls; i++) {
            // Stands in for a provider call followed by Token Pilot's post-call accounting.
            ledger.record("gpt-4o-mini", TokenUsage.from(1000 + i, 500), Map.of("tokenpilot.request.id", "req-" + i));
        }
        long recordMillis = (System.nanoTime() - start) / 1_000_000;

        client.close();
        ClientStats stats = client.stats();
        System.out.println("recordMillis=" + recordMillis + " stats=" + stats);

        boolean ok = expectDown
            ? recordMillis < 1000 && stats.droppedTotal() == calls
            : stats.created() + stats.duplicates() == calls;
        System.exit(ok ? 0 : 1);
    }
}
