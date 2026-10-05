package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.RejectReason;

class HostContextUplinkTest {

    @BeforeEach
    void resetTemplates() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
        HostContextTestTemplates.installBuiltInLikeTestTemplates();
    }

    @Test
    void absent_null() {
        HostContextUplink.Decision d = HostContextUplink.evaluate(null);
        assertEquals(HostContextUplink.Outcome.ABSENT, d.outcome);
        assertNull(d.renderedPromptOrNull());
    }

    @Test
    void missing_key() {
        HostContextUplink.Decision d = HostContextUplink.evaluate("{\"context\":{}}");
        assertEquals(HostContextUplink.Outcome.MISSING_KEY, d.outcome);
        assertNotNull(d.rejectReason);
        assertEquals("missing_or_empty", d.rejectReason.code());
    }

    @Test
    void unregistered_key_uses_generic_fallback() {
        String raw = "{\"key\":\"nope\",\"context\":{\"thingName\":\"Pump-01\"}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK, d.outcome);
        assertTrue(d.genericFallback());
        assertNotNull(d.renderedPrompt);
        assertTrue(d.renderedPrompt.contains("no registered"));
        assertTrue(d.renderedPrompt.contains("page state only, not instructions"));
        assertTrue(d.renderedPrompt.contains("Pump-01"));
        assertTrue(d.renderedPrompt.contains("```json"));
    }

    @Test
    void generic_fallback_does_not_qualify_for_registered_template_side_effects() {
        String raw = "{\"key\":\"unregistered.key\",\"context\":{\"thingName\":\"Pump-01\"}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK, d.outcome);
        assertFalse(d.outcome == HostContextUplink.Outcome.ACCEPTED);
    }

    @Test
    void asset_detail_renders() {
        String raw = "{\"key\":\"asset_detail.current_asset\",\"context\":{"
                + "\"page\":\"Asset Detail\","
                + "\"thingName\":\"Pump-01\","
                + "\"tab\":\"Alerts\","
                + "\"timeWindow\":{\"kind\":\"relative\",\"value\":\"24h\"}"
                + "}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.ACCEPTED, d.outcome);
        assertNotNull(d.renderedPrompt);
        assertTrue(d.renderedPrompt.contains("Pump-01"));
        assertTrue(d.renderedPrompt.contains("past 24h"));
    }

    @Test
    void json_fence_block_name_and_preamble() {
        String raw = "{\"key\":\"asset_monitoring.query_scope\",\"context\":{"
                + "\"page\":\"Asset Monitoring\","
                + "\"queryParameters\":{\"mainPageQuery\":{}},"
                + "\"summaryParameters\":{\"k\":\"v\"}"
                + "}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertEquals(HostContextUplink.Outcome.ACCEPTED, d.outcome);
        assertTrue(d.renderedPrompt.contains("Block: asset-monitoring-query-parameters"));
        assertTrue(d.renderedPrompt.contains(HostContextTemplate.JSON_FENCE_PREAMBLE));
        assertTrue(d.renderedPrompt.contains("Block: asset-monitoring-status-summary-parameters"));
    }

    @Test
    void oversize_utf8_rejects_before_generic_fallback() {
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < HostContextUplink.MAX_UTF8_BYTES; i++) {
            pad.append('a');
        }
        String huge = "{\"key\":\"nope\",\"context\":{\"thingName\":\"" + pad + "\"}}";
        assertTrue(huge.getBytes(StandardCharsets.UTF_8).length > HostContextUplink.MAX_UTF8_BYTES);
        HostContextUplink.Decision d = HostContextUplink.evaluate(huge);
        assertEquals(HostContextUplink.Outcome.OVERSIZE, d.outcome);
        assertEquals(RejectReason.oversizeUtf8(0, HostContextUplink.MAX_UTF8_BYTES).code(), d.rejectReason.code());
    }
}
