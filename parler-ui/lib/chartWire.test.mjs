import test from "node:test";
import assert from "node:assert/strict";
import { chartBoxplotRejection, chartHeatmapRejection, chartHistogramRejection, asChartBlock } from "./wireAdapter.js";

test("asChartBlock accepts pie with one series and positive total", () => {
  const b = asChartBlock({
    kind: "pie",
    title: "T",
    series: [{ name: "n", x: ["a", "b"], y: [1, 3] }],
  });
  assert.ok(b);
  assert.equal(b.kind, "pie");
  assert.equal(b.series.length, 1);
  assert.ok(!("y_reference_lines" in b));
});

test("asChartBlock rejects pie with multiple series", () => {
  assert.equal(
    asChartBlock({
      kind: "pie",
      series: [
        { name: "a", x: ["x"], y: [1] },
        { name: "b", x: ["x"], y: [2] },
      ],
    }),
    null
  );
});

test("asChartBlock rejects pie when sum of y is zero", () => {
  assert.equal(
    asChartBlock({
      kind: "pie",
      series: [{ name: "n", x: ["a", "b"], y: [0, 0] }],
    }),
    null
  );
});

test("asChartBlock rejects pie with negative y", () => {
  assert.equal(
    asChartBlock({
      kind: "pie",
      series: [{ name: "n", x: ["a"], y: [-1] }],
    }),
    null
  );
});

test("asChartBlock accepts valid elapsed PoP line chart", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 3600 },
    series: [
      { name: "Today", x: ["0", "300"], y: [1, 2] },
      { name: "Yesterday", x: ["0", "600"], y: [3, 4] },
    ],
  });
  assert.ok(b);
  assert.equal(b.xAxisMode, "elapsed");
  assert.ok(!("requested_time_range" in b));
});

test("asChartBlock rejects elapsed chart without elapsedDomain", () => {
  assert.equal(
    asChartBlock({
      kind: "line",
      xAxisMode: "elapsed",
      series: [{ name: "A", x: ["0"], y: [1] }],
    }),
    null
  );
});

test("asChartBlock rejects elapsed chart with non-integer x", () => {
  assert.equal(
    asChartBlock({
      kind: "line",
      xAxisMode: "elapsed",
      elapsedDomain: { start: 0, end: 3600 },
      series: [{ name: "A", x: ["2026-06-30T14:00:00.000Z"], y: [1] }],
    }),
    null
  );
});

test("asChartBlock drops requested_time_range on elapsed charts", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 60 },
    requested_time_range: { start: "2026-01-01T00:00:00.000Z", end: "2026-01-01T01:00:00.000Z" },
    series: [{ name: "A", x: ["0"], y: [1] }],
  });
  assert.ok(b);
  assert.ok(!("requested_time_range" in b));
});

test("asChartBlock preserves sourceWindow on elapsed charts", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 3600 },
    series: [{
      name: "Today",
      x: ["0", "300"],
      y: [1, 2],
      sourceWindow: {
        start: "2026-06-30T14:00:00.000Z",
        end: "2026-06-30T15:00:00.000Z",
        thingName: "ORD-Mixer-01",
        propertyName: "currentDraw",
        periodLabel: "Today",
        sampleCount: 2,
      },
    }],
  });
  assert.ok(b);
  assert.equal(b.series[0].sourceWindow.thingName, "ORD-Mixer-01");
});

test("asChartBlock accepts mixed elapsed overlay with reference lines (M1)", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 3600 },
    y_reference_lines: [
      { y: 8, role: "usl", label: "Upper limit" },
      { y: 2, role: "lsl", label: "Lower limit" },
    ],
    series: [
      { name: "Robot01 now", x: ["0", "1800", "3600"], y: [1, 2, 3] },
      { name: "Robot01 lw", x: ["0", "1800"], y: [4, 5] },
      {
        name: "Robot02 lw",
        x: ["0", "900"],
        y: [6, 7],
        sourceWindow: {
          start: "2026-06-25T14:00:00.000Z",
          end: "2026-06-25T15:00:00.000Z",
          thingName: "Robot-02",
          propertyName: "currentDraw",
          periodLabel: "Robot02 lw",
          sampleCount: 2,
        },
      },
    ],
  });
  assert.ok(b);
  assert.equal(b.series.length, 3);
  assert.equal(b.y_reference_lines.length, 2);
  assert.equal(b.elapsedDomain.end, 3600);
});

