package com.thingworx.things.agent.tools;

/**
 * Output budget helpers for document-knowledge tool results.
 */
public final class DocumentKnowledgeTextBounds {

    private DocumentKnowledgeTextBounds() {}

    /**
     * Truncate search snippets silently (no per-match warning — see design §5.4).
     */
    public static String truncateSnippet(String text, int maxChars) {
        return truncatePrefix(text, maxChars);
    }

    /**
     * Truncate chunk markdown; caller adds {@code CHUNK_MARKDOWN_TRUNCATED} when shortened.
     */
    public static String truncateMarkdown(String text, int maxChars) {
        return truncatePrefix(text, maxChars);
    }

    static String truncatePrefix(String text, int maxChars) {
        if (text == null || maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars <= 3) {
            return text.substring(0, maxChars);
        }
        return text.substring(0, maxChars - 1) + "…";
    }
}
