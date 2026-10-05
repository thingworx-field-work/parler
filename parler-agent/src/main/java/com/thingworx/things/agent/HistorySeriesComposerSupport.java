package com.thingworx.things.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.slf4j.Logger;

import com.thingworx.logging.LogUtilities;
import com.thingworx.things.Thing;
import com.thingworx.things.agent.source.SourceDescriptor;
import com.thingworx.things.agent.tools.NumericHistoryCacheWriter;
import com.thingworx.things.agent.tools.StoredSeriesCache;
import com.thingworx.things.agent.cache.ArtifactCacheTurnFaults;
import com.thingworx.things.agent.tools.PropertyToolsExecutor;
import com.thingworx.types.InfoTable;

/**
 * Shared numeric history fetch and fair per-series point budget for {@code build_history_overlay_chart}
 * and internal history chart builders. Primary model-facing consumer:
 * {@code BuildHistoryOverlayChartExecutor} / {@code HistoryOverlayChartBuilder} — design
 * {@code docs/agent/history-overlay-chart.md}. The same helper still supports retired PoP /
 * multi-series executor internals that preceded the history overlay
 * ({@code docs/agent/history-overlay-chart.md}).
 */
public final class HistorySeriesComposerSupport {

    public static final int MAX_TOTAL_EMITTED_POINTS = 5000;
    /** Fixed per-series request of the overlay; also echoed on its tool result. */
    public static final int MAX_HISTORY_ROWS = 5000;
    private static final Logger LOG =
            LogUtilities.getInstance().getApplicationLogger(HistorySeriesComposerSupport.class);

    /** Test seam: incremented when a non-empty store attempt begins. */
    public static final AtomicInteger STORE_ATTEMPTS_FOR_TESTS = new AtomicInteger();

    private HistorySeriesComposerSupport() {}

    /** One numeric sample from platform history. */
    public static class HistoryPoint {
        public final Instant timestamp;
        public final double value;

        public HistoryPoint(Instant timestamp, double value) {
            this.timestamp = timestamp;
            this.value = value;
        }
    }

    /** Series with fetched points (shared window metadata lives on the chart builder). */
    public static final class FetchedSeries implements PointCountSource {
        public final String label;
        public final String thingName;
        public final String propertyName;
        public final List<HistoryPoint> points;

        public FetchedSeries(String label, String thingName, String propertyName, List<HistoryPoint> points) {
            this.label = label;
            this.thingName = thingName;
            this.propertyName = propertyName;
            this.points = points != null ? points : List.of();
        }

        @Override
        public int pointCount() {
            return points.size();
        }
    }

    public enum FetchFailureReason {
        MISSING_INPUT,
        FETCH_EXCEPTION,
        TIMESTAMP_UNRESOLVED
    }

    public static final class FetchOutcome {
        public final List<HistoryPoint> points;
        public final FetchFailureReason failureReason;
        public final String errorMessage;
        /** What this series' platform read observed about its limit, before invalid points were dropped. */
        public final com.thingworx.things.agent.source.ReadLimitFact readLimit;

        private FetchOutcome(List<HistoryPoint> points, FetchFailureReason failureReason, String errorMessage,
                com.thingworx.things.agent.source.ReadLimitFact readLimit) {
            this.points = points != null ? points : List.of();
            this.failureReason = failureReason;
            this.errorMessage = errorMessage;
            this.readLimit = readLimit != null ? readLimit : com.thingworx.things.agent.source.ReadLimitFact.none();
        }

        public static FetchOutcome ok(List<HistoryPoint> points) {
            return new FetchOutcome(points, null, null, null);
        }

        public static FetchOutcome ok(List<HistoryPoint> points, com.thingworx.things.agent.source.ReadLimitFact readLimit) {
            return new FetchOutcome(points, null, null, readLimit);
        }

        public static FetchOutcome failure(FetchFailureReason reason, String message) {
            return new FetchOutcome(List.of(), reason, message, null);
        }

        public boolean isError() {
            return failureReason != null;
        }
    }

