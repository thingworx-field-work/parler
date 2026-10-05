package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;

class LlmToolResultCohortMergerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void compactBatch_getPropertyValues_threeSamePropertySet_emitsBundle() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("ca", "get_property_values", "{\"thingName\":\"A\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cb", "get_property_values", "{\"thingName\":\"B\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cc", "get_property_values", "{\"thingName\":\"C\",\"propertyNames\":[\"speed\"]}"))));
        messages.add(ChatMessage.toolResult("ca", gpvsJson("A", "speed", "NUMBER", 10)));
        messages.add(ChatMessage.toolResult("cb", gpvsJson("B", "speed", "NUMBER", 20)));
        messages.add(ChatMessage.toolResult("cc", gpvsJson("C", "speed", "NUMBER", 30)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertEquals("thingName", bundle.path("cohortDimension").asText());
        assertTrue(bundle.path("result").path("$format").asText().contains("matrix"));
        JsonNode m2 = MAPPER.readTree(messages.get(3).getContent());
        assertTrue(m2.path("$format").asText().contains("cohort.member"));
    }

    @Test
    void compactBatch_queryAlertSummary_threeInlineInfotable_emitsBundle() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        String bodyA = alertSummaryJson("ThingA", row1);
        String bodyB = alertSummaryJson("ThingB", row1);
        String bodyC = alertSummaryJson("ThingC", row1);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingName\":\"ThingA\",\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingName\":\"ThingB\",\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingName\":\"ThingC\",\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", bodyA));
        messages.add(ChatMessage.toolResult("x2", bodyB));
        messages.add(ChatMessage.toolResult("x3", bodyC));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertTrue(bundle.path("result").path("rows").isArray());
        assertTrue(bundle.path("result").path("rows").size() >= 3);
    }

    @Test
    void compactBatch_queryAlertHistory_threeInlineInfotable_emitsBundle() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        String bodyA = alertHistoryJson("ThingA", row1);
        String bodyB = alertHistoryJson("ThingB", row1);
        String bodyC = alertHistoryJson("ThingC", row1);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("h1", "query_alert_history", "{\"thingName\":\"ThingA\",\"timePreset\":\"last_7d\"}"),
                new ToolCall("h2", "query_alert_history", "{\"thingName\":\"ThingB\",\"timePreset\":\"last_7d\"}"),
                new ToolCall("h3", "query_alert_history", "{\"thingName\":\"ThingC\",\"timePreset\":\"last_7d\"}"))));
        messages.add(ChatMessage.toolResult("h1", bodyA));
        messages.add(ChatMessage.toolResult("h2", bodyB));
        messages.add(ChatMessage.toolResult("h3", bodyC));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertTrue("query_alert_history".equals(bundle.path("toolName").asText()));
    }

    @Test
    void compactBatch_alertSummary_matrixConstants_skipsCohort() throws Exception {
        String fmt = InfoTableMatrixCodec.FORMAT_MATRIX_V1;
        String pad = repeat('p', 400);
        String base = "\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"_pad\":\"" + pad
                + "\",\"columns\":[{\"name\":\"alert\",\"baseType\":\"STRING\"}],\"rows\":[[\"r\"]]";
        String b1 = "{" + base + ",\"thingName\":\"T1\",\"$format\":\"" + fmt
                + "\",\"constants\":{\"alertType\":\"High\"}}";
        String b2 = "{" + base + ",\"thingName\":\"T2\",\"$format\":\"" + fmt
                + "\",\"constants\":{\"alertType\":\"Low\"}}";
        String b3 = "{" + base + ",\"thingName\":\"T3\",\"$format\":\"" + fmt
                + "\",\"constants\":{\"alertType\":\"Mid\"}}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("a1", "query_alert_summary", "{\"thingName\":\"T1\",\"ackState\":\"all\"}"),
                new ToolCall("a2", "query_alert_summary", "{\"thingName\":\"T2\",\"ackState\":\"all\"}"),
                new ToolCall("a3", "query_alert_summary", "{\"thingName\":\"T3\",\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("a1", b1));
        messages.add(ChatMessage.toolResult("a2", b2));
        messages.add(ChatMessage.toolResult("a3", b3));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
        assertTrue(messages.get(1).getContent().contains("constants"));
    }

    @Test
    void compactBatch_getPropertyValues_oneNonOk_skipsCohort() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("ca", "get_property_values", "{\"thingName\":\"A\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cb", "get_property_values", "{\"thingName\":\"B\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cc", "get_property_values", "{\"thingName\":\"C\",\"propertyNames\":[\"speed\"]}"))));
        messages.add(ChatMessage.toolResult("ca", gpvsJson("A", "speed", "NUMBER", 10)));
        messages.add(ChatMessage.toolResult("cb", gpvsJson("B", "speed", "NUMBER", 20)));
        messages.add(ChatMessage.toolResult("cc", gpvsJsonNoValue("C", "speed")));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
        assertTrue(messages.get(3).getContent().contains("NO_VALUE"));
    }

    @Test
    void compactBatch_getPropertyValues_mixedBaseType_skipsCohort() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("ca", "get_property_values", "{\"thingName\":\"A\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cb", "get_property_values", "{\"thingName\":\"B\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cc", "get_property_values", "{\"thingName\":\"C\",\"propertyNames\":[\"speed\"]}"))));
        messages.add(ChatMessage.toolResult("ca", gpvsJson("A", "speed", "NUMBER", 10)));
        messages.add(ChatMessage.toolResult("cb", gpvsJson("B", "speed", "NUMBER", 20)));
        messages.add(ChatMessage.toolResult("cc", gpvsJson("C", "speed", "INTEGER", 30)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
    }

    @Test
    void compactBatch_getPropertyValues_equalSize_skipsCohort() throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("ca", "get_property_values", "{\"thingName\":\"A\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cb", "get_property_values", "{\"thingName\":\"B\",\"propertyNames\":[\"speed\"]}"),
                new ToolCall("cc", "get_property_values", "{\"thingName\":\"C\",\"propertyNames\":[\"speed\"]}"))));
        messages.add(ChatMessage.toolResult("ca", gpvsJsonSmall("A", "speed", "NUMBER", 1)));
        messages.add(ChatMessage.toolResult("cb", gpvsJsonSmall("B", "speed", "NUMBER", 2)));
        messages.add(ChatMessage.toolResult("cc", gpvsJsonSmall("C", "speed", "NUMBER", 3)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
    }

    /** S16: live N=1 {@code thingNames[]} fan-out must cohort-merge; member refs keep array wire. */
    @Test
    void compactBatch_queryAlertSummary_liveThingNamesArray_emitsBundleWithThingNamesMemberArgs() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingNames\":[\"ThingA\"],\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingB\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertEquals("ThingA", bundle.path("members").get(0).path("args").path("thingNames").get(0).asText());
        assertFalse(bundle.path("members").get(0).path("args").has("thingName"));
        JsonNode member = MAPPER.readTree(messages.get(3).getContent());
        assertTrue(member.path("$format").asText().contains("cohort.member"));
        assertEquals("ThingC", member.path("args").path("thingNames").get(0).asText());
        assertFalse(member.path("args").has("thingName"));
    }

    /** S16: scalar {@code thingName} replay transcripts keep scalar member args. */
    @Test
    void compactBatch_queryAlertSummary_scalarThingName_memberArgsRemainScalar() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingName\":\"ThingA\",\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingName\":\"ThingB\",\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingName\":\"ThingC\",\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertEquals("ThingA", bundle.path("members").get(0).path("args").path("thingName").asText());
        assertFalse(bundle.path("members").get(0).path("args").has("thingNames"));
        JsonNode member = MAPPER.readTree(messages.get(2).getContent());
        assertEquals("ThingB", member.path("args").path("thingName").asText());
        assertFalse(member.path("args").has("thingNames"));
    }

    /** S16: mixed scalar + array fan-out with same filters merges; each member keeps its wire shape. */
    @Test
    void compactBatch_queryAlertSummary_mixedScalarAndThingNames_preservesPerMemberWire() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingName\":\"ThingA\",\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingB\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertEquals("ThingA", bundle.path("members").get(0).path("args").path("thingName").asText());
        assertEquals("ThingB", bundle.path("members").get(1).path("args").path("thingNames").get(0).asText());
        JsonNode m2 = MAPPER.readTree(messages.get(3).getContent());
        assertEquals("ThingC", m2.path("args").path("thingNames").get(0).asText());
    }

    /** S16: multi-element {@code thingNames[]} is not a fan-out cohort member. */
    @Test
    void compactBatch_queryAlertSummary_multiElementThingNames_skipsCohort() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary",
                        "{\"thingNames\":[\"ThingA\",\"ThingZ\"],\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingB\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        // Only two eligible N=1 members remain → below cohort threshold of 3.
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
        assertFalse(messages.get(2).getContent().contains("parler.cohort.bundle"));
    }

    /** S16: missing Thing identity (no scalar, no single-element array, no body) skips candidate. */
    @Test
    void compactBatch_queryAlertSummary_missingThingIdentity_skipsCohort() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        String bodyNoThing = alertSummaryJson("", row1).replace("\"thingName\":\"\",", "");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingB\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", bodyNoThing));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(2).getContent().contains("parler.cohort.bundle"));
    }

    /** S16: duplicate Thing names still merge deterministically when ≥3 eligible siblings. */
    @Test
    void compactBatch_queryAlertSummary_duplicateThingNames_mergesInOriginalOrder() throws Exception {
        String row1 = "{\"alert\":\"x\",\"severity\":1}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingNames\":[\"ThingA\"],\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingA\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingA", row1)));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", row1)));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        JsonNode bundle = MAPPER.readTree(messages.get(1).getContent());
        assertTrue(bundle.path("$format").asText().contains("cohort.bundle"));
        assertEquals(3, bundle.path("memberCount").asInt());
        assertEquals("ThingA", bundle.path("members").get(0).path("args").path("thingNames").get(0).asText());
        assertEquals("ThingA", bundle.path("members").get(1).path("args").path("thingNames").get(0).asText());
        assertEquals("ThingC", bundle.path("members").get(2).path("args").path("thingNames").get(0).asText());
    }

    /** S16: {@code ALERT_SUMMARY_MULTI} bodies are not Tier-0 cohort candidates. */
    @Test
    void compactBatch_queryAlertSummary_alertSummaryMulti_skipsCohort() throws Exception {
        String pad = repeat('m', 400);
        String multi = "{\"status\":\"success\",\"resultKind\":\"ALERT_SUMMARY_MULTI\",\"_pad\":\"" + pad
                + "\",\"completeness\":\"complete\",\"byThing\":[{\"thingName\":\"A\"}],"
                + "\"columns\":[{\"name\":\"alert\",\"baseType\":\"STRING\"}],\"rows\":[{\"alert\":\"x\"}],"
                + "\"thingName\":\"A\"}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("x1", "query_alert_summary", "{\"thingNames\":[\"A\",\"B\"],\"ackState\":\"all\"}"),
                new ToolCall("x2", "query_alert_summary", "{\"thingNames\":[\"ThingB\"],\"ackState\":\"all\"}"),
                new ToolCall("x3", "query_alert_summary", "{\"thingNames\":[\"ThingC\"],\"ackState\":\"all\"}"))));
        messages.add(ChatMessage.toolResult("x1", multi));
        messages.add(ChatMessage.toolResult("x2", alertSummaryJson("ThingB", "{\"alert\":\"x\",\"severity\":1}")));
        messages.add(ChatMessage.toolResult("x3", alertSummaryJson("ThingC", "{\"alert\":\"x\",\"severity\":1}")));
        LlmToolResultReplayCompactor.compactBatch(messages, 0, true, null);
        assertFalse(messages.get(1).getContent().contains("parler.cohort.bundle"));
        assertTrue(messages.get(1).getContent().contains("ALERT_SUMMARY_MULTI"));
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String gpvsJson(String thing, String prop, String baseType, int value) {
        StringBuilder pad = new StringBuilder(400);
        for (int i = 0; i < 400; i++) {
            pad.append('z');
        }
        return "{\"status\":\"success\",\"thingName\":\"" + thing + "\",\"_pad\":\"" + pad + "\",\"properties\":["
                + "{\"name\":\"" + prop + "\",\"ok\":true,\"baseType\":\"" + baseType + "\",\"value\":" + value
                + "}]}";
    }

    private static String gpvsJsonSmall(String thing, String prop, String baseType, int value) {
        return "{\"status\":\"success\",\"thingName\":\"" + thing + "\",\"properties\":["
                + "{\"name\":\"" + prop + "\",\"ok\":true,\"baseType\":\"" + baseType + "\",\"value\":" + value
                + "}]}";
    }

    private static String gpvsJsonNoValue(String thing, String prop) {
        StringBuilder pad = new StringBuilder(400);
        for (int i = 0; i < 400; i++) {
            pad.append('z');
        }
        return "{\"status\":\"success\",\"thingName\":\"" + thing + "\",\"_pad\":\"" + pad + "\",\"properties\":["
                + "{\"name\":\"" + prop + "\",\"ok\":false,\"code\":\"NO_VALUE\",\"message\":\"no\"}]}";
    }

    private static String alertSummaryJson(String thingName, String rowJson) {
        StringBuilder pad = new StringBuilder(400);
        for (int i = 0; i < 400; i++) {
            pad.append('y');
        }
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":1,"
                + "\"thingName\":\"" + thingName + "\",\"_pad\":\"" + pad + "\","
                + "\"columns\":[{\"name\":\"alert\",\"baseType\":\"STRING\"},{\"name\":\"severity\",\"baseType\":\"INTEGER\"}],"
                + "\"rows\":[" + rowJson + "]}";
    }

    private static String alertHistoryJson(String thingName, String rowJson) {
        StringBuilder pad = new StringBuilder(400);
        for (int i = 0; i < 400; i++) {
            pad.append('h');
        }
        return "{\"status\":\"success\",\"resultKind\":\"INFOTABLE\",\"rowCount\":1,"
                + "\"thingName\":\"" + thingName + "\",\"_pad\":\"" + pad + "\","
                + "\"columns\":[{\"name\":\"alert\",\"baseType\":\"STRING\"},{\"name\":\"severity\",\"baseType\":\"INTEGER\"}],"
                + "\"rows\":[" + rowJson + "]}";
    }
}
