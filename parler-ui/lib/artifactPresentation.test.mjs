import assert from "node:assert/strict";
import { test } from "node:test";
import {
  ARTIFACT_GRID_GAP,
  ARTIFACT_GRID_MIN_COLUMN,
  artifactGridColumns,
  groupArtifactsForLayout,
  parentTableArtifactForChart,
  buildArtifactsFromLegacyBuckets,
  chartArtifactStableKey,
  CUTOFF_CONFIRM_MESSAGE,
  defaultTableCollapsed,
  expandTablesForPrint,
  makeRowArtifact,
  orderArtifactsForDisplay,
  tableArtifactStableKey,
  tableDisclosureSummaryLabel,
} from "./artifactPresentation.js";

/** @param {string} cacheId @param {string} [sourceCacheId] */
function tableBlock(cacheId, sourceCacheId) {
  return {
    kind: "entity-list",
    columns: [{ key: "a", label: "UtilizationState", baseType: "STRING" }],
    rows: [{ a: "Down" }],
    shownRows: 1,
    totalRows: 1,
    cacheId,
    sourceCacheId: sourceCacheId ?? null,
    exportStatus: "none",
    exportMessage: null,
    exportFile: null,
    exportRepository: null,
    exportDownloadUrl: null,
  };
}

/** @param {string} sourceCacheId */
function chartBlock(sourceCacheId) {
  return {
    kind: "pie",
    series: [{ name: "s", x: ["Down"], y: [1] }],
    source: { sourceCacheId },
  };
}

test("orderArtifactsForDisplay preserves cross-type append sequence in fallback", () => {
  const artifacts = [
    makeRowArtifact("table", tableBlock("t-only", null), 0),
    makeRowArtifact("chart", chartBlock("orphan"), 1),
  ];
  const ordered = orderArtifactsForDisplay(artifacts);
  assert.equal(ordered.length, 2);
  assert.equal(ordered[0].type, "table");
  assert.equal(ordered[1].type, "chart");
});

test("immediate-parent pairing: chart.source.sourceCacheId == table.cacheId", () => {
  const tA = tableBlock("cache-y-a", "raw-x-a");
  const tB = tableBlock("cache-y-b", "raw-x-b");
  const cA = chartBlock("cache-y-a");
  const cB = chartBlock("cache-y-b");
  const artifacts = [
    makeRowArtifact("table", tA, 0),
    makeRowArtifact("table", tB, 1),
    makeRowArtifact("chart", cA, 2),
    makeRowArtifact("chart", cB, 3),
  ];
  const ordered = orderArtifactsForDisplay(artifacts);
  assert.deepEqual(
    ordered.map((a) => a.type),
    ["table", "chart", "table", "chart"]
  );
  assert.equal(ordered[0].table?.cacheId, "cache-y-a");
  assert.equal(ordered[1].chart?.source?.sourceCacheId, "cache-y-a");
});

test("parallel multi-subject: structured order not naive tableA tableB chartA chartB", () => {
  const tA = tableBlock("y-a", "x-a");
  const tB = tableBlock("y-b", "x-b");
  const cA = chartBlock("y-a");
  const cB = chartBlock("y-b");
  const artifacts = [
    makeRowArtifact("table", tA, 0),
    makeRowArtifact("table", tB, 1),
    makeRowArtifact("chart", cA, 2),
    makeRowArtifact("chart", cB, 3),
  ];
  const ordered = orderArtifactsForDisplay(artifacts);
  const keys = ordered.map((a) =>
    a.type === "table" ? a.table?.cacheId : a.chart?.source?.sourceCacheId
  );
  assert.deepEqual(keys, ["y-a", "y-a", "y-b", "y-b"]);
});

test("immediate-parent pairs transformed table; raw source table stays unpaired bystander", () => {
  const rawX = tableBlock("raw-x", null);
  const midY = tableBlock("mid-y", "raw-x");
  const chart = chartBlock("mid-y");
  const artifacts = [
    makeRowArtifact("table", rawX, 0),
    makeRowArtifact("table", midY, 1),
    makeRowArtifact("chart", chart, 2),
  ];
  const ordered = orderArtifactsForDisplay(artifacts);
  assert.equal(ordered.length, 3);
  const midIdx = ordered.findIndex((a) => a.table?.cacheId === "mid-y");
  const chartIdx = ordered.findIndex((a) => a.type === "chart");
  assert.ok(midIdx >= 0 && chartIdx === midIdx + 1);
  assert.ok(ordered.some((a) => a.table?.cacheId === "raw-x"));
});

