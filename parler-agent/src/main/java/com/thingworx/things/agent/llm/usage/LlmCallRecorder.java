package com.thingworx.things.agent.llm.usage;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

/**
 * Records LLM call lifecycle events to {@link AgentLlmCallStreamWriter} (CC-7.3).
 */
public final class LlmCallRecorder {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(LlmCallRecorder.class);

    private static final String COLLECTOR_INSTANCE_ID = UUID.randomUUID().toString();
    private static final AtomicInteger KNOWN_WRITE_GAP_COUNT = new AtomicInteger();
    private static final AtomicInteger PENDING_GAP_EVENTS = new AtomicInteger();
    private static volatile boolean enabled = true;
    private static volatile boolean collectorStartedEmitted;
    private static volatile boolean writeGapWarned;
    private static final ThreadLocal<LlmCallAttempt> CURRENT_ATTEMPT = new ThreadLocal<>();

    private LlmCallRecorder() {}

    public static String collectorInstanceId() {
        return COLLECTOR_INSTANCE_ID;
    }

    public static int knownWriteGapCount() {
        return KNOWN_WRITE_GAP_COUNT.get();
    }

    public static void setEnabledForTest(boolean value) {
        enabled = value;
    }

    public static void resetForTest() {
        enabled = true;
        collectorStartedEmitted = false;
        writeGapWarned = false;
        KNOWN_WRITE_GAP_COUNT.set(0);
        PENDING_GAP_EVENTS.set(0);
        CURRENT_ATTEMPT.remove();
        AgentLlmCallStreamWriter.clearTestHooks();
        AgentLlmCallStreamWriter.setTestSink(event -> { });
    }

    public static void releaseCurrentAttempt() {
        CURRENT_ATTEMPT.remove();
    }

    public static void markCurrentCancelObserved() {
        LlmCallAttempt attempt = CURRENT_ATTEMPT.get();
        if (attempt != null) {
            attempt.markCancelObserved();
        }
    }

    /**
     * Records a pre-round cancel candidate (started → cancel_requested → finished not_sent).
     */
    public static void recordPreRoundCancelCandidate(
            String turnRequestId,
            String conversationId,
            String agentThing) {
        LlmCallContext context = LlmCallContext.builder(LlmCallKind.AGENT_ROUND, LlmCallEvent.newId())
                .turnRequestId(turnRequestId)
                .conversationId(conversationId)
                .agentThing(agentThing)
                .roundIndex(null)
                .build();
        try (LlmCallAttempt attempt = begin(context)) {
            attempt.markCancelObserved();
            attempt.finishNotSent();
        }
    }

    public static LlmCallAttempt ensureAttempt(LlmCallContext context) {
        if (!enabled || context == null) {
            return LlmCallAttempt.noop();
        }
        LlmCallAttempt existing = CURRENT_ATTEMPT.get();
        if (existing != null && existing.isActive() && !existing.isFinished()) {
            if (existing.matchesContext(context)) {
                return existing;
            }
            if (!existing.wasDispatched()) {
                existing.finishNotSent();
            }
        }
        return begin(context);
    }

    public static LlmCallAttempt begin(LlmCallContext context) {
        ensureCollectorStarted();
        if (!enabled || context == null) {
            return LlmCallAttempt.noop();
        }
        String callId = LlmCallEvent.newId();
        Instant startedAt = Instant.now();
        LlmCallAttempt attempt = new LlmCallAttempt(context, callId, startedAt);
        CURRENT_ATTEMPT.set(attempt);
        attempt.emit(LlmCallEventType.CALL_STARTED, 1, LlmCallDispatchState.NOT_SENT, null, null,
                null, null, null, null, null, null, null, null, null, LlmUsageSnapshot.notApplicable());
        return attempt;
    }

    private static void ensureCollectorStarted() {
        if (collectorStartedEmitted) {
            return;
        }
        synchronized (LlmCallRecorder.class) {
            if (collectorStartedEmitted) {
                return;
            }
            Instant now = Instant.now();
            LlmCallEvent event = LlmCallEvent.builder(LlmCallEventType.COLLECTOR_STARTED)
                    .eventId(LlmCallEvent.newId())
                    .occurredAt(now)
                    .callStartedAt(now)
                    .eventJson(buildCollectorJson("started", 0))
                    .build();
            if (write(event) != AgentLlmCallStreamWriter.WriteResult.FAILED) {
                collectorStartedEmitted = true;
            }
        }
    }

    private static String buildCollectorJson(String kind, int failedEvents) {
        return "{\"schemaVersion\":1,\"collectorInstanceId\":\"" + COLLECTOR_INSTANCE_ID
                + "\",\"kind\":\"" + kind + "\",\"failedEvents\":" + failedEvents + "}";
    }

