package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Offline parse checks aligned with {@code docs/agent/cached_tabular_golden.md} N-06 / malformed root
 * {@code arguments} (no ThingWorx {@code CachedTabularToolsExecutor} static init).
 */
class CachedTabularToolArgumentsJsonTest {

    @Test
    void readRoot_malformedObject_throwsJsonProcessingException() {
        assertThrows(JsonProcessingException.class, () -> CachedTabularToolArgumentsJson.readRoot("{not json"));
    }

    @Test
    void readRoot_arrayRoot_isValidJsonButNotObject() throws JsonProcessingException {
        JsonNode root = CachedTabularToolArgumentsJson.readRoot("[1,2,3]");
        assertTrue(root.isArray());
    }

    @Test
    void readRoot_null_isEmptyObject() {
        JsonNode root = assertDoesNotThrow(() -> CachedTabularToolArgumentsJson.readRoot(null));
        assertTrue(root.isObject());
        assertTrue(root.isEmpty());
    }

    @Test
    void readRoot_validObject() throws JsonProcessingException {
        JsonNode root = CachedTabularToolArgumentsJson.readRoot("{\"cacheId\":\"x\",\"mode\":\"sort_topn\"}");
        assertTrue(root.isObject());
        assertTrue(root.path("cacheId").isTextual());
    }
}
