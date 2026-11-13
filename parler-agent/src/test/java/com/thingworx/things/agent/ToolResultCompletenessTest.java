package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway.EgressResult;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptAssembly;
import com.thingworx.things.agent.configrepo.ExternalSystemPromptSelection;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.support.RecordingLlmClient;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ResultFixtures;

/**
 * P36 (topic round 6, R26): a reduced result says so in the result itself. Unless stated, a test observes the gateway
 * output, which is the <b>first model append</b>; the loop test also observes the <b>persist row</b> and the
 * <b>full body</b> kept for the UI lane.
 */
class ToolResultCompletenessTest {

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        com.thingworx.things.agent.llm.usage.LlmCallRecorder.resetForTest();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.llm.usage.LlmCallRecorder.resetForTest();
        AgentToolContext.clear();
    }

    private static JsonNode egress(String raw) {
        return ResultFixtures.read(ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", raw, null)
                .getLlmContent());
    }

    @Test
    void a_gateway_reduction_gets_the_note_through_every_pass() {
        JsonNode pass1 = egress(ResultFixtures.page0002());
        assertTrue(pass1.path("completenessNote").asText().startsWith("This result was reduced before you saw it"),
                pass1.toString());
        JsonNode nested = egress("{\"status\":\"success\",\"blob\":{\"text\":\"" + "w".repeat(9_000)
                + "\"},\"columns\":[" + "\"c\",".repeat(10) + "\"c\"]}");
        assertTrue(nested.path("completenessNote").asText().startsWith("This result was reduced before you saw it"),
                nested.toString());
        JsonNode filled = egress(ResultFixtures.wideTable());
        JsonNode filledCells = egress(ResultFixtures.longWideCellTable(ResultFixtures.FIXED_CACHE_ID));
        for (JsonNode n : List.of(filled, filledCells)) {
            assertTrue(n.path("completenessNote").asText().startsWith("This result is a sample of"), n.toString());
        }
        JsonNode overflow = egress(ResultFixtures.escapingHeavyTable());
        assertTrue(overflow.path("completenessNote").asText().startsWith("This result was too large to show"));
    }

    @Test
    void a_producer_table_sample_gets_the_note_even_though_the_gateway_changes_nothing_else() {
        String raw = ResultFixtures.longShortTable(ResultFixtures.FIXED_CACHE_ID);
        JsonNode out = egress(raw);
        assertTrue(out.path("completenessNote").asText().startsWith("This result is a sample of 20 of 40 rows."),
                out.toString());
        assertTrue(out.path("completenessNote").asText().contains("tabulate_cached_result"));
        int tabulate = out.path("completenessNote").asText().indexOf("tabulate_cached_result");
        int fetch = out.path("completenessNote").asText().indexOf("fetch_cached_result");
        assertTrue(tabulate < fetch, "the projection route is listed before fetch_cached_result");
        ObjectNode without = (ObjectNode) out.deepCopy();
        without.remove("completenessNote");
        assertEquals(ResultFixtures.read(raw), without, "nothing else changed");
    }

    @Test
    void a_truncated_page_gets_the_page_note() {
        ObjectNode page = (ObjectNode) ResultFixtures.read(ResultFixtures.page("page-0003", "Short page."));
        page.putArray("warnings").addObject().put("code", "CHUNK_MARKDOWN_TRUNCATED")
                .put("message", "Chunk markdown was truncated to the configured maximum.");
        JsonNode out = egress(ResultFixtures.write(page));
        assertTrue(out.path("completenessNote").asText().startsWith("This page was cut at the configured maximum"),
                out.toString());
        assertFalse(out.path("completenessNote").asText().contains("cache"), "no cache route for a page");
    }

    @Test
    void read_limit_evidence_gets_the_producer_note() {
        String raw = "{\"status\":\"success\",\"readLimitReached\":true,\"readLimitNote\":\"limit hit\",\"rowCount\":3}";
        JsonNode out = egress(raw);
        assertTrue(out.path("completenessNote").asText().startsWith("This result is partial"), out.toString());
        assertFalse(out.path("completenessNote").asText().contains("cache"), "no cacheId: no cache route");
    }

    @Test
    void classification_alone_and_a_nested_business_truncated_are_not_reductions() {
        String plain = "{\"status\":\"success\",\"value\":1}";
        EgressResult classified = ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", plain, null,
                List.of("internal"));
        JsonNode c = ResultFixtures.read(classified.getLlmContent());
        assertTrue(c.path("_egress").has("dataClassification"));
        assertFalse(c.has("completenessNote"));
        assertFalse(CompletenessNote.rootShowsReduction(c));

        String nested = "{\"status\":\"success\",\"resultKind\":\"JSON\",\"result\":{\"truncated\":true,\"v\":1}}";
        assertEquals(nested, ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", nested, null)
                .getLlmContent());
    }

    @Test
    void the_annotated_result_replaces_the_raw_one_even_when_longer() {
        StringBuilder sb = new StringBuilder("{\"status\":\"success\",\"items\":[");
        for (int i = 0; i < 21; i++) {
            sb.append(i == 0 ? "" : ",").append(i);
        }
        String raw = sb.append("]}").toString();
        EgressResult out = ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", raw, null);
        assertTrue(out.getLlmContent().length() > raw.length());
        JsonNode n = ResultFixtures.read(out.getLlmContent());
        assertEquals(20, n.path("items").size());
        assertTrue(n.has("completenessNote"));
    }

    @Test
    void error_envelopes_keep_the_error_rule_and_get_no_note() {
        String raw = ResultFixtures.errorEnvelope();
        assertFalse(egress(raw).has("completenessNote"));
    }

    @Test
    void every_note_variant_is_at_most_1200_characters() {
        JsonNode table = ResultFixtures.read(ResultFixtures.longShortTable(ResultFixtures.FIXED_CACHE_ID));
        JsonNode bare = ResultFixtures.read("{\"status\":\"success\"}");
        for (CompletenessNote.Variant v : CompletenessNote.Variant.values()) {
            for (JsonNode root : List.of(table, bare)) {
                String text = CompletenessNote.text(v, root);
                assertTrue(text.length() <= CompletenessNote.MAX_NOTE_CHARS, v + " " + text.length());
                assertFalse(text.isBlank());
            }
        }
    }

    @Test
    void the_note_is_in_the_first_append_and_the_persist_row_and_the_ui_lane_keeps_the_raw_body() throws Exception {
        // A page reduced by pass 1; the replay compactor leaves it alone, so the next request shows the first append.
        String raw = ResultFixtures.page0002();
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(RecordingLlmClient.toolCallsResponse(List.of(new ToolCall("p36-1", ResultFixtures.TOOL, "{}"))));
        llm.enqueue(RecordingLlmClient.textResponse("done"));
        List<ChatMessage> streamed = new ArrayList<>();
        AgentLoop loop = new AgentLoop(llm, tc -> raw, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("evidence"));
        // No routing guide and no system prompt: the note travels in the result itself.
        loop.run(msgs, List.of(new ToolDefinition(ResultFixtures.TOOL, "d", java.util.Collections.emptyMap())),
                (m, tu) -> streamed.add(m));
        String first = llm.requests.get(1).getMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow().getContent();
        String persist = streamed.stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow().getContent();
        assertTrue(ResultFixtures.read(first).has("completenessNote"));
        assertEquals(first, persist, "the persist row starts identical to the first append");
        assertEquals(raw, AgentToolContext.peekToolEgressFullJsonForToolCall("p36-1"), "UI lane: the full body");
    }

    @Test
    void the_contract_file_exists_and_the_guide_and_rule_carry_the_sentence() throws Exception {
        Path contract = Path.of("..", "CONTRACTS", "TOOL_RESULT_COMPLETENESS.md");
        assertTrue(Files.exists(contract), contract.toAbsolutePath().toString());
        assertTrue(Files.readString(contract).contains("completenessNote"));
        assertTrue(LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE.contains("completenessNote"));
        try (java.io.InputStream in = LeadingStablePromptComposer.class.getResourceAsStream(
                "/com/thingworx/things/agent/llm_tool_routing_guide.txt")) {
            assertNotNull(in);
            assertTrue(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .contains("completenessNote"));
        }
    }

    @Test
    void an_external_system_prompt_does_not_carry_the_sentence() {
        String external = ExternalSystemPromptAssembly.resolveLeadingStableSystemPrompt(
                ExternalSystemPromptSelection.externalFile("/prompts/demo.md", "Demo external prompt."), null, "",
                true, null, null, null);
        assertEquals("Demo external prompt.", external);
        assertFalse(external.contains("completenessNote"), "the sentence is not applied; the note in the result is");
    }
}
