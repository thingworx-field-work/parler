import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";
import { scaleLinear } from "d3-scale";

import {
  barRectGeometry,
  chartYDomain,
  drawChart,
  estimateTextWidth,
  responsiveChartLeftMargin,
  rotatedCategoryAxisBottom,
  signedBarYDomain,
} from "../components/chart-draw.js";
import {
  DEFAULT_PORTABLE_PRINT_THEME,
  rewriteClonedChartPaletteForPrint,
} from "./assistantResponseActions.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";

const EPS = 1e-6;

/** Default theme with a distinctive axis color and width so propagation is observable. */
function customAxisTheme() {
  const theme = structuredClone(DEFAULT_CHART_RENDER_THEME);
  theme.palette.axis = "rgba(255, 0, 0, 0.5)";
  theme.style.axisLineWidth = 4;
  return theme;
}

/** Draw a bar chart into a JSDOM host and read back the geometry the renderer produced. */
function renderBar(chart, theme = DEFAULT_CHART_RENDER_THEME) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, {
    pretendToBeVisual: true,
  });
  const el = dom.window.document.getElementById("host");
  drawChart(el, { kind: "bar", ...chart }, theme);
  const svg = el.querySelector("svg");
  assert.ok(svg, "bar chart must render an svg");
  const num = (node, attr) => Number(node.getAttribute(attr));
  const rects = [...svg.querySelectorAll("rect[class^='bar-s']")].map((r) => ({
    series: Number(r.getAttribute("class").replace("bar-s", "")),
    x: num(r, "x"),
    y: num(r, "y"),
    width: num(r, "width"),
    height: num(r, "height"),
  }));
  const baseline = svg.querySelector("line.zero-baseline");
  const plotGroup = [...svg.querySelectorAll("g[transform^='translate(0,']")].find((g) =>
    g.querySelector(".domain")
  );
  assert.ok(plotGroup, "category axis group must exist");
  const innerH = Number(/translate\(0,([-\d.]+)\)/.exec(plotGroup.getAttribute("transform"))[1]);
  const refLines = [...svg.querySelectorAll("g.y-ref-lines line")].map((l) => num(l, "y1"));
  return {
    svg,
    rects,
    baseline,
    baselineY: baseline ? num(baseline, "y1") : innerH,
    innerH,
    refLines,
  };
}

/** Horizontal full-width strokes carrying the axis palette role at a given plot Y. */
function axisRoleStrokesAt(svg, y) {
  return [...svg.querySelectorAll('line[data-parler-palette-role="axis"]')].filter(
    (l) => Math.abs(Number(l.getAttribute("y1")) - y) < EPS && Number(l.getAttribute("x1")) === 0
  );
}

function assertFiniteNonNegative(rects) {
  for (const r of rects) {
    for (const key of ["x", "y", "width", "height"]) {
      assert.ok(Number.isFinite(r[key]), `${key} must be finite, got ${r[key]}`);
    }
    assert.ok(r.width > 0, `width must be positive, got ${r.width}`);
    assert.ok(r.height >= 0, `height must be non-negative, got ${r.height}`);
  }
}

function bottom(rect) {
  return rect.y + rect.height;
}

test("signedBarYDomain spans zero, includes reference lines and never degenerates", () => {
  assert.deepEqual(signedBarYDomain([-5, 10]), [-5, 10]);
  assert.deepEqual(signedBarYDomain([-5, -10]), [-10, 0]);
  assert.deepEqual(signedBarYDomain([3, 8]), [0, 8]);
  assert.deepEqual(signedBarYDomain([0, 0]), [-1, 1]);
  assert.deepEqual(signedBarYDomain([0, 0], [0]), [-1, 1]);
  assert.deepEqual(signedBarYDomain([0, 0], [4]), [0, 4]);
  assert.deepEqual(signedBarYDomain([4, 8], [-3]), [-3, 8]);
  assert.deepEqual(signedBarYDomain([4, 8], [12]), [0, 12]);
  assert.deepEqual(signedBarYDomain([], []), [-1, 1]);
  assert.deepEqual(signedBarYDomain(["2", "-6"], ["x"]), [-6, 2]);
});

test("chartYDomain routes bar to the signed rule and keeps line/scatter data extent", () => {
  assert.deepEqual(chartYDomain([-5, 10], [], "bar"), [-5, 10]);
  assert.deepEqual(chartYDomain([3, 8], [], "bar"), [0, 8]);
  assert.deepEqual(chartYDomain([3, 8], [], "line"), [3, 8]);
  assert.deepEqual(chartYDomain([3, 8], [12], "scatter"), [3, 12]);
  assert.deepEqual(chartYDomain([3, 8], [-1], "line"), [-1, 8]);
});

