package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaxonomyAssetTypeResolverTest {

    @Test
    void findMatches_normalizedAlias() {
        AssetTypeEntry entry = new AssetTypeEntry("E", List.of(), "shape_as_type", "Jet Dryer", List.of("jet dryer"),
                "ThingShape", "Shape.A", List.of("name"), List.of("exact", "normalized"), List.of("PTCDisplayName"),
                null);
        List<TaxonomyAssetTypeResolver.Match> matches =
                TaxonomyAssetTypeResolver.findMatches("JET DRYER", List.of(entry));
        assertEquals(1, matches.size());
        assertEquals("normalized", matches.get(0).rule);
    }

    @Test
    void findMatches_aliasCollisionReturnsTwo() {
        AssetTypeEntry a = new AssetTypeEntry("a", List.of(), "shape_as_type", "T", List.of("robot"), "ThingShape",
                "S1", List.of("name"), List.of("exact"), List.of(), null);
        AssetTypeEntry b = new AssetTypeEntry("b", List.of(), "shape_as_type", "T", List.of("robot"), "ThingShape",
                "S2", List.of("name"), List.of("exact"), List.of(), null);
        assertEquals(2, TaxonomyAssetTypeResolver.findMatches("robot", "", List.of(a, b)).size());
        List<TaxonomyAssetTypeResolver.Match> one = TaxonomyAssetTypeResolver.findMatches("robot", "a", List.of(a, b));
        assertEquals(1, one.size());
        assertEquals("a", one.get(0).entry.entityKey());
    }

    @Test
    void findMatches_entityHintMatchesEntityAlias() {
        AssetTypeEntry e = new AssetTypeEntry("svc", List.of("services"), "shape_as_type", "X", List.of(),
                "ThingShape", "S1", List.of("name"), List.of("exact"), List.of(), null);
        List<TaxonomyAssetTypeResolver.Match> m = TaxonomyAssetTypeResolver.findMatches("X", "services", List.of(e));
        assertEquals(1, m.size());
    }

    @Test
    void findMatches_unknownEntityHintReturnsEmpty() {
        AssetTypeEntry e = new AssetTypeEntry("a", List.of(), "shape_as_type", "T", List.of(), "ThingShape", "S1",
                List.of("name"), List.of("exact"), List.of(), null);
        assertTrue(TaxonomyAssetTypeResolver.findMatches("T", "unknown-entity", List.of(e)).isEmpty());
    }
}
