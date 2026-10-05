package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.cache.TypedColumn;
import com.thingworx.types.BaseTypes;

/** CM-2 schema bound: measured on the serialized public object, names exact, suggestion kept or dropped whole. */
class AnalyzeProjectionDiagnosticsTest {

    private static final int CAP = AnalyzeProjectionDiagnostics.MAX_SCHEMA_CHARS;

    private static List<TypedColumn> cols(String... names) {
        List<TypedColumn> out = new ArrayList<>();
        for (String n : names) {
            out.add(new TypedColumn(n, BaseTypes.NUMBER));
        }
        return out;
    }

    private static int len(AnalyzeProjectionDiagnostics.BoundedSchema b) {
        return AnalyzeProjectionDiagnostics.serializedLength(b.node);
    }

    private static List<String> names(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (JsonNode c : node.path("columns")) {
            out.add(c.path("name").asText());
        }
        return out;
    }

    @Test
    void fourLongPlainNames_serializedWithinCap_shellIncluded() {
        List<TypedColumn> schema = cols("c1" + "x".repeat(460), "c2" + "x".repeat(460), "c3" + "x".repeat(460),
                "c4" + "x".repeat(460));
        AnalyzeProjectionDiagnostics.BoundedSchema b = AnalyzeProjectionDiagnostics.boundedSchema(schema, null);
        assertTrue(len(b) <= CAP, "len=" + len(b));
        assertEquals(4, b.node.path("columnCount").asInt());
        assertTrue(b.node.path("truncated").asBoolean());
        assertEquals(3, b.node.path("columns").size());
    }

    @Test
    void escapedNames_countEscapesTowardCap() {
        List<TypedColumn> schema = cols("c1" + "\\".repeat(460), "c2" + "\\".repeat(460), "c3" + "\"".repeat(460),
                "c4" + "x".repeat(460));
        AnalyzeProjectionDiagnostics.BoundedSchema b = AnalyzeProjectionDiagnostics.boundedSchema(schema, null);
        assertTrue(len(b) <= CAP, "len=" + len(b));
        assertTrue(b.node.path("truncated").asBoolean());
        for (String n : names(b.node)) {
            assertTrue(schema.stream().anyMatch(c -> c.name().equals(n)), "exact name: " + n);
        }
    }

    @Test
    void suggestedColumnBeyondCountCap_takesLastSlot() {
        List<String> n = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            n.add(String.format("c%03d", i));
        }
        AnalyzeProjectionDiagnostics.BoundedSchema b =
                AnalyzeProjectionDiagnostics.boundedSchema(cols(n.toArray(new String[0])), "c040");
        assertTrue(b.suggestionShown);
        assertEquals(AnalyzeProjectionDiagnostics.MAX_SCHEMA_COLUMNS, b.node.path("columns").size());
        assertEquals("c040", names(b.node).get(names(b.node).size() - 1));
        assertTrue(b.node.path("truncated").asBoolean());
        assertEquals(40, b.node.path("columnCount").asInt());
        assertTrue(len(b) <= CAP);
    }

    @Test
    void suggestedColumnThatCannotFit_isDroppedNotClipped() {
        String huge = "s" + "x".repeat(ToolResultEgressGateway.hardTextExcerptChars() - 10); // representable
        List<TypedColumn> schema = new ArrayList<>(cols("a", "b"));
        for (int i = 0; i < 5; i++) {
            schema.add(new TypedColumn(huge + i, BaseTypes.NUMBER));
        }
        // Suggestion fits alone: others are dropped from the end first.
        AnalyzeProjectionDiagnostics.BoundedSchema fits = AnalyzeProjectionDiagnostics.boundedSchema(schema, huge + "4");
        assertTrue(fits.suggestionShown);
        assertTrue(names(fits.node).contains(huge + "4"));
        assertTrue(len(fits) <= CAP);

        // Unrepresentable (longer than the egress excerpt): never listed, never suggested.
        String tooLong = "t" + "x".repeat(ToolResultEgressGateway.hardTextExcerptChars() + 1);
        List<TypedColumn> schema2 = new ArrayList<>(cols("a"));
        schema2.add(new TypedColumn(tooLong, BaseTypes.NUMBER));
        AnalyzeProjectionDiagnostics.BoundedSchema dropped = AnalyzeProjectionDiagnostics.boundedSchema(schema2, tooLong);
        assertFalse(dropped.suggestionShown);
        assertEquals(List.of("a"), names(dropped.node));
        assertTrue(dropped.node.path("truncated").asBoolean());
        assertEquals(2, dropped.node.path("columnCount").asInt());
    }

    @Test
    void smallSchema_isCompleteAndNotTruncated() {
        AnalyzeProjectionDiagnostics.BoundedSchema b = AnalyzeProjectionDiagnostics.boundedSchema(cols("ts", "value"), "value");
        assertFalse(b.node.path("truncated").asBoolean());
        assertTrue(b.suggestionShown);
        assertEquals(List.of("ts", "value"), names(b.node));
        assertFalse(b.node.has("_suggestionShown"));
    }
}