test("asChartBlock accepts valid normalized overlay line chart", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "normalized",
    normalizedDomain: { start: 0, end: 1 },
    series: [
      { name: "30m", x: ["0", "0.5", "1"], y: [1, 2, 3] },
      { name: "45m", x: ["0", "0.666667", "1"], y: [4, 5, 6] },
    ],
  });
  assert.ok(b);
  assert.equal(b.xAxisMode, "normalized");
  assert.ok(!("requested_time_range" in b));
  assert.ok(!("elapsedDomain" in b));
});

test("asChartBlock rejects normalized chart without normalizedDomain", () => {
  assert.equal(
    asChartBlock({
      kind: "line",
      xAxisMode: "normalized",
      series: [{ name: "A", x: ["0", "1"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock rejects normalized chart with out-of-range x", () => {
  assert.equal(
    asChartBlock({
      kind: "line",
      xAxisMode: "normalized",
      normalizedDomain: { start: 0, end: 1 },
      series: [{ name: "A", x: ["0", "1.5"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock drops requested_time_range on normalized charts", () => {
  const b = asChartBlock({
    kind: "line",
    xAxisMode: "normalized",
    normalizedDomain: { start: 0, end: 1 },
    requested_time_range: { start: "2026-01-01T00:00:00.000Z", end: "2026-01-01T01:00:00.000Z" },
    series: [{ name: "A", x: ["0", "1"], y: [1, 2] }],
  });
  assert.ok(b);
  assert.ok(!("requested_time_range" in b));
});

test("asChartBlock accepts normalized overlay with reference lines", () => {
  const b = asChartBlock({
    kind: "scatter",
    xAxisMode: "normalized",
    normalizedDomain: { start: 0, end: 1 },
    y_reference_lines: [{ y: 8, role: "usl" }],
    series: [
      { name: "A", x: ["0", "1"], y: [1, 2] },
      { name: "B", x: ["0", "0.5"], y: [3, 4] },
    ],
  });
  assert.ok(b);
  assert.equal(b.kind, "scatter");
  assert.equal(b.y_reference_lines.length, 1);
});

test("asChartBlock rejects elapsed bar charts", () => {
  assert.equal(
    asChartBlock({
      kind: "bar",
      xAxisMode: "elapsed",
      elapsedDomain: { start: 0, end: 3600 },
      series: [{ name: "A", x: ["0", "60"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock rejects normalized bar charts", () => {
  assert.equal(
    asChartBlock({
      kind: "bar",
      xAxisMode: "normalized",
      normalizedDomain: { start: 0, end: 1 },
      series: [{ name: "A", x: ["0", "1"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock rejects normalized chart with non-unit domain", () => {
  assert.equal(
    asChartBlock({
      kind: "line",
      xAxisMode: "normalized",
      normalizedDomain: { start: -1, end: 2 },
      series: [{ name: "A", x: ["0", "1"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock rejects elapsed pie charts", () => {
  assert.equal(
    asChartBlock({
      kind: "pie",
      xAxisMode: "elapsed",
      elapsedDomain: { start: 0, end: 3600 },
      series: [{ name: "A", x: ["a", "b"], y: [1, 2] }],
    }),
    null
  );
});

test("asChartBlock rejects normalized pie charts", () => {
  assert.equal(
    asChartBlock({
      kind: "pie",
      xAxisMode: "normalized",
      normalizedDomain: { start: 0, end: 1 },
      series: [{ name: "A", x: ["a", "b"], y: [1, 2] }],
    }),
    null
  );
});

// ---------------------------------------------------------------------------
// V1 input-validation alignment (design §5.3, CHART_CONTRACT §3.3 inv. 1/3, §4.2)
// ---------------------------------------------------------------------------

import { CHART_REJECTION_CODES } from "./wireAdapter.js";

const ISO_X = ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"];
const ELAPSED = { xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 60 } };
const NORMALIZED = { xAxisMode: "normalized", normalizedDomain: { start: 0, end: 1 } };

/**
 * Valid base charts for the full §5.3 matrix: line and scatter in each of absolute ISO,
 * numeric, elapsed and normalized X, plus categorical bar and pie. Every one MUST be accepted
 * before mutation.
 */
const V1_BASES = {
  absoluteLine: { kind: "line", series: [{ name: "t", x: ISO_X, y: [1, 2] }] },
  absoluteScatter: { kind: "scatter", series: [{ name: "t", x: ISO_X, y: [1, 2] }] },
  numericLine: { kind: "line", series: [{ name: "n", x: ["1", "2"], y: [1, 2] }] },
  numericScatter: { kind: "scatter", series: [{ name: "n", x: ["1", "2"], y: [1, 2] }] },
  elapsedLine: { kind: "line", ...ELAPSED, series: [{ name: "e", x: ["0", "30"], y: [1, 2] }] },
  elapsedScatter: { kind: "scatter", ...ELAPSED, series: [{ name: "e", x: ["0", "30"], y: [1, 2] }] },
  normalizedLine: { kind: "line", ...NORMALIZED, series: [{ name: "f", x: ["0", "0.5"], y: [1, 2] }] },
  normalizedScatter: { kind: "scatter", ...NORMALIZED, series: [{ name: "f", x: ["0", "0.5"], y: [1, 2] }] },
  categoricalBar: { kind: "bar", series: [{ name: "b", x: ["a", "b"], y: [1, 2] }] },
  pie: { kind: "pie", series: [{ name: "p", x: ["a", "b"], y: [1, 2] }] },
};

const sparse = [];
sparse[1] = 1;
/** Illegal Y arrays; each must reject in every base. */
const ILLEGAL_Y = {
  null: [null, 10],
  numericString: [5, "5"],
  boolean: [true, 1],
  nan: [Number.NaN, 1],
  infinity: [Number.POSITIVE_INFINITY, 1],
  sparse,
};

function withY(base, y) {
  return { ...base, series: [{ ...base.series[0], y }] };
}

function captureWarn(fn) {
  const original = console.warn;
  const lines = [];
  console.warn = (msg) => lines.push(String(msg));
  try {
    return { result: fn(), lines };
  } finally {
    console.warn = original;
  }
}

test("V1: every base kind × X mode is accepted with y kept verbatim", () => {
  for (const [name, base] of Object.entries(V1_BASES)) {
    const { result, lines } = captureWarn(() => asChartBlock(base));
    assert.ok(result, `${name} must be accepted`);
    assert.deepEqual(result.series[0].y, base.series[0].y, `${name} keeps y`);
    assert.equal(lines.length, 0, `${name} logs nothing`);
  }
});

test("V1: illegal y values reject the whole chart in every kind and X mode", () => {
  for (const [baseName, base] of Object.entries(V1_BASES)) {
    for (const [yName, y] of Object.entries(ILLEGAL_Y)) {
      const { result, lines } = captureWarn(() => asChartBlock(withY(base, y)));
      assert.equal(result, null, `${baseName} with ${yName} y must be rejected`);
      assert.equal(lines.length, 1);
      assert.ok(
        lines[0].includes(CHART_REJECTION_CODES.Y_NOT_FINITE_NUMBER),
        `${baseName}/${yName}: ${lines[0]}`
      );
    }
  }
});

test("V1: elapsed and normalized modes no longer coerce numeric-string y", () => {
  assert.equal(asChartBlock(withY(V1_BASES.elapsedLine, ["1", "2"])), null);
  assert.equal(asChartBlock(withY(V1_BASES.normalizedScatter, ["1", "2"])), null);
  assert.equal(asChartBlock(withY(V1_BASES.pie, ["1", "2"])), null);
});

test("V1: the matrix covers line and scatter in all four X modes plus bar and pie", () => {
  const combos = new Set(
    Object.values(V1_BASES).map((b) => `${b.kind}:${b.xAxisMode ?? (b.kind === "bar" || b.kind === "pie" ? "categorical" : Number.isFinite(Number(b.series[0].x[0])) ? "numeric" : "absolute")}`)
  );
  for (const kind of ["line", "scatter"]) {
    for (const mode of ["absolute", "numeric", "elapsed", "normalized"]) {
      assert.ok(combos.has(`${kind}:${mode}`), `missing ${kind} in ${mode} mode`);
    }
  }
  assert.ok(combos.has("bar:categorical") && combos.has("pie:categorical"));
});

test("V1: legal numeric zero is kept as zero", () => {
  for (const name of Object.keys(V1_BASES).filter((n) => n !== "pie")) {
    const both = asChartBlock(withY(V1_BASES[name], [0, 0]));
    assert.ok(both, `${name} accepts [0, 0]`);
    assert.deepEqual(both.series[0].y, [0, 0]);
    const one = asChartBlock(withY(V1_BASES[name], [0, 3]));
    assert.deepEqual(one.series[0].y, [0, 3]);
  }
  const pie = asChartBlock(withY(V1_BASES.pie, [0, 3]));
  assert.ok(pie, "pie accepts a zero slice when the total is positive");
  assert.deepEqual(pie.series[0].y, [0, 3]);
  assert.equal(asChartBlock(withY(V1_BASES.pie, [0, 0])), null, "pie zero total still rejected");
});

test("V1: series shape failures reject the whole chart", () => {
  const base = V1_BASES.categoricalBar;
  const cases = {
    lengthMismatch: [{ name: "b", x: ["a", "b"], y: [1] }],
    emptyX: [{ name: "b", x: [], y: [] }],
    yNotArray: [{ name: "b", x: ["a"], y: 1 }],
    seriesNotObject: [null],
    oneBadAmongTwo: [
      { name: "ok", x: ["a", "b"], y: [1, 2] },
      { name: "bad", x: ["a", "b"], y: [1] },
    ],
  };
  for (const [name, series] of Object.entries(cases)) {
    const { result, lines } = captureWarn(() => asChartBlock({ ...base, series }));
    assert.equal(result, null, `${name} must be rejected`);
    assert.ok(lines[0].includes(CHART_REJECTION_CODES.SERIES_SHAPE), `${name}: ${lines[0]}`);
  }
  const { lines } = captureWarn(() => asChartBlock({ ...base, series: [] }));
  assert.ok(lines[0].includes(CHART_REJECTION_CODES.SERIES_EMPTY));
  const kind = captureWarn(() => asChartBlock({ kind: "area", series: base.series }));
  assert.equal(kind.result, null);
  assert.ok(kind.lines[0].includes(CHART_REJECTION_CODES.KIND_INVALID));
});

test("V1: one bad series rejects the chart even when other valid series differ in point count", () => {
  const accepted = asChartBlock({
    kind: "line",
    series: [
      { name: "dense", x: ["1", "2", "3"], y: [1, 2, 3] },
      { name: "sparse", x: ["1", "3"], y: [1, 3] },
    ],
  });
  assert.ok(accepted, "valid series with different point counts stay accepted");
  const rejected = asChartBlock({
    kind: "line",
    series: [
      { name: "dense", x: ["1", "2", "3"], y: [1, 2, 3] },
      { name: "sparse", x: ["1", "3"], y: [1, null] },
    ],
  });
  assert.equal(rejected, null);
});

test("V1: rejection never throws on malformed identity metadata and keeps the reason code", () => {
  const unprintable = { toString: null };
  const withBadId = captureWarn(() =>
    asChartBlock({ kind: "bar", chartId: unprintable, series: [{ name: "s", x: ["a"], y: [null] }] })
  );
  assert.equal(withBadId.result, null);
  assert.equal(withBadId.lines.length, 1);
  assert.ok(withBadId.lines[0].includes(CHART_REJECTION_CODES.Y_NOT_FINITE_NUMBER));
  assert.ok(withBadId.lines[0].includes("chartId=-"));
  const withBadKind = captureWarn(() =>
    asChartBlock({ kind: unprintable, chartId: 7, series: [{ name: "s", x: ["a"], y: [1] }] })
  );
  assert.equal(withBadKind.result, null);
  assert.ok(withBadKind.lines[0].includes(CHART_REJECTION_CODES.KIND_INVALID));
  assert.ok(withBadKind.lines[0].includes("kind=-"));
  assert.ok(withBadKind.lines[0].includes("chartId=7"), "finite numeric ids are still printed");
  for (const odd of [Symbol("id"), Object.create(null), [], () => {}, Number.NaN, ""]) {
    const { result } = captureWarn(() =>
      asChartBlock({ kind: odd, chartId: odd, series: [{ name: "s", x: ["a"], y: [null] }] })
    );
    assert.equal(result, null);
  }
});

test("V1: rejection diagnostic names the reason, chartId and kind", () => {
  const { lines } = captureWarn(() =>
    asChartBlock({ kind: "bar", chartId: "c9", series: [{ name: "b", x: ["a"], y: [null] }] })
  );
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_Y_NOT_FINITE_NUMBER"));
  assert.ok(lines[0].includes("chartId=c9"));
  assert.ok(lines[0].includes("kind=bar"));
});

test("V1: reference-line per-item skipping is unchanged by the y rule", () => {
  const b = asChartBlock({
    ...V1_BASES.categoricalBar,
    y_reference_lines: [{ y: "x" }, { y: 5, role: "usl" }, {}],
  });
  assert.ok(b);
  assert.deepEqual(b.y_reference_lines, [{ y: 5, label: undefined, role: "usl" }]);
});

test("V1: pie rules still apply after the shared checks and report their own codes", () => {
  const neg = captureWarn(() => asChartBlock(withY(V1_BASES.pie, [-1, 2])));
  assert.equal(neg.result, null);
  assert.ok(neg.lines[0].includes(CHART_REJECTION_CODES.PIE_NEGATIVE));
  const count = captureWarn(() =>
    asChartBlock({ kind: "pie", series: [V1_BASES.pie.series[0], V1_BASES.pie.series[0]] })
  );
  assert.ok(count.lines[0].includes(CHART_REJECTION_CODES.PIE_SERIES_COUNT));
  const zero = captureWarn(() => asChartBlock(withY(V1_BASES.pie, [0, 0])));
  assert.ok(zero.lines[0].includes(CHART_REJECTION_CODES.PIE_ZERO_TOTAL));
});

test("HB-7: orientation is accepted only on bar with exactly vertical or horizontal", () => {
  const bar = (extra) => ({ kind: "bar", chartId: "hb", series: [{ name: "s", x: ["a", "b"], y: [1, 2] }], ...extra });
  const codes = [];
  const original = console.warn;
  console.warn = (m) => codes.push(String(m));
  try {
    assert.equal(asChartBlock(bar({})).orientation, undefined, "absent stays absent");
    assert.equal(asChartBlock(bar({ orientation: "vertical" })).orientation, "vertical");
    assert.equal(asChartBlock(bar({ orientation: "horizontal" })).orientation, "horizontal");
    for (const bad of ["Horizontal", "", 1, null, true, "sideways"]) {
      assert.equal(asChartBlock(bar({ orientation: bad })), null, `rejects ${JSON.stringify(bad)}`);
    }
    assert.equal(asChartBlock({ kind: "line", series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [1, 2] }], orientation: "horizontal" }), null, "line rejects the field");
    assert.equal(asChartBlock({ kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [1, 2] }], orientation: "vertical" }), null, "pie rejects the field");
    assert.equal(asChartBlock({ kind: "scatter", series: [{ name: "s", x: ["1", "2"], y: [1, 2] }], orientation: "horizontal" }), null);
    assert.equal(codes.length, 9);
    assert.ok(codes.every((c) => c.includes("CHART_ORIENTATION_INVALID")), codes.join("\n"));
  } finally {
    console.warn = original;
  }
});

// ---------------------------------------------------------------------------
// C2b-1 (WK-1, histogram): accepted payload and every rejected variant
// ---------------------------------------------------------------------------

const HIST_OK = () => ({
  kind: "histogram", chartId: "h1", x_label: "T",
  histogram: { edges: [0, 1, 3, 10], counts: [1, 4, 5], densities: [0.1, 0.2, 5 / 70], mode: "count",
    validCount: 12, excludedCount: 1, belowRangeCount: 1, aboveRangeCount: 1, method: "explicit_edges_v1" },
});

test("WK-1: a valid histogram is accepted verbatim; every broken invariant rejects the whole chart with CHART_HISTOGRAM_INVALID", () => {
  const warnings = [];
  const original = console.warn;
  console.warn = (m) => warnings.push(String(m));
  try {
    const ok = asChartBlock(HIST_OK());
    assert.ok(ok);
    assert.equal(ok.kind, "histogram");
    assert.deepEqual(ok.histogram.counts, [1, 4, 5]);
    assert.equal(ok.series, undefined);
    const withRange = asChartBlock({ ...HIST_OK(), requested_time_range: { start: "2026-09-12T00:00:00Z", end: "2026-09-13T00:00:00Z" } });
    assert.equal(withRange.requested_time_range, undefined, "requested_time_range is ignored on histogram");
    const json = asChartBlock(JSON.parse(JSON.stringify(HIST_OK())));
    assert.ok(json, "a JSON round-trip of the densities stays within the 1e-9 tolerance");
    const variants = {
      seriesPresent: { ...HIST_OK(), series: [{ name: "s", x: ["a"], y: [1] }] },
      refLines: { ...HIST_OK(), y_reference_lines: [{ y: 1 }] },
      missingPayload: { kind: "histogram", chartId: "h" },
      edgesNotIncreasing: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, edges: [0, 3, 1, 10] } },
      edgesLength: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, edges: [0, 1, 3] } },
      tooManyBins: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, edges: Array.from({ length: 52 }, (_v, i) => i), counts: Array(51).fill(1), densities: Array(51).fill(1 / 51), validCount: 51, belowRangeCount: 0, aboveRangeCount: 0, excludedCount: 0 } },
      negativeCount: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: [-1, 6, 5] } },
      fractionalCount: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: [1.5, 3.5, 5] } },
      sumMismatch: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: [1, 4, 6] } },
      sumZero: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: [0, 0, 0], densities: [0, 0, 0], validCount: 2, belowRangeCount: 1, aboveRangeCount: 1 } },
      badDensity: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, densities: [100, 100, 100] } },
      densityOffByMagnitude: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, densities: [0.1, 0.2, 5 / 7] } },
      densityLength: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, densities: [0.1, 0.2] } },
      emptyBinNonZeroDensity: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: [0, 5, 5], densities: [0.01, 0.25, 5 / 70] } },
      badMode: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, mode: "percent" } },
      badMethod: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, method: "sturges_v9" } },
      scalarNotInteger: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, validCount: 12.5 } },
      stringCount: { ...HIST_OK(), histogram: { ...HIST_OK().histogram, counts: ["1", 4, 5] } },
    };
    for (const [name, v] of Object.entries(variants)) {
      assert.equal(asChartBlock(v), null, `${name} is rejected`);
    }
    const codes = warnings.filter((w) => w.includes("CHART_HISTOGRAM_INVALID"));
    assert.equal(codes.length, Object.keys(variants).length, "each variant reports the histogram code");
    warnings.length = 0;
    assert.equal(asChartBlock({ ...HIST_OK(), xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 1 } }), null);
    assert.ok(warnings.at(-1).includes("CHART_FIXED_DOMAIN_INVALID"));
    assert.equal(asChartBlock({ ...HIST_OK(), orientation: "vertical" }), null);
    assert.ok(warnings.at(-1).includes("CHART_ORIENTATION_INVALID"));
    assert.equal(asChartBlock({ kind: "bar", series: [{ name: "s", x: ["a"], y: [1] }], histogram: HIST_OK().histogram }), null, "a series kind carrying a histogram payload is rejected");
    assert.ok(warnings.at(-1).includes("CHART_HISTOGRAM_INVALID"));
    assert.equal(chartHistogramRejection({ ...HIST_OK().histogram, counts: [0, 5, 5], densities: [0, 0.25, 5 / 70] }), null, "an empty bin with density exactly 0 is valid");
  } finally {
    console.warn = original;
  }
});

