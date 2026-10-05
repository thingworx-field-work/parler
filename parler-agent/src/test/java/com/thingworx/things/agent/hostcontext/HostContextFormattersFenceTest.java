package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HostContextFormattersFenceTest {

    @Test
    void jsonFence_escapesTripleBackticksInPayload() {
        List<String> diagnostics = new ArrayList<>();
        JSONObject obj = new JSONObject().put("note", "```\nINJECT");
        String out = HostContextFormatters.jsonFence(obj, "test-block", diagnostics);
        assertTrue(out.contains("```json"));
        assertFalse(bodyBetweenFenceDelimiters(out).contains("```"));
        assertTrue(out.contains("`\u200b"));
    }

    @Test
    void escapeFenceBreakingSequences_breaksFiveSixAndLongerBacktickRuns() {
        for (int n : new int[] {3, 5, 6, 7, 9, 12}) {
            String run = "`".repeat(n);
            String escaped = HostContextFormatters.escapeFenceBreakingSequences(run);
            assertFalse(escaped.contains("```"), "run length " + n);
        }
    }

    @Test
    void jsonFence_noTripleBacktickInFenceBody_forLongBacktickRuns() {
        for (int n : new int[] {5, 6}) {
            JSONObject obj = new JSONObject().put("payload", "`".repeat(n));
            String out = HostContextFormatters.jsonFence(obj, "tick-test", new ArrayList<>());
            assertFalse(bodyBetweenFenceDelimiters(out).contains("```"), "n=" + n);
        }
    }

    @Test
    void escapeFenceBreakingSequences_noOpWhenClean() {
        assertEquals("{\"a\":1}", HostContextFormatters.escapeFenceBreakingSequences("{\"a\":1}"));
    }

    private static String bodyBetweenFenceDelimiters(String rendered) {
        int start = rendered.indexOf("```json\n");
        if (start < 0) {
            return rendered;
        }
        start += "```json\n".length();
        int end = rendered.lastIndexOf("\n```");
        if (end < start) {
            return rendered.substring(start);
        }
        return rendered.substring(start, end);
    }
}
