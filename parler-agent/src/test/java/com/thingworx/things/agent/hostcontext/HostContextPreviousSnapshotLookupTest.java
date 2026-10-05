package com.thingworx.things.agent.hostcontext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.AgentMessageStreamAppender;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.StringPrimitive;

class HostContextPreviousSnapshotLookupTest {

    @BeforeEach
    void resetTemplates() {
        HostContextTemplateRegistry.resetBuiltInCacheForTests();
        HostContextTestTemplates.installBuiltInLikeTestTemplates();
    }

    @Test
    void scanLatestUserSnapshot_skipsToolTailAfterPriorUserRow() {
        String snap = sampleAcceptedSnapshotJson();
        List<ValueCollection> rows = new ArrayList<>();
        rows.add(row("user", "hello", snap));
        for (int i = 0; i < 100; i++) {
            rows.add(row("tool", "tool-result-" + i, ""));
        }
        HostContextPreviousSnapshot found = HostContextPreviousSnapshotLookup.scanLatestUserSnapshot(rows);
        assertEquals(HostContextPreviousSnapshot.Kind.ACCEPTED, found.kind());
        assertNotNull(found.acceptedHashOrNull());
    }

    @Test
    void resolve_expandsQueryCapWhenUserRowBehindToolTail() {
        String snap = sampleAcceptedSnapshotJson();
        List<ValueCollection> full = new ArrayList<>();
        full.add(row("user", "hello", snap));
        for (int i = 0; i < 300; i++) {
            full.add(row("tool", "tool-result-" + i, ""));
        }
        HostContextPreviousSnapshot found = HostContextPreviousSnapshotLookup.resolve("conv-1", null,
                (source, maxItems, start) -> tailWindow(full, maxItems));
        assertEquals(HostContextPreviousSnapshot.Kind.ACCEPTED, found.kind());
        assertNotNull(found.acceptedHashOrNull());
    }

    @Test
    void renderedOnly_hasNoFreshnessBlock() {
        String raw = "{\"key\":\"asset_detail.current_asset\",\"context\":{"
                + "\"page\":\"Asset Detail\",\"thingName\":\"Pump-01\",\"tab\":\"Alerts\","
                + "\"timeWindow\":{\"kind\":\"relative\",\"value\":\"24h\"}}}";
        HostContextTurnPrep prep = HostContextTurnPrep.renderedOnly(raw, null);
        assertNotNull(prep.llmEphemeralPromptOrNull);
        assertFalse(prep.llmEphemeralPromptOrNull.contains("Host page context freshness:"));
        assertNullSnapshot(prep);
    }

    /** Mimics {@code QueryStreamData} newest-{@code maxItems} window in chronological order. */
    private static List<ValueCollection> tailWindow(List<ValueCollection> chronological, int maxItems) {
        if (chronological.size() <= maxItems) {
            return new ArrayList<>(chronological);
        }
        return new ArrayList<>(chronological.subList(chronological.size() - maxItems, chronological.size()));
    }

    private static String sampleAcceptedSnapshotJson() {
        return HostContextSnapshotBuilder.buildSnapshotJson(
                HostContextUplink.evaluate("{\"key\":\"asset_detail.current_asset\",\"context\":{"
                        + "\"page\":\"Asset Detail\",\"thingName\":\"Pump-01\",\"tab\":\"Alerts\","
                        + "\"timeWindow\":{\"kind\":\"relative\",\"value\":\"24h\"}}}"),
                "{\"key\":\"asset_detail.current_asset\",\"context\":{}}",
                HostContextPreviousSnapshot.NONE);
    }

    private static void assertNullSnapshot(HostContextTurnPrep prep) {
        org.junit.jupiter.api.Assertions.assertNull(prep.snapshotJson);
    }

    private static ValueCollection row(String role, String content, String snapshotJson) {
        ValueCollection vc = new ValueCollection();
        vc.put("role", new StringPrimitive(role));
        vc.put("content", new StringPrimitive(content));
        vc.put(AgentMessageStreamAppender.FIELD_HOST_CONTEXT_SNAPSHOT_JSON, new StringPrimitive(snapshotJson));
        return vc;
    }
}
