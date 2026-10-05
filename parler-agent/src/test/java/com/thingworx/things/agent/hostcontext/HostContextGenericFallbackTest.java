package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostContextGenericFallbackTest {

    @BeforeEach
    void resetTemplates() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
    }

    @Test
    void generic_fallback_snapshot_is_accepted_with_distinct_outcome() {
        String raw = "{\"key\":\"nope\",\"context\":{\"thingName\":\"Pump-01\"}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, raw, HostContextPreviousSnapshot.NONE);
        assertTrue(json.contains("\"accepted\":true"));
        assertTrue(json.contains("\"genericFallback\":true"));
        assertTrue(json.contains("\"outcome\":\"UNREGISTERED_GENERIC_FALLBACK\""));
        assertFalse(json.contains("\"outcome\":\"ACCEPTED\""));
    }

    @Test
    void unregistered_key_with_newline_and_backticks_stays_inside_prose_framing() {
        String key = "evil\n- fake instruction\n```";
        String raw = new org.json.JSONObject()
                .put("key", key)
                .put("context", new org.json.JSONObject().put("x", 1))
                .toString();
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK, d.outcome);
        int fenceStart = d.renderedPrompt.indexOf("```json\n");
        assertTrue(fenceStart >= 0, "expected fenced JSON block");
        String beforeFence = d.renderedPrompt.substring(0, fenceStart);
        assertFalse(beforeFence.contains("\n- fake instruction"),
                "malicious key must not inject extra markdown list lines into prose");
        assertTrue(beforeFence.contains(org.json.JSONObject.quote(key)),
                "key must be JSON-quoted in prose header");
    }

    @Test
    void ingress_cap_and_render_cap_are_distinct_constants() {
        assertTrue(HostContextUplink.MAX_UTF8_BYTES > 0);
        assertTrue(HostContextGenericFallback.MAX_FALLBACK_RENDER_CHARS > 0);
        assertTrue(HostContextUplink.MAX_UTF8_BYTES != HostContextGenericFallback.MAX_FALLBACK_RENDER_CHARS);
    }

    @Test
    void oversized_fallback_context_truncates_and_surfaces_in_snapshot_and_validate() {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            pad.append('x');
        }
        String raw = new org.json.JSONObject()
                .put("key", "big.unregistered.context")
                .put("context", new org.json.JSONObject().put("data", pad.toString()))
                .toString();
        assertTrue(raw.getBytes(StandardCharsets.UTF_8).length <= HostContextUplink.MAX_UTF8_BYTES,
                "fixture must stay under ingress cap to exercise fallback render cap only");

        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK, d.outcome);
        assertTrue(d.renderTruncated);
        assertTrue(d.renderedPrompt.contains("… [truncated]"));

        String snapshot = HostContextSnapshotBuilder.buildSnapshotJson(
                d, raw, HostContextPreviousSnapshot.NONE);
        assertTrue(snapshot.contains("\"renderTruncated\":true"));

        var validate = HostContextValidate.validate(raw, null);
        assertTrue(validate.getBoolean("renderTruncated"));
        assertTrue(validate.getString("renderedPromptPreview").contains("… [truncated]"));
    }
}
