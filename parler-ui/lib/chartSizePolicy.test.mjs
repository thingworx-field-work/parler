import assert from "node:assert/strict";
import test from "node:test";
import { PIE_MAX_SIDE, bandMaxStep, chartPlotSize } from "./chartSizePolicy.js";

const WIDTHS = [320, 480, 768, 1200];

test("LS-1: pie side is W / φ clamped to 200–400 and the plot is square", () => {
  const sides = WIDTHS.map((W) => chartPlotSize({ kind: "pie", availableWidth: W }));
  assert.deepEqual(sides.map((s) => s.w), [200, 297, 400, 400]);
  assert.ok(sides.every((s) => s.h === s.w), "square plot");
  assert.equal(PIE_MAX_SIDE, 400);
  assert.equal(chartPlotSize({ kind: "pie", availableWidth: 240 }).w, 200, "the 240 floor still yields the 200 minimum");
  assert.equal(chartPlotSize({ kind: "pie", availableWidth: 647 }).w, 400, "W / φ = 399.9 rounds to 400");
  assert.equal(chartPlotSize({ kind: "pie", availableWidth: 100 }).availableWidth, 240, "W never drops below 240");
});

test("LS-2: the pie legend moves beside the disc only when 240px remain after the 24px gap", () => {
  const placements = WIDTHS.map((W) => chartPlotSize({ kind: "pie", availableWidth: W }));
  assert.deepEqual(placements.map((p) => p.legendPlacement), ["below", "below", "aside", "aside"]);
  assert.deepEqual(placements.map((p) => p.legendWidth), [0, 0, 320, 320]);
  assert.equal(chartPlotSize({ kind: "pie", availableWidth: 663 }).legendPlacement, "below", "W = 663 leaves 239");
  const edge = chartPlotSize({ kind: "pie", availableWidth: 664 });
  assert.equal(edge.legendPlacement, "aside", "W = 664 leaves exactly 240");
  assert.equal(edge.legendWidth, 240);
  assert.equal(chartPlotSize({ kind: "pie", availableWidth: 700 }).legendWidth, 276, "min(320, W − side − 24)");
});

test("LS-3: band charts cap the category step; w uses the renderer's margins and the W clamp", () => {
  assert.equal(bandMaxStep(1), 96);
  assert.equal(bandMaxStep(2), 128);
  assert.equal(bandMaxStep(6), 256, "S = 6 hits the 256 cap");
  assert.equal(bandMaxStep(9), 256);
  const bar = (n, W, S = 1) => chartPlotSize({ kind: "bar", orientation: "vertical", categories: n, seriesCount: S, marginLeft: 32, marginRight: 20, availableWidth: W });
  assert.equal(bar(2, 320).w, 244, "32 + 2 × 96 + 20");
  assert.equal(bar(2, 1200).w, 244, "the same at any wider W");
  assert.equal(bar(6, 320).w, 320, "min(628, 320)");
  assert.equal(bar(6, 1200).w, 628, "32 + 6 × 96 + 20");
  assert.equal(bar(24, 320).w, 320);
  assert.equal(bar(24, 1200).w, 1200, "many categories fill W as before");
  assert.equal(bar(2, 1200, 6).w, 32 + 2 * 256 + 20, "six series slots use the 256 step");
  assert.ok(WIDTHS.every((W) => bar(2, W).h === 240));
  assert.equal(chartPlotSize({ kind: "boxplot", categories: 3, seriesCount: 4, marginLeft: 40, marginRight: 20, availableWidth: 1200 }).w, 40 + 3 * 96 + 20, "boxplot counts one slot");
  assert.equal(bar(1, 1200).w, 240, "a single category still gets the 240 floor");
});

test("LS-4: line, scatter and horizontal bars keep the full width and their heights", () => {
  for (const W of WIDTHS) {
    for (const kind of ["line", "scatter", "histogram"]) {
      const s = chartPlotSize({ kind, availableWidth: W });
      assert.deepEqual([s.w, s.h, s.legendPlacement], [W, 240, "below"], `${kind} at ${W}`);
    }
    const hb = chartPlotSize({ kind: "bar", orientation: "horizontal", categories: 24, seriesCount: 6, marginLeft: 120, marginRight: 20, availableWidth: W });
    assert.equal(hb.w, W);
    assert.equal(hb.h, null, "the horizontal bar keeps its own height budget");
  }
});

test("LS-5: the three contexts share one function; expand caps the pie by height, print by 400; results are deterministic", () => {
  const input = { kind: "pie", availableWidth: 1200 };
  assert.equal(chartPlotSize({ ...input, context: "card" }).w, 400);
  assert.equal(chartPlotSize({ ...input, context: "print" }).w, 400);
  assert.equal(chartPlotSize({ ...input, context: "print", availableHeight: 900 }).w, 400, "print ignores availableHeight");
  assert.equal(chartPlotSize({ ...input, context: "expand", availableHeight: 300 }).w, 300, "expand is capped by the layer height");
  assert.equal(chartPlotSize({ ...input, context: "expand", availableHeight: 900 }).w, 742, "and may exceed 400 when the layer is tall (round(1200 / φ))");
  assert.equal(chartPlotSize({ ...input, context: "expand", availableHeight: 120 }).w, 200, "never below the 200 minimum");
  assert.equal(chartPlotSize({ ...input, context: "expand" }).w, 400, "expand without a height keeps the card cap");
  const bar = { kind: "bar", orientation: "vertical", categories: 2, seriesCount: 1, marginLeft: 32, marginRight: 20, availableWidth: 1200 };
  for (const context of ["card", "expand", "print"]) assert.equal(chartPlotSize({ ...bar, context }).w, 244, `bar in ${context}`);
  assert.deepEqual(chartPlotSize({ ...input, context: "expand", availableHeight: 500 }), chartPlotSize({ ...input, context: "expand", availableHeight: 500 }), "same inputs, same output");
  assert.equal(chartPlotSize({ ...input, context: "bogus" }).w, 400, "an unknown context behaves as the card");
});
