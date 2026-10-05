package com.thingworx.things.agent.taskstate;

import java.util.Locale;

import org.json.JSONObject;

/**
 * Maps tool JSON error envelopes to {@link TaskStateErrorCode} (exact keys only, v1a).
 */
public final class TaskStateErrorMapper {

    private TaskStateErrorMapper() {}

    public static TaskStateErrorCode mapFromToolJson(JSONObject root) {
        if (root == null) {
            return TaskStateErrorCode.UNKNOWN;
        }
        if (root.has("errorCode") && !root.isNull("errorCode")) {
            return parseEnum(root.optString("errorCode", null));
        }
        if (root.has("code") && !root.isNull("code")) {
            return parseEnum(root.optString("code", null));
        }
        if (root.has("error") && root.get("error") instanceof JSONObject) {
            JSONObject err = root.getJSONObject("error");
            if (err.has("code")) {
                return parseEnum(err.optString("code", null));
            }
        }
        return TaskStateErrorCode.UNKNOWN;
    }

    static TaskStateErrorCode parseEnum(String raw) {
        if (raw == null || raw.isEmpty()) {
            return TaskStateErrorCode.UNKNOWN;
        }
        String n = raw.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        if ("INVALID_PARAMETERS".equals(n)) {
            return TaskStateErrorCode.PARAMETER_INVALID;
        }
        try {
            return TaskStateErrorCode.valueOf(n);
        } catch (IllegalArgumentException e) {
            return TaskStateErrorCode.UNKNOWN;
        }
    }
}
