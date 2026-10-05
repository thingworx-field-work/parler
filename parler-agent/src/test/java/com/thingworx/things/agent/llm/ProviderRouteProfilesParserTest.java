package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProviderRouteProfilesParserTest {

    @Test
    void parsesDesignExampleProfile() {
        String json = "{"
                + "\"profiles\":[{"
                + "\"id\":\"interactive-industrial-tools-v1\","
                + "\"providers\":[\"PrimaryProviderThing\",\"FallbackProviderThing\"],"
                + "\"requiredCapabilities\":[\"TOOLS\",\"STRICT_JSON_SCHEMA\",\"HITL_CONTINUATION\"],"
                + "\"allowedDataClassifications\":[\"PUBLIC\",\"INTERNAL\"],"
                + "\"maxSameProviderRetries\":1,"
                + "\"maxProvidersTried\":2,"
                + "\"maxTotalAttempts\":3,"
                + "\"maxCumulativeWaitMs\":30000,"
                + "\"maxWallTimeMs\":60000,"
                + "\"qualityTier\":\"APPROVED_INTERACTIVE\","
                + "\"fallbackVisible\":true"
                + "}]}";
        var result = ProviderRouteProfilesParser.parse(json);
        assertTrue(result.diagnostics().isEmpty());
        ProviderRouteProfile p = result.find("interactive-industrial-tools-v1").orElseThrow();
        assertEquals(2, p.providers().size());
        assertTrue(p.requiredCapabilities().contains(ProviderCapabilityToken.TOOLS));
        assertEquals(1, p.maxSameProviderRetries());
    }

    @Test
    void rejectsCredentialAndEndpointKeys() {
        String json = "{\"profiles\":[{\"id\":\"bad\",\"providers\":[\"P1\"],\"apiKey\":\"secret\"}]}";
        var result = ProviderRouteProfilesParser.parse(json);
        assertTrue(result.profilesById().isEmpty());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("forbidden key")));
    }

    @Test
    void skipsBadIndependentProfileKeepsSibling() {
        String json = "{\"profiles\":["
                + "{\"id\":\"good\",\"providers\":[\"P1\"]},"
                + "{\"id\":\"bad\",\"providers\":[]}"
                + "]}";
        var result = ProviderRouteProfilesParser.parse(json);
        assertTrue(result.find("good").isPresent());
        assertFalse(result.find("bad").isPresent());
        assertFalse(result.diagnostics().isEmpty());
    }

    /** Non-positive wait/wall caps must skip only the bad profile (D10). */
    @Test
    void nonPositiveWaitWallCapsSkipBadProfileOnly() {
        String json = "{\"profiles\":["
                + "{\"id\":\"good\",\"providers\":[\"P1\"],"
                + "\"maxCumulativeWaitMs\":30000,\"maxWallTimeMs\":60000},"
                + "{\"id\":\"zeroWall\",\"providers\":[\"P2\"],\"maxWallTimeMs\":0},"
                + "{\"id\":\"negWait\",\"providers\":[\"P3\"],\"maxCumulativeWaitMs\":-1},"
                + "{\"id\":\"zeroAttempts\",\"providers\":[\"P4\"],\"maxTotalAttempts\":0}"
                + "]}";
        var result = ProviderRouteProfilesParser.parse(json);
        assertTrue(result.find("good").isPresent());
        assertFalse(result.find("zeroWall").isPresent());
        assertFalse(result.find("negWait").isPresent());
        assertFalse(result.find("zeroAttempts").isPresent());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("maxWallTimeMs")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("maxCumulativeWaitMs")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.contains("maxTotalAttempts")));
    }
}
