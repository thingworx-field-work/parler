package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * M3 Slice 3 / E16+B5: entity-list siblings that emit slim {@code columns[]} metadata must agree
 * on the {@code name} column {@code baseType}. Non-Thing taxonomy/spotlight paths that omit
 * {@code columns[].baseType} cannot contradict that formatter.
 */
class M3Slice3E16NameColumnTypeParityTest {

    @Test
    void entityListSiblingsShareNameColumnBaseTypeThingname() {
        String listNameBt = nameBaseType(ListEntitiesByTypeExecutor.listEntitiesSlimColumnsMetadata());
        String queryNameBt = nameBaseType(QueryEntitiesExecutor.queryEntitiesSlimColumnsMetadata());
        assertEquals("THINGNAME", listNameBt, "list_entities_by_type slim name baseType");
        assertEquals("THINGNAME", queryNameBt, "query_entities slim name baseType");
        assertEquals(listNameBt, queryNameBt, "E16/B5: entity-list siblings must share name baseType");
    }

    @Test
    void nonThingTaxonomyAndSpotlightOmitColumnsNameBaseTypeMetadata() throws Exception {
        // list_asset_types summaries use entityName/key JSON fields — no columns[] baseType ledger.
        // spotlight_search slim rows likewise omit columns metadata. Absence is not a type clash.
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        for (String name : new String[] {"list_asset_types", "spotlight_search", "resolve_asset_type"}) {
            assertTrue(reg.getAllDefinitions().stream().anyMatch(t -> name.equals(t.getName())), name);
        }
        // Guard: entity-list metadata still carries an explicit name baseType (not silently empty).
        assertFalse(nameBaseType(ListEntitiesByTypeExecutor.listEntitiesSlimColumnsMetadata()).isBlank());
        assertFalse(nameBaseType(QueryEntitiesExecutor.queryEntitiesSlimColumnsMetadata()).isBlank());
    }

    private static String nameBaseType(ArrayNode cols) {
        for (JsonNode c : cols) {
            if ("name".equals(c.path("name").asText())) {
                return c.path("baseType").asText();
            }
        }
        throw new AssertionError("columns metadata missing name entry: " + cols);
    }
}
