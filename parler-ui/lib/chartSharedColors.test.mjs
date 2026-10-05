import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

import { chartSeriesSlot, normalizeCategoryKey, resolveSeriesSlot, DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";
import { chartLegendItems, drawChart } from "../components/chart-draw.js";
import { asChartGroupManifest, chartGroupColorRejection } from "./wireAdapter.js";
import { buildArtifactsFromLegacyBuckets, chartColorContextFor, chartProducesCategoryKeys, groupArtifactsForLayout, orderArtifactsForDisplay, SHARED_COLOR_CAP_NOTE } from "./artifactPresentation.js";

const pie = (chartId, labels, values = labels.map(() => 1)) => ({ kind: "pie", chartId, series: [{ name: "share", x: labels, y: values }], source: { sourceCacheId: "t1" } });
const member = (key, order, over = {}) => ({ key, order, name: key, expectedType: "chart", state: "pending", ...over });
const manifest = (members, over = {}) => {
  const counts = { ready: 0, noData: 0, error: 0, cancelled: 0 };
  for (const m of members) {
    if (m.state === "ready") counts.ready += 1;
    if (m.state === "no-data") counts.noData += 1;
    if (m.state === "error") counts.error += 1;
    if (m.state === "cancelled") counts.cancelled += 1;
  }
  return { groupId: "g1", revision: 3, title: "T", layout: "auto", final: true, members, summary: { expected: members.length, ...counts, final: true }, ...over };
};
const KEYS = ["Setup", "Down", "Running"];

function slotsOf(chart, colorKeys) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, null, { availableWidth: 640, colorKeys });
  const marks = [...el.querySelectorAll("[data-parler-category]")].map((n) => [n.getAttribute("data-parler-category"), n.getAttribute("data-parler-series-slot")]);
  return { drawn, marks, svg: el.querySelector("svg").outerHTML.replace(/parler-[a-z-]+-\d+/g, "id") };
}

test("resolveSeriesSlot: shared keys own their index, everything else is the per-chart slot", () => {
  assert.equal(normalizeCategoryKey("  Down   time "), "Down time");
  assert.equal(normalizeCategoryKey("running"), "running", "case-sensitive");
  assert.equal(normalizeCategoryKey("   "), null);
  assert.equal(normalizeCategoryKey(null), null);
  assert.equal(resolveSeriesSlot("Running", 0, KEYS), 2);
  assert.equal(resolveSeriesSlot(" Running ", 5, KEYS), 2, "the lookup normalises like the server");
  assert.equal(resolveSeriesSlot("Idle", 1, KEYS), chartSeriesSlot(1), "unknown key → per-chart slot");
  assert.equal(resolveSeriesSlot("Running", 1, null), 1);
  assert.equal(resolveSeriesSlot("Running", 27, []), 3);
});

test("SC-1 / SC-3 / SC-7: the same category takes the same slot in both pies, unrelated views never reassign, other kinds are untouched", () => {
  const a = pie("c1", ["Setup", "Down", "Running"]);
  const b = pie("c2", ["Running", "Down"]);
  const la = chartLegendItems(a, DEFAULT_CHART_RENDER_THEME, KEYS);
  const lb = chartLegendItems(b, DEFAULT_CHART_RENDER_THEME, KEYS);
  assert.deepEqual(la.map((i) => [i.label, i.slot]), [["Setup", 0], ["Down", 1], ["Running", 2]]);
  assert.deepEqual(lb.map((i) => [i.label, i.slot]), [["Running", 2], ["Down", 1]]);
  assert.deepEqual(chartLegendItems(b, DEFAULT_CHART_RENDER_THEME, null).map((i) => i.slot), [0, 1], "without keys: today's per-chart slots");
  const da = slotsOf(a, KEYS);
  const db = slotsOf(b, KEYS);
  assert.deepEqual(da.marks, [["Setup", "0"], ["Down", "1"], ["Running", "2"]]);
  assert.deepEqual(db.marks, [["Running", "2"], ["Down", "1"]]);
  assert.deepEqual(da.drawn.hit.series[0].map((it) => [it.category, it.slot]), [["Setup", 0], ["Down", 1], ["Running", 2]], "hit items carry the category and slot for swatches and highlight");
  // Zero-value slice: drawn nothing, no slot consumed, others unchanged (SC-2 on the client side).
  const bz = slotsOf(pie("c2", ["Idle", "Running", "Down"], [0, 1, 1]), KEYS);
  assert.deepEqual(bz.marks, [["Running", "2"], ["Down", "1"]]);
  // Hidden series, legend order and zero filtering never change slots for a multi-series bar either.
  const bar = { kind: "bar", chartId: "c3", series: [{ name: "Running", x: ["d"], y: [1] }, { name: "Down", x: ["d"], y: [2] }] };
  assert.deepEqual(chartLegendItems(bar, DEFAULT_CHART_RENDER_THEME, KEYS).map((i) => i.slot), [2, 1]);
  const barDrawn = slotsOf(bar, KEYS);
  assert.deepEqual(barDrawn.marks, [["Running", "2"], ["Down", "1"]]);
  const single = { kind: "bar", chartId: "c4", series: [{ name: "Running", x: ["d"], y: [1] }] };
  assert.equal(chartProducesCategoryKeys(single), false, "a single-series chart produces no key, so the projection never hands it colour keys");
  assert.equal(slotsOf(single, null).marks[0][1], "0", "and without keys it renders exactly as before");
  assert.equal(chartProducesCategoryKeys(bar), true);
  assert.equal(chartProducesCategoryKeys(pie("c1", ["a"])), true);
  assert.equal(chartProducesCategoryKeys({ kind: "histogram", histogram: {} }), false);
  // SC-6: no keys → byte-identical SVG to the pre-C3b-2a renderer.
  assert.equal(slotsOf(b, null).svg, slotsOf(b, undefined).svg);
});

