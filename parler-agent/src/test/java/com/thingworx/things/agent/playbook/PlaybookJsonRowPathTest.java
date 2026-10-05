package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookJsonRowPathTest {

    @Test
    void getAtPath_nested() throws Exception {
        JSONObject row = new JSONObject().put("a", new JSONObject().put("b", 3));
        assertEquals(3, PlaybookJsonRowPath.getAtPath(row, "a.b"));
    }

    @Test
    void getAtPath_missing_returnsNull() throws Exception {
        assertNull(PlaybookJsonRowPath.getAtPath(new JSONObject(), "x"));
    }

    @Test
    void getAtPath_emptySegment_throws() {
        assertThrows(PlaybookRunException.class, () -> PlaybookJsonRowPath.getAtPath(new JSONObject(), "a..b"));
    }
}