    private static AgentLlmCallStreamWriter.WriteResult write(LlmCallEvent event) {
        if (event != null && event.getEventType() == LlmCallEventType.COLLECTOR_GAP) {
            return AgentLlmCallStreamWriter.append(event);
        }
        AgentLlmCallStreamWriter.WriteResult result = AgentLlmCallStreamWriter.append(event);
        if (result == AgentLlmCallStreamWriter.WriteResult.FAILED) {
            KNOWN_WRITE_GAP_COUNT.incrementAndGet();
            PENDING_GAP_EVENTS.incrementAndGet();
            if (!writeGapWarned) {
                writeGapWarned = true;
                LOG.warn("AgentLlmCallStream write failed; counting collector gap without immediate retry eventType={}",
                        event != null ? event.getEventType() : null);
            }
            return result;
        }
        flushPendingGapEvents();
        return result;
    }

    private static void flushPendingGapEvents() {
        int pending = PENDING_GAP_EVENTS.get();
        if (pending <= 0) {
            return;
        }
        if (!PENDING_GAP_EVENTS.compareAndSet(pending, 0)) {
            return;
        }
        Instant now = Instant.now();
        LlmCallEvent gapEvent = LlmCallEvent.builder(LlmCallEventType.COLLECTOR_GAP)
                .eventId(LlmCallEvent.newId())
                .occurredAt(now)
                .callStartedAt(now)
                .eventJson(buildCollectorJson("gap", pending))
                .build();
        if (AgentLlmCallStreamWriter.append(gapEvent) == AgentLlmCallStreamWriter.WriteResult.FAILED) {
            PENDING_GAP_EVENTS.addAndGet(pending);
        }
    }

    public static final class LlmCallAttempt implements AutoCloseable {
        private static final LlmCallAttempt NOOP = new LlmCallAttempt(null, "", Instant.EPOCH);

        private final LlmCallContext context;
        private final String callId;
        private final Instant callStartedAt;
        private final AtomicInteger sequence = new AtomicInteger(1);
        private volatile boolean finished;
        private volatile LlmCallDispatchState dispatchState = LlmCallDispatchState.NOT_SENT;
        private volatile Instant cancelObservedAt;
        private volatile LlmUsageSnapshot latestUsage = LlmUsageSnapshot.unavailable();

        private LlmCallAttempt(LlmCallContext context, String callId, Instant callStartedAt) {
            this.context = context;
            this.callId = callId;
            this.callStartedAt = callStartedAt;
        }

        static LlmCallAttempt noop() {
            return NOOP;
        }

        public boolean isActive() {
            return context != null;
        }

        public boolean isFinished() {
            return finished;
        }

        public boolean wasDispatched() {
            return dispatchState == LlmCallDispatchState.ATTEMPTED;
        }

        boolean matchesContext(LlmCallContext candidate) {
            if (!isActive() || context == null || candidate == null) {
                return false;
            }
            return context.getCallKind() == candidate.getCallKind()
                    && context.getLogicalCallId().equals(candidate.getLogicalCallId());
        }

        public String getCallId() {
            return callId;
        }

        public void markDispatched() {
            if (!isActive() || finished) {
                return;
            }
            dispatchState = LlmCallDispatchState.ATTEMPTED;
            emit(LlmCallEventType.CALL_DISPATCHED, nextSequence(), dispatchState, null, null,
                    null, null, null, null, null, null, null, null, null, latestUsage);
        }

        public void markCancelObserved() {
            if (!isActive()) {
                return;
            }
            if (cancelObservedAt == null) {
                cancelObservedAt = Instant.now();
            }
            emit(LlmCallEventType.CALL_CANCEL_REQUESTED, nextSequence(), dispatchState, null, cancelObservedAt,
                    null, null, null, null, null, null, null, null, null, latestUsage);
        }

        public void finishSuccess(
                LlmUsageWireIds wireIds,
                LlmUsageSnapshot usage,
                LlmResponse response,
                int httpStatus,
                String providerRequestId,
                String providerResponseId,
                String responseModel,
                long durationMs) {
            if (!isActive() || finished) {
                return;
            }
            latestUsage = usage != null ? usage : LlmUsageSnapshot.unavailable();
            finished = true;
            emit(LlmCallEventType.CALL_FINISHED, nextSequence(), dispatchState, LlmCallOutcome.SUCCESS,
                    cancelObservedAt, Instant.now(), durationMs, httpStatus, providerRequestId,
                    providerResponseId,
                    responseModel,
                    response != null && response.getFinishReason() != null ? response.getFinishReason().name() : null,
                    null, null, latestUsage);
        }

        public void finishError(
                LlmUsageWireIds wireIds,
                LlmUsageSnapshot usage,
                Integer httpStatus,
                String providerRequestId,
                String providerResponseId,
                String responseModel,
                String errorCategory,
                String errorSummary,
                long durationMs) {
            if (!isActive() || finished) {
                return;
            }
            latestUsage = usage != null ? usage : LlmUsageSnapshot.unavailable();
            finished = true;
            emit(LlmCallEventType.CALL_FINISHED, nextSequence(), dispatchState, LlmCallOutcome.ERROR,
                    cancelObservedAt, Instant.now(), durationMs, httpStatus, providerRequestId,
                    providerResponseId, responseModel, null,
                    errorCategory, errorSummary, latestUsage);
        }

