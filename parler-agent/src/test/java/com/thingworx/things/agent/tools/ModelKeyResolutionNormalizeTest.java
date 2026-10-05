package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.relationships.RelationshipTypes;

/** Offline tests for {@link ModelKeyResolutionNormalize} (no ThingWorx {@link ModelKeyResolver} / logging init). */
class ModelKeyResolutionNormalizeTest {

    @Test
    void normalizePhase0_collapsesWhitespaceAndSeparators() {
        assertEquals("datatable", ModelKeyResolutionNormalize.normalizePhase0("Data Table"));
        assertEquals("datatable", ModelKeyResolutionNormalize.normalizePhase0("data-table"));
        assertEquals("stackingrobot", ModelKeyResolutionNormalize.normalizePhase0("Stacking Robot"));
    }

    @Test
    void synonymEqualityRequiresExactNormalizedMatch_noSubstring() {
        String nu = ModelKeyResolutionNormalize.normalizePhase0("robot");
        assertTrue(ModelKeyResolutionNormalize.synonymsMatchNormalizedUser(nu, "stacking robot;robot"));
        assertFalse(ModelKeyResolutionNormalize.synonymsMatchNormalizedUser(nu, "stacking robot"));
    }

    @Test
    void hints_match_key_resolution_spec() {
        assertEquals("DataTable", ModelKeyResolutionNormalize.canonicalTemplateHint("datatable"));
        assertEquals("Stream", ModelKeyResolutionNormalize.canonicalTemplateHint("stream"));
        assertEquals("ValueStream", ModelKeyResolutionNormalize.canonicalTemplateHint("valuestream"));
    }

    @Test
    void rankCandidate_prefersExactCaseSensitive() {
        assertEquals(1, ModelKeyResolutionNormalize.rankCandidate("Stream", "Stream", "stream"));
        assertEquals(2, ModelKeyResolutionNormalize.rankCandidate("Stream", "stream", "stream"));
        assertEquals(3, ModelKeyResolutionNormalize.rankCandidate("Stream", "str", "stream"));
    }

    @Test
    void normalizePhase0_nullReturnsEmpty() {
        assertEquals("", ModelKeyResolutionNormalize.normalizePhase0(null));
    }

    @Test
    void normalizePhase0_blankReturnsEmpty() {
        assertEquals("", ModelKeyResolutionNormalize.normalizePhase0(""));
        assertEquals("", ModelKeyResolutionNormalize.normalizePhase0("   "));
    }

    @Test
    void canonicalTemplateHint_unknownReturnsNull() {
        assertNull(ModelKeyResolutionNormalize.canonicalTemplateHint("notaplatformtemplate"));
        assertNull(ModelKeyResolutionNormalize.canonicalTemplateHint(""));
        assertNull(ModelKeyResolutionNormalize.canonicalTemplateHint(null));
    }

    @Test
    void rankCandidate_substringEitherWayIsRank4() {
        assertEquals(4, ModelKeyResolutionNormalize.rankCandidate("Northwind_KPI_Stream_ThingTemplate", "northwind",
                "northwind"));
        assertEquals(4, ModelKeyResolutionNormalize.rankCandidate("KPI", "northwind_kpi_stream_thingtemplate", "northwindkpistreamthingtemplate"));
    }

    @Test
    void rankCandidate_noMatchIsRank99() {
        assertEquals(99, ModelKeyResolutionNormalize.rankCandidate("ZZZUnrelated", "foo", "foo"));
        assertEquals(99, ModelKeyResolutionNormalize.rankCandidate(null, "a", "a"));
    }

    @Test
    void synonymsMatchNormalizedUser_trimsFragmentsAndIgnoresEmptySegments() {
        String nu = ModelKeyResolutionNormalize.normalizePhase0("robot");
        assertTrue(ModelKeyResolutionNormalize.synonymsMatchNormalizedUser(nu, "  robot  ;  "));
        assertTrue(ModelKeyResolutionNormalize.synonymsMatchNormalizedUser(nu, "stacking robot;; robot"));
    }

    @Test
    void parseTaxonomyEntityType_mapsTemplateAndShape() {
        assertEquals(RelationshipTypes.ThingworxRelationshipTypes.ThingTemplate,
                ModelKeyResolutionNormalize.parseTaxonomyEntityType("ThingTemplate"));
        assertEquals(RelationshipTypes.ThingworxRelationshipTypes.ThingShape,
                ModelKeyResolutionNormalize.parseTaxonomyEntityType("ThingShape"));
        assertNull(ModelKeyResolutionNormalize.parseTaxonomyEntityType("Thing"));
        assertNull(ModelKeyResolutionNormalize.parseTaxonomyEntityType(null));
    }

    @Test
    void taxonomyMatchedRowBlockingDetail_requiresEntityName() {
        String d = ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", "ThingTemplate", null);
        assertNotNull(d);
        assertTrue(d.contains("EntityName"));
        assertNotNull(ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", "ThingTemplate", "  "));
    }

    @Test
    void taxonomyMatchedRowBlockingDetail_requiresEntityType() {
        assertNotNull(ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", null, "T1"));
        assertNotNull(ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", "", "T1"));
    }

    @Test
    void taxonomyMatchedRowBlockingDetail_rejectsNonTemplateShapeEntityType() {
        String d = ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", "Thing", "T1");
        assertNotNull(d);
        assertTrue(d.contains("ThingTemplate"));
    }

    @Test
    void taxonomyMatchedRowBlockingDetail_nullWhenRowWellFormed() {
        assertNull(ModelKeyResolutionNormalize.taxonomyMatchedRowBlockingDetail("robot", "ThingTemplate", "MyT"));
    }
}
