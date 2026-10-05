package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

class DocumentKnowledgeSearchScorerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void chiller_query_ranks_troubleshooting_chunk_on_page_25() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                DocumentKnowledgeIndexTest.fernwickFixtureReader(), settings, Instant.now());

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"Chiller High Pressure Shutdown\"}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 5);

        assertTrue(matches.size() >= 1);
        DocumentKnowledgeChunk top = matches.get(0).chunk();
        assertEquals("troubleshooting", top.contentType());
        assertEquals(25, top.pageStart());
        assertTrue(top.heading().contains("Chiller High Pressure Shutdown"));
    }

    @Test
    void exact_alarm_signal_outranks_broad_page_chunk() throws Exception {
        DocumentKnowledgeChunk page = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"page-0001\",\"contentType\":\"page\","
                        + "\"heading\":\"Page 01\",\"pageStart\":1,\"pageEnd\":1,"
                        + "\"summary\":\"general page text about chiller\"}").orElseThrow();
        DocumentKnowledgeChunk trouble = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"trouble-bpr\",\"contentType\":\"troubleshooting\","
                        + "\"heading\":\"Chiller High Pressure Shutdown - back pressure regulator\","
                        + "\"pageStart\":25,\"pageEnd\":25,"
                        + "\"tags\":[\"chiller\",\"shutdown\"],"
                        + "\"signals\":[{\"kind\":\"alarm\",\"name\":\"Chiller High Pressure Shutdown\"}],"
                        + "\"summary\":\"Cause: the back pressure regulator was moved.\"}").orElseThrow();

        DocumentKnowledgePackageManifest manifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("fernwick-carbaq-ops-v2")).manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"signals\":[{\"kind\":\"alarm\",\"name\":\"Chiller High Pressure Shutdown\"}]}"));

        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                List.of(page, trouble),
                docId -> Optional.of(manifest),
                request,
                5);

        assertEquals(1, matches.size());
        assertEquals("trouble-bpr", matches.get(0).chunk().chunkId());
        assertTrue(matches.get(0).score() >= 50);
    }

    @Test
    void c2_signal_chunk_demoted_below_substantive_even_with_higher_raw_score() throws Exception {
        // C2 (document-retrieval-convergence): a heading/marker signal-* chunk that would otherwise
        // outrank substantive content on raw score must still be demoted below it in matches[]. The
        // signal heading matches more query tokens (higher score), but content wins on the
        // class-level primary sort key.
        DocumentKnowledgeChunk signal = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"signal-0080\","
                        + "\"contentType\":\"troubleshooting\",\"heading\":\"8 Trouble causes elimination bearing lube\","
                        + "\"pageStart\":80,\"pageEnd\":80,"
                        + "\"summary\":\"trouble causes elimination bearing lube\","
                        + "\"markdown\":\"trouble causes elimination bearing lube oil pressure vibration\"}").orElseThrow();
        DocumentKnowledgeChunk section = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"section-0080-part-a\","
                        + "\"contentType\":\"troubleshooting\",\"heading\":\"Troubles\","
                        + "\"pageStart\":80,\"pageEnd\":82,"
                        + "\"summary\":\"trouble table\",\"markdown\":\"trouble bearing\"}").orElseThrow();
        DocumentKnowledgePackageManifest manifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("rk-t-operating-manual-7318042"))
                .manifest();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"trouble causes elimination bearing lube\"}"));

        // The signal chunk's raw score is >= the section's (it matches more tokens) ...
        int signalScore = DocumentKnowledgeSearchScorer.scoreChunk(signal, manifest, request);
        int sectionScore = DocumentKnowledgeSearchScorer.scoreChunk(section, manifest, request);
        assertTrue(signalScore >= sectionScore,
                "precondition: signal heading scores at least as high as the section");

        // ... yet the substantive section ranks first.
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                List.of(signal, section), docId -> Optional.of(manifest), request, 5);
        assertEquals(2, matches.size());
        assertEquals("section-0080-part-a", matches.get(0).chunk().chunkId(),
                "substantive content must outrank the signal-* heading despite the signal's higher raw score");
        assertEquals("signal-0080", matches.get(1).chunk().chunkId());
    }

    @Test
    void limit_above_max_is_clamped() {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 3, 3, 400, 6_000);
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        int limit = DocumentKnowledgeSearchScorer.resolveEffectiveLimit(99, settings, warnings);
        assertEquals(3, limit);
        assertEquals("LIMIT_CLAMPED", warnings.toJsonList().get(0).get("code"));
    }

    @Test
    void limit_zero_is_clamped_with_warning() {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        int limit = DocumentKnowledgeSearchScorer.resolveEffectiveLimit(0, settings, warnings);
        assertEquals(5, limit);
        assertEquals("LIMIT_CLAMPED", warnings.toJsonList().get(0).get("code"));
    }

    @Test
    void limit_negative_is_clamped_with_warning() {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        int limit = DocumentKnowledgeSearchScorer.resolveEffectiveLimit(-3, settings, warnings);
        assertEquals(5, limit);
        assertEquals("LIMIT_CLAMPED", warnings.toJsonList().get(0).get("code"));
    }

    @Test
    void snippet_is_truncated_to_budget() throws Exception {
        DocumentKnowledgeChunk longSummary = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"d\",\"chunkId\":\"c1\",\"heading\":\"H\",\"pageStart\":1,\"pageEnd\":1,"
                        + "\"summary\":\"" + "x".repeat(500) + "\"}").orElseThrow();
        String snippet = DocumentKnowledgeSearchScorer.buildSnippet(longSummary, 400);
        assertEquals(400, snippet.length());
    }

    @Test
    void kbm_damage_query_outranks_bundle_master() throws Exception {
        DocumentKnowledgeIndex index = loadKbmAndMasterIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"coupling damaged before commissioning may not be put into operation\"}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 10);

        assertTrue(matches.size() >= 2);
        assertEquals("kbm-coupling-manual-7318042", matches.get(0).chunk().docId());

        List<DocumentKnowledgeSearchScorer.ScoredMatch> wider = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 15);
        boolean kbmDamageInTop15 = wider.stream()
                .limit(15)
                .anyMatch(m -> "kbm-coupling-manual-7318042".equals(m.chunk().docId())
                        && ("page-0006".equals(m.chunk().chunkId())
                                || "signal-0006".equals(m.chunk().chunkId())));
        assertTrue(kbmDamageInTop15,
                "expected KBM page-0006 or signal-0006 (page-6 damage ban) in top-15 for damage query");
    }

    @Test
    void bundle_only_accessory_topic_stays_retrievable_from_the_bundle() throws Exception {
        DocumentKnowledgeIndex index = loadKbmAndMasterIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"lube oil purifier bowl cleaning interval\"}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 10);

        assertFalse(matches.isEmpty(), "expected matches for a topic that only the bundle contains");
        assertTrue(matches.stream().anyMatch(m -> "rk-t-turbogenerator-master-7318042".equals(m.chunk().docId())
                        && m.chunk().markdown().toLowerCase(Locale.ROOT).contains("purifier")),
                "bundle-only content must stay in the top-k despite the bundle penalty");
    }

    @Test
    void kbm_misalignment_query_includes_page_0009_in_top_k() throws Exception {
        DocumentKnowledgeIndex index = loadKbmAndMasterIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"shaft misalignment compensation nominal size tolerance Kr Kw\"}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 10);

        assertTrue(topKChunkIds(matches, 10).contains("page-0009"),
                "expected page-0009 in top-k for misalignment/tolerance query");
        assertEquals("kbm-coupling-manual-7318042",
                matches.stream()
                        .filter(m -> "page-0009".equals(m.chunk().chunkId()))
                        .findFirst()
                        .orElseThrow()
                        .chunk()
                        .docId());
    }

    @Test
    void doc_id_title_token_affinity_boosts_matching_package_without_hard_coded_doc_id() throws Exception {
        DocumentKnowledgeChunk kbmChunk = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"kbm-coupling-manual-7318042\",\"chunkId\":\"page-0001\","
                        + "\"heading\":\"Coupling overview\",\"pageStart\":1,\"pageEnd\":1,"
                        + "\"summary\":\"coupling overview\"}").orElseThrow();
        DocumentKnowledgeChunk otherChunk = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"rk-t-operating-manual-7318042\",\"chunkId\":\"page-0001\","
                        + "\"heading\":\"Turbine overview\",\"pageStart\":1,\"pageEnd\":1,"
                        + "\"summary\":\"turbine overview\"}").orElseThrow();
        DocumentKnowledgePackageManifest kbmManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("kbm-coupling-manual-7318042")
                        .replace("\"Operating Manual fixture\"", "\"KBM Flexible Pin Type Coupling Manual\""))
                .manifest();
        DocumentKnowledgePackageManifest otherManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("rk-t-operating-manual-7318042"))
                .manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"coupling commissioning guidance\"}"));

        int kbmScore = DocumentKnowledgeSearchScorer.scoreChunk(kbmChunk, kbmManifest, request);
        int otherScore = DocumentKnowledgeSearchScorer.scoreChunk(otherChunk, otherManifest, request);
        assertTrue(kbmScore > otherScore,
                "expected generic docId/title token affinity to favor the coupling manual");
    }

    @Test
    void kbm_live_shaped_damage_query_surfaces_damage_carrier_in_top_k() throws Exception {
        DocumentKnowledgeIndex index = loadKbmAndMasterIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree(
                        "{\"query\":\"KBM flexible pin type coupling damaged before commissioning inspection\","
                                + "\"signals\":[{\"kind\":\"symptom\",\"name\":\"coupling damaged\"}]}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 15);

        assertTrue(matches.size() >= 2);
        assertEquals("kbm-coupling-manual-7318042", matches.get(0).chunk().docId());

        Set<String> damageCarriers = Set.of(
                "page-0006",
                "signal-0006",
                "section-0006-damage-before-operation");
        assertTrue(
                damageCarriers.contains(matches.get(0).chunk().chunkId()),
                "expected damage carrier as top hit for live-shaped damage query, got "
                        + matches.get(0).chunk().chunkId());
        assertTrue(
                matches.stream().limit(5).noneMatch(m -> "rk-t-operating-manual-7318042".equals(m.chunk().docId())),
                "RK&T operating manual should not outrank KBM damage carrier when query names KBM");
    }

    @Test
    void kbm_natural_damage_query_surfaces_damage_carrier_in_top_k() throws Exception {
        DocumentKnowledgeIndex index = loadKbmAndMasterIndex();
        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree(
                        "{\"query\":\"KBM flexible pin coupling damaged before commissioning misalignment\"}"));
        List<DocumentKnowledgeSearchScorer.ScoredMatch> matches = DocumentKnowledgeSearchScorer.scoreAndRank(
                index.searchableChunks(), index::manifestFor, request, 15);

        assertTrue(matches.size() >= 2);
        assertEquals("kbm-coupling-manual-7318042", matches.get(0).chunk().docId());

        boolean damageCarrierInTop10 = matches.stream()
                .limit(10)
                .anyMatch(m -> "kbm-coupling-manual-7318042".equals(m.chunk().docId())
                        && ("page-0006".equals(m.chunk().chunkId())
                                || "signal-0006".equals(m.chunk().chunkId())
                                || "section-0006-damage-before-operation".equals(m.chunk().chunkId())));
        assertTrue(damageCarrierInTop10,
                "expected KBM page-0006 or signal-0006 in top-10 for natural damage-before-commissioning query");

        assertTrue(
                Set.of("page-0006", "signal-0006", "section-0006-damage-before-operation")
                        .contains(matches.get(0).chunk().chunkId()),
                "damage carrier should outrank commissioning-only chunks for damage+commissioning query");
    }

    @Test
    void bundle_role_manifest_receives_score_penalty() throws Exception {
        DocumentKnowledgeChunk chunk = DocumentKnowledgeChunk.parseJsonLine(
                "{\"docId\":\"bundle-doc\",\"chunkId\":\"c1\",\"heading\":\"coupling damaged commissioning\","
                        + "\"pageStart\":1,\"pageEnd\":1,"
                        + "\"summary\":\"coupling may be damaged before commissioning\","
                        + "\"markdown\":\"coupling may be damaged before commissioning may not be put into operation\"}"
        ).orElseThrow();
        DocumentKnowledgePackageManifest bundleManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("bundle-doc").replace(
                        "\"convertedAt\"", "\"documentRole\":\"bundle\",\"convertedAt\"")).manifest();
        DocumentKnowledgePackageManifest plainManifest = DocumentKnowledgePackageManifest.parse(
                DocumentKnowledgePackageManifestTest.contractValidManifestJson("plain-doc")).manifest();

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(
                MAPPER.readTree("{\"query\":\"coupling damaged before commissioning\"}"));

        int bundleScore = DocumentKnowledgeSearchScorer.scoreChunk(chunk, bundleManifest, request);
        int plainScore = DocumentKnowledgeSearchScorer.scoreChunk(chunk, plainManifest, request);
        assertTrue(plainScore > bundleScore);
        assertTrue(bundleScore >= 0);
    }

    private static Set<String> topKChunkIds(List<DocumentKnowledgeSearchScorer.ScoredMatch> matches, int k) {
        return matches.stream().limit(k).map(m -> m.chunk().chunkId()).collect(Collectors.toSet());
    }

    private static DocumentKnowledgeIndex loadKbmAndMasterIndex() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 10, 20, 400, 6_000);
        return DocumentKnowledgeIndex.load(kbmLiveIndexFixtureReader(), settings, Instant.now());
    }

    private static RepositoryReader kbmLiveIndexFixtureReader() {
        Path repoRoot = repoRoot();
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return multiDirectoryListing(
                            "kbm-coupling-manual-7318042",
                            "rk-t-install-spec-7318042",
                            "rk-t-operating-manual-7318042",
                            "rk-t-turbogenerator-master-7318042");
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (path.startsWith("/document-knowledge/rk-t-turbogenerator-master-7318042/")) {
                    Path file = repoRoot.resolve(
                            "dev_data/future_repo/_fallback/document-knowledge/rk-t-turbogenerator-master-7318042")
                            .resolve(path.substring("/document-knowledge/rk-t-turbogenerator-master-7318042/".length()));
                    if (Files.isRegularFile(file)) {
                        return Files.readString(file, StandardCharsets.UTF_8);
                    }
                }
                for (String docId : List.of(
                        "kbm-coupling-manual-7318042",
                        "rk-t-install-spec-7318042",
                        "rk-t-operating-manual-7318042")) {
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
