package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.support.RecordingLlmClient;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.ResultFixtures;

/** P38 (topic round 6, R24): settings, precedence and the budget captured once per call. */
class ToolResultBudgetTest {

    @AfterEach
    void tearDown() {
        LlmCallRecorder.resetForTest();
        AgentToolContext.clear();
    }

    @Test
    void clamps_and_warnings() {
        ToolResultSettings low = ToolResultSettings.fromRaw("string", 4_000, 100);
        assertEquals(8_192, low.softCapChars(), "below the minimum resets to the default");
        assertEquals(2_000, low.textCapChars());
        assertEquals(2, low.warnings().size());
        ToolResultSettings high = ToolResultSettings.fromRaw("string", 70_000, 60_000);
        assertEquals(65_536, high.softCapChars(), "above the maximum clamps");
        assertEquals(50_000, high.textCapChars());
        ToolResultSettings textOverSoft = ToolResultSettings.fromRaw("string", 8_192, 9_000);
        assertEquals(8_192 - 1_024, textOverSoft.textCapChars(), "text cap at most soft cap minus 1,024");
        assertTrue(textOverSoft.warnings().stream()
                .allMatch(w -> ToolResultSettings.CONFIG_VALUE_CLAMPED.equals(w.code())));
        ToolResultSettings unset = ToolResultSettings.fromRaw(null, null, null);
        assertEquals(ResultBudget.SOURCE_DEFAULT, unset.budgetFor(null).source());
        assertEquals(ResultBudget.SOURCE_SETTING, ToolResultSettings.fromRaw(null, 16_384, null).budgetFor(null).source());
    }

    @Test
    void a_tool_text_cap_applies_inside_the_installation_soft_cap_only() {
        ToolResultSettings s = ToolResultSettings.fromRaw("string", 16_384, 3_000);
        ResultBudget tool = s.budgetFor(4_000);
        assertEquals(16_384, tool.softCapChars(), "the soft cap is never raised for a tool");
        assertEquals(4_000, tool.textCapChars());
        assertEquals(ResultBudget.SOURCE_TOOL, tool.source());
        assertEquals(16_384 - 1_024, s.budgetFor(50_000).textCapChars());
        assertEquals(3_000, s.budgetFor(null).textCapChars(), "other tools keep the installation's text cap");
    }

    @Test
    void the_page_estimate_warning() {
        ToolResultSettings s = ToolResultSettings.fromRaw("string", 8_192, null);
        assertEquals(ToolResultSettings.CONFIG_PAGE_EXCEEDS_RESULT_CAP, s.pageWarnings(500_000).get(0).code());
        assertTrue(s.pageWarnings(6_000).isEmpty(), "6,000 + 1,500 fits the estimate");
        assertEquals(8_192, s.softCapChars(), "the soft cap is not raised for a page");
    }

    @Test
    void a_counting_resolver_is_asked_once_per_call_and_a_later_switch_does_not_affect_the_call() throws Exception {
        AtomicInteger asks = new AtomicInteger();
        AtomicReference<ResultBudget> current = new AtomicReference<>(new ResultBudget(8_192, 2_000, "setting"));
        List<ResultBudget> returned = new ArrayList<>();
        ResultBudgetResolver resolver = toolName -> {
            asks.incrementAndGet();
            ResultBudget b = current.get();
            returned.add(b);
            return b;
        };
        String raw = ResultFixtures.wideTable();
        assertTrue(raw.length() > 8_192 && raw.length() < 16_384, "precondition: between the two caps");
        List<ResultBudget> seenByExecutor = new ArrayList<>();
        RecordingLlmClient llm = new RecordingLlmClient();
        llm.enqueue(RecordingLlmClient.toolCallsResponse(List.of(new ToolCall("b-1", ResultFixtures.TOOL, "{}"),
                new ToolCall("b-2", ResultFixtures.TOOL, "{}"))));
        llm.enqueue(RecordingLlmClient.textResponse("done"));
        AgentLoop loop = new AgentLoop(llm, tc -> {
            seenByExecutor.add(AgentToolContext.resultBudgetFor(tc.getId(), tc.getFunctionName()));
            // The setting changes after the call started executing.
            current.set(new ResultBudget(16_384, 3_000, "setting"));
            return raw;
        }, null, 0, 8192, 8, 60_000L, null, 750_000, null, null, resolver);
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("evidence"));
        List<String> tools = new ArrayList<>();
        // Observes the persist row, which starts identical to the first model append (the next request may already
        // carry the replay compactor's form of a table).
        loop.run(msgs, List.of(new ToolDefinition(ResultFixtures.TOOL, "d", java.util.Collections.emptyMap())),
                (m, tu) -> {
                    if (m.getRole() == ChatMessage.Role.TOOL) {
                        tools.add(m.getContent());
                    }
                });

        assertEquals(2, asks.get(), "once per call");
        assertSame(returned.get(0), seenByExecutor.get(0), "the formatter's budget is the one resolved at start");
        assertTrue(tools.get(0).length() <= 8_192, "call 1 used its first budget (8,192): reduced");
        assertEquals(raw, tools.get(1), "call 2 started after the switch: whole at 16,384");
        assertFalse(tools.get(0).equals(raw));
        assertTrue(AgentToolContext.peekResultBudget("b-1") == null && AgentToolContext.peekResultBudget("b-2") == null,
                "removed after the first append");
    }

    @Test
    void a_call_with_no_stored_budget_asks_the_resolver_once_and_defaults_without_one() {
        AtomicInteger asks = new AtomicInteger();
        AgentToolContext.setResultBudgetResolver(t -> {
            asks.incrementAndGet();
            return new ResultBudget(12_000, 2_000, "setting");
        });
        assertEquals(12_000, AgentToolContext.resultBudgetFor("none", ResultFixtures.TOOL).softCapChars());
        assertEquals(1, asks.get());
        AgentToolContext.setResultBudgetResolver(null);
        assertSame(ResultBudget.defaults(), AgentToolContext.resultBudgetFor("none", ResultFixtures.TOOL));
    }
}
