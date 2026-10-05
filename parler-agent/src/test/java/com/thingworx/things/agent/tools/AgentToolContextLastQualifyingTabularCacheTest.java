package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Conversation-scoped last qualifying tabular {@code cacheId} for P2 TOKEN (no ThingWorx static init).
 */
class AgentToolContextLastQualifyingTabularCacheTest {

    private static final String CONV = "zzz-parler-tab-cache-test";

    @BeforeEach
    void setup() {
        AgentToolContext.clear();
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.clearLastQualifyingTabularCacheIdForConversation();
        AgentToolContext.clear();
    }

    @AfterEach
    void tearDown() {
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId(CONV);
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId("conv-d-switch-test");
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId("conv-e-switch-test");
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId("conv-api-clear");
        AgentToolContext.clear();
        AgentToolContext.setConversationId("");
        AgentToolContext.clearLastQualifyingTabularCacheIdForConversation();
        AgentToolContext.clear();
    }

    @Test
    void noteAndGet_roundTrip() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("cache-1");
        assertEquals("cache-1", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void survivesClear_thenReadableOnNextBind() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("cache-b");
        AgentToolContext.clear();
        AgentToolContext.setConversationId(CONV);
        assertEquals("cache-b", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void clearLast_removesForConversation() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("x");
        AgentToolContext.clearLastQualifyingTabularCacheIdForConversation();
        assertNull(AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void setConversationId_switchingRemovesPreviousConversationEntry() {
        AgentToolContext.setConversationId("conv-d-switch-test");
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("only-d");
        AgentToolContext.setConversationId("conv-e-switch-test");
        AgentToolContext.setConversationId("conv-d-switch-test");
        assertNull(AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void mirrorKey_singleTurnWithRequestId_isPerRequest() {
        assertEquals(AgentToolContext.SINGLE_TURN_CONVERSATION_ID + '\u0001' + "r1",
                AgentToolContext.tabularTokenMirrorMapKeyForConversationAndRequest(null, "r1"));
        assertEquals(AgentToolContext.SINGLE_TURN_CONVERSATION_ID + '\u0001' + "r2",
                AgentToolContext.tabularTokenMirrorMapKeyForConversationAndRequest(
                        AgentToolContext.SINGLE_TURN_CONVERSATION_ID, "r2"));
        assertEquals("uuid-1",
                AgentToolContext.tabularTokenMirrorMapKeyForConversationAndRequest("uuid-1", "ignored"));
    }

    @Test
    void singleTurn_withParlerRequestId_clearRemovesMirror() {
        AgentToolContext.setConversationId("");
        AgentToolContext.setParlerStreamIds("req-x", "gw");
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("cid-1");
        assertEquals("cid-1", AgentToolContext.getConversationLastQualifyingTabularCacheId());
        AgentToolContext.clear();
        AgentToolContext.setConversationId("");
        AgentToolContext.setParlerStreamIds("req-x", "gw");
        assertNull(AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void removeMirrorForConversationId_clearsPersistentKey() {
        AgentToolContext.setConversationId("conv-api-clear");
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("z");
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId("conv-api-clear");
        assertNull(AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void removeMirror_singleTurnId_noOp() {
        AgentToolContext.setConversationId("");
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("keep");
        AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId(AgentToolContext.SINGLE_TURN_CONVERSATION_ID);
        assertEquals("keep", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void pruneTabularTokenMirrorIfPointsTo_removesWhenMatches() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("gone-id");
        AgentToolContext.pruneTabularTokenMirrorIfPointsTo("gone-id");
        assertNull(AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void pruneTabularTokenMirrorIfPointsTo_noOpWhenDifferent() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("keep");
        AgentToolContext.pruneTabularTokenMirrorIfPointsTo("other");
        assertEquals("keep", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void noteTokenMirrorFromSummarizeJson_updatesMirrorFromSourceCacheId() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteTokenMirrorFromSummarizeCachedResultJson(
                "{\"status\":\"success\",\"sourceCacheId\":\"from-sum\"}");
        assertEquals("from-sum", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void noteTokenMirrorFromSummarizeJson_ignoresNonSuccess() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("old");
        AgentToolContext.noteTokenMirrorFromSummarizeCachedResultJson(
                "{\"status\":\"error\",\"sourceCacheId\":\"ignored\"}");
        assertEquals("old", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }

    @Test
    void noteTokenMirrorFromSummarizeJson_malformedJson_preservesMirror() {
        AgentToolContext.setConversationId(CONV);
        AgentToolContext.noteLastQualifyingTabularCacheIdForConversation("keep");
        AgentToolContext.noteTokenMirrorFromSummarizeCachedResultJson("not-json{{{");
        assertEquals("keep", AgentToolContext.getConversationLastQualifyingTabularCacheId());
    }
}
