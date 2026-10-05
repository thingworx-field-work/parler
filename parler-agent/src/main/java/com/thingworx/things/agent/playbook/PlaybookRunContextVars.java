package com.thingworx.things.agent.playbook;

import java.util.LinkedHashMap;
import java.util.Map;

/** Nested var map helper for {@code taxonomy.EntityType} style paths. */
final class PlaybookRunContextVars {

    private PlaybookRunContextVars() {}

    @SuppressWarnings("unchecked")
    static void put(Map<String, Object> root, String dottedPath, Object value) {
        if (dottedPath == null || dottedPath.isEmpty()) {
            return;
        }
        String[] parts = dottedPath.split("\\.");
        Map<String, Object> cur = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map)) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            }
            cur = (Map<String, Object>) next;
        }
        cur.put(parts[parts.length - 1], value);
    }

    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> root, String dottedPath) {
        if (root == null || dottedPath == null) {
            return null;
        }
        String[] parts = dottedPath.split("\\.");
        Object cur = root;
        for (String part : parts) {
            if (!(cur instanceof Map)) {
                return null;
            }
            cur = ((Map<String, Object>) cur).get(part);
        }
        return cur;
    }
}
