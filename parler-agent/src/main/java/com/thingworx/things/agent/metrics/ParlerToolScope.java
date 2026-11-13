package com.thingworx.things.agent.metrics;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.thingworx.things.agent.taskstate.NoEvidenceToolOutcome;

/**
 * One tool execution observation. {@link #completed(String)} classifies a top-level {@code status: "error"} envelope
 * as an error; {@link #markError()} classifies known terminal block branches regardless of response shape. A scope
 * closed without completion (thrown exception) records {@code error}. {@link #suppress()} (approval pause) records no
 * sample.
 */
public final class ParlerToolScope implements AutoCloseable {

    private final ParlerMetricsSink sink;
    private final LongSupplier nanoClock;
    private final long startNanos;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile ToolKind kind;
    private volatile boolean markedError;
    private volatile boolean completed;
    private volatile boolean completedWithErrorEnvelope;
    private volatile boolean suppressed;

    public ParlerToolScope(ToolKind kind, ParlerMetricsSink sink, LongSupplier nanoClock) {
        this.kind = kind != null ? kind : ToolKind.OTHER;
        this.sink = sink != null ? sink : ParlerMetricsSink.NOOP;
        this.nanoClock = nanoClock != null ? nanoClock : System::nanoTime;
        this.startNanos = this.nanoClock.getAsLong();
    }

    public void kind(ToolKind value) {
        if (value != null) {
            kind = value;
        }
    }

    /** Known terminal block branch (repetition, capability policy, missing Parler context, stale compare, ...). */
    public void markError() {
        markedError = true;
    }

    /** The tool returned a result; classification uses the explicit JSON error envelope. */
    public void completed(String result) {
        completedWithErrorEnvelope = NoEvidenceToolOutcome.isExplicitJsonErrorEnvelope(result);
        completed = true;
    }

    /** No sample for this call (approval pause). */
    public void suppress() {
        suppressed = true;
    }

    ToolOutcome resolvedOutcome() {
        if (markedError || !completed || completedWithErrorEnvelope) {
            return ToolOutcome.ERROR;
        }
        return ToolOutcome.SUCCESS;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true) || suppressed) {
            return;
        }
        long durationMs = Math.max(0L, (nanoClock.getAsLong() - startNanos) / 1_000_000L);
        sink.recordTool(kind, resolvedOutcome(), durationMs);
    }
}
