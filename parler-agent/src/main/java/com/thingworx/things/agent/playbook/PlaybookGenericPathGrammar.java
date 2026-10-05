package com.thingworx.things.agent.playbook;

import java.util.regex.Pattern;

/**
 * Dotted path grammar for generic Playbook ops ({@code project.from}, row predicates, sort keys,
 * etc.) — see {@code docs/agent/playbook-generic-ops-foundation.md} section 8.1.
 */
public final class PlaybookGenericPathGrammar {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private PlaybookGenericPathGrammar() {}

    /**
     * {@code identifier(.identifier)*} with no empty segments; each segment matches {@link #SEGMENT}.
     */
    public static boolean isValidDottedPath(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String[] parts = path.split("\\.", -1);
        for (String p : parts) {
            if (p.isEmpty() || !SEGMENT.matcher(p).matches()) {
                return false;
            }
        }
        return parts.length > 0;
    }

    /**
     * True for a single path segment (no dots), matching {@link #SEGMENT}. Used for {@code group_by}
     * {@code keys} v1 so output row columns stay flat and downstream {@code $ref} paths remain navigable.
     */
    public static boolean isSingleSegmentField(String path) {
        if (path == null || path.isBlank() || path.indexOf('.') >= 0) {
            return false;
        }
        return SEGMENT.matcher(path).matches();
    }
}
