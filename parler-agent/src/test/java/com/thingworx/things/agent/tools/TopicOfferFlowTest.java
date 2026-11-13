package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentLoop;
import com.thingworx.things.agent.AgentLoopTestFixtures;
import com.thingworx.things.agent.ParlerHitlContinuationContext;
import com.thingworx.things.agent.ToolResultEgressGateway;
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
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/** P8, P9, P11, P14 and P18: real loop, search and egress with narrowing explicitly enabled and the hooks of the built-in path. */
class TopicOfferFlowTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEARCH_ARGS = "{\"query\":\"collect demo evidence\"}";
    private static final String RESOLVED_UNIT = "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\","
            + "\"stale\":false,\"assetTypeKey\":\"\",\"totalCount\":1,"
            + "\"matches\":[{\"name\":\"Demo Unit\",\"criticalProperties\":{},\"matchedBy\":{}}]}";
    private static final String PAGES_ONLY = "{\"status\":\"success\",\"matches\":["
            + "{\"docId\":\"demo-guide\",\"chunkId\":\"page-0001\",\"contentType\":\"page\"}]}";

    private TopicTestFixtures fixture;
    private List<ToolDefinition> definitions;
    private String cannedSearchResult;
    private String resolvedUnit = RESOLVED_UNIT;
    private boolean searchesDeriveUnitScope;

    private final Function<String, Optional<ResolvedDocumentSet>> resolver = key ->
            Optional.of(DocumentSetResolver.resolveDefault(List.of(key), loadedManifests())).filter(set -> !set.isEmpty());

    TopicOfferFlowTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private java.util.Collection<DocumentKnowledgePackageManifest> loadedManifests() {
        try {
            return DocumentKnowledgeIndexCache.getOrLoad(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT,
                    org.slf4j.LoggerFactory.getLogger(TopicOfferFlowTest.class))
                    .index().allManifests();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach void setup() {
        LlmCallRecorder.resetForTest();
        fixture = new TopicTestFixtures();
        DocumentKnowledgeIndexCache.readerForTests = s -> fixture;
        definitions = List.of(DocumentKnowledgeToolSchemas.searchDocumentChunksDef(), DocumentKnowledgeToolSchemas.loadTopicDef(),
                new ToolDefinition("resolve_thing", "Resolve a unit", Collections.emptyMap()),
                new ToolDefinition("request_demo_log", "Request a log", Collections.emptyMap()));
        beginTurn();
    }

    @AfterEach void cleanup() {
        LlmCallRecorder.resetForTest();
        TabularArtifactHub.clearTestState();
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
        DocumentTurnToolNarrowing.resetTurnState();
    }

    private void beginTurn() {
        AgentToolContext.clear();
        AgentToolContext.setConversationId("offer-conversation");
        AgentToolContext.setParlerStreamIds("offer-request", "offer-conversation");
        AgentToolContext.setAgentTaskState(new AgentTaskState("offer-request", "offer-conversation", "collect evidence"));
        DocumentTurnToolNarrowing.resetTurnState();
    }

    private String execute(ToolCall call) throws Exception {
        DocumentTurnToolNarrowing.recordToolInvocation(call.getFunctionName());
        switch (call.getFunctionName()) {
            case "resolve_thing":
                ConversationEntityScope.afterResolveThing(call, resolvedUnit, resolver);
                return resolvedUnit;
            case "search_document_chunks":
                String result = "page-search".equals(call.getId()) ? PAGES_ONLY
                        : cannedSearchResult != null ? cannedSearchResult
                        : searchesDeriveUnitScope
                                ? DocumentKnowledgeRuntime.searchWithUnitScope(call, TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT,
                                        Optional::of, resolver)
                                : DocumentKnowledgeRuntime.executeSearchDocumentChunks(call, TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
                ActiveMethodHooks.afterSearchDocumentChunks(call, result);
                return result;
            case "load_topic":
                String topic = DocumentKnowledgeRuntime.executeLoadTopic(call, TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT,
                        (settings, agent) -> fixture);
                ActiveMethodHooks.afterLoadTopic(call, topic);
                return topic;
            case "request_demo_log":
                ParlerHitlStreamScopedEnqueue.enqueueOrThrow("principal", TopicTestFixtures.AGENT, call, null, null, null,
                        "DemoUnit", "RequestLog");
                throw new AssertionError("must pause");
            default:
                return "{\"status\":\"success\"}";
        }
    }

    private AgentLoop loop(Scripted llm) {
        return new AgentLoop(llm, this::execute, null, 0, 4096, 12, 60000L, null, 16000,
                (defs, iter) -> DocumentTurnToolNarrowing.filterForRoundInternal(defs, iter, true));
    }

    private static LlmResponse calls(ToolCall... calls) {
        return new LlmResponse("", List.of(calls), LlmResponse.FinishReason.TOOL_CALLS, 3, 4, 3, 4, 0, 0, 0, "rid", 0L);
    }

    private static LlmResponse answer() {
        return new LlmResponse("Done", List.of(), LlmResponse.FinishReason.STOP, 1, 2, 1, 2, 0, 0, 0, "rid", 0L);
    }

    private static ToolCall search(String id) {
        return new ToolCall(id, "search_document_chunks", SEARCH_ARGS);
    }

    private static boolean hasTool(LlmChatRequest request, String name) {
        return request.getTools().stream().anyMatch(tool -> name.equals(tool.getName()));
    }

    private Scripted runSearchTurn(String canned) throws Exception {
        cannedSearchResult = canned;
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("collect demo evidence")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted();
        llm.responses.add(calls(search("search")));
        llm.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(llm).run(messages, definitions).getStatus());
        return llm;
    }

    @Test void a_topic_match_keeps_the_operational_tool_for_the_rest_of_the_turn() throws Exception {
        Scripted llm = runSearchTurn(null);
        assertTrue(hasTool(llm.requests.get(1), "request_demo_log"));
        AgentTaskState state = AgentToolContext.getAgentTaskState();
        assertTrue(state.isMethodOffered());
        assertTrue(state.getActiveMethodIds().isEmpty(), "an offer is not an active method");
        assertTrue(state.snapshotActiveMethodIdsForPending().isEmpty());
    }

    /** P28: the model reads the runtime's own {@code topics} row, so the offer is not written by this test. */
    private static LlmResponse loadTheOfferedTopic(LlmChatRequest request) {
        try {
            String searchRow = request.getMessages().stream()
                    .filter(m -> m.getRole() == ChatMessage.Role.TOOL && "named".equals(m.getToolCallId()))
                    .findFirst().orElseThrow().getContent();
            JsonNode search = MAPPER.readTree(searchRow);
            assertEquals("documentIds-filter", search.path("selectionMode").asText());
            assertEquals("asset-context-resolver", search.path("documentScopeSource").asText());
            JsonNode offered = search.path("topics").get(0);
            assertEquals("load_topic", offered.path("loadWith").asText());
            ObjectNode args = MAPPER.createObjectNode().put("docId", offered.path("docId").asText())
                    .put("chunkId", offered.path("chunkId").asText());
            return calls(new ToolCall("load", "load_topic", args.toString()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test void a_first_search_that_names_the_unit_reaches_the_method_without_resolve_thing() throws Exception {
        String named = "{\"query\":\"collect demo evidence\",\"assetContext\":{\"thingName\":\"Demo Unit\"}}";
        assertTrue(realSearch(named).path("topics").isMissingNode(), "without the derivation this shape offers nothing");

        searchesDeriveUnitScope = true;
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"),
                ChatMessage.user("Demo Unit shows a fault. What should I collect for the ticket?")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted();
        llm.responses.add(calls(new ToolCall("named", "search_document_chunks", named)));
        llm.reactions.add(TopicOfferFlowTest::loadTheOfferedTopic);
        llm.reactions.add(request -> answer());

        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(llm).run(messages, definitions).getStatus());

        assertTrue(messages.stream().noneMatch(m -> "resolve_thing".equals(m.getExecutedToolName())));
        assertTrue(hasTool(llm.requests.get(2), "request_demo_log"), "the operational tool stays after load_topic");
        assertEquals(List.of("topic:demo-guide/topic-demo-r1"), AgentToolContext.getAgentTaskState().getActiveMethodIds());
    }

    @Test void a_page_only_result_keeps_the_narrowed_list() throws Exception {
        Scripted llm = runSearchTurn(PAGES_ONLY);
        assertFalse(hasTool(llm.requests.get(1), "request_demo_log"), "proves narrowing is on");
        assertFalse(AgentToolContext.getAgentTaskState().isMethodOffered());
    }

    @Test void an_unparsable_result_records_nothing() throws Exception {
        Scripted llm = runSearchTurn("{not json");
        assertFalse(hasTool(llm.requests.get(1), "request_demo_log"));
        assertFalse(AgentToolContext.getAgentTaskState().isMethodOffered());
    }

    @Test void a_page_only_result_after_an_offer_keeps_the_lift() throws Exception {
        cannedSearchResult = null;
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("collect demo evidence")));
        Scripted llm = new Scripted();
        llm.responses.add(calls(search("first")));
        llm.responses.add(calls(search("page-search")));
        llm.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(llm).run(messages, definitions).getStatus());
        assertTrue(hasTool(llm.requests.get(2), "request_demo_log"));
    }

    @Test void a_topic_match_counts_from_the_built_in_result_even_if_egress_sampling_removes_it() throws Exception {
        StringBuilder matches = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            matches.append("{\"docId\":\"d\",\"chunkId\":\"page-").append(i).append("\",\"contentType\":\"page\"},");
        }
        String result = "{\"status\":\"success\",\"matches\":[" + matches
                + "{\"docId\":\"d\",\"chunkId\":\"sampled-out-topic\",\"contentType\":\"topic\"}]}";
        assertFalse(ToolResultEgressGateway.compactForLlmAppend("search_document_chunks", "c", result, null)
                .getLlmContent().contains("sampled-out-topic"), "fixture precondition: sampling removes the topic");
        assertTrue(hasTool(runSearchTurn(result).requests.get(1), "request_demo_log"));
    }

    @Test void the_hook_ignores_other_tools() {
        ActiveMethodHooks.afterSearchDocumentChunks(new ToolCall("x", "get_document_chunk", "{}"),
                "{\"matches\":[{\"contentType\":\"topic\"}]}");
        ActiveMethodHooks.afterSearchDocumentChunks(null, "{\"matches\":[{\"contentType\":\"topic\"}]}");
        assertFalse(AgentToolContext.getAgentTaskState().isMethodOffered());
    }

    @Test void an_offer_is_not_carried_over_an_approval_pause_and_a_resolved_unit_is() throws Exception {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("collect demo evidence")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted();
        llm.responses.add(calls(new ToolCall("unit", "resolve_thing", "{\"text\":\"Demo Unit\"}")));
        llm.responses.add(calls(search("search")));
        llm.responses.add(calls(new ToolCall("gated", "request_demo_log", "{}")));
        AgentLoop.AgentResult paused = loop(llm).run(messages, definitions);
        assertEquals(AgentLoop.AgentResult.Status.AWAITING_APPROVAL, paused.getStatus(), paused.getErrorCode());
        assertTrue(AgentToolContext.getAgentTaskState().isMethodOffered());
        PendingApprovalRecord record = PendingApprovalStore.remove(paused.getApprovalPendingId());
        assertNotNull(record);
        assertTrue(record.getActiveMethodIdsSnapshot().isEmpty(), "an offer is not carried");

        beginTurn();
        String approved = "{\"status\":\"success\"}";
        List<ChatMessage> resumed = new ArrayList<>(record.getMessagesCopy());
        com.thingworx.things.agent.HitlSyntheticToolResultAppender.appendDurable(resumed, record.getGatedToolCall().getId(),
                approved, "", "", TopicTestFixtures.AGENT, record.getGatedToolCall().getFunctionName());
        ParlerHitlContinuationContext.bindForGatedToolExecution(null, record, null, null, resumed);
        ConversationEntityScope.injectFromHistory(resumed, resolver);

        assertEquals(List.of("demo-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals(DocumentScopeOrigin.CONVERSATION, AgentToolContext.getInjectedDocumentScopeOrigin());
        assertFalse(AgentToolContext.getAgentTaskState().isMethodOffered());

        cannedSearchResult = PAGES_ONLY;
        Scripted next = new Scripted();
        next.responses.add(calls(search("search-after")));
        next.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS,
                AgentLoopTestFixtures.runAfterApproval(loop(next), resumed, definitions, record, approved).getStatus());
        assertFalse(hasTool(next.requests.get(1), "request_demo_log"), "the pause cleared the offer");
    }

    @Test void a_continuation_does_not_restore_a_host_scope() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom");
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.user("collect demo evidence")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted llm = new Scripted();
        llm.responses.add(calls(new ToolCall("gated", "request_demo_log", "{}")));
        AgentLoop.AgentResult paused = loop(llm).run(messages, definitions);
        PendingApprovalRecord record = PendingApprovalStore.remove(paused.getApprovalPendingId());
        assertNotNull(record);

        beginTurn();
        List<ChatMessage> resumed = new ArrayList<>(record.getMessagesCopy());
        ParlerHitlContinuationContext.bindForGatedToolExecution(null, record, null, null, resumed);
        ConversationEntityScope.injectFromHistory(resumed, resolver);
        assertNull(AgentToolContext.getInjectedDocumentScopeIds());
    }

    private static List<String> fieldNames(JsonNode match) {
        List<String> names = new ArrayList<>();
        match.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private JsonNode realSearch(String args) throws Exception {
        return MAPPER.readTree(DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s", "search_document_chunks", args), TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT));
    }

    private static JsonNode match(JsonNode result, String contentType) {
        for (JsonNode match : result.path("matches")) {
            if (contentType.equals(match.path("contentType").asText())) {
                return match;
            }
        }
        throw new AssertionError("no " + contentType + " match in " + result);
    }

    @Test void load_with_appears_on_topic_matches_only_in_the_specified_position() throws Exception {
        JsonNode result = realSearch(SEARCH_ARGS);
        List<String> pageFields = List.of("docId", "chunkId", "heading", "sectionPath", "contentType", "pageStart",
                "pageEnd", "score", "bm25Boost", "snippet", "sourceLinks");
        assertEquals(pageFields, fieldNames(match(result, "page")));
        assertEquals(List.of("docId", "chunkId", "heading", "sectionPath", "contentType", "loadWith", "pageStart",
                "pageEnd", "score", "bm25Boost", "snippet", "sourceLinks"), fieldNames(match(result, "topic")));
        assertEquals("load_topic", match(result, "topic").path("loadWith").asText());
    }

    @Test void load_with_is_absent_from_a_topic_the_loader_would_refuse() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("other-doc"), "custom", DocumentScopeOrigin.CONVERSATION);
        JsonNode topic = match(realSearch("{\"query\":\"collect demo evidence\",\"documentIds\":[\"demo-guide\"]}"), "topic");
        assertFalse(topic.has("loadWith"));
        JsonNode refused = MAPPER.readTree(DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("load"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s, n) -> fixture));
        assertEquals("TOPIC_OUT_OF_SCOPE", refused.path("code").asText());
    }

    @Test void load_with_keeps_the_egress_outcome_of_a_result_with_large_pages() throws Exception {
        ObjectNode result = MAPPER.createObjectNode().put("status", "success");
        ArrayNode matches = result.putArray("matches");
        matches.addObject().put("chunkId", "topic-demo-r1").put("contentType", "topic").put("loadWith", "load_topic");
        for (int i = 0; i < 6; i++) {
            matches.addObject().put("chunkId", "page-" + i).put("contentType", "page").put("snippet", "x".repeat(3000));
        }
        ToolResultEgressGateway.EgressResult withField = ToolResultEgressGateway.compactForLlmAppend(
                "search_document_chunks", "c", result.toString(), null);
        ((ObjectNode) matches.get(0)).remove("loadWith");
        ToolResultEgressGateway.EgressResult withoutField = ToolResultEgressGateway.compactForLlmAppend(
                "search_document_chunks", "c", result.toString(), null);
        assertEquals(withoutField.isCompacted(), withField.isCompacted());
        assertTrue(withField.getLlmContent().contains("\"loadWith\":\"load_topic\""));
    }

    @Test void failing_conversation_shape_scopes_the_search_offers_the_topic_and_keeps_the_tools() throws Exception {
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("What is the state of Demo Unit?")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted turnOne = new Scripted();
        turnOne.responses.add(calls(new ToolCall("unit", "resolve_thing", "{\"text\":\"Demo Unit\"}")));
        turnOne.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(turnOne).run(messages, definitions).getStatus());

        beginTurn();
        messages.add(ChatMessage.user("What should I collect for this symptom? Collect it from the unit."));
        AgentToolContext.setParlerActiveMessages(messages);
        ConversationEntityScope.injectFromHistory(messages, resolver);
        Scripted turnTwo = new Scripted();
        turnTwo.responses.add(calls(search("search")));
        turnTwo.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(turnTwo).run(messages, definitions).getStatus());

        String searchRow = messages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL && "search".equals(m.getToolCallId()))
                .findFirst().orElseThrow().getContent();
        JsonNode search = MAPPER.readTree(searchRow);
        assertEquals("conversation-entity-resolver", search.path("documentScopeSource").asText());
        assertEquals("default-match", search.path("documentScopeResolverSource").asText());
        assertEquals("documentIds-filter", search.path("selectionMode").asText());
        assertEquals("load_topic", match(search, "topic").path("loadWith").asText());
        assertTrue(hasTool(turnTwo.requests.get(1), "request_demo_log"));
        assertEquals(1, messages.stream().filter(m -> "resolve_thing".equals(m.getExecutedToolName())).count(),
                "turn 2 made no new resolver call");
    }

    private void assertOffer(boolean expected, String result) {
        beginTurn();
        ActiveMethodHooks.afterSearchDocumentChunks(search("s"), result);
        assertEquals(expected, AgentToolContext.getAgentTaskState().isMethodOffered(), result);
    }

    @Test void the_topics_array_and_a_topic_match_are_each_an_offer() {
        assertOffer(true, "{\"topics\":[{\"docId\":\"d\",\"chunkId\":\"t\"}],\"matches\":[{\"contentType\":\"page\"}]}");
        assertOffer(true, "{\"matches\":[{\"contentType\":\"topic\"}]}");
        assertOffer(false, "{\"topics\":[],\"matches\":[{\"contentType\":\"page\"}]}");
        assertOffer(false, "{\"matches\":[{\"contentType\":\"page\"}]}");
    }

    @Test void hinted_search_on_turn_two_reaches_the_lane_and_keeps_the_tools() throws Exception {
        DocumentKnowledgeIndexCache.clearForTests();
        DocumentKnowledgeIndexCache.readerForTests = s -> new TopicRound3Fixtures();
        resolvedUnit = RESOLVED_UNIT.replace("Demo Unit", "Family Turbine");
        List<ChatMessage> messages = new ArrayList<>(List.of(ChatMessage.system("stable"), ChatMessage.user("What is the state of Family Turbine?")));
        AgentToolContext.setParlerActiveMessages(messages);
        Scripted turnOne = new Scripted();
        turnOne.responses.add(calls(new ToolCall("unit", "resolve_thing", "{\"text\":\"Family Turbine\"}")));
        turnOne.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(turnOne).run(messages, definitions).getStatus());

        beginTurn();
        messages.add(ChatMessage.user("What should I collect for the Internal Control failure? Collect it from the unit."));
        AgentToolContext.setParlerActiveMessages(messages);
        ConversationEntityScope.injectFromHistory(messages, resolver);
        Scripted turnTwo = new Scripted();
        turnTwo.responses.add(calls(new ToolCall("search", "search_document_chunks",
                "{\"query\":\"Internal Control failing collect logs\",\"assetContext\":{\"assetModel\":\"Family Turbine\"},\"limit\":3}")));
        turnTwo.responses.add(answer());
        assertEquals(AgentLoop.AgentResult.Status.SUCCESS, loop(turnTwo).run(messages, definitions).getStatus());

        JsonNode search = MAPPER.readTree(messages.stream()
                .filter(m -> m.getRole() == ChatMessage.Role.TOOL && "search".equals(m.getToolCallId()))
                .findFirst().orElseThrow().getContent());
        assertEquals("conversation-entity-resolver", search.path("documentScopeSource").asText());
        assertEquals(TopicRound3Fixtures.TOPIC, search.path("topics").get(0).path("chunkId").asText());
        assertEquals("load_topic", search.path("topics").get(0).path("loadWith").asText());
        for (JsonNode match : search.path("matches")) {
            assertEquals("page", match.path("contentType").asText(), "ranking alone would have hidden the topic");
        }
        assertTrue(hasTool(turnTwo.requests.get(1), "request_demo_log"));
        assertEquals(1, messages.stream().filter(m -> "resolve_thing".equals(m.getExecutedToolName())).count(),
                "turn 2 made no new resolver call");
    }

    private static final class Scripted implements LlmClient {
        final Queue<LlmResponse> responses = new ArrayDeque<>();
        final Queue<Function<LlmChatRequest, LlmResponse>> reactions = new ArrayDeque<>();
        final List<LlmChatRequest> requests = new ArrayList<>();

        @Override public LlmResponse chat(LlmChatRequest request) {
            requests.add(request);
            return responses.isEmpty() ? reactions.remove().apply(request) : responses.remove();
        }

        @Override public LlmUsageWireIds usageWireIds() {
            return new LlmUsageWireIds("p", "t", "openai-chat-completions-v1", "test-model");
        }

        @Override public boolean healthCheck() {
            return true;
        }
    }
}
