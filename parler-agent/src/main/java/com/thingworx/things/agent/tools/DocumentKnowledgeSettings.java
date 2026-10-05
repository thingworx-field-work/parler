package com.thingworx.things.agent.tools;

import com.thingworx.things.agent.AgentBaseThing;

/**
 * Resolved AgentThing document-knowledge settings ({@code docs/agent/document-chunk-tools.md} §4).
 */
public final class DocumentKnowledgeSettings {

    private final String repository;
    private final String rootPath;
    private final int indexTtlSeconds;
    private final int maxDocuments;
    private final int maxChunks;
    private final int searchDefaultLimit;
    private final int searchMaxLimit;
    private final int searchSnippetMaxChars;
    private final int chunkMaxChars;
    private final DocumentKnowledgeWarnings configClampWarnings;

    public DocumentKnowledgeSettings(
            String repository,
            String rootPath,
            int indexTtlSeconds,
            int maxDocuments,
            int maxChunks,
            int searchDefaultLimit,
            int searchMaxLimit,
            int searchSnippetMaxChars,
            int chunkMaxChars) {
        this(repository, rootPath, indexTtlSeconds, maxDocuments, maxChunks, searchDefaultLimit,
                searchMaxLimit, searchSnippetMaxChars, chunkMaxChars, new DocumentKnowledgeWarnings());
    }

    private DocumentKnowledgeSettings(
            String repository,
            String rootPath,
            int indexTtlSeconds,
            int maxDocuments,
            int maxChunks,
            int searchDefaultLimit,
            int searchMaxLimit,
            int searchSnippetMaxChars,
            int chunkMaxChars,
            DocumentKnowledgeWarnings configClampWarnings) {
        this.repository = repository != null ? repository.trim() : "";
        this.rootPath = DocumentKnowledgePaths.normalizeRoot(rootPath);
        this.indexTtlSeconds = indexTtlSeconds;
        this.maxDocuments = maxDocuments;
        this.maxChunks = maxChunks;
        this.searchDefaultLimit = searchDefaultLimit;
        this.searchMaxLimit = searchMaxLimit;
        this.searchSnippetMaxChars = searchSnippetMaxChars;
        this.chunkMaxChars = chunkMaxChars;
        this.configClampWarnings = configClampWarnings != null
                ? configClampWarnings
                : new DocumentKnowledgeWarnings();
    }

    public static DocumentKnowledgeSettings defaults() {
        return new DocumentKnowledgeSettings("", null, 300, 100, 10_000, 5, 10, 400, 6_000);
    }

    public static DocumentKnowledgeSettings fromAgent(AgentBaseThing agent) {
        if (agent == null) {
            return defaults();
        }
        return fromRawAgentConfiguration(
                agent.getDocumentKnowledgeRepository(),
                agent.getDocumentKnowledgeRootPath(),
                agent.getDocumentKnowledgeIndexTtlSeconds(),
                agent.getDocumentKnowledgeMaxDocuments(),
                agent.getDocumentKnowledgeMaxChunks(),
                agent.getDocumentKnowledgeSearchDefaultLimit(),
                agent.getDocumentKnowledgeSearchMaxLimit(),
                agent.getDocumentKnowledgeSearchSnippetMaxChars(),
                agent.getDocumentKnowledgeChunkMaxChars());
    }

    static DocumentKnowledgeSettings fromRawAgentConfiguration(
            String repository,
            String rootPath,
            int indexTtlSeconds,
            int maxDocuments,
            int maxChunks,
            int searchDefaultLimit,
            int searchMaxLimit,
            int searchSnippetMaxChars,
            int chunkMaxChars) {
        DocumentKnowledgeWarnings clampWarnings = new DocumentKnowledgeWarnings();
        int resolvedTtl = clampInt(
                "documentKnowledgeIndexTtlSeconds", indexTtlSeconds, 30, 86_400, 300, clampWarnings);
        int resolvedMaxDocuments = clampInt(
                "documentKnowledgeMaxDocuments", maxDocuments, 1, 10_000, 100, clampWarnings);
        int resolvedMaxChunks = clampInt(
                "documentKnowledgeMaxChunks", maxChunks, 1, 1_000_000, 10_000, clampWarnings);
        int resolvedSearchDefault = clampInt(
                "documentKnowledgeSearchDefaultLimit", searchDefaultLimit, 1, 100, 5, clampWarnings);
        int resolvedSearchMax = clampInt(
                "documentKnowledgeSearchMaxLimit",
                searchMaxLimit,
                resolvedSearchDefault,
                100,
                resolvedSearchDefault,
                clampWarnings);
        int resolvedSnippetMax = clampInt(
                "documentKnowledgeSearchSnippetMaxChars", searchSnippetMaxChars, 50, 10_000, 400, clampWarnings);
        int resolvedChunkMax = clampInt(
                "documentKnowledgeChunkMaxChars", chunkMaxChars, 500, 500_000, 6_000, clampWarnings);
        return new DocumentKnowledgeSettings(
                repository,
                rootPath,
                resolvedTtl,
                resolvedMaxDocuments,
                resolvedMaxChunks,
                resolvedSearchDefault,
                resolvedSearchMax,
                resolvedSnippetMax,
                resolvedChunkMax,
                clampWarnings);
    }

    public boolean isRepositoryConfigured() {
        return repository != null && !repository.isBlank();
    }

    public String repository() {
        return repository;
    }

    public String rootPath() {
        return rootPath;
    }

    public int indexTtlSeconds() {
        return indexTtlSeconds;
    }

    public int maxDocuments() {
        return maxDocuments;
    }

    public int maxChunks() {
        return maxChunks;
    }

    public int searchDefaultLimit() {
        return searchDefaultLimit;
    }

    public int searchMaxLimit() {
        return searchMaxLimit;
    }

    public int searchSnippetMaxChars() {
        return searchSnippetMaxChars;
    }

    public int chunkMaxChars() {
        return chunkMaxChars;
    }

    public DocumentKnowledgeWarnings configClampWarnings() {
        return configClampWarnings;
    }

    public boolean hasConfigClampWarnings() {
        return !configClampWarnings.isEmpty();
    }

    public String cacheKey(String agentThingName) {
        return (agentThingName != null ? agentThingName : "") + "|" + repository + "|" + rootPath;
    }

    private static int clampInt(
            String field,
            int value,
            int min,
            int max,
            int defaultWhenBelowMin,
            DocumentKnowledgeWarnings warnings) {
        if (value < min) {
            warnings.add("CONFIG_VALUE_CLAMPED", field + " was below the allowed minimum and was adjusted.");
            return defaultWhenBelowMin;
        }
        if (value > max) {
            warnings.add("CONFIG_VALUE_CLAMPED", field + " exceeded the allowed maximum and was clamped.");
            return max;
        }
        return value;
    }
}
