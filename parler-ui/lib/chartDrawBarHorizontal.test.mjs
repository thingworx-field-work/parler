import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";
import { scaleBand, scaleLinear } from "d3-scale";

import {
  barRectGeometry,
  barRectGeometryHorizontal,
  categoryLabelLineHeight,
  drawChart,
  createTextMeasurer,
  estimateTextWidth,
  horizontalBarCategoryGutter,
  horizontalBarHeightBudget,
  wrapCategoryLabel,
} from "../components/chart-draw.js";
import { DEFAULT_PORTABLE_PRINT_THEME, rewriteClonedChartPaletteForPrint } from "./assistantResponseActions.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";

const EPS = 1e-6;
const LONG_NAMES = Array.from({ length: 24 }, (_v, i) => `SE.CellFab.Model.Workunit.AC-BenchScale-${String(i + 1).padStart(2, "0")}-Line`);

function themeWithTick(tickSize) {
  const theme = structuredClone(DEFAULT_CHART_RENDER_THEME);
  theme.style.type.tickSize = tickSize;
  return theme;
}

/** Draw a chart into a JSDOM host and read back the horizontal-bar geometry. */
function render(chart, theme = DEFAULT_CHART_RENDER_THEME, view = null, width = 640, options = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, theme, view, { viewBoxWidth: width, ...options });
  assert.equal(el.querySelectorAll("svg").length, 1, "the measuring probe never stays in the host");
  const svg = el.querySelector("svg");
  assert.ok(svg, "chart must render an svg");
  const num = (node, attr) => Number(node.getAttribute(attr));
  const rects = [...svg.querySelectorAll("rect[class^='bar-s']")].map((r) => ({
    series: Number(r.getAttribute("class").replace("bar-s", "")),
    x: num(r, "x"),
    y: num(r, "y"),
    width: num(r, "width"),
    height: num(r, "height"),
  }));
  const viewBox = svg.getAttribute("viewBox").split(" ").map(Number);
  const labels = [...svg.querySelectorAll("g.category-axis .tick text")].map((t) => ({
    lines: [...t.querySelectorAll("tspan")].map((ts) => ({ text: ts.textContent, dy: Number(ts.getAttribute("dy")) })),
    declared: Number(t.getAttribute("data-lines")),
    tickY: Number(/translate\(0,([-\d.]+)\)/.exec(t.parentElement.getAttribute("transform"))[1]),
  }));
  const valueAxis = [...svg.querySelectorAll("svg > g > g")].find((g) => g.getAttribute("transform")?.startsWith("translate(0,") && g.querySelector(".domain"));
  return { dom, el, svg, drawn, rects, viewBox, labels, valueAxis, baseline: svg.querySelector("line.zero-baseline"),
    refLines: [...svg.querySelectorAll("g.y-ref-lines line")], refLabels: [...svg.querySelectorAll("g.y-ref-lines text")] };
}

const single = (names, values, extra = {}) => ({ kind: "bar", orientation: "horizontal", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: names, y: values }], ...extra });

test("the height budget follows the frozen §7.3 policy and the actual band step never falls below the requested step", () => {
  const cases = [
    [24, 1, 2, 12, 36, 936], [24, 1, 2, 18, 52, 1323], [24, 1, 1, 12, 20, 548],
    [24, 6, 1, 12, 50, 1274], [24, 6, 2, 18, 52, 1323], [3, 1, 1, 12, 20, 240],
  ];
  for (const [n, S, L, fs, step, h] of cases) {
    const b = horizontalBarHeightBudget({ categories: n, seriesCount: S, labelLines: L, tickSize: fs });
    assert.equal(b.step, step, `${n}x${S} L${L} fs${fs} requested step`);
    assert.equal(b.h, h, `${n}x${S} L${L} fs${fs} height`);
    const y0 = scaleBand().domain([...Array(n).keys()].map(String)).range([0, b.innerH]).padding(0.2);
    const y1 = scaleBand().domain([...Array(S).keys()].map(String)).range([0, y0.bandwidth()]).padding(0.08);
    assert.ok(y0.step() >= step - EPS, "actual step is at least the requested minimum");
    assert.ok(y1.bandwidth() >= 4, `sub-bar ${y1.bandwidth()} is at least 4 logical px`);
    assert.ok(L * categoryLabelLineHeight(fs) <= y0.step() - 4 + EPS, "the label block fits inside the step with breathing space");
  }
  assert.equal(horizontalBarHeightBudget({ categories: 24, seriesCount: 6, labelLines: 1, tickSize: 12 }).barStep, 50);
  assert.equal(horizontalBarHeightBudget({ categories: 24, seriesCount: 1, labelLines: 1, tickSize: 12 }).barStep, 9);
});

