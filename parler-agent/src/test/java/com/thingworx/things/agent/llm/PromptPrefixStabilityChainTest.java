package com.thingworx.things.agent.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.ParlerTimeAnchor;
import com.thingworx.things.agent.compaction.ContextBudgetPlanner;
import com.thingworx.things.agent.tools.AgentToolContext;

/** Two-wire stable-prefix / volatile-suffix chains and exact-content accounting fixtures. */
class PromptPrefixStabilityChainTest {

    private static final LlmUsageWireIds IDS =
            new LlmUsageWireIds("provider", "thing", "fixture-shape", "fixture-model");

    @AfterEach
    void clearTurnContext() {
        AgentToolContext.clear();
    }

    @Test
    void classifierUsesOnlyTheFourCanonicalAuthorityHeads() {
        assertEquals(ParlerSuffixFraming.AuthorityClass.SKILL,
                ParlerSuffixFraming.classify(ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nbody"));
        assertEquals(ParlerSuffixFraming.AuthorityClass.OBSERVATIONS,
                ParlerSuffixFraming.classify(ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nbody"));
        assertEquals(ParlerSuffixFraming.AuthorityClass.INSTRUCTION,
                ParlerSuffixFraming.classify(ParlerSuffixFraming.SERVER_INSTRUCTION + "\nbody"));
        assertEquals(ParlerSuffixFraming.AuthorityClass.TIME,
                ParlerSuffixFraming.classify(ParlerSuffixFraming.TIME_CONTEXT + "\nbody"));
        assertEquals(null, ParlerSuffixFraming.classify("Current time (UTC) for tool arguments: now"));
        assertEquals(null, ParlerSuffixFraming.classify("## Recent Tool Evidence"));
        assertEquals(null, ParlerSuffixFraming.classify("## Document retrieval coverage"));
    }

    @Test
    void catalogStaysInStableRowAndOnlyTimeChangesAtTerminalSuffix_onBothWires() {
        String stable = "stable-base\n\n---\nworkflow-catalog\n\n---\n"
                + ParlerTimeAnchor.STABLE_TIME_GUIDANCE;
        String timeA = LlmUtcClockInjector.buildTimeBlockContent(
                "America/New_York", Instant.parse("2026-05-06T18:00:00Z"));
        String timeB = LlmUtcClockInjector.buildTimeBlockContent(
                "America/New_York", Instant.parse("2026-05-06T18:01:00Z"));

        List<ChatMessage> roundA = List.of(
                ChatMessage.system(stable), ChatMessage.system(timeA), ChatMessage.user("user-one"));
        List<ChatMessage> roundB = List.of(
                ChatMessage.system(stable), ChatMessage.user("user-one"), ChatMessage.assistant("answer-one"),
                ChatMessage.system(timeB), ChatMessage.user("user-two"));

        List<Map<String, Object>> openA = ChatCompletionsApiMessages.toApiMessages(roundA);
        List<Map<String, Object>> openB = ChatCompletionsApiMessages.toApiMessages(roundB);
        assertEquals(stable, openA.get(0).get("content"));
        assertEquals(stable, openB.get(0).get("content"));
        assertEquals(timeA, openA.get(openA.size() - 1).get("content"));
        assertEquals(timeB, openB.get(openB.size() - 1).get("content"));

        Map<String, Object> anthropicA = AnthropicMessagesApi.buildRequestPayload(
                roundA, Collections.emptyList(), "claude", 0, 1024);
        Map<String, Object> anthropicB = AnthropicMessagesApi.buildRequestPayload(
                roundB, Collections.emptyList(), "claude", 0, 1024);
        assertEquals(stable, firstSystemText(anthropicA));
        assertEquals(stable, firstSystemText(anthropicB));
        assertFalse(promptTexts(anthropicA.get("system")).contains(timeA));
        assertFalse(promptTexts(anthropicB.get("system")).contains(timeB));
        assertTrue(promptTexts(anthropicA.get("messages")).contains(timeA));
        assertTrue(promptTexts(anthropicB.get("messages")).contains(timeB));
    }

    @Test
    void fullEphemeralSet_chain_isStablePartitionedInAuthorityOrder_onBothWires() {
        String stable = "stable-with-catalog-anchor-and-evidence-rule";
        String skill = ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nslash-skill";
        String host = ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nhost-scope";
        String evidence = ParlerSuffixFraming.SERVER_OBSERVATIONS + "\n## Recent Tool Evidence\nrow";
        String coverage = ParlerSuffixFraming.SERVER_INSTRUCTION + "\nRetrieval is saturated: finalize";
        String timeA = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: a";
        String timeB = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: b";
        String timeC = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: c";
        ChatMessage firstToolUse = ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("call-1", "read", "{}")));
        ChatMessage firstToolResult = ChatMessage.toolResult("call-1", "result-1");
        ChatMessage secondToolUse = ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("call-2", "read", "{}")));
        ChatMessage secondToolResult = ChatMessage.toolResult("call-2", "result-2");
        List<List<ChatMessage>> chain = List.of(
                List.of(ChatMessage.system(stable), ChatMessage.system(timeA), ChatMessage.system(skill),
                        ChatMessage.system(coverage), ChatMessage.system(host), ChatMessage.system(evidence),
                        ChatMessage.user("current-user")),
                List.of(ChatMessage.system(stable), ChatMessage.system(skill), ChatMessage.system(host),
                        ChatMessage.user("current-user"), firstToolUse, firstToolResult,
                        ChatMessage.system(timeB), ChatMessage.system(evidence), ChatMessage.system(coverage)),
                List.of(ChatMessage.system(stable), ChatMessage.system(host), ChatMessage.system(skill),
                        ChatMessage.user("current-user"), firstToolUse, firstToolResult,
                        secondToolUse, secondToolResult, ChatMessage.system(coverage), ChatMessage.system(timeC),
                        ChatMessage.system(evidence)));
        List<String> times = List.of(timeA, timeB, timeC);

        for (int i = 0; i < chain.size(); i++) {
            List<ChatMessage> planned = chain.get(i);
            List<String> expectedSuffix = List.of(skill, host, evidence, coverage, times.get(i));
            List<Map<String, Object>> openAi = ChatCompletionsApiMessages.toApiMessages(planned);
            assertEquals(stable, openAi.get(0).get("content"));
            assertEquals(expectedSuffix, tail(promptTexts(openAi), expectedSuffix.size()));

            Map<String, Object> anthropic = AnthropicMessagesApi.buildRequestPayload(
                    planned, Collections.emptyList(), "claude", 0, 1024);
            assertEquals(stable, firstSystemText(anthropic));
            assertEquals(expectedSuffix,
                    tail(promptTexts(anthropic.get("messages")), expectedSuffix.size()));
            List<String> allAnthropic = concat(
                    promptTexts(anthropic.get("system")), promptTexts(anthropic.get("messages")));
            for (String suffixRow : expectedSuffix) {
                assertEquals(1, counts(allAnthropic).get(suffixRow));
            }
        }
    }

