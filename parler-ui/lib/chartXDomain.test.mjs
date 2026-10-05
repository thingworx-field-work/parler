import assert from "node:assert/strict";
import test from "node:test";
import {
  X_VIEW_STEPS,
  chartXDomainInfo,
  clampXRange,
  clampXSelection,
  minimumXSpan,
  formatXRange,
  formatXValue,
  lineScatterXs,
  parseElapsedDomain,
  parseLineScatterXCell,
  parseNormalizedDomain,
  parseRequestedTimeRange,
} from "./chartXDomain.js";

test("X cells parse strictly: finite numbers or ISO instants, nothing else", () => {
  assert.deepEqual(parseLineScatterXCell(" 12.5 "), { t: "n", v: 12.5 });
  assert.equal(parseLineScatterXCell("2026-09-12T00:00:00Z").t, "d");
  assert.equal(parseLineScatterXCell("2026-09-12T00:00:00Z").v.getTime(), Date.parse("2026-09-12T00:00:00Z"));
  assert.equal(parseLineScatterXCell("garbage"), null);
  assert.equal(parseLineScatterXCell(""), null);
  assert.equal(parseLineScatterXCell(null), null);
  assert.equal(parseLineScatterXCell("Infinity"), null);
  assert.equal(lineScatterXs([{ x: ["1", "2026-09-12T00:00:00Z"] }]), null, "mixed types fail as a whole");
  assert.deepEqual(lineScatterXs([{ x: ["1", "2"] }, { x: ["3"] }]), { allDates: false, xsPerSeries: [[1, 2], [3]] });
});

test("fixed-domain modes parse their own domains and reject the wrong mode or inverted bounds", () => {
  assert.deepEqual(parseElapsedDomain({ xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 60 } }), [0, 60]);
  assert.equal(parseElapsedDomain({ xAxisMode: "normalized", elapsedDomain: { start: 0, end: 60 } }), null);
  assert.equal(parseElapsedDomain({ xAxisMode: "elapsed", elapsedDomain: { start: 60, end: 0 } }), null);
  assert.deepEqual(parseNormalizedDomain({ xAxisMode: "normalized", normalizedDomain: { start: 0, end: 1 } }), [0, 1]);
  assert.equal(parseNormalizedDomain({ xAxisMode: "normalized", normalizedDomain: { start: "x", end: 1 } }), null);
  const req = parseRequestedTimeRange({ requested_time_range: { start: "2026-09-12T01:00:00Z", end: "2026-09-12T00:00:00Z" } });
  assert.ok(req[0] < req[1], "inverted requested ranges are ordered");
  assert.equal(parseRequestedTimeRange({ requested_time_range: { start: 5, end: "2026-09-12T00:00:00Z" } }), null);
});

test("chartXDomainInfo uses the drawing precedence: requested window, fixed domain, else data extent", () => {
  const iso = ["2026-09-12T00:00:00Z", "2026-09-12T00:10:00Z"];
  const data = chartXDomainInfo({ kind: "line", series: [{ x: iso, y: [1, 2] }] });
  assert.deepEqual(data, { mode: "absolute", domain: [Date.parse(iso[0]), Date.parse(iso[1])] });
  const req = chartXDomainInfo({
    kind: "scatter",
    requested_time_range: { start: "2026-09-11T00:00:00Z", end: "2026-09-13T00:00:00Z" },
    series: [{ x: iso, y: [1, 2] }],
  });
  assert.deepEqual(req.domain, [Date.parse("2026-09-11T00:00:00Z"), Date.parse("2026-09-13T00:00:00Z")]);
  assert.deepEqual(chartXDomainInfo({ kind: "line", xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 90 }, series: [{ x: ["0", "5"], y: [1, 2] }] }), { mode: "elapsed", domain: [0, 90] });
  assert.deepEqual(chartXDomainInfo({ kind: "line", xAxisMode: "normalized", normalizedDomain: { start: 0, end: 1 }, series: [{ x: ["0.2", "0.4"], y: [1, 2] }] }), { mode: "normalized", domain: [0, 1] });
  assert.deepEqual(chartXDomainInfo({ kind: "line", series: [{ x: ["3", "1"], y: [1, 2] }, { x: ["7"], y: [1] }] }), { mode: "numeric", domain: [1, 7] });
  assert.equal(chartXDomainInfo({ kind: "bar", series: [{ x: ["a"], y: [1] }] }), null);
  assert.equal(chartXDomainInfo({ kind: "pie", series: [{ x: ["a"], y: [1] }] }), null);
  assert.equal(chartXDomainInfo({ kind: "line", series: [{ x: ["bad"], y: [1] }] }), null);
  assert.equal(chartXDomainInfo({ kind: "line", series: [] }), null);
  assert.equal(chartXDomainInfo(null), null);
});

