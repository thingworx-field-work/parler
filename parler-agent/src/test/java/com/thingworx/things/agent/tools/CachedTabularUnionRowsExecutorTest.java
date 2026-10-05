package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

class CachedTabularUnionRowsExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    CachedTabularUnionRowsExecutorTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static InfoTable table(String state, double hours) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "state", BaseTypes.STRING);
        addField(shape, "hours", BaseTypes.NUMBER);
        InfoTable it = new InfoTable(shape);
        ValueCollection row = new ValueCollection();
        row.put("state", new StringPrimitive(state));
        row.put("hours", new NumberPrimitive(hours));
        it.addRow(row);
        return it;
    }

    private static void addField(DataShapeDefinition shape, String name, BaseTypes bt) {
        FieldDefinition fd = new FieldDefinition();
        fd.setName(name);
        fd.setBaseType(bt);
        shape.addFieldDefinition(fd);
    }

    private static JsonNode union(String cidA, String cidB, String labelColumn, String labelA, String labelB)
            throws Exception {
        String args = "{\"mode\":\"union_rows\",\"sourceCacheIds\":[\"" + cidA + "\",\"" + cidB + "\"],"
                + "\"labelColumn\":\"" + labelColumn + "\",\"labelValues\":[\"" + labelA + "\",\"" + labelB + "\"]}";
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("u", "tabulate_cached_result", args)));
    }

    @Test
    void union_rows_appends_tables_with_label_column() throws Exception {
        AgentToolContext.setConversationId("union-1");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(table("Down", 1.0));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(table("Running", 2.0));
        JsonNode out = union(a, b, "day", "2026-09-01", "2026-09-02");
        assertEquals("success", out.path("status").asText(), out.toString());
        assertEquals(2, out.path("totalRows").asInt());
        assertTrue(out.path("unionMeta").path("unionRowsNotDeduped").asBoolean());
        assertEquals("2026-09-01", out.path("rows").get(0).path("day").asText());
        assertEquals("Down", out.path("rows").get(0).path("state").asText());
        assertEquals("2026-09-02", out.path("rows").get(1).path("day").asText());
    }

    @Test
    void union_rows_empty_tables_returns_cached_tabulate_empty() throws Exception {
        AgentToolContext.setConversationId("union-empty");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "state", BaseTypes.STRING);
        InfoTable empty = new InfoTable(shape);
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(empty);
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(empty);
        JsonNode out = union(a, b, "day", "x", "y");
        assertEquals("success", out.path("status").asText(), out.toString());
        assertEquals("CACHED_TABULATE_EMPTY", out.path("resultKind").asText());
        assertEquals(0, out.path("totalRows").asInt());
    }

    @Test
    void union_rows_rejects_column_mismatch() throws Exception {
        AgentToolContext.setConversationId("union-mismatch");
        DataShapeDefinition shape = new DataShapeDefinition();
        addField(shape, "state", BaseTypes.STRING);
        InfoTable left = new InfoTable(shape);
        left.addRow(new ValueCollection());
        DataShapeDefinition shape2 = new DataShapeDefinition();
        addField(shape2, "state", BaseTypes.NUMBER);
        InfoTable right = new InfoTable(shape2);
        right.addRow(new ValueCollection());
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(left);
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(right);
        JsonNode out = union(a, b, "label", "x", "y");
        assertEquals("UNION_COLUMN_MISMATCH", out.path("code").asText());
    }

    private static String storeWithClaimedRows(InfoTable table, long claimedRows) throws Exception {
        com.thingworx.things.agent.source.SourceDescriptor desc =
                com.thingworx.things.agent.source.SourceDescriptor.builder()
                        .sourceRouteId("invoke_service")
                        .rowsExamined(claimedRows)
                        .rowsReturned(claimedRows)
                        .completenessStatus(com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus.UNKNOWN)
                        .build();
        return InvokeServiceExecutor.storeInfotableInConversationCache(table, desc);
    }

    @Test
    void union_rows_rejects_label_column_collision() throws Exception {
        AgentToolContext.setConversationId("union-collision");
        String a = InvokeServiceExecutor.storeInfotableInConversationCache(table("Down", 1.0));
        String b = InvokeServiceExecutor.storeInfotableInConversationCache(table("Running", 2.0));
        JsonNode out = union(a, b, "state", "x", "y");
        assertEquals("LABEL_COLUMN_COLLISION", out.path("code").asText(), out.toString());
        assertTrue(out.path("message").asText().contains("\"state\""), out.toString());
    }

    @Test
    void union_rows_rejects_more_than_max_inputs_before_any_lookup() throws Exception {
        AgentToolContext.setConversationId("union-too-many");
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < CachedTabularToolsExecutor.MAX_UNION_INPUTS + 1; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append("\"never-stored-").append(i).append('"');
        }
        String args = "{\"mode\":\"union_rows\",\"sourceCacheIds\":[" + ids + "],\"labelColumn\":\"day\"}";
        JsonNode out = MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("u", "tabulate_cached_result", args)));
        // None of the 32 ids was ever stored: a lookup before the count check would have answered
        // with a cache miss, so this code proves the count check ran first.
        assertEquals("TOO_MANY_UNION_INPUTS", out.path("code").asText(), out.toString());
    }

    @Test
    void union_rows_rejects_descriptor_row_sum_over_budget_before_reading_tables() throws Exception {
        AgentToolContext.setConversationId("union-too-large");
        long half = CachedTabularToolsExecutor.MAX_SCANNED_ROWS_DECISION / 2 + 1;
        String a = storeWithClaimedRows(table("Down", 1.0), half);
        String b = storeWithClaimedRows(table("Running", 2.0), half);
        JsonNode out = union(a, b, "day", "x", "y");
        // Each stored table holds one row; only the descriptors claim 50,001. A decision taken after
        // reading the tables would have seen two rows and succeeded, so this code proves the
        // descriptor sum was checked before any InfoTable read.
        assertEquals("SOURCE_TOO_LARGE", out.path("code").asText(), out.toString());
    }
}
