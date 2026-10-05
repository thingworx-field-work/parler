package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.tools.DocumentKnowledgePackageManifest.ParseResult;
import com.thingworx.things.agent.tools.DocumentKnowledgePackageManifest.ParseStatus;

class DocumentKnowledgePackageManifestTest {

    @Test
    void fernwick_fixture_manifest_is_valid() throws Exception {
        String json = java.nio.file.Files.readString(
                repoRoot().resolve("dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/manifest.json"));
        ParseResult result = DocumentKnowledgePackageManifest.parse(json);
        assertEquals(ParseStatus.SUCCESS, result.status());
        assertEquals("fernwick-carbaq-ops-v2", result.manifest().docId());
    }

    @Test
    void unparseable_json_is_invalid_json() {
        assertEquals(ParseStatus.INVALID_JSON, DocumentKnowledgePackageManifest.parse("{").status());
    }

    @Test
    void valid_json_missing_doc_id_is_invalid_shape() {
        ParseResult result = DocumentKnowledgePackageManifest.parse("{\"title\":\"no docId\"}");
        assertEquals(ParseStatus.INVALID_SHAPE, result.status());
    }

    @Test
    void document_role_is_optional() {
        String json = contractValidManifestJson("bundle-doc").replace(
                "\"convertedAt\"", "\"documentRole\":\"bundle\",\"convertedAt\"");
        ParseResult result = DocumentKnowledgePackageManifest.parse(json);
        assertEquals(ParseStatus.SUCCESS, result.status());
        assertEquals("bundle", result.manifest().documentRole());
    }

    @Test
    void valid_json_missing_source_path_is_invalid_shape() {
        String json = "{"
                + "\"contractVersion\":\"0.1\","
                + "\"docId\":\"test-doc\","
                + "\"title\":\"Test\","
                + "\"sourcePath\":\"source/original.pdf\","
                + "\"markdownPath\":\"markdown/manual.md\","
                + "\"chunksPath\":\"chunks/chunks.jsonl\","
                + "\"pageCount\":1,"
                + "\"sourceRepository\":\"AIDocRepository\","
                + "\"sourceHref\":\"/Thingworx/FileRepositories/AIDocRepository/x.pdf\","
                + "\"sourceSha256\":\"abc\","
                + "\"convertedAt\":\"2026-06-15T00:00:00Z\""
                + "}";
        assertEquals(ParseStatus.INVALID_SHAPE, DocumentKnowledgePackageManifest.parse(json).status());
    }

    static String contractValidManifestJson(String docId) {
        return "{"
                + "\"contractVersion\":\"0.1\","
                + "\"docId\":\"" + docId + "\","
                + "\"title\":\"Test Document\","
                + "\"sourcePath\":\"source/original.pdf\","
                + "\"markdownPath\":\"markdown/manual.md\","
                + "\"chunksPath\":\"chunks/chunks.jsonl\","
                + "\"pageCount\":1,"
                + "\"sourceRepository\":\"AIDocRepository\","
                + "\"sourceRepositoryPath\":\"/document-knowledge/" + docId + "/source/original.pdf\","
                + "\"sourceHref\":\"/Thingworx/FileRepositories/AIDocRepository/document-knowledge/"
                + docId + "/source/original.pdf\","
                + "\"sourceSha256\":\"abc123\","
                + "\"convertedAt\":\"2026-06-15T00:00:00Z\""
                + "}";
    }

    private static java.nio.file.Path repoRoot() {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !dir.resolve("CONTRACTS/CONTRACT_VERSION.md").toFile().exists()) {
            dir = dir.getParent();
        }
        if (dir == null) {
            throw new IllegalStateException("repo root not found from cwd");
        }
        return dir;
    }
}
