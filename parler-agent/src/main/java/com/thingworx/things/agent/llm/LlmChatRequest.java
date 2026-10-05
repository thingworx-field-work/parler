package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.llm.ratecontrol.RateControlStatusSink;
import com.thingworx.things.agent.llm.usage.LlmCallContext;

/**
 * Per-round LLM request payload ({@code docs/agent/llm-api-provider-parameters.md} §6).
 * Immutable value object; assembled by {@link com.thingworx.things.agent.AgentThing} / {@link com.thingworx.things.agent.AgentLoop}.
 * {@link RateControlStatusSink} is intentionally excluded from {@link #equals(Object)} / {@link #hashCode()}.
 */
public final class LlmChatRequest {

    private final List<ChatMessage> messages;
    private final List<ToolDefinition> tools;
    private final double temperature;
    private final long requestedMaxOutputTokens;
    private final boolean enableCacheControl;
    private final String reasoningEffort;
    /**
     * Optional per-round model override. {@link com.thingworx.things.agent.AgentThing} passes {@code null}
     * (model/deployment is owned by the Provider Thing).
     */
    private final String modelOverride;
    /**
     * When non-null, HTTP paths use this for {@code LLM_HTTP_FAILURE} / {@code LLM_RATE_LIMIT} / probe lines
     * ({@link ProviderLlmClientBridge}).
     */
    private final LlmUsageWireIds usageWireIdsOverride;
    /**
     * When true, max-output and reasoning on this request were resolved by the Provider bridge; delegate clients
     * must not apply another {@code <= 0} fallback.
     */
    private final boolean providerResolvedOptions;
    /**
     * Optional live UI downlink for provider rate-control waits ({@code docs/agent/rate-control-ui-status.md}).
     */
    private final RateControlStatusSink rateControlStatusSink;
    /**
     * Diagnostic probe ({@code TestConnection} / {@code healthCheck}). Production callers leave {@code false}.
     * Delegate clients may make conservative wire choices (e.g. Anthropic omits extended thinking on probes).
     */
    private final boolean probeMode;
    /**
     * When {@code true} and this round's tool list is non-empty, provider clients emit {@code tool_choice} none
     * together with the {@code tools} array. When the tool list is empty (post-marker), clients omit both
     * {@code tools} and {@code tool_choice} — the model cannot call tools that are not offered.
     */
    private final boolean toolChoiceNone;
    /** Non-wire call ledger context (CC-7 M0a). */
    private final LlmCallContext callContext;

    public LlmChatRequest(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            long requestedMaxOutputTokens,
            boolean enableCacheControl,
            String reasoningEffort,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride,
            boolean providerResolvedOptions) {
        this(messages, tools, temperature, requestedMaxOutputTokens, enableCacheControl, reasoningEffort,
                modelOverride, usageWireIdsOverride, providerResolvedOptions, null, false, false, null);
    }

    public LlmChatRequest(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            long requestedMaxOutputTokens,
            boolean enableCacheControl,
            String reasoningEffort,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride,
            boolean providerResolvedOptions,
            boolean probeMode) {
        this(messages, tools, temperature, requestedMaxOutputTokens, enableCacheControl, reasoningEffort,
                modelOverride, usageWireIdsOverride, providerResolvedOptions, null, probeMode, false, null);
    }

    public LlmChatRequest(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            long requestedMaxOutputTokens,
            boolean enableCacheControl,
            String reasoningEffort,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride,
            boolean providerResolvedOptions,
            RateControlStatusSink rateControlStatusSink,
            boolean probeMode) {
        this(messages, tools, temperature, requestedMaxOutputTokens, enableCacheControl, reasoningEffort,
                modelOverride, usageWireIdsOverride, providerResolvedOptions, rateControlStatusSink, probeMode,
                false, null);
    }

    private LlmChatRequest(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            long requestedMaxOutputTokens,
            boolean enableCacheControl,
            String reasoningEffort,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride,
            boolean providerResolvedOptions,
            RateControlStatusSink rateControlStatusSink,
            boolean probeMode,
            boolean toolChoiceNone,
            LlmCallContext callContext) {
        if (messages != null && !messages.isEmpty()) {
            this.messages = Collections.unmodifiableList(new ArrayList<>(messages));
        } else {
            this.messages = Collections.emptyList();
        }
        if (tools != null && !tools.isEmpty()) {
            this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
        } else {
            this.tools = Collections.emptyList();
        }
        this.temperature = temperature;
        this.requestedMaxOutputTokens = requestedMaxOutputTokens;
        this.enableCacheControl = enableCacheControl;
        this.reasoningEffort = reasoningEffort;
        this.modelOverride = modelOverride;
        this.usageWireIdsOverride = usageWireIdsOverride;
        this.providerResolvedOptions = providerResolvedOptions;
        this.rateControlStatusSink = rateControlStatusSink;
        this.probeMode = probeMode;
        this.toolChoiceNone = toolChoiceNone;
        this.callContext = callContext;
    }

    /**
     * Copies {@code base} with Provider wire identity and resolved max-output / reasoning for one delegate call.
     */
    public static LlmChatRequest copyWithProviderAugmentation(
            LlmChatRequest base,
            LlmUsageWireIds usageWireIdsOverride,
            int resolvedMaxOutputTokens,
            String resolvedReasoningEffort) {
        return new LlmChatRequest(
                base.getMessages(),
                base.getTools(),
                base.getTemperature(),
                resolvedMaxOutputTokens,
                base.isEnableCacheControl(),
                resolvedReasoningEffort,
                base.getModelOverride(),
                usageWireIdsOverride,
                true,
                base.getRateControlStatusSink(),
                base.isProbeMode(),
                base.isToolChoiceNone(),
                base.getCallContext());
    }

