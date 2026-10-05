package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class SkillChecklistDynamicBodyMergeTest {

    private static String fence(String innerJson) {
        return "```parler-task-checklist-v1\n" + innerJson + "\n```";
    }

    private static JSONObject evidenceItem(String id) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("description", "d");
        o.put("kind", "evidence");
        o.put("tool", "invoke_service");
        return o;
    }

    @Test
    void mismatched_list_sizes_throw() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                        null, List.of("a"), List.of(), null));
    }

    @Test
    void null_bodies_list_throw() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(null, null, null, null));
    }

    @Test
    void dynamic_only_single_fence_appends() throws Exception {
        String inner =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"d1\", \"description\": \"x\", "
                        + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }";
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                null, List.of("SkillA"), List.of(fence(inner)), null);
        assertNotNull(out);
        assertEquals(1, out.getJSONArray("requiredEvidence").length());
        assertEquals("d1", out.getJSONArray("requiredEvidence").getJSONObject(0).getString("id"));
    }

    @Test
    void slash_then_dynamic_preserves_order() throws Exception {
        JSONObject slash = new JSONObject();
        slash.put("schemaVersion", 1);
        JSONArray sreq = new JSONArray();
        sreq.put(evidenceItem("slash1"));
        slash.put("requiredEvidence", sreq);

        String inner =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"dyn1\", \"description\": \"x\", "
                        + "\"kind\": \"evidence\", \"tool\": \"fetch_cached_result\" } ] }";
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                slash, List.of("Dyn"), List.of(fence(inner)), null);
        assertNotNull(out);
        JSONArray req = out.getJSONArray("requiredEvidence");
        assertEquals(2, req.length());
        assertEquals("slash1", req.getJSONObject(0).getString("id"));
        assertEquals("dyn1", req.getJSONObject(1).getString("id"));
    }

    @Test
    void duplicate_id_against_slash_skips_dynamic() throws Exception {
        JSONObject slash = new JSONObject();
        slash.put("schemaVersion", 1);
        JSONArray sreq = new JSONArray();
        sreq.put(evidenceItem("dup"));
        slash.put("requiredEvidence", sreq);

        String inner =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"dup\", \"description\": \"x\", "
                        + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }";
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                slash, List.of("D1"), List.of(fence(inner)), null);
        assertNotNull(out);
        assertEquals(1, out.getJSONArray("requiredEvidence").length());
    }

    @Test
    void duplicate_id_between_two_dynamics_skips_second() throws Exception {
        String inner1 =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"x1\", \"description\": \"a\", "
                        + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }";
        String inner2 =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"x1\", \"description\": \"b\", "
                        + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }";
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                null, List.of("A", "B"), List.of(fence(inner1), fence(inner2)), null);
        assertNotNull(out);
        assertEquals(1, out.getJSONArray("requiredEvidence").length());
    }

    @Test
    void null_body_skips_dynamic() {
        List<String> bodies = new ArrayList<>();
        bodies.add(null);
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                null, List.of("A"), bodies, null);
        assertNull(out);
    }

    @Test
    void multi_fence_skips() {
        String two = fence(
                        "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"a\", \"description\": \"x\", "
                                + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }")
                + fence(
                        "{ \"schemaVersion\": 1, \"requiredEvidence\": [ { \"id\": \"b\", \"description\": \"y\", "
                                + "\"kind\": \"evidence\", \"tool\": \"invoke_service\" } ] }");
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                null, List.of("A"), List.of(two), null);
        assertNull(out);
    }

    @Test
    void budget_exceeded_skips_dynamic_block() throws Exception {
        JSONObject slash = new JSONObject();
        slash.put("schemaVersion", 1);
        JSONArray sreq = new JSONArray();
        for (int i = 0; i < 29; i++) {
            sreq.put(evidenceItem("s" + i));
        }
        slash.put("requiredEvidence", sreq);

        String inner =
                "{ \"schemaVersion\": 1, \"requiredEvidence\": [ "
                        + "{ \"id\": \"a1\", \"description\": \"a\", \"kind\": \"evidence\", \"tool\": \"invoke_service\" }, "
                        + "{ \"id\": \"a2\", \"description\": \"b\", \"kind\": \"evidence\", \"tool\": \"invoke_service\" } "
                        + "] }";
        JSONObject out = SkillChecklistDynamicBodyMerge.mergeDynamicBodiesIntoSlashUnion(
                slash, List.of("D"), List.of(fence(inner)), null);
        assertNotNull(out);
        assertEquals(29, out.getJSONArray("requiredEvidence").length());
    }

    @Test
    void continuation_primer_restores_dynamic_snap_on_agent_task_state() {
        List<String> dynamicSnap = List.of("S1", " S2 ");
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        for (String dyn : dynamicSnap) {
            if (dyn != null && !dyn.isBlank()) {
                st.recordMergedDynamicSkillShortName(dyn.trim());
            }
        }
        assertEquals(List.of("S1", "S2"), st.getDynamicSkillShortNamesInOrder());
    }

    @Test
    void blank_skipped_in_restore_loop() {
        AgentTaskState st = new AgentTaskState("r", "c", "g");
        for (String dyn : List.of("A", "", "  ", "B")) {
            if (dyn != null && !dyn.isBlank()) {
                st.recordMergedDynamicSkillShortName(dyn.trim());
            }
        }
        assertEquals(List.of("A", "B"), st.getDynamicSkillShortNamesInOrder());
    }
}
