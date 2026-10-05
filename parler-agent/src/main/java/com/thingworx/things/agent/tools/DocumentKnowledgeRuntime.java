package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.entities.interfaces.IServiceProvider;
import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.AgentBaseThing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Orchestrates document-knowledge index resolution and tool result envelopes.
 */
public final class DocumentKnowledgeRuntime {

    private static final Logger LOG =
            LogUtilities.getInstance().getApplicationLogger(DocumentKnowledgeRuntime.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentKnowledgeRuntime() {}

    public static String executeSearchDocumentChunks(ToolCall call, AgentBaseThing agent) throws Exception {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromAgent(agent);
        String agentName = agent != null ? agent.getName() : "";
        return executeSearchDocumentChunks(call, settings, agentName);
    }

    static String executeSearchDocumentChunks(
            ToolCall call,
            DocumentKnowledgeSettings settings,
            String agentName) throws Exception {
        JsonNode rawArgs = parseArgs(call);
        // Host-context server-injected scope (§3.2): default documentIds to the
        // pre-resolved set ONLY when the model omitted the field — a default, not a hard
        // filter, so the model supplying documentIds at all (including []) suppresses it.
        JsonNode args = rawArgs;
        String injectedScopeSource = null;
        if (rawArgs != null && !rawArgs.has("documentIds")) {
            List<String> scope = AgentToolContext.getInjectedDocumentScopeIds();
            if (scope != null && !scope.isEmpty()) {
                args = withDocumentIds(rawArgs, scope);
                injectedScopeSource = AgentToolContext.getInjectedDocumentScopeSource();
            }
        }
        // isEmptySearchInput ignores documentIds, so an injected scope without retrieval
        // intent (query/signals/assetContext) still short-circuits — scope narrows, never
        // creates, a search.
        if (isEmptySearchInput(args)) {
            return MAPPER.writeValueAsString(emptySearchSuccess(false, warning("EMPTY_SEARCH_INPUT",
                    "Search input was empty; provide query, signals, or assetContext.")));
        }
        Optional<IndexResolution> resolution = resolveIndex(settings, agentName);
        if (resolution.isEmpty() || resolution.get().index.isEmpty()) {
            return MAPPER.writeValueAsString(resolveFailureSearch(settings, agentName));
        }
        IndexResolution resolved = resolution.get();
        return MAPPER.writeValueAsString(
                searchIndex(resolved.index.get(), settings, args, resolved.staleRebuildFailed, injectedScopeSource));
    }

    private static JsonNode withDocumentIds(JsonNode args, List<String> documentIds) {
        ObjectNode copy = args != null && args.isObject()
                ? ((ObjectNode) args).deepCopy()
                : MAPPER.createObjectNode();
        ArrayNode arr = MAPPER.createArrayNode();
        for (String id : documentIds) {
            arr.add(id);
        }
        copy.set("documentIds", arr);
        return copy;
    }

    public static String executeGetDocumentChunk(ToolCall call, AgentBaseThing agent) throws Exception {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromAgent(agent);
        String agentName = agent != null ? agent.getName() : "";
        return executeGetDocumentChunk(call, settings, agentName);
    }

    static String executeGetDocumentChunk(
            ToolCall call,
            DocumentKnowledgeSettings settings,
            String agentName) throws Exception {
        JsonNode args = parseArgs(call);
        String docId = text(args, "docId");
        String chunkId = text(args, "chunkId");
        if (!settings.isRepositoryConfigured()) {
            return MAPPER.writeValueAsString(getError("DOCUMENT_REPOSITORY_NOT_CONFIGURED",
                    "Document knowledge repository is not configured.", docId, chunkId));
        }
        Optional<IndexResolution> resolution = resolveIndex(settings, agentName);
        if (resolution.isEmpty() || resolution.get().index.isEmpty()) {
            String code = repositoryUnavailable(settings, agentName) ? "DOCUMENT_REPOSITORY_UNAVAILABLE"
                    : "DOCUMENT_TOOL_INTERNAL_ERROR";
            String message = repositoryUnavailable(settings, agentName)
                    ? "Document knowledge repository is unavailable."
                    : "Document tool failed unexpectedly.";
            return MAPPER.writeValueAsString(getError(code, message, docId, chunkId));
        }
        IndexResolution resolved = resolution.get();
        DocumentKnowledgeIndex index = resolved.index.get();
        Optional<DocumentKnowledgeChunk> chunk = index.findChunk(docId, chunkId);
        if (chunk.isEmpty()) {
            return MAPPER.writeValueAsString(getError("CHUNK_NOT_FOUND",
                    "Document chunk was not found.", docId, chunkId));
        }
        return MAPPER.writeValueAsString(buildGetSuccess(
                settings, index, chunk.get(), resolved.staleRebuildFailed));
    }

    /**
     * Resolves an override-or-default document set (design §3.3). The override is the
     * overridable {@code ResolveDocumentSet} ThingWorx service on the agent entity; if
     * absent it returns an empty typed table, so a non-empty result means an
     * App-developer override produced it.
     */
    @FunctionalInterface
    interface CustomDocumentSetResolver {
        /** @return the override's resolved (already Postel-parsed) rows; empty = no custom mapping */
        CustomResolution resolve(String key) throws Exception;
    }

    /**
     * Parsed rows of a custom {@code ResolveDocumentSet} result (design §3.5): the ordered,
     * deduped document ids plus the cross-cutting flag subsets. {@code alwaysInclude} drives
     * the cross-cutting union into scope; {@code appliesToMany} is carried for the wire but
     * is inert in M4.
     */
    static final class CustomResolution {
        private final List<String> documentIds;
        private final java.util.Set<String> alwaysIncludeIds;
        private final java.util.Set<String> appliesToManyIds;

        CustomResolution(
                List<String> documentIds,
                java.util.Set<String> alwaysIncludeIds,
                java.util.Set<String> appliesToManyIds) {
            this.documentIds = List.copyOf(documentIds);
            this.alwaysIncludeIds = java.util.Set.copyOf(alwaysIncludeIds);
            this.appliesToManyIds = java.util.Set.copyOf(appliesToManyIds);
        }

        /** No custom mapping. */
        static CustomResolution empty() {
            return new CustomResolution(List.of(), java.util.Set.of(), java.util.Set.of());
        }

        /** Convenience for ids without cross-cutting flags (test seam / simple overrides). */
        static CustomResolution of(List<String> documentIds) {
            return new CustomResolution(documentIds, java.util.Set.of(), java.util.Set.of());
        }

        boolean isEmpty() {
            return documentIds.isEmpty();
        }

        List<String> documentIds() {
            return documentIds;
        }

        java.util.Set<String> alwaysIncludeIds() {
            return alwaysIncludeIds;
        }

        java.util.Set<String> appliesToManyIds() {
            return appliesToManyIds;
        }
    }

    public static String executeResolveDocumentSet(ToolCall call, AgentBaseThing agent) throws Exception {
        DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromAgent(agent);
        String agentName = agent != null ? agent.getName() : "";
        return executeResolveDocumentSet(call, settings, agentName, customResolverFor(agent));
    }

    static String executeResolveDocumentSet(
            ToolCall call,
            DocumentKnowledgeSettings settings,
            String agentName) throws Exception {
        return executeResolveDocumentSet(call, settings, agentName, key -> CustomResolution.empty());
    }

    static String executeResolveDocumentSet(
            ToolCall call,
            DocumentKnowledgeSettings settings,
            String agentName,
            CustomDocumentSetResolver custom) throws Exception {
        JsonNode args = parseArgs(call);
        String key = text(args, "key");
        if (!settings.isRepositoryConfigured()) {
            return MAPPER.writeValueAsString(resolveError("DOCUMENT_REPOSITORY_NOT_CONFIGURED",
                    "Document knowledge repository is not configured."));
        }
        Optional<IndexResolution> resolution = resolveIndex(settings, agentName);
        if (resolution.isEmpty() || resolution.get().index.isEmpty()) {
            String code = repositoryUnavailable(settings, agentName) ? "DOCUMENT_REPOSITORY_UNAVAILABLE"
                    : "DOCUMENT_TOOL_INTERNAL_ERROR";
            String message = repositoryUnavailable(settings, agentName)
                    ? "Document knowledge repository is unavailable."
                    : "Document tool failed unexpectedly.";
            return MAPPER.writeValueAsString(resolveError(code, message));
        }
        IndexResolution resolved = resolution.get();
        return MAPPER.writeValueAsString(
                resolveDocumentSet(resolved.index.get(), key, resolved.staleRebuildFailed, custom));
    }

    /**
     * Production custom resolver: invokes the overridable {@code ResolveDocumentSet} service on the agent as the
     * current user, for both the {@code resolve_document_set} tool and host-context scoping.
     */
    static CustomDocumentSetResolver customResolverFor(IServiceProvider agent) {
        if (agent == null) {
            return key -> CustomResolution.empty();
        }
        return key -> {
            ValueCollection params = new ValueCollection();
            params.put("key", new StringPrimitive(key == null ? "" : key));
            InfoTable raw = PlatformAccess.invokeAsUser(agent, "ResolveDocumentSet", params);
            return parseResolvedDocuments(ServiceResultInfotable.extractInfotableResult(raw));
        };
    }

    /**
     * Postel parse of a {@code ResolvedDocument} result: trim {@code documentId}, drop blanks,
     * dedupe in row order, and capture the cross-cutting {@code alwaysInclude}/{@code appliesToMany}
     * flags (design §3.5). A docId flagged on any of its rows is treated as flagged.
     */
    static CustomResolution parseResolvedDocuments(InfoTable table) {
        if (table == null || table.getRowCount() == 0) {
            return CustomResolution.empty();
        }
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> always = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> many = new java.util.LinkedHashSet<>();
        for (int i = 0; i < table.getRowCount(); i++) {
            ValueCollection row = (ValueCollection) table.getRow(i);
            if (row == null) {
                continue;
            }
            Object value = row.getValue("documentId");
            if (value == null) {
                continue;
            }
            String id = value.toString().trim();
            if (id.isEmpty()) {
                continue;
            }
            ids.add(id);
            if (boolFlag(row, "alwaysInclude")) {
                always.add(id);
            }
            if (boolFlag(row, "appliesToMany")) {
                many.add(id);
            }
        }
        return new CustomResolution(new ArrayList<>(ids), always, many);
    }

    /** Back-compat helper: the resolved document ids only (cross-cutting flags dropped). */
    static List<String> parseResolvedDocIds(InfoTable table) {
        return parseResolvedDocuments(table).documentIds();
    }

    /** Tolerant BOOLEAN read for an optional cross-cutting flag column; absent/blank = false. */
    private static boolean boolFlag(ValueCollection row, String field) {
        Object value = row.getValue(field);
        if (value == null) {
            return false;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return Boolean.parseBoolean(value.toString().trim());
    }

    static Map<String, Object> resolveDocumentSet(DocumentKnowledgeIndex index, String key, boolean degraded)
            throws Exception {
        return resolveDocumentSet(index, key, degraded, k -> CustomResolution.empty());
    }

    /**
     * Resolves the document set (design §3.3): the App-developer override first (via
     * {@code custom}), else the built-in conservative high-confidence matcher. Emits
     * {@code resolverSource: custom} only when the override yields ≥ 1 valid documentId;
     * otherwise falls through to {@code default-match} / {@code default-empty}. Builds the
     * model-facing body ({@code documents[]} of {@code {documentId, alwaysInclude, appliesToMany}}
     * + {@code resolverSource}). Cross-cutting flags are surfaced per row so the model can
     * hand the union off correctly (design §3.5). Package-private seam so the orchestration is
     * unit-testable offline.
     */
    static Map<String, Object> resolveDocumentSet(
            DocumentKnowledgeIndex index, String key, boolean degraded,
            CustomDocumentSetResolver custom) throws Exception {
        ResolvedDocumentSet result = resolveDocumentSetResult(index, key, custom);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("degraded", degraded);
        body.put("resolverSource", result.source().wire());
        List<Map<String, Object>> documents = new ArrayList<>();
        for (String docId : result.documentIds()) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("documentId", docId);
            doc.put("alwaysInclude", result.isAlwaysInclude(docId));
            doc.put("appliesToMany", result.isAppliesToMany(docId));
            documents.add(doc);
        }
        body.put("documents", documents);
        return body;
    }

    /**
     * Override-first → built-in matcher; returns the resolved set (custom / default-match /
     * default-empty). On the custom path the cross-cutting {@code alwaysInclude} docs are
     * unioned into the scope (design §3.5) and the flag subsets carried; the built-in matcher
     * never synthesizes cross-cutting docs — they are resolver-sourced only.
     */
    static ResolvedDocumentSet resolveDocumentSetResult(
            DocumentKnowledgeIndex index, String key, CustomDocumentSetResolver custom) throws Exception {
        CustomResolution customRows = custom == null ? CustomResolution.empty() : custom.resolve(key);
        if (customRows != null && !customRows.isEmpty()) {
            return new ResolvedDocumentSet(
                    unionScope(customRows),
                    customRows.alwaysIncludeIds(),
                    customRows.appliesToManyIds(),
                    ResolverSource.CUSTOM);
        }
        return DocumentSetResolver.resolveDefault(
                key == null ? List.of() : List.of(key), index.allManifests());
    }

    /**
     * Final scoped union (design §3.5): key-matched rows first, then cross-cutting
     * {@code alwaysInclude} rows — so a cross-cutting doc survives in scope even when it is
     * outside the key-matched set. Order-preserving and deduped.
     */
    private static List<String> unionScope(CustomResolution rows) {
        java.util.LinkedHashSet<String> union = new java.util.LinkedHashSet<>(rows.documentIds());
        union.addAll(rows.alwaysIncludeIds());
        return new ArrayList<>(union);
    }

    /**
     * Server-side host-context document-scope resolve (design §3.2): runs the shared
     * resolver (override-first → built-in matcher) for the bound-Thing {@code key} and
     * returns the non-empty scope to inject, or empty when there is nothing to scope.
     * Fails open — never throws — so a resolver problem never aborts the turn.
     */
    public static Optional<ResolvedDocumentSet> resolveHostContextScope(AgentBaseThing agent, String key) {
        try {
            if (agent == null || key == null || key.isBlank() || !agent.isDocumentKnowledgeBuiltinsEnabled()) {
                return Optional.empty();
            }
            DocumentKnowledgeSettings settings = DocumentKnowledgeSettings.fromAgent(agent);
            if (!settings.isRepositoryConfigured()) {
                return Optional.empty();
            }
            Optional<IndexResolution> resolution = resolveIndex(settings, agent.getName());
            if (resolution.isEmpty() || resolution.get().index.isEmpty()) {
                return Optional.empty();
            }
            ResolvedDocumentSet result = resolveDocumentSetResult(
                    resolution.get().index.get(), key, customResolverFor(agent));
            return result.isEmpty() ? Optional.empty() : Optional.of(result);
        } catch (Exception e) {
            LOG.warn("[{}] host-context document scope resolve failed: {}",
                    agent != null ? agent.getName() : "", e.getMessage());
            return Optional.empty();
        }
    }

    private static Map<String, Object> resolveError(String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("resolverSource", ResolverSource.NONE.wire());
        return body;
    }

    static final class IndexResolution {
        private final Optional<DocumentKnowledgeIndex> index;
        private final boolean staleRebuildFailed;

        private IndexResolution(Optional<DocumentKnowledgeIndex> index, boolean staleRebuildFailed) {
            this.index = index;
            this.staleRebuildFailed = staleRebuildFailed;
        }

        static Optional<IndexResolution> of(DocumentKnowledgeIndexCache.LoadResult loadResult) {
            if (loadResult == null || loadResult.index() == null) {
                return Optional.empty();
            }
            return Optional.of(new IndexResolution(Optional.of(loadResult.index()), loadResult.staleRebuildFailed()));
        }
    }

    static Optional<IndexResolution> resolveIndex(DocumentKnowledgeSettings settings, String agentName) {
        if (!settings.isRepositoryConfigured()) {
            return Optional.empty();
        }
        try {
            DocumentKnowledgeIndexCache.LoadResult loadResult = DocumentKnowledgeIndexCache.getOrLoad(
                    settings, agentName != null ? agentName : "", LOG);
            if (settings.hasConfigClampWarnings()) {
                LOG.info("[{}] document knowledge configuration values were clamped", agentName);
            }
            return IndexResolution.of(loadResult);
        } catch (Exception e) {
            LOG.error("[{}] document knowledge index load failed: {}", agentName, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private static Map<String, Object> resolveFailureSearch(DocumentKnowledgeSettings settings, String agentName) {
        if (!settings.isRepositoryConfigured()) {
            return degradedEmptySearch(warning("DOCUMENT_REPOSITORY_NOT_CONFIGURED",
                    "Document knowledge repository is not configured."));
        }
        if (repositoryUnavailable(settings, agentName)) {
            return degradedEmptySearch(warning("DOCUMENT_REPOSITORY_UNAVAILABLE",
                    "Document knowledge repository is unavailable."));
        }
        return degradedEmptySearch(warning("DOCUMENT_TOOL_INTERNAL_ERROR",
                "Document tool failed unexpectedly."));
    }

    private static boolean repositoryUnavailable(DocumentKnowledgeSettings settings, String agentName) {
        if (!settings.isRepositoryConfigured()) {
            return false;
        }
        try {
            return com.thingworx.things.agent.FileRepositoryThingResolver
                    .resolveForCurrentUser(settings.repository(), LOG, agentName != null ? agentName : "")
                    .isEmpty();
        } catch (Exception e) {
            LOG.error("[{}] document knowledge repository resolve failed: {}", agentName, e.getMessage(), e);
            return true;
        }
    }

    private static Map<String, Object> searchIndex(
            DocumentKnowledgeIndex index,
            DocumentKnowledgeSettings settings,
            JsonNode args,
            boolean staleRebuildFailed,
            String injectedScopeResolverSource) {
        DocumentKnowledgeWarnings responseWarnings = new DocumentKnowledgeWarnings();
        appendLoadResponseWarnings(responseWarnings, settings, staleRebuildFailed);
        for (Map<String, Object> w : index.warnings().toJsonList()) {
            Object code = w.get("code");
            Object message = w.get("message");
            Object count = w.get("count");
            if (code instanceof String) {
                String c = (String) code;
                String m = message instanceof String ? (String) message : "";
                if (count instanceof Number && ((Number) count).intValue() > 1) {
                    responseWarnings.increment(c, m, ((Number) count).intValue());
                } else {
                    responseWarnings.add(c, m);
                }
            }
        }

        int requestedLimit = args.has("limit") && args.get("limit").isNumber()
                ? args.get("limit").asInt()
                : settings.searchDefaultLimit();
        int effectiveLimit = DocumentKnowledgeSearchScorer.resolveEffectiveLimit(
                requestedLimit, settings, responseWarnings);

        DocumentKnowledgeSearchScorer.SearchRequest request = DocumentKnowledgeSearchScorer.SearchRequest.from(args);
        DocumentKnowledgeSearchScorer.RankResult ranked = DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics(
                index.searchableChunks(),
                index::manifestFor,
                request,
                effectiveLimit);

        List<Map<String, Object>> matches = new ArrayList<>();
        for (DocumentKnowledgeSearchScorer.ScoredMatch match : ranked.matches()) {
            matches.add(buildSearchMatch(settings, index, match));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("degraded", index.degraded() || staleRebuildFailed);
        body.put("matches", matches);
        body.put("warnings", responseWarnings.toJsonList());
        body.put("searchedDocuments", index.searchedDocuments());
        body.put("searchedChunks", index.searchedChunks());
        body.put("skippedDocuments", index.skippedDocuments());
        body.put("skippedChunks", index.skippedChunks());
        body.put("documentScores", serializeDocumentScores(ranked.documentScores()));
        body.put("selectedDocIds", ranked.selectedDocIds());
        body.put("selectionMode", ranked.selectionMode());
        if (injectedScopeResolverSource != null) {
            body.put("documentScopeSource", "host-context-resolver");
            body.put("documentScopeResolverSource", injectedScopeResolverSource);
        }
        return body;
    }

    private static List<Map<String, Object>> serializeDocumentScores(
            List<DocumentKnowledgeSearchScorer.DocumentScoreEntry> scores) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DocumentKnowledgeSearchScorer.DocumentScoreEntry entry : scores) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("docId", entry.docId());
            row.put("score", entry.score());
            row.put("matchedEvidence", entry.matchedEvidence());
            out.add(row);
        }
        return out;
    }

    private static Map<String, Object> buildSearchMatch(
            DocumentKnowledgeSettings settings,
            DocumentKnowledgeIndex index,
            DocumentKnowledgeSearchScorer.ScoredMatch match) {
        DocumentKnowledgeChunk chunk = match.chunk();
        DocumentKnowledgePackageManifest manifest = index.manifestFor(chunk.docId()).orElse(null);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("docId", chunk.docId());
        row.put("chunkId", chunk.chunkId());
        row.put("heading", chunk.heading());
        row.put("sectionPath", chunk.sectionPath());
        row.put("contentType", chunk.contentType());
        row.put("pageStart", chunk.pageStart());
        row.put("pageEnd", chunk.pageEnd());
        row.put("score", match.score());
        row.put("bm25Boost", match.bm25Boost());
        row.put("snippet", DocumentKnowledgeSearchScorer.buildSnippet(chunk, settings.searchSnippetMaxChars()));
        row.put("sourceLinks", buildSourceLinks(settings, manifest, chunk));
        return row;
    }

    private static List<Map<String, Object>> buildSourceLinks(
            DocumentKnowledgeSettings settings,
            DocumentKnowledgePackageManifest manifest,
            DocumentKnowledgeChunk chunk) {
        List<Map<String, Object>> sourceLinks = new ArrayList<>();
        if (manifest == null) {
            return sourceLinks;
        }
        String repository = manifest.sourceRepository();
        if (repository == null || repository.isBlank()) {
            repository = settings.repository();
        }
        String pdfPath = manifest.sourceRepositoryPath();
        if (pdfPath != null && !pdfPath.isBlank() && repository != null && !repository.isBlank()) {
            Map<String, Object> link = new LinkedHashMap<>();
            link.put("label", buildSourceLabel(manifest, chunk));
            link.put("repository", repository);
            link.put("path", pdfPath);
            link.put("page", chunk.pageStart());
            link.put("href", DocumentKnowledgeLinkBuilder.buildPdfHref(repository, pdfPath, chunk.pageStart()));
            sourceLinks.add(link);
        }
        return sourceLinks;
    }

    private static Map<String, Object> buildGetSuccess(
            DocumentKnowledgeSettings settings,
            DocumentKnowledgeIndex index,
            DocumentKnowledgeChunk chunk,
            boolean staleRebuildFailed) {
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        appendLoadResponseWarnings(warnings, settings, staleRebuildFailed);
        String markdown = chunk.markdown() != null ? chunk.markdown() : "";
        if (markdown.length() > settings.chunkMaxChars()) {
            markdown = DocumentKnowledgeTextBounds.truncateMarkdown(markdown, settings.chunkMaxChars());
            warnings.add("CHUNK_MARKDOWN_TRUNCATED",
                    "Chunk markdown was truncated to the configured maximum.");
        }

        DocumentKnowledgePackageManifest manifest = index.manifestFor(chunk.docId()).orElse(null);
        List<Map<String, Object>> sourceLinks = buildSourceLinks(settings, manifest, chunk);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("degraded", staleRebuildFailed);
        body.put("docId", chunk.docId());
        body.put("chunkId", chunk.chunkId());
        body.put("heading", chunk.heading());
        body.put("sectionPath", chunk.sectionPath());
        body.put("contentType", chunk.contentType());
        body.put("pageStart", chunk.pageStart());
        body.put("pageEnd", chunk.pageEnd());
        body.put("markdown", markdown);
        body.put("sourceLinks", sourceLinks);
        body.put("warnings", warnings.toJsonList());
        return body;
    }

    private static String buildSourceLabel(DocumentKnowledgePackageManifest manifest, DocumentKnowledgeChunk chunk) {
        String docTitle = manifest != null && manifest.title() != null && !manifest.title().isBlank()
                ? manifest.title()
                : chunk.docId();
        String section = chunk.sectionPath().isEmpty()
                ? chunk.heading()
                : chunk.sectionPath().get(chunk.sectionPath().size() - 1);
        return docTitle + ", " + section + ", page " + chunk.pageStart();
    }

    private static Map<String, Object> getError(String code, String message, String docId, String chunkId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "error");
        body.put("code", code);
        body.put("message", message);
        body.put("docId", docId);
        body.put("chunkId", chunkId);
        return body;
    }

    private static JsonNode parseArgs(ToolCall call) throws Exception {
        String raw = call.getArguments();
        if (raw == null || raw.isBlank()) {
            return MAPPER.createObjectNode();
        }
        return MAPPER.readTree(raw);
    }

    private static boolean isEmptySearchInput(JsonNode args) {
        if (args == null || args.isNull()) {
            return true;
        }
        if (hasNonBlankText(args, "query")) {
            return false;
        }
        JsonNode signals = args.get("signals");
        if (signals != null && signals.isArray() && signals.size() > 0) {
            return false;
        }
        JsonNode assetContext = args.get("assetContext");
        if (assetContext != null && assetContext.isObject() && assetContext.size() > 0) {
            return false;
        }
        JsonNode documentTypes = args.get("documentTypes");
        if (documentTypes != null && documentTypes.isArray() && documentTypes.size() > 0) {
            return false;
        }
        return true;
    }

    private static boolean hasNonBlankText(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n != null && n.isTextual() && !n.asText().trim().isEmpty();
    }

    private static String text(JsonNode args, String field) {
        JsonNode n = args.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    private static Map<String, Object> degradedEmptySearch(Map<String, Object> warning) {
        return emptySearchSuccess(true, warning);
    }

    private static Map<String, Object> emptySearchSuccess(boolean degraded, Map<String, Object> warning) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "success");
        body.put("degraded", degraded);
        body.put("matches", List.of());
        body.put("warnings", warning == null ? List.of() : List.of(warning));
        body.put("searchedDocuments", 0);
        body.put("searchedChunks", 0);
        body.put("skippedDocuments", 0);
        body.put("skippedChunks", 0);
        return body;
    }

    private static void appendLoadResponseWarnings(
            DocumentKnowledgeWarnings target,
            DocumentKnowledgeSettings settings,
            boolean staleRebuildFailed) {
        for (Map<String, Object> w : settings.configClampWarnings().toJsonList()) {
            Object code = w.get("code");
            Object message = w.get("message");
            if (code instanceof String) {
                target.add((String) code, message instanceof String ? (String) message : "");
            }
        }
        if (staleRebuildFailed) {
            target.add("INDEX_REBUILD_FAILED_USING_STALE",
                    "Index rebuild failed; returned results from the previous cache entry.");
        }
    }

    private static Map<String, Object> warning(String code, String message) {
        Map<String, Object> w = new LinkedHashMap<>();
        w.put("code", code);
        w.put("message", message);
        return w;
    }
}
