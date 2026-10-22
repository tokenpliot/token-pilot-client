package io.tokenpilot.client.tokenpilot.springai;

import java.lang.System.Logger.Level;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import io.tokenpilot.client.enforce.ActualUsage;
import io.tokenpilot.client.enforce.CallRequest;
import io.tokenpilot.client.enforce.ControlPlaneEnforcer;
import io.tokenpilot.client.enforce.EnforcedResult;
import io.tokenpilot.client.enforce.EnforcementException;
import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.core.CostCalculator;
import io.tokenpilot.core.PricingRegistry;
import io.tokenpilot.core.TokenEstimator;
import io.tokenpilot.core.domain.PricingPlan;
import io.tokenpilot.core.domain.PricingSnapshot;
import io.tokenpilot.core.domain.TokenCountResult;
import io.tokenpilot.core.domain.TokenUsage;
import io.tokenpilot.core.domain.TokenUsageDetails;
import io.tokenpilot.core.domain.UsageSource;
import io.tokenpilot.springai.UsageExtractor;

/**
 * Asks the Control Plane before every {@code ChatClient} call and applies the customer's enforcement settings
 * through a {@link ControlPlaneEnforcer}.
 *
 * <p>Runs just outside Token Pilot's {@code LedgerAdvisor} ({@link #ORDER}), so a blocked call reaches neither
 * Token Pilot nor the model. A blocked call throws {@link EnforcementException}; every other call returns the
 * model's response with the decision in the response context ({@link #OUTCOME_CONTEXT_KEY} and friends), so the
 * application can show an advisory without changing its call code.
 *
 * <p>Only counts leave the process: the prompt text is used locally to estimate input tokens and is never sent.
 * Actual usage comes from Token Pilot's {@link UsageExtractor}; the cost from Token Pilot's own pricing catalog.
 * Without a USD price for the response model, the reservation is settled as reconciliation-required with the
 * token counts instead of committing a guessed cost.
 *
 * <p>Call-site parameters ({@code .advisors(a -> a.param(..))}): {@link #REQUEST_ID_PARAM} (reuse it when retrying
 * the same command so the Control Plane answers with the same decision and reservation) and
 * {@link #ENDPOINT_PARAM} (selects per-endpoint enforcement settings).
 */
public final class ControlPlaneEnforcementAdvisor implements CallAdvisor {

    /** Token Pilot's {@code LedgerAdvisor} runs at {@code LOWEST_PRECEDENCE - 1}; this runs right outside it. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    public static final String REQUEST_ID_PARAM = TokenPilotLedgerForwarder.REQUEST_ID_TAG;
    public static final String ENDPOINT_PARAM = "tokenpilot.enforcement.endpoint";
    /** Token Pilot's own model override; used when the prompt options carry no model. */
    public static final String MODEL_ID_PARAM = "tokenpilot.model.id";

    public static final String OUTCOME_CONTEXT_KEY = "tokenpilot.enforcement.outcome";
    public static final String ACTION_CONTEXT_KEY = "tokenpilot.enforcement.action";
    public static final String DECISION_ID_CONTEXT_KEY = "tokenpilot.enforcement.decision-id";

    /** Sent when no model can be resolved; the Control Plane answers INDETERMINATE (nothing to price). */
    static final String UNRESOLVED_MODEL = "unresolved";

