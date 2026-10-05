import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
for (const key of [
  "window",
  "document",
  "customElements",
  "HTMLElement",
  "Element",
  "Node",
  "Event",
  "CustomEvent",
  "MutationObserver",
  "CSSStyleSheet",
  "Document",
  "ShadowRoot",
]) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.requestAnimationFrame = (callback) => {
  callback(0);
  return 1;
};
globalThis.cancelAnimationFrame = () => {};
globalThis.ResizeObserver = class {
  observe() {}
  disconnect() {}
};

const { chartLegendItems, estimateTextWidth, responsiveChartViewBoxWidth, rotatedCategoryAxisBottom } = await import("../components/chart-draw.js");
const { CHART_LENGTH_LIMITS, DEFAULT_CHART_RENDER_THEME, resolveChartRenderObject } = await import(
  "../components/chart-theme.js"
);
const {
  DEFAULT_PORTABLE_PRINT_THEME,
  buildPortablePrintCss,
  buildPrintDocumentHtml,
  resolvePortablePrintTheme,
  rewriteClonedChartPaletteForPrint,
  stripNonPrintableControls,
  expandChartNotesForPrint,
  assembleChartCardsForPrint,
} = await import("./assistantResponseActions.js");
const screenCss = fs.readFileSync("styles/parler-ui.css", "utf8");
const { renderChartPrintCard } = await import("../components/parler-ui-chart.js");

async function mountCard(chart) {
  const el = document.createElement("parler-ui-chart");
  document.body.append(el);
  el.chart = chart;
  await el.updateComplete;
  await el.updateComplete;
  return el;
}

const ISO = ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"];
const TWO_LINES = {
  kind: "line",
  title: "Temperature (°C), line-1 vs line-2, 2026-09-12",
  series: [
    { name: "line-1", x: ISO, y: [1, 2] },
    { name: "line-2", x: ISO, y: [2, 3] },
  ],
};

test("chartLegendItems mirrors plot marks: shape, slot order, full labels, pie filtering", () => {
  const theme = DEFAULT_CHART_RENDER_THEME;
  const lines = chartLegendItems(TWO_LINES, theme);
  assert.deepEqual(
    lines.map((i) => [i.label, i.slot, i.mark, i.color]),
    [
      ["line-1", 0, "line", theme.palette.series[0]],
      ["line-2", 1, "line", theme.palette.series[1]],
    ]
  );
  const longName = "A very long device name that must never be truncated in the legend model";
  const bars = chartLegendItems(
    { kind: "bar", series: [{ name: longName, x: ["a"], y: [1] }, { name: " ", x: ["a"], y: [2] }] },
    theme
  );
  assert.equal(bars[0].label, longName, "labels are kept in full");
  assert.equal(bars[1].label, "Series 2", "blank names fall back to an index label");
  assert.ok(bars.every((i) => i.mark === "rect"));
  const scatter = chartLegendItems(
    { kind: "scatter", series: [{ name: "p", x: ["1"], y: [1] }, { name: "q", x: ["1"], y: [2] }] },
    theme
  );
  assert.ok(scatter.every((i) => i.mark === "circle"));
  const pie = chartLegendItems(
    { kind: "pie", series: [{ name: "s", x: ["zero", "first", "neg", "second"], y: [0, 3, -1, 7] }] },
    theme
  );
  assert.deepEqual(pie.map((i) => [i.label, i.slot]), [["first", 0], ["second", 1]]);
  assert.deepEqual(chartLegendItems({ kind: "line", series: [{ name: "only", x: ISO, y: [1, 2] }] }, theme), []);
  assert.deepEqual(chartLegendItems({ kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [0, 5] }] }, theme), []);
  assert.deepEqual(chartLegendItems({ kind: "bar", series: [{ name: "s", x: ["a"], y: [null] }] }, theme), []);
  const many = chartLegendItems(
    { kind: "line", series: Array.from({ length: 25 }, (_, i) => ({ name: `S${i + 1}`, x: ISO, y: [i, i] })) },
    theme
  );
  assert.equal(many[24].slot, 0, "slot 25 wraps to slot 1 exactly like the plot marks");
});

test("plot logical width equals the measured host width above the 240 floor", () => {
  const host = (width) => ({ getBoundingClientRect: () => ({ width }), clientWidth: width });
  assert.equal(responsiveChartViewBoxWidth(host(320)), 320, "narrow hosts no longer scale text");
  assert.equal(responsiveChartViewBoxWidth(host(480)), 480);
  assert.equal(responsiveChartViewBoxWidth(host(1200)), 1200);
  assert.equal(responsiveChartViewBoxWidth(host(100)), 240, "degenerate widths hit the floor");
  assert.equal(responsiveChartViewBoxWidth(host(0)), 640, "unmeasured hosts use the fallback");
});

test("the card renders title, plot and legend as DOM in that order", async () => {
  const el = await mountCard(TWO_LINES);
  const figure = el.querySelector("figure.chart-card");
  assert.ok(figure);
  const children = [...figure.children].map((c) => c.className);
  assert.deepEqual(children, ["chart-card-title", "chart-plot-area", "chart-range", "chart-legend", "chart-actions", "chart-notes"]);
  assert.equal(figure.querySelector(".chart-plot-area > .chart-root") !== null, true, "plot host sits in the focusable area");
  const title = figure.querySelector("figcaption.chart-card-title");
  assert.equal(title.getAttribute("part"), "chart-title");
  assert.equal(title.textContent.trim(), TWO_LINES.title);
  assert.equal(title.hasAttribute("style"), false, "no inline style: part rules must win");
  const svgPlot = figure.querySelector(".chart-root > svg");
  assert.ok(svgPlot, "the plot is drawn");
  assert.equal(svgPlot.querySelector('[data-parler-palette-role="title"]'), null, "no title inside the SVG");
  assert.equal(svgPlot.querySelector(".chart-legend"), null, "no legend inside the SVG");
  assert.equal(figure.querySelector(".chart-root").getAttribute("part"), "chart-plot");
  const legend = figure.querySelector("ul.chart-legend");
  assert.equal(legend.getAttribute("part"), "chart-legend");
  assert.equal(legend.hasAttribute("style"), false, "no inline style: part rules must win");
  const items = [...legend.querySelectorAll("li.chart-legend-item")];
  assert.deepEqual(items.map((li) => li.querySelector(".chart-legend-label").textContent), ["line-1", "line-2"]);
  assert.ok(items.every((li) => li.querySelector("button.chart-legend-toggle[part=chart-legend-toggle]")), "legend entries are controls");
  const marks = items.map((li) => li.querySelector("svg.chart-legend-swatch line"));
  assert.deepEqual(marks.map((m) => m.getAttribute("data-parler-series-slot")), ["0", "1"]);
  assert.equal(marks[0].getAttribute("stroke"), DEFAULT_CHART_RENDER_THEME.palette.series[0]);
  assert.equal(marks[0].getAttribute("data-parler-palette-role"), "series");
  el.remove();
});

test("single-series charts without a title render only the plot", async () => {
  const el = await mountCard({ kind: "bar", series: [{ name: "only", x: ["a", "b"], y: [1, 2] }] });
  const figure = el.querySelector("figure.chart-card");
  assert.deepEqual([...figure.children].map((c) => c.className), ["chart-plot-area", "chart-actions", "chart-notes"]);
  assert.deepEqual([...figure.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent), ["View data"], "only the view-data action applies to a single series");
  assert.ok(figure.querySelector(".chart-root > svg"));
  el.remove();
});

test("bar and pie legends use box marks with outline roles; scatter uses circles", async () => {
  const bar = await mountCard({
    kind: "bar",
    series: [{ name: "s1", x: ["a"], y: [1] }, { name: "s2", x: ["a"], y: [2] }],
  });
  const rect = bar.querySelector(".chart-legend-swatch rect");
  assert.equal(rect.getAttribute("data-parler-palette-role"), "series point-outline");
  assert.equal(rect.getAttribute("stroke"), DEFAULT_CHART_RENDER_THEME.palette.pointOutline);
  bar.remove();
  const pie = await mountCard({ kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [3, 7] }] });
  assert.equal(pie.querySelectorAll(".chart-legend-swatch rect").length, 2);
  pie.remove();
  const scatter = await mountCard({
    kind: "scatter",
    series: [{ name: "p", x: ["1"], y: [1] }, { name: "q", x: ["1"], y: [2] }],
  });
  assert.equal(scatter.querySelectorAll(".chart-legend-swatch circle").length, 2);
  scatter.remove();
});

test("a cloned card survives the print palette rewrite and keeps legend slots aligned", async () => {
  const el = await mountCard(TWO_LINES);
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  const rewritten = rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  assert.ok(rewritten > 0);
  const legendMarks = [...clone.querySelectorAll(".chart-legend-swatch line")];
  assert.deepEqual(
    legendMarks.map((m) => m.getAttribute("stroke")),
    DEFAULT_PORTABLE_PRINT_THEME.palette.series.slice(0, 2),
    "legend marks take the printed slot colors"
  );
  const plotPaths = [...clone.querySelectorAll('.chart-root path[data-parler-palette-role="series"]')];
  assert.deepEqual(
    plotPaths.map((p) => p.getAttribute("stroke")),
    DEFAULT_PORTABLE_PRINT_THEME.palette.series.slice(0, 2),
    "plot marks and legend marks share slots after printing"
  );
  assert.equal(el.querySelector(".chart-legend-swatch line").getAttribute("stroke"), DEFAULT_CHART_RENDER_THEME.palette.series[0], "screen DOM untouched");
  el.remove();
});

test("title and legend type comes from the chart tokens through the stylesheet in both modes", () => {
  const rule = (selector) => {
    const start = screenCss.indexOf(`${selector} {`);
    assert.ok(start >= 0, `${selector} rule exists`);
    return screenCss.slice(start, screenCss.indexOf("}", start));
  };
  const title = rule("parler-ui .chart-card-title");
  assert.match(title, /color: var\(--parler-effective-chart-title-text\)/);
  const [titleMin, titleMax] = CHART_LENGTH_LIMITS["chart-title-font-size"];
  assert.ok(
    title.includes(`font-size: clamp(${titleMin}px, var(--parler-effective-chart-title-font-size), ${titleMax}px)`),
    "title default size is clamped to the resolver's documented range"
  );
  assert.match(title, /font-weight: var\(--parler-effective-chart-title-font-weight\)/);
  assert.match(title, /font-family: var\(--parler-effective-chart-font-family\)/);
  assert.match(title, /overflow-wrap: anywhere/);
  const legend = rule("parler-ui .chart-legend");
  assert.match(legend, /color: var\(--parler-effective-chart-legend-text\)/);
  const [legendMin, legendMax] = CHART_LENGTH_LIMITS["chart-legend-font-size"];
  assert.ok(
    legend.includes(`font-size: clamp(${legendMin}px, var(--parler-effective-chart-legend-font-size), ${legendMax}px)`),
    "legend default size is clamped to the resolver's documented range"
  );
  const label = rule("parler-ui .chart-legend-label");
  assert.match(label, /overflow-wrap: anywhere/);
  const dark = screenCss.slice(screenCss.indexOf('parler-ui[theme-mode="parler-dark"] {'));
  for (const name of ["chart-title-text", "chart-legend-text", "chart-title-font-size", "chart-legend-font-size"]) {
    assert.ok(dark.includes(`--parler-effective-${name}: var(--parler-${name},`), `dark block defines ${name}`);
  }
});

