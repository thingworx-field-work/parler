import assert from "node:assert/strict";
import test from "node:test";
import {
  chartSnapshotSignature,
  chartViewStateKey,
  createChartViewState,
  describeChartView,
  isChartViewDefault,
  isSeriesHidden,
  reconcileChartViewState,
  resetChartView,
  setNotesOpen,
  setSelectedRange,
  setViewXDomain,
  setYDomainPolicy,
  showAllSeries,
  toggleSeriesVisibility,
  toggleSliceFocus,
} from "./chartViewState.js";

const chart = {
  kind: "line",
  series: [
    { name: "a", x: ["1", "2"], y: [1, 2] },
    { name: "b", x: ["1", "2"], y: [3, 4] },
    { name: "c", x: ["1", "2"], y: [5, 6] },
  ],
};

test("view state key combines conversation, request and artifact identity", () => {
  assert.equal(chartViewStateKey("conv", "req-1", "chart:c1"), "conv|req-1|chart:c1");
  assert.equal(chartViewStateKey(undefined, " req ", "chart:seq:0"), "|req|chart:seq:0");
});

test("series selection uses original indexes, is immutable and stays sorted", () => {
  let state = createChartViewState(chart);
  assert.deepEqual(state.hiddenSeriesKeys, []);
  assert.equal(state.yDomainPolicy, "full");
  const next = toggleSeriesVisibility(toggleSeriesVisibility(state, 2), 0);
  assert.deepEqual(next.hiddenSeriesKeys, [0, 2]);
  assert.deepEqual(state.hiddenSeriesKeys, [], "previous state untouched");
  assert.equal(isSeriesHidden(next, 2), true);
  assert.deepEqual(toggleSeriesVisibility(next, 2).hiddenSeriesKeys, [0]);
  assert.deepEqual(showAllSeries(next).hiddenSeriesKeys, []);
  assert.equal(setYDomainPolicy(next, "visible").yDomainPolicy, "visible");
  assert.equal(setYDomainPolicy(next, "bogus").yDomainPolicy, "full");
  assert.equal(setNotesOpen(next, true).notesOpen, true);
  const pie = createChartViewState({ kind: "pie", series: [{ name: "p", x: ["a", "b"], y: [1, 2] }] });
  assert.equal(toggleSliceFocus(pie, 1).focusedSlice, 1);
  assert.equal(toggleSliceFocus(toggleSliceFocus(pie, 1), 1).focusedSlice, null);
});

test("a new data snapshot under the same identity resets series selection but keeps policy and notes", () => {
  const state = setNotesOpen(setYDomainPolicy(toggleSeriesVisibility(createChartViewState(chart), 1), "visible"), true);
  const same = reconcileChartViewState(state, chart);
  assert.deepEqual(same.hiddenSeriesKeys, [1]);
  const changed = { ...chart, series: [chart.series[0], { name: "b", x: ["1", "2", "3"], y: [3, 4, 5] }] };
  assert.notEqual(chartSnapshotSignature(changed), chartSnapshotSignature(chart));
  const reset = reconcileChartViewState(state, changed);
  assert.deepEqual(reset.hiddenSeriesKeys, [], "indexes could point at the wrong series, so they reset");
  assert.equal(reset.yDomainPolicy, "visible");
  assert.equal(reset.notesOpen, true);
  assert.equal(reset.focusedSlice, null);
  assert.deepEqual(reconcileChartViewState(null, chart), createChartViewState(chart));
  const stale = reconcileChartViewState({ ...state, hiddenSeriesKeys: [1, 7, -1, 1.5] }, chart);
  assert.deepEqual(stale.hiddenSeriesKeys, [1], "out-of-range keys are dropped");
});

