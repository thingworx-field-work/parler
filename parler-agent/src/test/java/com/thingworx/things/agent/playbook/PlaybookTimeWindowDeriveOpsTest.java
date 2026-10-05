package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class PlaybookTimeWindowDeriveOpsTest {

    @Test
    void resolveTimeWindow_quickIntervalMatch() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw1", "p", new JSONObject());
        JSONArray rows = new JSONArray()
                .put(new JSONObject().put("name", "Today").put("QuickTimeIntervalUID", 3))
                .put(new JSONObject().put("name", "Yesterday").put("QuickTimeIntervalUID", 5));
        JSONObject args = new JSONObject()
                .put("phrase", "Today")
                .put("quickIntervalRows", rows)
                .put("timezone", "America/Toronto");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertEquals("quickInterval", output.getString("mode"));
        assertEquals(3, output.getInt("QuickTimeIntervalUID"));
        assertEquals("Today", output.getString("label"));
    }

    @Test
    void resolveTimeWindow_defaultTodayWhenPhraseEmpty() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw2", "p", new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("name", "Today").put("UID", 9));
        JSONObject args = new JSONObject()
                .put("phrase", "")
                .put("defaultQuickIntervalName", "Today")
                .put("quickIntervalRows", rows)
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("ok", out.getString("status"));
        assertTrue(out.getJSONObject("output").getBoolean("defaulted"));
        assertEquals(9, out.getJSONObject("output").getInt("QuickTimeIntervalUID"));
    }

    @Test
    void resolveTimeWindow_explicitCalendarDay() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw3", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("phrase", "today")
                .put("quickIntervalRows", new JSONArray())
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertEquals("explicitRange", output.getString("mode"));
        assertTrue(output.has("StartTime"));
        assertTrue(output.has("EndTime"));
    }

    @Test
    void resolveTimeWindow_unsupportedPhrase_clarify() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw4", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("phrase", "this month")
                .put("quickIntervalRows", new JSONArray())
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
    }

    @Test
    void resolveTimeWindow_dayPlusWallClock_rejectsResidue() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw5", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("phrase", "today at 8")
                .put("quickIntervalRows", new JSONArray())
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
    }

    @Test
    void resolveTimeWindow_todayMorning_rejectsResidue() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw6", "p", new JSONObject());
        JSONObject args = new JSONObject()
                .put("phrase", "today morning")
                .put("quickIntervalRows", new JSONArray())
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("needs_clarification", out.getString("status"));
    }

    @Test
    void resolveTimeWindow_defaultToday_nonMatchingQuickRows_fallsBackToExplicitRange() throws Exception {
        PlaybookRunContext ctx = new PlaybookRunContext("tw7", "p", new JSONObject());
        JSONArray rows = new JSONArray().put(new JSONObject().put("name", "Yesterday").put("UID", 5));
        JSONObject args = new JSONObject()
                .put("phrase", "")
                .put("defaultQuickIntervalName", "Today")
                .put("quickIntervalRows", rows)
                .put("timezone", "UTC");

        JSONObject out = PlaybookOrchestrationDeriveOps.execute("resolve_time_window_for_playbook", args, ctx);
        assertEquals("ok", out.getString("status"));
        JSONObject output = out.getJSONObject("output");
        assertEquals("explicitRange", output.getString("mode"));
        assertTrue(output.getBoolean("defaulted"));
        assertTrue(output.has("StartTime"));
        assertTrue(output.has("EndTime"));
    }
}