    @Test
    void assistantTail_usesOneSuffixOnlyAnthropicUserCarrier_withoutChangingAssistant() {
        String assistant = "durable assistant prose";
        String skill = ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nslash";
        String observations = ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nhost";
        String instruction = ParlerSuffixFraming.SERVER_INSTRUCTION + "\nfinalize";
        String time = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: fixed";
        List<ChatMessage> planned = List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("user"),
                ChatMessage.assistant(assistant),
                ChatMessage.system(time),
                ChatMessage.system(instruction),
                ChatMessage.system(observations),
                ChatMessage.system(skill));

        Map<String, Object> payload = AnthropicMessagesApi.buildRequestPayload(
                planned, Collections.emptyList(), "claude", 0, 1024);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("messages");
        assertEquals(3, rows.size());
        assertEquals("assistant", rows.get(1).get("role"));
        assertEquals(assistant, rows.get(1).get("content"));
        assertEquals("user", rows.get(2).get("role"));
        assertEquals(List.of(skill, observations, instruction, time), promptTexts(rows.get(2).get("content")));
        assertFalse(rows.get(2).toString().contains("cache_control"));
        assertTrue(containsOnlyClassifiedSuffixTextBlocks(rows.get(2).get("content")),
                "the carrier has no original user text or tool_result candidate and is structurally suffix-only");
        assertEquals(1, counts(promptTexts(payload.get("messages"))).get(assistant));
    }

    @Test
    void toolHeavyAccounting_emitsEveryPlannedContentExactlyOnce_andCreatesNoPromptText() {
        String stable = "planned-stable";
        String user = "planned-user";
        String result = "planned-tool-result";
        String time = LlmUtcClockInjector.buildTimeBlockContent(
                null, Instant.parse("2026-05-06T18:00:00Z"));
        List<ChatMessage> planned = List.of(
                ChatMessage.system(stable),
                ChatMessage.user(user),
                ChatMessage.assistantWithToolCalls(List.of(new ToolCall("call-1", "read", "{}"))),
                ChatMessage.toolResult("call-1", result),
                ChatMessage.system(time));
        Map<String, Integer> expected = counts(List.of(stable, user, result, time));

        List<Map<String, Object>> openAi = ChatCompletionsApiMessages.toApiMessages(planned);
        assertEquals(expected, counts(promptTexts(openAi)));

        Map<String, Object> anthropic = AnthropicMessagesApi.buildRequestPayload(
                planned, Collections.emptyList(), "claude", 0, 1024);
        List<String> anthropicTexts = new ArrayList<>();
        anthropicTexts.addAll(promptTexts(anthropic.get("system")));
        anthropicTexts.addAll(promptTexts(anthropic.get("messages")));
        assertEquals(expected, counts(anthropicTexts));
    }

    @Test
    void framingCharactersParticipateInPlannerAdmissionBoundary() {
        String rawValues = "- now_utc: 2026-05-06T18:00:00.000Z";
        List<ChatMessage> unframed = historyWithTime(rawValues);
        List<ChatMessage> framed = historyWithTime(ParlerSuffixFraming.TIME_CONTEXT + "\n" + rawValues);
        AgentToolContext.setParlerEphemeralSystemIndices(ParlerEphemeralSystemIndices.NONE);

        ContextBudgetPlanner.Metrics baseline = ContextBudgetPlanner.Metrics.compute(
                unframed, Collections.emptyList(), IDS, 100_000);
        long boundary = (long) baseline.stableChars + baseline.currentUserChars + baseline.transcriptChars;
        ContextBudgetPlanner.PlannedOutbound withoutFraming = ContextBudgetPlanner.planForProviderRound(
                LoggerFactory.getLogger(PromptPrefixStabilityChainTest.class), unframed, Collections.emptyList(), IDS,
                100_000, boundary, -1, 3);
        ContextBudgetPlanner.PlannedOutbound withFraming = ContextBudgetPlanner.planForProviderRound(
                LoggerFactory.getLogger(PromptPrefixStabilityChainTest.class), framed, Collections.emptyList(), IDS,
                100_000, boundary, -1, 3);

        assertEquals(5, withoutFraming.getOutboundMessages().size());
        assertEquals(3, withFraming.getOutboundMessages().size(),
                "framing overhead must force the oldest transcript pair across the exact cap boundary");
        assertEquals(ParlerSuffixFraming.TIME_CONTEXT + "\n" + rawValues,
                withFraming.getOutboundMessages().get(1).getContent());
    }

    private static List<ChatMessage> historyWithTime(String timeContent) {
        return List.of(
                ChatMessage.system("stable"),
                ChatMessage.user("old-user-" + "u".repeat(40)),
                ChatMessage.assistant("old-answer-" + "a".repeat(40)),
                ChatMessage.system(timeContent),
                ChatMessage.user("current-user"));
    }

    @SuppressWarnings("unchecked")
    private static String firstSystemText(Map<String, Object> payload) {
        Object system = payload.get("system");
        if (system instanceof String) {
            return (String) system;
        }
        return (String) ((Map<String, Object>) ((List<?>) system).get(0)).get("text");
    }

    private static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> out = new HashMap<>();
        for (String value : values) {
            out.put(value, out.getOrDefault(value, 0) + 1);
        }
        return out;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> out = new ArrayList<>(first);
        out.addAll(second);
        return out;
    }

    private static List<String> tail(List<String> values, int size) {
        return values.subList(values.size() - size, values.size());
    }

    private static boolean containsOnlyClassifiedSuffixTextBlocks(Object content) {
        if (!(content instanceof List<?>) || ((List<?>) content).isEmpty()) {
            return false;
        }
        for (Object block : (List<?>) content) {
            if (!(block instanceof Map<?, ?>)) {
                return false;
            }
            Map<?, ?> map = (Map<?, ?>) block;
            if (!"text".equals(map.get("type"))
                    || !(map.get("text") instanceof String)
                    || !ParlerSuffixFraming.isClassified((String) map.get("text"))) {
                return false;
            }
        }
        return true;
    }

    private static List<String> promptTexts(Object value) {
        List<String> out = new ArrayList<>();
        collectPromptTexts(value, out);
        return out;
    }

    private static void collectPromptTexts(Object value, List<String> out) {
        if (value instanceof List<?>) {
            for (Object item : (List<?>) value) {
                collectPromptTexts(item, out);
            }
            return;
        }
        if (!(value instanceof Map<?, ?>)) {
            return;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        Object text = map.get("text");
        if (text instanceof String) {
            out.add((String) text);
        }
        Object content = map.get("content");
        if (content instanceof String) {
            out.add((String) content);
        } else {
            collectPromptTexts(content, out);
        }
    }
}
