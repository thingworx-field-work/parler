package com.thingworx.things.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Test;

class HistoryOverlayChartBuilderTest {

    private static final Instant t0 = Instant.parse("2026-06-30T14:00:00.000Z");
    private static final Instant t1 = Instant.parse("2026-06-30T15:00:00.000Z");
    private static final Instant y0 = Instant.parse("2026-06-29T14:00:00.000Z");
    private static final Instant y1 = Instant.parse("2026-06-29T15:00:00.000Z");

    private static HistoryOverlayChartBuilder.ResolvedSeriesInput series(
            String label, String thing, Instant start, Instant end,
            List<HistorySeriesComposerSupport.HistoryPoint> pts) {
        return new HistoryOverlayChartBuilder.ResolvedSeriesInput(
                label, thing, "currentDraw", start, end, "America/Chicago", pts);
    }

    private static List<HistorySeriesComposerSupport.HistoryPoint> pts(Instant base, double... values) {
        List<HistorySeriesComposerSupport.HistoryPoint> out = new ArrayList<>();
        for (int i = 0; i < values.length; i++) {
            out.add(new HistorySeriesComposerSupport.HistoryPoint(base.plusSeconds(i * 300L), values[i]));
        }
        return out;
    }

    @Test
    void defaultModeSameWindowAbsolute() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                null, "line",
                List.of(
                        series("A", "Thing.A", t0, t1, pts(t0, 1.0, 2.0)),
                        series("B", "Thing.B", t0, t1, pts(t0, 3.0, 4.0))),
                null, null, null);
        assertFalse(chart.has("xAxisMode"));
        assertTrue(chart.has("requested_time_range"));
        assertEquals(2, chart.getJSONArray("series").length());
    }

    @Test
    void defaultModeShiftedWindowsElapsed() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                null, "line",
                List.of(
                        series("Today", "Thing.A", t0, t1, pts(t0, 1.0, 2.0)),
                        series("Yesterday", "Thing.A", y0, y1, pts(y0, 3.0, 4.0))),
                null, null, null);
        assertEquals("elapsed", chart.getString("xAxisMode"));
        assertEquals(3600, chart.getJSONObject("elapsedDomain").getInt("end"));
        assertFalse(chart.has("requested_time_range"));
    }

    @Test
    void mixedCrossThingSameWindowLastWeekElapsed() throws Exception {
        Instant lw0 = Instant.parse("2026-06-25T14:00:00.000Z");
        Instant lw1 = Instant.parse("2026-06-25T15:00:00.000Z");
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                HistoryOverlayChartBuilder.X_AXIS_ELAPSED, "line",
                List.of(
                        series("Robot01 now", "Robot-01", t0, t1, pts(t0, 1.0, 2.0)),
                        series("Robot01 lw", "Robot-01", lw0, lw1, pts(lw0, 2.0, 3.0)),
                        series("Robot02 lw", "Robot-02", lw0, lw1, pts(lw0, 4.0, 5.0))),
                null, null, null);
        assertEquals(3, chart.getJSONArray("series").length());
        assertEquals("elapsed", chart.getString("xAxisMode"));
    }

    @Test
    void unequalDurationElapsedDomainMax() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                HistoryOverlayChartBuilder.X_AXIS_ELAPSED, "line",
                List.of(
                        series("30m", "Thing.A", t0, t0.plusSeconds(1800), pts(t0, 1.0, 2.0)),
                        series("45m", "Thing.B", y0, y0.plusSeconds(2700), pts(y0, 3.0, 4.0, 5.0))),
                null, null, null);
        assertEquals(2700, chart.getJSONObject("elapsedDomain").getInt("end"));
    }

    @Test
    void duplicateExactTupleRejected() {
        HistoryOverlayChartBuilder.BuildException ex = assertThrows(
                HistoryOverlayChartBuilder.BuildException.class,
                () -> HistoryOverlayChartBuilder.buildChart(
                        null, "line",
                        List.of(
                                series("A", "Thing.A", t0, t1, pts(t0, 1.0)),
                                series("B", "Thing.A", t0, t1, pts(t0, 2.0))),
                        null, null, null));
        assertEquals("HISTORY_OVERLAY_DUPLICATE_SERIES", ex.code);
    }

    @Test
    void sameWindowDifferentThingsAllowed() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                HistoryOverlayChartBuilder.X_AXIS_ELAPSED, "line",
                List.of(
                        series("A", "Thing.A", t0, t1, pts(t0, 1.0)),
                        series("B", "Thing.B", t0, t1, pts(t0, 2.0))),
                null, null, null);
        assertEquals(2, chart.getJSONArray("series").length());
    }

    @Test
    void referenceLinesAttached() throws Exception {
        JSONArray refs = new JSONArray();
        JSONObject upper = new JSONObject();
        upper.put("y", 8.0);
        upper.put("label", "Upper limit");
        upper.put("role", "usl");
        refs.put(upper);
        JSONObject lower = new JSONObject();
        lower.put("y", 2.0);
        lower.put("role", "lsl");
        refs.put(lower);

        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                null, "line",
                List.of(
                        series("A", "Thing.A", t0, t1, pts(t0, 5.0, 6.0)),
                        series("B", "Thing.B", t0, t1, pts(t0, 7.0, 8.0))),
                null, null, refs);
        JSONArray yrl = chart.getJSONArray("y_reference_lines");
        assertEquals(2, yrl.length());
        assertEquals(8.0, yrl.getJSONObject(0).getDouble("y"), 0.001);
        assertEquals("usl", yrl.getJSONObject(0).getString("role"));
    }

    @Test
    void scatterKindPreserved() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                null, "scatter",
                List.of(
                        series("A", "Thing.A", t0, t1, pts(t0, 1.0)),
                        series("B", "Thing.B", t0, t1, pts(t0, 2.0))),
                null, null, null);
        assertEquals("scatter", chart.getString("kind"));
    }

    @Test
    void normalizedModeDifferentDurationShape() throws Exception {
        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                HistoryOverlayChartBuilder.X_AXIS_NORMALIZED, "line",
                List.of(
                        series("30m today", "Robot-01", t0, t0.plusSeconds(1800), pts(t0, 1.0, 2.0)),
                        series("45m yesterday", "Robot-02", y0, y0.plusSeconds(2700),
                                pts(y0, 3.0, 4.0, 5.0))),
                null, null, null);
        assertEquals("normalized", chart.getString("xAxisMode"));
        assertEquals(1, chart.getJSONObject("normalizedDomain").getInt("end"));
        assertFalse(chart.has("elapsedDomain"));
        assertFalse(chart.has("requested_time_range"));
        JSONArray x0 = chart.getJSONArray("series").getJSONObject(0).getJSONArray("x");
        assertEquals("0.000000", x0.getString(0));
        double lastX = Double.parseDouble(x0.getString(x0.length() - 1));
        assertTrue(lastX >= 0.0 && lastX <= 1.0);
    }

    @Test
    void normalizedXClampedToUnitInterval() {
        Instant end = t0.plusSeconds(1800);
        List<HistorySeriesComposerSupport.HistoryPoint> atEnd =
                List.of(new HistorySeriesComposerSupport.HistoryPoint(end, 1.0));
        assertEquals("1.000000", HistoryOverlayChartBuilder.formatNormalizedX(end, t0, end));
        assertEquals("0.000000", HistoryOverlayChartBuilder.formatNormalizedX(t0, t0, end));
    }

    @Test
    void zeroDurationWindowRejectedForNormalized() {
        HistoryOverlayChartBuilder.BuildException ex = assertThrows(
                HistoryOverlayChartBuilder.BuildException.class,
                () -> HistoryOverlayChartBuilder.buildChart(
                        HistoryOverlayChartBuilder.X_AXIS_NORMALIZED, "line",
                        List.of(
                                series("A", "Thing.A", t0, t0, pts(t0, 1.0)),
                                series("B", "Thing.B", y0, y1, pts(y0, 2.0))),
                        null, null, null));
        assertEquals("HISTORY_OVERLAY_INVALID_TIME_WINDOW", ex.code);
    }

    @Test
    void normalizedScatterWithReferenceLines() throws Exception {
        JSONArray refs = new JSONArray();
        JSONObject upper = new JSONObject();
        upper.put("y", 8.0);
        upper.put("role", "usl");
        refs.put(upper);

        JSONObject chart = HistoryOverlayChartBuilder.buildChart(
                HistoryOverlayChartBuilder.X_AXIS_NORMALIZED, "scatter",
                List.of(
                        series("A", "Thing.A", t0, t0.plusSeconds(1800), pts(t0, 1.0, 2.0)),
                        series("B", "Thing.B", y0, y0.plusSeconds(2700), pts(y0, 3.0, 4.0))),
                null, null, refs);
        assertEquals("scatter", chart.getString("kind"));
        assertEquals("normalized", chart.getString("xAxisMode"));
        assertEquals(1, chart.getJSONArray("y_reference_lines").length());
    }
}
