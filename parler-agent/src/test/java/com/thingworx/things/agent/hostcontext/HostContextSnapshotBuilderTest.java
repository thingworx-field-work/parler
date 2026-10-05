package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostContextSnapshotBuilderTest {

    private static final String RAW = "{\"key\":\"asset_detail.current_asset\",\"context\":{"
            + "\"page\":\"Asset Detail\",\"thingName\":\"Pump-01\",\"tab\":\"Alerts\","
            + "\"timeWindow\":{\"kind\":\"relative\",\"value\":\"24h\"}}}";

    @BeforeEach
    void resetTemplates() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
        HostContextTestTemplates.installBuiltInLikeTestTemplates();
    }

    @Test
    void accepted_anchor_storesRawJson() {
        HostContextUplink.Decision d = HostContextUplink.evaluate(RAW);
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, RAW, HostContextPreviousSnapshot.NONE);
        assertTrue(json.contains("\"accepted\":true"));
        assertTrue(json.contains("\"rawJsonStored\":true"));
        assertTrue(json.contains("\"changedFromPreviousUserTurn\":true"));
        assertTrue(json.contains("\"rawJson\":"));
    }

    @Test
    void accepted_unchanged_omitsRawJson() {
        HostContextUplink.Decision d = HostContextUplink.evaluate(RAW);
        String hash = HostContextSnapshotBuilder.sha256Prefixed(RAW);
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, RAW,
                HostContextPreviousSnapshot.accepted(hash));
        assertTrue(json.contains("\"rawJsonStored\":false"));
        assertFalse(json.contains("\"rawJson\":"));
        assertTrue(json.contains("\"changedFromPreviousUserTurn\":false"));
    }

    @Test
    void absent_after_absent_unchanged() {
        HostContextUplink.Decision d = HostContextUplink.evaluate(null);
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, null, HostContextPreviousSnapshot.absent());
        assertTrue(json.contains("\"outcome\":\"ABSENT\""));
        assertTrue(json.contains("\"changedFromPreviousUserTurn\":false"));
    }

    @Test
    void absent_after_accepted_changed() {
        HostContextUplink.Decision d = HostContextUplink.evaluate(null);
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, null,
                HostContextPreviousSnapshot.accepted("sha256:abc"));
        assertTrue(json.contains("\"changedFromPreviousUserTurn\":true"));
    }

    @Test
    void rejected_recordsOutcomeWithoutRawJson() {
        HostContextUplink.Decision d = HostContextUplink.evaluate("{\"context\":{}}");
        String json = HostContextSnapshotBuilder.buildSnapshotJson(d, "{\"context\":{}}",
                HostContextPreviousSnapshot.NONE);
        assertTrue(json.contains("\"accepted\":false"));
        assertTrue(json.contains("\"outcome\":\"MISSING_KEY\""));
        assertFalse(json.contains("\"rawJson\":"));
    }

    @Test
    void renderedOnly_differsFromFullTurnPrepOnChangedClaim() {
        String raw = "{\"key\":\"asset_detail.current_asset\",\"context\":{"
                + "\"page\":\"Asset Detail\",\"thingName\":\"Pump-01\",\"tab\":\"Alerts\","
                + "\"timeWindow\":{\"kind\":\"relative\",\"value\":\"24h\"}}}";
        HostContextTurnPrep rendered = HostContextTurnPrep.renderedOnly(raw, null);
        HostContextTurnPrep full = HostContextTurnPrep.prepare(raw, null, HostContextPreviousSnapshot.NONE);
        assertNotNull(full.llmEphemeralPromptOrNull);
        assertTrue(full.llmEphemeralPromptOrNull.contains("Host page context freshness:"));
        assertFalse(rendered.llmEphemeralPromptOrNull.contains("Host page context freshness:"));
    }

    @Test
    void freshness_prompt_stablePrefix() {
        String changed = HostContextFreshnessPrompt.build(true);
        String unchanged = HostContextFreshnessPrompt.build(false);
        String prefix = "Host page context freshness:\n";
        assertTrue(changed.startsWith(prefix));
        assertTrue(unchanged.startsWith(prefix));
        assertTrue(changed.endsWith("changed since the previous user turn."));
        assertTrue(unchanged.endsWith("unchanged since the previous user turn."));
    }

    @Test
    void hash_isDeterministic() {
        String a = HostContextSnapshotBuilder.sha256Prefixed(RAW);
        String b = HostContextSnapshotBuilder.sha256Prefixed(RAW);
        assertEquals(a, b);
        assertTrue(a.startsWith("sha256:"));
    }
}
