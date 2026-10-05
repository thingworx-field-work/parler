package com.thingworx.things.agent.playbook;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaybookGenericPathGrammarTest {

    @Test
    void valid_singleSegment() {
        assertTrue(PlaybookGenericPathGrammar.isValidDottedPath("a"));
        assertTrue(PlaybookGenericPathGrammar.isValidDottedPath("_x"));
        assertTrue(PlaybookGenericPathGrammar.isValidDottedPath("a_b"));
    }

    @Test
    void valid_dotted() {
        assertTrue(PlaybookGenericPathGrammar.isValidDottedPath("a.b.c"));
    }

    @Test
    void invalid_emptySegment() {
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath("a..b"));
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath(".a"));
    }

    @Test
    void invalid_charsOrBlank() {
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath(""));
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath(" "));
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath("a-b"));
        assertFalse(PlaybookGenericPathGrammar.isValidDottedPath("9"));
    }

    @Test
    void singleSegment_matchesSegmentGrammar_noDots() {
        assertTrue(PlaybookGenericPathGrammar.isSingleSegmentField("a"));
        assertTrue(PlaybookGenericPathGrammar.isSingleSegmentField("_x"));
        assertFalse(PlaybookGenericPathGrammar.isSingleSegmentField("a.b"));
        assertFalse(PlaybookGenericPathGrammar.isSingleSegmentField(""));
        assertFalse(PlaybookGenericPathGrammar.isSingleSegmentField("a-b"));
    }
}
