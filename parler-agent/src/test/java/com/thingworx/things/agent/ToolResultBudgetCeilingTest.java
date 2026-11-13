package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.support.RecordingLlmClient;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ResultFixtures;

/**
 * P37 (topic round 6, R23): the entry from serialized size and the ceiling. Observes the gateway output, which is the
 * first model append; the multi-tool case observes the next model request.
 */
class ToolResultBudgetCeilingTest {

    private static final ObjectMapper M = new ObjectMapper();
    private static final int CAP = ResultBudget.DEFAULT_SOFT_CAP_CHARS;

    @AfterEach
    void tearDown() {
        LlmCallRecorder.resetForTest();
        AgentToolContext.clear();
    }

    private static String egress(String raw) {
        return egress(raw, List.of());
    }

    private static String egress(String raw, List<String> classification) {
        return ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", raw, null, classification,
                ResultBudget.defaults()).getLlmContent();
    }

    /** {@code {"status":"success","text":"aaa…"}} of exactly {@code size} characters. */
    private static String textObject(int size) {
        String shell = "{\"status\":\"success\",\"text\":\"\"}";
        String raw = "{\"status\":\"success\",\"text\":\"" + "a".repeat(size - shell.length()) + "\"}";
        assertEquals(size, raw.length());
        return raw;
    }

    @Test
    void r_e_arrives_whole_byte_for_byte() {
        String raw = ResultFixtures.textObject();
        assertTrue(raw.contains("["));
        assertEquals(raw, egress(raw));
    }

    @Test
    void a_result_that_fits_after_sampling_keeps_its_text_and_one_still_over_takes_the_passes() {
        ObjectNode fits = M.createObjectNode().put("status", "success");
        ArrayNode items = fits.putArray("items");
        for (int i = 0; i < 40; i++) {
            items.add(ResultFixtures.value("item-" + i + "-", 300));
        }
        JsonNode out = ResultFixtures.read(egress(fits.toString()));
        assertEquals(20, out.path("items").size());
        assertEquals(300, out.path("items").get(19).asText().length(), "no text cut");
        assertTrue(out.has("completenessNote"));

        ObjectNode over = M.createObjectNode().put("status", "success");
        ArrayNode big = over.putArray("items");
        for (int i = 0; i < 40; i++) {
            big.add(ResultFixtures.value("item-" + i + "-", 600));
        }
        String s = egress(over.toString());
        JsonNode o = ResultFixtures.read(s);
        assertTrue(s.length() <= CAP, "within the cap: " + s.length());
        assertEquals(5, o.path("items").size(), "pass 2 samples to 5");
        assertTrue(o.path("items").get(0).asText().length() <= 515, "pass 2 text 512");
    }

    @Test
    void the_ceiling_holds_around_the_soft_cap() {
        for (int size : new int[] {CAP - 1, CAP, CAP + 1, CAP + 4_096, CAP + 4_097}) {
            String raw = textObject(size);
            String out = egress(raw);
            assertTrue(out.length() <= CAP, size + " -> " + out.length());
            if (size <= CAP) {
                assertEquals(raw, out, "equals the raw result up to the cap");
            } else {
                assertTrue(ResultFixtures.read(out).has("completenessNote"));
            }
        }
    }

    @Test
    void r_j_overflows_within_the_cap_with_its_field_names() {
        String raw = ResultFixtures.schemaOnly();
        assertTrue(raw.length() > CAP, "precondition: above the cap");
        String out = egress(raw);
        JsonNode o = ResultFixtures.read(out);
        assertTrue(out.length() <= CAP, "" + out.length());
        assertEquals("RESULT_OVERFLOW", o.path("resultKind").asText());
        assertEquals("[\"status\",\"columns\",\"hint\"]", o.path("fieldNames").toString());
        assertFalse(o.has("fieldNamesOmitted"));
        assertEquals("unavailable", o.path("recovery").asText(), "no cacheId");
        assertTrue(o.path("completenessNote").asText().startsWith("This result was too large to show"));
    }

    @Test
    void r_i_overflows_with_a_source_order_prefix_of_escaping_heavy_names_and_the_omitted_count() {
        String raw = ResultFixtures.escapingHeavyTable();
        String out = egress(raw);
        JsonNode o = ResultFixtures.read(out);
        assertTrue(out.length() <= CAP, "escaping included: " + out.length());
        assertEquals("RESULT_OVERFLOW", o.path("resultKind").asText());
        List<String> names = ResultFixtures.escapingHeavyNames();
        ArrayNode kept = (ArrayNode) o.path("columnNames");
        assertTrue(kept.size() > 0 && kept.size() < names.size(), "" + kept.size());
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(names.get(i), kept.get(i).asText(), "source order");
        }
        assertEquals(names.size() - kept.size(), o.path("columnNamesOmitted").asInt());
        // One more name would not have fitted.
        ObjectNode plusOne = (ObjectNode) o.deepCopy();
        ((ArrayNode) plusOne.get("columnNames")).add(names.get(kept.size()));
        assertTrue(ResultFixtures.write(plusOne).length() > CAP, "greedy: the next name does not fit");
    }

    @Test
    void an_author_classification_stamp_too_large_for_the_fixed_fields_gives_the_minimal_envelope() {
        String small = "{\"status\":\"success\",\"value\":1}";
        String nineThousand = "c".repeat(9_000);
        // cap - 50 serialized characters of stamp: ["<s>"] costs the string plus four characters.
        String nearCap = "d".repeat(CAP - 50 - 4);
        assertEquals(CAP - 50, ResultFixtures.write(M.createArrayNode().add(nearCap)).length());
        for (String stamp : List.of(nineThousand, nearCap)) {
            String out = egress(small, List.of(stamp));
            JsonNode o = ResultFixtures.read(out);
            assertEquals("RESULT_OVERFLOW", o.path("resultKind").asText());
            assertTrue(o.path("_egress").path("reducedFields").path("overBudget").asBoolean(), out.substring(0, 300));
            assertEquals(stamp, o.path("_egress").path("dataClassification").get(0).asText(), "preserved, not bounded");
            assertFalse(o.has("hint") || o.has("fieldNames"), "minimal: no names and no hint");
        }
    }

    @Test
    void the_error_and_plain_text_rules_are_kept() {
        assertEquals(LegacyGoldenRegressionTest.legacy("r-f.gateway"), egress(ResultFixtures.errorEnvelope()));
        String plain = ResultFixtures.plainText();
        assertEquals(plain, egress(plain));
        String longPlain = ResultFixtures.value("Plain text. ", 20_000);
        String out = egress(longPlain);
        assertTrue(out.length() <= CAP);
        assertEquals("LARGE_TEXT_COMPACT", ResultFixtures.read(out).path("resultKind").asText());
    }

    @Test
    void a_topic_carrier_of_8191_characters_is_unchanged_at_the_minimum_soft_cap() {
        String carrier = ResultFixtures.value("Topic demo-guide/topic-demo-r1\n\nCollect demo evidence. ", 8_191);
        assertEquals(carrier, egress(carrier));
    }

    @Test
    void the_next_model_call_receives_each_result_within_the_cap() throws Exception {
        String whole = ResultFixtures.textObject();
        String reduced = ResultFixtures.longWideCellTable(ResultFixtures.FIXED_CACHE_ID);
        LlmCallRecorder.resetForTest();
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(RecordingLlmClient.toolCallsResponse(List.of(new ToolCall("m-1", "tool_a", "{}"),
                new ToolCall("m-2", "tool_b", "{}"))));
        llm.enqueue(RecordingLlmClient.textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> "m-1".equals(tc.getId()) ? whole : reduced, null, 0, 8192, 8,
                60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("evidence"));
        loop.run(msgs, List.of(new ToolDefinition("tool_a", "d", java.util.Collections.emptyMap()),
                new ToolDefinition("tool_b", "d", java.util.Collections.emptyMap())));
        List<String> tools = new ArrayList<>();
        llm.requests.get(1).getMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .forEach(m -> tools.add(m.getContent()));
        assertEquals(2, tools.size());
        assertEquals(whole, tools.get(0));
        assertTrue(tools.get(1).length() <= CAP, "" + tools.get(1).length());
        assertTrue(ResultFixtures.read(tools.get(1)).has("completenessNote"));
    }

    // ---- review-3 corrections ----

    private static ObjectNode tableWithCells(int rows, JsonNode cell, String extraText) {
        ObjectNode root = M.createObjectNode().put("status", "success").put("resultKind", "INFOTABLE");
        root.put("rowCount", rows);
        root.putArray("columns").addObject().put("name", "id").put("baseType", "STRING");
        ((ArrayNode) root.get("columns")).addObject().put("name", "payload").put("baseType", "JSON");
        ArrayNode rs = root.putArray("rows");
        for (int i = 0; i < rows; i++) {
            ObjectNode r = rs.addObject().put("id", "row-" + i);
            r.set("payload", cell.deepCopy());
        }
        if (extraText != null) {
            root.put("narrative", extraText);
        }
        return root;
    }

    private static ArrayNode thirtyStrings() {
        ArrayNode a = M.createArrayNode();
        for (int i = 0; i < 30; i++) {
            a.add("entry-" + i);
        }
        return a;
    }

    @Test
    void an_array_valued_cell_of_a_table_row_is_never_cut_when_the_envelope_fits() {
        ObjectNode table = tableWithCells(1, thirtyStrings(), null);
        String out = egress(table.toString());
        JsonNode row = ResultFixtures.read(out).path("rows").get(0);
        assertEquals(table.path("rows").get(0), row, "the row arrives whole, all 30 cell entries");
    }

    @Test
    void array_and_nested_cells_stay_whole_when_whole_row_fill_runs() {
        ObjectNode nested = M.createObjectNode();
        nested.set("readings", thirtyStrings());
        nested.putObject("inner").set("more", thirtyStrings());
        ObjectNode table = tableWithCells(25, nested, "n".repeat(3_000));
        String raw = table.toString();
        assertTrue(raw.length() > CAP, "precondition: whole-row fill runs");
        String out = egress(raw);
        JsonNode o = ResultFixtures.read(out);
        assertTrue(out.length() <= CAP, "" + out.length());
        ArrayNode kept = (ArrayNode) o.path("rows");
        assertTrue(kept.size() > 0 && kept.size() < 25);
        for (int i = 0; i < kept.size(); i++) {
            assertEquals(table.path("rows").get(i), kept.get(i), "row " + i + " whole, nested cells included");
        }
        assertTrue(o.path("narrative").asText().length() <= 2_003, "other root text is cut at the text cap");
    }

    @Test
    void classified_plain_text_stays_within_the_cap() {
        for (String text : List.of("z".repeat(10_000), "\"\\\n".repeat(4_000))) {
            String out = egress(text, List.of("c".repeat(6_000)));
            assertTrue(out.length() <= CAP, "escaping and stamp included: " + out.length());
            JsonNode o = ResultFixtures.read(out);
            assertEquals("c".repeat(6_000), o.path("_egress").path("dataClassification").get(0).asText());
            assertFalse(o.path("_egress").path("reducedFields").path("overBudget").asBoolean(false));
            assertTrue(o.has("completenessNote"));
        }
        String over = egress("z".repeat(10_000), List.of("c".repeat(9_000)));
        JsonNode o = ResultFixtures.read(over);
        assertTrue(o.path("_egress").path("reducedFields").path("overBudget").asBoolean(), "measured exception");
        assertEquals("c".repeat(9_000), o.path("_egress").path("dataClassification").get(0).asText());
    }

    @Test
    void a_reduced_root_array_says_so_whether_or_not_it_was_over_the_cap() {
        ArrayNode big = M.createArrayNode();
        for (int i = 0; i < 40; i++) {
            big.add(ResultFixtures.value("item-" + i + "-", 250));
        }
        assertTrue(big.toString().length() > CAP);
        ArrayNode small = M.createArrayNode();
        for (int i = 0; i < 25; i++) {
            small.add("s" + i);
        }
        assertTrue(small.toString().length() < CAP, "precondition: the raw fast return would have applied");
        for (ArrayNode root : List.of(big, small)) {
            String out = egress(root.toString());
            JsonNode o = ResultFixtures.read(out);
            assertTrue(o.isObject(), "wrapped, not a bare array: " + out.substring(0, Math.min(200, out.length())));
            assertEquals("LARGE_JSON_COMPACT", o.path("resultKind").asText());
            assertEquals(20, o.path("sample").size());
            assertEquals(root.size(), o.path("_egress").path("reducedFields").path("$root").path("originalCount")
                    .asInt());
            assertTrue(o.path("sampleOnly").asBoolean() && o.has("completenessNote"));
            assertTrue(out.length() <= CAP);
        }
        ArrayNode twenty = M.createArrayNode();
        for (int i = 0; i < 20; i++) {
            twenty.add("t" + i);
        }
        assertEquals(twenty.toString(), egress(twenty.toString()), "an array of 20 is not reduced");
    }

    private static ObjectNode readLimitedWideResult() {
        ObjectNode root = M.createObjectNode().put("status", "success");
        root.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED, true);
        root.put(com.thingworx.things.agent.source.ReadLimitFact.FIELD_NOTE, "n".repeat(512));
        ArrayNode labels = root.putArray(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED_SERIES);
        for (int i = 0; i < 6; i++) {
            labels.add(String.valueOf(i).repeat(512));
        }
        ArrayNode columns = root.putArray("columns");
        for (int i = 0; i < 80; i++) {
            columns.add("field_" + i + "_" + "x".repeat(110));
        }
        return root;
    }

    @Test
    void the_overflow_fallback_keeps_read_limit_evidence_and_claims_overbudget_only_when_measured() {
        String raw = readLimitedWideResult().toString();
        String reached = com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED;
        for (int stampChars : new int[] {6_000, 7_000}) {
            String out = egress(raw, List.of("c".repeat(stampChars)));
            JsonNode o = ResultFixtures.read(out);
            assertTrue(out.length() <= CAP, stampChars + ": " + out.length());
            assertEquals("RESULT_OVERFLOW", o.path("resultKind").asText());
            assertTrue(o.path(reached).asBoolean(), stampChars + ": the limited source read is still stated");
            assertFalse(o.path("_egress").path("reducedFields").path("overBudget").asBoolean(false),
                    stampChars + ": the minimal envelope fits, so the exception did not happen");
        }
        String out = egress(raw, List.of("c".repeat(9_000)));
        JsonNode o = ResultFixtures.read(out);
        assertTrue(o.path("_egress").path("reducedFields").path("overBudget").asBoolean());
        assertTrue(o.path(reached).asBoolean());
        String noStamp = egress(raw);
        JsonNode n = ResultFixtures.read(noStamp);
        assertEquals(6, n.path(com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED_SERIES).size(),
                "with room, all of the evidence is kept");
    }

    // ---- review-4 corrections ----

    private static ObjectNode tableWithMetadataArray(int rows, int cellChars, int warnings, int messageChars) {
        ObjectNode root = M.createObjectNode().put("status", "success").put("resultKind", "INFOTABLE");
        root.put("rowCount", rows);
        root.putArray("columns").addObject().put("name", "id").put("baseType", "STRING");
        ((ArrayNode) root.get("columns")).addObject().put("name", "v").put("baseType", "STRING");
        ArrayNode rs = root.putArray("rows");
        for (int i = 0; i < rows; i++) {
            rs.addObject().put("id", "row-" + i).put("v", ResultFixtures.value("v" + i + "-", cellChars));
        }
        ArrayNode w = root.putArray("warnings");
        for (int i = 0; i < warnings; i++) {
            w.addObject().put("code", "W" + i).put("message", "x".repeat(messageChars));
        }
        return root;
    }

    private static void assertMetadataArraySampledWithMarker(ObjectNode source, String out, int keptRows) {
        JsonNode o = ResultFixtures.read(out);
        assertTrue(out.length() <= CAP, "" + out.length());
        assertEquals(20, o.path("warnings").size());
        for (int i = 0; i < 20; i++) {
            assertEquals(source.path("warnings").get(i), o.path("warnings").get(i), "source order, entries whole");
        }
        JsonNode marker = o.path("_egress").path("reducedFields").path("warnings");
        assertEquals(source.path("warnings").size(), marker.path("originalCount").asInt(), out);
        assertEquals(20, marker.path("returnedCount").asInt());
        assertTrue(o.has("completenessNote"));
        for (int i = 0; i < keptRows; i++) {
            assertEquals(source.path("rows").get(i), o.path("rows").get(i), "row contents unchanged");
        }
    }

    @Test
    void a_table_whose_metadata_array_sampling_alone_brings_it_under_the_cap_says_so() {
        ObjectNode t = tableWithMetadataArray(1, 50, 40, 250);
        assertTrue(t.toString().length() > CAP, "precondition: over the cap before sampling");
        String out = egress(t.toString());
        assertMetadataArraySampledWithMarker(t, out, 1);
        assertEquals(1, ResultFixtures.read(out).path("rows").size());
    }

    @Test
    void a_table_that_already_fits_still_samples_its_metadata_array_with_a_marker() {
        ObjectNode t = tableWithMetadataArray(2, 20, 25, 5);
        assertTrue(t.toString().length() < CAP, "precondition: the raw fast return would have applied");
        assertMetadataArraySampledWithMarker(t, egress(t.toString()), 2);
    }

    @Test
    void whole_row_fill_keeps_the_metadata_array_marker() {
        ObjectNode t = tableWithMetadataArray(20, 1_000, 40, 50);
        assertTrue(t.toString().length() > CAP);
        String out = egress(t.toString());
        JsonNode o = ResultFixtures.read(out);
        assertTrue(o.path("rows").size() < 20, "fill ran");
        assertMetadataArraySampledWithMarker(t, out, o.path("rows").size());
    }

    @Test
    void a_reduced_evidence_tier_still_gets_the_names_that_fit() {
        ObjectNode src = readLimitedWideResult();
        List<String> allNames = new ArrayList<>();
        src.fieldNames().forEachRemaining(allNames::add);
        String reached = com.thingworx.things.agent.source.ReadLimitFact.FIELD_REACHED;
        for (int stampChars : new int[] {6_000, 7_000}) {
            String out = egress(src.toString(), List.of("c".repeat(stampChars)));
            JsonNode o = ResultFixtures.read(out);
            assertTrue(out.length() <= CAP, stampChars + ": " + out.length());
            assertTrue(o.path(reached).asBoolean(), "flag kept");
            ArrayNode names = (ArrayNode) o.path("fieldNames");
            assertTrue(names.size() > 0, stampChars + ": names that fit are kept: " + out.substring(0, 400));
            for (int i = 0; i < names.size(); i++) {
                assertEquals(allNames.get(i), names.get(i).asText(), "source order");
            }
            if (names.size() < allNames.size()) {
                assertEquals(allNames.size() - names.size(), o.path("fieldNamesOmitted").asInt());
                ObjectNode plusOne = (ObjectNode) o.deepCopy();
                ((ArrayNode) plusOne.get("fieldNames")).add(allNames.get(names.size()));
                assertTrue(ResultFixtures.write(plusOne).length() > CAP, "greedy");
            } else {
                assertFalse(o.has("fieldNamesOmitted"));
            }
        }
    }
}
