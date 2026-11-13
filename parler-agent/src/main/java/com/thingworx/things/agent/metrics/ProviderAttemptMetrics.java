package com.thingworx.things.agent.metrics;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.thingworx.things.agent.llm.usage.LlmCallKind;
import com.thingworx.things.agent.llm.usage.LlmUsageCapture;
import com.thingworx.things.agent.llm.usage.LlmUsageCapture.OpenAiRawDetail;
import com.thingworx.things.agent.llm.usage.LlmUsageSnapshot;

/**
 * Metrics state for one dispatched provider HTTP attempt, owned by a single {@code LlmRecordedHttpChat} wrapper
 * invocation and independent of the {@code LlmCallRecorder} attempt and Stream writes. The first terminal
 * classification wins; later calls (for example an outer exception handler) are ignored.
 */
public final class ProviderAttemptMetrics {

    static final String INPUT_TOKENS_TOTAL = "inputTokensTotal";
    static final String OUTPUT_TOKENS_TOTAL = "outputTokensTotal";

    /** Token kinds sourced from {@code LlmUsageSnapshot} normalized fields, in emission order. */
    static final Map<TokenKind, String> NORMALIZED_KINDS = normalizedKinds();

    /** Token kinds sourced from the parser's allowlisted OpenAI-shaped raw detail fields, in emission order. */
    static final Map<TokenKind, OpenAiRawDetail> RAW_KINDS = rawKinds();

    private static Map<TokenKind, String> normalizedKinds() {
        Map<TokenKind, String> m = new LinkedHashMap<>();
        m.put(TokenKind.INPUT, INPUT_TOKENS_TOTAL);
        m.put(TokenKind.OUTPUT, OUTPUT_TOKENS_TOTAL);
        m.put(TokenKind.TOTAL, "totalTokens");
        m.put(TokenKind.INPUT_UNCACHED, "inputTokensUncached");
        m.put(TokenKind.INPUT_CACHE_READ, "inputTokensCacheRead");
        m.put(TokenKind.INPUT_CACHE_WRITE, "inputTokensCacheWrite");
        m.put(TokenKind.INPUT_CACHE_WRITE_5M, "cacheWrite5mTokens");
        m.put(TokenKind.INPUT_CACHE_WRITE_1H, "cacheWrite1hTokens");
        m.put(TokenKind.REASONING, "reasoningTokens");
        return Collections.unmodifiableMap(m);
    }

    private static Map<TokenKind, OpenAiRawDetail> rawKinds() {
        Map<TokenKind, OpenAiRawDetail> m = new LinkedHashMap<>();
        m.put(TokenKind.INPUT_AUDIO, OpenAiRawDetail.PROMPT_AUDIO_TOKENS);
        m.put(TokenKind.OUTPUT_AUDIO, OpenAiRawDetail.COMPLETION_AUDIO_TOKENS);
        m.put(TokenKind.OUTPUT_ACCEPTED_PREDICTION, OpenAiRawDetail.COMPLETION_ACCEPTED_PREDICTION_TOKENS);
        m.put(TokenKind.OUTPUT_REJECTED_PREDICTION, OpenAiRawDetail.COMPLETION_REJECTED_PREDICTION_TOKENS);
        return Collections.unmodifiableMap(m);
    }

    private final ParlerMetricsSink sink;
    private final ProviderFamily family;
    private final String apiShapeId;
    private final String callKind;
    private final LongSupplier nanoClock;
    private final long startNanos;
    private final AtomicBoolean finished = new AtomicBoolean();
    private volatile long stopNanos = Long.MIN_VALUE;

    public ProviderAttemptMetrics(ParlerMetricsSink sink, String apiShapeId, LlmCallKind callKind,
            LongSupplier nanoClock) {
        this.sink = sink != null ? sink : ParlerMetricsSink.NOOP;
        this.family = ProviderFamily.fromApiShapeId(apiShapeId);
        this.apiShapeId = apiShapeId;
        this.callKind = (callKind != null ? callKind : LlmCallKind.AGENT_ROUND).wireValue();
        this.nanoClock = nanoClock != null ? nanoClock : System::nanoTime;
        this.startNanos = this.nanoClock.getAsLong();
    }

    /** Stops the attempt timer at HTTP return; HTTP and parse errors keep this elapsed time. */
    public void responseReceived() {
        if (stopNanos == Long.MIN_VALUE) {
            stopNanos = nanoClock.getAsLong();
        }
    }

    public boolean isFinished() {
        return finished.get();
    }

    /**
     * Records the terminal outcome, one usage sample, and each valid token kind once: normalized fields, then the
     * allowlisted OpenAI-shaped raw detail fields. {@code INVALID} usage contributes no token kinds. One-shot.
     */
    public void finish(ProviderOutcome outcome, LlmUsageSnapshot usage) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        long end = stopNanos != Long.MIN_VALUE ? stopNanos : nanoClock.getAsLong();
        long durationMs = Math.max(0L, (end - startNanos) / 1_000_000L);
        LlmUsageSnapshot u = usage != null ? usage : LlmUsageSnapshot.unavailable();
        sink.recordLlmAttempt(family, callKind, outcome != null ? outcome : ProviderOutcome.TRANSPORT_ERROR,
                durationMs);
        sink.recordLlmUsageSample(family, callKind, u.getStatus().wireValue());
        for (Map.Entry<TokenKind, String> kind : NORMALIZED_KINDS.entrySet()) {
            long tokens = validTokens(u, kind.getValue());
            if (tokens >= 0) {
                sink.addLlmTokens(family, callKind, kind.getKey(), tokens);
            }
        }
        if (!acceptedStatus(u)) {
            return;
        }
        for (Map.Entry<TokenKind, OpenAiRawDetail> kind : RAW_KINDS.entrySet()) {
            Long tokens = LlmUsageCapture.openAiRawDetail(u, apiShapeId, kind.getValue());
            if (tokens != null) {
                sink.addLlmTokens(family, callKind, kind.getKey(), tokens);
            }
        }
    }

    private static boolean acceptedStatus(LlmUsageSnapshot usage) {
        LlmUsageSnapshot.UsageStatus status = usage.getStatus();
        return status == LlmUsageSnapshot.UsageStatus.COMPLETE || status == LlmUsageSnapshot.UsageStatus.PARTIAL;
    }

    /** Transport failure with unavailable usage: {@code timeout} under the wrapper's timeout rule, else transport. */
    public void finishTransportFailure(boolean timeout) {
        finish(timeout ? ProviderOutcome.TIMEOUT : ProviderOutcome.TRANSPORT_ERROR, LlmUsageSnapshot.unavailable());
    }

    /**
     * Valid normalized count, or {@code -1}: the snapshot must be {@code COMPLETE} or {@code PARTIAL} and the field
     * {@code REPORTED} or {@code DERIVED} with a non-negative value.
     */
    static long validTokens(LlmUsageSnapshot usage, String key) {
        if (usage == null) {
            return -1;
        }
        if (!acceptedStatus(usage)) {
            return -1;
        }
        LlmUsageSnapshot.FieldPresence presence = usage.getPresence().get(key);
        if (presence != LlmUsageSnapshot.FieldPresence.REPORTED && presence != LlmUsageSnapshot.FieldPresence.DERIVED) {
            return -1;
        }
        Long value = usage.getNormalized().get(key);
        return value != null && value >= 0 ? value : -1;
    }
}
