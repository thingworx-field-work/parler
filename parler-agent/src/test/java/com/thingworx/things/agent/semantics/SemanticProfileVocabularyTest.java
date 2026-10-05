package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SemanticProfileVocabularyTest {

    @Test
    void closedTables_coverSp4Minimum() {
        assertTrue(SemanticProfileVocabulary.isDimension("temperature"));
        assertTrue(SemanticProfileVocabulary.isUnit("Cel"));
        assertEquals("temperature", SemanticProfileVocabulary.dimensionForUnit("Cel"));
        assertTrue(SemanticProfileVocabulary.isGrain("sample"));
        assertFalse(SemanticProfileVocabulary.isUnit("celsius"));
        assertFalse(SemanticProfileVocabulary.isDimension("temp"));
    }

    @Test
    void cadence_optionalPositiveIsoDuration() {
        assertNull(SemanticProfileVocabulary.validateCadence(null));
        assertNull(SemanticProfileVocabulary.validateCadence(""));
        assertNull(SemanticProfileVocabulary.validateCadence("PT5S"));
        assertNull(SemanticProfileVocabulary.validateCadence("PT1H"));
        assertTrue(SemanticProfileVocabulary.validateCadence("5s") != null);
        assertTrue(SemanticProfileVocabulary.validateCadence("PT0S") != null);
        assertTrue(SemanticProfileVocabulary.validateCadence("-PT5S") != null);
    }
}
