package com.thingworx.things.agent.taskstate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ToolCall;

class TaskProgressV1bMatchToolArgsTest {

    @Test
    void match_empty_ok() {
        ToolCall tc = new ToolCall("c1", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"T1\",\"serviceName\":\"S1\"}");
        assertTrue(TaskProgressV1b.matchToolArgs(null, tc, "invoke_service"));
        assertTrue(TaskProgressV1b.matchToolArgs(new JSONObject(), tc, "invoke_service"));
    }

    @Test
    void match_target_entity_and_operation() {
        JSONObject m = new JSONObject();
        m.put("targetName", "T1");
        m.put("operation", "S1");
        ToolCall ok = new ToolCall("x", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"T1\",\"serviceName\":\"S1\"}");
        assertTrue(TaskProgressV1b.matchToolArgs(m, ok, "invoke_service"));
        ToolCall bad = new ToolCall("x", "invoke_service",
                "{\"entityType\":\"Thing\",\"entityName\":\"Other\",\"serviceName\":\"S1\"}");
        assertFalse(TaskProgressV1b.matchToolArgs(m, bad, "invoke_service"));
    }

    @Test
    void fetch_cache_match_uses_cacheId() {
        JSONObject m = new JSONObject();
        m.put("targetName", "cache-777");
        ToolCall tc = new ToolCall("x", "fetch_cached_result",
                "{\"cacheId\":\"cache-777\",\"offset\":0,\"pageSize\":10}");
        assertTrue(TaskProgressV1b.matchToolArgs(m, tc, "fetch_cached_result"));
    }
}
