package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import com.thingworx.things.agent.ParlerEphemeralSystemIndices;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.DocumentCoverageSummaryInjector;
import com.thingworx.things.agent.llm.EmptyFinalAnswerRetryInjector;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.LlmUtcClockInjector;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.taskstate.TaskStateLlmInjector;
import com.thingworx.things.agent.tools.AgentToolContext;

class ContextBudgetPlannerTest {

    @AfterEach
    void tearDownAgentToolContext() {
        LlmReplayCompactionGate.clearAllTestHooks();
        AgentToolContext.clear();
    }

    private static int rowCharsForTest(ChatMessage m) {
        if (m == null) {
            return 0;
        }
        switch (m.getRole()) {
            case SYSTEM:
            case USER:
                return len(m.getContent());
            case ASSISTANT:
                if (!m.hasToolCalls()) {
                    return len(m.getContent());
                }
                int s = len(m.getContent());
                for (ToolCall tc : m.getToolCalls()) {
                    if (tc == null) {
                        continue;
                    }
                    s += len(tc.getId()) + len(tc.getFunctionName()) + len(tc.getArguments()) + 80;
                }
                return s;
            case TOOL:
                return len(m.getToolCallId()) + len(m.getContent()) + 40;
            default:
                return 0;
        }
    }

    private static int len(String s) {
        return s != null ? s.length() : 0;
    }

