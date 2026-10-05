package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SkillSlashParserDocumentSearchTest {

    @Test
    void document_search_slash_on_own_line_before_prompt() {
        String msg = "/document_search\nFor the KBM flexible pin type coupling, search the document knowledge repository";
        SkillSlashParser.Result r = SkillSlashParser.parse(msg, Set.of("document_search", "region_health"));
        assertEquals(List.of("document_search"), r.skillShortNamesInOrder());
        assertTrue(!r.cleanedMessage().contains("/document_search"));
        assertTrue(r.cleanedMessage().startsWith("For the KBM"));
    }
}
