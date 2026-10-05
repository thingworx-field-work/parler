package com.thingworx.things.agent.playbook;

import org.json.JSONObject;

/**
 * Dotted navigation on JSON row objects (same path grammar as {@link PlaybookGenericPathGrammar};
 * load-time validation is still required for author literals).
 */
public final class PlaybookJsonRowPath {

    private PlaybookJsonRowPath() {}

    /** Navigate {@code dotted} on {@code root} ({@code a.b.c}); missing segments yield {@code null}. */
    public static Object getAtPath(JSONObject root, String dotted) throws PlaybookRunException {
        if (root == null || dotted == null || dotted.isBlank()) {
            return null;
        }
        String[] parts = dotted.split("\\.", -1);
        Object cur = root;
        for (String part : parts) {
            if (part.isEmpty()) {
                throw new PlaybookRunException(
                        "row path has empty segment: " + dotted, "GENERIC_PATH_MALFORMED");
            }
            if (!(cur instanceof JSONObject)) {
                return null;
            }
            JSONObject jo = (JSONObject) cur;
            cur = jo.has(part) ? jo.get(part) : null;
        }
        return cur;
    }
}