const mono = (px) => (text) => [...text].length * px;

test("category labels wrap by measured width at separators into at most two lines and ellipsize beyond", () => {
  const m = mono(10);
  assert.deepEqual(wrapCategoryLabel("gate-1", 200, m), ["gate-1"]);
  assert.deepEqual(wrapCategoryLabel("Bench Scale 01 Line", 120, m), ["Bench Scale", "01 Line"], "breaks at the last separator that fits");
  assert.deepEqual(wrapCategoryLabel("SE.CellFab.Model.Workunit", 110, m), ["SE.CellFab.", "Model.Work…"], "tokens fill the line greedily; an early separator does not end it");
  assert.deepEqual(wrapCategoryLabel("SE.CellFab.Model.Workunit", 100, m), ["SE.", "CellFab.M…"], "a token that would overflow by its own width starts the next line");
  assert.deepEqual(wrapCategoryLabel("AC-BenchScale-01 Line", 150, m), ["AC-BenchScale-", "01 Line"], "the separator stays with its token and the remainder fits on the second line");
  const tight = wrapCategoryLabel("AC-BenchScale-01 Line", 120, m);
  assert.ok(tight.every((l) => m(l) <= 120), tight.join("|"));
  assert.ok(tight[1].endsWith("…"), "at a narrower width the remainder is ellipsized");
  assert.deepEqual(wrapCategoryLabel("abcdefghijklmnopqrstuvwxyz", 80, m), ["abcdefgh", "ijklmno…"], "an unbreakable run is cut by measurement");
  assert.deepEqual(wrapCategoryLabel("  ", 80, m), [""]);
  const wide = mono(30);
  const cjk = wrapCategoryLabel("上海工厂一号线设备甲", 100, wide);
  assert.ok(cjk.every((l) => wide(l) <= 100), "CJK lines fit the width under a wide measurer");
  assert.ok(cjk.at(-1).endsWith("…"));
  assert.ok(estimateTextWidth("上海", 12) > estimateTextWidth("ab", 12) * 1.5, "the estimator counts CJK glyphs as full ems");
  assert.ok(estimateTextWidth("WWWW", 12) > estimateTextWidth("iiii", 12) * 2, "wide Latin glyphs cost more than narrow ones");
  const gutter = horizontalBarCategoryGutter(LONG_NAMES, 640);
  assert.ok(gutter.gutter >= 48 && gutter.gutter <= Math.min(160, 0.35 * 640), `gutter ${gutter.gutter} inside the clamp`);
  assert.equal(gutter.maxLines, 2);
  assert.ok(gutter.lines.every((ls) => ls.length <= 2));
  assert.ok(gutter.maxLineWidth + gutter.axisSpace <= gutter.gutter + 1, "every fitted line plus the axis space is inside the gutter");
  assert.equal(horizontalBarCategoryGutter(["a", "b"], 640).gutter, 48, "short labels use the lower clamp");
  const narrow = horizontalBarCategoryGutter(LONG_NAMES, 240);
  assert.ok(narrow.gutter <= Math.min(160, 0.35 * 240) && narrow.gutter >= 48, `narrow hosts stay inside the clamp (${narrow.gutter})`);
  assert.ok(narrow.gutter < horizontalBarCategoryGutter(LONG_NAMES, 640).gutter, "a narrower host wraps to a narrower gutter");
});

