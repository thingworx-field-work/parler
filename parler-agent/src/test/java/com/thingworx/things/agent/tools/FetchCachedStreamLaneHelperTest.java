package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;

class FetchCachedStreamLaneHelperTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    @Test
    void merge_export_sidecar_copies_from_full_augmented_body() {
        String compact = "{\"status\":\"success\",\"cacheId\":\"c1\",\"sampleOnly\":true,\"rows\":[]}";
        String fullAug = "{\"status\":\"success\",\"cacheId\":\"c1\",\"rows\":[1,2],"
                + "\"_parlerTableExport\":{\"exportStatus\":\"ok\",\"exportFile\":\"f.csv\",\"exportRepository\":\"r\","
                + "\"exportDownloadUrl\":\"u\",\"exportMessage\":\"m\"}}";
        String merged = FetchCachedStreamLaneHelper.mergeExportSidecarOntoCompactToolJson(compact, fullAug);
        JSONObject o = new JSONObject(merged);
        assertTrue(o.has("_parlerTableExport"));
        assertEquals("ok", o.getJSONObject("_parlerTableExport").getString("exportStatus"));
        assertEquals("f.csv", o.getJSONObject("_parlerTableExport").getString("exportFile"));
    }

    @Test
    void persistUsesCompact_fullDownlinkUsesSingleAugmentedFullBody() throws Exception {
        AgentToolContext.setConversationId("lane-test");
        AgentToolContext.setParlerStreamIds("r1", null);
        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey("lane-test", "r1"));
        String compact =
                "{\"status\":\"success\",\"cacheId\":\"c1\",\"sampleOnly\":true,\"rowsOmitted\":true,"
                        + "\"returnedRows\":25,\"rows\":[{\"a\":1}],\"hint\":\"x\"}";
        String full =
                "{\"status\":\"success\",\"cacheId\":\"c1\",\"returnedRows\":25,\"rows\":"
                        + "[{\"a\":1},{\"a\":2}],\"totalRows\":25}";
        AgentToolContext.setFetchCachedStreamJsonForToolCall("tid-1", full);
        ChatMessage compactMsg = ChatMessage.toolResult("tid-1", compact);
        ChatMessage mPersist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                compactMsg, null, "r1", "lane-test");
        assertTrue(mPersist.getContent().contains("\"sampleOnly\":true"));
        JSONObject persisted = new JSONObject(mPersist.getContent());
        assertEquals(FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1,
                persisted.getString(FetchCachedCompactPersistFormat.FORMAT_KEY));

        assertNotNull(AgentToolContext.peekFetchCachedStreamJsonForToolCall("tid-1"));
        ChatMessage mUi = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                compactMsg, mPersist, null, "r1", "lane-test");
        JSONObject uiRoot = new JSONObject(mUi.getContent());
        assertEquals(2, uiRoot.getJSONArray("rows").length());
        assertNull(AgentToolContext.takeFetchCachedFullAugmentedForDownlink("tid-1"));
    }

    @Test
    void augmentPeekBranch_nullCompactBody_noNpe() {
        AgentToolContext.setConversationId("lane-null");
        AgentToolContext.setParlerStreamIds("r1", null);
        FetchCachedReplayGuard.beginTurn(FetchCachedReplayGuard.turnKey("lane-null", "r1"));
        String full =
                "{\"status\":\"success\",\"cacheId\":\"c1\",\"returnedRows\":1,\"rows\":[{\"a\":1}],\"totalRows\":1}";
        AgentToolContext.setFetchCachedStreamJsonForToolCall("tid-null", full);
        ChatMessage compactNull = ChatMessage.toolResult("tid-null", null);
        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                compactNull, null, "r1", "lane-null");
        assertEquals("tid-null", out.getToolCallId());
        assertNull(out.getContent());
    }

    @Test
    void augmentToolForParlerStreamPersist_nonFetchToolWithCompactLikeShape_isNotStamped() {
        String body = "{\"status\":\"success\",\"cacheId\":\"some-cid\",\"sampleOnly\":true,"
                + "\"customExtendedToolField\":\"foo\"}";
        ChatMessage tool = ChatMessage.toolResult("call_other", body);
        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                tool, null, "rid", "cid");
        String persisted = out.getContent();
        assertFalse(
                persisted.contains(FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1),
                "Non-fetch tool body must not be stamped as compact fetch evidence");
    }

    @Test
    void augmentToolForParlerStreamPersist_nonFetchGenericEgressLaneDoesNotRunFetchPersistPath() {
        String body = "{\"status\":\"success\",\"cacheId\":\"some-cid\",\"sampleOnly\":true,"
                + "\"rowsOmitted\":true,\"customExtendedToolField\":\"foo\"}";
        AgentToolContext.setToolEgressFullJsonForToolCall("call_other", "{\"status\":\"success\",\"rows\":[{\"x\":1}]}");
        ChatMessage tool = ChatMessage.toolResult("call_other", body, "query_property_history");
        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                tool, null, "rid", "cid");
        String persisted = out.getContent();
        assertEquals(body, persisted);
        assertFalse(
                persisted.contains(FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1),
                "Generic egress tool body must not be stamped as compact fetch evidence");
    }

    @Test
    void augmentToolForParlerStreamPersist_numericCompactEmbedsChartBlockFromGenericFullBody() {
        String compact = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_INLINE\",\"thingName\":\"Thing1\",\"propertyName\":\"speed\","
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],\"sampleRows\":[],"
                + "\"cacheId\":\"numeric-cache\",\"chartEmitted\":true}";
        String full = "{\"status\":\"success\",\"thingName\":\"Thing1\",\"propertyName\":\"speed\","
                + "\"chart_kind\":\"line\",\"points\":["
                + "{\"timestamp\":\"2026-06-05T00:00:00Z\",\"value\":1.0},"
                + "{\"timestamp\":\"2026-06-05T00:01:00Z\",\"value\":2.0}]}";
        AgentToolContext.setToolEgressFullJsonForToolCall("call_numeric", full);
        ChatMessage tool = ChatMessage.toolResult("call_numeric", compact, "query_property_history");

        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                tool, null, "rid", "cid");

        JSONObject persisted = new JSONObject(out.getContent());
        assertFalse(persisted.has("points"));
        assertTrue(persisted.has("chartBlock"));
        assertTrue(persisted.getBoolean("chartBlockPersisted"));
        assertEquals(2, persisted.getInt("chartBlockPointCount"));
        assertEquals(2, persisted.getJSONObject("chartBlock")
                .getJSONArray("series").getJSONObject(0).getJSONArray("y").length());
        assertFalse(persisted.getJSONObject("chartBlock").has("chartId"));
    }

    @Test
    void augmentToolForParlerStreamPersist_numericCompactOmitsChartBlockWhenStorageBudgetExceeded() {
        String compact = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_INLINE\",\"thingName\":\"Thing1\",\"propertyName\":\"speed\","
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],\"sampleRows\":[],"
                + "\"cacheId\":\"numeric-cache\",\"chartEmitted\":true}";
        StringBuilder points = new StringBuilder();
        points.append("[");
        String longTimestamp = "2026-06-05T00:00:00Z".repeat(60);
        for (int i = 0; i < 5000; i++) {
            if (i > 0) {
                points.append(",");
            }
            points.append("{\"timestamp\":\"").append(longTimestamp).append(i).append("\",\"value\":")
                    .append(i).append(".0}");
        }
        points.append("]");
        String full = "{\"status\":\"success\",\"thingName\":\"Thing1\",\"propertyName\":\"speed\","
                + "\"chart_kind\":\"line\",\"points\":" + points + "}";
        AgentToolContext.setToolEgressFullJsonForToolCall("call_numeric_large", full);
        ChatMessage tool = ChatMessage.toolResult("call_numeric_large", compact, "query_property_history");

        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                tool, null, "rid", "cid");

        JSONObject persisted = new JSONObject(out.getContent());
        assertFalse(persisted.has("chartBlock"));
        assertFalse(persisted.getBoolean("chartBlockPersisted"));
        assertEquals("storage_budget_exceeded", persisted.getString("chartBlockOmittedReason"));
        assertEquals(5000, persisted.getInt("chartBlockPointCount"));
        assertTrue(persisted.getInt("chartBlockBytes") > FetchCachedStreamLaneHelper.NUMERIC_CHARTBLOCK_PERSIST_MAX_BYTES);
        assertEquals(FetchCachedStreamLaneHelper.NUMERIC_CHARTBLOCK_PERSIST_MAX_POINTS,
                persisted.getInt("chartBlockPointLimit"));
        assertEquals(FetchCachedStreamLaneHelper.NUMERIC_CHARTBLOCK_PERSIST_MAX_BYTES,
                persisted.getInt("chartBlockByteLimit"));
    }

    @Test
    void genericEgressDownlinkUsesFullBodyWithoutFetchCachedRegistration() {
        String compact = "{\"status\":\"success\",\"sampleOnly\":true,\"rowsOmitted\":true,\"rows\":[{\"x\":1}]}";
        String full = "{\"status\":\"success\",\"rows\":[{\"x\":1},{\"x\":2}]}";
        AgentToolContext.setToolEgressFullJsonForToolCall("call_generic", full);
        ChatMessage compactMsg = ChatMessage.toolResult("call_generic", compact, "query_property_history");
        ChatMessage persist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                compactMsg, null, "rid", "cid");

        assertNull(AgentToolContext.peekFetchCachedStreamJsonForToolCall("call_generic"));
        assertEquals(compact, persist.getContent());
        ChatMessage ui = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                compactMsg, persist, null, "rid", "cid");
        assertEquals(2, new JSONObject(ui.getContent()).getJSONArray("rows").length());
        assertNull(AgentToolContext.peekToolEgressFullJsonForToolCall("call_generic"));
    }

    @Test
    void genericEgressDownlinkDoesNotUseFullNumericBodyWhenChartSuppressed() {
        String compact = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_AGGREGATES\",\"columns\":[{\"name\":\"timestamp\","
                + "\"baseType\":\"STRING\"},{\"name\":\"value\",\"baseType\":\"NUMBER\"}],"
                + "\"sampleRows\":[],\"chartEmitted\":false}";
        String full = "{\"status\":\"success\",\"points\":[{\"timestamp\":\"t\",\"value\":1.0}]}";
        AgentToolContext.setToolEgressFullJsonForToolCall("call_numeric_suppressed", full);
        ChatMessage compactMsg = ChatMessage.toolResult(
                "call_numeric_suppressed", compact, "query_property_history");
        ChatMessage persist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                compactMsg, null, "rid", "cid");

        ChatMessage ui = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                compactMsg, persist, null, "rid", "cid");

        assertEquals(persist.getContent(), ui.getContent());
        assertNull(AgentToolContext.peekToolEgressFullJsonForToolCall("call_numeric_suppressed"));
    }

    @Test
    void fetchCachedFullBodyWinsWhenBothFetchAndGenericSlotsExist() {
        String compact = "{\"status\":\"success\",\"cacheId\":\"c1\",\"sampleOnly\":true,\"rowsOmitted\":true,"
                + "\"rows\":[{\"compact\":1}]}";
        String fetchFull = "{\"status\":\"success\",\"cacheId\":\"c1\",\"rows\":[{\"fetchFull\":1},{\"fetchFull\":2}]}";
        String genericFull = "{\"status\":\"success\",\"rows\":[{\"genericFull\":1}]}";
        AgentToolContext.setFetchCachedStreamJsonForToolCall("call_fetch", fetchFull);
        AgentToolContext.setToolEgressFullJsonForToolCall("call_fetch", genericFull);
        ChatMessage compactMsg = ChatMessage.toolResult("call_fetch", compact, "fetch_cached_result");
        ChatMessage persist = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                compactMsg, null, "rid", "cid");

        ChatMessage ui = FetchCachedStreamLaneHelper.resolveToolMessageForParlerTableDownlinks(
                compactMsg, persist, null, "rid", "cid");

        JSONObject root = new JSONObject(ui.getContent());
        assertTrue(root.getJSONArray("rows").getJSONObject(0).has("fetchFull"));
        assertEquals(genericFull, AgentToolContext.peekToolEgressFullJsonForToolCall("call_fetch"));
    }

    @Test
    void augmentToolForParlerStreamPersist_nullToolContent_noThrow() {
        ChatMessage tool = ChatMessage.toolResult("tid-null", null);
        ChatMessage out = FetchCachedStreamLaneHelper.augmentToolForParlerStreamPersist(
                tool, null, "rid", "cid");
        assertEquals("tid-null", out.getToolCallId());
        assertNull(out.getContent());
    }
}
