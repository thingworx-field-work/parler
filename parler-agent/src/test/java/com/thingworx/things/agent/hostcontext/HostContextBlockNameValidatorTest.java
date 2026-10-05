package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostContextBlockNameValidatorTest {

    @Test
    void accepts_kebab_case() {
        assertNull(HostContextBlockNameValidator.validateOrReason("asset-monitoring-query-parameters"));
    }

    @Test
    void rejects_uppercase() {
        assertTrue(HostContextBlockNameValidator.validateOrReason("Asset-Monitoring").contains("kebab"));
    }

    @Test
    void rejects_empty() {
        assertTrue(HostContextBlockNameValidator.validateOrReason("").contains("empty"));
    }
}
