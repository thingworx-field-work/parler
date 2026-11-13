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
    /**
     * Round 6 R27: a per-key generation. {@link #invalidate} increments it and removes the entry in one critical
     * section; a rebuild publishes only when the generation it captured before rebuilding is still current, compared
     * and stored in the same critical section, so a rebuild that began before an invalidation cannot repopulate it.
     */
    private static final ConcurrentMap<String, Long> GENERATIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, Object> KEY_LOCKS = new ConcurrentHashMap<>();

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
        public enum FailureReason { NONE, PERMISSION_DENIED, REPOSITORY_UNAVAILABLE, INTERNAL_ERROR }
        private final DocumentKnowledgeIndex index;
        private final boolean staleRebuildFailed;
        private final FailureReason failureReason;

        private LoadResult(DocumentKnowledgeIndex index, boolean staleRebuildFailed) {
            this(index, staleRebuildFailed, index == null ? FailureReason.INTERNAL_ERROR : FailureReason.NONE);
        }

        private LoadResult(DocumentKnowledgeIndex index, boolean staleRebuildFailed, FailureReason failureReason) {
            this.index = index;
            this.staleRebuildFailed = staleRebuildFailed;
            this.failureReason = failureReason;
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

        public static LoadResult failed(FailureReason reason) {
            if (reason == null || reason == FailureReason.NONE) {
                throw new IllegalArgumentException("No-index result requires a failure reason");
            }
            return new LoadResult(null, false, reason);
        }

        public FailureReason failureReason() {
            return failureReason;
        }

        public DocumentKnowledgeIndex index() {
            return index;
        }

        public boolean staleRebuildFailed() {
            return staleRebuildFailed;
        }
    }

    private DocumentKnowledgeIndexCache() {}

    /** What {@link #invalidate} removed. */
    public static final class Invalidation {
        private final boolean invalidated;
        private final Instant evictedIndexLoadedAt;

        Invalidation(boolean invalidated, Instant evictedIndexLoadedAt) {
            this.invalidated = invalidated;
            this.evictedIndexLoadedAt = evictedIndexLoadedAt;
        }

        /** True exactly when an entry was removed. */
        public boolean invalidated() {
            return invalidated;
        }

        /** The removed entry's load time, or {@code null} when there was none. */
        public Instant evictedIndexLoadedAt() {
            return evictedIndexLoadedAt;
        }
    }

    private static Object lockFor(String key) {
        return KEY_LOCKS.computeIfAbsent(key, k -> new Object());
    }

    static long generation(String key) {
        return GENERATIONS.getOrDefault(key, 0L);
    }

    /**
     * Invalidates this agent's index lazily: no rebuild here; the next search rebuilds. Other keys (another agent,
     * repository or root) are untouched.
     */
    public static Invalidation invalidate(DocumentKnowledgeSettings settings, String agentThingName) {
        return invalidate(settings.cacheKey(agentThingName));
    }

    static Invalidation invalidate(String key) {
        synchronized (lockFor(key)) {
            GENERATIONS.merge(key, 1L, Long::sum);
            CachedEntry removed = CACHE.remove(key);
            return new Invalidation(removed != null, removed != null ? removed.index.loadedAt() : null);
        }
    }

    /** Stores {@code index} only when {@code capturedGeneration} is still current; compare and store are atomic. */
    static boolean publishIfCurrent(String key, long capturedGeneration, DocumentKnowledgeIndex index) {
        synchronized (lockFor(key)) {
            if (generation(key) != capturedGeneration) {
                return false;
            }
            CACHE.put(key, new CachedEntry(index));
            return true;
        }
    }

    /** The cached index for these settings, without triggering a load; empty when none is cached. */
    public static Optional<DocumentKnowledgeIndex> peek(DocumentKnowledgeSettings settings, String agentThingName) {
        CachedEntry e = CACHE.get(settings.cacheKey(agentThingName));
        return e != null ? Optional.of(e.index) : Optional.empty();
    }

    public static LoadResult getOrLoad(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            Logger log) {
        String key = settings.cacheKey(agentThingName);
        Instant now = Instant.now();
        long capturedGeneration = generation(key);
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
        LoadResult failure = LoadResult.empty();
        try {
            LoadResult rebuiltResult = rebuildIndex(settings, agentThingName, log, now);
            DocumentKnowledgeIndex rebuilt = rebuiltResult.index();
            if (rebuilt != null) {
                if (publishIfCurrent(key, capturedGeneration, rebuilt)) {
                    log.info("[{}] document knowledge index rebuild succeeded documents={} chunks={}",
                            agentThingName, rebuilt.searchedDocuments(), rebuilt.searchedChunks());
                } else {
                    log.info("[{}] document knowledge index was invalidated during the rebuild; serving the rebuilt "
                            + "index to this call without storing it", agentThingName);
                }
                return LoadResult.fresh(rebuilt);
            }
            failure = rebuiltResult;
            log.warn("[{}] document knowledge index rebuild returned no index", agentThingName);
        } catch (Exception e) {
            if (PlatformAccess.isPermissionDenial(e)) {
                log.warn("[{}] document knowledge repository refused a read for the current user: {}",
                        agentThingName, e.getMessage());
                return LoadResult.failed(LoadResult.FailureReason.PERMISSION_DENIED);
            }
            log.error("[{}] document knowledge index rebuild failed: {}", agentThingName, e.getMessage(), e);
        }
        if (staleCandidate != null && generation(key) == capturedGeneration) {
            log.warn("[{}] document knowledge index rebuild failed; serving stale index loaded at {}",
                    agentThingName, staleCandidate.loadedAt());
            return LoadResult.stale(staleCandidate);
        }
        return failure;
    }

    private static LoadResult rebuildIndex(
            DocumentKnowledgeSettings settings,
            String agentThingName,
            Logger log,
            Instant now) throws Exception {
        RebuildHook hook = rebuildHookForTests;
        if (hook != null) {
            DocumentKnowledgeIndex index = hook.rebuild(settings, agentThingName, log, now);
            return index == null ? LoadResult.empty() : LoadResult.fresh(index);
        }
        RepositoryReader reader = currentUserReader(settings, agentThingName, log);
        if (reader == null) {
            return LoadResult.failed(LoadResult.FailureReason.REPOSITORY_UNAVAILABLE);
        }
        return LoadResult.fresh(DocumentKnowledgeIndex.load(reader, settings, now, log, agentThingName));
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
        GENERATIONS.clear();
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
