package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.agent.PromptContextCacheSnapshot;

class ServiceTargetEntityTypeResolverTest {

    @Test
    void dataTableTemplateNameNormalizesToThing() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolveWithGenericThingNames(
                "DataTable", List.of("DataTable", "Stream"));
        assertFalse(r.isError());
        assertEquals(RelationshipTypes.ThingworxRelationshipTypes.Thing, r.getEffectiveRel());
        assertTrue(r.isNormalized());
        assertEquals("DataTable", r.getMatchedTemplateName());
        assertEquals(ServiceTargetEntityTypeResolver.REASON_GENERIC_THING_TEMPLATE_NAME, r.getNormalizedReason());
    }

    @Test
    void dataTableEntryUnsupportedWhenNotATemplateName() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolveWithGenericThingNames(
                "DataTableEntry", List.of("DataTable"));
        assertTrue(r.isError());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_SERVICE_TARGET", r.getErrorCode());
    }

    @Test
    void unknownEntityType() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolveWithGenericThingNames(
                "NotARealType_xyz", List.of("DataTable"));
        assertTrue(r.isError());
        assertEquals("UNKNOWN_ENTITY_TYPE", r.getErrorCode());
        assertTrue(r.getErrorMessage().contains("Thing"));
    }

    @Test
    void ambiguousCaseInsensitiveTemplateMatch() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolveWithGenericThingNames(
                "aa", Arrays.asList("Aa", "aA"));
        assertTrue(r.isError());
        assertEquals("AMBIGUOUS_GENERIC_THING_TEMPLATE_MATCH", r.getErrorCode());
    }

    @Test
    void thingRootUnchanged() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolveWithGenericThingNames(
                "Thing", List.of("DataTable"));
        assertFalse(r.isError());
        assertEquals(RelationshipTypes.ThingworxRelationshipTypes.Thing, r.getEffectiveRel());
        assertFalse(r.isNormalized());
    }

    @Test
    void normalizedMetadataNodeShape() {
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolution.ok(
                RelationshipTypes.ThingworxRelationshipTypes.Thing, "DataTable", true,
                ServiceTargetEntityTypeResolver.REASON_GENERIC_THING_TEMPLATE_NAME, "DataTable");
        assertNotNull(r.toEntityTypeNormalizedNode());
        assertEquals("DataTable", r.toEntityTypeNormalizedNode().get("from").asText());
        assertEquals("Thing", r.toEntityTypeNormalizedNode().get("to").asText());
    }

    @Test
    void resolveThingWithEmptySnapshotSkipsGenericThingList() {
        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot(
                "", Collections.emptyList(), Collections.emptyList(), "", "", Instant.now());
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolve("Thing", snap);
        assertFalse(r.isError());
        assertEquals(RelationshipTypes.ThingworxRelationshipTypes.Thing, r.getEffectiveRel());
        assertFalse(r.isNormalized());
    }

    @Test
    void resolveDataTableWithEmptySnapshotIsUnsupported() {
        PromptContextCacheSnapshot snap = new PromptContextCacheSnapshot(
                "", Collections.emptyList(), Collections.emptyList(), "", "", Instant.now());
        ServiceTargetEntityTypeResolution r = ServiceTargetEntityTypeResolver.resolve("DataTable", snap);
        assertTrue(r.isError());
        assertEquals("UNSUPPORTED_ENTITY_TYPE_FOR_SERVICE_TARGET", r.getErrorCode());
    }
}
