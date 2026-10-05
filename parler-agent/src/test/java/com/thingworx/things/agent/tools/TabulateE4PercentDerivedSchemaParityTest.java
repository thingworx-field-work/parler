package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * E4: advertised {@code derived.op} / {@code derived.groupBy} must match executor support
 * bidirectionally, and percent_* calls shaped like the public schema must execute.
 */
class TabulateE4PercentDerivedSchemaParityTest {

    TabulateE4PercentDerivedSchemaParityTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    @Test
    void advertisedDerivedOpEnumMatchesExecutorSupportBidirectionally() {
        List<String> schemaOps = advertisedDerivedOpEnum();
        assertEquals(CachedTabularGroupMetricExecutor.DERIVED_OPS, schemaOps,
                "schema enum must be the shared DERIVED_OPS constant (order + membership)");
        assertEquals(new LinkedHashSet<>(CachedTabularGroupMetricExecutor.DERIVED_OPS),
                new LinkedHashSet<>(schemaOps));
        assertTrue(schemaOps.contains("percent_of_total"));
        assertTrue(schemaOps.contains("percent_of_group"));
    }

    @Test
    void advertisedDerivedItemPublishesGroupByArrayForPercentOfGroup() {
        Map<String, Object> derivedProps = advertisedDerivedItemProperties();
        assertTrue(derivedProps.containsKey("groupBy"), "E4 requires derived.groupBy on public schema");
        @SuppressWarnings("unchecked")
        Map<String, Object> groupBy = (Map<String, Object>) derivedProps.get("groupBy");
        assertEquals("array", groupBy.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) groupBy.get("items");
        assertEquals("string", items.get("type"));
        String desc = String.valueOf(groupBy.get("description"));
        assertTrue(desc.contains("percent_of_group"), desc);
    }

    @Test
    void publicSchemaShapedPercentOfTotalExecutes() throws Exception {
        AgentToolContext.setConversationId("e4-pot");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(fourRowDevValTable());
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\","
                                + "\"groupBy\":[\"dev\"],"
                                + "\"measures\":[{\"name\":\"tot\",\"op\":\"sum\",\"column\":\"v\"}],"
                                + "\"derived\":[{\"name\":\"pct\",\"op\":\"percent_of_total\",\"input\":\"tot\"}]}"));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
        assertTrue(root.path("derived").toString().contains("pct"), json);
    }

    @Test
    void publicSchemaShapedPercentOfGroupExecutes() throws Exception {
        AgentToolContext.setConversationId("e4-pog");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(fourRowDevValTable());
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("t1", "tabulate_cached_result",
                        "{\"cacheId\":\"" + cid + "\",\"mode\":\"group_metric\","
                                + "\"groupBy\":[\"dev\",\"name\"],"
                                + "\"measures\":[{\"name\":\"v\",\"op\":\"sum\",\"column\":\"v\"}],"
                                + "\"derived\":[{\"name\":\"pg\",\"op\":\"percent_of_group\","
                                + "\"input\":\"v\",\"groupBy\":[\"dev\"]}]}"));
        JsonNode root = MAPPER.readTree(json);
        assertEquals("success", root.path("status").asText(), json);
        assertTrue(root.path("derived").toString().contains("pg"), json);
    }

    @SuppressWarnings("unchecked")
    private static List<String> advertisedDerivedOpEnum() {
        Map<String, Object> derivedProps = advertisedDerivedItemProperties();
        Map<String, Object> op = (Map<String, Object>) derivedProps.get("op");
        return (List<String>) op.get("enum");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> advertisedDerivedItemProperties() {
        Map<String, Object> schema = TabulateCachedResultToolSchema.parametersSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> derived = (Map<String, Object>) props.get("derived");
        Map<String, Object> items = (Map<String, Object>) derived.get("items");
        return (Map<String, Object>) items.get("properties");
    }

    private static InfoTable fourRowDevValTable() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition dev = new FieldDefinition();
        dev.setName("dev");
        dev.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(dev);
        FieldDefinition name = new FieldDefinition();
        name.setName("name");
        name.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(name);
        FieldDefinition v = new FieldDefinition();
        v.setName("v");
        v.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(v);
        InfoTable t = new InfoTable(shape);
        add(t, "a", "x", 10);
        add(t, "a", "y", 30);
        add(t, "b", "x", 20);
        add(t, "b", "y", 40);
        return t;
    }

    private static void add(InfoTable t, String dev, String name, double val) throws Exception {
        ValueCollection row = new ValueCollection();
        row.put("dev", new StringPrimitive(dev));
        row.put("name", new StringPrimitive(name));
        row.put("v", new NumberPrimitive(val));
        t.addRow(row);
    }
}
