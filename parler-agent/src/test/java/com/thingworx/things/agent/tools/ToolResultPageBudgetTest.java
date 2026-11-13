package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.ResultBudget;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.ToolResultSettings;

/**
 * P38 page precedence (topic round 6, R24): a page has no exception. Pages are read through the real
 * {@code get_document_chunk} runtime over the synthetic {@code demo-guide} package; observes the gateway output.
 */
class ToolResultPageBudgetTest {

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
    }

    private static String page(String chunkId) throws Exception {
        DocumentKnowledgeSettings settings = DemoPageFixtures.settings(6_000);
        DocumentKnowledgeIndexCache.loadForTests(settings, DemoPageFixtures.AGENT, new DemoPageFixtures());
        return DocumentKnowledgeRuntime.executeGetDocumentChunk(DemoPageFixtures.getChunk("p-1", chunkId), settings,
                DemoPageFixtures.AGENT);
    }

    private static String egress(String raw, int softCap) {
        return ToolResultEgressGateway.compactForLlmAppend("get_document_chunk", "p-1", raw, null, List.of(),
                new ResultBudget(softCap, 2_000, "setting")).getLlmContent();
    }

    @Test
    void the_plain_page_arrives_whole() throws Exception {
        String raw = page("page-0001");
        assertTrue(raw.length() < 3_500);
        assertEquals(raw, egress(raw, 8_192));
    }

    @Test
    void the_escaping_page_is_reduced_with_the_note_at_8192_and_whole_at_16384() throws Exception {
        String raw = page("page-0002");
        assertTrue(raw.length() >= 9_000 && raw.length() <= 12_000, "serialized " + raw.length());
        assertTrue(ToolResultSettings.fromRaw("string", 8_192, null).pageWarnings(6_000).isEmpty(),
                "the estimate says it fits; the result still says it was reduced");
        String reduced = egress(raw, 8_192);
        JsonNode r = ResultFixtures.read(reduced);
        assertTrue(reduced.length() <= 8_192);
        assertTrue(r.path("completenessNote").asText().startsWith("This result was reduced before you saw it"));
        assertEquals(raw, egress(raw, 16_384));
    }
}
