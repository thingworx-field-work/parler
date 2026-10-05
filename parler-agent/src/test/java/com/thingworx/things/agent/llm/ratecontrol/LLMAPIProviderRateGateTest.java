package com.thingworx.things.agent.llm.ratecontrol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.thingworx.things.agent.llm.ChatMessage;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmHttpFailureException;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;

class LLMAPIProviderRateGateTest {

    @Test
    void enforce_rejectsWhenTokensInsufficient() {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 1, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hello world")),
                null, 0.0, 32, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        assertThrows(LlmRateLimitAdmissionException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw new AssertionError("delegate must not run");
        }));
    }

    @Test
    void enforce_refundsOnNon429Failure() {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 10_000, 100, 10, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("x")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        assertThrows(RuntimeException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw new RuntimeException("upstream 500");
        }));
    }

    @Test
    void enforce_structuredNon429RefundsReservation() throws Exception {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 10_000, 100, 10, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("structured500")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        LlmHttpFailureException http500 = new LlmHttpFailureException(
                "fail", 500, false, Map.of(), "");
        assertThrows(LlmHttpFailureException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw http500;
        }));

        LlmResponse ok = gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
    }

    @Test
    void enforce_upstream429EmptiesBucketsAndHonorsRetryAfter() {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 500, 60, 5, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("y")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        LlmHttpFailureException http429 = new LlmHttpFailureException(
                "fail", 429, true, Collections.singletonMap("retry-after", "30"), "preview");
        assertThrows(LlmHttpFailureException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw http429;
        }));

        LLMAPIProviderRateGate.GateSnapshot snap = gate.testSnapshot(config);
        assertEquals(0.0, snap.tokensRemaining, 0.001);
        assertEquals(0.0, snap.requestsRemaining, 0.001);

        LlmRateLimitAdmissionException blocked = assertThrows(LlmRateLimitAdmissionException.class,
                () -> gate.execute(req, ids, config, estimate, r -> {
                    throw new AssertionError("delegate must not run");
                }));
        assertEquals(LlmRateLimitAdmissionReason.upstream_blocked, blocked.getReason());
        assertTrue(blocked.getRetryAfterMs() >= 25_000 && blocked.getRetryAfterMs() <= 35_000,
                "retryAfterMs should reflect Retry-After: 30, got " + blocked.getRetryAfterMs());
    }

    @Test
    void observe_logAdmissions_initializesBucketsOnFreshGate() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.observe, 50_000, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, true);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        assertFalse(gate.testBucketsInitializedForTests());
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("observe")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));

        assertTrue(gate.testBucketsInitializedForTests());
        assertEquals(50_000, gate.testTokenLevelUnlocked(), 0.001);
    }

    @Test
    void observe_doesNotDebitBuckets() throws Exception {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.observe, 100, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("observe")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        LlmResponse ok = gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
    }

    @Test
    void tokenBucket_clampsNegativeDebt() throws Exception {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 10_000, 0, 0, 0,
                TokenReserveStrategy.input_plus_requested_output, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("z")),
                null, 0.0, 200, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 500, 500));

        LLMAPIProviderRateGate.GateSnapshot snap = gate.testSnapshot(config);
        assertTrue(snap.tokensRemaining >= -config.tokensPerMinuteLimit,
                "negative debt must not exceed one minute of TPM");
    }

    @Test
    void contextPlanningInputCapChars_reservesOutputAndSingleRequestMargin() {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 50_000, 0, 0, 0,
                TokenReserveStrategy.input_plus_requested_output, 1.15, 0, false);
        var cap = config.contextPlanningInputCapChars(8_192);
        assertTrue(cap.isPresent());
        assertTrue(cap.getAsLong() > 100_000, "cap should leave substantial input room");
        assertTrue(cap.getAsLong() < 125_000, "cap must reserve output tokens and margin");
    }

    @Test
    void contextPlanningInputCapChars_emptyOutsideEnforceMode() {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.observe, 50_000, 0, 0, 0,
                TokenReserveStrategy.input_plus_requested_output, 1.15, 0, false);
        assertTrue(config.contextPlanningInputCapChars(8_192).isEmpty());
    }

    @Test
    void expiredBlockedUntil_clearedAfterUpstreamBlockExpiry() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 500, 60, 5, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("y")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmHttpFailureException http429 = new LlmHttpFailureException(
                "fail", 429, true, Collections.singletonMap("retry-after", "1"), "preview");
        assertThrows(LlmHttpFailureException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw http429;
        }));
        Thread.sleep(1_100);
        LLMAPIProviderRateGate.GateSnapshot snap = gate.testSnapshot(config);
        assertEquals(0, snap.blockedUntilEpochMs);
        assertEquals(0, snap.retryAfterMs);
    }

    @Test
    void enforce_maxLocalWaitMsZero_rejectsWithoutWaiting() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 100, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("wait-zero")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 95, 0));

        long start = System.nanoTime();
        assertThrows(LlmRateLimitAdmissionException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw new AssertionError("delegate must not run");
        }));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertTrue(elapsedMs < 200, "fail-fast should not wait, elapsedMs=" + elapsedMs);
        assertEquals(0, gate.testSnapshot(config).localWaits);
    }

    @Test
    void enforce_boundedWait_succeedsAfterTokenRefill() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 60_000, 0, 0, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("drain")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 59_990, 0));

        RateEstimate followEstimate = new RateEstimate(5, 8, 500);

        long start = System.nanoTime();
        LlmResponse ok = gate.execute(drain, ids, config, followEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("ok", ok.getContent());
        assertTrue(elapsedMs >= 50, "expected local wait before refill, elapsedMs=" + elapsedMs);
        LLMAPIProviderRateGate.GateSnapshot snap = gate.testSnapshot(config);
        assertTrue(snap.localWaits >= 1);
        assertTrue(snap.maxWaitMs >= 1);
    }

    @Test
    void enforce_boundedWait_timesOutWithoutDebit() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 100, 0, 0, 80,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("d")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 95, 0));

        RateEstimate estimate = new RateEstimate(5, 8, 90);

        LLMAPIProviderRateGate.GateSnapshot before = gate.testSnapshot(config);
        LlmRateLimitAdmissionException rejected = assertThrows(LlmRateLimitAdmissionException.class,
                () -> gate.execute(drain, ids, config, estimate, r -> {
                    throw new AssertionError("delegate must not run");
                }));
        LLMAPIProviderRateGate.GateSnapshot after = gate.testSnapshot(config);

        assertEquals(LlmRateLimitAdmissionReason.tokens_per_minute, after.lastRejectionReason);
        assertEquals(before.inflight, after.inflight);
        assertTrue(after.localWaits >= 1);
        assertTrue(rejected.getWaitMs() >= 40,
                "waited-then-rejected should carry waitMs, got " + rejected.getWaitMs());
        assertTrue(gate.testLastRejectionWaitMsForTests() >= 40);
    }

    @Test
    void enforce_waitedAdmission_logsEvenWhenLogAdmissionsFalse() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 60_000, 0, 0, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("drain")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 59_990, 0));

        RateEstimate followEstimate = new RateEstimate(5, 8, 500);
        LlmResponse ok = gate.execute(drain, ids, config, followEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));

        assertEquals("ok", ok.getContent());
        assertEquals("wait", gate.testLastAdmissionActionForTests());
        assertTrue(gate.testSnapshot(config).localWaits >= 1);
    }

    @Test
    void enforce_immediateRejection_reportsZeroWaitMs() {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 1, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("hello world")),
                null, 0.0, 32, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");

        LlmRateLimitAdmissionException rejected = assertThrows(LlmRateLimitAdmissionException.class,
                () -> gate.execute(req, ids, config, estimate, r -> {
                    throw new AssertionError("delegate must not run");
                }));

        assertEquals(0, rejected.getWaitMs());
        assertEquals(0, gate.testLastRejectionWaitMsForTests());
    }

    @Test
    void enforce_waitsForUpstreamBlockWithinBudget() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 500, 60, 5, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("block")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmHttpFailureException http429 = new LlmHttpFailureException(
                "fail", 429, true, Collections.singletonMap("retry-after", "1"), "preview");
        assertThrows(LlmHttpFailureException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw http429;
        }));

        long start = System.nanoTime();
        LlmResponse ok = gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals("ok", ok.getContent());
        assertTrue(elapsedMs >= 900, "should wait for Retry-After block, elapsedMs=" + elapsedMs);
        assertTrue(gate.testSnapshot(config).localWaits >= 1);
    }

    @Test
    void enforce_upstreamBlockLongerThanMaxWait_rejectsWithoutWaiting() {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 500, 60, 5, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("block")),
                null, 0.0, 8, null, null);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmHttpFailureException http429 = new LlmHttpFailureException(
                "fail", 429, true, Collections.singletonMap("retry-after", "120"), "preview");
        assertThrows(LlmHttpFailureException.class, () -> gate.execute(req, ids, config, estimate, r -> {
            throw http429;
        }));

        long start = System.nanoTime();
        LlmRateLimitAdmissionException blocked = assertThrows(LlmRateLimitAdmissionException.class,
                () -> gate.execute(req, ids, config, estimate, r -> {
                    throw new AssertionError("delegate must not run");
                }));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertEquals(LlmRateLimitAdmissionReason.upstream_blocked, blocked.getReason());
        assertTrue(elapsedMs < 2_000, "block longer than maxLocalWaitMs must fail fast, elapsedMs=" + elapsedMs);
        assertEquals(0, gate.testSnapshot(config).localWaits);
    }

    @Test
    void enforce_waitTelemetryRecordsActualElapsedNotRequestedSlice() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 60_000, 0, 2, 10_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("wait-telemetry")),
                null, 0.0, 8, null, null);
        RateEstimate small = new RateEstimate(5, 8, 10);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmResponse stub = new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1);

        CountDownLatch twoInFlight = new CountDownLatch(2);
        CountDownLatch releaseHolder1 = new CountDownLatch(1);
        CountDownLatch releaseHolder2 = new CountDownLatch(1);
        AtomicReference<Exception> holderError = new AtomicReference<>();

        Thread holder1 = new Thread(() -> {
            try {
                gate.execute(req, ids, config, small, r -> {
                    twoInFlight.countDown();
                    releaseHolder1.await(30, TimeUnit.SECONDS);
                    return stub;
                });
            } catch (Exception e) {
                holderError.set(e);
            }
        });
        Thread holder2 = new Thread(() -> {
            try {
                gate.execute(req, ids, config, small, r -> {
                    twoInFlight.countDown();
                    releaseHolder2.await(30, TimeUnit.SECONDS);
                    return stub;
                });
            } catch (Exception e) {
                holderError.set(e);
            }
        });
        holder1.start();
        holder2.start();
        assertTrue(twoInFlight.await(5, TimeUnit.SECONDS), "expected two in-flight holders");

        AtomicReference<Exception> waiterError = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                gate.execute(req, ids, config, small, r -> stub);
            } catch (Exception e) {
                waiterError.set(e);
            }
        });

        long start = System.nanoTime();
        waiter.start();
        Thread.sleep(80);
        releaseHolder1.countDown();
        waiter.join(20_000);
        releaseHolder2.countDown();
        holder1.join(20_000);
        holder2.join(20_000);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertNull(holderError.get());
        assertNull(waiterError.get());
        LLMAPIProviderRateGate.GateSnapshot snap = gate.testSnapshot(config);
        assertTrue(snap.localWaits >= 1);
        assertTrue(snap.maxWaitMs < 4_000,
                "maxWaitMs must track actual elapsed wait, not full requested slices, was " + snap.maxWaitMs);
        assertTrue(snap.maxWaitMs <= elapsedMs + 300,
                "maxWaitMs=" + snap.maxWaitMs + " elapsedMs=" + elapsedMs);
        assertTrue(elapsedMs < 5_000, "waiter should admit after slot release, elapsedMs=" + elapsedMs);
    }

    @Test
    void effectiveMaxLocalWaitMs_returnsConfiguredValue() {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 500, 0, 0, 30_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        assertEquals(30_000, config.effectiveMaxLocalWaitMs());
    }

    @Test
    void enforce_uiStatus_emitsWaitingThenResumedOnBoundedTokenWait() throws Exception {
        // Also guards: after `waiting` emit outside the monitor, re-peek before `wait` must admit
        // when the bucket refills in the same pattern as enforce_boundedWait_succeedsAfterTokenRefill.
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 60_000, 0, 0, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("drain-ui")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 59_990, 0));

        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("follow-ui")),
                null, 0.0, 8, null, null, sink);
        RateEstimate followEstimate = new RateEstimate(5, 8, 500);
        LlmResponse ok = gate.execute(req, ids, config, followEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
        assertTrue(waitingFlags.contains(Boolean.TRUE), "expected waiting emit, got " + waitingFlags);
        assertTrue(waitingFlags.contains(Boolean.FALSE), "expected resumed emit, got " + waitingFlags);
    }

    @Test
    void enforce_uiStatus_emitsResumedWhenRejectedAfterPriorWaiting() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 100, 0, 0, 80,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("d-ui")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 95, 0));

        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("reject-ui")),
                null, 0.0, 8, null, null, sink);
        RateEstimate estimate = new RateEstimate(5, 8, 90);
        LlmRateLimitAdmissionException rejected = assertThrows(LlmRateLimitAdmissionException.class,
                () -> gate.execute(req, ids, config, estimate, r -> {
                    throw new AssertionError("delegate must not run");
                }));
        assertEquals(LlmRateLimitAdmissionReason.tokens_per_minute, rejected.getReason());
        assertTrue(waitingFlags.contains(Boolean.TRUE), "expected waiting emit, got " + waitingFlags);
        assertTrue(waitingFlags.contains(Boolean.FALSE), "expected resumed emit before throw, got " + waitingFlags);
    }

    @Test
    void enforce_uiStatus_probeModeDoesNotEmit() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 60_000, 0, 0, 5_000,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        LlmChatRequest drain = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("drain-probe")),
                null, 0.0, 8, null, null);
        RateEstimate drainEstimate = RateControlTokenEstimator.estimate(drain, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        gate.execute(drain, ids, config, drainEstimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 59_990, 0));

        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest base = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("probe-ui")),
                null, 0.0, 8, null, null, sink);
        LlmChatRequest probe = LlmChatRequest.copyWithProbeMode(base, true);
        LlmResponse ok = gate.execute(probe, ids, config, new RateEstimate(5, 8, 500), r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
        assertTrue(waitingFlags.isEmpty(), "probe mode must not emit UI status, got " + waitingFlags);
    }

    @Test
    void disabled_uiStatus_doesNotEmit() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.disabled, 10_000, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("disabled-ui")),
                null, 0.0, 8, null, null, sink);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmResponse ok = gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
        assertTrue(waitingFlags.isEmpty());
    }

    @Test
    void observe_uiStatus_doesNotEmit() throws Exception {
        RateControlConfig config = new RateControlConfig(
                RateControlMode.observe, 100, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false);
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P", config));
        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("observe-ui")),
                null, 0.0, 8, null, null, sink);
        RateEstimate estimate = RateControlTokenEstimator.estimate(req, config);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        LlmResponse ok = gate.execute(req, ids, config, estimate, r -> new LlmResponse(
                "ok", null, LlmResponse.FinishReason.STOP, 1, 1));
        assertEquals("ok", ok.getContent());
        assertTrue(waitingFlags.isEmpty());
    }

    @Test
    void enforce_uiStatus_singleRequestTooLarge_doesNotEmitWaiting() {
        LLMAPIProviderRateGate gate = new LLMAPIProviderRateGate(fixedOwner("P"));
        RateControlConfig config = new RateControlConfig(
                RateControlMode.enforce, 10_000, 0, 0, 5_000,
                TokenReserveStrategy.input_only, 1.0, 100, false);
        List<Boolean> waitingFlags = Collections.synchronizedList(new ArrayList<>());
        RateControlStatusSink sink = (waiting, reason, waitMs, retryAfterMs) -> waitingFlags.add(waiting);
        LlmChatRequest req = LlmChatRequest.forAgentRound(
                Collections.singletonList(ChatMessage.user("big-ui")),
                null, 0.0, 8, null, null, sink);
        LlmUsageWireIds ids = LlmUsageWireIds.forProviderThing("P", "T", "shape", "m");
        RateEstimate tooLarge = new RateEstimate(5, 8, 500);
        assertThrows(LlmRateLimitAdmissionException.class, () -> gate.execute(req, ids, config, tooLarge, r -> {
            throw new AssertionError("delegate must not run");
        }));
        assertTrue(waitingFlags.isEmpty(), "non-waitable rejection must not emit waiting, got " + waitingFlags);
    }

    private static RateGateOwner fixedOwner(String name) {
        return fixedOwner(name, new RateControlConfig(
                RateControlMode.disabled, 0, 0, 0, 0,
                TokenReserveStrategy.input_only, 1.0, 0, false));
    }

    private static RateGateOwner fixedOwner(String name, RateControlConfig config) {
        return new RateGateOwner() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public RateControlConfig loadRateControlConfig() {
                return config;
            }
        };
    }
}
