package com.thingworx.things.agent.tools;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Deterministic document-aware metadata + lexical scoring for {@code search_document_chunks}.
 */
public final class DocumentKnowledgeSearchScorer {

    /** Heading/marker chunk class (document-retrieval-convergence C2; same class C1 uses). */
    private static final String SIGNAL_CHUNK_PREFIX = "signal-";
    private static final int MARKDOWN_TOKEN_SCORE_CAP = 20;
    private static final int BUNDLE_ROLE_SCORE_PENALTY = 40;
    private static final int DOC_ID_TITLE_TOKEN_AFFINITY_BOOST = 15;
    private static final int DAMAGE_BEFORE_OPERATION_TAG_BOOST = 25;
    private static final int MIN_DOC_IDENTITY_TOKEN_LENGTH = 3;
    private static final int MIN_DOMAIN_TERM_LENGTH = 4;
    private static final int MAX_DIAGNOSTIC_DOCUMENTS = 12;
    private static final int MAX_EVIDENCE_PER_DOCUMENT = 8;

    private static final int SCORE_EXACT_ALIAS_PHRASE = 100;
    private static final int SCORE_DOC_ID_FILENAME_PHRASE = 60;
    private static final int SCORE_DOCUMENT_KIND = 40;

    /** Query tokens aligned with troubleshooting headings/signals in the indexed corpus (§5.2). */
    private static final Set<String> OPERATIONAL_TROUBLE_QUERY_TOKENS = Set.of(
            "alarm", "bearing", "cause", "causes", "eliminate", "elimination", "lube",
            "malfunction", "overspeed", "pressure", "restart", "restarting", "shutdown",
            "trip", "trips", "trouble", "turbine", "vibration");

    private static final Set<String> INSTALLATION_INTENT_QUERY_TOKENS = Set.of(
            "foundation", "alignment", "aligning", "install", "installation",
            "commissioning", "startup");
    private static final int SCORE_ASSET_MODEL = 25;
    private static final int SCORE_MANUFACTURER = 20;
    private static final int SCORE_DOMAIN_TERM = 10;
    private static final int SCORE_DOCUMENT_TYPE_ENUM = 5;
    private static final int SCORE_ASSET_CONTEXT_VALUE = 15;

    private static final int T_HIGH = 80;
    private static final int T_MARGIN = 30;

    private static final Map<String, Integer> CONTENT_TYPE_RANK = Map.of(
            "troubleshooting", 0,
            "maintenance", 1,
            "section", 2,
            "semantic-section", 2,
            "page", 3);

    private DocumentKnowledgeSearchScorer() {}

    public static final class SearchRequest {
        private final String query;
        private final List<String> queryTokens;
        private final List<String> queryPhrases;
        private final List<String> signalNames;
        private final List<String> documentTypes;
        private final List<String> assetContextTokens;
        private final List<String> assetContextPhrases;
        private final List<String> documentIds;
        private final boolean alarmOrShutdownContext;

        public SearchRequest(
                String query,
                List<String> queryTokens,
                List<String> queryPhrases,
                List<String> signalNames,
                List<String> documentTypes,
                List<String> assetContextTokens,
                List<String> assetContextPhrases,
                List<String> documentIds,
                boolean alarmOrShutdownContext) {
            this.query = query;
            this.queryTokens = queryTokens;
            this.queryPhrases = queryPhrases;
            this.signalNames = signalNames;
            this.documentTypes = documentTypes;
            this.assetContextTokens = assetContextTokens;
            this.assetContextPhrases = assetContextPhrases;
            this.documentIds = documentIds;
            this.alarmOrShutdownContext = alarmOrShutdownContext;
        }

        public static SearchRequest from(JsonNode args) {
            String query = text(args, "query");
            List<String> queryTokens = tokenize(query);
            List<String> queryPhrases = extractPhrases(query);
            List<String> signalNames = new ArrayList<>();
            boolean alarmContext = false;
            JsonNode signals = args.get("signals");
            if (signals != null && signals.isArray()) {
                for (JsonNode signal : signals) {
                    String name = text(signal, "name");
                    if (name != null && !name.isBlank()) {
                        signalNames.add(name.trim());
                    }
                    String kind = text(signal, "kind");
                    if (kind != null && "alarm".equalsIgnoreCase(kind.trim())) {
                        alarmContext = true;
                    }
                }
            }
            List<String> documentTypes = readStringArray(args.get("documentTypes"));
            List<String> documentIds = readStringArray(args.get("documentIds"));
            AssetContextEvidence assetContext = parseAssetContext(args.get("assetContext"));
            String qLower = query != null ? query.toLowerCase(Locale.ROOT) : "";
            if (qLower.contains("alarm") || qLower.contains("shutdown")) {
                alarmContext = true;
            }
            return new SearchRequest(
                    query,
                    queryTokens,
                    queryPhrases,
                    List.copyOf(signalNames),
                    documentTypes,
                    assetContext.tokens(),
                    assetContext.phrases(),
                    documentIds,
                    alarmContext);
        }

        public String query() {
            return query;
        }

        public List<String> queryTokens() {
            return queryTokens;
        }

        public List<String> queryPhrases() {
            return queryPhrases;
        }

