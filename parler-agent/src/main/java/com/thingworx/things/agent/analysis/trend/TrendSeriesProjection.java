package com.thingworx.things.agent.analysis.trend;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.thingworx.things.agent.analysis.stats.NumericObservation;
import com.thingworx.things.agent.analysis.stats.NumericSeries;

/**
 * Projects a timestamped numeric series to elapsed-seconds {@code x} and finite {@code y} for G4
 * trend methods (§7.6). Uses {@link Duration#between} (seconds + nanos) so sub-millisecond
 * timestamps stay distinct. Observations without Instant or non-finite values are dropped and
 * counted.
 */
public final class TrendSeriesProjection {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final Instant t0;
    private final Instant tEnd;
    private final double[] elapsedSeconds;
    private final double[] values;
    private final List<NumericObservation> used;
    private final long considered;
    private final long dropped;

    private TrendSeriesProjection(Instant t0, Instant tEnd, double[] elapsedSeconds, double[] values,
            List<NumericObservation> used, long considered, long dropped) {
        this.t0 = t0;
        this.tEnd = tEnd;
        this.elapsedSeconds = elapsedSeconds;
        this.values = values;
        this.used = used;
        this.considered = considered;
        this.dropped = dropped;
    }

    public static TrendSeriesProjection from(NumericSeries series) {
        Objects.requireNonNull(series, "series");
        List<NumericObservation> candidates = new ArrayList<>();
        for (NumericObservation o : series.observations()) {
            if (o.instant() != null && o.isFinite()) {
                candidates.add(o);
            }
        }
        candidates.sort((a, b) -> {
            int c = a.instant().compareTo(b.instant());
            return c != 0 ? c : Long.compare(a.sourceOrdinal(), b.sourceOrdinal());
        });
        long considered = series.observations().size();
        long dropped = considered - candidates.size();
        if (candidates.isEmpty()) {
            return new TrendSeriesProjection(null, null, new double[0], new double[0], List.of(),
                    considered, dropped);
        }
        Instant t0 = candidates.get(0).instant();
        Instant tEnd = candidates.get(candidates.size() - 1).instant();
        double[] x = new double[candidates.size()];
        double[] y = new double[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            NumericObservation o = candidates.get(i);
            x[i] = toElapsedSeconds(t0, o.instant());
            y[i] = o.value();
        }
        return new TrendSeriesProjection(t0, tEnd, x, y, List.copyOf(candidates), considered, dropped);
    }

    /** Elapsed seconds from {@code t0} with nanosecond precision (no millisecond truncation). */
    static double toElapsedSeconds(Instant t0, Instant instant) {
        Duration d = Duration.between(t0, instant);
        return d.getSeconds() + d.getNano() / NANOS_PER_SECOND;
    }

    public Instant t0() {
        return t0;
    }

    public Instant tEnd() {
        return tEnd;
    }

    public double[] elapsedSeconds() {
        return elapsedSeconds.clone();
    }

    public double[] values() {
        return values.clone();
    }

    public List<NumericObservation> used() {
        return used;
    }

    public long usedCount() {
        return used.size();
    }

    public long considered() {
        return considered;
    }

    public long dropped() {
        return dropped;
    }

    /**
     * Reconstruct an Instant from elapsed seconds without millisecond rounding. Uses nanosecond
     * addition from {@code t0}.
     */
    public Instant instantAtElapsedSeconds(double elapsedSeconds) {
        if (t0 == null || !Double.isFinite(elapsedSeconds)) {
            return null;
        }
        long nanos = Math.round(elapsedSeconds * NANOS_PER_SECOND);
        return t0.plusNanos(nanos);
    }
}
