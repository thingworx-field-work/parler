import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

import { drawChart, chartYDomain, estimateTextWidth, rotatedCategoryAxisBottom } from "../components/chart-draw.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";
import { DEFAULT_PORTABLE_PRINT_THEME, rewriteClonedChartPaletteForPrint } from "./assistantResponseActions.js";
import { bandMaxStep } from "./chartSizePolicy.js";

const EPS = 1e-9;

/** BX-1-like odd sample [1..9] (no outliers) and a BX-3-like group with 30 outliers, 20 listed. */
const G_ODD = Object.freeze({ key: "Oven-01", n: 9, excludedCount: 0, min: 1, whiskerLow: 1, q1: 3, median: 5, q3: 7, whiskerHigh: 9, max: 9, outliers: [], outlierCount: 0 });
const HIGH = Array.from({ length: 20 }, (_v, i) => 200 - i);
const G_OUT = Object.freeze({ key: "Oven-02", n: 130, excludedCount: 2, min: 58.1, whiskerLow: 60.2, q1: 62, median: 63.1, q3: 64.4, whiskerHigh: 67.9, max: 200, outliers: HIGH, outlierCount: 30 });

export const BOX = Object.freeze({
  kind: "boxplot",
  title: "Temperature",
  x_label: "Device",
  y_label: "Temperature (°C)",
  boxplot: { method: "tukey_1_5_iqr_linear_p_v1", groups: [G_ODD, G_OUT] },
  y_reference_lines: [{ y: 70, label: "USL", role: "usl" }],
});

function render(chart, width = 640, options = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, null, { availableWidth: width, ...options });
  const svg = el.querySelector("svg");
  const num = (node, a) => Number(node.getAttribute(a));
  return { dom, el, svg, drawn, num, viewBox: svg ? svg.getAttribute("viewBox").split(" ").map(Number) : null };
}

test("BP-1: box, median and whisker coordinates come from the statistics; reference lines enter the value domain", () => {
  const r = render(BOX);
  assert.ok(r.svg);
  const boxes = [...r.svg.querySelectorAll("rect.box-body")];
  assert.equal(boxes.length, 2);
  const g0 = r.svg.querySelector("g.box-g0");
  const body = g0.querySelector("rect.box-body");
  const median = g0.querySelector("line.box-median");
  const low = g0.querySelector("line.box-whisker-low");
  const high = g0.querySelector("line.box-whisker-high");
  // The scale is monotone: q3 draws above the median, which draws above q1, and the whiskers reach min / max.
  assert.ok(r.num(body, "y") < r.num(median, "y1") && r.num(median, "y1") < r.num(body, "y") + r.num(body, "height"));
  assert.ok(Math.abs(r.num(low, "y1") - (r.num(body, "y") + r.num(body, "height"))) < EPS, "lower whisker starts at q1");
  assert.ok(Math.abs(r.num(high, "y1") - r.num(body, "y")) < EPS, "upper whisker starts at q3");
  assert.ok(r.num(low, "y2") > r.num(low, "y1") && r.num(high, "y2") < r.num(high, "y1"));
  // Linear check: (median − q1) / (q3 − q1) = 0.5 in value space equals the same ratio in pixel space.
  const ratio = (r.num(body, "y") + r.num(body, "height") - r.num(median, "y1")) / r.num(body, "height");
  assert.ok(Math.abs(ratio - 0.5) < 1e-6);
  // Whisker of G_ODD spans 1..9 (min..max) since it has no outliers: its pixel length equals 4 × the box height.
  const whiskerSpan = r.num(low, "y2") - r.num(high, "y2");
  assert.ok(Math.abs(whiskerSpan / r.num(body, "height") - 2) < 1e-6, "(9 − 1) / (7 − 3) = 2");
  // Outliers: 20 hollow circles in group 2, none in group 1; the reference line and the outliers extend the domain.
  assert.equal(r.svg.querySelectorAll("g.box-g0 circle.box-outlier").length, 0);
  const circles = [...r.svg.querySelectorAll("g.box-g1 circle.box-outlier")];
  assert.equal(circles.length, 20);
  assert.ok(circles.every((c) => c.getAttribute("fill") === "none"), "outliers are hollow");
  const ref = r.svg.querySelector("g.y-ref-lines line");
  assert.ok(ref, "the usl reference line is drawn");
  const dom = chartYDomain([1, 9, 58.1, 200, ...HIGH], [70], "boxplot");
  assert.deepEqual(dom, [1, 200]);
  const refY = r.num(ref, "y1");
  const topOutlier = Math.min(...circles.map((c) => r.num(c, "cy")));
  assert.ok(refY > topOutlier && refY < r.num(low, "y2"), "the reference line sits between the top outlier and the lowest whisker end");
  assert.equal(r.svg.querySelector("ul.chart-legend"), null);
  assert.equal(r.drawn.hiddenSeries.length, 0);
  assert.equal(r.drawn.xDomain, undefined, "no X zoom");
  assert.equal(r.drawn.groupCount, 2);
});

