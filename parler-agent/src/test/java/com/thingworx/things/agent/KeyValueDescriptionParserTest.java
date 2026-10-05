package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class KeyValueDescriptionParserTest {

    @Test
    void parsesKeyValueLines() {
        Map<String, String> m = KeyValueDescriptionParser.parse("title: Hello\nwhen_to_use: Now");
        assertEquals("Hello", m.get("title"));
        assertEquals("Now", m.get("when_to_use"));
    }

    @Test
    void ignoresCommentsAndBlanks() {
        Map<String, String> m = KeyValueDescriptionParser.parse("# c\n\na: 1\n");
        assertEquals(1, m.size());
        assertEquals("1", m.get("a"));
    }

    @Test
    void lowercasesKeys() {
        Map<String, String> m = KeyValueDescriptionParser.parse("Title: X");
        assertTrue(m.containsKey("title"));
    }
}