    /** PoP tool boundary — maps neutral helper failures to {@code POP_*} codes. */
    public static String popErrorCode(FetchFailureReason reason) {
        if (reason == null) {
            return "POP_INTERNAL";
        }
        switch (reason) {
            case TIMESTAMP_UNRESOLVED:
                return "POP_HISTORY_TIMESTAMP_UNRESOLVED";
            case MISSING_INPUT:
            case FETCH_EXCEPTION:
            default:
                return "POP_INTERNAL";
        }
    }

    /** Multi-series tool boundary — maps neutral helper failures to {@code MULTI_SERIES_*} codes. */
    public static String multiSeriesErrorCode(FetchFailureReason reason) {
        if (reason == null) {
            return "MULTI_SERIES_HISTORY_FETCH_FAILED";
        }
        switch (reason) {
            case TIMESTAMP_UNRESOLVED:
                return "MULTI_SERIES_HISTORY_TIMESTAMP_UNRESOLVED";
            case MISSING_INPUT:
            case FETCH_EXCEPTION:
            default:
                return "MULTI_SERIES_HISTORY_FETCH_FAILED";
        }
    }

    /** History overlay tool boundary — maps neutral helper failures to {@code HISTORY_OVERLAY_*} codes. */
    public static String overlayErrorCode(FetchFailureReason reason) {
        if (reason == null) {
            return "HISTORY_OVERLAY_INTERNAL";
        }
        switch (reason) {
            case TIMESTAMP_UNRESOLVED:
                return "HISTORY_OVERLAY_HISTORY_TIMESTAMP_UNRESOLVED";
            case MISSING_INPUT:
            case FETCH_EXCEPTION:
            default:
                return "HISTORY_OVERLAY_INTERNAL";
        }
    }

    /**
     * Fetch numeric property history for one Thing over {@code [start, end)}.
     */
    public static FetchOutcome fetchNumericHistory(Thing thing, String propertyName, Instant start, Instant end) {
        if (thing == null || propertyName == null || propertyName.isBlank() || start == null || end == null) {
            return FetchOutcome.failure(FetchFailureReason.MISSING_INPUT,
                    "Thing, property, and window are required.");
        }
        try {
            DateTime startDt = new DateTime(start.toEpochMilli(), DateTimeZone.UTC);
            DateTime endDt = new DateTime(end.toEpochMilli(), DateTimeZone.UTC);
            InfoTable history = PropertyToolsExecutor.queryNumericPropertyHistoryTable(
                    thing, propertyName, startDt, endDt, MAX_HISTORY_ROWS);
            // Observed on the platform's table, before extractPopNumericHistoryPoints drops invalid rows: 5000
            // rows read with one invalid value are 4999 points, and the fact would be lost at the cache write.
            com.thingworx.things.agent.source.ReadLimitFact readLimit = com.thingworx.things.agent.source.ReadLimitFact.observe(history, MAX_HISTORY_ROWS);
            PropertyToolsExecutor.PopHistoryExtract extract =
                    PropertyToolsExecutor.extractPopNumericHistoryPoints(history, propertyName);
            if (extract.errorCode != null) {
                FetchFailureReason reason = "POP_HISTORY_TIMESTAMP_UNRESOLVED".equals(extract.errorCode)
                        ? FetchFailureReason.TIMESTAMP_UNRESOLVED
                        : FetchFailureReason.FETCH_EXCEPTION;
                return FetchOutcome.failure(reason, extract.errorMessage);
            }
            return FetchOutcome.ok(extract.points, readLimit);
        } catch (Exception e) {
            return FetchOutcome.failure(FetchFailureReason.FETCH_EXCEPTION,
                    e.getMessage() != null ? e.getMessage() : "history fetch failed");
        }
    }

