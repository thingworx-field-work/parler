package com.thingworx.things.agent.configrepo;

/**
 * Character-level {@code *} glob only (no regex, no {@code **}). Case-sensitive. Empty pattern never matches.
 */
public final class GlobPattern {

    private GlobPattern() {}

    public static boolean matches(String pattern, String value) {
        if (pattern == null || value == null) {
            return false;
        }
        if (pattern.isEmpty()) {
            return false;
        }
        if (!pattern.contains("*")) {
            return pattern.equals(value);
        }
        String[] parts = pattern.split("\\*", -1);
        if (parts.length == 1) {
            return false;
        }
        if (!value.startsWith(parts[0])) {
            return false;
        }
        int idx = parts[0].length();
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i];
            if (seg.isEmpty()) {
                continue;
            }
            int found = value.indexOf(seg, idx);
            if (found < 0) {
                return false;
            }
            idx = found + seg.length();
        }
        if (pattern.endsWith("*")) {
            return true;
        }
        return idx == value.length();
    }
}
