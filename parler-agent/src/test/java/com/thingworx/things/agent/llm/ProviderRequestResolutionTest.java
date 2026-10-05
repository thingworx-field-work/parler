package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ProviderRequestResolutionTest {

    @Test
    void resolveMaxOutput_requestPositiveWins() {
        assertEquals(4096, ProviderRequestResolution.resolveMaxOutput(4096, 8192, 4096));
    }

    @Test
    void resolveMaxOutput_legacyAgent4096WinsOverProvider8192() {
        assertEquals(4096, ProviderRequestResolution.resolveMaxOutput(4096, 8192, 8192));
    }

    @Test
    void resolveMaxOutput_tableWhenRequestNonPositive() {
        assertEquals(8192, ProviderRequestResolution.resolveMaxOutput(-1, 8192, 4096));
        assertEquals(8192, ProviderRequestResolution.resolveMaxOutput(0, 8192, 4096));
    }

    @Test
    void resolveMaxOutput_codeDefaultWhenUnset() {
        assertEquals(4096, ProviderRequestResolution.resolveMaxOutput(-1, 0, 4096));
        assertEquals(8192, ProviderRequestResolution.resolveMaxOutput(-1, 0, 8192));
    }

    @Test
    void resolveMaxOutput_rejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ProviderRequestResolution.resolveMaxOutput(
                (long) Integer.MAX_VALUE + 1, 0, 4096));
    }

    @Test
    void toPositiveResolvedMaxOutput_rejectsOverflow() {
        assertThrows(IllegalArgumentException.class, () -> ProviderRequestResolution.toPositiveResolvedMaxOutput(
                (long) Integer.MAX_VALUE + 1));
    }

    @Test
    void resolveReasoning_requestWins() {
        assertEquals("high", ProviderRequestResolution.resolveReasoning("high", "low", "minimal"));
    }

    @Test
    void resolveReasoning_tableWhenRequestBlank() {
        assertEquals("medium", ProviderRequestResolution.resolveReasoning(null, "medium", "low"));
        assertEquals("low", ProviderRequestResolution.resolveReasoning(null, "", "low"));
    }

    @Test
    void resolveReasoning_codeDefaultWhenUnset() {
        assertEquals("low", ProviderRequestResolution.resolveReasoning(null, null, "low"));
        assertNull(ProviderRequestResolution.resolveReasoning(null, null, null));
    }

    @Test
    void positiveOrDefault_treatsNonPositiveAsUnset() {
        assertEquals(8192, ProviderRequestResolution.positiveOrDefault(0, 8192));
        assertEquals(4096, ProviderRequestResolution.positiveOrDefault(4096, 8192));
    }
}