test("barRectGeometry measures every bar from the zero baseline with non-negative height", () => {
  const yScale = scaleLinear().domain([-10, 10]).range([200, 0]);
  const y0 = yScale(0);
  assert.equal(y0, 100);
  assert.deepEqual(barRectGeometry(yScale, 10), { y: 0, height: 100 });
  assert.deepEqual(barRectGeometry(yScale, -10), { y: 100, height: 100 });
  assert.deepEqual(barRectGeometry(yScale, 5), { y: 50, height: 50 });
  assert.deepEqual(barRectGeometry(yScale, -5), { y: 100, height: 50 });
  assert.deepEqual(barRectGeometry(yScale, 0), { y: 100, height: 0 });
});

test("mixed-sign bars share one zero baseline with heights proportional to values", () => {
  const { rects, baseline, baselineY, innerH } = renderBar({
    series: [{ name: "delta", x: ["a", "b"], y: [-5, 10] }],
  });
  assert.ok(baseline, "a domain crossing zero draws the zero baseline");
  assert.equal(rects.length, 2);
  assertFiniteNonNegative(rects);
  const [neg, pos] = rects;
  assert.ok(baselineY > 0 && baselineY < innerH, `baseline ${baselineY} must sit inside the plot`);
  assert.ok(Math.abs(neg.y - baselineY) < EPS, "negative bar starts at the zero baseline");
  assert.ok(Math.abs(bottom(pos) - baselineY) < EPS, "positive bar ends at the zero baseline");
  assert.ok(Math.abs(neg.height / pos.height - 0.5) < EPS, "|-5| : 10 must be 1 : 2 in pixels");
  assert.ok(neg.height > 0 && pos.height > 0);
});

test("all-negative bars hang from a baseline at the top of the plot", () => {
  const { rects, baseline, baselineY } = renderBar({
    series: [{ name: "delta", x: ["a", "b"], y: [-5, -10] }],
  });
  assert.ok(baseline, "an all-negative domain draws the zero baseline");
  assertFiniteNonNegative(rects);
  assert.ok(Math.abs(baselineY) < EPS, `baseline must be at the plot top, got ${baselineY}`);
  for (const r of rects) {
    assert.ok(Math.abs(r.y - baselineY) < EPS, "every negative bar starts at the baseline");
  }
  assert.ok(Math.abs(rects[0].height / rects[1].height - 0.5) < EPS, "|-5| : |-10| must be 1 : 2");
});

test("all-zero bars draw zero-height rectangles around a centred baseline", () => {
  const { svg, rects, baseline, baselineY, innerH } = renderBar({
    series: [{ name: "delta", x: ["a", "b"], y: [0, 0] }],
  });
  assert.ok(baseline, "the [-1, 1] fallback domain draws the zero baseline");
  assert.equal(rects.length, 2);
  assertFiniteNonNegative(rects);
  for (const r of rects) {
    assert.equal(r.height, 0, "zero value must not draw a positive bar");
    assert.ok(Math.abs(r.y - baselineY) < EPS);
  }
  assert.ok(Math.abs(baselineY - innerH / 2) < EPS, "[-1, 1] fallback domain centres zero");
  assert.ok(svg.querySelectorAll("g.chart-grid line").length > 0, "grid still renders");
});

test("multi-series mixed-sign bars use the same baseline and rule per series", () => {
  const { rects, baselineY } = renderBar({
    series: [
      { name: "s1", x: ["a", "b"], y: [3, -2] },
      { name: "s2", x: ["a", "b"], y: [-4, 1] },
    ],
  });
  assert.equal(rects.length, 4);
  assertFiniteNonNegative(rects);
  const byValue = [
    { rect: rects[0], value: 3 },
    { rect: rects[1], value: -2 },
    { rect: rects[2], value: -4 },
    { rect: rects[3], value: 1 },
  ];
  for (const { rect, value } of byValue) {
    if (value >= 0) {
      assert.ok(Math.abs(bottom(rect) - baselineY) < EPS, `bar ${value} ends at the baseline`);
    } else {
      assert.ok(Math.abs(rect.y - baselineY) < EPS, `bar ${value} starts at the baseline`);
    }
  }
  const unit = byValue[3].rect.height;
  for (const { rect, value } of byValue) {
    assert.ok(
      Math.abs(rect.height / unit - Math.abs(value)) < EPS,
      `height of ${value} must be |${value}| units`
    );
  }
});