// ---------------------------------------------------------------------------
// C2b-2 (WK-1, boxplot): accepted payload and every rejected variant
// ---------------------------------------------------------------------------

const BOX_G = () => ({ key: "A", n: 9, excludedCount: 1, min: 1, whiskerLow: 2, q1: 3, median: 5, q3: 7, whiskerHigh: 8, max: 9, outliers: [1, 9], outlierCount: 2 });
const BOX_OK = () => ({ kind: "boxplot", chartId: "b1", x_label: "Device", boxplot: { method: "tukey_1_5_iqr_linear_p_v1", groups: [BOX_G(), { ...BOX_G(), key: "B", whiskerLow: 1, whiskerHigh: 9, outliers: [], outlierCount: 0 }] } });
const withGroup = (over) => ({ ...BOX_OK(), boxplot: { ...BOX_OK().boxplot, groups: [{ ...BOX_G(), ...over }] } });

test("WK-1 (boxplot): a valid payload is accepted with its reference lines; every broken invariant rejects with CHART_BOXPLOT_INVALID", () => {
  const warnings = [];
  const original = console.warn;
  console.warn = (m) => warnings.push(String(m));
  try {
    const ok = asChartBlock({ ...BOX_OK(), y_reference_lines: [{ y: 8.5, label: "USL", role: "usl" }, { y: "x" }], requested_time_range: { start: "a", end: "b" } });
    assert.ok(ok);
    assert.equal(ok.kind, "boxplot");
    assert.equal(ok.series, undefined);
    assert.deepEqual(ok.y_reference_lines, [{ y: 8.5, label: "USL", role: "usl" }], "reference lines are cleaned like the series kinds");
    assert.equal(ok.requested_time_range, undefined);
    assert.equal(asChartBlock(BOX_OK()).y_reference_lines, undefined);
    const twenty = Array.from({ length: 20 }, (_v, i) => 100 + i);
    const variants = {
      seriesPresent: { ...BOX_OK(), series: [{ name: "s", x: ["a"], y: [1] }] },
      histogramPayload: { ...BOX_OK(), histogram: {} },
      missingPayload: { kind: "boxplot", chartId: "b" },
      noGroups: { ...BOX_OK(), boxplot: { ...BOX_OK().boxplot, groups: [] } },
      tooManyGroups: { ...BOX_OK(), boxplot: { ...BOX_OK().boxplot, groups: Array.from({ length: 25 }, (_v, i) => ({ ...BOX_G(), key: `G${i}` })) } },
      badMethod: { ...BOX_OK(), boxplot: { ...BOX_OK().boxplot, method: "tukey_3_iqr_v1" } },
      duplicateKey: { ...BOX_OK(), boxplot: { ...BOX_OK().boxplot, groups: [BOX_G(), BOX_G()] } },
      emptyKey: withGroup({ key: " " }),
      nZero: withGroup({ n: 0, outliers: [], outlierCount: 0, whiskerLow: 1, whiskerHigh: 9 }),
      nFractional: withGroup({ n: 9.5 }),
      excludedNegative: withGroup({ excludedCount: -1 }),
      orderBroken: withGroup({ q1: 6 }),
      whiskerBelowMin: withGroup({ whiskerLow: 0 }),
      statNotFinite: withGroup({ median: "5" }),
      outlierCountAboveN: withGroup({ outlierCount: 10, outliers: [1, 9, 1, 9, 1, 9, 1, 9, 1, 9] }),
      outliersLength: withGroup({ outliers: [1] }),
      outliersLengthWithCap: withGroup({ n: 200, max: 200, outlierCount: 30, outliers: [...twenty, 120] }),
      outlierInsideWhiskers: withGroup({ outliers: [1, 5] }),
      outlierAboveMax: withGroup({ outliers: [1, 10] }),
      outlierNotFinite: withGroup({ outliers: [1, "9"] }),
      noOutliersButWhiskerShort: withGroup({ outliers: [], outlierCount: 0, whiskerLow: 1 }),
    };
    for (const [name, v] of Object.entries(variants)) {
      assert.equal(asChartBlock(v), null, `${name} is rejected`);
    }
    const codes = warnings.filter((w) => w.includes("CHART_BOXPLOT_INVALID"));
    assert.equal(codes.length, Object.keys(variants).length, "each variant reports the boxplot code");
    warnings.length = 0;
    assert.equal(asChartBlock({ ...BOX_OK(), xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 1 } }), null);
    assert.ok(warnings.at(-1).includes("CHART_FIXED_DOMAIN_INVALID"));
    assert.equal(asChartBlock({ ...BOX_OK(), orientation: "vertical" }), null);
    assert.ok(warnings.at(-1).includes("CHART_ORIENTATION_INVALID"));
    assert.equal(asChartBlock({ kind: "bar", series: [{ name: "s", x: ["a"], y: [1] }], boxplot: BOX_OK().boxplot }), null, "a series kind carrying a boxplot payload is rejected");
    assert.ok(warnings.at(-1).includes("CHART_BOXPLOT_INVALID"));
    assert.equal(asChartBlock({ ...HIST_OK(), boxplot: BOX_OK().boxplot }), null, "a histogram carrying a boxplot payload is rejected");
    assert.equal(chartBoxplotRejection(withGroup({ n: 1, min: 4, whiskerLow: 4, q1: 4, median: 4, q3: 4, whiskerHigh: 4, max: 4, outliers: [], outlierCount: 0 }).boxplot), null, "n = 1 with seven equal statistics is valid");
    assert.equal(chartBoxplotRejection(withGroup({ n: 200, max: 200, outlierCount: 30, outliers: twenty }).boxplot), null, "30 outliers with 20 listed is valid");
  } finally {
    console.warn = original;
  }
});