test("the snapshot signature covers every value, so interior changes and reorders reset selection", () => {
  const pie = { kind: "pie", series: [{ name: "status", x: ["Idle", "Running", "Fault"], y: [1, 2, 3] }] };
  const replaced = { kind: "pie", series: [{ name: "status", x: ["Idle", "Maintenance", "Fault"], y: [1, 20, 3] }] };
  assert.notEqual(chartSnapshotSignature(pie), chartSnapshotSignature(replaced), "unchanged endpoints must not collide");
  const focused = toggleSliceFocus(createChartViewState(pie), 1);
  assert.equal(reconcileChartViewState(focused, replaced).focusedSlice, null, "focus resets on a replaced category");
  const reordered = { kind: "pie", series: [{ name: "status", x: ["Running", "Idle", "Fault"], y: [2, 1, 3] }] };
  assert.notEqual(chartSnapshotSignature(pie), chartSnapshotSignature(reordered));
  const interior = { ...chart, series: [chart.series[0], { name: "b", x: ["1", "2"], y: [3, 9] }, chart.series[2]] };
  assert.equal(chart.series[1].y[1], 4);
  assert.notEqual(chartSnapshotSignature(chart), chartSnapshotSignature(interior), "an interior y change is detected");
  const hidden = toggleSeriesVisibility(createChartViewState(chart), 1);
  assert.deepEqual(reconcileChartViewState(hidden, interior).hiddenSeriesKeys, []);
  const equivalent = JSON.parse(JSON.stringify(chart));
  assert.equal(chartSnapshotSignature(chart), chartSnapshotSignature(equivalent), "a structurally equal snapshot keeps its signature");
  assert.deepEqual(reconcileChartViewState(hidden, equivalent).hiddenSeriesKeys, [1], "selection is retained for an equivalent snapshot");
  const typed = { kind: "bar", series: [{ name: "s", x: ["1"], y: [1] }] };
  const typedText = { kind: "bar", series: [{ name: "s", x: [1], y: [1] }] };
  assert.notEqual(chartSnapshotSignature(typed), chartSnapshotSignature(typedText), "value types are part of the signature");
});

test("describeChartView states hidden series, policy and focus in plain text", () => {
  const legend = chart.series.map((s, i) => ({ label: s.name, slot: i }));
  const none = createChartViewState(chart);
  assert.equal(describeChartView(none, chart, legend), "");
  const one = toggleSeriesVisibility(none, 1);
  assert.equal(describeChartView(one, chart, legend), "1 of 3 series hidden (b)");
  const fit = setYDomainPolicy(one, "visible");
  assert.equal(describeChartView(fit, chart, legend), "1 of 3 series hidden (b) · Y axis fitted to visible series");
  const all = toggleSeriesVisibility(toggleSeriesVisibility(one, 0), 2);
  assert.equal(describeChartView(all, chart, legend), "No series selected");
  const pieChart = { kind: "pie", series: [{ name: "p", x: ["a", "b"], y: [1, 2] }] };
  const pieLegend = [{ label: "a", slot: 0 }, { label: "b", slot: 1 }];
  assert.equal(describeChartView(toggleSliceFocus(createChartViewState(pieChart), 1), pieChart, pieLegend), "Focused slice: b");
});