test("SC-6 (adapter): an invalid extension loses only the extension; the group and its members stay", () => {
  const ok = manifest([member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2", colorShared: true })], { sharedCategories: { dimension: "utilization state", keys: KEYS } });
  assert.equal(chartGroupColorRejection(ok), null);
  assert.deepEqual(asChartGroupManifest(ok), ok);
  const original = console.warn;
  const lines = [];
  console.warn = (m) => lines.push(String(m));
  try {
    for (const [name, bad] of Object.entries({
      duplicateKey: { ...ok, sharedCategories: { dimension: "d", keys: ["a", "a"] } },
      tooMany: { ...ok, sharedCategories: { dimension: "d", keys: Array.from({ length: 25 }, (_v, i) => `k${i}`) } },
      emptyKey: { ...ok, sharedCategories: { dimension: "d", keys: ["a", " "] } },
      notString: { ...ok, sharedCategories: { dimension: "d", keys: ["a", 2] } },
      emptyDimension: { ...ok, sharedCategories: { dimension: "", keys: KEYS } },
      notObject: { ...ok, sharedCategories: "x" },
      badFlag: { ...ok, members: [member("a", 0, { state: "ready", chartId: "c1", colorShared: "yes" }), member("b", 1, { state: "ready", chartId: "c2" })] },
      flagWithoutMapping: manifest([member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2" })]),
    })) {
      const out = asChartGroupManifest(bad);
      assert.ok(out, `${name}: the group is kept`);
      assert.equal(out.sharedCategories, undefined, `${name}: extension dropped`);
      assert.ok(out.members.every((m) => m.colorShared === undefined), `${name}: flags dropped`);
      assert.equal(out.members.length, 2);
    }
  } finally {
    console.warn = original;
  }
  assert.equal(lines.filter((l) => l.includes("CHART_GROUP_COLOR_INVALID")).length, 8);
  assert.equal(lines.filter((l) => l.includes("CHART_GROUP_INVALID ")).length, 0, "the group itself is never rejected for its extension");
});

test("projection: colour-shared members get the keys, capped categorical members get the note, plain members get nothing", () => {
  const charts = [pie("c1", ["Setup", "Down", "Running"]), pie("c2", ["Running", "Down"]), { kind: "histogram", chartId: "c3", histogram: { edges: [0, 1], counts: [1], densities: [1], mode: "count", validCount: 1, excludedCount: 0, belowRangeCount: 0, aboveRangeCount: 0, method: "explicit_edges_v1" }, source: { sourceCacheId: "t1" } }];
  const ordered = orderArtifactsForDisplay(buildArtifactsFromLegacyBuckets(charts, []));
  const group = manifest([member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2" }), member("h", 2, { state: "ready", chartId: "c3" })], { sharedCategories: { dimension: "utilization state", keys: KEYS } });
  const layout = groupArtifactsForLayout(ordered, [group]);
  const g = layout.find((s) => s.type === "chart-group");
  assert.deepEqual(g.slots.map((s) => [s.colorKeys, s.colorNote]), [[KEYS, null], [null, SHARED_COLOR_CAP_NOTE], [null, null]]);
  assert.deepEqual(chartColorContextFor(layout, "chart:c1"), { colorKeys: KEYS, colorNote: null, groupId: "g1" });
  assert.deepEqual(chartColorContextFor(layout, "chart:c2"), { colorKeys: null, colorNote: SHARED_COLOR_CAP_NOTE, groupId: "g1" });
  assert.deepEqual(chartColorContextFor(layout, "chart:c9"), { colorKeys: null, colorNote: null, groupId: null });
  const plain = groupArtifactsForLayout(ordered, [manifest([member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "ready", chartId: "c2" })])]);
  assert.deepEqual(plain.find((s) => s.type === "chart-group").slots.map((s) => [s.colorKeys, s.colorNote]), [[null, null], [null, null]]);
});
