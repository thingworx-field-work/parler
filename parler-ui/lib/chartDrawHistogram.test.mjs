import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

import { drawChart } from "../components/chart-draw.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";
import { DEFAULT_PORTABLE_PRINT_THEME, rewriteClonedChartPaletteForPrint } from "./assistantResponseActions.js";

const EPS = 1e-9;

/** A valid histogram block over unequal bins [0,1), [1,3), [3,10]: counts 1,4,5 of 10 values. */
export const HIST = Object.freeze({
  kind: "histogram",
  title: "Temperature",
  x_label: "Temperature (°C)",
  histogram: {
    edges: [0, 1, 3, 10],
    counts: [1, 4, 5],
    densities: [0.1, 0.2, 5 / 70],
    mode: "count",
    validCount: 12,
    excludedCount: 1,
    belowRangeCount: 1,
    aboveRangeCount: 1,
    method: "explicit_edges_v1",
  },
});

function render(chart, width = 640, options = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, null, { availableWidth: width, ...options });
  const svg = el.querySelector("svg");
  const num = (n, a) => Number(n.getAttribute(a));
  const rects = [...(svg?.querySelectorAll("rect.bar-s0") ?? [])].map((r) => ({ x: num(r, "x"), y: num(r, "y"), width: num(r, "width"), height: num(r, "height"), bin: num(r, "data-bin") }));
  return { dom, el, svg, drawn, rects, viewBox: svg ? svg.getAttribute("viewBox").split(" ").map(Number) : null };
}

test("HG-3: bins are drawn edge to edge with widths proportional to their real widths, full width and 240 high", () => {
  for (const W of [320, 480, 768, 1200]) {
    const r = render(HIST, W);
    assert.deepEqual([r.viewBox[2], r.viewBox[3]], [W, 240], `viewBox is W × 240 at ${W}`);
    assert.equal(r.rects.length, 3);
    const innerW = r.drawn.hit.layout.innerW;
    assert.ok(Math.abs(r.rects[0].x) < EPS, "the first bin starts at the left edge");
    assert.ok(Math.abs(r.rects[2].x + r.rects[2].width - innerW) < EPS, "the last bin ends at the right edge");
    for (let i = 1; i < 3; i++) assert.ok(Math.abs(r.rects[i].x - (r.rects[i - 1].x + r.rects[i - 1].width)) < EPS, "adjacent bins are seamless");
    assert.ok(Math.abs(r.rects[1].width / r.rects[0].width - 2) < EPS, "[1,3) is twice as wide as [0,1)");
    assert.ok(Math.abs(r.rects[2].width / r.rects[0].width - 7) < EPS, "[3,10] is seven times as wide as [0,1)");
    assert.ok(r.rects[2].height > r.rects[1].height && r.rects[1].height > r.rects[0].height, "heights follow the counts in count mode");
    assert.ok(Math.abs(r.rects[2].height / r.rects[0].height - 5) < 1e-6, "heights are proportional to counts from the zero baseline");
    assert.equal(r.svg.querySelector("line.zero-baseline"), null, "non-negative counts draw no extra baseline");
    assert.equal(r.drawn.plotSize.w, W);
  }
});

test("hit items report the interval (last bin closed), count and density; keyboard steps walk the bins", () => {
  const r = render(HIST);
  const items = r.drawn.hit.series[0];
  assert.equal(items.length, 3);
  assert.deepEqual(items[0].lines, ["Temperature (°C): [0, 1)", "Count: 1", "Density: 0.1"]);
  assert.deepEqual(items[2].lines, ["Temperature (°C): [3, 10]", "Count: 5", `Density: ${String(5 / 70)}`]);
  const first = r.drawn.hit.first();
  assert.equal(first.index, 0);
  assert.equal(r.drawn.hit.step(first, "right").index, 1);
  assert.equal(r.drawn.hit.step(first, "end").index, 2);
  assert.equal(r.drawn.hit.step(first, "up"), first, "no other series or references");
  const middle = r.rects[1].x + r.rects[1].width / 2;
  assert.equal(r.drawn.hit.nearest(middle, r.drawn.hit.layout.innerH - 1).index, 1, "pointer query uses the bin band");
  assert.equal(r.drawn.hit.nearest(r.rects[2].x + 1, 5).index, 2);
  assert.equal(r.drawn.hiddenSeries.length, 0);
  assert.equal(r.drawn.histogramMode, "count");
  assert.equal(r.drawn.xDomain, undefined, "no X zoom for histograms");
});

test("density mode draws the densities and labels the value axis Density; counts stay in the tooltip", () => {
  const dens = { ...HIST, histogram: { ...HIST.histogram, mode: "density" } };
  const r = render(dens);
  assert.ok(Math.abs(r.rects[1].height / r.rects[0].height - 2) < 1e-6, "0.2 / 0.1 in density mode");
  assert.ok(r.rects[2].height < r.rects[1].height, "the wide last bin is lower in density mode although it holds the most values");
  const label = r.svg.querySelector('[data-parler-palette-role="axis-label"]').textContent;
  assert.equal(label, "Temperature (°C) · Density");
  assert.equal(render(HIST).svg.querySelector('[data-parler-palette-role="axis-label"]').textContent, "Temperature (°C) · Count");
  assert.deepEqual(r.drawn.hit.series[0][0].lines.slice(1), ["Count: 1", "Density: 0.1"]);
});

