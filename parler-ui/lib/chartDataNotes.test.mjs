import assert from "node:assert/strict";
import test from "node:test";

process.env.TZ = "Asia/Shanghai";
const { chartDataNotes, chartDataPage, chartDataRows, CHART_DATA_PAGE_SIZE, NOT_PROVIDED } = await import("./chartDataNotes.js");

const byLabel = (rows) => Object.fromEntries(rows.map((r) => [r.label, r.value]));

test("notes read only what the chart provides and say so when it does not", () => {
  const { rows, diagnostics, meta } = chartDataNotes({
    kind: "line",
    chartId: "c9",
    y_label: "Temperature (°C)",
    requested_time_range: { start: "2026-09-12T00:00:00Z", end: "2026-09-12T02:00:00Z" },
    series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T01:00:00Z"], y: [1, 2] }],
    source: {
      sourceResolved: "cache_id",
      sourceResultKind: "CACHED_TABULATE_GROUP_METRIC",
      sourceCacheId: "cache-77",
      sourceToolCallId: "call-3",
      sourceColumns: ["timestamp", "temperature"],
      transformSummary: "long_table_pivot(device)",
      rowCount: 120,
      pointCount: 2,
      truncationApplied: true,
      filledMissingCombinations: 3,
      zeroValueCategoryCount: 0,
      missingSeries: ["line-3"],
    },
  });
  const r = byLabel(rows);
  assert.equal(r.Source, "cache_id · CACHED_TABULATE_GROUP_METRIC");
  assert.match(r.Window, /^2026-09-12 08:00:00 – 2026-09-12 10:00:00 \(.*UTC\+08:00\)\)?$/);
  assert.equal(r.Columns, "timestamp, temperature");
  assert.equal(r.Transform, "long_table_pivot(device)");
  assert.equal(r["Input rows"], "120");
  assert.equal(r["Emitted points"], "2");
  assert.equal(r["Top-N / Other / sampling"], "Applied (top-N, Other, or point budget)");
  assert.equal(r["Zero-filled combinations"], "3");
  assert.equal(r["Zero-value categories"], "0");
  assert.equal(r["Missing series"], "line-3");
  assert.deepEqual(byLabel(diagnostics), { "Chart id": "c9", "Cache id": "cache-77", "Tool call id": "call-3" });
  assert.ok(rows.every((row) => !/cache-77|call-3/.test(row.value)), "identifiers stay in diagnostics");
  assert.match(meta, /^Window: 2026-09-12 08:00:00 – 2026-09-12 10:00:00 \(.*\) · Values: Temperature \(°C\) · Top-N \/ Other \/ sampling applied$/);
});

test("absent provenance is stated, never inferred; emitted points fall back to a counted value", () => {
  const { rows, diagnostics, meta } = chartDataNotes({
    kind: "bar",
    series: [{ name: "s", x: ["a", "b", "c"], y: [1, 2, 3] }],
  });
  const r = byLabel(rows);
  for (const label of ["Source", "Window", "Columns", "Transform", "Input rows", "Top-N / Other / sampling", "Zero-filled combinations", "Zero-value categories"]) {
    assert.equal(r[label], NOT_PROVIDED, label);
  }
  assert.equal(r["Emitted points"], "3 (counted in chart)");
  assert.equal(r["Missing series"], "None reported");
  assert.ok(diagnostics.every((d) => d.value === NOT_PROVIDED));
  assert.equal(meta, "");
});