test("hit items: one box per group with n and the five numbers, one item per listed outlier, reference lines reachable", () => {
  const r = render(BOX);
  const [boxes, outliers] = r.drawn.hit.series;
  assert.equal(boxes.length, 2);
  assert.deepEqual(boxes[0].lines, ["Device: Oven-01", "n: 9", "Min: 1", "Q1: 3", "Median: 5", "Q3: 7", "Max: 9", "Whiskers: 1 – 9", "Outliers: None"]);
  assert.equal(boxes[1].lines[1], "n: 130 (excluded 2)");
  assert.match(boxes[1].lines.at(-1), /^Outliers: 200, 199, .*181 \(another 10 not shown\)$/);
  assert.equal(outliers.length, 20);
  assert.deepEqual(outliers[0].lines, ["Device: Oven-02", "Outlier: 181", "Temperature (°C): 181", "Another 10 outliers not shown"]);
  const first = r.drawn.hit.first();
  assert.equal(first.index, 0);
  assert.equal(r.drawn.hit.step(first, "right").index, 1);
  const up = r.drawn.hit.step(first, "up");
  assert.equal(up.series, 1, "up from a box reaches the outliers");
  const ref = r.drawn.hit.step(up, "up");
  assert.equal(ref.type, "reference");
  assert.deepEqual(ref.lines, ["USL", "Temperature (°C): 70"]);
  // Pointer: inside the second band near an outlier picks the outlier, near the median picks the box.
  const g1 = r.svg.querySelector("g.box-g1");
  const median = g1.querySelector("line.box-median");
  const cx = boxes[1].cx;
  assert.equal(r.drawn.hit.nearest(cx, r.num(median, "y1")).type, "bar");
  const topCircle = [...g1.querySelectorAll("circle.box-outlier")].reduce((a, c) => (r.num(c, "cy") < r.num(a, "cy") ? c : a));
  assert.equal(r.drawn.hit.nearest(cx, r.num(topCircle, "cy")).type, "point");
});

/**
 * Lowest y (in viewBox units) reached by any rotated category tick label, and the top of the axis caption.
 * A label is end-anchored at its tick and rotated by -35 degrees, so it hangs by width x sin + glyph height x cos.
 */