test("positive data with a negative reference line extends the domain below zero", () => {
  const { rects, baselineY, innerH, refLines } = renderBar({
    series: [{ name: "delta", x: ["a", "b"], y: [4, 8] }],
    y_reference_lines: [{ y: -3, role: "lcl", label: "LCL" }],
  });
  assertFiniteNonNegative(rects);
  assert.equal(refLines.length, 1);
  const refY = refLines[0];
  assert.ok(Number.isFinite(refY));
  assert.ok(refY > baselineY && refY <= innerH, "reference line is drawn below the baseline");
  assert.ok(baselineY > 0 && baselineY < innerH, "baseline is no longer glued to the plot bottom");
  for (const r of rects) {
    assert.ok(Math.abs(bottom(r) - baselineY) < EPS, "positive bars still end at the baseline");
  }
  const unit = rects[0].height / 4;
  assert.ok(Math.abs((refY - baselineY) / unit - 3) < EPS, "reference line sits 3 units below zero");
});

test("positive-only bars keep the pre-C0 layout: one bottom stroke, no extra baseline", () => {
  const theme = customAxisTheme();
  const { svg, rects, baseline, innerH } = renderBar(
    {
      title: "Utilisation",
      series: [{ name: "util", x: ["a", "b", "c"], y: [1, 5, 3] }],
      y_reference_lines: [{ y: 6, role: "usl", label: "USL" }],
    },
    theme
  );
  assertFiniteNonNegative(rects);
  assert.equal(baseline, null, "no zero baseline when the domain does not extend below zero");
  assert.equal(axisRoleStrokesAt(svg, innerH).length, 0, "no axis-role line duplicates the axis");
  const axisDomains = svg.querySelectorAll('path.domain[data-parler-palette-role="axis"]');
  assert.equal(axisDomains.length, 2, "exactly the category and value axis domains are painted");
  for (const r of rects) {
    assert.ok(Math.abs(bottom(r) - innerH) < EPS, "bars end at the category axis");
  }
  assert.ok(Math.abs(rects[1].height / rects[0].height - 5) < EPS, "5 : 1 in pixels");
  const clone = svg.cloneNode(true);
  rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  assert.equal(clone.querySelector("line.zero-baseline"), null, "print clone adds no baseline");
  assert.equal(axisRoleStrokesAt(clone, innerH).length, 0);
});

test("zero baseline takes the axis color and width from the theme and prints as an axis", () => {
  const theme = customAxisTheme();
  const { svg, baseline, baselineY, innerH } = renderBar(
    { series: [{ name: "delta", x: ["a", "b"], y: [-5, 10] }] },
    theme
  );
  assert.ok(baseline);
  assert.equal(baseline.getAttribute("stroke"), theme.palette.axis);
  assert.equal(baseline.getAttribute("stroke-width"), String(theme.style.axisLineWidth));
  assert.ok(baselineY > 0 && baselineY < innerH, "baseline is distinct from the bottom axis");
  assert.equal(axisRoleStrokesAt(svg, baselineY).length, 1, "one baseline stroke at yScale(0)");
  const clone = svg.cloneNode(true);
  rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  const printed = clone.querySelector("line.zero-baseline");
  assert.equal(printed.getAttribute("stroke"), DEFAULT_PORTABLE_PRINT_THEME.palette.border);
  assert.equal(printed.getAttribute("stroke-width"), String(theme.style.axisLineWidth));
  assert.equal(printed.getAttribute("y1"), baseline.getAttribute("y1"));
});

test("left margin estimate uses the signed bar domain instead of assuming a zero floor", () => {
  const positive = responsiveChartLeftMargin(chartYDomain([5000, 10], [], "bar"), "bar", 5);
  const negative = responsiveChartLeftMargin(chartYDomain([-5000, 10], [], "bar"), "bar", 5);
  assert.ok(negative > positive, `negative labels need more gutter: ${negative} vs ${positive}`);
  const refOnly = responsiveChartLeftMargin(chartYDomain([4, 8], [-123456], "bar"), "bar", 5);
  assert.ok(refOnly > positive, "a negative reference line widens the gutter too");
});

