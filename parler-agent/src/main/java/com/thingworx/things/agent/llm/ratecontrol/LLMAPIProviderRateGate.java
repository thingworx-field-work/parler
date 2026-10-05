package com.thingworx.things.agent.llm.ratecontrol;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.LlmChatRequest;
import com.thingworx.things.agent.llm.LlmHttpFailureException;
import com.thingworx.things.agent.llm.LlmResponse;
import com.thingworx.things.agent.llm.LlmUsageWireIds;
import com.thingworx.types.InfoTable;

/**
 * In-memory provider rate gate ({@code docs/agent/rate-control.md}).
 */
public final class LLMAPIProviderRateGate {

    public static final String RATE_CONTROL_TABLE = "LLMAPIProviderRateControl";

    private final RateGateOwner owner;

    private double tokenLevel;
    private double requestLevel;
    private int inFlight;
    private long blockedUntilEpochMs;
    private long lastRefillNano;
    private boolean bucketsInitialized;

    private long localAdmissions;
    private long localWaits;
    private long localRejections;
    private long upstream429s;
    private long maxWaitMs;
    private LlmRateLimitAdmissionReason lastRejectionReason;
    private String lastSafeHeaderSnapshot;

    private String testLastAdmissionAction;
    private long testLastRejectionWaitMs = -1;

    public LLMAPIProviderRateGate(RateGateOwner owner) {
        this.owner = owner;
        lastRefillNano = System.nanoTime();
    }

    public LlmResponse execute(
            LlmChatRequest augmented,
            LlmUsageWireIds ids,
            RateControlConfig config,
            RateEstimate estimate,
            LlmChatDelegate delegate) throws Exception {
        if (config.mode == RateControlMode.disabled) {
            return LlmResponse.withRateGateAdmissionWait(delegate.chat(augmented), 0L);
        }
        if (config.mode == RateControlMode.observe) {
            AdmissionLog admissionLog = null;
            if (config.logAdmissions) {
                synchronized (this) {
                    ensureBucketsInitialized(config);
                    refillUnlocked(config);
                    admissionLog = new AdmissionLog(ids, config.mode, "allow", estimate, config,
                            tokenLevel, requestLevel, inFlight, 0);
                }
                if (admissionLog != null) {
                    admissionLog.emit();
                }
            }
            return LlmResponse.withRateGateAdmissionWait(delegate.chat(augmented), 0L);
        }
        RateReservation reservation = null;
        AdmissionLog admissionLog = null;
        RejectionLog rejectionLog = null;
        LlmRateLimitAdmissionException rejection = null;
        long admissionWaitMs = 0;
        RateControlStatusSink uiSink = augmented.getRateControlStatusSink();
        boolean canEmitUi = uiSink != null && !augmented.isProbeMode();
        boolean[] emittedWaitingUi = { false };
        EnforceAcquire acquire = acquireEnforceSplitForUi(augmented, config, estimate, emittedWaitingUi);
        admissionWaitMs = acquire.waitMs;
        if (!acquire.allowed) {
            PeekResult peek = acquire.rejection;
            synchronized (this) {
                ensureBucketsInitialized(config);
                refillUnlocked(config);
                localRejections++;
                lastRejectionReason = peek.reason;
                rejectionLog = new RejectionLog(ids, peek.reason, estimate, config,
                        tokenLevel, requestLevel, peek.retryAfterMs, admissionWaitMs);
            }
            rejection = new LlmRateLimitAdmissionException(
                    owner.getName(),
                    peek.reason,
                    peek.retryAfterMs,
                    admissionWaitMs,
                    estimate.estimatedInputTokens,
                    estimate.reservedTokens,
                    config.tokensPerMinuteLimit,
                    config.requestsPerMinuteLimit,
                    config.maxConcurrentRequests);
        } else {
            reservation = acquire.reservation;
            synchronized (this) {
                ensureBucketsInitialized(config);
                refillUnlocked(config);
                if (config.logAdmissions || admissionWaitMs > 0) {
                    admissionLog = new AdmissionLog(ids, config.mode,
                            admissionWaitMs > 0 ? "wait" : "allow", estimate, config,
                            tokenLevel, requestLevel, inFlight, admissionWaitMs);
                }
                localAdmissions++;
            }
        }
        if (rejection != null) {
            if (canEmitUi && emittedWaitingUi[0]) {
                safeRateControlUiEmit(uiSink, false, null, 0L, 0L);
            }
            rejectionLog.emit();
            throw rejection;
        }
        if (reservation == null) {
            throw new IllegalStateException("rate gate admission missing reservation");
        }
        final RateReservation acquired = reservation;
        if (admissionLog != null) {
            admissionLog.emit();
        }
        if (canEmitUi && emittedWaitingUi[0]) {
            safeRateControlUiEmit(uiSink, false, null, 0L, 0L);
        }
        try {
            LlmResponse response = LlmResponse.withRateGateAdmissionWait(delegate.chat(augmented), admissionWaitMs);
            synchronized (this) {
                reconcileAfterSuccessAndRelease(acquired, response, config);
            }
            return response;
        } catch (LlmHttpFailureException e) {
            synchronized (this) {
                accountHttpFailureAndRelease(acquired, e, config);
            }
            throw e;
        } catch (Exception e) {
            synchronized (this) {
                refundReservationAndRelease(acquired, config);
            }
            throw e;
        }
    }

