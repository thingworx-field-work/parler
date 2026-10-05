package com.thingworx.things.agent.llm;

import java.util.Collections;
import java.util.List;

public class LlmResponse {

    public enum FinishReason {
        STOP, TOOL_CALLS, LENGTH, ERROR
    }

    private final String content;
    private final List<ToolCall> toolCalls;
    private final FinishReason finishReason;
    /** Legacy aggregate prompt-side tokens (Anthropic: input + cache read + cache create). */
    private final int promptTokens;
    private final int completionTokens;
    /** Provider-reported input tokens (Anthropic: raw {@code usage.input_tokens}; OpenAI/Azure: same as {@link #promptTokens}). */
    private final int inputTokens;
    /** Same as {@link #completionTokens}. */
    private final int outputTokens;
    private final int cacheReadInputTokens;
    private final int cacheCreationInputTokens;
    private final int cachedPromptTokens;
    /** OpenAI/Azure {@code usage.completion_tokens_details.reasoning_tokens} when present; 0 otherwise. */
    private final int reasoningTokens;
    private final String providerRequestId;
    /** Milliseconds waited in the provider rate gate before admission (0 when none or gate disabled). */
    private final long rateGateAdmissionWaitMs;
    /** Optional content-free provider response structure for anomaly diagnostics. */
    private final LlmResponseShapeDiagnostics responseShapeDiagnostics;

    public LlmResponse(String content, List<ToolCall> toolCalls,
            FinishReason finishReason, int promptTokens, int completionTokens) {
        this(content, toolCalls, finishReason, promptTokens, completionTokens,
                promptTokens, completionTokens, 0, 0, 0, null, 0L);
    }

    /**
     * Full constructor for Phase 1 telemetry. See {@code docs/agent/llm-token-budget.md} for field semantics.
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason,
            int promptTokens, int completionTokens,
            int inputTokens, int outputTokens,
            int cacheReadInputTokens, int cacheCreationInputTokens, int cachedPromptTokens,
            String providerRequestId) {
        this(content, toolCalls, finishReason, promptTokens, completionTokens,
                inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, cachedPromptTokens,
                providerRequestId, 0, 0L);
    }

    /**
     * Full constructor including rate-gate admission wait.
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason,
            int promptTokens, int completionTokens,
            int inputTokens, int outputTokens,
            int cacheReadInputTokens, int cacheCreationInputTokens, int cachedPromptTokens,
            String providerRequestId,
            long rateGateAdmissionWaitMs) {
        this(content, toolCalls, finishReason, promptTokens, completionTokens,
                inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, cachedPromptTokens,
                providerRequestId, 0, rateGateAdmissionWaitMs);
    }

    /**
     * Full constructor including optional reasoning-token subset and rate-gate admission wait.
     */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason,
            int promptTokens, int completionTokens,
            int inputTokens, int outputTokens,
            int cacheReadInputTokens, int cacheCreationInputTokens, int cachedPromptTokens,
            String providerRequestId, int reasoningTokens,
            long rateGateAdmissionWaitMs) {
        this(content, toolCalls, finishReason, promptTokens, completionTokens,
                inputTokens, outputTokens, cacheReadInputTokens, cacheCreationInputTokens, cachedPromptTokens,
                providerRequestId, reasoningTokens, rateGateAdmissionWaitMs, null);
    }

    /** Full constructor including optional content-free provider response shape diagnostics. */
    public LlmResponse(String content, List<ToolCall> toolCalls, FinishReason finishReason,
            int promptTokens, int completionTokens,
            int inputTokens, int outputTokens,
            int cacheReadInputTokens, int cacheCreationInputTokens, int cachedPromptTokens,
            String providerRequestId, int reasoningTokens,
            long rateGateAdmissionWaitMs, LlmResponseShapeDiagnostics responseShapeDiagnostics) {
        this.content = content;
        this.toolCalls = toolCalls != null ? toolCalls : Collections.emptyList();
        this.finishReason = finishReason;
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.cacheReadInputTokens = cacheReadInputTokens;
        this.cacheCreationInputTokens = cacheCreationInputTokens;
        this.cachedPromptTokens = cachedPromptTokens;
        this.reasoningTokens = Math.max(0, reasoningTokens);
        this.providerRequestId = providerRequestId;
        this.rateGateAdmissionWaitMs = Math.max(0L, rateGateAdmissionWaitMs);
        this.responseShapeDiagnostics = responseShapeDiagnostics;
    }

    /**
     * Returns a copy of {@code base} with {@link #getRateGateAdmissionWaitMs()} set to {@code rateGateAdmissionWaitMs}.
     */
    public static LlmResponse withRateGateAdmissionWait(LlmResponse base, long rateGateAdmissionWaitMs) {
        if (base == null) {
            return null;
        }
        long w = Math.max(0L, rateGateAdmissionWaitMs);
        if (w == base.rateGateAdmissionWaitMs) {
            return base;
        }
        return new LlmResponse(base.content, base.toolCalls, base.finishReason,
                base.promptTokens, base.completionTokens,
                base.inputTokens, base.outputTokens,
                base.cacheReadInputTokens, base.cacheCreationInputTokens, base.cachedPromptTokens,
                base.providerRequestId, base.reasoningTokens, w, base.responseShapeDiagnostics);
    }

    public String getContent() { return content; }
    public List<ToolCall> getToolCalls() { return toolCalls; }
    public boolean hasToolCalls() { return !toolCalls.isEmpty(); }
    public FinishReason getFinishReason() { return finishReason; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getInputTokens() { return inputTokens; }
    public int getOutputTokens() { return outputTokens; }
    public int getCacheReadInputTokens() { return cacheReadInputTokens; }
    public int getCacheCreationInputTokens() { return cacheCreationInputTokens; }
    public int getCachedPromptTokens() { return cachedPromptTokens; }
    public int getReasoningTokens() { return reasoningTokens; }
    public String getProviderRequestId() { return providerRequestId; }
    public LlmResponseShapeDiagnostics getResponseShapeDiagnostics() { return responseShapeDiagnostics; }

    public long getRateGateAdmissionWaitMs() {
        return rateGateAdmissionWaitMs;
    }

    /** Sum of prompt + completion; cache breakdown fields are not added again. */
    public int getTotalTokens() { return promptTokens + completionTokens; }
}
