package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * JSON markers for Playbook-internal tool rows persisted on {@code AgentMessageStream}.
 * These keys are stripped before chart/table hydration from Stream rows and are not normative wire contract.
 */
public final class ParlerPlaybookArtifactWireConstants {

    /**
     * When {@code true} on a persisted {@code role=tool} row, {@link AgentConversationRehydrator} must not surface
     * the row as compact assistant evidence (avoids LLM context bloat from internal Playbook calls).
     */
    public static final String OMIT_FROM_LLM_REHYDRATE_JSON_KEY = "_parlerPlaybookInternalOmitFromLlmRehydrate";

    /** Optional diagnostics / provenance; stripped before chart/table builders read tool JSON. */
    public static final String PLAYBOOK_NODE_ID_JSON_KEY = "playbookNodeId";

    private ParlerPlaybookArtifactWireConstants() {}

    /**
     * Removes internal-only keys so {@link ParlerChartWireSupport} / table wire helpers see the same JSON shape as
     * top-level tool rows.
     */
    public static String stripInternalWireMetadataForArtifactHydration(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        String t = body.trim();
        if (!t.startsWith("{")) {
            return body;
        }
        try {
            JSONObject o = new JSONObject(t);
            o.remove(OMIT_FROM_LLM_REHYDRATE_JSON_KEY);
            o.remove(PLAYBOOK_NODE_ID_JSON_KEY);
            return o.toString();
        } catch (Exception e) {
            return body;
        }
    }
}
