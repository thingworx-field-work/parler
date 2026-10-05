package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookGenericRowArraysTest {

    @Test
    void truncateIfNeeded_noOpWhenUnderCap() {
        JSONArray rows = new JSONArray().put(new JSONObject().put("x", 1));
        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeeded(rows, 10, "join_by_key");
        assertSame(rows, t.rows);
        assertEquals(1, t.logicalCount);
        assertEquals(1, t.returned);
        assertEquals(0, t.gaps.length());
    }

    @Test
    void truncateIfNeeded_truncatesAndGap() {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < 5; i++) {
            rows.put(new JSONObject().put("i", i));
        }
        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeeded(rows, 3, "join_by_key");
        assertEquals(5, t.logicalCount);
        assertEquals(3, t.returned);
        assertEquals(3, t.rows.length());
        assertEquals(0, t.rows.getJSONObject(0).getInt("i"));
        assertEquals(2, t.rows.getJSONObject(2).getInt("i"));
        assertEquals(1, t.gaps.length());
        assertTrue(t.gaps.getString(0).contains("truncated"));
        assertTrue(t.gaps.getString(0).contains("join_by_key"));
    }

    @Test
    void truncateIfNeededWithLogicalCount_matchesTruncateWhenMaterializedIsPrefix() {
        JSONArray head = new JSONArray();
        head.put(new JSONObject().put("i", 0));
        head.put(new JSONObject().put("i", 1));
        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeededWithLogicalCount(head, 5, 2, "join_by_key");
        assertEquals(5, t.logicalCount);
        assertEquals(2, t.returned);
        assertEquals(2, t.rows.length());
        assertEquals(1, t.gaps.length());
        assertTrue(t.gaps.getString(0).contains("truncated"));
    }

    @Test
    void truncateIfNeededWithLogicalCount_noTruncationWhenLogicalEqualsMaterialized() {
        JSONArray all = new JSONArray().put(new JSONObject().put("a", 1));
        PlaybookGenericRowArrays.Truncation t =
                PlaybookGenericRowArrays.truncateIfNeededWithLogicalCount(all, 1, 10, "join_by_key");
        assertSame(all, t.rows);
        assertEquals(1, t.logicalCount);
        assertEquals(0, t.gaps.length());
    }

    @Test
    void requireEachSlotIsObject_rejectsNullSlot() {
        JSONArray rows = new JSONArray().put(new JSONObject()).put(JSONObject.NULL);
        PlaybookRunException ex = assertThrows(
                PlaybookRunException.class, () -> PlaybookGenericRowArrays.requireEachSlotIsObject(rows, "filter"));
        assertEquals("GENERIC_INPUT_INVALID", ex.failureCode());
    }
}
