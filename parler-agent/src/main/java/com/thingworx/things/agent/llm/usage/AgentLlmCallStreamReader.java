package com.thingworx.things.agent.llm.usage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;

import com.thingworx.entities.RootEntity;
import com.thingworx.logging.LogUtilities;
import com.thingworx.relationships.RelationshipTypes;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.PlatformAccess;
import com.thingworx.types.InfoTable;
import com.thingworx.types.collections.ValueCollection;
import com.thingworx.types.primitives.BooleanPrimitive;
import com.thingworx.types.primitives.DatetimePrimitive;
import com.thingworx.types.primitives.NumberPrimitive;
import com.thingworx.types.primitives.StringPrimitive;

/**
 * Reads {@link AgentLlmCallStreamWriter#STREAM_THING_NAME} rows for helper reports (CC-7.6).
 */
public final class AgentLlmCallStreamReader {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(AgentLlmCallStreamReader.class);

    public static final int SLICE_PROBE_MAX_ITEMS = 1001;
    public static final int SLICE_MAX_ROWS = 1000;
    public static final int TOTAL_EVENT_LIMIT = 50_000;

    private static volatile List<LlmCallEvent> testEventsOverride;
    private static volatile Boolean testStreamMissing;
    private static volatile PlatformRowsHook testPlatformRowsHook;

    private AgentLlmCallStreamReader() {}

    public static void setTestEvents(List<LlmCallEvent> events) {
        testEventsOverride = events != null ? List.copyOf(events) : null;
    }

    public static void setTestStreamMissing(boolean missing) {
        testStreamMissing = missing;
    }

    public static void setTestPlatformRowsHook(PlatformRowsHook hook) {
        testPlatformRowsHook = hook;
    }

    public static void clearTestHooks() {
        testEventsOverride = null;
        testStreamMissing = null;
        testPlatformRowsHook = null;
    }

