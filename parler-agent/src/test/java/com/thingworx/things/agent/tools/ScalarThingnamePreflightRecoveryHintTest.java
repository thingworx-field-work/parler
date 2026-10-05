package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Design coverage #9: {@code IDENTITY_RESOLUTION_REQUIRED.recoveryHint} only when {@code resolve_thing} is recoverable.
 */
class ScalarThingnamePreflightRecoveryHintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AgentToolContext.clear();
    }

    @Test
    void identity_json_includes_recovery_hint_when_recoverable_override_true() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
        JsonNode n = MAPPER.readTree(ScalarThingnamePreflight.identityResolutionRequiredJson("thingName", "x"));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertEquals("resolve_thing", n.path("recoveryHint").path("tool").asText());
        assertTrue(n.path("message").asText().contains("resolve_thing"));
    }

    @Test
    void identity_json_omits_recovery_hint_when_recoverable_override_false() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        JsonNode n = MAPPER.readTree(ScalarThingnamePreflight.identityResolutionRequiredJson("thingName", "label"));
        assertEquals("IDENTITY_RESOLUTION_REQUIRED", n.path("code").asText());
        assertFalse(n.has("recoveryHint"));
        assertTrue(n.path("message").asText().contains("resolve_thing is not available"));
    }

    @Test
    void identity_json_omits_recovery_hint_without_agent_and_no_override() throws Exception {
        AgentToolContext.clear();
        JsonNode n = MAPPER.readTree(ScalarThingnamePreflight.identityResolutionRequiredJson("p", "v"));
        assertFalse(n.has("recoveryHint"));
    }
}
