package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
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
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class CompactFetchStreamRehydrateTest {

    private static final String FAULT_CACHE_ID = "11111111-2222-3333-4444-555555555555";

    CompactFetchStreamRehydrateTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void accepts_marker_compact_v1() {
        JSONObject o = new JSONObject();
        o.put(FetchCachedCompactPersistFormat.FORMAT_KEY, FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1);
        o.put("status", "success");
        o.put("cacheId", "c1");
        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void accepts_numeric_history_compact_v1() {
        JSONObject o = numericCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_AGGREGATES");
        o.put("totalRows", 242);
        o.put("returnedRows", 0);
        o.put("sampleRows", new org.json.JSONArray());

        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void accepts_numeric_history_matrix_v1() {
        JSONObject o = numericCompact("parler.infotable.matrix.v1", "NUMERIC_HISTORY_INLINE");
        o.put("sampleRows", new org.json.JSONArray("[[\"2026-06-05T00:00:00Z\",5.0]]"));

        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void rejects_numeric_history_compact_with_points_array() {
        JSONObject o = numericCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_INLINE");
        o.put("sampleRows", new org.json.JSONArray());
        o.put("points", new org.json.JSONArray("[{\"timestamp\":\"2026-06-05T00:00:00Z\",\"value\":5.0}]"));

        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void rejects_numeric_history_matrix_without_numeric_resultKind() {
        JSONObject o = numericCompact("parler.infotable.matrix.v1", "INFOTABLE_LARGE");
        o.put("sampleRows", new org.json.JSONArray("[[\"2026-06-05T00:00:00Z\",5.0]]"));

        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void accepts_value_stream_history_compact_native() {
        JSONObject o = valueStreamCompact(InvokeServiceExecutor.PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT);
        o.put("sampleRows", new org.json.JSONArray("[[\"2026-06-05T00:00:00Z\",\"on\"]]"));
        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void accepts_value_stream_history_matrix_v1_with_value_stream_resultKind() {
        JSONObject o = valueStreamCompact("parler.infotable.matrix.v1");
        o.put("sampleRows", new org.json.JSONArray("[[\"2026-06-05T00:00:00Z\",\"x\"]]"));
        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void rejects_value_stream_history_with_points_array() {
        JSONObject o = valueStreamCompact(InvokeServiceExecutor.PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT);
        o.put("sampleRows", new org.json.JSONArray());
        o.put("points", new org.json.JSONArray("[1]"));
        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void prepare_value_stream_compact_annotates_historical_when_cache_missing() {
        String in = valueStreamCompact(InvokeServiceExecutor.PROPERTY_HISTORY_VALUE_STREAM_COMPACT_FORMAT)
                .put("cacheId", "vs-missing")
                .put("sampleRows", new org.json.JSONArray("[[\"t\",\"v\"]]"))
                .toString();
        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent("conv-vs-rh", in);
        assertNotNull(out);
        JSONObject o = new JSONObject(out);
        assertTrue(o.getBoolean(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
    }

    @Test
    void rejects_legacy_without_columns() {
        JSONObject o = new JSONObject();
        o.put("status", "success");
        o.put("cacheId", "c1");
        o.put("sampleOnly", true);
        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void accepts_legacy_with_columns() {
        JSONObject o = new JSONObject();
        o.put("status", "success");
        o.put("cacheId", "c1");
        o.put("sampleOnly", true);
        org.json.JSONArray cols = new org.json.JSONArray();
        cols.put(new JSONObject().put("name", "a").put("baseType", "STRING"));
        o.put("columns", cols);
        assertTrue(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void rejects_password_column() {
        JSONObject o = new JSONObject();
        o.put(FetchCachedCompactPersistFormat.FORMAT_KEY, FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1);
        o.put("status", "success");
        o.put("cacheId", "c1");
        org.json.JSONArray cols = new org.json.JSONArray();
        cols.put(new JSONObject().put("name", "p").put("baseType", "PASSWORD"));
        o.put("columns", cols);
        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void rejects_oversized_rows_sample() {
        org.json.JSONArray rows = new org.json.JSONArray();
        for (int i = 0; i < 250; i++) {
            rows.put(new JSONObject().put("x", i));
        }
        JSONObject o = new JSONObject();
        o.put(FetchCachedCompactPersistFormat.FORMAT_KEY, FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1);
        o.put("status", "success");
        o.put("cacheId", "c1");
        o.put("columns", new org.json.JSONArray("[{\"name\":\"x\",\"baseType\":\"NUMBER\"}]"));
        o.put("rows", rows);
        assertFalse(CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(o));
    }

    @Test
    void skips_hitl_sibling_skipped() {
        assertTrue(CompactFetchStreamRehydrate.shouldSkipToolRowForStreamRehydrate(
                "{\"status\":\"skipped\",\"code\":\"HITL_PAUSE_INTERRUPTED_BATCH\"}"));
    }

    @Test
    void prepare_annotates_historical_when_cache_missing() {
        String in = compactLegacyJson("missing-cache");
        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent("conv-rh", in);
        assertNotNull(out);
        JSONObject o = new JSONObject(out);
        assertTrue(o.getBoolean(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
        assertFalse(o.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_LIVE_KEY));
    }

    @Test
    void prepare_annotates_live_when_cache_present() throws Exception {
        AgentToolContext.setConversationId("conv-live");
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition fd = new FieldDefinition();
        fd.setName("a");
        fd.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(fd);
        InfoTable t = new InfoTable(shape);
        ValueCollection vr = new ValueCollection();
        vr.put("a", new StringPrimitive("x"));
        t.addRow(vr);
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(t);
        String in = compactLegacyJson(cid);
        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent("conv-live", in);
        assertNotNull(out);
        JSONObject o = new JSONObject(out);
        assertTrue(o.getBoolean(CompactFetchStreamRehydrate.REHYDRATED_CACHE_LIVE_KEY));
        assertFalse(o.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
    }

    @Test
    void payloadFaultDuringContinuityProbe_doesNotAbortConversationRehydrationOrClaimMiss() {
        installOpenFault(ArtifactCacheFaultCode.PAYLOAD_FAULT);

        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent(
                "conv-payload-fault", compactLegacyJson(FAULT_CACHE_ID));

        assertNotNull(out);
        JSONObject parsed = new JSONObject(out);
        assertFalse(parsed.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_LIVE_KEY));
        assertFalse(parsed.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
    }

    @Test
    void repositoryUnavailableDuringContinuityProbe_remainsFatal() {
        installOpenFault(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE);

        ArtifactCacheException ex = assertThrows(ArtifactCacheException.class,
                () -> CompactFetchStreamRehydrate.prepareRehydratedToolContent(
                        "conv-repository-fault", compactLegacyJson(FAULT_CACHE_ID)));

        assertEquals(ArtifactCacheFaultCode.REPOSITORY_UNAVAILABLE, ex.code());
    }

    @Test
    void prepare_numericChartBlockDoesNotResurrectLiveCache_bp9() {
        String in = numericCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_INLINE")
                .put("cacheId", "numeric-restore-cache")
                .put("chartEmitted", true)
                .put("chartBlockPersisted", true)
                .put("chartBlockPointCount", 2)
                .put("chartBlockBytes", 180)
                .put("chartBlock", new JSONObject()
                        .put("kind", "line")
                        .put("series", new org.json.JSONArray()
                                .put(new JSONObject()
                                        .put("name", "speed")
                                        .put("x", new org.json.JSONArray("[\"t1\",\"t2\"]"))
                                        .put("y", new org.json.JSONArray("[1.0,2.0]")))))
                .toString();

        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent("conv-numeric-restore", in);

        assertNotNull(out);
        JSONObject o = new JSONObject(out);
        assertFalse(o.has("chartBlock"));
        assertFalse(o.has("chartBlockPersisted"));
        assertFalse(o.has("chartBlockPointCount"));
        assertFalse(o.has("chartBlockBytes"));
        // BP9: presentation-only — strip chartBlock, annotate historical, never recreate live entry.
        assertFalse(o.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_LIVE_KEY));
        assertTrue(o.getBoolean(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
        assertNull(InvokeServiceExecutor.lookupCachedInfotableForConversation(
                "conv-numeric-restore", "numeric-restore-cache"));
    }

    private static void installOpenFault(ArtifactCacheFaultCode code) {
        TabularArtifactHub.setTestArtifactCache(new ArtifactCache() {
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
        });
    }

    @Test
    void prepare_numericChartBlockBudgetOmittedDoesNotRestoreCache() {
        String in = numericCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_INLINE")
                .put("cacheId", "numeric-budget-omitted")
                .put("chartEmitted", true)
                .put("chartBlockPersisted", false)
                .put("chartBlockOmittedReason", "storage_budget_exceeded")
                .put("chartBlockPointCount", 5000)
                .put("chartBlockBytes", 300000)
                .toString();

        String out = CompactFetchStreamRehydrate.prepareRehydratedToolContent("conv-numeric-budget", in);

        assertNotNull(out);
        JSONObject o = new JSONObject(out);
        assertFalse(o.has("chartBlock"));
        assertFalse(o.has("chartBlockPersisted"));
        assertFalse(o.has("chartBlockOmittedReason"));
        assertFalse(o.has("chartBlockPointCount"));
        assertFalse(o.has("chartBlockBytes"));
        assertFalse(o.has(CompactFetchStreamRehydrate.REHYDRATED_CACHE_LIVE_KEY));
        assertTrue(o.getBoolean(CompactFetchStreamRehydrate.REHYDRATED_CACHE_HISTORICAL_KEY));
        assertNull(InvokeServiceExecutor.lookupCachedInfotableForConversation(
                "conv-numeric-budget", "numeric-budget-omitted"));
    }

    private static String compactLegacyJson(String cacheId) {
        return "{\"status\":\"success\",\"cacheId\":\"" + cacheId + "\",\"sampleOnly\":true,"
                + "\"columns\":[{\"name\":\"a\",\"baseType\":\"STRING\"}],\"rows\":[{\"a\":\"1\"}]}";
    }

    private static JSONObject numericCompact(String format, String resultKind) {
        JSONObject o = new JSONObject();
        o.put(FetchCachedCompactPersistFormat.FORMAT_KEY, format);
        o.put("status", "success");
        o.put("resultKind", resultKind);
        o.put("cacheId", "numeric-cache");
        o.put("columns", new org.json.JSONArray("[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}]"));
        return o;
    }

    private static JSONObject valueStreamCompact(String format) {
        JSONObject o = new JSONObject();
        o.put(FetchCachedCompactPersistFormat.FORMAT_KEY, format);
        o.put("status", "success");
        o.put("resultKind", InvokeServiceExecutor.RESULT_KIND_VALUE_STREAM_HISTORY_INLINE);
        o.put("cacheId", "vs-cache");
        o.put("chartEmitted", false);
        o.put("columns", new org.json.JSONArray("[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"STRING\"}]"));
        return o;
    }
}
