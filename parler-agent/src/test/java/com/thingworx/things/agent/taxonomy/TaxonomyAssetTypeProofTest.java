package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.configrepo.ConfigurationRepositoryPaths;

class TaxonomyAssetTypeProofTest {

    @Test
    void proveExactAssetTypeKey_nullThing_empty() {
        ApplicationSemanticTaxonomySnapshot taxonomy = taxonomyWith(
                shapeEntry("StackingRobot", "PTCTDD.CellfabDataset.StackingRobot_TS"));
        assertTrue(TaxonomyAssetTypeProof.proveExactAssetTypeKey(null, taxonomy).isEmpty());
    }

    @Test
    void proveExactAssetTypeKey_nullTaxonomy_empty() {
        assertTrue(TaxonomyAssetTypeProof.proveExactAssetTypeKey((com.thingworx.things.Thing) null, null)
                .isEmpty());
    }

    @Test
    void proveExactAssetTypeKey_uniqueMatch_returnsKey() {
        ApplicationSemanticTaxonomySnapshot taxonomy = taxonomyWith(
                shapeEntry("StackingRobot", "PTCTDD.CellfabDataset.StackingRobot_TS"),
                shapeEntry("Sealing", "PTCTDD.CellfabDataset.Sealing_TS"));
        Optional<String> proven = TaxonomyAssetTypeProof.proveExactAssetTypeKey(taxonomy,
                e -> "StackingRobot".equals(e.key()));
        assertEquals(Optional.of("StackingRobot"), proven);
    }

    @Test
    void proveExactAssetTypeKey_zeroMatches_empty() {
        ApplicationSemanticTaxonomySnapshot taxonomy = taxonomyWith(
                shapeEntry("StackingRobot", "PTCTDD.CellfabDataset.StackingRobot_TS"));
        assertTrue(TaxonomyAssetTypeProof.proveExactAssetTypeKey(taxonomy, e -> false).isEmpty());
    }

    @Test
    void proveExactAssetTypeKey_multipleDistinctKeys_empty() {
        ApplicationSemanticTaxonomySnapshot taxonomy = taxonomyWith(
                shapeEntry("StackingRobot", "PTCTDD.CellfabDataset.StackingRobot_TS"),
                shapeEntry("Sealing", "PTCTDD.CellfabDataset.Sealing_TS"));
        assertTrue(TaxonomyAssetTypeProof.proveExactAssetTypeKey(taxonomy, e -> true).isEmpty());
    }

    @Test
    void proveExactAssetTypeKey_duplicateRowsSameKey_stillUnique() {
        ApplicationSemanticTaxonomySnapshot taxonomy = taxonomyWith(
                shapeEntry("StackingRobot", "PTCTDD.CellfabDataset.StackingRobot_TS"),
                new AssetTypeEntry("otherEntity", List.of(), "shape_as_type", "StackingRobot", List.of(),
                        "ThingShape", "AltShape", List.of(), List.of(), List.of(), null));
        Optional<String> proven = TaxonomyAssetTypeProof.proveExactAssetTypeKey(taxonomy, e -> true);
        assertEquals(Optional.of("StackingRobot"), proven);
    }

    private static ApplicationSemanticTaxonomySnapshot taxonomyWith(AssetTypeEntry... entries) {
        return ApplicationSemanticTaxonomySnapshot.loaded(List.of(entries), List.of(), false, Instant.now(),
                ConfigurationRepositoryPaths.IDENTITY_TYPES_JSON);
    }

    private static AssetTypeEntry shapeEntry(String key, String shapeName) {
        return new AssetTypeEntry("entity:" + key, List.of(), "shape_as_type", key, List.of(), "ThingShape",
                shapeName, List.of("name"), List.of("exact"), List.of(), null);
    }
}
