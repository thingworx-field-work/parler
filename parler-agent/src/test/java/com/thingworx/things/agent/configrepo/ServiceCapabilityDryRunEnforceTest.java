package com.thingworx.things.agent.configrepo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class ServiceCapabilityDryRunEnforceTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void unsupportedLeavesArgsUnchanged() {
        ServiceCapabilityMetadata cap = meta(false, "");
        ServiceCapabilityDryRunEnforce.Result r =
                ServiceCapabilityDryRunEnforce.apply("{\"requestId\":\"a\"}", cap);
        assertFalse(r.blocked());
        assertEquals("{\"requestId\":\"a\"}", r.argumentsJson());
    }

    @Test
    void missingParameterInjectsTrue() throws Exception {
        ServiceCapabilityMetadata cap = meta(true, "dryRun");
        ServiceCapabilityDryRunEnforce.Result r =
                ServiceCapabilityDryRunEnforce.apply("{\"requestId\":\"a\"}", cap);
        assertFalse(r.blocked());
        JsonNode n = JSON.readTree(r.argumentsJson());
        assertTrue(n.path("dryRun").asBoolean());
        assertEquals("a", n.path("requestId").asText());
    }

    @Test
    void explicitFalsePreserved() {
        ServiceCapabilityMetadata cap = meta(true, "dryRun");
        ServiceCapabilityDryRunEnforce.Result r =
                ServiceCapabilityDryRunEnforce.apply("{\"dryRun\":false}", cap);
        assertFalse(r.blocked());
        assertTrue(r.argumentsJson().contains("false"));
    }

    @Test
    void wrongTypeFailsClosed() {
        ServiceCapabilityMetadata cap = meta(true, "dryRun");
        ServiceCapabilityDryRunEnforce.Result r =
                ServiceCapabilityDryRunEnforce.apply("{\"dryRun\":\"yes\"}", cap);
        assertTrue(r.blocked());
        assertTrue(r.blockReason().orElse("").contains("boolean"));
    }

    @Test
    void nonObjectArgsFailClosed() {
        ServiceCapabilityMetadata cap = meta(true, "dryRun");
        assertTrue(ServiceCapabilityDryRunEnforce.apply("[1]", cap).blocked());
    }

    private static ServiceCapabilityMetadata meta(boolean dryRunSupported, String dryRunParameter) {
        return new ServiceCapabilityMetadata(
                "purpose",
                "1",
                ServiceCapabilityRisk.MUTATING,
                dryRunSupported,
                dryRunParameter,
                ServiceIdempotencyMode.NONE,
                "",
                List.of("INTERNAL"),
                ServiceCapabilityAdmission.DEFAULT,
                true,
                "",
                "",
                "",
                "",
                List.of(),
                ServiceCapabilityRuntimeState.ACTIVE);
    }
}
