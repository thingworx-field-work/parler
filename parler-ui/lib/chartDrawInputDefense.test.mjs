import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { drawChart } from "../components/chart-draw.js";

/** Bypass the adapter on purpose: the renderer must refuse on its own. */
function draw(chart) {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, {
    pretendToBeVisual: true,
  });
  const el = dom.window.document.getElementById("host");
  const original = console.warn;
  const lines = [];
  console.warn = (msg) => lines.push(String(msg));
  try {
    drawChart(el, chart);
  } finally {
    console.warn = original;
  }
  return { svg: el.querySelector("svg"), lines };
}

const ISO = ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"];

test("renderer refuses a chart with null y instead of drawing it at zero", () => {
  for (const chart of [
    { kind: "bar", series: [{ name: "s", x: ["a", "b"], y: [null, 10] }] },
    { kind: "line", series: [{ name: "s", x: ISO, y: [null, 10] }] },
    { kind: "scatter", series: [{ name: "s", x: ["1", "2"], y: [null, 10] }] },
    { kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [null, 10] }] },
  ]) {
    const { svg, lines } = draw(chart);
    assert.equal(svg, null, `${chart.kind} must not draw`);
    assert.equal(lines.length, 1);
    assert.ok(lines[0].includes("CHART_Y_NOT_FINITE_NUMBER"));
  }
});

test("renderer rejection does not throw on malformed identity metadata", () => {
  const { svg, lines } = draw({
    kind: "bar",
    chartId: { toString: null },
    series: [{ name: "s", x: ["a"], y: [null] }],
  });
  assert.equal(svg, null);
  assert.equal(lines.length, 1);
  assert.ok(lines[0].includes("CHART_Y_NOT_FINITE_NUMBER"));
  assert.ok(lines[0].includes("chartId=-"));
});

test("renderer refuses numeric-string and boolean y without coercion", () => {
  assert.equal(draw({ kind: "bar", series: [{ name: "s", x: ["a", "b"], y: [5, "5"] }] }).svg, null);
  assert.equal(draw({ kind: "line", series: [{ name: "s", x: ISO, y: [true, 1] }] }).svg, null);
});

test("renderer refuses the whole chart when one series has a bad shape (no partial drawing)", () => {
  const { svg, lines } = draw({
    kind: "bar",
    series: [
      { name: "ok", x: ["a", "b"], y: [1, 2] },
      { name: "bad", x: ["a", "b"], y: [1] },
    ],
  });
  assert.equal(svg, null, "previously the valid series was drawn alone");
  assert.ok(lines[0].includes("CHART_SERIES_SHAPE"));
});

test("renderer still draws legal zeros for every kind", () => {
  const bar = draw({ kind: "bar", series: [{ name: "s", x: ["a", "b"], y: [0, 3] }] });
  assert.ok(bar.svg);
  assert.equal(bar.svg.querySelectorAll("rect[class^='bar-s']").length, 2);
  const line = draw({ kind: "line", series: [{ name: "s", x: ISO, y: [0, 0] }] });
  assert.ok(line.svg);
  assert.equal(line.svg.querySelectorAll("circle").length, 2);
  const scatter = draw({ kind: "scatter", series: [{ name: "s", x: ["1", "2"], y: [0, 2] }] });
  assert.ok(scatter.svg);
  const pie = draw({ kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [0, 3] }] });
  assert.ok(pie.svg);
  assert.equal(pie.svg.querySelectorAll("path").length, 1, "zero slice draws no arc");
  assert.equal(bar.lines.length + line.lines.length + scatter.lines.length + pie.lines.length, 0);
});

test("renderer accepts valid series with different point counts", () => {
  const { svg } = draw({
    kind: "line",
    series: [
      { name: "dense", x: ["1", "2", "3"], y: [1, 2, 3] },
      { name: "sparse", x: ["1", "3"], y: [1, 3] },
    ],
  });
  assert.ok(svg);
  assert.equal(svg.querySelectorAll("path[data-parler-palette-role='series']").length, 2);
});
