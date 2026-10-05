package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookAuthoringDeriveOpsTest {

    @Test
    void normalizeText_identifierMode_collapsesAndLowercases() throws Exception {
        JSONObject args = new JSONObject()
                .put("text", "  Plant.LineX-RobotA!!  ")
                .put("mode", "identifier");
        JSONObject out = PlaybookAuthoringDeriveOps.execute("normalize_text", args, new PlaybookRunContext("n1", "pb", new JSONObject()));
        assertEquals("plant linex robota", out.getJSONObject("output").getString("text"));
    }

    @Test
    void matchCandidates_findsSingleRow() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("m1", "pb", new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("name", "Robot-A"))
                .put(new JSONObject().put("name", "Robot-B"));
        JSONObject args = new JSONObject()
                .put("needle", "robot-a")
                .put("rows", rows)
                .put("candidateField", "name");
        JSONObject out = PlaybookAuthoringDeriveOps.execute("match_candidates", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertEquals("Robot-A", out.getJSONObject("output").getJSONObject("row").getString("name"));
    }

    @Test
    void dedupe_removesDuplicatesByKeys() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("d1", "pb", new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("name", "A").put("region", "US"))
                .put(new JSONObject().put("name", "A").put("region", "US"))
                .put(new JSONObject().put("name", "B").put("region", "EU"));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("keys", new JSONArray().put("name").put("region"));
        JSONObject out = PlaybookAuthoringDeriveOps.execute("dedupe", args, ctx);
        assertEquals(2, out.getJSONObject("output").getJSONArray("rows").length());
        assertEquals(3, out.getJSONObject("output").getInt("logicalCount"));
    }

    @Test
    void limitRows_preservesTotalCountAndTruncates() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("l1", "pb", new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("id", 1))
                .put(new JSONObject().put("id", 2))
                .put(new JSONObject().put("id", 3));
        JSONObject args = new JSONObject().put("rows", rows).put("maxRows", 2);
        JSONObject out = PlaybookAuthoringDeriveOps.execute("limit_rows", args, ctx);
        JSONObject output = out.getJSONObject("output");
        assertEquals(3, output.getInt("totalCount"));
        assertEquals(2, output.getInt("returned"));
        assertTrue(output.getJSONArray("gaps").length() > 0);
    }

    @Test
    void formatEvidenceLines_appliesTemplateToRows() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("f1", "pb", new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("name", "Temp").put("count", 3));
        JSONObject args = new JSONObject()
                .put("rows", rows)
                .put("template", "{name}={count}");
        JSONObject out = PlaybookAuthoringDeriveOps.execute("format_evidence_lines", args, ctx);
        assertEquals("Temp=3", out.getJSONArray("evidenceLines").getString(0));
        assertEquals("Temp=3", out.getJSONObject("output").getJSONArray("lines").getString(0));
    }

    @Test
    void authoringOps_dispatchViaPlaybookDeriveOps() throws Exception {
        JSONObject args = new JSONObject().put("text", " ABC ").put("mode", "trim");
        JSONObject out = PlaybookDeriveOps.execute("normalize_text", args, new PlaybookRunContext("x", "pb", new JSONObject()));
        assertEquals("ABC", out.getJSONObject("output").getString("text"));
    }
}
