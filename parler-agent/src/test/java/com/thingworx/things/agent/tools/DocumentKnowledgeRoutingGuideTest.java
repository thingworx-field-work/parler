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
            assertTrue(s.contains("load it with load_topic and follow its steps"));
            assertTrue(s.contains("use the unit already resolved in the conversation"));
            assertTrue(s.contains("call resolve_thing only when the unit is not yet established or has changed"));
            assertTrue(s.contains("search that unit's documents before calling operational tools"));
            assertTrue(s.contains("A search may carry the unit's canonical Thing name in assetContext.thingName to scope the "
                    + "searches of that step; this is not the conversation anchor, and resolve_thing remains what keeps "
                    + "a unit across turns and approval pauses."));
        }
    }
}
