package com.thingworx.things.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Unified history overlay {@code ChartBlock} builder ({@code docs/agent/history-overlay-chart.md}).
 */
public final class HistoryOverlayChartBuilder {

    public static final int HISTORY_OVERLAY_MAX_SERIES = 6;
    public static final int HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS =
            HistorySeriesComposerSupport.MAX_TOTAL_EMITTED_POINTS;
    /** Owned here after S13 removed the retired {@code PeriodOverPeriodChartBuilder} constant host. */
    public static final long WINDOW_EQUALITY_TOLERANCE_SECONDS = 1L;

    public static final String X_AXIS_ABSOLUTE = "absolute_time";
    public static final String X_AXIS_ELAPSED = "elapsed_time";
    public static final String X_AXIS_NORMALIZED = "normalized_time";

    private HistoryOverlayChartBuilder() {}

    public static final class BuildException extends Exception {
        public final String code;

        public BuildException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static final class ResolvedSeriesInput implements HistorySeriesComposerSupport.PointCountSource {
        public final String label;
        public final String thingName;
        public final String propertyName;
        public final Instant windowStart;
        public final Instant windowEnd;
        public final String resolvedTimeZone;
        public final List<HistorySeriesComposerSupport.HistoryPoint> points;
        /** Read-limit fact of this series' own platform read; never compared across series or with emitted points. */
        public final com.thingworx.things.agent.source.ReadLimitFact readLimit;

        public ResolvedSeriesInput(String label, String thingName, String propertyName, Instant windowStart,
                Instant windowEnd, String resolvedTimeZone,
                List<? extends HistorySeriesComposerSupport.HistoryPoint> points) {
            this(label, thingName, propertyName, windowStart, windowEnd, resolvedTimeZone, points, null);
        }

        public ResolvedSeriesInput(String label, String thingName, String propertyName, Instant windowStart,
                Instant windowEnd, String resolvedTimeZone,
                List<? extends HistorySeriesComposerSupport.HistoryPoint> points,
                com.thingworx.things.agent.source.ReadLimitFact readLimit) {
            this.readLimit = readLimit != null ? readLimit : com.thingworx.things.agent.source.ReadLimitFact.none();
            this.label = label;
            this.thingName = thingName;
            this.propertyName = propertyName;
            this.windowStart = windowStart;
            this.windowEnd = windowEnd;
            this.resolvedTimeZone = resolvedTimeZone != null ? resolvedTimeZone : "";
            this.points = points != null ? List.copyOf(points) : List.of();
        }

        @Override
        public int pointCount() {
            return points.size();
        }
    }

    public static JSONObject buildChart(
            String xAxisModeRaw,
            String chartKindRaw,
            List<ResolvedSeriesInput> series,
            String title,
            String yLabel,
            JSONArray yReferenceLines) throws BuildException {

        String xAxisMode = normalizeXAxisMode(xAxisModeRaw, series);
        String kind = normalizeChartKind(chartKindRaw);

        if (series == null || series.size() < 2) {
            throw new BuildException("HISTORY_OVERLAY_TOO_FEW_SERIES", "At least two series are required.");
        }
        if (series.size() > HISTORY_OVERLAY_MAX_SERIES) {
            throw new BuildException("HISTORY_OVERLAY_TOO_MANY_SERIES",
                    "At most " + HISTORY_OVERLAY_MAX_SERIES + " series allowed.");
        }

        String propertyName = series.get(0).propertyName;
        if (propertyName == null || propertyName.isBlank()) {
            throw new BuildException("HISTORY_OVERLAY_MISSING_PROPERTY", "propertyName is required.");
        }
        for (ResolvedSeriesInput s : series) {
            if (!propertyName.equals(s.propertyName)) {
                throw new BuildException("HISTORY_OVERLAY_MISSING_PROPERTY",
                        "All series must use the same propertyName.");
            }
        }

        validateNoDuplicateSeries(series);

        if (X_AXIS_ELAPSED.equals(xAxisMode)) {
            return buildElapsedChart(kind, propertyName, series, title, yLabel, yReferenceLines);
        }
        if (X_AXIS_NORMALIZED.equals(xAxisMode)) {
            return buildNormalizedChart(kind, propertyName, series, title, yLabel, yReferenceLines);
        }
        return buildAbsoluteChart(kind, propertyName, series, title, yLabel, yReferenceLines);
    }