        public List<String> signalNames() {
            return signalNames;
        }

        public List<String> documentTypes() {
            return documentTypes;
        }

        public List<String> assetContextTokens() {
            return assetContextTokens;
        }

        public List<String> assetContextPhrases() {
            return assetContextPhrases;
        }

        public List<String> documentIds() {
            return documentIds;
        }

        public boolean alarmOrShutdownContext() {
            return alarmOrShutdownContext;
        }
    }

    public static final class DocumentScoreEntry {
        private final String docId;
        private final int score;
        private final List<String> matchedEvidence;

        public DocumentScoreEntry(String docId, int score, List<String> matchedEvidence) {
            this.docId = docId;
            this.score = score;
            this.matchedEvidence = List.copyOf(matchedEvidence);
        }

        public String docId() {
            return docId;
        }

        public int score() {
            return score;
        }

        public List<String> matchedEvidence() {
            return matchedEvidence;
        }
    }

    public static final class RankResult {
        private final List<ScoredMatch> matches;
        private final List<DocumentScoreEntry> documentScores;
        private final List<String> selectedDocIds;
        private final String selectionMode;

        public RankResult(
                List<ScoredMatch> matches,
                List<DocumentScoreEntry> documentScores,
                List<String> selectedDocIds,
                String selectionMode) {
            this.matches = List.copyOf(matches);
            this.documentScores = List.copyOf(documentScores);
            this.selectedDocIds = List.copyOf(selectedDocIds);
            this.selectionMode = selectionMode;
        }

        public List<ScoredMatch> matches() {
            return matches;
        }

        public List<DocumentScoreEntry> documentScores() {
            return documentScores;
        }

        public List<String> selectedDocIds() {
            return selectedDocIds;
        }

        public String selectionMode() {
            return selectionMode;
        }
    }

    public static final class ScoredMatch {
        private final DocumentKnowledgeChunk chunk;
        private final int score;
        private final int bm25Boost;

        public ScoredMatch(DocumentKnowledgeChunk chunk, int score) {
            this(chunk, score, 0);
        }

        public ScoredMatch(DocumentKnowledgeChunk chunk, int score, int bm25Boost) {
            this.chunk = chunk;
            this.score = score;
            this.bm25Boost = bm25Boost;
        }

        public DocumentKnowledgeChunk chunk() {
            return chunk;
        }

        /** Total rank score (additive scoreChunk + document boost + BM25 boost). */
        public int score() {
            return score;
        }

        /** The BM25/IDF component included in {@link #score()} (diagnostic; design §4). */
        public int bm25Boost() {
            return bm25Boost;
        }
    }

    public static List<ScoredMatch> scoreAndRank(
            List<DocumentKnowledgeChunk> chunks,
            Function<String, Optional<DocumentKnowledgePackageManifest>> manifestFor,
            SearchRequest request,
            int limit) {
        return scoreAndRankWithDiagnostics(chunks, manifestFor, request, limit).matches();
    }

