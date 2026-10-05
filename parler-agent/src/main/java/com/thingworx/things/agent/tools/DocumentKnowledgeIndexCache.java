package com.thingworx.things.agent.tools;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import org.slf4j.Logger;

import com.thingworx.things.agent.FileRepositoryThingResolver;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.things.agent.skillregistry.FileRepositoryRepositoryReader;
import com.thingworx.things.agent.skillregistry.RepositoryReader;
import com.thingworx.things.repository.FileRepositoryThing;

/**
 * Lazy TTL cache for {@link DocumentKnowledgeIndex} (§10), shared by every user of the same settings.
 *
 * <p>A (re)build reads the repository as the current user ({@link FileRepositoryRepositoryReader#forCurrentUser}),
 * so ThingWorx authorizes each read it makes. A hit reuses the loaded index and makes no repository call. If the
 * repository refuses a rebuild read, the lookup returns nothing and leaves the shared entry untouched.
 */
public final class DocumentKnowledgeIndexCache {

    private static final ConcurrentMap<String, CachedEntry> CACHE = new ConcurrentHashMap<>();

    /** Test hook: when set, replaces the index rebuild during {@link #getOrLoad}. */
    static volatile RebuildHook rebuildHookForTests;

    /** Test hook: when set, replaces the current user's repository reader for a rebuild. */
    static volatile Function<DocumentKnowledgeSettings, RepositoryReader> readerForTests;

    @FunctionalInterface
    interface RebuildHook {
        DocumentKnowledgeIndex rebuild(
                DocumentKnowledgeSettings settings,
                String agentThingName,
                Logger log,
                Instant now) throws Exception;
    }

    /** Result of a cache lookup; stale rebuild failures are response-level only (§10). */
    public static final class LoadResult {
        private final DocumentKnowledgeIndex index;
        private final boolean staleRebuildFailed;

        private LoadResult(DocumentKnowledgeIndex index, boolean staleRebuildFailed) {
            this.index = index;
            this.staleRebuildFailed = staleRebuildFailed;
        }

        public static LoadResult fresh(DocumentKnowledgeIndex index) {
            return new LoadResult(index, false);
        }

        public static LoadResult stale(DocumentKnowledgeIndex index) {
            return new LoadResult(index, true);
        }

        public static LoadResult empty() {
            return new LoadResult(null, false);
        }

        public DocumentKnowledgeIndex index() {
            return index;
        }

        public boolean staleRebuildFailed() {
            return staleRebuildFailed;
        }
    }

    private DocumentKnowledgeIndexCache() {}

    public static LoadResult getOrLoad(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            Logger log) {
        String key = settings.cacheKey(agentThingName);
        Instant now = Instant.now();
        CachedEntry existing = CACHE.get(key);
        if (existing != null && existing.index.expiresAt().isAfter(now)) {
            log.debug("[{}] document knowledge index cache hit (expires {})",
                    agentThingName, existing.index.expiresAt());
            return LoadResult.fresh(existing.index);
        }
        DocumentKnowledgeIndex staleCandidate = existing != null ? existing.index : null;
        if (existing != null) {
            log.info("[{}] document knowledge index cache expired; rebuilding", agentThingName);
        } else {
            log.info("[{}] document knowledge index cache miss; loading", agentThingName);
        }
        try {
            DocumentKnowledgeIndex rebuilt = rebuildIndex(settings, agentThingName, log, now);
            if (rebuilt != null) {
                CACHE.put(key, new CachedEntry(rebuilt));
                log.info("[{}] document knowledge index rebuild succeeded documents={} chunks={}",
                        agentThingName, rebuilt.searchedDocuments(), rebuilt.searchedChunks());
                return LoadResult.fresh(rebuilt);
            }
            log.warn("[{}] document knowledge index rebuild returned no index", agentThingName);
        } catch (Exception e) {
            if (PlatformAccess.isPermissionDenial(e)) {
                log.warn("[{}] document knowledge repository refused a read for the current user: {}",
                        agentThingName, e.getMessage());
                return LoadResult.empty();
            }
            log.error("[{}] document knowledge index rebuild failed: {}", agentThingName, e.getMessage(), e);
        }
        if (staleCandidate != null) {
            log.warn("[{}] document knowledge index rebuild failed; serving stale index loaded at {}",
                    agentThingName, staleCandidate.loadedAt());
            return LoadResult.stale(staleCandidate);
        }
        return LoadResult.empty();
    }

    private static DocumentKnowledgeIndex rebuildIndex(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            Logger log,
            Instant now) throws Exception {
        RebuildHook hook = rebuildHookForTests;
        if (hook != null) {
            return hook.rebuild(settings, agentThingName, log, now);
        }
        RepositoryReader reader = currentUserReader(settings, agentThingName, log);
        if (reader == null) {
            return null;
        }
        return DocumentKnowledgeIndex.load(reader, settings, now, log, agentThingName);
    }

    private static RepositoryReader currentUserReader(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            Logger log) {
        Function<DocumentKnowledgeSettings, RepositoryReader> hook = readerForTests;
        if (hook != null) {
            return hook.apply(settings);
        }
        Optional<FileRepositoryThing> fr =
                FileRepositoryThingResolver.resolveForCurrentUser(settings.repository(), log, agentThingName);
        return fr.map(FileRepositoryRepositoryReader::forCurrentUser).orElse(null);
    }

    /** Test hook: clear cached indexes and both hooks. */
    public static void clearForTests() {
        CACHE.clear();
        rebuildHookForTests = null;
        readerForTests = null;
    }

    public static DocumentKnowledgeIndex loadForTests(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            RepositoryReader reader) throws Exception {
        DocumentKnowledgeIndex index = DocumentKnowledgeIndex.load(
                reader, settings, Instant.now(), null, agentThingName);
        CACHE.put(settings.cacheKey(agentThingName), new CachedEntry(index));
        return index;
    }

    /** Test hook: install an expired cache entry (for rebuild-failure semantics). */
    public static void installExpiredForTests(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            DocumentKnowledgeIndex index) {
        DocumentKnowledgeIndex expired = DocumentKnowledgeIndex.withExpiresAtForTests(index, Instant.EPOCH);
        CACHE.put(settings.cacheKey(agentThingName), new CachedEntry(expired));
    }

    private static final class CachedEntry {
        private final DocumentKnowledgeIndex index;

        private CachedEntry(DocumentKnowledgeIndex index) {
            this.index = index;
        }
    }
}
