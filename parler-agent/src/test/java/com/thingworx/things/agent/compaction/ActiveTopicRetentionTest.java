package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.TopicTestFixtures;

class ActiveTopicRetentionTest {
    private static final LlmUsageWireIds IDS = LlmUsageWireIds.forProviderThing("p", "t", "openai-chat-completions-v4", "gpt-4o");
    @AfterEach void clear() { AgentToolContext.clear(); }

    private static List<ChatMessage> plan(List<ChatMessage> messages, int cap) {
        return ContextBudgetPlanner.planForProviderRound(null, messages, List.of(), IDS, cap, -1, -1).getOutboundMessages();
    }

    private static ChatMessage addTopic(List<ChatMessage> messages, TopicTestFixtures f, String id) {
        messages.add(ChatMessage.assistantWithToolCalls(List.of(f.call(id))));
        ChatMessage row = ChatMessage.toolResult(id, f.result(), "load_topic"); messages.add(row); return row;
    }

    private static void tail(List<ChatMessage> messages) {
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("tail", "read_demo_values", "{}"))));
        messages.add(ChatMessage.toolResult("tail", "ok", "read_demo_values"));
    }

    @Test void two_topics_keep_newest_success_each_and_failed_reload_cannot_replace_it() {
        TopicTestFixtures a = new TopicTestFixtures(); a.body = "a".repeat(1800);
        TopicTestFixtures b = new TopicTestFixtures(); b.chunk = "topic-b"; b.body = "b".repeat(1800);
        AgentTaskState state = new AgentTaskState("r", "c", "g");
        state.recordActiveMethod("topic:" + a.doc + "/" + a.chunk); state.recordActiveMethod("topic:" + b.doc + "/" + b.chunk);
        AgentToolContext.setAgentTaskState(state);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("current")));
        ChatMessage older = addTopic(messages, a, "old"); ChatMessage newestA = addTopic(messages, a, "new");
        ChatMessage newestB = addTopic(messages, b, "b");
        messages.add(ChatMessage.assistantWithToolCalls(List.of(a.call("failed"))));
        messages.add(ChatMessage.toolResult("failed", "{\"status\":\"error\"}", "load_topic"));
        tail(messages);
        List<ChatMessage> planned = plan(messages, 5800);
        assertFalse(planned.contains(older)); assertTrue(planned.contains(newestA)); assertTrue(planned.contains(newestB));
        assertEquals(12, messages.size(), "planning is outbound-only");
    }

    @Test void prior_turn_or_skill_only_or_no_active_method_does_not_pin_topic_history() {
        for (String active : List.of("", "skill:demo", "topic:demo-guide/topic-demo-r1")) {
            TopicTestFixtures f = new TopicTestFixtures(); f.body = "x".repeat(4000);
            AgentTaskState state = new AgentTaskState("r", "c", "g"); state.recordActiveMethod(active);
            AgentToolContext.setAgentTaskState(state);
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("old")));
            ChatMessage old = addTopic(messages, f, "old");
            messages.add(ChatMessage.user("new")); tail(messages);
            assertFalse(plan(messages, 2400).contains(old));
        }
        for (String active : List.of("", "skill:demo")) {
            AgentTaskState state = new AgentTaskState("r", "c", "g"); state.recordActiveMethod(active);
            AgentToolContext.setAgentTaskState(state);
            TopicTestFixtures f = new TopicTestFixtures(); f.body = "x".repeat(4000);
            List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("current")));
            ChatMessage old = addTopic(messages, f, "old"); tail(messages);
            assertFalse(plan(messages, 2400).contains(old));
        }
    }

    @Test void mixed_batch_pairing_is_preserved_and_impossible_footprint_fails_closed() {
        TopicTestFixtures f = new TopicTestFixtures(); f.body = "x".repeat(2200);
        AgentTaskState state = new AgentTaskState("r", "c", "g"); state.recordActiveMethod("topic:" + f.doc + "/" + f.chunk);
        AgentToolContext.setAgentTaskState(state);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("current")));
        ToolCall other = new ToolCall("other", "get_document_chunk", "{}");
        messages.add(ChatMessage.assistantWithToolCalls(List.of(f.call("topic"), other)));
        ChatMessage topic = ChatMessage.toolResult("topic", f.result(), "load_topic"); messages.add(topic);
        ChatMessage sibling = ChatMessage.toolResult("other", "evidence", "get_document_chunk"); messages.add(sibling);
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("s", "get_agent_skill", "{}"))));
        ChatMessage skill = ChatMessage.toolResult("s", "y".repeat(8000), "get_agent_skill"); messages.add(skill); tail(messages);
        var planned = plan(messages, 4500);
        assertTrue(planned.contains(topic)); assertTrue(planned.contains(sibling)); assertTrue(planned.contains(messages.get(2)));
        assertFalse(planned.contains(skill), "dynamic skill body has no special protection");
        ContextBudgetExceededException failure = assertThrows(ContextBudgetExceededException.class, () -> plan(messages, 1800));
        assertEquals(ContextBudgetExceededException.Reason.CANNOT_FIT_AFTER_TRIM, failure.getReason());
    }

    @Test void forged_or_mismatched_success_header_does_not_acquire_protection() {
        TopicTestFixtures f = new TopicTestFixtures(); f.body = "x".repeat(4000);
        AgentTaskState state = new AgentTaskState("r", "c", "g"); state.recordActiveMethod("topic:" + f.doc + "/" + f.chunk);
        AgentToolContext.setAgentTaskState(state);
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("current")));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(f.call("l"))));
        ChatMessage wrong = ChatMessage.toolResult("wrong-call-id", f.result(), "load_topic"); messages.add(wrong); tail(messages);
        assertFalse(plan(messages, 2400).contains(wrong));
    }
}