test("signed bar svg clones through the print palette rewrite without unknown roles", () => {
  const { svg } = renderBar({
    series: [{ name: "delta", x: ["a", "b"], y: [-5, 10] }],
    y_reference_lines: [{ y: -3, role: "lcl" }],
  });
  const clone = svg.cloneNode(true);
  const rewritten = rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  assert.ok(rewritten > 0);
  const baseline = clone.querySelector("line.zero-baseline");
  assert.equal(baseline.getAttribute("stroke"), DEFAULT_PORTABLE_PRINT_THEME.palette.border);
  const screenRects = [...svg.querySelectorAll("rect[class^='bar-s']")].map((r) => r.getAttribute("height"));
  const printRects = [...clone.querySelectorAll("rect[class^='bar-s']")].map((r) => r.getAttribute("height"));
  assert.deepEqual(printRects, screenRects, "print reuses the screen geometry");
});

// ---------------------------------------------------------------------------
// L1 (design §4.6): band cap and full-width invariants through the renderer
// ---------------------------------------------------------------------------

/** SVG markup with the per-draw random clip-path id neutralised, for whole-geometry comparisons. */
const stableSvg = (svg) => svg.outerHTML.replace(/aipc-[a-z0-9]+-\d+/g, "aipc");

function renderAt(chart, availableWidth, options = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, null, { availableWidth, ...options });
  const svg = el.querySelector("svg");
  const viewBox = svg.getAttribute("viewBox").split(" ").map(Number);
  const rects = [...svg.querySelectorAll("rect[class^='bar-s']")].map((r) => Number(r.getAttribute("width")));
  return { drawn, svg, viewBox, rects, layout: drawn.hit.layout };
}

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

test("LS-6: rotated category labels of a vertical bar never reach the axis caption or leave the SVG", () => {
  const measure = (text) => String(text).length * 7.5;
  const reasonGroups = ["Downtime", "Idle", "Mechanical", "Misc", "Running", "Setup", "Unavailable"];
  const stacked = { kind: "bar", stackMode: "stacked", x_label: "Reason group", y_label: "Total event duration (seconds)",
    series: ["Down", "Idle", "Running"].map((name, s) => ({ name, x: reasonGroups, y: reasonGroups.map((_g, i) => (i + s) * 10) })) };
  const long = { kind: "bar", x_label: "Asset", y_label: "Value", series: [{ name: "s", x: ["A".repeat(40), "short"], y: [1, 2] }] };
  for (const [name, chart] of Object.entries({ stacked, long })) {
    for (const W of [320, 900]) {
      const r = renderAt(chart, W, { measureText: measure });
      const geo = categoryAxisGeometry(r.svg, r.layout, measure);
      assert.ok(geo.count > 0, name);
      assert.ok(geo.lowest <= geo.captionTop, `${name} at ${W}: lowest label point ${geo.lowest.toFixed(1)} is above the caption top ${geo.captionTop}`);
      assert.ok(geo.captionBaseline < geo.height, `${name} at ${W}: the caption is inside the SVG`);
      assert.ok(geo.leftmost >= 0, `${name} at ${W}: no label leaves the SVG on the left (${geo.leftmost.toFixed(1)})`);
      assert.equal(r.layout.innerH, 240 - r.layout.marginTop - 44, `${name} at ${W}: the plot area keeps its height`);
    }
  }
  const horizontal = renderAt({ kind: "bar", orientation: "horizontal", series: [{ name: "s", x: ["Unavailable", "b"], y: [1, 2] }] }, 900);
  assert.equal(horizontal.viewBox[3], 240, "horizontal bars are untouched");
});

/** Pixel widths by glyph class, so a test label is as wide as a browser would make it: CJK a full em, W nearly so. */
const glyphMeasure = (tickSize) => (text) => [...String(text)].reduce((w, ch) =>
  w + (/[\u2E80-\u9FFF\uF900-\uFAFF]/.test(ch) ? 1 : /[WM]/.test(ch) ? 0.95 : ch === "…" ? 1 : 0.56) * tickSize, 0);
const themeWithTick = (tickSize) => ({ ...DEFAULT_CHART_RENDER_THEME, style: { ...DEFAULT_CHART_RENDER_THEME.style, type: { ...DEFAULT_CHART_RENDER_THEME.style.type, tickSize } } });
const CJK_LONG = "设备维护等待生产排程及材料运输等待状态报警";