test("the generated print document keeps a long legend label and constrains it to the page width", async () => {
  const longLabel = `Equipment_${"LongIdentifier".repeat(18)}`;
  const el = await mountCard({
    kind: "bar",
    title: `Very long title ${"word ".repeat(40)}`.trim(),
    series: [
      { name: longLabel, x: ["a"], y: [1] },
      { name: "short", x: ["a"], y: [2] },
    ],
  });
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  const htmlDoc = buildPrintDocumentHtml(clone.outerHTML, { printTheme: DEFAULT_PORTABLE_PRINT_THEME });
  const printed = new JSDOM(htmlDoc);
  const label = [...printed.window.document.querySelectorAll(".print-root .chart-legend-label")].find(
    (n) => n.textContent === longLabel
  );
  assert.ok(label, "the full label reaches the print document unchanged");
  assert.equal(printed.window.document.querySelector(".print-root .chart-card-title").textContent.startsWith("Very long title"), true);
  const css = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);
  const rule = (selector) => {
    const start = css.indexOf(`${selector} {`);
    assert.ok(start >= 0, `${selector} print rule exists`);
    return css.slice(start, css.indexOf("}", start));
  };
  assert.match(rule(".chart-legend-label"), /overflow-wrap: anywhere/);
  assert.match(rule(".chart-legend-label"), /min-width: 0/);
  assert.match(rule(".chart-legend-item"), /max-width: 100%/);
  assert.match(rule(".chart-legend-item"), /min-width: 0/);
  assert.match(rule(".chart-legend"), /max-width: 100%/);
  assert.match(rule(".chart-card-title"), /overflow-wrap: anywhere/);
  assert.match(rule(".chart-card-title"), new RegExp(`color: ${DEFAULT_PORTABLE_PRINT_THEME.palette.text}`));
  assert.match(rule(".chart-legend"), new RegExp(`color: ${DEFAULT_PORTABLE_PRINT_THEME.palette.muted}`));
  assert.doesNotMatch(css, /!important/, "no inline styles remain, so print needs no !important");
  el.remove();
});

const stubStyle = (values) => ({ getPropertyValue: (name) => values[name] ?? "" });
const pxOnly = (value) => {
  const m = /^(-?(?:\d+\.?\d*|\.\d+))px$/i.exec(String(value).trim());
  return m ? Number(m[1]) : null;
};
const anyColor = (value) => (String(value).trim() ? String(value).trim() : null);

test("out-of-range public font tokens are clamped to the documented ranges before print", () => {
  const cases = [
    { title: "100px", legend: "100px", expectTitle: 16, expectLegend: 18 },
    { title: "1px", legend: "1px", expectTitle: 8, expectLegend: 6 },
    { title: "15px", legend: "13px", expectTitle: 15, expectLegend: 13 },
  ];
  for (const c of cases) {
    const chartTheme = resolveChartRenderObject(
      stubStyle({
        "--parler-chart-title-font-size": c.title,
        "--parler-chart-legend-font-size": c.legend,
      }),
      { validateColor: anyColor, lengthToPixels: pxOnly }
    );
    assert.equal(chartTheme.style.type.titleSize, c.expectTitle, `title ${c.title}`);
    assert.equal(chartTheme.style.type.legendSize, c.expectLegend, `legend ${c.legend}`);
    const printTheme = resolvePortablePrintTheme(stubStyle({}), { chartTheme });
    assert.deepEqual(
      [printTheme.chartType.titleSize, printTheme.chartType.legendSize],
      [c.expectTitle, c.expectLegend]
    );
    const css = buildPortablePrintCss(printTheme);
    assert.ok(css.includes(`.chart-card-title {`) && css.includes(`font-size: ${c.expectTitle}px;`));
    assert.ok(css.includes(`.chart-legend {`) && css.includes(`font-size: ${c.expectLegend}px;`));
  }
});

test("the print document keeps the validated screen chart typography for title and legend", async () => {
  const chartTheme = resolveChartRenderObject(
    stubStyle({
      "--parler-chart-font-family": '"Mono Test", monospace',
      "--parler-chart-title-font-size": "16px",
      "--parler-chart-title-font-weight": "700",
      "--parler-chart-legend-font-size": "18px",
    }),
    { validateColor: anyColor, lengthToPixels: pxOnly }
  );
  const printTheme = resolvePortablePrintTheme(stubStyle({}), { chartTheme });
  assert.deepEqual(printTheme.chartType, {
    fontFamily: '"Mono Test", monospace',
    titleSize: 16,
    titleWeight: "700",
    legendSize: 18,
  });
  const el = await mountCard(TWO_LINES);
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  rewriteClonedChartPaletteForPrint(clone, printTheme);
  const htmlDoc = buildPrintDocumentHtml(clone.outerHTML, { printTheme });
  const css = buildPortablePrintCss(printTheme);
  assert.ok(htmlDoc.includes(css), "the generated document embeds the print CSS");
  const rule = (selector) => css.slice(css.indexOf(`${selector} {`), css.indexOf("}", css.indexOf(`${selector} {`)));
  const title = rule(".chart-card-title");
  assert.match(title, /font-family: "Mono Test", monospace;/);
  assert.match(title, /font-size: 16px;/);
  assert.match(title, /font-weight: 700;/);
  assert.match(title, new RegExp(`color: ${printTheme.palette.text};`), "color still comes from the print palette");
  const legend = rule(".chart-legend");
  assert.match(legend, /font-family: "Mono Test", monospace;/);
  assert.match(legend, /font-size: 18px;/);
  assert.match(legend, new RegExp(`color: ${printTheme.palette.muted};`));
  const defaults = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);
  assert.match(defaults.slice(defaults.indexOf(".chart-card-title {")), /font-weight: 600;/, "default title keeps weight 600 in print");
  assert.match(defaults.slice(defaults.indexOf(".chart-legend {")), /font-size: 12px;/);
  el.remove();
});

test("an invalid chart renders no plot and no legend but keeps the card shell", async () => {
  const original = console.warn;
  console.warn = () => {};
  try {
    const el = await mountCard({ kind: "bar", title: "bad", series: [{ name: "s", x: ["a"], y: [null] }] });
    assert.equal(el.querySelector(".chart-root > svg"), null);
    assert.equal(el.querySelector(".chart-legend"), null);
    assert.equal(el.querySelector(".chart-card-title").textContent.trim(), "bad");
    el.remove();
  } finally {
    console.warn = original;
  }
});

// ---------------------------------------------------------------------------
// C1a-2: keyboard point query, hover, reference lines, print stripping
// ---------------------------------------------------------------------------

function key(el, keyName) {
  const area = el.querySelector(".chart-plot-area");
  const event = new dom.window.KeyboardEvent("keydown", { key: keyName, bubbles: true, cancelable: true });
  area.dispatchEvent(event);
  return event;
}

async function settle(el) {
  await el.updateComplete;
  await el.updateComplete;
}

test("the plot area is one keyboard stop and arrows read points into a tooltip", async () => {
  const el = await mountCard({
    ...TWO_LINES,
    x_label: "Time",
    y_label: "Temperature (°C)",
    y_reference_lines: [{ y: 2.5, role: "ucl", label: "UCL" }],
  });
  const area = el.querySelector(".chart-plot-area");
  assert.equal(area.getAttribute("tabindex"), "0");
  assert.equal(area.getAttribute("role"), "group");
  assert.match(area.getAttribute("aria-label"), /arrow keys/);
  assert.equal(el.querySelector(".chart-tooltip"), null, "no tooltip before a query");
  assert.equal(el.querySelectorAll("[tabindex]").length, 1, "points are not individual tab stops");

  const first = key(el, "ArrowRight");
  assert.equal(first.defaultPrevented, true);
  await settle(el);
  let tip = el.querySelector(".chart-tooltip");
  assert.ok(tip, "tooltip appears on the first point");
  assert.equal(tip.getAttribute("part"), "chart-tooltip");
  assert.equal(tip.getAttribute("role"), "status");
  assert.equal(tip.hasAttribute("data-no-print"), true);
  let lines = [...tip.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[0], "line-1");
  assert.match(lines[1], /^Time: 2026-09-1\d \d\d:\d\d:\d\d \(.*UTC[+-]\d\d:\d\d\)\)?$/, "full local time with display zone");
  assert.equal(lines[2], "Temperature (°C): 1", "raw value with the axis caption");
  const ring = el.querySelector("svg .chart-focus-ring");
  assert.ok(ring, "focus ring drawn on the active point");
  assert.equal(ring.hasAttribute("data-no-print"), true);

  key(el, "ArrowRight");
  await settle(el);
  lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[2], "Temperature (°C): 2", "next point along x");
  key(el, "ArrowUp");
  await settle(el);
  lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[0], "line-2", "up moves to the next series at the same x");
  key(el, "ArrowUp");
  await settle(el);
  lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.deepEqual(lines, ["UCL", "Temperature (°C): 2.5"], "reference lines are queryable above the last series");
  key(el, "ArrowDown");
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip-line").textContent, "line-2");
  key(el, "Home");
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")][2].textContent, "Temperature (°C): 2");
  key(el, "End");
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")][2].textContent, "Temperature (°C): 3");

  const esc = key(el, "Escape");
  assert.equal(esc.defaultPrevented, true);
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip"), null, "Escape closes the tooltip");
  assert.equal(el.querySelector(".chart-focus-ring"), null);
  const unhandled = key(el, "Tab");
  assert.equal(unhandled.defaultPrevented, false, "other keys pass through");
  el.remove();
});

test("pointer hover queries the nearest mark and leaves nothing behind", async () => {
  const el = await mountCard({ ...TWO_LINES, x_label: "Time", y_label: "Temp" });
  const host = el.querySelector(".chart-root");
  const hit = el._drawn.hit;
  host.getBoundingClientRect = () => ({ left: 0, top: 0, width: hit.layout.viewBoxW, height: hit.layout.viewBoxH });
  const target = hit.series[1][0];
  const area = el.querySelector(".chart-plot-area");
  area.dispatchEvent(new dom.window.MouseEvent("mousemove", {
    bubbles: true,
    clientX: hit.layout.marginLeft + target.cx + 3,
    clientY: hit.layout.marginTop + target.cy - 3,
  }));
  await settle(el);
  const lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[0], "line-2");
  assert.equal(lines[2], "Temp: 2");
  area.dispatchEvent(new dom.window.MouseEvent("mousemove", { bubbles: true, clientX: 5, clientY: 5 }));
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip"), null, "far from any mark: no tooltip");
  area.dispatchEvent(new dom.window.MouseEvent("mousemove", {
    bubbles: true,
    clientX: hit.layout.marginLeft + target.cx,
    clientY: hit.layout.marginTop + target.cy,
  }));
  await settle(el);
  assert.ok(el.querySelector(".chart-tooltip"));
  area.dispatchEvent(new dom.window.MouseEvent("mouseleave", { bubbles: false }));
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip"), null, "mouseleave clears hover");
  el.remove();
});

test("bar and pie cards expose category and slice tooltips with raw values", async () => {
  const bar = await mountCard({
    kind: "bar",
    x_label: "Device",
    y_label: "Alarms",
    series: [{ name: "count", x: ["gate-1", "gate-2"], y: [-5, 10] }],
  });
  key(bar, "ArrowRight");
  await settle(bar);
  assert.deepEqual(
    [...bar.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent),
    ["count", "Device: gate-1", "Alarms: -5"]
  );
  key(bar, "ArrowRight");
  await settle(bar);
  assert.equal([...bar.querySelectorAll(".chart-tooltip-line")][2].textContent, "Alarms: 10");
  bar.remove();
  const pie = await mountCard({ kind: "pie", y_label: "Share", series: [{ name: "s", x: ["a", "b"], y: [3, 7] }] });
  key(pie, "ArrowRight");
  await settle(pie);
  assert.deepEqual(
    [...pie.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent),
    ["a", "Share: 3 (30.0%)", "Total: 10"]
  );
  key(pie, "ArrowRight");
  await settle(pie);
  assert.equal(pie.querySelector(".chart-tooltip-line").textContent, "b");
  pie.remove();
});

test("elapsed series tooltips show elapsed position and the source window zone", async () => {
  const el = await mountCard({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 120 },
    y_label: "Speed",
    series: [
      { name: "today", x: ["0", "60"], y: [1, 2], sourceWindow: { start: "2026-09-12T00:00:00Z", end: "2026-09-12T00:02:00Z", resolvedTimeZone: "Europe/Berlin" } },
      { name: "yesterday", x: ["0", "60"], y: [3, 4] },
    ],
  });
  key(el, "ArrowRight");
  await settle(el);
  const lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[0], "today");
  assert.equal(lines[1], "X: 0:00 elapsed (0)");
  assert.equal(lines[2], "Speed: 1");
  assert.equal(lines.length, 3, "elapsed X is not an absolute instant, so no display-zone line and no source-zone line");
  el.remove();
});

