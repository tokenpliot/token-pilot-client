package io.tokenpilot.client.tokenpilot.springai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.tokenpilot.autoconfigure.TokenPilotAutoConfiguration;
import io.tokenpilot.autoconfigure.TokenPilotSpringAiAutoConfiguration;
import io.tokenpilot.client.enforce.ControlPlaneEnforcer;
import io.tokenpilot.client.enforce.DecisionOutcome;
import io.tokenpilot.client.enforce.EnforcementAction;
import io.tokenpilot.client.enforce.EnforcementException;
import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.client.testing.FakeControlPlane.Reply;
import io.tokenpilot.client.tokenpilot.autoconfigure.TokenPilotClientAutoConfiguration;
import io.tokenpilot.client.tokenpilot.autoconfigure.TokenPilotEnforcementAutoConfiguration;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;
import reactor.core.publisher.Flux;

/**
 * A customer Spring Boot app with Token Pilot, this SDK and a fake provider: the Control Plane decision is applied
 * before the model call and the reservation is settled after it, through the auto-configured ChatClient advisor.
 */
class SpringAiEnforcementE2ETest {

    private static final String MODEL = "gpt-4o-2024-08-06";
    private static final String PROMPT_TEXT = "secret customer question about invoice 4711";
    private static final String DECISIONS = "/api/control/v1/decisions";

