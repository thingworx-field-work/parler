package com.thingworx.things.agent.tools;

/**
 * Parses {@code direction} for {@code tabulate_cached_result} {@code mode=sort_topn}. Pure string logic for
 * offline JUnit; must stay aligned with {@link CachedTabularToolsExecutor} and {@code docs/agent/cached_tabular_tools.md}.
 */
public final class CachedTabularSortTopnDirection {

    private CachedTabularSortTopnDirection() {}

    /**
     * @param dirRaw value of the {@code direction} field, or {@code null} / blank for default
     * @return {@code true} for descending (default when absent or blank)
     */
    public static boolean isDescending(String dirRaw) {
        if (dirRaw == null || dirRaw.isBlank()) {
            return true;
        }
        String t = dirRaw.trim();
        if ("desc".equalsIgnoreCase(t) || "descending".equalsIgnoreCase(t)) {
            return true;
        }
        if ("asc".equalsIgnoreCase(t) || "ascending".equalsIgnoreCase(t)) {
            return false;
        }
        throw new IllegalArgumentException("direction must be asc or desc.");
    }
}
