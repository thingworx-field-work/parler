package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.support.RecordingLlmClient;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ResultFixtures;

/**
 * P42 (topic round 6, R26d), S1 behaviour: an approved result is presented once. Observes separately the
 * <b>durable TOOL row</b> (the messages list; {@code appendDurable} hands the same message to the stream) and the
 * <b>first request the resumed loop sends</b>.
 */
class ApprovedToolResultAppenderTest {

    @AfterEach
    void tearDown() {
        AgentToolContext.clear();
    }

    static String legacy(String name) {
        try (InputStream in = ApprovedToolResultAppenderTest.class.getResourceAsStream(
                "/tool-result/legacy/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    private static List<ChatMessage> pausedHistory(String toolName) {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("approved evidence"));
        msgs.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("approved-1", toolName, "{}"))));
        return msgs;
    }

    private static String resumedRequestToolMessage(List<ChatMessage> history) throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(RecordingLlmClient.textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> "{}", null, 0, 8192, 8, 60_000L);
        AgentLoopTestFixtures.runAfterApproval(loop, history,
                List.of(new ToolDefinition("noop", "d", java.util.Collections.emptyMap())), null, null);
        return llm.requests.get(0).getMessages().stream().filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow().getContent();
    }

    private static String append(List<ChatMessage> msgs, String tool, String raw, ResultBudget budget) {
        AgentToolContext.putResultBudget("approved-1", budget);
        return ApprovedToolResultAppender.append(msgs, "approved-1", tool, raw, List.of(), budget, "", "",
                "DemoAgent");
    }

    @Test
    void a_producer_sample_gets_the_note_in_the_durable_row_and_the_resumed_request() throws Exception {
        for (String tool : List.of(ResultFixtures.TOOL, "invoke_service")) {
            String raw = ResultFixtures.longShortTable(ResultFixtures.FIXED_CACHE_ID);
            List<ChatMessage> msgs = pausedHistory(tool);
            String presented = append(msgs, tool, raw, ResultBudget.defaults());
            ChatMessage row = msgs.get(msgs.size() - 1);
            assertEquals(presented, row.getContent(), "durable row");
            assertEquals(tool, row.getExecutedToolName());
            JsonNode n = ResultFixtures.read(presented);
            assertTrue(n.path("completenessNote").asText().startsWith("This result is a sample of 20 of 40 rows."));
            ObjectNode without = (ObjectNode) n.deepCopy();
            without.remove("completenessNote");
            assertEquals(ResultFixtures.read(legacy("r-d.approved")), without, "otherwise the golden");
            assertEquals(presented, resumedRequestToolMessage(msgs), "resumed request, tool=" + tool);
        }
    }

    @Test
    void an_over_cap_approved_result_equals_the_ordinary_gateway_output_under_the_same_budget() throws Exception {
        for (String raw : List.of(ResultFixtures.wideTable(),
                ResultFixtures.longWideCellTable(ResultFixtures.FIXED_CACHE_ID))) {
            assertTrue(raw.length() > 8_192);
            List<ChatMessage> msgs = pausedHistory(ResultFixtures.TOOL);
            String presented = append(msgs, ResultFixtures.TOOL, raw, ResultBudget.defaults());
            String ordinary = ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "other", raw, null,
                    List.of(), ResultBudget.defaults()).getLlmContent();
            assertEquals(ordinary, presented);
            assertTrue(presented.length() <= 8_192, "within the soft cap");
            assertTrue(ResultFixtures.read(presented).has("completenessNote"));
            assertEquals(raw, AgentToolContext.peekToolEgressFullJsonForToolCall("approved-1"), "full body kept");
            assertEquals(presented, resumedRequestToolMessage(msgs));
            assertNull(AgentToolContext.peekResultBudget("approved-1"), "budget removed after the append");
            AgentToolContext.clear();
        }
    }

    @Test
    void a_nondefault_captured_budget_is_honoured() {
        String raw = ResultFixtures.wideTable();
        List<ChatMessage> msgs = pausedHistory(ResultFixtures.TOOL);
        String presented = append(msgs, ResultFixtures.TOOL, raw, new ResultBudget(16_384, 3_000, "setting"));
        assertEquals(raw, presented, "a three-row table that fits 16,384 arrives whole, as before");
    }

    @Test
    void a_result_with_no_root_sign_that_fits_is_byte_identical() {
        String raw = ResultFixtures.stringEnvelope(ResultFixtures.recordsMap());
        List<ChatMessage> msgs = pausedHistory(ResultFixtures.TOOL);
        assertEquals(raw, append(msgs, ResultFixtures.TOOL, raw, ResultBudget.defaults()));
    }
}
