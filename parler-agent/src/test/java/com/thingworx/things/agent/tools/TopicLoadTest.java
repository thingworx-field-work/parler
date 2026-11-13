package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.thingworx.common.RESTAPIConstants.StatusCode;
import com.thingworx.common.exceptions.InvalidRequestException;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.agent.taskstate.ActiveMethodHooks;
import com.thingworx.things.agent.taskstate.AgentTaskState;
import com.thingworx.types.InfoTable;

class TopicLoadTest {
    private TopicTestFixtures fixture;

    @BeforeEach void setup() {
        fixture = new TopicTestFixtures();
        DocumentKnowledgeIndexCache.clearForTests();
        DocumentKnowledgeIndexCache.readerForTests = s -> fixture;
        AgentToolContext.setAgentTaskState(new AgentTaskState("r", "c", "goal"));
        AgentToolContext.getAgentTaskState().recordActiveMethod("skill:existing");
    }

    @AfterEach void cleanup() {
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
    }

    private String load(ToolCall call) throws Exception {
        String result = DocumentKnowledgeRuntime.executeLoadTopic(call, TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT, (s, n) -> fixture);
        ActiveMethodHooks.afterLoadTopic(call, result);
        return result;
    }

    private void error(String code, String result) {
        JSONObject json = new JSONObject(result);
        assertEquals("error", json.getString("status"));
        assertEquals(code, json.getString("code"), result);
        assertEquals(List.of("skill:existing"), AgentToolContext.getAgentTaskState().getActiveMethodIds());
    }