test("clampXSelection keeps a full-domain selection and only drops empty, inverted or outside ranges", () => {
  assert.deepEqual(clampXSelection([0, 10], [0, 10]), [0, 10], "the whole domain is a valid selection");
  assert.deepEqual(clampXSelection([-5, 50], [0, 10]), [0, 10]);
  assert.deepEqual(clampXSelection([2, 5], [0, 10]), [2, 5]);
  assert.deepEqual(clampXSelection([2, 2.0001], [0, 10]), [2, 2.0001], "no minimum span for a selection");
  assert.equal(clampXSelection([5, 2], [0, 10]), null);
  assert.equal(clampXSelection([20, 30], [0, 10]), null);
  assert.equal(clampXSelection([3, 3], [0, 10]), null);
  assert.equal(clampXSelection([2, 5], [10, 10]), null);
  assert.equal(clampXSelection(null, [0, 10]), null);
});

test("clampXRange widens an undersized zoom to the minimum span inside the hard bounds", () => {
  assert.equal(X_VIEW_STEPS, 1000);
  assert.equal(minimumXSpan([0, 10]), 0.01);
  assert.deepEqual(clampXRange([5, 5.001], [0, 10]), [5, 5.01], "widened forward to 1/1000 of the domain");
  assert.deepEqual(clampXRange([9.995, 9.999], [0, 10]), [9.99, 10], "widened backward at the upper bound");
  assert.deepEqual(clampXRange([0, 0.001], [0, 10]), [0, 0.01]);
  assert.deepEqual(clampXRange([5, 5.01], [0, 10]), [5, 5.01], "exactly the minimum passes unchanged");
  assert.deepEqual(clampXRange([0, 1e-9], [0, 10]), [0, 0.01], "a hairline zoom at the lower bound still leaves a zoom, never the whole domain");
});

test("clampXRange keeps ranges inside the full domain and rejects empty, inverted or whole-domain ranges", () => {
  assert.deepEqual(clampXRange([2, 5], [0, 10]), [2, 5]);
  assert.deepEqual(clampXRange([-5, 5], [0, 10]), [0, 5]);
  assert.deepEqual(clampXRange([2, 50], [0, 10]), [2, 10]);
  assert.equal(clampXRange([5, 2], [0, 10]), null);
  assert.equal(clampXRange([20, 30], [0, 10]), null);
  assert.equal(clampXRange([0, 10], [0, 10]), null);
  assert.equal(clampXRange([3, 3], [0, 10]), null);
  assert.equal(clampXRange(["2", "5"], [0, 10]) === null, false, "numeric strings are tolerated");
  assert.equal(clampXRange([2, 5], [10, 10]), null, "a degenerate full domain has no zoom");
  assert.equal(clampXRange(null, [0, 10]), null);
  assert.equal(clampXRange([1], [0, 10]), null);
});

test("formatXValue and formatXRange speak each mode's units", () => {
  assert.equal(formatXValue("numeric", 12.5), "12.5");
  assert.equal(formatXValue("elapsed", 90), "1:30 elapsed");
  assert.equal(formatXValue("normalized", 0.25), "0.25 (25%)");
  assert.equal(formatXValue("normalized", 0.123456), "0.1235 (12%)");
  const t = Date.parse("2026-09-12T00:00:00Z");
  assert.match(formatXValue("absolute", t), /2026/);
  assert.equal(formatXRange("elapsed", [0, 90]), "0:00 elapsed – 1:30 elapsed");
  assert.match(formatXRange("absolute", [t, t + 60_000]), /^.+ – .+ \(.+\)$/, "absolute ranges carry the zone once");
  assert.equal(formatXRange("numeric", [1, 2]), "1 – 2");
});
