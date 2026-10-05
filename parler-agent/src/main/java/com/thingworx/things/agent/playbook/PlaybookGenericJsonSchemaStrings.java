package com.thingworx.things.agent.playbook;

import org.json.JSONObject;

/**
 * Generic Playbook JSON args: schema fields that must be JSON strings (not booleans/numbers coerced via
 * {@link JSONObject#optString}) — see design-review guidance on fail-closed typing for {@code group_by} keys and
 * measure specs.
 */
final class PlaybookGenericJsonSchemaStrings {

    private PlaybookGenericJsonSchemaStrings() {}

    /**
     * Required string field: must exist, be a JSON string (not null), trimmed; empty after trim is allowed unless
     * caller checks.
     */
    static String requireTrimmed(JSONObject o, String key, String ctx) throws PlaybookRunException {
        if (!o.has(key)) {
            throw new PlaybookRunException(ctx + ": " + key + " is required");
        }
        Object v = o.get(key);
        if (v == JSONObject.NULL) {
            throw new PlaybookRunException(ctx + ": " + key + " must be a JSON string", "GENERIC_INPUT_INVALID");
        }
        if (!(v instanceof String)) {
            throw new PlaybookRunException(ctx + ": " + key + " must be a JSON string", "GENERIC_INPUT_INVALID");
        }
        return ((String) v).trim();
    }

    /** Optional string field: absent or JSON null → empty; present non-string → {@code GENERIC_INPUT_INVALID}. */
    static String optionalTrimmed(JSONObject o, String key, String ctx) throws PlaybookRunException {
        if (!o.has(key) || o.isNull(key)) {
            return "";
        }
        Object v = o.get(key);
        if (!(v instanceof String)) {
            throw new PlaybookRunException(ctx + ": " + key + " must be a JSON string", "GENERIC_INPUT_INVALID");
        }
        return ((String) v).trim();
    }
}
