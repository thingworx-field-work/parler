package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

/** Direct coverage for {@link PlaybookPredicateValueOps}. */
class PlaybookPredicateValueOpsTest {

    @Test
    void isEmpty_blankString_true() {
        assertTrue(PlaybookPredicateValueOps.isEmpty("   "));
    }

    @Test
    void isEmpty_jsonNull_true() {
        assertTrue(PlaybookPredicateValueOps.isEmpty(JSONObject.NULL));
    }

    @Test
    void isEmpty_emptyObject_true() {
        assertTrue(PlaybookPredicateValueOps.isEmpty(new JSONObject()));
    }

    @Test
    void isEmpty_nonBlankString_false() {
        assertFalse(PlaybookPredicateValueOps.isEmpty("x"));
    }

    @Test
    void compare_twoNumbers_numericOrder() {
        assertTrue(PlaybookPredicateValueOps.compare(1, 2) < 0);
        assertEquals(0, PlaybookPredicateValueOps.compare(3, 3));
        assertTrue(PlaybookPredicateValueOps.compare(5, 4) > 0);
    }

    @Test
    void compare_mixedNumberAndString_usesStringValueOfOrdering() {
        assertTrue(PlaybookPredicateValueOps.compare(10, "2") < 0);
        assertTrue(PlaybookPredicateValueOps.compare(2, "10") > 0);
    }

    @Test
    void compare_nullSortsBeforeNonNull() {
        assertTrue(PlaybookPredicateValueOps.compare(null, 1) < 0);
        assertEquals(0, PlaybookPredicateValueOps.compare(null, JSONObject.NULL));
    }

    @Test
    void isEmpty_emptyArray_true() {
        assertTrue(PlaybookPredicateValueOps.isEmpty(new JSONArray()));
    }
}
