import assert from "node:assert/strict";
import test from "node:test";
import { createChartHitModel } from "./chartHitModel.js";

const layout = { viewBoxW: 640, viewBoxH: 240, marginLeft: 40, marginTop: 20, innerW: 580, innerH: 176 };
const pt = (series, index, cx, cy) => ({
  type: "point",
  series,
  index,
  cx,
  cy,
  lines: [`s${series}`, `x${index}`, `y${cy}`],
});

function lineModel() {
  return createChartHitModel({
    kind: "line",
    // series 0 arrives out of x order on purpose: navigation must follow x, not data order
    series: [
      [pt(0, 0, 100, 50), pt(0, 1, 300, 60), pt(0, 2, 200, 70)],
      [pt(1, 0, 100, 150), pt(1, 1, 200, 140)],
    ],
    references: [{ type: "reference", series: -1, index: 0, cx: 0, cy: 30, lines: ["UCL", "Value: 9"] }],
    layout,
  });
}

test("nearest point uses a bounded search across series", () => {
  const m = lineModel();
  assert.equal(m.nearest(205, 72).index, 2, "closest by x then distance");
  assert.equal(m.nearest(205, 72).series, 0);
  assert.equal(m.nearest(195, 138).series, 1);
  assert.equal(m.nearest(400, 60), null, "beyond the 24px bound");
  assert.equal(m.nearest(298, 60, 5).index, 1, "custom bound accepted");
  assert.equal(m.nearest(Number.NaN, 0), null);
});

test("left/right/home/end follow x order within a series; up/down cross series and references", () => {
  const m = lineModel();
  const start = m.step(null, "right");
  assert.deepEqual([start.series, start.index], [0, 0], "first step lands on the first point");
  const right = m.step(start, "right");
  assert.equal(right.index, 2, "x order: 100 -> 200 (index 2) before 300 (index 1)");
  assert.equal(m.step(right, "right").index, 1);
  assert.equal(m.step(m.step(right, "right"), "right").index, 1, "clamped at the end");
  assert.equal(m.step(right, "left").index, 0);
  assert.equal(m.step(right, "home").index, 0);
  assert.equal(m.step(right, "end").index, 1);
  const up = m.step(right, "up");
  assert.deepEqual([up.series, up.index], [1, 1], "same x position in the next series");
  const ref = m.step(up, "up");
  assert.equal(ref.type, "reference");
  assert.equal(ref.cx, up.cx, "reference anchors at the current x");
  assert.deepEqual(ref.lines, ["UCL", "Value: 9"]);
  assert.equal(m.step(ref, "up"), ref, "no further reference");
  const back = m.step(ref, "down");
  assert.deepEqual([back.series, back.type], [1, "point"]);
  assert.equal(m.step(start, "down"), start, "no series below the first");
  assert.equal(m.step(ref, "left"), ref, "references do not move sideways");
});

test("bar hits resolve by band and pie hits by angle", () => {
  const bars = createChartHitModel({
    kind: "bar",
    series: [
      [{ type: "bar", series: 0, index: 0, cx: 30, cy: 40, x: 20, width: 20, lines: ["a"] },
       { type: "bar", series: 0, index: 1, cx: 80, cy: 90, x: 70, width: 20, lines: ["b"] }],
      [{ type: "bar", series: 1, index: 0, cx: 52, cy: 60, x: 42, width: 20, lines: ["c"] },
       { type: "bar", series: 1, index: 1, cx: 102, cy: 20, x: 92, width: 20, lines: ["d"] }],
    ],
    references: [],
    layout,
  });
  assert.equal(bars.nearest(25, 100).lines[0], "a", "inside the first band");
  assert.equal(bars.nearest(60, 100).lines[0], "c");
  assert.equal(bars.nearest(65, 100).lines[0], "c", "nearest edge within bound");
  assert.equal(bars.nearest(300, 100), null);
  assert.equal(bars.step(bars.nearest(25, 100), "right").lines[0], "b");
  assert.equal(bars.step(bars.nearest(25, 100), "up").lines[0], "c");
  const pie = createChartHitModel({
    kind: "pie",
    series: [[
      { type: "slice", series: 0, index: 0, cx: 0, cy: 0, startAngle: 0, endAngle: Math.PI, lines: ["A"] },
      { type: "slice", series: 0, index: 1, cx: 0, cy: 0, startAngle: Math.PI, endAngle: 2 * Math.PI, lines: ["B"] },
    ]],
    references: [],
    layout: { ...layout, pie: { cx: 100, cy: 100, r: 50 } },
  });
  assert.equal(pie.nearest(130, 90).lines[0], "A", "right half is the first slice (clockwise from 12)");
  assert.equal(pie.nearest(70, 110).lines[0], "B");
  assert.equal(pie.nearest(100, 30), null, "outside the radius");
  const a = pie.nearest(130, 90);
  assert.equal(pie.step(a, "right").lines[0], "B");
  assert.equal(pie.step(a, "up"), a, "pie has no series axis");
});

test("find re-resolves an item by identity after a redraw", () => {
  const m = lineModel();
  assert.equal(m.find("point", 0, 2).cx, 200);
  assert.equal(m.find("point", 5, 0), null);
  assert.equal(m.find("reference", -1, 0, 123).cx, 123);
  assert.equal(m.first().index, 0);
});

test("nearest considers every candidate within the bound: duplicate x, steep neighbours, farther index", () => {
  const dup = createChartHitModel({
    kind: "scatter",
    series: [[pt(0, 0, 10, 10), pt(0, 1, 10, 150), pt(0, 2, 20, 150)]],
    references: [],
    layout,
  });
  assert.equal(dup.nearest(10, 10).index, 0, "a mark exactly under the pointer with a duplicate x");
  assert.equal(dup.nearest(10, 150).index, 1);
  const steep = createChartHitModel({
    kind: "scatter",
    series: [[pt(0, 0, 9, 10), pt(0, 1, 10, 150), pt(0, 2, 11, 150)]],
    references: [],
    layout,
  });
  assert.equal(steep.nearest(10, 10).index, 0, "the 1px-away mark beats the same-x mark 140px below");
  const far = createChartHitModel({
    kind: "line",
    series: [[pt(0, 0, 10, 100), pt(0, 1, 12, 100), pt(0, 2, 14, 0)]],
    references: [],
    layout,
  });
  assert.equal(far.nearest(9, 0).index, 2, "closest mark lies beyond the two x-neighbours");
  assert.equal(far.nearest(9, 200), null, "still bounded by the 24px distance");
});