    static String normalizeXAxisMode(String xAxisModeRaw, List<ResolvedSeriesInput> series)
            throws BuildException {
        if (xAxisModeRaw == null || xAxisModeRaw.isBlank()) {
            return defaultXAxisMode(series);
        }
        String mode = xAxisModeRaw.trim().toLowerCase(Locale.ROOT);
        if (X_AXIS_ABSOLUTE.equals(mode) || "absolute".equals(mode)) {
            return X_AXIS_ABSOLUTE;
        }
        if (X_AXIS_ELAPSED.equals(mode) || "elapsed".equals(mode)) {
            return X_AXIS_ELAPSED;
        }
        if (X_AXIS_NORMALIZED.equals(mode) || "normalized".equals(mode)) {
            return X_AXIS_NORMALIZED;
        }
        throw new BuildException("HISTORY_OVERLAY_INVALID_X_AXIS_MODE",
                "xAxisMode must be absolute_time, elapsed_time, or normalized_time.");
    }

    static String defaultXAxisMode(List<ResolvedSeriesInput> series) {
        ResolvedSeriesInput ref = series.get(0);
        for (int i = 1; i < series.size(); i++) {
            if (!windowsEquivalent(ref, series.get(i))) {
                return X_AXIS_ELAPSED;
            }
        }
        return X_AXIS_ABSOLUTE;
    }

    static boolean windowsEquivalent(ResolvedSeriesInput a, ResolvedSeriesInput b) {
        long startDiff = Math.abs(a.windowStart.toEpochMilli() - b.windowStart.toEpochMilli());
        long endDiff = Math.abs(a.windowEnd.toEpochMilli() - b.windowEnd.toEpochMilli());
        return startDiff <= WINDOW_EQUALITY_TOLERANCE_SECONDS * 1000L
                && endDiff <= WINDOW_EQUALITY_TOLERANCE_SECONDS * 1000L;
    }

    static void validateNoDuplicateSeries(List<ResolvedSeriesInput> series) throws BuildException {
        Set<String> seen = new LinkedHashSet<>();
        for (ResolvedSeriesInput s : series) {
            String key = s.thingName + "|" + s.propertyName + "|" + s.windowStart.toEpochMilli() + "|"
                    + s.windowEnd.toEpochMilli();
            if (!seen.add(key)) {
                throw new BuildException("HISTORY_OVERLAY_DUPLICATE_SERIES",
                        "Duplicate series window for " + s.label + ".");
            }
        }
    }

    static long windowDurationSeconds(ResolvedSeriesInput s) {
        return Math.max(0L, Duration.between(s.windowStart, s.windowEnd).getSeconds());
    }

    static long maxWindowDurationSeconds(List<ResolvedSeriesInput> series) {
        long max = 0L;
        for (ResolvedSeriesInput s : series) {
            max = Math.max(max, windowDurationSeconds(s));
        }
        return max;
    }

    private static String normalizeChartKind(String chartKindRaw) throws BuildException {
        String kind = chartKindRaw == null || chartKindRaw.isBlank() ? "line" : chartKindRaw.trim().toLowerCase(Locale.ROOT);
        if (!"line".equals(kind) && !"scatter".equals(kind)) {
            throw new BuildException("HISTORY_OVERLAY_INVALID_KIND", "chart_kind must be line or scatter.");
        }
        return kind;
    }