    /** Proportional fair allocation; sum of targets &lt;= {@link #MAX_TOTAL_EMITTED_POINTS}. */
    public static int[] allocateEmitTargets(List<? extends PointCountSource> withData, int rawTotal) {
        int n = withData.size();
        int[] sizes = new int[n];
        for (int i = 0; i < n; i++) {
            sizes[i] = withData.get(i).pointCount();
        }
        if (rawTotal <= MAX_TOTAL_EMITTED_POINTS) {
            return sizes;
        }
        int cap = MAX_TOTAL_EMITTED_POINTS;
        int[] targets = new int[n];
        int[] remainders = new int[n];
        int allocated = 0;
        for (int i = 0; i < n; i++) {
            long numer = (long) sizes[i] * cap;
            targets[i] = (int) (numer / rawTotal);
            remainders[i] = (int) (numer % rawTotal);
            if (sizes[i] > 0 && targets[i] < 1) {
                targets[i] = 1;
            }
            if (targets[i] > sizes[i]) {
                targets[i] = sizes[i];
            }
            allocated += targets[i];
        }
        while (allocated < cap) {
            int best = -1;
            int bestRem = -1;
            for (int i = 0; i < n; i++) {
                if (targets[i] >= sizes[i]) {
                    continue;
                }
                if (remainders[i] > bestRem) {
                    bestRem = remainders[i];
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            targets[best]++;
            remainders[best] = 0;
            allocated++;
        }
        while (allocated > cap) {
            int best = -1;
            for (int i = 0; i < n; i++) {
                if (targets[i] <= 1) {
                    continue;
                }
                if (best < 0 || targets[i] > targets[best]) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            targets[best]--;
            allocated--;
        }
        return targets;
    }

    /** Uniform index subsample preserving endpoints when target &gt;= 2. */
    public static List<HistoryPoint> uniformSubsample(List<HistoryPoint> points, int target) {
        int n = points.size();
        if (target >= n || target <= 0) {
            return new ArrayList<>(points);
        }
        if (target == 1) {
            return List.of(points.get(0));
        }
        int[] idx = new int[target];
        for (int i = 0; i < target; i++) {
            idx[i] = (int) Math.round(i * (n - 1.0) / (target - 1.0));
        }
        List<HistoryPoint> out = new ArrayList<>(target);
        for (int i : idx) {
            out.add(points.get(i));
        }
        return out;
    }

    /**
     * S4: store a full pre-subsample numeric history series through {@link NumericHistoryCacheWriter}
     * ({@code timestamp} STRING ISO, {@code value} NUMBER, writer-declared roles) for
     * {@code fetch_cached_result} / {@code tabulate_cached_result} / {@code analyze_cached_result}
     * reuse. Returns {@code null} when points are empty or the store fails (soft-skip; chart
     * emission continues).
     */
    public static StoredSeriesCache storeNumericHistoryPointsInConversationCache(List<HistoryPoint> points) {
        return storeNumericHistoryPointsInConversationCache(points, null, null);
    }

    /**
     * Same store with the subject identity the overlay already resolved (CM-4): the canonical Thing
     * name and property of the series. Blank names declare no identity.
     */
    public static StoredSeriesCache storeNumericHistoryPointsInConversationCache(List<HistoryPoint> points,
            String subjectThingName, String subjectPropertyName) {
        return storeNumericHistoryPointsInConversationCache(points, subjectThingName, subjectPropertyName, null);
    }

    /** Same store, with the read-limit fact of the platform read that produced this series. */
    public static StoredSeriesCache storeNumericHistoryPointsInConversationCache(List<HistoryPoint> points,
            String subjectThingName, String subjectPropertyName, com.thingworx.things.agent.source.ReadLimitFact readLimit) {
        if (points == null || points.isEmpty()) {
            return null;
        }
        STORE_ATTEMPTS_FOR_TESTS.incrementAndGet();
        try {
            InfoTable table = NumericHistoryCacheWriter.newTable();
            for (HistoryPoint p : points) {
                if (p == null || p.timestamp == null) {
                    continue;
                }
                NumericHistoryCacheWriter.addRow(table, p.timestamp.toString(), p.value);
            }
            if (table.getRowCount() == null || table.getRowCount() == 0) {
                return null;
            }
            return NumericHistoryCacheWriter.store(table, "build_history_overlay_chart", subjectThingName,
                    subjectPropertyName,
                    d -> com.thingworx.things.agent.source.SourceDescriptorSupport.withReadLimitFact(d, readLimit));
        } catch (Exception e) {
            ArtifactCacheTurnFaults.rethrowRepositoryUnavailable(e);
            LOG.warn("build_history_overlay_chart series cache registration skipped: {}", e.getMessage());
            return null;
        }
    }

    /** Adapter for {@link #allocateEmitTargets}. */
    public interface PointCountSource {
        int pointCount();
    }
}