test("an invalid payload is refused by the renderer too, and a clone prints with known roles only", () => {
  const bad = { ...HIST, histogram: { ...HIST.histogram, counts: [1, 4, 6] } };
  const original = console.warn;
  const warnings = [];
  console.warn = (m) => warnings.push(String(m));
  try {
    const r = render(bad);
    assert.equal(r.svg, null, "nothing is drawn");
    assert.equal(r.drawn, null);
    assert.ok(warnings.some((w) => w.includes("CHART_HISTOGRAM_INVALID")));
  } finally {
    console.warn = original;
  }
  const r = render(HIST);
  const holder = r.dom.window.document.createElement("div");
  holder.append(r.svg.cloneNode(true));
  rewriteClonedChartPaletteForPrint(holder, DEFAULT_PORTABLE_PRINT_THEME);
  const roles = new Set([...holder.querySelectorAll("[data-parler-palette-role]")].map((n) => n.getAttribute("data-parler-palette-role")));
  assert.ok([...roles].every((role) => ["axis", "axis-label", "tick", "grid", "series point-outline"].includes(role)), [...roles].join(","));
  assert.equal(holder.querySelectorAll("rect.bar-s0").length, 3);
});

/** Three equal bins over `[lo, hi]`, a normal histogram the renderer accepts. */
function threeBins(lo, hi) {
  const w = (hi - lo) / 3;
  return { kind: "histogram", histogram: { edges: [lo, lo + w, lo + 2 * w, hi], counts: [2, 4, 2], densities: [0.25, 0.5, 0.25].map((v) => v / w),
    mode: "count", validCount: 8, excludedCount: 0, belowRangeCount: 0, aboveRangeCount: 0, method: "equal_width_v1" } };
}

/** Left and right edge of every X tick label as drawn: tick position, measured width and the anchor on the node. */
function drawnXLabelBoxes(r, measure) {
  const layout = r.drawn.hit.layout;
  const xAxis = [...r.svg.querySelectorAll("g")].find((g) => (g.getAttribute("transform") || "") === `translate(0,${layout.innerH})`);
  return [...xAxis.querySelectorAll(".tick")].map((tick) => {
    const x = layout.marginLeft + Number(/translate\(([-\d.]+)/.exec(tick.getAttribute("transform"))[1]);
    const text = tick.querySelector("text");
    const w = measure(text.textContent);
    const anchor = text.style.textAnchor || "middle";
    const left = anchor === "start" ? x : anchor === "end" ? x - w : x - w / 2;
    return { text: text.textContent, anchor, left, right: left + w };
  });
}

test("HG-7: numeric tick labels are judged where they are finally drawn: inside the SVG and clear of each other", () => {
  // 6.05 px per character is what a browser gives "1,000,000.5" at the default 12 px tick font (66.5 px). At
  // this width the renderer before this rule overlapped its labels by 22.8 and 28.1 px in the last two cases.
  const measure = (text) => String(text).length * 6.05;
  const cases = [
    { name: "large base, small range", width: 320, lo: 100000, hi: 100003 },
    { name: "tidy domain whose end labels must be anchored inward", width: 360, lo: 1000000, hi: 1000002 },
    { name: "untidy domain: d3 ticks do not sit on the domain ends", width: 320, lo: 1000000.1, hi: 1000005.1 },
  ];
  for (const c of cases) {
    const r = render(threeBins(c.lo, c.hi), c.width, { measureText: measure });
    const W = r.drawn.hit.layout.viewBoxW;
    const labels = drawnXLabelBoxes(r, measure);
    assert.ok(labels.length >= 1, `${c.name}: the axis keeps at least one label`);
    for (const l of labels) assert.ok(l.left >= 0 && l.right <= W, `${c.name}: "${l.text}" (${l.anchor}) is inside 0..${W}: ${l.left.toFixed(1)}..${l.right.toFixed(1)}`);
    for (let i = 1; i < labels.length; i++) {
      const gap = labels[i].left - labels[i - 1].right;
      assert.ok(gap >= 8 - 1e-6, `${c.name}: "${labels[i - 1].text}" and "${labels[i].text}" are ${gap.toFixed(1)} px apart after anchoring`);
    }
    assert.equal(new Set(labels.map((l) => l.text)).size, labels.length, `${c.name}: neighbouring values keep distinct labels`);
    assert.deepEqual(r.rects.map((b) => b.bin), [0, 1, 2], `${c.name}: bins are untouched`);
  }
  const roomy = render(threeBins(100000, 100003), 1200, { measureText: measure });
  assert.ok(roomy.svg.querySelectorAll(".tick").length > drawnXLabelBoxes(render(threeBins(100000, 100003), 320, { measureText: measure }), measure).length,
    "a wide card keeps the denser default ticks");
  // Labels so wide that not even one fits: the axis shows none rather than a clipped one.
  const none = render(threeBins(100000, 100003), 320, { measureText: () => 5000 });
  assert.equal(drawnXLabelBoxes(none, () => 5000).length, 0);
});
