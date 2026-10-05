package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

class PlaybookSlashParserTest {

    @Test
    void freeText_clarification_usesPairParamsForCrossAssetPairHealth() {
        PlaybookSlashParser.Result r = PlaybookSlashParser.parse(
                "/cross_asset_pair_health Compare two robots",
                Set.of(PlaybookIds.CROSS_REGION_HEALTH_ID, PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID));
        assertTrue(r.clarificationMessage().contains("assetIdentifierA"));
        assertFalse(r.clarificationMessage().contains("\"regions\""));
    }

    @Test
    void freeText_clarification_usesRegionsForCrossRegionHealth() {
        PlaybookSlashParser.Result r = PlaybookSlashParser.parse(
                "/cross_region_health Compare USA and Germany",
                Set.of(PlaybookIds.CROSS_REGION_HEALTH_ID, PlaybookIds.CROSS_ASSET_PAIR_HEALTH_ID));
        assertTrue(r.clarificationMessage().contains("regions"));
    }
}