    public static RankResult scoreAndRankWithDiagnostics(
            List<DocumentKnowledgeChunk> chunks,
            Function<String, Optional<DocumentKnowledgePackageManifest>> manifestFor,
            SearchRequest request,
            int limit) {
        if (chunks == null || chunks.isEmpty() || limit <= 0) {
            return new RankResult(List.of(), List.of(), List.of(), "empty");
        }

        Set<String> allowedDocIds = resolveAllowedDocIds(chunks, request);
        Map<String, DocumentKnowledgePackageManifest> manifestsByDocId = new TreeMap<>();
        Map<String, List<DocumentKnowledgeChunk>> chunksByDocId = new TreeMap<>();
        for (DocumentKnowledgeChunk chunk : chunks) {
            if (!allowedDocIds.isEmpty() && !allowedDocIds.contains(chunk.docId())) {
                continue;
            }
            Optional<DocumentKnowledgePackageManifest> manifestOpt = manifestFor.apply(chunk.docId());
            if (!passesDocumentTypeFilter(request, manifestOpt.orElse(null))) {
                continue;
            }
            manifestsByDocId.putIfAbsent(chunk.docId(), manifestOpt.orElse(null));
            chunksByDocId.computeIfAbsent(chunk.docId(), k -> new ArrayList<>()).add(chunk);
        }
        if (chunksByDocId.isEmpty()) {
            return new RankResult(List.of(), List.of(), List.of(), "empty");
        }

        Map<String, DocumentScoreEntry> documentScores = new LinkedHashMap<>();
        for (Map.Entry<String, DocumentKnowledgePackageManifest> entry : manifestsByDocId.entrySet()) {
            DocumentScoreAccumulator acc = scoreDocument(entry.getKey(), entry.getValue(), request);
            if (acc.score > 0) {
                documentScores.put(entry.getKey(), new DocumentScoreEntry(entry.getKey(), acc.score, acc.evidence));
            }
        }

        SelectionPlan plan = buildSelectionPlan(documentScores, request, chunksByDocId.keySet());
        Map<String, Integer> documentBoosts = new TreeMap<>();
        for (String docId : plan.selectedDocIds) {
            DocumentScoreEntry entry = documentScores.get(docId);
            documentBoosts.put(docId, entry != null ? entry.score() : 0);
        }

        // BM25/IDF corpus = ALL chunks in the selected documents (not only the
        // additive-positive ones), so document frequencies are correct and a chunk that
        // only BM25 finds is still eligible (design §4 candidate-set rule).
        List<DocumentKnowledgeChunk> candidateChunks = new ArrayList<>();
        for (Map.Entry<String, List<DocumentKnowledgeChunk>> entry : chunksByDocId.entrySet()) {
            if (plan.selectedDocIds.contains(entry.getKey())) {
                candidateChunks.addAll(entry.getValue());
            }
        }
        DocumentKnowledgeBm25 bm25 = DocumentKnowledgeBm25.forCorpus(candidateChunks, bm25QueryTerms(request));

        List<ScoredMatch> allScored = new ArrayList<>();
        for (Map.Entry<String, List<DocumentKnowledgeChunk>> entry : chunksByDocId.entrySet()) {
            if (!plan.selectedDocIds.contains(entry.getKey())) {
                continue;
            }
            DocumentKnowledgePackageManifest manifest = manifestsByDocId.get(entry.getKey());
            int boost = documentBoosts.getOrDefault(entry.getKey(), 0);
            for (DocumentKnowledgeChunk chunk : entry.getValue()) {
                int chunkScore = scoreChunk(chunk, manifest, request);
                int bm25Boost = bm25.boostFor(chunk);
                if (chunkScore > 0 || bm25Boost > 0) {
                    allScored.add(new ScoredMatch(chunk, chunkScore + boost + bm25Boost, bm25Boost));
                }
            }
        }
        allScored.sort(comparator());

        List<ScoredMatch> ranked = plan.diversified
                ? diversify(allScored, limit, perDocCap(limit, plan.selectedDocIds.size()))
                : truncate(allScored, limit);

        List<DocumentScoreEntry> diagnosticScores = documentScores.values().stream()
                .sorted(Comparator.comparingInt(DocumentScoreEntry::score).reversed()
                        .thenComparing(DocumentScoreEntry::docId))
                .limit(MAX_DIAGNOSTIC_DOCUMENTS)
                .map(e -> new DocumentScoreEntry(
                        e.docId(),
                        e.score(),
                        e.matchedEvidence().stream().limit(MAX_EVIDENCE_PER_DOCUMENT).toList()))
                .toList();

        return new RankResult(ranked, diagnosticScores, List.copyOf(plan.selectedDocIds), plan.selectionMode);
    }

    /** Query side for BM25 (design §4): query tokens + deduped signal-name and asset-context tokens. */
    private static List<String> bm25QueryTerms(SearchRequest request) {
        LinkedHashSet<String> terms = new LinkedHashSet<>(request.queryTokens());
        for (String signal : request.signalNames()) {
            terms.addAll(tokenize(signal));
        }
        terms.addAll(request.assetContextTokens());
        return new ArrayList<>(terms);
    }

    static int scoreChunk(
            DocumentKnowledgeChunk chunk,
            DocumentKnowledgePackageManifest manifest,
            SearchRequest request) {
        int score = 0;

        for (String signalName : request.signalNames()) {
            for (DocumentKnowledgeChunk.SignalEntry signal : chunk.signals()) {
                if (signalName.equalsIgnoreCase(signal.name())) {
                    score += 50;
                }
            }
            if (signalName.toLowerCase(Locale.ROOT).contains("damage")
                    && chunkHasSignalName(chunk, "damage-before-operation")) {
                score += 50;
            }
        }

        for (String token : request.queryTokens()) {
            for (DocumentKnowledgeChunk.SignalEntry signal : chunk.signals()) {
                if (queryTokenMatchesSignalName(token, signal.name())) {
                    score += 25;
                }
            }
        }

        String query = request.query();
        if (query != null && !query.isBlank() && chunk.heading() != null) {
            if (chunk.heading().toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                score += 35;
            }
        }

        for (String token : request.queryTokens()) {
            if (matchesTagOrComponent(chunk, token)) {
                score += 25;
            }
        }
        for (String signalName : request.signalNames()) {
            if (matchesTagOrComponent(chunk, signalName)) {
                score += 25;
            }
        }

        if (manifest != null && manifest.documentType() != null && !request.documentTypes().isEmpty()) {
            for (String filter : request.documentTypes()) {
                if (filter != null && filter.equalsIgnoreCase(manifest.documentType())) {
                    score += 15;
                }
            }
        }

        if ((request.alarmOrShutdownContext() || queryHasOperationalTroubleIntent(request))
                && "troubleshooting".equalsIgnoreCase(chunk.contentType())) {
            score += 15;
        }

        for (String token : request.queryTokens()) {
            if (containsToken(chunk.heading(), token)) {
                score += 8;
            }
        }

        for (String token : request.queryTokens()) {
            if (containsToken(chunk.summary(), token) || containsInTags(chunk.tags(), token)) {
                score += 5;
            }
        }

        int markdownScore = 0;
        for (String token : request.queryTokens()) {
            if (containsToken(chunk.markdown(), token)) {
                markdownScore += 1;
            }
        }
        score += Math.min(markdownScore, MARKDOWN_TOKEN_SCORE_CAP);

        score += scoreDocIdTitleTokenAffinity(chunk, manifest, request);

        if (queryHasDamageIntent(request)
                && matchesTagOrComponent(chunk, "damage-before-operation")) {
            score += DAMAGE_BEFORE_OPERATION_TAG_BOOST;
        }

        for (String token : request.queryTokens()) {
            if ("damage-before-operation".equalsIgnoreCase(token)
                    && matchesTagOrComponent(chunk, "damage-before-operation")) {
                score += 25;
            }
        }

        if (manifest != null && manifest.documentRole() != null
                && "bundle".equalsIgnoreCase(manifest.documentRole().trim())) {
            score = Math.max(0, score - BUNDLE_ROLE_SCORE_PENALTY);
        }

        return score;
    }

