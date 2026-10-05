package com.thingworx.things.agent.hostcontext;

import java.util.List;

import org.joda.time.DateTime;

import com.thingworx.things.agent.AgentMessageStreamAppender;
import com.thingworx.things.agent.AgentMessageStreamHistoryExporter;
import com.thingworx.things.agent.AgentMessageStreamReader;
import com.thingworx.things.agent.StreamHistoryBounds;
import com.thingworx.types.collections.ValueCollection;

/**
 * Bounded backward lookup of the prior user row's host-context snapshot (rehydrate / cold start).
 * Scans for the latest {@code role=user} row with {@code hostContextSnapshotJson}, expanding the
 * Stream query window when a tool-heavy tail pushes the user row beyond a small role-blind cap.
 */
public final class HostContextPreviousSnapshotLookup {

    private static final int[] SCAN_CAPS = {256, 1024, 4096, AgentMessageStreamHistoryExporter.HARD_CAP_STREAM_ROWS};

    @FunctionalInterface
    interface ChronologicalRowSource {
        List<ValueCollection> queryChronologicalRows(String conversationSource, int maxItems,
                DateTime startDateExclusiveOrNull) throws Exception;
    }

    private HostContextPreviousSnapshotLookup() {
    }

    public static HostContextPreviousSnapshot resolve(String conversationSource,
            DateTime historyClearedAtOrNull) {
        return resolve(conversationSource, historyClearedAtOrNull, AgentMessageStreamReader::queryChronologicalRows);
    }

    static HostContextPreviousSnapshot resolve(String conversationSource, DateTime historyClearedAtOrNull,
            ChronologicalRowSource rowSource) {
        try {
            DateTime start = StreamHistoryBounds.queryStartAfterClear(historyClearedAtOrNull);
            for (int cap : SCAN_CAPS) {
                List<ValueCollection> rows = rowSource.queryChronologicalRows(conversationSource, cap, start);
                HostContextPreviousSnapshot found = scanLatestUserSnapshot(rows);
                if (found.kind() != HostContextPreviousSnapshot.Kind.NONE) {
                    return found;
                }
                if (rows.isEmpty() || rows.size() < cap) {
                    return HostContextPreviousSnapshot.NONE;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return HostContextPreviousSnapshot.NONE;
    }

    /**
     * Newest-to-oldest scan for the latest user row carrying a non-empty snapshot JSON.
     */
    static HostContextPreviousSnapshot scanLatestUserSnapshot(List<ValueCollection> chronologicalRows) {
        if (chronologicalRows == null || chronologicalRows.isEmpty()) {
            return HostContextPreviousSnapshot.NONE;
        }
        for (int i = chronologicalRows.size() - 1; i >= 0; i--) {
            ValueCollection row = chronologicalRows.get(i);
            String role = AgentMessageStreamReader.stringField(row, "role").trim().toLowerCase();
            if (!"user".equals(role)) {
                continue;
            }
            String snap = AgentMessageStreamReader.stringField(row,
                    AgentMessageStreamAppender.FIELD_HOST_CONTEXT_SNAPSHOT_JSON).trim();
            if (!snap.isEmpty()) {
                return HostContextPreviousSnapshot.fromSnapshotJson(snap);
            }
        }
        return HostContextPreviousSnapshot.NONE;
    }
}