// ---------------------------------------------------------------------------
// C2b-3 (WK-1, heatmap): accepted payload and every rejected variant
// ---------------------------------------------------------------------------

const HEAT_OK = () => ({ kind: "heatmap", chartId: "hm1", x_label: "Hour", y_label: "Device", heatmap: { rows: ["A", "B"], cols: ["00", "01", "02"], values: [[1, 2, null], [3, 4, 5]], valueLabel: "Count", missingCount: 1 } });
const withHeat = (over) => ({ ...HEAT_OK(), heatmap: { ...HEAT_OK().heatmap, ...over } });

test("WK-1 (heatmap): a valid payload is accepted; every broken invariant rejects with CHART_HEATMAP_INVALID", () => {
  const warnings = [];
  const original = console.warn;
  console.warn = (m) => warnings.push(String(m));
  try {
    const ok = asChartBlock({ ...HEAT_OK(), requested_time_range: { start: "a", end: "b" } });
    assert.ok(ok);
    assert.equal(ok.kind, "heatmap");
    assert.equal(ok.series, undefined);
    assert.equal(ok.requested_time_range, undefined);
    assert.deepEqual(ok.heatmap.values[0], [1, 2, null], "null stays an explicit missing cell");
    const variants = {
      seriesPresent: { ...HEAT_OK(), series: [{ name: "s", x: ["a"], y: [1] }] },
      histogramPayload: { ...HEAT_OK(), histogram: {} },
      boxplotPayload: { ...HEAT_OK(), boxplot: {} },
      refLines: { ...HEAT_OK(), y_reference_lines: [{ y: 1 }] },
      missingPayload: { kind: "heatmap", chartId: "h" },
      noRows: withHeat({ rows: [], values: [] }),
      tooManyRows: withHeat({ rows: Array.from({ length: 25 }, (_v, i) => `R${i}`), values: Array.from({ length: 25 }, () => [1, 1, 1]) }),
      tooManyCols: withHeat({ cols: Array.from({ length: 49 }, (_v, i) => `C${i}`), values: [Array(49).fill(1), Array(49).fill(1)], missingCount: 0 }),
      duplicateRow: withHeat({ rows: ["A", "A"] }),
      emptyCol: withHeat({ cols: ["00", " ", "02"] }),
      valuesLength: withHeat({ values: [[1, 2, null]] }),
      rowLength: withHeat({ values: [[1, 2], [3, 4, 5]] }),
      stringCell: withHeat({ values: [["1", 2, null], [3, 4, 5]] }),
      undefinedCell: withHeat({ values: [[1, 2, undefined], [3, 4, 5]] }),
      nanCell: withHeat({ values: [[1, 2, NaN], [3, 4, 5]] }),
      allMissing: withHeat({ values: [[null, null, null], [null, null, null]], missingCount: 6 }),
      missingCountWrong: withHeat({ missingCount: 0 }),
      valueLabelMissing: withHeat({ valueLabel: undefined }),
    };
    for (const [name, v] of Object.entries(variants)) {
      assert.equal(asChartBlock(v), null, `${name} is rejected`);
    }
    const codes = warnings.filter((w) => w.includes("CHART_HEATMAP_INVALID"));
    assert.equal(codes.length, Object.keys(variants).length, "each variant reports the heatmap code");
    warnings.length = 0;
    assert.equal(asChartBlock({ ...HEAT_OK(), xAxisMode: "normalized", normalizedDomain: { start: 0, end: 1 } }), null);
    assert.ok(warnings.at(-1).includes("CHART_FIXED_DOMAIN_INVALID"));
    assert.equal(asChartBlock({ ...HEAT_OK(), orientation: "horizontal" }), null);
    assert.ok(warnings.at(-1).includes("CHART_ORIENTATION_INVALID"));
    assert.equal(asChartBlock({ kind: "bar", series: [{ name: "s", x: ["a"], y: [1] }], heatmap: HEAT_OK().heatmap }), null);
    assert.ok(warnings.at(-1).includes("CHART_HEATMAP_INVALID"));
    assert.equal(asChartBlock({ ...BOX_OK(), heatmap: HEAT_OK().heatmap }), null, "a boxplot carrying a heatmap payload is rejected");
    assert.equal(chartHeatmapRejection(withHeat({ values: [[-1, 0, null], [0.5, 4, 5]] }).heatmap), null, "zero and negatives are values");
    assert.equal(chartHeatmapRejection(withHeat({ rows: ["A"], cols: ["x"], values: [[0]], missingCount: 0 }).heatmap), null, "1 × 1 is valid");
  } finally {
    console.warn = original;
  }
});