        public void finishTimeout(LlmUsageWireIds wireIds, String responseModel, long durationMs) {
            if (!isActive() || finished) {
                return;
            }
            finished = true;
            emit(LlmCallEventType.CALL_FINISHED, nextSequence(), dispatchState, LlmCallOutcome.TIMEOUT,
                    cancelObservedAt, Instant.now(), durationMs, null, null,
                    null, responseModel, null,
                    "timeout", "transport_timeout", latestUsage);
        }

        public void finishNotSent() {
            if (!isActive() || finished) {
                return;
            }
            finished = true;
            emit(LlmCallEventType.CALL_FINISHED, nextSequence(), LlmCallDispatchState.NOT_SENT,
                    LlmCallOutcome.NOT_SENT, cancelObservedAt, Instant.now(), 0L, null, null,
                    null, null, null, null, null, LlmUsageSnapshot.notApplicable());
        }

        public void observeLateUsage(LlmUsageSnapshot usage) {
            if (!isActive()) {
                return;
            }
            if (usage == null) {
                return;
            }
            int revision = latestUsage.getRevision() + 1;
            latestUsage = LlmUsageSnapshot.withRevision(usage, revision);
            emit(LlmCallEventType.USAGE_OBSERVED, nextSequence(), dispatchState, null,
                    cancelObservedAt, Instant.now(), null, null, null,
                    null, null, null, null, null, latestUsage);
        }

        @Override
        public void close() {
            try {
                if (isActive() && !finished) {
                    finishNotSent();
                }
            } finally {
                if (CURRENT_ATTEMPT.get() == this) {
                    CURRENT_ATTEMPT.remove();
                }
            }
        }

        private int nextSequence() {
            return sequence.incrementAndGet();
        }

        private void emit(
                LlmCallEventType eventType,
                int sequence,
                LlmCallDispatchState dispatch,
                LlmCallOutcome outcome,
                Instant cancelAt,
                Instant endedAt,
                Long durationMs,
                Integer httpStatus,
                String providerRequestId,
                String providerResponseId,
                String responseModel,
                String finishReason,
                String errorCategory,
                String errorSummary,
                LlmUsageSnapshot usage) {
            LlmUsageWireIds ids = context.getWireIds();
            String apiShape = ids != null ? ids.getApiShapeId() : "";
            String eventJson = LlmCallEvent.buildEventJson(
                    context,
                    eventType,
                    sequence,
                    dispatch,
                    outcome,
                    cancelAt,
                    endedAt,
                    durationMs,
                    httpStatus,
                    providerRequestId,
                    providerResponseId,
                    responseModel,
                    finishReason,
                    errorCategory,
                    errorSummary,
                    usage,
                    COLLECTOR_INSTANCE_ID);
            Instant occurredAt = endedAt != null ? endedAt : Instant.now();
            LlmCallEvent event = LlmCallEvent.builder(eventType)
                    .eventId(LlmCallEvent.newId())
                    .callId(callId)
                    .logicalCallId(context.getLogicalCallId())
                    .turnRequestId(context.getTurnRequestId())
                    .conversationId(context.getConversationId())
                    .agentThing(context.getAgentThing())
                    .providerThingName(ids != null ? ids.getProviderThingName() : "")
                    .providerFamily(LlmCallEvent.providerFamilyFromShape(apiShape))
                    .apiShapeId(apiShape)
                    .requestedModel(ids != null ? ids.getModel() : "")
                    .callKind(context.getCallKind())
                    .sequence(sequence)
                    .callStartedAt(callStartedAt)
                    .occurredAt(occurredAt)
                    .eventJson(eventJson)
                    .build();
            write(event);
        }
    }

    /** Factory helpers for call contexts at integration sites. */
    public static LlmCallContext agentRoundContext(
            String turnRequestId,
            String conversationId,
            String agentThing,
            int roundIndex,
            LlmUsageWireIds wireIds,
            LlmCallContextPlanSnapshot plan) {
        return LlmCallContext.builder(LlmCallKind.AGENT_ROUND, LlmCallEvent.newId())
                .turnRequestId(turnRequestId)
                .conversationId(conversationId)
                .agentThing(agentThing)
                .roundIndex(roundIndex)
                .wireIds(wireIds)
                .contextPlan(plan)
                .build();
    }

    public static LlmCallContext subCallContext(LlmCallKind kind, LlmUsageWireIds wireIds) {
        return subCallContext(kind, null, wireIds);
    }

    public static LlmCallContext subCallContext(LlmCallKind kind, LlmCallContext parent, LlmUsageWireIds wireIds) {
        LlmCallContext.Builder builder = LlmCallContext.builder(kind, LlmCallEvent.newId()).wireIds(wireIds);
        if (parent != null) {
            builder.turnRequestId(parent.getTurnRequestId())
                    .conversationId(parent.getConversationId())
                    .agentThing(parent.getAgentThing());
        }
        return builder.build();
    }
}
