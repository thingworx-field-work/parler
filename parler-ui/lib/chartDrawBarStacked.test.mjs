import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

import { barStackSegments, barStackTotals, chartYDomain, drawChart } from "../components/chart-draw.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";
import { createChartViewState, describeChartView, setYDomainPolicy, toggleSeriesVisibility } from "./chartViewState.js";
import { chartLegendItems } from "../components/chart-draw.js";

const EPS = 1e-6;

/** SK-1: two series with positive and negative values. */
const SIGNED = Object.freeze({
  kind: "bar",
  x_label: "Device",
  y_label: "Delta",
  stackMode: "stacked",
  series: [
    { name: "Shift A", x: ["D1", "D2", "D3"], y: [4, -2, 3] },
    { name: "Shift B", x: ["D1", "D2", "D3"], y: [2, -3, -1] },
  ],
});

/** SK-2: three series, one category whose total is zero. */
const PERCENT = Object.freeze({
  kind: "bar",
  x_label: "Device",
  y_label: "Count",
  stackMode: "percent",
  series: [
    { name: "OK", x: ["D1", "D2", "D3"], y: [50, 0, 10] },
    { name: "Warn", x: ["D1", "D2", "D3"], y: [30, 0, 10] },
    { name: "Fault", x: ["D1", "D2", "D3"], y: [20, 0, 20] },
  ],
});

function render(chart, view = null, width = 640) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, view, { availableWidth: width, measureText: (t) => String(t).length * 6 });
  const svg = el.querySelector("svg");
  const num = (node, a) => Number(node.getAttribute(a));
  const rects = [...(svg?.querySelectorAll("rect[class^='bar-s']") ?? [])].map((r) => ({
    series: Number(r.getAttribute("class").replace("bar-s", "")),
    x: num(r, "x"), y: num(r, "y"), width: num(r, "width"), height: num(r, "height"), stack: r.getAttribute("data-stack"),
  }));
  const ticks = [...(svg?.querySelectorAll(".tick text") ?? [])].map((t) => t.textContent);
  return { dom, el, svg, drawn, rects, ticks };
}

test("SK-1: positive and negative values accumulate separately from zero, segments abut, the domain is the extreme of the sums", () => {
  const r = render(SIGNED);
  assert.ok(r.svg);
  assert.equal(r.rects.length, 6);
  assert.ok(r.rects.every((x) => x.stack === "stacked"));
  const byCat = (i) => r.rects.filter((x) => Math.abs(x.x - r.drawn.hit.series[0][i].x) < EPS);
  // D1: 4 then 2 upward — series 1 sits exactly on top of series 0.
  const d1 = byCat(0);
  const a1 = d1.find((x) => x.series === 0);
  const b1 = d1.find((x) => x.series === 1);
  assert.ok(Math.abs(a1.y - (b1.y + b1.height)) < EPS, "the second segment ends where the first begins");
  assert.ok(Math.abs(b1.height / a1.height - 0.5) < EPS, "heights are proportional to values (2 / 4)");
  // D2: −2 then −3 downward.
  const d2 = byCat(1);
  const a2 = d2.find((x) => x.series === 0);
  const b2 = d2.find((x) => x.series === 1);
  assert.ok(Math.abs(b2.y - (a2.y + a2.height)) < EPS, "negative segments accumulate downward, abutting");
  // D3: +3 up and −1 down both start at the zero baseline.
  const d3 = byCat(2);
  const a3 = d3.find((x) => x.series === 0);
  const b3 = d3.find((x) => x.series === 1);
  assert.ok(Math.abs(a3.y + a3.height - b3.y) < EPS, "positive and negative parts meet at zero");
  assert.equal(a1.width, b1.width, "stacked segments span the whole category band");
  const totals = barStackTotals(SIGNED.series, 3, SIGNED.series);
  assert.deepEqual(totals.totals, [6, -5, 2]);
  assert.deepEqual(totals.domainValues, [6, 0, 0, -5, 3, -1]);
  assert.deepEqual(chartYDomain(totals.domainValues, [], "bar"), [-5, 6], "the domain is the extreme of the positive and negative sums");
  assert.deepEqual(r.drawn.hit.series[1][0].lines, ["Shift B", "Device: D1", "Delta: 2", "Category total (Delta): 6"]);
  // Horizontal: the same stacking along the value axis; widths equal the vertical heights for the same width budget.
  const h = render({ ...SIGNED, orientation: "horizontal" });
  assert.equal(h.rects.length, 6);
  const hb1 = h.rects.filter((x) => x.series === 1)[0];
  const ha1 = h.rects.filter((x) => x.series === 0)[0];
  assert.ok(Math.abs(hb1.x - (ha1.x + ha1.width)) < EPS, "horizontal segments abut left to right");
  assert.ok(Math.abs(hb1.width / ha1.width - 0.5) < EPS);
  assert.equal(hb1.height, ha1.height);
  assert.equal(chartLegendItems(SIGNED, DEFAULT_CHART_RENDER_THEME).length, 2, "a stacked chart keeps its series legend");
});