test("no synthetic table: chart without matching table renders alone", () => {
  const chart = chartBlock("missing-cache");
  const ordered = orderArtifactsForDisplay([
    makeRowArtifact("chart", chart, 0),
  ]);
  assert.equal(ordered.length, 1);
  assert.equal(ordered[0].type, "chart");
});

test("defaultTableCollapsed: chart row collapses tables", () => {
  assert.equal(defaultTableCollapsed(true, 2), true);
});

test("defaultTableCollapsed: one or two tables without charts expands", () => {
  assert.equal(defaultTableCollapsed(false, 1), false);
  assert.equal(defaultTableCollapsed(false, 2), false);
});

test("defaultTableCollapsed: three or more tables without charts collapses", () => {
  assert.equal(defaultTableCollapsed(false, 3), true);
});

test("tableDisclosureSummaryLabel uses column labels and row counts", () => {
  const label = tableDisclosureSummaryLabel(
    tableBlock("c", null)
  );
  assert.match(label, /^Columns: UtilizationState · 1\/1 rows$/);
});

test("tableDisclosureSummaryLabel prefers presentationTitle when set", () => {
  const tb = tableBlock("c", null);
  tb.presentationTitle = "tabulate_cached_result: groupBy=UtilizationState";
  assert.equal(
    tableDisclosureSummaryLabel(tb),
    "tabulate_cached_result: groupBy=UtilizationState"
  );
});

test("smoke-like ordering: raw bystanders then paired tab/chart groups", () => {
  const rawA = tableBlock("raw-a", null);
  const rawB = tableBlock("raw-b", null);
  const tabA = tableBlock("y-a", "raw-a");
  const tabB = tableBlock("y-b", "raw-b");
  const chartA = chartBlock("y-a");
  const chartB = chartBlock("y-b");
  const artifacts = [
    makeRowArtifact("table", rawA, 0),
    makeRowArtifact("table", rawB, 1),
    makeRowArtifact("table", tabA, 2),
    makeRowArtifact("table", tabB, 3),
    makeRowArtifact("chart", chartA, 4),
    makeRowArtifact("chart", chartB, 5),
  ];
  const ordered = orderArtifactsForDisplay(artifacts);
  assert.deepEqual(
    ordered.map((a) =>
      a.type === "table" ? `table:${a.table?.cacheId}` : `chart:${a.chart?.source?.sourceCacheId}`
    ),
    [
      "table:raw-a",
      "table:raw-b",
      "table:y-a",
      "chart:y-a",
      "table:y-b",
      "chart:y-b",
    ]
  );
});

test("buildArtifactsFromLegacyBuckets assigns monotonic seq", () => {
  const artifacts = buildArtifactsFromLegacyBuckets(
    [chartBlock("y")],
    [tableBlock("y", null)]
  );
  assert.equal(artifacts.length, 2);
  assert.equal(artifacts[0].type, "table");
  assert.equal(artifacts[0].seq, 0);
  assert.equal(artifacts[1].type, "chart");
  assert.equal(artifacts[1].seq, 1);
});

test("stable keys use cacheId when present", () => {
  const tb = tableBlock("cid-1", null);
  assert.equal(tableArtifactStableKey(tb, 9), "table:cid-1");
  const ch = chartBlock("src-1");
  assert.equal(chartArtifactStableKey(ch, 9), "chart:src:src-1:9");
});

test("CUTOFF_CONFIRM_MESSAGE mentions visible trim not durable deletion", () => {
  assert.match(CUTOFF_CONFIRM_MESSAGE, /visible conversation/i);
  assert.match(CUTOFF_CONFIRM_MESSAGE, /does not delete durable/i);
});

test("parentTableArtifactForChart resolves the explicit sourceCacheId relation only", () => {
  const artifacts = [
    { type: "table", seq: 0, key: "table:t1", table: { kind: "entity-list", cacheId: "t1", columns: [], rows: [], exportStatus: "none" } },
    { type: "table", seq: 1, key: "table:t2", table: { kind: "entity-list", cacheId: "t2", columns: [], rows: [], exportStatus: "none" } },
    { type: "chart", seq: 2, key: "chart:c1", chart: { kind: "bar", series: [], source: { sourceCacheId: "t2" } } },
  ];
  assert.equal(parentTableArtifactForChart(artifacts, artifacts[2].chart).key, "table:t2");
  assert.equal(parentTableArtifactForChart(artifacts, { kind: "bar", series: [], source: { sourceCacheId: "t9" } }), null);
  assert.equal(parentTableArtifactForChart(artifacts, { kind: "bar", title: "t1", series: [] }), null, "titles never pair");
  assert.equal(parentTableArtifactForChart([], artifacts[2].chart), null);
  const shared = { kind: "line", series: [], source: { sourceCacheId: "t1" } };
  assert.equal(parentTableArtifactForChart(artifacts, shared), parentTableArtifactForChart(artifacts, { ...shared }), "two charts share one parent");
});


