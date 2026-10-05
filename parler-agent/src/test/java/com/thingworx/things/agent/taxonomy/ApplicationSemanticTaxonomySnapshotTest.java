package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;

class ApplicationSemanticTaxonomySnapshotTest {

    @Test
    void loadedEmptySnapshotStatusIsEmpty() {
        ApplicationSemanticTaxonomySnapshot snap =
                ApplicationSemanticTaxonomySnapshot.loaded(List.of(), List.of(), false, Instant.now(),
                        ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        assertEquals("empty", snap.snapshotStatus());
        assertTrue(snap.isLoaded());
    }

    @Test
    void loadedWithIdentityRules_emptyAssets_snapshotStatusLoaded() {
        List<ThingIdentityRuleV3> rules = List.of(new ThingIdentityRuleV3("T.Template",
                List.of(new ThingIdentityPropertyMatchV3("name", "equals")), List.of()));
        ApplicationSemanticTaxonomySnapshot snap = ApplicationSemanticTaxonomySnapshot.loaded(List.of(), rules,
                List.of(), false, Instant.now(), ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        assertEquals("loaded", snap.snapshotStatus());
        assertEquals(0, snap.assetTypeCount());
        assertEquals(1, snap.thingIdentityRules().size());
    }

    @Test
    void staleFromPrior_mergesCurrentFailureDiagnostics() {
        ApplicationSemanticTaxonomySnapshot prior = ApplicationSemanticTaxonomySnapshot.loaded(
                List.of(new AssetTypeEntry("ek", List.of(), "shape_as_type", "A", List.of(), "ThingShape", "S",
                        List.of("name"), List.of("exact"), List.of(), null)),
                List.of(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.WARNING, "TAXONOMY_ALIAS_COLLISION",
                        "old")),
                false,
                Instant.now(),
                ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        List<TaxonomyDiagnostic> current = List.of(new TaxonomyDiagnostic(TaxonomyDiagnostic.Severity.ERROR,
                "TAXONOMY_CONFIG_INVALID", "parse failed"));
        ApplicationSemanticTaxonomySnapshot stale =
                ApplicationSemanticTaxonomySnapshot.staleFromPrior(prior, Instant.now(), current);
        assertEquals("stale", stale.snapshotStatus());
        assertTrue(stale.isStale());
        assertEquals(2, stale.diagnostics().size());
        assertEquals("TAXONOMY_CONFIG_INVALID", stale.diagnostics().get(0).code());
        assertEquals("TAXONOMY_ALIAS_COLLISION", stale.diagnostics().get(1).code());
    }

    @Test
    void resolveIdentifierEntry_requiresEntityKeyWhenDuplicateTypeKeys() {
        List<AssetTypeEntry> rows = List.of(
                new AssetTypeEntry("a", List.of(), "shape_as_type", "T", List.of("robot"), "ThingShape", "S1",
                        List.of("name"), List.of("exact"), List.of(), null),
                new AssetTypeEntry("b", List.of(), "shape_as_type", "T", List.of("robot"), "ThingShape", "S2",
                        List.of("name"), List.of("exact"), List.of(), null));
        ApplicationSemanticTaxonomySnapshot snap = ApplicationSemanticTaxonomySnapshot.loaded(rows, List.of(), false,
                Instant.now(), ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
        assertTrue(snap.resolveIdentifierEntry("T", null).isEmpty());
        assertTrue(snap.findByExactKey("T").isEmpty());
        assertEquals("S1", snap.resolveIdentifierEntry("T", "a").orElseThrow().parentEntityName());
        assertEquals("S2", snap.resolveIdentifierEntry("T", "b").orElseThrow().parentEntityName());
    }
}
