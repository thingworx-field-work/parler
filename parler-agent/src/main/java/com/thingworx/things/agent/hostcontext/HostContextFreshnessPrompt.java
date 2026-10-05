package com.thingworx.things.agent.hostcontext;

/**
 * Fixed LLM steering block when host page context is accepted for a turn.
 * Stable prefix lines MUST remain byte-stable for provider prefix cache (docs/architecture/host-context-turn-state.md §3.4).
 */
public final class HostContextFreshnessPrompt {

    private static final String STABLE_PREFIX = ""
            + "Host page context freshness:\n"
            + "- The host page context below is the current page state for this user turn.\n"
            + "- For \"here\", \"now\", \"current view\", \"selected\", \"this page\", and similar prompts, "
            + "prefer this current host page context over prior assistant answers.\n"
            + "- If the host page context changed since the previous user turn, re-query evidence before answering "
            + "counts, lists, summaries, or comparisons.\n"
            + "- Explicit user text still wins over host page context.\n";

    private static final String CHANGED_SUFFIX = "- Host page context changed since the previous user turn.";
    private static final String UNCHANGED_SUFFIX = "- Host page context is unchanged since the previous user turn.";

    private HostContextFreshnessPrompt() {
    }

    public static String build(boolean changedFromPreviousUserTurn) {
        return STABLE_PREFIX + (changedFromPreviousUserTurn ? CHANGED_SUFFIX : UNCHANGED_SUFFIX);
    }

    /** Freshness block, then rendered host-context template fragment. */
    public static String combineWithRendered(boolean changedFromPreviousUserTurn, String renderedHostContextPrompt) {
        if (renderedHostContextPrompt == null || renderedHostContextPrompt.isEmpty()) {
            return null;
        }
        return build(changedFromPreviousUserTurn) + "\n\n" + renderedHostContextPrompt;
    }
}
