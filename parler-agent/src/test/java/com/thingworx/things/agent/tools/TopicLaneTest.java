package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.tools.AgentToolContext.DocumentScopeOrigin;

/** P12, P13, P15 and P16: the topic lane on production scorer, runtime and egress, with synthetic packages. */
class TopicLaneTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DOC = "doc-a";
    private static final String HINT = "\"assetContext\":{\"assetModel\":\"Family Turbine\"}";
    private static final String SCOPE = "\"documentIds\":[\"" + TopicRound3Fixtures.MANUAL + "\",\""
            + TopicRound3Fixtures.ARTICLE + "\",\"" + TopicRound3Fixtures.NOTES + "\",\"" + TopicRound3Fixtures.OTHER + "\"]";
    private static final String FAILING_QUERY = "\"query\":\"Internal Control failing collect logs\"";

    @BeforeEach void setup() {
        DocumentKnowledgeIndexCache.readerForTests = settings -> new TopicRound3Fixtures();
    }

    @AfterEach void cleanup() {
        DocumentKnowledgeIndexCache.clearForTests();
        AgentToolContext.clear();
    }

    // ---- scorer rules, on synthetic rows ----

    private static DocumentKnowledgeChunk row(String doc, String chunkId, String contentType, String heading,
            List<String> tags, List<String> signals, String summary, String markdown) {
        ObjectNode line = MAPPER.createObjectNode();
        line.put("docId", doc);
        line.put("chunkId", chunkId);
        line.put("contentType", contentType);
        if (heading != null) {
            line.put("heading", heading);
        }
        ArrayNode tagArray = line.putArray("tags");
        tags.forEach(tagArray::add);
        ArrayNode signalArray = line.putArray("signals");
        signals.forEach(name -> signalArray.addObject().put("kind", "symptom").put("name", name));
        line.put("summary", summary);
        line.put("markdown", markdown);
        return DocumentKnowledgeChunk.parseJsonLine(line.toString()).orElseThrow();
    }

    private static DocumentKnowledgeChunk topic(String chunkId, String heading, List<String> tags, List<String> signals) {
        return row(DOC, chunkId, "topic", heading, tags, signals, "", "");
    }

    private static Function<String, Optional<DocumentKnowledgePackageManifest>> family() {
        String json = "{\"contractVersion\":\"0.1\",\"docId\":\"" + DOC + "\",\"title\":\"t\",\"sourcePath\":\"s\","
                + "\"markdownPath\":\"m\",\"chunksPath\":\"c\",\"pageCount\":1,\"sourceRepository\":\"r\","
                + "\"sourceRepositoryPath\":\"p\",\"sourceHref\":\"h\",\"sourceSha256\":\"0\",\"convertedAt\":\"d\","
                + "\"assetModels\":[\"Family Turbine\"]}";
        DocumentKnowledgePackageManifest manifest = DocumentKnowledgePackageManifest.parse(json).manifest();
        return doc -> Optional.of(manifest);
    }

    private static Function<String, Optional<DocumentKnowledgePackageManifest>> noManifest() {
        return doc -> Optional.empty();
    }

    private static DocumentKnowledgeSearchScorer.SearchRequest request(String query, String... signals) throws Exception {
        ObjectNode args = MAPPER.createObjectNode();
        args.put("query", query);
        args.putArray("documentIds").add(DOC);
        ArrayNode signalArray = args.putArray("signals");
        Arrays.stream(signals).forEach(name -> signalArray.addObject().put("kind", "symptom").put("name", name));
        return DocumentKnowledgeSearchScorer.SearchRequest.from(args);
    }

    private static List<String> lane(Function<String, Optional<DocumentKnowledgePackageManifest>> manifests,
            DocumentKnowledgeSearchScorer.SearchRequest request, DocumentKnowledgeChunk... chunks) {
        return DocumentKnowledgeSearchScorer.topicLane(List.of(chunks), manifests, request, null).stream()
                .map(match -> match.chunk().chunkId()).toList();
    }

    private static int score(DocumentKnowledgeSearchScorer.SearchRequest request, DocumentKnowledgeChunk chunk) {
        return DocumentKnowledgeSearchScorer.topicLane(List.of(chunk), noManifest(), request, null).get(0).score();
    }

    @Test void a_multi_word_tag_matches_by_token_set() throws Exception {
        DocumentKnowledgeChunk chunk = topic("t", "Internal Control", List.of("internal control"), List.of());
        assertEquals(List.of("t"), lane(noManifest(), request("Internal Control failing collect logs"), chunk));
        assertEquals(List.of(), lane(noManifest(), request("internal failing"),
                topic("t", "Internal", List.of("internal control"), List.of())), "every tag token is required");
    }

    @Test void a_single_generic_tag_needs_a_heading_or_summary_token() throws Exception {
        List<String> tag = List.of("logs");
        assertEquals(List.of(), lane(noManifest(), request("collect logs"), topic("t", "Other", tag, List.of())));
        assertEquals(List.of(), lane(noManifest(), request("one two three four logs"),
                row(DOC, "t", "topic", "Other", tag, List.of(), "", "one two three four")), "markdown scores 1 each");
        assertEquals(List.of("t"), lane(noManifest(), request("one two three four five logs"),
                row(DOC, "t", "topic", "Other", tag, List.of(), "", "one two three four five")), "five markdown tokens");
        assertEquals(List.of("t"), lane(noManifest(), request("collect logs"), topic("t", "Logs", tag, List.of())));
        assertEquals(List.of("t"), lane(noManifest(), request("collect logs"),
                row(DOC, "t", "topic", "Other", tag, List.of(), "logs here", "")));
    }

    @Test void a_blank_tag_never_matches() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("collect logs"),
                topic("t", "Collect", List.of("   "), List.of())));
    }

    @Test void a_repeated_query_word_earns_nothing() throws Exception {
        DocumentKnowledgeChunk chunk = topic("t", "Other", List.of(), List.of("logsfault"));
        assertEquals(List.of(), lane(noManifest(), request("logs logs"), chunk), "25 once, not 50");
    }

    @Test void the_heading_phrase_needs_two_distinct_tokens() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("logs"), topic("t", "Collect logs", List.of(), List.of())));
        assertEquals(List.of(), lane(noManifest(), request("on"), topic("t", "Operation", List.of(), List.of())));
        assertEquals(List.of(), lane(noManifest(), request("logs logs"), topic("t", "logs logs", List.of(), List.of())));
        assertEquals(List.of("t"), lane(noManifest(), request("collect logs"),
                topic("t", "How to collect logs", List.of(), List.of())));
        assertEquals(35 + 16, score(request(" Collect Logs "), topic("t", "how to collect logs", List.of(), List.of())));
    }

    @Test void text_and_heading_tokens_alone_do_not_qualify() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("collect internal control logs failing"),
                row(DOC, "t", "topic", "Logs of the internal control", List.of(), List.of(),
                        "collect internal control logs failing", "collect internal control logs failing")));
    }

    @Test void signal_equality_scores_fifty_and_a_signal_token_twenty_five() throws Exception {
        assertEquals(50 + 25, score(request("", "Overheat"), row(DOC, "t", "topic", "Other", List.of("overheat"),
                List.of("overheat"), "", "")), "signal equality plus a tag matched by a signal token");
        assertEquals(25 + 5, score(request("overheat now"),
                row(DOC, "t", "topic", "Other", List.of(), List.of("overheating"), "overheat", "")));
    }

    // ---- P25 lane signals ----

    private static final String PUMP_SIGNAL = "pump shows low pressure";
    private static final String PUMP_QUESTION = "pump shows low pressure what should I collect";

    private static DocumentKnowledgeChunk pumpTopic(String heading) {
        return topic("t", heading, List.of(), List.of(PUMP_SIGNAL));
    }

    @Test void a_first_word_alone_does_not_fire_a_multi_word_signal() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("pump priming"), pumpTopic("Pump handling")));
    }

    @Test void a_query_with_every_signal_word_earns_the_signal_in_any_order() throws Exception {
        assertEquals(25 + 8, score(request(PUMP_QUESTION), pumpTopic("Collect evidence")));
        assertEquals(25 + 8, score(request("low pressure shows on pump collect"), pumpTopic("Collect evidence")));
        assertEquals(List.of(), lane(noManifest(), request(PUMP_QUESTION), pumpTopic("Other")),
                "one signal alone stays below the floor");
    }

    @Test void the_other_structured_paths_still_reach_a_multi_word_signal() throws Exception {
        assertEquals(50 + 8, score(request("what should I collect", PUMP_SIGNAL), pumpTopic("Collect evidence")));
        assertEquals(25 + 8, score(request("low pressure collect"),
                topic("t", "Collect evidence", List.of("low pressure"), List.of(PUMP_SIGNAL))));
        assertEquals(35 + 16, score(request("low pressure"), pumpTopic("Low pressure on a pump")));
        assertEquals(List.of(), lane(noManifest(), request("what should I collect", "low pressure"),
                pumpTopic("Collect evidence")), "a request signal must equal the name");
    }

    @Test void a_missing_or_inflected_signal_word_earns_no_signal_points() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("pump low pressure collect"), pumpTopic("Collect evidence")));
        assertEquals(List.of(), lane(noManifest(), request("pump showing low pressure collect"), pumpTopic("Collect evidence")));
    }

    @Test void a_multi_word_signal_earns_once_each_however_often_it_repeats() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("pump shows low pressure pump shows low pressure"),
                pumpTopic("Other")), "repeated query words: 25 once, not 50");
        assertEquals(List.of(), lane(noManifest(), request(PUMP_QUESTION),
                topic("t", "Other", List.of(), List.of(PUMP_SIGNAL, "Pump Shows Low Pressure"))),
                "the same signal twice in another case: 25 once, not 50");
        assertEquals(25 + 25, score(request(PUMP_QUESTION),
                topic("t", "Other", List.of(), List.of(PUMP_SIGNAL, "low pressure"))), "two different signals earn each");
    }

    @Test void a_multi_word_signal_needs_a_task_word_beside_asset_identity() throws Exception {
        assertEquals(List.of(), lane(family(), request("family turbine status"),
                topic("t", "Status report", List.of(), List.of("family turbine"))), "identity-only signal");
        DocumentKnowledgeChunk mixed = topic("t", "Collect evidence", List.of(), List.of("turbine vibration"));
        assertEquals(List.of("t"), lane(family(), request("turbine vibration collect"), mixed));
        assertEquals(List.of(), lane(family(), request("turbine collect"), mixed), "the task word is missing");
    }

    @Test void a_single_token_signal_keeps_equality_and_the_prefix_rule() throws Exception {
        assertEquals(25 + 8, score(request("pumps status"), topic("t", "Pump status", List.of(), List.of("pump"))));
        assertEquals(25 + 8, score(request("pump"), topic("t", "Pump handling", List.of(), List.of("pump"))));
        assertEquals(25 + 8, score(request("valve check"), topic("t", "Valve", List.of(), List.of("valve A"))),
                "one word survives the tokenizer");
        assertEquals(25 + 8, score(request("pump priming"), topic("t", "Pump handling", List.of(), List.of("PUMP 压力低"))),
                "one Latin word beside a non-Latin part");
    }

    @Test void asset_identity_cannot_qualify_a_topic() throws Exception {
        assertEquals(List.of(), lane(family(), request("Family Turbine control panel"),
                topic("tag", "Replace the control panel", List.of("family turbine"), List.of())), "identity-only tag");
        assertEquals(List.of(), lane(family(), request("turbine status"),
                topic("prefix", "Turbine", List.of(), List.of("turbinevibration"))), "asset query token, signal prefix");
        assertEquals(List.of(), lane(family(), request("turbines status"),
                topic("signal", "Turbines", List.of(), List.of("turbine"))), "identity-only signal, signal-token branch");
        assertEquals(List.of(), lane(family(), request("status", "Family Turbine"),
                topic("signal", "Status", List.of(), List.of("family turbine"))), "identity-only signal, equality branch");
        assertEquals(List.of(), lane(family(), request("Family Turbine"),
                topic("phrase", "Family Turbine maintenance", List.of(), List.of())), "identity-only query in a heading");
    }

    @Test void a_task_word_keeps_a_topic_with_family_words() throws Exception {
        assertEquals(List.of("phrase"), lane(family(), request("Family Turbine maintenance"),
                topic("phrase", "Family Turbine maintenance", List.of(), List.of())));
        assertEquals(List.of("tag"), lane(family(), request("Family Turbine internal control"),
                topic("tag", "Internal control", List.of("family turbine", "internal control"), List.of())));
        assertEquals(List.of("mixed"), lane(family(), request("turbine trip record"),
                topic("mixed", "Turbine trip", List.of("turbine trip"), List.of())), "a tag with one task token");
    }

    @Test void a_missing_manifest_removes_nothing() throws Exception {
        assertEquals(List.of("t"), lane(noManifest(), request("Family Turbine status"),
                topic("t", "Family Turbine", List.of("family turbine"), List.of())));
    }

    @Test void invalid_ids_are_out_of_the_lane_before_capping() throws Exception {
        String overlong = "t".repeat(257);
        DocumentKnowledgeChunk first = topic(overlong, "Collect logs", List.of("collect logs"), List.of("logs"));
        DocumentKnowledgeChunk second = topic("a/b", "Collect logs", List.of("collect logs"), List.of("logs"));
        DocumentKnowledgeChunk third = row("doc/x", "c", "topic", "Collect logs", List.of("collect logs"), List.of(), "", "");
        DocumentKnowledgeChunk valid = topic("valid", "Other", List.of("collect logs"), List.of());
        DocumentKnowledgeChunk alsoValid = topic("also-valid", "Logs", List.of("collect logs"), List.of());
        ObjectNode args = MAPPER.createObjectNode().put("query", "collect logs");
        args.putArray("documentIds").add(DOC).add("doc/x");
        assertEquals(List.of("also-valid"), lane(noManifest(), DocumentKnowledgeSearchScorer.SearchRequest.from(args),
                first, second, third, valid, alsoValid), "invalid rows outscore these and take no slot; valid is below the floor");
        assertEquals(List.of(), lane(noManifest(), request("collect logs"), first, second));
    }

    @Test void order_is_score_then_chunk_id_then_doc_id_and_a_third_is_cut() throws Exception {
        DocumentKnowledgeChunk low = topic("a-low", "Logs", List.of("collect logs"), List.of());
        DocumentKnowledgeChunk high = topic("z-high", "Logs", List.of("collect logs", "logs"), List.of());
        DocumentKnowledgeChunk tied = topic("b-tied", "Logs", List.of("collect logs"), List.of());
        assertEquals(List.of("z-high", "a-low"), lane(noManifest(), request("collect logs"), low, tied, high));

        DocumentKnowledgeChunk inDocB = row("doc-b", "same", "topic", "Logs", List.of("collect logs"), List.of(), "", "");
        DocumentKnowledgeChunk inDocA = row("doc-a", "same", "topic", "Logs", List.of("collect logs"), List.of(), "", "");
        ObjectNode args = MAPPER.createObjectNode().put("query", "collect logs");
        args.putArray("documentIds").add("doc-b").add("doc-a");
        assertEquals(List.of("doc-a", "doc-b"), DocumentKnowledgeSearchScorer.topicLane(List.of(inDocB, inDocA),
                noManifest(), DocumentKnowledgeSearchScorer.SearchRequest.from(args), null).stream()
                .map(match -> match.chunk().docId()).toList());
    }

    @Test void only_the_exact_topic_content_type_is_a_candidate() throws Exception {
        assertEquals(List.of(), lane(noManifest(), request("collect logs"),
                row(DOC, "t", "Topic", "Logs", List.of("collect logs"), List.of(), "", ""),
                row(DOC, "p", "page", "Logs", List.of("collect logs"), List.of(), "", "")));
    }

    @Test void a_chinese_query_matches_by_token_equality_or_signal_prefix_only() throws Exception {
        String query = "发电机故障";
        assertEquals(List.of("t"), lane(noManifest(), request(query), topic("t", query, List.of(query), List.of())),
                "whole-token tag equality");
        assertEquals(List.of("t"), lane(noManifest(), request("", query), topic("t", "Other", List.of(), List.of(query))),
                "signal equality reaches the floor by itself");
        assertEquals(List.of("t"), lane(noManifest(), request(query), topic("t", query, List.of(), List.of("发电机故障诊断"))),
                "length-qualified signal prefix");
        assertEquals(List.of(), lane(noManifest(), request(query),
                topic("t", query, List.of("发电机"), List.of("备用发电机故障记录"))), "non-prefix overlap");
    }

    // ---- production runtime on the fixture packages ----

    private static JsonNode search(String args) throws Exception {
        return MAPPER.readTree(rawSearch(args));
    }

    private static String rawSearch(String args) throws Exception {
        return DocumentKnowledgeRuntime.executeSearchDocumentChunks(new ToolCall("s", "search_document_chunks", args),
                TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT);
    }

    private static List<String> ids(JsonNode rows) {
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.path("chunkId").asText()));
        return ids;
    }

    private static List<String> fields(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static DocumentKnowledgeIndex index() throws Exception {
        return DocumentKnowledgeIndexCache.getOrLoad(TopicTestFixtures.SETTINGS, TopicTestFixtures.AGENT,
                org.slf4j.LoggerFactory.getLogger(TopicLaneTest.class)).index();
    }

    @Test void the_topic_reaches_the_lane_when_the_manuals_identity_fills_matches() throws Exception {
        for (String arguments : List.of(
                "{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":3}",
                "{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":10}",
                "{\"query\":\"Family Turbine Internal Control failing collect logs\"," + SCOPE + ",\"limit\":3}")) {
            JsonNode result = search(arguments);
            assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(result.path("topics")), arguments);
            assertFalse(ids(result.path("matches")).contains(TopicRound3Fixtures.TOPIC), "the section 1 failure: " + arguments);
        }
    }

    @Test void the_lane_leaves_matches_as_the_ranking_produced_them() throws Exception {
        String arguments = "{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":3}";
        JsonNode result = search(arguments);
        List<String> ranked = DocumentKnowledgeSearchScorer.scoreAndRank(index().searchableChunks(), index()::manifestFor,
                DocumentKnowledgeSearchScorer.SearchRequest.from(MAPPER.readTree(arguments)), 3).stream()
                .map(match -> match.chunk().chunkId()).toList();
        assertEquals(ranked, ids(result.path("matches")));
    }

    @Test void the_lane_follows_the_documents_of_the_request() throws Exception {
        String article = "\"documentIds\":[\"" + TopicRound3Fixtures.ARTICLE + "\"]";
        String manual = "\"documentIds\":[\"" + TopicRound3Fixtures.MANUAL + "\"]";
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(search("{" + FAILING_QUERY + "," + article + "}").path("topics")));
        assertFalse(search("{" + FAILING_QUERY + "," + manual + "}").has("topics"), "ids that exclude the article");
        assertFalse(search("{" + FAILING_QUERY + "}").has("topics"), "no ids at all: never the whole index");

        JsonNode unknown = search("{" + FAILING_QUERY + ",\"documentIds\":[\"nope\"]}");
        assertFalse(unknown.has("topics"), "unknown ids: not all documents");
        assertEquals(0, unknown.path("matches").size());
        assertEquals("documentIds-filter", unknown.path("selectionMode").asText());
    }

    @Test void an_invalid_id_stays_in_matches_and_out_of_the_lane() throws Exception {
        DocumentKnowledgeIndexCache.clearForTests();
        TopicRound3Fixtures fixtures = new TopicRound3Fixtures();
        fixtures.extraArticleRow = "{\"docId\":\"short-article\",\"chunkId\":\"bad/topic\",\"contentType\":\"topic\","
                + "\"heading\":\"Collect logs for an Internal Control failure\","
                + "\"tags\":[\"internal control\",\"collect logs\",\"failing\"],\"pageStart\":1,\"pageEnd\":1}\n";
        DocumentKnowledgeIndexCache.readerForTests = settings -> fixtures;
        JsonNode result = search("{" + FAILING_QUERY + ",\"documentIds\":[\"" + TopicRound3Fixtures.ARTICLE + "\"]}");
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(result.path("topics")));
        assertTrue(ids(result.path("matches")).contains("bad/topic"), "matches keeps today's behaviour");
    }

    @Test void the_injected_scope_defaults_and_bounds_the_lane() throws Exception {
        AgentToolContext.setInjectedDocumentScope(List.of(TopicRound3Fixtures.ARTICLE), "custom", DocumentScopeOrigin.CONVERSATION);
        JsonNode scoped = search("{" + FAILING_QUERY + "}");
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(scoped.path("topics")));
        assertEquals("load_topic", scoped.path("topics").get(0).path("loadWith").asText());
        assertFalse(search("{" + FAILING_QUERY + ",\"documentIds\":[]}").has("topics"), "[] replaces the scope");

        AgentToolContext.setInjectedDocumentScope(List.of(TopicRound3Fixtures.MANUAL), "custom", DocumentScopeOrigin.CONVERSATION);
        JsonNode outside = search("{" + FAILING_QUERY + ",\"documentIds\":[\"" + TopicRound3Fixtures.ARTICLE + "\"]}");
        assertFalse(outside.has("topics"), "load_topic would refuse it");
        assertTrue(ids(outside.path("matches")).contains(TopicRound3Fixtures.TOPIC), "matches is not scoped by the lane rule");
    }

    @Test void the_lane_ignores_document_types_and_matches_does_not() throws Exception {
        JsonNode result = search("{" + FAILING_QUERY + "," + SCOPE + ",\"documentTypes\":[\"operations_manual\"]}");
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(result.path("topics")));
        assertFalse(result.path("selectedDocIds").toString().contains(TopicRound3Fixtures.ARTICLE));
        assertFalse(ids(result.path("matches")).contains(TopicRound3Fixtures.TOPIC));
    }

    @Test void distractors_stay_out_with_family_words_in_the_query() throws Exception {
        JsonNode result = search("{\"query\":\"Family Turbine control panel internal control failing\"," + SCOPE + "}");
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(result.path("topics")));
    }

    @Test void a_search_without_query_or_signals_has_no_lane() throws Exception {
        assertFalse(search("{" + HINT + "," + SCOPE + "}").has("topics"));
    }

    @Test void a_lane_failure_omits_topics_and_nothing_else() {
        assertEquals(List.of(), DocumentKnowledgeRuntime.buildTopicLane(() -> {
            throw new IllegalStateException("lane");
        }));
    }

    // ---- P13 shape and egress ----

    @Test void topics_has_the_six_projected_fields_and_precedes_matches() throws Exception {
        JsonNode result = search("{" + FAILING_QUERY + "," + HINT + "," + SCOPE + "}");
        assertEquals(List.of("docId", "chunkId", "contentType", "heading", "score", "loadWith"), fields(result.path("topics").get(0)));
        List<String> top = fields(result);
        assertEquals(top.indexOf("degraded") + 1, top.indexOf("topics"));
        assertEquals(top.indexOf("topics") + 1, top.indexOf("matches"));
        JsonNode entry = result.path("topics").get(0);
        assertEquals(TopicRound3Fixtures.ARTICLE, entry.path("docId").asText());
        assertEquals("topic", entry.path("contentType").asText());
        assertEquals("load_topic", entry.path("loadWith").asText());
    }

    @Test void a_search_without_a_qualifying_topic_keeps_todays_fields() throws Exception {
        JsonNode result = search("{\"query\":\"internal control\",\"documentIds\":[\"" + TopicRound3Fixtures.MANUAL + "\"]}");
        assertEquals(List.of("status", "degraded", "matches", "warnings", "searchedDocuments", "searchedChunks",
                "skippedDocuments", "skippedChunks", "documentScores", "selectedDocIds", "selectionMode"), fields(result));
    }

    @Test void a_long_heading_is_cut_without_splitting_a_pair_and_an_absent_one_is_empty() {
        String emoji = "😀";
        String split = "h".repeat(79) + emoji + "tail";
        DocumentKnowledgeChunk atBoundary = row(DOC, "t", "topic", split, List.of(), List.of(), "", "");
        DocumentKnowledgeChunk noHeading = row(DOC, "u", "topic", null, List.of(), List.of(), "", "");
        List<Map<String, Object>> entries = DocumentKnowledgeRuntime.buildTopicLane(() -> List.of(
                new DocumentKnowledgeSearchScorer.ScoredMatch(atBoundary, 40),
                new DocumentKnowledgeSearchScorer.ScoredMatch(noHeading, 30)));
        assertEquals("h".repeat(79), entries.get(0).get("heading"));
        assertEquals("", entries.get(1).get("heading"));
    }

    private static ObjectNode withMaximumLengthTopics(String resultJson) throws Exception {
        String longDoc = "d".repeat(256);
        DocumentKnowledgeChunk first = row(longDoc, "a" + "x".repeat(255), "topic", "Collect logs", List.of("collect logs"), List.of(), "", "");
        DocumentKnowledgeChunk second = row(longDoc, "b" + "x".repeat(255), "topic", "Collect logs", List.of("collect logs"), List.of(), "", "");
        ObjectNode args = MAPPER.createObjectNode().put("query", "collect logs");
        args.putArray("documentIds").add(longDoc);
        List<Map<String, Object>> entries = DocumentKnowledgeRuntime.buildTopicLane(() -> DocumentKnowledgeSearchScorer.topicLane(
                List.of(first, second), noManifest(), DocumentKnowledgeSearchScorer.SearchRequest.from(args), null));
        assertEquals(2, entries.size());
        ObjectNode result = (ObjectNode) MAPPER.readTree(resultJson);
        ObjectNode ordered = MAPPER.createObjectNode();
        for (Iterator<String> it = result.fieldNames(); it.hasNext(); ) {
            String name = it.next();
            ordered.set(name, "topics".equals(name) ? MAPPER.valueToTree(entries) : result.get(name));
        }
        return ordered;
    }

    private static void assertTopicsSurvive(ObjectNode raw, String model) throws Exception {
        JsonNode copy = MAPPER.readTree(model);
        assertEquals(raw.path("topics"), copy.path("topics"), "exact references, not excerpts");
    }

    private static String egress(String raw) {
        return ToolResultEgressGateway.compactForLlmAppend("search_document_chunks", "c", raw, null).getLlmContent();
    }

    @Test void topics_survive_every_egress_pass_with_exact_maximum_length_references() throws Exception {
        String args = "{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":10}";
        JsonNode unreduced = search("{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":1}");
        ObjectNode small = withMaximumLengthTopics(unreduced.toString());
        assertTrue(small.toString().length() <= 8_192, "pass 1 fixture");
        assertTopicsSurvive(small, egress(small.toString()));

        ObjectNode tenPages = withMaximumLengthTopics(rawSearch(args));
        tenPages.path("matches").forEach(match -> ((ObjectNode) match).put("snippet", "s".repeat(1_000)));
        assertTrue(tenPages.toString().length() > 8_192, "pass 2 fixture, the size of the observed result");
        JsonNode second = MAPPER.readTree(egress(tenPages.toString()));
        assertEquals(10, tenPages.path("matches").size());
        assertEquals(5, second.path("matches").size(), "the observed reduction");
        assertTopicsSurvive(tenPages, second.toString());

        ObjectNode huge = withMaximumLengthTopics(rawSearch(args));
        ArrayNode scores = huge.putArray("documentScores");
        for (int i = 0; i < 5; i++) {
            ArrayNode evidence = scores.addObject().put("docId", "d" + i).putArray("matchedEvidence");
            for (int j = 0; j < 5; j++) {
                evidence.add("e".repeat(512));
            }
        }
        JsonNode third = MAPPER.readTree(egress(huge.toString()));
        assertTrue(third.path("_egress").path("reducedFields").has("topics"), "only the last resort marks every array");
        assertTopicsSurvive(huge, third.toString());
    }

    @Test void the_runtime_lane_survives_the_observed_reduction_with_usable_references() throws Exception {
        String raw = rawSearch("{" + FAILING_QUERY + "," + HINT + "," + SCOPE + ",\"limit\":10}");
        JsonNode model = MAPPER.readTree(egress(raw));
        JsonNode entry = model.path("topics").get(0);
        assertEquals(TopicRound3Fixtures.ARTICLE, entry.path("docId").asText());
        assertEquals(TopicRound3Fixtures.TOPIC, entry.path("chunkId").asText());
        assertEquals("load_topic", entry.path("loadWith").asText());
    }

    // ---- P15 instructions ----

    @SuppressWarnings("unchecked")
    private static String parameterDescription(String name) {
        Map<String, Object> properties = (Map<String, Object>)
                DocumentKnowledgeToolSchemas.searchDocumentChunksDef().getParametersSchema().get("properties");
        return (String) ((Map<String, Object>) properties.get(name)).get("description");
    }

    @Test void the_asset_context_description_says_how_a_search_scopes_to_a_unit() {
        assertEquals("Optional asset context such as asset model, component, or document type hints. To scope searches "
                + "in this step to one unit's documents, put that unit's canonical Thing name in thingName; it is used "
                + "only if it names a visible unit that has documents. To keep a unit across the conversation, call "
                + "resolve_thing.", parameterDescription("assetContext"));
    }

    @Test void the_three_surfaces_carry_the_conditional_wording() {
        String search = DocumentKnowledgeToolSchemas.searchDocumentChunksDef().getDescription();
        String resolve = DocumentKnowledgeToolSchemas.resolveDocumentSetDef().getDescription();
        String ids = parameterDescription("documentIds");
        assertTrue(search.contains("When a search result carries documentScopeSource, the runtime already scoped the search "
                + "to the unit: omit documentIds. Otherwise pass the ids of a complete resolve_document_set result as "
                + "documentIds. A resolve_document_set result marked sampleOnly or rowsOmitted lists only part of the set: "
                + "do not pass it; omit documentIds, which uses the runtime scope when there is one and otherwise searches "
                + "all documents."));
        assertTrue(search.endsWith("A non-empty topics array lists methods that match the request whatever the document "
                + "ranking: load the first relevant one with load_topic."));
        assertEquals("Optional explicit document id filter. Omit it when a search result carried documentScopeSource. "
                + "Otherwise pass the documents[].documentId values of a complete resolve_document_set result to scope "
                + "this search to that set (selectionMode documentIds-filter); a result marked sampleOnly or rowsOmitted "
                + "is not complete. Any value here, including an empty array, replaces the runtime scope.", ids);
        assertTrue(parameterDescription("documentTypes").endsWith("Applies to matches, not to topics."));
        assertTrue(resolve.contains("When a search result already carried documentScopeSource, the runtime has scoped "
                + "search to the unit and this call is not needed for scope. When documents is non-empty and the result "
                + "is not marked sampleOnly or rowsOmitted, pass documents[].documentId as the documentIds argument to "
                + "search_document_chunks. A marked result lists only part of the set: do not pass it; omit documentIds."));
        for (String surface : List.of(search, ids, resolve)) {
            assertFalse(surface.contains("pass those ids"), surface);
            assertFalse(surface.contains("pass those documents"), surface);
            assertFalse(surface.contains("When documents is non-empty, pass"), surface);
        }
    }

    // ---- P16 type filter ----

    private static JsonNode typeWarning(JsonNode result) {
        for (JsonNode warning : result.path("warnings")) {
            if ("DOCUMENT_TYPE_FILTER_EMPTY".equals(warning.path("code").asText())) {
                return warning;
            }
        }
        return null;
    }

    @Test void a_filter_that_removes_every_document_says_so() throws Exception {
        JsonNode result = search("{" + FAILING_QUERY + ",\"documentTypes\":[\"wrong_type\"]}");
        assertEquals("empty", result.path("selectionMode").asText());
        assertEquals("documentTypes filter [wrong_type] matched no document; available types: [article, operations_manual]",
                typeWarning(result).path("message").asText());
    }

    @Test void the_available_types_are_those_of_the_searched_documents() throws Exception {
        JsonNode result = search("{" + FAILING_QUERY + ",\"documentTypes\":[\"wrong_type\"],\"documentIds\":[\""
                + TopicRound3Fixtures.ARTICLE + "\"]}");
        assertTrue(typeWarning(result).path("message").asText().endsWith("available types: [article]"));
        assertEquals(List.of(TopicRound3Fixtures.TOPIC), ids(result.path("topics")), "the lane has its own rules");
    }

    @Test void at_most_ten_distinct_types_are_listed() {
        List<DocumentKnowledgeChunk> chunks = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            chunks.add(row("doc-" + i, "c", "page", "h", List.of(), List.of(), "", ""));
        }
        Function<String, Optional<DocumentKnowledgePackageManifest>> typed = doc -> Optional.of(
                DocumentKnowledgePackageManifest.parse("{\"contractVersion\":\"0.1\",\"docId\":\"" + doc + "\",\"title\":\"t\","
                        + "\"sourcePath\":\"s\",\"markdownPath\":\"m\",\"chunksPath\":\"c\",\"pageCount\":1,"
                        + "\"sourceRepository\":\"r\",\"sourceRepositoryPath\":\"p\",\"sourceHref\":\"h\","
                        + "\"sourceSha256\":\"0\",\"convertedAt\":\"d\",\"documentType\":\"type-" + doc + "\"}").manifest());
        ObjectNode args = MAPPER.createObjectNode().put("query", "q");
        args.putArray("documentTypes").add("wrong");
        assertEquals(10, DocumentKnowledgeSearchScorer.typesAvailableToEmptyFilter(chunks, typed,
                DocumentKnowledgeSearchScorer.SearchRequest.from(args)).orElseThrow().size());
    }

    @Test void a_filter_that_matches_or_unknown_ids_add_no_warning() throws Exception {
        assertEquals(null, typeWarning(search("{" + FAILING_QUERY + ",\"documentTypes\":[\"article\"]}")));
        assertEquals(null, typeWarning(search("{" + FAILING_QUERY + ",\"documentTypes\":[\"wrong_type\"],\"documentIds\":[\"nope\"]}")),
                "the cause is the ids, not the filter");
        assertEquals(null, typeWarning(search("{" + FAILING_QUERY + "}")));
    }

    @Test void the_warning_survives_egress() throws Exception {
        String raw = rawSearch("{" + FAILING_QUERY + ",\"documentTypes\":[\"wrong_type\"]}");
        assertTrue(typeWarning(MAPPER.readTree(egress(raw))) != null);
    }
}
