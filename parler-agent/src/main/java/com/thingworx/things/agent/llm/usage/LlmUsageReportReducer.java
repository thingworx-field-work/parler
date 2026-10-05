package com.thingworx.things.agent.llm.usage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Merges stream events into per-call summaries and JSON report (CC-7.5 / CC-7.6).
 */
public final class LlmUsageReportReducer {

    static final List<String> FIXED_NUMERIC_COLUMNS = List.of(
            "inputTokensTotal",
            "inputTokensUncached",
            "inputTokensCacheRead",
            "inputTokensCacheWrite",
            "cacheWrite5mTokens",
            "cacheWrite1hTokens",
            "outputTokensTotal",
            "reasoningTokens",
            "totalTokens");

    private static final ObjectMapper JSON = new ObjectMapper();

    private LlmUsageReportReducer() {}

    public static ObjectNode reduce(
            LlmUsageReportQuery query,
            AgentLlmCallStreamReader.ReadOutcome readOutcome,
            Instant reportAsOf,
            LlmUsageCostCalculator costCalculator) throws LlmUsageReportException {
        if (query == null || readOutcome == null) {
            throw new LlmUsageReportException("REPORT_READ_FAILED", "missing query or read outcome");
        }
        Map<String, CallAccumulator> byCallId = new LinkedHashMap<>();
        List<ObjectNode> collectorDiagnostics = new ArrayList<>();
        int recordConflictCount = readOutcome.getRecordConflictCount();
        Set<String> readerConflictedCallIds = readOutcome.getConflictedCallIds();
        for (LlmCallEvent event : readOutcome.getEvents()) {
            if (event.getEventType() == LlmCallEventType.COLLECTOR_GAP
                    || event.getEventType() == LlmCallEventType.COLLECTOR_STARTED) {
                collectorDiagnostics.add(collectorDiagnostic(event));
                continue;
            }
            if (event.getCallId() == null || event.getCallId().isBlank()) {
                continue;
            }
            CallAccumulator acc = byCallId.computeIfAbsent(event.getCallId(), CallAccumulator::new);
            if (readerConflictedCallIds.contains(event.getCallId())) {
                acc.conflict = true;
            }
            if (acc.recordConflict(event)) {
                recordConflictCount++;
            }
        }

        ObjectNode report = JSON.createObjectNode();
        report.put("schemaVersion", 1);
        report.put("reportAsOf", (reportAsOf != null ? reportAsOf : Instant.now()).toString());
        ObjectNode filters = report.putObject("filters");
        filters.put("start", query.getRangeStart().toString());
        filters.put("end", query.getRangeEnd().toString());
        putIfPresent(filters, "conversationId", query.getConversationId());
        putIfPresent(filters, "agentThing", query.getAgentThing());
        putIfPresent(filters, "model", query.getModel());

        report.put("captureCoverage", "observed_only");
        report.put("readComplete", readOutcome.isReadComplete());
        report.put("knownWriteGapCount", readOutcome.getKnownWriteGapCount());
        report.put("recordConflictCount", recordConflictCount);
        if (!collectorDiagnostics.isEmpty()) {
            ArrayNode diagnostics = report.putArray("collectorDiagnostics");
            collectorDiagnostics.forEach(diagnostics::add);
        }

        int success = 0;
        int error = 0;
        int timeout = 0;
        int notSent = 0;
        int unfinished = 0;
        int orphan = 0;
        int sentCallCount = 0;
        int unknownDispatchCount = 0;
        int cancelRequestedCount = 0;
        int usageComplete = 0;
        int usagePartial = 0;
        int usageUnavailable = 0;
        int usageInvalid = 0;
        int unpricedCallCount = 0;
        int costUnsupportedCount = 0;
        BigDecimal knownCostUsd = BigDecimal.ZERO;
        Map<String, Long> knownSums = new TreeMap<>();
        Map<String, Integer> unknownCounts = new TreeMap<>();

        ArrayNode calls = report.putArray("calls");
        for (CallAccumulator acc : byCallId.values()) {
            CallSummary summary = acc.summarize();
            if (!summary.conflict) {
                if (!summary.started && summary.finished) {
                    orphan++;
                } else if (summary.outcome == LlmCallOutcome.SUCCESS) {
                    success++;
                } else if (summary.outcome == LlmCallOutcome.ERROR) {
                    error++;
                } else if (summary.outcome == LlmCallOutcome.TIMEOUT) {
                    timeout++;
                } else if (summary.outcome == LlmCallOutcome.NOT_SENT) {
                    notSent++;
                } else if (summary.started && !summary.finished) {
                    unfinished++;
                }
            }
            if (!summary.conflict && summary.dispatched) {
                sentCallCount++;
            } else if (!summary.conflict && summary.outcome != LlmCallOutcome.NOT_SENT
                    && !summary.dispatched && summary.dispatchState != LlmCallDispatchState.ATTEMPTED) {
                unknownDispatchCount++;
            }
            if (!summary.conflict && summary.cancelObservedAt != null) {
                cancelRequestedCount++;
            }
            if (!summary.conflict) {
                if (summary.usageStatus == LlmUsageSnapshot.UsageStatus.COMPLETE) {
                    usageComplete++;
                } else if (summary.usageStatus == LlmUsageSnapshot.UsageStatus.PARTIAL) {
                    usagePartial++;
                } else if (summary.usageStatus == LlmUsageSnapshot.UsageStatus.UNAVAILABLE) {
                    usageUnavailable++;
                } else if (summary.usageStatus == LlmUsageSnapshot.UsageStatus.INVALID) {
                    usageInvalid++;
                }
            }
            LlmUsageCostCalculator.CostResult cost = summary.conflict
                    ? LlmUsageCostCalculator.CostResult.unpriced()
                    : (costCalculator != null
                            ? costCalculator.priceCall(summary)
                            : LlmUsageCostCalculator.CostResult.unpriced());
            if (!summary.conflict && cost.knownUsd != null) {
                knownCostUsd = knownCostUsd.add(cost.knownUsd);
            } else if (!summary.conflict && cost.unpriced) {
                unpricedCallCount++;
            }
            if (!summary.conflict && cost.unsupported) {
                costUnsupportedCount++;
            }
            accumulateNormalized(knownSums, unknownCounts, summary, report);
            calls.add(summary.toJson(cost, costCalculator != null ? costCalculator.getPriceVersion() : "unpriced-v1"));
        }

        report.put("callCount", byCallId.size());
        report.put("sentCallCount", sentCallCount);
        report.put("unknownDispatchCount", unknownDispatchCount);
        report.put("notSentCount", notSent);
        report.put("successCount", success);
        report.put("errorCount", error);
        report.put("timeoutCount", timeout);
        report.put("unfinishedCount", unfinished);
        report.put("orphanCount", orphan);
        report.put("cancelRequestedCount", cancelRequestedCount);
        report.put("usageCompleteCount", usageComplete);
        report.put("usagePartialCount", usagePartial);
        report.put("usageUnavailableCount", usageUnavailable);
        report.put("usageInvalidCount", usageInvalid);
        report.put("unpricedCallCount", unpricedCallCount);
        report.put("costUnsupportedCount", costUnsupportedCount);
        report.put("knownCostUsd", knownCostUsd.setScale(4, RoundingMode.HALF_UP).toPlainString());
        report.put("priceVersion", costCalculator != null ? costCalculator.getPriceVersion() : "unpriced-v1");
        ObjectNode sums = report.putObject("knownSum");
        knownSums.forEach((key, value) -> sums.put(key, value));
        ObjectNode unknown = report.putObject("unknownCount");
        unknownCounts.forEach(unknown::put);
        if (calls.isEmpty()) {
            report.put("status", "no_visible_records");
        } else {
            report.put("status", "ok");
        }
        return report;
    }