    /** Maximum achievable chunk score for a document without document-level boost (calibration tests). */
    static int maxAchievableChunkScoreForDocument(
            List<DocumentKnowledgeChunk> chunks,
            DocumentKnowledgePackageManifest manifest,
            SearchRequest request) {
        int max = 0;
        for (DocumentKnowledgeChunk chunk : chunks) {
            max = Math.max(max, scoreChunk(chunk, manifest, request));
        }
        return max;
    }

    static String buildSnippet(DocumentKnowledgeChunk chunk, int maxChars) {
        String base = chunk.summary();
        if (base == null || base.isBlank()) {
            base = chunk.markdown();
        }
        if (base == null) {
            base = "";
        }
        return DocumentKnowledgeTextBounds.truncateSnippet(base, maxChars);
    }

    static int resolveEffectiveLimit(int requestedLimit, DocumentKnowledgeSettings settings, DocumentKnowledgeWarnings warnings) {
        int limit = requestedLimit;
        if (limit <= 0) {
            warnings.add("LIMIT_CLAMPED", "Search limit was clamped to the configured bounds.");
            limit = settings.searchDefaultLimit();
        }
        if (limit > settings.searchMaxLimit()) {
            warnings.add("LIMIT_CLAMPED", "Search limit was clamped to the configured bounds.");
            limit = settings.searchMaxLimit();
        }
        return limit;
    }

    private static final class SelectionPlan {
        private final List<String> selectedDocIds;
        private final boolean diversified;
        private final String selectionMode;

        private SelectionPlan(List<String> selectedDocIds, boolean diversified, String selectionMode) {
            this.selectedDocIds = selectedDocIds;
            this.diversified = diversified;
            this.selectionMode = selectionMode;
        }
    }

    private static final class DocumentScoreAccumulator {
        private int score;
        private final List<String> evidence = new ArrayList<>();
    }

    private static SelectionPlan buildSelectionPlan(
            Map<String, DocumentScoreEntry> documentScores,
            SearchRequest request,
            Set<String> availableDocIds) {
        if (!request.documentIds().isEmpty()) {
            List<String> selected = request.documentIds().stream()
                    .filter(id -> availableDocIds.contains(id))
                    .distinct()
                    .sorted()
                    .toList();
            return new SelectionPlan(selected, false, "documentIds-filter");
        }

        List<DocumentScoreEntry> ranked = documentScores.values().stream()
                .sorted(Comparator.comparingInt(DocumentScoreEntry::score).reversed()
                        .thenComparing(DocumentScoreEntry::docId))
                .toList();
        if (ranked.isEmpty()) {
            List<String> fallback = availableDocIds.stream().sorted().toList();
            return new SelectionPlan(fallback, fallback.size() > 1, "diversified");
        }

        DocumentScoreEntry top = ranked.get(0);
        DocumentScoreEntry second = ranked.size() > 1 ? ranked.get(1) : null;
        if (hasHardIdentityEvidence(top) && top.score() >= T_HIGH
                && (second == null || top.score() - second.score() >= T_MARGIN)) {
            return new SelectionPlan(List.of(top.docId()), false, "hard-single");
        }

        List<String> selected = availableDocIds.stream().sorted().toList();
        return new SelectionPlan(selected, selected.size() > 1, "diversified");
    }

    private static boolean hasHardIdentityEvidence(DocumentScoreEntry entry) {
        for (String ev : entry.matchedEvidence()) {
            if (ev.startsWith("alias:") || ev.startsWith("title:") || ev.startsWith("docId:")
                    || ev.startsWith("filename:")) {
                return true;
            }
        }
        return false;
    }

    private static int perDocCap(int limit, int selectedDocCount) {
        if (selectedDocCount <= 1) {
            return limit;
        }
        return Math.max(1, (limit + 1) / 2);
    }

