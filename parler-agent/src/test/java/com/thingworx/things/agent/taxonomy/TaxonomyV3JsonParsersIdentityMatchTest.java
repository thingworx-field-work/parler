package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/** v3 identity-types.json: only {@code equals} and {@code suffix} match modes. */
class TaxonomyV3JsonParsersIdentityMatchTest {

    @Test
    void rejectsUnsupportedMatchMode() {
        String json = "[{\"baseThingTemplate\":\"T.T\",\"identityProperties\":[{\"name\":\"x\",\"match\":\"contains\"}],\"criticalProperties\":[]}]";
        TaxonomyV3JsonParsers.IdentityRulesOutcome out =
                TaxonomyV3JsonParsers.parseIdentityRulesArray(json, NOPLogger.NOP_LOGGER, "test");
        assertFalse(out.valid());
        assertTrue(out.diagnostics().stream().anyMatch(d -> d.message().contains("unsupported match")));
    }

    @Test
    void textualIdentityProperty_defaultsToEquals() {
        String json = "[{\"baseThingTemplate\":\"T.T\",\"identityProperties\":[\"PTCDisplayName\"],\"criticalProperties\":[]}]";
        TaxonomyV3JsonParsers.IdentityRulesOutcome out =
                TaxonomyV3JsonParsers.parseIdentityRulesArray(json, NOPLogger.NOP_LOGGER, "test");
        assertTrue(out.valid(), out.diagnostics().toString());
        assertEquals("equals", out.rules().get(0).identityProperties().get(0).match());
    }
}
