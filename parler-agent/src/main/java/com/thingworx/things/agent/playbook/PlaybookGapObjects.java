package com.thingworx.things.agent.playbook;

import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Stable gap objects ({@code code} + {@code message}) per
 * {@code docs/agent/playbook-customer-readiness.md} §8.2.
 */
public final class PlaybookGapObjects {

    public static final String CODE_GAP_NOTE = "GAP_NOTE";

    /** §8.2 optional context keys preserved on collection-facing gap objects. */
    private static final Set<String> CONTEXT_KEYS = Set.of(
            "detail", "region", "input", "candidates", "arrayPath", "sourceNodeId", "childIndex");

    private PlaybookGapObjects() {}

    public static JSONObject structured(String code, String message) {
        String c = code != null && !code.isBlank() ? code.trim() : CODE_GAP_NOTE;
        return new JSONObject().put("code", c).put("message", message != null ? message : "");
    }

    /** Producer helper: attach allowlisted context keys from {@code extra}. */
    public static JSONObject withContext(String code, String message, JSONObject extra) {
        JSONObject g = structured(code, message);
        if (extra != null) {
            for (String key : CONTEXT_KEYS) {
                if (extra.has(key) && !extra.isNull(key)) {
                    g.put(key, extra.get(key));
                }
            }
        }
        return g;
    }

    /** Collection-facing: every gap entry is a structured object with allowlisted context only. */
    public static JSONArray normalizeForCollection(JSONArray gaps) {
        JSONArray out = new JSONArray();
        if (gaps == null) {
            return out;
        }
        for (int i = 0; i < gaps.length(); i++) {
            Object el = gaps.get(i);
            if (el instanceof String) {
                String s = ((String) el).trim();
                if (!s.isEmpty()) {
                    out.put(structured(CODE_GAP_NOTE, s));
                }
            } else if (el instanceof JSONObject) {
                out.put(normalizeObject((JSONObject) el));
            }
        }
        return out;
    }

    private static JSONObject normalizeObject(JSONObject o) {
        String code = o.optString("code", "").trim();
        if (code.isEmpty()) {
            code = o.optString("reason", "").trim();
        }
        if (code.isEmpty()) {
            code = CODE_GAP_NOTE;
        }
        String message = o.optString("message", "").trim();
        if (message.isEmpty()) {
            message = code;
        }
        JSONObject out = structured(code, message);
        for (String key : CONTEXT_KEYS) {
            if (o.has(key) && !o.isNull(key)) {
                out.put(key, o.get(key));
            }
        }
        return out;
    }

    /**
     * Run-level outcome for collection / {@code GetAgentRuntimeSnapshot} (§8.2 run-level envelope +
     * per-node gaps/evidence).
     */
    public static JSONObject buildRunOutcome(
            PlaybookRunContext ctx,
            PlaybookRunResult.Status status,
            String message,
            String failureCode,
            int toolCallCount,
            int llmCallCount,
            long elapsedMs) {
        JSONObject root = new JSONObject();
        root.put("playbookId", ctx.playbookId());
        root.put("runId", ctx.runId());
        root.put("status", toWireStatus(status));
        if (message != null && !message.isBlank()) {
            root.put("message", message);
        }
        if (failureCode != null && !failureCode.isBlank()) {
            root.put("failureCode", failureCode);
        }
        root.put("nodeCount", ctx.nodeOutputSize());
        root.put("toolCallCount", toolCallCount);
        root.put("llmCallCount", llmCallCount);
        root.put("elapsedMs", elapsedMs);
        root.put("nodes", buildNodeDiagnostics(ctx));
        return root;
    }

    /** @deprecated use {@link #buildRunOutcome} */
    @Deprecated
    public static JSONObject buildRunDiagnostics(PlaybookRunContext ctx) {
        return buildRunOutcome(ctx, PlaybookRunResult.Status.COMPLETED, "", null, ctx.toolCallCount(),
                ctx.llmCallCount(), 0);
    }

    private static JSONArray buildNodeDiagnostics(PlaybookRunContext ctx) {
        JSONArray nodes = new JSONArray();
        for (Map.Entry<String, JSONObject> e : ctx.nodeOutputsSnapshot().entrySet()) {
            JSONObject result = e.getValue();
            if (result == null) {
                continue;
            }
            JSONObject row = new JSONObject();
            row.put("nodeId", e.getKey());
            row.put("status", result.optString("status", ""));
            String nodeMessage = result.optString("message", "");
            if (!nodeMessage.isBlank()) {
                row.put("message", nodeMessage);
            }
            String errorCode = result.optString("errorCode", "");
            if (!errorCode.isBlank()) {
                row.put("errorCode", errorCode);
            }
            JSONObject output = result.optJSONObject("output");
            if (output != null && output.has("gaps")) {
                row.put("gaps", normalizeForCollection(output.optJSONArray("gaps")));
            }
            if (output != null && output.has("childResults")) {
                row.put("childResults", output.optJSONArray("childResults"));
            }
            if (result.has("evidenceLines")) {
                row.put("evidenceLines", result.optJSONArray("evidenceLines"));
            }
            nodes.put(row);
        }
        return nodes;
    }

    static String toWireStatus(PlaybookRunResult.Status status) {
        if (status == null) {
            return "failed";
        }
        if (status == PlaybookRunResult.Status.COMPLETED) {
            return "completed";
        }
        if (status == PlaybookRunResult.Status.NEEDS_CLARIFICATION) {
            return "needs_clarification";
        }
        if (status == PlaybookRunResult.Status.CANCELLED) {
            return "cancelled";
        }
        return "failed";
    }
}
