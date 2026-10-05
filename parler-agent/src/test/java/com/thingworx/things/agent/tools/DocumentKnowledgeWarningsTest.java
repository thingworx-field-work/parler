package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DocumentKnowledgeWarningsTest {

    @Test
    void aggregates_counts_for_same_code() {
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        warnings.increment("CHUNK_LINE_INVALID_JSON", "invalid lines", 3);
        warnings.increment("CHUNK_LINE_INVALID_JSON", "invalid lines", 2);
        assertEquals(1, warnings.toJsonList().size());
        assertEquals(5, warnings.toJsonList().get(0).get("count"));
    }

    @Test
    void caps_distinct_codes() {
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        for (int i = 0; i < 25; i++) {
            warnings.add("CODE_" + i, "message " + i);
        }
        assertEquals(20, warnings.toJsonList().size());
    }

    @Test
    void empty_produces_no_warnings() {
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        assertTrue(warnings.isEmpty());
        assertEquals(0, warnings.toJsonList().size());
    }
}
