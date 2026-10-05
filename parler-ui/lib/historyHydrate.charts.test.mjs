import test from "node:test";
import assert from "node:assert/strict";
import { parseHistoryRows } from "./historyHydrate.js";

/**
 * Representative `ai-parler-history-v1` assistant row as produced by
 * AgentMessageStreamHistoryExporter.chartsFromToolRows through the COPYING replay helpers
 * (chartBlockFromChartEmittedToolResult / chartBlockFromNumericCompactToolResult), which
 * clone a persisted `chartBlock` without inspecting `series[].y`. The two malformed charts
 * below therefore reach the client verbatim; a points-based result would have been cleaned
 * server-side and cannot exercise this boundary. This fixture is hand-written to that shape;
 * it is not a live capture.
 */
const EXPORTED_ROW_WITH_UNVALIDATED_CHARTS = {
  format: "ai-parler-history-v1",
  rows: [
    { kind: "user", text: "compare the two lines" },
    {
      kind: "assistant",
      assistantMessageId: "am-7",
      markdown: "Here are the charts.",
      charts: [
        {
          // provenance: CHART_EMITTED tool result, chartBlock copied verbatim
          kind: "bar",
          chartId: "c1",
          title: "null y",
          series: [{ name: "s", x: ["a", "b"], y: [null, 10] }],
        },
        {
          // provenance: NUMERIC_HISTORY_INLINE success envelope, chartBlock copied verbatim
          kind: "line",
          chartId: "c2",
          title: "numeric-string y",
          series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [5, "5"] }],
        },
        {
          kind: "line",
          chartId: "c3",
          title: "valid",
          series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [0, 7] }],
        },
      ],
      tables: [
        {
          kind: "entity-list",
          columns: [{ key: "name", label: "Name", baseType: "STRING" }],
          rows: [{ name: "x" }],
          exportStatus: "none",
        },
      ],
      activity: null,
    },
    {
      kind: "assistant",
      assistantMessageId: "am-8",
      markdown: "Unaffected row.",
      charts: [{ kind: "bar", chartId: "c4", series: [{ name: "s", x: ["a"], y: [1] }] }],
      tables: [],
    },
  ],
};

function silenced(fn) {
  const original = console.warn;
  const lines = [];
  console.warn = (msg) => lines.push(String(msg));
  try {
    return { result: fn(), lines };
  } finally {
    console.warn = original;
  }
}

test("hydrate keeps only the valid chart from an exported row carrying unvalidated charts", () => {
  const { result: rows, lines } = silenced(() =>
    parseHistoryRows(structuredClone(EXPORTED_ROW_WITH_UNVALIDATED_CHARTS))
  );
  assert.equal(rows.length, 3);
  const row = rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.charts.length, 1, "null and numeric-string charts are dropped");
  assert.equal(row.charts[0].chartId, "c3");
  assert.deepEqual(row.charts[0].series[0].y, [0, 7], "legal zero is preserved");
  assert.equal(row.tables.length, 1, "the table survives");
  assert.equal(row.markdown, "Here are the charts.");
  assert.deepEqual(
    row.artifacts.map((a) => a.type),
    ["table", "chart"],
    "legacy-bucket projection order is unchanged: tables then charts"
  );
  assert.equal(lines.length, 2, "one diagnostic per dropped chart");
  assert.ok(lines.every((l) => l.includes("CHART_Y_NOT_FINITE_NUMBER")));
  assert.ok(lines.some((l) => l.includes("chartId=c1")));
  assert.ok(lines.some((l) => l.includes("chartId=c2")));
});

test("hydrate leaves sibling rows untouched", () => {
  const { result: rows } = silenced(() =>
    parseHistoryRows(structuredClone(EXPORTED_ROW_WITH_UNVALIDATED_CHARTS))
  );
  const other = rows[2];
  assert.equal(other.kind, "assistant");
  assert.equal(other.charts.length, 1);
  assert.equal(other.charts[0].chartId, "c4");
  assert.equal(rows[0].kind, "user");
});