test("HB-1: 24 long names wrap, keep full names for query, and never overlap at 12px or 18px", () => {
  for (const fs of [12, 18]) {
    const theme = themeWithTick(fs);
    const r = render(single(LONG_NAMES, LONG_NAMES.map((_n, i) => i + 1)), theme);
    assert.equal(r.rects.length, 24);
    const budget = horizontalBarHeightBudget({ categories: 24, seriesCount: 1, labelLines: 2, tickSize: fs });
    assert.equal(r.viewBox[3], budget.h, `svg height equals the policy value at ${fs}px`);
    assert.equal(r.drawn.orientation, "horizontal");
    const gutter = horizontalBarCategoryGutter(LONG_NAMES, 640, theme);
    assert.equal(r.drawn.hit.layout.marginLeft, gutter.gutter, "the left margin is the category gutter");
    assert.equal(r.labels.length, 24);
    assert.ok(r.labels.some((l) => l.lines.length === 2), "at least one label wraps to two lines");
    assert.ok(r.labels.some((l) => l.lines.at(-1).text.endsWith("…")), "at least one label is ellipsized");
    assert.ok(r.labels.every((l) => l.lines.length === l.declared && l.lines.length <= 2));
    const lh = categoryLabelLineHeight(fs);
    const step = r.drawn.hit.layout.barStep;
    assert.ok(step >= budget.step - EPS, "actual step meets the requested minimum");
    // No adjacent overlap: block extents from the actual tick y and dy attributes.
    const blocks = r.labels.map((l) => {
      const top = l.tickY + l.lines[0].dy - lh / 2;
      return { top, bottom: top + l.lines.length * lh };
    });
    for (let i = 0; i < blocks.length; i++) {
      assert.ok(blocks[i].bottom - blocks[i].top <= step - 4 + EPS, "block height leaves 4px of breathing space");
      if (i > 0) {
        assert.ok(Math.abs(r.labels[i].tickY - r.labels[i - 1].tickY - step) < EPS, "centres are one step apart");
        assert.ok(blocks[i].top >= blocks[i - 1].bottom - EPS, `labels ${i - 1} and ${i} do not overlap`);
      }
    }
    assert.ok(r.rects[0].y < r.rects[23].y, "the first category is at the top");
    const first = r.drawn.hit.first();
    assert.equal(first.lines[1], `Device: ${LONG_NAMES[0]}`, "the query keeps the full name");
    assert.equal(r.drawn.hit.step(first, "end").index, 23);
  }
});

test("HB-1: wide-glyph and multilingual names are fitted to the gutter by the resolved font's widths at 12px and 18px", () => {
  const names = [
    "WWWW-MMMMMMMMMMMM-DEVICE-01", "上海工厂一号线设备甲乙丙丁戊己庚辛", "Ｆｕｌｌｗｉｄｔｈ-Ｄｅｖｉｃｅ-０１",
    "MMMMMMMMMMMMMMMMMMMMMMMMMMMM", "東京第二工場ラインＢ設備", "gate-1",
  ];
  for (const fs of [12, 18]) {
    const theme = themeWithTick(fs);
    // A deliberately wide font: every glyph 1.1em, so character counts would badly under-allocate.
    const measure = (text) => [...text].length * fs * 1.1;
    const r = render(single(names, names.map((_n, i) => i + 1)), theme, null, 640, { measureText: measure });
    const axisSpace = theme.style.axis.tickLength + theme.style.axis.tickPadding + 4;
    const gutter = r.drawn.hit.layout.marginLeft;
    assert.ok(gutter <= Math.min(160, 0.35 * 640) && gutter >= 48, `gutter ${gutter} inside the clamp`);
    for (const label of r.labels) {
      for (const line of label.lines) {
        assert.ok(measure(line.text) <= gutter - axisSpace + 1e-6, `"${line.text}" (${measure(line.text)}px) fits inside the ${gutter}px gutter at ${fs}px`);
      }
      assert.ok(label.lines.length <= 2);
    }
    const ellipsized = r.labels.filter((l) => l.lines.at(-1).text.endsWith("…")).length;
    assert.ok(ellipsized >= (fs === 18 ? 4 : 2), `the wide and CJK names are ellipsized rather than cropped (${ellipsized} at ${fs}px)`);
    assert.deepEqual(r.labels[5].lines.map((l) => l.text), ["gate-1"], "a short name is untouched");
    const items = r.drawn.hit.series[0];
    names.forEach((name, i) => assert.equal(items[i].lines[1], `Device: ${name}`, "the query keeps every full name"));
    const est = render(single(names, names.map((_n, i) => i + 1)), theme);
    for (const label of est.labels) {
      for (const line of label.lines) {
        assert.ok(estimateTextWidth(line.text, fs) <= est.drawn.hit.layout.marginLeft - axisSpace + 1e-6, "the estimator path fits too");
      }
    }
  }
});