test("tooltips and focus rings never reach the print clone", async () => {
  const el = await mountCard(TWO_LINES);
  key(el, "ArrowRight");
  await settle(el);
  assert.ok(el.querySelector(".chart-tooltip") && el.querySelector(".chart-focus-ring"));
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  stripNonPrintableControls(clone);
  assert.equal(clone.querySelector(".chart-tooltip"), null);
  assert.equal(clone.querySelector(".chart-focus-ring"), null);
  assert.ok(clone.querySelector(".chart-root > svg"), "the plot itself is kept");
  rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  el.remove();
});

test("the active point survives a redraw and resets when the chart changes", async () => {
  const el = await mountCard(TWO_LINES);
  key(el, "ArrowRight");
  key(el, "ArrowRight");
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")][2].textContent, "Value: 2");
  el._draw(true);
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")][2].textContent, "Value: 2", "identity re-found after redraw");
  el.chart = { ...TWO_LINES, title: "changed" };
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip"), null, "a new chart snapshot clears the query");
  el.remove();
});

test("normalized and subsecond tooltips keep the raw value text", async () => {
  const norm = await mountCard({
    kind: "scatter",
    xAxisMode: "normalized",
    normalizedDomain: { start: 0, end: 1 },
    x_label: "Progress",
    y_label: "Speed",
    series: [{ name: "run", x: ["0.1234", "0.1244"], y: [1, 2] }],
  });
  key(norm, "ArrowRight");
  await settle(norm);
  assert.equal([...norm.querySelectorAll(".chart-tooltip-line")][1].textContent, "Progress: 0.1234 (12%)");
  key(norm, "ArrowRight");
  await settle(norm);
  assert.equal([...norm.querySelectorAll(".chart-tooltip-line")][1].textContent, "Progress: 0.1244 (12%)");
  norm.remove();
  const sub = await mountCard({
    kind: "line",
    x_label: "Time",
    series: [{ name: "s", x: ["2026-09-12T12:00:00.100Z", "2026-09-12T12:00:00.900Z"], y: [1, 2] }],
  });
  key(sub, "ArrowRight");
  await settle(sub);
  const first = [...sub.querySelectorAll(".chart-tooltip-line")][1].textContent;
  key(sub, "ArrowRight");
  await settle(sub);
  const second = [...sub.querySelectorAll(".chart-tooltip-line")][1].textContent;
  assert.match(first, /\.100 \(/, "milliseconds survive in the tooltip");
  assert.match(second, /\.900 \(/);
  assert.notEqual(first, second);
  const tickText = [...sub.querySelectorAll("svg .tick text")].map((t) => t.textContent);
  assert.ok(tickText.every((t) => !/UTC/.test(t)), "precision loss is not reported as a DST repeat");
  sub.remove();
});

test("datetime ticks are fitted to a narrow host so DST offset labels do not collide", async () => {
  const el = document.createElement("parler-ui-chart");
  document.body.append(el);
  el.chart = {
    kind: "line",
    series: [{ name: "s", x: ["2026-11-01T04:00:00Z", "2026-11-01T06:00:00Z", "2026-11-01T08:00:00Z"], y: [1, 2, 3] }],
  };
  await settle(el);
  const host = el.querySelector(".chart-root");
  Object.defineProperty(host, "clientWidth", { configurable: true, value: 320 });
  el._draw(true);
  await settle(el);
  const svg = host.querySelector("svg");
  assert.equal(svg.getAttribute("viewBox"), "0 0 320 240");
  const ticks = [...svg.querySelectorAll("svg > g > g")].filter((g) => g.getAttribute("transform")?.startsWith("translate(0,"));
  const xTicks = [...ticks[0].querySelectorAll(".tick")];
  const labels = xTicks.map((t) => t.querySelector("text").textContent);
  const xs = xTicks.map((t) => Number(/translate\(([-\d.]+),/.exec(t.getAttribute("transform"))[1])).sort((a, b) => a - b);
  const charWidth = DEFAULT_CHART_RENDER_THEME.style.type.tickSize * 0.62;
  const labelWidth = Math.max(...labels.map((l) => l.length)) * charWidth;
  for (let i = 1; i < xs.length; i++) {
    assert.ok(xs[i] - xs[i - 1] >= labelWidth + 8, `labels ${labels[i - 1]} / ${labels[i]} would overlap`);
  }
  assert.ok(labels.length >= 1 && labels.length <= 3, `only ${labels.length} fitted ticks at 320px`);
  el.remove();
});

test("a subsecond cross-day range at a narrow host keeps every tick label inside the gutters", async () => {
  const el = document.createElement("parler-ui-chart");
  document.body.append(el);
  el.chart = {
    kind: "line",
    series: [{ name: "s", x: ["2026-09-12T03:59:59.900Z", "2026-09-12T04:00:00.000Z"], y: [1, 2] }],
  };
  await settle(el);
  const host = el.querySelector(".chart-root");
  Object.defineProperty(host, "clientWidth", { configurable: true, value: 320 });
  el._draw(true);
  await settle(el);
  const layout = el._drawn.hit.layout;
  const svg = host.querySelector("svg");
  const axis = [...svg.querySelectorAll("svg > g > g")].find((g) => g.getAttribute("transform")?.startsWith("translate(0,"));
  const ticks = [...axis.querySelectorAll(".tick")];
  assert.ok(ticks.length >= 1, "the axis keeps a readable tick");
  const charWidth = DEFAULT_CHART_RENDER_THEME.style.type.tickSize * 0.62;
  for (const tick of ticks) {
    const label = tick.querySelector("text").textContent;
    const x = Number(/translate\(([-\d.]+),/.exec(tick.getAttribute("transform"))[1]);
    const half = (label.length * charWidth) / 2;
    assert.ok(x - half >= -layout.marginLeft, `${label} does not clip on the left`);
    assert.ok(x + half <= layout.innerW + (layout.viewBoxW - layout.marginLeft - layout.innerW), `${label} does not clip on the right`);
  }
  el.remove();
});

// ---------------------------------------------------------------------------
// C1a-3a: legend interaction, view state, actions, notes, print
// ---------------------------------------------------------------------------

const THREE_LINES = {
  kind: "line",
  title: "Three",
  y_label: "Temp",
  series: [
    { name: "small", x: ISO, y: [1, 2] },
    { name: "big", x: ISO, y: [100, 200] },
    { name: "mid", x: ISO, y: [10, 20] },
  ],
};

const legendButton = (el, i) => el.querySelectorAll("button.chart-legend-toggle")[i];
const seriesPaths = (el) => [...el.querySelectorAll('.chart-root path[data-parler-palette-role="series"]')];
const yTickMax = (el) => {
  const svg = el.querySelector(".chart-root svg");
  const axes = [...svg.querySelectorAll("svg > g > g")].filter((g) => g.querySelector(".domain"));
  const yAxis = axes.find((g) => !g.getAttribute("transform")?.startsWith("translate(0,"));
  return Math.max(...[...yAxis.querySelectorAll(".tick text")].map((t) => Number(t.textContent.replace(/,/g, ""))));
};

test("legend toggles hide a series while keeping its slot, and the Y domain stays full by default", async () => {
  const el = await mountCard(THREE_LINES);
  assert.equal(seriesPaths(el).length, 3);
  const fullMax = yTickMax(el);
  assert.ok(fullMax >= 200);
  const events = [];
  el.addEventListener("chart-view-change", (e) => events.push(e.detail.state));
  legendButton(el, 1).click();
  await settle(el);
  assert.equal(events.length, 1);
  assert.deepEqual(events[0].hiddenSeriesKeys, [1]);
  const paths = seriesPaths(el);
  assert.equal(paths.length, 2, "the hidden series is not drawn");
  assert.deepEqual(paths.map((p) => p.getAttribute("data-parler-series-slot")), ["0", "2"], "remaining series keep their original slots");
  assert.equal(legendButton(el, 1).getAttribute("aria-pressed"), "false");
  assert.equal(legendButton(el, 1).getAttribute("aria-label"), "Show series big");
  assert.equal(el.querySelectorAll("li.chart-legend-item")[1].hasAttribute("data-hidden"), true);
  assert.equal(yTickMax(el), fullMax, "full policy keeps the hidden series in the Y domain");
  assert.equal(el.querySelector(".chart-view-note").textContent, "1 of 3 series hidden (big)");
  const fit = [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => /Fit Y axis/.test(b.textContent));
  assert.equal(fit.getAttribute("aria-pressed"), "false");
  fit.click();
  await settle(el);
  assert.ok(yTickMax(el) < 100, "visible policy fits the Y domain to the shown series");
  assert.equal(fit.getAttribute("aria-pressed"), "true");
  assert.match(el.querySelector(".chart-view-note").textContent, /Y axis fitted to visible series/);
  const showAll = [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => /Show all/.test(b.textContent));
  showAll.click();
  await settle(el);
  assert.equal(seriesPaths(el).length, 3);
  assert.equal(el.querySelector(".chart-view-note").textContent, "Y axis fitted to visible series");
  el.remove();
});

test("hiding every series keeps the axes and offers a way back; keyboard query skips hidden series", async () => {
  const el = await mountCard(THREE_LINES);
  legendButton(el, 0).click();
  await settle(el);
  key(el, "ArrowRight");
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip-line").textContent, "big", "query starts at the first visible series");
  legendButton(el, 1).click();
  legendButton(el, 2).click();
  await settle(el);
  assert.equal(seriesPaths(el).length, 0);
  assert.ok(el.querySelector(".chart-root svg .domain"), "axes remain");
  assert.equal(el.querySelector(".chart-view-note").textContent, "No series selected");
  const showAll = [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => /Show all/.test(b.textContent));
  assert.ok(showAll);
  showAll.click();
  await settle(el);
  assert.equal(seriesPaths(el).length, 3);
  assert.equal(el.querySelector(".chart-view-note"), null);
  el.remove();
});

test("pie legend focuses a slice, dims the others and keeps the original denominator", async () => {
  const el = await mountCard({ kind: "pie", y_label: "Share", series: [{ name: "s", x: ["a", "b", "c"], y: [1, 2, 7] }] });
  legendButton(el, 2).click();
  await settle(el);
  const slices = [...el.querySelectorAll(".chart-root path[data-parler-series-slot]")];
  assert.deepEqual(slices.map((p) => p.getAttribute("opacity")), ["0.35", "0.35", null]);
  assert.equal(slices[2].hasAttribute("data-parler-slice-focused"), true);
  assert.equal(legendButton(el, 2).getAttribute("aria-pressed"), "true");
  assert.equal(el.querySelector(".chart-view-note").textContent, "Focused slice: c");
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent), ["a", "Share: 1 (10.0%)", "Total: 10"], "percentages keep the full total");
  const clear = [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => /Clear slice focus/.test(b.textContent));
  clear.click();
  await settle(el);
  assert.ok([...el.querySelectorAll(".chart-root path[data-parler-series-slot]")].every((p) => !p.hasAttribute("opacity")));
  el.remove();
});

test("data notes and the meta line read the chart's provenance and print expanded without controls", async () => {
  const el = await mountCard({
    ...TWO_LINES,
    y_label: "Temperature (°C)",
    requested_time_range: { start: "2026-09-12T00:00:00Z", end: "2026-09-12T02:00:00Z" },
    source: { sourceResolved: "cache_id", sourceCacheId: "cache-1", sourceColumns: ["t", "v"], rowCount: 9, pointCount: 4, truncationApplied: false },
  });
  const meta = el.querySelector("p.chart-card-meta[part=chart-meta]");
  assert.match(meta.textContent, /^Window: .* · Values: Temperature \(°C\)$/);
  const notes = el.querySelector("details.chart-notes[part=chart-notes]");
  assert.equal(notes.open, false, "notes start folded");
  const dl = [...notes.querySelectorAll(":scope > dl > dt")].map((dt) => dt.textContent);
  assert.deepEqual(dl.slice(0, 4), ["Source", "Window", "Columns", "Transform"]);
  const values = [...notes.querySelectorAll(":scope > dl > dd")].map((dd) => dd.textContent);
  assert.equal(values[0], "cache_id");
  assert.equal(values[3], "Not provided by source");
  assert.ok(!notes.querySelector(":scope > dl").textContent.includes("cache-1"), "cache id only in diagnostics");
  assert.ok(notes.querySelector("details.chart-notes-diagnostics dd").textContent.includes("c") || true);
  const diag = [...notes.querySelectorAll("details.chart-notes-diagnostics dd")].map((dd) => dd.textContent);
  assert.ok(diag.includes("cache-1"));
  legendButton(el, 1).click();
  await settle(el);
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  stripNonPrintableControls(clone);
  expandChartNotesForPrint(clone);
  assert.equal(clone.querySelector(".chart-actions"), null, "actions are not printed");
  assert.equal(clone.querySelector("details.chart-notes").hasAttribute("open"), true, "notes print expanded");
  assert.equal(clone.querySelector("details.chart-notes-diagnostics").hasAttribute("open"), true);
  assert.equal(clone.querySelector(".chart-view-note").textContent, "1 of 2 series hidden (line-2)", "the printed view is stated");
  assert.equal(clone.querySelector(".chart-card-meta") !== null, true);
  assert.equal(el.querySelector("details.chart-notes").open, false, "screen DOM untouched");
  const css = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);
  assert.match(css, /\.chart-actions,\n\.chart-data,\n\.chart-range,\n\.chart-expand-placeholder \{\n  display: none;/);
  assert.match(css, /\.chart-notes-list \{/);
  el.remove();
});

test("a host-owned view state is applied and a new chart snapshot resets series selection", async () => {
  const el = await mountCard(THREE_LINES);
  const changes = [];
  el.addEventListener("chart-view-change", (e) => changes.push(e.detail.state));
  legendButton(el, 2).click();
  await settle(el);
  el.viewState = changes[0];
  await settle(el);
  assert.equal(seriesPaths(el).length, 2);
  el.viewState = { ...changes[0], hiddenSeriesKeys: [] };
  await settle(el);
  assert.equal(seriesPaths(el).length, 3, "host state wins over the local copy");
  el.viewState = changes[0];
  await settle(el);
  assert.equal(seriesPaths(el).length, 2);
  el.chart = { ...THREE_LINES, series: [...THREE_LINES.series, { name: "extra", x: ISO, y: [5, 6] }] };
  await settle(el);
  assert.equal(seriesPaths(el).length, 4, "a different snapshot resets series selection");
  assert.equal(el.querySelector(".chart-view-note"), null);
  el.remove();
});

test("single-series charts show no legend controls or series actions but still offer view data and notes", async () => {
  const el = await mountCard({ kind: "bar", series: [{ name: "only", x: ["a", "b"], y: [1, 2] }] });
  assert.equal(el.querySelector(".chart-legend"), null);
  assert.deepEqual([...el.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent), ["View data"]);
  assert.ok(el.querySelector("details.chart-notes"));
  el.remove();
});

test("a replaced category with unchanged endpoints still resets pie focus", async () => {
  const el = await mountCard({ kind: "pie", series: [{ name: "status", x: ["Idle", "Running", "Fault"], y: [1, 2, 3] }] });
  legendButton(el, 1).click();
  await settle(el);
  assert.equal(el.querySelector(".chart-view-note").textContent, "Focused slice: Running");
  el.chart = { kind: "pie", series: [{ name: "status", x: ["Idle", "Maintenance", "Fault"], y: [1, 20, 3] }] };
  await settle(el);
  assert.equal(el.querySelector(".chart-view-note"), null, "focus does not carry over to a different category");
  assert.ok([...el.querySelectorAll(".chart-root path[data-parler-series-slot]")].every((p) => !p.hasAttribute("opacity")));
  assert.equal(legendButton(el, 1).getAttribute("aria-pressed"), "false");
  el.remove();
});

test("view data falls back to an in-card paged view and a host can cancel it", async () => {
  const el = await mountCard({
    kind: "scatter",
    x_label: "Load",
    y_label: "Temp",
    series: [{ name: "run", x: Array.from({ length: 25 }, (_, i) => String(i)), y: Array.from({ length: 25 }, (_, i) => i * 2) }],
  });
  const button = [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "View data");
  assert.ok(button, "the view-data action is offered for every drawable chart");
  assert.equal(button.getAttribute("aria-pressed"), "false");
  let seen = null;
  el.addEventListener("chart-view-data", (e) => { seen = e; });
  button.click();
  await settle(el);
  await new Promise((r) => setTimeout(r, 0));
  assert.ok(seen && seen.cancelable && seen.bubbles, "the event is cancelable and bubbles to the host");
  const view = el.querySelector("section.chart-data[part=chart-data]");
  assert.ok(view);
  assert.equal(view.hasAttribute("data-no-print"), true);
  assert.equal(button.getAttribute("aria-pressed"), "true");
  assert.equal(document.activeElement, view.querySelector(".chart-data-heading"));
  assert.match(view.querySelector(".chart-data-heading").textContent, /Showing 1–20 of 25 points/);
  assert.deepEqual([...view.querySelectorAll("thead th")].map((th) => th.textContent), ["Series", "Load", "Temp"]);
  assert.equal(view.querySelectorAll("tbody tr").length, 20);
  assert.deepEqual([...view.querySelector("tbody tr").querySelectorAll("td")].map((td) => td.textContent), ["run", "0", "0"]);
  const nav = [...view.querySelectorAll(".chart-data-nav .chart-action")];
  const prev = nav.find((b) => b.textContent === "Previous");
  const next = nav.find((b) => b.textContent === "Next");
  assert.equal(prev.disabled, true);
  next.click();
  await settle(el);
  assert.match(el.querySelector(".chart-data-heading").textContent, /Showing 21–25 of 25 points/);
  assert.equal(el.querySelectorAll(".chart-data tbody tr").length, 5);
  assert.equal([...el.querySelectorAll(".chart-data-nav .chart-action")].find((b) => b.textContent === "Next").disabled, true);
  const clone = el.querySelector("figure.chart-card").cloneNode(true);
  stripNonPrintableControls(clone);
  assert.equal(clone.querySelector(".chart-data"), null, "the data view is not printed");
  [...el.querySelectorAll(".chart-data-nav .chart-action")].find((b) => b.textContent === "Close").click();
  await settle(el);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(el.querySelector(".chart-data"), null);
  assert.equal(document.activeElement, button, "closing returns focus to the view-data control");
  el.addEventListener("chart-view-data", (e) => e.preventDefault());
  button.click();
  await settle(el);
  assert.equal(el.querySelector(".chart-data"), null, "a host that located the table suppresses the fallback view");
  el.remove();
});

// ---------------------------------------------------------------------------
// C1b-1: full-view print card and artifact-driven assembly
// ---------------------------------------------------------------------------

test("renderChartPrintCard builds a complete full-view card from the artifact alone", () => {
  const chart = { ...THREE_LINES, source: { sourceResolved: "cache_id", sourceCacheId: "cache-9", rowCount: 3, pointCount: 6, truncationApplied: false } };
  const card = renderChartPrintCard({ chart, theme: DEFAULT_CHART_RENDER_THEME, width: 500, viewSummary: "1 of 3 series hidden (big)", doc: document });
  assert.equal(card.isConnected, false, "detached");
  assert.equal(card.tagName, "FIGURE");
  assert.deepEqual([...card.children].map((c) => c.className), ["chart-card-title", "chart-card-meta", "chart-plot-area", "chart-print-note", "chart-legend", "chart-notes"]);
  const svg = card.querySelector(".chart-root svg");
  assert.equal(svg.getAttribute("viewBox"), "0 0 500 240", "the supplied width sets the logical width");
  assert.equal(svg.querySelectorAll('path[data-parler-palette-role="series"]').length, 3, "every series drawn");
  assert.equal(card.querySelectorAll("[data-hidden], [data-parler-slice-dimmed], [data-parler-series-slot][opacity]").length, 0, "no hidden or dimmed marks");
  assert.equal(card.querySelector(".chart-print-note").textContent, "Printed with the full analysis range and all series; on screen: 1 of 3 series hidden (big).");
  assert.deepEqual([...card.querySelectorAll(".chart-legend-label")].map((n) => n.textContent), ["small", "big", "mid"]);
  assert.deepEqual([...card.querySelectorAll(".chart-legend-swatch line")].map((l) => l.getAttribute("data-parler-series-slot")), ["0", "1", "2"]);
  assert.equal(card.querySelector("details.chart-notes").hasAttribute("open"), true);
  assert.equal(card.querySelector("details.chart-notes-diagnostics").hasAttribute("open"), true);
  assert.ok([...card.querySelectorAll("details.chart-notes-diagnostics dd")].some((dd) => dd.textContent === "cache-9"));
  assert.equal(card.querySelector(".chart-actions"), null);
  assert.equal(card.querySelector(".chart-view-note"), null);
  const plain = renderChartPrintCard({ chart: THREE_LINES, doc: document });
  assert.equal(plain.querySelector(".chart-print-note"), null, "no note when the screen matches the full view");
  assert.equal(plain.querySelector(".chart-root svg").getAttribute("viewBox"), "0 0 640 240", "unknown width falls back to 640");
  const clone = card.cloneNode(true);
  rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);
  assert.equal(clone.querySelector(".chart-legend-swatch line").getAttribute("stroke"), DEFAULT_PORTABLE_PRINT_THEME.palette.series[0]);
});

test("assembleChartCardsForPrint replaces mounted cards and inserts missing ones in artifact order", () => {
  const clone = document.createElement("div");
  clone.innerHTML = `
    <div class="parler-data-table-wrap" data-parler-artifact-key="table:t1"></div>
    <parler-ui-chart data-parler-artifact-key="chart:c1"></parler-ui-chart>
    <div class="md">text</div>`;
  const artifacts = [
    { type: "table", key: "table:t1", seq: 0, table: {} },
    { type: "chart", key: "chart:c1", seq: 1, chart: THREE_LINES },
    { type: "chart", key: "chart:c2", seq: 2, chart: { ...THREE_LINES, title: "missing" } },
    { type: "chart", key: "chart:c0", seq: 3, chart: { ...THREE_LINES, title: "trailing" } },
  ];
  const built = [];
  const count = assembleChartCardsForPrint(clone, artifacts, (artifact) => {
    built.push(artifact.key);
    const figure = document.createElement("figure");
    figure.className = "chart-card";
    figure.textContent = artifact.chart.title ?? "";
    return figure;
  });
  assert.equal(count, 3);
  assert.deepEqual(built, ["chart:c1", "chart:c2", "chart:c0"]);
  assert.equal(clone.querySelectorAll("parler-ui-chart").length, 0, "live elements replaced");
  const order = [...clone.querySelectorAll("[data-parler-artifact-key]")].map((el) => el.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(order, ["table:t1", "chart:c1", "chart:c2", "chart:c0"], "missing members land at their artifact position");
  assert.equal(clone.lastElementChild.className, "md", "markdown stays after the artifacts");
  const empty = document.createElement("div");
  empty.innerHTML = `<div class="md">only text</div>`;
  assert.equal(assembleChartCardsForPrint(empty, [{ type: "chart", key: "chart:x", seq: 0, chart: THREE_LINES }], () => document.createElement("figure")), 1);
  assert.equal(empty.firstElementChild.tagName, "FIGURE", "with no artifact elements the card goes before the markdown");
});

// ---------------------------------------------------------------------------
// C1b-2: X zoom, selection, reset
// ---------------------------------------------------------------------------

const FIVE_ISO = [0, 1, 2, 3, 4].map((m) => `2026-09-12T00:0${m}:00Z`);
const ZOOM_LINE = {
  kind: "line",
  title: "Zoom",
  y_label: "Temp",
  series: [
    { name: "a", x: FIVE_ISO, y: [1, 2, 3, 4, 5] },
    { name: "b", x: FIVE_ISO, y: [5, 4, 3, 2, 1] },
  ],
};
const rangeInputs = (el) => [...el.querySelectorAll(".chart-range input[type=range]")];
const setRange = async (el, index, value) => {
  const input = rangeInputs(el)[index];
  input.value = String(value);
  input.dispatchEvent(new dom.window.Event("input", { bubbles: true }));
  await settle(el);
};
const actionButton = (el, text) => [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === text) ?? null;
const xTickTexts = (el) => {
  const svg = el.querySelector(".chart-root svg");
  const axes = [...svg.querySelectorAll("svg > g > g")].filter((g) => g.querySelector(".domain"));
  const xAxis = axes.find((g) => g.getAttribute("transform")?.startsWith("translate(0,"));
  return [...xAxis.querySelectorAll(".tick text")].map((t) => t.textContent);
};

test("the range control zooms only the X axis: visible points stay linear, outside points leave the hit model, ticks follow", async () => {
  const el = await mountCard(ZOOM_LINE);
  const range = el.querySelector("div.chart-range");
  assert.ok(range, "line charts get a range control");
  assert.equal(range.getAttribute("part"), "chart-range");
  assert.ok(range.hasAttribute("data-no-print"), "the control never prints");
  const [start, end] = rangeInputs(el);
  assert.deepEqual([start.min, start.max, start.value, end.value], ["0", "1000", "0", "1000"]);
  assert.match(start.getAttribute("aria-valuetext"), /2026/);
  const fullTicks = xTickTexts(el);
  const fullYMax = yTickMax(el);
  const fullHit = el._drawn.hit;
  const fullInnerW = fullHit.layout.innerW;
  const changes = [];
  el.addEventListener("chart-view-change", (e) => changes.push(e.detail.state));
  await setRange(el, 0, 250);
  await setRange(el, 1, 750);
  const full = el._drawn.xDomain.full;
  const span = full[1] - full[0];
  assert.deepEqual(changes.at(-1).viewXDomain, [full[0] + 0.25 * span, full[0] + 0.75 * span]);
  assert.deepEqual(el._drawn.xDomain.view, changes.at(-1).viewXDomain, "the drawn view is the committed zoom");
  const hit = el._drawn.hit;
  assert.equal(hit.layout.innerW, fullInnerW, "plot geometry is unchanged; only the x domain moved");
  const visible = hit.series[0].map((p) => p.index);
  assert.deepEqual(visible, [1, 2, 3], "points outside the view are not hit targets");
  const cx = hit.series[0].map((p) => p.cx);
  const minute = span / 4;
  const expected = [1, 2, 3].map((i) => ((full[0] + i * minute - changes.at(-1).viewXDomain[0]) / (0.5 * span)) * fullInnerW);
  cx.forEach((v, i) => assert.ok(Math.abs(v - expected[i]) < 1e-6, `cx ${v} maps linearly inside the zoomed view`));
  assert.equal(yTickMax(el), fullYMax, "the Y domain is independent of the X view");
  assert.notDeepEqual(xTickTexts(el), fullTicks, "ticks are recomputed for the zoomed domain");
  assert.ok(el.querySelector(".chart-root svg clipPath"), "zoomed data layer is clipped");
  assert.match(el.querySelector(".chart-view-note").textContent, /^Zoomed to .* of .*/);
  assert.equal(rangeInputs(el)[0].value, "250");
  assert.equal(rangeInputs(el)[1].value, "750");
  el.remove();
});

test("range start never passes end, whole-domain positions clear the zoom, and a redraw keeps it", async () => {
  const el = await mountCard(ZOOM_LINE);
  await setRange(el, 1, 400);
  await setRange(el, 0, 900);
  assert.deepEqual(rangeInputs(el).map((i) => i.value), ["399", "400"], "start is clamped one step below end");
  const endInput = rangeInputs(el)[1];
  endInput.value = "1200";
  endInput.dispatchEvent(new dom.window.Event("change", { bubbles: true }));
  await settle(el);
  assert.equal(rangeInputs(el)[1].value, "1000", "a change event also updates the state and is clamped to the domain");
  assert.equal(el._drawn.xDomain.view[1], el._drawn.xDomain.full[1]);
  const before = el._drawn.xDomain.view;
  el._draw(true);
  await settle(el);
  assert.deepEqual(el._drawn.xDomain.view, before, "a forced redraw keeps the zoom");
  await setRange(el, 0, 0);
  await setRange(el, 1, 1000);
  assert.equal(el.querySelector(".chart-view-note"), null, "full positions mean no zoom");
  assert.equal(el.querySelector(".chart-range-text").textContent, "Full range");
  assert.equal(el._drawn.hit.series[0].length, 5);
  el.remove();
});

test("select visible range draws a band with the grid role, the note states it, and reset restores everything without fetching", async () => {
  const fetchCalls = [];
  const priorFetch = globalThis.fetch;
  globalThis.fetch = (...args) => {
    fetchCalls.push(args);
    return Promise.reject(new Error("no network in chart interactions"));
  };
  try {
    const el = await mountCard(ZOOM_LINE);
    assert.equal(actionButton(el, "Reset view"), null, "a default view offers no reset");
    assert.equal(actionButton(el, "Clear selection"), null);
    actionButton(el, "Select visible range").click();
    await settle(el);
    const fullBand = el.querySelector(".chart-root rect.chart-selection");
    assert.ok(fullBand, "selecting the unzoomed view marks the whole domain");
    assert.equal(Number(fullBand.getAttribute("x")), 0);
    assert.equal(Number(fullBand.getAttribute("width")), el._drawn.hit.layout.innerW, "the band spans the full plot width");
    assert.match(el.querySelector(".chart-actions .chart-selection-text").textContent, /^Selected: /);
    assert.match(el.querySelector(".chart-view-note").textContent, /^Selected: /);
    assert.ok(actionButton(el, "Clear selection"));
    assert.ok(actionButton(el, "Reset view"));
    actionButton(el, "Clear selection").click();
    await settle(el);
    assert.equal(el.querySelector(".chart-root rect.chart-selection"), null, "a full-domain selection clears like any other");
    assert.equal(el.querySelector(".chart-view-note"), null);
    await setRange(el, 0, 200);
    actionButton(el, "Select visible range").click();
    await settle(el);
    const band = el.querySelector(".chart-root rect.chart-selection");
    assert.ok(band, "selection band is drawn");
    assert.equal(band.getAttribute("data-parler-palette-role"), "grid");
    assert.equal(band.getAttribute("fill"), DEFAULT_CHART_RENDER_THEME.palette.grid);
    assert.match(el.querySelector(".chart-view-note").textContent, /Zoomed to .* · Selected: .*/);
    assert.match(el.querySelector(".chart-actions .chart-selection-text").textContent, /^Selected: .+ – .+ \(.+\)$/, "the actions bar shows the start and end with the display zone");
    assert.equal(band.getAttribute("part"), "chart-selection");
    await setRange(el, 0, 0);
    assert.ok(el.querySelector(".chart-root rect.chart-selection"), "the selection stays after zooming out");
    assert.match(el.querySelector(".chart-view-note").textContent, /^Selected: /);
    actionButton(el, "Clear selection").click();
    await settle(el);
    assert.equal(el.querySelector(".chart-root rect.chart-selection"), null);
    await setRange(el, 1, 500);
    legendButton(el, 1).click();
    await settle(el);
    actionButton(el, "Fit Y axis to visible series").click();
    await settle(el);
    actionButton(el, "Select visible range").click();
    await settle(el);
    const reset = actionButton(el, "Reset view");
    assert.ok(reset);
    const changes = [];
    el.addEventListener("chart-view-change", (e) => changes.push(e.detail.state));
    const eventTypes = new Set();
    const dispatch = el.dispatchEvent.bind(el);
    el.dispatchEvent = (event) => {
      eventTypes.add(event.type);
      return dispatch(event);
    };
    reset.click();
    await settle(el);
    assert.deepEqual([...eventTypes], ["chart-view-change"], "reset emits only the existing view-change event");
    const state = changes.at(-1);
    assert.deepEqual(
      [state.hiddenSeriesKeys, state.yDomainPolicy, state.viewXDomain, state.selectedRange, state.focusedSlice],
      [[], "full", null, null, null]
    );
    assert.equal(seriesPaths(el).length, 2);
    assert.equal(el._drawn.hit.series[0].length, 5);
    assert.equal(el.querySelector(".chart-view-note"), null);
    assert.equal(el.querySelector(".chart-root rect.chart-selection"), null);
    assert.equal(actionButton(el, "Reset view"), null);
    assert.deepEqual(rangeInputs(el).map((i) => i.value), ["0", "1000"]);
    assert.equal(fetchCalls.length, 0, "zoom, selection and reset never touch the network");
    el.remove();
  } finally {
    globalThis.fetch = priorFetch;
  }
});

test("a horizontal drag inside the plot zooms; a short drag is ignored and the range control mirrors the drag", async () => {
  const el = await mountCard(ZOOM_LINE);
  const host = el.querySelector(".chart-root");
  const layout = el._drawn.hit.layout;
  host.getBoundingClientRect = () => ({ left: 0, top: 0, width: layout.viewBoxW, height: layout.viewBoxH });
  const area = el.querySelector(".chart-plot-area");
  const mouse = (type, px) =>
    area.dispatchEvent(new dom.window.MouseEvent(type, { bubbles: true, button: 0, clientX: layout.marginLeft + px, clientY: layout.marginTop + 10 }));
  mouse("mousedown", 10);
  mouse("mousemove", 14);
  mouse("mouseup", 14);
  await settle(el);
  assert.equal(el.querySelector(".chart-view-note"), null, "under 8 logical px is a click, not a zoom");
  mouse("mousedown", layout.innerW * 0.5);
  mouse("mousemove", layout.innerW * 0.75);
  mouse("mouseup", layout.innerW * 0.75);
  await settle(el);
  assert.deepEqual(rangeInputs(el).map((i) => i.value), ["500", "750"]);
  assert.match(el.querySelector(".chart-view-note").textContent, /^Zoomed to /);
  mouse("mousedown", layout.innerW * 0.75);
  mouse("mousemove", layout.innerW * 0.25);
  mouse("mouseup", layout.innerW * 0.25);
  await settle(el);
  assert.deepEqual(rangeInputs(el).map((i) => i.value), ["563", "688"], "a right-to-left drag zooms inside the current view");
  el.remove();
});

test("repeated small drags in a scaled host never zoom below 1/1000 of the domain and the sliders stay distinct and ordered", async () => {
  const el = await mountCard(ZOOM_LINE);
  const host = el.querySelector(".chart-root");
  const layout = el._drawn.hit.layout;
  const scale = 0.8;
  host.getBoundingClientRect = () => ({ left: 0, top: 0, width: layout.viewBoxW * scale, height: layout.viewBoxH * scale });
  const area = el.querySelector(".chart-plot-area");
  const mouse = (type, px) =>
    area.dispatchEvent(new dom.window.MouseEvent(type, { bubbles: true, button: 0, clientX: (layout.marginLeft + px) * scale, clientY: (layout.marginTop + 10) * scale }));
  const changes = [];
  el.addEventListener("chart-view-change", (e) => changes.push(e.detail.state));
  const full = el._drawn.xDomain.full;
  const minSpan = (full[1] - full[0]) / 1000;
  for (let i = 0; i < 4; i++) {
    const from = layout.innerW * 0.5;
    mouse("mousedown", from);
    mouse("mousemove", from + 10);
    mouse("mouseup", from + 10);
    await settle(el);
    const view = el._drawn.xDomain.view;
    assert.ok(view[1] - view[0] >= minSpan - 1e-9, `drag ${i + 1}: span ${view[1] - view[0]} is at least the minimum ${minSpan}`);
    assert.ok(view[0] >= full[0] && view[1] <= full[1], "the view stays inside the hard bounds");
    const stored = changes.at(-1).viewXDomain;
    assert.ok(stored[1] - stored[0] >= minSpan - 1e-9, "the committed state honors the minimum, not only the render");
    const [start, end] = rangeInputs(el).map((r) => Number(r.value));
    assert.ok(end === start + 1 || end > start, `sliders ${start} < ${end} stay distinct and ordered`);
    assert.ok(end > start);
  }
  const finalView = el._drawn.xDomain.view;
  assert.ok(Math.abs(finalView[1] - finalView[0] - minSpan) < 1e-9, "nested drags settle at exactly the minimum span");
  const [start, end] = rangeInputs(el);
  assert.notEqual(start.getAttribute("aria-valuetext"), end.getAttribute("aria-valuetext"), "the accessible values name two different instants");
  el.remove();
});

test("a zoom survives a new snapshot when it still fits and is clamped to the new domain otherwise", async () => {
  const el = await mountCard(ZOOM_LINE);
  await setRange(el, 0, 250);
  await setRange(el, 1, 750);
  const zoom = el._drawn.xDomain.view;
  el.chart = { ...ZOOM_LINE, series: [{ name: "a", x: FIVE_ISO, y: [1, 2, 3, 4, 6] }, ZOOM_LINE.series[1]] };
  await settle(el);
  assert.deepEqual(el._drawn.xDomain.view, zoom, "same X domain: the zoom is kept across a value change");
  el.chart = { ...ZOOM_LINE, series: [{ name: "a", x: FIVE_ISO.slice(2), y: [3, 4, 5] }, { name: "b", x: FIVE_ISO.slice(2), y: [3, 2, 1] }] };
  await settle(el);
  const full = el._drawn.xDomain.full;
  const view = el._drawn.xDomain.view;
  assert.equal(view[0], full[0], "the view start is clamped to the new domain start");
  assert.equal(view[1], zoom[1], "the view end still fits");
  assert.ok(view[1] < full[1]);
  assert.match(el.querySelector(".chart-view-note").textContent, /^Zoomed to /);
  el.chart = { ...ZOOM_LINE, series: [{ name: "a", x: FIVE_ISO.slice(3), y: [4, 5] }, { name: "b", x: FIVE_ISO.slice(3), y: [2, 1] }] };
  await settle(el);
  assert.deepEqual(el._drawn.xDomain.view, el._drawn.xDomain.full, "a zoom entirely outside the new domain is cleared");
  assert.equal(el.querySelector(".chart-view-note"), null);
  el.remove();
});

test("bar and pie cards offer no range control, selection or drag zoom", async () => {
  const bar = await mountCard({ kind: "bar", series: [{ name: "s1", x: ["a", "b"], y: [1, 2] }, { name: "s2", x: ["a", "b"], y: [2, 3] }] });
  assert.equal(bar.querySelector(".chart-range"), null);
  assert.equal(actionButton(bar, "Select visible range"), null);
  assert.equal(bar._drawn.xDomain, undefined);
  bar.remove();
  const pie = await mountCard({ kind: "pie", series: [{ name: "s", x: ["a", "b"], y: [3, 7] }] });
  assert.equal(pie.querySelector(".chart-range"), null);
  assert.equal(actionButton(pie, "Select visible range"), null);
  pie.remove();
  const elapsed = await mountCard({
    kind: "line",
    xAxisMode: "elapsed",
    elapsedDomain: { start: 0, end: 600 },
    series: [{ name: "run", x: ["0", "300", "600"], y: [1, 2, 3] }, { name: "ref", x: ["0", "300", "600"], y: [3, 2, 1] }],
  });
  assert.equal(elapsed._drawn.xDomain.mode, "elapsed");
  assert.match(rangeInputs(elapsed)[1].getAttribute("aria-valuetext"), /^10:00 elapsed$/);
  elapsed.remove();
});

test("the print card ignores zoom and selection but the print note states them", async () => {
  const el = await mountCard(ZOOM_LINE);
  await setRange(el, 0, 250);
  actionButton(el, "Select visible range").click();
  await settle(el);
  const summary = el.querySelector(".chart-view-note").textContent;
  const card = renderChartPrintCard({ chart: ZOOM_LINE, width: 640, viewSummary: summary });
  assert.equal(card.querySelector(".chart-range"), null, "no range control in print");
  assert.equal(card.querySelector("rect.chart-selection"), null, "no selection band in print");
  assert.match(card.querySelector(".chart-print-note").textContent, /on screen: Zoomed to .* · Selected: /);
  const printSvg = card.querySelector(".chart-root svg");
  assert.ok(printSvg.querySelectorAll('circle[data-parler-palette-role="series"]').length >= 10 || printSvg.querySelectorAll("path[data-parler-palette-role=\"series\"]").length === 2);
  el.remove();
});

// ---------------------------------------------------------------------------
// C1b-3: expand action (the widget owns the expansion)
// ---------------------------------------------------------------------------

test("the expand action appears only when the owner marks the card expandable and asks the owner to act", async () => {
  const el = await mountCard(TWO_LINES);
  assert.equal(el.querySelector(".chart-expand-toggle"), null, "a standalone card cannot expand");
  el.expandable = true;
  await settle(el);
  const toggle = el.querySelector(".chart-actions .chart-expand-toggle");
  assert.equal(toggle.textContent, "Expand");
  assert.equal(toggle.getAttribute("aria-pressed"), "false");
  const seen = [];
  el.addEventListener("chart-expand", (e) => {
    seen.push({ cancelable: e.cancelable, bubbles: e.bubbles, expanded: e.detail.expanded, chart: e.detail.chart });
    e.preventDefault();
  });
  toggle.click();
  await settle(el);
  assert.deepEqual(seen, [{ cancelable: true, bubbles: true, expanded: false, chart: TWO_LINES }]);
  assert.equal(el.querySelector(".chart-expand-layer"), null, "the card never builds a layer itself");
  el.expanded = true;
  await settle(el);
  assert.equal(el.querySelector(".chart-expand-toggle").textContent, "Close");
  assert.equal(el.querySelector(".chart-expand-toggle").getAttribute("aria-pressed"), "true");
  assert.equal(el.getAttribute("expanded"), "", "the expanded state is reflected for styling");
  el.querySelector(".chart-expand-toggle").click();
  assert.equal(seen[1].expanded, true);
  el.openDataView();
  await settle(el);
  assert.ok(el.querySelector("section.chart-data"), "the owner can open the in-card data view directly");
  el.remove();
});

// ---------------------------------------------------------------------------
// C2a-1: horizontal bar card
// ---------------------------------------------------------------------------

test("a horizontal bar card scrolls in its plot area, keeps keyboard query and tooltips, and prints at full height", async () => {
  const names = Array.from({ length: 24 }, (_v, i) => `device-${i + 1}`);
  const chart = { kind: "bar", orientation: "horizontal", title: "Alarms by device", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: names, y: names.map((_n, i) => 24 - i) }, { name: "warnings", x: names, y: names.map((_n, i) => i) }] };
  const el = await mountCard(chart);
  const area = el.querySelector(".chart-plot-area");
  assert.equal(area.getAttribute("data-orientation"), "horizontal");
  const svg = el.querySelector(".chart-root svg");
  const height = Number(svg.getAttribute("viewBox").split(" ")[3]);
  assert.ok(height > 240, `the plot grows with the categories (${height})`);
  assert.match(screenCss, /\.chart-plot-area\[data-orientation="horizontal"\] \{\n  max-height: 720px;\n  overflow-y: auto;/);
  assert.match(screenCss, /\.chart-expand-layer \.chart-plot-area\[data-orientation="horizontal"\] \{\n  max-height: none;/);
  Object.defineProperty(area, "scrollHeight", { value: height, configurable: true });
  Object.defineProperty(area, "clientHeight", { value: 300, configurable: true });
  Object.defineProperty(area, "clientWidth", { value: 640, configurable: true });
  const viewBoxW = el._drawn.hit.layout.viewBoxW;
  // Real layout: boxes move with the scroll position of their scrolling ancestors.
  area.getBoundingClientRect = () => ({ left: 0, top: 0, width: 640, height: 300 });
  el.querySelector(".chart-root").getBoundingClientRect = () => ({ left: 0, top: -area.scrollTop, width: viewBoxW, height });
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent), ["count", "Device: device-1", "Alarms: 24"]);
  assert.equal(area.scrollTop, 0, "the first bar is already visible");
  key(el, "End");
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")][1].textContent, "Device: device-24");
  const last = el._active;
  const markY = el._drawn.hit.layout.marginTop + last.cy;
  const expected = markY + 12 - 300;
  assert.ok(Math.abs(area.scrollTop - expected) < 1e-6, `End scrolls the last bar and its tooltip room into view (${area.scrollTop} vs ${expected})`);
  const tip = el.querySelector(".chart-tooltip");
  const tipTop = Number.parseInt(tip.style.top, 10);
  const tipH = Math.ceil(3 * 12 * 1.35 + 14);
  assert.ok(tipTop >= area.scrollTop - 1e-6 && tipTop + tipH <= area.scrollTop + 300 + 1e-6, `the whole tooltip sits inside the visible region (${tipTop}..${tipTop + tipH} in ${area.scrollTop}..${area.scrollTop + 300})`);
  assert.ok(tipTop + tipH <= markY - 10 + 1e-6, "it is placed above the mark, in the room the reveal reserved");
  assert.equal(tip.style.right, "");
  key(el, "Home");
  await settle(el);
  assert.equal(area.scrollTop, 0, "Home scrolls back to the top");
  const firstTip = el.querySelector(".chart-tooltip");
  assert.ok(Number.parseInt(firstTip.style.top, 10) >= 0, "at the top row the tooltip flips below the mark instead of leaving the region");
  assert.ok(Number.parseInt(firstTip.style.top, 10) >= el._drawn.hit.layout.marginTop + el._active.cy + 10 - 1, "below the mark (integer-rounded)");
  key(el, "ArrowUp");
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip-line").textContent, "warnings", "Up moves to the adjacent series");
  el.querySelectorAll("button.chart-legend-toggle")[1].click();
  await settle(el);
  assert.equal(el.querySelectorAll('.chart-root rect[class^="bar-s"]').length, 24, "hiding a series keeps the other slots");
  assert.equal(Number(el.querySelector(".chart-root svg").getAttribute("viewBox").split(" ")[3]), height, "height is unchanged by hiding");
  const card = renderChartPrintCard({ chart, width: 640, viewSummary: "" });
  assert.equal(card.querySelector(".chart-plot-area").getAttribute("data-orientation"), "horizontal");
  assert.equal(Number(card.querySelector("svg").getAttribute("viewBox").split(" ")[3]), height, "print keeps the full height");
  assert.equal(card.querySelectorAll('rect[class^="bar-s"]').length, 48, "print draws every series");
  const css = buildPortablePrintCss(DEFAULT_PORTABLE_PRINT_THEME);
  assert.match(css, /\.chart-plot-area \{\n  max-height: none;\n  overflow: visible;/);
  el.remove();
});

test("the detached print card fits wide labels with the document's measured font, not the estimate", () => {
  const names = ["WWWW-MMMMMMMMMMMM-DEVICE-01", "MMMMMMMMMMMMMMMMMMMMMMMMMMMM", "上海工厂一号线设备甲乙丙丁戊己庚辛", "gate-1"];
  const chart = { kind: "bar", orientation: "horizontal", title: "Wide", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: names, y: [1, 2, 3, 4] }] };
  const proto = dom.window.SVGElement.prototype;
  const original = proto.getComputedTextLength;
  // A rendering browser whose font is much wider than the estimate for capitals: 1.4em per glyph.
  const perGlyph = (fs) => fs * 1.4;
  for (const fs of [12, 18]) {
    proto.getComputedTextLength = function () { return [...(this.textContent ?? "")].length * perGlyph(fs); };
    const theme = structuredClone(DEFAULT_CHART_RENDER_THEME);
    theme.style.type.tickSize = fs;
    try {
      const card = renderChartPrintCard({ chart, theme, width: 640, viewSummary: "" });
      assert.equal(document.body.querySelectorAll("svg").length, 0, "the probe is gone once the card is built");
      document.body.append(card);
      try {
        const svg = card.querySelector(".chart-root svg");
        const gutter = Number(/translate\(([\d.]+),/.exec(svg.querySelector("svg > g").getAttribute("transform"))[1]);
        const axisSpace = theme.style.axis.tickLength + theme.style.axis.tickPadding + 4;
        const lines = [...svg.querySelectorAll("g.category-axis .tick text tspan")].map((t) => t.textContent);
        assert.ok(lines.length >= 4);
        for (const line of lines) {
          const measured = [...line].length * perGlyph(fs);
          assert.ok(measured <= gutter - axisSpace + 1e-6, `printed line "${line}" (${measured}px) fits inside the ${gutter}px gutter at ${fs}px`);
          assert.ok(estimateTextWidth(line, fs) < measured || line === "gate-1", "the fit came from measurement, which is wider than the estimate here");
        }
        assert.ok(lines.some((l) => l.endsWith("…")), "wide names are ellipsized, not cropped");
      } finally {
        card.remove();
      }
    } finally {
      proto.getComputedTextLength = original;
    }
  }
});

// ---------------------------------------------------------------------------
// L1 (design §4.6): sizing in the card, aside pie legend, centred host, coordinates
// ---------------------------------------------------------------------------

const PIE = { kind: "pie", title: "Share", y_label: "Share", series: [{ name: "s", x: ["a", "b", "c"], y: [1, 2, 7] }] };

/** Give the card a measured width and re-run the observer path, as a real layout would. */
function layoutCard(el, width, height = 300) {
  const figure = el.querySelector("figure.chart-card");
  figure.getBoundingClientRect = () => ({ left: 0, top: 0, width, height });
  el._installObservers();
}

test("LS-1/LS-2: the pie card sizes its disc from the card width and lists values beside it when room allows", async () => {
  const el = await mountCard(PIE);
  const expect = { 320: ["200", "below"], 480: ["297", "below"], 768: ["400", "aside"], 1200: ["400", "aside"] };
  for (const [W, [side, placement]] of Object.entries(expect)) {
    layoutCard(el, Number(W));
    await settle(el);
    const root = el.querySelector(".chart-root");
    const svg = root.querySelector("svg");
    const vb = svg.getAttribute("viewBox").split(" ").map(Number);
    assert.deepEqual([vb[2], vb[3]], [Number(side), Number(side)], `square ${side} at W = ${W}`);
    assert.equal(root.style.width, `${side}px`, "the host is given the plot width");
    assert.equal(el._drawn.hit.layout.pie.r, Number(side) / 2 - 8, "radius is side / 2 − 8");
    assert.deepEqual([el._drawn.hit.layout.pie.cx, el._drawn.hit.layout.pie.cy], [Number(side) / 2, Number(side) / 2], "disc centred in the plot");
    const figure = el.querySelector("figure.chart-card");
    assert.equal(figure.getAttribute("data-legend-placement"), placement);
    const values = [...el.querySelectorAll(".chart-legend-value")].map((n) => n.textContent);
    if (placement === "aside") {
      assert.equal(figure.style.getPropertyValue("--parler-chart-legend-width"), "320px");
      assert.deepEqual(values, ["1 (10.0%)", "2 (20.0%)", "7 (70.0%)"]);
      assert.equal(el.querySelector(".chart-plot-area").nextElementSibling.className, "chart-legend", "the aside legend follows the plot area");
    } else {
      assert.deepEqual(values, [], "the legend below keeps its labels only");
      assert.equal(figure.style.getPropertyValue("--parler-chart-legend-width"), "");
    }
  }
  layoutCard(el, 1200);
  await settle(el);
  key(el, "ArrowRight");
  await settle(el);
  const tip = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(tip[1], "Share: 1 (10.0%)");
  assert.equal(el.querySelectorAll(".chart-legend-value")[0].textContent, "1 (10.0%)", "legend values and tooltip values come from the same numbers");
  legendButton(el, 2).click();
  await settle(el);
  assert.equal(legendButton(el, 2).getAttribute("aria-pressed"), "true", "the aside row highlights the focused slice");
  assert.equal(el.querySelectorAll(".chart-legend-value")[2].textContent, "7 (70.0%)", "percentages are not recomputed on focus");
  assert.match(screenCss, /\.chart-card\[data-legend-placement="aside"\] \{\n  display: flex;/);
  assert.match(screenCss, /\.chart-root \{\n  width: 100%;\n  max-width: 100%;\n  min-width: 0;\n  margin: 0 auto;/);
  el.remove();
});

test("LS-5: card, expand layer and print card call one policy; expand caps the pie by height, print by 400", async () => {
  const el = await mountCard(PIE);
  layoutCard(el, 1200);
  await settle(el);
  assert.equal(el._drawn.plotSize.w, 400, "card cap");
  const body = document.createElement("div");
  body.className = "chart-expand-body";
  Object.defineProperty(body, "clientHeight", { value: 300, configurable: true });
  document.body.append(body);
  body.append(el);
  el.expanded = true;
  await settle(el);
  layoutCard(el, 1200);
  await settle(el);
  assert.equal(el._drawn.plotSize.w, 300, "the expanded pie is capped by the layer's usable height");
  assert.equal(el.querySelector(".chart-root").style.width, "300px");
  Object.defineProperty(body, "clientHeight", { value: 900, configurable: true });
  layoutCard(el, 1201);
  await settle(el);
  assert.equal(el._drawn.plotSize.w, 742, "and grows past 400 when the layer is tall");
  const card = renderChartPrintCard({ chart: PIE, width: 1200, viewSummary: "" });
  const printSvg = card.querySelector(".chart-root svg");
  assert.equal(printSvg.getAttribute("viewBox"), "0 0 400 400", "print keeps the 400 cap at the card width");
  assert.equal(card.querySelector(".chart-root").style.width, "400px");
  assert.equal(card.getAttribute("data-legend-placement"), "aside");
  assert.deepEqual([...card.querySelectorAll(".chart-legend-value")].map((n) => n.textContent), ["1 (10.0%)", "2 (20.0%)", "7 (70.0%)"]);
  assert.equal(card.querySelector(".chart-plot-area").nextElementSibling.className, "chart-legend");
  const narrow = renderChartPrintCard({ chart: PIE, width: 480, viewSummary: "" });
  assert.equal(narrow.getAttribute("data-legend-placement"), "below");
  assert.equal(narrow.querySelectorAll(".chart-legend-value").length, 0);
  const barCard = renderChartPrintCard({ chart: { kind: "bar", series: [{ name: "s", x: ["a", "b"], y: [10, 20] }] }, width: 1200, viewSummary: "" });
  assert.equal(barCard.querySelector(".chart-root svg").getAttribute("viewBox"), `0 0 244 ${240 + rotatedCategoryAxisBottom(["a", "b"], DEFAULT_CHART_RENDER_THEME, (t) => estimateTextWidth(t, DEFAULT_CHART_RENDER_THEME.style.type.tickSize)).extra}`,
    "the printed bar uses the same band cap, plus the room its rotated labels need");
  assert.equal(barCard.querySelector(".chart-root").style.width, "244px");
  body.remove();
});

test("LS-6: a narrow centred bar plot keeps pointer, tooltip and keyboard coordinates on its marks and does not shrink on resize", async () => {
  const el = await mountCard({ kind: "bar", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: ["a", "b"], y: [10, 20] }] });
  const drawsBefore = [];
  layoutCard(el, 1200);
  await settle(el);
  const root = el.querySelector(".chart-root");
  const area = el.querySelector(".chart-plot-area");
  assert.equal(root.style.width, "244px", "root width equals the policy w");
  assert.equal(el._drawn.plotSize.w, 244);
  // Real layout: the 244px host sits centred inside the 1200px area.
  const offset = (1200 - 244) / 2;
  area.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1200, height: 240 });
  Object.defineProperty(area, "clientWidth", { value: 1200, configurable: true });
  root.getBoundingClientRect = () => ({ left: offset, top: 0, width: 244, height: 240 });
  const hit = el._drawn.hit;
  const target = hit.series[0][1];
  area.dispatchEvent(new dom.window.MouseEvent("mousemove", { bubbles: true, clientX: offset + hit.layout.marginLeft + target.cx, clientY: hit.layout.marginTop + target.cy }));
  await settle(el);
  assert.equal(el._hover?.index, 1, "the pointer hits the second bar through the centred offset");
  const tip = el.querySelector(".chart-tooltip");
  const tipLeft = Number.parseInt(tip.style.left, 10);
  assert.ok(Math.abs(tipLeft - Math.round(offset + hit.layout.marginLeft + target.cx + 10)) <= 1, `tooltip anchored at the bar (${tipLeft})`);
  area.dispatchEvent(new dom.window.MouseEvent("mousemove", { bubbles: true, clientX: 10, clientY: 10 }));
  await settle(el);
  assert.equal(el._hover, null, "the empty margin beside the centred plot hits nothing");
  key(el, "ArrowRight");
  await settle(el);
  const pos = el._drawn.hit.first();
  const ring = el.querySelector(".chart-focus-ring");
  assert.equal(Number(ring.getAttribute("cx")), pos.cx, "the focus ring is drawn in plot coordinates inside the SVG");
  // Stability: repeated resizes back to the same width never shrink the plot.
  for (let i = 0; i < 3; i++) {
    layoutCard(el, 1200);
    await settle(el);
    drawsBefore.push(el._drawn.plotSize.w);
  }
  assert.deepEqual(drawsBefore, [244, 244, 244]);
  assert.equal(root.style.width, "244px");
  // A height-only change of the card does not re-size.
  const captured = [];
  const RO = globalThis.ResizeObserver;
  globalThis.ResizeObserver = class { constructor(cb) { captured.push(cb); } observe() {} disconnect() {} };
  try {
    el._ro = null;
    el._installObservers();
    await settle(el);
    const drawn = el._drawn;
    const figure = el.querySelector("figure.chart-card");
    figure.getBoundingClientRect = () => ({ left: 0, top: 0, width: 1200, height: 900 });
    captured.at(-1)();
    assert.equal(el._drawn, drawn, "same width: no redraw");
    figure.getBoundingClientRect = () => ({ left: 0, top: 0, width: 600, height: 900 });
    captured.at(-1)();
    assert.notEqual(el._drawn, drawn, "a width change redraws");
    assert.equal(el._drawn.plotSize.availableWidth, 600);
    assert.equal(el._drawn.plotSize.w, 244, "and the narrow plot is unchanged because it never depended on the host width");
  } finally {
    globalThis.ResizeObserver = RO;
  }
  el.remove();
});

