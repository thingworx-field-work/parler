package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

/**
 * ThingShape {@code assetTypeKey} must not require the rule's {@code baseThingTemplate} to
 * {@code implementsShape} the asset shape — intersection is row-level after QIT.
 */
class TaxonomyResolverThingShapeRuleRegressionTest {

    @Test
    void contactingAssetRow_passesFilter_andBuildsShapeSyntheticWithTemplateQueryParent() throws Exception {
        String identity = new String(getClass().getResourceAsStream("/taxonomy/cellfab-v3/identity-types.json")
                .readAllBytes(), StandardCharsets.UTF_8);
        String assets = new String(getClass().getResourceAsStream("/taxonomy/cellfab-v3/asset-types.json").readAllBytes(),
                StandardCharsets.UTF_8);
        TaxonomyV3JsonParsers.IdentityRulesOutcome idOut =
                TaxonomyV3JsonParsers.parseIdentityRulesArray(identity, NOPLogger.NOP_LOGGER, "test");
        TaxonomyV3JsonParsers.AssetTypesOutcome assOut =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assets, NOPLogger.NOP_LOGGER, "test");
        assertTrue(idOut.valid(), idOut.diagnostics().toString());
        assertTrue(assOut.valid(), assOut.diagnostics().toString());
        ThingIdentityRuleV3 rule = idOut.rules().get(0);
        AssetTypeEntry contacting = assOut.entries().stream().filter(e -> "Contacting".equals(e.key())).findFirst()
                .orElseThrow();
        assertTrue(TaxonomyResolverExecutor.ruleMatchesAssetFilter(rule, contacting));
        AssetTypeEntry syn = rule.toSyntheticAssetTypeEntry(0, contacting.criticalProperties(), contacting);
        assertEquals("shape_as_type", syn.representation());
        assertEquals("ThingShape", syn.parentEntityType());
        assertEquals("PTCTDD.CellfabDataset.Contacting_TS", syn.parentEntityName());
        assertNotNull(syn.queryParent());
        assertEquals("ThingTemplate", syn.queryParent().entityType());
        assertEquals("PTC.MfgModel.DefaultWorkunit_TT", syn.queryParent().entityName());
    }
}
