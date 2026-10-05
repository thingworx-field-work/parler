package com.thingworx.things.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.joda.time.DateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.thingworx.metadata.DataShapeDefinition;
import com.thingworx.metadata.FieldDefinition;
import com.thingworx.things.agent.ToolResultEgressGateway;
import com.thingworx.things.agent.analysis.AnalysisEnvelopeValidator;
import com.thingworx.things.agent.analysis.ComputingOperationAdmission;
import com.thingworx.things.agent.cache.ArtifactCacheTestFixtures;
import com.thingworx.things.agent.cache.TabularArtifactHub;
import com.thingworx.things.agent.compaction.M1aEditableDescriptionMetrics;
import com.thingworx.things.agent.llm.ToolCall;
import com.thingworx.things.agent.llm.ToolDefinition;
import com.thingworx.things.agent.llm.ToolSchemaSizer;
import com.thingworx.things.agent.source.ReadLimitFact;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.source.SourceDescriptor.CompletenessStatus;
import com.thingworx.things.agent.transform.time.CalendarBucketCacheRunner;
import com.thingworx.things.agent.transform.time.CalendarBucketLabeler;
import com.thingworx.things.agent.transform.time.TimeWeightedCacheRunner;
import com.thingworx.types.BaseTypes;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * CF-03 {@code tabulate_cached_result} {@code mode=calendar_bucket}: a real cached artifact through the real
 * schema, admission and dispatch to the labelled table, its descriptor, the envelope, the existing
 * {@code group_metric}, and what the model sees after egress compaction.
 */
class TabulateCalendarBucketModeTest {

