package com.thingworx.things.agent.playbook;

import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded dot-path resolution for playbook tool-output evidence projection. */
final class PlaybookEvidencePathSupport {

    static final int MAX_DOT_PATH_DEPTH = 8;
    static final int MAX_INCLUDE_TOOL_OUTPUT_PATHS = 24;
    static final int MAX_TABLE_MAX_ROWS = 100;

    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private PlaybookEvidencePathSupport() {}

    static boolean isValidDotPath(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String[] segs = path.split("\\.", -1);
        if (segs.length == 0 || segs.length > MAX_DOT_PATH_DEPTH) {
            return false;
        }
        for (String seg : segs) {
            if (seg.isEmpty() || !PATH_SEGMENT.matcher(seg).matches()) {
                return false;
            }
        }
        return true;
    }

    static Object resolveDotPath(JSONObject toolOut, String dotPath) {
        if (toolOut == null) {
            return null;
        }
        if (dotPath == null || dotPath.isBlank()) {
            return toolOut;
        }
        Object current = toolOut;
        for (String seg : dotPath.split("\\.")) {
            current = step(current, seg);
            if (current == null || JSONObject.NULL.equals(current)) {
                return null;
            }
        }
        return current;
    }

    private static Object step(Object current, String seg) {
        if (!(current instanceof JSONObject)) {
            return null;
        }
        JSONObject obj = (JSONObject) current;
        if (!obj.has(seg)) {
            return null;
        }
        Object v = obj.opt(seg);
        if ("result".equals(seg)) {
            return normalizeJsonResultValue(v);
        }
        return v;
    }

    /** Parse JSON-string {@code result} envelopes before nested projection. */
    static Object normalizeJsonResultValue(Object v) {
        if (v == null || JSONObject.NULL.equals(v)) {
            return null;
        }
        if (v instanceof JSONObject || v instanceof JSONArray) {
            return v;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.startsWith("{")) {
                try {
                    return new JSONObject(s);
                } catch (Exception ignored) {
                    return v;
                }
            }
            if (s.startsWith("[")) {
                try {
                    return new JSONArray(s);
                } catch (Exception ignored) {
                    return v;
                }
            }
        }
        return v;
    }
}
