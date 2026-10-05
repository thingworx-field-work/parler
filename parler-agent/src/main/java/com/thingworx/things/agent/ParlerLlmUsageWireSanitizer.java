package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * Whitelist compact LLM usage JSON for AlwaysOn {@code done} frames and {@code ai-parler-history-v1} assistant rows.
 * Drops unknown keys and non-scalar values so wire/history payloads cannot carry prompt bodies or nested blobs.
 */
public final class ParlerLlmUsageWireSanitizer {

    private static final int MAX_STRING_LEN = 256;

    private static final String[] STRING_KEYS = {
            "providerThingName", "providerTemplateName", "apiShapeId", "model", "requestId", "parlerRequestId",
            "firstToolCallCacheHit", "toolProtocolViolation",
    };

    private static final String[] NUMBER_KEYS = {
            "inputTokens", "outputTokens", "promptTokens", "completionTokens",
            "cacheReadInputTokens", "cacheCreationInputTokens", "cachedPromptTokens", "reasoningTokens",
            "rateWaitMs",
            "turnWallMs", "agentIterations", "toolCallCount", "llmWallMs", "toolWallMs",
            "promptTokensTotal", "completionTokensTotal", "reasoningTokensTotal",
            "requestedMaxOutputTokensTotal",
            "finalAnswerRoundIndex", "fetchAfterCompleteAnswerSetCount", "roundsHitMaxOutput",
            "toolExecutionMaxConcurrency", "multiToolCallRoundsCount", "repetitionBlockedCount",
            "parlerChartWireEmittedCount", "presentationActionsRequested", "presentationActionsExecuted",
            "presentationActionsBlocked",
    };

    private static final String[] BOOLEAN_KEYS = {
            "markerEmitterEnabled", "noToolFinalAnswerApplied", "chartExpectedButMissing",
            "chartRescueAttempted",
            "presentationPhaseEntered",
    };

    private ParlerLlmUsageWireSanitizer() {}

    /**
     * @param llmUsageJson raw JSON string from {@link StreamTokenUsage#getLlmUsageJson()} (may be empty)
     * @return sanitized object or {@code null} when nothing safe remains
     */
    public static JSONObject sanitizeLlmUsageJson(String llmUsageJson) {
        if (llmUsageJson == null || llmUsageJson.trim().isEmpty()) {
            return null;
        }
        try {
            JSONObject src = new JSONObject(llmUsageJson);
            JSONObject out = new JSONObject();
            for (String k : STRING_KEYS) {
                if (!src.has(k) || src.isNull(k)) {
                    continue;
                }
                String s = String.valueOf(src.get(k));
                if (s.length() > MAX_STRING_LEN) {
                    s = s.substring(0, MAX_STRING_LEN);
                }
                out.put(k, s);
            }
            for (String k : NUMBER_KEYS) {
                if (!src.has(k) || src.isNull(k)) {
                    continue;
                }
                Object v = src.get(k);
                if (v instanceof Number) {
                    long n = ((Number) v).longValue();
                    if (n > Integer.MAX_VALUE) {
                        n = Integer.MAX_VALUE;
                    }
                    if (n < Integer.MIN_VALUE) {
                        n = Integer.MIN_VALUE;
                    }
                    out.put(k, (int) n);
                }
            }
            for (String k : BOOLEAN_KEYS) {
                if (!src.has(k) || src.isNull(k)) {
                    continue;
                }
                Object v = src.get(k);
                if (v instanceof Boolean) {
                    out.put(k, (Boolean) v);
                }
            }
            return out.length() > 0 ? out : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
