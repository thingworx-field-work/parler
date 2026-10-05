package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.junit.jupiter.api.Test;

class ParlerTableFileExportPathTest {

    private static final DateTime NOW = new DateTime(2026, 9, 28, 5, 7, 9, DateTimeZone.UTC);

    @Test
    void exportPath_usesCompactUtcTimestamp() {
        assertEquals("/Administrator/20260928/20260928T050709Z_req-1.csv",
                ParlerTableFileExportHook.exportPath("Administrator", "req-1", NOW));
    }

    @Test
    void exportPath_sanitizesSegmentsAndDefaultsMissingValues() {
        assertEquals("/anonymous/20260928/20260928T050709Z_req.csv",
                ParlerTableFileExportHook.exportPath(null, null, NOW));
        assertEquals("/a_b/20260928/20260928T050709Z_r_1.csv",
                ParlerTableFileExportHook.exportPath("a b", "r/1", NOW));
    }
}
