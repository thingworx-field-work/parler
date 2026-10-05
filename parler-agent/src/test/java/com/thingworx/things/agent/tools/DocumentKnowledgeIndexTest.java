package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

class DocumentKnowledgeIndexTest {

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
    }

    @Test
    void loads_fernwick_fixture_package() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                fernwickFixtureReader(), settings, Instant.now());
        assertTrue(index.searchedDocuments() >= 1);
        assertTrue(index.searchedChunks() >= 40);
        assertFalse(index.degraded());
        assertTrue(index.findChunk("fernwick-carbaq-ops-v2", "page-0001").isPresent());
        assertTrue(index.manifestFor("fernwick-carbaq-ops-v2").isPresent());
        assertEquals("AIDocRepository", index.manifestFor("fernwick-carbaq-ops-v2").get().sourceRepository());
    }

    @Test
    void missing_root_emits_document_root_not_found() throws Exception {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.defaults();
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                emptyListingReader(), settings, Instant.now());
        assertEquals(0, index.searchedDocuments());
        assertTrue(index.degraded());
        assertEquals("DOCUMENT_ROOT_NOT_FOUND",
                index.warnings().toJsonList().get(0).get("code"));
    }

    @Test
    void invalid_manifest_shape_is_skipped_with_warning() throws Exception {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.defaults();
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                invalidShapeManifestReader(), settings, Instant.now());
        assertEquals(0, index.searchedDocuments());
        assertEquals("MANIFEST_INVALID_SHAPE",
                index.warnings().toJsonList().get(0).get("code"));
    }

    @Test
    void max_documents_cap_sets_degraded() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 1, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                twoPackageReader(), settings, Instant.now());
        assertEquals(1, index.searchedDocuments());
        assertTrue(index.degraded());
        assertEquals("INDEX_LIMIT_REACHED",
                index.warnings().toJsonList().stream()
                        .filter(w -> "INDEX_LIMIT_REACHED".equals(w.get("code")))
                        .findFirst()
                        .map(w -> w.get("code"))
                        .orElse(null));
    }

    static RepositoryReader fernwickFixtureReader() {
        Path packageDir = fernwickFixtureDir();
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return singleDirectoryListing("fernwick-carbaq-ops-v2");
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (path.startsWith("/document-knowledge/fernwick-carbaq-ops-v2/")) {
                    String rel = path.substring("/document-knowledge/fernwick-carbaq-ops-v2/".length());
                    Path file = packageDir.resolve(rel);
                    if (Files.isRegularFile(file)) {
                        return Files.readString(file, StandardCharsets.UTF_8);
                    }
                }
                throw new java.io.FileNotFoundException(path);
            }
        };
    }

    private static RepositoryReader invalidShapeManifestReader() {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return singleDirectoryListing("bad-pkg");
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                if (path.endsWith("/manifest.json")) {
                    return "{\"title\":\"missing docId and required fields\"}";
                }
                return null;
            }
        };
    }

    private static RepositoryReader emptyListingReader() {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                return new InfoTable();
            }

            @Override
            public String loadText(String path) {
                return null;
            }
        };
    }

    private static RepositoryReader twoPackageReader() {
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    return multiDirectoryListing("pkg-a", "pkg-b");
                }
                return new InfoTable();
            }

            @Override
            public String loadText(String path) throws Exception {
                if (path.endsWith("/manifest.json")) {
                    String docId = path.contains("pkg-a") ? "pkg-a" : "pkg-b";
                    return DocumentKnowledgePackageManifestTest.contractValidManifestJson(docId);
                }
                if (path.endsWith("/chunks/chunks.jsonl")) {
                    String docId = path.contains("pkg-a") ? "pkg-a" : "pkg-b";
                    return "{\"docId\":\"" + docId + "\",\"chunkId\":\"c1\",\"heading\":\"H\","
                            + "\"pageStart\":1,\"pageEnd\":1,\"markdown\":\"body\"}\n";
                }
                throw new java.io.FileNotFoundException(path);
            }
        };
    }

    private static InfoTable singleDirectoryListing(String dirName) {
        return multiDirectoryListing(dirName);
    }

    static InfoTable multiDirectoryListing(String... dirNames) {
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

    private static Path fernwickFixtureDir() {
        return repoRoot().resolve("dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2");
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
