package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnthropicHistoryMarkerSelectionTest {

    @Test
    void trueKindMarksCurrentAndPreviousSubstantiveUsers_andKeepsAllUserRowsArrays() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("older"),
                ChatMessage.assistant("answer"),
                ChatMessage.user("current"),
                ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));

        Map<String, Object> payload = payload(messages, Collections.emptyList(), true);
        List<Map<String, Object>> rows = rows(payload);
        Map<String, Object> older = rows.get(0);
        Map<String, Object> current = rows.get(2);
        assertTrue(older.get("content") instanceof List<?>);
        assertTrue(current.get("content") instanceof List<?>);
        assertEquals("older", block(older, 0).get("text"));
        assertEquals("current", block(current, 0).get("text"));
        assertTrue(block(older, 0).containsKey("cache_control"));
        assertTrue(block(current, 0).containsKey("cache_control"));
        assertFalse(block(current, 1).containsKey("cache_control"), "volatile suffix is never marked");
        assertEquals(3, countCacheControls(payload), "stable system plus two history markers without tools");
    }

    @Test
    void falseAndNullKindsKeepTextStrings_andOnlyStableMarkers() {
        List<ChatMessage> messages = List.of(ChatMessage.system("stable"), ChatMessage.user("u"));
        Map<String, Object> falseKind = payload(messages, Collections.emptyList(), false);
        Map<String, Object> nullKind = AnthropicMessagesApi.buildRequestPayload(
                messages, Collections.emptyList(), "claude", 0.0, 256);
        assertEquals("u", rows(falseKind).get(0).get("content"));
        assertEquals("u", rows(nullKind).get(0).get("content"));
        assertEquals(1, countCacheControls(falseKind));
        assertEquals(1, countCacheControls(nullKind));
    }

    @Test
    void initialTrueKindCountsAreConditionalOnTools() {
        List<ChatMessage> messages = List.of(ChatMessage.system("stable"), ChatMessage.user("u"));
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        assertEquals(2, countCacheControls(payload(messages, Collections.emptyList(), true)),
                "stable system plus current frontier");
        assertEquals(3, countCacheControls(payload(messages, tools, true)),
                "stable system, last tool, and current frontier");
    }

    @Test
    void initialSlashOnlyDirectiveStaysNonEmptyArrayAndMarksItsFrontier() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("/Demo"),
                ChatMessage.system(ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nloaded Demo"),
                ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        Map<String, Object> payload = payload(messages, tools, true);
        Map<String, Object> user = rows(payload).get(0);
        assertTrue(user.get("content") instanceof List<?>);
        assertEquals(3, blocks(user).size());
        assertEquals("/Demo", block(user, 0).get("text"));
        assertTrue(block(user, 0).containsKey("cache_control"));
        assertEquals(ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nloaded Demo", block(user, 1).get("text"));
        assertEquals(ParlerSuffixFraming.TIME_CONTEXT + "\nnow", block(user, 2).get("text"));
        assertFalse(block(user, 1).containsKey("cache_control"), "skill suffix is never marked");
        assertFalse(block(user, 2).containsKey("cache_control"), "time suffix is never marked");
        assertEquals(3, countCacheControls(payload), "stable system, last tool, and slash frontier are marked");
    }

    @Test
    void historicalSlashOnlyDirectiveParticipatesInPreviousFrontierSelection() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("older"),
                ChatMessage.assistant("first answer"),
                ChatMessage.user("/Demo"),
                ChatMessage.assistant("slash-only answer"),
                ChatMessage.user("eligible-current"),
                ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));

        Map<String, Object> payload = payload(messages, Collections.emptyList(), true);
        List<Map<String, Object>> rows = rows(payload);
        Map<String, Object> older = rows.get(0);
        Map<String, Object> previous = rows.get(2);
        Map<String, Object> current = rows.get(4);
        assertFalse(block(older, 0).containsKey("cache_control"));
        assertTrue(block(previous, 0).containsKey("cache_control"));
        assertEquals("/Demo", block(previous, 0).get("text"));
        assertTrue(block(current, 0).containsKey("cache_control"));
        assertFalse(block(current, 1).containsKey("cache_control"), "volatile suffix is never marked");
        assertEquals(3, countCacheControls(payload), "stable system plus two eligible history markers");
        assertTrue(countCacheControls(payload) <= 4);
    }

    @Test
    void emptyOrdinaryUserRowsFailClosedForTrueAndFalseKinds() {
        List<ChatMessage> empty = List.of(ChatMessage.system("stable"), ChatMessage.user(""));
        List<ChatMessage> whitespace = List.of(ChatMessage.system("stable"), ChatMessage.user(" \t "));
        List<ChatMessage> nullContent = List.of(ChatMessage.system("stable"), ChatMessage.user(null));

        for (List<ChatMessage> messages : List.of(empty, whitespace, nullContent)) {
            for (boolean enabled : List.of(false, true)) {
                IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> payload(messages, Collections.emptyList(), enabled));
                assertEquals("Anthropic user text content must be non-empty", error.getMessage());
            }
        }
    }

    @Test
    void toolResultFrontierMarksOnlyLastResultBeforeSuffix() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("u"),
                ChatMessage.assistantWithToolCalls(List.of(
                        new ToolCall("a", "read", "{}"), new ToolCall("b", "read", "{}"))),
                ChatMessage.toolResult("a", "ra"),
                ChatMessage.toolResult("b", "rb"),
                ChatMessage.system(ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nevidence"));
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        Map<String, Object> payload = payload(messages, tools, true);
        Map<String, Object> toolResults = rows(payload).get(2);
        assertFalse(block(toolResults, 0).containsKey("cache_control"));
        assertTrue(block(toolResults, 1).containsKey("cache_control"));
        assertEquals("text", block(toolResults, 2).get("type"));
        assertFalse(block(toolResults, 2).containsKey("cache_control"));
        assertEquals(4, countCacheControls(payload), "system, last tool, previous user, current result");
    }

    @Test
    void suffixOnlyCarrierIsExcludedOnNoNewUserResample() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("u"),
                ChatMessage.assistantWithToolCalls(List.of(new ToolCall("a", "read", "{}"))),
                ChatMessage.toolResult("a", "r"),
                ChatMessage.assistant("durable prose"),
                ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));

        Map<String, Object> payload = payload(messages, Collections.emptyList(), true);
        List<Map<String, Object>> rows = rows(payload);
        Map<String, Object> carrier = rows.get(rows.size() - 1);
        assertEquals("user", carrier.get("role"));
        assertEquals(1, blocks(carrier).size());
        assertFalse(block(carrier, 0).containsKey("cache_control"));
        assertTrue(block(rows.get(2), 0).containsKey("cache_control"), "current tool frontier remains marked");
        assertTrue(block(rows.get(0), 0).containsKey("cache_control"), "older candidate is harmlessly retained");
    }

    @Test
    void postTrimOrCheckpointRewriteSelectsOnlySurvivingSubstantiveCandidates() {
        List<ChatMessage> original = new ArrayList<>(List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("surviving-oldest"),
                ChatMessage.assistant("old answer"),
                ChatMessage.user("trimmed-intermediate"),
                ChatMessage.assistant("trimmed answer"),
                ChatMessage.user("surviving-current"),
                ChatMessage.assistant("durable tail"),
                ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow")));
        original.subList(3, 5).clear();
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        Map<String, Object> payload = payload(original, tools, true);
        List<Map<String, Object>> rows = rows(payload);
        Map<String, Object> oldest = rows.get(0);
        Map<String, Object> current = rows.get(2);
        Map<String, Object> carrier = rows.get(rows.size() - 1);
        assertEquals("surviving-oldest", block(oldest, 0).get("text"));
        assertEquals("surviving-current", block(current, 0).get("text"));
        assertTrue(block(oldest, 0).containsKey("cache_control"));
        assertTrue(block(current, 0).containsKey("cache_control"));
        assertFalse(payload.toString().contains("trimmed-intermediate"));
        assertEquals("user", carrier.get("role"));
        assertEquals(1, blocks(carrier).size());
        assertFalse(block(carrier, 0).containsKey("cache_control"));
        assertEquals(4, countCacheControls(payload),
                "stable system, last tool, and the two surviving frontiers only");
    }

    @Test
    void twentyFiveSequentialTwoCallRoundsKeepMarkerBudgetAndArrayStability() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        messages.add(ChatMessage.user("initial"));
        for (int i = 0; i < 25; i++) {
            messages.add(ChatMessage.assistantWithToolCalls(List.of(
                    new ToolCall("a" + i, "read", "{}"),
                    new ToolCall("b" + i, "read", "{}"))));
            messages.add(ChatMessage.toolResult("a" + i, "ra" + i));
            messages.add(ChatMessage.toolResult("b" + i, "rb" + i));
        }
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        Map<String, Object> payload = payload(messages, tools, true);
        assertEquals(4, countCacheControls(payload));
        assertEquals(26, rows(payload).stream().filter(row -> "user".equals(row.get("role"))).count());
        assertTrue(rows(payload).get(0).get("content") instanceof List<?>,
                "an old text row never demotes after leaving the marked frontier");
    }

    @Test
    void oneRoundTwentyFiveParallelCallsExceedsLookbackBeforeMarkerAssertions() {
        List<ToolCall> calls = new ArrayList<>();
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        messages.add(ChatMessage.user("previous-frontier"));
        for (int i = 0; i < 25; i++) {
            calls.add(new ToolCall("call-" + i, "read", "{}"));
        }
        messages.add(ChatMessage.assistantWithToolCalls(calls));
        for (int i = 0; i < 25; i++) {
            messages.add(ChatMessage.toolResult("call-" + i, "result-" + i));
        }
        messages.add(ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));
        List<ToolDefinition> tools = List.of(new ToolDefinition("read", "read", Collections.emptyMap()));

        Map<String, Object> payload = payload(messages, tools, true);
        List<Map<String, Object>> rows = rows(payload);
        Map<String, Object> previous = rows.get(0);
        Map<String, Object> assistant = rows.get(1);
        Map<String, Object> current = rows.get(2);
        int currentFrontierIndex = 24;
        int positionsBetweenFrontiers = blocks(assistant).size() + currentFrontierIndex;

        assertEquals(49, positionsBetweenFrontiers,
                "25 tool_use blocks plus 24 earlier tool_result blocks lie between the two frontier blocks");
        assertTrue(positionsBetweenFrontiers > 20,
                "the one-round shape must exceed the measured approximate lookback before marker assertions");
        assertTrue(block(previous, 0).containsKey("cache_control"));
        assertTrue(block(current, currentFrontierIndex).containsKey("cache_control"));
        assertFalse(block(current, currentFrontierIndex + 1).containsKey("cache_control"),
                "the suffix following the current frontier remains unmarked");
        assertEquals(4, countCacheControls(payload));
    }

    @Test
    void diagnosticsPreserveUnknownSystemAuthorityAndNeverExposeItsText() {
        List<ChatMessage> messages = List.of(
                ChatMessage.system("stable"),
                ChatMessage.system("unclassified-secret"),
                ChatMessage.user("u"));
        LlmChatRequest request = LlmChatRequest.copyWithCacheControl(
                LlmChatRequest.forAgentRound(messages, null, 0.0, 256, null), true);

        AnthropicMessagesApi.RequestPayloadResult result =
                AnthropicMessagesApi.buildRequestPayloadWithDiagnostics(
                        messages, null, "claude", 0.0, 256, 0, request,
                        AnthropicSamplingParametersMode.legacy);
        assertEquals(1, result.getDiagnostics().getEntries().size());
        assertEquals("b645abfd0f5b985a",
                result.getDiagnostics().getEntries().get(0).getContentDigest());
        assertTrue(result.getPayload().get("system").toString().contains("unclassified-secret"),
                "Anthropic keeps unknown system authority in the top-level system field");
        assertFalse(result.getDiagnostics().toString().contains("unclassified-secret"));
    }

    private static Map<String, Object> payload(
            List<ChatMessage> messages, List<ToolDefinition> tools, boolean enabled) {
        LlmChatRequest request = LlmChatRequest.copyWithCacheControl(
                LlmChatRequest.forAgentRound(messages, tools, 0.0, 256, null), enabled);
        return AnthropicMessagesApi.buildRequestPayload(
                messages, tools, "claude", 0.0, 256, 0, request);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Map<String, Object> payload) {
        return (List<Map<String, Object>>) payload.get("messages");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> blocks(Map<String, Object> row) {
        return (List<Map<String, Object>>) row.get("content");
    }

    private static Map<String, Object> block(Map<String, Object> row, int index) {
        return blocks(row).get(index);
    }

    private static int countCacheControls(Object value) {
        if (value instanceof Map<?, ?>) {
            int count = ((Map<?, ?>) value).containsKey("cache_control") ? 1 : 0;
            for (Object child : ((Map<?, ?>) value).values()) {
                count += countCacheControls(child);
            }
            return count;
        }
        if (value instanceof List<?>) {
            int count = 0;
            for (Object child : (List<?>) value) {
                count += countCacheControls(child);
            }
            return count;
        }
        return 0;
    }
}
