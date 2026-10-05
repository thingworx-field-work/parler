package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.thingworx.relationships.RelationshipTypes;

/**
 * Asserts platform-derived root types (ThingworxEntityTypes) — requires ThingWorx SDK on test classpath.
 */
class ThingworxRootEntityTypesTest {

    @Test
    void sortedNamesIncludeCoreRootsExcludeDataTableRelationship() {
        List<String> names = ThingworxRootEntityTypes.sortedRootEntityTypeNames();
        assertNotNull(names);
        assertTrue(names.contains("Thing"));
        assertTrue(names.contains("ThingTemplate"));
        assertTrue(names.contains("Resource"));
        assertFalse(names.contains("DataTable"));
        assertFalse(names.contains("Stream"));
        assertFalse(names.contains("ValueStream"));
        assertFalse(names.contains("DataTableEntry"));
    }

    @Test
    void parseRootAcceptsThingCaseInsensitive() {
        assertNotNull(ThingworxRootEntityTypes.parseRootEntityType("thing"));
        assertTrue(ThingworxRootEntityTypes.isRootEntityType(RelationshipTypes.ThingworxRelationshipTypes.Thing));
        assertFalse(ThingworxRootEntityTypes.isRootEntityType(RelationshipTypes.ThingworxRelationshipTypes.DataTable));
    }
}
