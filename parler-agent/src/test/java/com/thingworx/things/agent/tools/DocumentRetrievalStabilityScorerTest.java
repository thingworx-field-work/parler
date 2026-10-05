package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Acceptance fixtures for {@code docs/operations/document-retrieval-stability.md} §11.
 */
class DocumentRetrievalStabilityScorerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TROUBLE_CHUNK = "section-0080-part-a-08-trouble-causes-and-their-elimination";
    private static final List<String> OPERATING_TROUBLE_WEAK_PARAPHRASES = List.of(
            "what trips the turbine and how do I bring it back online",
            "steps before restarting after bearing temperature or vibration alarm",
            "how to recover the turbo-generator after an unplanned shutdown",
            "procedure when lube oil pressure dropped and restart is planned",
            "bearing heat and vibration during operation what to check first");

    @Test
    void search_response_includes_bounded_diagnostics() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"RK&T operating manual trouble before restarting\"}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 8);

        assertFalse(ranked.documentScores().isEmpty());
        assertFalse(ranked.selectedDocIds().isEmpty());
        assertTrue(Set.of("diversified", "hard-single", "documentIds-filter").contains(ranked.selectionMode()));
        ranked.documentScores().forEach(e ->
                assertTrue(e.matchedEvidence().size() <= 8));
    }

    @Test
    void identity_query_favors_rkt_operating_manual_over_fernwick() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"RK&T operating manual overspeed steam pressure before restart\"}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 10);

        assertFalse(ranked.matches().isEmpty());
        assertEquals("rk-t-operating-manual-7318042", ranked.matches().get(0).chunk().docId());
        assertFalse(ranked.matches().stream()
                .limit(3)
                .allMatch(m -> "fernwick-carbaq-ops-v2".equals(m.chunk().docId())));
    }

    @Test
    void calibration_invariant_identity_beats_wrong_doc_max_chunk_score() throws Exception {
        DocumentKnowledgeChunk fernwickTrouble = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"trouble-rich\",\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Chiller High Pressure Shutdown alarm trip\",\"pageStart\":25,\"pageEnd\":25,"
                        + "\"tags\":[\"shutdown\",\"alarm\",\"trouble\"],"
                        + "\"signals\":[{\"kind\":\"alarm\",\"name\":\"Chiller High Pressure Shutdown\"}],"
                        + "\"summary\":\"Probable causes and shutdown troubleshooting steps.\"}").orElseThrow();
        DocumentKnowledgeChunk rktTrouble = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"" + TROUBLE_CHUNK + "\","
                        + "\"contentType\":\"section\",\"heading\":\"Trouble, causes and their elimination\","
                        + "\"pageStart\":80,\"pageEnd\":82,\"summary\":\"Trouble shooting on the turbine.\"}")
                .orElseThrow();

        DocumentKnowledgePackageManifest fernwickManifest = DocumentKnowledgePackageManifest.parse(
                Files.readString(repoRoot().resolve(
                        "dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/manifest.json"),
                        StandardCharsets.UTF_8)).manifest();
        DocumentKnowledgePackageManifest rktManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("rk-t-operating-manual-7318042")
                        .replace("\"Test Document\"",
                                "\"RK&T Operating Manual — Turbo-generator Set CB 24 GT4 (7.318.042)\""))
                .manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"RK&T operating manual trouble before restarting\"}"));

        int wrongMax = DocumentKnowledgeSearchScorer.maxAchievableChunkScoreForDocument(
                List.of(fernwickTrouble), fernwickManifest, request);
        int rightChunkScore = DocumentKnowledgeSearchScorer.scoreChunk(rktTrouble, rktManifest, request);
        DocumentKnowledgeSearchScorer.DocumentScoreEntry rktDoc = DocumentKnowledgeSearchScorer
                .scoreAndRankWithDiagnostics(
                        List.of(rktTrouble, fernwickTrouble),
                        docId -> Optional.of(
                                "rk-t-operating-manual-7318042".equals(docId) ? rktManifest : fernwickManifest),
                        request,
                        5)
                .documentScores()
                .stream()
                .filter(e -> "rk-t-operating-manual-7318042".equals(e.docId()))
                .findFirst()
                .orElseThrow();
        assertTrue(rightChunkScore + rktDoc.score() > wrongMax,
                "right identity + chunk must beat wrong-doc max chunk score");
    }

    @Test
    void zero_document_score_doc_remains_chunk_eligible_when_other_doc_scores() throws Exception {
        DocumentKnowledgeChunk fernwickTrouble1 = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"e-trouble-1\","
                        + "\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Chiller trip shutdown alarm\",\"pageStart\":20,\"pageEnd\":20,"
                        + "\"tags\":[\"shutdown\",\"trip\"],"
                        + "\"summary\":\"general shutdown alarm guidance\"}").orElseThrow();
        DocumentKnowledgeChunk rktTrouble = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"k-trouble\","
                        + "\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Trouble causes and elimination before restart\","
                        + "\"pageStart\":80,\"pageEnd\":82,"
                        + "\"summary\":\"what trips the turbine and how to bring it back online\"}")
                .orElseThrow();

        String fernwickJson = Files.readString(repoRoot().resolve(
                "dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/manifest.json"),
                StandardCharsets.UTF_8).replace(
                "\"documentType\": \"operations_manual\"",
                "\"documentType\": \"operations_manual\","
                        + "\"documentProfile\":{\"aliases\":[],\"manufacturers\":[\"Fernwick Labs\"],"
                        + "\"assetModels\":[],\"documentKinds\":[\"operations_manual\"],"
                        + "\"domainTerms\":[\"online\"]}");
        DocumentKnowledgePackageManifest fernwickManifest = DocumentKnowledgePackageManifest.parse(fernwickJson)
                .manifest();
        DocumentKnowledgePackageManifest rktManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("rk-t-operating-manual-7318042"))
                .manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"what trips the turbine and how do I bring it back online\"}"));

        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                List.of(fernwickTrouble1, rktTrouble),
                docId -> Optional.of(
                        "rk-t-operating-manual-7318042".equals(docId) ? rktManifest : fernwickManifest),
                request,
                5);

        assertTrue(
                ranked.documentScores().stream().anyMatch(e -> "fernwick-carbaq-ops-v2".equals(e.docId())),
                "off-topic document should have positive document evidence");
        assertTrue(
                ranked.matches().stream()
                        .anyMatch(m -> "rk-t-operating-manual-7318042".equals(m.chunk().docId())),
                "zero-document-score doc with matching chunk evidence must remain reachable");
    }

    @Test
    void per_doc_cap_surfaces_right_doc_when_wrong_doc_has_high_chunk_scores() throws Exception {
        DocumentKnowledgeChunk fernwick1 = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"e1\",\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Turbine trip shutdown alarm trouble\",\"pageStart\":1,\"pageEnd\":1,"
                        + "\"tags\":[\"turbine\",\"shutdown\",\"trouble\"],"
                        + "\"signals\":[{\"kind\":\"alarm\",\"name\":\"trip\"}],"
                        + "\"summary\":\"turbine bearing vibration trouble shutdown trip\"}").orElseThrow();
        DocumentKnowledgeChunk fernwick2 = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"e2\",\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Shutdown vibration bearing alarm\",\"pageStart\":2,\"pageEnd\":2,"
                        + "\"tags\":[\"turbine\",\"vibration\",\"shutdown\"],"
                        + "\"summary\":\"turbine vibration bearing shutdown trouble\"}").orElseThrow();
        DocumentKnowledgeChunk fernwick3 = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"e3\",\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Alarm trouble trip bearing\",\"pageStart\":3,\"pageEnd\":3,"
                        + "\"tags\":[\"trouble\",\"bearing\"],"
                        + "\"summary\":\"bearing trouble trip turbine vibration\"}").orElseThrow();
        DocumentKnowledgeChunk rkt = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"k1\","
                        + "\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Trouble causes elimination restart\",\"pageStart\":80,\"pageEnd\":80,"
                        + "\"summary\":\"turbine bearing vibration trouble before restarting\"}").orElseThrow();

        String fernwickJson = Files.readString(repoRoot().resolve(
                "dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/manifest.json"),
                StandardCharsets.UTF_8).replace(
                "\"documentType\": \"operations_manual\"",
                "\"documentType\": \"operations_manual\","
                        + "\"documentProfile\":{\"aliases\":[],\"manufacturers\":[\"Fernwick Labs\"],"
                        + "\"assetModels\":[\"CarbaQ CO2 Capture Solution\",\"turbine\"],"
                        + "\"documentKinds\":[\"operations_manual\"],"
                        + "\"domainTerms\":[\"turbine\",\"shutdown\",\"trouble\"]}");
        DocumentKnowledgePackageManifest fernwickManifest = DocumentKnowledgePackageManifest.parse(fernwickJson)
                .manifest();
        DocumentKnowledgePackageManifest rktManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("rk-t-operating-manual-7318042"))
                .manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"turbine bearing vibration trouble shutdown trip\"}"));

        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                List.of(fernwick1, fernwick2, fernwick3, rkt),
                docId -> Optional.of("fernwick-carbaq-ops-v2".equals(docId) ? fernwickManifest : rktManifest),
                request,
                4);

        assertEquals(4, ranked.matches().size(), "diversify should fill to limit once diversity is satisfied");
        long fernwickSlots = ranked.matches().stream()
                .filter(m -> "fernwick-carbaq-ops-v2".equals(m.chunk().docId()))
                .count();
        assertTrue(fernwickSlots < 4,
                "per-doc cap must prevent one document from occupying the entire top-k");
        assertTrue(
                ranked.matches().stream().anyMatch(m -> "rk-t-operating-manual-7318042".equals(m.chunk().docId())),
                "primary pass must reserve at least one slot for the second plausible document");
    }

    @Test
    void stability_fixture_identity_variants_favor_rkt_operating_manual() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        List<String> identityQueries = List.of(
                "RK&T operating manual trouble overspeed steam pressure before restarting turbine",
                "Search the RK&T operating manual for bearing temperature vibration before restart");
        for (String query : identityQueries) {
            DocumentKnowledgeSearchScorer.RankResult ranked = rankOperatingTroubleQuery(index, query);
            assertEquals(
                    "rk-t-operating-manual-7318042",
                    ranked.matches().get(0).chunk().docId(),
                    () -> "identity query top doc: " + query + " selectionMode=" + ranked.selectionMode()
                            + " topDocs=" + topDocIds(ranked));
            assertNotFernwickOrInstallTopRanked(ranked, query);
        }
    }

    @Test
    void stability_fixture_weak_identity_paraphrases_surface_trouble_chunk_post_p2() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        Assumptions.assumeTrue(isPostP2Corpus(index),
                "§11.2 ≥4/5 weak-identity pass bar requires post-P2 corpus with trouble chapter typed troubleshooting (§7)");

        int surfaced = 0;
        List<String> failures = new ArrayList<>();
        for (String query : OPERATING_TROUBLE_WEAK_PARAPHRASES) {
            DocumentKnowledgeSearchScorer.RankResult ranked = rankOperatingTroubleQuery(index, query);
            if (ranked.matches().stream().anyMatch(m -> TROUBLE_CHUNK.equals(m.chunk().chunkId()))) {
                surfaced++;
            } else {
                failures.add(query + " -> topDocs=" + topDocIds(ranked)
                        + " selectionMode=" + ranked.selectionMode());
            }
        }
        assertTrue(surfaced >= 4,
                "expected >=4/5 weak paraphrases to surface " + TROUBLE_CHUNK + "; got " + surfaced
                        + "; failures: " + failures);
    }

    @Test
    void stability_fixture_weak_identity_paraphrases_do_not_top_rank_wrong_doc_family_post_p2() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        Assumptions.assumeTrue(isPostP2Corpus(index),
                "§11.2 wrong-doc-family bar requires post-P2 corpus (§7, §11.3 operating-vs-install)");

        List<String> failures = new ArrayList<>();
        for (String query : OPERATING_TROUBLE_WEAK_PARAPHRASES) {
            DocumentKnowledgeSearchScorer.RankResult ranked = rankOperatingTroubleQuery(index, query);
            if (ranked.matches().isEmpty()) {
                continue;
            }
            String topDoc = ranked.matches().get(0).chunk().docId();
            if ("fernwick-carbaq-ops-v2".equals(topDoc) || "rk-t-install-spec-7318042".equals(topDoc)) {
                failures.add(query + " -> topDoc=" + topDoc + " selectionMode=" + ranked.selectionMode()
                        + " topDocs=" + topDocIds(ranked));
            }
        }
        assertTrue(failures.isEmpty(),
                "§11.2: zero weak paraphrases may top-rank Fernwick or install spec; failures: " + failures);
    }

    private static boolean isPostP2Corpus(DocumentKnowledgeIndex index) {
        return index.findChunk("rk-t-operating-manual-7318042", TROUBLE_CHUNK)
                .map(c -> "troubleshooting".equalsIgnoreCase(c.contentType()))
                .orElse(false);
    }

    private static DocumentKnowledgeSearchScorer.RankResult rankOperatingTroubleQuery(
            DocumentKnowledgeIndex index,
            String query) throws Exception {
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"" + query.replace("\"", "\\\"") + "\"}"));
        return DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 10);
    }

    private static List<String> topDocIds(DocumentKnowledgeSearchScorer.RankResult ranked) {
        return ranked.matches().stream()
                .map(m -> m.chunk().docId())
                .distinct()
                .limit(5)
                .toList();
    }

    private static void assertNotFernwickOrInstallTopRanked(
            DocumentKnowledgeSearchScorer.RankResult ranked,
            String query) {
        if (ranked.matches().isEmpty()) {
            return;
        }
        String topDoc = ranked.matches().get(0).chunk().docId();
        assertFalse(
                "fernwick-carbaq-ops-v2".equals(topDoc) || "rk-t-install-spec-7318042".equals(topDoc),
                () -> "operating-trouble identity query must not top-rank Fernwick or install spec: " + query
                        + " topDoc=" + topDoc + " selectionMode=" + ranked.selectionMode());
    }

    @Test
    void adversarial_fixture_install_queries_favor_install_spec() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        List<String> installQueries = List.of(
                "RK&T installation foundation and turbine driven machine alignment",
                "RK&T start-up with back pressure below 6 bar",
                "RK&T start-up back pressure between 6 and 11 bar",
                "RK&T start-up back pressure higher than 11 bar");
        for (String query : installQueries) {
            DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                    MAPPER.readTree("{\"query\":\"" + query.replace("\"", "\\\"") + "\"}"));
            DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                    index.searchableChunks(), index::manifestFor, request, 8);
            assertFalse(ranked.matches().isEmpty(), () -> "no matches for install query: " + query);
            assertEquals(
                    "rk-t-install-spec-7318042",
                    ranked.matches().get(0).chunk().docId(),
                    () -> "install intent must top-rank install spec: " + query
                            + " selectionMode=" + ranked.selectionMode()
                            + " topDocs=" + topDocIds(ranked));
        }
    }

    @Test
    void fernwick_identity_query_still_favors_fernwick() throws Exception {
        DocumentKnowledgeIndex index = loadMultiDocIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"CarbaQ chiller high pressure shutdown\"}"));
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(), index::manifestFor, request, 6);
        assertFalse(ranked.matches().isEmpty());
        assertEquals("fernwick-carbaq-ops-v2", ranked.matches().get(0).chunk().docId());
    }

    private static DocumentKnowledgeIndex loadMultiDocIndex() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 10, 20, 400, 6_000);
        return DocumentKnowledgeIndex.load(multiDocFixtureReader(), settings, Instant.now());
    }

    private static RepositoryReader multiDocFixtureReader() {
        Path repoRoot = repoRoot();
        List<String> docIds = List.of(
                "fernwick-carbaq-ops-v2",
                "kbm-coupling-manual-7318042",
                "rk-t-install-spec-7318042",
                "rk-t-operating-manual-7318042");
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return multiDirectoryListing(docIds.toArray(new String[0]));
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                for (String docId : docIds) {
                    String prefix = "/document-knowledge/" + docId + "/";
                    if (path.startsWith(prefix)) {
                        Path file = repoRoot.resolve("dev_data/future_repo/document-knowledge")
                                .resolve(docId)
                                .resolve(path.substring(prefix.length()));
                        if (Files.isRegularFile(file)) {
                            return Files.readString(file, StandardCharsets.UTF_8);
                        }
                    }
                }
                throw new java.io.FileNotFoundException(path);
            }
        };
    }

    private static InfoTable multiDirectoryListing(String... dirNames) {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
        shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
        InfoTable it = new InfoTable(shape);
        for (String dir : dirNames) {
            ValueCollection vc = new ValueCollection();
            vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive(dir));
            vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
            it.addRow(vc);
        }
        return it;
    }

    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !dir.resolve("CONTRACTS/CONTRACT_VERSION.md").toFile().exists()) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found from cwd");
        }
        return dir;
    }
}
