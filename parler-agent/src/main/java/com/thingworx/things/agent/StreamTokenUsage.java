package com.thingworx.things.agent;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.tools.AgentToolContext;

/**
 * Token use for one LLM completion round, carried into {@link AgentMessageStreamAppender} for assistant/tool rows.
 * Includes optional compact {@code llmUsageJson} for assistant rows (empty for user/tool / legacy two-int constructor).
 */
public final class StreamTokenUsage {

    public static final StreamTokenUsage ZERO = new StreamTokenUsage(0, 0, "");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final int promptTokens;
    private final int completionTokens;
    /** Compact provider usage JSON; empty string when not persisted (user/tool rows or legacy ctor). */
    private final String llmUsageJson;

    public StreamTokenUsage(int promptTokens, int completionTokens) {
        this(Math.max(0, promptTokens), Math.max(0, completionTokens), "");
    }

    private StreamTokenUsage(int promptTokens, int completionTokens, String llmUsageJson) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        this.llmUsageJson = llmUsageJson != null ? llmUsageJson : "";
    }

    /**
     * Builds usage payload for one {@link LlmResponse} (assistant stream rows). Never includes prompts or tool bodies.
     */
    public static StreamTokenUsage fromLlmResponse(LlmResponse response, LlmUsageWireIds ids) {
        if (response == null) {
            return ZERO;
        }
        int pt = Math.max(0, response.getPromptTokens());
        int ct = Math.max(0, response.getCompletionTokens());
        String json = buildUsageJson(response, ids);
        return new StreamTokenUsage(pt, ct, json);
    }

    /**
     * Merges per-turn diagnostics into {@code base}'s compact usage JSON.
     *
     * @param rateWaitMs provider rate-gate wait for this turn (ms); omitted from JSON when {@code <= 0}
     * @param firstToolCallCacheHit {@code "true"}, {@code "false"}, {@code "unknown"}, or {@code null} to skip
     */
    public static StreamTokenUsage withParlerTurnDiagnostics(StreamTokenUsage base, long rateWaitMs,
            String firstToolCallCacheHit) {
        if (base == null) {
            return ZERO;
        }
        String json = mergeTurnDiagnosticsIntoUsageJson(base.getLlmUsageJson(), rateWaitMs, firstToolCallCacheHit);
        return new StreamTokenUsage(base.getPromptTokens(), base.getCompletionTokens(), json);
    }

    private static String mergeTurnDiagnosticsIntoUsageJson(String baseJson, long rateWaitMs,
            String firstToolCallCacheHit) {
        try {
            ObjectNode o = baseJson != null && !baseJson.isBlank()
                    ? (ObjectNode) JSON.readTree(baseJson)
                    : JSON.createObjectNode();
            if (rateWaitMs > 0) {
                o.put("rateWaitMs", (int) Math.min(rateWaitMs, Integer.MAX_VALUE));
            }
            if (firstToolCallCacheHit != null && !firstToolCallCacheHit.isBlank()) {
                o.put("firstToolCallCacheHit", firstToolCallCacheHit);
            }
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return baseJson != null ? baseJson : "";
        }
    }

    /**
     * Sums prompt/completion totals and merges compact {@code llmUsageJson} for multi-step playbook-internal LLM calls.
     * String metadata in JSON uses the <b>latest</b> non-empty value from {@code b}.
     */
    /**
     * Shallow-merges {@code overlayJson} object keys into {@code base}'s compact usage JSON (turn-level performance
     * subset from {@link com.thingworx.things.agent.AgentLoop}).
     */
    public static StreamTokenUsage withPerformanceWireOverlay(StreamTokenUsage base, String overlayJson) {
        if (base == null || overlayJson == null || overlayJson.isBlank()) {
            return base != null ? base : ZERO;
        }
        try {
            ObjectNode root = base.getLlmUsageJson() != null && !base.getLlmUsageJson().isBlank()
                    ? (ObjectNode) JSON.readTree(base.getLlmUsageJson())
                    : JSON.createObjectNode();
            ObjectNode over = (ObjectNode) JSON.readTree(overlayJson);
            java.util.Iterator<String> it = over.fieldNames();
            while (it.hasNext()) {
                String k = it.next();
                root.set(k, over.get(k));
            }
            return new StreamTokenUsage(base.getPromptTokens(), base.getCompletionTokens(), JSON.writeValueAsString(root));
        } catch (Exception e) {
            return base;
        }
    }

    public static StreamTokenUsage combine(StreamTokenUsage a, StreamTokenUsage b) {
        if (b == null || b == ZERO) {
            return a != null ? a : ZERO;
        }
        if (a == null || a == ZERO) {
            return b;
        }
        int pt = a.getPromptTokens() + b.getPromptTokens();
        int ct = a.getCompletionTokens() + b.getCompletionTokens();
        String merged = mergeUsageJsonStrings(a.getLlmUsageJson(), b.getLlmUsageJson());
        return new StreamTokenUsage(pt, ct, merged);
    }

    private static String mergeUsageJsonStrings(String aJson, String bJson) {
        if (bJson == null || bJson.isBlank()) {
            return aJson != null ? aJson : "";
        }
        if (aJson == null || aJson.isBlank()) {
            return bJson;
        }
        try {
            ObjectNode a = (ObjectNode) JSON.readTree(aJson);
            ObjectNode b = (ObjectNode) JSON.readTree(bJson);
            ObjectNode o = JSON.createObjectNode();
            copyStringFieldLatest(a, b, o, "providerThingName");
            copyStringFieldLatest(a, b, o, "providerTemplateName");
            copyStringFieldLatest(a, b, o, "apiShapeId");
            copyStringFieldLatest(a, b, o, "model");
            copyStringFieldLatest(a, b, o, "requestId");
            copyStringFieldLatest(a, b, o, "parlerRequestId");
            copyStringFieldLatest(a, b, o, "firstToolCallCacheHit");
            sumIntField(a, b, o, "inputTokens");
            sumIntField(a, b, o, "outputTokens");
            sumIntField(a, b, o, "promptTokens");
            sumIntField(a, b, o, "completionTokens");
            sumIntField(a, b, o, "cacheReadInputTokens");
            sumIntField(a, b, o, "cacheCreationInputTokens");
            sumIntField(a, b, o, "cachedPromptTokens");
            sumIntField(a, b, o, "reasoningTokens");
            sumIntField(a, b, o, "rateWaitMs");
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return bJson;
        }
    }

    private static void copyStringFieldLatest(ObjectNode a, ObjectNode b, ObjectNode out, String field) {
        String bv = textOrEmpty(b.get(field));
        if (!bv.isEmpty()) {
            out.put(field, bv);
        } else {
            out.put(field, textOrEmpty(a.get(field)));
        }
    }

    private static String textOrEmpty(JsonNode n) {
        return n == null || n.isNull() ? "" : n.asText("");
    }

    private static void sumIntField(ObjectNode a, ObjectNode b, ObjectNode out, String field) {
        int sum = a.path(field).asInt(0) + b.path(field).asInt(0);
        if (sum != 0 || a.has(field) || b.has(field)) {
            out.put(field, sum);
        }
    }

    private static String buildUsageJson(LlmResponse r, LlmUsageWireIds ids) {
        try {
            ObjectNode o = JSON.createObjectNode();
            if (ids != null) {
                o.put("providerThingName", ids.getProviderThingName());
                o.put("providerTemplateName", ids.getProviderTemplateName());
                o.put("apiShapeId", ids.getApiShapeId());
                o.put("model", ids.getModel());
            } else {
                o.put("providerThingName", "");
                o.put("providerTemplateName", "");
                o.put("apiShapeId", "");
                o.put("model", "");
            }
            String rid = r.getProviderRequestId();
            o.put("requestId", rid != null ? rid : "");
            String parlerRid = AgentToolContext.getParlerRequestId();
            o.put("parlerRequestId", parlerRid != null ? parlerRid : "");
            o.put("inputTokens", r.getInputTokens());
            o.put("outputTokens", r.getOutputTokens());
            o.put("promptTokens", r.getPromptTokens());
            o.put("completionTokens", r.getCompletionTokens());
            String shape = ids != null ? ids.getApiShapeId() : "";
            String shapeLc = shape.toLowerCase(Locale.ROOT);
            boolean anthropicShape = shapeLc.startsWith("anthropic-");
            boolean openAiFamilyShape = shapeLc.startsWith("openai-") || shapeLc.startsWith("azure-openai-");
            if (anthropicShape) {
                o.put("cacheReadInputTokens", r.getCacheReadInputTokens());
                o.put("cacheCreationInputTokens", r.getCacheCreationInputTokens());
            } else if (openAiFamilyShape) {
                o.put("cachedPromptTokens", r.getCachedPromptTokens());
            }
            int reasoning = r.getReasoningTokens();
            if (reasoning > 0) {
                o.put("reasoningTokens", reasoning);
            }
            long rw = r.getRateGateAdmissionWaitMs();
            if (rw > 0) {
                o.put("rateWaitMs", (int) Math.min(rw, Integer.MAX_VALUE));
            }
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            return "";
        }
    }

    public int getPromptTokens() {
        return promptTokens;
    }

    public int getCompletionTokens() {
        return completionTokens;
    }

    public String getLlmUsageJson() {
        return llmUsageJson;
    }
}
