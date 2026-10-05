package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TaxonomyPromptInjectionTest {

    @Test
    void effectiveOrDefault_missingOrBlank_returnsFullTable() {
        assertEquals(TaxonomyPromptInjection.FULL_TABLE, TaxonomyPromptInjection.effectiveOrDefault(null));
        assertEquals(TaxonomyPromptInjection.FULL_TABLE, TaxonomyPromptInjection.effectiveOrDefault("  "));
    }

    @Test
    void effectiveOrDefault_invalidCase_fallsBackToFullTable() {
        assertEquals(TaxonomyPromptInjection.FULL_TABLE, TaxonomyPromptInjection.effectiveOrDefault("Full_Table"));
        assertEquals(TaxonomyPromptInjection.FULL_TABLE, TaxonomyPromptInjection.effectiveOrDefault("NONE "));
    }

    @Test
    void isAllowed_exactLowercaseValues() {
        assertTrue(TaxonomyPromptInjection.isAllowed("full_table"));
        assertTrue(TaxonomyPromptInjection.isAllowed("resolver_guidance_only"));
        assertTrue(TaxonomyPromptInjection.isAllowed("none"));
        assertFalse(TaxonomyPromptInjection.isAllowed("FULL_TABLE"));
    }
}
