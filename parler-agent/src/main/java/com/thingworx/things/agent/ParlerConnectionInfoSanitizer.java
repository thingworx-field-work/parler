package com.thingworx.things.agent;

/**
 * Sanitizes client-supplied widget version echo for server logs only (not authoritative).
 */
public final class ParlerConnectionInfoSanitizer {

    static final int MAX_WIDGET_ECHO_LEN = 64;

    private ParlerConnectionInfoSanitizer() {}

    /**
     * Trim, collapse whitespace, strip ISO control characters, cap length — safe for a single-line log field.
     */
    public static String sanitizeWidgetEcho(String raw) {
        if (raw == null) {
            return "";
        }
        String t = raw.trim().replaceAll("\\s+", " ");
        StringBuilder sb = new StringBuilder(Math.min(t.length(), MAX_WIDGET_ECHO_LEN));
        for (int i = 0; i < t.length() && sb.length() < MAX_WIDGET_ECHO_LEN; i++) {
            char c = t.charAt(i);
            if (!Character.isISOControl(c)) {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }
}