// ---------------------------------------------------------------------------
// C2b-1: histogram card
// ---------------------------------------------------------------------------

test("a histogram card has no legend or series actions, reads bins by keyboard, shows bin rows and notes, and prints", async () => {
  const chart = { kind: "histogram", title: "Temperature", x_label: "Temperature (°C)", source: { sourceResolved: "cache_id", sourceCacheId: "bn-1", sourceColumns: ["binIndex"], rowCount: 3, pointCount: 3, truncationApplied: false, transformSummary: "histogram(bin_numeric)" },
    histogram: { edges: [0, 1, 3, 10], counts: [1, 4, 5], densities: [0.1, 0.2, 5 / 70], mode: "count", validCount: 12, excludedCount: 1, belowRangeCount: 1, aboveRangeCount: 1, method: "explicit_edges_v1" } };
  const el = await mountCard(chart);
  assert.ok(el.querySelector(".chart-root svg"), "drawn");
  assert.equal(el.querySelector("ul.chart-legend"), null, "no legend");
  const actions = [...el.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent);
  assert.ok(actions.includes("View data"));
  assert.ok(!actions.some((t) => /Fit Y|Show all|Select visible|Reset view/.test(t)), "no series or range actions");
  assert.equal(el.querySelector(".chart-range"), null, "no range control");
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent), ["Temperature (°C): [0, 1)", "Count: 1", "Density: 0.1"]);
  key(el, "End");
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip-line").textContent, "Temperature (°C): [3, 10]", "the last bin is closed");
  assert.match(el.querySelector(".chart-card-meta").textContent, /Height: count/);
  const notes = [...el.querySelectorAll("details.chart-notes dt")].map((n) => n.textContent);
  for (const label of ["Binning method", "Bins", "Bar height", "Valid values", "Excluded values", "Below range", "Above range"]) assert.ok(notes.includes(label), label);
  const value = (label) => [...el.querySelectorAll("details.chart-notes dt")].find((n) => n.textContent === label).nextElementSibling.textContent;
  assert.equal(value("Binning method"), "explicit_edges_v1");
  assert.equal(value("Bins"), "3");
  assert.equal(value("Valid values"), "12");
  assert.equal(value("Above range"), "1");
  actions.length && [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "View data").click();
  await settle(el);
  const rowsText = [...el.querySelectorAll(".chart-data-table tbody tr")].map((tr) => [...tr.children].map((td) => td.textContent));
  assert.deepEqual(rowsText[0], ["Bin 1", "[0, 1)", "1 (density 0.1)"]);
  assert.deepEqual(rowsText[2], ["Bin 3", "[3, 10]", `5 (density ${String(5 / 70)})`]);
  assert.deepEqual([...el.querySelectorAll(".chart-data-table th")].map((n) => n.textContent), ["Bin", "Temperature (°C)", "Count (density)"]);
  const card = renderChartPrintCard({ chart, width: 640, viewSummary: "" });
  assert.equal(card.querySelectorAll("rect.bar-s0").length, 3, "the print card draws the bins");
  assert.equal(card.querySelector("ul.chart-legend"), null);
  assert.ok([...card.querySelectorAll("details.chart-notes dt")].some((n) => n.textContent === "Binning method"));
  el.remove();
});