test("hydrate survives malformed identity metadata on a rejected chart and keeps siblings", () => {
  const { result: rows, lines } = silenced(() =>
    parseHistoryRows({
      format: "ai-parler-history-v1",
      rows: [
        {
          kind: "assistant",
          markdown: "metadata",
          charts: [
            { kind: "bar", chartId: { toString: null }, series: [{ name: "s", x: ["a"], y: [null] }] },
            { kind: { toString: null }, chartId: "c8", series: [{ name: "s", x: ["a"], y: [1] }] },
            { kind: "bar", chartId: "c9", series: [{ name: "s", x: ["a"], y: [1] }] },
          ],
          tables: [
            {
              kind: "entity-list",
              columns: [{ key: "name", label: "Name", baseType: "STRING" }],
              rows: [{ name: "x" }],
              exportStatus: "none",
            },
          ],
        },
        { kind: "assistant", markdown: "after", charts: [], tables: [] },
      ],
    })
  );
  assert.equal(rows.length, 2, "no throw; both rows returned");
  assert.equal(rows[0].charts.length, 1);
  assert.equal(rows[0].charts[0].chartId, "c9");
  assert.equal(rows[0].tables.length, 1, "tables built after the chart loop still land");
  assert.equal(rows[0].markdown, "metadata");
  assert.equal(rows[1].markdown, "after");
  assert.equal(lines.length, 2);
});

test("hydrate drops a chart whose series shape is invalid and keeps the row", () => {
  const { result: rows, lines } = silenced(() =>
    parseHistoryRows({
      format: "ai-parler-history-v1",
      rows: [
        {
          kind: "assistant",
          markdown: "shape",
          charts: [
            { kind: "bar", chartId: "c5", series: [{ name: "s", x: ["a", "b"], y: [1] }] },
            { kind: "bar", chartId: "c6", series: [{ name: "s", x: ["a"], y: [1] }] },
          ],
          tables: [],
        },
      ],
    })
  );
  assert.equal(rows[0].charts.length, 1);
  assert.equal(rows[0].charts[0].chartId, "c6");
  assert.ok(lines[0].includes("CHART_SERIES_SHAPE"));
});

test("HB-7: hydrate keeps a horizontal bar and drops only a chart whose orientation is invalid", () => {
  const exported = {
    format: "ai-parler-history-v1",
    rows: [
      {
        kind: "assistant",
        assistantMessageId: "am-9",
        markdown: "bars",
        charts: [
          { kind: "bar", chartId: "h1", orientation: "horizontal", series: [{ name: "s", x: ["a", "b"], y: [1, 2] }] },
          { kind: "bar", chartId: "h2", orientation: "Horizontal", series: [{ name: "s", x: ["a"], y: [1] }] },
          { kind: "line", chartId: "h3", orientation: "horizontal", series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [1, 2] }] },
          { kind: "bar", chartId: "h4", series: [{ name: "s", x: ["a"], y: [1] }] },
        ],
        tables: [],
        activity: null,
      },
    ],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.deepEqual(rows[0].charts.map((c) => c.chartId), ["h1", "h4"]);
  assert.equal(rows[0].charts[0].orientation, "horizontal");
  assert.equal(lines.length, 2);
  assert.ok(lines.every((l) => l.includes("CHART_ORIENTATION_INVALID")));
});