test("SK-2: percent shares sum to 100, a zero-total category draws nothing and reads as no share, the domain is [0, 100]", () => {
  const r = render(PERCENT);
  assert.equal(r.rects.length, 6, "D2 draws no segment for any series");
  const segs = barStackSegments(PERCENT.series, 3, new Set(), "percent", barStackTotals(PERCENT.series, 3, PERCENT.series));
  assert.deepEqual(segs.map((s) => s[0].share), [50, 30, 20]);
  assert.equal(segs[2][0].to, 100, "shares stack to exactly 100");
  assert.deepEqual(segs.map((s) => s[1].share), [null, null, null]);
  assert.deepEqual(segs.map((s) => s[2].share), [25, 25, 50]);
  const d1 = r.rects.filter((x) => Math.abs(x.x - r.drawn.hit.series[0][0].x) < EPS);
  const top = Math.min(...d1.map((x) => x.y));
  const bottom = Math.max(...d1.map((x) => x.y + x.height));
  assert.ok(Math.abs(bottom - top - r.drawn.hit.layout.innerH) < 1e-6, "the full stack spans 0..100 = the whole inner height");
  assert.ok(r.ticks.includes("100") && r.ticks.includes("0"), "the value axis is fixed to [0, 100]");
  assert.deepEqual(r.drawn.hit.series[0][1].lines, ["OK", "Device: D2", "Count: 0", "Share: none (category total is 0)", "Category total (Count): 0"]);
  assert.deepEqual(r.drawn.hit.series[2][2].lines, ["Fault", "Device: D3", "Count: 20", "Share: 50.0%", "Category total (Count): 40"]);
  assert.equal(r.drawn.hit.step(r.drawn.hit.first(), "right").index, 1, "the zero-total category is still reachable by keyboard");
});

test("SK-3: hiding a series closes the gap but keeps shares, denominators and the default Y domain; the view note names it", () => {
  const state = toggleSeriesVisibility(createChartViewState(PERCENT), 1);
  const r = render(PERCENT, state);
  assert.equal(r.rects.filter((x) => x.series === 1).length, 0, "the hidden series draws nothing");
  const d1 = r.rects.filter((x) => Math.abs(x.x - r.drawn.hit.series[0][0].x) < EPS);
  const ok = d1.find((x) => x.series === 0);
  const fault = d1.find((x) => x.series === 2);
  assert.ok(Math.abs(fault.y + fault.height - ok.y) < EPS, "Fault now sits directly on OK");
  const segs = barStackSegments(PERCENT.series, 3, new Set([1]), "percent", barStackTotals(PERCENT.series, 3, PERCENT.series));
  assert.equal(segs[2][0].share, 20, "the share is still 20 of the full total, not 20 / 70");
  assert.equal(segs[2][0].to, 70, "the visible stack ends at 70, not 100");
  assert.deepEqual(r.drawn.hiddenSeries, [1]);
  assert.ok(r.ticks.includes("100"), "the domain stays [0, 100]");
  const legend = chartLegendItems(PERCENT, DEFAULT_CHART_RENDER_THEME);
  assert.match(describeChartView(state, PERCENT, legend), /Warn/);
  // Signed stacking with the default policy keeps the full domain when a series is hidden; the visible policy refits.
  const hiddenB = toggleSeriesVisibility(createChartViewState(SIGNED), 1);
  const full = render(SIGNED, hiddenB);
  assert.ok(full.ticks.includes("6") || full.ticks.includes("5"), "the default domain still covers the full stack");
  const fit = render(SIGNED, setYDomainPolicy(hiddenB, "visible"));
  const fitTotals = barStackTotals(SIGNED.series, 3, [SIGNED.series[0]]);
  assert.deepEqual(chartYDomain(fitTotals.domainValues, [], "bar"), [-2, 4]);
  assert.ok(!fit.ticks.includes("6"), "Fit Y to visible series refits to the visible sums");
});
