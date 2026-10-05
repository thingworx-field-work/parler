package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class ParlerAnalyzeEntitySetEntityListTableWireTest {

    @Test
    void inline_success_builds_entity_list_table_with_cache_id() {
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_SET_INLINE\",\"cacheId\":\"cid-inline\","
                + "\"totalRows\":2,\"matchedKeys\":2,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],"
                + "\"rows\":[{\"name\":\"a\"},{\"name\":\"b\"}]}";
        JSONObject tb = ParlerAnalyzeEntitySetEntityListTableWire.tableBlockFromAnalyzeEntitySetToolSuccessJson(body);
        assertNotNull(tb);
        assertEquals("entity-list", tb.getString("kind"));
        assertEquals(2, tb.getInt("shownRows"));
        assertEquals(2, tb.getInt("totalRows"));
        assertEquals("cid-inline", tb.getString("cacheId"));
        assertEquals(1, tb.getJSONArray("columns").length());
    }

    @Test
    void empty_success_has_zero_rows_and_cache_id() {
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_SET_EMPTY\",\"cacheId\":\"cid-empty\","
                + "\"totalRows\":0,\"matchedKeys\":0,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],\"rows\":[]}";
        JSONObject tb = ParlerAnalyzeEntitySetEntityListTableWire.tableBlockFromAnalyzeEntitySetToolSuccessJson(body);
        assertNotNull(tb);
        assertEquals(0, tb.getInt("shownRows"));
        assertEquals("cid-empty", tb.getString("cacheId"));
    }

    @Test
    void large_success_uses_sample_rows() {
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_SET_LARGE\",\"cacheId\":\"cid-large\","
                + "\"totalRows\":99,\"matchedKeys\":99,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],"
                + "\"sampleRows\":[{\"name\":\"s0\"},{\"name\":\"s1\"}]}";
        JSONObject tb = ParlerAnalyzeEntitySetEntityListTableWire.tableBlockFromAnalyzeEntitySetToolSuccessJson(body);
        assertNotNull(tb);
        assertEquals(2, tb.getInt("shownRows"));
        assertEquals(99, tb.getInt("totalRows"));
    }

    @Test
    void util_delegates_to_analyze_entity_set_wire() {
        String body = "{\"status\":\"success\",\"resultKind\":\"ENTITY_SET_INLINE\",\"cacheId\":\"cid-u\","
                + "\"totalRows\":1,\"matchedKeys\":1,"
                + "\"columns\":[{\"name\":\"name\",\"baseType\":\"STRING\"}],\"rows\":[{\"name\":\"x\"}]}";
        JSONObject tb = ParlerToolTableWireUtil.tableBlockFromListClassToolJson(body);
        assertNotNull(tb);
        assertEquals("entity-list", tb.getString("kind"));
        assertEquals("cid-u", tb.getString("cacheId"));
    }
}
