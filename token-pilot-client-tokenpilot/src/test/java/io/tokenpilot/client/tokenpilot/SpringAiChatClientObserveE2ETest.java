package io.tokenpilot.client.tokenpilot;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
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
import io.tokenpilot.client.TokenPilotClient;
import io.tokenpilot.client.testing.FakeControlPlane;
import io.tokenpilot.client.tokenpilot.autoconfigure.TokenPilotClientAutoConfiguration;
import io.tokenpilot.core.LedgerListener;
import io.tokenpilot.core.domain.CostRecordedEvent;

/**
 * A customer Spring Boot app that only adds this SDK and properties: a {@code ChatClient} call goes through Token
 * Pilot's advisor and ledger, and the usage reaches the Control Plane without any glue code.
 */
class SpringAiChatClientObserveE2ETest {

    private static final String MODEL = "gpt-4o-2024-08-06";
    private static final String PROMPT_TEXT = "secret customer question about invoice 4711";

    private FakeControlPlane server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.close();
        }
    }

    private ApplicationContextRunner app(String... clientProperties) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TokenPilotAutoConfiguration.class,
                TokenPilotSpringAiAutoConfiguration.class, TokenPilotClientAutoConfiguration.class))
            .withUserConfiguration(CustomerApp.class)
            .withPropertyValues(
                "token-pilot.pricing.plans[0].model-id=" + MODEL,
                "token-pilot.pricing.plans[0].currency=USD",
                "token-pilot.pricing.plans[0].rates.PROMPT=0.00015",
                "token-pilot.pricing.plans[0].rates.COMPLETION=0.00060",
                "token-pilot.client.endpoint=" + server.uri(),
                "token-pilot.client.api-key=tpk_live_test_secret",
                "token-pilot.client.project-key=support-copilot",
                "token-pilot.client.environment=prod",
                "token-pilot.client.provider=openai",
                "token-pilot.client.flush-interval=1h",
                "token-pilot.client.forwarded-tags.tenant_id=tenant_id")
            .withPropertyValues(clientProperties);
    }

    private static String ask(ChatClient chatClient) {
        return chatClient.prompt()
            .user(PROMPT_TEXT)
            .options(ChatOptions.builder().model(MODEL).maxTokens(100))
            .advisors(advisor -> advisor.param("tokenpilot.request.id", "app-req-1").param("tenant_id", "tenant-a"))
            .call()
            .content();
    }

    @Test
    void aChatClientCallIsSentToTheBatchEndpointWithTheLedgerCost() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());

        app().run(context -> {
            String answer = ask(context.getBean(ChatClient.Builder.class).build());
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(answer).isEqualTo("fake answer");
            assertThat(server.requests()).hasSize(1);
            FakeControlPlane.Request request = server.requests().get(0);
            assertThat(request.path()).isEqualTo("/api/ingestion/events/batch");
            assertThat(request.body()).doesNotContain(PROMPT_TEXT).doesNotContain("fake answer");

            Map<String, Object> item = request.items().get(0);
            assertThat(item).containsEntry("provider", "openai").containsEntry("model", MODEL)
                .containsEntry("sourceType", TokenPilotLedgerForwarder.SOURCE_TYPE);
            assertThat(item.get("promptTokens").toString()).isEqualTo("1000");
            assertThat(item.get("completionTokens").toString()).isEqualTo("2000");
            assertThat(item.get("metadata")).isEqualTo(Map.of(
                "usage_source", "provider_reported", "app_request_id", "app-req-1", "tenant_id", "tenant-a"));

            CostRecordedEvent ledgerRecord = context.getBean(LedgerProbe.class).records.get(0);
            assertThat(new BigDecimal(item.get("totalCostUsd").toString()))
                .isPositive()
                .isEqualByComparingTo(ledgerRecord.cost().value());
            assertThat(client.stats().created()).isEqualTo(1);
        });
    }

    @Test
    void withObserveV2TheSameCallReachesTheObserveLedgerEndpoint() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.observeDeduplicating());

        app("token-pilot.client.ingestion-api=observe-v2").run(context -> {
            ask(context.getBean(ChatClient.Builder.class).build());
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(5))).isTrue();

            assertThat(server.requests()).hasSize(1);
            FakeControlPlane.Request request = server.requests().get(0);
            assertThat(request.path()).isEqualTo("/api/v2/observe/events");
            assertThat(request.body()).doesNotContain(PROMPT_TEXT);
            assertThat(request.json()).containsEntry("model", MODEL)
                .containsEntry("callStatus", "SUCCEEDED")
                .containsEntry("tokenSemantics", "INCLUSIVE_V1")
                .doesNotContainKey("totalCostUsd");
            assertThat(request.json().get("promptTokens").toString()).isEqualTo("1000");
            assertThat(client.stats().created()).isEqualTo(1);
        });
    }

    @Test
    void anUnreachableControlPlaneDoesNotFailTheChatCall() throws Exception {
        server = FakeControlPlane.start(FakeControlPlane.deduplicating());

        app("token-pilot.client.endpoint=" + FakeControlPlane.UNREACHABLE,
            "token-pilot.client.max-attempts=1").run(context -> {
            assertThat(ask(context.getBean(ChatClient.Builder.class).build())).isEqualTo("fake answer");
            TokenPilotClient client = context.getBean(TokenPilotClient.class);
            assertThat(client.flush(Duration.ofSeconds(10))).isTrue();
            assertThat(client.stats().droppedTotal()).isEqualTo(1);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomerApp {

        @Bean
        ChatModel fakeChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(Prompt prompt) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("fake answer"))),
                        ChatResponseMetadata.builder().model(MODEL).usage(new DefaultUsage(1_000, 2_000)).build());
                }

                @Override
                public ChatOptions getOptions() {
                    return ToolCallingChatOptions.builder().build();
                }
            };
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

    static final class LedgerProbe implements LedgerListener {

        final List<CostRecordedEvent> records = new CopyOnWriteArrayList<>();

        @Override
        public void onRecord(CostRecordedEvent event) {
            records.add(event);
        }
    }
}
