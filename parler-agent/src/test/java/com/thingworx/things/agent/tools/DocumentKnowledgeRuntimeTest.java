package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.constants.CommonPropertyNames;
import com.thingworx.types.primitives.StringPrimitive;

class DocumentKnowledgeRuntimeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void tearDown() {
        DocumentKnowledgeIndexCache.clearForTests();
    }

    @Test
    void get_document_chunk_returns_indexed_chunk_with_manifest_links() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndexCache.loadForTests(settings, "DocKnowledgeTestAgent", fernwickFixtureReader());

        String json = DocumentKnowledgeRuntime.executeGetDocumentChunk(
                new ToolCall("g1", "get_document_chunk",
                        "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"page-0001\"}"),
                settings,
                "DocKnowledgeTestAgent");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertEquals("fernwick-carbaq-ops-v2", body.path("docId").asText());
        assertEquals("page-0001", body.path("chunkId").asText());
        assertTrue(body.path("markdown").asText().contains("Operations Manual"));
        JsonNode link = body.path("sourceLinks").get(0);
        assertEquals("AIDocRepository", link.path("repository").asText());
        assertEquals("/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf", link.path("path").asText());
        assertTrue(link.path("href").asText().contains("AIDocRepository"));
        assertTrue(link.path("label").asText().contains("Fernwick Labs"));
    }

    @Test
    void search_finds_chiller_troubleshooting_on_page_25() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndexCache.loadForTests(settings, "DocKnowledgeTestAgent", fernwickFixtureReader());

        String json = DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s1", "search_document_chunks",
                        "{\"query\":\"Chiller High Pressure Shutdown\"}"),
                settings,
                "DocKnowledgeTestAgent");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertTrue(body.path("matches").size() >= 1);
        JsonNode top = body.path("matches").get(0);
        assertEquals("troubleshooting", top.path("contentType").asText());
        assertEquals(25, top.path("pageStart").asInt());
        assertTrue(top.path("heading").asText().contains("Chiller High Pressure Shutdown"));
        assertTrue(body.path("searchedChunks").asInt() >= 40);
    }

    @Test
    void expired_cache_rebuild_failure_serves_stale_index_with_warning() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                fernwickFixtureReader(), settings, java.time.Instant.now());
        DocumentKnowledgeIndexCache.installExpiredForTests(settings, "DocKnowledgeTestAgent", index);
        DocumentKnowledgeIndexCache.rebuildHookForTests =
                (s, agent, log, now) -> null;

        String json = DocumentKnowledgeRuntime.executeSearchDocumentChunks(
                new ToolCall("s2", "search_document_chunks", "{\"query\":\"Chiller\"}"),
                settings,
                "DocKnowledgeTestAgent");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertTrue(body.path("degraded").asBoolean());
        assertTrue(body.path("matches").size() >= 1);
        assertTrue(body.path("warnings").toString().contains("INDEX_REBUILD_FAILED_USING_STALE"));
    }

    @Test
    void expired_cache_rebuild_failure_get_serves_stale_chunk_with_warning() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                fernwickFixtureReader(), settings, java.time.Instant.now());
        DocumentKnowledgeIndexCache.installExpiredForTests(settings, "DocKnowledgeTestAgent", index);
        DocumentKnowledgeIndexCache.rebuildHookForTests =
                (s, agent, log, now) -> null;

        String json = DocumentKnowledgeRuntime.executeGetDocumentChunk(
                new ToolCall("g3", "get_document_chunk",
                        "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"page-0001\"}"),
                settings,
                "DocKnowledgeTestAgent");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("success", body.path("status").asText());
        assertTrue(body.path("degraded").asBoolean());
        assertTrue(body.path("warnings").toString().contains("INDEX_REBUILD_FAILED_USING_STALE"));
    }

    @Test
    void get_missing_chunk_returns_chunk_not_found() throws Exception {
        DocumentKnowledgeSettings settings = new DocumentKnowledgeSettings(
                "AIDocRepository", "/document-knowledge", 300, 100, 10_000, 5, 10, 400, 6_000);
        DocumentKnowledgeIndexCache.loadForTests(settings, "DocKnowledgeTestAgent", fernwickFixtureReader());

        String json = DocumentKnowledgeRuntime.executeGetDocumentChunk(
                new ToolCall("g2", "get_document_chunk",
                        "{\"docId\":\"fernwick-carbaq-ops-v2\",\"chunkId\":\"missing-chunk\"}"),
                settings,
                "DocKnowledgeTestAgent");
        JsonNode body = MAPPER.readTree(json);
        assertEquals("error", body.path("status").asText());
        assertEquals("CHUNK_NOT_FOUND", body.path("code").asText());
    }

    private static RepositoryReader fernwickFixtureReader() {
        Path packageDir = fernwickFixtureDir();
        return new RepositoryReader() {
            @Override
            public InfoTable getFileListing(String path, String nameMask) {
                if ("/document-knowledge".equals(path)) {
                    DataShapeDefinition shape = new DataShapeDefinition();
                    shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_NAME, "", BaseTypes.STRING));
                    shape.addFieldDefinition(new FieldDefinition(CommonPropertyNames.PROP_FILETYPE, "", BaseTypes.STRING));
                    InfoTable it = new InfoTable(shape);
                    ValueCollection vc = new ValueCollection();
                    vc.put(CommonPropertyNames.PROP_NAME, new StringPrimitive("fernwick-carbaq-ops-v2"));
                    vc.put(CommonPropertyNames.PROP_FILETYPE, new StringPrimitive("D"));
                    it.addRow(vc);
                    return it;
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
