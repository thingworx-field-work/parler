package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

class TabularPagingEchoTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void clampsNegativeOffsetAndOvershoot() {
        TabularPagingEcho.Page p = TabularPagingEcho.resolve(-5, 50, 50, 200, 10);
        assertEquals(-5, p.offsetRequested);
        assertEquals(0, p.offsetEffective);
        assertEquals(10, p.returnedRows);
        assertFalse(p.hasMore);
    }

    @Test
    void clampsLimitCapAndEchoesRequested() {
        TabularPagingEcho.Page p = TabularPagingEcho.resolve(0, 999, 50, 200, 1000);
        assertEquals(999, p.limitRequested);
        assertEquals(200, p.limitEffective);
        assertEquals(200, p.returnedRows);
        assertTrue(p.hasMore);
    }

    @Test
    void putOnWritesSharedKeys() {
        TabularPagingEcho.Page p = TabularPagingEcho.resolve(8, 3, 50, 200, 20);
        ObjectNode out = MAPPER.createObjectNode();
        TabularPagingEcho.putOn(out, p);
        assertEquals(8, out.path("offsetRequested").asInt());
        assertEquals(8, out.path("offsetEffective").asInt());
        assertEquals(3, out.path("limitEffective").asInt());
        assertEquals(3, out.path("returnedRows").asInt());
        assertEquals(20, out.path("totalRows").asInt());
        assertTrue(out.path("hasMore").asBoolean());
        assertEquals(8, out.path("offset").asInt());
        assertEquals(3, out.path("limit").asInt());
    }
}
