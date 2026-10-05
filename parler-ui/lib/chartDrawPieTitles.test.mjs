import test from "node:test";
import assert from "node:assert/strict";
import { JSDOM } from "jsdom";
import { drawChart } from "../components/chart-draw.js";

test("drawChart pie appends native title tooltips with label value and percent", () => {
  const dom = new JSDOM(`<!DOCTYPE html><html><body><div id="host"></div></body></html>`, {
    pretendToBeVisual: true,
  });
  const el = dom.window.document.getElementById("host");
  drawChart(el, {
    kind: "pie",
    title: "T",
    series: [{ name: "n", x: ["a", "b"], y: [3, 7] }],
  });
  const titles = el.querySelectorAll("svg title");
  assert.equal(titles.length, 2);
  const text = [...titles].map((t) => t.textContent ?? "");
  assert.ok(text.some((s) => s.includes("a:") && s.includes("3") && s.includes("30.0%")));
  assert.ok(text.some((s) => s.includes("b:") && s.includes("7") && s.includes("70.0%")));
});
