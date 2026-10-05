package com.thingworx.things.agent.taskstate;

import org.json.JSONObject;

/**
 * v1b / v1b.2: tool-outcome classification when there is no v1a {@link AgentTaskEvidence} row.
 *
 * <p>Normative: {@code docs/agent/task-state.md} § v1b.2 Hook Order — required classification fix.
 */
public final class NoEvidenceToolOutcome {

    private NoEvidenceToolOutcome() {}

    /**
     * Explicit JSON error envelope for v1b.2: parseable JSON object whose top-level {@code status} is {@code error}.
     */
    public static boolean isExplicitJsonErrorEnvelope(String result) {
        if (result == null || result.isEmpty()) {
            return false;
        }
        try {
            JSONObject o = new JSONObject(result);
            return "error".equals(o.optString("status", ""));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Success for the no-evidence path: non-null, not an explicit error envelope, including empty STRING and plain
     * text (e.g. {@code get_agent_skill} body) and non-error JSON objects.
     */
    public static boolean isSatisfiedForNoEvidencePath(String result) {
        if (result == null) {
            return false;
        }
        if (isExplicitJsonErrorEnvelope(result)) {
            return false;
        }
        if (result.isEmpty()) {
            return true;
        }
        try {
            JSONObject o = new JSONObject(result);
            if ("success".equals(o.optString("status", ""))) {
                return true;
            }
            return !"error".equals(o.optString("status", ""));
        } catch (Exception e) {
            return true;
        }
    }
}
