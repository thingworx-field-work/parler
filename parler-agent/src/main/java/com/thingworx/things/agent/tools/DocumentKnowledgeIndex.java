package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.configrepo.RepositoryTextLoads;
import com.thingworx.things.agent.tools.DocumentKnowledgePackageManifest.ParseResult;
import com.thingworx.things.agent.skillregistry.RepositoryReader;

/**
 * JVM index of document-knowledge manifests and chunks (§9–§10).
 */
public final class DocumentKnowledgeIndex {

    private final Instant loadedAt;
    private final Instant expiresAt;
    private final boolean degraded;
    private final Map<String, DocumentKnowledgePackageManifest> manifestsByDocId;
    private final Map<String, DocumentKnowledgeChunk> chunksByKey;
    private final List<DocumentKnowledgeChunk> searchableChunks;
    private final DocumentKnowledgeWarnings warnings;
    private final int searchedDocuments;
    private final int searchedChunks;
    private final int skippedDocuments;
    private final int skippedChunks;

    private DocumentKnowledgeIndex(
            Instant loadedAt,
            Instant expiresAt,
            boolean degraded,
            Map<String, DocumentKnowledgePackageManifest> manifestsByDocId,
            Map<String, DocumentKnowledgeChunk> chunksByKey,
            List<DocumentKnowledgeChunk> searchableChunks,
            DocumentKnowledgeWarnings warnings,
            int searchedDocuments,
            int searchedChunks,
            int skippedDocuments,
            int skippedChunks) {
        this.loadedAt = loadedAt;
        this.expiresAt = expiresAt;
        this.degraded = degraded;
        this.manifestsByDocId = manifestsByDocId;
        this.chunksByKey = chunksByKey;
        this.searchableChunks = searchableChunks;
        this.warnings = warnings;
        this.searchedDocuments = searchedDocuments;
        this.searchedChunks = searchedChunks;
        this.skippedDocuments = skippedDocuments;
        this.skippedChunks = skippedChunks;
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean degraded() {
        return degraded;
    }

    public DocumentKnowledgeWarnings warnings() {
        return warnings;
    }

    public int searchedDocuments() {
        return searchedDocuments;
    }

    public int searchedChunks() {
        return searchedChunks;
    }

    public int skippedDocuments() {
        return skippedDocuments;
    }

    public int skippedChunks() {
        return skippedChunks;
    }

    public Optional<DocumentKnowledgeChunk> findChunk(String docId, String chunkId) {
        if (docId == null || chunkId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(chunksByKey.get(DocumentKnowledgePaths.chunkKey(docId, chunkId)));
    }

    public Optional<DocumentKnowledgePackageManifest> manifestFor(String docId) {
        return Optional.ofNullable(manifestsByDocId.get(docId));
    }

    /** All loaded manifests, for whole-corpus passes such as the document-set resolver. */
    public Collection<DocumentKnowledgePackageManifest> allManifests() {
        return manifestsByDocId.values();
    }

    public List<DocumentKnowledgeChunk> searchableChunks() {
        return searchableChunks;
    }

    /** Test hook: same index with an earlier expiry. */
    static DocumentKnowledgeIndex withExpiresAtForTests(DocumentKnowledgeIndex index, Instant expiresAt) {
        return new DocumentKnowledgeIndex(
                index.loadedAt,
                expiresAt,
                index.degraded,
                index.manifestsByDocId,
                index.chunksByKey,
                index.searchableChunks,
                index.warnings,
                index.searchedDocuments,
                index.searchedChunks,
                index.skippedDocuments,
                index.skippedChunks);
    }

    public static DocumentKnowledgeIndex load(
            RepositoryReader reader,
            DocumentKnowledgeSettings settings,
            Instant now) throws Exception {
        return load(reader, settings, now, null, null);
    }

    public static DocumentKnowledgeIndex load(
            RepositoryReader reader,
            DocumentKnowledgeSettings settings,
            Instant now,
            org.slf4j.Logger log,
            String agentThingName) throws Exception {
        DocumentKnowledgeWarnings warnings = new DocumentKnowledgeWarnings();
        Map<String, DocumentKnowledgePackageManifest> manifests = new LinkedHashMap<>();
        Map<String, DocumentKnowledgeChunk> chunksByKey = new LinkedHashMap<>();
        List<DocumentKnowledgeChunk> searchable = new ArrayList<>();

        List<String> packageDirs;
        try {
            packageDirs = DocumentKnowledgeRepositoryReader.listPackageDirectoryNames(reader, settings.rootPath());
        } catch (Exception e) {
            if (!PlatformAccess.isPermissionDenial(e) && RepositoryTextLoads.isProbablyMissingFile(e)) {
                warnings.add("DOCUMENT_ROOT_NOT_FOUND", "Document knowledge root path was not found.");
                return emptyIndexWithLog(now, settings, warnings, true, log, agentThingName);
            }
            throw e;
        }
        if (packageDirs.isEmpty()) {
            warnings.add("DOCUMENT_ROOT_NOT_FOUND", "Document knowledge root path was not found.");
            return emptyIndexWithLog(now, settings, warnings, true, log, agentThingName);
        }

        if (log != null) {
            String agent = agentThingName != null && !agentThingName.isBlank() ? agentThingName : "unknown";
            log.info("[{}] document knowledge index load starting repository={} root={} packageDirs={}",
                    agent, settings.repository(), settings.rootPath(), packageDirs.size());
        }

        boolean degraded = false;
        int skippedDocuments = 0;
        int skippedChunks = 0;
        int docsLoaded = 0;

        for (String dirName : packageDirs) {
            if (docsLoaded >= settings.maxDocuments()) {
                degraded = true;
                warnings.add("INDEX_LIMIT_REACHED", "Document index reached the configured document limit.");
                break;
            }
            String packagePath = DocumentKnowledgePaths.join(settings.rootPath(), dirName);
            String manifestPath = DocumentKnowledgePaths.join(packagePath, "manifest.json");
            String manifestJson;
            try {
                manifestJson = DocumentKnowledgeRepositoryReader.loadText(reader, manifestPath);
            } catch (Exception e) {
                if (PlatformAccess.isPermissionDenial(e)) {
                    throw e;
                }
                if (RepositoryTextLoads.isProbablyMissingFile(e)) {
                    skippedDocuments++;
                    warnings.increment("MANIFEST_MISSING", "One or more package manifests were missing.", 1);
                    continue;
                }
                skippedDocuments++;
                warnings.increment("MANIFEST_INVALID_JSON", "One or more package manifests were invalid JSON.", 1);
                continue;
            }
            ParseResult manifestResult = DocumentKnowledgePackageManifest.parse(manifestJson);
            if (manifestResult.status() == DocumentKnowledgePackageManifest.ParseStatus.INVALID_JSON) {
                skippedDocuments++;
                warnings.increment("MANIFEST_INVALID_JSON", "One or more package manifests were invalid JSON.", 1);
                continue;
            }
            if (manifestResult.status() == DocumentKnowledgePackageManifest.ParseStatus.INVALID_SHAPE) {
                skippedDocuments++;
                warnings.increment("MANIFEST_INVALID_SHAPE", "One or more package manifests had an invalid shape.", 1);
                continue;
            }
            DocumentKnowledgePackageManifest manifest = manifestResult.manifest();

            String chunksRel = manifest.chunksPath();
            String chunksPath = chunksRel.startsWith("/")
                    ? chunksRel
                    : DocumentKnowledgePaths.join(packagePath, chunksRel);
            String chunksJsonl;
            try {
                chunksJsonl = DocumentKnowledgeRepositoryReader.loadText(reader, chunksPath);
            } catch (Exception e) {
                if (PlatformAccess.isPermissionDenial(e)) {
                    throw e;
                }
                skippedDocuments++;
                if (RepositoryTextLoads.isProbablyMissingFile(e)) {
                    warnings.increment("CHUNKS_FILE_MISSING", "One or more chunk files were missing.", 1);
                } else {
                    warnings.increment("CHUNKS_FILE_READ_ERROR", "One or more chunk files could not be read.", 1);
                }
                continue;
            }

            docsLoaded++;
            manifests.put(manifest.docId(), manifest);

            int invalidLines = 0;
            int invalidShapes = 0;
            for (String line : chunksJsonl.split("\n")) {
                if (searchable.size() >= settings.maxChunks()) {
                    degraded = true;
                    warnings.add("INDEX_LIMIT_REACHED", "Document index reached the configured chunk limit.");
                    break;
                }
                Optional<DocumentKnowledgeChunk> chunkOpt = DocumentKnowledgeChunk.parseJsonLine(line);
                if (chunkOpt.isEmpty()) {
                    if (line != null && !line.isBlank()) {
                        invalidLines++;
                    }
                    continue;
                }
                DocumentKnowledgeChunk chunk = chunkOpt.get();
                if (!manifest.docId().equals(chunk.docId())) {
                    invalidShapes++;
                    continue;
                }
                String key = DocumentKnowledgePaths.chunkKey(chunk.docId(), chunk.chunkId());
                chunksByKey.put(key, chunk);
                searchable.add(chunk);
            }
            if (invalidLines > 0) {
                skippedChunks += invalidLines;
                warnings.increment("CHUNK_LINE_INVALID_JSON",
                        "Some chunk lines were skipped because they were invalid JSON.", invalidLines);
            }
            if (invalidShapes > 0) {
                skippedChunks += invalidShapes;
                warnings.increment("CHUNK_INVALID_SHAPE",
                        "Some chunk lines were skipped because they had an invalid shape.", invalidShapes);
            }
        }

        Instant expiresAt = now.plusSeconds(settings.indexTtlSeconds());
        DocumentKnowledgeIndex index = new DocumentKnowledgeIndex(
                now,
                expiresAt,
                degraded,
                Collections.unmodifiableMap(manifests),
                Collections.unmodifiableMap(chunksByKey),
                Collections.unmodifiableList(searchable),
                warnings,
                manifests.size(),
                searchable.size(),
                skippedDocuments,
                skippedChunks);
        logIndexLoad(log, agentThingName, settings, index);
        return index;
    }

    private static void logIndexLoad(
            org.slf4j.Logger log,
            String agentThingName,
            DocumentKnowledgeSettings settings,
            DocumentKnowledgeIndex index) {
        if (log == null) {
            return;
        }
        String agent = agentThingName != null && !agentThingName.isBlank() ? agentThingName : "unknown";
        log.info("[{}] document knowledge index load finished repository={} root={} documents={} chunks={} "
                        + "skippedDocuments={} skippedChunks={} degraded={}",
                agent,
                settings.repository(),
                settings.rootPath(),
                index.searchedDocuments(),
                index.searchedChunks(),
                index.skippedDocuments(),
                index.skippedChunks(),
                index.degraded());
        if (index.skippedDocuments() > 0 || index.skippedChunks() > 0) {
            log.info("[{}] document knowledge index load skipped packages={} chunkLines={} warningCodes={}",
                    agent,
                    index.skippedDocuments(),
                    index.skippedChunks(),
                    summarizeWarningCodes(index.warnings()));
        }
    }

    private static String summarizeWarningCodes(DocumentKnowledgeWarnings warnings) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> w : warnings.toJsonList()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            Object code = w.get("code");
            sb.append(code != null ? code : "?");
        }
        return sb.toString();
    }

    private static DocumentKnowledgeIndex emptyIndexWithLog(
            Instant now,
            DocumentKnowledgeSettings settings,
            DocumentKnowledgeWarnings warnings,
            boolean degraded,
            org.slf4j.Logger log,
            String agentThingName) {
        DocumentKnowledgeIndex index = emptyIndex(now, settings, warnings, degraded);
        logIndexLoad(log, agentThingName, settings, index);
        return index;
    }

    private static DocumentKnowledgeIndex emptyIndex(
            Instant now,
            DocumentKnowledgeSettings settings,
            DocumentKnowledgeWarnings warnings,
            boolean degraded) {
        return new DocumentKnowledgeIndex(
                now,
                now.plusSeconds(settings.indexTtlSeconds()),
                degraded,
                Map.of(),
                Map.of(),
                List.of(),
                warnings,
                0,
                0,
                0,
                0);
    }
}
