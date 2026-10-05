package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.joda.time.DateTime;
import org.junit.jupiter.api.Test;

class StreamHistoryBoundsTest {

    @Test
    void queryStartAfterClear_null_when_never_cleared() {
        assertNull(StreamHistoryBounds.queryStartAfterClear(null));
    }

    @Test
    void queryStartAfterClear_adds_one_millisecond() {
        DateTime t = new DateTime(2026, 5, 7, 12, 0, 0, 0);
        assertEquals(t.getMillis() + 1, StreamHistoryBounds.queryStartAfterClear(t).getMillis());
    }
}