    @Test void search_reference_loads_exact_complete_plain_text_and_repeat_is_idempotent() throws Exception {
        String search = DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s", "search_document_chunks", "{\"query\":\"Collect demo evidence\"}"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
        var rows = new JSONObject(search).getJSONArray("matches");
        JSONObject topic = null;
        for (int i = 0; i < rows.length(); i++)
            if ("topic".equals(rows.getJSONObject(i).getString("contentType"))) topic = rows.getJSONObject(i);
        assertNotNull(topic);
        ToolCall ref = new ToolCall("l", "load_topic", new JSONObject().put("docId", topic.getString("docId"))
                .put("chunkId", topic.getString("chunkId")).toString());
        String result = load(ref);
        assertEquals(fixture.result(), result);
        assertEquals(result, ToolResultEgressGateway.compactForLlmAppend("load_topic", "l", result, null).getLlmContent());
        assertTrue(result.endsWith(fixture.body));
        assertTrue(result.length() < 8192);
        assertEquals(result, load(ref));
        assertEquals(2, fixture.bodyReads, "every load reads current-user text even on a cache hit");
        assertEquals(List.of("skill:existing", "topic:demo-guide/topic-demo-r1"), AgentToolContext.getAgentTaskState().getActiveMethodIds());
    }

    @Test void carrier_uses_the_known_sha256_vector_and_utf16_character_count() throws Exception {
        fixture.body = "abc";
        assertEquals("topic: topic-demo-r1\ndocument: demo-guide\nsha256: "
                + "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad   chars: 3\ncomplete: true\n---\nabc",
                load(fixture.call("l")));
        fixture.body = "🙂".repeat(3500);
        String astral = load(fixture.call("astral"));
        assertTrue(astral.contains("   chars: 7000\ncomplete: true"));
        assertTrue(astral.endsWith(fixture.body));
        fixture.body += "x";
        assertEquals("TOPIC_TOO_LARGE", new JSONObject(load(fixture.call("too-large"))).getString("code"));
    }

    @ParameterizedTest
    @ValueSource(strings={"{", "null", "[]", "{}", "{\"docId\":\"d\",\"chunkId\":\"c\"} {}", "{\"docId\":null,\"chunkId\":\"x\"}",
            "{\"docId\":1,\"chunkId\":\"x\"}", "{\"docId\":\"x\"}", "{\"docId\":\"\",\"chunkId\":\"x\"}"})
    void invalid_json_and_fields_fail_before_io(String args) throws Exception {
        error("BAD_REQUEST", load(new ToolCall("l", "load_topic", args)));
        assertEquals(0, fixture.listings + fixture.fileReads);
    }

    @Test void invalid_ids_and_exact_length_limits() throws Exception {
        for (String id : List.of("..", "a/b", "a\\b", ".", " x", "x ", "\nx", "x\u0000y", "x\u2028y", "x".repeat(257))) {
            error("BAD_REQUEST", load(new ToolCall("l", "load_topic", new JSONObject().put("docId", id).put("chunkId", "x").toString())));
            error("BAD_REQUEST", load(new ToolCall("l", "load_topic", new JSONObject().put("docId", "x").put("chunkId", id).toString())));
        }
        assertEquals(0, fixture.listings + fixture.fileReads);
        fixture.doc = "d".repeat(256); fixture.chunk = "c".repeat(256); fixture.body = "温[".repeat(3500);
        String result = load(fixture.call("l"));
        assertEquals(fixture.result(), result);
        assertTrue(result.length() < 8192);
        assertEquals(result, ToolResultEgressGateway.compactForLlmAppend("load_topic", "l", result, null).getLlmContent());
    }

    @Test void not_indexed_non_topic_scope_missing_empty_and_oversized_fail_without_recognition() throws Exception {
        error("TOPIC_NOT_FOUND", load(new ToolCall("l", "load_topic", "{\"docId\":\"demo-guide\",\"chunkId\":\"missing\"}")));
        error("TOPIC_NOT_FOUND", load(new ToolCall("l", "load_topic", "{\"docId\":\"demo-guide\",\"chunkId\":\"page-0001\"}")));
        assertEquals(0, fixture.bodyReads);
        AgentToolContext.setInjectedDocumentScope(List.of("other"), "test");
        error("TOPIC_OUT_OF_SCOPE", load(fixture.call("l")));
        assertEquals(0, fixture.bodyReads);
        AgentToolContext.setInjectedDocumentScope(List.of("demo-guide"), "test");
        fixture.body = null; error("TOPIC_LOAD_FAILED", load(fixture.call("l")));
        fixture.body = " \n"; error("TOPIC_LOAD_FAILED", load(fixture.call("l")));
        fixture.body = "x".repeat(7001); error("TOPIC_TOO_LARGE", load(fixture.call("l")));
        AgentToolContext.setInjectedDocumentScope(List.of(), "test");
        fixture.body = "x".repeat(7000); assertEquals(fixture.result(), load(fixture.call("l")));
    }

    @ParameterizedTest @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void index_denial_retains_cause_without_stale_fallback_or_diagnostic_probe(boolean expired, boolean textRead) throws Exception {
        DocumentKnowledgeIndex original = DocumentKnowledgeIndex.load(fixture, TopicTestFixtures.SETTINGS, Instant.now());
        if (expired) DocumentKnowledgeIndexCache.installExpiredForTests(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, original);
        AtomicInteger deniedCalls = new AtomicInteger();
        DocumentKnowledgeIndexCache.readerForTests = s -> new RepositoryReader() {
            public InfoTable getFileListing(String path, String mask) throws Exception {
                if (!textRead) { deniedCalls.incrementAndGet(); throw denied(); }
                return fixture.getFileListing(path, mask);
            }
            public String loadText(String path) throws Exception { deniedCalls.incrementAndGet(); throw denied(); }
        };
        AtomicInteger bodyFactories = new AtomicInteger();
        String result = DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("l"), TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT, (s,n) -> { bodyFactories.incrementAndGet(); return fixture; });
        error("DOCUMENT_REPOSITORY_UNAVAILABLE", result);
        assertEquals(1, deniedCalls.get());
        assertEquals(0, bodyFactories.get());
        var classified = DocumentKnowledgeRuntime.resolveIndexForTopic(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
        assertEquals(DocumentKnowledgeIndexCache.LoadResult.FailureReason.PERMISSION_DENIED, classified.failureReason());
        assertNull(classified.index()); assertFalse(classified.staleRebuildFailed());
        DocumentKnowledgeIndexCache.readerForTests = null;
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s,n,l,t) -> null;
        var later = DocumentKnowledgeRuntime.resolveIndexForTopic(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
        assertEquals(expired, later.index() != null, "denial must not delete/create the shared entry");
        if (expired) assertTrue(later.staleRebuildFailed());
    }

    @Test void index_internal_failure_absent_reader_and_stale_controls() throws Exception {
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s,n,l,t) -> { throw new IllegalStateException("synthetic failure"); };
        error("DOCUMENT_TOOL_INTERNAL_ERROR", load(fixture.call("l")));
        DocumentKnowledgeIndexCache.rebuildHookForTests = (s,n,l,t) -> null;
        error("DOCUMENT_TOOL_INTERNAL_ERROR", load(fixture.call("l")));
        DocumentKnowledgeIndexCache.rebuildHookForTests = null;
        DocumentKnowledgeIndexCache.readerForTests = s -> null;
        error("DOCUMENT_REPOSITORY_UNAVAILABLE", load(fixture.call("l")));
        DocumentKnowledgeIndex original = DocumentKnowledgeIndex.load(fixture, TopicTestFixtures.SETTINGS, Instant.now());
        DocumentKnowledgeIndexCache.installExpiredForTests(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, original);
        var stale = DocumentKnowledgeRuntime.resolveIndexForTopic(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
        assertNotNull(stale.index()); assertTrue(stale.staleRebuildFailed());
        assertEquals(DocumentKnowledgeIndexCache.LoadResult.FailureReason.NONE, stale.failureReason());
        // Absent reader with a stale candidate is safe only because the actual body reader is still required.
        error("DOCUMENT_REPOSITORY_UNAVAILABLE", DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("l"),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT, (s,n) -> null));
        assertEquals(fixture.result(), load(fixture.call("l")));
    }

