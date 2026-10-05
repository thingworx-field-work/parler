package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

import com.thingworx.things.agent.ParlerChartGroupWire;
import com.thingworx.things.agent.llm.ToolCall;

/** C3b-1 (chart-enhancement design §8.5): GR-1 / GR-2 state machine, GR-5 tool validation, the wire frame. */
class ChartGroupStateAndDeclareTest {

    ChartGroupStateAndDeclareTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String declare(String args) {
        return DeclareChartGroupExecutor.execute(new ToolCall("dg", DeclareChartGroupExecutor.TOOL_NAME, args));
    }

    private static InfoTable table() {
        DataShapeDefinition shape = new DataShapeDefinition();
        FieldDefinition x = new FieldDefinition();
        x.setName("cat");
        x.setBaseType(BaseTypes.STRING);
        shape.addFieldDefinition(x);
        FieldDefinition y = new FieldDefinition();
        y.setName("v");
        y.setBaseType(BaseTypes.NUMBER);
        shape.addFieldDefinition(y);
        InfoTable t = new InfoTable(shape);
        for (int i = 0; i < 3; i++) {
            ValueCollection r = new ValueCollection();
            r.put("cat", new StringPrimitive("c" + i));
            r.put("v", new NumberPrimitive((double) (i + 1)));
            t.addRow(r);
        }
        return t;
    }

    private static JSONObject chart(String args) {
        return new JSONObject(BuildChartFromTabularResultExecutor.execute(new ToolCall("bc", "build_chart_from_tabular_result", args)));
    }

