package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Parsed {@code manifest.json} for a document-knowledge package.
 */
public final class DocumentKnowledgePackageManifest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public enum ParseStatus {
        SUCCESS,
        INVALID_JSON,
        INVALID_SHAPE
    }

    public static final class ParseResult {
        private final ParseStatus status;
        private final DocumentKnowledgePackageManifest manifest;

        private ParseResult(ParseStatus status, DocumentKnowledgePackageManifest manifest) {
            this.status = status;
            this.manifest = manifest;
        }

        public static ParseResult success(DocumentKnowledgePackageManifest manifest) {
            return new ParseResult(ParseStatus.SUCCESS, manifest);
        }

        public static ParseResult invalidJson() {
            return new ParseResult(ParseStatus.INVALID_JSON, null);
        }

        public static ParseResult invalidShape() {
            return new ParseResult(ParseStatus.INVALID_SHAPE, null);
        }

        public ParseStatus status() {
            return status;
        }

        public DocumentKnowledgePackageManifest manifest() {
            return manifest;
        }

        public boolean isSuccess() {
            return status == ParseStatus.SUCCESS;
        }
    }

    private final String docId;
    private final String title;
    private final String chunksPath;
    private final String sourceRepository;
    private final String sourceRepositoryPath;
    private final String documentType;
    private final String documentRole;
    private final List<String> assetModels;
    private final String sourceFileName;
    private final DocumentKnowledgeDocumentProfile documentProfile;

    private DocumentKnowledgePackageManifest(String docId, String title, String chunksPath,
            String sourceRepository, String sourceRepositoryPath, String documentType,
            String documentRole, List<String> assetModels, String sourceFileName,
            DocumentKnowledgeDocumentProfile documentProfile) {
        this.docId = docId;
        this.title = title;
        this.chunksPath = chunksPath;
        this.sourceRepository = sourceRepository;
        this.sourceRepositoryPath = sourceRepositoryPath;
        this.documentType = documentType;
        this.documentRole = documentRole;
        this.assetModels = assetModels;
        this.sourceFileName = sourceFileName;
        this.documentProfile = documentProfile;
    }

    public String docId() {
        return docId;
    }

    public String title() {
        return title;
    }

    public String chunksPath() {
        return chunksPath;
    }

    public String sourceRepository() {
        return sourceRepository;
    }

    public String sourceRepositoryPath() {
        return sourceRepositoryPath;
    }

    public String documentType() {
        return documentType;
    }

    public String documentRole() {
        return documentRole;
    }

    public List<String> assetModels() {
        return assetModels;
    }

    public String sourceFileName() {
        return sourceFileName;
    }

    public DocumentKnowledgeDocumentProfile documentProfile() {
        return documentProfile;
    }

    /**
     * @return parse outcome separating JSON syntax errors from contract shape violations
     */
    public static ParseResult parse(String json) {
        if (json == null || json.isBlank()) {
            return ParseResult.invalidJson();
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!hasRequiredShape(root)) {
                return ParseResult.invalidShape();
            }
            return ParseResult.success(fromRoot(root));
        } catch (Exception e) {
            return ParseResult.invalidJson();
        }
    }

    /**
     * @return parsed manifest only when JSON and contract shape are valid
     */
    public static Optional<DocumentKnowledgePackageManifest> parseJson(String json) {
        ParseResult result = parse(json);
        return result.isSuccess() ? Optional.of(result.manifest()) : Optional.empty();
    }

    private static DocumentKnowledgePackageManifest fromRoot(JsonNode root) {
        return new DocumentKnowledgePackageManifest(
                requiredText(root, "docId"),
                requiredText(root, "title"),
                requiredText(root, "chunksPath"),
                requiredText(root, "sourceRepository"),
                requiredText(root, "sourceRepositoryPath"),
                optionalText(root, "documentType"),
                optionalText(root, "documentRole"),
                readStringArray(root.get("assetModels")),
                optionalText(root, "sourceFileName"),
                DocumentKnowledgeDocumentProfile.parse(root));
    }

    private static boolean hasRequiredShape(JsonNode root) {
        if (root == null || !root.isObject()) {
            return false;
        }
        String[] requiredTextFields = {
                "contractVersion",
                "docId",
                "title",
                "sourcePath",
                "markdownPath",
                "chunksPath",
                "sourceRepository",
                "sourceRepositoryPath",
                "sourceHref",
                "sourceSha256",
                "convertedAt"
        };
        for (String field : requiredTextFields) {
            if (requiredText(root, field) == null) {
                return false;
            }
        }
        JsonNode pageCount = root.get("pageCount");
        return pageCount != null && pageCount.isNumber() && pageCount.asInt() >= 1;
    }

    private static String requiredText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        String t = n.asText().trim();
        return t.isEmpty() ? null : t;
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode n = root.get(field);
        if (n == null || n.isNull() || !n.isTextual()) {
            return null;
        }
        return n.asText();
    }

    private static List<String> readStringArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && item.isTextual()) {
                String t = item.asText().trim();
                if (!t.isEmpty()) {
                    out.add(t);
                }
            }
        }
        return List.copyOf(out);
    }
}
