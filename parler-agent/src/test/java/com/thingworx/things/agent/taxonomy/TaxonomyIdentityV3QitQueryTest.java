package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

class TaxonomyIdentityV3QitQueryTest {

    private static ThingIdentityRuleV3 workunitRule() {
        return new ThingIdentityRuleV3("PTC.MfgModel.DefaultWorkunit_TT",
                List.of(new ThingIdentityPropertyMatchV3("name", "suffix"),
                        new ThingIdentityPropertyMatchV3("PTCDisplayName", "equals"),
                        new ThingIdentityPropertyMatchV3("PTCSerialNumber", "equals")),
                List.of());
    }

    @Test
    void buildsOrQueryMatchingBug006ExampleShape() {
        AssetTypeEntry type = workunitRule().toSyntheticAssetTypeEntry(0, List.of(), null);
        JsonNode root = TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, "ORD Contacting 02");
        assertNotNull(root);
        JsonNode filters = root.get("filters");
        assertEquals("OR", filters.get("type").asText());
        JsonNode arr = filters.get("filters");
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertLikeOrValueLeaf(arr.get(0), "name", "LIKE", "*ORD Contacting 02");
        assertLikeOrValueLeaf(arr.get(1), "PTCDisplayName", "EQ", "ORD Contacting 02");
        assertLikeOrValueLeaf(arr.get(2), "PTCSerialNumber", "EQ", "ORD Contacting 02");
    }

    private static void assertLikeOrValueLeaf(JsonNode leaf, String field, String type, String value) {
        assertEquals(field, leaf.get("fieldName").asText());
        assertEquals(type, leaf.get("type").asText());
        assertEquals(value, leaf.get("value").asText());
        assertEquals(false, leaf.get("isCaseSensitive").asBoolean());
    }

    @Test
    void wildcardInText_suffixLikeEscapesPercentStarQuestionAndBackslash() {
        AssetTypeEntry type = workunitRule().toSyntheticAssetTypeEntry(0, List.of(), null);
        JsonNode root = TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, "SN%1528465");
        assertNotNull(root);
        JsonNode arr = root.get("filters").get("filters");
        assertLikeOrValueLeaf(arr.get(0), "name", "LIKE", "*SN\\%1528465");
        assertLikeOrValueLeaf(arr.get(1), "PTCDisplayName", "EQ", "SN%1528465");
        assertLikeOrValueLeaf(arr.get(2), "PTCSerialNumber", "EQ", "SN%1528465");
    }

    @Test
    void escapeTwSqlLikeSuffixUserPortion_escapesWildcardsAndBackslash() {
        assertEquals("a\\%b\\*c\\\\d", TaxonomyIdentityV3QitQuery.escapeTwSqlLikeSuffixUserPortion("a%b*c\\d"));
    }

    @Test
    void wildcardQuestionMarkInText_suffixLikeEscapesQuestionMark() {
        AssetTypeEntry type = workunitRule().toSyntheticAssetTypeEntry(0, List.of(), null);
        JsonNode root = TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, "SN?284");
        assertNotNull(root);
        JsonNode arr = root.get("filters").get("filters");
        assertLikeOrValueLeaf(arr.get(0), "name", "LIKE", "*SN\\?284");
        assertLikeOrValueLeaf(arr.get(1), "PTCDisplayName", "EQ", "SN?284");
        assertLikeOrValueLeaf(arr.get(2), "PTCSerialNumber", "EQ", "SN?284");
    }

    @Test
    void nullWhenUnsupportedMatchMode() {
        ThingIdentityRuleV3 rule = new ThingIdentityRuleV3("T",
                List.of(new ThingIdentityPropertyMatchV3("name", "contains")), List.of());
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        assertNull(TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, "x"));
    }

    @Test
    void nullForNonV3SyntheticEntry() {
        AssetTypeEntry type = new AssetTypeEntry("k", List.of(), "template_as_type", "thing", List.of(), "ThingTemplate",
                "SomeTemplate", List.of("name"), List.of("exact"), List.of(), null);
        assertNull(TaxonomyIdentityV3QitQuery.buildRootFiltersOr(type, "n"));
    }
}
