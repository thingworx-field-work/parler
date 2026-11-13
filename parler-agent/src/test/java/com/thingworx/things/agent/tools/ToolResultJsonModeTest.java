package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.thingworx.things.agent.AgentLoop;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.ToolResultSettings.JsonMode;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.support.RecordingLlmClient;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;

/**
 * P35 (topic round 6, R22): {@code toolResultJsonMode}. Observes the formatter output of both invocation paths and the
 * <b>first model append</b> ({@code llm.requests.get(1)}, TOOL role).
 *
 * <p>The local library's {@code JSONPrimitive} cannot be constructed in a unit test (its static initializer needs a
 * Jackson Joda module that is not on the test classpath), so the SDK value type, an {@code org.json.JSONObject}, is
 * supplied by {@link JsonObjectCell}; the User's live step 1 confirms the real type on a server.
 */
class ToolResultJsonModeTest {

    ToolResultJsonModeTest() {
        com.thingworx.things.agent.cache.ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    @AfterEach
    void tearDown() {
        com.thingworx.things.agent.cache.TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String format(InfoTable outer, BaseTypes type, boolean extendedPath) throws Exception {
        AgentToolContext.setConversationId("p35-json-mode");
        if (extendedPath) {
            return InvokeServiceExecutor.formatDirectServiceResultForLlm(outer, ResultFixtures.serviceReturning(type),
                    null);
        }
        return InvokeServiceExecutor.formatToolResult(outer, ResultFixtures.serviceReturning(type), null,
                InvokeServiceParameterNormalizer.Repair.none(), null, false);
    }

    static String legacy(String name) {
        try (InputStream in = ToolResultJsonModeTest.class.getResourceAsStream("/tool-result/legacy/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(name, e);
        }
    }

    /** Runs one tool call through the loop and returns the first model append (the TOOL message of request 2). */
    static String firstModelAppend(String toolResult) throws Exception {
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(RecordingLlmClient.toolCallsResponse(List.of(new ToolCall("p35-1", ResultFixtures.TOOL, "{}"))));
        llm.enqueue(RecordingLlmClient.textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> toolResult, null, 0, 8192, 8, 60_000L);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("evidence"));
        loop.run(msgs, List.of(new ToolDefinition(ResultFixtures.TOOL, "d", java.util.Collections.emptyMap())));
        return llm.requests.get(1).getMessages().stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL)
                .findFirst().orElseThrow().getContent();
    }

    @Test
    void string_mode_formatter_is_byte_identical_to_the_legacy_golden_on_both_paths() throws Exception {
        for (boolean extended : List.of(true, false)) {
            AgentToolContext.setToolResultJsonMode(JsonMode.STRING);
            String out = format(ResultFixtures.jsonServiceResult(ResultFixtures.recordsArray()), BaseTypes.JSON,
                    extended);
            assertEquals(legacy("r-a-string.formatter"), out, "extended=" + extended);
        }
    }

    @Test
    void default_mode_with_no_agent_is_string() {
        assertEquals(JsonMode.STRING, AgentToolContext.toolResultJsonMode());
    }

    @Test
    void string_mode_first_append_arrives_whole_because_it_fits_the_soft_cap() throws Exception {
        AgentToolContext.setToolResultJsonMode(JsonMode.STRING);
        String formatted = format(ResultFixtures.jsonServiceResult(ResultFixtures.recordsArray()), BaseTypes.JSON, true);
        assertTrue(formatted.length() >= 4_500 && formatted.length() <= 8_000, "R-A envelope " + formatted.length());
        String first = firstModelAppend(formatted);
        assertEquals(formatted, first, "S2: whole, byte for byte (S1 was today's cut plus the note)");
        assertTrue(ResultFixtures.read(first).path("result").isTextual(), "still one string");
    }

    @Test
    void node_mode_delivers_an_object_with_four_intact_records_on_both_paths() throws Exception {
        for (boolean extended : List.of(true, false)) {
            AgentToolContext.setToolResultJsonMode(JsonMode.NODE);
            String formatted = format(ResultFixtures.jsonServiceResult(ResultFixtures.recordsArray()), BaseTypes.JSON,
                    extended);
            JsonNode node = ResultFixtures.read(formatted);
            assertEquals("JSON", node.path("resultKind").asText());
            assertTrue(node.path("result").isObject(), formatted);
            assertEquals(ResultFixtures.recordsArray(), node.path("result"), "four intact records");

            JsonNode first = ResultFixtures.read(firstModelAppend(formatted));
            assertEquals(ResultFixtures.recordsArray(), first.path("result"), "first append, extended=" + extended);
            assertFalse(first.has("truncated") || first.has("_egress") || first.has("completenessNote"),
                    first.toString());
        }
    }

    @Test
    void a_value_that_cannot_be_read_as_json_falls_back_to_the_string() {
        assertEquals("not json {", InvokeServiceExecutor.jsonValueOrText("not json {").asText());
        assertTrue(InvokeServiceExecutor.jsonValueOrText("42").isTextual(), "a scalar stays a string");
        assertTrue(InvokeServiceExecutor.jsonValueOrText("{\"a\":1}").isObject());
    }

    @Test
    void a_string_result_stays_a_literal_string_in_node_mode() throws Exception {
        AgentToolContext.setToolResultJsonMode(JsonMode.NODE);
        String out = format(ResultFixtures.stringServiceResult("{\"looks\":\"like json\"}"), BaseTypes.STRING, true);
        JsonNode node = ResultFixtures.read(out);
        assertEquals("STRING", node.path("resultKind").asText());
        assertEquals("{\"looks\":\"like json\"}", node.path("result").asText());
    }

    @Test
    void r_a0_is_whole_with_no_note_in_both_modes() throws Exception {
        for (JsonMode mode : JsonMode.values()) {
            AgentToolContext.setToolResultJsonMode(mode);
            String formatted = format(ResultFixtures.jsonServiceResult(ResultFixtures.recordsMap()), BaseTypes.JSON,
                    true);
            assertEquals(formatted, firstModelAppend(formatted), "mode=" + mode);
        }
    }

    @Test
    void r_b_is_sampled_with_markers_and_the_note_as_an_object_and_whole_as_a_string() {
        String node = ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c",
                ResultFixtures.nodeEnvelope(ResultFixtures.thirtyStrings()), null).getLlmContent();
        JsonNode n = ResultFixtures.read(node);
        assertEquals(20, n.path("result").path("items").size());
        assertTrue(n.path("sampleOnly").asBoolean(false) && n.has("_egress") && n.has("completenessNote"), node);

        String string = ResultFixtures.stringEnvelope(ResultFixtures.thirtyStrings());
        assertEquals(string, ToolResultEgressGateway.compactForLlmAppend(ResultFixtures.TOOL, "c", string, null)
                .getLlmContent());
    }

    @Test
    void decode_business_object_accepts_the_node_and_the_string() throws Exception {
        java.lang.reflect.Method decode = TabularChartRoundHooks.class.getDeclaredMethod("decodeBusinessObject",
                JsonNode.class);
        decode.setAccessible(true);
        JsonNode value = ResultFixtures.recordsArray();
        assertEquals(value, decode.invoke(null, value));
        assertEquals(value, decode.invoke(null,
                com.fasterxml.jackson.databind.node.TextNode.valueOf(ResultFixtures.write(value))));
    }
}
