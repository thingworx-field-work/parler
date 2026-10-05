package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * Rejection paths for {@link QueryJsonPrimitiveMapper} — these fail before ThingWorx {@code JSONPrimitive}
 * static initialization (which pulls security / Jackson wiring not present in plain JUnit). Success paths
 * (structured object → QUERY primitive) belong in integration or in-container tests.
 */
class InvokeServiceExecutorQueryPrimitiveTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void arrayRootRejected() throws Exception {
        var node = MAPPER.readTree("[1,2]");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
        assertTrue(ex.getMessage().contains("JSON object"));
    }

    @Test
    void numberRootRejected() throws Exception {
        var node = MAPPER.readTree("42");
        assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
    }

    @Test
    void booleanRootRejected() throws Exception {
        var node = MAPPER.readTree("true");
        assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
    }

    @Test
    void nullRejected() throws Exception {
        var node = MAPPER.readTree("null");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
        assertTrue(ex.getMessage().contains("null"));
    }

    @Test
    void textualNotStartingWithBraceRejected() {
        var node = new TextNode("not-json");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
        assertTrue(ex.getMessage().contains("{"));
    }

    @Test
    void textualArrayStringRejected() {
        var node = new TextNode("[1,2]");
        assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
    }

    @Test
    void textualWhitespaceOnlyRejected() {
        var node = new TextNode("   \t\n");
        assertThrows(IllegalArgumentException.class,
                () -> QueryJsonPrimitiveMapper.toJsonPrimitive("query", node, MAPPER));
    }
}
