package com.thingworx.things.agent.tools;

import java.util.List;

import com.thingworx.things.agent.cache.TypedColumn;

/**
 * Store-time description of one cached table written by {@link NumericHistoryCacheWriter} (CM-0/CM-1):
 * the handle, the real written columns, and the writer-declared roles that were validated and stamped
 * on the descriptor. Producers copy these fields into their result JSON; nothing is re-derived.
 */
public final class StoredSeriesCache {

    private final String cacheId;
    private final List<TypedColumn> columns;
    private final String timeColumn;
    private final String valueColumn;

    public StoredSeriesCache(String cacheId, List<TypedColumn> columns, String timeColumn, String valueColumn) {
        if (cacheId == null || cacheId.isBlank()) {
            throw new IllegalArgumentException("cacheId required");
        }
        this.cacheId = cacheId;
        this.columns = columns == null ? List.of() : List.copyOf(columns);
        this.timeColumn = timeColumn;
        this.valueColumn = valueColumn;
    }

    public String cacheId() {
        return cacheId;
    }

    /** Written columns in source order (PASSWORD columns excluded). */
    public List<TypedColumn> columns() {
        return columns;
    }

    /** Validated time role, or {@code null} when the declared role was omitted. */
    public String timeColumn() {
        return timeColumn;
    }

    /** Validated value role, or {@code null} when the declared role was omitted. */
    public String valueColumn() {
        return valueColumn;
    }

    public boolean hasRoles() {
        return timeColumn != null && valueColumn != null;
    }
}
