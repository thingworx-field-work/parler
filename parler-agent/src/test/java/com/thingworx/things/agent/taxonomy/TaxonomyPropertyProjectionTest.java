package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;

import org.junit.jupiter.api.Test;

class TaxonomyPropertyProjectionTest {

    @Test
    void safeIdentityFields_unknownParent_includesNameOnly() {
        AssetTypeEntry entry = new AssetTypeEntry("E", List.of(), "shape_as_type", "A", List.of(), "ThingShape",
                "Unknown.Shape.X", List.of("name", "PTCDisplayName"), List.of("exact"), List.of("PTCDisplayName"), null);
        List<String> projected = TaxonomyPropertyProjection.qitPropertyNamesForParent(entry, null);
        assertEquals(List.of(), projected);
        List<String> fields = TaxonomyPropertyProjection.safeIdentityFields(entry, null);
        assertEquals(List.of("name"), fields);
        assertFalse(TaxonomyPropertyProjection.safeCriticalPropertyFields(entry, null).contains("PTCDisplayName"));
    }

    @Test
    void v3ZipSynthetic_keepsIdentityPropertyNamesWhenParentUnknown() {
        ThingIdentityRuleV3 rule = new ThingIdentityRuleV3("T",
                List.of(new ThingIdentityPropertyMatchV3("PTCDisplayName", "equals"),
                        new ThingIdentityPropertyMatchV3("PTCSerialNumber", "equals")),
                List.of());
        AssetTypeEntry syn = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        List<String> projected = TaxonomyPropertyProjection.qitPropertyNamesForParent(syn, null);
        assertEquals(List.of("PTCDisplayName", "PTCSerialNumber"), projected);
        List<String> idFields = TaxonomyPropertyProjection.safeIdentityFields(syn, null);
        assertEquals(List.of("PTCDisplayName", "PTCSerialNumber"), idFields);
    }
}
