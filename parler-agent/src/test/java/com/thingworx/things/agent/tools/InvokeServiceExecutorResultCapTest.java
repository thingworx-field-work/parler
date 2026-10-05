package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.cache.JsonArtifactHub;
import com.thingworx.things.agent.cache.ArtifactAccessContext;
import com.thingworx.things.agent.cache.ArtifactCache;
import com.thingworx.things.agent.cache.ArtifactCacheException;
import com.thingworx.things.agent.cache.ArtifactCacheFaultCode;
import com.thingworx.things.agent.cache.ArtifactCreateRequest;
import com.thingworx.things.agent.cache.ArtifactIoLimits;
import com.thingworx.things.agent.cache.ArtifactReader;
import com.thingworx.things.agent.cache.ArtifactRef;
import com.thingworx.things.agent.cache.ArtifactWriter;
import com.thingworx.things.agent.cache.TabularArtifactHub;

class InvokeServiceExecutorResultCapTest {

    InvokeServiceExecutorResultCapTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void nonTabularOverCapOnThing_cachesAsLargeJson() throws Exception {
        AgentToolContext.setConversationId("cap-thing-1");
        String body = oversizedJson("JSON", null);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "MyThing", "GetMetadata");
        JsonNode env = MAPPER.readTree(out);
        assertEquals("success", env.path("status").asText(), out);
        assertEquals("LARGE_JSON", env.path("resultKind").asText());
        assertTrue(env.path("cacheId").asText().length() > 0);
        assertEquals(body.length(), env.path("utf16Chars").asInt());
        assertEquals(InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP, env.path("limitChars").asInt());
        assertEquals("invoke_service", env.path("originalToolName").asText());
        assertEquals("Thing", env.path("entityType").asText());
        assertEquals("MyThing", env.path("entityName").asText());
        assertEquals("GetMetadata", env.path("serviceName").asText());
        assertTrue(containsAlternative(env, "inspect_cached_payload"));
        assertEquals(body, JsonArtifactHub.lookup(env.path("cacheId").asText()));
    }

    @Test
    void nonTabularOverCapOnThingTemplate_cachesAsLargeJson() throws Exception {
        AgentToolContext.setConversationId("cap-template-1");
        String body = oversizedJson("JSON", null);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "ThingTemplate", "MyTemplate", "GetMetadata");
        JsonNode env = MAPPER.readTree(out);
        assertEquals("LARGE_JSON", env.path("resultKind").asText());
        assertEquals("ThingTemplate", env.path("entityType").asText());
        assertEquals("MyTemplate", env.path("entityName").asText());
        assertFalse(env.has("thingName") && !env.path("thingName").asText("").isEmpty()
                && "MyTemplate".equals(env.path("thingName").asText()));
    }

    @Test
    void nonTabularUnderCap_passesThrough() {
        String body = "{\"status\":\"success\",\"resultKind\":\"JSON\",\"value\":\"small\"}";
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Svc");
        assertEquals(body, out);
    }

    @Test
    void infotableLargeOverCap_passesThroughExempt() {
        String body = oversizedJson("INFOTABLE_LARGE", null);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Query");
        assertEquals(body, out);
    }

    @Test
    void smallBodyWithCacheId_passesThroughExempt() {
        String body = "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"cacheId\":\"abc-123\","
                + "\"sampleRows\":[]}";
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Query");
        assertEquals(body, out);
    }

    @Test
    void exactlyAtCap_inclusive_passesThrough() {
        String body = "x".repeat(InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Svc");
        assertEquals(body, out);
    }

    @Test
    void descriptionContainingInfotableSubstring_stillCaches() throws Exception {
        AgentToolContext.setConversationId("cap-desc-1");
        String body = oversizedJson("JSON", "prefix INFOTABLE suffix");
        assertTrue(InvokeServiceExecutor.isTabularExemptInvokeServiceResult(body) == false);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Svc");
        assertEquals("LARGE_JSON", MAPPER.readTree(out).path("resultKind").asText());
    }

    @Test
    void malformedJsonOverCap_cachesAsLargeJson() throws Exception {
        AgentToolContext.setConversationId("cap-malformed-1");
        char[] chars = new char[InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP + 1000];
        java.util.Arrays.fill(chars, 'x');
        String body = new String(chars);
        String out = InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                body, "Thing", "T", "Svc");
        JsonNode env = MAPPER.readTree(out);
        assertEquals("success", env.path("status").asText());
        assertEquals("LARGE_JSON", env.path("resultKind").asText());
        assertTrue(env.path("cacheId").asText().length() > 0);
    }

    @Test
    void repositoryUnavailableDuringLargeJsonProducer_doesNotFallBackToSizeError() {
        AgentToolContext.setConversationId("cap-repository-fault");
        TabularArtifactHub.setTestArtifactCache(new ArtifactCache() {
            @Override
            public ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
                    ArtifactIoLimits limits) {
                throw new ArtifactCacheException(
                        ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, "injected create failure");
            }

            @Override
            public ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context) {
                throw new UnsupportedOperationException("publish not used");
            }

            @Override
            public ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits) {
                throw new UnsupportedOperationException("open not used");
            }

            @Override
            public void invalidate(ArtifactRef ref, ArtifactAccessContext context) {}

            @Override
            public void invalidateScope(ArtifactAccessContext context) {}
        });

        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult(
                        oversizedJson("JSON", null), "Thing", "T", "Svc"));

        assertEquals(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, ex.code());
    }

    private static String oversizedJson(String resultKind, String description) {
        try {
            ObjectNode o = MAPPER.createObjectNode();
            o.put("status", "success");
            o.put("resultKind", resultKind);
            if (description != null) {
                o.put("description", description);
            }
            int base = o.toString().length();
            int need = InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP + 1000 - base;
            StringBuilder pad = new StringBuilder(need);
            for (int i = 0; i < need; i++) {
                pad.append('a');
            }
            o.put("payload", pad.toString());
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static boolean containsAlternative(JsonNode env, String tool) {
        for (JsonNode n : env.path("recoveryHint").path("alternatives")) {
            if (tool.equals(n.asText())) {
                return true;
            }
        }
        return false;
    }
}
