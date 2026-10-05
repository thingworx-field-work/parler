package com.thingworx.things.agent.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.execution.BudgetVector;
import com.thingworx.things.agent.execution.ExecutionScope;
import com.thingworx.things.agent.execution.RunInvocationContext;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.AgentToolContext;

class TypedToolErrorJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        RetryLedger.clearAllForTests();
        TabularArtifactHub.invalidateCurrentScope();
        AgentToolContext.clear();
    }

    @Test
    void malformedCacheId_omitsReason_keepsOuterCacheMiss() throws Exception {
        JsonNode n = MAPPER.readTree(TypedToolErrorJson.cacheMiss("not-a-uuid", "gone"));
        assertEquals("CACHE_MISS", n.path("code").asText());
        assertFalse(n.has("reason"));
        assertEquals("LIFECYCLE", n.path("category").asText());
    }

    @Test
    void foreignWellFormedUuid_omitsReason() throws Exception {
        String foreign = UUID.randomUUID().toString();
        JsonNode n = MAPPER.readTree(TypedToolErrorJson.cacheMiss(foreign, "gone"));
        assertEquals("CACHE_MISS", n.path("code").asText());
        assertFalse(n.has("reason"));
    }

    @Test
    void provenCurrentScopeMiss_emitsNotFoundAndConsumesLedgerOnce() throws Exception {
        String id = UUID.randomUUID().toString();
        TabularArtifactHub.rememberDescriptorForProofTests(id,
                SourceDescriptor.builder().sourceRouteId("query_numeric_property_history").build());
        RunInvocationContext inv = RunInvocationContext.of("inv-cache-miss", ExecutionScope.CONVERSATION, "scope-1",
                BudgetVector.defaultsForTabular());
        AgentToolContext.setRunInvocationContext(inv);

        JsonNode first = MAPPER.readTree(TypedToolErrorJson.cacheMiss(id, "gone"));
        assertEquals("error", first.path("status").asText());
        assertEquals("CACHE_MISS", first.path("code").asText());
        assertEquals("LIFECYCLE", first.path("category").asText());
        assertEquals("NOT_FOUND", first.path("reason").asText());
        assertTrue(first.path("retryable").asBoolean());
        assertEquals("source-query", first.path("retryBudgetKey").asText());
        assertEquals(1, first.path("recoveryActions").size());
        assertEquals("REEXECUTE_SOURCE", first.path("recoveryActions").get(0).path("type").asText());
        assertEquals("source-query", AgentToolContext.getRunInvocationContext().retryBudgetKey());

        JsonNode second = MAPPER.readTree(TypedToolErrorJson.cacheMiss(id, "gone again"));
        assertEquals("CACHE_MISS", second.path("code").asText());
        assertEquals("NOT_FOUND", second.path("reason").asText());
        assertEquals(0, second.path("recoveryActions").size());
        assertFalse(second.path("retryable").asBoolean());
        assertFalse(second.has("retryBudgetKey"));
        assertTrue(second.path("message").asText().contains("exhausted"));
    }

    @Test
    void toJson_preservesOuterCodeWithoutLedger() throws Exception {
        TypedToolError t = ErrorRecoveryMapper.map("PERMISSION_DENIED", "nope");
        JsonNode n = MAPPER.readTree(TypedToolErrorJson.toJson(t));
        assertEquals("PERMISSION_DENIED", n.path("code").asText());
        assertEquals("AUTHORIZATION", n.path("category").asText());
        assertFalse(n.path("retryable").asBoolean());
        assertEquals("STOP_WITH_EVIDENCE", n.path("recoveryActions").get(0).path("type").asText());
    }
}
