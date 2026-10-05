import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

import { appendHeatLegend, drawChart, heatColumnLabelIndexes } from "../components/chart-draw.js";
import { DEFAULT_CHART_RENDER_THEME } from "../components/chart-theme.js";
import { DEFAULT_PORTABLE_PRINT_THEME, rewriteClonedChartPaletteForPrint } from "./assistantResponseActions.js";
import { heatPaint, heatPosition, heatScale, HEAT_MID_OPACITY, HEAT_MIN_OPACITY } from "./chartHeatColor.js";
import { chartPlotSize, HEAT_CELL_MAX_W, HEAT_CELL_MIN_W } from "./chartSizePolicy.js";

/** HM-1: a 2 × 3 matrix with one missing combination. */
export const HEAT = Object.freeze({
  kind: "heatmap",
  title: "Average temperature",
  x_label: "Hour",
  y_label: "Device",
  heatmap: { rows: ["Oven-01", "Oven-02"], cols: ["00", "01", "02"], values: [[63.1, 63.4, null], [61.0, 60.8, 61.2]], valueLabel: "Avg temperature (°C)", missingCount: 1 },
});

const measure = (text) => String(text).length * 6;

function render(chart, width = 640, options = {}) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
  const el = dom.window.document.getElementById("host");
  const drawn = drawChart(el, chart, DEFAULT_CHART_RENDER_THEME, null, { availableWidth: width, measureText: measure, ...options });
  const svg = el.querySelector("svg");
  const num = (node, a) => Number(node.getAttribute(a));
  return { dom, el, svg, drawn, num, viewBox: svg ? svg.getAttribute("viewBox").split(" ").map(Number) : null };
}

function bigMatrix(rows, cols) {
  return {
    kind: "heatmap",
    x_label: "Hour",
    y_label: "Device",
    heatmap: {
      rows: Array.from({ length: rows }, (_v, i) => `Device-${i + 1}`),
      cols: Array.from({ length: cols }, (_v, i) => String(i).padStart(2, "0")),
      values: Array.from({ length: rows }, (_v, r) => Array.from({ length: cols }, (_w, c) => r + c)),
      valueLabel: "Count",
      missingCount: 0,
    },
  };
}