    public InfoTable getStateInfoTable() {
        synchronized (this) {
            RateControlConfig config = owner.loadRateControlConfig();
            ensureBucketsInitialized(config);
            refillUnlocked(config);
            return RateControlInfoTables.stateRow(snapshotUnlocked(config));
        }
    }

    public InfoTable resetState() {
        synchronized (this) {
            if (inFlight > 0) {
                return RateControlInfoTables.resetResult(false,
                        "Cannot reset while upstream calls are in flight (inflight=" + inFlight + ")", inFlight);
            }
            RateControlConfig config = owner.loadRateControlConfig();
            resetBucketsForConfig(config);
            bucketsInitialized = true;
            blockedUntilEpochMs = 0;
            localAdmissions = 0;
            localWaits = 0;
            localRejections = 0;
            upstream429s = 0;
            maxWaitMs = 0;
            lastRejectionReason = null;
            lastSafeHeaderSnapshot = "";
            notifyAll();
            return RateControlInfoTables.resetResult(true, "Rate control state reset", inFlight);
        }
    }

    public InfoTable testAdmission(long estimatedInputTokens, long requestedMaxOutputTokens, boolean dryRun) {
        synchronized (this) {
            RateControlConfig config = owner.loadRateControlConfig();
            ensureBucketsInitialized(config);
            refillUnlocked(config);
            RateEstimate estimate = new RateEstimate(estimatedInputTokens, requestedMaxOutputTokens,
                    computeReservedForTest(config, estimatedInputTokens, requestedMaxOutputTokens));
            PeekResult peek = peekAcquireUnlocked(config, estimate);
            return RateControlInfoTables.testAdmissionRow(peek.allowed, peek.reason, estimate, config,
                    tokenLevel, requestLevel, inFlight, peek.retryAfterMs);
        }
    }

    private long computeReservedForTest(RateControlConfig config, long input, long requestedOut) {
        double mult = Math.max(1.0, config.estimateSafetyMultiplier);
        long scaledInput = (long) Math.ceil(input * mult);
        if (config.tokenReserveStrategy == TokenReserveStrategy.input_only) {
            return scaledInput;
        }
        return scaledInput + (long) Math.ceil(requestedOut * mult);
    }

    private void ensureBucketsInitialized(RateControlConfig config) {
        if (bucketsInitialized) {
            return;
        }
        resetBucketsForConfig(config);
        bucketsInitialized = true;
    }

    private void resetBucketsForConfig(RateControlConfig config) {
        tokenLevel = config.tokenChecksEnabled() ? config.tokensPerMinuteLimit : 0;
        requestLevel = config.requestChecksEnabled() ? config.requestsPerMinuteLimit : 0;
        inFlight = 0;
        lastRefillNano = System.nanoTime();
    }

