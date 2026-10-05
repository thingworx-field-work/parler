package com.thingworx.things.agent.tools;

/**
 * Checked exception for deterministic cached-table decision modes ({@code filter_*}, later {@code group_metric})
 * carrying a stable {@code code} for {@code tabulate_cached_result} error JSON.
 */
public final class CachedTabularDecisionToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public final String code;

    public CachedTabularDecisionToolException(String code, String message) {
        super(message);
        this.code = code;
    }
}