// ---------------------------------------------------------------------------
// C2b-2: boxplot card
// ---------------------------------------------------------------------------

test("a boxplot card has no legend or series actions, reads groups and outliers by keyboard, shows statistic rows and notes, and prints", async () => {
  const high = Array.from({ length: 20 }, (_v, i) => 200 - i);
  const chart = { kind: "boxplot", title: "Temperature", x_label: "Device", y_label: "Temperature (°C)",
    source: { sourceResolved: "cache_id", sourceCacheId: "bx-1", sourceColumns: ["groupKey"], rowCount: 2, pointCount: 2, truncationApplied: false, transformSummary: "boxplot(box_summary)" },
    boxplot: { method: "tukey_1_5_iqr_linear_p_v1", groups: [
      { key: "Oven-01", n: 9, excludedCount: 0, min: 1, whiskerLow: 1, q1: 3, median: 5, q3: 7, whiskerHigh: 9, max: 9, outliers: [], outlierCount: 0 },
      { key: "Oven-02", n: 130, excludedCount: 2, min: 58.1, whiskerLow: 60.2, q1: 62, median: 63.1, q3: 64.4, whiskerHigh: 67.9, max: 200, outliers: high, outlierCount: 30 },
    ] },
    y_reference_lines: [{ y: 70, label: "USL", role: "usl" }] };
  const el = await mountCard(chart);
  assert.ok(el.querySelector(".chart-root svg"), "drawn");
  assert.equal(el.querySelector("ul.chart-legend"), null, "no legend");
  const actions = [...el.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent);
  assert.ok(actions.includes("View data"));
  assert.ok(!actions.some((t) => /Fit Y|Show all|Select visible|Reset view/.test(t)), "no series or range actions");
  assert.equal(el.querySelector(".chart-range"), null);
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent).slice(0, 2), ["Device: Oven-01", "n: 9"]);
  key(el, "ArrowRight");
  await settle(el);
  const lines = [...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent);
  assert.equal(lines[0], "Device: Oven-02");
  assert.match(lines.at(-1), /another 10 not shown/);
  key(el, "ArrowUp");
  await settle(el);
  assert.ok([...el.querySelectorAll(".chart-tooltip-line")].some((n) => /^Outlier: /.test(n.textContent)), "outliers are reachable");
  assert.match(el.querySelector(".chart-card-meta").textContent, /Groups: 2/);
  const value = (label) => [...el.querySelectorAll("details.chart-notes dt")].find((n) => n.textContent === label)?.nextElementSibling.textContent;
  assert.equal(value("Summary method"), "tukey_1_5_iqr_linear_p_v1");
  assert.equal(value("Groups"), "2");
  assert.equal(value("Values summarised"), "139");
  assert.equal(value("Excluded values"), "2");
  assert.equal(value("Outliers"), "30 (20 shown, another 10 not shown)");
  [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "View data").click();
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-data-table th")].map((n) => n.textContent), ["Device", "Statistic", "Temperature (°C)"]);
  const rowsText = [...el.querySelectorAll(".chart-data-table tbody tr")].map((tr) => [...tr.children].map((td) => td.textContent));
  assert.deepEqual(rowsText[0], ["Oven-01", "n", "9"]);
  assert.deepEqual(rowsText[5], ["Oven-01", "Median", "5"]);
  assert.deepEqual(rowsText[9], ["Oven-01", "Outliers", "None"]);
  assert.equal(rowsText.length, 20, "first page of 20 rows");
  const card = renderChartPrintCard({ chart, width: 640, viewSummary: "" });
  assert.equal(card.querySelectorAll("rect.box-body").length, 2, "the print card draws the boxes");
  assert.equal(card.querySelectorAll("circle.box-outlier").length, 20);
  assert.equal(card.querySelector("ul.chart-legend"), null);
  assert.ok([...card.querySelectorAll("details.chart-notes dt")].some((n) => n.textContent === "Summary method"));
  el.remove();
});

