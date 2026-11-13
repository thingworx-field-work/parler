package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.StreamTokenUsage;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.playbook.PlaybookTerminalHandoff;
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/** P7: the default document scope derived from the conversation's newest resolved unit. */
class ConversationEntityScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String RESOLVE_THING = "resolve_thing";
    private static final ToolCall RESOLVE_CALL = new ToolCall("r", RESOLVE_THING, "{\"text\":\"unit\"}");

    private final List<String> resolverKeys = new ArrayList<>();
    private final Map<String, List<String>> documentsByThing = Map.of(
            "Demo_Unit_1", List.of("demo-guide"), "Demo_Unit_2", List.of("second-guide"));
    private TopicTestFixtures fixture;

    private final Function<String, Optional<ResolvedDocumentSet>> resolver = key -> {
        resolverKeys.add(key);
        List<String> ids = documentsByThing.get(key);
        return ids == null ? Optional.empty() : Optional.of(new ResolvedDocumentSet(ids, ResolverSource.CUSTOM));
    };

    @BeforeEach void setup() {
        fixture = new TopicTestFixtures();
        DocumentKnowledgeIndexCache.readerForTests = s -> fixture;
    }

    @AfterEach void cleanup() {
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
    }

    private static String single(String name) {
        return "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"stale\":false,\"assetTypeKey\":\"\","
                + "\"totalCount\":1,\"matches\":[{\"name\":\"" + name + "\",\"criticalProperties\":{},\"matchedBy\":{}}]}";
    }

    private static final String AMBIGUOUS = "{\"status\":\"error\",\"code\":\"IDENTITY_AMBIGUOUS\",\"totalCount\":2,"
            + "\"message\":\"Multiple Things matched.\",\"candidates\":[{\"name\":\"Demo_Unit_1\"},{\"name\":\"Demo_Unit_2\"}]}";

    private static ChatMessage resolved(String id, String content) {
        return ChatMessage.toolResult(id, content, RESOLVE_THING);
    }

    private void assertUnscoped() {
        assertNull(AgentToolContext.getInjectedDocumentScopeIds());
        assertNull(AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    private void assertConversationScope(String documentId) {
        assertEquals(List.of(documentId), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals("custom", AgentToolContext.getInjectedDocumentScopeSource());
        assertEquals(DocumentScopeOrigin.CONVERSATION, AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    @Test void single_match_injects_the_resolver_scope_with_conversation_origin() {
        ConversationEntityScope.injectFromHistory(List.of(resolved("a", single("Demo_Unit_1"))), resolver);
        assertConversationScope("demo-guide");
        assertEquals(List.of("Demo_Unit_1"), resolverKeys);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"totalCount\":0,\"matches\":[]}",
            "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"totalCount\":2,\"matches\":[{\"name\":\"A\"},{\"name\":\"B\"}]}",
            "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"totalCount\":1,\"matches\":[{\"name\":\"A\"},{\"name\":\"B\"}]}",
            "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"totalCount\":1,\"matches\":[{\"name\":\" \"}]}",
            "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_LARGE\",\"totalCount\":1,\"matches\":[{\"name\":\"A\"}]}",
            "{\"status\":\"success\",\"resultKind\":\"TAXONOMY_ASSET_IDENTIFIER_INLINE\",\"totalCount\":1,\"matches\":[{\"name\":\"A\"}]}",
            "{\"status\":\"error\",\"code\":\"IDENTITY_NOT_FOUND\",\"message\":\"No Thing matched the given text.\"}",
            "{\"status\":\"error\",\"code\":\"IDENTITY_AMBIGUOUS\",\"totalCount\":2,\"candidates\":[{\"name\":\"A\"}]}",
            "[1,2]",
            "not json"})
    void any_other_result_gives_no_scope(String content) {
        ConversationEntityScope.injectFromHistory(List.of(resolved("a", content)), resolver);
        assertUnscoped();
        assertTrue(resolverKeys.isEmpty());
    }

    @Test void newest_ambiguous_or_failed_row_is_selected_before_validation_and_never_falls_back() {
        ConversationEntityScope.injectFromHistory(
                List.of(resolved("old", single("Demo_Unit_1")), resolved("new", AMBIGUOUS)), resolver);
        assertUnscoped();
        assertTrue(resolverKeys.isEmpty());
    }

    @Test void newest_row_decides_when_a_later_unit_was_resolved() {
        ConversationEntityScope.injectFromHistory(
                List.of(resolved("old", single("Demo_Unit_1")), resolved("new", single("Demo_Unit_2"))), resolver);
        assertConversationScope("second-guide");
        assertEquals(List.of("Demo_Unit_2"), resolverKeys);
    }

    @Test void newer_rows_of_other_tools_do_not_change_the_selection() {
        ConversationEntityScope.injectFromHistory(List.of(resolved("a", single("Demo_Unit_1")),
                ChatMessage.toolResult("b", "{\"status\":\"error\"}", "get_property_values"),
                ChatMessage.assistant("done")), resolver);
        assertConversationScope("demo-guide");
    }

    @Test void playbook_terminal_handoff_skipped_resolution_blocks_fallback_to_the_older_unit() {
        List<ToolCall> batch = List.of(new ToolCall("start", "start_playbook", "{}"),
                new ToolCall("later", RESOLVE_THING, "{\"text\":\"Demo_Unit_2\"}"));
        List<ChatMessage> history = new ArrayList<>(List.of(resolved("old", single("Demo_Unit_1")),
                ChatMessage.assistantWithToolCalls(batch),
                ChatMessage.toolResult("start", "{\"status\":\"completed\"}", "start_playbook")));
        assertEquals(1, PlaybookTerminalHandoff.appendSkippedSiblingToolResults(batch, 1, history,
                (row, usage) -> assertEquals(StreamTokenUsage.ZERO, usage)));

        ConversationEntityScope.injectFromHistory(history, resolver);

        assertUnscoped();
        assertTrue(resolverKeys.isEmpty(), "the older unit must not reach the resolver");
    }

    @Test void a_row_for_any_other_unexecuted_call_counts_as_the_newest_result() {
        String repetitionBlocked = "{\"status\":\"error\",\"code\":\"REPEATED_IDENTICAL_TOOL_CALL\"}";
        ConversationEntityScope.injectFromHistory(
                List.of(resolved("old", single("Demo_Unit_1")), resolved("blocked", repetitionBlocked)), resolver);
        assertUnscoped();
        ConversationEntityScope.injectFromHistory(List.of(resolved("old", single("Demo_Unit_1")),
                resolved("interrupted", HitlInterruptedBatchSiblingResult.JSON)), resolver);
        assertUnscoped();
    }

    @Test void a_row_removed_by_trimming_yields_no_scope() {
        ConversationEntityScope.injectFromHistory(List.of(ChatMessage.user("this analyzer")), resolver);
        assertUnscoped();
        ConversationEntityScope.injectFromHistory(null, resolver);
        assertUnscoped();
    }

    @Test void an_injected_host_scope_is_not_replaced_at_turn_start() {
        AgentToolContext.setInjectedDocumentScope(List.of("host-doc"), "custom");
        ConversationEntityScope.injectFromHistory(List.of(resolved("a", single("Demo_Unit_1"))), resolver);
        assertEquals(List.of("host-doc"), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals(DocumentScopeOrigin.HOST_CONTEXT, AgentToolContext.getInjectedDocumentScopeOrigin());
        assertTrue(resolverKeys.isEmpty());
    }

    @Test void in_turn_resolution_sets_then_replaces_a_conversation_scope() {
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_1"), resolver);
        assertConversationScope("demo-guide");
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_2"), resolver);
        assertConversationScope("second-guide");
    }

    @Test void in_turn_ambiguous_failed_or_unmapped_result_clears_a_conversation_scope() {
        for (String result : List.of(AMBIGUOUS, "{\"status\":\"error\",\"code\":\"IDENTITY_NOT_FOUND\"}",
                single("Unmapped_Unit"))) {
            ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_1"), resolver);
            assertConversationScope("demo-guide");
            ConversationEntityScope.afterResolveThing(RESOLVE_CALL, result, resolver);
            assertUnscoped();
        }
    }

    @Test void host_scope_is_never_changed_in_turn_even_with_the_same_resolver_source() {
        AgentToolContext.setInjectedDocumentScope(List.of("host-doc"), "custom");
        for (String result : List.of(single("Demo_Unit_1"), AMBIGUOUS)) {
            ConversationEntityScope.afterResolveThing(RESOLVE_CALL, result, resolver);
            assertEquals(List.of("host-doc"), AgentToolContext.getInjectedDocumentScopeIds());
            assertEquals(DocumentScopeOrigin.HOST_CONTEXT, AgentToolContext.getInjectedDocumentScopeOrigin());
        }
        assertTrue(resolverKeys.isEmpty());
    }

    @Test void other_tools_do_not_trigger_the_in_turn_update() {
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_1"), resolver);
        ConversationEntityScope.afterResolveThing(new ToolCall("x", "get_property_values", "{}"), AMBIGUOUS, resolver);
        ConversationEntityScope.afterResolveThing(null, AMBIGUOUS, resolver);
        assertConversationScope("demo-guide");
    }

    @Test void resolver_exception_leaves_the_turn_unscoped() {
        Function<String, Optional<ResolvedDocumentSet>> failing = key -> { throw new IllegalStateException("boom"); };
        ConversationEntityScope.injectFromHistory(List.of(resolved("a", single("Demo_Unit_1"))), failing);
        assertUnscoped();
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_1"), resolver);
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, single("Demo_Unit_2"), failing);
        assertUnscoped();
    }

    @Test void the_two_argument_setter_means_host_origin_and_clearing_clears_the_origin() {
        AgentToolContext.setInjectedDocumentScope(List.of("a"), "default-match");
        assertEquals(DocumentScopeOrigin.HOST_CONTEXT, AgentToolContext.getInjectedDocumentScopeOrigin());
        AgentToolContext.setInjectedDocumentScope(List.of(), null);
        assertUnscoped();
        AgentToolContext.setInjectedDocumentScope(List.of("a"), "custom", DocumentScopeOrigin.CONVERSATION);
        AgentToolContext.clear();
        assertUnscoped();
    }

    private JsonNode search(String args) throws Exception {
        return MAPPER.readTree(DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s", "search_document_chunks", args), TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT));
    }

    private JsonNode load() throws Exception {
        return MAPPER.readTree(DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("load"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s, n) -> fixture));
    }

    @Test void search_reports_the_scope_origin_and_keeps_the_host_label() throws Exception {
        String args = "{\"query\":\"collect demo evidence\"}";
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom", DocumentScopeOrigin.CONVERSATION);
        JsonNode conversation = search(args);
        assertEquals("conversation-entity-resolver", conversation.path("documentScopeSource").asText());
        assertEquals("custom", conversation.path("documentScopeResolverSource").asText());
        assertEquals("documentIds-filter", conversation.path("selectionMode").asText());

        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom");
        assertEquals("host-context-resolver", search(args).path("documentScopeSource").asText());
    }

    @Test void model_supplied_document_ids_suppress_the_default() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("other-doc"), "custom", DocumentScopeOrigin.CONVERSATION);
        assertFalse(search("{\"query\":\"collect demo evidence\",\"documentIds\":[]}").has("documentScopeSource"));
        JsonNode explicit = search("{\"query\":\"collect demo evidence\",\"documentIds\":[\"demo-guide\"]}");
        assertFalse(explicit.has("documentScopeSource"));
        assertFalse(explicit.path("matches").isEmpty());
    }

    @Test void load_topic_enforces_a_conversation_scope() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("other-doc"), "custom", DocumentScopeOrigin.CONVERSATION);
        assertEquals("TOPIC_OUT_OF_SCOPE", load().path("code").asText());
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom", DocumentScopeOrigin.CONVERSATION);
        String inScope = DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("load"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s, n) -> fixture);
        assertTrue(TopicMethodText.successfulReference(fixture.call("load"), inScope).isPresent(), inScope);
    }

    private static String agentThingSource() throws IOException {
        Path source = Paths.get("src/main/java/com/thingworx/things/agent/AgentThing.java");
        if (!Files.exists(source)) {
            source = Paths.get("parler-agent/src/main/java/com/thingworx/things/agent/AgentThing.java");
        }
        return new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
    }

    private static int count(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int found = 0;
        while (matcher.find()) {
            found++;
        }
        return found;
    }

    @Test void every_turn_entry_and_the_continuation_derive_the_conversation_scope() throws IOException {
        String source = agentThingSource();
        assertEquals(3, count(source, "(?<!void )maybeInjectHostContextDocumentScope\\(\\w+\\);"));
        assertEquals(3, count(source,
                "maybeInjectHostContextDocumentScope\\(\\w+\\);\\s+maybeInjectConversationEntityDocumentScope\\(messages\\);"));
        int continuation = source.indexOf("private TurnOutcome runParlerPostToolAgentLoop(");
        int bind = source.indexOf("ParlerHitlContinuationContext.bindForGatedToolExecution(", continuation);
        int derive = source.indexOf("maybeInjectConversationEntityDocumentScope(messages);", bind);
        int run = source.indexOf("loop.runAfterApproval(", bind);
        assertTrue(continuation > 0 && bind > continuation && derive > bind && run > derive);
    }

    @Test void round_two_hooks_run_only_on_the_java_built_in_path() throws IOException {
        String source = agentThingSource();
        assertEquals(1, count(source, "ActiveMethodHooks\\.afterSearchDocumentChunks\\("));
        assertEquals(1, count(source, "ConversationEntityScope\\.afterResolveThing\\("));
        assertEquals(1, count(source, "String toolResult = _toolRegistry\\.executeTool\\(toolCall\\);\\s+"
                + "ActiveMethodHooks\\.afterLoadTopic\\(toolCall, toolResult\\);\\s+"
                + "ActiveMethodHooks\\.afterSearchDocumentChunks\\(toolCall, toolResult\\);\\s+"
                + "ConversationEntityScope\\.afterResolveThing\\("));
    }
}