test("overlay windows are shown in the display zone; the source zone is stated separately, never mixed", () => {
  const { rows } = chartDataNotes({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 3600 },
    series: [
      { name: "today", x: ["0"], y: [1], sourceWindow: { start: "2026-09-12T00:00:00Z", end: "2026-09-12T01:00:00Z", resolvedTimeZone: "Europe/Berlin" } },
      { name: "yesterday", x: ["0"], y: [2], sourceWindow: { start: "2026-09-11T00:00:00Z", end: "2026-09-11T01:00:00Z" } },
    ],
    source: { sourceResolved: "history_overlay", truncationApplied: false, missingPeriods: ["last week"] },
  });
  const r = byLabel(rows);
  // Browser zone is Asia/Shanghai: the UTC window is 08:00–09:00 there, and the label says so.
  assert.equal(
    r.Window,
    "today: 2026-09-12 08:00:00 – 2026-09-12 09:00:00 (Asia/Shanghai (UTC+08:00)); source window zone Europe/Berlin; " +
      "yesterday: 2026-09-11 08:00:00 – 2026-09-11 09:00:00 (Asia/Shanghai (UTC+08:00))"
  );
  assert.ok(!r.Window.includes("09:00:00 (Europe/Berlin)"), "display-zone times are never labelled with the source zone");
  assert.equal(r["Top-N / Other / sampling"], "Not applied");
  assert.equal(r["Missing series"], "last week", "plain string entries are kept");
});

test("a window spanning a DST transition shows both endpoint offsets", () => {
  process.env.TZ = "America/New_York";
  try {
    const { rows } = chartDataNotes({
      kind: "line",
      requested_time_range: { start: "2026-11-01T04:00:00Z", end: "2026-11-01T08:00:00Z" },
      series: [{ name: "s", x: ["2026-11-01T04:00:00Z"], y: [1] }],
    });
    assert.equal(
      byLabel(rows).Window,
      "2026-11-01 00:00:00 – 2026-11-01 03:00:00 (America/New_York (UTC-04:00) → America/New_York (UTC-05:00))"
    );
  } finally {
    process.env.TZ = "Asia/Shanghai";
  }
});

test("contract-shaped missing periods render their identity and interval, not [object Object]", () => {
  const { rows } = chartDataNotes({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 60 },
    series: [{ name: "today", x: ["0"], y: [1] }],
    source: {
      sourceResolved: "history_overlay",
      missingSeries: ["Pump-2"],
      missingPeriods: [
        { label: "yesterday", thingName: "Pump-1", propertyName: "temperature", start: "2026-09-11T00:00:00Z", end: "2026-09-11T01:00:00Z" },
        { label: "last week", thingName: "Pump-1" },
        { propertyName: "pressure" },
        {},
      ],
    },
  });
  assert.equal(
    byLabel(rows)["Missing series"],
    "Pump-2, yesterday — Pump-1.temperature — 2026-09-11 08:00:00 – 2026-09-11 09:00:00 (Asia/Shanghai (UTC+08:00)), " +
      "last week — Pump-1, pressure, unnamed entry"
  );
});

test("chartDataRows lists the received values with raw text in series order, and pages them", () => {
  const { columns, rows } = chartDataRows({
    kind: "line",
    x_label: "Time",
    y_label: "Temp",
    series: [
      { name: "a", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [1.5, 2] },
      { name: "", x: ["2026-09-12T00:00:00Z"], y: [-3] },
    ],
  });
  assert.deepEqual(columns, ["Series", "Time", "Temp"]);
  assert.deepEqual(rows, [
    { series: "a", x: "2026-09-12T00:00:00Z", y: "1.5" },
    { series: "a", x: "2026-09-12T00:01:00Z", y: "2" },
    { series: "Series 2", x: "2026-09-12T00:00:00Z", y: "-3" },
  ]);
  const pie = chartDataRows({ kind: "pie", series: [{ name: "status", x: ["Idle", "Run"], y: [1, 9] }] });
  assert.deepEqual(pie.columns, ["Series", "Slice", "Value"]);
  assert.equal(pie.rows[1].x, "Run");
  const many = Array.from({ length: 45 }, (_, i) => ({ series: "s", x: String(i), y: String(i) }));
  assert.equal(CHART_DATA_PAGE_SIZE, 20);
  const first = chartDataPage(many, 0);
  assert.deepEqual([first.page, first.pageCount, first.from, first.to, first.total, first.rows.length], [0, 3, 1, 20, 45, 20]);
  const last = chartDataPage(many, 99);
  assert.deepEqual([last.page, last.from, last.to, last.rows.length], [2, 41, 45, 5], "page index clamps to the last page");
  assert.deepEqual([chartDataPage([], 0).from, chartDataPage([], 0).pageCount], [0, 1]);
});
