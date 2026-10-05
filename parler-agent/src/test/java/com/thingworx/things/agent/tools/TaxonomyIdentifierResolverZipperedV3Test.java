package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.taxonomy.AssetTypeEntry;
import com.thingworx.things.agent.taxonomy.ThingIdentityPropertyMatchV3;
import com.thingworx.things.agent.taxonomy.ThingIdentityRuleV3;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/** v3 zippered identity: OR across property/match pairs. */
class TaxonomyIdentifierResolverZipperedV3Test {

    private static ThingIdentityRuleV3 triplePropertyRule() {
        return new ThingIdentityRuleV3("PTC.MfgModel.DefaultWorkunit_TT",
                List.of(new ThingIdentityPropertyMatchV3("name", "suffix"),
                        new ThingIdentityPropertyMatchV3("PTCDisplayName", "equals"),
                        new ThingIdentityPropertyMatchV3("PTCSerialNumber", "equals")),
                List.of());
    }

    @Test
    void zipperedOr_matchesOnNameSuffixWhenOtherColumnsDoNotMatch() {
        ThingIdentityRuleV3 rule = triplePropertyRule();
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("PrefixFoo"));
        row.put("PTCDisplayName", new StringPrimitive("wrong"));
        row.put("PTCSerialNumber", new StringPrimitive("wrong"));
        List<TaxonomyIdentifierResolver.Candidate> out = TaxonomyIdentifierResolver.matchFromProjectedRowsZipperedForTest(
                type, "Foo", List.of(row),
                List.of("name", "PTCDisplayName", "PTCSerialNumber"), List.of("suffix", "equals", "equals"), List.of());
        assertEquals(1, out.size());
        assertEquals("PrefixFoo", out.get(0).name);
        assertEquals("name", out.get(0).matchedField);
        assertEquals("suffix", out.get(0).matchedRule);
    }

    @Test
    void zipperedOr_matchesOnDisplayNameEquals() {
        ThingIdentityRuleV3 rule = triplePropertyRule();
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("X"));
        row.put("PTCDisplayName", new StringPrimitive("ExactLabel"));
        row.put("PTCSerialNumber", new StringPrimitive("SN1"));
        List<TaxonomyIdentifierResolver.Candidate> out = TaxonomyIdentifierResolver.matchFromProjectedRowsZipperedForTest(
                type, "ExactLabel", List.of(row),
                List.of("name", "PTCDisplayName", "PTCSerialNumber"), List.of("suffix", "equals", "equals"), List.of());
        assertEquals(1, out.size());
        assertEquals("PTCDisplayName", out.get(0).matchedField);
        assertEquals("equals", out.get(0).matchedRule);
    }

    @Test
    void zipperedOr_matchesOnSerialEqualsWhenEarlierPairsFail() {
        ThingIdentityRuleV3 rule = triplePropertyRule();
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("NoSuffixHere"));
        row.put("PTCDisplayName", new StringPrimitive("other"));
        row.put("PTCSerialNumber", new StringPrimitive("SERIAL-99"));
        List<TaxonomyIdentifierResolver.Candidate> out = TaxonomyIdentifierResolver.matchFromProjectedRowsZipperedForTest(
                type, "SERIAL-99", List.of(row),
                List.of("name", "PTCDisplayName", "PTCSerialNumber"), List.of("suffix", "equals", "equals"), List.of());
        assertEquals(1, out.size());
        assertEquals("PTCSerialNumber", out.get(0).matchedField);
        assertEquals("equals", out.get(0).matchedRule);
    }

    @Test
    void zipperedOr_equalsIsCaseInsensitive() {
        ThingIdentityRuleV3 rule = triplePropertyRule();
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("X"));
        row.put("PTCDisplayName", new StringPrimitive("ExactLabel"));
        row.put("PTCSerialNumber", new StringPrimitive("SN1"));
        List<TaxonomyIdentifierResolver.Candidate> out = TaxonomyIdentifierResolver.matchFromProjectedRowsZipperedForTest(
                type, "exactlabel", List.of(row),
                List.of("name", "PTCDisplayName", "PTCSerialNumber"), List.of("suffix", "equals", "equals"), List.of());
        assertEquals(1, out.size());
        assertEquals("PTCDisplayName", out.get(0).matchedField);
        assertEquals("equals", out.get(0).matchedRule);
    }

    @Test
    void zipperedOr_suffixIsCaseInsensitive() {
        ThingIdentityRuleV3 rule = triplePropertyRule();
        AssetTypeEntry type = rule.toSyntheticAssetTypeEntry(0, List.of(), null);
        ValueCollection row = new ValueCollection();
        row.put("name", new StringPrimitive("PrefixFOO"));
        row.put("PTCDisplayName", new StringPrimitive("wrong"));
        row.put("PTCSerialNumber", new StringPrimitive("wrong"));
        List<TaxonomyIdentifierResolver.Candidate> out = TaxonomyIdentifierResolver.matchFromProjectedRowsZipperedForTest(
                type, "foo", List.of(row),
                List.of("name", "PTCDisplayName", "PTCSerialNumber"), List.of("suffix", "equals", "equals"), List.of());
        assertEquals(1, out.size());
        assertEquals("name", out.get(0).matchedField);
        assertEquals("suffix", out.get(0).matchedRule);
    }

    @Test
    void thingShapeAssetFilterProducesShapeAsTypeWithTemplateQueryParent() {
        ThingIdentityRuleV3 rule = new ThingIdentityRuleV3("My.Template",
                List.of(new ThingIdentityPropertyMatchV3("name", "equals")), List.of());
        AssetTypeEntry filter = new AssetTypeEntry("cuttingKey", List.of(), "shape_as_type", "asset", List.of(),
                "ThingShape", "CuttingShape", List.of("name"), List.of("equals"), List.of("name"), null);
        AssetTypeEntry syn = rule.toSyntheticAssetTypeEntry(0, List.of("name"), filter);
        assertEquals("shape_as_type", syn.representation());
        assertEquals("ThingShape", syn.parentEntityType());
        assertEquals("CuttingShape", syn.parentEntityName());
        assertNotNull(syn.queryParent());
        assertEquals("ThingTemplate", syn.queryParent().entityType());
        assertEquals("My.Template", syn.queryParent().entityName());
    }
}
