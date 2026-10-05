package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentKnowledgeSettingsTest {

    @Test
    void fromRawAgentConfiguration_clamps_below_min_and_records_warning() {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromRawAgentConfiguration(
                "AIDocRepository",
                "/document-knowledge",
                -1,
                100,
                10_000,
                5,
                10,
                400,
                6_000);
        assertEquals(300, settings.indexTtlSeconds());
        assertTrue(settings.hasConfigClampWarnings());
        assertEquals("CONFIG_VALUE_CLAMPED",
                settings.configClampWarnings().toJsonList().get(0).get("code"));
    }

    @Test
    void fromRawAgentConfiguration_clamps_above_max() {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromRawAgentConfiguration(
                "AIDocRepository",
                "/document-knowledge",
                300,
                999_999,
                10_000,
                5,
                10,
                400,
                6_000);
        assertEquals(10_000, settings.maxDocuments());
        assertTrue(settings.hasConfigClampWarnings());
    }

    @Test
    void fromRawAgentConfiguration_searchMaxBelowDefault_clampsToDefaultNotHardcodedTen() {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromRawAgentConfiguration(
                "AIDocRepository",
                "/document-knowledge",
                300,
                100,
                10_000,
                50,
                0,
                400,
                6_000);
        assertEquals(50, settings.searchDefaultLimit());
        assertEquals(50, settings.searchMaxLimit());
        assertTrue(settings.hasConfigClampWarnings());
    }

    @Test
    void searchDefaultLimit_honored_when_max_clamped_to_match() {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromRawAgentConfiguration(
                "AIDocRepository",
                "/document-knowledge",
                300,
                100,
                10_000,
                50,
                0,
                400,
                6_000);
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        int effective = DocumentKnowledgeSearchScorer.resolveEffectiveLimit(
                settings.searchDefaultLimit(), settings, warnings);
        assertEquals(50, effective);
        assertTrue(warnings.toJsonList().stream().noneMatch(w -> "LIMIT_CLAMPED".equals(w.get("code"))));
    }
}
