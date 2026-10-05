package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.providers.LLMAPIProviderThing;
import com.thingworx.things.agent.llm.ratecontrol.RateControlConfig;
import com.thingworx.things.agent.llm.ratecontrol.RateControlMode;
import com.thingworx.things.agent.llm.ratecontrol.RateEstimate;
import com.thingworx.things.agent.llm.usage.LlmCallContext;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;

/**
 * Wraps a built-in {@link LlmClient} so telemetry uses Provider Thing identity (§10.2) and resolves max-output /
 * reasoning once per {@link #chat(LlmChatRequest)} ({@code docs/agent/llm-api-provider-parameters.md} §7).
 */
public final class ProviderLlmClientBridge implements LlmClient {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ProviderLlmClientBridge.class);

    private final ProviderBridgeContext owner;
    private final LlmClient delegate;
    private final String defaultModel;

    public ProviderLlmClientBridge(LLMAPIProviderThing owner, LlmClient delegate, String defaultModel) {
        this((ProviderBridgeContext) owner, delegate, defaultModel);
    }

    /** Accepts test fakes implementing {@link ProviderBridgeContext} without a ThingWorx Provider Thing. */
    public ProviderLlmClientBridge(ProviderBridgeContext owner, LlmClient delegate, String defaultModel) {
        this.owner = owner;
        this.delegate = delegate;
        this.defaultModel = defaultModel != null ? defaultModel : "";
    }

    @Override
    public LlmResponse chat(LlmChatRequest request) throws Exception {
        String effective = (request.getModelOverride() != null && !request.getModelOverride().isBlank())
                ? request.getModelOverride().trim()
                : defaultModel;
        LlmUsageWireIds ids = usageWireIdsForEffectiveModel(effective);
        int resolvedMax = owner.resolveMaxOutputTokens(request);
        Optional<String> resolvedReasoning = owner.resolveReasoningEffort(request);
        LlmChatRequest augmented = LlmChatRequest.copyWithProviderAugmentation(
                request,
                ids,
                resolvedMax,
                resolvedReasoning.orElse(null));
        if (augmented.isProbeMode()) {
            return delegate.chat(augmented);
        }
        LlmCallContext callContext = augmented.getCallContext();
        LlmCallRecorder.LlmCallAttempt attempt = null;
        if (callContext != null) {
            attempt = LlmCallRecorder.ensureAttempt(callContext.withWireIds(ids));
        }
        try {
            RateControlConfig rateConfig = owner.loadRateControlConfig();
            if (rateConfig.mode == RateControlMode.disabled) {
                return delegate.chat(augmented);
            }
            RateEstimate estimate = owner.estimateRateUsage(augmented);
            return owner.getRateGate().execute(augmented, ids, rateConfig, estimate,
                    req -> delegate.chat(req));
        } catch (Exception e) {
            if (attempt != null && attempt.isActive() && !attempt.isFinished()) {
                attempt.finishNotSent();
            }
            throw e;
        }
    }

    @Override
    public LlmUsageWireIds usageWireIds() {
        return baseWireIds(defaultModel);
    }

    @Override
    public LlmUsageWireIds usageWireIdsForEffectiveModel(String effectiveModel) {
        String m = (effectiveModel != null && !effectiveModel.isBlank()) ? effectiveModel.trim() : defaultModel;
        return baseWireIds(m);
    }

    @Override
    public OptionalLong contextPlanningInputCapChars(long requestedMaxOutputTokens, String modelOverride) {
        String effective = (modelOverride != null && !modelOverride.isBlank())
                ? modelOverride.trim()
                : defaultModel;
        int requested = requestedMaxOutputTokens > Integer.MAX_VALUE
                ? Integer.MAX_VALUE
                : (int) requestedMaxOutputTokens;
        LlmChatRequest probe = LlmChatRequest.forAgentRound(
                List.of(), List.of(), 0.0, requested, effective, usageWireIdsForEffectiveModel(effective));
        int resolvedMax = owner.resolveMaxOutputTokens(probe);
        RateControlConfig rateConfig = owner.loadRateControlConfig();
        return rateConfig.contextPlanningInputCapChars(resolvedMax);
    }

    private LlmUsageWireIds baseWireIds(String model) {
        return LlmUsageWireIds.forProviderThing(
                owner.getName(),
                owner.getThingTemplateName(),
                owner.getApiShapeId(),
                model);
    }

    @Override
    public boolean healthCheck() {
        LlmUsageWireIds probeIds = usageWireIdsForEffectiveModel(defaultModel);
        long t0 = System.currentTimeMillis();
        try {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(ChatMessage.user("Reply with exactly: OK"));
            LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(
                    LlmChatRequest.forAgentRound(messages, null, 0.0, 32, null, probeIds),
                    true);
            LlmResponse r = chat(probe);
            boolean ok = r.getContent() != null && r.getContent().trim().toUpperCase().contains("OK");
            long elapsed = System.currentTimeMillis() - t0;
            if (!ok) {
                LOG.warn("Provider healthCheck: unexpected content provider={} content={}", owner.getName(), r.getContent());
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, messages.size(), elapsed, null);
            } else {
                LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, true, messages.size(), elapsed, null);
            }
            return ok;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - t0;
            LlmUsageTelemetry.logProviderTestConnection(LOG, probeIds, false, 0, elapsed, e);
            LOG.error("Provider healthCheck failed provider={}: {}", owner.getName(), e.getMessage(), e);
            return false;
        }
    }
}