    private static Logger infoEnabledLogger() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "ContextBudgetPlannerTestLogger";
                }
            }
            if ("isInfoEnabled".equals(method.getName())) {
                return true;
            }
            if (method.getReturnType() == void.class) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType().isPrimitive()) {
                return 0;
            }
            return null;
        };
        @SuppressWarnings("unchecked")
        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                handler);
        return log;
    }

    @Test
    void effectiveCap_usesMinOfScaledProviderLimitAndConfiguredCap_forKnownOpenAiModel() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("x".repeat(100)));
        messages.add(ChatMessage.user("hello"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertEquals(448_000, m.effectiveRequestCapChars);
        assertEquals(100, m.stableChars);
        assertEquals(5, m.currentUserChars);
    }

    @Test
    void effectiveCap_equalsConfigured_whenProviderLimitUnknown() {
        List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v5", "custom-unknown");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertEquals(750_000, m.effectiveRequestCapChars);
    }

    @Test
    void effectiveCap_usesProviderSingleRequestCapBelowConfiguredAndModelCaps() {
        List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v5", "custom-unknown");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(
                messages, Collections.emptyList(), ids, 750_000, 120_000L);
        assertEquals(750_000, m.configuredCapChars);
        assertEquals(120_000, m.effectiveRequestCapChars);
    }

    @Test
    void negativeHistoryBudget_setsHistoryClampedToOne() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("z".repeat(2500)));
        messages.add(ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 1000);
        assertTrue(m.historyBudgetChars < 0);
        assertEquals(1, m.historyClampedToZero);
    }

    @Test
    void ephemeralChars_sumsIndexedRowsAndApiRoundInjectors() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("STABLE🙂"));
        String cat = "C".repeat(12);
        String slash = "S".repeat(8);
        String task = ParlerSuffixFraming.SERVER_OBSERVATIONS + "\n" + TaskStateLlmInjector.SECTION_HEADER + "\nx";
        String utc = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: fixed-instant";
        messages.add(ChatMessage.system(cat));
        messages.add(ChatMessage.system(slash));
        messages.add(ChatMessage.user("u"));
        messages.add(ChatMessage.system(task));
        messages.add(ChatMessage.system(utc));
        AgentToolContext.setParlerEphemeralSystemIndices(new ParlerEphemeralSystemIndices(1, 2, -1, -1, -1, -1, -1));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        int expected = cat.length() + slash.length() + task.length() + utc.length();
        assertEquals(expected, m.ephemeralChars);
    }

    @Test
    void ephemeralChars_withNullBundle_sumsAllSubsequentSystemRows() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("lead"));
        String extra = "extra-system-body";
        messages.add(ChatMessage.system(extra));
        messages.add(ChatMessage.user("u"));
        String utc = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: z";
        messages.add(ChatMessage.system(utc));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertEquals(extra.length() + utc.length(), m.ephemeralChars);
    }

    @Test
    void framedEphemeralClassifier_coversAllFourClassesForAccountingAndActiveBatchScan() {
        ChatMessage assistant = ChatMessage.assistantWithToolCalls(
                Collections.singletonList(new ToolCall("c1", "read", "{}")));
        ChatMessage result = ChatMessage.toolResult("c1", "active-result");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        messages.add(ChatMessage.user("current"));
        messages.add(assistant);
        messages.add(result);
        List<String> framed = List.of(
                ParlerSuffixFraming.SKILL_INSTRUCTIONS + "\nskill",
                ParlerSuffixFraming.SERVER_OBSERVATIONS + "\nobservation",
                ParlerSuffixFraming.SERVER_INSTRUCTION + "\ninstruction",
                ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: fixed");
        for (String row : framed) {
            messages.add(ChatMessage.system(row));
        }
        AgentToolContext.setParlerEphemeralSystemIndices(ParlerEphemeralSystemIndices.NONE);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "P", "T", "openai-chat-completions-v4", "gpt-4o");

        ContextBudgetPlanner.Metrics metrics = ContextBudgetPlanner.Metrics.compute(
                messages, Collections.emptyList(), ids, 750_000);

        assertEquals(framed.stream().mapToInt(String::length).sum(), metrics.ephemeralChars);
        assertEquals(rowCharsForTest(assistant) + rowCharsForTest(result), metrics.activeBatchReserveChars,
                "all framed suffix rows must be skipped while locating the active tool batch");
    }

    @Test
    void metricsCompute_emptyMessages_reflectsConfiguredAndEffectiveCaps() {
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(
                Collections.emptyList(), Collections.emptyList(), ids, 500_000);
        assertEquals(500_000, m.configuredCapChars);
        assertEquals(448_000, m.effectiveRequestCapChars);
        assertEquals(448_000, m.historyBudgetChars);
        assertEquals(0, m.historyClampedToZero);
    }

    @Test
    void bucketFixture_exactCounts_forMultiRoundToolTurn() {
        String utcFixed = ParlerSuffixFraming.TIME_CONTEXT + "\n- now_utc: fixed";
        ToolCall tc = new ToolCall("call_old", "invoke_service", "{}");
        ToolCall tc2 = new ToolCall("call_new", "invoke_service", "{}");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("first"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(tc)));
        messages.add(ChatMessage.toolResult("call_old", "{\"rows\":[]}"));
        messages.add(ChatMessage.assistant("done old"));
        messages.add(ChatMessage.user("second"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(tc2)));
        messages.add(ChatMessage.toolResult("call_new", "{\"rows\":[]}"));
        messages.add(ChatMessage.system(utcFixed));

        ChatMessage asstOld = messages.get(2);
        ChatMessage toolOld = messages.get(3);
        ChatMessage asstNew = messages.get(6);
        ChatMessage toolNew = messages.get(7);

        int expectHistoricEvidence = rowCharsForTest(asstOld) + rowCharsForTest(toolOld);
        int expectActive = rowCharsForTest(asstNew) + rowCharsForTest(toolNew);

        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertEquals(3, m.stableChars);
        assertEquals(6, m.currentUserChars);
        assertEquals("first".length() + "done old".length(), m.transcriptChars);
        assertEquals(expectActive, m.activeBatchReserveChars);
        assertEquals(expectHistoricEvidence, m.evidenceRawChars);
        assertEquals(expectHistoricEvidence, m.evidenceChars);
        assertEquals(utcFixed.length(), m.ephemeralChars);
    }

    @Test
    void planAndLog_doesNotMutateInputMessagesOrChatMessageInstances() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("call_1", "f", "{}"))));
        messages.add(ChatMessage.toolResult("call_1", "{}"));
        messages.add(ChatMessage.user("hi"));
        int sizeBefore = messages.size();
        ChatMessage[] refs = messages.toArray(new ChatMessage[0]);
        String[] contents = new String[refs.length];
        for (int i = 0; i < refs.length; i++) {
            contents[i] = refs[i].getContent();
        }

        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.planAndLog(infoEnabledLogger(), messages, Collections.emptyList(), ids, 750_000);

        assertEquals(sizeBefore, messages.size());
        for (int i = 0; i < refs.length; i++) {
            assertSame(refs[i], messages.get(i), "ChatMessage instance at " + i);
            assertEquals(contents[i], messages.get(i).getContent(), "content at " + i);
        }
    }

    @Test
    void metricsCompute_preservesLeadingStableSystemContent() {
        String stable = "αβγ📎";
        List<ChatMessage> messages = List.of(ChatMessage.system(stable), ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertEquals(stable.length(), m.stableChars);
        assertEquals(stable, messages.get(0).getContent());
    }

    @Test
    void activeBatchReserve_excludesHistoricToolEvidence() {
        ToolCall tc = new ToolCall("call_old", "invoke_service", "{}");
        ToolCall tc2 = new ToolCall("call_new", "invoke_service", "{}");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("first"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(tc)));
        messages.add(ChatMessage.toolResult("call_old", "{\"rows\":[]}"));
        messages.add(ChatMessage.assistant("done old"));
        messages.add(ChatMessage.user("second"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(tc2)));
        messages.add(ChatMessage.toolResult("call_new", "{\"rows\":[]}"));
        messages.add(ChatMessage.system(LlmUtcClockInjector.buildTimeBlockContent(null)));

        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, Collections.emptyList(), ids, 750_000);
        assertTrue(m.activeBatchReserveChars > 0);
        assertTrue(m.evidenceRawChars > 0, "historic assistant/tool batch should contribute");
    }

    @Test
    void toolSchemaChars_nonZero_whenToolsPresent_openAiShape() {
        List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        List<ToolDefinition> tools = Collections.singletonList(
                new ToolDefinition("invoke_service", "desc", Collections.singletonMap("type", "object")));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "azure-openai-chat-completions-v5", "gpt-4o");
        ContextBudgetPlanner.Metrics m = ContextBudgetPlanner.Metrics.compute(messages, tools, ids, 750_000);
        assertTrue(m.toolSchemaChars > 20);
    }

    @Test
    void toolSchemaChars_usesAnthropicWireShape_whenAnthropicApi() {
        List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        List<ToolDefinition> tools = Collections.singletonList(
                new ToolDefinition("invoke_service", "desc", Collections.singletonMap("type", "object")));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "anthropic-messages-v1", "claude-sonnet-4-20250514");
        ContextBudgetPlanner.Metrics anth = ContextBudgetPlanner.Metrics.compute(messages, tools, ids, 750_000);
        LlmUsageWireIds openIds = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.Metrics open = ContextBudgetPlanner.Metrics.compute(messages, tools, openIds, 750_000);
        assertTrue(anth.toolSchemaChars != open.toolSchemaChars);
    }

    @Test
    void planAndLog_unsafeDiagnostics_emitsWarnEachCall() {
        LlmReplayCompactionGate.setUnsafeDiagnosticsDisableForcedForTest(true);
        try {
            AtomicInteger warns = new AtomicInteger(0);
            Logger log = countingPlannerLogger(true, true, warns);
            List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
            LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
            ContextBudgetPlanner.planAndLog(log, messages, Collections.emptyList(), ids, 750_000);
            assertEquals(1, warns.get());
            ContextBudgetPlanner.planAndLog(log, messages, Collections.emptyList(), ids, 750_000);
            assertEquals(2, warns.get());
        } finally {
            LlmReplayCompactionGate.clearAllTestHooks();
        }
    }

    @Test
    void planAndLog_unsafeDiagnostics_emitsWarnWhenInfoDisabled() {
        LlmReplayCompactionGate.setUnsafeDiagnosticsDisableForcedForTest(true);
        try {
            AtomicInteger warns = new AtomicInteger(0);
            Logger log = countingPlannerLogger(false, true, warns);
            List<ChatMessage> messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
            LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
            ContextBudgetPlanner.planAndLog(log, messages, Collections.emptyList(), ids, 750_000);
            assertEquals(1, warns.get());
        } finally {
            LlmReplayCompactionGate.clearAllTestHooks();
        }
    }

    private static Logger countingPlannerLogger(boolean infoEnabled, boolean warnEnabled, AtomicInteger warnCount) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "ContextBudgetPlannerCountingLogger";
                }
            }
            if ("isInfoEnabled".equals(method.getName())) {
                return infoEnabled;
            }
            if ("isWarnEnabled".equals(method.getName())) {
                return warnEnabled;
            }
            if ("warn".equals(method.getName())) {
                warnCount.incrementAndGet();
                return null;
            }
            if (method.getReturnType() == void.class) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType().isPrimitive()) {
                return 0;
            }
            return null;
        };
        @SuppressWarnings("unchecked")
        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                handler);
        return log;
    }

    @Test
    void sliceC_throwsWhenOverheadExceedsEffectiveCap() {
        String big = "x".repeat(5000);
        List<ChatMessage> messages = List.of(ChatMessage.system(big), ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetExceededException ex = assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(null, messages, Collections.emptyList(), ids, 800, -1,
                        -1));
        assertEquals(ContextBudgetExceededException.Reason.OVERHEAD_EXCEEDS_CAP, ex.getReason());
    }

    @Test
    void sliceC_logsLlmContextPlanFailBeforeOverheadThrow() {
        String big = "x".repeat(5000);
        List<ChatMessage> messages = List.of(ChatMessage.system(big), ChatMessage.user("u"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        AtomicReference<String> errLine = new AtomicReference<>();
        Logger log = errorLineCapturingLogger(errLine);
        assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(log, messages, Collections.emptyList(), ids, 800, -1,
                        -1));
        String line = errLine.get();
        assertTrue(line != null && line.contains("LLM_CONTEXT_PLAN_FAIL"), line);
        assertTrue(line.contains("reason=OVERHEAD_EXCEEDS_CAP"), line);
    }

    private static Logger errorLineCapturingLogger(AtomicReference<String> sink) {
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("toString".equals(method.getName())) {
                    return "errorLineCapturingLogger";
                }
            }
            if ("isErrorEnabled".equals(method.getName())) {
                return true;
            }
            if ("error".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof String) {
                sink.set((String) args[0]);
                return null;
            }
            if (method.getReturnType() == void.class) {
                return null;
            }
            if (method.getReturnType() == boolean.class) {
                return false;
            }
            if (method.getReturnType().isPrimitive()) {
                return 0;
            }
            return null;
        };
        @SuppressWarnings("unchecked")
        Logger log = (Logger) Proxy.newProxyInstance(
                Logger.class.getClassLoader(),
                new Class<?>[] { Logger.class },
                handler);
        return log;
    }

    @Test
    void sliceC_dropsOldestHistoricEvidenceBeforeTranscriptWhenBudgetTight() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("olduser"));
        messages.add(ChatMessage.assistant("oldreply"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c1", "z".repeat(45_000)));
        messages.add(ChatMessage.user("currentuser"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1);
        assertEquals(4, p.getOutboundMessages().size(), "expect historic assistant+tool batch removed (4 rows left)");
        assertEquals(1, p.getPlannedMetrics().droppedEvidence, "droppedEvidence counts TOOL rows per §13");
        assertTrue(p.getPlannedMetrics().droppedAssistantBatches >= 1);
        assertOutboundAssistantToolBatchesContiguous(p.getOutboundMessages());
        assertTrue(p.getOutboundMessages().contains(messages.get(5)));
        assertEquals(6, messages.size());
    }

    @Test
    void sliceC_usesProviderCapToDropHistoricEvidenceEvenWhenConfiguredCapIsLarge() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c1", "z".repeat(45_000)));
        messages.add(ChatMessage.user("currentuser"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v5", "custom-unknown");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 750_000, 3_500L, -1, -1);
        assertEquals(2, p.getOutboundMessages().size(), "expect historic assistant+tool batch removed");
        assertEquals(750_000, p.getPlannedMetrics().configuredCapChars);
        assertEquals(3_500, p.getPlannedMetrics().effectiveRequestCapChars);
        assertEquals(1, p.getPlannedMetrics().droppedEvidence);
        assertTrue(p.getOutboundMessages().contains(messages.get(3)));
    }

    @Test
    void sliceC_preservesIndexedEphemeralCatalog_underTightBudget() {
        String cat = "CATALOG_EPHEMERAL";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.system(cat));
        messages.add(ChatMessage.user("old1"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c0", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c0", "z".repeat(45_000)));
        messages.add(ChatMessage.user("mid"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c1", "{}"));
        messages.add(ChatMessage.user("current"));
        AgentToolContext.setParlerEphemeralSystemIndices(new ParlerEphemeralSystemIndices(1, -1, -1, -1, -1, -1, -1));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1);
        assertTrue(p.getOutboundMessages().contains(messages.get(1)), "indexed ephemeral must survive trim");
    }

    @Test
    void sliceC_outboundAssistantToolCalls_followedByMatchingToolRows() {
        ToolCall t0 = new ToolCall("b0", "invoke_service", "{}");
        ToolCall t1 = new ToolCall("b1", "invoke_service", "{}");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("s"));
        messages.add(ChatMessage.user("h0"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c0", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c0", "old"));
        messages.add(ChatMessage.user("h1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(t0, t1)));
        messages.add(ChatMessage.toolResult("b0", "r0"));
        messages.add(ChatMessage.toolResult("b1", "r1"));
        messages.add(ChatMessage.user("current"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 5000, -1, -1);
        assertOutboundAssistantToolBatchesContiguous(p.getOutboundMessages());
    }

    @Test
    void sliceC_cannotFitAfterTrim_logsCumulativeDropCounters() {
        ToolCall t1 = new ToolCall("t1", "invoke_service", "{}");
        ToolCall t2 = new ToolCall("t2", "invoke_service", "{}");
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("u0"));
        messages.add(ChatMessage.assistant("a0"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(new ToolCall("c0", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c0", "{}"));
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(t1, t2)));
        messages.add(ChatMessage.toolResult("t1", "{}"));
        messages.add(ChatMessage.toolResult("t2", "z".repeat(12_000)));
        messages.add(ChatMessage.user("current"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        AtomicReference<String> err = new AtomicReference<>();
        Logger log = errorLineCapturingLogger(err);
        ContextBudgetExceededException ex = assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(log, messages, Collections.emptyList(), ids, 2200, -1,
                        7));
        assertEquals(ContextBudgetExceededException.Reason.CANNOT_FIT_AFTER_TRIM, ex.getReason());
        String line = err.get();
        assertTrue(line != null && line.contains("LLM_CONTEXT_PLAN_FAIL"), line);
        assertTrue(line.contains("reason=CANNOT_FIT_AFTER_TRIM"), line);
        assertTrue(line.contains("droppedAssistantBatches=1"), "expected one fully dropped historic batch before fail: "
                + line);
        assertTrue(line.contains("droppedTranscript=2"), "expected one dropped USER+ASSISTANT transcript pair: "
                + line);
        assertTrue(line.contains("droppedEvidence=1"), "expected one dropped TOOL row from dropped batch: " + line);
    }

    @Test
    void toolBackedTurn_dropsLogicallyAdjacentTranscriptPair_afterEvidenceBatchRemoved() {
        // Common historical turn shape: USER / ASSISTANT(tool_calls) / TOOL / ASSISTANT(final prose).
        // After the evidence batch is marked dropped, the USER and its final prose ASSISTANT are
        // logically adjacent in the outbound view but not physically adjacent in the source array.
        // The pair scan must still find and drop them instead of failing CANNOT_FIT_AFTER_TRIM.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("u".repeat(2_000)));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c0", "invoke_service", "{}"))));
        messages.add(ChatMessage.toolResult("c0", "z".repeat(45_000)));
        messages.add(ChatMessage.assistant("a".repeat(2_000)));
        messages.add(ChatMessage.user("currentuser"));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1);
        assertEquals(2, p.getOutboundMessages().size(),
                "expect evidence batch plus logically adjacent USER+prose-ASSISTANT pair removed");
        assertSame(messages.get(0), p.getOutboundMessages().get(0));
        assertSame(messages.get(5), p.getOutboundMessages().get(1));
        assertEquals(2, p.getPlannedMetrics().droppedTranscript,
                "USER plus prose ASSISTANT of the tool-backed turn count as dropped transcript rows");
        assertEquals(1, p.getPlannedMetrics().droppedEvidence);
        assertEquals(1, p.getPlannedMetrics().droppedAssistantBatches);
        assertOutboundAssistantToolBatchesContiguous(p.getOutboundMessages());
        assertEquals(6, messages.size(), "planning must not mutate the live message list");
    }

    @Test
    void transcriptPairScan_doesNotJumpOverKeptRows() {
        // A KEPT row between a historic USER and its prose ASSISTANT (here an indexed ephemeral
        // SYSTEM row) still blocks the pair: logical adjacency extends only across DROPPED rows.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("u".repeat(2_000)));
        messages.add(ChatMessage.system("CATALOG_EPHEMERAL"));
        messages.add(ChatMessage.assistant("a".repeat(2_000)));
        messages.add(ChatMessage.user("currentuser"));
        AgentToolContext.setParlerEphemeralSystemIndices(new ParlerEphemeralSystemIndices(2, -1, -1, -1, -1, -1, -1));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetExceededException ex = assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(
                        infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1));
        assertEquals(ContextBudgetExceededException.Reason.CANNOT_FIT_AFTER_TRIM, ex.getReason());
    }

    @Test
    void coverageGuidanceRound_isRoundEphemeral_protectsActiveEvidenceFromBeingDroppedAsHistory() {
        // C1 saturation finalize round: the round ends with
        // the active assistant/tool batch (the surfaced evidence the coverage instruction must cite) plus a
        // trailing DocumentCoverageSummaryInjector SYSTEM row. The planner must recognize that row as
        // round-ephemeral so findActiveAssistantToolBatchRange still locates and protects the active batch.
        // Proof: with the active batch protected as mandatory current-round content, an oversized evidence
        // body makes the planner fail loudly instead of silently dropping the very evidence C1 cites.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("currentuser"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "get_document_chunk", "{}"))));
        messages.add(ChatMessage.toolResult("c1", "z".repeat(45_000)));   // active surfaced evidence
        messages.add(ChatMessage.system(DocumentCoverageSummaryInjector.buildSystemContent())); // trailing ephemeral
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        assertThrows(ContextBudgetExceededException.class,
                () -> ContextBudgetPlanner.planForProviderRound(
                        infoEnabledLogger(), messages, Collections.emptyList(), ids, 2000, -1, -1),
                "protected active evidence that cannot fit must fail loudly, not be dropped as history");
    }

    @Test
    void coverageGuidanceRound_retainsCoverageRowAndActiveEvidence_dropsOlderHistory() {
        // Same finalize shape, comfortable budget: the coverage row is retained (round-ephemeral) and the
        // active evidence batch survives while an older historic batch is trimmed.
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));                  // 0
        messages.add(ChatMessage.user("olduser"));                       // 1
        messages.add(ChatMessage.assistant("oldreply"));                 // 2
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c0", "get_document_chunk", "{}"))));       // 3
        messages.add(ChatMessage.toolResult("c0", "z".repeat(45_000)));  // 4 old droppable evidence
        messages.add(ChatMessage.user("currentuser"));                  // 5
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "get_document_chunk", "{}"))));       // 6 active batch
        messages.add(ChatMessage.toolResult("c1", "section-0080 surfaced evidence")); // 7 must survive
        messages.add(ChatMessage.system(DocumentCoverageSummaryInjector.buildSystemContent())); // 8 ephemeral
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "openai-chat-completions-v4", "gpt-4o");
        ContextBudgetPlanner.PlannedOutbound p = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1);
        assertTrue(p.getOutboundMessages().contains(messages.get(7)),
                "active surfaced-evidence tool row must survive");
        assertTrue(p.getOutboundMessages().contains(messages.get(8)),
                "coverage guidance row must be retained as round-ephemeral");
        assertTrue(p.getPlannedMetrics().droppedEvidence >= 1, "old historic evidence should be dropped");
        assertOutboundAssistantToolBatchesContiguous(p.getOutboundMessages());
    }

    @Test
    void emptyFinalRetryGuidance_isFramedEphemeralAndRetainsActiveEvidence() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable-lead"));
        messages.add(ChatMessage.user("currentuser"));
        messages.add(ChatMessage.assistantWithToolCalls(Collections.singletonList(
                new ToolCall("c1", "inspect_asset", "{}"))));
        messages.add(ChatMessage.toolResult("c1", "bounded evidence"));
        messages.add(ChatMessage.system(EmptyFinalAnswerRetryInjector.buildSystemContent()));
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing(
                "P", "T", "anthropic-messages-v1", "claude-test");

        ContextBudgetPlanner.PlannedOutbound planned = ContextBudgetPlanner.planForProviderRound(
                infoEnabledLogger(), messages, Collections.emptyList(), ids, 3500, -1, -1);

        assertTrue(planned.getOutboundMessages().contains(messages.get(3)));
        assertTrue(planned.getOutboundMessages().contains(messages.get(4)));
    }

    private static void assertOutboundAssistantToolBatchesContiguous(List<ChatMessage> out) {
        for (int i = 0; i < out.size(); i++) {
            ChatMessage m = out.get(i);
            if (m.getRole() != ChatMessage.Role.ASSISTANT || !m.hasToolCalls()) {
                continue;
            }
            int ntc = m.getToolCalls().size();
            for (int j = 1; j <= ntc; j++) {
                int k = i + j;
                assertTrue(k < out.size(), "tool row expected after assistant tool_calls");
                assertEquals(ChatMessage.Role.TOOL, out.get(k).getRole(), "index " + k);
            }
        }
    }

}
