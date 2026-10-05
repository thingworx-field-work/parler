package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.llm.ToolCall;

/**
 * B11: TOKEN sentinel failures use {@code LAST_TABULAR_CACHE_UNAVAILABLE} + recoveryHint
 * (not a bare undifferentiated {@code CACHE_MISS}).
 */
class CachedTabularTokenRecoveryHintTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearContext() {
        AgentToolContext.clear();
    }

    @Test
    void tabulate_tokenWithNoMirror_returnsTargetedRecovery() throws Exception {
        AgentToolContext.setConversationId("b11-conv");
        String json = CachedTabularToolsExecutor.executeTabulateCachedResult(new ToolCall("t1", "tabulate_cached_result",
                "{\"cacheId\":\"" + CachedTabularLastCacheHandle.TOKEN + "\",\"mode\":\"filter_count\"}"));
        JsonNode n = MAPPER.readTree(json);
        assertEquals("error", n.path("status").asText());
        assertEquals("LAST_TABULAR_CACHE_UNAVAILABLE", n.path("code").asText());
        assertEquals("retry_with_explicit_cacheId", n.path("recoveryHint").path("action").asText());
        assertTrue(n.path("recoveryHint").path("hint").asText().contains("cacheId"), n.toString());
    }
}