test("HB-2: horizontal geometry mirrors the signed vertical rule and the zero baseline is a vertical line only below zero", () => {
  const x = scaleLinear().domain([-10, 10]).range([0, 200]);
  const y = scaleLinear().domain([-10, 10]).range([200, 0]);
  for (const v of [-5, 10, -10, 0, 7.5]) {
    const hg = barRectGeometryHorizontal(x, v);
    const vg = barRectGeometry(y, v);
    assert.ok(Math.abs(hg.width - vg.height) < EPS, `extent for ${v} is the same in both orientations`);
    assert.ok(hg.width >= 0 && hg.x >= 0);
  }
  assert.deepEqual(barRectGeometryHorizontal(x, 0), { x: 100, width: 0 }, "a zero value has zero width at the baseline");
  const mixed = render(single(["a", "b"], [-5, 10]));
  assert.ok(mixed.baseline, "the domain crosses zero, so the baseline exists");
  assert.equal(mixed.baseline.getAttribute("x1"), mixed.baseline.getAttribute("x2"), "the baseline is vertical");
  assert.equal(Number(mixed.baseline.getAttribute("y1")), 0);
  assert.equal(mixed.baseline.getAttribute("data-parler-palette-role"), "axis");
  const x0 = Number(mixed.baseline.getAttribute("x1"));
  assert.ok(Math.abs(mixed.rects[0].x + mixed.rects[0].width - x0) < EPS, "the negative bar ends at the baseline");
  assert.ok(Math.abs(mixed.rects[1].x - x0) < EPS, "the positive bar starts at the baseline");
  assert.ok(Math.abs(mixed.rects[1].width / mixed.rects[0].width - 2) < 1e-9, "widths are proportional to values");
  const negative = render(single(["a", "b"], [-5, -10]));
  assert.ok(negative.baseline);
  assert.ok(Math.abs(Number(negative.baseline.getAttribute("x1")) - negative.drawn.hit.layout.innerW) < EPS, "all-negative bars hang from a baseline at the right edge");
  const zeros = render(single(["a", "b"], [0, 0]));
  assert.ok(zeros.baseline, "all-zero data uses the non-degenerate signed domain, so the baseline is drawn");
  assert.ok(Math.abs(Number(zeros.baseline.getAttribute("x1")) - zeros.drawn.hit.layout.innerW / 2) < EPS, "and it is centred");
  assert.ok(zeros.rects.every((r) => r.width === 0 && r.height > 0), "zero bars have zero width but keep their band");
  assert.equal(zeros.drawn.hit.nearest(0, zeros.rects[0].y + zeros.rects[0].height / 2)?.index, 0, "zero bars are still queryable by band");
  const positive = render(single(["a", "b"], [3, 4]));
  assert.equal(positive.baseline, null);
});