    TabulateCalendarBucketModeTest() {
        ArtifactCacheTestFixtures.installFreshInMemoryCache();
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BERLIN_DAY = ",\"timeZone\":\"Europe/Berlin\",\"calendarBucket\":\"day\"";

    @BeforeEach
    void setUp() {
        AgentToolContext.setConversationId("ce4-calendar-bucket");
        ComputingOperationAdmission.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TabularArtifactHub.clearTestState();
        AgentToolContext.clear();
        ComputingOperationAdmission.resetForTests();
    }

    /**
     * The inherited event-row day column: an event is assigned by the instant in the given column only, so an
     * event that starts at 23:30 and ends the next day counts wholly on its start day.
     */
    @Test
    void eventsGroupedByStartDay_countACrossMidnightEventOnItsStartDay() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-03-02T08:00:00Z", "2026-03-02T08:20:00Z"),
                event("2026-03-02T22:30:00Z", "2026-03-02T23:40:00Z"),
                event("2026-03-03T09:00:00Z", "2026-03-03T09:05:00Z")));
        JsonNode byStart = run(cid, "start", BERLIN_DAY);
        assertEquals("OK", byStart.path("status").asText(), byStart.toString());
        assertEquals(CalendarBucketCacheRunner.OUTCOME_OK, byStart.path("reason").asText());
        assertEquals("calendar_bucket", byStart.path("analysisEnvelope").path("operation").asText());
        assertEquals(CalendarBucketLabeler.METHOD_ID,
                byStart.path("analysisEnvelope").path("method").path("id").asText());
        assertEquals(Map.of("2026-03-02", 2, "2026-03-03", 1), countsByLabel(byStart));

        // 23:40Z is 00:40 local on March 3: labelling the end column groups differently.
        assertEquals(Map.of("2026-03-02", 1, "2026-03-03", 2), countsByLabel(run(cid, "end", BERLIN_DAY)));

        InfoTable labelled = InvokeServiceExecutor.lookupCachedInfotable(byStart.path("findingCacheId").asText());
        assertEquals(3, labelled.getRowCount());
        assertEquals(Instant.parse("2026-03-01T23:00:00Z").toEpochMilli(),
                ((DateTime) labelled.getRow(0).getValue("bucketStart")).getMillis());
        assertEquals(86_400d, (Double) labelled.getRow(0).getValue("bucketSeconds"), 0d);
        JsonNode m = byStart.path("analysisEnvelope").path("metrics");
        assertEquals("Europe/Berlin", m.path("timeZone").asText());
        assertEquals("2", m.path("distinctBuckets").asText());
        assertEquals("2", m.path("distinctLabels").asText());
        assertEquals("0", m.path("recurringLabels").asText());
        assertEquals("0", m.path("nonStandardDayBuckets").asText());
        assertFalse(m.path("tzdbVersion").asText().isEmpty());
        for (JsonNode w : byStart.path("warnings")) {
            String text = w.asText().toLowerCase();
            assertFalse(text.contains("utiliz") || text.contains("allocat"), w.asText());
        }
    }

    /** A local date that recurs after a rollback: two intervals, one label, one group. */
    @Test
    void recurringLocalDate_keepsOneLabel_andIsDisclosed() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("1988-10-30T01:30:00Z", null), event("1988-10-30T02:00:30Z", null),
                event("1988-10-30T02:30:00Z", null), event("1988-10-30T05:00:00Z", null)));
        JsonNode out = run(cid, "start", ",\"timeZone\":\"America/Goose_Bay\",\"calendarBucket\":\"day\"");
        JsonNode m = out.path("analysisEnvelope").path("metrics");
        assertEquals("4", m.path("distinctBuckets").asText(), out.toString());
        assertEquals("2", m.path("distinctLabels").asText());
        assertEquals("2", m.path("recurringLabels").asText());
        assertEquals("2", m.path("nonStandardDayBuckets").asText());
        assertEquals(Map.of("1988-10-29", 2, "1988-10-30", 2), countsByLabel(out));
        assertTrue(containsText(out.path("warnings"), "bucketSeconds is not the label's total length"),
                out.path("warnings").toString());
        InfoTable labelled = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
        assertEquals(7_140d, (Double) labelled.getRow(2).getValue("bucketSeconds"), 0d);
    }

    /** Hour buckets with the offset in the label, through the artifact path. */
    @Test
    void hourBuckets_distinguishTheRepeatedHour_andReportRealLength() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-10-25T00:30:00Z", null), event("2026-10-25T01:30:00Z", null)));
        JsonNode berlin = run(cid, "start", ",\"timeZone\":\"Europe/Berlin\",\"calendarBucket\":\"hour\"");
        InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(berlin.path("findingCacheId").asText());
        assertEquals("2026-10-25T02:00+02:00", t.getRow(0).getStringValue("bucketLabel"));
        assertEquals("2026-10-25T02:00+01:00", t.getRow(1).getStringValue("bucketLabel"));

        String lordHowe = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-04-04T15:15:00Z", null)));
        InfoTable clipped = InvokeServiceExecutor.lookupCachedInfotable(run(lordHowe, "start",
                ",\"timeZone\":\"Australia/Lord_Howe\",\"calendarBucket\":\"hour\"").path("findingCacheId").asText());
        assertEquals(1_800d, (Double) clipped.getRow(0).getValue("bucketSeconds"), 0d);
        assertEquals("2026-04-05T01:00+10:30", clipped.getRow(0).getStringValue("bucketLabel"));
    }

    /** Rows are never dropped: a row without a usable time stays, with empty bucket cells. */
    @Test
    void rowsWithoutAUsableTime_areKept_andGroupMetricKeepsItsNullKeyBehaviour() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(textEvents(
                "2026-03-02T08:00:00Z", "not a time", null, "2026-03-02T09:00:00Z"));
        JsonNode out = run(cid, "start", BERLIN_DAY);
        assertEquals("OK", out.path("status").asText(), out.toString());
        JsonNode m = out.path("analysisEnvelope").path("metrics");
        assertEquals("4", m.path("rows").asText());
        assertEquals("2", m.path("assignedRows").asText());
        assertEquals("2", m.path("unassignedRows").asText());
        assertTrue(containsText(out.path("warnings"), "2 row(s) have no usable time"));
        InfoTable labelled = InvokeServiceExecutor.lookupCachedInfotable(out.path("findingCacheId").asText());
        assertEquals(4, labelled.getRowCount());
        assertEquals("not a time", labelled.getRow(1).getStringValue("start"), "source cells and order are kept");
        assertNull(valueOf(labelled.getRow(1), "bucketLabel"));
        assertNull(valueOf(labelled.getRow(2), "bucketStart"));

        // Pinned, not changed: group_metric gathers rows with an empty key in one group of their own.
        JsonNode grouped = tabulate("{\"cacheId\":\"" + out.path("findingCacheId").asText()
                + "\",\"mode\":\"group_metric\",\"groupBy\":[\"bucketLabel\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}");
        assertEquals("success", grouped.path("status").asText(), grouped.toString());
        int total = 0;
        int nullKeyRows = 0;
        for (JsonNode r : grouped.path("rows")) {
            total += r.path("n").asInt();
            if (r.path("bucketLabel").isNull() || r.path("bucketLabel").isMissingNode()) {
                nullKeyRows += r.path("n").asInt();
            }
        }
        assertEquals(4, total, grouped.toString());
        assertEquals(2, nullKeyRows, grouped.toString());

        JsonNode none = run(InvokeServiceExecutor.storeInfotableInConversationCache(textEvents("x", "y")), "start",
                BERLIN_DAY);
        assertEquals(CalendarBucketCacheRunner.OUTCOME_NO_ASSIGNED_ROW, none.path("reason").asText());
        assertEquals("INSUFFICIENT_EVIDENCE", none.path("analysisEnvelope").path("status").asText());
        assertTrue(none.path("mayPublish").asBoolean(), "the rows are still carried");
    }

    /**
     * A non-finite number is no usable time: a cast would turn NaN into 0 and invent 1970-01-01. Such a row is
     * kept unassigned, in both granularities; a genuine epoch 0 is a valid time.
     */
    @Test
    void nonFiniteNumericTimes_areUnassigned_neverTurnedInto1970() throws Exception {
        for (String granularity : new String[] {"day", "hour"}) {
            String args = ",\"timeZone\":\"UTC\",\"calendarBucket\":\"" + granularity + "\"";
            double valid = Instant.parse("2026-03-02T08:00:00Z").toEpochMilli();
            JsonNode mixed = run(InvokeServiceExecutor.storeInfotableInConversationCache(numericTimes(
                    Double.NaN, valid, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0d)), "start", args);
            assertEquals("OK", mixed.path("status").asText(), mixed.toString());
            JsonNode m = mixed.path("analysisEnvelope").path("metrics");
            assertEquals("2", m.path("assignedRows").asText(), granularity);
            assertEquals("3", m.path("unassignedRows").asText(), granularity);
            InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(mixed.path("findingCacheId").asText());
            assertEquals(5, t.getRowCount());
            assertNull(valueOf(t.getRow(0), "bucketLabel"), "NaN carries no bucket");
            assertNull(valueOf(t.getRow(2), "bucketStart"));
            assertNull(valueOf(t.getRow(3), "bucketStart"));
            assertTrue(t.getRow(1).getStringValue("bucketLabel").startsWith("2026-03-02"));
            assertTrue(t.getRow(4).getStringValue("bucketLabel").startsWith("1970-01-01"), "a real epoch 0");
            assertTrue(Double.isNaN((Double) t.getRow(0).getValue("start")), "the source cell is kept as it was");
            assertTrue(containsText(mixed.path("warnings"), "3 row(s) have no usable time"));

            JsonNode allInvalid = run(InvokeServiceExecutor.storeInfotableInConversationCache(numericTimes(
                    Double.NaN, Double.POSITIVE_INFINITY)), "start", args);
            assertEquals(CalendarBucketCacheRunner.OUTCOME_NO_ASSIGNED_ROW, allInvalid.path("reason").asText());
            assertEquals("INSUFFICIENT_EVIDENCE", allInvalid.path("analysisEnvelope").path("status").asText());
            assertEquals("0", allInvalid.path("analysisEnvelope").path("metrics").path("assignedRows").asText());
        }
    }

    /** A zone id that only names a non-zero offset is refused like any fixed offset, and nothing is published. */
    @Test
    void offsetNamedZoneAliases_areRefused_whileUtcAndPlacesAreAccepted() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-03-02T08:00:00Z", null)));
        cache.onNextCreates(() -> { });
        for (String zone : new String[] {"Etc/GMT-2", "Etc/GMT+5", "Etc/GMT-14", "SystemV/EST5", "+02:00",
                "GMT+02:00"}) {
            assertError(CalendarBucketLabeler.TIME_ZONE_INVALID,
                    run(cid, "start", ",\"timeZone\":\"" + zone + "\",\"calendarBucket\":\"day\""));
        }
        assertEquals(0, cache.publicationsSinceArmed(), "a refused zone publishes nothing");
        for (String zone : new String[] {"UTC", "Etc/UTC", "Asia/Kolkata", "Asia/Tokyo", "America/Phoenix"}) {
            JsonNode out = run(cid, "start", ",\"timeZone\":\"" + zone + "\",\"calendarBucket\":\"day\"");
            assertEquals("OK", out.path("status").asText(), zone + ": " + out);
        }
    }

    @Test
    void argumentAndShapeErrors_failFast_andPublishNothing() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-03-02T08:00:00Z", "2026-03-02T08:20:00Z")));
        assertError(U4SeriesToolArgs.ARGUMENT_MISSING, run(cid, "start", ",\"calendarBucket\":\"day\""));
        assertError(U4SeriesToolArgs.ARGUMENT_MISSING, run(cid, "start", ",\"timeZone\":\"UTC\""));
        assertError(U4SeriesToolArgs.TIME_AXIS_MISSING, tabulate("{\"cacheId\":\"" + cid
                + "\",\"mode\":\"calendar_bucket\"" + BERLIN_DAY + "}"));
        for (String zone : new String[] {"+02:00", "GMT+2", "Europe/Nowhere", "CEST"}) {
            assertError(CalendarBucketLabeler.TIME_ZONE_INVALID,
                    run(cid, "start", ",\"timeZone\":\"" + zone + "\",\"calendarBucket\":\"day\""));
        }
        assertError(CalendarBucketLabeler.CALENDAR_BUCKET_INVALID,
                run(cid, "start", ",\"timeZone\":\"UTC\",\"calendarBucket\":\"week\""));
        assertError(CalendarBucketCacheRunner.COLUMN_NOT_FOUND, run(cid, "nope", BERLIN_DAY));
        for (String extra : new String[] {"\"valueColumn\":\"v\"", "\"entityColumn\":\"e\"",
                "\"windowStart\":\"2026-03-02T00:00:00Z\"", "\"windowEnd\":\"2026-03-03T00:00:00Z\""}) {
            assertError(CalendarBucketCachedResultExecutor.ARGUMENT_UNSUPPORTED,
                    run(cid, "start", BERLIN_DAY + "," + extra));
        }
        // A second labelling would collide with the bucket columns of the first.
        JsonNode once = run(cid, "start", BERLIN_DAY);
        assertError(CalendarBucketCacheRunner.COLUMN_NAME_CONFLICT,
                run(once.path("findingCacheId").asText(), "end", BERLIN_DAY));
        // A bucket edge outside years 0001-9999 cannot be published as a DATETIME.
        assertError(CalendarBucketCacheRunner.TIMESTAMP_UNSUPPORTED, run(
                InvokeServiceExecutor.storeInfotableInConversationCache(textEvents("9999-12-31T23:30:00Z")),
                "start", ",\"timeZone\":\"UTC\",\"calendarBucket\":\"day\""));
    }

    @Test
    void calendarRangeFailures_areTypedWithoutPublishing_andFractionalSourceTimesRemainValid() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        for (String timestamp : new String[] {"+1000000000-01-01T00:00:00Z",
                "-1000000000-12-31T00:00:00Z", "+999999999-12-31T23:59:59Z",
                "+10000-01-01T00:00:00Z"}) {
            for (String kind : new String[] {"day", "hour"}) {
                String cid = InvokeServiceExecutor.storeInfotableInConversationCache(textEvents(timestamp));
                cache.onNextCreates(() -> {});
                assertError(CalendarBucketCacheRunner.TIMESTAMP_UNSUPPORTED,
                        run(cid, "start", ",\"timeZone\":\"UTC\",\"calendarBucket\":\"" + kind + "\""));
                assertEquals(0, cache.publicationsSinceArmed(), timestamp + " " + kind);
            }
        }
        String timestamp = "2026-03-02T08:00:00.123456789Z";
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(textEvents(timestamp));
        for (String kind : new String[] {"day", "hour"}) {
            JsonNode result = run(cid, "start", ",\"timeZone\":\"UTC\",\"calendarBucket\":\"" + kind + "\"");
            assertEquals("OK", result.path("status").asText(), result.toString());
            InfoTable labelled = InvokeServiceExecutor.lookupCachedInfotable(result.path("findingCacheId").asText());
            assertEquals(timestamp, labelled.getRow(0).getStringValue("start"));
        }
    }

    /** The source's completeness never changes a label; it changes the scope text and is never upgraded. */
    @Test
    void completeness_isCopied_neverUpgraded_andTheOutputIsNoTimeWeightedInput() throws Exception {
        InfoTable limited = events(event("2026-03-02T08:00:00Z", null), event("2026-03-03T09:00:00Z", null));
        String withReason = InvokeServiceExecutor.storePrimaryWithReadLimit(limited, ReadLimitFact.observe(limited, 2));
        String partial = TabularArtifactHub.store(
                events(event("2026-03-02T08:00:00Z", null), event("2026-03-03T09:00:00Z", null)),
                SourceDescriptor.builder().completenessStatus(CompletenessStatus.PARTIAL).build());
        String unknown = InvokeServiceExecutor.storeInfotableInConversationCache(
                events(event("2026-03-02T08:00:00Z", null), event("2026-03-03T09:00:00Z", null)));
        JsonNode a = run(withReason, "start", BERLIN_DAY);
        JsonNode b = run(partial, "start", BERLIN_DAY);
        JsonNode c = run(unknown, "start", BERLIN_DAY);
        assertEquals(labelsOf(a), labelsOf(b));
        assertEquals(labelsOf(a), labelsOf(c));
        for (JsonNode out : List.of(a, b, c)) {
            assertEquals("SUCCESS", out.path("analysisEnvelope").path("status").asText(), out.toString());
            assertTrue(out.path("warnings").get(0).asText().startsWith(AnalysisEnvelopeValidator.SCOPE_OBSERVED_SPAN));
        }
        assertTrue(a.path("warnings").get(0).asText().contains("not that day's total"));
        assertTrue(b.path("warnings").get(0).asText().contains("not that day's total"));
        assertFalse(c.path("warnings").get(0).asText().contains("not that day's total"));
        assertEquals(List.of(ReadLimitFact.REASON), strings(a.path("completeness").path("reasons")));
        assertEquals("PARTIAL", b.path("completeness").path("status").asText());
        assertEquals("UNKNOWN", c.path("completeness").path("status").asText());

        SourceDescriptor d = InvokeServiceExecutor.lookupSourceDescriptor(a.path("findingCacheId").asText());
        assertEquals(CalendarBucketCacheRunner.ROUTE_ID, d.sourceRouteId());
        assertEquals(List.of(withReason), d.parentSourceCacheIds());
        assertEquals(List.of(ReadLimitFact.REASON), d.completenessReasons());
        assertNull(d.subjectThingName());

        // A labelled history series is a derived table: time_weighted refuses it.
        InfoTable history = NumericHistoryCacheWriter.newTable();
        NumericHistoryCacheWriter.addRow(history, "2026-03-02T08:00:00Z", 1d);
        NumericHistoryCacheWriter.addRow(history, "2026-03-02T08:00:10Z", 2d);
        String series = NumericHistoryCacheWriter.store(history, "query_numeric_property_history", "Pump1", "power",
                null).cacheId();
        JsonNode labelledSeries = run(series, "timestamp", BERLIN_DAY);
        JsonNode refused = tabulate("{\"cacheId\":\"" + labelledSeries.path("findingCacheId").asText()
                + "\",\"mode\":\"time_weighted\",\"timeColumn\":\"timestamp\",\"valueColumn\":\"value\","
                + "\"windowStart\":\"2026-03-02T08:00:00Z\",\"windowEnd\":\"2026-03-02T08:00:10Z\","
                + "\"integrationMethod\":\"trapezoid\",\"maxGapSeconds\":10,\"timeUnit\":\"seconds\"}");
        assertEquals(TimeWeightedCacheRunner.SOURCE_CONTINUITY_UNKNOWN, refused.path("reason").asText());
    }

    /** Composition: counter increments per local day, by labelling the segment table and grouping it. */
    @Test
    void counterDeltaSegments_areSummedPerLocalDay_withGroupMetric() throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("ts", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("v", BaseTypes.NUMBER));
        InfoTable counter = new InfoTable(shape);
        String[] times = {"2026-03-02T10:00:00Z", "2026-03-02T20:00:00Z", "2026-03-03T02:00:00Z",
                "2026-03-03T12:00:00Z"};
        double[] values = {100, 130, 170, 180};
        for (int i = 0; i < times.length; i++) {
            ValueCollection vc = new ValueCollection();
            vc.put("ts", new DatetimePrimitive(new DateTime(Instant.parse(times[i]).toEpochMilli())));
            vc.put("v", new NumberPrimitive(values[i]));
            counter.addRow(vc);
        }
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(counter);
        JsonNode delta = tabulate("{\"cacheId\":\"" + cid + "\",\"mode\":\"counter_delta\",\"timeColumn\":\"ts\","
                + "\"valueColumn\":\"v\",\"windowStart\":\"2026-03-02T00:00:00Z\","
                + "\"windowEnd\":\"2026-03-04T00:00:00Z\"}");
        assertEquals("OK", delta.path("status").asText(), delta.toString());
        JsonNode labelled = run(delta.path("findingCacheId").asText(), "segmentEnd", BERLIN_DAY);
        JsonNode grouped = tabulate("{\"cacheId\":\"" + labelled.path("findingCacheId").asText()
                + "\",\"mode\":\"group_metric\",\"groupBy\":[\"bucketLabel\"],"
                + "\"measures\":[{\"name\":\"delta\",\"op\":\"sum\",\"column\":\"delta\"}]}");
        assertEquals("success", grouped.path("status").asText(), grouped.toString());
        Map<String, Double> sums = new LinkedHashMap<>();
        grouped.path("rows").forEach(r -> sums.put(r.path("bucketLabel").asText(), r.path("delta").asDouble()));
        assertEquals(Map.of("2026-03-02", 30d, "2026-03-03", 50d), sums);
        assertTrue(labelled.path("warnings").get(0).asText().contains("not thereby fully observed"));
    }

    @Test
    void interruptDuringOutputCreation_returnsCancelled_andNothingBecomesVisible() throws Exception {
        ArtifactCacheTestFixtures.HookedCache cache = ArtifactCacheTestFixtures.installFreshHookedInMemoryCache();
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-03-02T08:00:00Z", null)));
        cache.onNextCreates(() -> Thread.currentThread().interrupt());
        JsonNode out;
        try {
            out = run(cid, "start", BERLIN_DAY);
        } finally {
            Thread.interrupted();
        }
        assertError(CalendarBucketCacheRunner.CANCELLED, out);
        assertEquals(0, cache.publicationsSinceArmed(), "no derived artifact became visible");
    }

    @Test
    void admissionOff_withdrawsTheModeAndItsProperties_andRejectsDirectCalls() throws Exception {
        String cid = InvokeServiceExecutor.storeInfotableInConversationCache(events(
                event("2026-03-02T08:00:00Z", null)));
        String on = advertisedSurface();
        assertTrue(on.contains("calendar_bucket") && on.contains("\"timeZone\"") && on.contains("\"calendarBucket\""));
        assertTrue(on.contains("no default"), "the zone rule is visible before the first call");
        assertTrue(on.contains("by each row's own time"));

        ComputingOperationAdmission.setCalendarBucketEnabled(false);
        String off = advertisedSurface();
        assertFalse(Pattern.compile("\\bcalendar_bucket\\b").matcher(off).find());
        assertFalse(off.contains("\"timeZone\"") || off.contains("\"calendarBucket\""));
        assertTrue(off.contains("\"timeColumn\""), "still advertised for the series modes");
        assertError(ComputingOperationAdmission.CALENDAR_BUCKET_UNAVAILABLE, run(cid, "start", BERLIN_DAY));
    }

    /**
     * Description budget after the scoped CC-1.4 revision: all computing modes together stay within 2,500
     * editable characters of this tool, this slice within 300, and the aggregate schema grows by at most 600.
     */
    @Test
    void descriptionAndSchemaBudgets_hold() throws Exception {
        int allOn = editableChars();
        int allOff = ComputingOperationAdmission.callWithAllDisabled(TabulateCalendarBucketModeTest::editableChars);
        ComputingOperationAdmission.setCalendarBucketEnabled(false);
        int withoutThisMode = editableChars();
        ComputingOperationAdmission.resetForTests();
        System.out.println("Computing editable description chars: all=" + (allOn - allOff) + " calendar_bucket="
                + (allOn - withoutThisMode));
        assertTrue(allOn - allOff <= 2_500, "computing total=" + (allOn - allOff));
        assertTrue(allOn - withoutThisMode > 0 && allOn - withoutThisMode <= 300,
                "calendar_bucket=" + (allOn - withoutThisMode));
        for (String shape : new String[] {"openai-chat-completions-v1", "anthropic-messages-v1"}) {
            ComputingOperationAdmission.setCalendarBucketEnabled(true);
            int on = schemaChars(shape);
            ComputingOperationAdmission.setCalendarBucketEnabled(false);
            int off = schemaChars(shape);
            System.out.println("CalendarBucket schema growth " + shape + ": " + (on - off) + " (total " + on + ")");
            assertTrue(on > off && on - off <= 600, shape + " growth=" + (on - off));
        }
    }

    @Test
    void warningsAndCompleteness_surviveLastResortCompaction() throws Exception {
        InfoTable limited = textEvents("2026-03-29T08:00:00Z", "bad", "2026-03-30T08:00:00Z");
        String cid = InvokeServiceExecutor.storePrimaryWithReadLimit(limited, ReadLimitFact.observe(limited, 3));
        JsonNode out = run(cid, "start", BERLIN_DAY);
        assertEquals(3, out.path("warnings").size(), out.path("warnings").toString());
        assertTrue(containsText(out.path("warnings"), "1 day bucket(s) are not 86400 s long"));
        ObjectNode padded = ((ObjectNode) out).deepCopy();
        ObjectNode filler = padded.putObject("filler");
        for (int i = 0; i < 120; i++) {
            filler.put("field_" + i, "x".repeat(110));
        }
        JsonNode seen = MAPPER.readTree(ToolResultEgressGateway.compactForLlmAppend("tabulate_cached_result", "call-1",
                MAPPER.writeValueAsString(padded), LoggerFactory.getLogger(TabulateCalendarBucketModeTest.class))
                .getLlmContent());
        boolean omitted = false;
        for (JsonNode marker : seen.path("_egress").path("reducedFields")) {
            omitted |= marker.path("omitted").asBoolean();
        }
        assertTrue(omitted, "only last-resort compaction omits whole fields: " + seen.path("_egress"));
        assertEquals(strings(out.path("warnings")), strings(seen.path("warnings")));
        assertEquals(out.path("completeness"), seen.path("completeness"));
    }

    private static void assertError(String reason, JsonNode out) {
        assertEquals("ERROR", out.path("status").asText(), out.toString());
        assertEquals(reason, out.path("reason").asText(), out.toString());
        assertFalse(out.has("findingCacheId"));
        assertFalse(out.path("mayPublish").asBoolean(true));
    }

    private static JsonNode run(String cacheId, String timeColumn, String extraArgs) throws Exception {
        return tabulate("{\"cacheId\":\"" + cacheId + "\",\"mode\":\"calendar_bucket\",\"timeColumn\":\""
                + timeColumn + "\"" + extraArgs + "}");
    }

    private static JsonNode tabulate(String args) throws Exception {
        return MAPPER.readTree(CachedTabularToolsExecutor.executeTabulateCachedResult(
                new ToolCall("1", "tabulate_cached_result", args)));
    }

    /** Through the real, advertised group_metric: rows per bucketLabel. */
    private static Map<String, Integer> countsByLabel(JsonNode labelled) throws Exception {
        JsonNode grouped = tabulate("{\"cacheId\":\"" + labelled.path("findingCacheId").asText()
                + "\",\"mode\":\"group_metric\",\"groupBy\":[\"bucketLabel\"],"
                + "\"measures\":[{\"name\":\"n\",\"op\":\"count\"}]}");
        assertEquals("success", grouped.path("status").asText(), grouped.toString());
        Map<String, Integer> out = new LinkedHashMap<>();
        grouped.path("rows").forEach(r -> out.put(r.path("bucketLabel").asText(), r.path("n").asInt()));
        return out;
    }

    private static List<String> labelsOf(JsonNode result) throws Exception {
        InfoTable t = InvokeServiceExecutor.lookupCachedInfotable(result.path("findingCacheId").asText());
        assertNotNull(t, result.toString());
        List<String> out = new ArrayList<>();
        for (int i = 0; i < t.getRowCount(); i++) {
            out.add(t.getRow(i).getStringValue("bucketLabel") + "|" + t.getRow(i).getValue("bucketStart"));
        }
        return out;
    }

    private static Object valueOf(ValueCollection row, String column) {
        return row.getPrimitive(column) == null ? null : row.getPrimitive(column).getValue();
    }

    private static String[] event(String start, String end) {
        return new String[] {start, end};
    }

    /** An event table with DATETIME start and end columns; a null end stays empty. */
    private static InfoTable events(String[]... events) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("start", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("end", BaseTypes.DATETIME));
        shape.addFieldDefinition(field("name", BaseTypes.STRING));
        InfoTable table = new InfoTable(shape);
        int i = 0;
        for (String[] e : events) {
            ValueCollection vc = new ValueCollection();
            vc.put("start", new DatetimePrimitive(new DateTime(Instant.parse(e[0]).toEpochMilli())));
            if (e[1] != null) {
                vc.put("end", new DatetimePrimitive(new DateTime(Instant.parse(e[1]).toEpochMilli())));
            }
            vc.put("name", new StringPrimitive("event-" + i++));
            table.addRow(vc);
        }
        return table;
    }

    /** A NUMBER time column of epoch milliseconds, the only way to carry a non-finite time. */
    private static InfoTable numericTimes(double... starts) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("start", BaseTypes.NUMBER));
        InfoTable table = new InfoTable(shape);
        for (double start : starts) {
            ValueCollection vc = new ValueCollection();
            vc.put("start", new NumberPrimitive(start));
            table.addRow(vc);
        }
        return table;
    }

    /** A STRING time column, the only way to carry an unparseable or missing time. */
    private static InfoTable textEvents(String... starts) throws Exception {
        DataShapeDefinition shape = new DataShapeDefinition();
        shape.addFieldDefinition(field("start", BaseTypes.STRING));
        shape.addFieldDefinition(field("name", BaseTypes.STRING));
        InfoTable table = new InfoTable(shape);
        int i = 0;
        for (String start : starts) {
            ValueCollection vc = new ValueCollection();
            if (start != null) {
                vc.put("start", new StringPrimitive(start));
            }
            vc.put("name", new StringPrimitive("event-" + i++));
            table.addRow(vc);
        }
        return table;
    }

    private static FieldDefinition field(String name, BaseTypes type) {
        FieldDefinition f = new FieldDefinition();
        f.setName(name);
        f.setBaseType(type);
        return f;
    }

    private static boolean containsText(JsonNode array, String text) {
        for (JsonNode n : array) {
            if (n.asText().contains(text)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static ToolDefinition tabulateDefinition() throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return reg.getAllDefinitions().stream()
                .filter(d -> "tabulate_cached_result".equals(d.getName())).findFirst().orElseThrow();
    }

    private static int editableChars() throws Exception {
        return M1aEditableDescriptionMetrics.toolSchemaEditableChars(tabulateDefinition());
    }

    private static int schemaChars(String apiShape) throws Exception {
        ToolRegistry reg = new ToolRegistry();
        BuiltInTools.registerAll(reg, false);
        return ToolSchemaSizer.totalSchemaChars(apiShape, new ArrayList<>(reg.getAllDefinitions()));
    }

    private static String advertisedSurface() throws Exception {
        ToolDefinition tabulate = tabulateDefinition();
        return tabulate.getDescription() + "\n" + MAPPER.writeValueAsString(tabulate.getParametersSchema());
    }
}