// ---------------------------------------------------------------------------
// C2a-2 (SK-4, adapter): stackMode on the wire
// ---------------------------------------------------------------------------

test("SK-4 (adapter): stackMode is bar-only, exactly stacked or percent, needs two series, and percent rejects negatives", () => {
  const warnings = [];
  const original = console.warn;
  console.warn = (m) => warnings.push(String(m));
  try {
    const two = [{ name: "a", x: ["p", "q"], y: [1, 2] }, { name: "b", x: ["p", "q"], y: [3, -4] }];
    const ok = asChartBlock({ kind: "bar", series: two, stackMode: "stacked" });
    assert.equal(ok.stackMode, "stacked");
    assert.equal(asChartBlock({ kind: "bar", series: two, stackMode: "stacked", orientation: "horizontal" }).orientation, "horizontal", "stackMode combines with orientation");
    const nonNeg = [{ name: "a", x: ["p"], y: [1] }, { name: "b", x: ["p"], y: [0] }];
    assert.equal(asChartBlock({ kind: "bar", series: nonNeg, stackMode: "percent" }).stackMode, "percent");
    assert.equal(asChartBlock({ kind: "bar", series: two }).stackMode, undefined, "absent stays absent (grouped)");
    const variants = {
      grouped: { kind: "bar", series: two, stackMode: "grouped" },
      caseVariant: { kind: "bar", series: two, stackMode: "Stacked" },
      nullValue: { kind: "bar", series: two, stackMode: null },
      onLine: { kind: "line", series: [{ name: "a", x: ["1", "2"], y: [1, 2] }, { name: "b", x: ["1", "2"], y: [1, 2] }], stackMode: "stacked" },
      singleSeries: { kind: "bar", series: [two[0]], stackMode: "stacked" },
      percentNegative: { kind: "bar", series: two, stackMode: "percent" },
    };
    for (const [name, v] of Object.entries(variants)) {
      assert.equal(asChartBlock(v), null, `${name} is rejected`);
    }
    assert.equal(warnings.filter((w) => w.includes("CHART_STACK_MODE_INVALID")).length, Object.keys(variants).length);
  } finally {
    console.warn = original;
  }
});