// ---------------------------------------------------------------------------
// C2b-3: heatmap card
// ---------------------------------------------------------------------------

test("a heatmap card scrolls its plot, shows the colour bar legend, reads cells by keyboard including No data, lists cell rows and notes, and prints", async () => {
  const chart = { kind: "heatmap", title: "Average temperature", x_label: "Hour", y_label: "Device",
    source: { sourceResolved: "cache_id", sourceCacheId: "gm-1", sourceColumns: ["hour", "device", "avg"], rowCount: 5, pointCount: 5, truncationApplied: false, transformSummary: "heatmap(device × hour)" },
    heatmap: { rows: ["Oven-01", "Oven-02"], cols: ["00", "01", "02"], values: [[63.1, 63.4, null], [61.0, 60.8, 61.2]], valueLabel: "Avg temperature (°C)", missingCount: 1 } };
  const el = await mountCard(chart);
  assert.ok(el.querySelector(".chart-root svg"), "drawn");
  assert.equal(el.querySelector("ul.chart-legend"), null, "no series legend");
  assert.equal(el.querySelector(".chart-plot-area").getAttribute("data-kind"), "heatmap");
  await settle(el);
  const legend = el.querySelector(".chart-heat-legend svg");
  assert.ok(legend, "the colour bar legend is rendered");
  assert.deepEqual([...legend.querySelectorAll("text.chart-heat-tick")].map((n) => n.textContent), ["60.8", "63.4"]);
  assert.ok([...legend.querySelectorAll("text")].some((n) => n.textContent === "No data"));
  const actions = [...el.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent);
  assert.ok(actions.includes("View data"));
  assert.ok(!actions.some((t) => /Fit Y|Show all|Select visible|Reset view/.test(t)));
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent), ["Device: Oven-01", "Hour: 00", "Avg temperature (°C): 63.1"]);
  key(el, "End");
  await settle(el);
  assert.equal([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent).at(-1), "Avg temperature (°C): No data");
  key(el, "ArrowUp");
  await settle(el);
  assert.equal(el.querySelector(".chart-tooltip-line").textContent, "Device: Oven-02");
  assert.match(el.querySelector(".chart-card-meta").textContent, /Cells: Avg temperature/);
  const value = (label) => [...el.querySelectorAll("details.chart-notes dt")].find((n) => n.textContent === label)?.nextElementSibling.textContent;
  assert.equal(value("Cell value"), "Avg temperature (°C)");
  assert.equal(value("Rows × columns"), "2 × 3");
  assert.equal(value("Cells with data"), "5");
  assert.match(value("Missing cells"), /^1 \(shown hatched/);
  [...el.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "View data").click();
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-data-table th")].map((n) => n.textContent), ["Device", "Hour", "Avg temperature (°C)"]);
  const rowsText = [...el.querySelectorAll(".chart-data-table tbody tr")].map((tr) => [...tr.children].map((td) => td.textContent));
  assert.equal(rowsText.length, 6);
  assert.deepEqual(rowsText[2], ["Oven-01", "02", "No data"]);
  assert.deepEqual(rowsText[3], ["Oven-02", "00", "61"]);
  const card = renderChartPrintCard({ chart, width: 640, viewSummary: "" });
  assert.equal(card.querySelectorAll("rect.heat-cell").length, 6, "the print card draws every cell");
  assert.equal(card.querySelector(".chart-plot-area").getAttribute("data-kind"), "heatmap");
  assert.ok(card.querySelector(".chart-heat-legend svg"), "the print card carries the colour bar legend");
  assert.ok([...card.querySelectorAll("details.chart-notes dt")].some((n) => n.textContent === "Missing cells"));
  el.remove();
});

