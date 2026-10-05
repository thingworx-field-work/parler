package com.thingworx.things.agent.llm.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.ObjectNode;

class AgentLlmCallStreamReaderTest {

    @AfterEach
    void cleanup() {
        AgentLlmCallStreamReader.clearTestHooks();
    }

    @Test
    void read_eventIdConflict_marksCallConflicted() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent first = event("same-id", "call-1", started, "{\"schemaVersion\":1,"
                + "\"identity\":{\"logicalCallId\":\"logical-1\"},\"usage\":{\"normalized\":"
                + "{\"inputTokensTotal\":100}}}");
        LlmCallEvent second = event("same-id", "call-1", started, "{\"schemaVersion\":1,"
                + "\"identity\":{\"logicalCallId\":\"logical-1\"},\"usage\":{\"normalized\":"
                + "{\"inputTokensTotal\":999}}}");
        AgentLlmCallStreamReader.setTestEvents(List.of(first, second));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome = AgentLlmCallStreamReader.read(query, started.plusSeconds(3600));
        assertEquals(1, outcome.getRecordConflictCount());
        assertEquals(1, outcome.getEvents().size());
        assertTrue(outcome.getConflictedCallIds().contains("call-1"));
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                outcome,
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}"));
        assertTrue(report.withArray("calls").get(0).path("conflict").asBoolean());
    }

    @Test
    void read_collectorDiagnostics_notFilteredByBusinessFilters() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent call = LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                .eventId("call-event")
                .callId("call-1")
                .logicalCallId("logical-1")
                .conversationId("other-conv")
                .agentThing("OtherAgent")
                .callStartedAt(started)
                .occurredAt(started)
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-1\"}}")
                .build();
        LlmCallEvent gap = LlmCallEvent.builder(LlmCallEventType.COLLECTOR_GAP)
                .eventId("gap-event")
                .occurredAt(started.plusSeconds(1))
                .callStartedAt(started.plusSeconds(1))
                .eventJson("{\"schemaVersion\":1,\"kind\":\"gap\",\"failedEvents\":2}")
                .build();
        AgentLlmCallStreamReader.setTestEvents(List.of(call, gap));
        LlmUsageReportQuery query = new LlmUsageReportQuery(
                started.minusSeconds(1), started.plusSeconds(60), "filtered-conv", "FilteredAgent", null);
        AgentLlmCallStreamReader.ReadOutcome outcome = AgentLlmCallStreamReader.read(query, started.plusSeconds(3600));
        ObjectNode report = LlmUsageReportReducer.reduce(
                query,
                outcome,
                started.plusSeconds(3600),
                new LlmUsageCostCalculator("{\"version\":\"unpriced-v1\",\"prices\":[]}"));
        assertEquals(0, report.path("callCount").asInt());
        assertEquals(1, report.withArray("collectorDiagnostics").size());
    }

    @Test
    void read_openClosedEndpoint_includesBoundaryStartedAt() throws Exception {
        Instant boundary = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent open = event("open", "call-open", boundary,
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-open\"}}");
        LlmCallEvent closed = LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                .eventId("closed")
                .callId("call-closed")
                .logicalCallId("logical-closed")
                .callStartedAt(boundary.plusSeconds(60))
                .occurredAt(boundary.plusSeconds(60))
                .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-closed\"}}")
                .build();
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, end) -> {
            long startMs = start != null ? start.getMillis() : Long.MIN_VALUE;
            long endMs = end != null ? end.getMillis() : Long.MAX_VALUE;
            java.util.ArrayList<LlmCallEvent> rows = new java.util.ArrayList<>();
            for (LlmCallEvent candidate : List.of(open, closed)) {
                long startedMs = candidate.getCallStartedAt().toEpochMilli();
                if (startedMs >= startMs && startedMs < endMs) {
                    rows.add(candidate);
                }
            }
            return rows;
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(boundary, boundary.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome = AgentLlmCallStreamReader.read(query, boundary.plusSeconds(120));
        assertEquals(1, outcome.getEvents().stream()
                .filter(e -> e.getEventType() != LlmCallEventType.COLLECTOR_GAP
                        && e.getEventType() != LlmCallEventType.COLLECTOR_STARTED)
                .count());
        assertEquals("call-open", outcome.getEvents().get(0).getCallId());
    }

    @Test
    void read_epochZeroOpenEndpoint_includesStartedAtZero() throws Exception {
        Instant epoch = Instant.ofEpochMilli(0);
        LlmCallEvent atZero = event("zero", "call-zero", epoch,
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-zero\"}}");
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, end) -> {
            long startMs = start != null ? start.getMillis() : Long.MIN_VALUE;
            long endMs = end != null ? end.getMillis() : Long.MAX_VALUE;
            if (atZero.getCallStartedAt().toEpochMilli() >= startMs
                    && atZero.getCallStartedAt().toEpochMilli() < endMs) {
                return List.of(atZero);
            }
            return List.of();
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(epoch, epoch.plusMillis(10), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome =
                AgentLlmCallStreamReader.read(query, epoch.plusSeconds(60));
        assertEquals(1, outcome.getEvents().size());
        assertEquals("call-zero", outcome.getEvents().get(0).getCallId());
    }

    @Test
    void read_platformRowsHookAtProbeLimit_subdividesSlice() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        Instant end = started.plusSeconds(10);
        java.util.ArrayList<LlmCallEvent> catalog = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            catalog.add(LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                    .eventId("subdivide-" + i)
                    .callId("call-" + i)
                    .logicalCallId("logical-" + i)
                    .callStartedAt(started.plusMillis(i % 10))
                    .occurredAt(started.plusMillis(i % 10))
                    .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-" + i + "\"}}")
                    .build());
        }
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, endExclusive) -> {
            long queryStartMs = start != null ? start.getMillis() : Long.MIN_VALUE;
            long queryEndMs = endExclusive != null ? endExclusive.getMillis() : Long.MAX_VALUE;
            java.util.ArrayList<LlmCallEvent> rows = new java.util.ArrayList<>();
            for (LlmCallEvent candidate : catalog) {
                long startedMs = candidate.getCallStartedAt().toEpochMilli();
                if (startedMs >= queryStartMs && startedMs < queryEndMs) {
                    rows.add(candidate);
                }
                if (rows.size() >= AgentLlmCallStreamReader.SLICE_PROBE_MAX_ITEMS) {
                    break;
                }
            }
            return rows;
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(started, end, null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome =
                AgentLlmCallStreamReader.read(query, end.plusSeconds(60));
        assertTrue(outcome.isReadComplete());
        assertEquals(1001, outcome.getEvents().size());
    }

    @Test
    void read_oneMillisecondSliceSaturation_throwsReportLimitExceeded() {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        Instant end = started.plusMillis(1);
        java.util.ArrayList<LlmCallEvent> sameMs = new java.util.ArrayList<>();
        for (int i = 0; i < AgentLlmCallStreamReader.SLICE_PROBE_MAX_ITEMS; i++) {
            sameMs.add(LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                    .eventId("same-ms-" + i)
                    .callId("call-" + i)
                    .logicalCallId("logical-" + i)
                    .callStartedAt(started)
                    .occurredAt(started)
                    .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-" + i + "\"}}")
                    .build());
        }
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, endExclusive) -> {
            long queryStartMs = start != null ? start.getMillis() : Long.MIN_VALUE;
            long queryEndMs = endExclusive != null ? endExclusive.getMillis() : Long.MAX_VALUE;
            java.util.ArrayList<LlmCallEvent> rows = new java.util.ArrayList<>();
            for (LlmCallEvent candidate : sameMs) {
                long startedMs = candidate.getCallStartedAt().toEpochMilli();
                if (startedMs >= queryStartMs && startedMs < queryEndMs) {
                    rows.add(candidate);
                }
            }
            return rows;
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(started, end, null, null, null);
        LlmUsageReportException ex = assertThrows(LlmUsageReportException.class,
                () -> AgentLlmCallStreamReader.read(query, end.plusSeconds(60)));
        assertEquals("REPORT_LIMIT_EXCEEDED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("one millisecond slice"));
    }

    @Test
    void read_totalEventLimitThroughSlicePath_throwsReportLimitExceeded() {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        Instant end = started.plusMillis(AgentLlmCallStreamReader.TOTAL_EVENT_LIMIT + 1);
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, endExclusive) -> {
            long startMs = start != null ? start.getMillis() : Long.MIN_VALUE;
            long endMs = endExclusive != null ? endExclusive.getMillis() : Long.MAX_VALUE;
            java.util.ArrayList<LlmCallEvent> rows = new java.util.ArrayList<>();
            for (long ms = startMs; ms < endMs; ms++) {
                rows.add(LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                        .eventId("limit-" + ms)
                        .callId("call-" + ms)
                        .logicalCallId("logical-" + ms)
                        .callStartedAt(Instant.ofEpochMilli(ms))
                        .occurredAt(Instant.ofEpochMilli(ms))
                        .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-" + ms + "\"}}")
                        .build());
            }
            return rows;
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(started, end, null, null, null);
        LlmUsageReportException ex = assertThrows(LlmUsageReportException.class,
                () -> AgentLlmCallStreamReader.read(query, end.plusSeconds(60)));
        assertEquals("REPORT_LIMIT_EXCEEDED", ex.getErrorCode());
    }

    @Test
    void read_openStartPlatformRowsHook_includesEventsBeforeExplicitStart() throws Exception {
        Instant boundary = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent before = event("before", "call-before", boundary.minusSeconds(5),
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-before\"}}");
        LlmCallEvent atBoundary = event("at", "call-at", boundary,
                "{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-at\"}}");
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, end) -> {
            long endMs = end != null ? end.getMillis() : Long.MAX_VALUE;
            java.util.ArrayList<LlmCallEvent> rows = new java.util.ArrayList<>();
            for (LlmCallEvent candidate : List.of(before, atBoundary)) {
                long startedMs = candidate.getCallStartedAt().toEpochMilli();
                if (start == null || startedMs > start.getMillis()) {
                    if (startedMs < endMs) {
                        rows.add(candidate);
                    }
                }
            }
            return rows;
        });
        LlmUsageReportQuery query = new LlmUsageReportQuery(boundary, boundary.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome =
                AgentLlmCallStreamReader.read(query, boundary.plusSeconds(120));
        assertEquals(1, outcome.getEvents().size());
        assertEquals("call-at", outcome.getEvents().get(0).getCallId());
    }

    @Test
    void read_slicePathConflict_marksCallConflicted() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        LlmCallEvent first = event("same-id", "call-slice", started, "{\"schemaVersion\":1,"
                + "\"identity\":{\"logicalCallId\":\"logical-slice\"},\"usage\":{\"normalized\":"
                + "{\"inputTokensTotal\":100}}}");
        LlmCallEvent second = event("same-id", "call-slice", started, "{\"schemaVersion\":1,"
                + "\"identity\":{\"logicalCallId\":\"logical-slice\"},\"usage\":{\"normalized\":"
                + "{\"inputTokensTotal\":999}}}");
        AgentLlmCallStreamReader.setTestPlatformRowsHook((start, end) -> List.of(first, second));
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(60), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome = AgentLlmCallStreamReader.read(query, started.plusSeconds(3600));
        assertEquals(1, outcome.getRecordConflictCount());
        assertTrue(outcome.getConflictedCallIds().contains("call-slice"));
    }

    @Test
    void read_exactEventLimit_isReadComplete() throws Exception {
        Instant started = Instant.parse("2026-09-12T10:00:00Z");
        java.util.ArrayList<LlmCallEvent> events = new java.util.ArrayList<>();
        for (int i = 0; i < AgentLlmCallStreamReader.TOTAL_EVENT_LIMIT; i++) {
            events.add(LlmCallEvent.builder(LlmCallEventType.CALL_STARTED)
                    .eventId("event-" + i)
                    .callId("call-" + i)
                    .logicalCallId("logical-" + i)
                    .callStartedAt(started.plusMillis(i))
                    .occurredAt(started.plusMillis(i))
                    .eventJson("{\"schemaVersion\":1,\"identity\":{\"logicalCallId\":\"logical-" + i + "\"}}")
                    .build());
        }
        AgentLlmCallStreamReader.setTestEvents(events);
        LlmUsageReportQuery query = new LlmUsageReportQuery(started.minusSeconds(1), started.plusSeconds(3600), null, null, null);
        AgentLlmCallStreamReader.ReadOutcome outcome =
                AgentLlmCallStreamReader.read(query, started.plusSeconds(7200));
        assertTrue(outcome.isReadComplete());
        assertEquals(AgentLlmCallStreamReader.TOTAL_EVENT_LIMIT, outcome.getEvents().size());
    }

    @Test
    void read_missingStream_throwsReportReadFailed() {
        AgentLlmCallStreamReader.setTestStreamMissing(true);
        LlmUsageReportQuery query = new LlmUsageReportQuery(
                Instant.parse("2026-09-12T10:00:00Z"),
                Instant.parse("2026-09-12T11:00:00Z"),
                null,
                null,
                null);
        LlmUsageReportException ex = assertThrows(LlmUsageReportException.class,
                () -> AgentLlmCallStreamReader.read(query, Instant.now()));
        assertEquals("REPORT_READ_FAILED", ex.getErrorCode());
    }

    private static LlmCallEvent event(String eventId, String callId, Instant started, String eventJson) {
        return LlmCallEvent.builder(LlmCallEventType.USAGE_OBSERVED)
                .eventId(eventId)
                .callId(callId)
                .logicalCallId("logical-1")
                .callStartedAt(started)
                .occurredAt(started)
                .eventJson(eventJson)
                .build();
    }
}
