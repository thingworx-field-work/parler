package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class DocumentKnowledgeRoutingGuideTest {

    @Test
    void routingGuide_contains_document_knowledge_rules() throws Exception {
        try (InputStream in = DocumentKnowledgeRoutingGuideTest.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertTrue(in != null, () -> "classpath resource missing");
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(s.contains("## Document knowledge"));
            assertTrue(s.contains("search_document_chunks"));
            assertTrue(s.contains("get_document_chunk"));
            assertTrue(s.contains("query live tools first"));
            assertTrue(s.contains("Do not invent text or URLs"));
            assertTrue(s.contains("no matching manual section"));
            assertTrue(s.contains("sourceLinks[].href"));
            assertTrue(s.contains("#page="));
        }
    }
}