    private static JSONObject buildElapsedChart(
            String kind,
            String propertyName,
            List<ResolvedSeriesInput> series,
            String title,
            String yLabel,
            JSONArray yReferenceLines) throws BuildException {

        long elapsedEndSec = maxWindowDurationSeconds(series);

        List<ResolvedSeriesInput> withData = new ArrayList<>();
        JSONArray missing = new JSONArray();
        for (ResolvedSeriesInput s : series) {
            if (s.points.isEmpty()) {
                JSONObject miss = new JSONObject();
                miss.put("label", s.label);
                miss.put("thingName", s.thingName);
                miss.put("propertyName", s.propertyName);
                miss.put("start", s.windowStart.toString());
                miss.put("end", s.windowEnd.toString());
                missing.put(miss);
            } else {
                withData.add(s);
            }
        }
        if (withData.isEmpty()) {
            throw new BuildException("HISTORY_OVERLAY_NO_DATA",
                    "Every series had zero samples; cannot render a chart.");
        }

        int rawTotal = withData.stream().mapToInt(ResolvedSeriesInput::pointCount).sum();
        int[] emitTargets = HistorySeriesComposerSupport.allocateEmitTargets(withData, rawTotal);
        boolean truncationApplied = rawTotal > HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS;

        JSONArray seriesArr = new JSONArray();
        int emittedTotal = 0;
        for (int i = 0; i < withData.size(); i++) {
            ResolvedSeriesInput s = withData.get(i);
            int target = emitTargets[i];
            List<HistorySeriesComposerSupport.HistoryPoint> sampled =
                    HistorySeriesComposerSupport.uniformSubsample(s.points, target);
            emittedTotal += sampled.size();

            JSONArray xArr = new JSONArray();
            JSONArray yArr = new JSONArray();
            for (HistorySeriesComposerSupport.HistoryPoint pt : sampled) {
                long elapsed = Duration.between(s.windowStart, pt.timestamp).getSeconds();
                if (elapsed < 0) {
                    elapsed = 0;
                }
                xArr.put(Long.toString(elapsed));
                yArr.put(pt.value);
            }

            JSONObject sourceWindow = new JSONObject();
            sourceWindow.put("start", s.windowStart.toString());
            sourceWindow.put("end", s.windowEnd.toString());
            sourceWindow.put("thingName", s.thingName);
            sourceWindow.put("propertyName", s.propertyName);
            sourceWindow.put("periodLabel", s.label);
            if (!s.resolvedTimeZone.isEmpty()) {
                sourceWindow.put("resolvedTimeZone", s.resolvedTimeZone);
            }
            sourceWindow.put("sampleCount", sampled.size());
            sourceWindow.put("rawSampleCount", s.points.size());
            if (truncationApplied && s.points.size() > sampled.size()) {
                sourceWindow.put("omittedSampleCount", s.points.size() - sampled.size());
            }

            JSONObject ser = new JSONObject();
            ser.put("name", s.label);
            ser.put("x", xArr);
            ser.put("y", yArr);
            ser.put("sourceWindow", sourceWindow);
            seriesArr.put(ser);
        }

        JSONObject source = new JSONObject();
        source.put("sourceResolved", "history_overlay");
        source.put("rowCount", rawTotal);
        source.put("pointCount", emittedTotal);
        source.put("truncationApplied", truncationApplied);
        if (missing.length() > 0) {
            source.put("missingPeriods", missing);
        }

        JSONObject chart = new JSONObject();
        chart.put("kind", kind);
        chart.put("xAxisMode", "elapsed");
        JSONObject elapsedDomain = new JSONObject();
        elapsedDomain.put("start", 0);
        elapsedDomain.put("end", elapsedEndSec);
        chart.put("elapsedDomain", elapsedDomain);
        chart.put("x_label", "Elapsed time");
        chart.put("y_label", yLabel != null && !yLabel.isBlank() ? yLabel : propertyName);
        applyTitle(chart, title, propertyName, series);
        chart.put("series", seriesArr);
        chart.put("source", source);
        attachReferenceLines(chart, yReferenceLines);
        return chart;
    }