    private static ObjectNode collectorDiagnostic(LlmCallEvent event) throws LlmUsageReportException {
        ObjectNode node = JSON.createObjectNode();
        node.put("eventId", event.getEventId());
        node.put("eventType", event.getEventType().wireValue());
        if (event.getOccurredAt() != null) {
            node.put("occurredAt", event.getOccurredAt().toString());
        }
        try {
            JsonNode eventJson = JSON.readTree(event.getEventJson());
            if (event.getSchemaVersion() != LlmUsageSnapshot.SCHEMA_VERSION) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "unknown schemaVersion for collector eventId=" + event.getEventId());
            }
            int envelopeVersion = eventJson.path("schemaVersion").asInt(-1);
            if (envelopeVersion != LlmUsageSnapshot.SCHEMA_VERSION) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "unsupported collector envelope schemaVersion for eventId=" + event.getEventId());
            }
            node.set("eventJson", eventJson);
        } catch (LlmUsageReportException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                    "invalid collector eventJson for eventId=" + event.getEventId());
        }
        return node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
    }

    private static void accumulateNormalized(
            Map<String, Long> knownSums,
            Map<String, Integer> unknownCounts,
            CallSummary summary,
            ObjectNode report) throws LlmUsageReportException {
        boolean allColumnsUnknown = summary.conflict
                || summary.usageStatus == LlmUsageSnapshot.UsageStatus.UNAVAILABLE
                || summary.usageStatus == LlmUsageSnapshot.UsageStatus.INVALID;
        for (String key : FIXED_NUMERIC_COLUMNS) {
            if (allColumnsUnknown) {
                unknownCounts.merge(key, 1, Integer::sum);
                continue;
            }
            LlmUsageSnapshot.FieldPresence presence = summary.presence != null ? summary.presence.get(key) : null;
            Long value = summary.normalized != null ? summary.normalized.get(key) : null;
            if (value != null
                    && (presence == LlmUsageSnapshot.FieldPresence.REPORTED
                            || presence == LlmUsageSnapshot.FieldPresence.DERIVED)) {
                try {
                    knownSums.merge(key, value, Math::addExact);
                } catch (ArithmeticException overflow) {
                    throw new LlmUsageReportException("REPORT_LIMIT_EXCEEDED",
                            "knownSum overflow for column=" + key);
                }
            } else {
                unknownCounts.merge(key, 1, Integer::sum);
            }
        }
    }

    static final class CallSummary {
        final String callId;
        final String logicalCallId;
        final String conversationId;
        final String agentThing;
        final String requestedModel;
        final String responseModel;
        final String providerFamily;
        final String providerThingName;
        final String apiShapeId;
        final LlmCallKind callKind;
        final Instant callStartedAt;
        final Instant endedAt;
        final Instant cancelObservedAt;
        final LlmCallOutcome outcome;
        final LlmCallDispatchState dispatchState;
        final boolean started;
        final boolean finished;
        final boolean dispatched;
        final boolean conflict;
        final LlmUsageSnapshot.UsageStatus usageStatus;
        final Map<String, Long> normalized;
        final Map<String, LlmUsageSnapshot.FieldPresence> presence;
        final JsonNode rawUsage;

        CallSummary(
                String callId,
                String logicalCallId,
                String conversationId,
                String agentThing,
                String requestedModel,
                String responseModel,
                String providerFamily,
                String providerThingName,
                String apiShapeId,
                LlmCallKind callKind,
                Instant callStartedAt,
                Instant endedAt,
                Instant cancelObservedAt,
                LlmCallOutcome outcome,
                LlmCallDispatchState dispatchState,
                boolean started,
                boolean finished,
                boolean dispatched,
                boolean conflict,
                LlmUsageSnapshot.UsageStatus usageStatus,
                Map<String, Long> normalized,
                Map<String, LlmUsageSnapshot.FieldPresence> presence,
                JsonNode rawUsage) {
            this.callId = callId;
            this.logicalCallId = logicalCallId;
            this.conversationId = conversationId;
            this.agentThing = agentThing;
            this.requestedModel = requestedModel;
            this.responseModel = responseModel;
            this.providerFamily = providerFamily;
            this.providerThingName = providerThingName;
            this.apiShapeId = apiShapeId;
            this.callKind = callKind;
            this.callStartedAt = callStartedAt;
            this.endedAt = endedAt;
            this.cancelObservedAt = cancelObservedAt;
            this.outcome = outcome;
            this.dispatchState = dispatchState;
            this.started = started;
            this.finished = finished;
            this.dispatched = dispatched;
            this.conflict = conflict;
            this.usageStatus = usageStatus;
            this.normalized = normalized;
            this.presence = presence;
            this.rawUsage = rawUsage;
        }

        ObjectNode toJson(LlmUsageCostCalculator.CostResult cost, String priceVersion) {
            ObjectNode node = JSON.createObjectNode();
            node.put("callId", callId);
            node.put("logicalCallId", logicalCallId);
            node.put("conversationId", conversationId);
            node.put("agentThing", agentThing);
            node.put("requestedModel", requestedModel);
            node.put("responseModel", responseModel != null ? responseModel : "");
            node.put("providerFamily", providerFamily);
            node.put("providerThingName", providerThingName != null ? providerThingName : "");
            node.put("apiShapeId", apiShapeId != null ? apiShapeId : "");
            node.put("callKind", callKind != null ? callKind.wireValue() : "");
            if (callStartedAt != null) {
                node.put("callStartedAt", callStartedAt.toString());
            }
            if (endedAt != null) {
                node.put("endedAt", endedAt.toString());
            }
            if (cancelObservedAt != null) {
                node.put("cancelObservedAt", cancelObservedAt.toString());
            }
            node.put("outcome", outcome != null ? outcome.wireValue() : "");
            node.put("dispatchState", dispatchState != null ? dispatchState.wireValue() : "");
            node.put("usageStatus", usageStatus != null ? usageStatus.wireValue() : "");
            node.put("conflict", conflict);
            node.put("priceVersion", priceVersion != null ? priceVersion : "");
            if (cost.knownUsd != null) {
                node.put("knownCostUsd", cost.knownUsd.setScale(4, RoundingMode.HALF_UP).toPlainString());
            }
            node.put("costStatus", cost.status);
            ObjectNode normalizedNode = node.putObject("normalized");
            if (normalized != null) {
                normalized.forEach((key, value) -> {
                    if (value != null) {
                        normalizedNode.put(key, value);
                    }
                });
            }
            ObjectNode presenceNode = node.putObject("presence");
            if (presence != null) {
                presence.forEach((key, value) -> presenceNode.put(key, value.wireValue()));
            }
            if (rawUsage != null && !rawUsage.isMissingNode()) {
                node.set("rawUsage", rawUsage.deepCopy());
            }
            return node;
        }
    }

    private static final class CallAccumulator {
        private final String callId;
        private final List<LlmCallEvent> events = new ArrayList<>();
        private boolean conflict;
        private String anchorLogicalCallId;
        private String anchorConversationId;
        private String anchorAgentThing;
        private String anchorRequestedModel;
        private String anchorProviderThingName;
        private String anchorApiShapeId;
        private String anchorTurnRequestId;
        private LlmCallKind anchorCallKind;
        private LlmCallOutcome terminalOutcome;
        private LlmCallDispatchState terminalDispatchState;
        private LlmUsageSnapshot terminalUsageSnapshot;

        CallAccumulator(String callId) {
            this.callId = callId;
        }

        boolean recordConflict(LlmCallEvent event) {
            if (conflictIdentity(event)) {
                conflict = true;
                return true;
            }
            for (LlmCallEvent existing : events) {
                if (existing.getSequence() == event.getSequence()
                        && !existing.getEventId().equals(event.getEventId())) {
                    conflict = true;
                    return true;
                }
            }
            events.add(event);
            if (event.getEventType() == LlmCallEventType.CALL_FINISHED) {
                try {
                    JsonNode envelope = parseEnvelope(event);
                    LlmCallOutcome outcome = LlmCallOutcome.fromWire(text(envelope, "outcome"));
                    LlmCallDispatchState dispatch = LlmCallDispatchState.fromWire(text(envelope, "dispatchState"));
                    LlmUsageSnapshot usage = usageFromEnvelope(envelope);
                    if (terminalUsageSnapshot != null && usage != null
                            && !sameNormalizedSnapshot(terminalUsageSnapshot, usage)) {
                        conflict = true;
                        return true;
                    }
                    if (usage != null) {
                        terminalUsageSnapshot = usage;
                    }
                    if (terminalOutcome != null && outcome != null && terminalOutcome != outcome) {
                        conflict = true;
                        return true;
                    }
                    if (terminalDispatchState != null && dispatch != null && terminalDispatchState != dispatch) {
                        conflict = true;
                        return true;
                    }
                    if (outcome != null) {
                        terminalOutcome = outcome;
                    }
                    if (dispatch != null) {
                        terminalDispatchState = dispatch;
                    }
                } catch (LlmUsageReportException ignored) {
                    conflict = true;
                    return true;
                }
            }
            return false;
        }

        private boolean conflictIdentity(LlmCallEvent event) {
            if (anchorLogicalCallId == null) {
                anchorLogicalCallId = event.getLogicalCallId();
                anchorConversationId = event.getConversationId();
                anchorAgentThing = event.getAgentThing();
                anchorRequestedModel = event.getRequestedModel();
                anchorProviderThingName = event.getProviderThingName();
                anchorApiShapeId = event.getApiShapeId();
                anchorTurnRequestId = event.getTurnRequestId();
                anchorCallKind = event.getCallKind();
                return false;
            }
            return !Objects.equals(anchorLogicalCallId, event.getLogicalCallId())
                    || !Objects.equals(anchorConversationId, event.getConversationId())
                    || !Objects.equals(anchorAgentThing, event.getAgentThing())
                    || !Objects.equals(anchorRequestedModel, event.getRequestedModel())
                    || !Objects.equals(anchorProviderThingName, event.getProviderThingName())
                    || !Objects.equals(anchorApiShapeId, event.getApiShapeId())
                    || !Objects.equals(anchorTurnRequestId, event.getTurnRequestId())
                    || anchorCallKind != event.getCallKind();
        }

        private static boolean sameNormalizedSnapshot(LlmUsageSnapshot left, LlmUsageSnapshot right) {
            if (left == null || right == null) {
                return left == right;
            }
            return left.getStatus() == right.getStatus()
                    && Objects.equals(left.getNormalized(), right.getNormalized())
                    && Objects.equals(left.getPresence(), right.getPresence())
                    && sameRawUsage(left.getRawUsage(), right.getRawUsage());
        }

        private static boolean sameRawUsage(JsonNode left, JsonNode right) {
            if (left == null || left.isMissingNode() || left.isNull()) {
                return right == null || right.isMissingNode() || right.isNull();
            }
            return left.equals(right);
        }

        CallSummary summarize() throws LlmUsageReportException {
            events.sort(Comparator.comparingInt(LlmCallEvent::getSequence));
            LlmCallEvent anchor = events.get(0);
            boolean started = false;
            boolean finished = false;
            boolean dispatched = false;
            LlmCallOutcome outcome = null;
            LlmCallDispatchState dispatchState = null;
            Instant cancelObservedAt = null;
            Instant endedAt = null;
            String responseModel = null;
            LlmUsageSnapshot bestUsage = null;
            for (LlmCallEvent event : events) {
                JsonNode envelope = parseEnvelope(event);
                if (event.getEventType() == LlmCallEventType.CALL_STARTED) {
                    started = true;
                }
                if (event.getEventType() == LlmCallEventType.CALL_DISPATCHED) {
                    dispatched = true;
                }
                if (event.getEventType() == LlmCallEventType.CALL_FINISHED) {
                    finished = true;
                    String outcomeWire = text(envelope, "outcome");
                    outcome = LlmCallOutcome.fromWire(outcomeWire);
                    endedAt = parseInstant(text(envelope, "endedAt"));
                }
                String dispatchWire = text(envelope, "dispatchState");
                if (dispatchWire != null) {
                    dispatchState = LlmCallDispatchState.fromWire(dispatchWire);
                }
                Instant cancelAt = parseInstant(text(envelope, "cancelObservedAt"));
                if (cancelAt != null) {
                    cancelObservedAt = cancelAt;
                }
                String model = text(envelope, "responseModel");
                if (model != null && !model.isBlank()) {
                    responseModel = model;
                }
                LlmUsageSnapshot usage = usageFromEnvelope(envelope);
                if (isBetterUsage(bestUsage, usage)) {
                    bestUsage = usage;
                }
            }
            if (!dispatched && dispatchState == LlmCallDispatchState.ATTEMPTED) {
                dispatched = true;
            }
            LlmUsageSnapshot.UsageStatus usageStatus = bestUsage != null
                    ? bestUsage.getStatus()
                    : LlmUsageSnapshot.UsageStatus.UNAVAILABLE;
            return new CallSummary(
                    callId,
                    anchor.getLogicalCallId(),
                    anchor.getConversationId(),
                    anchor.getAgentThing(),
                    anchor.getRequestedModel(),
                    responseModel,
                    anchor.getProviderFamily(),
                    anchor.getProviderThingName(),
                    anchor.getApiShapeId(),
                    anchor.getCallKind(),
                    anchor.getCallStartedAt(),
                    endedAt,
                    cancelObservedAt,
                    outcome,
                    dispatchState,
                    started,
                    finished,
                    dispatched,
                    conflict,
                    usageStatus,
                    bestUsage != null ? bestUsage.getNormalized() : Map.of(),
                    bestUsage != null ? bestUsage.getPresence() : Map.of(),
                    bestUsage != null ? bestUsage.getRawUsage() : null);
        }

        private static boolean isBetterUsage(LlmUsageSnapshot current, LlmUsageSnapshot candidate) {
            if (candidate == null) {
                return false;
            }
            if (current == null) {
                return true;
            }
            if (candidate.getRevision() < current.getRevision()) {
                return false;
            }
            if (current.getStatus() == LlmUsageSnapshot.UsageStatus.COMPLETE
                    && (candidate.getStatus() == LlmUsageSnapshot.UsageStatus.UNAVAILABLE
                            || candidate.getNormalized().isEmpty())) {
                return false;
            }
            return candidate.getRevision() > current.getRevision()
                    || current.getStatus() != LlmUsageSnapshot.UsageStatus.COMPLETE;
        }

        private static JsonNode parseEnvelope(LlmCallEvent event) throws LlmUsageReportException {
            if (event.getSchemaVersion() != LlmUsageSnapshot.SCHEMA_VERSION) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "unknown schemaVersion for eventId=" + event.getEventId());
            }
            try {
                JsonNode envelope = JSON.readTree(event.getEventJson());
                int envelopeVersion = envelope.path("schemaVersion").asInt(-1);
                if (envelopeVersion != LlmUsageSnapshot.SCHEMA_VERSION) {
                    throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                            "unsupported envelope schemaVersion for eventId=" + event.getEventId());
                }
                if (event.getEventType() != LlmCallEventType.COLLECTOR_GAP
                        && event.getEventType() != LlmCallEventType.COLLECTOR_STARTED) {
                    JsonNode identity = envelope.path("identity");
                    if (!identity.isObject() || !identity.hasNonNull("logicalCallId")) {
                        throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                                "missing identity for eventId=" + event.getEventId());
                    }
                    if (!Objects.equals(identity.path("logicalCallId").asText(), event.getLogicalCallId())) {
                        throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                                "identity logicalCallId mismatch for eventId=" + event.getEventId());
                    }
                }
                return envelope;
            } catch (LlmUsageReportException e) {
                throw e;
            } catch (Exception e) {
                throw new LlmUsageReportException("REPORT_INVALID_RECORD",
                        "invalid eventJson for eventId=" + event.getEventId());
            }
        }

        private static LlmUsageSnapshot usageFromEnvelope(JsonNode envelope) {
            JsonNode usage = envelope.path("usage");
            if (!usage.isObject()) {
                return null;
            }
            LlmUsageSnapshot.Builder builder = new LlmUsageSnapshot.Builder();
            builder.status(LlmUsageSnapshot.UsageStatus.fromWire(text(usage, "status")));
            builder.revision(usage.path("revision").asInt(0));
            if (usage.has("rawUsage")) {
                builder.rawUsage(usage.get("rawUsage").deepCopy());
            }
            JsonNode normalized = usage.path("normalized");
            JsonNode presence = usage.path("presence");
            if (normalized.isObject()) {
                normalized.fields().forEachRemaining(entry -> {
                    if (entry.getValue().isIntegralNumber()) {
                        LlmUsageSnapshot.FieldPresence fieldPresence = presenceField(presence, entry.getKey());
                        builder.putNormalized(entry.getKey(), entry.getValue().asLong(), fieldPresence);
                    }
                });
            }
            if (presence.isObject()) {
                presence.fields().forEachRemaining(entry -> {
                    if (!normalized.isObject() || !normalized.has(entry.getKey())) {
                        builder.putNormalized(entry.getKey(), null, presenceField(presence, entry.getKey()));
                    }
                });
            }
            return builder.build();
        }

        private static LlmUsageSnapshot.FieldPresence presenceField(JsonNode presence, String key) {
            if (!presence.isObject() || !presence.has(key)) {
                return LlmUsageSnapshot.FieldPresence.REPORTED;
            }
            String wire = presence.get(key).asText();
            for (LlmUsageSnapshot.FieldPresence value : LlmUsageSnapshot.FieldPresence.values()) {
                if (value.wireValue().equals(wire)) {
                    return value;
                }
            }
            return LlmUsageSnapshot.FieldPresence.ABSENT;
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        }

        private static Instant parseInstant(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return Instant.parse(value);
        }
    }
}