function categoryAxisGeometry(svg, layout, measure, theme = DEFAULT_CHART_RENDER_THEME) {
  const angle = (35 * Math.PI) / 180;
  const ticks = [...svg.querySelectorAll(".tick text")].filter((t) => (t.getAttribute("transform") || "").includes("rotate(-35)") && t.textContent);
  const axisY = layout.marginTop + layout.innerH;
  const hang = (t) => theme.style.axis.tickLength + theme.style.axis.tickPadding
    + measure(t.textContent) * Math.sin(angle) + theme.style.type.tickSize * Math.cos(angle);
  const lowest = Math.max(...ticks.map((t) => axisY + hang(t)));
  // Leftmost x reached by a label: its tick x (from the tick group's translate) minus its run along the baseline.
  const tickX = (t) => Number(/translate\(([-\d.]+)/.exec(t.parentNode.getAttribute("transform"))[1]);
  const leftmost = Math.min(...ticks.map((t) => layout.marginLeft + tickX(t) - measure(t.textContent) * Math.cos(angle)));
  const caption = svg.querySelector('text[data-parler-palette-role="axis-label"]');
  const captionBaseline = Number(caption.getAttribute("y"));
  // Perpendicular distance between neighbouring drawn labels: they overprint when it is under one glyph height.
  const xs = ticks.map(tickX).sort((a, b) => a - b);
  const minGap = xs.length > 1 ? Math.min(...xs.slice(1).map((x, i) => x - xs[i])) * Math.sin(angle) : Infinity;
  return { count: ticks.length, lowest, leftmost, minGap, texts: ticks.map((t) => t.textContent), captionTop: captionBaseline - theme.style.type.axisLabelSize, captionBaseline, height: layout.viewBoxH };
}

test("BP-6: rotated group labels never reach the axis caption or leave the SVG, whatever their length", () => {
  const measure = (text) => String(text).length * 7.5;
  const labelSets = {
    "live utilization states": ["Down", "Running", "Setup", "Unavailable", "Idle"],
    "single characters": ["a", "b"],
    "longest label the formatter lets through": ["A".repeat(40), "B"],
  };
  for (const [name, keys] of Object.entries(labelSets)) {
    const groups = keys.map((key) => ({ ...G_ODD, key }));
    const r = render({ ...BOX, y_reference_lines: undefined, boxplot: { ...BOX.boxplot, groups } }, 900, { measureText: measure });
    const geo = categoryAxisGeometry(r.svg, r.drawn.hit.layout, measure);
    assert.equal(geo.count, keys.length, name);
    assert.ok(geo.lowest <= geo.captionTop, `${name}: lowest label point ${geo.lowest.toFixed(1)} is above the caption top ${geo.captionTop}`);
    assert.ok(geo.captionBaseline < geo.height, `${name}: the caption is inside the SVG`);
    assert.ok(geo.leftmost >= 0, `${name}: no label leaves the SVG on the left (${geo.leftmost.toFixed(1)})`);
    assert.equal(r.drawn.hit.layout.innerH, 240 - r.drawn.hit.layout.marginTop - 44, `${name}: the plot area keeps its height`);
  }
});

/** Pixel widths by glyph class, so a test label is as wide as a browser would make it: CJK a full em, W nearly so. */
const glyphMeasure = (tickSize) => (text) => [...String(text)].reduce((w, ch) =>
  w + (/[\u2E80-\u9FFF\uF900-\uFAFF]/.test(ch) ? 1 : /[WM]/.test(ch) ? 0.95 : ch === "…" ? 1 : 0.56) * tickSize, 0);
const themeWithTick = (tickSize) => ({ ...DEFAULT_CHART_RENDER_THEME, style: { ...DEFAULT_CHART_RENDER_THEME.style, type: { ...DEFAULT_CHART_RENDER_THEME.style.type, tickSize } } });
const CJK_LONG = "设备维护等待生产排程及材料运输等待状态报警";

test("BP-7: capped margins elide by pixel width, so wide glyphs and an 18 px tick font stay inside the SVG", () => {
  const cases = [
    { name: "320 wide, CJK first label", width: 320, tick: 12, keys: [CJK_LONG, "生产中", "空闲"] },
    { name: "320 wide, 21 W", width: 320, tick: 12, keys: ["W".repeat(21), "Running", "Idle"] },
    { name: "900 wide, 18 px tick font, CJK", width: 900, tick: 18, keys: [CJK_LONG, "生产中", "空闲"] },
  ];
  for (const c of cases) {
    const theme = themeWithTick(c.tick);
    const measure = glyphMeasure(c.tick);
    const groups = c.keys.map((key) => ({ ...G_ODD, key }));
    const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
    const el = dom.window.document.getElementById("host");
    const drawn = drawChart(el, { ...BOX, y_reference_lines: undefined, boxplot: { ...BOX.boxplot, groups } }, theme, null, { availableWidth: c.width, measureText: measure });
    const svg = el.querySelector("svg");
    const geo = categoryAxisGeometry(svg, drawn.hit.layout, measure, theme);
    assert.ok(geo.leftmost >= 0, `${c.name}: leftmost label edge ${geo.leftmost.toFixed(1)} is inside the SVG`);
    assert.ok(geo.lowest <= geo.captionTop, `${c.name}: lowest label point ${geo.lowest.toFixed(1)} is above the caption top ${geo.captionTop}`);
    assert.ok(geo.captionBaseline < geo.height && geo.height <= 240 + 160 - 44, `${c.name}: the height stays bounded (${geo.height})`);
    assert.ok(geo.texts[0].endsWith("…") && geo.texts[0].length > 1, `${c.name}: the long label is elided, not dropped ("${geo.texts[0]}")`);
    assert.equal(geo.texts[1], c.keys[1], `${c.name}: a label that fits is drawn in full`);
    const body = svg.querySelector("g.box-g0 rect.box-body");
    const num = (a) => Number(body.getAttribute(a));
    const item = drawn.hit.nearest(num("x") + num("width") / 2, num("y") + num("height") / 2);
    assert.ok(JSON.stringify(item).includes(c.keys[0]), `${c.name}: the query item keeps the full name`);
  }
});

/** Height of a vertical category chart: the 240 px plot plus the measured room for its rotated tick labels. */
function categoryChartHeight(labels) {
  const tick = DEFAULT_CHART_RENDER_THEME.style.type.tickSize;
  return 240 + rotatedCategoryAxisBottom(labels, DEFAULT_CHART_RENDER_THEME, (t) => estimateTextWidth(t, tick)).extra;
}

test("BP-2: two groups narrow the plot per the band cap and 24 groups fill 1200 px", () => {
  const two = render(BOX, 1200);
  const left = two.drawn.hit.layout.marginLeft;
  const expected = Math.round(left + 2 * bandMaxStep(1) + 20);
  assert.equal(two.viewBox[2], expected, "w = marginLeft + 2 × 96 + marginRight");
  assert.equal(two.viewBox[3], categoryChartHeight(["Oven-01", "Oven-02"]), "240 plus the room its rotated labels need");
  assert.equal(two.drawn.hit.layout.innerH, 240 - two.drawn.hit.layout.marginTop - 44, "the plot area keeps its height");
  assert.equal(two.drawn.plotSize.availableWidth, 1200);
  const groups = Array.from({ length: 24 }, (_v, i) => ({ ...G_ODD, key: `G${i + 1}` }));
  const many = render({ ...BOX, y_reference_lines: undefined, boxplot: { ...BOX.boxplot, groups } }, 1200);
  assert.equal(many.viewBox[2], 1200, "24 groups fill the width");
  assert.equal(many.svg.querySelectorAll("rect.box-body").length, 24);
});

test("an invalid payload is refused by the renderer too, and a clone prints with known roles only", () => {
  const bad = { ...BOX, boxplot: { ...BOX.boxplot, groups: [{ ...G_ODD, q1: 6 }] } };
  const original = console.warn;
  const warnings = [];
  console.warn = (m) => warnings.push(String(m));
  try {
    const r = render(bad);
    assert.equal(r.svg, null);
    assert.equal(r.drawn, null);
    assert.ok(warnings.some((w) => w.includes("CHART_BOXPLOT_INVALID")));
  } finally {
    console.warn = original;
  }
  const r = render(BOX);
  const holder = r.dom.window.document.createElement("div");
  holder.append(r.svg.cloneNode(true));
  const rewritten = rewriteClonedChartPaletteForPrint(holder, DEFAULT_PORTABLE_PRINT_THEME);
  assert.ok(rewritten > 0);
  assert.equal(holder.querySelectorAll("circle.box-outlier").length, 20);
});