    private static JSONObject buildNormalizedChart(
            String kind,
            String propertyName,
            List<ResolvedSeriesInput> series,
            String title,
            String yLabel,
            JSONArray yReferenceLines) throws BuildException {

        for (ResolvedSeriesInput s : series) {
            if (windowDurationSeconds(s) <= 0L) {
                throw new BuildException("HISTORY_OVERLAY_INVALID_TIME_WINDOW",
                        "Series window must have positive duration for normalized_time.");
            }
        }

        List<ResolvedSeriesInput> withData = new ArrayList<>();
        JSONArray missing = new JSONArray();
        for (ResolvedSeriesInput s : series) {
            if (s.points.isEmpty()) {
                JSONObject miss = new JSONObject();
                miss.put("label", s.label);
                miss.put("thingName", s.thingName);
                miss.put("propertyName", s.propertyName);
                miss.put("start", s.windowStart.toString());
                miss.put("end", s.windowEnd.toString());
                missing.put(miss);
            } else {
                withData.add(s);
            }
        }
        if (withData.isEmpty()) {
            throw new BuildException("HISTORY_OVERLAY_NO_DATA",
                    "Every series had zero samples; cannot render a chart.");
        }

        int rawTotal = withData.stream().mapToInt(ResolvedSeriesInput::pointCount).sum();
        int[] emitTargets = HistorySeriesComposerSupport.allocateEmitTargets(withData, rawTotal);
        boolean truncationApplied = rawTotal > HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS;

        JSONArray seriesArr = new JSONArray();
        int emittedTotal = 0;
        for (int i = 0; i < withData.size(); i++) {
            ResolvedSeriesInput s = withData.get(i);
            int target = emitTargets[i];
            List<HistorySeriesComposerSupport.HistoryPoint> sampled =
                    HistorySeriesComposerSupport.uniformSubsample(s.points, target);
            emittedTotal += sampled.size();

            JSONArray xArr = new JSONArray();
            JSONArray yArr = new JSONArray();
            for (HistorySeriesComposerSupport.HistoryPoint pt : sampled) {
                xArr.put(formatNormalizedX(pt.timestamp, s.windowStart, s.windowEnd));
                yArr.put(pt.value);
            }

            JSONObject sourceWindow = new JSONObject();
            sourceWindow.put("start", s.windowStart.toString());
            sourceWindow.put("end", s.windowEnd.toString());
            sourceWindow.put("thingName", s.thingName);
            sourceWindow.put("propertyName", s.propertyName);
            sourceWindow.put("periodLabel", s.label);
            if (!s.resolvedTimeZone.isEmpty()) {
                sourceWindow.put("resolvedTimeZone", s.resolvedTimeZone);
            }
            sourceWindow.put("sampleCount", sampled.size());
            sourceWindow.put("rawSampleCount", s.points.size());
            if (truncationApplied && s.points.size() > sampled.size()) {
                sourceWindow.put("omittedSampleCount", s.points.size() - sampled.size());
            }

            JSONObject ser = new JSONObject();
            ser.put("name", s.label);
            ser.put("x", xArr);
            ser.put("y", yArr);
            ser.put("sourceWindow", sourceWindow);
            seriesArr.put(ser);
        }

        JSONObject source = new JSONObject();
        source.put("sourceResolved", "history_overlay");
        source.put("rowCount", rawTotal);
        source.put("pointCount", emittedTotal);
        source.put("truncationApplied", truncationApplied);
        if (missing.length() > 0) {
            source.put("missingPeriods", missing);
        }

        JSONObject chart = new JSONObject();
        chart.put("kind", kind);
        chart.put("xAxisMode", "normalized");
        JSONObject normalizedDomain = new JSONObject();
        normalizedDomain.put("start", 0);
        normalizedDomain.put("end", 1);
        chart.put("normalizedDomain", normalizedDomain);
        chart.put("x_label", "Normalized time");
        chart.put("y_label", yLabel != null && !yLabel.isBlank() ? yLabel : propertyName);
        applyTitle(chart, title, propertyName, series);
        chart.put("series", seriesArr);
        chart.put("source", source);
        attachReferenceLines(chart, yReferenceLines);
        return chart;
    }

    static String formatNormalizedX(Instant timestamp, Instant windowStart, Instant windowEnd) {
        long startMs = windowStart.toEpochMilli();
        long endMs = windowEnd.toEpochMilli();
        long durMs = endMs - startMs;
        if (durMs <= 0L) {
            return "0";
        }
        double normalized = (timestamp.toEpochMilli() - startMs) / (double) durMs;
        if (normalized < 0.0) {
            normalized = 0.0;
        } else if (normalized > 1.0) {
            normalized = 1.0;
        }
        return String.format(Locale.US, "%.6f", normalized);
    }