    @Test
    void gr5_declarationValidationAndTheOneGroupPerRequestLimit() {
        AgentToolContext.setConversationId("gr5");
        for (String[] c : new String[][] {
                {"{\"title\":\"T\",\"members\":[{\"key\":\"a\",\"name\":\"A\"}]}", "members_count"},
                {"{\"title\":\"T\",\"members\":[" + "{\"key\":\"a\",\"name\":\"A\"},".repeat(6) + "{\"key\":\"g\",\"name\":\"G\"}]}", "members_count"},
                {"{\"title\":\"T\",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"a\",\"name\":\"B\"}]}", "duplicate_key"},
                {"{\"title\":\"T\",\"members\":[{\"key\":\"A B\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"}]}", "member_key"},
                {"{\"title\":\"\",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"}]}", "title"},
                {"{\"title\":\"T\",\"layout\":\"masonry\",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"}]}", "layout"},
                {"{\"title\":\"T\",\"members\":[{\"key\":\"a\",\"name\":\"\"},{\"key\":\"b\",\"name\":\"B\"}]}", "member_name"}}) {
            JSONObject r = new JSONObject(declare(c[0]));
            assertEquals("INVALID_PARAMETERS", r.getString("code"), c[0]);
            assertEquals(c[1], r.getJSONObject("details").getString("reason"), c[0]);
            assertNull(AgentToolContext.tabularChartRoundState().getChartGroup(), "nothing declared on error");
        }
        JSONObject ok = new JSONObject(declare("{\"title\":\"Ovens\",\"members\":[{\"key\":\"temp\",\"name\":\"Temperature\"},{\"key\":\"pres\",\"name\":\"Pressure\"}]}"));
        assertEquals("CHART_GROUP_DECLARED", ok.getString("code"), ok.toString());
        assertEquals("g1", ok.getString("groupId"));
        assertEquals(1, ok.getInt("revision"));
        assertEquals("auto", ok.getString("layout"));
        assertEquals("[\"temp\",\"pres\"]", ok.getJSONArray("memberKeys").toString());
        assertEquals("Temperature", ok.getJSONArray("members").getJSONObject(0).getString("name"));
        JSONObject second = new JSONObject(declare("{\"title\":\"Again\",\"members\":[{\"key\":\"x\",\"name\":\"X\"},{\"key\":\"y\",\"name\":\"Y\"}]}"));
        assertEquals("CHART_GROUP_LIMIT", second.getString("code"));
        // Binding validation on the chart tool: no group (other request), unknown key, member already filled.
        ChartGroupState state = AgentToolContext.tabularChartRoundState().getChartGroup();
        assertNotNull(state);
        assertEquals("unknown_member", state.bindReason("nope"));
        assertNull(state.bindReason("temp"));
        state.assignChartId("temp", "c1");
        assertEquals("member_not_pending", state.bindReason("temp"));
        assertTrue(state.isDirty(), "the declaration is a pending revision");
        JSONObject rev1 = state.nextManifest(false);
        assertEquals(1, rev1.getInt("revision"));
        assertFalse(state.isDirty());
        assertEquals("pending", rev1.getJSONArray("members").getJSONObject(0).getString("state"));
        assertFalse(rev1.getJSONArray("members").getJSONObject(0).has("chartId"), "a bound but not yet downlinked member is still pending without chartId");
    }

    @Test
    void gr1_gr2_boundBuildsReadyOnDownlink_failuresConverge_endTurnFinalises() throws Exception {
        AgentToolContext.setConversationId("gr1");
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(table());
        assertEquals("CHART_GROUP_DECLARED", new JSONObject(declare("{\"title\":\"T\",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"},{\"key\":\"c\",\"name\":\"C\"}]}")).getString("code"));
        ChartGroupState state = AgentToolContext.tabularChartRoundState().getChartGroup();
        JSONObject noGroupKey = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"bar\",\"xColumn\":\"cat\",\"yColumn\":\"v\",\"groupMemberKey\":\"zzz\"}");
        assertEquals("INVALID_PARAMETERS", noGroupKey.getString("code"));
        assertEquals("unknown_member", noGroupKey.getJSONObject("details").getString("reason"));
        JSONObject built = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"bar\",\"xColumn\":\"cat\",\"yColumn\":\"v\",\"groupMemberKey\":\"a\"}");
        assertEquals("CHART_EMITTED", built.getString("code"), built.toString());
        String chartId = built.getString("chartId");
        assertFalse(built.getJSONObject("chartBlock").has("group"), "ChartBlock carries no group field");
        assertEquals("pending", state.member("a").state(), "ready only once the chart frame is downlinked");
        assertEquals(chartId, state.member("a").chartId());
        JSONObject again = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"bar\",\"xColumn\":\"cat\",\"yColumn\":\"v\",\"groupMemberKey\":\"a\"}");
        assertEquals("member_not_pending", again.getJSONObject("details").getString("reason"));
        // A failed bound build converges to error (unknown column) with the originating code.
        JSONObject failed = chart("{\"source\":\"cache_id\",\"cacheId\":\"" + cid + "\",\"kind\":\"bar\",\"xColumn\":\"cat\",\"yColumn\":\"missing\",\"groupMemberKey\":\"b\"}");
        assertEquals("error", failed.getString("status"));
        assertEquals("error", state.member("b").state());
        assertEquals(failed.getString("code"), state.member("b").code());
        assertTrue(state.isDirty());
        JSONObject rev = state.nextManifest(false);
        assertEquals(1, rev.getInt("revision"), "the declaration revision was not sent in this offline test, so this is revision 1");
        // The chart frame downlink turns the member ready; a second downlink of the same id changes nothing.
        assertTrue(state.onChartDownlinked(chartId));
        assertFalse(state.onChartDownlinked(chartId));
        JSONObject rev2 = state.nextManifest(false);
        JSONObject a = rev2.getJSONArray("members").getJSONObject(0);
        assertEquals("ready", a.getString("state"));
        assertEquals(chartId, a.getString("chartId"));
        assertEquals(0, a.getInt("order"));
        assertEquals("chart", a.getString("expectedType"));
        assertEquals("pending", rev2.getJSONArray("members").getJSONObject(2).getString("state"));
        // End of turn: the never-built member converges to MEMBER_NOT_PRODUCED and the final revision is self-consistent.
        state.convergePending(false);
        JSONObject fin = state.nextManifest(true);
        assertTrue(fin.getBoolean("final"));
        assertTrue(state.isFinalSent());
        assertFalse(state.isDirty(), "nothing is dirty after final");
        JSONObject c = fin.getJSONArray("members").getJSONObject(2);
        assertEquals("error", c.getString("state"));
        assertEquals(ChartGroupState.CODE_MEMBER_NOT_PRODUCED, c.getString("code"));
        JSONObject summary = fin.getJSONObject("summary");
        assertEquals(3, summary.getInt("expected"));
        assertEquals(1, summary.getInt("ready"));
        assertEquals(0, summary.getInt("noData"));
        assertEquals(2, summary.getInt("error"));
        assertEquals(0, summary.getInt("cancelled"));
        assertTrue(summary.getBoolean("final"));
        // GR-2 cancelled path and the no-data code set on a fresh state.
        ChartGroupState cancelled = new ChartGroupState("g9", "X", "stack", List.of(new String[]{"p", "P"}, new String[]{"q", "Q"}));
        cancelled.failMember("p", "EMPTY_AFTER_FILTER", "no rows");
        cancelled.convergePending(true);
        JSONObject cfin = cancelled.nextManifest(true);
        assertEquals("no-data", cfin.getJSONArray("members").getJSONObject(0).getString("state"));
        assertEquals("EMPTY_AFTER_FILTER", cfin.getJSONArray("members").getJSONObject(0).getString("code"));
        assertEquals("cancelled", cfin.getJSONArray("members").getJSONObject(1).getString("state"));
        assertEquals(1, cfin.getJSONObject("summary").getInt("cancelled"));
        // Downlink failure of a bound chart ends as DOWNLINK_FAILED.
        ChartGroupState dl = new ChartGroupState("g8", "X", "auto", List.of(new String[]{"p", "P"}, new String[]{"q", "Q"}));
        dl.assignChartId("p", "c7");
        dl.onChartDownlinkFailed("c7");
        dl.convergePending(false);
        assertEquals(ChartGroupState.CODE_DOWNLINK_FAILED, dl.nextManifest(true).getJSONArray("members").getJSONObject(0).getString("code"));
        // The blocked-build note from the loop converges the member with PRESENTATION_ACTION_LIMIT (an error, not no-data).
        BuildChartFromTabularResultExecutor.noteBlockedBuild(new ToolCall("x", "build_chart_from_tabular_result",
                "{\"groupMemberKey\":\"c\"}"), "PRESENTATION_ACTION_LIMIT");
        assertEquals("error", state.member("c").state(), "already terminal members never change");
        ChartGroupState blocked = new ChartGroupState("g7", "X", "auto", List.of(new String[]{"p", "P"}, new String[]{"q", "Q"}));
        blocked.failMember("q", "PRESENTATION_ACTION_LIMIT", null);
        assertEquals("error", blocked.member("q").state());
    }

    private static JSONObject pieBlock(String chartId, String[] labels, double[] values) {
        JSONArray x = new JSONArray();
        JSONArray y = new JSONArray();
        for (int i = 0; i < labels.length; i++) {
            x.put(labels[i]);
            y.put(values[i]);
        }
        return new JSONObject().put("kind", "pie").put("chartId", chartId)
                .put("series", new JSONArray().put(new JSONObject().put("name", "share").put("x", x).put("y", y)));
    }

    private static ChartGroupState sharedGroup(String... keys) {
        List<String[]> members = new java.util.ArrayList<>();
        for (String k : keys) {
            members.add(new String[] {k, k.toUpperCase()});
        }
        return new ChartGroupState("g1", "T", "auto", members, "utilization state");
    }

    @Test
    void sc1_sc2_sc4_sharedKeysAppendInBindOrderAndNeverMove() {
        // SC-1, A first: [Setup, Down, Running] then B [Running, Down].
        ChartGroupState aFirst = sharedGroup("a", "b", "c");
        aFirst.assignChartId("a", "c1");
        aFirst.bindMemberCategories("a", pieBlock("c1", new String[] {"Setup", "Down", "Running"}, new double[] {1, 2, 3}));
        aFirst.assignChartId("b", "c2");
        aFirst.bindMemberCategories("b", pieBlock("c2", new String[] {"Running", "Down"}, new double[] {5, 5}));
        assertEquals(List.of("Setup", "Down", "Running"), aFirst.sharedCategories());
        assertTrue(aFirst.member("a").colorShared());
        assertTrue(aFirst.member("b").colorShared());
        // SC-1, B first: [Running, Down] then A appends Setup at the next free slot.
        ChartGroupState bFirst = sharedGroup("a", "b", "c");
        bFirst.assignChartId("b", "c2");
        bFirst.bindMemberCategories("b", pieBlock("c2", new String[] {"Running", "Down"}, new double[] {5, 5}));
        bFirst.assignChartId("a", "c1");
        bFirst.bindMemberCategories("a", pieBlock("c1", new String[] {"Setup", "Down", "Running"}, new double[] {1, 2, 3}));
        assertEquals(List.of("Running", "Down", "Setup"), bFirst.sharedCategories(), "already assigned slots never change");
        // SC-2: a zero-value slice takes no key; Other is its own key; whitespace and case rules.
        ChartGroupState zero = sharedGroup("a", "b");
        zero.assignChartId("a", "c1");
        zero.bindMemberCategories("a", pieBlock("c1", new String[] {" Running ", "Idle", "Other", "running", "Down  time"},
                new double[] {1, 0, 2, 3, 4}));
        assertEquals(List.of("Running", "Other", "running", "Down time"), zero.sharedCategories(),
                "trim + whitespace collapse, case-sensitive, zero-value slice skipped");
        assertEquals(List.of("Down time"), ChartGroupState.categoryKeys(pieBlock("c9", new String[] {"Down \t time", ""}, new double[] {1, 1})));
        // SC-4: a later member with a new category takes the next free slot; earlier keys keep theirs.
        aFirst.assignChartId("c", "c3");
        aFirst.bindMemberCategories("c", pieBlock("c3", new String[] {"Idle", "Running"}, new double[] {1, 1}));
        assertEquals(List.of("Setup", "Down", "Running", "Idle"), aFirst.sharedCategories());
        // The manifest carries the mapping and the flags; a plain group carries neither.
        JSONObject m = aFirst.nextManifest(false);
        assertEquals("utilization state", m.getJSONObject("sharedCategories").getString("dimension"));
        assertEquals(4, m.getJSONObject("sharedCategories").getJSONArray("keys").length());
        assertTrue(m.getJSONArray("members").getJSONObject(0).getBoolean("colorShared"));
        ChartGroupState plain = new ChartGroupState("g2", "T", "auto", List.of(new String[] {"a", "A"}, new String[] {"b", "B"}));
        plain.assignChartId("a", "c1");
        plain.bindMemberCategories("a", pieBlock("c1", new String[] {"Setup"}, new double[] {1}));
        JSONObject pm = plain.nextManifest(false);
        assertFalse(pm.has("sharedCategories"));
        assertFalse(pm.getJSONArray("members").getJSONObject(0).has("colorShared"));
        assertTrue(plain.sharedCategories().isEmpty());
        // The declaration parameter is validated and echoed.
        AgentToolContext.setConversationId("sc1-declare");
        JSONObject bad = new JSONObject(declare("{\"title\":\"T\",\"sharedCategoryDimension\":\"  \",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"}]}"));
        assertEquals("INVALID_PARAMETERS", bad.getString("code"));
        assertEquals("shared_dimension", bad.getJSONObject("details").getString("reason"));
        JSONObject ok = new JSONObject(declare("{\"title\":\"T\",\"sharedCategoryDimension\":\" utilization state \",\"members\":[{\"key\":\"a\",\"name\":\"A\"},{\"key\":\"b\",\"name\":\"B\"}]}"));
        assertEquals("CHART_GROUP_DECLARED", ok.getString("code"));
        assertEquals("utilization state", ok.getString("sharedCategoryDimension"));
        assertEquals("utilization state", AgentToolContext.tabularChartRoundState().getChartGroup().sharedCategoryDimension());
        Map<String, Object> schema = DeclareChartGroupToolSchema.parametersSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertTrue(props.containsKey("sharedCategoryDimension"));
    }

    @Test
    void sc5_sc7_capLeavesTheLateMemberUnsharedAndNonCategoricalKindsProduceNoKeys() {
        ChartGroupState group = sharedGroup("a", "b", "c");
        String[] labels = new String[23];
        double[] values = new double[23];
        for (int i = 0; i < 23; i++) {
            labels[i] = "k" + i;
            values[i] = 1;
        }
        group.assignChartId("a", "c1");
        group.bindMemberCategories("a", pieBlock("c1", labels, values));
        assertEquals(23, group.sharedCategories().size());
        group.assignChartId("b", "c2");
        group.bindMemberCategories("b", pieBlock("c2", new String[] {"k0", "n1", "n2", "n3"}, new double[] {1, 1, 1, 1}));
        assertEquals(23, group.sharedCategories().size(), "nothing appended when the cap would be exceeded");
        assertFalse(group.member("b").colorShared());
        assertTrue(group.member("a").colorShared(), "other members are untouched");
        group.assignChartId("c", "c3");
        group.bindMemberCategories("c", pieBlock("c3", new String[] {"k1", "n9"}, new double[] {1, 1}));
        assertEquals(24, group.sharedCategories().size(), "a member that fits still appends up to the cap");
        assertTrue(group.member("c").colorShared());
        // SC-7: multi-series bar shares by series name; single-series and the distribution kinds produce nothing.
        JSONObject multi = new JSONObject().put("kind", "bar").put("series", new JSONArray()
                .put(new JSONObject().put("name", "Running").put("x", new JSONArray().put("d")).put("y", new JSONArray().put(1)))
                .put(new JSONObject().put("name", " Down ").put("x", new JSONArray().put("d")).put("y", new JSONArray().put(0))));
        assertEquals(List.of("Running", "Down"), ChartGroupState.categoryKeys(multi), "series names, zero values included");
        JSONObject single = new JSONObject().put("kind", "bar").put("series", new JSONArray()
                .put(new JSONObject().put("name", "Running").put("x", new JSONArray().put("d")).put("y", new JSONArray().put(1))));
        assertTrue(ChartGroupState.categoryKeys(single).isEmpty());
        for (String kind : new String[] {"histogram", "boxplot", "heatmap"}) {
            assertTrue(ChartGroupState.categoryKeys(new JSONObject().put("kind", kind)).isEmpty(), kind);
        }
        ChartGroupState none = sharedGroup("a", "b");
        none.nextManifest(false);
        none.assignChartId("a", "c1");
        none.bindMemberCategories("a", single);
        assertFalse(none.member("a").colorShared());
        assertTrue(none.sharedCategories().isEmpty());
        assertFalse(none.isDirty(), "a member without keys does not dirty the group after the declaration revision");
    }

    @Test
    void wireFrameCarriesTheManifestWithTheChartFrameIds() {
        ChartGroupState state = new ChartGroupState("g1", "T", "grid", List.of(new String[]{"a", "A"}, new String[]{"b", "B"}));
        JSONObject frame = new JSONObject(ParlerChartGroupWire.toWireJson("req-1", "conv-1", state.nextManifest(false)));
        assertEquals("chart_group", frame.getString("type"));
        assertEquals("req-1", frame.getString("request_id"));
        assertEquals("conv-1", frame.getString("conversation_id"));
        JSONObject g = frame.getJSONObject("group");
        assertEquals("g1", g.getString("groupId"));
        assertEquals(1, g.getInt("revision"));
        assertEquals("grid", g.getString("layout"));
        assertFalse(g.getBoolean("final"));
        assertEquals(2, g.getJSONArray("members").length());
        assertEquals(2, g.getJSONObject("summary").getInt("expected"));
        // The approval snapshot carries the group.
        TabularChartRoundState round = new TabularChartRoundState();
        round.setChartGroup(state);
        TabularChartRoundState.Snapshot snap = round.snapshot();
        state.assignChartId("a", "c1");
        TabularChartRoundState restored = new TabularChartRoundState();
        restored.restore(snap);
        assertNotNull(restored.getChartGroup());
        assertNull(restored.getChartGroup().member("a").chartId(), "the snapshot is a private copy");
        assertEquals(1, restored.getChartGroup().revision());
        Map<String, Object> schema = DeclareChartGroupToolSchema.parametersSchema();
        assertEquals("object", schema.get("type"));
        assertTrue(new JSONArray(List.of((Object[]) (String[]) schema.get("required"))).toString().contains("members"));
    }
}
