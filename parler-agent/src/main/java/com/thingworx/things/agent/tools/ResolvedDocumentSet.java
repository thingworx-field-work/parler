package com.thingworx.things.agent.tools;

import java.util.List;
import java.util.Set;

/**
 * Result of resolving a bounded document set for a turn
 * (docs/operations/knowledge-retrieval-pipeline.md §3.1/§3.3/§3.5).
 *
 * <p>{@link #documentIds()} is the final scoped set: the key-matched documents unioned
 * with any cross-cutting {@code alwaysInclude} documents (design §3.5). Cross-cutting
 * documents are <em>resolver-sourced only</em> — the built-in default matcher never
 * synthesizes them, so they enter scope solely via a custom {@code ResolveDocumentSet}
 * override. {@code appliesToMany} is parsed/carried but inert (no separate runtime
 * effect yet); per-document keywords/type still live in the manifest
 * {@code documentProfile}, not in resolver output.
 */
public final class ResolvedDocumentSet {

    private final List<String> documentIds;
    private final Set<String> alwaysIncludeIds;
    private final Set<String> appliesToManyIds;
    private final ResolverSource source;

    public ResolvedDocumentSet(List<String> documentIds, ResolverSource source) {
        this(documentIds, Set.of(), Set.of(), source);
    }

    public ResolvedDocumentSet(
            List<String> documentIds,
            Set<String> alwaysIncludeIds,
            Set<String> appliesToManyIds,
            ResolverSource source) {
        this.documentIds = List.copyOf(documentIds);
        this.alwaysIncludeIds = Set.copyOf(alwaysIncludeIds);
        this.appliesToManyIds = Set.copyOf(appliesToManyIds);
        this.source = source;
    }

    /**
     * @return the scoped document ids — key-matched docs unioned with cross-cutting
     *         {@code alwaysInclude} docs; empty for {@code default-empty} / {@code none}
     */
    public List<String> documentIds() {
        return documentIds;
    }

    /** @return the subset of {@link #documentIds()} flagged cross-cutting ({@code alwaysInclude=true}) */
    public Set<String> alwaysIncludeIds() {
        return alwaysIncludeIds;
    }

    /** @return whether {@code docId} is a cross-cutting ({@code alwaysInclude}) document */
    public boolean isAlwaysInclude(String docId) {
        return alwaysIncludeIds.contains(docId);
    }

    /** @return whether {@code docId} is flagged {@code appliesToMany} (parsed-but-inert in M4) */
    public boolean isAppliesToMany(String docId) {
        return appliesToManyIds.contains(docId);
    }

    public ResolverSource source() {
        return source;
    }

    public boolean isEmpty() {
        return documentIds.isEmpty();
    }

    /** Resolver not invoked (e.g. tools off / no asset context). */
    public static ResolvedDocumentSet none() {
        return new ResolvedDocumentSet(List.of(), ResolverSource.NONE);
    }
}
