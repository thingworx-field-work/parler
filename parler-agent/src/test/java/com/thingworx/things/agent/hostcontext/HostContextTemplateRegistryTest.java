package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostContextTemplateRegistryTest {

    @BeforeEach
    void reset() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
    }

    @Test
    void builtInTemplates_are_empty_at_runtime() {
        assertTrue(HostContextTemplateRegistry.builtInTemplates().isEmpty());
    }

    @Test
    void test_overlay_supplies_registered_templates_for_unit_tests() {
        HostContextTestTemplates.installBuiltInLikeTestTemplates();
        assertNotNull(HostContextTemplateRegistry.find("asset_detail.current_asset", null));
    }

    @Test
    void unregistered_key_without_overlay_uses_generic_fallback_not_classpath_template() {
        String raw = "{\"key\":\"asset_monitoring.query_scope\",\"context\":{"
                + "\"page\":\"Asset Monitoring\","
                + "\"queryParameters\":{\"mainPageQuery\":{}},"
                + "\"summaryParameters\":{\"k\":\"v\"}"
                + "}}";
        HostContextUplink.Decision d = HostContextUplink.evaluate(raw);
        assertFalse(d.outcome == HostContextUplink.Outcome.ACCEPTED);
        assertTrue(d.outcome == HostContextUplink.Outcome.UNREGISTERED_GENERIC_FALLBACK);
        assertTrue(d.renderedPrompt.contains("no registered"));
        assertFalse(d.renderedPrompt.contains("DemoWrapper"));
    }
}
