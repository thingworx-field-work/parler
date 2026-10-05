package com.thingworx.things.agent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.things.agent.tools.AgentToolContext;
import com.thingworx.things.agent.tools.InvokeServiceExecutor;
import com.thingworx.things.agent.tools.TabularChartRoundHooks;
import com.thingworx.things.agent.tools.TabularChartRoundState;

/**
 * Second pass over an approved-HITL promoted JSON table. {@code AgentLoop} calls
 * {@code afterBuiltInToolResult} for the approved body and <b>ignores the returned string</b>, so that pass can
 * only register the handle the body already carries. It must do so only while the cache still holds the artifact
 * for this conversation, and must never rebuild a removed artifact from the transcript rows.
 */
class PromotedJsonReplayLivenessTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONVERSATION = "replay-liveness";

    @BeforeEach
    void setUp() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
        AgentToolContext.setConversationId(CONVERSATION);
        AgentToolContext.resetTabularChartRound();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
    }

    private static String envelope(int n) throws Exception {
        ArrayNode rows = MAPPER.createArrayNode();
        for (int i = 0; i < n; i++) {
            rows.addObject().put("state", "s" + i).put("value", i + 1);
        }
        ObjectNode business = MAPPER.createObjectNode();
        business.put("status", "success");
        business.put("hasMore", false);
        business.set("rows", rows);
        ObjectNode env = MAPPER.createObjectNode();
        env.put("status", "success");
        env.put("resultKind", "JSON");
        env.put("result", MAPPER.writeValueAsString(business));
        return MAPPER.writeValueAsString(env);
    }

    private static String approve() throws Exception {
        String approved = TabularChartRoundHooks.augmentJsonSourceHandle("invoke_service", envelope(25));
        assertFalse(MAPPER.readTree(approved).path("cacheId").asText().isEmpty());
        return approved;
    }

    private static String idOf(String approved) throws Exception {
        return MAPPER.readTree(approved).path("cacheId").asText();
    }

    /** Mirrors {@code AgentLoop.runInner}: the continuation calls the hook and drops its return value. */
    private static void continuationPass(String approvedBody) {
        TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", approvedBody);
    }

    @Test
    void liveHandle_isRegisteredOnce_underTheSameId_andTheBodyIsNotRewritten() throws Exception {
        String approved = approve();
        assertEquals(approved, TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", approved));
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(idOf(approved), st.getLastCacheId());
        assertEquals(1, st.getQualifyingTabularSuccessCount());
    }

    @Test
    void invalidatedArtifact_withItsDescriptorStillIndexed_isNotRegistered_andThePriorTargetStays() throws Exception {
        continuationPass(envelope(3));
        assertEquals(1, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
        String approved = approve();
        String id = idOf(approved);
        TabularArtifactHub.resolveCache().invalidate(ArtifactCacheIds.resolveStoreLookupRef(CONVERSATION, id),
                TabularArtifactHub.accessContext(CONVERSATION));
        assertNotNull(InvokeServiceExecutor.lookupSourceDescriptor(id), "the descriptor sidecar outlives the artifact");
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, id));

        continuationPass(approved);
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(1, st.getQualifyingTabularSuccessCount(), "the dead handle added nothing");
        assertNull(st.getLastCacheId(), "the dead handle is not the chart target");
        assertEquals(3, st.getLastInlineRows().size(), "the prior legal table is still the target");
    }

    @Test
    void clearedScope_isNotRepopulatedFromTheTranscriptRows() throws Exception {
        String approved = approve();
        String id = idOf(approved);
        TabularArtifactHub.invalidateCurrentScope();
        assertFalse(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, id));

        String out = TabularChartRoundHooks.afterBuiltInToolResult("invoke_service", approved);
        assertEquals(approved, out, "no new handle is written over the one the model already holds");
        TabularChartRoundState st = AgentToolContext.tabularChartRoundState();
        assertEquals(0, st.getQualifyingTabularSuccessCount());
        assertFalse(st.hasChartableLastInvokeTarget(), "nothing was stored or registered for the removed data");
        assertNull(InvokeServiceExecutor.lookupCachedInfotable(id));
    }

    @Test
    void differentConversation_doesNotAdoptTheHandle() throws Exception {
        String approved = approve();
        AgentToolContext.setConversationId("another-conversation");
        AgentToolContext.resetTabularChartRound();
        continuationPass(approved);
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
        assertTrue(ArtifactCacheLiveness.isIndexedForConversation(CONVERSATION, idOf(approved)),
                "the owner's artifact is untouched");
    }

    @Test
    void differentPrincipal_doesNotAdoptTheHandle() throws Exception {
        String approved = approve();
        ArtifactAccessContextFactory.setCurrentPrincipalLookup(() -> "someone-else");
        AgentToolContext.resetTabularChartRound();
        continuationPass(approved);
        assertEquals(0, AgentToolContext.tabularChartRoundState().getQualifyingTabularSuccessCount());
    }
}
