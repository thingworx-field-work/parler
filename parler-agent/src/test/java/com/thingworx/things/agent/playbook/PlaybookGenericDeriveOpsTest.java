package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookGenericDeriveOpsTest {

    @Test
    void project_mapsFieldsAndEvidence() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("a", 1).put("b", "x"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("fields", new JSONArray()
                        .put(new JSONObject().put("from", "a").put("as", "A"))
                        .put(new JSONObject().put("from", "b").put("as", "bb")));
        JSONObject out = PlaybookGenericDeriveOps.execute("project", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject row = out.getJSONObject("output").getJSONArray("rows").getJSONObject(0);
        assertEquals(1, row.getInt("A"));
        assertEquals("x", row.getString("bb"));
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("emitted 1 row(s)"));
    }

    @Test
    void project_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("n", 2)))
                .put("fields", new JSONArray().put(new JSONObject().put("from", "n").put("as", "nn")));
        JSONObject out = PlaybookDeriveOps.execute("project", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(2, out.getJSONObject("output").getJSONArray("rows").getJSONObject(0).getInt("nn"));
    }

    @Test
    void project_missingField_emitsJsonNullKey() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("a", 1)))
                .put("fields", new JSONArray().put(new JSONObject().put("from", "missing").put("as", "m")));
        JSONObject out = PlaybookGenericDeriveOps.execute("project", args, ctx);
        JSONObject row = out.getJSONObject("output").getJSONArray("rows").getJSONObject(0);
        assertTrue(row.has("m"));
        assertEquals(JSONObject.NULL, row.get("m"));
    }

    @Test
    void project_defaultAppliedBeforeDropNullOnly_keepsRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject()))
                .put("dropNullOnlyRows", true)
                .put("fields", new JSONArray()
                        .put(new JSONObject().put("from", "x").put("as", "y").put("default", "filled")));
        JSONObject out = PlaybookGenericDeriveOps.execute("project", args, ctx);
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals("filled", out.getJSONObject("output").getJSONArray("rows").getJSONObject(0).getString("y"));
    }

    @Test
    void project_dropNullOnlyRows_skipsWhenAllNullAfterDefaults() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r5", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject()))
                .put("dropNullOnlyRows", true)
                .put("fields", new JSONArray()
                        .put(new JSONObject().put("from", "x").put("as", "y").put("default", JSONObject.NULL)));
        JSONObject out = PlaybookGenericDeriveOps.execute("project", args, ctx);
        assertEquals(0, out.getJSONObject("output").getJSONArray("rows").length());
        String ev = out.getJSONArray("evidenceLines").getString(0);
        assertTrue(ev.contains("emitted 0 row(s)"));
        assertTrue(ev.contains("skipped"));
    }

    @Test
    void project_resolvedObject_wrappedAsSingleRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("r6", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONObject().put("k", 7))
                .put("fields", new JSONArray().put(new JSONObject().put("from", "k").put("as", "kk")));
        JSONObject out = PlaybookGenericDeriveOps.execute("project", args, ctx);
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals(7, out.getJSONObject("output").getJSONArray("rows").getJSONObject(0).getInt("kk"));
    }

    @Test
    void project_nonArrayNonObjectRows_failsClosed() {
        PlaybookRunContext ctx = new PlaybookRunContext("r7", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", "not-rows")
                .put("fields", new JSONArray().put(new JSONObject().put("from", "a").put("as", "b")));
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("project", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void project_inputOverCap_failsClosed() {
        PlaybookRunContext ctx = new PlaybookRunContext("r8", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        int cap = PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS;
        JSONArray rows = new JSONArray();
        for (int i = 0; i < cap + 1; i++) {
            rows.put(new JSONObject().put("n", i));
        }
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("fields", new JSONArray().put(new JSONObject().put("from", "n").put("as", "nn")));
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("project", args, ctx));
        assertEquals("GENERIC_INPUT_TOO_LARGE", ex.failureCode());
    }

    @Test
    void project_nonObjectRowSlot_failsClosed() {
        PlaybookRunContext ctx = new PlaybookRunContext("r9", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("a", 1)).put(JSONObject.NULL))
                .put("fields", new JSONArray().put(new JSONObject().put("from", "a").put("as", "A")));
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("project", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
        assertTrue(ex.getMessage().contains("rows[1]"));
    }

    @Test
    void filter_keepsMatchingRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("f1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", 1))
                .put(new JSONObject().put("k", 2));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("where", new JSONObject().put("op", "eq").put("field", "k").put("right", 2));
        JSONObject out = PlaybookGenericDeriveOps.execute("filter", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals(2, out.getJSONObject("output").getJSONArray("rows").getJSONObject(0).getInt("k"));
        String ev = out.getJSONArray("evidenceLines").getString(0);
        assertTrue(ev.contains("kept 1"));
        assertTrue(ev.contains("dropped 1"));
    }

    @Test
    void filter_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("f2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("x", "a")))
                .put("where", new JSONObject().put("op", "is_present").put("field", "x"));
        JSONObject out = PlaybookDeriveOps.execute("filter", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }

    @Test
    void sort_ordersDescByNumericField() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("s1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", 1))
                .put(new JSONObject().put("k", 3))
                .put(new JSONObject().put("k", 2));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("orderBy", new JSONArray().put(new JSONObject().put("field", "k").put("direction", "desc")));
        JSONObject out = PlaybookGenericDeriveOps.execute("sort", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(3, outRows.getJSONObject(0).getInt("k"));
        assertEquals(2, outRows.getJSONObject(1).getInt("k"));
        assertEquals(1, outRows.getJSONObject(2).getInt("k"));
    }

    @Test
    void sort_stableWhenKeysEqual_preservesInputOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("s2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", 1).put("tag", "first"))
                .put(new JSONObject().put("k", 1).put("tag", "second"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("orderBy", new JSONArray().put(new JSONObject().put("field", "k").put("direction", "asc")));
        JSONObject out = PlaybookGenericDeriveOps.execute("sort", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals("first", outRows.getJSONObject(0).getString("tag"));
        assertEquals("second", outRows.getJSONObject(1).getString("tag"));
    }

    @Test
    void sort_nullsLastAscending() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("s3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", 5))
                .put(new JSONObject().put("k", JSONObject.NULL))
                .put(new JSONObject().put("k", 3));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("orderBy", new JSONArray().put(new JSONObject().put("field", "k").put("direction", "asc")));
        JSONObject out = PlaybookGenericDeriveOps.execute("sort", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(3, outRows.getJSONObject(0).getInt("k"));
        assertEquals(5, outRows.getJSONObject(1).getInt("k"));
        assertEquals(JSONObject.NULL, outRows.getJSONObject(2).opt("k"));
    }

    @Test
    void top_n_selectsFirstNAfterSort() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("t1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("n", 10))
                .put(new JSONObject().put("n", 30))
                .put(new JSONObject().put("n", 20));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("n", 2)
                .put("orderBy", new JSONArray().put(new JSONObject().put("field", "n").put("direction", "desc")));
        JSONObject out = PlaybookGenericDeriveOps.execute("top_n", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(2, outRows.length());
        assertEquals(30, outRows.getJSONObject(0).getInt("n"));
        assertEquals(20, outRows.getJSONObject(1).getInt("n"));
        assertEquals(0, out.getJSONObject("output").getJSONArray("gaps").length());
    }

    @Test
    void top_n_whenFewerRowsThanN_addsGap() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("t2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("n", 1)).put(new JSONObject().put("n", 2));
        JSONObject args = new JSONObject().put("rows", rows).put("n", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("top_n", args, ctx);
        assertEquals(2, out.getJSONObject("output").getJSONArray("rows").length());
        assertTrue(out.getJSONObject("output").getJSONArray("gaps").getString(0).contains("fewer"));
    }

    @Test
    void top_n_withoutOrderBy_preservesInputOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("t3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("n", 3))
                .put(new JSONObject().put("n", 1))
                .put(new JSONObject().put("n", 2));
        JSONObject args = new JSONObject().put("rows", rows).put("n", 2);
        JSONObject out = PlaybookGenericDeriveOps.execute("top_n", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(3, outRows.getJSONObject(0).getInt("n"));
        assertEquals(1, outRows.getJSONObject(1).getInt("n"));
    }

    @Test
    void pick_one_singleMatch_returnsOutputRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("id", "a"))
                .put(new JSONObject().put("id", "b"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("where", new JSONObject().put("op", "eq").put("field", "id").put("right", "b"))
                .put("label", "pick");
        JSONObject out = PlaybookGenericDeriveOps.execute("pick_one", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("b", out.getJSONObject("output").getJSONObject("row").getString("id"));
    }

    @Test
    void pick_one_zeroMatches_needsClarification() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("id", 1)))
                .put("where", new JSONObject().put("op", "eq").put("field", "id").put("right", 2));
        JSONObject out = PlaybookGenericDeriveOps.execute("pick_one", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertTrue(out.getString("message").contains("0"));
        JSONObject output = out.getJSONObject("output");
        assertEquals(JSONObject.NULL, output.get("row"));
        assertEquals(0, output.getInt("totalCount"));
        assertTrue(out.getJSONArray("evidenceLines").length() > 0);
    }

    @Test
    void pick_one_multipleMatches_needsClarification_includesOutputShell() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p2b", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("x", 1)).put(new JSONObject().put("x", 1));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("where", new JSONObject().put("op", "is_present").put("field", "x"))
                .put("onMultiple", "needs_clarification");
        JSONObject out = PlaybookGenericDeriveOps.execute("pick_one", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
        assertEquals(JSONObject.NULL, out.getJSONObject("output").get("row"));
    }

    @Test
    void pick_one_zeroMatches_gapMode() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("id", 1)))
                .put("where", new JSONObject().put("op", "eq").put("field", "id").put("right", 2))
                .put("onZero", "gap");
        JSONObject out = PlaybookGenericDeriveOps.execute("pick_one", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(JSONObject.NULL, out.getJSONObject("output").get("row"));
        assertFalse(out.getJSONObject("output").getJSONArray("gaps").isEmpty());
    }

    @Test
    void pick_one_multiple_firstMode() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("x", 1)).put(new JSONObject().put("x", 1));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("where", new JSONObject().put("op", "is_present").put("field", "x"))
                .put("onMultiple", "first");
        JSONObject out = PlaybookGenericDeriveOps.execute("pick_one", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONObject("row").getInt("x"));
    }

    @Test
    void pick_one_invalidOnZero_throws() {
        PlaybookRunContext ctx = new PlaybookRunContext("p5", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("x", 1)))
                .put("where", new JSONObject().put("op", "eq").put("field", "x").put("right", 2))
                .put("onZero", "not_a_mode");
        assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("pick_one", args, ctx));
    }

    @Test
    void pick_one_requireValidShapeBeforeEvaluate() {
        PlaybookRunContext ctx = new PlaybookRunContext("p6", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("x", 1)))
                .put("where", new JSONObject().put("not", "bad"));
        assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("pick_one", args, ctx));
    }

    @Test
    void group_by_countsPerDistinctKey() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("g1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", "a").put("v", 1))
                .put(new JSONObject().put("k", "a").put("v", 2))
                .put(new JSONObject().put("k", "b").put("v", 3));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("keys", new JSONArray().put("k"))
                .put("maxGroups", 50)
                .put("measures", new JSONArray().put(new JSONObject().put("name", "n").put("op", "count")));
        JSONObject out = PlaybookGenericDeriveOps.execute("group_by", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(2, outRows.length());
        assertEquals(2, out.getJSONObject("output").getInt("totalCount"));
        assertEquals(2, out.getJSONObject("output").getInt("returned"));
    }

    @Test
    void group_by_truncatesDeterministicOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("g2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("k", "c"))
                .put(new JSONObject().put("k", "a"))
                .put(new JSONObject().put("k", "b"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("keys", new JSONArray().put("k"))
                .put("maxGroups", 2)
                .put("measures", new JSONArray().put(new JSONObject().put("name", "n").put("op", "count")));
        JSONObject out = PlaybookGenericDeriveOps.execute("group_by", args, ctx);
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(2, outRows.length());
        assertEquals("a", outRows.getJSONObject(0).getString("k"));
        assertEquals("b", outRows.getJSONObject(1).getString("k"));
        assertTrue(out.getJSONObject("output").getJSONArray("gaps").getString(0).contains("truncated"));
        assertEquals(3, out.getJSONObject("output").getInt("totalCount"));
    }

    @Test
    void group_by_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("g3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("region", "east")))
                .put("keys", new JSONArray().put("region"))
                .put("maxGroups", 10)
                .put("measures", new JSONArray().put(new JSONObject().put("name", "cnt").put("op", "count")));
        JSONObject out = PlaybookDeriveOps.execute("group_by", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }

    @Test
    void group_by_foldOverflowToOther_throws() {
        PlaybookRunContext ctx = new PlaybookRunContext("g4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("k", 1)))
                .put("keys", new JSONArray().put("k"))
                .put("maxGroups", 5)
                .put("foldOverflowToOther", true);
        assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("group_by", args, ctx));
    }

    @Test
    void group_by_measuresPresentButNonArray_throws() {
        PlaybookRunContext ctx = new PlaybookRunContext("g5", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("k", 1)))
                .put("keys", new JSONArray().put("k"))
                .put("maxGroups", 5)
                .put("measures", "not-an-array");
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("group_by", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void group_by_dottedKey_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("g6", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("sensor", new JSONObject().put("id", "x"))))
                .put("keys", new JSONArray().put("sensor.id"))
                .put("maxGroups", 5);
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("group_by", args, ctx));
        assertTrue(ex.getMessage().contains("single identifier"));
    }

    @Test
    void group_by_keyNonJsonString_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("g7", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("k", 1)))
                .put("keys", new JSONArray().put(true))
                .put("maxGroups", 5);
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("group_by", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
        assertTrue(ex.getMessage().contains("JSON string"));
    }

    @Test
    void group_by_measureNameNonJsonString_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("g8", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("k", 1)))
                .put("keys", new JSONArray().put("k"))
                .put("maxGroups", 5)
                .put("measures", new JSONArray().put(new JSONObject().put("name", true).put("op", "count")));
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("group_by", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void aggregate_measureFieldNonJsonString_rejected() {
        PlaybookRunContext ctx = new PlaybookRunContext("g9", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("v", 1)))
                .put("measures", new JSONArray()
                        .put(new JSONObject().put("name", "s").put("op", "sum").put("field", 99)));
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("aggregate", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void aggregate_computesMeasures_scalarOutput() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ag1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("v", 10))
                .put(new JSONObject().put("v", 20));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("measures", new JSONArray()
                        .put(new JSONObject().put("name", "n").put("op", "count"))
                        .put(new JSONObject().put("name", "s").put("op", "sum").put("field", "v"))
                        .put(new JSONObject().put("name", "m").put("op", "mean").put("field", "v")));
        JSONObject out = PlaybookGenericDeriveOps.execute("aggregate", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject o = out.getJSONObject("output");
        assertEquals(2, o.getInt("n"));
        assertEquals(30.0, o.getDouble("s"), 0.0001);
        assertEquals(15.0, o.getDouble("m"), 0.0001);
        assertFalse(o.has("rows"));
        assertEquals(2, o.getInt("totalCount"));
        assertEquals(2, o.getInt("returned"));
    }

    @Test
    void aggregate_emptyInput_numericNullsAndGaps() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ag2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray())
                .put("measures", new JSONArray()
                        .put(new JSONObject().put("name", "n").put("op", "count"))
                        .put(new JSONObject().put("name", "m").put("op", "mean").put("field", "v")));
        JSONObject out = PlaybookGenericDeriveOps.execute("aggregate", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject o = out.getJSONObject("output");
        assertEquals(0, o.getInt("n"));
        assertEquals(JSONObject.NULL, o.get("m"));
        assertTrue(o.getJSONArray("gaps").toString().contains("empty"));
    }

    @Test
    void aggregate_measuresNotArray_throws() {
        PlaybookRunContext ctx = new PlaybookRunContext("ag3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject()))
                .put("measures", new JSONObject());
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("aggregate", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void aggregate_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ag4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("rows", new JSONArray().put(new JSONObject().put("x", 1)))
                .put("measures", new JSONArray().put(new JSONObject().put("name", "c").put("op", "count")));
        JSONObject out = PlaybookDeriveOps.execute("aggregate", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getInt("c"));
    }

    @Test
    void join_by_key_nullKeysDoNotFanOut_inner() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jkN0", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray left = new JSONArray()
                .put(new JSONObject().put("k", JSONObject.NULL).put("t", "a"))
                .put(new JSONObject().put("k", 1).put("t", "b"));
        JSONArray right = new JSONArray()
                .put(new JSONObject().put("k", JSONObject.NULL).put("v", 9))
                .put(new JSONObject().put("k", 1).put("v", 8));
        JSONObject args = new JSONObject()
                .put("left", left)
                .put("right", right)
                .put("leftKey", "k")
                .put("rightKey", "k")
                .put("joinType", "inner")
                .put("rightPrefix", "r_")
                .put("maxRows", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_by_key", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(1, o.getInt("totalCount"));
        assertEquals(1, o.getJSONArray("rows").length());
    }

    @Test
    void join_by_key_inner_evidenceCountsLeftRowsDroppedForNoRightMatch() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jkN1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray left = new JSONArray()
                .put(new JSONObject().put("k", 1))
                .put(new JSONObject().put("k", 2))
                .put(new JSONObject().put("k", 3));
        JSONArray right = new JSONArray().put(new JSONObject().put("k", 1).put("v", 10));
        JSONObject args = new JSONObject()
                .put("left", left)
                .put("right", right)
                .put("leftKey", "k")
                .put("rightKey", "k")
                .put("joinType", "inner")
                .put("rightPrefix", "r_")
                .put("maxRows", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_by_key", args, ctx);
        assertEquals(1, out.getJSONObject("output").getInt("totalCount"));
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("2 inner left row(s) had no right match"));
    }

    @Test
    void join_by_key_left_nullKey_emitsLeftOnlyRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jkN2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray left = new JSONArray().put(new JSONObject().put("tag", "solo"));
        JSONArray right = new JSONArray().put(new JSONObject().put("k", 1).put("v", 1));
        JSONObject args = new JSONObject()
                .put("left", left)
                .put("right", right)
                .put("leftKey", "k")
                .put("rightKey", "k")
                .put("joinType", "left")
                .put("rightPrefix", "r_")
                .put("maxRows", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_by_key", args, ctx);
        JSONArray rows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(1, rows.length());
        assertEquals("solo", rows.getJSONObject(0).getString("tag"));
        assertFalse(rows.getJSONObject(0).has("r_v"));
    }

    @Test
    void join_by_key_inner_duplicateRight_truncatesWithGapAndTotalCount() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jk1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray left = new JSONArray().put(new JSONObject().put("k", 1).put("a", "L"));
        JSONArray right = new JSONArray()
                .put(new JSONObject().put("k", 1).put("v", 1))
                .put(new JSONObject().put("k", 1).put("v", 2));
        JSONObject args = new JSONObject()
                .put("left", left)
                .put("right", right)
                .put("leftKey", "k")
                .put("rightKey", "k")
                .put("joinType", "inner")
                .put("rightPrefix", "r_")
                .put("maxRows", 1);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_by_key", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject o = out.getJSONObject("output");
        assertEquals(2, o.getInt("totalCount"));
        assertEquals(1, o.getInt("returned"));
        assertEquals(1, o.getJSONArray("rows").length());
        assertTrue(o.getJSONArray("gaps").getString(0).contains("truncated"));
    }

    @Test
    void join_by_key_left_includesUnmatchedLeftRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jk2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONArray left = new JSONArray()
                .put(new JSONObject().put("k", 1).put("tag", "a"))
                .put(new JSONObject().put("k", 2).put("tag", "b"));
        JSONArray right = new JSONArray().put(new JSONObject().put("k", 1).put("r", 99));
        JSONObject args = new JSONObject()
                .put("left", left)
                .put("right", right)
                .put("leftKey", "k")
                .put("rightKey", "k")
                .put("joinType", "left")
                .put("rightPrefix", "r_")
                .put("maxRows", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("join_by_key", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONArray rows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(2, rows.length());
        assertEquals(99, rows.getJSONObject(0).getInt("r_r"));
        assertEquals(2, rows.getJSONObject(1).getInt("k"));
        assertFalse(rows.getJSONObject(1).has("r_r"));
    }

    @Test
    void join_by_key_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("jk3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("left", new JSONArray().put(new JSONObject().put("lid", 1)))
                .put("right", new JSONArray().put(new JSONObject().put("lid", 1).put("x", 5)))
                .put("leftKey", "lid")
                .put("rightKey", "lid")
                .put("joinType", "inner")
                .put("rightPrefix", "R_")
                .put("maxRows", 5);
        JSONObject out = PlaybookDeriveOps.execute("join_by_key", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject row = out.getJSONObject("output").getJSONArray("rows").getJSONObject(0);
        assertEquals(5, row.getInt("R_x"));
    }

    @Test
    void build_targets_cartesian_emitsTargetsInDeterministicOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("bt0", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("a", new JSONArray()
                                .put(new JSONObject().put("name", "A1"))
                                .put(new JSONObject().put("name", "A2")))
                        .put("b", new JSONArray()
                                .put(new JSONObject().put("p", "P1"))
                                .put(new JSONObject().put("p", "P2"))))
                .put("template", new JSONObject()
                        .put("x", new JSONObject().put("$path", "a.name"))
                        .put("y", new JSONObject().put("$path", "b.p")))
                .put("maxTargets", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONArray targets = out.getJSONObject("output").getJSONArray("targets");
        assertEquals(4, targets.length());
        assertEquals("A1", targets.getJSONObject(0).getString("x"));
        assertEquals("P1", targets.getJSONObject(0).getString("y"));
        assertEquals("A1", targets.getJSONObject(1).getString("x"));
        assertEquals("P2", targets.getJSONObject(1).getString("y"));
        assertEquals("A2", targets.getJSONObject(2).getString("x"));
        assertEquals("A2", targets.getJSONObject(3).getString("x"));
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("lex order"));
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("2 a x 2 b"));
    }

    @Test
    void build_targets_lexicographicSourceNames_determineCartesianOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("btLex", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("z", new JSONArray()
                                .put(new JSONObject().put("idz", 10))
                                .put(new JSONObject().put("idz", 11)))
                        .put("a", new JSONArray()
                                .put(new JSONObject().put("ida", 1))
                                .put(new JSONObject().put("ida", 2))))
                .put("template", new JSONObject()
                        .put("u", new JSONObject().put("$path", "a.ida"))
                        .put("v", new JSONObject().put("$path", "z.idz")))
                .put("maxTargets", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        JSONArray targets = out.getJSONObject("output").getJSONArray("targets");
        assertEquals(4, targets.length());
        assertEquals(1, targets.getJSONObject(1).getInt("u"));
        assertEquals(11, targets.getJSONObject(1).getInt("v"));
    }

    @Test
    void build_targets_missingPath_emitsGapAndNullField() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("btGap", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("a", new JSONArray().put(new JSONObject().put("name", "only"))))
                .put("template", new JSONObject()
                        .put("k", new JSONObject().put("$path", "a.missing")))
                .put("maxTargets", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(1, o.getJSONArray("targets").length());
        assertEquals(JSONObject.NULL, o.getJSONArray("targets").getJSONObject(0).get("k"));
        JSONArray gaps = o.getJSONArray("gaps");
        assertEquals(1, gaps.length());
        assertTrue(gaps.getString(0).contains("no value from $path"));
    }

    @Test
    void build_targets_emptySource_emitsGapAndZeroTargets() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("btEmp", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("a", new JSONArray())
                        .put("b", new JSONArray().put(new JSONObject().put("x", 1))))
                .put("template", new JSONObject()
                        .put("u", new JSONObject().put("$path", "b.x")))
                .put("maxTargets", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(0, o.getJSONArray("targets").length());
        assertEquals(0, o.getInt("totalCount"));
        assertTrue(o.getJSONArray("gaps").getString(0).contains("zero rows"));
    }

    @Test
    void build_targets_truncatesWithGapWhenProductExceedsMaxTargets() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("bt1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("c", new JSONArray()
                                .put(new JSONObject().put("n", 1))
                                .put(new JSONObject().put("n", 2)))
                        .put("d", new JSONArray()
                                .put(new JSONObject().put("n", 3))
                                .put(new JSONObject().put("n", 4))))
                .put("template", new JSONObject()
                        .put("u", new JSONObject().put("$path", "c.n"))
                        .put("v", new JSONObject().put("$path", "d.n")))
                .put("maxTargets", 2);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(4, o.getInt("totalCount"));
        assertEquals(2, o.getInt("returned"));
        assertEquals(2, o.getJSONArray("targets").length());
        assertTrue(o.getJSONArray("gaps").getString(0).contains("truncated"));
    }

    @Test
    void build_targets_resolvesInputInTemplate() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("bt2", PlaybookIds.CROSS_REGION_HEALTH_ID,
                new JSONObject().put("gapMinutes", 30));
        JSONObject args = new JSONObject()
                .put("sources", new JSONObject()
                        .put("c", new JSONArray().put(new JSONObject().put("n", 1))))
                .put("template", new JSONObject()
                        .put("u", new JSONObject().put("$path", "c.n"))
                        .put("w", new JSONObject().put("$input", "gapMinutes")))
                .put("maxTargets", 5);
        JSONObject out = PlaybookGenericDeriveOps.execute("build_targets", args, ctx);
        assertEquals(1, out.getJSONObject("output").getJSONArray("targets").length());
        assertEquals(30, out.getJSONObject("output").getJSONArray("targets").getJSONObject(0).getInt("w"));
    }

    @Test
    void collect_gaps_mergesRefsInOrderAndDedupesStrings() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cg0", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("n1", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray()
                .put("dup")
                .put("first"))));
        ctx.putNodeOutput("n2", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray()
                .put("dup")
                .put("second"))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("n1.output.gaps").put("n2.output.gaps"))
                .put("maxItems", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject o = out.getJSONObject("output");
        JSONArray gaps = o.getJSONArray("gaps");
        assertEquals(3, gaps.length());
        assertEquals("dup", gaps.getString(0));
        assertEquals("first", gaps.getString(1));
        assertEquals("second", gaps.getString(2));
        assertEquals(3, o.getInt("totalCount"));
        assertEquals(3, o.getInt("returned"));
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("3 merged"));
    }

    @Test
    void collect_gaps_dedupesStructuredGapsByCanonicalKeyOrder() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cg1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject a = new JSONObject().put("message", "same").put("code", "C1");
        JSONObject b = new JSONObject().put("code", "C1").put("message", "same");
        ctx.putNodeOutput("g", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray().put(a).put(b))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("g.output.gaps"))
                .put("maxItems", 10);
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx);
        JSONArray gaps = out.getJSONObject("output").getJSONArray("gaps");
        assertEquals(1, gaps.length());
        JSONObject row = gaps.getJSONObject(0);
        assertEquals("C1", row.getString("code"));
        assertEquals("same", row.getString("message"));
        assertFalse(row.has("detail"));
        assertFalse(row.has("kind"));
    }

    @Test
    void collect_gaps_truncatesToMaxItems() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cg2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("x", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray()
                .put("a")
                .put("b")
                .put("c")
                .put("d")
                .put("e"))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("x.output.gaps"))
                .put("maxItems", 3);
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(5, o.getInt("totalCount"));
        assertEquals(3, o.getInt("returned"));
        assertEquals(3, o.getJSONArray("gaps").length());
        assertTrue(out.getJSONArray("evidenceLines").getString(0).contains("truncated"));
    }

    @Test
    void collect_gaps_rejectsStructuredGapWithIllegalKey() {
        PlaybookRunContext ctx = new PlaybookRunContext("cg3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("x", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray()
                .put(new JSONObject().put("message", "ok").put("extra", "bad")))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("x.output.gaps"))
                .put("maxItems", 5);
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }

    @Test
    void collect_gaps_structuredGap_serializationIncludesAllCanonicalFields() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cgSer", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject gap = new JSONObject().put("message", "m").put("code", "c");
        ctx.putNodeOutput("g", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray().put(gap))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("g.output.gaps"))
                .put("maxItems", 8);
        JSONObject out = PlaybookGenericDeriveOps.execute("collect_gaps", args, ctx);
        String ser = out.getJSONObject("output").getJSONArray("gaps").getJSONObject(0).toString();
        assertTrue(ser.contains("\"code\"") && ser.contains("c"), ser);
        assertTrue(ser.contains("\"message\"") && ser.contains("m"), ser);
    }

    @Test
    void collect_gaps_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("cg4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("z", new JSONObject().put("output", new JSONObject().put("gaps", new JSONArray().put("z1"))));
        JSONObject args = new JSONObject()
                .put("refs", new JSONArray().put("z.output.gaps"))
                .put("maxItems", 8);
        JSONObject out = PlaybookDeriveOps.execute("collect_gaps", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("gaps").length());
    }

    @Test
    void flatten_fan_out_rows_mergesChildToolRowsAndInjectsThingName() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ff1", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("sourceProperty", "Temp"));
        JSONObject fan = new JSONObject()
                .put("status", "ok")
                .put("children", new JSONArray()
                        .put(new JSONObject()
                                .put("status", "ok")
                                .put("item", new JSONObject().put("name", "Thing.A"))
                                .put("toolOutput", new JSONObject().put("rows", rows))));
        ctx.putNodeOutput("fo", fan);
        JSONObject args = new JSONObject()
                .put("fanOutNodeId", "fo")
                .put("injectFromItem", new JSONArray()
                        .put(new JSONObject().put("from", "name").put("as", "thingName")));
        JSONObject out = PlaybookGenericDeriveOps.execute("flatten_fan_out_rows", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONArray outRows = out.getJSONObject("output").getJSONArray("rows");
        assertEquals(1, outRows.length());
        assertEquals("Thing.A", outRows.getJSONObject(0).getString("thingName"));
        assertEquals("Temp", outRows.getJSONObject(0).getString("sourceProperty"));
    }

    @Test
    void flatten_fan_out_rows_truncation_setsTotalCountAboveReturned() throws Exception {
        int cap = PlaybookGenericOpsConstants.MAX_GENERIC_INPUT_ROWS;
        JSONArray rows = new JSONArray();
        for (int i = 0; i < cap + 1; i++) {
            rows.put(new JSONObject().put("sourceProperty", "p"));
        }
        JSONObject fan = new JSONObject()
                .put("status", "ok")
                .put("children", new JSONArray()
                        .put(new JSONObject()
                                .put("status", "ok")
                                .put("item", new JSONObject().put("name", "T"))
                                .put("toolOutput", new JSONObject().put("rows", rows))));
        PlaybookRunContext ctx = new PlaybookRunContext("ffCap", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("fo", fan);
        JSONObject args = new JSONObject()
                .put("fanOutNodeId", "fo")
                .put("injectFromItem", new JSONArray()
                        .put(new JSONObject().put("from", "name").put("as", "thingName")));
        JSONObject out = PlaybookGenericDeriveOps.execute("flatten_fan_out_rows", args, ctx);
        JSONObject o = out.getJSONObject("output");
        assertEquals(cap + 1, o.getInt("totalCount"));
        assertEquals(cap, o.getInt("returned"));
        assertTrue(o.getJSONArray("gaps").toString().contains("truncated"));
    }

    @Test
    void flatten_fan_out_rows_viaPlaybookDeriveOps_dispatches() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("ff2", PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID, new JSONObject());
        ctx.putNodeOutput("fo", new JSONObject()
                .put("status", "ok")
                .put("children", new JSONArray()
                        .put(new JSONObject()
                                .put("status", "ok")
                                .put("item", new JSONObject().put("name", "X"))
                                .put("toolOutput", new JSONObject()
                                        .put("rows", new JSONArray().put(new JSONObject().put("k", 1)))))));
        JSONObject args = new JSONObject()
                .put("fanOutNodeId", "fo")
                .put("injectFromItem", new JSONArray()
                        .put(new JSONObject().put("from", "name").put("as", "thingName")));
        JSONObject out = PlaybookDeriveOps.execute("flatten_fan_out_rows", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals(1, out.getJSONObject("output").getJSONArray("rows").length());
    }
}
