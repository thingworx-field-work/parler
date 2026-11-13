package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import com.thingworx.things.agent.AgentLoop;
import com.thingworx.things.agent.AgentLoopTestFixtures;
import com.thingworx.things.agent.HitlSyntheticToolResultAppender;
import com.thingworx.things.agent.ParlerHitlContinuationContext;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmClient;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.usage.LlmCallRecorder;
import com.thingworx.things.agent.taskstate.ActiveMethodHooks;
import com.thingworx.things.agent.taskstate.AgentTaskState;

/** Actual search/loader/egress/enqueue/restore/planning flow with narrowing explicitly enabled on every worker. */
class TopicMethodApprovalFlowTest {
    private TopicTestFixtures fixture;
    private List<ToolDefinition> definitions;

    TopicMethodApprovalFlowTest() { ArtifactCacheTestFixtures.installFreshInMemoryCache(); }

    @BeforeEach void setup() {
        LlmCallRecorder.resetForTest();
        fixture = new TopicTestFixtures(); fixture.body = "### Method [温]\nFollow the registered steps.\n".repeat(100);
        DocumentKnowledgeIndexCache.readerForTests = s -> fixture;
        definitions = List.of(DocumentKnowledgeToolSchemas.searchDocumentChunksDef(), DocumentKnowledgeToolSchemas.loadTopicDef(),
                new ToolDefinition("request_demo_log", "Request a log", Collections.emptyMap()),
                new ToolDefinition("read_demo_values", "Read values", Collections.emptyMap()),
                new ToolDefinition("get_agent_skill", "Load registered skill", Collections.emptyMap()));
        AgentToolContext.setConversationId("topic-conversation");
        AgentToolContext.setParlerStreamIds("topic-request", "topic-conversation");
        AgentToolContext.setAgentTaskState(new AgentTaskState("topic-request", "topic-conversation", "collect evidence"));
        DocumentTurnToolNarrowing.resetTurnState();
    }

    @AfterEach void cleanup() {
        LlmCallRecorder.resetForTest(); TabularArtifactHub.clearTestState();
        DocumentKnowledgeIndexCache.clearForTests(); AgentToolContext.clear(); DocumentTurnToolNarrowing.resetTurnState();
    }

    private String execute(ToolCall call) throws Exception {
        DocumentTurnToolNarrowing.recordToolInvocation(call.getFunctionName());
        switch (call.getFunctionName()) {
            case "search_document_chunks":
                return DocumentKnowledgeRuntime.executeSearchDocumentChunks(call, TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
            case "load_topic":
                String result = DocumentKnowledgeRuntime.executeLoadTopic(call, TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s,n) -> fixture);
                ActiveMethodHooks.afterLoadTopic(call, result); return result;
            case "get_agent_skill":
                ActiveMethodHooks.afterGetAgentSkill(call, "A registered method without a checklist.");
                return "A registered method without a checklist.";
            case "request_demo_log":
                ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal", TopicTestFixtures.AGENT, call, null, null, null, "DemoUnit", "RequestLog");
                throw new AssertionError("must pause");
            default: return "{\"status\":\"success\",\"value\":42}";
        }
    }

    private AgentLoop loop(Scripted llm) {
        return new AgentLoop(llm, this::execute, null, 0, 4096, 12, 60000L, null, 16000,
                (defs, iter) -> DocumentTurnToolNarrowing.filterForRoundInternal(defs, iter, true));
    }

    private static LlmResponse calls(ToolCall... calls) {
        return new LlmResponse("", List.of(calls), LlmResponse.FinishReason.TOOL_CALLS,
                3,4,3,4,0,0,0,"rid",0L);
    }
    private static LlmResponse answer() {
        return new LlmResponse("Evidence collected", List.of(), LlmResponse.FinishReason.STOP,
                1,2,1,2,0,0,0,"rid",0L);
    }