test("zoom and selection are stored in X units, clamped to the full domain, and survive a new snapshot", () => {
  const numeric = { kind: "line", series: [{ name: "a", x: ["10", "20", "30"], y: [1, 2, 3] }, { name: "b", x: ["10", "20", "30"], y: [3, 2, 1] }] };
  const base = createChartViewState(numeric);
  assert.equal(base.viewXDomain, null);
  const zoomed = setSelectedRange(setViewXDomain(base, [12, 25]), [15, 18]);
  const same = reconcileChartViewState(zoomed, numeric);
  assert.deepEqual([same.viewXDomain, same.selectedRange], [[12, 25], [15, 18]]);
  assert.deepEqual(reconcileChartViewState(setViewXDomain(base, [5, 25]), numeric).viewXDomain, [10, 25], "start clamped to the domain");
  assert.deepEqual(reconcileChartViewState(setViewXDomain(base, [12, 99]), numeric).viewXDomain, [12, 30], "end clamped to the domain");
  assert.equal(reconcileChartViewState(setViewXDomain(base, [25, 12]), numeric).viewXDomain, null, "inverted ranges are dropped");
  assert.equal(reconcileChartViewState(setViewXDomain(base, [40, 50]), numeric).viewXDomain, null, "ranges outside the domain are dropped");
  assert.equal(reconcileChartViewState(setViewXDomain(base, [10, 30]), numeric).viewXDomain, null, "the whole domain is not a zoom");
  assert.equal(reconcileChartViewState(setViewXDomain(base, [Number.NaN, 20]), numeric).viewXDomain, null);
  assert.equal(reconcileChartViewState({ ...base, viewXDomain: "12,25" }, numeric).viewXDomain, null, "malformed host state is ignored");
  const grown = { ...numeric, series: [{ name: "a", x: ["10", "20", "30", "40"], y: [1, 2, 3, 4] }, { name: "b", x: ["10", "20", "30", "40"], y: [4, 3, 2, 1] }] };
  const kept = reconcileChartViewState(zoomed, grown);
  assert.deepEqual([kept.viewXDomain, kept.selectedRange], [[12, 25], [15, 18]], "a new snapshot keeps a zoom that still fits");
  const shrunk = { ...numeric, series: [{ name: "a", x: ["20", "30"], y: [2, 3] }, { name: "b", x: ["20", "30"], y: [2, 1] }] };
  const clamped = reconcileChartViewState(zoomed, shrunk);
  assert.deepEqual(clamped.viewXDomain, [20, 25]);
  assert.equal(clamped.selectedRange, null, "a selection outside the new domain is cleared");
  const fullSel = reconcileChartViewState(setSelectedRange(base, [10, 30]), numeric);
  assert.deepEqual(fullSel.selectedRange, [10, 30], "a full-domain selection is kept: it is not the no-zoom sentinel");
  const grownSel = reconcileChartViewState(setSelectedRange(base, [12, 25]), { ...numeric, series: [{ name: "a", x: ["12", "25"], y: [1, 2] }, { name: "b", x: ["12", "25"], y: [2, 1] }] });
  assert.deepEqual(grownSel.selectedRange, [12, 25], "a selection that becomes the whole domain after reconciliation survives");
  const wide = reconcileChartViewState(setSelectedRange(base, [0, 100]), numeric);
  assert.deepEqual(wide.selectedRange, [10, 30], "a selection past the bounds is clamped to them");
  assert.deepEqual(reconcileChartViewState(setViewXDomain(base, [12, 12.001]), numeric).viewXDomain, [12, 12.02], "an undersized zoom is widened to 1/1000 of the domain on reconcile");
  assert.deepEqual(reconcileChartViewState(setViewXDomain(base, [29.999, 30]), numeric).viewXDomain, [29.98, 30], "widening stays inside the hard bounds");
  const bar = { kind: "bar", series: [{ name: "a", x: ["p", "q"], y: [1, 2] }] };
  assert.equal(reconcileChartViewState(setViewXDomain(createChartViewState(bar), [0, 1]), bar).viewXDomain, null, "bar charts have no X view");
});

test("reset restores the full analysis view but leaves the notes disclosure alone", () => {
  const busy = setNotesOpen(setSelectedRange(setViewXDomain(setYDomainPolicy(toggleSeriesVisibility(createChartViewState(chart), 1), "visible"), [1, 1.5]), [1.2, 1.4]), true);
  assert.equal(isChartViewDefault(busy), false);
  const reset = resetChartView(busy);
  assert.deepEqual(
    [reset.hiddenSeriesKeys, reset.yDomainPolicy, reset.viewXDomain, reset.selectedRange, reset.focusedSlice, reset.notesOpen],
    [[], "full", null, null, null, true]
  );
  assert.equal(isChartViewDefault(reset), true);
  assert.equal(reset.snapshot, busy.snapshot);
  assert.equal(busy.viewXDomain[0], 1, "previous state untouched");
});

test("describeChartView states the zoom and the selection in the chart's X units", () => {
  const legend = chart.series.map((s, i) => ({ label: s.name, slot: i }));
  const zoomed = setViewXDomain(createChartViewState(chart), [1.25, 1.75]);
  assert.equal(describeChartView(zoomed, chart, legend), "Zoomed to 1.25 – 1.75 of 1 – 2");
  const selected = setSelectedRange(zoomed, [1.5, 1.6]);
  assert.equal(describeChartView(selected, chart, legend), "Zoomed to 1.25 – 1.75 of 1 – 2 · Selected: 1.5 – 1.6");
  const hidden = toggleSeriesVisibility(selected, 0);
  assert.match(describeChartView(hidden, chart, legend), /^1 of 3 series hidden \(a\) · Zoomed to /);
  const elapsed = { kind: "line", xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 600 }, series: [{ name: "r", x: ["0", "600"], y: [1, 2] }] };
  assert.equal(describeChartView(setViewXDomain(createChartViewState(elapsed), [60, 150]), elapsed, []), "Zoomed to 1:00 elapsed – 2:30 elapsed of 0:00 elapsed – 10:00 elapsed");
  const absolute = { kind: "line", series: [{ name: "r", x: ["2026-09-12T00:00:00Z", "2026-09-12T01:00:00Z"], y: [1, 2] }] };
  const t0 = Date.parse("2026-09-12T00:15:00Z");
  const t1 = Date.parse("2026-09-12T00:45:00Z");
  const text = describeChartView(setViewXDomain(createChartViewState(absolute), [t0, t1]), absolute, []);
  assert.match(text, /^Zoomed to .+ – .+ \(.+\) of .+ – .+ \(.+\)$/, "absolute ranges name the display zone once per range");
  assert.equal(describeChartView(setSelectedRange(createChartViewState(chart), [0, 0.5]), chart, legend), "", "a selection outside the domain is not described");
  assert.equal(describeChartView(setSelectedRange(createChartViewState(chart), [1, 2]), chart, legend), "Selected: 1 – 2", "a full-domain selection is described");
});