    @Test void warm_index_still_requires_body_permission_and_failures_preserve_prior_success() throws Exception {
        String prior = load(fixture.call("l"));
        List<String> ids = AgentToolContext.getAgentTaskState().getActiveMethodIds();
        DocumentKnowledgeIndexCache.readerForTests = s -> { throw new AssertionError("warm index must not rebuild"); };
        String result = DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("denied"), TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT, (s,n) -> new RepositoryReader() {
                    public InfoTable getFileListing(String p, String m) { throw new AssertionError(); }
                    public String loadText(String p) throws Exception { throw denied(); }
                });
        ActiveMethodHooks.afterLoadTopic(fixture.call("denied"), result);
        assertEquals("DOCUMENT_REPOSITORY_UNAVAILABLE", new JSONObject(result).getString("code"));
        assertEquals(ids, AgentToolContext.getAgentTaskState().getActiveMethodIds());
        assertTrue(TopicMethodText.successfulReference(fixture.call("l"), prior).isPresent());
    }

    @Test void unconfigured_repository_uses_existing_error_name() throws Exception {
        error("DOCUMENT_REPOSITORY_NOT_CONFIGURED", DocumentKnowledgeRuntime.executeLoadTopic(fixture.call("l"),
                DocumentKnowledgeSettings.defaults(), TopicTestFixtures.AGENT, (s,n) -> { throw new AssertionError(); }));
    }

    @Test void working_file_is_md_and_the_legacy_txt_is_never_requested() throws Exception {
        fixture.body = null;
        fixture.legacyTxtBody = "### Legacy\nOld text.\n";
        String txtOnly = load(fixture.call("l"));
        error("TOPIC_LOAD_FAILED", txtOnly);
        assertEquals("Topic text could not be read.", new JSONObject(txtOnly).getString("message"));
        assertEquals(List.of(fixture.topicPath("md")), topicPathsRequested());

        fixture.paths.clear();
        fixture.body = "### Current\nNew text.\n";
        String both = load(fixture.call("l"));
        assertEquals(fixture.result(), both);
        assertFalse(both.contains("Legacy"));
        assertEquals(List.of(fixture.topicPath("md")), topicPathsRequested());
    }

    @Test void platform_missing_file_is_a_load_failure_not_an_unavailable_repository() throws Exception {
        RepositoryReader platformShape = new RepositoryReader() {
            public InfoTable getFileListing(String p, String m) { return fixture.getFileListing(p, m); }
            public String loadText(String p) throws Exception {
                if (p.equals(fixture.topicPath("md")))
                    throw new InvalidRequestException("File Does Not Exist", StatusCode.STATUS_NOT_FOUND);
                return fixture.loadText(p);
            }
        };
        ToolCall call = fixture.call("l");
        String result = DocumentKnowledgeRuntime.executeLoadTopic(call, TopicTestFixtures.SETTINGS,
                TopicTestFixtures.AGENT, (s,n) -> platformShape);
        ActiveMethodHooks.afterLoadTopic(call, result);
        error("TOPIC_LOAD_FAILED", result);
        assertEquals("Topic text could not be read.", new JSONObject(result).getString("message"));
    }

    @Test void markdown_body_is_delivered_byte_for_byte_without_a_body_rule() throws Exception {
        fixture.body = "# Title\n\n---\nfront: matter\n---\n| a | 温 |\n|---|---|\n";
        String result = load(fixture.call("l"));
        assertEquals(fixture.result(), result);
        assertTrue(result.endsWith("complete: true\n---\n" + fixture.body), result);
        assertTrue(result.contains("sha256: " + sha256Hex(fixture.body) + "   chars: " + fixture.body.length() + "\n"), result);
    }

    private List<String> topicPathsRequested() {
        return fixture.paths.stream().filter(p -> p.contains("/topics/")).collect(Collectors.toList());
    }

    private static String sha256Hex(String text) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static InvalidRequestException denied() {
        return new InvalidRequestException("synthetic typed permission denial", StatusCode.STATUS_FORBIDDEN);
    }
}
