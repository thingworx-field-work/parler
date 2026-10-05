package com.thingworx.things.agent.playbook;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Orchestration-path navigation for {@code extract_from_tool_output} (Slice A: dotted segments
 * only on {@code arrayPath}; {@code $parent.*} dotted tails on {@code fields[].from}).
 * See {@code docs/agent/playbook-34-35.md} §6.3.
 */
final class PlaybookOrchestrationPath {

    static final String PARENT_PREFIX = "$parent.";

    private PlaybookOrchestrationPath() {}

    static boolean isValidArrayPath(String path) {
        if (path == null || path.isBlank() || path.startsWith(PARENT_PREFIX) || path.contains("[")) {
            return false;
        }
        return PlaybookGenericPathGrammar.isValidDottedPath(path);
    }

    static boolean isValidFieldFrom(String from, boolean fanOutChildrenMode) {
        if (from == null || from.isBlank()) {
            return false;
        }
        if (from.startsWith(PARENT_PREFIX)) {
            if (!fanOutChildrenMode) {
                return false;
            }
            String tail = from.substring(PARENT_PREFIX.length());
            return !tail.isBlank() && PlaybookGenericPathGrammar.isValidDottedPath(tail);
        }
        return PlaybookGenericPathGrammar.isValidDottedPath(from);
    }

    static Object navigateFromRoot(JSONObject root, String path) throws PlaybookRunException {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        if (path.startsWith(PARENT_PREFIX)) {
            throw new PlaybookRunException("orchestration path must not use $parent in arrayPath",
                    "ORCHESTRATION_PATH_INVALID");
        }
        return PlaybookJsonRowPath.getAtPath(root, path);
    }

    static Object navigateFieldFrom(JSONObject arrayElement, JSONObject parentEntry, String from)
            throws PlaybookRunException {
        if (from == null || from.isBlank()) {
            return null;
        }
        if (from.startsWith(PARENT_PREFIX)) {
            if (parentEntry == null) {
                throw new PlaybookRunException("$parent field reference requires fan_out_children mode",
                        "ORCHESTRATION_PATH_INVALID");
            }
            String tail = from.substring(PARENT_PREFIX.length());
            return PlaybookJsonRowPath.getAtPath(parentEntry, tail);
        }
        return PlaybookJsonRowPath.getAtPath(arrayElement, from);
    }

    static JSONArray requireArray(Object value, String label) throws PlaybookRunException {
        if (value instanceof JSONArray) {
            return (JSONArray) value;
        }
        if (value == null || value == JSONObject.NULL) {
            return new JSONArray();
        }
        throw new PlaybookRunException(label + ": path did not resolve to a JSON array", "ORCHESTRATION_PATH_INVALID");
    }
}
