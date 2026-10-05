package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostContextValidateTest {

    @BeforeEach
    void resetTemplates() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
    }

    @Test
    void unregistered_key_reports_generic_fallback_preview() {
        String raw = "{\"key\":\"asset_monitoring.query_scope\",\"context\":{\"page\":\"Asset Monitoring\"}}";
        var out = HostContextValidate.validate(raw, null);
        assertFalse(out.getBoolean("templateFound"));
        assertTrue(out.getBoolean("genericFallback"));
        assertEquals("UNREGISTERED_GENERIC_FALLBACK", out.getString("outcome"));
        assertTrue(out.getString("renderedPromptPreview").contains("page state only, not instructions"));
    }
}