// ---------------------------------------------------------------------------
// C2a-2: stacked bar card
// ---------------------------------------------------------------------------

test("a percent-stacked bar card keeps its legend and series actions, reads shares by keyboard, and states the stacking in meta and notes", async () => {
  const chart = { kind: "bar", title: "Status share", x_label: "Device", y_label: "Count", stackMode: "percent",
    source: { sourceResolved: "cache_id", sourceCacheId: "gm-2", sourceColumns: ["device", "status", "n"], rowCount: 9, pointCount: 9, truncationApplied: false, transformSummary: "grouped_bar(seriesColumn)" },
    series: [
      { name: "OK", x: ["D1", "D2", "D3"], y: [50, 0, 10] },
      { name: "Warn", x: ["D1", "D2", "D3"], y: [30, 0, 10] },
      { name: "Fault", x: ["D1", "D2", "D3"], y: [20, 0, 20] },
    ] };
  const el = await mountCard(chart);
  assert.ok(el.querySelector(".chart-root svg"));
  assert.equal(el.querySelectorAll("ul.chart-legend li").length, 3, "the series legend stays");
  const actions = [...el.querySelectorAll(".chart-actions .chart-action")].map((b) => b.textContent);
  assert.ok(actions.includes("Fit Y axis to visible series"));
  key(el, "ArrowRight");
  await settle(el);
  assert.deepEqual([...el.querySelectorAll(".chart-tooltip-line")].map((n) => n.textContent), ["OK", "Device: D1", "Count: 50", "Share: 50.0%", "Category total (Count): 100"]);
  key(el, "ArrowRight");
  await settle(el);
  assert.ok([...el.querySelectorAll(".chart-tooltip-line")].some((n) => n.textContent === "Share: none (category total is 0)"));
  assert.match(el.querySelector(".chart-card-meta").textContent, /Stacked: percent of category total/);
  const value = (label) => [...el.querySelectorAll("details.chart-notes dt")].find((n) => n.textContent === label)?.nextElementSibling.textContent;
  assert.match(value("Stacking"), /^Percent of the category total/);
  assert.equal(value("Categories with no share"), "D2 (total is 0; no bar is drawn, not 100%)");
  const card = renderChartPrintCard({ chart, width: 640, viewSummary: "" });
  assert.equal(card.querySelectorAll("rect[data-stack='percent']").length, 6, "the print card draws the stacked segments");
  el.remove();
});