test("LS-7: capped margins elide by pixel width; dense categories thin their labels instead of overprinting", () => {
  const cases = [
    { name: "320 wide, CJK first label", width: 320, tick: 12, x: [CJK_LONG, "生产中", "空闲"] },
    { name: "320 wide, 21 W", width: 320, tick: 12, x: ["W".repeat(21), "Running", "Idle"] },
    { name: "900 wide, 18 px tick font, CJK", width: 900, tick: 18, x: [CJK_LONG, "生产中", "空闲"] },
  ];
  for (const c of cases) {
    const theme = themeWithTick(c.tick);
    const measure = glyphMeasure(c.tick);
    const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
    const el = dom.window.document.getElementById("host");
    const drawn = drawChart(el, { kind: "bar", series: [{ name: "s", x: c.x, y: [10, 12, 14] }] }, theme, null, { availableWidth: c.width, measureText: measure });
    const geo = categoryAxisGeometry(el.querySelector("svg"), drawn.hit.layout, measure, theme);
    assert.ok(geo.leftmost >= 0, `${c.name}: leftmost label edge ${geo.leftmost.toFixed(1)} is inside the SVG`);
    assert.ok(geo.lowest <= geo.captionTop, `${c.name}: lowest label point ${geo.lowest.toFixed(1)} is above the caption top ${geo.captionTop}`);
    assert.ok(geo.height <= 240 + 160 - 44, `${c.name}: the height stays bounded (${geo.height})`);
    assert.ok(geo.texts[0].endsWith("…") && geo.texts[0].length > 1, `${c.name}: elided, not dropped ("${geo.texts[0]}")`);
    assert.equal(geo.texts[1], c.x[1], `${c.name}: a label that fits is drawn in full`);
  }
  // 24 categories in a 320 px card: about 11 px per band, far too little for one rotated label per band.
  const machines = Array.from({ length: 24 }, (_v, i) => `Machine-${String(i + 1).padStart(2, "0")}`);
  const measure = glyphMeasure(12);
  const dense = renderAt({ kind: "bar", series: [{ name: "s", x: machines, y: machines.map((_m, i) => 10 + 2 * i) }] }, 320, { measureText: measure });
  const geo = categoryAxisGeometry(dense.svg, dense.layout, measure);
  assert.ok(geo.count < 24 && geo.count >= 2, `labels are thinned (${geo.count} of 24 drawn)`);
  assert.ok(geo.minGap >= 12, `neighbouring labels are at least one glyph height apart (${geo.minGap.toFixed(1)})`);
  assert.equal(geo.texts[0], "Machine-01");
  assert.equal(geo.texts[geo.texts.length - 1], "Machine-24", "the last category keeps its label");
  assert.equal(dense.svg.querySelectorAll("rect[class^='bar-s']").length, 24, "every bar is still drawn");
  assert.equal(dense.svg.querySelectorAll(".tick line").length >= 24, true, "every tick mark is still drawn");
  const wide = renderAt({ kind: "bar", series: [{ name: "s", x: machines, y: machines.map((_m, i) => 10 + 2 * i) }] }, 1200, { measureText: measure });
  assert.equal(categoryAxisGeometry(wide.svg, wide.layout, measure).count, 24, "at 1200 px every label is drawn");
});

test("LS-8: series of different lengths lay out the bands that are drawn, so no label is thinned away by phantom categories", () => {
  const machines = Array.from({ length: 24 }, (_v, i) => `Machine-${String(i + 1).padStart(2, "0")}`);
  const chart = { kind: "bar", series: [{ name: "A", x: machines, y: machines.map(() => 10) }, { name: "B", x: machines.slice(0, 2), y: [12, 14] }] };
  const measure = glyphMeasure(12);
  const r = renderAt(chart, 900, { measureText: measure });
  assert.equal(r.svg.querySelectorAll("rect[class^='bar-s']").length, 4, "two categories by two series, as before");
  const geo = categoryAxisGeometry(r.svg, r.layout, measure);
  assert.deepEqual(geo.texts, ["Machine-01", "Machine-02"], "both drawn categories keep their full label");
});

/** Height of a vertical bar chart: the 240 px plot plus the measured room for its rotated tick labels. */
function verticalBarHeight(labels) {
  const tick = DEFAULT_CHART_RENDER_THEME.style.type.tickSize;
  return 240 + rotatedCategoryAxisBottom(labels, DEFAULT_CHART_RENDER_THEME, (t) => estimateTextWidth(t, tick)).extra;
}