    private static List<ScoredMatch> diversify(List<ScoredMatch> sorted, int limit, int perDocCap) {
        Map<String, Integer> counts = new TreeMap<>();
        List<ScoredMatch> out = new ArrayList<>();
        Set<String> included = new LinkedHashSet<>();
        for (ScoredMatch match : sorted) {
            String docId = match.chunk().docId();
            int used = counts.getOrDefault(docId, 0);
            if (used >= perDocCap) {
                continue;
            }
            out.add(match);
            included.add(match.chunk().docId() + "\u0001" + match.chunk().chunkId());
            counts.put(docId, used + 1);
            if (out.size() >= limit) {
                break;
            }
        }
        if (out.size() < limit) {
            for (ScoredMatch match : sorted) {
                String key = match.chunk().docId() + "\u0001" + match.chunk().chunkId();
                if (included.contains(key)) {
                    continue;
                }
                String docId = match.chunk().docId();
                int used = counts.getOrDefault(docId, 0);
                if (used >= perDocCap) {
                    continue;
                }
                out.add(match);
                included.add(key);
                counts.put(docId, used + 1);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        if (out.size() < limit && !hasUncappedRemainingChunk(sorted, included, counts, perDocCap)) {
            for (ScoredMatch match : sorted) {
                String key = match.chunk().docId() + "\u0001" + match.chunk().chunkId();
                if (included.contains(key)) {
                    continue;
                }
                out.add(match);
                included.add(key);
                if (out.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static boolean hasUncappedRemainingChunk(
            List<ScoredMatch> sorted,
            Set<String> included,
            Map<String, Integer> counts,
            int perDocCap) {
        for (ScoredMatch match : sorted) {
            String key = match.chunk().docId() + "\u0001" + match.chunk().chunkId();
            if (included.contains(key)) {
                continue;
            }
            if (counts.getOrDefault(match.chunk().docId(), 0) < perDocCap) {
                return true;
            }
        }
        return false;
    }

    private static List<ScoredMatch> truncate(List<ScoredMatch> sorted, int limit) {
        if (sorted.size() <= limit) {
            return List.copyOf(sorted);
        }
        return List.copyOf(sorted.subList(0, limit));
    }

    private static Set<String> resolveAllowedDocIds(List<DocumentKnowledgeChunk> chunks, SearchRequest request) {
        if (request.documentIds().isEmpty()) {
            return Set.of();
        }
        Set<String> known = new LinkedHashSet<>();
        for (DocumentKnowledgeChunk chunk : chunks) {
            known.add(chunk.docId());
        }
        Set<String> allowed = new LinkedHashSet<>();
        for (String docId : request.documentIds()) {
            if (docId != null && known.contains(docId)) {
                allowed.add(docId);
            }
        }
        return allowed;
    }

    private static DocumentScoreAccumulator scoreDocument(
            String docId,
            DocumentKnowledgePackageManifest manifest,
            SearchRequest request) {
        DocumentScoreAccumulator acc = new DocumentScoreAccumulator();
        if (manifest == null) {
            matchDocIdTokens(acc, docId, request);
            return acc;
        }

        DocumentKnowledgeDocumentProfile profile = manifest.documentProfile();
        for (String phrase : request.queryPhrases()) {
            if (phraseMatches(phrase, manifest.title())) {
                addEvidence(acc, SCORE_EXACT_ALIAS_PHRASE, "title:" + phrase);
            }
            for (String alias : profile.aliases()) {
                if (phraseMatches(phrase, alias)) {
                    addEvidence(acc, SCORE_EXACT_ALIAS_PHRASE, "alias:" + alias);
                }
            }
        }
        for (String phrase : request.assetContextPhrases()) {
            for (String alias : profile.aliases()) {
                if (phraseMatches(phrase, alias)) {
                    addEvidence(acc, SCORE_EXACT_ALIAS_PHRASE, "alias:" + alias);
                }
            }
            if (phraseMatches(phrase, manifest.title())) {
                addEvidence(acc, SCORE_EXACT_ALIAS_PHRASE, "title:" + phrase);
            }
        }

        matchDocIdTokens(acc, docId, request);
        if (manifest.sourceFileName() != null) {
            matchFilenameTokens(acc, manifest.sourceFileName(), request);
        }

        String normalizedType = DocumentKnowledgeDocumentProfile.normalizeDocumentKind(manifest.documentType());
        boolean opTrouble = queryHasOperationalTroubleIntent(request);
        boolean installIntent = queryHasInstallationIntent(request);
        boolean steamTurbineTrouble = querySuggestsSteamTurbineOperationalTrouble(request);
        if (opTrouble && !installIntent && "operating_manual".equals(normalizedType)) {
            if (!steamTurbineTrouble || manifestIsSteamTurbineFamily(manifest, profile)
                    || (queryHasChillerCaptureIdentity(request) && manifestIsChillerCaptureFamily(manifest, profile))) {
                addEvidence(acc, SCORE_DOCUMENT_KIND, "kind:operating_manual");
            }
            if (steamTurbineTrouble && manifestIsSteamTurbineFamily(manifest, profile)) {
                addEvidence(acc, SCORE_ASSET_MODEL, "asset:steam_turbine_family");
            }
        } else if (installIntent && "installation_spec".equals(normalizedType)) {
            addEvidence(acc, SCORE_DOCUMENT_KIND, "kind:installation_spec");
        } else if (!opTrouble && !installIntent) {
            for (String token : allRequestTokens(request)) {
                if (tokenMatchesDocumentKind(token, normalizedType)) {
                    addEvidence(acc, SCORE_DOCUMENT_KIND, "kind:" + normalizedType);
                }
                for (String kind : profile.documentKinds()) {
                    if (tokenMatchesDocumentKind(token, DocumentKnowledgeDocumentProfile.normalizeDocumentKind(kind))) {
                        addEvidence(acc, SCORE_DOCUMENT_KIND, "kind:" + kind);
                    }
                }
            }
        }

        for (String model : manifest.assetModels()) {
            for (String phrase : allRequestPhrases(request)) {
                if (phraseMatches(phrase, model)) {
                    addEvidence(acc, SCORE_ASSET_MODEL, "asset:" + model);
                }
            }
            for (String token : allRequestTokens(request)) {
                if (containsToken(model, token)) {
                    addEvidence(acc, SCORE_ASSET_MODEL, "asset:" + model);
                }
            }
        }
        for (String model : profile.assetModels()) {
            for (String phrase : allRequestPhrases(request)) {
                if (phraseMatches(phrase, model)) {
                    addEvidence(acc, SCORE_ASSET_MODEL, "asset:" + model);
                }
            }
        }

        for (String manufacturer : profile.manufacturers()) {
            for (String token : allRequestTokens(request)) {
                if (containsToken(manufacturer, token)) {
                    addEvidence(acc, SCORE_MANUFACTURER, "manufacturer:" + manufacturer);
                }
            }
        }

        for (String term : profile.domainTerms()) {
            if (term == null || term.length() < MIN_DOMAIN_TERM_LENGTH) {
                continue;
            }
            for (String token : allRequestTokens(request)) {
                if (term.equalsIgnoreCase(token)) {
                    addEvidence(acc, SCORE_DOMAIN_TERM, "domain:" + term);
                }
            }
        }

        if (manifest.documentType() != null) {
            for (String filter : request.documentTypes()) {
                if (filter != null && filter.equalsIgnoreCase(manifest.documentType())) {
                    addEvidence(acc, SCORE_DOCUMENT_TYPE_ENUM, "documentType:" + manifest.documentType());
                }
            }
        }

        for (String value : request.assetContextTokens()) {
            if (value.length() < MIN_DOC_IDENTITY_TOKEN_LENGTH) {
                continue;
            }
            if (containsToken(manifest.title(), value)
                    || containsToken(docId, value)
                    || profile.aliases().stream().anyMatch(a -> containsToken(a, value))) {
                addEvidence(acc, SCORE_ASSET_CONTEXT_VALUE, "assetContext:" + value);
            }
        }

        return acc;
    }

    private static void matchDocIdTokens(DocumentScoreAccumulator acc, String docId, SearchRequest request) {
        List<String> docTokens = docIdentityTokens(docId, null);
        for (String phrase : request.queryPhrases()) {
            String normalized = phrase.toLowerCase(Locale.ROOT);
            for (String docToken : docTokens) {
                if (normalized.contains(docToken) && docToken.length() >= MIN_DOC_IDENTITY_TOKEN_LENGTH) {
                    addEvidence(acc, SCORE_DOC_ID_FILENAME_PHRASE, "docId:" + docToken);
                }
            }
        }
        for (String token : request.queryTokens()) {
            if (token == null || token.length() < MIN_DOC_IDENTITY_TOKEN_LENGTH) {
                continue;
            }
            for (String docToken : docTokens) {
                if (docToken.equals(token)) {
                    addEvidence(acc, SCORE_DOC_ID_FILENAME_PHRASE, "docId:" + docToken);
                }
            }
        }
    }

    private static void matchFilenameTokens(DocumentScoreAccumulator acc, String filename, SearchRequest request) {
        for (String token : request.queryTokens()) {
            if (token != null && containsToken(filename, token)) {
                addEvidence(acc, SCORE_DOC_ID_FILENAME_PHRASE, "filename:" + token);
            }
        }
    }

    private static void addEvidence(DocumentScoreAccumulator acc, int weight, String label) {
        if (acc.evidence.contains(label)) {
            return;
        }
        acc.score += weight;
        acc.evidence.add(label);
    }

    private static List<String> allRequestTokens(SearchRequest request) {
        List<String> tokens = new ArrayList<>(request.queryTokens());
        tokens.addAll(request.assetContextTokens());
        return tokens;
    }

    private static List<String> allRequestPhrases(SearchRequest request) {
        List<String> phrases = new ArrayList<>(request.queryPhrases());
        phrases.addAll(request.assetContextPhrases());
        return phrases;
    }

    private static boolean tokenMatchesDocumentKind(String token, String kind) {
        if (kind == null || kind.isBlank() || token == null || token.isBlank()) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        return kind.contains(t) || t.contains(kind.replace('_', ' ')) || kind.replace('_', ' ').contains(t);
    }

    private static boolean phraseMatches(String phrase, String candidate) {
        if (phrase == null || candidate == null) {
            return false;
        }
        String p = phrase.trim().toLowerCase(Locale.ROOT);
        String c = candidate.trim().toLowerCase(Locale.ROOT);
        return !p.isEmpty() && (p.equals(c) || c.contains(p) || p.contains(c));
    }

    private static final class AssetContextEvidence {
        private final List<String> tokens;
        private final List<String> phrases;

        private AssetContextEvidence(List<String> tokens, List<String> phrases) {
            this.tokens = tokens;
            this.phrases = phrases;
        }

        private List<String> tokens() {
            return tokens;
        }

        private List<String> phrases() {
            return phrases;
        }
    }

    private static AssetContextEvidence parseAssetContext(JsonNode assetContext) {
        if (assetContext == null || !assetContext.isObject()) {
            return new AssetContextEvidence(List.of(), List.of());
        }
        List<String> tokens = new ArrayList<>();
        List<String> phrases = new ArrayList<>();
        collectAssetContext(assetContext, tokens, phrases);
        return new AssetContextEvidence(List.copyOf(tokens), List.copyOf(phrases));
    }

    private static void collectAssetContext(JsonNode node, List<String> tokens, List<String> phrases) {
        if (node == null) {
            return;
        }
        if (node.isTextual()) {
            String text = node.asText().trim();
            if (!text.isEmpty()) {
                tokens.addAll(tokenize(text));
                phrases.addAll(extractPhrases(text));
            }
            return;
        }
        if (node.isNumber() || node.isBoolean()) {
            tokens.addAll(tokenize(node.asText()));
            return;
        }
        if (node.isArray()) {
            for (JsonNode child : node) {
                collectAssetContext(child, tokens, phrases);
            }
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(e -> collectAssetContext(e.getValue(), tokens, phrases));
        }
    }

    private static List<String> extractPhrases(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> phrases = new ArrayList<>();
        String trimmed = text.trim();
        if (trimmed.length() >= 4) {
            phrases.add(trimmed);
        }
        for (String part : trimmed.split("[,;|]")) {
            String p = part.trim();
            if (p.length() >= 4) {
                phrases.add(p);
            }
        }
        return List.copyOf(phrases);
    }

    private static boolean passesDocumentTypeFilter(SearchRequest request, DocumentKnowledgePackageManifest manifest) {
        if (request.documentTypes().isEmpty()) {
            return true;
        }
        if (manifest == null || manifest.documentType() == null || manifest.documentType().isBlank()) {
            return false;
        }
        for (String filter : request.documentTypes()) {
            if (filter != null && filter.equalsIgnoreCase(manifest.documentType())) {
                return true;
            }
        }
        return false;
    }

    private static int scoreDocIdTitleTokenAffinity(
            DocumentKnowledgeChunk chunk,
            DocumentKnowledgePackageManifest manifest,
            SearchRequest request) {
        List<String> docTokens = docIdentityTokens(chunk.docId(), manifest);
        if (docTokens.isEmpty()) {
            return 0;
        }
        int boost = 0;
        for (String queryToken : allRequestTokens(request)) {
            if (queryToken == null || queryToken.length() < MIN_DOC_IDENTITY_TOKEN_LENGTH) {
                continue;
            }
            for (String docToken : docTokens) {
                if (docToken.equals(queryToken)) {
                    boost += DOC_ID_TITLE_TOKEN_AFFINITY_BOOST;
                    break;
                }
            }
        }
        return boost;
    }

    private static List<String> docIdentityTokens(String docId, DocumentKnowledgePackageManifest manifest) {
        List<String> tokens = new ArrayList<>();
        appendIdentityTokens(tokens, docId);
        if (manifest != null) {
            appendIdentityTokens(tokens, manifest.title());
            appendIdentityTokens(tokens, manifest.sourceFileName());
        }
        return List.copyOf(tokens);
    }

    private static void appendIdentityTokens(List<String> tokens, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String part : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part != null && part.length() >= MIN_DOC_IDENTITY_TOKEN_LENGTH) {
                tokens.add(part);
            }
        }
    }

    private static boolean queryHasOperationalTroubleIntent(SearchRequest request) {
        for (String token : request.queryTokens()) {
            if (OPERATIONAL_TROUBLE_QUERY_TOKENS.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return request.alarmOrShutdownContext();
    }

    private static boolean queryHasChillerCaptureIdentity(SearchRequest request) {
        String query = request.query();
        if (query == null || query.isBlank()) {
            return false;
        }
        String qLower = query.toLowerCase(Locale.ROOT);
        return qLower.contains("chiller") || qLower.contains("co2") || qLower.contains("capture");
    }

    private static boolean querySuggestsSteamTurbineOperationalTrouble(SearchRequest request) {
        if (!queryHasOperationalTroubleIntent(request) || queryHasChillerCaptureIdentity(request)) {
            return false;
        }
        for (String token : request.queryTokens()) {
            String t = token.toLowerCase(Locale.ROOT);
            if ("bearing".equals(t) || "vibration".equals(t) || "lube".equals(t) || "turbine".equals(t)
                    || "generator".equals(t) || "overspeed".equals(t) || "steam".equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean manifestIsSteamTurbineFamily(
            DocumentKnowledgePackageManifest manifest,
            DocumentKnowledgeDocumentProfile profile) {
        return identityBlob(manifest, profile).contains("turbine")
                || identityBlob(manifest, profile).contains("turbo")
                || identityBlob(manifest, profile).contains("generator");
    }

    private static boolean manifestIsChillerCaptureFamily(
            DocumentKnowledgePackageManifest manifest,
            DocumentKnowledgeDocumentProfile profile) {
        String blob = identityBlob(manifest, profile);
        return blob.contains("chiller") || blob.contains("co2") || blob.contains("capture");
    }

    private static String identityBlob(
            DocumentKnowledgePackageManifest manifest,
            DocumentKnowledgeDocumentProfile profile) {
        StringBuilder sb = new StringBuilder();
        if (manifest.title() != null) {
            sb.append(manifest.title().toLowerCase(Locale.ROOT)).append(' ');
        }
        if (manifest.assetModels() != null) {
            for (String model : manifest.assetModels()) {
                if (model != null) {
                    sb.append(model.toLowerCase(Locale.ROOT)).append(' ');
                }
            }
        }
        for (String model : profile.assetModels()) {
            sb.append(model.toLowerCase(Locale.ROOT)).append(' ');
        }
        for (String manufacturer : profile.manufacturers()) {
            sb.append(manufacturer.toLowerCase(Locale.ROOT)).append(' ');
        }
        return sb.toString();
    }

    private static boolean queryHasInstallationIntent(SearchRequest request) {
        String query = request.query();
        if (query == null || query.isBlank()) {
            return false;
        }
        String qLower = query.toLowerCase(Locale.ROOT);
        if (qLower.contains("back pressure") || qLower.contains("start-up") || qLower.contains("startup")) {
            return true;
        }
        for (String token : request.queryTokens()) {
            if (INSTALLATION_INTENT_QUERY_TOKENS.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean queryTokenMatchesSignalName(String token, String signalName) {
        if (token == null || signalName == null || token.isBlank() || signalName.isBlank()) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        String s = signalName.toLowerCase(Locale.ROOT);
        if (t.equals(s)) {
            return true;
        }
        if (t.length() >= 4 && s.length() >= 4 && (t.startsWith(s) || s.startsWith(t))) {
            return true;
        }
        return false;
    }

    private static boolean queryHasDamageIntent(SearchRequest request) {
        for (String token : request.queryTokens()) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (token.contains("damage") || "damaged".equals(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean chunkHasSignalName(DocumentKnowledgeChunk chunk, String name) {
        for (DocumentKnowledgeChunk.SignalEntry signal : chunk.signals()) {
            if (signal.name() != null && name.equalsIgnoreCase(signal.name().trim())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesTagOrComponent(DocumentKnowledgeChunk chunk, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        for (String tag : chunk.tags()) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).equals(t)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsToken(String text, String token) {
        return text != null && token != null && !token.isBlank()
                && text.toLowerCase(Locale.ROOT).contains(token.toLowerCase(Locale.ROOT));
    }

    private static boolean containsInTags(List<String> tags, String token) {
        if (tags == null || token == null) {
            return false;
        }
        String t = token.toLowerCase(Locale.ROOT);
        for (String tag : tags) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains(t)) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<ScoredMatch> comparator() {
        // C2 (document-retrieval-convergence): substantive content sorts before heading/marker
        // `signal-*` chunks as the PRIMARY key, so heading-only signposts can never outrank
        // substantive answer content in `matches[]`. Ranking-only — document selection,
        // `documentScores`, BM25, and exposed score arithmetic are untouched. A single global
        // total order keeps the comparator transitive.
        return Comparator
                .comparingInt((ScoredMatch m) -> isSignalChunk(m.chunk()) ? 1 : 0)
                .thenComparing(Comparator.comparingInt(ScoredMatch::score).reversed())
                .thenComparingInt(m -> contentTypeRank(m.chunk().contentType()))
                .thenComparingInt(m -> semanticBeforePageRank(m.chunk()))
                .thenComparing(m -> m.chunk().docId(), Comparator.nullsLast(String::compareTo))
                .thenComparing(m -> m.chunk().chunkId(), Comparator.nullsLast(String::compareTo));
    }

    /** Heading/marker chunks (`signal-*`) are signposts, not answers — demoted below content in `matches[]`. */
    private static boolean isSignalChunk(DocumentKnowledgeChunk chunk) {
        String chunkId = chunk.chunkId();
        return chunkId != null && chunkId.startsWith(SIGNAL_CHUNK_PREFIX);
    }

    private static int contentTypeRank(String contentType) {
        if (contentType == null) {
            return 99;
        }
        return CONTENT_TYPE_RANK.getOrDefault(contentType.toLowerCase(Locale.ROOT), 50);
    }

    private static int semanticBeforePageRank(DocumentKnowledgeChunk chunk) {
        if ("page".equalsIgnoreCase(chunk.contentType())) {
            return 1;
        }
        return 0;
    }

    private static List<String> tokenize(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String[] parts = query.toLowerCase(Locale.ROOT).split("[^a-z0-9]+");
        List<String> tokens = new ArrayList<>();
        for (String part : parts) {
            if (part != null && part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens.isEmpty() ? List.of(query.trim().toLowerCase(Locale.ROOT)) : List.copyOf(tokens);
    }

    private static List<String> readStringArray(JsonNode arr) {
        if (arr == null || !arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && item.isTextual() && !item.asText().isBlank()) {
                out.add(item.asText().trim());
            }
        }
        return List.copyOf(out);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode n = node.get(field);
        return n != null && n.isTextual() ? n.asText() : null;
    }
}
