package com.thingworx.things.agent.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.ParlerProtectionAudit;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.tools.ProtectedValuePolicy;

/**
 * U3E M3 / EG6: lock G20 current-consumer alignment without inventing a new action-risk framework.
 */
class U3eG20AlignmentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void passwordProtectionCodes_unchanged() {
        assertEquals("PROTECTED_VALUE_READ_BLOCKED", ProtectedValuePolicy.CODE_READ_BLOCKED);
        assertEquals("PROTECTED_VALUE_WRITE_BLOCKED", ProtectedValuePolicy.CODE_WRITE_BLOCKED);
        assertEquals("PROTECTED_VALUE_INPUT_BLOCKED", ProtectedValuePolicy.CODE_INPUT_BLOCKED);
        assertEquals("PROTECTED_TABULAR_COLUMN_BLOCKED", ProtectedValuePolicy.CODE_TABULAR_PROTECTED_COLUMN);
        assertEquals("***", ProtectedValuePolicy.MASK);
        // Audit sink remains the existing structured logger class (no new audit framework).
        assertEquals("com.thingworx.things.agent.ParlerProtectionAudit", ParlerProtectionAudit.class.getName());
    }

    @Test
    void egress_preservesG18TypedErrorFieldsThroughLastResort() throws Exception {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 9000; i++) {
            pad.append('x');
        }
        String raw = "{"
                + "\"status\":\"error\","
                + "\"code\":\"CACHE_MISS\","
                + "\"category\":\"LIFECYCLE\","
                + "\"reason\":\"NOT_FOUND\","
                + "\"retryable\":true,"
                + "\"retryBudgetKey\":\"source-query\","
                + "\"recoveryActions\":[{\"type\":\"REEXECUTE_SOURCE\"}],"
                + "\"evidenceStillUsable\":false,"
                + "\"message\":\"gone\","
                + "\"padding\":\"" + pad + "\""
                + "}";
        var egress = ToolResultEgressGateway.compactForLlmAppend("fetch_cached_result", "c1", raw, null);
        JsonNode n = MAPPER.readTree(egress.getLlmContent());
        assertEquals("CACHE_MISS", n.path("code").asText());
        assertEquals("LIFECYCLE", n.path("category").asText());
        assertEquals("NOT_FOUND", n.path("reason").asText());
        assertTrue(n.path("retryable").asBoolean());
        assertEquals("source-query", n.path("retryBudgetKey").asText());
        assertEquals("REEXECUTE_SOURCE", n.path("recoveryActions").get(0).path("type").asText());
        assertFalse(n.path("evidenceStillUsable").asBoolean());
    }

    @Test
    void noActionRiskEnumInvented() {
        // EG6: prefer zero new classification framework — ActionRisk must not appear as a type.
        try {
            Class.forName("com.thingworx.things.agent.evidence.ActionRisk");
            throw new AssertionError("ActionRisk enum must not be invented in U3E M3");
        } catch (ClassNotFoundException expected) {
            // ok
        }
        try {
            Class.forName("com.thingworx.things.agent.ActionRisk");
            throw new AssertionError("ActionRisk enum must not be invented in U3E M3");
        } catch (ClassNotFoundException expected) {
            // ok
        }
    }
}
