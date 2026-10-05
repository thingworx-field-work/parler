package com.thingworx.things.agent.compaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.PendingApprovalRecord;
import com.thingworx.things.agent.tools.PendingApprovalStore;

class ConversationsStorageBudgetTrimmerTest {

    @AfterEach
    void clearPending() {
        PendingApprovalStore.remove("pid-storage-gate");
    }

    @Test
    void storageTrimInfoLine_containsStableFieldNames() {
        String line = ConversationsStorageBudgetTrimmer.storageTrimInfoLine(1, 2, 3, 100, 80, 50);
        assertTrue(line.contains("droppedAssistantBatches=1"));
        assertTrue(line.contains("droppedToolResultRows=2"));
        assertTrue(line.contains("droppedTranscriptRows=3"));
        assertTrue(line.contains("charsBefore=100"));
        assertTrue(line.contains("charsAfter=80"));
        assertTrue(line.contains("cap=50"));
    }

    @Test
    void exhaustedNothingDroppable_stillOverCap_noThrow() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("x".repeat(50_000)));
        messages.add(ChatMessage.user("y".repeat(50_000)));
        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 10_000, true, null, null);
        assertEquals(0, r.droppedAssistantBatches);
        assertEquals(0, r.droppedToolResultRows);
        assertEquals(0, r.droppedTranscriptRows);
        assertEquals(r.charsBefore, r.charsAfter);
        assertEquals(2, messages.size());
        assertTrue(r.charsAfter > 10_000);
    }

    @Test
    void dropsHistoricBatch_butStillOverCap_noThrow() {
        String big = "z".repeat(100_000);
        String matrix = "{\"status\":\"ok\",\"body\":\"" + big + "\"}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("s".repeat(20_000)));
        messages.add(ChatMessage.user("old"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.user("last"));
        messages.add(ChatMessage.assistant("fin"));

        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 5_000, true, null, null);
        assertTrue(r.droppedAssistantBatches >= 1);
        assertTrue(r.charsAfter > 5_000);
        assertTrue(r.charsAfter < r.charsBefore);
    }

    @Test
    void underBudget_noRemoval() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("u"));
        messages.add(ChatMessage.assistant("a"));
        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 1_000_000, true, null, null);
        assertEquals(0, r.droppedAssistantBatches);
        assertEquals(3, messages.size());
    }

    @Test
    void overBudget_dropsOldestHistoricEvidenceBatch() {
        String big = "x".repeat(400_000);
        String matrix = "{\"status\":\"success\",\"body\":\"" + big + "\"}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("old"));
        messages.add(ChatMessage.assistantWithToolCalls(
                List.of(new ToolCall("t1", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t1", matrix));
        messages.add(ChatMessage.assistant("old answer"));
        messages.add(ChatMessage.user("new"));
        messages.add(ChatMessage.assistant("fin"));

        int before = ConversationsStorageBudgetTrimmer.sumMessageChars(messages);
        assertTrue(before > 100_000);
        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 50_000, true, null, null);
        assertTrue(r.anyRemoval());
        assertEquals(1, r.droppedAssistantBatches);
        assertTrue(r.charsAfter < before);
        assertEquals(5, messages.size());
        assertEquals("new", messages.get(3).getContent());
    }

    @Test
    void overBudget_thenDropsTranscriptPair() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("s"));
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistant("a1"));
        String matrix = "{\"status\":\"ok\",\"x\":\"" + "y".repeat(30_000) + "\"}";
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("tc", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("tc", matrix));
        messages.add(ChatMessage.assistant("a2"));
        messages.add(ChatMessage.user("u3"));
        messages.add(ChatMessage.assistant("fin"));

        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 20_000, true, null, null);
        assertTrue(r.droppedAssistantBatches >= 1 || r.droppedTranscriptRows >= 2);
        assertTrue(messages.size() < 9);
        assertEquals("u3", messages.get(messages.size() - 2).getContent());
    }

    @Test
    void compactionOff_skipsTrim() {
        String matrix = "{\"status\":\"ok\",\"x\":\"" + "z".repeat(100_000) + "\"}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t", matrix));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("f"));

        int n = messages.size();
        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 100, false, null, null);
        assertEquals(0, r.droppedAssistantBatches);
        assertEquals(n, messages.size());
    }

    @Test
    void activePending_skipsTrim() {
        String matrix = "{\"status\":\"ok\",\"x\":\"" + "z".repeat(100_000) + "\"}";
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.user("u1"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("t", "query_entities", "{}"))));
        messages.add(ChatMessage.toolResult("t", matrix));
        messages.add(ChatMessage.user("u2"));
        messages.add(ChatMessage.assistant("f"));

        ToolCall tc = new ToolCall("g", "invoke_service", "{}");
        PendingApprovalStore.put(new PendingApprovalRecord(
                "pid-storage-gate",
                "rid",
                "cid-p",
                "p",
                "AgentThing",
                "r",
                tc,
                Collections.emptyList(),
                null,
                null,
                null,
                System.currentTimeMillis() + 60_000));

        int n = messages.size();
        ConversationsStorageBudgetTrimmer.TrimResult r = ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget(
                messages, 100, true, null, "cid-p");
        assertEquals(0, r.droppedAssistantBatches);
        assertEquals(n, messages.size());
    }
}
