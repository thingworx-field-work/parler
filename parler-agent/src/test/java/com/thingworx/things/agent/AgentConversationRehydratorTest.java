package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.CompactFetchStreamRehydrate;
import com.thingworx.things.agent.tools.FetchCachedCompactPersistFormat;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class AgentConversationRehydratorTest {

    AgentConversationRehydratorTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void mapAndFilterRows_emits_compact_fetch_as_framed_assistant_prose() {
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "u1", "", "", "Agent"));
        rows.add(streamRow("assistant", "a1", "", "", "Agent"));
        String toolJson = "{\"status\":\"success\",\"cacheId\":\"x\",\"sampleOnly\":true,"
                + "\"columns\":[{\"name\":\"n\",\"baseType\":\"STRING\"}],\"rows\":[{\"n\":\"v\"}]}";
        rows.add(streamRow("tool", toolJson, "tc-1", "", "Agent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-1", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(3, out.size());
        assertEquals(ChatMessage.Role.USER, out.get(0).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(1).getRole());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(2).getRole());
        String prose = out.get(2).getContent();
        assertTrue(prose.startsWith(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX));
        JSONObject body = new JSONObject(prose.substring(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX.length()));
        assertTrue(body.getBoolean("parlerRehydratedCacheHistorical"));
    }

    @Test
    void mapAndFilterRows_skips_raw_tool_json() {
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "u1", "", "", "Agent"));
        String raw = "{\"status\":\"success\",\"cacheId\":\"y\",\"resultKind\":\"INFOTABLE\",\"rows\":[]}";
        rows.add(streamRow("tool", raw, "tc-2", "", "Agent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-2", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(1, out.size());
        assertEquals(ChatMessage.Role.USER, out.get(0).getRole());
    }

    @Test
    void mapAndFilterRows_skips_assistant_with_tool_calls() {
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "u1", "", "", "Agent"));
        rows.add(streamRow("assistant", "", "", "[{\"id\":\"t\",\"name\":\"x\",\"arguments\":\"{}\"}]", "Agent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-3", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(1, out.size());
    }

    @Test
    void mapAndFilterRows_keepsRawSlashDirectiveAndDefensivelySkipsEmptyLegacyUser() {
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "", "", "", "Agent"));
        rows.add(streamRow("user", "/Demo", "", "", "Agent"));
        rows.add(streamRow("assistant", "done", "", "", "Agent"));

        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-slash", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);

        assertEquals(2, out.size());
        assertEquals(ChatMessage.Role.USER, out.get(0).getRole());
        assertEquals("/Demo", out.get(0).getContent());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(1).getRole());
    }

    @Test
    void mapAndFilterRows_accepts_marker_without_columns_array() {
        JSONObject root = new JSONObject();
        root.put(FetchCachedCompactPersistFormat.FORMAT_KEY, FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1);
        root.put("status", "success");
        root.put("cacheId", "z");
        root.put("sampleOnly", true);
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "u", "", "", "Agent"));
        rows.add(streamRow("tool", root.toString(), "tc-m", "", "Agent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-4", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(2, out.size());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(1).getRole());
        String prose = out.get(1).getContent();
        assertTrue(prose.startsWith(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX));
        JSONObject body = new JSONObject(prose.substring(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX.length()));
        assertFalse(body.has("columns"));
    }

    @Test
    void mapAndFilterRows_emits_numeric_history_compact_as_framed_assistant_prose() {
        JSONObject root = numericHistoryCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_AGGREGATES");
        root.put("aggregates", new JSONObject().put("COUNT", 242.0).put("MEAN", 5.35));
        root.put("sampleRows", new org.json.JSONArray());
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "compare statistically", "", "", "Agent"));
        rows.add(streamRow("tool", root.toString(), "tc-num", "", "Agent"));

        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-num", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);

        assertEquals(2, out.size());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(1).getRole());
        String prose = out.get(1).getContent();
        assertTrue(prose.startsWith(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX));
        JSONObject body = new JSONObject(prose.substring(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX.length()));
        assertEquals("parler.numeric_history.compact.v1", body.getString("$format"));
        assertEquals("NUMERIC_HISTORY_AGGREGATES", body.getString("resultKind"));
        assertTrue(body.getBoolean("parlerRehydratedCacheHistorical"));
        assertFalse(body.has("points"));
    }

    @Test
    void mapAndFilterRows_emits_numeric_history_matrix_as_framed_assistant_prose() {
        JSONObject root = numericHistoryCompact("parler.infotable.matrix.v1", "NUMERIC_HISTORY_INLINE");
        root.put("sampleRows", new org.json.JSONArray("[[\"2026-06-05T00:00:00Z\",5.0]]"));
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "trend", "", "", "Agent"));
        rows.add(streamRow("tool", root.toString(), "tc-matrix", "", "Agent"));

        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-matrix", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);

        assertEquals(2, out.size());
        assertEquals(ChatMessage.Role.ASSISTANT, out.get(1).getRole());
        String prose = out.get(1).getContent();
        JSONObject body = new JSONObject(prose.substring(CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX.length()));
        assertEquals("parler.infotable.matrix.v1", body.getString("$format"));
        assertEquals("NUMERIC_HISTORY_INLINE", body.getString("resultKind"));
    }

    @Test
    void mapAndFilterRows_skips_compact_tool_when_agentThing_mismatches() {
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "u1", "", "", "Agent"));
        String toolJson = "{\"status\":\"success\",\"cacheId\":\"x\",\"sampleOnly\":true,"
                + "\"columns\":[{\"name\":\"n\",\"baseType\":\"STRING\"}],\"rows\":[{\"n\":\"v\"}]}";
        rows.add(streamRow("tool", toolJson, "tc-1", "", "OtherAgent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-5", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(1, out.size());
        assertEquals(ChatMessage.Role.USER, out.get(0).getRole());
    }

    @Test
    void mapAndFilterRows_skips_playbook_internal_tool_rows_for_llm_rehydrate() {
        JSONObject root = numericHistoryCompact("parler.numeric_history.compact.v1", "NUMERIC_HISTORY_AGGREGATES");
        root.put("aggregates", new JSONObject().put("COUNT", 1.0));
        root.put("sampleRows", new org.json.JSONArray());
        root.put(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY, true);
        root.put(ParlerPlaybookArtifactWireConstants.PLAYBOOK_NODE_ID_JSON_KEY, "trends[0]");
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(streamRow("user", "compare", "", "", "Agent"));
        rows.add(streamRow("tool", root.toString(), "pb-1", "", "Agent"));
        List<ChatMessage> out = AgentConversationRehydrator.mapAndFilterRows(rows, "conv-pb-skip", "Agent",
                ConversationRehydrateSettings.custom(true, 300, 200_000, false), null);
        assertEquals(1, out.size());
        assertEquals(ChatMessage.Role.USER, out.get(0).getRole());
    }

    private static ValueCollection streamRow(String role, String content, String toolCallId, String toolCalls,
            String agentThing) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("content", new StringPrimitive(content != null ? content : ""));
        vc.put("toolCallId", new StringPrimitive(toolCallId != null ? toolCallId : ""));
        vc.put("toolCalls", new StringPrimitive(toolCalls != null ? toolCalls : ""));
        vc.put("agentThing", new StringPrimitive(agentThing != null ? agentThing : ""));
        return vc;
    }

    private static JSONObject numericHistoryCompact(String format, String resultKind) {
        JSONObject root = new JSONObject();
        root.put(FetchCachedCompactPersistFormat.FORMAT_KEY, format);
        root.put("status", "success");
        root.put("resultKind", resultKind);
        root.put("cacheId", "numeric-cache");
        root.put("columns", new org.json.JSONArray("[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}]"));
        return root;
    }
}
