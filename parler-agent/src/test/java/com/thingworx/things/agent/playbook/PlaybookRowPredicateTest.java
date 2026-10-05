package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookRowPredicateTest {

    @Test
    void is_present_onField() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p1", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject().put("name", "x");
        JSONObject pred = new JSONObject().put("op", "is_present").put("field", "name");
        assertTrue(PlaybookRowPredicate.evaluate(pred, row, ctx));
    }

    @Test
    void and_requiresBoth() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p2", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject().put("a", 2).put("b", 1);
        JSONObject pred = new JSONObject()
                .put("and", new JSONArray()
                        .put(new JSONObject().put("op", "gt").put("field", "a").put("right", 1))
                        .put(new JSONObject().put("op", "gt").put("field", "b").put("right", 0)));
        assertTrue(PlaybookRowPredicate.evaluate(pred, row, ctx));
    }

    @Test
    void is_empty_blankString() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("p3", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject().put("s", "  ");
        JSONObject pred = new JSONObject().put("op", "is_empty").put("field", "s");
        assertTrue(PlaybookRowPredicate.evaluate(pred, row, ctx));
    }

    @Test
    void structuralBranchCount_singleOp() {
        JSONObject p = new JSONObject().put("op", "is_present").put("field", "a");
        assertEquals(1, PlaybookRowPredicate.structuralBranchCount(p));
    }

    @Test
    void structuralBranchCount_multipleStructuralKeys() {
        JSONObject p = new JSONObject()
                .put("op", "is_present")
                .put("field", "a")
                .put("and", new JSONArray());
        assertEquals(2, PlaybookRowPredicate.structuralBranchCount(p));
    }

    @Test
    void evaluate_rejectsEmptyAndArray() {
        PlaybookRunContext ctx = new PlaybookRunContext("p4", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject();
        JSONObject pred = new JSONObject().put("and", new JSONArray());
        assertThrows(PlaybookRunException.class, () -> PlaybookRowPredicate.evaluate(pred, row, ctx));
    }

    @Test
    void evaluate_rejectsNotBindingNonObject() {
        PlaybookRunContext ctx = new PlaybookRunContext("p5", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject();
        JSONObject pred = new JSONObject().put("not", "bad");
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookRowPredicate.evaluate(pred, row, ctx));
        assertEquals("GENERIC_PREDICATE_INVALID", ex.failureCode());
    }

    @Test
    void evaluate_rejectsAndNotJsonArray() {
        PlaybookRunContext ctx = new PlaybookRunContext("p6", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject();
        JSONObject pred = new JSONObject().put("and", "bad");
        PlaybookRunException ex =
                assertThrows(PlaybookRunException.class, () -> PlaybookRowPredicate.evaluate(pred, row, ctx));
        assertEquals("GENERIC_PREDICATE_INVALID", ex.failureCode());
        assertTrue(ex.getMessage().contains("JSONArray"));
    }

    @Test
    void evaluate_rejectsOrNonObjectElement() {
        PlaybookRunContext ctx = new PlaybookRunContext("p7", PlaybookIds.CROSS_REGION_HEALTH_ID, new JSONObject());
        JSONObject row = new JSONObject();
        JSONObject pred = new JSONObject().put("or", new JSONArray().put(1).put(new JSONObject().put("op", "is_present").put("field", "a")));
        assertThrows(PlaybookRunException.class, () -> PlaybookRowPredicate.evaluate(pred, row, ctx));
    }
}
