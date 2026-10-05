package com.thingworx.things.agent.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.analysis.AnalysisEnvelope;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeJson;

/** Shared OK/ERROR JSON shell for U4 tabulate modes / demoted aliases. */
final class U4ToolResultJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private U4ToolResultJson() {}

    static String error(String reason, String detail) throws Exception {
        ObjectNode err = MAPPER.createObjectNode();
        err.put("status", "ERROR");
        err.put("reason", reason);
        if (detail != null && !detail.isBlank()) {
            err.put("detail", detail);
        }
        err.put("mayPublish", false);
        return MAPPER.writeValueAsString(err);
    }

    static String ok(String reason, String findingCacheId, boolean mayPublish, AnalysisEnvelope envelope)
            throws Exception {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "OK");
        out.put("reason", reason == null ? "OK" : reason);
        if (findingCacheId != null) {
            out.put("findingCacheId", findingCacheId);
        }
        out.put("mayPublish", mayPublish);
        if (envelope != null) {
            out.set("analysisEnvelope", MAPPER.readTree(AnalysisEnvelopeJson.toCompactJson(envelope)));
        }
        return MAPPER.writeValueAsString(out);
    }
}
