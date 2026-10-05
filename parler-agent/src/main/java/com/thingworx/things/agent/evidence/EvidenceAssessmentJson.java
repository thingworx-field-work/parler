package com.thingworx.things.agent.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compact JSON projection of {@link EvidenceAssessment} for task/replay/final-answer adapters.
 * Omits empty caveat arrays and null optional fields. Never embeds raw rows.
 */
public final class EvidenceAssessmentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvidenceAssessmentJson() {}

    public static String toCompactJson(EvidenceAssessment assessment) {
        if (assessment == null) {
            return "{}";
        }
        ObjectNode n = MAPPER.createObjectNode();
        n.put("status", assessment.status().name());
        n.put("completeness", assessment.completeness().name());
        if (assessment.coverage() != null) {
            n.put("coverage", assessment.coverage());
        }
        n.put("n", assessment.n());
        putStringArray(n, "quality", assessment.quality());
        putStringArray(n, "applicability", assessment.applicability());
        putStringArray(n, "warnings", assessment.warnings());
        putStringArray(n, "conflicts", assessment.conflicts());
        putStringArray(n, "sourceCacheIds", assessment.sourceCacheIds());
        EvidenceMethodRef m = assessment.method();
        if (m != null) {
            ObjectNode method = n.putObject("method");
            if (m.id() != null) {
                method.put("id", m.id());
            }
            if (m.version() != null) {
                method.put("version", m.version());
            }
            if (m.semanticProfileDigest() != null) {
                method.put("semanticProfileDigest", m.semanticProfileDigest());
            }
        }
        try {
            return MAPPER.writeValueAsString(n);
        } catch (Exception e) {
            return "{\"status\":\"" + assessment.status().name() + "\"}";
        }
    }

    private static void putStringArray(ObjectNode n, String field, java.util.List<String> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        ArrayNode arr = n.putArray(field);
        for (String v : values) {
            arr.add(v);
        }
    }
}
