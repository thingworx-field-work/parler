package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SkillChecklistParserFenceTest {

    private static String oneFence(String innerJson) {
        return "```parler-task-checklist-v1\n" + innerJson + "\n```";
    }

    @Test
    void count_fences() {
        assertEquals(0, SkillChecklistParser.countChecklistFences(null));
        assertEquals(0, SkillChecklistParser.countChecklistFences("no fence"));
        assertEquals(1, SkillChecklistParser.countChecklistFences(oneFence(minimalFenceJson())));
        assertEquals(2, SkillChecklistParser.countChecklistFences(
                oneFence(minimalFenceJson()) + "\n" + oneFence(minimalFenceJson())));
    }

    @Test
    void parse_exactly_one_ok() throws Exception {
        JSONObject root = SkillChecklistParser.parseExactlyOneChecklistFence(oneFence(minimalFenceJson()));
        JSONArray req = root.getJSONArray("requiredEvidence");
        assertEquals(1, req.length());
        assertEquals("a1", req.getJSONObject(0).getString("id"));
    }

    @Test
    void parse_exactly_one_rejects_none_and_multi() {
        assertThrows(SkillChecklistParseException.class, () -> SkillChecklistParser.parseExactlyOneChecklistFence(""));
        assertThrows(
                SkillChecklistParseException.class,
                () -> SkillChecklistParser.parseExactlyOneChecklistFence(
                        oneFence(minimalFenceJson()) + oneFence(minimalFenceJson())));
    }

    private static String minimalFenceJson() {
        return "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"a1\", \"description\": \"d\", "
                + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }";
    }
}
