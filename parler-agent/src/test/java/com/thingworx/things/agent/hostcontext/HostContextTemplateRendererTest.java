package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HostContextTemplateRendererTest {

    @Test
    void validateTemplateLine_rejectsUnknownFormatter() {
        List<String> names = new ArrayList<>();
        assertNotNull(HostContextTemplateRenderer.validateTemplateLine(
                "{{format.nope(context.x)}}", names));
    }

    @Test
    void validateTemplateLine_rejectsWrongArity() {
        List<String> names = new ArrayList<>();
        assertNotNull(HostContextTemplateRenderer.validateTemplateLine(
                "{{format.list(context.x)}}", names));
    }

    @Test
    void contextValueWithEmbeddedFormatter_isLiteral() {
        HostContextTemplate template = new HostContextTemplate(
                "t", "", List.of(), 4000,
                List.of("Value: {{context.thingName}}"));
        JSONObject ctx = new JSONObject().put("thingName", "{{format.kv(context.x)}}");
        HostContextTemplateRenderer.RenderResult r = HostContextTemplateRenderer.render(template, ctx);
        assertEquals("Value: {{format.kv(context.x)}}", r.rendered.trim());
        assertTrue(r.diagnostics.isEmpty());
    }

    @Test
    void truncatePreservingFences_closesOpenJsonFence() {
        String open = "Block: x\n\nPage data\n\n```json\n{\"a\":1";
        String out = HostContextTemplateRenderer.truncatePreservingFences(open, 30);
        assertTrue(out.contains("```json"));
        assertTrue(out.endsWith("… [truncated]") || out.contains("… [truncated]"));
        assertEquals(0, HostContextTemplateRenderer.countUnclosedFenceMarkers(out.replace("\n… [truncated]", "")));
    }

    @Test
    void parseValidationError_rejectsUnknownInlineFormatter() {
        JSONObject root = new JSONObject()
                .put("schema", HostContextTemplate.SCHEMA)
                .put("key", "bad")
                .put("promptTemplate", new org.json.JSONArray().put("x {{format.nope(context.a)}}"));
        assertNotNull(HostContextTemplate.parseValidationError(root));
    }

    @Test
    void inlineFormatterExpandedBeforeContextSubstitution() {
        HostContextTemplate template = new HostContextTemplate(
                "t", "", List.of(), 4000,
                List.of("Window: {{format.timeWindow(context.timeWindow)}}"));
        JSONObject ctx = new JSONObject().put("timeWindow",
                new JSONObject().put("kind", "relative").put("value", "24h"));
        HostContextTemplateRenderer.RenderResult r = HostContextTemplateRenderer.render(template, ctx);
        assertTrue(r.rendered.contains("past 24h"));
    }
}
