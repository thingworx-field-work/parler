package com.thingworx.things.agent.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.AgentLoop;

/** Outcome mapper rows and the one-shot turn scope (design: Verification check 1). */
class ParlerTurnScopeTest {

    @Test
    void resultStatusRows() {
        assertEquals(TurnOutcome.SUCCESS, TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.SUCCESS));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.ERROR));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.TIMEOUT));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.MAX_ITERATIONS));
        assertEquals(TurnOutcome.CANCELLED, TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.CANCELLED));
        // AWAITING_APPROVAL without pending-record context is an incomplete pause.
        assertEquals(TurnOutcome.ERROR,
                TurnOutcomes.forResultStatus(AgentLoop.AgentResult.Status.AWAITING_APPROVAL));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forResultStatus(null));
    }

    @Test
    void awaitingApprovalRows() {
        assertEquals(TurnOutcome.AWAITING_APPROVAL, TurnOutcomes.forAwaitingApproval(false, true));
        assertEquals(TurnOutcome.CANCELLED, TurnOutcomes.forAwaitingApproval(true, true));
        assertEquals(TurnOutcome.CANCELLED, TurnOutcomes.forAwaitingApproval(true, false));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forAwaitingApproval(false, false));
    }

    @Test
    void playbookSlashRows() {
        assertEquals(TurnOutcome.SUCCESS, TurnOutcomes.forPlaybookSlash(true));
        assertEquals(TurnOutcome.ERROR, TurnOutcomes.forPlaybookSlash(false));
    }

    @Test
    void scopeRecordsOneOutcomeAndDurationPerSegment() {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        TurnInFlight inFlight = new TurnInFlight();
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        for (TurnEntryPoint ep : TurnEntryPoint.values()) {
            for (TurnOutcome outcome : TurnOutcome.values()) {
                ParlerTurnScope scope = new ParlerTurnScope(ep, sink, inFlight, clock::get);
                assertEquals(1, inFlight.current(ep));
                clock.addAndGet(25_000_000L);
                scope.outcome(outcome);
                scope.close();
                scope.close();
                assertEquals(0, inFlight.current(ep));
            }
        }
        assertEquals(TurnEntryPoint.values().length * TurnOutcome.values().length, sink.events.size());
        assertTrue(sink.events.contains("turn|stream|awaiting_approval"));
        assertTrue(sink.events.contains("turn|approval_resume|cancelled"));
        assertTrue(sink.durations.stream().allMatch(d -> d == 25L));
    }

    @Test
    void unassignedCloseRecordsError() {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        TurnInFlight inFlight = new TurnInFlight();
        try {
            ParlerTurnScope scope = new ParlerTurnScope(TurnEntryPoint.CHAT_ASYNC, sink, inFlight, null);
            try {
                throw new IllegalStateException("post-admission execution exception");
            } finally {
                scope.close();
            }
        } catch (IllegalStateException expected) {
            // unwinding to an outer worker catch
        }
        assertEquals(List.of("turn|chat_async|error"), sink.events);
        assertEquals(0, inFlight.current(TurnEntryPoint.CHAT_ASYNC));
    }

    @Test
    void laterAssignmentBeforeCloseWins() {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        ParlerTurnScope scope = new ParlerTurnScope(TurnEntryPoint.APPROVAL_RESUME, sink, new TurnInFlight(), null);
        scope.outcome(TurnOutcome.SUCCESS);
        scope.outcome(TurnOutcome.ERROR);
        scope.outcome(null);
        scope.close();
        assertEquals(List.of("turn|approval_resume|error"), sink.events);
    }

    @Test
    void inFlightReturnsToZeroAfterRecordingFailure() {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        sink.failRecording = true;
        TurnInFlight inFlight = new TurnInFlight();
        ParlerTurnScope raw = new ParlerTurnScope(TurnEntryPoint.STREAM, sink, inFlight, null);
        assertThrows(IllegalStateException.class, raw::close);
        assertEquals(0, inFlight.current(TurnEntryPoint.STREAM));

        ParlerTurnScope isolated = new ParlerTurnScope(TurnEntryPoint.STREAM,
                new ParlerMetrics.IsolatingSink(sink), inFlight, null);
        isolated.close();
        assertEquals(0, inFlight.current(TurnEntryPoint.STREAM));
    }

    @Test
    void inFlightReturnsToZeroAfterConcurrentCompletion() throws Exception {
        RecordingMetricsSink sink = new RecordingMetricsSink();
        TurnInFlight inFlight = new TurnInFlight();
        int threads = 8;
        int perThread = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int seed = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    TurnEntryPoint ep = TurnEntryPoint.values()[(seed + i) % TurnEntryPoint.values().length];
                    ParlerTurnScope scope = new ParlerTurnScope(ep, sink, inFlight, null);
                    if (i % 3 != 0) {
                        scope.outcome(TurnOutcome.SUCCESS);
                    }
                    scope.close();
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        for (TurnEntryPoint ep : TurnEntryPoint.values()) {
            assertEquals(0, inFlight.current(ep), ep.name());
        }
        assertEquals(threads * perThread, sink.events.size());
    }
}
