package com.thingworx.things.agent;

import org.joda.time.DateTime;

/**
 * Stream query lower bound after {@code historyClearedAt} ({@code docs/agent/conversation-continuity.md} §10).
 */
public final class StreamHistoryBounds {

    private StreamHistoryBounds() {}

    /**
     * Exclusive lower bound for {@code QueryStreamData.startDate} when rows at the clear timestamp must not appear as
     * effective history (assumes platform {@code startDate} is inclusive — use {@code cleared + 1ms}).
     */
    public static DateTime queryStartAfterClear(DateTime historyClearedAtOrNull) {
        if (historyClearedAtOrNull == null) {
            return null;
        }
        return historyClearedAtOrNull.plusMillis(1);
    }
}
