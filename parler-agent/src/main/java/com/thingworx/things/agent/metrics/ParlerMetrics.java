package com.thingworx.things.agent.metrics;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.agent.llm.usage.LlmCallKind;

/**
 * Parler operational metrics facade (design: {@code docs/agent/metrics-support.md}). Call sites use only this class
 * and the bounded dimension enums; the ThingWorx MetricsV2 types stay behind {@link MetricsV2Registrar}.
 *
 * <p>The facade registers all instruments on first use, once per extension runtime. A registration failure, including
 * after partial registration or on a platform without MetricsV2, logs one diagnostic and latches the no-op sink.
 * Recording failures never reach agent execution and log at most one diagnostic.
 */
public final class ParlerMetrics {

    private static final Logger LOG = LogUtilities.getInstance().getApplicationLogger(ParlerMetrics.class);
    private static final Object INIT_LOCK = new Object();
    private static final AtomicBoolean RECORDING_FAILURE_LOGGED = new AtomicBoolean();

    private static volatile ParlerMetricsSink active;
    private static volatile MetricsRegistrar registrarOverride;

    private ParlerMetrics() {}

    /** Starts one admitted turn segment; close it once in {@code finally}. Never throws. */
    public static ParlerTurnScope startTurn(TurnEntryPoint entryPoint) {
        return new ParlerTurnScope(entryPoint, sink(), TurnInFlight.SHARED, System::nanoTime);
    }

    /** Starts one tool observation; close it once in {@code finally}. Never throws. */
    public static ParlerToolScope startTool(ToolKind kind) {
        return new ParlerToolScope(kind, sink(), System::nanoTime);
    }

    /** Starts metrics for one dispatched provider attempt. Never throws. */
    public static ProviderAttemptMetrics startProviderAttempt(String apiShapeId, LlmCallKind callKind) {
        return new ProviderAttemptMetrics(sink(), apiShapeId, callKind, System::nanoTime);
    }

    /** The isolating recording sink, registering instruments on first use. */
    static ParlerMetricsSink sink() {
        ParlerMetricsSink s = active;
        if (s != null) {
            return s;
        }
        synchronized (INIT_LOCK) {
            if (active != null) {
                return active;
            }
            MetricsRegistrar registrar = registrarOverride;
            try {
                if (registrar == null) {
                    registrar = new MetricsV2Registrar();
                }
                if (!registrar.platformReady()) {
                    return new IsolatingSink(ParlerMetricsSink.NOOP);
                }
                active = new IsolatingSink(RegisteredParlerMetricsSink.register(registrar, TurnInFlight.SHARED));
            } catch (Throwable t) {
                LOG.warn("Parler metrics disabled for this extension runtime: instrument registration failed ({}: {})",
                        t.getClass().getSimpleName(), t.getMessage());
                active = new IsolatingSink(ParlerMetricsSink.NOOP);
            }
            return active;
        }
    }

    static void reportRecordingFailure(Throwable t) {
        if (RECORDING_FAILURE_LOGGED.compareAndSet(false, true)) {
            LOG.warn("Parler metrics recording failed; further recording failures are suppressed ({}: {})",
                    t.getClass().getSimpleName(), t.getMessage());
        }
    }

    /** Test hook: record into {@code sink} (wrapped in the production isolation layer). */
    public static void installSinkForTest(ParlerMetricsSink sink) {
        synchronized (INIT_LOCK) {
            registrarOverride = null;
            active = new IsolatingSink(sink != null ? sink : ParlerMetricsSink.NOOP);
        }
    }

    /** Test hook: register through {@code registrar} on next use instead of MetricsV2. */
    public static void installRegistrarForTest(MetricsRegistrar registrar) {
        synchronized (INIT_LOCK) {
            registrarOverride = registrar;
            active = null;
        }
    }

    public static void resetForTest() {
        synchronized (INIT_LOCK) {
            registrarOverride = null;
            active = null;
            RECORDING_FAILURE_LOGGED.set(false);
        }
    }

    static boolean recordingFailureLoggedForTest() {
        return RECORDING_FAILURE_LOGGED.get();
    }

    /** Swallows every recording failure so metrics never change agent results. */
    static final class IsolatingSink implements ParlerMetricsSink {
        private final ParlerMetricsSink delegate;

        IsolatingSink(ParlerMetricsSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void recordTurn(TurnEntryPoint entryPoint, TurnOutcome outcome, long durationMs) {
            try {
                delegate.recordTurn(entryPoint, outcome, durationMs);
            } catch (Throwable t) {
                reportRecordingFailure(t);
            }
        }

        @Override
        public void recordLlmAttempt(ProviderFamily family, String callKind, ProviderOutcome outcome,
                long durationMs) {
            try {
                delegate.recordLlmAttempt(family, callKind, outcome, durationMs);
            } catch (Throwable t) {
                reportRecordingFailure(t);
            }
        }

        @Override
        public void addLlmTokens(ProviderFamily family, String callKind, TokenKind tokenKind, long tokens) {
            try {
                delegate.addLlmTokens(family, callKind, tokenKind, tokens);
            } catch (Throwable t) {
                reportRecordingFailure(t);
            }
        }

        @Override
        public void recordLlmUsageSample(ProviderFamily family, String callKind, String usageStatus) {
            try {
                delegate.recordLlmUsageSample(family, callKind, usageStatus);
            } catch (Throwable t) {
                reportRecordingFailure(t);
            }
        }

        @Override
        public void recordTool(ToolKind kind, ToolOutcome outcome, long durationMs) {
            try {
                delegate.recordTool(kind, outcome, durationMs);
            } catch (Throwable t) {
                reportRecordingFailure(t);
            }
        }
    }
}
