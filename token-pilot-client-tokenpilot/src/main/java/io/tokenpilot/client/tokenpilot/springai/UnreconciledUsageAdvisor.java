package io.tokenpilot.client.tokenpilot.springai;

import java.lang.System.Logger.Level;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.core.Ordered;

import io.tokenpilot.client.tokenpilot.TokenPilotLedgerForwarder;
import io.tokenpilot.core.domain.CostRecordedEvent;
import io.tokenpilot.core.domain.PricingReconciliationResult;
import io.tokenpilot.springai.UsageExtractor;

/**
 * Forwards the {@code ChatClient} calls Token Pilot answers but leaves out of its ledger.
 *
 * <p>Token Pilot's {@code LedgerAdvisor} only records a call when its pricing is {@code RECONCILED}; a model
 * without a price ({@code UNPRICED}) or a response model that differs from the priced one
 * ({@code RECONCILIATION_REQUIRED}) produces no ledger record, so {@link TokenPilotLedgerForwarder} never sees it.
 * Those are exactly the calls whose cost is unknown, so they are sent here with their tokens and no cost. A
 * {@code RECONCILED} call is left to the ledger listener, so no call is sent twice.
 *
 * <p>Runs just outside the {@code LedgerAdvisor} to read the reconciliation result it puts in the response
 * context. Calls through Token Pilot's budget path carry no such result and are not forwarded.
 */
public final class UnreconciledUsageAdvisor implements CallAdvisor {

    /** Token Pilot's {@code LedgerAdvisor} runs at {@code LOWEST_PRECEDENCE - 1}. */
    public static final int ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    /** Response context key the {@code LedgerAdvisor} fills with a {@link PricingReconciliationResult}. */
    static final String RECONCILIATION_RESULT_CONTEXT = "tokenpilot.pricing.reconciliation.result";

    private static final System.Logger LOG = System.getLogger(UnreconciledUsageAdvisor.class.getName());

    private final TokenPilotLedgerForwarder forwarder;
    private final UsageExtractor usageExtractor;

    public UnreconciledUsageAdvisor(TokenPilotLedgerForwarder forwarder, UsageExtractor usageExtractor) {
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder must not be null");
        this.usageExtractor = Objects.requireNonNull(usageExtractor, "usageExtractor must not be null");
    }

    @Override
    public String getName() {
        return "UnreconciledUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        try {
            if (response.context().get(RECONCILIATION_RESULT_CONTEXT) instanceof PricingReconciliationResult result
                && result != PricingReconciliationResult.RECONCILED) {
                String model = model(request, response);
                if (model == null) {
                    LOG.log(Level.WARNING, "Unreconciled Token Pilot call without a model id not sent");
                } else {
                    forwarder.forwardUnreconciled(
                        new CostRecordedEvent(model, usageExtractor.extract(response), null, tags(response)), result);
                }
            }
        } catch (RuntimeException exception) {
            // Observe must never fail the call it observes.
            LOG.log(Level.WARNING, "Unreconciled Token Pilot call not sent: {0}", exception.getClass().getSimpleName());
        }
        return response;
    }

    /** What the provider says it ran, else what was asked for; the server prices by this model. */
    private static String model(ChatClientRequest request, ChatClientResponse response) {
        ChatResponse chatResponse = response.chatResponse();
        if (chatResponse != null && chatResponse.getMetadata() != null) {
            String model = chatResponse.getMetadata().getModel();
            if (model != null && !model.isBlank()) {
                return model;
            }
        }
        ChatOptions options = request.prompt().getOptions();
        String requested = options == null ? null : options.getModel();
        return requested == null || requested.isBlank() ? null : requested;
    }

    /** Same view as Token Pilot's ledger tags: the String values of the call context. */
    private static Map<String, String> tags(ChatClientResponse response) {
        Map<String, String> tags = new LinkedHashMap<>();
        response.context().forEach((key, value) -> {
            if (value instanceof String text) {
                tags.put(key, text);
            }
        });
        return tags;
    }
}