    private void refillUnlocked(RateControlConfig config) {
        long nowMs = System.currentTimeMillis();
        if (blockedUntilEpochMs > 0 && blockedUntilEpochMs <= nowMs) {
            blockedUntilEpochMs = 0;
            notifyAll();
        }
        long nowNano = System.nanoTime();
        long deltaMs = (nowNano - lastRefillNano) / 1_000_000L;
        if (deltaMs <= 0) {
            return;
        }
        lastRefillNano = nowNano;
        if (config.tokenChecksEnabled()) {
            double rate = config.tokensPerMinuteLimit / 60000.0;
            tokenLevel = Math.min(config.tokensPerMinuteLimit, tokenLevel + rate * deltaMs);
            double floor = -config.tokensPerMinuteLimit;
            if (tokenLevel < floor) {
                tokenLevel = floor;
            }
        }
        if (config.requestChecksEnabled()) {
            double rate = config.requestsPerMinuteLimit / 60000.0;
            requestLevel = Math.min(config.requestsPerMinuteLimit, requestLevel + rate * deltaMs);
            if (requestLevel < 0) {
                requestLevel = 0;
            }
        }
    }

    private static void safeRateControlUiEmit(
            RateControlStatusSink sink, boolean waiting, LlmRateLimitAdmissionReason reason, long waitMs, long retryAfter) {
        if (sink == null) {
            return;
        }
        try {
            sink.emit(waiting, reason, waitMs, retryAfter);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Enforce-mode admission with optional UI status emission outside the gate monitor ({@code docs/agent/rate-control-ui-status.md}).
     */
    private EnforceAcquire acquireEnforceSplitForUi(
            LlmChatRequest augmented,
            RateControlConfig config,
            RateEstimate estimate,
            boolean[] emittedWaitingUi) {
        RateControlStatusSink sink = augmented.getRateControlStatusSink();
        boolean canEmitUi = sink != null && !augmented.isProbeMode();
        long totalWaitMs = 0;
        long deadlineMs = config.maxLocalWaitMs > 0
                ? System.currentTimeMillis() + config.maxLocalWaitMs
                : Long.MAX_VALUE;
        while (true) {
            PeekResult peek;
            long slice;
            synchronized (this) {
                ensureBucketsInitialized(config);
                refillUnlocked(config);
                peek = peekAcquireUnlocked(config, estimate);
                if (peek.allowed) {
                    long reserved = estimate.reservedTokens;
                    if (config.tokenChecksEnabled()) {
                        tokenLevel -= reserved;
                    }
                    if (config.requestChecksEnabled()) {
                        requestLevel -= 1;
                    }
                    inFlight++;
                    RateReservation reservation = new RateReservation(reserved, 1, config.tokenReserveStrategy);
                    return finishEnforceAcquire(EnforceAcquire.allowed(reservation, totalWaitMs));
                }
                if (config.maxLocalWaitMs <= 0 || !isWaitableRejection(peek, config)) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                long now = System.currentTimeMillis();
                if (now >= deadlineMs) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                slice = computeWaitSliceMs(peek, config, estimate, deadlineMs - now);
                if (slice <= 0) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
            }
            if (canEmitUi && slice > 0 && !emittedWaitingUi[0]) {
                safeRateControlUiEmit(sink, true, peek.reason, slice, peek.retryAfterMs);
                emittedWaitingUi[0] = true;
            }
            synchronized (this) {
                ensureBucketsInitialized(config);
                refillUnlocked(config);
                peek = peekAcquireUnlocked(config, estimate);
                if (peek.allowed) {
                    long reserved = estimate.reservedTokens;
                    if (config.tokenChecksEnabled()) {
                        tokenLevel -= reserved;
                    }
                    if (config.requestChecksEnabled()) {
                        requestLevel -= 1;
                    }
                    inFlight++;
                    RateReservation reservation = new RateReservation(reserved, 1, config.tokenReserveStrategy);
                    return finishEnforceAcquire(EnforceAcquire.allowed(reservation, totalWaitMs));
                }
                if (config.maxLocalWaitMs <= 0 || !isWaitableRejection(peek, config)) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                long now = System.currentTimeMillis();
                if (now >= deadlineMs) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                slice = computeWaitSliceMs(peek, config, estimate, deadlineMs - now);
                if (slice <= 0) {
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                long waitStartNano = System.nanoTime();
                try {
                    wait(slice);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return finishEnforceAcquire(EnforceAcquire.denied(peek, totalWaitMs));
                }
                long actualMs = Math.max(0L, (System.nanoTime() - waitStartNano) / 1_000_000L);
                totalWaitMs += actualMs;
                if (actualMs > 0) {
                    localWaits++;
                }
            }
        }
    }

    private EnforceAcquire finishEnforceAcquire(EnforceAcquire result) {
        recordMaxAdmissionWait(result.waitMs);
        return result;
    }

    private void recordMaxAdmissionWait(long admissionWaitMs) {
        if (admissionWaitMs > maxWaitMs) {
            maxWaitMs = admissionWaitMs;
        }
    }

    private static boolean isWaitableRejection(PeekResult peek, RateControlConfig config) {
        if (peek.reason == LlmRateLimitAdmissionReason.single_request_too_large) {
            return false;
        }
        if (peek.reason == LlmRateLimitAdmissionReason.upstream_blocked
                && config.maxLocalWaitMs > 0
                && peek.retryAfterMs > config.maxLocalWaitMs) {
            return false;
        }
        return true;
    }

    private long computeWaitSliceMs(
            PeekResult peek,
            RateControlConfig config,
            RateEstimate estimate,
            long remainingMs) {
        if (remainingMs <= 0) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long hintMs;
        if (peek.reason == LlmRateLimitAdmissionReason.upstream_blocked) {
            hintMs = Math.max(1L, blockedUntilEpochMs - now);
        } else if (peek.reason == LlmRateLimitAdmissionReason.tokens_per_minute) {
            hintMs = retryMsForTokens(config, estimate.reservedTokens, tokenLevel);
        } else if (peek.reason == LlmRateLimitAdmissionReason.requests_per_minute) {
            hintMs = retryMsForRequests(config, requestLevel);
        } else {
            hintMs = 50L;
        }
        return Math.min(remainingMs, Math.max(1L, hintMs));
    }

    private PeekResult peekAcquireUnlocked(RateControlConfig config, RateEstimate estimate) {
        long now = System.currentTimeMillis();
        if (blockedUntilEpochMs > now) {
            return PeekResult.denied(
                    LlmRateLimitAdmissionReason.upstream_blocked,
                    blockedUntilEpochMs - now);
        }
        int capSingle = config.effectiveMaxSingleRequestTokens();
        if (capSingle > 0 && estimate.reservedTokens > capSingle) {
            return PeekResult.denied(LlmRateLimitAdmissionReason.single_request_too_large, 0);
        }
        long reserved = estimate.reservedTokens;
        if (config.tokenChecksEnabled() && tokenLevel < reserved) {
            return PeekResult.denied(LlmRateLimitAdmissionReason.tokens_per_minute,
                    retryMsForTokens(config, reserved, tokenLevel));
        }
        if (config.requestChecksEnabled() && requestLevel < 1) {
            return PeekResult.denied(LlmRateLimitAdmissionReason.requests_per_minute,
                    retryMsForRequests(config, requestLevel));
        }
        if (config.concurrencyChecksEnabled() && inFlight >= config.maxConcurrentRequests) {
            return PeekResult.denied(LlmRateLimitAdmissionReason.concurrency, 0);
        }
        return PeekResult.allowed();
    }

    private static long retryMsForTokens(RateControlConfig config, long reservedTokens, double currentLevel) {
        if (config.tokenChecksEnabled() && currentLevel < reservedTokens) {
            double deficit = reservedTokens - currentLevel;
            double rate = config.tokensPerMinuteLimit / 60000.0;
            return Math.max(1L, (long) Math.ceil(deficit / rate));
        }
        return 1000L;
    }

    private static long retryMsForRequests(RateControlConfig config, double currentRequestLevel) {
        if (config.requestChecksEnabled() && currentRequestLevel < 1) {
            double rate = config.requestsPerMinuteLimit / 60000.0;
            return Math.max(1L, (long) Math.ceil(1.0 / rate));
        }
        return 1000L;
    }

    private void reconcileAfterSuccessAndRelease(RateReservation reservation, LlmResponse response, RateControlConfig config) {
        try {
            long actual = actualDebit(reservation.strategy, response);
            long delta = actual - reservation.reservedTokens;
            if (config.tokenChecksEnabled() && delta != 0) {
                tokenLevel -= delta;
                double floor = -config.tokensPerMinuteLimit;
                if (tokenLevel < floor) {
                    tokenLevel = floor;
                }
            }
        } catch (Exception e) {
            warnSafe("Rate gate reconcile error provider=" + owner.getName() + ": " + e.getMessage());
        } finally {
            releaseInFlightUnlocked();
        }
    }

    private void accountHttpFailureAndRelease(
            RateReservation reservation,
            LlmHttpFailureException e,
            RateControlConfig config) {
        try {
            if (e.isRateLimited()) {
                upstream429s++;
                blockedUntilEpochMs = RateLimitHeaderParser.blockedUntilEpochMs(
                        headersFromMap(e.getSafeHeaders()));
                lastSafeHeaderSnapshot = RateLimitHeaderParser.formatSafeHeaderSnapshot(e.getSafeHeaders());
                if (config.tokenChecksEnabled()) {
                    tokenLevel = 0;
                }
                if (config.requestChecksEnabled()) {
                    requestLevel = 0;
                }
                notifyAll();
            } else {
                refundReservationUnlocked(reservation, config);
            }
        } catch (Exception ex) {
            warnSafe("Rate gate HTTP failure accounting error provider=" + owner.getName() + ": " + ex.getMessage());
            refundReservationUnlocked(reservation, config);
        } finally {
            releaseInFlightUnlocked();
        }
    }

    private void refundReservationAndRelease(RateReservation reservation, RateControlConfig config) {
        try {
            refundReservationUnlocked(reservation, config);
        } finally {
            releaseInFlightUnlocked();
        }
    }

    private void refundReservationUnlocked(RateReservation reservation, RateControlConfig config) {
        if (config.tokenChecksEnabled()) {
            tokenLevel += reservation.reservedTokens;
            if (tokenLevel > config.tokensPerMinuteLimit) {
                tokenLevel = config.tokensPerMinuteLimit;
            }
        }
        if (config.requestChecksEnabled()) {
            requestLevel += reservation.reservedRequests;
            if (requestLevel > config.requestsPerMinuteLimit) {
                requestLevel = config.requestsPerMinuteLimit;
            }
        }
    }

    private void releaseInFlightUnlocked() {
        if (inFlight > 0) {
            inFlight--;
        }
        notifyAll();
    }

    private static long actualDebit(TokenReserveStrategy strategy, LlmResponse response) {
        long input = Math.max(0, response.getPromptTokens());
        long output = Math.max(0, response.getCompletionTokens());
        if (strategy == TokenReserveStrategy.input_only) {
            return input;
        }
        return input + output;
    }

    private static org.apache.http.Header[] headersFromMap(java.util.Map<String, String> map) {
        if (map == null || map.isEmpty()) {
            return new org.apache.http.Header[0];
        }
        org.apache.http.Header[] out = new org.apache.http.Header[map.size()];
        int i = 0;
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            out[i++] = new org.apache.http.message.BasicHeader(e.getKey(), e.getValue());
        }
        return out;
    }

    /** Package-private for unit tests (observe-mode bucket init). */
    boolean testBucketsInitializedForTests() {
        return bucketsInitialized;
    }

    /** Package-private for unit tests (observe-mode bucket init). */
    double testTokenLevelUnlocked() {
        return tokenLevel;
    }

    /** Package-private for unit tests (last {@code LLM_RATE_ADMISSION} action, or null). */
    String testLastAdmissionActionForTests() {
        return testLastAdmissionAction;
    }

    /** Package-private for unit tests (last {@code LLM_RATE_REJECTION} waitMs, or -1). */
    long testLastRejectionWaitMsForTests() {
        return testLastRejectionWaitMs;
    }

    /** Package-private for unit tests. */
    GateSnapshot testSnapshot(RateControlConfig config) {
        synchronized (this) {
            ensureBucketsInitialized(config);
            refillUnlocked(config);
            return snapshotUnlocked(config);
        }
    }

    GateSnapshot snapshotUnlocked(RateControlConfig config) {
        long now = System.currentTimeMillis();
        long activeBlock = blockedUntilEpochMs > now ? blockedUntilEpochMs : 0;
        long retry = activeBlock > 0 ? activeBlock - now : 0;
        return new GateSnapshot(
                config.mode,
                config.tokenReserveStrategy,
                config.tokensPerMinuteLimit,
                config.requestsPerMinuteLimit,
                config.maxConcurrentRequests,
                config.maxLocalWaitMs,
                config.maxSingleRequestTokens,
                config.estimateSafetyMultiplier,
                tokenLevel,
                requestLevel,
                inFlight,
                activeBlock,
                retry,
                lastRejectionReason,
                localAdmissions,
                localWaits,
                localRejections,
                upstream429s,
                maxWaitMs,
                lastSafeHeaderSnapshot);
    }

    /** Immutable gate state for services. */
    public static final class GateSnapshot {
        public final RateControlMode mode;
        public final TokenReserveStrategy tokenReserveStrategy;
        public final int tokensPerMinuteLimit;
        public final int requestsPerMinuteLimit;
        public final int maxConcurrentRequests;
        public final int maxLocalWaitMs;
        public final int maxSingleRequestTokens;
        public final double estimateSafetyMultiplier;
        public final double tokensRemaining;
        public final double requestsRemaining;
        public final int inflight;
        public final long blockedUntilEpochMs;
        public final long retryAfterMs;
        public final LlmRateLimitAdmissionReason lastRejectionReason;
        public final long localAdmissions;
        public final long localWaits;
        public final long localRejections;
        public final long upstream429s;
        public final long maxWaitMs;
        public final String lastSafeHeaderSnapshot;

        GateSnapshot(
                RateControlMode mode,
                TokenReserveStrategy tokenReserveStrategy,
                int tokensPerMinuteLimit,
                int requestsPerMinuteLimit,
                int maxConcurrentRequests,
                int maxLocalWaitMs,
                int maxSingleRequestTokens,
                double estimateSafetyMultiplier,
                double tokensRemaining,
                double requestsRemaining,
                int inflight,
                long blockedUntilEpochMs,
                long retryAfterMs,
                LlmRateLimitAdmissionReason lastRejectionReason,
                long localAdmissions,
                long localWaits,
                long localRejections,
                long upstream429s,
                long maxWaitMs,
                String lastSafeHeaderSnapshot) {
            this.mode = mode;
            this.tokenReserveStrategy = tokenReserveStrategy;
            this.tokensPerMinuteLimit = tokensPerMinuteLimit;
            this.requestsPerMinuteLimit = requestsPerMinuteLimit;
            this.maxConcurrentRequests = maxConcurrentRequests;
            this.maxLocalWaitMs = maxLocalWaitMs;
            this.maxSingleRequestTokens = maxSingleRequestTokens;
            this.estimateSafetyMultiplier = estimateSafetyMultiplier;
            this.tokensRemaining = tokensRemaining;
            this.requestsRemaining = requestsRemaining;
            this.inflight = inflight;
            this.blockedUntilEpochMs = blockedUntilEpochMs;
            this.retryAfterMs = retryAfterMs;
            this.lastRejectionReason = lastRejectionReason;
            this.localAdmissions = localAdmissions;
            this.localWaits = localWaits;
            this.localRejections = localRejections;
            this.upstream429s = upstream429s;
            this.maxWaitMs = maxWaitMs;
            this.lastSafeHeaderSnapshot = lastSafeHeaderSnapshot;
        }
    }

    private static final class EnforceAcquire {
        final boolean allowed;
        final RateReservation reservation;
        final long waitMs;
        final PeekResult rejection;

        private EnforceAcquire(boolean allowed, RateReservation reservation, long waitMs, PeekResult rejection) {
            this.allowed = allowed;
            this.reservation = reservation;
            this.waitMs = waitMs;
            this.rejection = rejection;
        }

        static EnforceAcquire allowed(RateReservation reservation, long waitMs) {
            return new EnforceAcquire(true, reservation, waitMs, null);
        }

        static EnforceAcquire denied(PeekResult rejection, long waitMs) {
            return new EnforceAcquire(false, null, waitMs, rejection);
        }
    }

    private static final class PeekResult {
        final boolean allowed;
        final LlmRateLimitAdmissionReason reason;
        final long retryAfterMs;

        private PeekResult(boolean allowed, LlmRateLimitAdmissionReason reason, long retryAfterMs) {
            this.allowed = allowed;
            this.reason = reason;
            this.retryAfterMs = retryAfterMs;
        }

        static PeekResult allowed() {
            return new PeekResult(true, null, 0);
        }

        static PeekResult denied(LlmRateLimitAdmissionReason reason, long retryAfterMs) {
            return new PeekResult(false, reason, retryAfterMs);
        }
    }

    /** Delegate upstream chat after local admission. */
    @FunctionalInterface
    public interface LlmChatDelegate {
        LlmResponse chat(LlmChatRequest augmented) throws Exception;
    }

    private static Logger applicationLogger() {
        try {
            return LogUtilities.getInstance().getApplicationLogger(LLMAPIProviderRateGate.class);
        } catch (Throwable t) {
            return null;
        }
    }

    private static void warnSafe(String message) {
        Logger log = applicationLogger();
        if (log != null) {
            log.warn(message);
        }
    }

    private final class AdmissionLog {
        private final LlmUsageWireIds ids;
        private final RateControlMode mode;
        private final String action;
        private final RateEstimate estimate;
        private final RateControlConfig config;
        private final double tokensRemaining;
        private final double requestsRemaining;
        private final int inflight;
        private final long waitMs;

        private AdmissionLog(
                LlmUsageWireIds ids,
                RateControlMode mode,
                String action,
                RateEstimate estimate,
                RateControlConfig config,
                double tokensRemaining,
                double requestsRemaining,
                int inflight,
                long waitMs) {
            this.ids = ids;
            this.mode = mode;
            this.action = action;
            this.estimate = estimate;
            this.config = config;
            this.tokensRemaining = tokensRemaining;
            this.requestsRemaining = requestsRemaining;
            this.inflight = inflight;
            this.waitMs = waitMs;
        }

        private void emit() {
            testLastAdmissionAction = action;
            LlmRateControlTelemetry.logAdmission(applicationLogger(), ids, mode, action, estimate, config,
                    tokensRemaining, requestsRemaining, inflight, waitMs);
        }
    }

    private final class RejectionLog {
        private final LlmUsageWireIds ids;
        private final LlmRateLimitAdmissionReason reason;
        private final RateEstimate estimate;
        private final RateControlConfig config;
        private final double tokensRemaining;
        private final double requestsRemaining;
        private final long retryAfterMs;
        private final long waitMs;

        private RejectionLog(
                LlmUsageWireIds ids,
                LlmRateLimitAdmissionReason reason,
                RateEstimate estimate,
                RateControlConfig config,
                double tokensRemaining,
                double requestsRemaining,
                long retryAfterMs,
                long waitMs) {
            this.ids = ids;
            this.reason = reason;
            this.estimate = estimate;
            this.config = config;
            this.tokensRemaining = tokensRemaining;
            this.requestsRemaining = requestsRemaining;
            this.retryAfterMs = retryAfterMs;
            this.waitMs = waitMs;
        }

        private void emit() {
            testLastRejectionWaitMs = waitMs;
            LlmRateControlTelemetry.logRejection(applicationLogger(), ids, reason, estimate, config,
                    tokensRemaining, requestsRemaining, retryAfterMs, waitMs);
        }
    }
}