    public static ReadOutcome read(LlmUsageReportQuery query, Instant reportAsOf) throws Exception {
        if (query == null) {
            throw new IllegalArgumentException("query is required");
        }
        Instant asOf = reportAsOf != null ? reportAsOf : Instant.now();
        Set<String> conflictedCallIds = new LinkedHashSet<>();
        int recordConflictCount = 0;
        SliceReadState sliceReadState = new SliceReadState();
        List<LlmCallEvent> source = testEventsOverride != null
                ? testEventsOverride
                : readFromStream(query.getRangeStart(), query.getRangeEnd(), sliceReadState);
        recordConflictCount += sliceReadState.recordConflictCount;
        conflictedCallIds.addAll(sliceReadState.conflictedCallIds);
        Map<String, LlmCallEvent> deduped = new LinkedHashMap<>();
        List<LlmCallEvent> collectorDiagnostics = new ArrayList<>();
        for (LlmCallEvent event : source) {
            if (event.getOccurredAt() != null && event.getOccurredAt().isAfter(asOf)) {
                continue;
            }
            if (isCollectorEvent(event)) {
                if (matchesOccurredAtRange(event, query)) {
                    collectorDiagnostics.add(event);
                }
                continue;
            }
            if (!query.matches(event)) {
                continue;
            }
            LlmCallEvent existing = deduped.get(event.getEventId());
            if (existing != null) {
                if (!sameEventContent(existing, event)) {
                    recordConflictCount++;
                    if (event.getCallId() != null && !event.getCallId().isBlank()) {
                        conflictedCallIds.add(event.getCallId());
                    }
                    if (existing.getCallId() != null && !existing.getCallId().isBlank()) {
                        conflictedCallIds.add(existing.getCallId());
                    }
                }
                continue;
            }
            deduped.put(event.getEventId(), event);
        }
        List<LlmCallEvent> events = new ArrayList<>(deduped.values());
        events.addAll(collectorDiagnostics);
        events.sort(Comparator.comparing(LlmCallEvent::getCallStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(LlmCallEvent::getEventId, Comparator.nullsLast(String::compareTo)));
        boolean readComplete = testEventsOverride != null || source.size() <= TOTAL_EVENT_LIMIT;
        return new ReadOutcome(events, readComplete, LlmCallRecorder.knownWriteGapCount(), recordConflictCount,
                Set.copyOf(conflictedCallIds));
    }

    private static final class SliceReadState {
        private int recordConflictCount;
        private final Set<String> conflictedCallIds = new LinkedHashSet<>();
    }

    private static void noteEventIdConflict(LlmCallEvent left, LlmCallEvent right, SliceReadState state) {
        state.recordConflictCount++;
        if (left.getCallId() != null && !left.getCallId().isBlank()) {
            state.conflictedCallIds.add(left.getCallId());
        }
        if (right.getCallId() != null && !right.getCallId().isBlank()) {
            state.conflictedCallIds.add(right.getCallId());
        }
    }

    private static boolean isCollectorEvent(LlmCallEvent event) {
        return event.getEventType() == LlmCallEventType.COLLECTOR_GAP
                || event.getEventType() == LlmCallEventType.COLLECTOR_STARTED;
    }

    private static boolean matchesOccurredAtRange(LlmCallEvent event, LlmUsageReportQuery query) {
        Instant occurredAt = event.getOccurredAt();
        if (occurredAt == null) {
            return false;
        }
        return !occurredAt.isBefore(query.getRangeStart()) && occurredAt.isBefore(query.getRangeEnd());
    }

    private static boolean sameEventContent(LlmCallEvent left, LlmCallEvent right) {
        return Objects.equals(left.getCallId(), right.getCallId())
                && Objects.equals(left.getLogicalCallId(), right.getLogicalCallId())
                && left.getSequence() == right.getSequence()
                && left.getEventType() == right.getEventType()
                && left.getSchemaVersion() == right.getSchemaVersion()
                && Objects.equals(left.getEventJson(), right.getEventJson());
    }

    private static List<LlmCallEvent> readFromStream(Instant start, Instant end, SliceReadState state) throws Exception {
        List<LlmCallEvent> collected = new ArrayList<>();
        readSlice(start, end, collected, state);
        if (collected.size() > TOTAL_EVENT_LIMIT) {
            throw new LlmUsageReportException("REPORT_LIMIT_EXCEEDED", "event count exceeds limit");
        }
        return collected;
    }

    private static void readSlice(Instant sliceStart, Instant sliceEnd, List<LlmCallEvent> out, SliceReadState state)
            throws Exception {
        if (sliceStart == null || sliceEnd == null || !sliceStart.isBefore(sliceEnd)) {
            return;
        }
        long startMs = sliceStart.toEpochMilli();
        long endMs = sliceEnd.toEpochMilli();
        long queryStartMs = startMs - 1;
        List<LlmCallEvent> sliceRows = queryPlatformRows(new DateTime(queryStartMs, DateTimeZone.UTC),
                new DateTime(endMs, DateTimeZone.UTC));
        Map<String, LlmCallEvent> deduped = new LinkedHashMap<>();
        for (LlmCallEvent event : sliceRows) {
            LlmCallEvent existing = deduped.get(event.getEventId());
            if (existing != null && !sameEventContent(existing, event)) {
                noteEventIdConflict(existing, event, state);
                continue;
            }
            deduped.putIfAbsent(event.getEventId(), event);
        }
        List<LlmCallEvent> filtered = new ArrayList<>();
        for (LlmCallEvent event : deduped.values()) {
            Instant started = event.getCallStartedAt();
            if (started == null) {
                continue;
            }
            if (!started.isBefore(sliceStart) && started.isBefore(sliceEnd)) {
                filtered.add(event);
            }
        }
        if (sliceRows.size() >= SLICE_PROBE_MAX_ITEMS) {
            if (sliceEnd.toEpochMilli() - sliceStart.toEpochMilli() <= 1) {
                throw new LlmUsageReportException("REPORT_LIMIT_EXCEEDED",
                        "more than " + SLICE_MAX_ROWS + " events in one millisecond slice");
            }
            long mid = sliceStart.toEpochMilli() + (sliceEnd.toEpochMilli() - sliceStart.toEpochMilli()) / 2;
            Instant midInstant = Instant.ofEpochMilli(mid);
            readSlice(sliceStart, midInstant, out, state);
            readSlice(midInstant, sliceEnd, out, state);
            return;
        }
        out.addAll(filtered);
    }

    private static List<LlmCallEvent> queryPlatformRows(DateTime startInclusive, DateTime endExclusiveOrNull)
            throws Exception {
        PlatformRowsHook hook = testPlatformRowsHook;
        if (hook != null) {
            return hook.query(startInclusive, endExclusiveOrNull);
        }
        Thing stream = resolveStreamThing();
        if (stream == null) {
            throw new LlmUsageReportException("REPORT_READ_FAILED",
                    "Stream Thing [" + AgentLlmCallStreamWriter.STREAM_THING_NAME + "] missing");
        }
        ValueCollection params = new ValueCollection();
        params.put("maxItems", new NumberPrimitive((double) SLICE_PROBE_MAX_ITEMS));
        params.put("source", null);
        params.put("tags", null);
        params.put("sourceTags", null);
        params.put("startDate", startInclusive != null ? new DatetimePrimitive(startInclusive) : null);
        params.put("endDate", endExclusiveOrNull != null ? new DatetimePrimitive(endExclusiveOrNull) : null);
        params.put("oldestFirst", new BooleanPrimitive(true));
        params.put("query", null);
        InfoTable rows = PlatformAccess.invokeProgrammatic(stream, "QueryStreamData", params);
        List<LlmCallEvent> events = new ArrayList<>(rows.getRowCount());
        for (int i = 0; i < rows.getRowCount(); i++) {
            events.add(fromRow(rows.getRow(i)));
        }
        return events;
    }

    static LlmCallEvent fromRow(ValueCollection row) throws LlmUsageReportException {
        if (row == null) {
            throw new LlmUsageReportException("REPORT_INVALID_RECORD", "missing row");
        }
        try {
            String eventId = stringField(row, "eventId");
            String eventType = stringField(row, "eventType");
            LlmCallEventType type = LlmCallEventType.fromWire(eventType);
            if (type == null || eventId.isBlank()) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "invalid eventType or eventId for row");
            }
            LlmCallKind kind = LlmCallKind.fromWire(stringField(row, "callKind"));
            Instant callStartedAt = instantField(row, "callStartedAt");
            Instant occurredAt = instantField(row, "occurredAt");
            if (callStartedAt == null || occurredAt == null) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "missing timestamps for eventId=" + eventId);
            }
            int schemaVersion = intField(row, "schemaVersion");
            if (schemaVersion <= 0) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "missing or invalid schemaVersion for eventId=" + eventId);
            }
            return LlmCallEvent.builder(type)
                    .eventId(eventId)
                    .callId(stringField(row, "callId"))
                    .logicalCallId(stringField(row, "logicalCallId"))
                    .turnRequestId(stringField(row, "turnRequestId"))
                    .conversationId(stringField(row, "conversationId"))
                    .agentThing(stringField(row, "agentThing"))
                    .providerThingName(stringField(row, "providerThingName"))
                    .providerFamily(stringField(row, "providerFamily"))
                    .apiShapeId(stringField(row, "apiShapeId"))
                    .requestedModel(stringField(row, "requestedModel"))
                    .callKind(kind)
                    .sequence(intField(row, "sequence"))
                    .callStartedAt(callStartedAt)
                    .occurredAt(occurredAt)
                    .schemaVersion(schemaVersion)
                    .eventJson(stringField(row, "eventJson"))
                    .build();
        } catch (LlmUsageReportException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUsageReportException("REPORT_INVALID_RECORD", "malformed row: " + e.getMessage());
        }
    }

    private static String stringField(ValueCollection row, String name) {
        Object value = row.getValue(name);
        return value == null ? "" : String.valueOf(value);
    }

    private static int intField(ValueCollection row, String name) {
        Object value = row.getValue(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    private static Instant instantField(ValueCollection row, String name) {
        Object value = row.getValue(name);
        if (value instanceof DateTime) {
            return Instant.ofEpochMilli(((DateTime) value).getMillis());
        }
        return null;
    }

    private static Thing resolveStreamThing() {
        if (testStreamMissing != null && testStreamMissing) {
            return null;
        }
        try {
            RootEntity entity = PlatformAccess.findProgrammatic(AgentLlmCallStreamWriter.STREAM_THING_NAME,
                    RelationshipTypes.ThingworxRelationshipTypes.Thing);
            return entity instanceof Thing ? (Thing) entity : null;
        } catch (Exception e) {
            return null;
        }
    }

    public interface PlatformRowsHook {
        List<LlmCallEvent> query(DateTime startInclusive, DateTime endExclusiveOrNull) throws Exception;
    }

    public static final class ReadOutcome {
        private final List<LlmCallEvent> events;
        private final boolean readComplete;
        private final int knownWriteGapCount;
        private final int recordConflictCount;
        private final Set<String> conflictedCallIds;

        ReadOutcome(
                List<LlmCallEvent> events,
                boolean readComplete,
                int knownWriteGapCount,
                int recordConflictCount,
                Set<String> conflictedCallIds) {
            this.events = events != null ? List.copyOf(events) : List.of();
            this.readComplete = readComplete;
            this.knownWriteGapCount = knownWriteGapCount;
            this.recordConflictCount = recordConflictCount;
            this.conflictedCallIds = conflictedCallIds != null ? Set.copyOf(conflictedCallIds) : Set.of();
        }

        public List<LlmCallEvent> getEvents() {
            return events;
        }

        public boolean isReadComplete() {
            return readComplete;
        }

        public int getKnownWriteGapCount() {
            return knownWriteGapCount;
        }

        public int getRecordConflictCount() {
            return recordConflictCount;
        }

        public Set<String> getConflictedCallIds() {
            return conflictedCallIds;
        }
    }
}
