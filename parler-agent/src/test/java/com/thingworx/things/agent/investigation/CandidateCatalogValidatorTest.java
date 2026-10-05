package com.thingworx.things.agent.investigation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CandidateCatalogValidatorTest {

    @Test
    void minimalFourEntryCatalogValidates() {
        CandidateCatalog catalog = CandidateCatalog.builder()
                .catalogId("demo-rca-v1")
                .maxRelationDepth(2)
                .maxRelationNodes(8)
                .relations(List.of(new CatalogRelationEntry(
                        "feeds", "compressor.line1", "dryer.line1", 1)))
                .signals(List.of(new CatalogSignalEntry(
                        "role.vibration.rms", "Compressor", "RUNNING")))
                .serviceBindings(List.of(
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.EVENT,
                                "SCPA_EventHelper",
                                "QueryAlarms",
                                3_600_000L),
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.MAINTENANCE,
                                "SCPA_MaintHelper",
                                "QueryWorkOrders",
                                86_400_000L),
                        new CatalogServiceBinding(
                                CatalogServiceBinding.BindingKind.BATCH,
                                "SCPA_BatchHelper",
                                "QueryBatches",
                                86_400_000L)))
                .build();
        assertDoesNotThrow(() -> CandidateCatalogValidator.validateOrThrow(catalog));
        assertTrue(catalog.relations().size() >= 1);
        assertTrue(catalog.signals().size() >= 1);
        assertTrue(catalog.serviceBindings().size() >= 3);
    }

    @Test
    void emptyCatalogFailsClosed() {
        assertThrows(IllegalArgumentException.class, () -> CandidateCatalog.builder()
                .catalogId("empty")
                .build());
    }

    @Test
    void relationDistanceBeyondCapFails() {
        assertThrows(IllegalArgumentException.class, () -> CandidateCatalog.builder()
                .catalogId("deep")
                .maxRelationDepth(1)
                .relations(List.of(new CatalogRelationEntry(
                        "feeds", "a", "b", 2)))
                .build());
    }

    @Test
    void duplicateSignalFails() {
        assertThrows(IllegalArgumentException.class, () -> CandidateCatalog.builder()
                .catalogId("dup")
                .signals(List.of(
                        new CatalogSignalEntry("role.temp", "Compressor", null),
                        new CatalogSignalEntry("role.temp", "Compressor", "RUNNING")))
                .build());
    }
}
