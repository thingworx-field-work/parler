package com.thingworx.things.agent.semantics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.taxonomy.ApplicationSemanticTaxonomySnapshot;
import com.thingworx.things.agent.taxonomy.TaxonomyAssetTypeProof;
import com.thingworx.things.agent.taxonomy.TaxonomyV3JsonParsers;

/**
 * U3S M3 taxonomy regression: profile {@code assetTypeKey} joins cellfab-v3 taxonomy by exact key
 * only; proof + scoped resolve stay within that key.
 */
class SemanticProfileTaxonomyJoinRegressionTest {

    @Test
    void cellfabKeys_exactJoin_andScopedResolve() throws Exception {
        String assets = readResource("/taxonomy/cellfab-v3/asset-types.json");
        TaxonomyV3JsonParsers.AssetTypesOutcome assOut =
                TaxonomyV3JsonParsers.parseAssetTypesObject(assets, NOPLogger.NOP_LOGGER, "test");
        assertTrue(assOut.valid(), assOut.diagnostics().toString());

        Set<String> knownKeys = assOut.entries().stream().map(e -> e.key())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertTrue(knownKeys.contains("Stacking Robot"));
        assertTrue(knownKeys.contains("Sealing"));

        ApplicationSemanticTaxonomySnapshot taxonomy = ApplicationSemanticTaxonomySnapshot.loaded(
                assOut.entries(), java.util.List.of(), false, Instant.now(),
                "/taxonomies/asset-types.json");

        String profileJson = "{"
                + "\"schema\":\"parler-semantic-profile-v1\","
                + "\"profileId\":\"cellfab-join\","
                + "\"version\":\"1\","
                + "\"assetTypes\":{"
                + "\"Stacking Robot\":{\"propertyRoles\":{"
                + "\"operating_temperature\":{\"binding\":{\"kind\":\"PROPERTY\",\"propertyName\":\"Wrst1\"},"
                + "\"unit\":\"Cel\",\"dimension\":\"temperature\",\"grain\":\"sample\"}}}"
                + "}}";

        SemanticProfileBuilder.ParseOutcome badAliasKey =
                SemanticProfileBuilder.parse(profileJson.replace("Stacking Robot", "StackingRobot"), Instant.now(),
                        knownKeys);
        assertFalse(badAliasKey.valid());
        assertTrue(badAliasKey.diagnostics().stream()
                .anyMatch(d -> "SEMANTIC_PROFILE_TAXONOMY_REF".equals(d.code())));

        SemanticProfileBuilder.ParseOutcome good =
                SemanticProfileBuilder.parse(profileJson, Instant.now(), knownKeys);
        assertTrue(good.valid(), good.diagnostics().toString());
        SemanticProfileSnapshot profile = good.snapshot();

        Optional<String> proven = TaxonomyAssetTypeProof.proveExactAssetTypeKey(taxonomy,
                e -> "Stacking Robot".equals(e.key()));
        assertEquals(Optional.of("Stacking Robot"), proven);

        SemanticResolveResult underProven = SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile,
                proven.get(), "Wrst1");
        assertEquals(SemanticResolveStatus.RESOLVED, underProven.status());
        assertEquals("Stacking Robot.propertyRole.operating_temperature", underProven.propertyRoleRef());

        SemanticResolveResult underWrongType =
                SemanticSourceHandoff.resolvePropertyBindingUnderAssetType(profile, "Sealing", "Wrst1");
        assertEquals(SemanticResolveStatus.NOT_FOUND, underWrongType.status());
    }

    private static String readResource(String path) throws Exception {
        try (InputStream in = SemanticProfileTaxonomyJoinRegressionTest.class.getResourceAsStream(path)) {
            Objects.requireNonNull(in, path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
