package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.AgentBaseThing;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/** P19 to P24: a search that names a unit, on the production runtime and scorer with a synthetic package. */
class SearchUnitScopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CODE = "UNIT_SCOPE_NOT_ESTABLISHED";
    private static final String QUERY = "\"query\":\"collect demo evidence\"";
    private static final String NAMED = named("Demo_Unit_1");
    private static final String RESOLVE_ROW = "{\"status\":\"success\",\"resultKind\":\"THING_RESOLVED_INLINE\",\"stale\":false,"
            + "\"assetTypeKey\":\"\",\"totalCount\":1,\"matches\":[{\"name\":\"Demo_Unit_2\",\"criticalProperties\":{},\"matchedBy\":{}}]}";
    private static final String AMBIGUOUS = "{\"status\":\"error\",\"code\":\"IDENTITY_AMBIGUOUS\",\"totalCount\":2}";
    private static final ToolCall RESOLVE_CALL = new ToolCall("r", "resolve_thing", "{\"text\":\"unit\"}");

    private final List<String> decided = new ArrayList<>();
    private final Map<String, List<String>> documentsByThing = Map.of(
            "Demo_Unit_1", List.of("demo-guide"), "Demo_Unit_2", List.of("second-guide"), "Bare_Unit", List.of());

    private final Function<String, Optional<String>> canonicalName = name -> {
        decided.add(name);
        return documentsByThing.containsKey(name) ? Optional.of(name) : Optional.empty();
    };

    private final Function<String, Optional<ResolvedDocumentSet>> resolver = name -> {
        decided.add(name);
        return Optional.ofNullable(documentsByThing.get(name)).map(ids -> new ResolvedDocumentSet(ids, ResolverSource.CUSTOM));
    };

    private static String named(String thingName) {
        return "{" + QUERY + ",\"assetContext\":{\"thingName\":" + MAPPER.valueToTree(thingName) + "}}";
    }

    private static ToolCall call(String args) {
        return new ToolCall("s", "search_document_chunks", args);
    }

    @BeforeEach void setup() {
        TopicTestFixtures fixture = new TopicTestFixtures();
        DocumentKnowledgeIndexCache.readerForTests = settings -> fixture;
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = true;
    }

    @AfterEach void cleanup() {
        DocumentKnowledgeIndexCache.clearForTests();
        ScalarThingnamePreflight.clearModelGateOverrideForTests();
        AgentToolContext.clear();
    }

    private JsonNode searchNamed(String args) throws Exception {
        return searchNamed(args, canonicalName, resolver);
    }

    private JsonNode searchNamed(String args, Function<String, Optional<String>> names,
            Function<String, Optional<ResolvedDocumentSet>> sets) throws Exception {
        return MAPPER.readTree(DocumentKnowledgeRuntime.searchWithUnitScope(call(args), TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT, names, sets));
    }

    /** The pre-round-4 result for the same complete arguments: this overload contains no derivation. */
    private static JsonNode baseline(String args) throws Exception {
        return MAPPER.readTree(DocumentKnowledgeRuntime.executeSearchDocumentChunks(call(args), TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT));
    }

    private static List<JsonNode> unitWarnings(JsonNode result) {
        List<JsonNode> found = new ArrayList<>();
        result.path("warnings").forEach(entry -> {
            if (CODE.equals(entry.path("code").asText())) {
                found.add(entry);
            }
        });
        return found;
    }

    private static String onlyWarningMessage(JsonNode result) {
        List<JsonNode> found = unitWarnings(result);
        assertEquals(1, found.size(), result.toString());
        List<String> fields = new ArrayList<>();
        found.get(0).fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("code", "message"), fields);
        return found.get(0).path("message").asText();
    }

    private static JsonNode withoutUnitWarning(JsonNode result) {
        ObjectNode copy = result.deepCopy();
        ArrayNode kept = MAPPER.createArrayNode();
        result.path("warnings").forEach(entry -> {
            if (!CODE.equals(entry.path("code").asText())) {
                kept.add(entry);
            }
        });
        copy.set("warnings", kept);
        return copy;
    }

    private static void assertNoScope() {
        assertNull(AgentToolContext.getInjectedDocumentScopeIds());
        assertNull(AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    private static String identityMessage(String value, String recovery) {
        return "assetContext.thingName '" + value + "' did not establish a document scope: it is not the exact name of a "
                + "visible unit. " + recovery;
    }

    private static final String LOOK_UP = "If the user named a unit, call resolve_thing with the user's text and search "
            + "again; otherwise ignore this.";
    private static final String EXACT_NAME = "Use the exact Thing name if the user named a unit; otherwise ignore this.";

    private static String documentMessage(String value) {
        return "assetContext.thingName '" + value + "' is a visible unit, but no document scope is available for it (no "
                + "documents for it, or document knowledge unavailable). This search is not limited to that unit's "
                + "documents. Looking the unit up again will not change this.";
    }

    // ---- P19 derivation ----

    @Test void a_search_that_names_a_unit_is_scoped_and_offers_the_topic() throws Exception {
        assertTrue(baseline(NAMED).path("topics").isMissingNode(), "the failing shape: unscoped, no topic");

        JsonNode result = searchNamed(NAMED);

        assertEquals("documentIds-filter", result.path("selectionMode").asText());
        assertEquals("asset-context-resolver", result.path("documentScopeSource").asText());
        assertEquals("custom", result.path("documentScopeResolverSource").asText());
        assertEquals("topic-demo-r1", result.path("topics").get(0).path("chunkId").asText());
        assertTrue(unitWarnings(result).isEmpty());
        assertEquals(List.of("demo-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals(DocumentScopeOrigin.SEARCH_ASSET_CONTEXT, AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    @Test void the_resolver_receives_the_canonical_name_and_a_trimmed_value_reaches_the_gate() throws Exception {
        searchNamed(named("  demo_unit_1 "), name -> Optional.of("Demo_Unit_1"), resolver);
        assertEquals(List.of("Demo_Unit_1"), decided);
        assertEquals(List.of("demo-guide"), AgentToolContext.getInjectedDocumentScopeIds());
    }

    // ---- P20 precedence ----

    @Test void a_host_scope_ignores_the_named_unit() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom");
        JsonNode result = searchNamed(named("Demo_Unit_2"));
        assertEquals("host-context-resolver", result.path("documentScopeSource").asText());
        assertEquals(List.of("demo-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertTrue(unitWarnings(result).isEmpty());
        assertTrue(decided.isEmpty());
    }

    @Test void a_conversation_scope_is_untouched() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "custom", DocumentScopeOrigin.CONVERSATION);
        JsonNode result = searchNamed(named("Unknown_Unit"));
        assertEquals("conversation-entity-resolver", result.path("documentScopeSource").asText());
        assertEquals(DocumentScopeOrigin.CONVERSATION, AgentToolContext.getInjectedDocumentScopeOrigin());
        assertTrue(unitWarnings(result).isEmpty());
        assertTrue(decided.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"demo-guide\"]", "[]"})
    void explicit_document_ids_win(String ids) throws Exception {
        String args = "{" + QUERY + ",\"documentIds\":" + ids + ",\"assetContext\":{\"thingName\":\"Demo_Unit_1\"}}";
        assertEquals(baseline(args), searchNamed(args));
        assertNoScope();
        assertTrue(decided.isEmpty());
    }

    // ---- P21 not an identity ----

    @Test void a_name_the_gate_rejects_changes_nothing_but_the_warning() throws Exception {
        String args = "{" + QUERY + ",\"assetContext\":{\"thingName\":\"Demo Model\",\"assetModel\":\"Demo\"}}";
        JsonNode result = searchNamed(args);
        assertEquals(baseline(args), withoutUnitWarning(result));
        assertEquals(1, unitWarnings(result).size());
        assertNoScope();
    }

    @Test void a_name_with_no_documents_changes_nothing_but_the_warning() throws Exception {
        String args = "{" + QUERY + ",\"assetContext\":{\"thingName\":\"Bare_Unit\",\"component\":\"valve\"}}";
        JsonNode result = searchNamed(args);
        assertEquals(baseline(args), withoutUnitWarning(result));
        assertEquals(1, unitWarnings(result).size());
        assertNoScope();
    }

    @Test void other_asset_context_fields_are_not_an_identity() throws Exception {
        String args = "{" + QUERY + ",\"assetContext\":{\"assetModel\":\"Demo_Unit_1\",\"component\":\"valve\"}}";
        assertEquals(baseline(args), searchNamed(args));
        assertNoScope();
        assertTrue(decided.isEmpty());
    }

    // ---- P22 warning shape ----

    @Test void an_identity_failure_points_at_resolve_thing_when_it_can_help() throws Exception {
        assertEquals(identityMessage("Alias", LOOK_UP), onlyWarningMessage(searchNamed(named("Alias"))));
    }

    @Test void an_identity_failure_asks_for_the_exact_name_when_resolve_thing_cannot_help() throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = false;
        String message = onlyWarningMessage(searchNamed(named("Alias")));
        assertEquals(identityMessage("Alias", EXACT_NAME), message);
        assertFalse(message.contains("resolve_thing"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void a_document_failure_directs_no_lookup(boolean recoverable) throws Exception {
        ScalarThingnamePreflight.resolveThingRecoverableOverrideForTests = recoverable;
        String message = onlyWarningMessage(searchNamed(named("Bare_Unit")));
        assertEquals(documentMessage("Bare_Unit"), message);
        assertFalse(message.contains("resolve_thing"));
    }

    @Test void an_overlong_name_is_an_identity_failure_and_is_cut_in_the_echo() throws Exception {
        String message = onlyWarningMessage(searchNamed(named("x".repeat(257))));
        assertEquals(identityMessage("x".repeat(80), LOOK_UP), message);
        assertTrue(decided.isEmpty(), "the name reaches no decision");
    }

    @Test void the_name_length_limit_is_inclusive() throws Exception {
        searchNamed(named("x".repeat(256)));
        assertEquals(List.of("x".repeat(256)), decided);
    }

    @Test void control_characters_are_an_identity_failure_and_become_spaces_in_the_echo() throws Exception {
        String message = onlyWarningMessage(searchNamed(named("  Demo\nUnit\t1\u0085 ")));
        assertEquals(identityMessage("Demo Unit 1 ", LOOK_UP), message);
        assertTrue(decided.isEmpty());
    }

    @Test void the_echo_never_splits_a_surrogate_pair() throws Exception {
        String message = onlyWarningMessage(searchNamed(named("x".repeat(79) + "😀" + "y".repeat(5))));
        assertEquals(identityMessage("x".repeat(79), LOOK_UP), message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"query\":\"q\"}", "{\"query\":\"q\",\"assetContext\":{\"thingName\":\"   \"}}",
            "{\"query\":\"q\",\"assetContext\":{\"thingName\":\"\"}}", "{\"query\":\"q\",\"assetContext\":{\"thingName\":7}}",
            "{\"query\":\"q\",\"assetContext\":{\"thingName\":[\"Demo_Unit_1\"]}}", "{\"query\":\"q\",\"assetContext\":\"Demo_Unit_1\"}",
            "{\"query\":\"q\",\"assetContext\":{\"thing\":\"Demo_Unit_1\"}}"})
    void an_ineligible_search_is_silent(String args) throws Exception {
        assertEquals(baseline(args), searchNamed(args));
        assertNoScope();
        assertTrue(decided.isEmpty());
    }

    @Test void a_rejected_name_warns_on_each_search_that_carries_it() throws Exception {
        assertEquals(1, unitWarnings(searchNamed(named("Alias"))).size());
        assertEquals(1, unitWarnings(searchNamed(named("Alias"))).size());
    }

    private SearchUnitScope.Outcome rejected() {
        return SearchUnitScope.derive(call(named("Alias")), canonicalName, resolver);
    }

    @Test void an_existing_entry_with_the_code_is_not_duplicated() throws Exception {
        String result = "{\"status\":\"success\",\"warnings\":[{\"code\":\"" + CODE + "\",\"message\":\"earlier\"}]}";
        assertEquals(result, SearchUnitScope.appendWarning(result, rejected()));
    }

    @Test void the_warnings_array_is_created_when_absent() throws Exception {
        JsonNode result = MAPPER.readTree(SearchUnitScope.appendWarning("{\"status\":\"success\"}", rejected()));
        assertEquals(identityMessage("Alias", LOOK_UP), onlyWarningMessage(result));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{not json", "[]", "{\"warnings\":\"text\"}"})
    void a_result_the_append_cannot_read_is_returned_as_it_came(String result) {
        assertEquals(result, SearchUnitScope.appendWarning(result, rejected()));
    }

    @Test void the_warning_survives_egress_of_a_large_result() throws Exception {
        ObjectNode large = MAPPER.createObjectNode();
        large.put("status", "success");
        ArrayNode matches = large.putArray("matches");
        for (int i = 0; i < 400; i++) {
            matches.addObject().put("docId", "doc").put("chunkId", "page-" + i).put("snippet", "x".repeat(500));
        }
        String raw = SearchUnitScope.appendWarning(MAPPER.writeValueAsString(large), rejected());
        ToolResultEgressGateway.EgressResult egress =
                ToolResultEgressGateway.compactForLlmAppend("search_document_chunks", "c", raw, null);
        assertTrue(egress.getLlmContent().length() < raw.length(), "fixture precondition: egress compacts this result");
        assertTrue(egress.getLlmContent().contains(CODE));
    }

    // ---- P23 fail open ----

    @Test void a_canonical_name_decision_that_throws_is_an_identity_failure() throws Exception {
        JsonNode result = searchNamed(NAMED, name -> {
            throw new IllegalStateException("gate failed");
        }, resolver);
        assertEquals(baseline(NAMED), withoutUnitWarning(result));
        assertEquals(identityMessage("Demo_Unit_1", LOOK_UP), onlyWarningMessage(result));
        assertNoScope();
    }

    @Test void a_resolver_that_throws_is_a_document_failure() throws Exception {
        JsonNode result = searchNamed(NAMED, canonicalName, name -> {
            throw new IllegalStateException("resolver failed");
        });
        assertEquals(baseline(NAMED), withoutUnitWarning(result));
        assertEquals(documentMessage("Demo_Unit_1"), onlyWarningMessage(result));
        assertNoScope();
    }

    @Test void unparsable_arguments_fail_as_they_do_today() {
        assertThrows(Exception.class, () -> baseline("{not json"));
        assertThrows(Exception.class, () -> searchNamed("{not json"));
        assertTrue(decided.isEmpty());
    }

    @Test void an_empty_search_input_is_unchanged() throws Exception {
        assertEquals(baseline("{}"), searchNamed("{}"));
        assertEquals("EMPTY_SEARCH_INPUT", searchNamed("{}").path("warnings").get(0).path("code").asText());
    }

    @Test void an_unresolved_index_keeps_its_result_and_gains_the_document_warning() throws Exception {
        DocumentKnowledgeSettings unconfigured = DocumentKnowledgeSettings.fromAgent(null);
        JsonNode today = MAPPER.readTree(DocumentKnowledgeRuntime.executeSearchDocumentChunks(call(NAMED), unconfigured, ""));
        JsonNode result = MAPPER.readTree(DocumentKnowledgeRuntime.searchWithUnitScope(call(NAMED), unconfigured, "",
                canonicalName, name -> Optional.empty()));
        assertEquals("DOCUMENT_REPOSITORY_NOT_CONFIGURED", today.path("warnings").get(0).path("code").asText());
        assertEquals(today, withoutUnitWarning(result));
        assertEquals(documentMessage("Demo_Unit_1"), onlyWarningMessage(result));
    }

    @Test void the_public_overload_with_no_agent_skips_the_derivation() throws Exception {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> {
            decided.add(name);
            return true;
        };
        String result = DocumentKnowledgeRuntime.executeSearchDocumentChunks(call(NAMED), (AgentBaseThing) null);
        assertEquals(DocumentKnowledgeRuntime.executeSearchDocumentChunks(call(NAMED), DocumentKnowledgeSettings.fromAgent(null), ""),
                result);
        assertTrue(decided.isEmpty());
        assertNoScope();
    }

    @Test void the_gate_adapter_returns_nothing_for_a_name_the_gate_rejects() {
        ScalarThingnamePreflight.modelVisibleThingNamePredicateForTests = name -> false;
        assertEquals(Optional.empty(), SearchUnitScope.visibleCanonicalName("Demo_Unit_1"));
    }

    // ---- P24 lifetime ----

    @Test void the_scope_serves_later_searches_of_the_segment() throws Exception {
        searchNamed(NAMED);
        decided.clear();
        JsonNode later = searchNamed("{" + QUERY + "}");
        assertEquals("asset-context-resolver", later.path("documentScopeSource").asText());
        assertEquals("topic-demo-r1", later.path("topics").get(0).path("chunkId").asText());
        assertTrue(decided.isEmpty(), "a later search is not eligible");
    }

    @Test void a_later_unit_named_in_the_segment_does_not_replace_the_scope() throws Exception {
        searchNamed(NAMED);
        JsonNode later = searchNamed(named("Demo_Unit_2"));
        assertEquals(List.of("demo-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertTrue(unitWarnings(later).isEmpty());
    }

    @Test void a_successful_resolve_thing_replaces_the_scope() throws Exception {
        searchNamed(NAMED);
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, RESOLVE_ROW, resolver);
        assertEquals(List.of("second-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals(DocumentScopeOrigin.CONVERSATION, AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    @Test void a_non_single_match_resolve_thing_clears_the_scope() throws Exception {
        searchNamed(NAMED);
        ConversationEntityScope.afterResolveThing(RESOLVE_CALL, AMBIGUOUS, resolver);
        assertNoScope();
    }

    @Test void the_scope_is_not_restored_in_a_new_segment_without_a_resolved_unit() throws Exception {
        searchNamed(NAMED);
        AgentToolContext.clear();
        ConversationEntityScope.injectFromHistory(List.of(ChatMessage.user("named the unit"),
                ChatMessage.toolResult("s", "{\"status\":\"success\"}", "search_document_chunks")), resolver);
        assertNoScope();
    }

    @Test void a_resolved_unit_in_the_history_still_scopes_a_new_segment() throws Exception {
        searchNamed(NAMED);
        AgentToolContext.clear();
        ConversationEntityScope.injectFromHistory(List.of(ChatMessage.toolResult("r", RESOLVE_ROW, "resolve_thing")), resolver);
        assertEquals(List.of("second-guide"), AgentToolContext.getInjectedDocumentScopeIds());
        assertEquals(DocumentScopeOrigin.CONVERSATION, AgentToolContext.getInjectedDocumentScopeOrigin());
    }

    @Test void a_topic_outside_the_derived_scope_is_refused_like_any_injected_scope() throws Exception {
        searchNamed(named("Demo_Unit_2"));
        TopicTestFixtures fixture = new TopicTestFixtures();
        JsonNode load = MAPPER.readTree(DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("load"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s, n) -> fixture));
        assertEquals("TOPIC_OUT_OF_SCOPE", load.path("code").asText());
    }
}