    private FakeControlPlane server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ApplicationContextRunner app(String endpoint, String... properties) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TokenPilotAutoConfiguration.class,
                TokenPilotSpringAiAutoConfiguration.class, TokenPilotClientAutoConfiguration.class,
                TokenPilotEnforcementAutoConfiguration.class))
            .withUserConfiguration(CustomerApp.class)
            .withPropertyValues(
                "token-pilot.pricing.plans[0].model-id=" + MODEL,
                "token-pilot.pricing.plans[0].currency=USD",
                "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                "token-pilot.client.endpoint=" + endpoint,
                "token-pilot.client.api-key=tpk_live_test_secret",
                "token-pilot.client.project-key=support-copilot",
                "token-pilot.client.environment=prod",
                "token-pilot.client.provider=openai",
                "token-pilot.client.flush-interval=1h",
                "token-pilot.client.enforcement.enabled=true",
                "token-pilot.client.enforcement.decision-timeout=500ms")
            .withPropertyValues(properties);
    }

    private static ChatClientResponse ask(ChatClient.Builder builder, String requestId, String endpoint) {
        return builder.build().prompt()
            .user(PROMPT_TEXT)
            .options(ChatOptions.builder().model(MODEL).maxTokens(100))
            .advisors(advisor -> {
                advisor.param("tokenpilot.request.id", requestId);
                if (endpoint != null) {
                    advisor.param(ControlPlaneEnforcementAdvisor.ENDPOINT_PARAM, endpoint);
                }
            })
            .call()
            .chatClientResponse();
    }

    /** Answers decisions with {@code decision} and every settlement with COMMITTED. */
    private static FakeControlPlane.Responder controlPlane(String decision) {
        return (request, number) -> request.path().equals(DECISIONS)
            ? new Reply(200, "{\"success\":true,\"code\":\"SUCCESS\",\"data\":" + decision + "}")
            : new Reply(200, "{\"success\":true,\"code\":\"SUCCESS\",\"data\":{\"schemaVersion\":\"2026-10-01\","
                + "\"reservationId\":\"rsv-1\",\"requestId\":\"req-1\",\"previousState\":\"IN_FLIGHT\","
                + "\"state\":\"COMMITTED\",\"duplicate\":false}}");
    }

    private static String decision(String requestId, String outcome, String mode, boolean reservation) {
        return "{\"schemaVersion\":\"2026-10-01\",\"decisionId\":\"dec-1\",\"requestId\":\"" + requestId + "\","
            + "\"outcome\":\"" + outcome + "\",\"effectiveMode\":\"" + mode + "\","
            + "\"reasons\":[" + (outcome.startsWith("DENY") ? "{\"code\":\"MONTHLY_BUDGET_EXCEEDED\",\"message\":\"x\"}" : "")
            + "],\"policyVersion\":\"pol-v3\",\"evaluatedAt\":\"2026-11-10T00:00:00Z\""
            + (reservation ? ",\"reservation\":{\"reservationId\":\"rsv-1\",\"state\":\"RESERVED\","
                + "\"reservedCostUsd\":\"1.500000\",\"expiresAt\":\"2026-11-10T00:05:00Z\"}" : "")
            + "}";
    }

    private static List<FakeControlPlane.Request> settlements(FakeControlPlane server) {
        return server.requests().stream().filter(request -> request.path().startsWith("/api/control/v1/reservations/"))
            .toList();
    }

    @Test
    void anAllowedEnforcedCallReachesTheModelAndCommitsTheReservationWithTokenPilotsCost() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-1", "ALLOW", "ENFORCE", true)));

        app(server.uri().toString(), "token-pilot.client.enforcement.mode=enforce").run(context -> {
            ChatClientResponse response = ask(context.getBean(ChatClient.Builder.class), "req-1", null);
            context.getBean(ControlPlaneEnforcer.class).close();

            assertThat(context.getBean(Provider.class).calls).hasValue(1);
            assertThat(response.chatResponse().getResult().getOutput().getText()).isEqualTo("fake answer");
            assertThat(response.context())
                .containsEntry(ControlPlaneEnforcementAdvisor.OUTCOME_CONTEXT_KEY, "ALLOW")
                .containsEntry(ControlPlaneEnforcementAdvisor.ACTION_CONTEXT_KEY, "PROCEED")
                .containsEntry(ControlPlaneEnforcementAdvisor.DECISION_ID_CONTEXT_KEY, "dec-1");

            FakeControlPlane.Request decide = server.requests().get(0);
            assertThat(decide.path()).isEqualTo(DECISIONS);
            assertThat(decide.body()).doesNotContain(PROMPT_TEXT);
            assertThat(decide.json()).containsEntry("requestId", "req-1").containsEntry("clientMode", "ENFORCE")
                .containsEntry("model", MODEL).containsEntry("provider", "openai").containsEntry("environment", "prod");
            assertThat(decide.json().get("maxOutputTokens").toString()).isEqualTo("100");
            assertThat(((BigDecimal) decide.json().get("estimatedInputTokens")).longValue()).isPositive();

            assertThat(settlements(server)).singleElement().satisfies(settlement -> {
                assertThat(settlement.path()).isEqualTo("/api/control/v1/reservations/rsv-1/settlements");
                assertThat(settlement.json()).containsEntry("result", "COMMIT").containsEntry("requestId", "req-1");
                @SuppressWarnings("unchecked")
                Map<String, Object> cost = (Map<String, Object>) settlement.json().get("cost");
                CostRecordedEvent ledger = context.getBean(LedgerProbe.class).records.get(0);
                assertThat(new BigDecimal((String) cost.get("totalCostUsd")))
                    .isEqualByComparingTo(ledger.cost().value());
                assertThat(new BigDecimal((String) cost.get("promptCostUsd"))
                    .add(new BigDecimal((String) cost.get("completionCostUsd"))))
                    .isEqualByComparingTo(new BigDecimal((String) cost.get("totalCostUsd")));
            });
        });
    }

    @Test
    void anEnforcedBudgetDenialBlocksBeforeTheModelAndBeforeTokenPilot() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-2", "DENY_BUDGET", "ENFORCE", false)));

        app(server.uri().toString(), "token-pilot.client.enforcement.mode=enforce").run(context -> {
            assertThatThrownBy(() -> ask(context.getBean(ChatClient.Builder.class), "req-2", null))
                .isInstanceOfSatisfying(EnforcementException.class, blocked -> {
                    assertThat(blocked.action()).isEqualTo(EnforcementAction.BLOCK);
                    assertThat(blocked.decision().outcome()).isEqualTo(DecisionOutcome.DENY_BUDGET);
                });

            assertThat(context.getBean(Provider.class).calls).hasValue(0);
            assertThat(context.getBean(LedgerProbe.class).records).isEmpty();
            assertThat(settlements(server)).isEmpty();
        });
    }

    @Test
    void withoutOptInADenialIsOnlyReportedAndTheCallProceeds() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-3", "DENY_POLICY", "OBSERVE", false)));

        app(server.uri().toString()).run(context -> {
            ChatClientResponse response = ask(context.getBean(ChatClient.Builder.class), "req-3", null);

            assertThat(context.getBean(Provider.class).calls).hasValue(1);
            assertThat(server.requests().get(0).json()).containsEntry("clientMode", "OBSERVE");
            assertThat(response.context())
                .containsEntry(ControlPlaneEnforcementAdvisor.OUTCOME_CONTEXT_KEY, "DENY_POLICY")
                .containsEntry(ControlPlaneEnforcementAdvisor.ACTION_CONTEXT_KEY, "PROCEED");
        });
    }

    @Test
    void aFailedProviderCallLeavesTheReservationForReconciliation() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-4", "ALLOW", "ENFORCE", true)));

        app(server.uri().toString(), "token-pilot.client.enforcement.mode=enforce").run(context -> {
            context.getBean(Provider.class).failure.set(new IllegalStateException("provider timeout"));

            assertThatThrownBy(() -> ask(context.getBean(ChatClient.Builder.class), "req-4", null))
                .hasMessageContaining("provider timeout");
            context.getBean(ControlPlaneEnforcer.class).close();

            assertThat(settlements(server)).singleElement()
                .satisfies(settlement -> assertThat(settlement.json())
                    .containsEntry("result", "RECONCILIATION_REQUIRED")
                    .doesNotContainKey("cost"));
        });
    }

    @Test
    void anUnavailableControlPlaneFollowsTheScopeFailureSettingAndIsNeverAPolicyDenial() {
        app(FakeControlPlane.UNREACHABLE.toString(),
            "token-pilot.client.enforcement.mode=enforce",
            "token-pilot.client.enforcement.endpoints.checkout.on-unavailable=fail-closed").run(context -> {
            ChatClient.Builder builder = context.getBean(ChatClient.Builder.class);

            ChatClientResponse open = ask(builder, "req-5", null);
            assertThat(open.context())
                .containsEntry(ControlPlaneEnforcementAdvisor.OUTCOME_CONTEXT_KEY, "CONTROL_PLANE_UNAVAILABLE")
                .containsEntry(ControlPlaneEnforcementAdvisor.ACTION_CONTEXT_KEY, "PROCEED_WITH_ADVISORY");

            assertThatThrownBy(() -> ask(builder, "req-6", "checkout"))
                .isInstanceOfSatisfying(EnforcementException.class, blocked -> assertThat(
                    blocked.decision().outcome()).isEqualTo(DecisionOutcome.CONTROL_PLANE_UNAVAILABLE));
            assertThat(context.getBean(Provider.class).calls).hasValue(1);
        });
    }

    @Test
    void retryingTheSameCommandReusesTheDecisionIdempotencyKey() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-7", "ALLOW", "OBSERVE", false)));

        app(server.uri().toString()).run(context -> {
            ChatClient.Builder builder = context.getBean(ChatClient.Builder.class);
            ask(builder, "req-7", null);
            ask(builder, "req-7", null);

            List<FakeControlPlane.Request> decisions = server.requests().stream()
                .filter(request -> request.path().equals(DECISIONS)).toList();
            assertThat(decisions).hasSize(2);
            assertThat(decisions.get(1).header("Idempotency-Key"))
                .isEqualTo(decisions.get(0).header("Idempotency-Key"));
            assertThat(decisions.get(1).body()).isEqualTo(decisions.get(0).body());
        });
    }

    @Test
    void nothingIsRegisteredUnlessEnforcementIsEnabled() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-8", "ALLOW", "OBSERVE", false)));

        app(server.uri().toString(), "token-pilot.client.enforcement.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(ControlPlaneEnforcementAdvisor.class);
            ask(context.getBean(ChatClient.Builder.class), "req-8", null);
            assertThat(server.requests()).isEmpty();
        });
    }

    @Test
    void streamingIsRefusedInAnEnforceScopeAndPassesElsewhere() throws Exception {
        server = FakeControlPlane.start(controlPlane(decision("req-9", "ALLOW", "OBSERVE", false)));

        app(server.uri().toString(), "token-pilot.client.enforcement.endpoints.checkout.mode=enforce")
            .run(context -> {
                ChatClient chatClient = context.getBean(ChatClient.Builder.class).build();

                List<String> observed = chatClient.prompt().user(PROMPT_TEXT)
                    .options(ChatOptions.builder().model(MODEL))
                    .stream().content().collectList().block();
                assertThat(observed).containsExactly("fake answer");

                assertThatThrownBy(() -> chatClient.prompt().user(PROMPT_TEXT)
                    .options(ChatOptions.builder().model(MODEL))
                    .advisors(advisor -> advisor.param(ControlPlaneEnforcementAdvisor.ENDPOINT_PARAM, "checkout"))
                    .stream().content().collectList().block())
                    .isInstanceOfSatisfying(EnforcementException.class, refused -> assertThat(
                        refused.decision().outcome()).isEqualTo(DecisionOutcome.INDETERMINATE));

                assertThat(context.getBean(Provider.class).calls).as("only the OBSERVE stream reached the model")
                    .hasValue(1);
                assertThat(server.requests()).as("streams never ask for a decision").isEmpty();
            });
    }

    static final class Provider implements ChatModel {

        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (failure.get() != null) {
                throw failure.get();
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("fake answer"))),
                ChatResponseMetadata.builder().model(MODEL).usage(new DefaultUsage(1_000, 2_000)).build());
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.defer(() -> Flux.just(call(prompt)));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    static final class LedgerProbe implements LedgerListener {

        final List<CostRecordedEvent> records = new CopyOnWriteArrayList<>();

        @Override
        public void onRecord(CostRecordedEvent event) {
            records.add(event);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomerApp {

        @Bean
        Provider fakeChatModel() {
            return new Provider();
        }

        /** What Spring AI's own ChatClient auto-configuration does: apply every customizer. */
        @Bean
        ChatClient.Builder chatClientBuilder(ChatModel chatModel,
            ObjectProvider<ChatClientBuilderCustomizer> customizers) {
            ChatClient.Builder builder = ChatClient.builder(chatModel);
            customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
            return builder;
        }

        @Bean
        LedgerProbe ledgerProbe() {
            return new LedgerProbe();
        }
    }
}
