package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.AnthropicMessagesApi;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.ParlerSuffixFraming;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.tools.HitlInterruptedBatchSiblingResult;
import com.thingworx.things.agent.tools.PendingApprovalRecord;

class HitlSyntheticToolResultAppenderTest {

    @Test
    void appendDurable_appends_tool_row_to_messages() {
        List<ChatMessage> msgs = new ArrayList<>();
        msgs.add(ChatMessage.user("u"));
        HitlSyntheticToolResultAppender.appendDurable(msgs, "call-gated", "{\"status\":\"ok\"}", "", "",
                "AgentThing");
        assertEquals(2, msgs.size());
        ChatMessage tr = msgs.get(1);
        assertEquals(ChatMessage.Role.TOOL, tr.getRole());
        assertEquals("call-gated", tr.getToolCallId());
        assertEquals("{\"status\":\"ok\"}", tr.getContent());
    }

    @Test
    void appendInterruptedSiblings_appends_one_row_per_sibling() {
        ToolCall gated = new ToolCall("g0", "invoke_service", "{}");
        ToolCall sib = new ToolCall("s1", "tabulate_cached_result", "{}");
        List<ChatMessage> snap = new ArrayList<>();
        snap.add(ChatMessage.user("goal"));
        snap.add(ChatMessage.assistantWithToolCalls(List.of(gated, sib)));
        PendingApprovalRecord rec = new PendingApprovalRecord(
                "pid-h",
                "rid-h",
                "conv-h",
                "p",
                "Agent",
                "remote",
                gated,
                snap,
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000).withInterruptedBatchSiblings(List.of(sib));
        List<ChatMessage> msgs = new ArrayList<>(rec.getMessagesCopy());
        HitlSyntheticToolResultAppender.appendInterruptedSiblingsDurable(rec, msgs, "", "", "Agent");
        assertEquals(3, msgs.size());
        ChatMessage tr = msgs.get(2);
        assertEquals(ChatMessage.Role.TOOL, tr.getRole());
        assertEquals("s1", tr.getToolCallId());
        assertEquals(HitlInterruptedBatchSiblingResult.JSON, tr.getContent());
        assertEquals("tabulate_cached_result", tr.getExecutedToolName());
    }

    @Test
    void appendDurable_withExecutedToolName_setsCarrier() {
        List<ChatMessage> msgs = new ArrayList<>();
        HitlSyntheticToolResultAppender.appendDurable(msgs, "call-gated", "{\"status\":\"ok\"}", "", "",
                "AgentThing", "invoke_service");
        assertEquals(1, msgs.size());
        assertEquals("invoke_service", msgs.get(0).getExecutedToolName());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hitlContinuation_preservesSyntheticResultOrder_andMarksLastResultBeforeSuffix() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("stable"));
        messages.add(ChatMessage.user("approve work"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(
                new ToolCall("approved", "invoke_service", "{}"),
                new ToolCall("sibling", "tabulate_cached_result", "{}"))));
        HitlSyntheticToolResultAppender.appendDurable(
                messages, "approved", "{\"status\":\"ok\"}", "", "", "Agent", "invoke_service");
        HitlSyntheticToolResultAppender.appendDurable(
                messages, "sibling", "{\"status\":\"interrupted\"}", "", "", "Agent",
                "tabulate_cached_result");
        messages.add(ChatMessage.system(ParlerSuffixFraming.TIME_CONTEXT + "\nnow"));
        List<ToolDefinition> tools = List.of(
                new ToolDefinition("invoke_service", "", Collections.emptyMap()));
        LlmChatRequest request = LlmChatRequest.copyWithCacheControl(
                LlmChatRequest.forAgentRound(messages, tools, 0.0, 256, null), true);

        Map<String, Object> payload = AnthropicMessagesApi.buildRequestPayload(
                messages, tools, "claude", 0.0, 256, 0, request);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) payload.get("messages");
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) rows.get(2).get("content");
        assertEquals("approved", blocks.get(0).get("tool_use_id"));
        assertEquals("sibling", blocks.get(1).get("tool_use_id"));
        assertEquals("ephemeral", ((Map<String, Object>) blocks.get(1).get("cache_control")).get("type"));
        assertEquals("text", blocks.get(2).get("type"));
    }
}
