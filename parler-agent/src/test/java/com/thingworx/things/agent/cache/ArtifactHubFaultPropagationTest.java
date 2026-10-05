package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.AnalyzeEntitySetExecutor;
import com.thingworx.things.agent.tools.CachedPayloadInspect;
import com.thingworx.things.agent.tools.CachedTabularToolsExecutor;
import com.thingworx.things.agent.tools.BuildChartFromTabularResultExecutor;
import com.thingworx.things.agent.tools.ExtractNestedCachedResult;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;

class ArtifactHubFaultPropagationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CACHE_ID = "11111111-2222-3333-4444-555555555555";

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void cacheMissRemainsNullForBothHubs() {
        install(new OpenFaultCache(ArtifactCacheFaultCode.CACHE_MISS));

        assertNull(TabularArtifactHub.lookup(CACHE_ID));
        assertNull(JsonArtifactHub.lookup(CACHE_ID));
    }

    @Test
    void repositoryUnavailablePropagatesFromBothHubs() {
        install(new OpenFaultCache(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE));

        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> TabularArtifactHub.lookup(CACHE_ID));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> JsonArtifactHub.lookup(CACHE_ID));
    }

    @Test
    void absentAgentContextPropagatesRepositoryUnavailableFromLookup() {
        TabularArtifactHub.clearTestState();

        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> TabularArtifactHub.lookup(CACHE_ID));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> JsonArtifactHub.lookup(CACHE_ID));
    }

    @Test
    void payloadFaultPropagatesFromBothHubs() {
        install(new OpenFaultCache(ArtifactCacheFaultCode.PAYLOAD_FAULT));

        assertFault(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                () -> TabularArtifactHub.lookup(CACHE_ID));
        assertFault(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                () -> JsonArtifactHub.lookup(CACHE_ID));
    }

    @Test
    void unexpectedTabularDecodeFailureBecomesBoundedPayloadFault() {
        install(new FixedPayloadCache("not-tabular-json".getBytes(StandardCharsets.UTF_8)));

        ArtifactCacheException ex = assertFault(ArtifactCacheFaultCode.PAYLOAD_FAULT,
                () -> TabularArtifactHub.lookup(CACHE_ID));
        assertEquals("Cached tabular payload could not be read or validated", ex.getMessage());
        assertNotNull(ex.getCause());
    }

    @Test
    void representativeToolsPreservePayloadFaultCode() throws Exception {
        install(new OpenFaultCache(ArtifactCacheFaultCode.PAYLOAD_FAULT));

        assertErrorCode("PAYLOAD_FAULT",
                InvokeServiceExecutor.executeFetchCachedResult(new ToolCall(
                        "fetch-1", "fetch_cached_result", "{\"cacheId\":\"" + CACHE_ID + "\"}")));
        assertErrorCode("PAYLOAD_FAULT",
                CachedTabularToolsExecutor.executeSummarizeCachedResult(new ToolCall(
                        "summary-1", "summarize_cached_result", "{\"cacheId\":\"" + CACHE_ID + "\"}")));
        assertErrorCode("PAYLOAD_FAULT", AnalyzeEntitySetExecutor.execute(entitySetCall("entity-1")));
        assertErrorCode("PAYLOAD_FAULT",
                CachedPayloadInspect.executeInspectCachedPayload(new ToolCall(
                        "inspect-1", "inspect_cached_payload",
                        "{\"cacheId\":\"" + CACHE_ID + "\",\"mode\":\"inspect\"}")));
        assertErrorCode("PAYLOAD_FAULT",
                ExtractNestedCachedResult.executeExtractNested(new ToolCall(
                        "nested-1", "extract_nested",
                        "{\"sourceCacheId\":\"" + CACHE_ID + "\",\"cellPath\":\"[0].children\"}")));
        assertErrorCode("PAYLOAD_FAULT",
                BuildChartFromTabularResultExecutor.execute(chartCall("chart-1")));
    }

    @Test
    void representativeToolsDoNotConsumeRepositoryUnavailable() {
        install(new OpenFaultCache(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE));

        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> InvokeServiceExecutor.executeFetchCachedResult(new ToolCall(
                        "fetch-2", "fetch_cached_result", "{\"cacheId\":\"" + CACHE_ID + "\"}")));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> CachedTabularToolsExecutor.executeSummarizeCachedResult(new ToolCall(
                        "summary-2", "summarize_cached_result", "{\"cacheId\":\"" + CACHE_ID + "\"}")));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> AnalyzeEntitySetExecutor.execute(entitySetCall("entity-2")));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> CachedPayloadInspect.executeInspectCachedPayload(new ToolCall(
                        "inspect-2", "inspect_cached_payload",
                        "{\"cacheId\":\"" + CACHE_ID + "\",\"mode\":\"inspect\"}")));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> ExtractNestedCachedResult.executeExtractNested(new ToolCall(
                        "nested-2", "extract_nested",
                        "{\"sourceCacheId\":\"" + CACHE_ID + "\",\"cellPath\":\"[0].children\"}")));
        assertFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE,
                () -> BuildChartFromTabularResultExecutor.execute(chartCall("chart-2")));
    }

    private static void install(ArtifactCache cache) {
        TabularArtifactHub.clearTestState();
        TabularArtifactHub.setTestArtifactCache(cache);
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "artifact-hub-fault-test");
        AgentToolContext.setConversationId("artifact-hub-fault-test");
    }

    private static ArtifactCacheException assertFault(ArtifactCacheFaultCode expected,
            org.junit.jupiter.api.function.Executable executable) {
        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class, executable);
        assertEquals(expected, ex.code());
        return ex;
    }

    private static void assertErrorCode(String expected, String json) throws Exception {
        JsonNode root = MAPPER.readTree(json);
        assertEquals("error", root.path("status").asText(), json);
        assertEquals(expected, root.path("code").asText(), json);
    }

    private static ToolCall entitySetCall(String id) {
        String operand = "{\"cacheId\":\"" + CACHE_ID + "\",\"keyColumn\":\"id\"}";
        return new ToolCall(id, "analyze_entity_set",
                "{\"operation\":\"intersection\",\"left\":" + operand + ",\"right\":" + operand + "}");
    }

    private static ToolCall chartCall(String id) {
        return new ToolCall(id, "build_chart_from_tabular_result",
                "{\"source\":\"cache_id\",\"cacheId\":\"" + CACHE_ID
                        + "\",\"kind\":\"line\",\"xColumn\":\"x\",\"yColumn\":\"y\"}");
    }

    private static class OpenFaultCache implements ArtifactCache {
        private final ArtifactCacheFaultCode code;

        OpenFaultCache(ArtifactCacheFaultCode code) {
            this.code = code;
        }

        @Override
        public ArtifactWriter create(ArtifactCreateRequest request, ArtifactAccessContext context,
                ArtifactIoLimits limits) {
            throw new UnsupportedOperationException("create not used");
        }

        @Override
        public ArtifactRef publish(ArtifactWriter writer, ArtifactAccessContext context) {
            throw new UnsupportedOperationException("publish not used");
        }

        @Override
        public ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits) {
            throw new ArtifactCacheException(code, "injected " + code.name());
        }

        @Override
        public void invalidate(ArtifactRef ref, ArtifactAccessContext context) {}

        @Override
        public void invalidateScope(ArtifactAccessContext context) {}
    }

    private static final class FixedPayloadCache extends OpenFaultCache {
        private final byte[] payload;

        FixedPayloadCache(byte[] payload) {
            super(ArtifactCacheFaultCode.INTERNAL);
            this.payload = payload.clone();
        }

        @Override
        public ArtifactReader open(ArtifactRef ref, ArtifactAccessContext context, ArtifactIoLimits limits) {
            return new ArtifactReader() {
                private boolean read;

                @Override
                public ArtifactRecord record() {
                    return null;
                }

                @Override
                public byte[] readBytes(int maxLen) {
                    if (read) {
                        return new byte[0];
                    }
                    read = true;
                    return payload.clone();
                }

                @Override
                public void close() {}
            };
        }
    }
}
