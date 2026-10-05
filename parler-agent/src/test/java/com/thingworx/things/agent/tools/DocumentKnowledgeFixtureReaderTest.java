package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

class DocumentKnowledgeFixtureReaderTest {

    private static Path fernwickFixtureDir() {
        return repoRoot().resolve("dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2");
    }

    @Test
    void reads_manifest_and_chunks_from_repo_fixture() throws Exception {
        Path packageDir = fernwickFixtureDir();
        Optional<DocumentKnowledgePackageManifest> manifest = DocumentKnowledgeFixtureReader.readManifest(packageDir);
        assertTrue(manifest.isPresent());
        assertEquals("fernwick-carbaq-ops-v2", manifest.get().docId());
        assertEquals("AIDocRepository", manifest.get().sourceRepository());

        DocumentKnowledgeFixtureReader.ChunkLoadResult loaded =
                DocumentKnowledgeFixtureReader.readChunks(packageDir, manifest.get());
        assertTrue(loaded.chunks().size() >= 40);
        assertEquals(0, loaded.invalidLineCount());

        Optional<DocumentKnowledgeChunk> bpr = loaded.chunks().stream()
                .filter(c -> "troubleshooting-chiller-high-pressure-bpr".equals(c.chunkId()))
                .findFirst();
        assertTrue(bpr.isPresent());
        assertEquals(25, bpr.get().pageStart());
        assertEquals("troubleshooting", bpr.get().contentType());
        assertTrue(bpr.get().heading().contains("Chiller High Pressure Shutdown"));
    }

    @Test
    void invalid_jsonl_line_is_counted_not_indexed() throws Exception {
        Path temp = java.nio.file.Files.createTempDirectory("doc-knowledge-fixture");
        try {
            java.nio.file.Files.writeString(temp.resolve("manifest.json"),
                    DocumentKnowledgePackageManifestTest.contractValidManifestJson("test-doc") + "\n");
            java.nio.file.Files.createDirectories(temp.resolve("chunks"));
            java.nio.file.Files.writeString(temp.resolve("chunks/chunks.jsonl"),
                    "{\"docId\":\"test-doc\",\"chunkId\":\"good\",\"heading\":\"Good\",\"pageStart\":1,\"pageEnd\":1}\n"
                            + "not-json\n"
                            + "{\"docId\":\"test-doc\",\"chunkId\":\"also-good\",\"heading\":\"Also good\",\"pageStart\":2,\"pageEnd\":2}\n");
            DocumentKnowledgePackageManifest manifest =
                    DocumentKnowledgePackageManifest.parseJson(java.nio.file.Files.readString(temp.resolve("manifest.json")))
                            .orElseThrow();
            DocumentKnowledgeFixtureReader.ChunkLoadResult loaded =
                    DocumentKnowledgeFixtureReader.readChunks(temp, manifest);
            assertEquals(2, loaded.chunks().size());
            assertEquals(1, loaded.invalidLineCount());
        } finally {
            deleteRecursively(temp);
        }
    }

    @Test
    void invalid_manifest_json_is_rejected() {
        assertEquals(DocumentKnowledgePackageManifest.ParseStatus.INVALID_JSON,
                DocumentKnowledgePackageManifest.parse("{").status());
        assertEquals(DocumentKnowledgePackageManifest.ParseStatus.INVALID_SHAPE,
                DocumentKnowledgePackageManifest.parse("{\"title\":\"no docId\"}").status());
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

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !java.nio.file.Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    java.nio.file.Files.deleteIfExists(p);
                } catch (Exception ignored) {
                    // best effort temp cleanup
                }
            });
        }
    }
}
