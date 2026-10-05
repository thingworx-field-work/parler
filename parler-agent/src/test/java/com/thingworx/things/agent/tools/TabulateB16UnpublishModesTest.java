package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * B16: unpublished modes leave the advertised schema but remain executable as replay aliases.
 */
class TabulateB16UnpublishModesTest {

    TabulateB16UnpublishModesTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Word-boundary tokens so {@code filter_sort_topn} does not false-flag {@code sort_topn}. */
    private static final Pattern RETIRED_MODE_TOKEN = Pattern.compile(
            "\\b(sort_topn|group_count|group_aggregate)\\b");

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void advertisedModeEnumOmitsUnpublishedAliases() {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> mode = (Map<String, Object>) props.get("mode");
        @SuppressWarnings("unchecked")
        List<String> enumVals = (List<String>) mode.get("enum");
        assertEquals(List.of("filter_count", "filter_rows", "filter_sort_topn", "group_metric",
                "bin_numeric", "box_summary", "union_rows",
                "exact_join", "quality", "resample", "rolling", "rate_of_change", "period_compare",
                "counter_delta", "rolling_stats", "time_weighted", "calendar_bucket"),
                enumVals);
        assertFalse(props.containsKey("aggregateColumn"), "alias-only properties must not be advertised");
        assertFalse(props.containsKey("fn"), "alias-only properties must not be advertised");
    }

    @Test
    void completeAdvertisedSchemaOmitsRetiredModeTokens() throws Exception {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        String schemaJson = MAPPER.writeValueAsString(schema);
        assertFalse(RETIRED_MODE_TOKEN.matcher(schemaJson).find(),
                "advertised parameters schema must not contain retired mode tokens: " + schemaJson);

        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        ToolDefinition tabulate = reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName()))
                .findFirst()
                .orElseThrow();
        String toolSurface = tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
        assertFalse(RETIRED_MODE_TOKEN.matcher(toolSurface).find(),
                "advertised tabulate tool surface must not contain retired mode tokens: " + toolSurface);
    }

    @Test
    void replayAliasSortTopnStillExecutes() throws Exception {
        AgentToolContext.setConversationId("b16-sort");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(twoRowTable());
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cid + "\",\"mode\":\"sort_topn\","
                                + "\"sorts\":[{\"fieldName\":\"n\",\"isAscending\":false}],\"maxItems\":1}"));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
    }

    @Test
    void replayAliasGroupCountStillExecutes() throws Exception {
        AgentToolContext.setConversationId("b16-gc");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(twoRowTable());
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_count\",\"groupBy\":\"name\"}"));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
    }

    @Test
    void replayAliasGroupAggregateStillExecutesWithoutAdvertisedProps() throws Exception {
        AgentToolContext.setConversationId("b16-ga");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(twoRowTable());
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_aggregate\","
                                + "\"groupBy\":\"name\",\"aggregateColumn\":\"n\",\"fn\":\"sum\"}"));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
    }

    private static InfoTable twoRowTable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        FieldDefinition n = new FieldDefinition();
        n.setName("n");
        n.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(n);
        InfoTable t = new InfoTable(shape);
        ValueCollection r1 = new ValueCollection();
        r1.put("name", new StringPrimitive("a"));
        r1.put("n", new NumberPrimitive(1));
        t.addRow(r1);
        ValueCollection r2 = new ValueCollection();
        r2.put("name", new StringPrimitive("b"));
        r2.put("n", new NumberPrimitive(2));
        t.addRow(r2);
        return t;
    }
}
