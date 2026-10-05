import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

import {
  chartLegendItems,
  drawChart,
  chartYDomain,
  responsiveChartLeftMargin,
  responsiveChartTickCount,
  responsiveChartViewBoxWidth,
} from "../components/chart-draw.js";
import {
  chartSeriesSlot,
  chartThemeSignature,
  DEFAULT_CHART_RENDER_THEME,
  resolveChartRenderObject,
} from "../components/chart-theme.js";
import {
  DEFAULT_PORTABLE_PRINT_THEME,
  rewriteClonedChartPaletteForPrint,
} from "./assistantResponseActions.js";

function style(values = {}) {
  return { getPropertyValue: (name) => values[name] ?? "" };
}

const validateColor = (value) =>
  /^(?:#[0-9a-f]{3,8}|[a-z]+)$/i.test(String(value).trim())
    ? String(value).trim()
    : null;
const lengthToPixels = (value) => {
  const match = /^(-?(?:\d+\.?\d*|\.\d+))px$/i.exec(String(value).trim());
  return match ? Number(match[1]) : null;
};

test("chart resolver uses effective values, falls through malformed public colors, and clamps geometry", () => {
  const render = resolveChartRenderObject(
    style({
      "--parler-effective-chart-series-1": "#112233",
      "--parler-chart-series-2": "linear-gradient(red, blue)",
      "--parler-theme-chart-series-2": "#223344",
      "--parler-chart-point-outline": "not-a-color",
      "--parler-theme-chart-point-outline": "linear-gradient(red, blue)",
      "--parler-theme-chart-point-outline-secondary": "#334455",
      "--parler-effective-chart-line-width": "99px",
      "--parler-effective-chart-line-point-radius": "-5px",
      "--parler-effective-chart-grid-opacity": "4",
      "--parler-effective-chart-reference-control-dash": "6, 4, 200",
      "--parler-effective-chart-title-font-weight": "1400",
    }),
    { validateColor, lengthToPixels }
  );

  assert.equal(render.palette.series[0], "#112233");
  assert.equal(render.palette.series[1], "#223344");
  assert.equal(render.palette.pointOutline, "#334455");
  assert.equal(render.style.lineWidth, 12);
  assert.equal(render.style.linePointRadius, 0);
  assert.equal(render.style.gridOpacity, 1);
  assert.equal(render.style.reference.controlDash, "6 4 100");
  assert.equal(render.style.type.titleWeight, "1000");
  assert.equal(render.palette.series.length, 24);
});

test("dark mode ignores bridged sources while preserving public overrides", () => {
  const render = resolveChartRenderObject(
    style({
      "--parler-chart-series-1": "#abcdef",
      "--parler-theme-chart-series-2": "#010203",
    }),
    { mode: "parler-dark", validateColor, lengthToPixels }
  );
  assert.equal(render.palette.series[0], "#abcdef");
  assert.equal(render.palette.series[1], "#ffb74d");
  assert.notEqual(render.palette.series[1], "#010203");
});

test("chart assignment is ordered modulo 24 and the complete signature is stable", () => {
  assert.equal(chartSeriesSlot(0), 0);
  assert.equal(chartSeriesSlot(23), 23);
  assert.equal(chartSeriesSlot(24), 0);
  assert.equal(chartSeriesSlot(49), 1);
  assert.equal(DEFAULT_CHART_RENDER_THEME.style.gridOpacity, 0.7);
  assert.equal(DEFAULT_CHART_RENDER_THEME.style.reference.opacity, 0.95);
  assert.equal(DEFAULT_CHART_RENDER_THEME.style.type.titleWeight, "600");
  assert.equal(
    chartThemeSignature(DEFAULT_CHART_RENDER_THEME),
    chartThemeSignature(structuredClone(DEFAULT_CHART_RENDER_THEME))
  );
});

function renderChart(chart, mutateTheme = () => {}) {
  const dom = new JSDOM("<!doctype html><div id=chart></div>", {
    pretendToBeVisual: true,
  });
  const root = dom.window.document.getElementById("chart");
  const theme = structuredClone(DEFAULT_CHART_RENDER_THEME);
  theme.palette.series = Array.from({ length: 24 }, (_, index) => `rgb(${index + 1}, 2, 3)`);
  theme.palette.grid = "rgb(4, 5, 6)";
  theme.palette.axis = "rgb(7, 8, 9)";
  theme.palette.pointOutline = "rgb(10, 11, 12)";
  theme.style.lineWidth = 5;
  theme.style.linePointRadius = 7;
  theme.style.scatterPointRadius = 8;
  theme.style.outlineWidth = 3;
  theme.style.barRadius = 6;
  theme.style.gridLineWidth = 2;
  theme.style.gridOpacity = 0.4;
  mutateTheme(theme);
  drawChart(root, chart, theme);
  return { root, theme };
}

test("responsive chart canvas preserves readable type in narrow embedded panels", () => {
  const dom = new JSDOM("<!doctype html><div id=chart></div>", {
    pretendToBeVisual: true,
  });
  const root = dom.window.document.getElementById("chart");
  const chart = {
    kind: "line",
    title: "Narrow embedded chart",
    series: [{ name: "reading", x: [0, 100], y: [2, 4] }],
  };

  Object.defineProperty(root, "clientWidth", { configurable: true, value: 457 });
  assert.equal(responsiveChartViewBoxWidth(root), 457, "1 logical px = 1 CSS px in narrow hosts");
  drawChart(root, chart);

  assert.equal(root.querySelector("svg").getAttribute("viewBox"), "0 0 457 240");
  const narrowTickCount = root.querySelectorAll('[data-parler-palette-role="tick"]').length;
  assert.equal(
    root.querySelector('[data-parler-palette-role="tick"]').getAttribute("font-size"),
    String(DEFAULT_CHART_RENDER_THEME.style.type.tickSize)
  );

  Object.defineProperty(root, "clientWidth", { configurable: true, value: 1024 });
  assert.equal(responsiveChartViewBoxWidth(root), 1024);
  assert.equal(responsiveChartTickCount(480), 5);
  assert.equal(responsiveChartTickCount(640), 5);
  assert.equal(responsiveChartTickCount(1024), 8);
  assert.equal(responsiveChartTickCount(2000), 10);
  drawChart(root, chart);
  assert.equal(root.querySelector("svg").getAttribute("viewBox"), "0 0 1024 240");
  assert.ok(
    root.querySelectorAll('[data-parler-palette-role="tick"]').length > narrowTickCount,
    "wide charts should expose more axis detail"
  );
  Object.defineProperty(root, "clientWidth", { configurable: true, value: 0 });
  assert.equal(responsiveChartViewBoxWidth(root), 640);
  assert.equal(responsiveChartTickCount(Number.NaN), 5);
});

test("chart title aligns to content while y-axis gutter follows formatted labels", () => {
  const compactSeries = [{ name: "reading", x: [0, 1], y: [2, 4] }];
  const longSeries = [{ name: "reading", x: [0, 1], y: [-123456789, 987654321] }];
  const compact = responsiveChartLeftMargin(chartYDomain(compactSeries[0].y, [], "line"), "line", 5);
  const long = responsiveChartLeftMargin(chartYDomain(longSeries[0].y, [], "line"), "line", 5);
  assert.ok(compact >= 32 && compact < 52, `compact gutter was ${compact}`);
  assert.ok(long > compact && long <= 64, `long gutter was ${long}`);
  assert.equal(responsiveChartLeftMargin(null, "pie", 5), 24);

  const { root } = renderChart({
    kind: "line",
    title: "Aligned title",
    series: compactSeries,
  });
  assert.equal(
    root.querySelector('[data-parler-palette-role="title"]'),
    null,
    "the title is card DOM, not SVG text"
  );
  const plotTransform = root.querySelector("svg > g").getAttribute("transform");
  assert.equal(plotTransform, `translate(${compact},20)`);
});

test("line charts theme marks, legends, axes, grid, typography, and references", () => {
  const series = Array.from({ length: 25 }, (_, index) => ({
    name: `Series ${index + 1}`,
    x: [0, 1],
    y: [index, index + 1],
  }));
  const { root, theme } = renderChart({
    kind: "line",
    title: "Themed chart",
    x_label: "time",
    y_label: "value",
    series,
    y_reference_lines: [{ y: 10, role: "target", label: "goal" }],
  });

  const seriesNodes = root.querySelectorAll('[data-parler-palette-role~="series"]');
  assert.ok(seriesNodes.length > 25);
  const slot0 = root.querySelectorAll('[data-parler-series-slot="0"]');
  assert.ok(slot0.length >= 4, "slot 1 and slot 25 marks and legends must wrap together");
  assert.ok([...slot0].every((node) =>
    node.getAttribute("stroke") === theme.palette.series[0] ||
    node.getAttribute("fill") === theme.palette.series[0]
  ));
  assert.ok(root.querySelectorAll('[data-parler-palette-role="grid"]').length > 0);
  assert.equal(root.querySelector(".chart-legend"), null, "the legend is card DOM, not SVG");
  const legend = chartLegendItems({ kind: "line", series }, theme);
  assert.equal(legend.length, 25);
  assert.equal(legend[24].slot, 0, "the 25th legend entry wraps to slot 1 with its plot marks");
  assert.equal(legend[24].color, theme.palette.series[0]);
  assert.equal(legend[24].label, "Series 25");
  assert.ok(legend.every((item) => item.mark === "line"));
  assert.ok(root.querySelectorAll('[data-parler-palette-role="axis"]').length > 0);
  assert.ok(root.querySelectorAll('[data-parler-palette-role="tick"]').length > 0);
  assert.equal(root.querySelector('[data-parler-palette-role="title"]'), null);
  assert.equal(root.querySelector('[data-parler-palette-role="reference-target"]').getAttribute("stroke"), theme.palette.reference.target);
});

test("filtered positive pie slices use render-order slots and matching legend marks", () => {
  const { root, theme } = renderChart({
    kind: "pie",
    series: [{ name: "pie", x: ["zero", "first", "negative", "second"], y: [0, 3, -1, 7] }],
  });
  const slices = [...root.querySelectorAll("path[data-parler-series-slot]")];
  assert.deepEqual(slices.map((node) => node.getAttribute("data-parler-series-slot")), ["0", "1"]);
  assert.deepEqual(slices.map((node) => node.getAttribute("fill")), theme.palette.series.slice(0, 2));
  const legend = chartLegendItems(
    { kind: "pie", series: [{ name: "pie", x: ["zero", "first", "negative", "second"], y: [0, 3, -1, 7] }] },
    theme
  );
  assert.deepEqual(legend.map((item) => item.color), theme.palette.series.slice(0, 2));
  assert.deepEqual(legend.map((item) => item.label), ["first", "second"]);
});

test("bar and scatter marks consume geometry and outline roles", () => {
  const bar = renderChart({
    kind: "bar",
    series: [{ name: "bars", x: ["a", "b"], y: [2, 4] }],
  });
  const barMark = bar.root.querySelector("rect.bar-s0");
  assert.equal(barMark.getAttribute("rx"), String(bar.theme.style.barRadius));
  assert.equal(barMark.getAttribute("stroke"), bar.theme.palette.pointOutline);
  assert.ok(bar.root.querySelectorAll('[data-parler-palette-role="grid"]').length > 0);

  const scatter = renderChart({
    kind: "scatter",
    series: [{ name: "points", x: [0, 1], y: [2, 4] }],
  });
  const point = scatter.root.querySelector("circle.pt-s0");
  assert.equal(point.getAttribute("r"), String(scatter.theme.style.scatterPointRadius));
  assert.equal(point.getAttribute("stroke-width"), String(scatter.theme.style.outlineWidth));
});

test("portable print rewrites every chart kind while preserving validated presentation", () => {
  const charts = [
    {
      kind: "line",
      title: "line",
      x_label: "x",
      y_label: "y",
      series: [{ name: "line", x: [0, 1], y: [2, 4] }],
      y_reference_lines: [{ y: 3, role: "target", label: "goal" }],
    },
    {
      kind: "scatter",
      title: "scatter",
      x_label: "x",
      y_label: "y",
      series: [{ name: "scatter", x: [0, 1], y: [2, 4] }],
      y_reference_lines: [{ y: 3, role: "warning", label: "watch" }],
    },
    {
      kind: "bar",
      title: "bar",
      x_label: "x",
      y_label: "y",
      series: [{ name: "bar", x: ["a", "b"], y: [2, 4] }],
      y_reference_lines: [{ y: 3, role: "limit", label: "limit" }],
    },
    {
      kind: "pie",
      title: "pie",
      series: [{ name: "pie", x: ["a", "b"], y: [2, 4] }],
    },
  ];

  for (const chart of charts) {
    const { root } = renderChart(chart);
    const clone = root.cloneNode(true);
    const geometry = [...clone.querySelectorAll("*")].map((node) =>
      ["viewBox", "stroke-width", "stroke-opacity", "stroke-dasharray", "r", "rx", "font-family", "font-size", "font-weight"]
        .map((name) => node.getAttribute(name))
    );
    const liveSvg = root.innerHTML;
    rewriteClonedChartPaletteForPrint(clone, DEFAULT_PORTABLE_PRINT_THEME);

    assert.equal(root.innerHTML, liveSvg, `${chart.kind} live SVG must remain screen-themed`);
    assert.deepEqual(
      [...clone.querySelectorAll("*")].map((node) =>
        ["viewBox", "stroke-width", "stroke-opacity", "stroke-dasharray", "r", "rx", "font-family", "font-size", "font-weight"]
          .map((name) => node.getAttribute(name))
      ),
      geometry,
      `${chart.kind} print rewriting must preserve presentation geometry`
    );

    for (const node of clone.querySelectorAll("[data-parler-palette-role]")) {
      const roles = node.getAttribute("data-parler-palette-role").split(/\s+/);
      if (roles.includes("series")) {
        const slot = Number(node.getAttribute("data-parler-series-slot"));
        if (node.hasAttribute("fill") && node.getAttribute("fill") !== "none") {
          assert.equal(node.getAttribute("fill"), DEFAULT_PORTABLE_PRINT_THEME.palette.series[slot]);
        }
        if (node.hasAttribute("stroke") && !roles.includes("point-outline")) {
          assert.equal(node.getAttribute("stroke"), DEFAULT_PORTABLE_PRINT_THEME.palette.series[slot]);
        }
      }
      if (roles.includes("point-outline")) {
        assert.equal(node.getAttribute("stroke"), DEFAULT_PORTABLE_PRINT_THEME.palette.outline);
      }
    }
  }
});

test("chart drawing source has no owned presentation color literals", () => {
  const source = fs.readFileSync("components/chart-draw.js", "utf8");
  assert.doesNotMatch(source, /#[0-9a-f]{3,8}/i);
  assert.doesNotMatch(source, /sliceColorByLabel|SERIES_COLORS/);
  assert.match(source, /data-parler-palette-role/);
  assert.match(source, /appendYGrid/);
});

test("chart host observes the concrete refresh attributes and skips unchanged Theme signatures", () => {
  const source = fs.readFileSync("components/parler-ui-chart.js", "utf8");
  // §4.6: the observer watches the card and redraws only when the available width changes.
  assert.match(source, /new ResizeObserver\(\(\) => \{[\s\S]*?this\._draw\(true\);[\s\S]*?\}\)/);
  assert.match(source, /this\._ro\.observe\(this\._cardRef\.value \?\? el\)/);
  assert.match(source, /if \(width === this\._availableWidth\) return;/);
  assert.match(source, /new MutationObserver\(\(\) => this\._draw\(false\)\)/);
  assert.match(source, /attributeFilter: \["class", "style", "theme-mode"\]/);
  assert.match(source, /signature === this\._themeSignature/);
  assert.match(source, /drawChart\(el, this\.chart, theme, this\.#view\(\), \{/);
});