test("LS-3: vertical bars are no wider than the capped step; w comes from the renderer's own margins", () => {
  const two = { kind: "bar", series: [{ name: "s", x: ["a", "b"], y: [10, 20] }] };
  for (const W of [320, 1200]) {
    const r = renderAt(two, W);
    assert.equal(r.layout.marginLeft, 32, `left margin for [10, 20] at ${W}`);
    assert.equal(r.viewBox[2], 244, `w = 32 + 2 × 96 + 20 at W = ${W}`);
    assert.equal(r.viewBox[3], verticalBarHeight(["a", "b"]), "240 plus the room the rotated labels need");
    assert.equal(r.layout.innerH, 240 - r.layout.marginTop - 44, "the plot area keeps its height");
    assert.deepEqual(r.drawn.plotSize, { w: 244, h: 240, legendPlacement: "below", legendWidth: 0, availableWidth: W });
    assert.ok(r.rects.every((w) => w <= 77 + 1e-9), `bars are at most 77px thick (${r.rects})`);
    assert.equal(r.layout.viewBoxW, 244, "the hit layout uses the plot width, not W");
  }
  const six = { kind: "bar", series: [{ name: "s", x: ["a", "b", "c", "d", "e", "f"], y: [10, 20, 30, 40, 50, 60] }] };
  assert.equal(renderAt(six, 320).viewBox[2], 320, "min(628, 320)");
  assert.equal(renderAt(six, 1200).viewBox[2], 628, "32 + 6 × 96 + 20");
  const names = Array.from({ length: 24 }, (_v, i) => `c${i}`);
  const many = { kind: "bar", series: [{ name: "s", x: names, y: names.map((_n, i) => i + 1) }] };
  for (const W of [320, 1200]) {
    const r = renderAt(many, W);
    assert.equal(r.viewBox[2], W, "24 categories fill the width as before");
    const before = renderAt(many, W, { viewBoxWidth: W });
    assert.equal(stableSvg(before.svg), stableSvg(r.svg), "the legacy viewBoxWidth alias produces the same SVG");
  }
  const grouped = { kind: "bar", series: Array.from({ length: 6 }, (_v, s) => ({ name: `s${s}`, x: ["a", "b"], y: [s + 1, s + 2] })) };
  const g = renderAt(grouped, 1200);
  assert.equal(g.viewBox[2], g.layout.marginLeft + 2 * 256 + 20, "six series slots use the 256 step");
});

test("LS-4: line, scatter in every X mode and horizontal bars keep the full width at four widths", () => {
  const iso = ["2026-09-12T00:00:00Z", "2026-09-12T00:10:00Z", "2026-09-12T00:20:00Z"];
  const charts = {
    line: { kind: "line", series: [{ name: "s", x: iso, y: [1, 2, 3] }] },
    timeScatter: { kind: "scatter", series: [{ name: "s", x: iso, y: [1, 2, 3] }] },
    numericScatter: { kind: "scatter", series: [{ name: "s", x: ["1", "2", "3"], y: [1, 2, 3] }] },
    elapsed: { kind: "line", xAxisMode: "elapsed", elapsedDomain: { start: 0, end: 60 }, series: [{ name: "s", x: ["0", "30", "60"], y: [1, 2, 3] }] },
    normalized: { kind: "line", xAxisMode: "normalized", normalizedDomain: { start: 0, end: 1 }, series: [{ name: "s", x: ["0", "0.5", "1"], y: [1, 2, 3] }] },
    horizontal: { kind: "bar", orientation: "horizontal", series: [{ name: "s", x: ["a", "b"], y: [10, 20] }] },
  };
  for (const [name, chart] of Object.entries(charts)) {
    for (const W of [320, 480, 768, 1200]) {
      const r = renderAt(chart, W);
      assert.equal(r.viewBox[2], W, `${name} at ${W} keeps the full width`);
      if (name !== "horizontal") assert.equal(r.viewBox[3], 240, `${name} keeps 240 high`);
      assert.equal(r.drawn.plotSize.w, W);
      const before = renderAt(chart, W, { viewBoxWidth: W });
      assert.equal(stableSvg(before.svg), stableSvg(r.svg), `${name} at ${W}: identical to the pre-policy geometry`);
      if (name === "line") {
        const cxs = [...r.svg.querySelectorAll("circle[data-parler-palette-role]")].map((c) => Number(c.getAttribute("cx")));
        assert.ok(Math.abs(cxs[0]) < 1e-9 && Math.abs(cxs.at(-1) - r.layout.innerW) < 1e-9, "points span the inner width");
      }
    }
  }
});
