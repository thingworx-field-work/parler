package com.thingworx.things.agent;

import org.json.JSONObject;

/**
 * Shared {@code TableBlock} wire field helpers.
 */
public final class ParlerTableWireFields {

    /** Max UTF-16 code units for {@code presentationTitle} on the wire. */
    public static final int PRESENTATION_TITLE_MAX_CHARS = 80;

    private ParlerTableWireFields() {}

    /**
     * Emits top-level {@code cacheId} when the tool success body carries one; otherwise JSON null.
     */
    public static void putCacheIdFromRoot(JSONObject table, JSONObject root) {
        if (table == null || root == null) {
            return;
        }
        String cid = root.optString("cacheId", "");
        if (!cid.isEmpty()) {
            table.put("cacheId", cid);
        } else {
            table.put("cacheId", JSONObject.NULL);
        }
    }

    /**
     * @param title presentation-only label; omitted when blank
     */
    public static void putPresentationTitle(JSONObject table, String title) {
        if (table == null || title == null) {
            return;
        }
        String t = title.trim();
        if (t.isEmpty()) {
            return;
        }
        table.put("presentationTitle", truncateWithEllipsis(t, PRESENTATION_TITLE_MAX_CHARS));
    }

    static String truncateWithEllipsis(String s, int maxChars) {
        if (s == null || maxChars < 1) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        if (maxChars == 1) {
            return "…";
        }
        return s.substring(0, maxChars - 1) + "…";
    }
}
