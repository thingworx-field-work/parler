package com.thingworx.things.agent.tools;

/**
 * Diagnostic provenance of a resolved document set
 * (docs/operations/knowledge-retrieval-pipeline.md §3.3).
 *
 * <ul>
 *   <li>{@code custom} — an App-developer override produced the set.</li>
 *   <li>{@code default-match} — the built-in high-confidence matcher scoped a set.</li>
 *   <li>{@code default-empty} — the built-in matcher ran but found no confident match.</li>
 *   <li>{@code none} — the resolver was not invoked (tools off / no asset context).</li>
 * </ul>
 */
public enum ResolverSource {

    CUSTOM("custom"),
    DEFAULT_MATCH("default-match"),
    DEFAULT_EMPTY("default-empty"),
    NONE("none");

    private final String wire;

    ResolverSource(String wire) {
        this.wire = wire;
    }

    /** @return the diagnostic wire token surfaced to the model / stream */
    public String wire() {
        return wire;
    }
}