test("HB-3: 6 series × 24 categories has no cap, keeps hidden slots, and every sub-bar is at least 4px thick", () => {
  const names = Array.from({ length: 24 }, (_v, i) => `g${i + 1}`);
  const series = Array.from({ length: 6 }, (_v, s) => ({ name: `s${s + 1}`, x: names, y: names.map((_n, i) => (i + 1) * (s + 1)) }));
  const chart = { kind: "bar", orientation: "horizontal", series };
  const full = render(chart);
  assert.equal(full.viewBox[3], 1274, "24 × 6 single-line labels: 1274 logical px, uncapped");
  assert.ok(Math.abs(full.drawn.hit.layout.barStep - 50) < EPS);
  assert.equal(full.rects.length, 144);
  for (const r of full.rects) assert.ok(r.height >= 4, `sub-bar ${r.height} at least 4px`);
  assert.ok(full.rects.every((r) => Math.abs(r.height - full.drawn.hit.layout.subBarThickness) < EPS));
  const hidden = render(chart, DEFAULT_CHART_RENDER_THEME, { hiddenSeriesKeys: [1, 4], yDomainPolicy: "full", focusedSlice: null, notesOpen: false, viewXDomain: null, selectedRange: null, snapshot: "x" });
  assert.equal(hidden.viewBox[3], 1274, "hiding series keeps the height: slots are retained");
  assert.equal(hidden.rects.length, 96);
  const at = (rects, s, i) => rects.find((r) => r.series === s && Math.abs(r.y - full.rects.find((f) => f.series === s)?.y - (full.drawn.hit.layout.barStep * i)) < 1e-6);
  const fullS5 = full.rects.filter((r) => r.series === 5);
  const hiddenS5 = hidden.rects.filter((r) => r.series === 5);
  assert.deepEqual(hiddenS5.map((r) => [r.y, r.height]), fullS5.map((r) => [r.y, r.height]), "remaining bars keep their position and thickness");
  assert.ok(at(full.rects, 0, 0), "helper sanity");
  const fitted = render(chart, DEFAULT_CHART_RENDER_THEME, { hiddenSeriesKeys: [1, 2, 3, 4, 5], yDomainPolicy: "visible", focusedSlice: null, notesOpen: false, viewXDomain: null, selectedRange: null, snapshot: "x" });
  const maxTick = (r) => Math.max(...[...r.valueAxis.querySelectorAll(".tick text")].map((t) => Number(t.textContent.replace(/,/g, ""))));
  assert.ok(maxTick(fitted) < maxTick(full), "Fit Y only shrinks the value axis");
  assert.equal(fitted.viewBox[3], 1274, "and does not change the height");
});

test("HB-5: value-axis reference lines are vertical with labels, extend the domain, and print without unknown roles", () => {
  const r = render(single(["a", "b", "c"], [3, 6, 9], { y_reference_lines: [{ y: -2, label: "Lower spec", role: "lsl" }, { y: 8, label: "Target", role: "target" }] }));
  assert.equal(r.refLines.length, 2);
  for (const l of r.refLines) {
    assert.equal(l.getAttribute("x1"), l.getAttribute("x2"), "reference lines are vertical");
    assert.equal(Number(l.getAttribute("y1")), 0);
    assert.ok(Math.abs(Number(l.getAttribute("y2")) - r.drawn.hit.layout.innerH) < EPS);
  }
  assert.deepEqual(r.refLabels.map((t) => t.textContent), ["Lower spec", "Target"]);
  assert.ok(r.baseline, "the negative reference line extends the domain below zero");
  assert.ok(Number(r.refLines[0].getAttribute("x1")) < Number(r.baseline.getAttribute("x1")));
  const first = r.drawn.hit.first();
  const up = r.drawn.hit.step(r.drawn.hit.step(first, "up"), "up");
  assert.equal(up.type, "reference");
  assert.equal(up.index, 1);
  assert.ok(Math.abs(up.cy - first.cy) < EPS, "a reference line is queried at the current bar's row");
  assert.equal(up.cx, Number(r.refLines[1].getAttribute("x1")));
  assert.equal(r.drawn.hit.find("reference", -1, 1, 0, first.cy).cy, first.cy);
  const clone = r.svg.cloneNode(true);
  const unknown = rewriteClonedChartPaletteForPrint(clone.ownerDocument.createElement("div"), DEFAULT_PORTABLE_PRINT_THEME);
  void unknown;
  const holder = r.dom.window.document.createElement("div");
  holder.append(clone);
  rewriteClonedChartPaletteForPrint(holder, DEFAULT_PORTABLE_PRINT_THEME);
  const roles = new Set([...holder.querySelectorAll("[data-parler-palette-role]")].map((n) => n.getAttribute("data-parler-palette-role")));
  assert.ok([...roles].every((role) => ["axis", "axis-label", "tick", "grid", "series point-outline", "reference-danger", "reference-target", "reference-control", "reference-warning"].includes(role)), `roles ${[...roles].join(",")}`);
});