// ---------------------------------------------------------------------------
// C3a (design §8.1): grid slots for adjacent charts, the two-column rule
// ---------------------------------------------------------------------------

function pieBlock(chartId, sourceCacheId) {
  return { kind: "pie", chartId, series: [{ name: "s", x: ["a", "b"], y: [1, 2] }], source: { sourceCacheId } };
}

test("C3a: runs of two or more adjacent charts become one grid slot; tables keep their position and break the run", () => {
  const shared = buildArtifactsFromLegacyBuckets([pieBlock("c1", "t1"), pieBlock("c2", "t1")], [tableBlock("t1")]);
  const slots = groupArtifactsForLayout(orderArtifactsForDisplay(shared));
  assert.deepEqual(slots.map((s) => s.type), ["artifact", "chart-grid"]);
  assert.equal(slots[0].artifact.key, "table:t1", "the shared parent table precedes the grid, once");
  assert.deepEqual(slots[1].charts.map((c) => c.key), ["chart:c1", "chart:c2"]);
  assert.equal(slots[1].key, "grid:chart:c1");
  const separate = buildArtifactsFromLegacyBuckets([pieBlock("c1", "t1"), pieBlock("c2", "t2")], [tableBlock("t1"), tableBlock("t2")]);
  const separateSlots = groupArtifactsForLayout(orderArtifactsForDisplay(separate));
  assert.deepEqual(separateSlots.map((s) => s.type), ["artifact", "artifact", "artifact", "artifact"], "a table between two charts keeps them in single slots");
  assert.deepEqual(separateSlots.map((s) => s.key), ["table:t1", "chart:c1", "table:t2", "chart:c2"], "the original order is untouched");
  const four = buildArtifactsFromLegacyBuckets([pieBlock("c1", "t1"), pieBlock("c2", "t1"), pieBlock("c3", "t1"), pieBlock("c4", "t1")], [tableBlock("t1")]);
  const fourSlots = groupArtifactsForLayout(orderArtifactsForDisplay(four));
  assert.equal(fourSlots[1].charts.length, 4, "four pies form one grid");
  const single = groupArtifactsForLayout(orderArtifactsForDisplay(buildArtifactsFromLegacyBuckets([pieBlock("c1", "t1")], [tableBlock("t1")])));
  assert.deepEqual(single.map((s) => s.type), ["artifact", "artifact"], "one chart never forms a grid");
  const none = groupArtifactsForLayout(orderArtifactsForDisplay(buildArtifactsFromLegacyBuckets([], [tableBlock("t1")])));
  assert.deepEqual(none.map((s) => s.key), ["table:t1"]);
  assert.deepEqual(groupArtifactsForLayout(undefined), []);
  const mixed = groupArtifactsForLayout(orderArtifactsForDisplay(buildArtifactsFromLegacyBuckets([pieBlock("c1", "t1"), { ...pieBlock("c2", "t1"), kind: "line" }], [tableBlock("t1")])));
  assert.equal(mixed[1].charts.length, 2, "line + pie share layout only");
});

test("C3a: two columns only when each column keeps 360 px after the 16 px gap; never more than two", () => {
  assert.equal(ARTIFACT_GRID_GAP, 16);
  assert.equal(ARTIFACT_GRID_MIN_COLUMN, 360);
  assert.equal(artifactGridColumns(320, 2), 1);
  assert.equal(artifactGridColumns(480, 2), 1);
  assert.equal(artifactGridColumns(735, 2), 1, "735 − 16 leaves 359.5 per column");
  assert.equal(artifactGridColumns(736, 2), 2);
  assert.equal(artifactGridColumns(768, 2), 2);
  assert.equal(artifactGridColumns(1200, 4), 2, "never more than two columns");
  assert.equal(artifactGridColumns(1200, 1), 1, "a single chart is not a grid");
  assert.equal(artifactGridColumns(0, 2), 1);
});
