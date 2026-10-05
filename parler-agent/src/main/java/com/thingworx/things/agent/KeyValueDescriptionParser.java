package com.thingworx.things.agent;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Line-oriented {@code key: value} parsing for Service descriptions and repository SKILL.md frontmatter slabs.
 * Pure static API (no logging) so callers and tests avoid {@code LogUtilities} initialization.
 */
public final class KeyValueDescriptionParser {

    private KeyValueDescriptionParser() {}

    /**
     * Parses newline-separated {@code key: value} lines. Only the first {@code ':'} splits; keys lower-cased.
     * Empty lines and lines starting with {@code #} are ignored.
     */
    public static Map<String, String> parse(String description) {
        Map<String, String> map = new LinkedHashMap<>();
        if (description == null || description.isBlank()) {
            return map;
        }
        for (String rawLine : description.split("\\R")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int c = line.indexOf(':');
            if (c <= 0) {
                continue;
            }
            String key = line.substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(c + 1).trim();
            if (!key.isEmpty()) {
                map.put(key, value);
            }
        }
        return map;
    }
}
