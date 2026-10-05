package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class CachedTabularTabulatePagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void sortTopn_omittedMaxItemsOffset_defaults() {
        ObjectNode root = MAPPER.createObjectNode();
        assertArrayEquals(new int[] {50, 0},
                CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT));
    }

    @Test
    void sortTopn_explicitMaxItemsOffset() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 10);
        root.put("offset", 3);
        assertArrayEquals(new int[] {10, 3},
                CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT));
    }

    @Test
    void sortTopn_maxItemsZero_throws() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 0);
        assertThrows(IllegalArgumentException.class,
                () -> CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT));
    }

    @Test
    void sortTopn_maxItems501_throws() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 501);
        assertThrows(IllegalArgumentException.class,
                () -> CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT));
    }

    @Test
    void sortTopn_negativeOffset_throws() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("offset", -1);
        assertThrows(IllegalArgumentException.class,
                () -> CachedTabularTabulatePaging.parseMaxItemsAndOffset(root, CachedTabularTabulatePaging.SORT_TOPN_DEFAULT_LIMIT));
    }

    @Test
    void groupOutput_omittedMaxItems_returns500() {
        ObjectNode root = MAPPER.createObjectNode();
        assertEquals(500, CachedTabularTabulatePaging.parseGroupOutputMaxItems(root));
    }

    @Test
    void groupOutput_explicitMaxItems() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 100);
        assertEquals(100, CachedTabularTabulatePaging.parseGroupOutputMaxItems(root));
    }

    @Test
    void groupOutput_maxItemsTooHigh_throws() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("maxItems", 501);
        assertThrows(IllegalArgumentException.class, () -> CachedTabularTabulatePaging.parseGroupOutputMaxItems(root));
    }
}
