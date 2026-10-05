package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class AgentMessageStreamHistoryExporterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ValueCollection toolRow(String content) {
        ValueCollection row = new ValueCollection();
        row.put("role", new StringPrimitive("tool"));
        row.put("content", new StringPrimitive(content));
        return row;
    }

    private static ValueCollection toolRowWithExecutedName(String content, String executedToolName) {
        ValueCollection row = toolRow(content);
        row.put(AgentMessageStreamAppender.FIELD_EXECUTED_TOOL_NAME, new StringPrimitive(executedToolName));
        return row;
    }

    private static String minimalInfotableJson(String thingName) {
        JSONArray cols = new JSONArray();
        cols.put(new JSONObject().put("name", "n").put("baseType", "NUMBER"));
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("n", 1));
        return new JSONObject()
                .put("status", "success")
                .put("resultKind", "INFOTABLE")
                .put("rowCount", 1)
                .put("thingName", thingName)
                .put("columns", cols)
                .put("rows", rows)
                .toString();
    }

    /** C3b-1 GR-6: {@code groups[]} comes from the final assistant row's {@code chartGroupsJson}, else the declaration. */
    @Test
    void groupsFromTail_finalManifestField_elseDeclarationConvergedToTurnIncomplete_elseNone() throws Exception {
        String finalManifest = "[{\"groupId\":\"g1\",\"revision\":3,\"title\":\"T\",\"layout\":\"auto\",\"final\":true,"
                + "\"members\":[{\"key\":\"a\",\"order\":0,\"name\":\"A\",\"expectedType\":\"chart\",\"state\":\"ready\",\"chartId\":\"c1\"},"
                + "{\"key\":\"b\",\"order\":1,\"name\":\"B\",\"expectedType\":\"chart\",\"state\":\"no-data\",\"code\":\"EMPTY_AFTER_FILTER\"}],"
                + "\"summary\":{\"expected\":2,\"ready\":1,\"noData\":1,\"error\":0,\"cancelled\":0,\"final\":true}}]";
        ValueCollection assistant = new ValueCollection();
        assistant.put("role", new StringPrimitive("assistant"));
        assistant.put("content", new StringPrimitive("done"));
        assistant.put(AgentMessageStreamAppender.FIELD_CHART_GROUPS_JSON, new StringPrimitive(finalManifest));
        String declared = "{\"status\":\"success\",\"code\":\"CHART_GROUP_DECLARED\",\"groupId\":\"g1\",\"revision\":1,\"title\":\"T\","
                + "\"layout\":\"auto\",\"memberKeys\":[\"a\",\"b\"],\"members\":[{\"key\":\"a\",\"name\":\"A\",\"order\":0},{\"key\":\"b\",\"name\":\"B\",\"order\":1}]}";
        ArrayNode fromField = AgentMessageStreamHistoryExporter.groupsFromTail(List.of(toolRow(declared), assistant));
        assertEquals(1, fromField.size());
        assertEquals(3, fromField.get(0).get("revision").asInt(), "the persisted final manifest wins over the declaration");
        assertEquals("ready", fromField.get(0).get("members").get(0).get("state").asText());
        ValueCollection bare = new ValueCollection();
        bare.put("role", new StringPrimitive("assistant"));
        bare.put("content", new StringPrimitive("crashed"));
        ArrayNode rebuilt = AgentMessageStreamHistoryExporter.groupsFromTail(List.of(toolRow(declared), bare));
        assertEquals(1, rebuilt.size());
        JsonNode g = rebuilt.get(0);
        assertEquals("g1", g.get("groupId").asText());
        assertTrue(g.get("final").asBoolean());
        assertEquals(1, g.get("revision").asInt());
        for (JsonNode m : g.get("members")) {
            assertEquals("error", m.get("state").asText());
            assertEquals("TURN_INCOMPLETE", m.get("code").asText());
        }
        assertEquals(2, g.get("summary").get("error").asInt());
        assertEquals("B", g.get("members").get(1).get("name").asText());
        ArrayNode none = AgentMessageStreamHistoryExporter.groupsFromTail(List.of(toolRow("{\"status\":\"success\"}"), bare));
        assertEquals(0, none.size());
    }

    /**
     * C3b-1 GR-6, end to end: every {@code ready} member of an exported group must resolve to a chart in the same
     * row's {@code charts[]}. The first live replay showed "2 / 2 ready" with a loading placeholder per member and
     * both charts outside the card, because the exporter dropped {@code chartId} while {@code groups[]} kept it.
     */
    @Test
    void exportedReadyMembersResolveToExportedChartsByChartId() throws Exception {
        String pie1 = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartId\":\"c1\",\"chartBlock\":{\"kind\":\"pie\","
                + "\"chartId\":\"c1\",\"title\":\"A\",\"series\":[{\"name\":\"s\",\"x\":[\"Run\",\"Down\"],\"y\":[60,40]}]}}";
        String pie2 = pie1.replace("\"c1\"", "\"c2\"").replace("\"title\":\"A\"", "\"title\":\"B\"");
        String finalManifest = "[{\"groupId\":\"g1\",\"revision\":4,\"title\":\"T\",\"layout\":\"auto\",\"final\":true,"
                + "\"members\":[{\"key\":\"a\",\"order\":0,\"name\":\"A\",\"expectedType\":\"chart\",\"state\":\"ready\",\"chartId\":\"c1\"},"
                + "{\"key\":\"b\",\"order\":1,\"name\":\"B\",\"expectedType\":\"chart\",\"state\":\"ready\",\"chartId\":\"c2\"}],"
                + "\"summary\":{\"expected\":2,\"ready\":2,\"noData\":0,\"error\":0,\"cancelled\":0,\"final\":true}}]";
        ValueCollection assistant = new ValueCollection();
        assistant.put("role", new StringPrimitive("assistant"));
        assistant.put("content", new StringPrimitive("done"));
        assistant.put(AgentMessageStreamAppender.FIELD_CHART_GROUPS_JSON, new StringPrimitive(finalManifest));
        List<ValueCollection> tail = List.of(toolRow(pie1), toolRow(pie2), assistant);

        JsonNode charts = AgentMessageStreamHistoryExporter.chartsFromToolRows(tail);
        ArrayNode groups = AgentMessageStreamHistoryExporter.groupsFromTail(tail);

        java.util.Set<String> chartIds = new java.util.HashSet<>();
        for (JsonNode c : charts) {
            assertTrue(chartIds.add(c.path("chartId").asText()), "chart ids are unique within the row");
        }
        assertEquals(java.util.Set.of("c1", "c2"), chartIds);
        int ready = 0;
        for (JsonNode m : groups.get(0).get("members")) {
            if ("ready".equals(m.get("state").asText())) {
                ready++;
                assertTrue(chartIds.contains(m.get("chartId").asText()),
                        "ready member " + m.get("key").asText() + " has no chart to show");
            }
        }
        assertEquals(2, ready);
    }

    /**
     * C3b-2a SC-11 (design §8.7, mandatory verification 2): the two-pie utilization group is declared and built
     * through the real tools, finalised through the real turn-end order, exported by the real exporter from the
     * Stream rows the appender would write, and the resulting {@code ai-parler-history-v1} document is the UI's
     * fixture {@code parler-ui/lib/fixtures/chart-group-shared-colors.history.json}. The committed file must equal
     * the live export, so it can never carry a field the exporter does not produce.
     */
    @Test
    void sc11_sharedColourGroupExportIsTheCommittedUiFixture() throws Exception {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
        try {
            com.thingworx.things.agent.tools.AgentToolContext.setConversationId("sc11");
            String declared = com.thingworx.things.agent.tools.DeclareChartGroupExecutor.execute(new com.thingworx.things.agent.llm.ToolCall(
                    "dg", "declare_chart_group", "{\"title\":\"Utilization by device\",\"sharedCategoryDimension\":\"utilization state\","
                            + "\"members\":[{\"key\":\"oven1\",\"name\":\"Oven-01\"},{\"key\":\"oven2\",\"name\":\"Oven-02\"}]}"));
            assertEquals("CHART_GROUP_DECLARED", new JSONObject(declared).getString("code"));
            String chartA = buildPie("oven1", new String[] {"Setup", "Down", "Running"}, new double[] {30, 90, 360});
            String chartB = buildPie("oven2", new String[] {"Running", "Down"}, new double[] {400, 80});
            com.thingworx.things.agent.tools.ChartGroupState group =
                    com.thingworx.things.agent.tools.AgentToolContext.tabularChartRoundState().getChartGroup();
            group.nextManifest(false);
            for (JSONObject chart : com.thingworx.things.agent.tools.AgentToolContext.drainPendingParlerChartBlocks()) {
                group.nextManifest(false);
                assertTrue(group.onChartDownlinked(chart.getString("chartId")));
                group.nextManifest(false);
            }
            ChartGroupTurnHooks.captureBeforeContextClear();
            com.thingworx.things.agent.tools.AgentToolContext.clear();
            ChartGroupTurnHooks.onTurnEnd(false);
            String groupsJson = com.thingworx.things.agent.tools.AgentToolContext.takeChartGroupsJsonForFinalAssistantRow();
            assertTrue(groupsJson != null && !groupsJson.isEmpty(), "the final manifest reaches the Stream row field");

            ValueCollection user = new ValueCollection();
            user.put("role", new StringPrimitive("user"));
            user.put("content", new StringPrimitive("Compare today's utilization of Oven-01 and Oven-02"));
            ValueCollection assistant = new ValueCollection();
            assistant.put("role", new StringPrimitive("assistant"));
            assistant.put("content", new StringPrimitive("Both ovens ran most of the day; Oven-01 also had setup time."));
            assistant.put("assistantMessageId", new StringPrimitive("am-sc11"));
            assistant.put(AgentMessageStreamAppender.FIELD_CHART_GROUPS_JSON, new StringPrimitive(groupsJson));
            List<ValueCollection> rows = List.of(user, toolRowWithExecutedName(declared, "declare_chart_group"),
                    toolRowWithExecutedName(chartA, "build_chart_from_tabular_result"),
                    toolRowWithExecutedName(chartB, "build_chart_from_tabular_result"), assistant);
            String live = AgentMessageStreamHistoryExporter.buildHistoryJson(rows);
            JsonNode liveTree = JSON.readTree(live);
            JsonNode row = liveTree.get("rows").get(1);
            assertEquals("assistant", row.get("kind").asText());
            assertEquals(2, row.get("charts").size());
            JsonNode g = row.get("groups").get(0);
            // The pie builder's default slice policy orders slices by value, so A's block reads Running, Down, Setup;
            // the shared keys follow the emitted block order, as §8.7 states, and B's Running / Down reuse them.
            assertEquals("[\"Running\",\"Down\",\"Setup\"]", g.get("sharedCategories").get("keys").toString());
            assertTrue(g.get("members").get(0).get("colorShared").asBoolean());
            assertTrue(g.get("members").get(1).get("colorShared").asBoolean());
            java.nio.file.Path fixture = java.nio.file.Path.of("..", "parler-ui", "lib", "fixtures", "chart-group-shared-colors.history.json");
            String pretty = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(liveTree) + "\n";
            java.nio.file.Files.writeString(java.nio.file.Path.of("build", "chart-group-shared-colors.history.json"), pretty,
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(java.nio.file.Files.exists(fixture), "commit build/chart-group-shared-colors.history.json as " + fixture);
            // The conversation cache mints a random id per run; it is the only non-deterministic value the exporter
            // writes, so both trees are compared with that one field masked.
            JsonNode committed = JSON.readTree(java.nio.file.Files.readString(fixture, java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(maskSourceCacheIds(liveTree.deepCopy()), maskSourceCacheIds(committed.deepCopy()),
                    "the committed UI fixture must equal the live export; copy build/chart-group-shared-colors.history.json over it");
        } finally {
            com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
            com.thingworx.things.agent.tools.AgentToolContext.clear();
        }
    }

    private static JsonNode maskSourceCacheIds(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode o = (com.fasterxml.jackson.databind.node.ObjectNode) node;
            if (o.has("sourceCacheId")) {
                o.put("sourceCacheId", "<cache-id>");
            }
            o.fields().forEachRemaining(e -> maskSourceCacheIds(e.getValue()));
        } else if (node.isArray()) {
            node.forEach(AgentMessageStreamHistoryExporterTest::maskSourceCacheIds);
        }
        return node;
    }

    /** A pie member built through the real chart tool from a cached (state, minutes) table, bound to {@code memberKey}. */
    private static String buildPie(String memberKey, String[] states, double[] minutes) throws Exception {
        com.thingworx.metadata.DataShapeDefinition shape = new com.thingworx.metadata.DataShapeDefinition();
        com.thingworx.metadata.FieldDefinition st = new com.thingworx.metadata.FieldDefinition();
        st.setName("state");
        st.setBaseType(com.thingworx.types.BaseTypes.STRING);
        shape.addFieldDefinition(st);
        com.thingworx.metadata.FieldDefinition mi = new com.thingworx.metadata.FieldDefinition();
        mi.setName("minutes");
        mi.setBaseType(com.thingworx.types.BaseTypes.NUMBER);
        shape.addFieldDefinition(mi);
        com.thingworx.types.InfoTable t = new com.thingworx.types.InfoTable(shape);
        for (int i = 0; i < states.length; i++) {
            ValueCollection r = new ValueCollection();
            r.put("state", new StringPrimitive(states[i]));
            r.put("minutes", new com.thingworx.types.primitives.NumberPrimitive(minutes[i]));
            t.addRow(r);
        }
        String cid = com.thingworx.things.agent.tools.InvokeServiceExecutor.storeInfotableInConversationCache(t);
        String out = com.thingworx.things.agent.tools.BuildChartFromTabularResultExecutor.execute(new com.thingworx.things.agent.llm.ToolCall(
                "bc-" + memberKey, "build_chart_from_tabular_result", "{\"source\":\"cache_id\",\"cacheId\":\"" + cid
                        + "\",\"kind\":\"pie\",\"xColumn\":\"state\",\"yColumn\":\"minutes\",\"title\":\"" + memberKey
                        + "\",\"groupMemberKey\":\"" + memberKey + "\"}"));
        assertEquals("CHART_EMITTED", new JSONObject(out).getString("code"), out);
        return out;
    }

    /** §10 T5 — live util path matches history {@code tablesFromToolRows} for same body + row {@code executedToolName}. */
    @Test
    void tablesFromToolRows_parityWithListClassUtil_carrier() throws Exception {
        String body = minimalInfotableJson("ParityThing");
        String etn = "query_alert_history";
        JSONObject util = ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body, etn);
        List<ValueCollection> tail = List.of(toolRowWithExecutedName(body, etn));
        ArrayNode tables = AgentMessageStreamHistoryExporter.tablesFromToolRows(tail);
        assertEquals(1, tables.size());
        assertEquals(util.getString("presentationTitle"), tables.get(0).path("presentationTitle").asText());
    }

    /**
     * §10 T6 — export-window style tail: only a tool row (no assistant row) still reconstructs title when
     * {@code executedToolName} is stamped.
     */
    @Test
    void tablesFromToolRows_toolOnlyTail_selfContainedRow() throws Exception {
        String body = minimalInfotableJson("SoloTool");
        String etn = "query_stream_data";
        List<ValueCollection> tail = List.of(toolRowWithExecutedName(body, etn));
        ArrayNode tables = AgentMessageStreamHistoryExporter.tablesFromToolRows(tail);
        assertEquals(1, tables.size());
        assertEquals("query_stream_data: thingName=SoloTool", tables.get(0).path("presentationTitle").asText());
    }

    /** §10 T8 — unstamped row: valid body {@code tool} still resolves presentation (legacy path). */
    @Test
    void tablesFromToolRows_legacyBodyToolWithoutExecutedName() throws Exception {
        JSONObject o = new JSONObject(minimalInfotableJson("BodT"));
        o.put("tool", "query_alert_summary");
        List<ValueCollection> tail = List.of(toolRow(o.toString()));
        ArrayNode tables = AgentMessageStreamHistoryExporter.tablesFromToolRows(tail);
        assertEquals(1, tables.size());
        assertEquals("query_alert_summary: thingName=BodT", tables.get(0).path("presentationTitle").asText());
    }

    @Test
    void chartsFromToolRows_hydrates_persisted_chart_emitted_blocks_in_order() throws Exception {
        String chartBlock = "{\"kind\":\"pie\",\"title\":\"By region\",\"x_label\":\"Region\",\"y_label\":\"Pct\","
                + "\"series\":[{\"name\":\"s1\",\"x\":[\"A\",\"B\"],\"y\":[40,60]}]}";
        String emitted1 = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":" + chartBlock + "}";
        String emitted2 = "{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":"
                + "{\"kind\":\"bar\",\"title\":\"Second\",\"x_label\":\"X\",\"y_label\":\"Y\","
                + "\"series\":[{\"name\":\"s1\",\"x\":[\"1\"],\"y\":[1]}]}}";
        List<ValueCollection> tail = new ArrayList<>();
        tail.add(toolRow("{\"status\":\"success\",\"code\":\"OTHER\"}"));
        tail.add(toolRow(emitted1));
        tail.add(toolRow(emitted2));

        JsonNode charts = AgentMessageStreamHistoryExporter.chartsFromToolRows(tail);
        assertEquals(2, charts.size());
        assertEquals("pie", charts.get(0).path("kind").asText());
        assertEquals("bar", charts.get(1).path("kind").asText());
        assertFalse(charts.get(0).has("chartId"));
    }

    @Test
    void chartsFromToolRows_hydrates_numeric_compact_chartBlock_onlyWhenChartEmitted() throws Exception {
        String chartBlock = "{\"kind\":\"line\",\"title\":\"speed\",\"x_label\":\"Time\",\"y_label\":\"speed\","
                + "\"chartId\":\"live-id\","
                + "\"series\":[{\"name\":\"speed\",\"x\":[\"t1\",\"t2\"],\"y\":[1.0,2.0]}]}";
        String emitted = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_INLINE\",\"chartEmitted\":true,"
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],\"chartBlock\":" + chartBlock + "}";
        String suppressed = "{\"status\":\"success\",\"$format\":\"parler.numeric_history.compact.v1\","
                + "\"resultKind\":\"NUMERIC_HISTORY_AGGREGATES\",\"chartEmitted\":false,"
                + "\"columns\":[{\"name\":\"timestamp\",\"baseType\":\"STRING\"},"
                + "{\"name\":\"value\",\"baseType\":\"NUMBER\"}],\"chartBlock\":" + chartBlock + "}";

        JsonNode charts = AgentMessageStreamHistoryExporter.chartsFromToolRows(
                List.of(toolRow(emitted), toolRow(suppressed)));

        assertEquals(1, charts.size());
        assertEquals("line", charts.get(0).path("kind").asText());
        assertEquals(2, charts.get(0).path("series").get(0).path("y").size());
        assertFalse(charts.get(0).has("chartId"));
    }

    @Test
    void chartsFromToolRows_skips_malformed_chart_emitted() throws Exception {
        List<ValueCollection> tail = List.of(
                toolRow("{\"status\":\"success\",\"code\":\"CHART_EMITTED\"}"),
                toolRow("{\"status\":\"success\",\"code\":\"CHART_EMITTED\",\"chartBlock\":{\"kind\":\"pie\"}}"));
        JsonNode charts = AgentMessageStreamHistoryExporter.chartsFromToolRows(tail);
        assertEquals(0, charts.size());
    }

    @Test
    void chartsFromToolRows_stripsPlaybookInternalMarkersBeforeHydratingNumericHistory() throws Exception {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("thingName", "T");
        root.put("propertyName", "P");
        JSONArray pts = new JSONArray();
        pts.put(new JSONObject().put("timestamp", "2026-01-01T00:00:00Z").put("value", 1.0));
        root.put("points", pts);
        root.put(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY, true);
        root.put(ParlerPlaybookArtifactWireConstants.PLAYBOOK_NODE_ID_JSON_KEY, "hist[0]");
        JsonNode charts = AgentMessageStreamHistoryExporter.chartsFromToolRows(List.of(toolRow(root.toString())));
        assertEquals(1, charts.size());
        String chartJson = charts.get(0).toString();
        assertFalse(chartJson.contains(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY));
        assertFalse(chartJson.contains(ParlerPlaybookArtifactWireConstants.PLAYBOOK_NODE_ID_JSON_KEY));
    }

    @Test
    void tablesFromToolRows_stripsPlaybookInternalMarkersBeforeHydratingTaxonomyTable() throws Exception {
        JSONArray rows = new JSONArray();
        rows.put(new JSONObject().put("name", "T1").put("n", 3));
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("resultShape", "ImplementedThingsWithTotalCount");
        root.put("resultKind", "ENTITY_TAXONOMY_QUERY_INLINE");
        root.put("totalCount", 1);
        root.put("rootEntityList", rows);
        root.put(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY, true);
        root.put(ParlerPlaybookArtifactWireConstants.PLAYBOOK_NODE_ID_JSON_KEY, "n1");
        String body = root.toString();
        List<ValueCollection> tail = List.of(toolRowWithExecutedName(body, "query_entities_by_taxonomy"));
        ArrayNode tables = AgentMessageStreamHistoryExporter.tablesFromToolRows(tail);
        assertEquals(1, tables.size());
        assertFalse(tables.get(0).toString().contains(ParlerPlaybookArtifactWireConstants.OMIT_FROM_LLM_REHYDRATE_JSON_KEY));
    }
}
