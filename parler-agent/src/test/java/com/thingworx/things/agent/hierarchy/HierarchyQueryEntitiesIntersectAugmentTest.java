package com.thingworx.things.agent.hierarchy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class HierarchyQueryEntitiesIntersectAugmentTest {

    @AfterEach
    void tearDown() {
        HierarchyQueryEntitiesIntersectAugment.setAssetListInvokerOverrideForTests(null);
    }

    @Test
    void explicitIntersectThingNames_skipsAugment() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.putArray("intersectThingNames").add("Pump-01");
        root.put("hierarchyNodeName", "USA");
        assertNull(HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyNodeName(root));
        assertEquals("USA", root.get("hierarchyNodeName").asText());
    }

    @Test
    void blankHierarchyNodeName_noOp() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("hierarchyNodeName", "   ");
        assertNull(HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyNodeName(root));
    }

    @Test
    void hierarchyNodeId_withoutAgent_returnsErrorNotUnscoped() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("hierarchyNodeId", "SE.CellFab.Model.Region.Germany");
        String err = HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyScope(root);
        assertNotNull(err);
        assertTrue(err.contains("HIERARCHY_ASSET_LIST_FAILED"));
        assertTrue(err.contains("Agent context unavailable"));
        assertTrue(root.has("hierarchyNodeId"));
        assertFalse(root.has("intersectThingNames"));
    }

    @Test
    void hierarchyNodeId_precedence_overName_withoutAgent_errorsOnIdPath() {
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("hierarchyNodeId", "node-id-direct");
        root.put("hierarchyNodeName", "Germany");
        String err = HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyScope(root);
        assertNotNull(err);
        assertTrue(err.contains("node-id-direct"));
        assertTrue(root.has("hierarchyNodeName"));
    }

    @Test
    void hierarchyNodeId_happyPath_injectsIntersectThingNames() {
        HierarchyQueryEntitiesIntersectAugment.setAssetListInvokerOverrideForTests((agent, id) -> {
            InfoTable table = new InfoTable();
            ValueCollection row = new ValueCollection();
            row.put("name", new StringPrimitive("Pump-01"));
            table.addRow(row);
            return table;
        });
        ObjectNode root = JsonNodeFactory.instance.objectNode();
        root.put("hierarchyNodeId", "SE.CellFab.Model.Region.Germany");
        String err = HierarchyQueryEntitiesIntersectAugment.maybeInjectIntersectFromHierarchyScope(root);
        assertNull(err);
        ArrayNode names = (ArrayNode) root.get("intersectThingNames");
        assertNotNull(names);
        assertEquals(1, names.size());
        assertEquals("Pump-01", names.get(0).asText());
        assertFalse(root.has("hierarchyNodeId"));
        assertFalse(root.has("hierarchyNodeName"));
    }
}
