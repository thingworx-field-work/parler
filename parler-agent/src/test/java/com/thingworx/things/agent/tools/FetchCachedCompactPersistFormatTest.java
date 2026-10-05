package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class FetchCachedCompactPersistFormatTest {

    @Test
    void stamp_addsFormatWhenCompactStructural() {
        String in = "{\"status\":\"success\",\"cacheId\":\"c1\",\"sampleOnly\":true,\"rows\":[]}";
        String out = FetchCachedCompactPersistFormat.stampWhenApplicable(in);
        JSONObject o = new JSONObject(out);
        assertEquals(FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1,
                o.getString(FetchCachedCompactPersistFormat.FORMAT_KEY));
    }

    @Test
    void stamp_idempotentWhenAlreadyStamped() {
        JSONObject root = new JSONObject();
        root.put("status", "success");
        root.put("cacheId", "c1");
        root.put("sampleOnly", true);
        root.put(FetchCachedCompactPersistFormat.FORMAT_KEY, FetchCachedCompactPersistFormat.FORMAT_VALUE_COMPACT_V1);
        String in = root.toString();
        assertEquals(in, FetchCachedCompactPersistFormat.stampWhenApplicable(in));
    }

    @Test
    void stamp_skipsNonCompactInlineSuccess() {
        String in = "{\"status\":\"success\",\"cacheId\":\"c1\",\"returnedRows\":3,\"rows\":[{\"a\":1}]}";
        assertEquals(in, FetchCachedCompactPersistFormat.stampWhenApplicable(in));
    }

    @Test
    void isLegacy_detectsRowsOmitted() {
        JSONObject o = new JSONObject(
                "{\"status\":\"success\",\"cacheId\":\"x\",\"rowsOmitted\":true,\"rows\":[]}");
        assertTrue(FetchCachedCompactPersistFormat.isLegacyStructuralCompactFetchSuccess(o));
    }

    @Test
    void isLegacy_rejectsMissingCacheId() {
        JSONObject o = new JSONObject("{\"status\":\"success\",\"sampleOnly\":true}");
        assertFalse(FetchCachedCompactPersistFormat.isLegacyStructuralCompactFetchSuccess(o));
    }
}
