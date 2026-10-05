package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Live {@link PeriodOverPeriodPeriodResolver} coverage retained after S13 removed
 * {@code PeriodOverPeriodChartBuilder}. Duplicate-series rejection for the shipped overlay path is
 * covered by {@link com.thingworx.things.agent.HistoryOverlayChartBuilderTest}.
 */
class PeriodOverPeriodPeriodResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant ANCHOR = Instant.parse("2026-06-30T15:00:00.000Z");

    @Test
    void flagshipClassThreeOneHourWindowsWithoutExplicitIso() throws Exception {
        var today = MAPPER.readTree("{\"label\":\"Today\",\"relativeDuration\":\"1h\"}");
        var yesterday = MAPPER.readTree("{\"label\":\"Yesterday\",\"relativeDuration\":\"1h\",\"anchorOffset\":\"1d\"}");
        var lastWeek = MAPPER.readTree("{\"label\":\"Last week\",\"relativeDuration\":\"1h\",\"anchorOffset\":\"7d\"}");

        PeriodOverPeriodPeriodResolver.Outcome o0 = PeriodOverPeriodPeriodResolver.resolve(today, ANCHOR);
        PeriodOverPeriodPeriodResolver.Outcome o1 = PeriodOverPeriodPeriodResolver.resolve(yesterday, ANCHOR);
        PeriodOverPeriodPeriodResolver.Outcome o2 = PeriodOverPeriodPeriodResolver.resolve(lastWeek, ANCHOR);
        assertFalse(o0.isError());
        assertFalse(o1.isError());
        assertFalse(o2.isError());

        long d0 = Duration.between(o0.startUtc, o0.endUtc).getSeconds();
        long d1 = Duration.between(o1.startUtc, o1.endUtc).getSeconds();
        long d2 = Duration.between(o2.startUtc, o2.endUtc).getSeconds();
        assertEquals(3600L, d0);
        assertEquals(3600L, d1);
        assertEquals(3600L, d2);

        assertEquals(ANCHOR, o0.endUtc);
        assertEquals(ANCHOR.minusSeconds(86400L), o1.endUtc);
        assertEquals(ANCHOR.minusSeconds(7L * 86400L), o2.endUtc);
    }

    @Test
    void duplicateRelativeWithoutOffsetResolvesIdenticalWindows() throws Exception {
        var a = MAPPER.readTree("{\"label\":\"A\",\"relativeDuration\":\"1h\"}");
        var b = MAPPER.readTree("{\"label\":\"B\",\"relativeDuration\":\"1h\"}");
        PeriodOverPeriodPeriodResolver.Outcome oa = PeriodOverPeriodPeriodResolver.resolve(a, ANCHOR);
        PeriodOverPeriodPeriodResolver.Outcome ob = PeriodOverPeriodPeriodResolver.resolve(b, ANCHOR);
        assertFalse(oa.isError());
        assertFalse(ob.isError());
        assertEquals(oa.startUtc, ob.startUtc);
        assertEquals(oa.endUtc, ob.endUtc);
    }

    @Test
    void anchorOffsetWithExplicitIsoRejected() throws Exception {
        var p = MAPPER.readTree("{\"label\":\"Bad\",\"anchorOffset\":\"1d\","
                + "\"startTime\":\"2026-06-29T14:00:00.000Z\",\"endTime\":\"2026-06-29T15:00:00.000Z\"}");
        PeriodOverPeriodPeriodResolver.Outcome o = PeriodOverPeriodPeriodResolver.resolve(p, ANCHOR);
        assertTrue(o.isError());
        assertEquals("POP_OFFSET_WITH_EXPLICIT", o.errorCode);
    }
}
