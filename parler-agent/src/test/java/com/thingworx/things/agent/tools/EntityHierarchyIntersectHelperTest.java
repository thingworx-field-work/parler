package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

class EntityHierarchyIntersectHelperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseIntersect_omits_when_absent_or_empty() throws Exception {
        assertTrue(EntityHierarchyIntersectHelper.parseIntersectThingNames(MAPPER.readTree("{}")) == null);
        ObjectNode o = MAPPER.createObjectNode();
        o.putArray("intersectThingNames");
        assertTrue(EntityHierarchyIntersectHelper.parseIntersectThingNames(o) == null);
    }

    @Test
    void parseIntersect_collects_strings() throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        ArrayNode a = o.putArray("intersectThingNames");
        a.add("Pump-1");
        a.add("Pump-2");
        Set<String> s = EntityHierarchyIntersectHelper.parseIntersectThingNames(o);
        assertEquals(2, s.size());
        assertTrue(s.contains("Pump-1"));
    }

    @Test
    void parseIntersect_rejects_over_cap() {
        ObjectNode o = MAPPER.createObjectNode();
        ArrayNode a = o.putArray("intersectThingNames");
        for (int i = 0; i < EntityHierarchyIntersectHelper.MAX_INTERSECT_NAMES + 1; i++) {
            a.add("x");
        }
        assertThrows(IllegalArgumentException.class, () -> EntityHierarchyIntersectHelper.parseIntersectThingNames(o));
    }

    @Test
    void parseExpandHasMore_default_false() throws Exception {
        assertFalse(EntityHierarchyIntersectHelper.parseIntersectExpandHasMore(MAPPER.readTree("{}")));
        ObjectNode o = MAPPER.createObjectNode();
        o.put("intersectExpandHasMore", true);
        assertTrue(EntityHierarchyIntersectHelper.parseIntersectExpandHasMore(o));
    }

    @Test
    void computeQuerySideHasMore_uses_pre_intersect_page_size() {
        assertFalse(EntityHierarchyIntersectHelper.computeQuerySideHasMore(0, 6, 6L, 50));
        assertFalse(EntityHierarchyIntersectHelper.computeQuerySideHasMore(0, 6, 6L, 200));
        assertTrue(EntityHierarchyIntersectHelper.computeQuerySideHasMore(0, 50, 100L, 50));
        assertTrue(EntityHierarchyIntersectHelper.computeQuerySideHasMore(0, 50, null, 50));
        assertFalse(EntityHierarchyIntersectHelper.computeQuerySideHasMore(0, 5, null, 50));
    }

    @Test
    void parseIntersect_detailed_counts_dropped_entries() throws Exception {
        ObjectNode o = MAPPER.createObjectNode();
        ArrayNode a = o.putArray("intersectThingNames");
        a.add("A");
        a.add("");
        a.addNull();
        a.add(3);
        a.add("B");
        EntityHierarchyIntersectHelper.IntersectThingNamesParse p =
                EntityHierarchyIntersectHelper.parseIntersectThingNamesDetailed(o);
        assertEquals(2, p.namesOrNull().size());
        assertEquals(3, p.droppedEmptyOrNonTextCount());
    }

    @Test
    void writeIntersect_success_fields_when_active() {
        ObjectNode out = MAPPER.createObjectNode();
        out.put("status", "success");
        EntityHierarchyIntersectHelper.writeIntersectSuccessFields(out, true, 3, 1, false, true);
        assertEquals(3, out.get("preIntersectMatchCount").asInt());
        assertEquals(1, out.get("intersectedRowCount").asInt());
        assertFalse(out.get("queryHasMore").asBoolean());
        assertTrue(out.get("expandHasMore").asBoolean());
        assertTrue(out.get("hasMore").asBoolean());
    }

    @Test
    void writeIntersect_hasMoreOrsCompletenessUnknown() {
        ObjectNode out = MAPPER.createObjectNode();
        EntityHierarchyIntersectHelper.writeIntersectSuccessFields(out, true, 3, 1, false, false, true);
        assertFalse(out.get("queryHasMore").asBoolean());
        assertFalse(out.get("expandHasMore").asBoolean());
        assertTrue(out.get("hasMore").asBoolean());
    }

    @Test
    void writeIntersect_success_fields_skipped_when_inactive() {
        ObjectNode out = MAPPER.createObjectNode();
        EntityHierarchyIntersectHelper.writeIntersectSuccessFields(out, false, 1, 1, true, true);
        assertFalse(out.has("preIntersectMatchCount"));
        // Intersect writer remains a no-op off the intersect path; non-intersect E1 vocabulary
        // is emitted by BoundedQueryCompleteness.writeNonIntersectFields instead.
        assertFalse(out.has("hasMore"));
        assertFalse(out.has("truncated"));
        assertFalse(out.has("totalUnderlyingCount"));
        assertFalse(out.has("queryHasMore"));
        assertFalse(out.has("expandHasMore"));
    }
}