test("HB-6: switching orientation changes geometry only, never the values, ticks, tooltips, legend or notes", () => {
  const names = ["gate-1", "gate-2", "gate-3", "gate-4"];
  const values = [-5, 10, 10, 2.5];
  const vertical = render({ ...single(names, values), orientation: "vertical" });
  const horizontal = render(single(names, values));
  const ticks = (r, axis) => [...r.svg.querySelectorAll("svg > g > g")].filter((g) => g.querySelector(".domain")).map((g) => [...g.querySelectorAll(".tick text")].map((t) => t.textContent).join("|"));
  const vTicks = ticks(vertical).find((t) => t.includes("-5") || t.includes("10"));
  const hTicks = ticks(horizontal).find((t) => t.includes("-5") || t.includes("10"));
  assert.equal(vTicks, hTicks, "the value axis has the same tick text");
  assert.deepEqual(horizontal.drawn.hit.series[0].map((it) => it.lines), vertical.drawn.hit.series[0].map((it) => it.lines), "tooltip lines are identical");
  assert.equal(vertical.drawn.orientation, undefined);
  assert.equal(horizontal.drawn.orientation, "horizontal");
  assert.equal(horizontal.viewBox[3], 240, "four categories keep the default height");
  const widths = horizontal.rects.map((r) => r.width);
  const heights = vertical.rects.map((r) => r.height);
  assert.ok(Math.abs(widths[1] / widths[3] - heights[1] / heights[3]) < 1e-9, "relative bar lengths are the same in both orientations");
  assert.ok(Math.abs(widths[1] - widths[2]) < EPS, "tied values have equal length and keep source order");
  assert.ok(horizontal.rects[1].y < horizontal.rects[2].y);
  const nearest = horizontal.drawn.hit.nearest(5, horizontal.rects[2].y + 1);
  assert.equal(nearest.index, 2, "pointer query uses the band on the vertical axis");
  assert.equal(horizontal.drawn.hit.step(nearest, "right").index, 3, "Right moves to the next category (data order)");
  assert.equal(horizontal.drawn.hit.step(nearest, "left").index, 1);
});

test("a detached drawing root measures through the document's body, and the probe never remains", () => {
  const dom = new JSDOM(`<!DOCTYPE html><html><body></body></html>`, { pretendToBeVisual: true });
  const doc = dom.window.document;
  const proto = dom.window.SVGElement.prototype;
  const original = proto.getComputedTextLength;
  // Stand in for a rendering browser: every glyph 9px in this "font".
  proto.getComputedTextLength = function () { return [...(this.textContent ?? "")].length * 9; };
  try {
    const detached = doc.createElement("div");
    assert.equal(detached.isConnected, false);
    const measure = createTextMeasurer(detached, DEFAULT_CHART_RENDER_THEME, doc);
    assert.equal(doc.body.querySelectorAll("svg").length, 1, "the probe is mounted in the document body while measuring");
    assert.equal(measure("WWWW"), 36, "the detached root is measured by the document's font, not estimated");
    assert.notEqual(estimateTextWidth("WWWW", 12), 36);
    measure.dispose();
    assert.equal(doc.body.querySelectorAll("svg").length, 0, "dispose removes the probe");
    const noDoc = createTextMeasurer(null, DEFAULT_CHART_RENDER_THEME);
    assert.equal(noDoc("WWWW"), estimateTextWidth("WWWW", 12), "with no document at all the estimate is used");
    assert.ok(estimateTextWidth("MW", 12) >= 24, "the estimate gives M and W a full em");
  } finally {
    proto.getComputedTextLength = original;
  }
});