    private static final System.Logger LOG = System.getLogger(ControlPlaneEnforcementAdvisor.class.getName());
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z0-9._:-]{1,128}$");
    private static final Currency USD = Currency.getInstance("USD");

    private final ControlPlaneEnforcer enforcer;
    private final String provider;
    private final String defaultModel;
    private final long defaultMaxOutputTokens;
    private final UsageExtractor usageExtractor;
    private final TokenEstimator tokenEstimator;
    private final PricingRegistry pricingRegistry;
    private final CostCalculator costCalculator;

    private ControlPlaneEnforcementAdvisor(Builder builder) {
        this.enforcer = Objects.requireNonNull(builder.enforcer, "enforcer must not be null");
        this.provider = requireIdentifier("provider", builder.provider);
        this.defaultModel = builder.defaultModel == null ? null : requireIdentifier("defaultModel", builder.defaultModel);
        if (builder.defaultMaxOutputTokens < 0) {
            throw new IllegalArgumentException("defaultMaxOutputTokens must not be negative");
        }
        this.defaultMaxOutputTokens = builder.defaultMaxOutputTokens;
        this.usageExtractor = Objects.requireNonNull(builder.usageExtractor, "usageExtractor must not be null");
        this.tokenEstimator = builder.tokenEstimator;
        this.pricingRegistry = builder.pricingRegistry;
        this.costCalculator = builder.costCalculator;
    }

    public static Builder builder(ControlPlaneEnforcer enforcer, UsageExtractor usageExtractor) {
        return new Builder(enforcer, usageExtractor);
    }

    @Override
    public String getName() {
        return "ControlPlaneEnforcementAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        EnforcedResult<ChatClientResponse> result;
        try {
            result = enforcer.execute(callRequest(request), () -> chain.nextCall(request), this::actualUsage);
        } catch (RuntimeException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the Control Plane decision", exception);
        } catch (Exception exception) {
            // chain.nextCall throws only unchecked exceptions; anything else is unexpected.
            throw new IllegalStateException(exception);
        }

        ChatClientResponse.Builder response = result.response().mutate()
            .context(OUTCOME_CONTEXT_KEY, result.decision().outcome().name())
            .context(ACTION_CONTEXT_KEY, result.action().name());
        if (result.decision().decisionId() != null) {
            response.context(DECISION_ID_CONTEXT_KEY, result.decision().decisionId());
        }
        return response.build();
    }

    CallRequest callRequest(ChatClientRequest request) {
        Map<String, Object> context = request.context();
        Prompt prompt = request.prompt();
        ChatOptions options = prompt.getOptions();
        Integer maxTokens = options == null ? null : options.getMaxTokens();

        return CallRequest.builder()
            .requestId(requestId(context.get(REQUEST_ID_PARAM)))
            .endpoint(context.get(ENDPOINT_PARAM) instanceof String endpoint ? endpoint : null)
            .provider(provider)
            .model(model(options, context))
            .estimatedInputTokens(estimateInputTokens(prompt.getContents()))
            .maxOutputTokens(maxTokens != null && maxTokens >= 0 ? maxTokens : defaultMaxOutputTokens)
            .build();
    }

    /**
     * The application's request id when it is a valid contract identifier; otherwise a fresh one. A fresh id
     * still works but loses idempotency across the application's own retries, so it is logged.
     */
    private static String requestId(Object value) {
        if (value instanceof String id && IDENTIFIER.matcher(id).matches()) {
            return id;
        }
        if (value != null) {
            LOG.log(Level.WARNING, "{0} is not a valid Control Plane identifier; using a generated request id",
                REQUEST_ID_PARAM);
        }
        return UUID.randomUUID().toString();
    }

    private String model(ChatOptions options, Map<String, Object> context) {
        String model = options == null ? null : options.getModel();
        if (model == null && context.get(MODEL_ID_PARAM) instanceof String override) {
            model = override;
        }
        if (model == null) {
            model = defaultModel;
        }
        return model != null && IDENTIFIER.matcher(model).matches() ? model : UNRESOLVED_MODEL;
    }

    /** Upper bound, so the reservation is not too small; the UTF-16 length bounds any tokenizer's count. */
    private long estimateInputTokens(String text) {
        String content = text == null ? "" : text;
        if (tokenEstimator != null) {
            try {
                TokenCountResult result = tokenEstimator.estimate(content);
                if (result.isCounted()) {
                    return result.safeUpperBoundTokens().orElse(result.tokens().orElse(content.length()));
                }
            } catch (RuntimeException exception) {
                LOG.log(Level.WARNING, "TokenEstimator failed ({0}); using the text length",
                    exception.getClass().getSimpleName());
            }
        }
        return content.length();
    }

    ActualUsage actualUsage(ChatClientResponse response) {
        TokenUsage usage = usageExtractor.extract(response);
        if (usage == null || usage.source() == UsageSource.UNAVAILABLE) {
            return null;
        }
        TokenUsageDetails details = usage.details();
        Long cached = details.cacheReadInputTokens();
        Long reasoning = details.reasoningOutputTokens();

        Optional<PricingSnapshot> snapshot = snapshot(response);
        if (snapshot.isEmpty() || !USD.equals(snapshot.get().currency())) {
            return new ActualUsage(usage.inputTokens(), usage.outputTokens(), cached, reasoning, null, null, null);
        }
        // The contract wants prompt and completion costs separately: price the input side alone, the rest is output.
        BigDecimal total = costCalculator.calculate(usage, snapshot.get()).value();
        TokenUsage inputOnly = new TokenUsage(usage.inputTokens(), 0,
            new TokenUsageDetails(cached, details.cacheCreationInputTokens(), null), usage.source(), Map.of());
        BigDecimal promptCost = usd(costCalculator.calculate(inputOnly, snapshot.get()).value());
        BigDecimal completionCost = usd(total).subtract(promptCost).max(BigDecimal.ZERO);
        return new ActualUsage(usage.inputTokens(), usage.outputTokens(), cached, reasoning,
            promptCost, completionCost, promptCost.add(completionCost));
    }

    private Optional<PricingSnapshot> snapshot(ChatClientResponse response) {
        if (pricingRegistry == null || costCalculator == null) {
            return Optional.empty();
        }
        ChatResponse chatResponse = response.chatResponse();
        String model = chatResponse == null || chatResponse.getMetadata() == null
            ? null
            : chatResponse.getMetadata().getModel();
        if (model == null || model.isBlank()) {
            return Optional.empty();
        }
        return pricingRegistry.resolveSnapshot(model, PricingPlan.DEFAULT_PRICING_POLICY_ID);
    }

    private static BigDecimal usd(BigDecimal value) {
        return value.setScale(6, RoundingMode.UP);
    }

    private static String requireIdentifier(String name, String value) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must match " + IDENTIFIER.pattern());
        }
        return value;
    }

    public static final class Builder {

        private final ControlPlaneEnforcer enforcer;
        private final UsageExtractor usageExtractor;
        private String provider;
        private String defaultModel;
        private long defaultMaxOutputTokens = 1024;
        private TokenEstimator tokenEstimator;
        private PricingRegistry pricingRegistry;
        private CostCalculator costCalculator;

        private Builder(ControlPlaneEnforcer enforcer, UsageExtractor usageExtractor) {
            this.enforcer = enforcer;
            this.usageExtractor = usageExtractor;
        }

        /** LLM provider name, e.g. {@code openai}. */
        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        /** Model to report when neither the prompt options nor {@link #MODEL_ID_PARAM} name one. */
        public Builder defaultModel(String defaultModel) {
            this.defaultModel = defaultModel;
            return this;
        }

        /** Output reservation when the prompt options set no {@code maxTokens}. */
        public Builder defaultMaxOutputTokens(long defaultMaxOutputTokens) {
            this.defaultMaxOutputTokens = defaultMaxOutputTokens;
            return this;
        }

        /** Token Pilot's estimator; without it the prompt's character count is used as the upper bound. */
        public Builder tokenEstimator(TokenEstimator tokenEstimator) {
            this.tokenEstimator = tokenEstimator;
            return this;
        }

        /** Token Pilot's catalog and calculator; without both, reservations are never committed with a cost. */
        public Builder pricing(PricingRegistry pricingRegistry, CostCalculator costCalculator) {
            this.pricingRegistry = pricingRegistry;
            this.costCalculator = costCalculator;
            return this;
        }

        public ControlPlaneEnforcementAdvisor build() {
            return new ControlPlaneEnforcementAdvisor(this);
        }
    }
}