test("WK-1 (hydrate): a valid histogram survives replay and a contradictory one loses only itself", () => {
  const hist = (over) => ({ kind: "histogram", chartId: "h1", histogram: { edges: [0, 1, 2], counts: [1, 1], densities: [0.5, 0.5], mode: "count", validCount: 2, excludedCount: 0, belowRangeCount: 0, aboveRangeCount: 0, method: "equal_width_v1", ...over } });
  const exported = {
    format: "ai-parler-history-v1",
    rows: [{
      kind: "assistant", assistantMessageId: "am-h", markdown: "hist",
      charts: [hist({}), { ...hist({ counts: [1, 2] }), chartId: "h2" }, { kind: "bar", chartId: "b1", series: [{ name: "s", x: ["a"], y: [1] }] }],
      tables: [{ kind: "entity-list", columns: [{ key: "name", label: "Name", baseType: "STRING" }], rows: [{ name: "x" }], exportStatus: "none" }],
      activity: null,
    }],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.deepEqual(rows[0].charts.map((c) => c.chartId), ["h1", "b1"]);
  assert.equal(rows[0].charts[0].histogram.mode, "count");
  assert.equal(rows[0].tables.length, 1, "the table survives");
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_HISTOGRAM_INVALID"));
});

test("WK-1 (hydrate, boxplot): a valid boxplot survives replay and a contradictory one loses only itself", () => {
  const group = (over) => ({ key: "A", n: 3, excludedCount: 0, min: 1, whiskerLow: 1, q1: 1.5, median: 2, q3: 2.5, whiskerHigh: 3, max: 3, outliers: [], outlierCount: 0, ...over });
  const box = (over) => ({ kind: "boxplot", chartId: "b1", boxplot: { method: "tukey_1_5_iqr_linear_p_v1", groups: [group(over)] } });
  const exported = {
    format: "ai-parler-history-v1",
    rows: [{
      kind: "assistant", assistantMessageId: "am-b", markdown: "box",
      charts: [{ ...box({}), y_reference_lines: [{ y: 2.8, role: "usl" }] }, { ...box({ outlierCount: 1 }), chartId: "b2" }, { kind: "bar", chartId: "b3", series: [{ name: "s", x: ["a"], y: [1] }] }],
      tables: [{ kind: "entity-list", columns: [{ key: "name", label: "Name", baseType: "STRING" }], rows: [{ name: "x" }], exportStatus: "none" }],
      activity: null,
    }],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.deepEqual(rows[0].charts.map((c) => c.chartId), ["b1", "b3"]);
  assert.deepEqual(rows[0].charts[0].y_reference_lines, [{ y: 2.8, label: undefined, role: "usl" }]);
  assert.equal(rows[0].tables.length, 1);
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_BOXPLOT_INVALID"));
});

test("WK-1 (hydrate, heatmap): a valid heatmap survives replay with its null cells and a contradictory one loses only itself", () => {
  const heat = (over) => ({ kind: "heatmap", chartId: "hm1", heatmap: { rows: ["A"], cols: ["x", "y"], values: [[1, null]], valueLabel: "v", missingCount: 1, ...over } });
  const exported = {
    format: "ai-parler-history-v1",
    rows: [{
      kind: "assistant", assistantMessageId: "am-h", markdown: "heat",
      charts: [heat({}), { ...heat({ missingCount: 2 }), chartId: "hm2" }, { kind: "bar", chartId: "b3", series: [{ name: "s", x: ["a"], y: [1] }] }],
      tables: [{ kind: "entity-list", columns: [{ key: "name", label: "Name", baseType: "STRING" }], rows: [{ name: "x" }], exportStatus: "none" }],
      activity: null,
    }],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.deepEqual(rows[0].charts.map((c) => c.chartId), ["hm1", "b3"]);
  assert.deepEqual(rows[0].charts[0].heatmap.values, [[1, null]]);
  assert.equal(rows[0].tables.length, 1);
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_HEATMAP_INVALID"));
});

test("SK-4 (hydrate): a stacked bar survives replay and a percent chart with a negative value loses only itself", () => {
  const two = [{ name: "a", x: ["p"], y: [1] }, { name: "b", x: ["p"], y: [-1] }];
  const exported = {
    format: "ai-parler-history-v1",
    rows: [{
      kind: "assistant", assistantMessageId: "am-s", markdown: "stack",
      charts: [{ kind: "bar", chartId: "s1", series: two, stackMode: "stacked" }, { kind: "bar", chartId: "s2", series: two, stackMode: "percent" }, { kind: "bar", chartId: "s3", series: two }],
      tables: [], activity: null,
    }],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.deepEqual(rows[0].charts.map((c) => c.chartId), ["s1", "s3"]);
  assert.equal(rows[0].charts[0].stackMode, "stacked");
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_STACK_MODE_INVALID"));
});
