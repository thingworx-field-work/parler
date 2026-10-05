package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class AlertHistorySortOrderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void orderOldestFirstAlone() throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("order", "oldest_first");
        assertTrue(AlertHistorySortOrder.resolveOldestFirst(n));
    }

    @Test
    void orderNewestFirstAlone() throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("order", "newest_first");
        assertFalse(AlertHistorySortOrder.resolveOldestFirst(n));
    }

    @Test
    void legacyOldestFirstWhenOrderAbsent() throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("oldestFirst", true);
        assertTrue(AlertHistorySortOrder.resolveOldestFirst(n));
    }

    @Test
    void orderAndOldestFirstAgree() throws Exception {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("order", "oldest_first");
        n.put("oldestFirst", true);
        assertTrue(AlertHistorySortOrder.resolveOldestFirst(n));
    }

    @Test
    void orderAndOldestFirstConflict() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("order", "newest_first");
        n.put("oldestFirst", true);
        assertThrows(IllegalArgumentException.class, () -> AlertHistorySortOrder.resolveOldestFirst(n));
    }

    @Test
    void unknownOrderThrows() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("order", "by_priority");
        assertThrows(IllegalArgumentException.class, () -> AlertHistorySortOrder.resolveOldestFirst(n));
    }
}