test("HM-1: a missing combination is a hatched null cell read as No data, never zero", () => {
  const r = render(HEAT);
  assert.ok(r.svg);
  const cells = [...r.svg.querySelectorAll("rect.heat-cell")];
  assert.equal(cells.length, 6);
  const missing = cells.filter((c) => c.getAttribute("data-missing") === "true");
  assert.equal(missing.length, 1);
  assert.equal(missing[0].getAttribute("data-row"), "0");
  assert.equal(missing[0].getAttribute("data-col"), "2");
  assert.match(missing[0].getAttribute("fill"), /^url\(#parler-heat-missing-/);
  assert.equal(missing[0].getAttribute("data-parler-palette-role"), null, "a missing cell carries no heat role");
  assert.ok(r.svg.querySelector("pattern line[data-parler-palette-role=\"grid\"]"), "the hatch uses the grid colour");
  const item = r.drawn.hit.series[0][2];
  assert.deepEqual(item.lines, ["Device: Oven-01", "Hour: 02", "Avg temperature (°C): No data"]);
  assert.deepEqual(r.drawn.hit.series[1][0].lines, ["Device: Oven-02", "Hour: 00", "Avg temperature (°C): 61"]);
  assert.equal(r.drawn.heat.missingCount, 1);
  assert.equal(r.drawn.kind, "heatmap");
  assert.equal(r.drawn.hiddenSeries.length, 0);
  assert.equal(r.drawn.xDomain, undefined);
});

test("HM-2: sequential, diverging and constant scales; colour-bar ticks equal the data extremes", () => {
  const seq = heatScale([[1, 2], [3, null]]);
  assert.deepEqual(seq, { kind: "sequential", min: 1, max: 3, ticks: [1, 3] });
  assert.equal(heatPosition(1, seq), 0);
  assert.equal(heatPosition(3, seq), 1);
  assert.equal(heatPaint(0, "sequential", DEFAULT_CHART_RENDER_THEME.palette).opacity, HEAT_MIN_OPACITY);
  assert.equal(heatPaint(1, "sequential", DEFAULT_CHART_RENDER_THEME.palette).fill, DEFAULT_CHART_RENDER_THEME.palette.series[0]);
  const neg = heatScale([[-5, -1]]);
  assert.equal(neg.kind, "sequential", "all non-positive is still one-directional");
  const div = heatScale([[-2, 0], [4, null]]);
  assert.deepEqual(div, { kind: "diverging", min: -2, max: 4, ticks: [-2, 0, 4] });
  assert.equal(heatPosition(-2, div), -0.5, "symmetric about zero: −2 is half the strength of +4");
  assert.equal(heatPosition(4, div), 1);
  assert.equal(heatPaint(-0.5, "diverging", DEFAULT_CHART_RENDER_THEME.palette).slot, 1);
  assert.equal(heatPaint(0, "diverging", DEFAULT_CHART_RENDER_THEME.palette).opacity, HEAT_MIN_OPACITY);
  const constant = heatScale([[7, 7], [null, 7]]);
  assert.deepEqual(constant, { kind: "constant", min: 7, max: 7, ticks: [7] });
  assert.equal(heatPaint(0, "constant", DEFAULT_CHART_RENDER_THEME.palette).opacity, HEAT_MID_OPACITY);
  // The drawn legend for a diverging matrix shows min, 0 and max under the bar.
  const r = render({ ...HEAT, heatmap: { ...HEAT.heatmap, values: [[-2, 0, null], [4, 1, 3]] } });
  const legendHost = r.dom.window.document.createElement("div");
  appendHeatLegend(legendHost, r.drawn.heat, DEFAULT_CHART_RENDER_THEME, r.dom.window.document);
  assert.deepEqual([...legendHost.querySelectorAll("text.chart-heat-tick")].map((n) => n.textContent), ["-2", "0", "4"]);
  assert.equal(legendHost.querySelectorAll("linearGradient stop[data-parler-palette-role=\"heat\"]").length, 3);
  assert.ok([...legendHost.querySelectorAll("text")].some((n) => n.textContent === "No data"));
  assert.ok([...legendHost.querySelectorAll("text")].some((n) => n.textContent === "Avg temperature (°C)"));
  const cellsDiv = [...r.svg.querySelectorAll("rect.heat-cell[data-heat-scale]")];
  assert.ok(cellsDiv.every((c) => c.getAttribute("data-heat-scale") === "diverging"));
  const seqLegend = r.dom.window.document.createElement("div");
  appendHeatLegend(seqLegend, render(HEAT).drawn.heat, DEFAULT_CHART_RENDER_THEME, r.dom.window.document);
  assert.deepEqual([...seqLegend.querySelectorAll("text.chart-heat-tick")].map((n) => n.textContent), ["60.8", "63.4"]);
});

test("HM-4: 24 × 48 keeps 20 px cells and scrolls in a 480 px card and expand layer; print fits 760 px; 2 × 3 clamps to 64 and centres", () => {
  const big = bigMatrix(24, 48);
  const card = render(big, 480, { context: "card" });
  assert.equal(card.drawn.plotSize.cell.w, HEAT_CELL_MIN_W);
  assert.ok(card.drawn.plotSize.w > 480, "wider than the card: the plot area scrolls horizontally");
  assert.equal(card.svg.querySelectorAll("rect.heat-cell").length, 24 * 48);
  const lastCell = card.svg.querySelector('rect.heat-cell[data-row="23"][data-col="47"]');
  assert.equal(card.num(lastCell, "x") + card.num(lastCell, "width"), 48 * 20, "the last column is reachable at the end of the scroll run");
  assert.equal(card.svg.querySelectorAll("text.heat-cell-value").length, 0, "20 px cells carry no value text");
  const tickSize = DEFAULT_CHART_RENDER_THEME.style.type.tickSize;
  assert.ok([...card.svg.querySelectorAll("g.heat-col-labels text")].every((t) => Number(t.getAttribute("font-size")) === tickSize), "text is never scaled");
  const expand = render(big, 480, { context: "expand" });
  assert.equal(expand.drawn.plotSize.cell.w, HEAT_CELL_MIN_W);
  assert.equal(expand.drawn.plotSize.w, card.drawn.plotSize.w);
  const print = render(big, 760, { context: "print" });
  assert.ok(print.drawn.plotSize.w <= 760, "print never scrolls: every column fits the page width");
  assert.ok(print.drawn.plotSize.cell.w < HEAT_CELL_MIN_W && print.drawn.plotSize.cell.w >= 4);
  assert.equal(print.svg.querySelectorAll("rect.heat-cell").length, 24 * 48);
  const lastPrint = print.svg.querySelector('rect.heat-cell[data-row="23"][data-col="47"]');
  assert.ok(print.drawn.hit.layout.marginLeft + print.num(lastPrint, "x") + print.num(lastPrint, "width") <= 760);
  const drawnCols = [...print.svg.querySelectorAll("g.heat-col-labels text")].map((t) => Number(t.getAttribute("data-col")));
  assert.ok(drawnCols.length < 48, "column labels are thinned");
  assert.equal(drawnCols[0], 0);
  assert.equal(drawnCols.at(-1), 47, "the last column label is kept");
  assert.ok([...print.svg.querySelectorAll("g.heat-col-labels text")].every((t) => Number(t.getAttribute("font-size")) === tickSize));
  assert.equal(print.svg.querySelectorAll("g.heat-row-labels text").length, 24, "row labels are all kept");
  assert.equal(print.svg.querySelectorAll("text.heat-cell-value").length, 0);
  assert.ok(print.svg.querySelector("pattern"), "the missing-cell encoding is present");
  const small = render(HEAT, 1200);
  assert.equal(small.drawn.plotSize.cell.w, HEAT_CELL_MAX_W);
  assert.ok(small.drawn.plotSize.w < 1200, "the host is narrower than the card and gets centred by the card");
  assert.equal(small.svg.querySelectorAll("text.heat-cell-value").length, 5, "64 × 40 cells carry their value; the missing one does not");
  assert.deepEqual(heatColumnLabelIndexes(10, 4), [0, 4, 9], "8 is dropped because it would collide with the kept last label");
  assert.deepEqual(heatColumnLabelIndexes(9, 4), [0, 4, 8]);
  const policy = chartPlotSize({ kind: "heatmap", rows: 2, cols: 3, marginLeft: 60, marginRight: 20, marginTop: 20, marginBottom: 44, availableWidth: 1200, context: "card" });
  assert.deepEqual(policy.cell, { w: 64, h: 40 });
  assert.equal(policy.w, 60 + 3 * 64 + 20);
  assert.equal(policy.h, 20 + 2 * 40 + 44);
});

test("hit model: pointer picks the containing cell, arrows move along the row and between rows", () => {
  const r = render(HEAT);
  const first = r.drawn.hit.first();
  assert.deepEqual([first.series, first.index], [0, 0]);
  assert.deepEqual([r.drawn.hit.step(first, "right").series, r.drawn.hit.step(first, "right").index], [0, 1]);
  const down = r.drawn.hit.step(first, "up");
  assert.equal(down.series, 1, "up/down changes the row");
  assert.equal(r.drawn.hit.step(down, "down"), first);
  const cell = r.drawn.hit.series[1][2];
  assert.equal(r.drawn.hit.nearest(cell.cx, cell.cy), cell);
  assert.equal(r.drawn.hit.nearest(cell.x + 1, cell.y + 1), cell);
  assert.equal(r.drawn.hit.find("cell", 1, 2), cell);
});

test("HM-5: an invalid payload is refused by the renderer, and a print clone recomputes every cell and stop from the print palette", () => {
  const original = console.warn;
  const warnings = [];
  console.warn = (m) => warnings.push(String(m));
  try {
    const bad = { ...HEAT, heatmap: { ...HEAT.heatmap, missingCount: 0 } };
    const r = render(bad);
    assert.equal(r.svg, null);
    assert.ok(warnings.some((w) => w.includes("CHART_HEATMAP_INVALID")));
  } finally {
    console.warn = original;
  }
  const r = render({ ...HEAT, heatmap: { ...HEAT.heatmap, values: [[-2, 0, null], [4, 1, 3]] } }, 640, { context: "print" });
  const holder = r.dom.window.document.createElement("div");
  holder.append(r.svg.cloneNode(true));
  const legendHost = r.dom.window.document.createElement("div");
  appendHeatLegend(legendHost, r.drawn.heat, DEFAULT_CHART_RENDER_THEME, r.dom.window.document);
  holder.append(legendHost);
  const rewritten = rewriteClonedChartPaletteForPrint(holder, DEFAULT_PORTABLE_PRINT_THEME);
  assert.ok(rewritten > 0);
  const printSeries = DEFAULT_PORTABLE_PRINT_THEME.palette.series;
  const positive = holder.querySelector('rect.heat-cell[data-row="1"][data-col="0"]');
  assert.equal(positive.getAttribute("fill"), printSeries[0]);
  assert.equal(positive.getAttribute("fill-opacity"), "1");
  const negative = holder.querySelector('rect.heat-cell[data-row="0"][data-col="0"]');
  assert.equal(negative.getAttribute("fill"), printSeries[1]);
  assert.equal(negative.getAttribute("fill-opacity"), String(heatPaint(-0.5, "diverging", DEFAULT_PORTABLE_PRINT_THEME.palette).opacity));
  const stops = [...holder.querySelectorAll("linearGradient stop")];
  assert.deepEqual(stops.map((s) => s.getAttribute("stop-color")), [printSeries[1], printSeries[0], printSeries[0]]);
  assert.ok(holder.querySelector("rect.chart-heat-missing-sample"), "the No data sample survives the clone");
});

test("HM-8: a printed heatmap never makes a row shorter than its label line, at the default and at an 18 px tick font", () => {
  const rows = Array.from({ length: 24 }, (_v, r) => `Device-Very-Long-Name-${r}`);
  const cols = Array.from({ length: 48 }, (_v, c) => String(c));
  const big = { kind: "heatmap", heatmap: { rows, cols, values: rows.map((_r, r) => cols.map((_c, c) => r + c)), missingCount: 0, valueLabel: "v" } };
  for (const tickSize of [12, 18]) {
    const theme = { ...DEFAULT_CHART_RENDER_THEME, style: { ...DEFAULT_CHART_RENDER_THEME.style, type: { ...DEFAULT_CHART_RENDER_THEME.style.type, tickSize } } };
    for (const width of [320, 640]) {
      const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, { pretendToBeVisual: true });
      const el = dom.window.document.getElementById("host");
      const drawn = drawChart(el, big, theme, null, { availableWidth: width, context: "print" });
      const ys = [...el.querySelectorAll(".heat-row-labels text")].map((t) => Number(t.getAttribute("y"))).sort((a, b) => a - b);
      assert.equal(ys.length, 24, "every row keeps its name");
      const pitch = Math.min(...ys.slice(1).map((y, i) => y - ys[i]));
      assert.ok(pitch >= tickSize, `print ${width} px, ${tickSize} px font: rows are ${pitch} px apart`);
      assert.equal(el.querySelectorAll("rect.heat-cell").length, 24 * 48, "every cell is drawn");
      assert.ok(drawn.plotSize.cell.h >= tickSize);
    }
  }
  const screen = chartPlotSize({ kind: "heatmap", rows: 24, cols: 48, availableWidth: 480, minCellHeight: 16 });
  assert.equal(screen.cell.h, 20, "the screen path keeps its 20 px rows at the default font");
});