test("a histogram snapshot signature covers its edges, counts, densities and mode", () => {
  const h = { kind: "histogram", histogram: { edges: [0, 1, 2], counts: [1, 1], densities: [0.5, 0.5], mode: "count", validCount: 2, excludedCount: 0, belowRangeCount: 0, aboveRangeCount: 0, method: "equal_width_v1" } };
  const base = chartSnapshotSignature(h);
  assert.ok(base.startsWith("histogram|count:"));
  assert.notEqual(chartSnapshotSignature({ ...h, histogram: { ...h.histogram, counts: [2, 0] } }), base);
  assert.notEqual(chartSnapshotSignature({ ...h, histogram: { ...h.histogram, mode: "density" } }), base);
  assert.equal(chartSnapshotSignature(structuredClone(h)), base, "structurally equal payloads share a signature");
  const state = createChartViewState(h);
  assert.equal(state.snapshot, base);
  assert.deepEqual(reconcileChartViewState(state, h), state);
});

test("a boxplot snapshot signature covers every group statistic and the listed outliers", () => {
  const g = { key: "A", n: 3, excludedCount: 0, min: 1, whiskerLow: 1, q1: 1.5, median: 2, q3: 2.5, whiskerHigh: 3, max: 3, outliers: [], outlierCount: 0 };
  const b = { kind: "boxplot", boxplot: { method: "tukey_1_5_iqr_linear_p_v1", groups: [g] } };
  const base = chartSnapshotSignature(b);
  assert.ok(base.startsWith("boxplot|tukey_1_5_iqr_linear_p_v1:1:"));
  assert.notEqual(chartSnapshotSignature({ ...b, boxplot: { ...b.boxplot, groups: [{ ...g, median: 2.1 }] } }), base);
  assert.notEqual(chartSnapshotSignature({ ...b, boxplot: { ...b.boxplot, groups: [{ ...g, outliers: [0.5], outlierCount: 1, whiskerLow: 1 }] } }), base);
  assert.notEqual(chartSnapshotSignature({ ...b, boxplot: { ...b.boxplot, groups: [{ ...g, key: "B" }] } }), base);
  assert.equal(chartSnapshotSignature(structuredClone(b)), base);
  const state = createChartViewState(b);
  assert.equal(state.snapshot, base);
  assert.deepEqual(reconcileChartViewState(state, b), state);
});

test("a heatmap snapshot signature covers keys, every cell and the missing marker", () => {
  const h = { kind: "heatmap", heatmap: { rows: ["A"], cols: ["x", "y"], values: [[1, null]], valueLabel: "v", missingCount: 1 } };
  const base = chartSnapshotSignature(h);
  assert.ok(base.startsWith("heatmap|v:"));
  assert.notEqual(chartSnapshotSignature({ ...h, heatmap: { ...h.heatmap, values: [[1, 0]], missingCount: 0 } }), base, "null and 0 differ");
  assert.notEqual(chartSnapshotSignature({ ...h, heatmap: { ...h.heatmap, cols: ["x", "z"] } }), base);
  assert.equal(chartSnapshotSignature(structuredClone(h)), base);
  const state = createChartViewState(h);
  assert.equal(state.snapshot, base);
  assert.deepEqual(reconcileChartViewState(state, h), state);
});