    public static LlmChatRequest copyWithCallContext(LlmChatRequest base, LlmCallContext callContext) {
        return new LlmChatRequest(
                base.getMessages(),
                base.getTools(),
                base.getTemperature(),
                base.getRequestedMaxOutputTokens(),
                base.isEnableCacheControl(),
                base.getReasoningEffort(),
                base.getModelOverride(),
                base.getUsageWireIdsOverride(),
                base.isProviderResolvedOptions(),
                base.getRateControlStatusSink(),
                base.isProbeMode(),
                base.isToolChoiceNone(),
                callContext);
    }

    public static LlmChatRequest copyWithProbeMode(LlmChatRequest base, boolean probeMode) {
        return new LlmChatRequest(
                base.getMessages(),
                base.getTools(),
                base.getTemperature(),
                base.getRequestedMaxOutputTokens(),
                base.isEnableCacheControl(),
                base.getReasoningEffort(),
                base.getModelOverride(),
                base.getUsageWireIdsOverride(),
                base.isProviderResolvedOptions(),
                base.getRateControlStatusSink(),
                probeMode,
                base.isToolChoiceNone(),
                base.getCallContext());
    }

    /** Copies {@code base} while changing only the Anthropic agent-round cache-control request kind. */
    public static LlmChatRequest copyWithCacheControl(LlmChatRequest base, boolean enableCacheControl) {
        return new LlmChatRequest(
                base.getMessages(),
                base.getTools(),
                base.getTemperature(),
                base.getRequestedMaxOutputTokens(),
                enableCacheControl,
                base.getReasoningEffort(),
                base.getModelOverride(),
                base.getUsageWireIdsOverride(),
                base.isProviderResolvedOptions(),
                base.getRateControlStatusSink(),
                base.isProbeMode(),
                base.isToolChoiceNone(),
                base.getCallContext());
    }

    /**
     * Copies {@code base} with per-round tool policy (post-marker: {@code toolChoiceNone=true} with an empty
     * {@code tools} list; provider bodies omit tool fields — see {@code ChatCompletionsApi} /
     * {@code AnthropicMessagesApi}).
     */
    public static LlmChatRequest copyWithToolPolicy(LlmChatRequest base, boolean toolChoiceNone) {
        return new LlmChatRequest(
                base.getMessages(),
                base.getTools(),
                base.getTemperature(),
                base.getRequestedMaxOutputTokens(),
                base.isEnableCacheControl(),
                base.getReasoningEffort(),
                base.getModelOverride(),
                base.getUsageWireIdsOverride(),
                base.isProviderResolvedOptions(),
                base.getRateControlStatusSink(),
                base.isProbeMode(),
                toolChoiceNone,
                base.getCallContext());
    }

    public boolean isProviderResolvedOptions() {
        return providerResolvedOptions;
    }

    public boolean isProbeMode() {
        return probeMode;
    }

    public boolean isToolChoiceNone() {
        return toolChoiceNone;
    }

    public RateControlStatusSink getRateControlStatusSink() {
        return rateControlStatusSink;
    }

    public String getModelOverride() {
        return modelOverride;
    }

    public LlmUsageWireIds getUsageWireIdsOverride() {
        return usageWireIdsOverride;
    }

    public LlmCallContext getCallContext() {
        return callContext;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }

    public List<ToolDefinition> getTools() {
        return tools;
    }

    public double getTemperature() {
        return temperature;
    }

    public long getRequestedMaxOutputTokens() {
        return requestedMaxOutputTokens;
    }

    public boolean isEnableCacheControl() {
        return enableCacheControl;
    }

    public String getReasoningEffort() {
        return reasoningEffort;
    }

    public static LlmChatRequest forAgentRound(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            int maxTokensFromAgentSettings,
            String modelOverride) {
        return forAgentRound(messages, tools, temperature, maxTokensFromAgentSettings, modelOverride, null);
    }

    public static LlmChatRequest forAgentRound(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            int maxTokensFromAgentSettings,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride) {
        return forAgentRound(messages, tools, temperature, maxTokensFromAgentSettings, modelOverride,
                usageWireIdsOverride, null);
    }

    public static LlmChatRequest forAgentRound(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            double temperature,
            int maxTokensFromAgentSettings,
            String modelOverride,
            LlmUsageWireIds usageWireIdsOverride,
            RateControlStatusSink rateControlStatusSink) {
        return new LlmChatRequest(
                messages,
                tools,
                temperature,
                maxTokensFromAgentSettings,
                false,
                null,
                modelOverride,
                usageWireIdsOverride,
                false,
                rateControlStatusSink,
                false,
                false,
                null);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof LlmChatRequest)) {
            return false;
        }
        LlmChatRequest that = (LlmChatRequest) o;
        return Double.compare(that.temperature, temperature) == 0
                && requestedMaxOutputTokens == that.requestedMaxOutputTokens
                && enableCacheControl == that.enableCacheControl
                && providerResolvedOptions == that.providerResolvedOptions
                && probeMode == that.probeMode
                && toolChoiceNone == that.toolChoiceNone
                && Objects.equals(messages, that.messages)
                && Objects.equals(tools, that.tools)
                && Objects.equals(reasoningEffort, that.reasoningEffort)
                && Objects.equals(modelOverride, that.modelOverride)
                && Objects.equals(usageWireIdsOverride, that.usageWireIdsOverride);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messages, tools, temperature, requestedMaxOutputTokens, enableCacheControl, reasoningEffort,
                modelOverride, usageWireIdsOverride, providerResolvedOptions, probeMode, toolChoiceNone);
    }
}
