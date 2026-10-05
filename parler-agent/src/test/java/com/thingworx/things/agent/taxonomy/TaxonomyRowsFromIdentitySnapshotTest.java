package com.thingworx.things.agent.taxonomy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

import com.thingworx.things.agent.TaxonomyRow;
import com.thingworx.things.agent.tools.ModelKeyResolutionNormalize;

class TaxonomyRowsFromIdentitySnapshotTest {

    @Test
    void fromMinimalFixture_exactTypeKeyMatchesSynonyms() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/taxonomy/identity-types-minimal.json")) {
            assertTrue(in != null);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        assertTrue(out.configValid(), out.diagnostics().toString());
        ApplicationSemanticTaxonomySnapshot sem =
                ApplicationSemanticTaxonomySnapshot.loaded(out.entries(), List.of(), false, Instant.now(), "test");
        List<TaxonomyRow> rows = TaxonomyRowsFromIdentitySnapshot.build(sem);
        String nu = ModelKeyResolutionNormalize.normalizePhase0("Stacking Robot");
        TaxonomyRow stacking = rows.stream().filter(r -> "Stacking Robot".equals(r.getAssetType())).findFirst().orElseThrow();
        assertTrue(stacking.synonymsMatchNormalizedUser(nu), "normalized type key must match Phase −1");

        TaxonomyRow workunit = rows.stream().filter(r -> "Workunit Jet Dryer".equals(r.getAssetType())).findFirst().orElseThrow();
        String wk = ModelKeyResolutionNormalize.normalizePhase0("Workunit Jet Dryer");
        assertTrue(workunit.synonymsMatchNormalizedUser(wk));
    }

    @Test
    void fromMinimalFixture_entityAliasEquipment_notInTypeRowSynonyms() throws Exception {
        String json;
        try (InputStream in = getClass().getResourceAsStream("/taxonomy/identity-types-minimal.json")) {
            assertTrue(in != null);
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        IdentityTypesJsonParser.ParseOutcome out =
                IdentityTypesJsonParser.parse(json, NOPLogger.NOP_LOGGER, "test-agent", false);
        ApplicationSemanticTaxonomySnapshot sem =
                ApplicationSemanticTaxonomySnapshot.loaded(out.entries(), List.of(), false, Instant.now(), "test");
        List<TaxonomyRow> rows = TaxonomyRowsFromIdentitySnapshot.build(sem);
        String equipment = ModelKeyResolutionNormalize.normalizePhase0("equipment");
        int matches = 0;
        for (TaxonomyRow r : rows) {
            if (r.synonymsMatchNormalizedUser(equipment)) {
                matches++;
            }
        }
        assertEquals(0, matches, "entity-level aliases must not widen Phase −1 synonym sets");
    }
}