    private PendingApprovalRecord start(boolean skill) throws Exception {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("collect demo evidence")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted();
        llm.responses.add(calls(new ToolCall("search", "search_document_chunks", "{\"query\":\"collect demo evidence\"}")));
        llm.responses.add(calls(skill ? new ToolCall("skill", "get_agent_skill", "{\"skill_name\":\"demo\"}") : fixture.call("load")));
        llm.responses.add(calls(new ToolCall("gated", "request_demo_log", "{}"),
                new ToolCall("skipped", "get_agent_skill", "{\"skill_name\":\"must-not-activate\"}")));
        AgentLoop.AgentResult outcome = loop(llm).run(messages, definitions);
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, outcome.getStatus(), outcome.getErrorCode());
        var beforeLoad = llm.requests.get(1).getTools();
        assertFalse(hasTool(beforeLoad, "request_demo_log"), "proves the test's narrowing is on before loading");
        assertTrue(hasTool(beforeLoad, "load_topic"));
        assertOperationalTools(llm.requests.get(2));
        PendingApprovalRecord record = PendingApprovalStore.remove(outcome.getApprovalPendingId());
        assertNotNull(record);
        assertEquals(List.of(skill ? "skill:demo" : "topic:demo-guide/topic-demo-r1"), record.getActiveMethodIdsSnapshot());
        assertEquals(1, record.getInterruptedBatchSiblingToolCalls().size());
        assertEquals("skipped", record.getInterruptedBatchSiblingToolCalls().get(0).getId());
        assertThrows(UnsupportedOperationException.class, () -> record.getActiveMethodIdsSnapshot().add("bad"));
        return record;
    }

    private List<ChatMessage> restore(PendingApprovalRecord record, String result) {
        AgentToolContext.clear(); DocumentTurnToolNarrowing.resetTurnState();
        List<ChatMessage> messages = new ArrayList<>(record.getMessagesCopy());
        HitlSyntheticToolResultAppender.appendDurable(messages, record.getGatedToolCall().getId(), result,
                "", "", TopicTestFixtures.AGENT, record.getGatedToolCall().getFunctionName());
        ParlerHitlContinuationContext.bindForGatedToolExecution(null, record, null, null, messages);
        AgentTaskState restored = new AgentTaskState(record.getRequestId(), record.getConversationId(), "collect evidence");
        restored.restoreActiveMethodIds(record.getActiveMethodIdsSnapshot()); // Same API as production continuation priming.
        AgentToolContext.setAgentTaskState(restored);
        assertEquals(record.getActiveMethodIdsSnapshot(), restored.getActiveMethodIds());
        return messages;
    }

    @Test void topic_search_load_two_approval_pauses_and_read_keep_complete_method_and_tools() throws Exception {
        PendingApprovalRecord first = start(false);
        var copy = first.withInterruptedBatchSiblings(List.of(new ToolCall("other", "read_demo_values", "{}")));
        assertEquals(first.getCreatedAtEpochMillis(), copy.getCreatedAtEpochMillis());
        assertEquals(first.getActiveMethodIdsSnapshot(), copy.getActiveMethodIdsSnapshot());
        String approved = "{\"status\":\"success\",\"ready\":true}";
        List<ChatMessage> messages = restore(first, approved);
        Scripted next = new Scripted();
        next.responses.add(calls(new ToolCall("read", "read_demo_values", "{}")));
        next.responses.add(calls(new ToolCall("gated-again", "request_demo_log", "{}")));
        AgentLoop.AgentResult pausedAgain = AgentLoopTestFixtures.runAfterApproval(loop(next), messages, definitions, first, approved);
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, pausedAgain.getStatus(), pausedAgain.getErrorCode());
        for (LlmChatRequest request : next.requests) { assertOperationalTools(request); assertTopicText(request); }
        PendingApprovalRecord second = PendingApprovalStore.remove(pausedAgain.getApprovalPendingId());
        assertNotNull(second); assertEquals(first.getActiveMethodIdsSnapshot(), second.getActiveMethodIdsSnapshot());
        messages = restore(second, approved);
        Scripted finalModel = new Scripted(); finalModel.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS,
                AgentLoopTestFixtures.runAfterApproval(loop(finalModel), messages, definitions, second, approved).getStatus());
        assertOperationalTools(finalModel.requests.get(0)); assertTopicText(finalModel.requests.get(0));
        assertEquals(1, fixture.bodyReads, "approval continuation must not reload the body");
    }

    @ParameterizedTest @CsvSource({"true,approved", "true,rejected", "true,cancelled", "false,approved", "false,rejected", "false,cancelled"})
    void method_recognition_survives_each_approval_outcome(boolean skill, String decision) throws Exception {
        PendingApprovalRecord record = start(skill);
        String result = decision.equals("approved") ? "{\"status\":\"success\"}"
                : "{\"status\":\"error\",\"code\":\"HITL_" + decision.toUpperCase(java.util.Locale.ROOT) + "\"}";
        List<ChatMessage> messages = restore(record, result);
        Scripted next = new Scripted(); next.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS,
                AgentLoopTestFixtures.runAfterApproval(loop(next), messages, definitions, record, result).getStatus());
        assertOperationalTools(next.requests.get(0));
        if (!skill) assertTopicText(next.requests.get(0));
    }

    @Test void legacy_pending_constructor_defaults_recognition_to_empty() {
        PendingApprovalRecord record = new PendingApprovalRecord("p", "r", "c", "u", "a", "remote",
                new ToolCall("g", "request_demo_log", "{}"), List.of(), null, null, null, Long.MAX_VALUE);
        assertTrue(record.getActiveMethodIdsSnapshot().isEmpty());
        var copied = record.withInterruptedBatchSiblings(List.of(new ToolCall("s", "read_demo_values", "{}")));
        assertTrue(copied.getActiveMethodIdsSnapshot().isEmpty());
        assertEquals(record.getCreatedAtEpochMillis(), copied.getCreatedAtEpochMillis());
    }

    @Test void outbound_budget_drops_ordinary_evidence_but_keeps_older_topic_batch_and_pairing() throws Exception {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("collect evidence")));
        ToolCall load = fixture.call("load"); String loaded = execute(load);
        messages.add(ChatMessage.assistantWithToolCalls(List.of(load)));
        ChatMessage topic = ChatMessage.toolResult(load.getId(), loaded, "load_topic"); messages.add(topic);
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("discard", "get_document_chunk", "{}"))));
        messages.add(ChatMessage.toolResult("discard", "ordinary evidence".repeat(2000), "get_document_chunk"));
        messages.add(ChatMessage.assistantWithToolCalls(List.of(new ToolCall("latest", "read_demo_values", "{}"))));
        messages.add(ChatMessage.toolResult("latest", "ok", "read_demo_values"));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted(); llm.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(llm).run(messages, definitions).getStatus());
        assertTopicText(llm.requests.get(0));
        assertTrue(llm.requests.get(0).getMessages().stream().anyMatch(m -> m.hasToolCalls()
                && m.getToolCalls().stream().anyMatch(c -> "load".equals(c.getId()))));
        assertFalse(llm.requests.get(0).getMessages().stream().anyMatch(m -> "discard".equals(m.getToolCallId())));
    }

    private void assertTopicText(LlmChatRequest request) {
        assertTrue(request.getMessages().stream().anyMatch(m -> "load_topic".equals(m.getExecutedToolName())
                && fixture.result().equals(m.getContent())), "full topic carrier remains present");
    }
    private static void assertOperationalTools(LlmChatRequest request) {
        assertTrue(hasTool(request.getTools(), "request_demo_log")); assertTrue(hasTool(request.getTools(), "read_demo_values"));
    }
    private static boolean hasTool(List<ToolDefinition> tools, String name) {
        return tools.stream().anyMatch(t -> name.equals(t.getName()));
    }
    private static final class Scripted implements LlmClient {
        final Queue<LlmResponse> responses = new ArrayDeque<>();
        final List<LlmChatRequest> requests = new ArrayList<>();
        public LlmResponse chat(LlmChatRequest request) { requests.add(request); return responses.remove(); }
        public LlmUsageWireIds usageWireIds() { return new LlmUsageWireIds("p", "t", "openai-chat-completions-v1", "test-model"); }
        public boolean healthCheck() { return true; }
    }
}