    private static JSONObject buildAbsoluteChart(
            String kind,
            String propertyName,
            List<ResolvedSeriesInput> series,
            String title,
            String yLabel,
            JSONArray yReferenceLines) throws BuildException {

        List<ResolvedSeriesInput> withData = new ArrayList<>();
        JSONArray missingSeries = new JSONArray();
        for (ResolvedSeriesInput s : series) {
            if (s.points.isEmpty()) {
                JSONObject miss = new JSONObject();
                miss.put("label", s.label);
                miss.put("thingName", s.thingName);
                miss.put("propertyName", s.propertyName);
                miss.put("start", s.windowStart.toString());
                miss.put("end", s.windowEnd.toString());
                missingSeries.put(miss);
            } else {
                withData.add(s);
            }
        }
        if (withData.isEmpty()) {
            throw new BuildException("HISTORY_OVERLAY_NO_DATA",
                    "Every series had zero samples; cannot render a chart.");
        }

        int rawTotal = withData.stream().mapToInt(ResolvedSeriesInput::pointCount).sum();
        int[] emitTargets = HistorySeriesComposerSupport.allocateEmitTargets(withData, rawTotal);
        boolean truncationApplied = rawTotal > HISTORY_OVERLAY_MAX_TOTAL_EMITTED_POINTS;

        JSONArray seriesArr = new JSONArray();
        int emittedTotal = 0;
        for (int i = 0; i < withData.size(); i++) {
            ResolvedSeriesInput s = withData.get(i);
            int target = emitTargets[i];
            List<HistorySeriesComposerSupport.HistoryPoint> sampled =
                    HistorySeriesComposerSupport.uniformSubsample(s.points, target);
            emittedTotal += sampled.size();

            JSONArray xArr = new JSONArray();
            JSONArray yArr = new JSONArray();
            for (HistorySeriesComposerSupport.HistoryPoint pt : sampled) {
                xArr.put(pt.timestamp.toString());
                yArr.put(pt.value);
            }
            JSONObject ser = new JSONObject();
            ser.put("name", s.label);
            ser.put("x", xArr);
            ser.put("y", yArr);
            seriesArr.put(ser);
        }

        JSONObject source = new JSONObject();
        source.put("sourceResolved", "history_overlay");
        source.put("rowCount", rawTotal);
        source.put("pointCount", emittedTotal);
        source.put("truncationApplied", truncationApplied);
        if (missingSeries.length() > 0) {
            source.put("missingSeries", missingSeries);
        }

        JSONObject chart = new JSONObject();
        chart.put("kind", kind);
        if (allWindowsEquivalent(series)) {
            JSONObject requested = new JSONObject();
            requested.put("start", series.get(0).windowStart.toString());
            requested.put("end", series.get(0).windowEnd.toString());
            chart.put("requested_time_range", requested);
            if (!series.get(0).resolvedTimeZone.isEmpty()) {
                source.put("resolvedTimeZone", series.get(0).resolvedTimeZone);
            }
        }
        chart.put("x_label", "Time");
        chart.put("y_label", yLabel != null && !yLabel.isBlank() ? yLabel.trim() : propertyName);
        applyTitle(chart, title, propertyName, series);
        chart.put("series", seriesArr);
        chart.put("source", source);
        attachReferenceLines(chart, yReferenceLines);
        return chart;
    }

    private static boolean allWindowsEquivalent(List<ResolvedSeriesInput> series) {
        if (series.isEmpty()) {
            return false;
        }
        ResolvedSeriesInput ref = series.get(0);
        for (ResolvedSeriesInput s : series) {
            if (!windowsEquivalent(ref, s)) {
                return false;
            }
        }
        return true;
    }

    private static void applyTitle(JSONObject chart, String title, String propertyName,
            List<ResolvedSeriesInput> series) {
        if (title != null && !title.isBlank()) {
            chart.put("title", title.trim());
            return;
        }
        Set<String> things = new LinkedHashSet<>();
        for (ResolvedSeriesInput s : series) {
            things.add(s.thingName);
        }
        if (things.size() == 1) {
            chart.put("title", propertyName + " (" + series.get(0).thingName + ")");
        } else {
            chart.put("title", propertyName + " — history overlay");
        }
    }

    private static void attachReferenceLines(JSONObject chart, JSONArray yReferenceLines) {
        if (yReferenceLines != null && yReferenceLines.length() > 0) {
            chart.put("y_reference_lines", yReferenceLines);
        }
    }
}
