package com.thingworx.things.agent.metrics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Registers every Parler instrument once through a {@link MetricsRegistrar} and caches the handles. Any registration
 * failure, including after partial registration, propagates to {@link ParlerMetrics}, which latches the no-op sink.
 *
 * <p>The in-flight gauge is registered last, and its callback reports points only after every registration has
 * succeeded. A gauge that the platform keeps after a later failure (the registry is JVM-wide and has no removal)
 * therefore observes no turns.
 */
public final class RegisteredParlerMetricsSink implements ParlerMetricsSink {

    public static final String TURNS_TOTAL = "parler_turns_total";
    public static final String TURN_DURATION_MS = "parler_turn_duration_ms";
    public static final String TURNS_IN_FLIGHT = "parler_turns_in_flight";
    public static final String LLM_ATTEMPTS_TOTAL = "parler_llm_attempts_total";
    public static final String LLM_ATTEMPT_DURATION_MS = "parler_llm_attempt_duration_ms";
    public static final String LLM_TOKENS_TOTAL = "parler_llm_tokens_total";
    public static final String LLM_USAGE_SAMPLES_TOTAL = "parler_llm_usage_samples_total";
    public static final String TOOL_EXECUTIONS_TOTAL = "parler_tool_executions_total";
    public static final String TOOL_DURATION_MS = "parler_tool_duration_ms";

    public static final String ENTRY_POINT = "entry_point";
    public static final String OUTCOME = "outcome";
    public static final String PROVIDER_FAMILY = "provider_family";
    public static final String CALL_KIND = "call_kind";
    public static final String TOKEN_KIND = "token_kind";
    public static final String USAGE_STATUS = "usage_status";
    public static final String TOOL_KIND = "tool_kind";

    public static final List<Double> TURN_AND_PROVIDER_BUCKETS = List.of(
            10d, 50d, 100d, 250d, 500d, 1_000d, 2_500d, 5_000d, 10_000d, 30_000d, 60_000d);
    public static final List<Double> TOOL_BUCKETS = List.of(
            1d, 5d, 10d, 25d, 50d, 100d, 250d, 500d, 1_000d, 2_500d, 5_000d, 10_000d, 30_000d);

    private final MetricsRegistrar.Counter turnsTotal;
    private final MetricsRegistrar.Histogram turnDuration;
    private final MetricsRegistrar.Counter llmAttemptsTotal;
    private final MetricsRegistrar.Histogram llmAttemptDuration;
    private final MetricsRegistrar.Counter llmTokensTotal;
    private final MetricsRegistrar.Counter llmUsageSamplesTotal;
    private final MetricsRegistrar.Counter toolExecutionsTotal;
    private final MetricsRegistrar.Histogram toolDuration;
    private final AtomicBoolean registrationComplete = new AtomicBoolean();

    private RegisteredParlerMetricsSink(MetricsRegistrar registrar, TurnInFlight inFlight) {
        Set<String> turnKeys = keys(ENTRY_POINT, OUTCOME);
        Set<String> llmKeys = keys(PROVIDER_FAMILY, CALL_KIND, OUTCOME);
        Set<String> toolKeys = keys(TOOL_KIND, OUTCOME);
        turnsTotal = registrar.counter(TURNS_TOTAL,
                "Completed execution segments of admitted Parler turns", "1", turnKeys);
        turnDuration = registrar.histogram(TURN_DURATION_MS,
                "Admitted Parler turn segment duration", "ms", TURN_AND_PROVIDER_BUCKETS, turnKeys);
        llmAttemptsTotal = registrar.counter(LLM_ATTEMPTS_TOTAL,
                "Dispatched LLM provider attempts by terminal outcome", "1", llmKeys);
        llmAttemptDuration = registrar.histogram(LLM_ATTEMPT_DURATION_MS,
                "LLM provider HTTP attempt duration", "ms", TURN_AND_PROVIDER_BUCKETS, llmKeys);
        llmTokensTotal = registrar.counter(LLM_TOKENS_TOTAL,
                "LLM provider token counts by token kind; kinds overlap and are not additive", "1",
                keys(PROVIDER_FAMILY, CALL_KIND, TOKEN_KIND));
        llmUsageSamplesTotal = registrar.counter(LLM_USAGE_SAMPLES_TOTAL,
                "LLM usage capture status per dispatched attempt", "1",
                keys(PROVIDER_FAMILY, CALL_KIND, USAGE_STATUS));
        toolExecutionsTotal = registrar.counter(TOOL_EXECUTIONS_TOTAL,
                "Completed Parler tool executions", "1", toolKeys);
        toolDuration = registrar.histogram(TOOL_DURATION_MS,
                "Parler tool execution duration", "ms", TOOL_BUCKETS, toolKeys);
        registrar.asyncGauge(TURNS_IN_FLIGHT,
                "Parler turn segments currently executing on this node", "1", keys(ENTRY_POINT),
                () -> registrationComplete.get() ? gaugePoints(inFlight) : List.of());
        registrationComplete.set(true);
    }

    /** Registers all instruments; throws on any registration failure. */
    public static RegisteredParlerMetricsSink register(MetricsRegistrar registrar, TurnInFlight inFlight) {
        return new RegisteredParlerMetricsSink(registrar, inFlight);
    }

    /** One gauge point per entry point, including zero. */
    static List<MetricsRegistrar.GaugePoint> gaugePoints(TurnInFlight inFlight) {
        List<MetricsRegistrar.GaugePoint> points = new ArrayList<>();
        for (TurnEntryPoint ep : TurnEntryPoint.values()) {
            points.add(new MetricsRegistrar.GaugePoint(inFlight.current(ep), attrs(ENTRY_POINT, ep.wireValue())));
        }
        return points;
    }

    @Override
    public void recordTurn(TurnEntryPoint entryPoint, TurnOutcome outcome, long durationMs) {
        Map<String, String> a = attrs(ENTRY_POINT, entryPoint.wireValue(), OUTCOME, outcome.wireValue());
        turnsTotal.add(1, a);
        turnDuration.record(durationMs, a);
    }

    @Override
    public void recordLlmAttempt(ProviderFamily family, String callKind, ProviderOutcome outcome, long durationMs) {
        Map<String, String> a = attrs(PROVIDER_FAMILY, family.wireValue(), CALL_KIND, callKind,
                OUTCOME, outcome.wireValue());
        llmAttemptsTotal.add(1, a);
        llmAttemptDuration.record(durationMs, a);
    }

    @Override
    public void addLlmTokens(ProviderFamily family, String callKind, TokenKind tokenKind, long tokens) {
        llmTokensTotal.add(tokens, attrs(PROVIDER_FAMILY, family.wireValue(), CALL_KIND, callKind,
                TOKEN_KIND, tokenKind.wireValue()));
    }

    @Override
    public void recordLlmUsageSample(ProviderFamily family, String callKind, String usageStatus) {
        llmUsageSamplesTotal.add(1, attrs(PROVIDER_FAMILY, family.wireValue(), CALL_KIND, callKind,
                USAGE_STATUS, usageStatus));
    }

    @Override
    public void recordTool(ToolKind kind, ToolOutcome outcome, long durationMs) {
        Map<String, String> a = attrs(TOOL_KIND, kind.wireValue(), OUTCOME, outcome.wireValue());
        toolExecutionsTotal.add(1, a);
        toolDuration.record(durationMs, a);
    }

    private static Set<String> keys(String... keys) {
        return Collections.unmodifiableSet(new LinkedHashSet<>(List.of(keys)));
    }

    private static Map<String, String> attrs(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }
}
