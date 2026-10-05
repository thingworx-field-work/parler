package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaxonomyResolverJsonTest {

    @Test
    void error_staleTrue_emitsStale() {
        String json = TaxonomyResolverJson.error("TAXONOMY_RESOLVE_FAILED", "boom", true, null, null);
        assertTrue(json.contains("\"stale\":true"));
    }

    @Test
    void error_staleFalse_emitsStaleFalse() {
        String json = TaxonomyResolverJson.error("ASSET_TYPE_NOT_FOUND", "x", false, null, null);
        assertTrue(json.contains("\"stale\":false"));
    }

    @Test
    void error_staleNull_omitsStale() {
        String json = TaxonomyResolverJson.error("TAXONOMY_UNAVAILABLE", "x", null, null, null);
        assertFalse(json.contains("\"stale\""));
    }

    @Test
    void unavailable_omitsStale() {
        String json = TaxonomyResolverJson.unavailable();
        assertFalse(json.contains("\"stale\""));
        assertTrue(json.contains("TAXONOMY_UNAVAILABLE"));
    }

    @Test
    void asset_types_not_configured_includesCodeAndStale() {
        String json = TaxonomyResolverJson.assetTypesNotConfigured(false);
        assertTrue(json.contains("ASSET_TYPES_NOT_CONFIGURED"));
        assertTrue(json.contains("\"stale\":false"));
    }

    @Test
    void putTruncation_onlyWhenTrue() throws Exception {
        var mapper = TaxonomyResolverJson.mapper();
        var o = mapper.createObjectNode();
        TaxonomyResolverJson.putTruncation(o, null, null);
        assertFalse(o.has("truncated"));
        TaxonomyResolverJson.putTruncation(o, true, 8000);
        assertTrue(o.get("truncated").asBoolean());
        assertEquals(8000, o.get("totalUnderlyingCount").asInt());
    }
}
