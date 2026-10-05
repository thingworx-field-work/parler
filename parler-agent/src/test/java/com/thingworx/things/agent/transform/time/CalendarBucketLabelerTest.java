package com.thingworx.things.agent.transform.time;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.zone.ZoneOffsetTransition;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Bucket;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler.Granularity;

/** CF-03 kernel: reference cases from {@code computing-enhancement/calendar-bucket-reference.json} plus properties. */
class CalendarBucketLabelerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant DOMAIN_START = Instant.parse("0001-01-03T00:00:00Z");

    @TestFactory
    Stream<DynamicTest> referenceCases() throws Exception {
        JsonNode root;
        try (InputStream in = getClass().getResourceAsStream("/computing-enhancement/calendar-bucket-reference.json")) {
            root = MAPPER.readTree(in);
        }
        assertEquals(CalendarBucketLabeler.METHOD_ID, root.path("method").asText());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            tests.add(DynamicTest.dynamicTest(c.path("name").asText(), () -> {
                Bucket b = CalendarBucketLabeler.bucketOf(ZoneId.of(c.path("zone").asText()),
                        Instant.parse(c.path("instant").asText()),
                        Granularity.fromWire(c.path("calendarBucket").asText()));
                assertEquals(Instant.parse(c.path("start").asText()), b.start(), "start");
                assertEquals(Instant.parse(c.path("end").asText()), b.end(), "end");
                assertEquals(c.path("label").asText(), b.label(), "label");
                assertEquals(c.path("seconds").asLong(), b.seconds(), "seconds");
            }));
        }
        assertTrue(tests.size() >= 20);
        return tests.stream();
    }

    /**
     * Every explicit historical transition of every zone of the local rule set, sampled around it and at its
     * edges, for days and hours: containment, one key inside the bucket, idempotence from both ends, adjacency of
     * the neighbours. No exemption list.
     */
    @Test
    void bucketsContainTheirInstant_shareOneKey_andTile_inEveryZone() {
        long samples = 0L;
        List<String> failures = new ArrayList<>();
        for (String id : ZoneId.getAvailableZoneIds()) {
            ZoneId zone = ZoneId.of(id);
            for (ZoneOffsetTransition tr : zone.getRules().getTransitions()) {
                if (tr.getInstant().isBefore(DOMAIN_START)) {
                    continue;
                }
                for (Instant t : around(tr.getInstant())) {
                    for (Granularity g : Granularity.values()) {
                        samples++;
                        String failure = check(zone, t, g);
                        if (failure != null && failures.size() < 10) {
                            failures.add(id + " " + g + " " + t + ": " + failure);
                        }
                    }
                }
            }
        }
        // Rule-based transitions of the current year are not in the explicit list.
        for (String id : new String[] {"Europe/Berlin", "Australia/Lord_Howe", "America/New_York", "Asia/Kolkata"}) {
            for (String day : new String[] {"2026-03-08", "2026-03-29", "2026-04-04", "2026-10-03", "2026-10-25",
                    "2026-11-01"}) {
                Instant from = Instant.parse(day + "T00:00:00Z").minusSeconds(86_400L);
                for (int minute = 0; minute < 3 * 1_440; minute++) {
                    for (Granularity g : Granularity.values()) {
                        samples++;
                        String failure = check(ZoneId.of(id), from.plusSeconds(minute * 60L), g);
                        if (failure != null && failures.size() < 10) {
                            failures.add(id + " " + g + ": " + failure);
                        }
                    }
                }
            }
        }
        assertTrue(failures.isEmpty(), failures.toString());
        assertTrue(samples > 500_000L, "samples=" + samples);
    }

    private static List<Instant> around(Instant transition) {
        List<Instant> out = new ArrayList<>();
        for (long minutes = -1_500L; minutes <= 1_500L; minutes += 293L) {
            out.add(transition.plusSeconds(minutes * 60L));
        }
        out.add(transition);
        out.add(transition.minusNanos(1L));
        out.add(transition.plusNanos(1L));
        out.add(transition.minusSeconds(1L));
        out.add(transition.plusSeconds(1L));
        return out;
    }

    private static String check(ZoneId zone, Instant t, Granularity g) {
        Bucket b = CalendarBucketLabeler.bucketOf(zone, t, g);
        if (t.isBefore(b.start()) || !t.isBefore(b.end())) {
            return "does not contain its instant " + b.start() + " " + b.end();
        }
        Instant last = b.end().minusNanos(1L);
        if (!key(zone, b.start(), g).equals(key(zone, t, g)) || !key(zone, last, g).equals(key(zone, t, g))) {
            return "key differs inside the bucket";
        }
        Bucket fromStart = CalendarBucketLabeler.bucketOf(zone, b.start(), g);
        Bucket fromLast = CalendarBucketLabeler.bucketOf(zone, last, g);
        if (!same(fromStart, b) || !same(fromLast, b)) {
            return "not idempotent from its ends";
        }
        Bucket next = CalendarBucketLabeler.bucketOf(zone, b.end(), g);
        Bucket previous = CalendarBucketLabeler.bucketOf(zone, b.start().minusNanos(1L), g);
        if (!next.start().equals(b.end()) || !previous.end().equals(b.start())) {
            return "neighbours are not adjacent";
        }
        if (key(zone, b.end(), g).equals(key(zone, t, g)) || key(zone, b.start().minusNanos(1L), g)
                .equals(key(zone, t, g))) {
            return "not maximal";
        }
        return null;
    }

    private static boolean same(Bucket a, Bucket b) {
        return a.start().equals(b.start()) && a.end().equals(b.end()) && a.label().equals(b.label());
    }

    private static String key(ZoneId zone, Instant t, Granularity g) {
        LocalDateTime local = LocalDateTime.ofInstant(t, zone);
        return g == Granularity.DAY ? local.toLocalDate().toString()
                : local.toLocalDate() + "T" + local.getHour() + zone.getRules().getOffset(t).getId();
    }

    @Test
    void timeZone_mustBeARegionOrUtc_neverAFixedOffsetOrADefault() {
        assertEquals("Europe/Berlin", CalendarBucketLabeler.requireRegionZone(" Europe/Berlin ").getId());
        assertEquals("UTC", CalendarBucketLabeler.requireRegionZone("UTC").getId());
        for (String bad : new String[] {null, "", "  ", "+02:00", "Z", "UTC+2", "GMT+02:00", "UT-5", "Berlin",
                "Europe/Nowhere", "CEST"}) {
            MeasurementException e = assertThrows(MeasurementException.class,
                    () -> CalendarBucketLabeler.requireRegionZone(bad), String.valueOf(bad));
            assertEquals(CalendarBucketLabeler.TIME_ZONE_INVALID, e.code());
        }
        // Ids that name one non-zero offset, not a place: the whole class, not a list of literals.
        int offsetAliases = 0;
        for (String id : ZoneId.getAvailableZoneIds()) {
            ZoneId zone = ZoneId.of(id);
            boolean fixedNonZero = zone.getRules().isFixedOffset()
                    && zone.getRules().getOffset(Instant.EPOCH).getTotalSeconds() != 0;
            boolean geographical = id.indexOf('/') > 0 && !id.startsWith("Etc/") && !id.startsWith("SystemV/");
            if (fixedNonZero && !geographical) {
                offsetAliases++;
                assertEquals(CalendarBucketLabeler.TIME_ZONE_INVALID, assertThrows(MeasurementException.class,
                        () -> CalendarBucketLabeler.requireRegionZone(id), id).code());
            } else {
                assertEquals(id, CalendarBucketLabeler.requireRegionZone(id).getId());
            }
        }
        assertTrue(offsetAliases >= 26, "Etc/GMT-14 to Etc/GMT+12 at least: " + offsetAliases);
        for (String kept : new String[] {"Etc/UTC", "Etc/GMT", "GMT", "Asia/Kolkata", "Asia/Tokyo",
                "America/Phoenix"}) {
            assertEquals(kept, CalendarBucketLabeler.requireRegionZone(kept).getId());
        }
        assertEquals(CalendarBucketLabeler.CALENDAR_BUCKET_INVALID, assertThrows(MeasurementException.class,
                () -> Granularity.fromWire("week")).code());
        assertEquals(CalendarBucketLabeler.CALENDAR_BUCKET_INVALID, assertThrows(MeasurementException.class,
                () -> Granularity.fromWire(null)).code());
    }
}
