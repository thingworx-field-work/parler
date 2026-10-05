package com.thingworx.things.agent.llm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Content-free structural diagnostics captured while parsing a provider response.
 *
 * <p>This type deliberately carries block counts and sanitized block type names only. It must never retain
 * response text, thinking contents, tool inputs, or the raw provider response.</p>
 */
public final class LlmResponseShapeDiagnostics {

    private static final int MAX_OTHER_BLOCK_TYPES = 8;

    private final int contentBlockCount;
    private final int textBlockCount;
    private final int toolUseBlockCount;
    private final List<String> otherBlockTypes;

    public LlmResponseShapeDiagnostics(int contentBlockCount, int textBlockCount, int toolUseBlockCount,
            List<String> otherBlockTypes) {
        this.contentBlockCount = Math.max(0, contentBlockCount);
        this.textBlockCount = Math.max(0, textBlockCount);
        this.toolUseBlockCount = Math.max(0, toolUseBlockCount);
        List<String> sanitized = new ArrayList<>();
        if (otherBlockTypes != null) {
            for (String type : otherBlockTypes) {
                String safe = sanitizeBlockType(type);
                if (!sanitized.contains(safe)) {
                    sanitized.add(safe);
                }
                if (sanitized.size() >= MAX_OTHER_BLOCK_TYPES) {
                    break;
                }
            }
        }
        this.otherBlockTypes = Collections.unmodifiableList(sanitized);
    }

    private static String sanitizeBlockType(String type) {
        if (type == null || type.isEmpty()) {
            return "unknown";
        }
        StringBuilder safe = new StringBuilder(Math.min(type.length(), 48));
        for (int i = 0; i < type.length() && safe.length() < 48; i++) {
            char c = type.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        return safe.length() == 0 ? "unknown" : safe.toString();
    }

    public int getContentBlockCount() { return contentBlockCount; }
    public int getTextBlockCount() { return textBlockCount; }
    public int getToolUseBlockCount() { return toolUseBlockCount; }
    public int getOtherBlockCount() {
        return Math.max(0, contentBlockCount - textBlockCount - toolUseBlockCount);
    }
    public List<String> getOtherBlockTypes() { return otherBlockTypes; }
}
