/**
 * D3 chart drawing — ChartBlock wire (CONTRACTS/CHART_CONTRACT.md)
 * @typedef {import('../lib/types.js').ChartBlock} ChartBlock
 */

import { axisBottom, axisLeft } from "d3-axis";
import { extent, max, min } from "d3-array";
import { scaleBand, scaleLinear, scaleTime } from "d3-scale";
import { select } from "d3-selection";
import { arc as d3Arc, line as d3Line, pie as d3Pie } from "d3-shape";
import {
  chartSeriesSlot,
  normalizeCategoryKey,
  resolveSeriesSlot,
  DEFAULT_CHART_RENDER_THEME,
} from "./chart-theme.js";
import { chartSeriesRejection, rejectChart, chartHistogramRejection, chartBoxplotRejection, chartHeatmapRejection, CHART_REJECTION_CODES } from "../lib/wireAdapter.js";
import { boxplotOutlierText, NO_DATA } from "../lib/chartDataNotes.js";
import { heatBarStops, heatPaint, heatPosition, heatScale } from "../lib/chartHeatColor.js";
import { createChartHitModel } from "../lib/chartHitModel.js";
import {
  displayTimeZoneLabel,
  fitTimeTicks,
  formatFullLocalTime,
} from "../lib/chartTimeFormat.js";
import { chartPlotSize } from "../lib/chartSizePolicy.js";
import {
  clampXRange,
  clampXSelection,
  formatElapsedTick,
  formatNormalizedTick,
  lineScatterXs,
  parseElapsedDomain,
  parseNormalizedDomain,
  parseRequestedTimeRange,
} from "../lib/chartXDomain.js";

/**
 * Renderer defense in depth (CHART_CONTRACT §3.3 inv. 3, §4.2): the whole chart is refused,
 * never partially drawn, when any series fails the shared shape / finite-number Y rule.
 * The live/history adapter already enforces the same rule; this guards direct callers.
 * @param {ChartBlock} chart
 * @returns {NonNullable<ChartBlock["series"]> | null}
 */
function drawableSeries(chart) {
  const code = chartSeriesRejection(chart?.series);
  if (code) {
    rejectChart(code, chart);
    return null;
  }
  return /** @type {NonNullable<ChartBlock["series"]>} */ (chart.series);
}

/**
 * Bar X-axis tick: show categorical labels as-is (truncated if long); ISO datetimes with a time
 * component use the `HH:mm:ss` slice so short names like "A" or "Shift 1" are not mangled.
 * @param {string | undefined} xStr
 */
function formatBarCategoryLabel(xStr) {
  if (xStr == null) return "";
  const s = String(xStr).trim();
  if (!s) return "";
  if (/^\d{4}-\d{2}-\d{2}T/.test(s) && !Number.isNaN(Date.parse(s))) {
    const time = s.slice(11, 19);
    if (time.length >= 8 && /^\d{2}:\d{2}:\d{2}$/.test(time)) return time;
  }
  return s.length > 22 ? `${s.slice(0, 20)}…` : s;
}

/**
 * Horizontal bar layout (chart-enhancement design §7.3, C2a-1). Everything below is pure so the
 * frozen budget can be asserted without a DOM.
 */

/** Band paddings shared by both bar orientations. */
const BAR_OUTER_PADDING = 0.2;
const BAR_INNER_PADDING = 0.08;
/** Target thickness of one sub-bar in logical px; the budget guarantees at least this. */
const HORIZONTAL_BAR_TARGET_THICKNESS = 6;
/** Breathing space kept between adjacent category label blocks. */
const HORIZONTAL_LABEL_GAP = 4;
/** Vertical margins of every cartesian plot. */
const PLOT_MARGIN_TOP = 20;
const PLOT_MARGIN_BOTTOM = 44;
/** Default plot height and the inner height it leaves. */
const DEFAULT_PLOT_HEIGHT = 240;
const MIN_HORIZONTAL_INNER_HEIGHT = DEFAULT_PLOT_HEIGHT - PLOT_MARGIN_TOP - PLOT_MARGIN_BOTTOM;
/** Conservative average glyph width for the supported UI font stacks, as a fraction of font size. */
/** Maximum label lines per category; longer names are ellipsized on the last line. */
const MAX_CATEGORY_LABEL_LINES = 2;

/** Line height used for wrapped category labels. @param {number} tickSize */
export function categoryLabelLineHeight(tickSize) {
  return Math.ceil(1.3 * tickSize);
}

/**
 * Text width estimate by glyph class, in px, used only where no document can measure text (for
 * example JSDOM). It is deliberately generous for the supported UI font stacks: CJK and
 * full-width glyphs 1em; `M` and `W` 1em; other capitals and `m`/`w` 0.8em; digits 0.65em; other
 * lower-case 0.6em; narrow glyphs and spaces 0.35em. It is a documented fallback, not a proof of
 * fit; the measured path below is used whenever a rendering document exists.
 * @param {string} text @param {number} fontSize
 */
export function estimateTextWidth(text, fontSize) {
  let em = 0;
  for (const ch of String(text ?? "")) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp >= 0x2e80 || (cp >= 0xff01 && cp <= 0xff60)) em += 1;
    else if (ch === "M" || ch === "W") em += 1;
    else if (/[A-Z@#%&mw]/.test(ch)) em += 0.8;
    else if (/[0-9]/.test(ch)) em += 0.65;
    else if (/[iljtfrI.,:;'|!()\[\]{}\- ]/.test(ch)) em += 0.35;
    else em += 0.6;
  }
  return em * fontSize;
}

/**
 * A text measurer using the chart's resolved tick font: an SVG probe text measured with
 * `getComputedTextLength()` whenever a rendering document is available, else the glyph-class
 * estimate. The probe is mounted in `el` when it is connected, otherwise in the document's body
 * so a detached drawing root (the print builder) still measures with the browser's real font;
 * only a document that cannot render text falls back to the estimate. Results are cached per
 * string and the probe is removed by `dispose()` or on every fallback path.
 * @param {Element | null | undefined} el @param {typeof DEFAULT_CHART_RENDER_THEME} theme
 * @param {Document} [document] document to probe when `el` is detached; defaults to `el`'s
 * @returns {((text: string) => number) & { dispose?: () => void }}
 */
export function createTextMeasurer(el, theme = DEFAULT_CHART_RENDER_THEME, document = undefined) {
  const fontSize = theme.style.type.tickSize;
  const estimate = (text) => estimateTextWidth(text, fontSize);
  const doc = document ?? el?.ownerDocument;
  const mount = el?.isConnected ? el : doc?.body ?? doc?.documentElement ?? null;
  if (!doc || !mount) return estimate;
  const svg = doc.createElementNS(SVG_NS, "svg");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.style.position = "absolute";
  svg.style.visibility = "hidden";
  const text = doc.createElementNS(SVG_NS, "text");
  text.setAttribute("font-family", theme.style.fontFamily);
  text.setAttribute("font-size", String(fontSize));
  svg.append(text);
  mount.append(svg);
  try {
    if (typeof text.getComputedTextLength !== "function") {
      svg.remove();
      return estimate;
    }
    text.textContent = "Wm";
    const probe = text.getComputedTextLength();
    if (!Number.isFinite(probe) || probe <= 0) {
      svg.remove();
      return estimate;
    }
  } catch {
    svg.remove();
    return estimate;
  }
  /** @type {Map<string, number>} */
  const cache = new Map();
  const measure = (raw) => {
    const value = String(raw ?? "");
    const hit = cache.get(value);
    if (hit !== undefined) return hit;
    text.textContent = value;
    const width = text.getComputedTextLength();
    const out = Number.isFinite(width) && width >= 0 ? width : estimate(value);
    cache.set(value, out);
    return out;
  };
  measure.dispose = () => svg.remove();
  return measure;
}

/** Longest prefix of `text` that, followed by an ellipsis, measures at most `maxWidth`. */
function fitWithEllipsis(text, maxWidth, measure) {
  if (measure(text) <= maxWidth) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measure(`${text.slice(0, mid).trimEnd()}…`) <= maxWidth) lo = mid;
    else hi = mid - 1;
  }
  return `${text.slice(0, lo).trimEnd()}…`;
}

/** Longest prefix of `text` measuring at most `maxWidth` (at least one glyph). */
function fitPrefix(text, maxWidth, measure) {
  let lo = 1;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measure(text.slice(0, mid)) <= maxWidth) lo = mid;
    else hi = mid - 1;
  }
  return text.slice(0, Math.max(1, lo));
}

/**
 * Wrap a category label into at most two lines whose measured width fits `maxWidth`, breaking at
 * spaces, `-`, `_` and `.` (the separator stays with the preceding token) and filling each line
 * greedily; a token wider than a line is cut by measurement. Text left after the second line is
 * ellipsized to fit. The full source text is never lost: it stays in the tooltip, keyboard query
 * and data notes. `measure(text)` returns the rendered width in px.
 * @param {string} text @param {number} maxWidth @param {(text: string) => number} measure
 * @returns {string[]}
 */
export function wrapCategoryLabel(text, maxWidth, measure) {
  const s = String(text ?? "").trim();
  if (!s) return [""];
  if (measure(s) <= maxWidth) return [s];
  const tokens = s.match(/[^\s\-_.]+[\-_.]*\s*|[\-_.]+\s*|\s+/g) ?? [s];
  /** @type {string[]} */
  const lines = [];
  let line = "";
  let index = 0;
  while (index < tokens.length && lines.length < MAX_CATEGORY_LABEL_LINES - 1) {
    const token = tokens[index];
    const candidate = `${line}${token}`;
    if (measure(candidate.trimEnd()) <= maxWidth) {
      line = candidate;
      index += 1;
      continue;
    }
    if (!line.trim()) {
      // A single token wider than the line: cut it by measurement and keep the rest.
      const head = fitPrefix(token.trimEnd(), maxWidth, measure);
      lines.push(head);
      tokens[index] = token.slice(head.length);
      line = "";
      continue;
    }
    lines.push(line.trimEnd());
    line = "";
  }
  const rest = `${line}${tokens.slice(index).join("")}`.trim();
  lines.push(fitWithEllipsis(rest, maxWidth, measure));
  return lines.filter((l, i) => l !== "" || i === 0);
}

/**
 * Category gutter (left margin) for horizontal bars: the widest fitted label line plus the axis
 * space, clamped to `[48, min(160, 0.35 × viewBoxW)]`. Labels are fitted by measured width
 * against the clamp's upper bound first, so the line count is known before the step is chosen
 * and no rendered line can exceed the gutter.
 * @param {string[]} labels @param {number} viewBoxW @param {typeof DEFAULT_CHART_RENDER_THEME} theme
 * @param {(text: string) => number} [measure] text measurer; defaults to the glyph-class estimate
 * @returns {{ gutter: number, lines: string[][], maxLines: number, maxLineWidth: number, axisSpace: number }}
 */
export function horizontalBarCategoryGutter(labels, viewBoxW, theme = DEFAULT_CHART_RENDER_THEME, measure) {
  const tickSize = theme.style.type.tickSize;
  const m = measure ?? ((text) => estimateTextWidth(text, tickSize));
  const axisSpace = theme.style.axis.tickLength + theme.style.axis.tickPadding + 4;
  const upper = Math.min(160, 0.35 * viewBoxW);
  const maxWidth = Math.max(8, upper - axisSpace);
  const lines = labels.map((label) => wrapCategoryLabel(label, maxWidth, m));
  const maxLineWidth = lines.reduce((w, ls) => Math.max(w, ...ls.map((l) => m(l))), 0);
  const gutter = Math.max(48, Math.min(upper, Math.ceil(maxLineWidth + axisSpace)));
  const maxLines = lines.reduce((mx, ls) => Math.max(mx, ls.length), 1);
  return { gutter, lines, maxLines, maxLineWidth, axisSpace };
}

/**
 * Height budget for horizontal bars (§7.3): the category step is the larger of the padded
 * sub-bar requirement and the label block, the inner height follows from the step with the D3
 * band formula and never drops below the default plot's inner height, and the total adds the
 * fixed vertical margins. The SVG is never capped; the card scrolls it.
 * @param {{ categories: number, seriesCount: number, labelLines: number, tickSize: number }} input
 * @returns {{ barStep: number, labelStep: number, step: number, innerH: number, h: number, lineHeight: number }}
 */
export function horizontalBarHeightBudget({ categories, seriesCount, labelLines, tickSize }) {
  const n = Math.max(1, categories);
  const S = Math.max(1, seriesCount);
  const L = Math.max(1, Math.min(MAX_CATEGORY_LABEL_LINES, labelLines));
  const lineHeight = categoryLabelLineHeight(tickSize);
  const barStep = Math.ceil(
    (HORIZONTAL_BAR_TARGET_THICKNESS * (S + BAR_INNER_PADDING)) / (1 - BAR_INNER_PADDING) / (1 - BAR_OUTER_PADDING)
  );
  const labelStep = L * lineHeight + HORIZONTAL_LABEL_GAP;
  const step = Math.max(barStep, labelStep);
  const innerH = Math.max(MIN_HORIZONTAL_INNER_HEIGHT, Math.ceil(step * (n + BAR_OUTER_PADDING)));
  return { barStep, labelStep, step, innerH, h: innerH + PLOT_MARGIN_TOP + PLOT_MARGIN_BOTTOM, lineHeight };
}

/**
 * Horizontal counterpart of {@link barRectGeometry}: every bar starts at the zero baseline
 * `xScale(0)` and extends toward the value, so `x = min(xScale(v), x0)` and
 * `width = abs(xScale(v) − x0)` (zero-width for `v = 0`).
 * @param {import('d3-scale').ScaleLinear<number, number>} xScale @param {number} value
 */
export function barRectGeometryHorizontal(xScale, value) {
  const x0 = xScale(0);
  const xv = xScale(value);
  return { x: Math.min(xv, x0), width: Math.abs(xv - x0) };
}

const SVG_NS = "http://www.w3.org/2000/svg";

/** Below this logical width the plot geometry degenerates; the SVG scales instead. */
const MIN_PLOT_LOGICAL_WIDTH = 240;

/**
 * Logical (viewBox) width of the plot. It equals the measured host width so SVG text renders at
 * its configured CSS-pixel size (1 logical px = 1 CSS px); the card title and legend live outside
 * the SVG and never share this scaling. Only hosts narrower than {@link MIN_PLOT_LOGICAL_WIDTH}
 * scale the SVG down. Unmeasurable hosts (not laid out yet) fall back to 640.
 *
 * @param {HTMLElement} el
 * @returns {number}
 */
export function responsiveChartViewBoxWidth(el) {
  const rectWidth = Number(el?.getBoundingClientRect?.().width);
  const clientWidth = Number(el?.clientWidth);
  const measured = rectWidth > 0 ? rectWidth : clientWidth;
  if (!Number.isFinite(measured) || measured <= 0) return 640;
  return Math.round(Math.max(MIN_PLOT_LOGICAL_WIDTH, measured));
}

/**
 * Add useful axis detail as charts grow instead of stretching the narrow-layout five-tick
 * presentation across an arbitrarily wide host.
 *
 * @param {number} logicalWidth
 * @returns {number}
 */
export function responsiveChartTickCount(logicalWidth) {
  const width = Number(logicalWidth);
  if (!Number.isFinite(width) || width <= 0) return 5;
  return Math.max(5, Math.min(10, Math.round(width / 128)));
}

/**
 * Raw (pre-`nice`) Y domain for signed `bar` charts (chart-enhancement design §5.1).
 * The domain always spans zero so bars grow from a shared zero baseline, includes every
 * Y reference line, and never degenerates: all-zero input with no non-zero reference line
 * yields `[-1, 1]`. Non-finite inputs are ignored (input validation is a separate slice).
 *
 * @param {unknown[]} values plotted bar values across all drawn series
 * @param {unknown[]} [referenceYs]
 * @returns {[number, number]}
 */
export function signedBarYDomain(values, referenceYs = []) {
  const all = [...values, ...referenceYs].map(Number).filter(Number.isFinite);
  const lo = Math.min(0, ...all);
  const hi = Math.max(0, ...all);
  if (lo === 0 && hi === 0) return [-1, 1];
  return [lo, hi];
}

/**
 * Raw (pre-`nice`) Y domain shared by the left-margin estimate, grid, reference lines and marks.
 * `bar` uses {@link signedBarYDomain}; `line` / `scatter` keep the data ∪ reference-line extent.
 *
 * @param {unknown[]} values plotted Y values across all drawn series
 * @param {unknown[]} referenceYs
 * @param {string} kind
 * @returns {[number, number]}
 */
export function chartYDomain(values, referenceYs, kind) {
  if (kind === "bar") return signedBarYDomain(values, referenceYs);
  const ys = values.map(Number).filter(Number.isFinite);
  const refs = referenceYs.map(Number).filter(Number.isFinite);
  const lo = min([min(ys) ?? 0, ...refs]) ?? 0;
  const hi = max([max(ys) ?? 1, ...refs]) ?? 1;
  return [lo, hi];
}

/**
 * Bar rectangle geometry from a shared zero baseline: `y = min(yScale(v), yScale(0))`,
 * `height = |yScale(v) - yScale(0)|`. Positive, negative, mixed and zero values use this
 * one rule, so height is never negative and a zero value draws a zero-height rectangle.
 *
 * @param {import('d3-scale').ScaleLinear<number, number>} yScale
 * @param {number} value
 * @returns {{ y: number, height: number }}
 */
export function barRectGeometry(yScale, value) {
  const y0 = yScale(0);
  const yv = yScale(value);
  return { y: Math.min(yv, y0), height: Math.abs(yv - y0) };
}

/**
 * Reserve only the space the y-axis labels are likely to need. The estimate uses D3's own
 * tick formatter and the configured tick size on the same raw Y domain the plot draws with
 * (see {@link chartYDomain}), then clamps the result so unusually long values cannot consume
 * the plot. Pie charts have no y axis and use the ordinary edge gutter.
 *
 * @param {[number, number] | null} yDomain raw Y domain shared with the plot; `null` for pie
 * @param {string} kind
 * @param {number} tickCount
 * @param {typeof DEFAULT_CHART_RENDER_THEME} [theme]
 * @returns {number}
 */
export function responsiveChartLeftMargin(
  yDomain,
  kind,
  tickCount,
  theme = DEFAULT_CHART_RENDER_THEME
) {
  if (kind === "pie" || !yDomain) return 24;
  const scale = scaleLinear().domain(yDomain).nice();
  const format = scale.tickFormat(tickCount);
  const longest = scale.ticks(tickCount).reduce(
    (length, value) => Math.max(length, String(format(value)).length),
    1
  );
  // 0.62em is a conservative average glyph width for the supported UI font stacks.
  const labelWidth = longest * theme.style.type.tickSize * 0.62;
  const axisSpace = theme.style.axis.tickLength + theme.style.axis.tickPadding + 4;
  return Math.max(32, Math.min(64, Math.ceil(labelWidth + axisSpace)));
}

/** @param {ChartBlock} chart */
function refYsFromChart(chart) {
  const lines = chart.y_reference_lines;
  if (!lines?.length) return [];
  return lines.map((r) => r.y).filter((n) => Number.isFinite(n));
}

/** @param {string | undefined} role @param {typeof DEFAULT_CHART_RENDER_THEME} theme */
function refLineStyle(role, theme) {
  const { palette, style } = theme;
  switch (role) {
    case "usl":
    case "lsl":
      return {
        paletteRole: "reference-danger",
        stroke: palette.reference.danger,
        dash: style.reference.limitDash,
        width: style.reference.lineWidth,
      };
    case "ucl":
    case "lcl":
      return {
        paletteRole: "reference-warning",
        stroke: palette.reference.warning,
        dash: style.reference.controlDash,
        width: style.reference.lineWidth,
      };
    case "target":
      return {
        paletteRole: "reference-target",
        stroke: palette.reference.target,
        dash: style.reference.targetDash,
        width: style.reference.targetLineWidth,
      };
    case "warning":
      return {
        paletteRole: "reference-warning",
        stroke: palette.reference.warning,
        dash: style.reference.warningDash,
        width: style.reference.lineWidth,
      };
    case "limit":
    default:
      return {
        paletteRole: "reference-danger",
        stroke: palette.reference.danger,
        dash: style.reference.limitDash,
        width: style.reference.lineWidth,
      };
  }
}

/**
 * @param {import('d3-selection').Selection<SVGGElement, unknown, null, undefined>} g
 * @param {import('d3-scale').ScaleLinear<number, number>} yScale
 * @param {number} innerW
 * @param {NonNullable<ChartBlock['y_reference_lines']> | undefined} refs
 * @param {typeof DEFAULT_CHART_RENDER_THEME} theme
 */
function appendYReferenceLines(g, yScale, innerW, refs, theme) {
  if (!refs?.length) return;
  const layer = g.append("g").attr("class", "y-ref-lines");
  for (const refLine of refs) {
    const yy = yScale(refLine.y);
    if (!Number.isFinite(yy)) continue;
    const st = refLineStyle(refLine.role, theme);
    layer
      .append("line")
      .attr("x1", 0)
      .attr("x2", innerW)
      .attr("y1", yy)
      .attr("y2", yy)
      .attr("stroke", st.stroke)
      .attr("stroke-width", st.width)
      .attr("stroke-opacity", theme.style.reference.opacity)
      .attr("stroke-dasharray", st.dash === "none" ? null : st.dash)
      .attr("data-parler-palette-role", st.paletteRole);
    const lbl = refLine.label?.trim();
    if (lbl) {
      layer
        .append("text")
        .attr("x", innerW - 4)
        .attr("y", yy - 5)
        .attr("text-anchor", "end")
        .attr("fill", st.stroke)
        .attr("font-family", theme.style.fontFamily)
        .attr("font-size", theme.style.type.referenceLabelSize)
        .attr("data-parler-palette-role", st.paletteRole)
        .text(lbl);
    }
  }
}

function appendYGrid(g, yScale, innerW, tickCount, theme) {
  g.append("g")
    .attr("class", "chart-grid")
    .selectAll("line")
    .data(yScale.ticks(tickCount))
    .join("line")
    .attr("x1", 0)
    .attr("x2", innerW)
    .attr("y1", (value) => yScale(value))
    .attr("y2", (value) => yScale(value))
    .attr("stroke", theme.palette.grid)
    .attr("stroke-width", theme.style.gridLineWidth)
    .attr("stroke-opacity", theme.style.gridOpacity)
    .attr("data-parler-palette-role", "grid");
}

/**
 * Zero baseline for signed `bar` charts, drawn at `yScale(0)` inside the plot only when the raw
 * Y domain extends below zero. For all-non-negative data the category axis at the plot bottom
 * already paints the origin, so no second stroke is added there (positive-only charts keep
 * exactly one bottom stroke). Uses the `axis` palette color and line width and the `axis`
 * palette role so answer printing recolors it with the axes.
 *
 * @param {[number, number]} yDomainRaw
 */
function appendZeroBaseline(g, yScale, innerW, yDomainRaw, theme) {
  if (!(yDomainRaw[0] < 0)) return;
  const y0 = yScale(0);
  if (!Number.isFinite(y0)) return;
  g.append("line")
    .attr("class", "zero-baseline")
    .attr("x1", 0)
    .attr("x2", innerW)
    .attr("y1", y0)
    .attr("y2", y0)
    .attr("stroke", theme.palette.axis)
    .attr("stroke-width", theme.style.axisLineWidth)
    .attr("data-parler-palette-role", "axis");
}

/** Vertical grid for horizontal bars: one line per value tick across the plot height. */
function appendXGrid(g, xScale, innerH, tickCount, theme) {
  g.append("g")
    .attr("class", "chart-grid")
    .selectAll("line")
    .data(xScale.ticks(tickCount))
    .join("line")
    .attr("x1", (value) => xScale(value))
    .attr("x2", (value) => xScale(value))
    .attr("y1", 0)
    .attr("y2", innerH)
    .attr("stroke", theme.palette.grid)
    .attr("stroke-width", theme.style.gridLineWidth)
    .attr("stroke-opacity", theme.style.gridOpacity)
    .attr("data-parler-palette-role", "grid");
}

/** Zero baseline for horizontal signed bars: a vertical line at `xScale(0)`, same rule as {@link appendZeroBaseline}. */
function appendZeroBaselineVertical(g, xScale, innerH, domainRaw, theme) {
  if (!(domainRaw[0] < 0)) return;
  const x0 = xScale(0);
  if (!Number.isFinite(x0)) return;
  g.append("line")
    .attr("class", "zero-baseline")
    .attr("x1", x0)
    .attr("x2", x0)
    .attr("y1", 0)
    .attr("y2", innerH)
    .attr("stroke", theme.palette.axis)
    .attr("stroke-width", theme.style.axisLineWidth)
    .attr("data-parler-palette-role", "axis");
}

/** Value-axis reference lines for horizontal bars: full-height vertical segments with labels at the top. */
function appendXReferenceLines(g, xScale, innerH, refs, theme) {
  if (!refs?.length) return;
  const layer = g.append("g").attr("class", "y-ref-lines");
  for (const refLine of refs) {
    const xx = xScale(refLine.y);
    if (!Number.isFinite(xx)) continue;
    const st = refLineStyle(refLine.role, theme);
    layer
      .append("line")
      .attr("x1", xx)
      .attr("x2", xx)
      .attr("y1", 0)
      .attr("y2", innerH)
      .attr("stroke", st.stroke)
      .attr("stroke-width", st.width)
      .attr("stroke-opacity", theme.style.reference.opacity)
      .attr("stroke-dasharray", st.dash === "none" ? null : st.dash)
      .attr("data-parler-palette-role", st.paletteRole);
    const lbl = refLine.label?.trim();
    if (lbl) {
      layer
        .append("text")
        .attr("x", xx + 4)
        .attr("y", theme.style.type.referenceLabelSize)
        .attr("text-anchor", "start")
        .attr("fill", st.stroke)
        .attr("font-family", theme.style.fontFamily)
        .attr("font-size", theme.style.type.referenceLabelSize)
        .attr("data-parler-palette-role", st.paletteRole)
        .text(lbl);
    }
  }
}

function styleAxis(group, theme) {
  group
    .selectAll(".domain, .tick line")
    .attr("stroke", theme.palette.axis)
    .attr("stroke-width", theme.style.axisLineWidth)
    .attr("data-parler-palette-role", "axis");
  group
    .selectAll(".tick text")
    .attr("fill", theme.palette.text.tick)
    .attr("font-family", theme.style.fontFamily)
    .attr("font-size", theme.style.type.tickSize)
    .attr("data-parler-palette-role", "tick");
  return group;
}

/**
 * @typedef {object} ChartLegendItem
 * @property {string} label full label text; wrapping belongs to the card layout, never to the data
 * @property {number} slot ordered palette slot shared with the plot marks (modulo 24)
 * @property {string} color resolved series color for that slot
 * @property {"line" | "circle" | "rect"} mark legend mark shape matching the plot mark
 */

/**
 * Legend entries for the card's DOM legend (chart-enhancement design §4.1/§4.4). One entry per
 * drawn series for line/scatter/bar, one per positive slice for pie, in render order so slots
 * match the plot marks exactly. Single-entry legends are omitted, as before.
 *
 * @param {ChartBlock} chart
 * @param {typeof DEFAULT_CHART_RENDER_THEME} [theme]
 * @returns {ChartLegendItem[]}
 */
export function chartLegendItems(chart, theme = DEFAULT_CHART_RENDER_THEME, colorKeys = null) {
  if (!chart || chartSeriesRejection(chart.series)) return [];
  const seriesList = /** @type {NonNullable<ChartBlock["series"]>} */ (chart.series);
  const { palette } = theme;
  if (chart.kind === "pie") {
    const s = seriesList[0];
    /** @type {ChartLegendItem[]} */
    const items = [];
    for (let i = 0; i < s.x.length; i++) {
      const val = Number(s.y[i]);
      if (!Number.isFinite(val) || val <= 0) continue;
      const label = String(s.x[i] ?? "").trim() || `Slice ${i + 1}`;
      const slot = resolveSeriesSlot(s.x[i], items.length, colorKeys);
      items.push({ label, slot, color: palette.series[slot], mark: "rect", category: normalizeCategoryKey(s.x[i]) });
    }
    return items.length > 1 ? items : [];
  }
  if (seriesList.length <= 1) return [];
  const mark = chart.kind === "scatter" ? "circle" : chart.kind === "bar" ? "rect" : "line";
  return seriesList.map((s, i) => {
    const slot = resolveSeriesSlot(s.name, i, colorKeys);
    const label = String(s.name ?? `Series ${i + 1}`).trim() || `Series ${i + 1}`;
    return { label, slot, color: palette.series[slot], mark, category: normalizeCategoryKey(s.name) };
  });
}

/** Tooltip caption for an axis: the chart's structured label, else a neutral fallback. */
function axisCaption(label, fallback) {
  const text = typeof label === "string" ? label.trim() : "";
  return text || fallback;
}

/** Reference-line hit items for horizontal bars (cx set; the hit model supplies cy from the current bar). */
function referenceHitItemsHorizontal(chart, xScale, innerH, yCaption) {
  const lines = chart.y_reference_lines ?? [];
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  const out = [];
  lines.forEach((ref, i) => {
    const cx = xScale(ref.y);
    if (!Number.isFinite(cx)) return;
    const name = (ref.label ?? "").trim() || (ref.role ? `${ref.role} reference` : "Reference line");
    out.push({ type: "reference", series: -1, index: i, cx, cy: innerH / 2, lines: [name, `${yCaption}: ${ref.y}`] });
  });
  return out;
}

/** Reference-line hit items (cy only; the hit model supplies cx from the current point). */
function referenceHitItems(chart, yScale, yCaption) {
  const lines = chart.y_reference_lines ?? [];
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  const out = [];
  lines.forEach((ref, i) => {
    const cy = yScale(ref.y);
    if (!Number.isFinite(cy)) return;
    const name = (ref.label ?? "").trim() || (ref.role ? `${ref.role} reference` : "Reference line");
    out.push({ type: "reference", series: -1, index: i, cx: 0, cy, lines: [name, `${yCaption}: ${ref.y}`] });
  });
  return out;
}

/** Opacity of pie slices other than the focused one (design §4.3: focus highlights, never removes). */
const UNFOCUSED_SLICE_OPACITY = 0.35;

/** Outer width `W` for the size policy: the caller's available width, its legacy alias, else the measured host. */
function outerWidthFor(el, options) {
  const outerRaw = Number(options?.availableWidth ?? options?.viewBoxWidth);
  return Number.isFinite(outerRaw) && outerRaw > 0
    ? Math.round(Math.max(MIN_PLOT_LOGICAL_WIDTH, outerRaw))
    : responsiveChartViewBoxWidth(el);
}

/**
 * Histogram (chart-enhancement design §7.4 C2b-1): one rectangle per bin between its real edges on a
 * linear numeric X (unequal widths stay unequal), height from `counts` or `densities` as `mode` says,
 * full width and 240 high, no legend. The adapter has already validated the payload; nothing is
 * re-binned here. One hit item per bin reports the interval (last bin closed), count and density.
 */
function drawHistogram(el, chart, theme, options) {
  const reason = chartHistogramRejection(chart.histogram);
  if (reason) {
    rejectChart(CHART_REJECTION_CODES.HISTOGRAM_INVALID, chart, reason);
    return null;
  }
  const h = chart.histogram;
  const edges = h.edges;
  const values = h.mode === "density" ? h.densities : h.counts;
  const bins = h.counts.length;
  const xCaption = axisCaption(chart.x_label, "Value");
  const yCaption = h.mode === "density" ? "Density" : "Count";
  const outerW = outerWidthFor(el, options);
  const outerTickCount = responsiveChartTickCount(outerW);
  const yDomainRaw = chartYDomain(values, [], "bar");
  const margin = {
    top: PLOT_MARGIN_TOP,
    right: 20,
    bottom: PLOT_MARGIN_BOTTOM,
    left: responsiveChartLeftMargin(yDomainRaw, "bar", outerTickCount, theme),
  };
  const plotSize = chartPlotSize({ kind: "histogram", availableWidth: outerW, context: options?.context, availableHeight: options?.availableHeight });
  const viewBoxW = plotSize.w;
  const plotH = plotSize.h ?? DEFAULT_PLOT_HEIGHT;
  const tickCount = responsiveChartTickCount(viewBoxW);
  const svg = select(el)
    .append("svg")
    .attr("viewBox", `0 0 ${viewBoxW} ${plotH}`)
    .attr("width", "100%")
    .style("max-width", "100%")
    .style("height", "auto")
    .style("display", "block")
    .attr("preserveAspectRatio", "xMidYMid meet")
    .attr("role", "img");
  const g = svg.append("g").attr("transform", `translate(${margin.left},${margin.top})`);
  const innerW = viewBoxW - margin.left - margin.right;
  const innerH = plotH - margin.top - margin.bottom;
  /** @type {import('../lib/chartHitModel.js').ChartHitLayout} */
  const layout = { viewBoxW, viewBoxH: plotH, marginLeft: margin.left, marginTop: margin.top, innerW, innerH };
  const xScale = scaleLinear().domain([edges[0], edges[bins]]).range([0, innerW]);
  const yScale = scaleLinear().domain(/** @type {[number, number]} */ (yDomainRaw)).nice().range([innerH, 0]);
  appendYGrid(g, yScale, innerW, tickCount, theme);
  const slot = chartSeriesSlot(0);
  const color = theme.palette.series[slot];
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  const items = [];
  for (let i = 0; i < bins; i++) {
    const x0 = xScale(edges[i]);
    const x1 = xScale(edges[i + 1]);
    const geom = barRectGeometry(yScale, values[i]);
    const width = Math.max(0, x1 - x0);
    const closed = i === bins - 1;
    items.push({
      type: "bar",
      series: 0,
      index: i,
      cx: x0 + width / 2,
      cy: geom.y,
      x: x0,
      width,
      lines: [
        `${xCaption}: [${String(edges[i])}, ${String(edges[i + 1])}${closed ? "]" : ")"}`,
        `Count: ${String(h.counts[i])}`,
        `Density: ${String(h.densities[i])}`,
      ],
    });
    g.append("rect")
      .attr("class", "bar-s0")
      .attr("data-bin", i)
      .attr("x", x0)
      .attr("y", geom.y)
      .attr("width", width)
      .attr("height", geom.height)
      .attr("fill", color)
      .attr("stroke", theme.palette.pointOutline)
      .attr("stroke-width", theme.style.outlineWidth)
      .attr("data-parler-palette-role", "series point-outline")
      .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
  }
  appendZeroBaseline(g, yScale, innerW, /** @type {[number, number]} */ (yDomainRaw), theme);
  const measure = typeof options?.measureText === "function" ? options.measureText : createTextMeasurer(el, theme);
  const xTicks = fitLinearAxisLabels(xScale, tickCount, -margin.left, innerW + margin.right, measure);
  const xAxisGroup = g.append("g")
    .attr("transform", `translate(0,${innerH})`)
    .call(axisBottom(xScale).tickValues(xTicks.values).tickFormat(xTicks.format)
      .tickSize(theme.style.axis.tickLength).tickPadding(theme.style.axis.tickPadding));
  styleAxis(xAxisGroup, theme);
  // The anchors are the ones the fit was judged with; they are applied, not recomputed.
  xAxisGroup.selectAll(".tick text").each(function (_d, k) {
    if (xTicks.anchors[k] && xTicks.anchors[k] !== "middle") select(this).style("text-anchor", xTicks.anchors[k]);
  });
  if (typeof options?.measureText !== "function" && typeof measure.dispose === "function") measure.dispose();
  const yAxisGroup = g.append("g").call(
    axisLeft(yScale).ticks(tickCount).tickSize(theme.style.axis.tickLength).tickPadding(theme.style.axis.tickPadding)
  );
  styleAxis(yAxisGroup, theme);
  svg
    .append("text")
    .attr("x", viewBoxW / 2)
    .attr("y", plotH - 8)
    .attr("text-anchor", "middle")
    .attr("fill", theme.palette.text.axisLabel)
    .attr("font-family", theme.style.fontFamily)
    .attr("font-size", theme.style.type.axisLabelSize)
    .attr("data-parler-palette-role", "axis-label")
    .text(`${chart.x_label ?? ""}${chart.x_label ? " · " : ""}${yCaption}`);
  return {
    hit: createChartHitModel({ kind: "histogram", series: [items], references: [], layout }),
    hiddenSeries: [],
    plotSize,
    histogramMode: h.mode,
  };
}

/** Cells at least this large carry their value as text (design §7.4). */
const HEAT_VALUE_TEXT_MIN_W = 40;
const HEAT_VALUE_TEXT_MIN_H = 24;
const LINEAR_TICK_LABEL_GAP = 8;

/**
 * Tick values, format and text anchors for a horizontal linear axis whose labels must stay inside
 * `[minX, maxX]` (plot coordinates) and clear of each other. A large base with a small range (1,000,000 to
 * 1,000,002) gives labels wider than the tick spacing of a narrow card.
 *
 * Every candidate is judged on the geometry that will actually be drawn: the scale's own tick positions (d3
 * ticks rarely sit on the domain ends), the measured width of each formatted label, and the anchor each label
 * ends up with (an end label that would leave the SVG is anchored inward, which moves it towards its
 * neighbour). Nothing is decided first and moved afterwards. Candidates run from `count` down to one tick, so the
 * search is bounded; each count keeps d3's own format, so neighbouring values stay distinct and nothing is
 * abbreviated. When not even one tick fits, the axis shows none rather than a clipped one.
 *
 * @returns {{ values: number[], format: (v: number) => string, anchors: ("start"|"middle"|"end")[] }}
 */
export function fitLinearAxisLabels(scale, count, minX, maxX, measure) {
  const place = (values, format) => {
    const boxes = values.map((value) => {
      const x = scale(value);
      const width = measure(format(value));
      let anchor = "middle";
      if (x - width / 2 < minX) anchor = "start";
      else if (x + width / 2 > maxX) anchor = "end";
      const left = anchor === "start" ? x : anchor === "end" ? x - width : x - width / 2;
      return { anchor, left, right: left + width };
    });
    const inside = boxes.every((b) => b.left >= minX && b.right <= maxX);
    const apart = boxes.every((b, k) => k === 0 || b.left - boxes[k - 1].right >= LINEAR_TICK_LABEL_GAP);
    return inside && apart ? boxes.map((b) => b.anchor) : null;
  };
  for (let c = Math.max(1, count); c >= 1; c--) {
    const values = scale.ticks(c);
    if (!values.length) continue;
    const format = scale.tickFormat(c);
    const anchors = place(values, format);
    if (anchors) return { values, format, anchors };
    if (values.length > 1 && c === 1) {
      // d3 may still return several ticks for a count of one: fall back to the one nearest the plot centre.
      const centre = (minX + maxX) / 2;
      const nearest = [...values].sort((a, b) => Math.abs(scale(a) - centre) - Math.abs(scale(b) - centre))[0];
      const single = place([nearest], format);
      if (single) return { values: [nearest], format, anchors: single };
    }
  }
  return { values: [], format: scale.tickFormat(1), anchors: [] };
}

/** Rotated column labels need about this many px of axis run each, in tick-size units. */
const HEAT_ROTATED_LABEL_RUN = 1.25;
const HEAT_LABEL_ROTATION = -35;
/** Category tick labels of vertical bar and boxplot charts are drawn at this angle, anchored at their end. */
const CATEGORY_LABEL_ROTATION = -35;
const CATEGORY_AXIS_MAX_BOTTOM = 160;
const AXIS_CAPTION_GAP = 6;
const AXIS_CAPTION_BOTTOM_INSET = 8;

/**
 * Bottom margin for a category axis whose tick labels are rotated. The labels hang below the axis by
 * `widest × sin(angle)` plus one line, so a fixed margin lets every label longer than a few characters run into
 * the axis caption or out of the SVG. The margin is measured instead: tick, rotated run, a gap, the caption line
 * and the inset below it. The rule is vertical only: it does not count on a label happening to sit to the left of
 * the centred caption. Never below {@link PLOT_MARGIN_BOTTOM}.
 *
 * @param {string[]} labels formatted tick labels, exactly as drawn
 * @param {typeof DEFAULT_CHART_RENDER_THEME} theme
 * @param {(text: string) => number} measure
 * @returns {{ bottom: number, extra: number }} `extra` is what the chart height grows by, so the plot area keeps its size
 */
/** A rotated label needs this much band step, in tick-size units, so neighbours do not overprint (glyph height / sin 35°). */
const CATEGORY_LABEL_MIN_STEP_EM = 1.75;
const ELLIPSIS = "…";

/**
 * Longest prefix of `text` plus an ellipsis that measures at most `maxWidth`; `text` itself when it fits. The
 * limit is pixels, not characters: CJK and wide glyphs, or a larger tick font, make a 21-character label far
 * wider than the same count of Latin letters.
 */
export function elideToWidth(text, maxWidth, measure) {
  const full = String(text ?? "");
  if (!full || measure(full) <= maxWidth) return full;
  const chars = [...(full.endsWith(ELLIPSIS) ? full.slice(0, -1) : full)];
  let lo = 0;
  let hi = chars.length;
  while (lo < hi) {
    const mid = Math.ceil((lo + hi) / 2);
    if (measure(chars.slice(0, mid).join("") + ELLIPSIS) <= maxWidth) lo = mid;
    else hi = mid - 1;
  }
  return lo > 0 ? chars.slice(0, lo).join("") + ELLIPSIS : ELLIPSIS;
}

/** Tick centres of `scaleBand().range([0, innerW]).padding(BAR_OUTER_PADDING)`, and its step. */
function categoryBandCentres(n, innerW) {
  const step = innerW / (n + BAR_OUTER_PADDING);
  return { step, centre: (i) => step * (i + 0.5 + BAR_OUTER_PADDING / 2) };
}

/**
 * Whole layout of a rotated category axis (vertical bar, boxplot), in one bounded computation that reads label
 * text and band geometry only, never a drawn axis:
 *
 * 1. every label is elided to what the capped bottom margin can hold;
 * 2. when the band step is too small for one label per band, only every k-th label is drawn (all ticks, bars and
 *    query points stay; the full names remain in tooltips, keyboard query and View data);
 * 3. the left margin grows for a label that would leave the SVG, at most to 40% of the width, in at most two passes;
 * 4. what still does not fit on the left is elided to the room it really has;
 * 5. the bottom margin is measured from the labels finally drawn.
 *
 * The text that is measured is the text that is drawn.
 *
 * @param {{ labels: string[], theme: typeof DEFAULT_CHART_RENDER_THEME, measure: (t: string) => number,
 *           marginLeft: number, marginRight: number, availableWidth: number,
 *           sizeFor: (marginLeft: number) => { w: number } }} input
 */
export function layoutRotatedCategoryAxis(input) {
  const { labels, theme, measure, marginRight, availableWidth, sizeFor } = input;
  const n = Math.max(1, labels.length);
  const tickSize = theme.style.type.tickSize;
  const angle = (-CATEGORY_LABEL_ROTATION * Math.PI) / 180;
  const fixedBottom = theme.style.axis.tickLength + theme.style.axis.tickPadding + tickSize * Math.cos(angle)
    + AXIS_CAPTION_GAP + theme.style.type.axisLabelSize + AXIS_CAPTION_BOTTOM_INSET;
  const maxWidthByBottom = Math.max(0, (CATEGORY_AXIS_MAX_BOTTOM - fixedBottom - 1) / Math.sin(angle));
  const fitted = labels.map((label) => elideToWidth(label, maxWidthByBottom, measure));
  const shownFor = (step) => new Set(heatColumnLabelIndexes(labels.length, Math.max(1, Math.ceil((tickSize * CATEGORY_LABEL_MIN_STEP_EM) / Math.max(step, 1e-6)))));
  let marginLeft = input.marginLeft;
  let plotSize = sizeFor(marginLeft);
  for (let pass = 0; pass < 2; pass++) {
    const { step } = categoryBandCentres(n, plotSize.w - marginLeft - marginRight);
    const shown = shownFor(step);
    const left = rotatedCategoryAxisLeft(fitted.map((label, i) => (shown.has(i) ? label : "")), measure, marginLeft, step, availableWidth);
    if (left === marginLeft) break;
    marginLeft = left;
    plotSize = sizeFor(left);
  }
  const bands = categoryBandCentres(n, plotSize.w - marginLeft - marginRight);
  const shown = shownFor(bands.step);
  const drawn = fitted.map((label, i) => {
    if (!shown.has(i)) return "";
    const room = (marginLeft + bands.centre(i) - 2) / Math.cos(angle);
    return elideToWidth(label, Math.max(0, room), measure);
  });
  return { labels: drawn, marginLeft, plotSize, ...rotatedCategoryAxisBottom(drawn, theme, measure) };
}

/**
 * Left margin a rotated category axis needs. An end-anchored label runs `width × cos(angle)` to the left of its
 * tick, so with few categories a long first label leaves the SVG on the left. Label `i` sits at about
 * `(i + 0.5) × step` from the plot's left edge; the margin grows only by what is missing, and never past 40% of
 * the available width.
 *
 * @param {string[]} labels formatted tick labels, exactly as drawn
 * @param {(text: string) => number} measure
 * @param {number} marginLeft the margin the value axis already needs
 * @param {number} step band step in px
 * @param {number} availableWidth
 */
export function rotatedCategoryAxisLeft(labels, measure, marginLeft, step, availableWidth) {
  const cos = Math.cos((-CATEGORY_LABEL_ROTATION * Math.PI) / 180);
  let needed = marginLeft;
  labels.forEach((label, i) => {
    if (!label) return;
    needed = Math.max(needed, Math.ceil(measure(String(label)) * cos - step * (i + 0.5 + BAR_OUTER_PADDING / 2) + 4));
  });
  return Math.min(needed, Math.max(marginLeft, Math.floor(availableWidth * 0.4)));
}

export function rotatedCategoryAxisBottom(labels, theme, measure) {
  const tickSize = theme.style.type.tickSize;
  const widest = labels.reduce((m, label) => Math.max(m, measure(String(label))), 0);
  const angle = (-CATEGORY_LABEL_ROTATION * Math.PI) / 180;
  // Lowest point of an end-anchored rotated label: its run along the baseline plus the glyph height, both projected.
  const rotatedRun = Math.ceil(widest * Math.sin(angle) + tickSize * Math.cos(angle));
  const needed =
    theme.style.axis.tickLength + theme.style.axis.tickPadding + rotatedRun +
    AXIS_CAPTION_GAP + theme.style.type.axisLabelSize + AXIS_CAPTION_BOTTOM_INSET;
  const bottom = Math.max(PLOT_MARGIN_BOTTOM, Math.min(CATEGORY_AXIS_MAX_BOTTOM, Math.ceil(needed)));
  return { bottom, extra: bottom - PLOT_MARGIN_BOTTOM };
}
let heatPatternSequence = 0;

/** Short cell text: integers as they are, other values to three significant digits. */
function heatCellText(v) {
  if (Number.isInteger(v)) return String(v);
  return String(Number(v.toPrecision(3)));
}

/**
 * Column label indexes to draw: all when every label fits its cell run; otherwise every `step`-th
 * label with the first and last always kept (the neighbour that would collide with the last is dropped).
 * @param {number} count @param {number} step
 */
export function heatColumnLabelIndexes(count, step) {
  if (step <= 1) return Array.from({ length: count }, (_v, i) => i);
  const out = [];
  for (let i = 0; i < count; i += step) out.push(i);
  const last = count - 1;
  if (out[out.length - 1] !== last) {
    if (last - out[out.length - 1] < step) out.pop();
    out.push(last);
  }
  return out;
}

/**
 * Heatmap (chart-enhancement design §7.4 C2b-3): rows on the left (the horizontal-bar category gutter,
 * wrapped and clamped), columns below, one rectangle per cell sized by the §7.4 clamp for the context
 * (the card and the expand layer keep the 20 px floor and scroll; print fits every column in the page
 * width), the colour from {@link heatScale} / {@link heatPaint}, missing cells hatched in the grid
 * colour and read as "No data", cell values as text when the cell is at least 40 × 24, and no legend
 * list: the caller appends {@link appendHeatLegend}. Nothing is regrouped or aggregated here. Hit items:
 * one per cell, rows as hit series so up/down moves between rows and left/right along a row.
 */
function drawHeatmap(el, chart, theme, options) {
  const reason = chartHeatmapRejection(chart.heatmap);
  if (reason) {
    rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, chart, reason);
    return null;
  }
  const h = chart.heatmap;
  const rows = h.rows;
  const cols = h.cols;
  const scale = heatScale(h.values);
  const rowCaption = axisCaption(chart.y_label, "Row");
  const colCaption = axisCaption(chart.x_label, "Column");
  const valueCaption = axisCaption(h.valueLabel, "Value");
  const context = options?.context === "expand" || options?.context === "print" ? options.context : "card";
  const outerW = outerWidthFor(el, options);
  const injected = typeof options?.measureText === "function";
  const measure = injected ? options.measureText : createTextMeasurer(el, theme);
  const tickSize = theme.style.type.tickSize;
  const lineHeight = categoryLabelLineHeight(tickSize);
  const gutter = horizontalBarCategoryGutter(rows.map(String), outerW, theme, measure);
  const axisSpace = gutter.axisSpace;
  const marginLeft = gutter.gutter;
  const marginRight = 20;
  const first = chartPlotSize({ kind: "heatmap", rows: rows.length, cols: cols.length, marginLeft, marginRight, marginTop: 0, marginBottom: 0, availableWidth: outerW, context, minCellHeight: lineHeight });
  const cellW = first.cell.w;
  const cellH = first.cell.h;
  const colWidths = cols.map((c) => measure(String(c)));
  const widest = colWidths.reduce((m, w) => Math.max(m, w), 0);
  const horizontalLabels = widest <= cellW - 4;
  const step = horizontalLabels ? 1 : Math.max(1, Math.ceil((tickSize * HEAT_ROTATED_LABEL_RUN) / cellW));
  const labelIndexes = heatColumnLabelIndexes(cols.length, step);
  const rotatedRun = horizontalLabels ? lineHeight : Math.ceil(widest * Math.sin((-HEAT_LABEL_ROTATION * Math.PI) / 180) + lineHeight);
  const marginBottom = Math.max(PLOT_MARGIN_BOTTOM, Math.min(140, axisSpace + rotatedRun + 20));
  const plotSize = chartPlotSize({ kind: "heatmap", rows: rows.length, cols: cols.length, marginLeft, marginRight, marginTop: PLOT_MARGIN_TOP, marginBottom, availableWidth: outerW, context, minCellHeight: lineHeight });
  const viewBoxW = plotSize.w;
  const plotH = plotSize.h;
  const innerW = cols.length * cellW;
  const innerH = rows.length * cellH;
  const svg = select(el)
    .append("svg")
    .attr("viewBox", `0 0 ${viewBoxW} ${plotH}`)
    .attr("width", "100%")
    .style("max-width", "100%")
    .style("height", "auto")
    .style("display", "block")
    .attr("preserveAspectRatio", "xMidYMid meet")
    .attr("role", "img");
  heatPatternSequence += 1;
  const patternId = `parler-heat-missing-${heatPatternSequence}`;
  const defs = svg.append("defs");
  const pattern = defs.append("pattern").attr("id", patternId).attr("patternUnits", "userSpaceOnUse").attr("width", 6).attr("height", 6)
    .attr("patternTransform", "rotate(45)");
  pattern.append("line").attr("x1", 0).attr("y1", 0).attr("x2", 0).attr("y2", 6).attr("stroke", theme.palette.grid).attr("stroke-width", 1)
    .attr("data-parler-palette-role", "grid");
  const g = svg.append("g").attr("transform", `translate(${marginLeft},${PLOT_MARGIN_TOP})`);
  /** @type {import('../lib/chartHitModel.js').ChartHitLayout} */
  const layout = { viewBoxW, viewBoxH: plotH, marginLeft, marginTop: PLOT_MARGIN_TOP, innerW, innerH };
  const showValues = cellW >= HEAT_VALUE_TEXT_MIN_W && cellH >= HEAT_VALUE_TEXT_MIN_H;
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[][]} */
  const hitSeries = rows.map(() => []);
  const cells = g.append("g").attr("class", "heat-cells");
  rows.forEach((rk, r) => {
    cols.forEach((ck, c) => {
      const v = h.values[r][c];
      const x = c * cellW;
      const y = r * cellH;
      const rect = cells.append("rect").attr("class", "heat-cell").attr("data-row", r).attr("data-col", c)
        .attr("x", x).attr("y", y).attr("width", cellW).attr("height", cellH)
        .attr("stroke", theme.palette.grid).attr("stroke-width", 0.5).attr("stroke-opacity", theme.style.gridOpacity);
      if (v === null) {
        rect.attr("fill", `url(#${patternId})`).attr("data-missing", "true");
      } else {
        const t = heatPosition(v, scale);
        const paint = heatPaint(t, scale.kind, theme.palette);
        rect.attr("fill", paint.fill).attr("fill-opacity", paint.opacity)
          .attr("data-heat-t", String(t)).attr("data-heat-scale", scale.kind).attr("data-parler-palette-role", "heat");
        if (showValues) {
          cells.append("text").attr("class", "heat-cell-value").attr("x", x + cellW / 2).attr("y", y + cellH / 2)
            .attr("text-anchor", "middle").attr("dominant-baseline", "central")
            .attr("fill", theme.palette.text.tick).attr("font-family", theme.style.fontFamily).attr("font-size", tickSize)
            .attr("data-parler-palette-role", "tick").text(heatCellText(v));
        }
      }
      hitSeries[r].push({
        type: "cell",
        series: r,
        index: c,
        cx: x + cellW / 2,
        cy: y + cellH / 2,
        x,
        y,
        width: cellW,
        height: cellH,
        lines: [`${rowCaption}: ${String(rk)}`, `${colCaption}: ${String(ck)}`, `${valueCaption}: ${v === null ? NO_DATA : String(v)}`],
      });
    });
  });
  // Row labels: the gutter's wrapped lines, one line when the cell is too short for two.
  const rowLabels = g.append("g").attr("class", "heat-row-labels");
  const twoLines = cellH >= 2 * lineHeight;
  const maxLabelWidth = Math.max(8, marginLeft - axisSpace);
  rows.forEach((rk, r) => {
    const lines = twoLines ? gutter.lines[r] : [fitWithEllipsis(String(rk).trim(), maxLabelWidth, measure)];
    const text = rowLabels.append("text").attr("x", -axisSpace + theme.style.axis.tickLength + 0).attr("y", r * cellH + cellH / 2)
      .attr("text-anchor", "end").attr("dominant-baseline", "central")
      .attr("fill", theme.palette.text.tick).attr("font-family", theme.style.fontFamily).attr("font-size", tickSize)
      .attr("data-parler-palette-role", "tick");
    const offset = -((lines.length - 1) * lineHeight) / 2;
    lines.forEach((line, i) => {
      text.append("tspan").attr("x", -(theme.style.axis.tickPadding + 4)).attr("dy", i === 0 ? offset : lineHeight).text(line);
    });
  });
  const colLabels = g.append("g").attr("class", "heat-col-labels");
  for (const c of labelIndexes) {
    const x = c * cellW + cellW / 2;
    const y = innerH + theme.style.axis.tickLength + theme.style.axis.tickPadding;
    const text = colLabels.append("text").attr("data-col", c)
      .attr("fill", theme.palette.text.tick).attr("font-family", theme.style.fontFamily).attr("font-size", tickSize)
      .attr("data-parler-palette-role", "tick").text(String(cols[c]));
    if (horizontalLabels) {
      text.attr("x", x).attr("y", y).attr("text-anchor", "middle").attr("dominant-baseline", "hanging");
    } else {
      text.attr("x", x).attr("y", y).attr("text-anchor", "end").attr("transform", `rotate(${HEAT_LABEL_ROTATION} ${x} ${y})`);
    }
  }
  g.append("line").attr("class", "heat-axis-left").attr("x1", 0).attr("x2", 0).attr("y1", 0).attr("y2", innerH)
    .attr("stroke", theme.palette.axis).attr("stroke-width", theme.style.axisLineWidth).attr("data-parler-palette-role", "axis");
  g.append("line").attr("class", "heat-axis-bottom").attr("x1", 0).attr("x2", innerW).attr("y1", innerH).attr("y2", innerH)
    .attr("stroke", theme.palette.axis).attr("stroke-width", theme.style.axisLineWidth).attr("data-parler-palette-role", "axis");
  svg
    .append("text")
    .attr("x", marginLeft + innerW / 2)
    .attr("y", plotH - 8)
    .attr("text-anchor", "middle")
    .attr("fill", theme.palette.text.axisLabel)
    .attr("font-family", theme.style.fontFamily)
    .attr("font-size", theme.style.type.axisLabelSize)
    .attr("data-parler-palette-role", "axis-label")
    .text(`${colCaption} (columns) · ${rowCaption} (rows)`);
  if (!injected && typeof measure.dispose === "function") measure.dispose();
  return {
    kind: "heatmap",
    hit: createChartHitModel({ kind: "heatmap", series: hitSeries, references: [], layout }),
    hiddenSeries: [],
    plotSize,
    heat: { scale, valueLabel: valueCaption, rowCaption, colCaption, missingCount: h.missingCount, labelIndexes, showValues },
  };
}

/**
 * The heatmap legend (design §7.4): a colour bar with its min / max (and 0 when the scale crosses zero)
 * ticks, the value label, and a hatched "No data" sample. Built as one SVG from the same colour rule as
 * the cells so screen, expand and print agree; stops carry `data-heat-t` for the print rewriter.
 * @param {Element} container emptied and filled
 * @param {{ scale: import('../lib/chartHeatColor.js').HeatScale, valueLabel: string }} heat
 * @param {typeof DEFAULT_CHART_RENDER_THEME} theme
 * @param {Document} [doc]
 */
export function appendHeatLegend(container, heat, theme, doc = container.ownerDocument) {
  const width = 320;
  const height = 48;
  const barX = 0;
  const barY = 18;
  const barW = 200;
  const barH = 12;
  while (container.firstChild) container.removeChild(container.firstChild);
  const svg = select(container).append(() => doc.createElementNS("http://www.w3.org/2000/svg", "svg"))
    .attr("class", "chart-heat-legend-svg").attr("viewBox", `0 0 ${width} ${height}`).attr("width", width).attr("height", height)
    .attr("role", "img").attr("aria-label", `${heat.valueLabel}: colour scale from ${String(heat.scale.min)} to ${String(heat.scale.max)}; hatched cells have no data`);
  heatPatternSequence += 1;
  const gradientId = `parler-heat-bar-${heatPatternSequence}`;
  const patternId = `parler-heat-legend-missing-${heatPatternSequence}`;
  const defs = svg.append("defs");
  const gradient = defs.append("linearGradient").attr("id", gradientId).attr("x1", "0").attr("x2", "1").attr("y1", "0").attr("y2", "0");
  for (const stop of heatBarStops(heat.scale.kind)) {
    const paint = heatPaint(stop.t, heat.scale.kind, theme.palette);
    gradient.append("stop").attr("offset", String(stop.offset)).attr("stop-color", paint.fill).attr("stop-opacity", String(paint.opacity))
      .attr("data-heat-t", String(stop.t)).attr("data-heat-scale", heat.scale.kind).attr("data-parler-palette-role", "heat");
  }
  const pattern = defs.append("pattern").attr("id", patternId).attr("patternUnits", "userSpaceOnUse").attr("width", 6).attr("height", 6).attr("patternTransform", "rotate(45)");
  pattern.append("line").attr("x1", 0).attr("y1", 0).attr("x2", 0).attr("y2", 6).attr("stroke", theme.palette.grid).attr("stroke-width", 1).attr("data-parler-palette-role", "grid");
  const label = (x, y, text, anchor) => svg.append("text").attr("x", x).attr("y", y).attr("text-anchor", anchor)
    .attr("fill", theme.palette.text.legend).attr("font-family", theme.style.fontFamily).attr("font-size", theme.style.type.legendSize)
    .attr("data-parler-palette-role", "legend").text(text);
  label(barX, barY - 6, heat.valueLabel, "start");
  svg.append("rect").attr("class", "chart-heat-bar").attr("x", barX).attr("y", barY).attr("width", barW).attr("height", barH)
    .attr("fill", `url(#${gradientId})`).attr("stroke", theme.palette.grid).attr("stroke-width", 0.5).attr("data-parler-palette-role", "grid");
  const ticks = heat.scale.ticks;
  ticks.forEach((t, i) => {
    const pos = heat.scale.kind === "diverging"
      ? barX + (barW * (heatPosition(t, heat.scale) + 1)) / 2
      : barX + barW * (ticks.length === 1 ? 0.5 : i / (ticks.length - 1));
    const anchor = ticks.length === 1 ? "middle" : i === 0 ? "start" : i === ticks.length - 1 ? "end" : "middle";
    label(pos, barY + barH + 12, String(t), anchor).attr("class", "chart-heat-tick").attr("data-parler-palette-role", "tick").attr("fill", theme.palette.text.tick).attr("font-size", theme.style.type.tickSize);
  });
  svg.append("rect").attr("class", "chart-heat-missing-sample").attr("x", barX + barW + 24).attr("y", barY).attr("width", 18).attr("height", barH)
    .attr("fill", `url(#${patternId})`).attr("stroke", theme.palette.grid).attr("stroke-width", 0.5).attr("data-parler-palette-role", "grid");
  label(barX + barW + 46, barY + barH - 2, NO_DATA, "start");
  return svg.node();
}

/** Whisker cap width as a fraction of the box width. */
const BOX_WHISKER_CAP = 0.5;
/** Outlier marker radius (logical px); drawn hollow so overlapping outliers stay countable. */
const BOX_OUTLIER_RADIUS = 3;

/**
 * Boxplot (chart-enhancement design §7.4 C2b-2): one band per group in source order (vertical, §4.6 band
 * cap with one series slot), the value axis over every `min` / `max`, listed outlier and reference line
 * through `chartYDomain`; box `q1–q3`, median line, whiskers to `whiskerLow` / `whiskerHigh` with caps,
 * hollow outlier markers, reference lines as on bar charts, no legend. The adapter has already validated
 * the payload; no statistic is recomputed here. Hit items: one per group's box (n and the five numbers,
 * whiskers and the outlier summary) in series 0, one per listed outlier in series 1 (group order, then
 * value), plus the reference lines.
 */
function drawBoxplot(el, chart, theme, options) {
  const reason = chartBoxplotRejection(chart.boxplot);
  if (reason) {
    rejectChart(CHART_REJECTION_CODES.BOXPLOT_INVALID, chart, reason);
    return null;
  }
  const groups = chart.boxplot.groups;
  const n = groups.length;
  const xCaption = axisCaption(chart.x_label, "Group");
  const yCaption = axisCaption(chart.y_label, "Value");
  const refYs = refYsFromChart(chart);
  const extentValues = groups.flatMap((g) => [g.min, g.max, ...g.outliers]);
  const yDomainRaw = chartYDomain(extentValues, refYs, "boxplot");
  const outerW = outerWidthFor(el, options);
  const outerTickCount = responsiveChartTickCount(outerW);
  const measure = typeof options?.measureText === "function" ? options.measureText : createTextMeasurer(el, theme);
  const margin = {
    top: PLOT_MARGIN_TOP,
    right: 20,
    bottom: PLOT_MARGIN_BOTTOM,
    left: responsiveChartLeftMargin(yDomainRaw, "boxplot", outerTickCount, theme),
  };
  const categoryAxis = layoutRotatedCategoryAxis({
    labels: groups.map((group, i) => formatBarCategoryLabel(String(group?.key ?? i))),
    theme,
    measure,
    marginLeft: margin.left,
    marginRight: margin.right,
    availableWidth: outerW,
    sizeFor: (marginLeft) => chartPlotSize({
      kind: "boxplot",
      categories: n,
      seriesCount: 1,
      marginLeft,
      marginRight: margin.right,
      availableWidth: outerW,
      context: options?.context,
      availableHeight: options?.availableHeight,
    }),
  });
  if (typeof options?.measureText !== "function" && typeof measure.dispose === "function") measure.dispose();
  margin.left = categoryAxis.marginLeft;
  margin.bottom = categoryAxis.bottom;
  const groupLabels = categoryAxis.labels;
  const plotSize = categoryAxis.plotSize;
  const viewBoxW = plotSize.w;
  // The rotated labels get their own room below the plot; the plot area keeps the height it had.
  const plotH = (plotSize.h ?? DEFAULT_PLOT_HEIGHT) + categoryAxis.extra;
  const tickCount = responsiveChartTickCount(viewBoxW);
  const svg = select(el)
    .append("svg")
    .attr("viewBox", `0 0 ${viewBoxW} ${plotH}`)
    .attr("width", "100%")
    .style("max-width", "100%")
    .style("height", "auto")
    .style("display", "block")
    .attr("preserveAspectRatio", "xMidYMid meet")
    .attr("role", "img");
  const g = svg.append("g").attr("transform", `translate(${margin.left},${margin.top})`);
  const innerW = viewBoxW - margin.left - margin.right;
  const innerH = plotH - margin.top - margin.bottom;
  /** @type {import('../lib/chartHitModel.js').ChartHitLayout} */
  const layout = { viewBoxW, viewBoxH: plotH, marginLeft: margin.left, marginTop: margin.top, innerW, innerH };
  const band = scaleBand().domain([...Array(n).keys()].map(String)).range([0, innerW]).padding(BAR_OUTER_PADDING);
  const yScale = scaleLinear().domain(/** @type {[number, number]} */ (yDomainRaw)).nice().range([innerH, 0]);
  layout.barBandwidth = band.bandwidth();
  appendYGrid(g, yScale, innerW, tickCount, theme);
  const slot = chartSeriesSlot(0);
  const color = theme.palette.series[slot];
  const outline = theme.palette.pointOutline;
  const strokeW = theme.style.outlineWidth;
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  const boxItems = [];
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  const outlierItems = [];
  groups.forEach((grp, i) => {
    const x0 = band(String(i)) ?? 0;
    const w = band.bandwidth();
    const cx = x0 + w / 2;
    const gg = g.append("g").attr("class", `box-g${i}`).attr("data-group", i);
    const capHalf = (w * BOX_WHISKER_CAP) / 2;
    const whisker = (from, to, cls) => {
      gg.append("line").attr("class", cls).attr("x1", cx).attr("x2", cx).attr("y1", yScale(from)).attr("y2", yScale(to))
        .attr("stroke", color).attr("stroke-width", strokeW).attr("data-parler-palette-role", "series").attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
      gg.append("line").attr("class", `${cls}-cap`).attr("x1", cx - capHalf).attr("x2", cx + capHalf).attr("y1", yScale(to)).attr("y2", yScale(to))
        .attr("stroke", color).attr("stroke-width", strokeW).attr("data-parler-palette-role", "series").attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
    };
    whisker(grp.q1, grp.whiskerLow, "box-whisker-low");
    whisker(grp.q3, grp.whiskerHigh, "box-whisker-high");
    const top = yScale(grp.q3);
    const bottom = yScale(grp.q1);
    gg.append("rect").attr("class", "box-body").attr("x", x0).attr("y", top).attr("width", w).attr("height", Math.max(0, bottom - top))
      .attr("fill", color).attr("fill-opacity", 0.55).attr("stroke", outline).attr("stroke-width", strokeW).attr("rx", theme.style.barRadius)
      .attr("data-parler-palette-role", "series point-outline").attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
    gg.append("line").attr("class", "box-median").attr("x1", x0).attr("x2", x0 + w).attr("y1", yScale(grp.median)).attr("y2", yScale(grp.median))
      .attr("stroke", outline).attr("stroke-width", strokeW * 2).attr("data-parler-palette-role", "point-outline");
    const key = String(grp.key);
    boxItems.push({
      type: "bar",
      series: 0,
      index: i,
      cx,
      cy: yScale(grp.median),
      x: x0,
      width: w,
      lines: [
        `${xCaption}: ${key}`,
        `n: ${String(grp.n)}${grp.excludedCount ? ` (excluded ${String(grp.excludedCount)})` : ""}`,
        `Min: ${String(grp.min)}`,
        `Q1: ${String(grp.q1)}`,
        `Median: ${String(grp.median)}`,
        `Q3: ${String(grp.q3)}`,
        `Max: ${String(grp.max)}`,
        `Whiskers: ${String(grp.whiskerLow)} – ${String(grp.whiskerHigh)}`,
        `Outliers: ${boxplotOutlierText(grp)}`,
      ],
    });
    const sortedOutliers = [...grp.outliers].sort((a, b) => a - b);
    sortedOutliers.forEach((v) => {
      const cy = yScale(v);
      gg.append("circle").attr("class", "box-outlier").attr("cx", cx).attr("cy", cy).attr("r", BOX_OUTLIER_RADIUS)
        .attr("fill", "none").attr("stroke", color).attr("stroke-width", strokeW).attr("data-parler-palette-role", "series").attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
      const hidden = grp.outlierCount - grp.outliers.length;
      outlierItems.push({
        type: "point",
        series: 1,
        index: outlierItems.length,
        cx,
        cy,
        x: x0,
        width: w,
        lines: [`${xCaption}: ${key}`, `Outlier: ${String(v)}`, `${yCaption}: ${String(v)}`, ...(hidden > 0 ? [`Another ${hidden} outlier${hidden === 1 ? "" : "s"} not shown`] : [])],
      });
    });
  });
  appendYReferenceLines(g, yScale, innerW, chart.y_reference_lines ?? undefined, theme);
  const hitReferences = referenceHitItems(chart, yScale, yCaption);
  const xAxisGroup = g.append("g")
    .attr("transform", `translate(0,${innerH})`)
    .call(
      axisBottom(band)
        .tickSize(theme.style.axis.tickLength)
        .tickPadding(theme.style.axis.tickPadding)
        .tickFormat((d) => groupLabels[Number(d)] ?? String(d))
    );
  styleAxis(xAxisGroup, theme).selectAll(".tick text")
    .attr("transform", `rotate(${CATEGORY_LABEL_ROTATION})`).style("text-anchor", "end");
  const yAxisGroup = g.append("g").call(
    axisLeft(yScale).ticks(tickCount).tickSize(theme.style.axis.tickLength).tickPadding(theme.style.axis.tickPadding)
  );
  styleAxis(yAxisGroup, theme);
  svg
    .append("text")
    .attr("x", viewBoxW / 2)
    .attr("y", plotH - 8)
    .attr("text-anchor", "middle")
    .attr("fill", theme.palette.text.axisLabel)
    .attr("font-family", theme.style.fontFamily)
    .attr("font-size", theme.style.type.axisLabelSize)
    .attr("data-parler-palette-role", "axis-label")
    .text(`${chart.x_label ?? ""}${chart.x_label && chart.y_label ? " · " : ""}${chart.y_label ?? ""}`);
  return {
    hit: createChartHitModel({ kind: "boxplot", series: [boxItems, outlierItems], references: hitReferences, layout }),
    hiddenSeries: [],
    plotSize,
    groupCount: n,
  };
}

/**
 * Stacked-bar totals (design §7.4 C2a-2). Per category: the sum of every emitted series (the percent
 * denominator, never recomputed when a series is hidden), and the positive / negative sums over
 * `domainSeries` (all series by default, the visible ones under the `visible` Y policy) that feed the
 * signed domain. Pure.
 * @param {{ y: number[] }[]} seriesList all emitted series @param {number} categories
 * @param {{ y: number[] }[]} domainSeries the series the Y domain is fitted to
 * @returns {{ totals: number[], domainValues: number[] }}
 */
export function barStackTotals(seriesList, categories, domainSeries) {
  const totals = [];
  const domainValues = [];
  for (let i = 0; i < categories; i++) {
    let total = 0;
    for (const s of seriesList) total += Number(s.y[i]);
    totals.push(total);
    let pos = 0;
    let neg = 0;
    for (const s of domainSeries) {
      const v = Number(s.y[i]);
      if (v >= 0) pos += v;
      else neg += v;
    }
    domainValues.push(pos, neg);
  }
  return { totals, domainValues };
}

/**
 * Stacked-bar segments per series (design §7.4 C2a-2): `stacked` accumulates positive values from zero
 * upwards and negative values from zero downwards; `percent` accumulates each series' share of the
 * category total from every emitted series. Hidden series draw no segment and the others close the
 * gap, but shares and denominators stay those of the full chart. A percent category whose total is
 * zero draws nothing and is reported as having no share. Pure.
 * @param {{ y: number[] }[]} seriesList @param {number} categories @param {Set<number>} hidden
 * @param {"stacked" | "percent"} stackMode @param {{ totals: number[] }} stacks
 * @returns {{ index: number, value: number, from: number, to: number, share: number | null, total: number, drawn: boolean }[][]}
 */
export function barStackSegments(seriesList, categories, hidden, stackMode, stacks) {
  const out = seriesList.map(() => []);
  for (let i = 0; i < categories; i++) {
    const total = stacks.totals[i];
    let up = 0;
    let down = 0;
    seriesList.forEach((s, si) => {
      const value = Number(s.y[i]);
      if (stackMode === "percent") {
        const share = total > 0 ? (100 * value) / total : null;
        if (hidden.has(si) || share === null) {
          out[si].push({ index: i, value, from: up, to: up, share, total, drawn: false });
          return;
        }
        out[si].push({ index: i, value, from: up, to: up + share, share, total, drawn: true });
        up += share;
        return;
      }
      if (hidden.has(si)) {
        out[si].push({ index: i, value, from: 0, to: 0, share: null, total, drawn: false });
        return;
      }
      if (value >= 0) {
        out[si].push({ index: i, value, from: up, to: up + value, share: null, total, drawn: true });
        up += value;
      } else {
        out[si].push({ index: i, value, from: down, to: down + value, share: null, total, drawn: true });
        down += value;
      }
    });
  }
  return out;
}

/** Tooltip lines after the value for a stacked segment: share (percent) and the category total. */
function stackTooltipLines(stackMode, seg, yCaption) {
  const lines = [];
  if (stackMode === "percent") {
    lines.push(seg.share === null ? "Share: none (category total is 0)" : `Share: ${seg.share.toFixed(1)}%`);
  }
  lines.push(`Category total (${yCaption}): ${String(seg.total)}`);
  return lines;
}

/**
 * Draw the plot into `el` and return the hit model for point query, or `null` when the chart
 * is not drawable (the shared validator has already logged why). `view` is the read-only view
 * state (design §4.3/§4.4): hidden series are not drawn but keep their palette slots; the Y
 * domain keeps every series and reference line unless the policy is `visible`; a focused pie
 * slice dims the others. Nothing in `view` changes the data or the hit model's identities.
 * @param {HTMLDivElement} el @param {ChartBlock} chart @param {typeof DEFAULT_CHART_RENDER_THEME} [theme]
 * @param {import('../lib/chartViewState.js').ChartViewState | null} [view]
 * @param {{ availableWidth?: number, viewBoxWidth?: number, context?: import('../lib/chartSizePolicy.js').ChartSizeContext, availableHeight?: number, measureText?: (text: string) => number }} [options]
 *   `availableWidth` is the outer width `W` the size policy decides from (design §4.6: the card's
 *   content width, never the plot host's own width); `viewBoxWidth` is its legacy alias; when
 *   neither is given the host is measured. `context` and `availableHeight` feed the policy;
 *   `measureText` overrides the category-label text measurer (tests and print builds)
 * For line/scatter, `view.viewXDomain` (numbers in the X domain: epoch ms, elapsed seconds,
 * normalized fraction or numeric value) replaces only the x scale's domain, clamped to the full
 * domain; the data layer is always clipped and the hit model only holds points inside the view.
 * `view.selectedRange` draws a translucent band. The Y domain never depends on the X view.
 * @returns {{ hit: ReturnType<typeof createChartHitModel>, hiddenSeries: number[], xDomain?: { mode: import('../lib/chartXDomain.js').ChartXMode, full: [number, number], view: [number, number] } } | null}
 */
export function drawChart(el, chart, theme = DEFAULT_CHART_RENDER_THEME, view = null, options = {}) {
  select(el).selectAll("svg").remove();
  /** C3b-2a (design §8.7): the group's shared category keys for this member, else null (per-chart slots). */
  const colorKeys = Array.isArray(options?.colorKeys) ? options.colorKeys : null;

  if (chart?.kind === "histogram") return drawHistogram(el, chart, theme, options);
  if (chart?.kind === "boxplot") return drawBoxplot(el, chart, theme, options);
  if (chart?.kind === "heatmap") return drawHeatmap(el, chart, theme, options);

  const seriesList = drawableSeries(chart);
  if (!seriesList) return null;
  const xCaption = axisCaption(chart.x_label, "X");
  const yCaption = axisCaption(chart.y_label, "Value");
  const hidden = new Set(
    (view?.hiddenSeriesKeys ?? []).filter((k) => Number.isInteger(k) && k >= 0 && k < seriesList.length)
  );
  const fitVisible = view?.yDomainPolicy === "visible";
  const focusedSlice = chart.kind === "pie" && Number.isInteger(view?.focusedSlice) ? view.focusedSlice : null;
  const domainSeries = seriesList.filter((_s, i) => !fitVisible || !hidden.has(i));

  const s0 = seriesList[0];
  /**
   * Logical width inside viewBox; it tracks the measured host so text keeps its CSS-pixel size
   * (see responsiveChartViewBoxWidth). The message/container layout owns physical width. Title
   * and legend are rendered by the card component outside this SVG.
   */
  /**
   * Outer width `W` (§4.6): the caller's available width, else the measured host. Margins are
   * estimated once from `W`'s tick count; the policy then fixes the plot width `w`, and drawing
   * uses `w`'s tick count without ever feeding the drawn margins back into `w`.
   */
  const outerRaw = Number(options?.availableWidth ?? options?.viewBoxWidth);
  const outerW =
    Number.isFinite(outerRaw) && outerRaw > 0
      ? Math.round(Math.max(MIN_PLOT_LOGICAL_WIDTH, outerRaw))
      : responsiveChartViewBoxWidth(el);
  const outerTickCount = responsiveChartTickCount(outerW);
  const refYs = refYsFromChart(chart);
  /** Grouped bar draws the shared category count = min length across series. */
  const barCategoryCount = seriesList.reduce(
    (m, s) => Math.min(m, s.x.length, s.y.length),
    s0.x.length
  );
  /** Stacked bars (design §7.4 C2a-2): `stacked` or `percent`; absent means grouped. */
  const stackMode = chart.kind === "bar" && (chart.stackMode === "stacked" || chart.stackMode === "percent") ? chart.stackMode : null;
  const stacks = stackMode ? barStackTotals(seriesList, barCategoryCount, domainSeries) : null;
  const plottedYs =
    chart.kind === "bar"
      ? stacks
        ? stacks.domainValues
        : domainSeries.flatMap((s) => s.y.slice(0, barCategoryCount).map((y) => Number(y)))
      : domainSeries.flatMap((s) => s.y.map((y) => Number(y)));
  /** One raw Y domain feeds margin, grid, reference lines and marks (design §5.1). */
  const yDomainRaw = chart.kind === "pie"
    ? null
    : stackMode === "percent"
      ? /** @type {[number, number]} */ ([0, 100])
      : chartYDomain(plottedYs, refYs, chart.kind);
  /** Horizontal bar (design §7.3): the category gutter and the height budget are fixed before drawing. */
  const horizontal = chart.kind === "bar" && chart.orientation === "horizontal";
  const categoryLabels = horizontal
    ? Array.from({ length: barCategoryCount }, (_v, i) => String(s0.x[i] ?? "").trim())
    : [];
  const injected = typeof options?.measureText === "function";
  const measure = horizontal ? (injected ? options.measureText : createTextMeasurer(el, theme)) : null;
  const gutter = horizontal ? horizontalBarCategoryGutter(categoryLabels, outerW, theme, measure) : null;
  if (measure && !injected && typeof measure.dispose === "function") measure.dispose();
  const budget = horizontal
    ? horizontalBarHeightBudget({
        categories: barCategoryCount,
        seriesCount: seriesList.length,
        labelLines: gutter.maxLines,
        tickSize: theme.style.type.tickSize,
      })
    : null;
  const verticalBar = chart.kind === "bar" && !horizontal;
  const margin =
    chart.kind === "pie"
      ? { top: 0, right: 0, bottom: 0, left: 0 }
      : {
          top: PLOT_MARGIN_TOP,
          right: 20,
          bottom: PLOT_MARGIN_BOTTOM,
          left: gutter ? gutter.gutter : responsiveChartLeftMargin(yDomainRaw, chart.kind, outerTickCount, theme),
        };
  const sizeFor = (marginLeft) => chartPlotSize({
    kind: chart.kind,
    orientation: horizontal ? "horizontal" : "vertical",
    categories: barCategoryCount,
    seriesCount: seriesList.length,
    marginLeft,
    marginRight: margin.right,
    availableWidth: outerW,
    context: options?.context,
    availableHeight: options?.availableHeight,
  });
  const categoryMeasure = verticalBar ? (injected ? options.measureText : createTextMeasurer(el, theme)) : null;
  const categoryAxis = verticalBar
    ? layoutRotatedCategoryAxis({
        // Exactly the bands that are drawn: series may differ in length and the shortest one decides.
        labels: (s0?.x ?? []).slice(0, barCategoryCount).map((raw) => formatBarCategoryLabel(raw)),
        theme,
        measure: categoryMeasure,
        marginLeft: margin.left,
        marginRight: margin.right,
        availableWidth: outerW,
        sizeFor,
      })
    : null;
  // The measurer mounts a hidden probe in the host; remove it as soon as the layout is known.
  if (categoryMeasure && !injected && typeof categoryMeasure.dispose === "function") categoryMeasure.dispose();
  if (categoryAxis) {
    margin.left = categoryAxis.marginLeft;
    margin.bottom = categoryAxis.bottom;
  }
  const plotSize = categoryAxis ? categoryAxis.plotSize : sizeFor(margin.left);
  const viewBoxW = plotSize.w;
  // A vertical bar's rotated labels get their own room below the plot; the plot area keeps its height.
  const h = (budget ? budget.h : plotSize.h ?? DEFAULT_PLOT_HEIGHT) + (categoryAxis ? categoryAxis.extra : 0);
  const tickCount = responsiveChartTickCount(viewBoxW);

  const svg = select(el)
    .append("svg")
    .attr("viewBox", `0 0 ${viewBoxW} ${h}`)
    .attr("width", "100%")
    // Inline styles so Mashup/global CSS cannot force an intrinsic 760px-wide SVG (older bundles).
    .style("max-width", "100%")
    .style("height", "auto")
    .style("display", "block")
    .attr("preserveAspectRatio", "xMidYMid meet")
    .attr("role", "img");

  const g = svg
    .append("g")
    .attr("transform", `translate(${margin.left},${margin.top})`);

  const innerW = viewBoxW - margin.left - margin.right;
  const innerH = h - margin.top - margin.bottom;
  /** @type {import('../lib/chartHitModel.js').ChartHitLayout} */
  const layout = {
    viewBoxW,
    viewBoxH: h,
    marginLeft: margin.left,
    marginTop: margin.top,
    innerW,
    innerH,
  };
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[][]} */
  const hitSeries = seriesList.map(() => []);
  /** @type {import('../lib/chartHitModel.js').ChartHitItem[]} */
  let hitReferences = [];
  /** @type {{ mode: import('../lib/chartXDomain.js').ChartXMode, full: [number, number], view: [number, number] } | undefined} */
  let xDomainOut;

  if (chart.kind === "pie") {
    const s = seriesList[0];
    /** @type {{ label: string; value: number; slot: number }[]} */
    const entries = [];
    for (let i = 0; i < s.x.length; i++) {
      const val = Number(s.y[i]);
      if (!Number.isFinite(val) || val <= 0) continue;
      const lbl = String(s.x[i] ?? "").trim() || `Item ${i + 1}`;
      entries.push({ label: lbl, value: val, slot: resolveSeriesSlot(s.x[i], entries.length, colorKeys), category: normalizeCategoryKey(s.x[i]) });
    }
    if (!entries.length) return null;
    const pieLay = d3Pie()
      .value((d) => d.value)
      .sort(null)(entries);
    const total = entries.reduce((sum, e) => sum + e.value, 0);
    const r = Math.min(innerW, innerH) / 2 - 8;
    const ar = d3Arc().innerRadius(0).outerRadius(r);
    const pieCx = innerW / 2;
    const pieCy = innerH / 2;
    layout.pie = { cx: pieCx, cy: pieCy, r };
    const gPie = g.append("g").attr("transform", `translate(${pieCx},${pieCy})`);
    pieLay.forEach((d, entryIndex) => {
      const col = theme.palette.series[d.data.slot];
      const pct = total > 0 ? (100 * d.data.value) / total : 0;
      const tip = `${d.data.label}: ${d.data.value} (${pct.toFixed(1)}%)`;
      const dimmed = focusedSlice !== null && focusedSlice !== entryIndex;
      const [ccx, ccy] = ar.centroid(d);
      hitSeries[0].push({
        type: "slice",
        series: 0,
        index: entryIndex,
        slot: d.data.slot,
        category: d.data.category,
        cx: pieCx + ccx,
        cy: pieCy + ccy,
        startAngle: d.startAngle,
        endAngle: d.endAngle,
        lines: [d.data.label, `${yCaption}: ${d.data.value} (${pct.toFixed(1)}%)`, `Total: ${total}`],
      });
      gPie
        .append("path")
        .attr("d", ar(d))
        .attr("fill", col)
        .attr("stroke", theme.palette.pointOutline)
        .attr("stroke-width", theme.style.outlineWidth)
        .attr("opacity", dimmed ? UNFOCUSED_SLICE_OPACITY : null)
        .attr("data-parler-slice-dimmed", dimmed ? "" : null)
        .attr("data-parler-slice-focused", focusedSlice === entryIndex ? "" : null)
        .attr("data-parler-palette-role", "series point-outline")
        .attr("data-parler-series-slot", d.data.slot)
        .attr("data-parler-category", d.data.category ?? null)
        .append("title")
        .text(tip);
    });
  } else if (horizontal && budget && gutter) {
    const m = barCategoryCount;
    const y0 = scaleBand()
      .domain([...Array(m).keys()].map(String))
      .range([0, innerH])
      .padding(BAR_OUTER_PADDING);
    const y1 = scaleBand()
      .domain([...Array(seriesList.length).keys()].map(String))
      .range([0, y0.bandwidth()])
      .padding(BAR_INNER_PADDING);
    const xScale = scaleLinear()
      .domain(/** @type {[number, number]} */ (yDomainRaw))
      .nice()
      .range([0, innerW]);

    appendXGrid(g, xScale, innerH, tickCount, theme);

    if (stackMode && stacks) {
      const segments = barStackSegments(seriesList, m, hidden, stackMode, stacks);
      segments.forEach((segs, si) => {
        const s = seriesList[si];
        const seriesName = String(s.name ?? `Series ${si + 1}`).trim() || `Series ${si + 1}`;
        const slot = resolveSeriesSlot(s.name, si, colorKeys);
        const color = theme.palette.series[slot];
        const category = normalizeCategoryKey(s.name);
        const y = (i) => y0(String(i)) ?? 0;
        const height = y0.bandwidth();
        segs.forEach((seg) => {
          const x0px = xScale(seg.from);
          const x1px = xScale(seg.to);
          hitSeries[si].push({
            type: "bar",
            series: si,
            slot,
            category,
            index: seg.index,
            cx: seg.drawn ? (x0px + x1px) / 2 : xScale(0),
            cy: y(seg.index) + height / 2,
            x: Math.min(x0px, x1px),
            width: Math.abs(x1px - x0px),
            y: y(seg.index),
            height,
            lines: [seriesName, `${xCaption}: ${categoryLabels[seg.index]}`, `${yCaption}: ${String(s.y[seg.index])}`, ...stackTooltipLines(stackMode, seg, yCaption)],
          });
        });
        g.selectAll(`rect.bar-s${si}`)
          .data(segs.filter((seg) => seg.drawn))
          .join("rect")
          .attr("class", `bar-s${si}`)
          .attr("data-stack", stackMode)
          .attr("x", (seg) => Math.min(xScale(seg.from), xScale(seg.to)))
          .attr("y", (seg) => y(seg.index))
          .attr("width", (seg) => Math.abs(xScale(seg.to) - xScale(seg.from)))
          .attr("height", height)
          .attr("fill", color)
          .attr("stroke", theme.palette.pointOutline)
          .attr("stroke-width", theme.style.outlineWidth)
          .attr("data-parler-palette-role", "series point-outline")
          .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
      });
    }
    seriesList.forEach((s, si) => {
      if (stackMode || hidden.has(si)) return;
      const ys = s.y.slice(0, m).map((y) => Number(y));
      const seriesName = String(s.name ?? `Series ${si + 1}`).trim() || `Series ${si + 1}`;
      const slot = resolveSeriesSlot(s.name, si, colorKeys);
      const color = theme.palette.series[slot];
      const category = normalizeCategoryKey(s.name);
      ys.forEach((v, i) => {
        const y = (y0(String(i)) ?? 0) + (y1(String(si)) ?? 0);
        const height = y1.bandwidth();
        const geom = barRectGeometryHorizontal(xScale, v);
        hitSeries[si].push({
          type: "bar",
          series: si,
          slot,
          category,
          index: i,
          cx: v >= 0 ? geom.x + geom.width : geom.x,
          cy: y + height / 2,
          x: geom.x,
          width: geom.width,
          y,
          height,
          lines: [seriesName, `${xCaption}: ${categoryLabels[i]}`, `${yCaption}: ${String(s.y[i])}`],
        });
      });
      g.selectAll(`rect.bar-s${si}`)
        .data(ys)
        .join("rect")
        .attr("class", `bar-s${si}`)
        .attr("x", (d) => barRectGeometryHorizontal(xScale, d).x)
        .attr("y", (_d, i) => (y0(String(i)) ?? 0) + (y1(String(si)) ?? 0))
        .attr("width", (d) => barRectGeometryHorizontal(xScale, d).width)
        .attr("height", y1.bandwidth())
        .attr("fill", color)
        .attr("stroke", theme.palette.pointOutline)
        .attr("stroke-width", theme.style.outlineWidth)
        .attr("rx", theme.style.barRadius)
        .attr("data-parler-palette-role", "series point-outline")
        .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
    });

    appendZeroBaselineVertical(g, xScale, innerH, /** @type {[number, number]} */ (yDomainRaw), theme);
    appendXReferenceLines(g, xScale, innerH, chart.y_reference_lines ?? undefined, theme);
    hitReferences = referenceHitItemsHorizontal(chart, xScale, innerH, yCaption);
    const valueAxisGroup = g.append("g")
      .attr("transform", `translate(0,${innerH})`)
      .call(
        axisBottom(xScale)
          .ticks(tickCount)
          .tickSize(theme.style.axis.tickLength)
          .tickPadding(theme.style.axis.tickPadding)
      );
    styleAxis(valueAxisGroup, theme);
    const categoryAxisGroup = g.append("g").attr("class", "category-axis").call(
      axisLeft(y0)
        .tickSize(theme.style.axis.tickLength)
        .tickPadding(theme.style.axis.tickPadding)
        .tickFormat(() => "")
    );
    styleAxis(categoryAxisGroup, theme);
    const lineHeight = budget.lineHeight;
    const labelX = -(theme.style.axis.tickLength + theme.style.axis.tickPadding);
    categoryAxisGroup.selectAll(".tick").each(function (_d, i) {
      const lines = gutter.lines[i] ?? [""];
      const text = select(this).select("text");
      text.text(null).attr("x", labelX).attr("text-anchor", "end").attr("data-lines", lines.length);
      // Centre the label block on the band: the first line starts (L − 1) / 2 line heights above.
      const firstDy = -((lines.length - 1) * lineHeight) / 2;
      lines.forEach((line, li) => {
        text
          .append("tspan")
          .attr("x", labelX)
          .attr("dy", li === 0 ? firstDy : lineHeight)
          .text(line);
      });
    });
    layout.barStep = y0.step();
    layout.barBandwidth = y0.bandwidth();
    layout.subBarThickness = y1.bandwidth();
    layout.labelLines = gutter.maxLines;
    layout.labelLineHeight = lineHeight;
  } else if (chart.kind === "bar") {
    const m = barCategoryCount;
    if (seriesList.length > 1) {
      for (let si = 1; si < seriesList.length; si++) {
        const s = seriesList[si];
        for (let j = 0; j < m; j++) {
          const a = String(s0.x[j] ?? "").trim();
          const b = String(s.x[j] ?? "").trim();
          if (a !== b) {
            console.warn(
              `[parler-ui chart-draw] bar multi-series: x label mismatch at row ${j}`
            );
            break;
          }
        }
      }
    }
    const x0 = scaleBand()
      .domain([...Array(m).keys()].map(String))
      .range([0, innerW])
      .padding(0.2);
    const x1 = scaleBand()
      .domain([...Array(seriesList.length).keys()].map(String))
      .range([0, x0.bandwidth()])
      .padding(0.08);

    const yScale = scaleLinear()
      .domain(/** @type {[number, number]} */ (yDomainRaw))
      .nice()
      .range([innerH, 0]);

    appendYGrid(g, yScale, innerW, tickCount, theme);

    if (stackMode && stacks) {
      const segments = barStackSegments(seriesList, m, hidden, stackMode, stacks);
      segments.forEach((segs, si) => {
        const s = seriesList[si];
        const seriesName = String(s.name ?? `Series ${si + 1}`).trim() || `Series ${si + 1}`;
        const slot = resolveSeriesSlot(s.name, si, colorKeys);
        const color = theme.palette.series[slot];
        const category = normalizeCategoryKey(s.name);
        const x = (i) => x0(String(i)) ?? 0;
        const width = x0.bandwidth();
        segs.forEach((seg) => {
          const yFrom = yScale(seg.from);
          const yTo = yScale(seg.to);
          hitSeries[si].push({
            type: "bar",
            series: si,
            slot,
            category,
            index: seg.index,
            cx: x(seg.index) + width / 2,
            cy: seg.drawn ? (yFrom + yTo) / 2 : yScale(0),
            x: x(seg.index),
            width,
            lines: [seriesName, `${xCaption}: ${String(s0.x[seg.index] ?? "").trim()}`, `${yCaption}: ${String(s.y[seg.index])}`, ...stackTooltipLines(stackMode, seg, yCaption)],
          });
        });
        g.selectAll(`rect.bar-s${si}`)
          .data(segs.filter((seg) => seg.drawn))
          .join("rect")
          .attr("class", `bar-s${si}`)
          .attr("data-stack", stackMode)
          .attr("x", (seg) => x(seg.index))
          .attr("y", (seg) => Math.min(yScale(seg.from), yScale(seg.to)))
          .attr("width", width)
          .attr("height", (seg) => Math.abs(yScale(seg.from) - yScale(seg.to)))
          .attr("fill", color)
          .attr("stroke", theme.palette.pointOutline)
          .attr("stroke-width", theme.style.outlineWidth)
          .attr("data-parler-palette-role", "series point-outline")
          .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
      });
    }
    seriesList.forEach((s, si) => {
      if (stackMode || hidden.has(si)) return;
      const ys = s.y.slice(0, m).map((y) => Number(y));
      const seriesName = String(s.name ?? `Series ${si + 1}`).trim() || `Series ${si + 1}`;
      const slot = resolveSeriesSlot(s.name, si, colorKeys);
      const color = theme.palette.series[slot];
      const category = normalizeCategoryKey(s.name);
      ys.forEach((v, i) => {
        const x = (x0(String(i)) ?? 0) + (x1(String(si)) ?? 0);
        const width = x1.bandwidth();
        const geom = barRectGeometry(yScale, v);
        hitSeries[si].push({
          type: "bar",
          series: si,
          slot,
          category,
          index: i,
          cx: x + width / 2,
          cy: v >= 0 ? geom.y : geom.y + geom.height,
          x,
          width,
          lines: [seriesName, `${xCaption}: ${String(s0.x[i] ?? "").trim()}`, `${yCaption}: ${String(s.y[i])}`],
        });
      });
      g.selectAll(`rect.bar-s${si}`)
        .data(ys)
        .join("rect")
        .attr("class", `bar-s${si}`)
        .attr("x", (_d, i) => (x0(String(i)) ?? 0) + (x1(String(si)) ?? 0))
        .attr("y", (d) => barRectGeometry(yScale, d).y)
        .attr("width", x1.bandwidth())
        .attr("height", (d) => barRectGeometry(yScale, d).height)
        .attr("fill", color)
        .attr("stroke", theme.palette.pointOutline)
        .attr("stroke-width", theme.style.outlineWidth)
        .attr("rx", theme.style.barRadius)
        .attr("data-parler-palette-role", "series point-outline")
        .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
    });

    appendZeroBaseline(g, yScale, innerW, /** @type {[number, number]} */ (yDomainRaw), theme);
    appendYReferenceLines(
      g,
      yScale,
      innerW,
      chart.y_reference_lines ?? undefined,
      theme
    );
    hitReferences = referenceHitItems(chart, yScale, yCaption);
    const xAxisGroup = g.append("g")
      .attr("transform", `translate(0,${innerH})`)
      .call(
        axisBottom(x0)
          .tickSize(theme.style.axis.tickLength)
          .tickPadding(theme.style.axis.tickPadding)
          .tickFormat((d) => categoryAxis?.labels[Number(d)] ?? "")
      );
    styleAxis(xAxisGroup, theme)
      .selectAll(".tick text")
      .attr("transform", `rotate(${CATEGORY_LABEL_ROTATION})`)
      .style("text-anchor", "end");
    const yAxisGroup = g.append("g").call(
      axisLeft(yScale)
        .ticks(tickCount)
        .tickSize(theme.style.axis.tickLength)
        .tickPadding(theme.style.axis.tickPadding)
    );
    styleAxis(yAxisGroup, theme);
  } else {
    const ls = lineScatterXs(seriesList);
    if (!ls) return null;

    const { allDates, xsPerSeries } = ls;
    const elapsedDom = !allDates ? parseElapsedDomain(chart) : null;
    const normalizedDom = !allDates ? parseNormalizedDomain(chart) : null;
    const flatX = xsPerSeries.flatMap((row) => row);
    const reqDom =
      allDates && (chart.kind === "line" || chart.kind === "scatter")
        ? parseRequestedTimeRange(chart)
        : null;

    /** @type {[number, number]} */
    let fullDomain;
    /** @type {import('../lib/chartXDomain.js').ChartXMode} */
    let xMode;
    if (allDates) {
      const flatDates = /** @type {Date[]} */ (flatX);
      const dataDom = /** @type {[Date, Date]} */ (extent(flatDates));
      const dom = reqDom ?? dataDom;
      fullDomain = [dom[0].getTime(), dom[1].getTime()];
      xMode = "absolute";
    } else {
      const flatNum = /** @type {number[]} */ (flatX);
      const lo = normalizedDom ? normalizedDom[0] : elapsedDom ? elapsedDom[0] : min(flatNum) ?? 0;
      const hi = normalizedDom ? normalizedDom[1] : elapsedDom ? elapsedDom[1] : max(flatNum) ?? 1;
      fullDomain = [lo, hi];
      xMode = normalizedDom ? "normalized" : elapsedDom ? "elapsed" : "numeric";
    }
    const zoom = clampXRange(view?.viewXDomain, fullDomain);
    const viewDomain = zoom ?? fullDomain;
    const selection = clampXSelection(view?.selectedRange, fullDomain);
    xDomainOut = { mode: xMode, full: fullDomain, view: viewDomain };
    let xScale;
    if (allDates) {
      /** Local wall-clock axis — see repo `docs/architecture/times-solution.md` (“present in local time”); domain instants unchanged (ISO on wire). */
      xScale = scaleTime()
        .domain([new Date(viewDomain[0]), new Date(viewDomain[1])])
        .range([0, innerW]);
    } else {
      xScale = scaleLinear().domain(viewDomain).range([0, innerW]);
    }
    const xNumber = (xv) => (allDates ? /** @type {Date} */ (xv).getTime() : /** @type {number} */ (xv));

    const yScale = scaleLinear()
      .domain(/** @type {[number, number]} */ (yDomainRaw))
      .nice()
      .range([innerH, 0]);

    appendYGrid(g, yScale, innerW, tickCount, theme);

    const clipId = `aipc-${Date.now().toString(36)}-${Math.floor(Math.random() * 1e9)}`;
    /** Clipped plot layer when a fixed query window or a zoomed view may extend past data (sparse gaps stay empty). */
    const gPlot = reqDom || elapsedDom || normalizedDom || zoom
      ? (() => {
          g.append("defs")
            .append("clipPath")
            .attr("id", clipId)
            .append("rect")
            .attr("width", innerW)
            .attr("height", innerH);
          return g.append("g").attr("clip-path", `url(#${clipId})`);
        })()
      : g;

    if (selection) {
      const sx0 = Number(allDates ? xScale(new Date(selection[0])) : xScale(selection[0]));
      const sx1 = Number(allDates ? xScale(new Date(selection[1])) : xScale(selection[1]));
      const left = Math.max(0, Math.min(sx0, sx1));
      const right = Math.min(innerW, Math.max(sx0, sx1));
      if (right > left) {
        gPlot
          .append("rect")
          .attr("class", "chart-selection")
          .attr("part", "chart-selection")
          .attr("x", left)
          .attr("y", 0)
          .attr("width", right - left)
          .attr("height", innerH)
          .attr("fill", theme.palette.grid)
          .attr("fill-opacity", 0.28)
          .attr("stroke", theme.palette.grid)
          .attr("stroke-width", theme.style.gridLineWidth)
          .attr("pointer-events", "none")
          .attr("data-parler-palette-role", "grid");
      }
    }

    const xText = (xv, raw) => {
      if (allDates) return `${formatFullLocalTime(/** @type {Date} */ (xv))} (${displayTimeZoneLabel(/** @type {Date} */ (xv))})`;
      if (elapsedDom) return `${formatElapsedTick(/** @type {number} */ (xv))} elapsed (${raw})`;
      if (normalizedDom) return `${String(raw).trim()} (${formatNormalizedTick(/** @type {number} */ (xv))})`;
      return String(raw);
    };
    seriesList.forEach((s, si) => {
      if (hidden.has(si)) return;
      const xs = xsPerSeries[si];
      const ys = s.y.map((y) => Number(y));
      const seriesName = String(s.name ?? `Series ${si + 1}`).trim() || `Series ${si + 1}`;
      const slot = resolveSeriesSlot(s.name, si, colorKeys);
      const color = theme.palette.series[slot];
      const category = normalizeCategoryKey(s.name);
      const sourceZone =
        allDates && s.sourceWindow && typeof s.sourceWindow.resolvedTimeZone === "string"
          ? s.sourceWindow.resolvedTimeZone.trim()
          : "";
      ys.forEach((v, i) => {
        const xv = xs[i];
        const xn = xNumber(xv);
        if (xn < viewDomain[0] || xn > viewDomain[1]) return;
        const cx = Number(allDates ? xScale(/** @type {Date} */ (xv)) : xScale(/** @type {number} */ (xv)));
        const cy = yScale(v);
        if (!Number.isFinite(cx) || !Number.isFinite(cy)) return;
        const lines = [seriesName, `${xCaption}: ${xText(xv, s.x[i])}`, `${yCaption}: ${String(s.y[i])}`];
        if (sourceZone) lines.push(`Source window zone: ${sourceZone}`);
        hitSeries[si].push({ type: "point", series: si, slot, category, index: i, cx, cy, lines });
      });

      const ln = d3Line()
        .x((_d, i) => {
          const xv = xs[i];
          const raw = allDates
            ? xScale(/** @type {Date} */ (xv))
            : xScale(/** @type {number} */ (xv));
          return Number(raw);
        })
        .y((d) => yScale(d));

      if (chart.kind === "line") {
        gPlot
          .append("path")
          .datum(ys)
          .attr("fill", "none")
          .attr("stroke", color)
          .attr("stroke-width", theme.style.lineWidth)
          .attr("data-parler-palette-role", "series")
          .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null)
          .attr("d", ln);
      }

      gPlot
        .selectAll(`circle.pt-s${si}`)
        .data(ys)
        .join("circle")
        .attr("class", `pt-s${si}`)
        .attr("cx", (_d, i) => {
          const xv = xs[i];
          const raw = allDates
            ? xScale(/** @type {Date} */ (xv))
            : xScale(/** @type {number} */ (xv));
          return Number(raw);
        })
        .attr("cy", (d) => yScale(d))
        .attr("r", chart.kind === "scatter" ? theme.style.scatterPointRadius : theme.style.linePointRadius)
        .attr("fill", color)
        .attr("stroke", theme.palette.pointOutline)
        .attr("stroke-width", theme.style.outlineWidth)
        .attr("data-parler-palette-role", "series point-outline")
        .attr("data-parler-series-slot", slot)
        .attr("data-parler-category", typeof category === "string" ? category : null);
    });

    appendYReferenceLines(
      gPlot,
      yScale,
      innerW,
      chart.y_reference_lines ?? undefined,
      theme
    );
    hitReferences = referenceHitItems(chart, yScale, yCaption);

    /**
     * Browser-local tick labels chosen by span (design §5.2): time only within one day, date +
     * time across days, and UTC offsets when a local time repeats (DST fall-back). The tick set
     * is then fitted to the plot width using the formatted labels, so longer labels never
     * overlap or run past the available gutters.
     */
    let timeAxis = null;
    if (allDates) {
      const timeScale = /** @type {import('d3-scale').ScaleTime<number, number>} */ (xScale);
      const fit = fitTimeTicks({
        ticksFor: (n) => timeScale.ticks(n),
        xFor: (d) => Number(timeScale(d)),
        desired: tickCount,
        innerW,
        leftRoom: margin.left,
        rightRoom: margin.right,
        charWidth: theme.style.type.tickSize * 0.62,
        instantAt: (x) => timeScale.invert(x),
      });
      timeAxis = axisBottom(timeScale).tickValues(fit.ticks).tickFormat(fit.format);
    }
    const xAxis = timeAxis
      ? timeAxis
      : normalizedDom
        ? axisBottom(xScale).ticks(tickCount).tickFormat(formatNormalizedTick)
        : elapsedDom
          ? axisBottom(xScale).ticks(tickCount).tickFormat(formatElapsedTick)
          : axisBottom(xScale).ticks(tickCount);
    const xAxisGroup = g.append("g")
      .attr("transform", `translate(0,${innerH})`)
      .call(
        xAxis
          .tickSize(theme.style.axis.tickLength)
          .tickPadding(theme.style.axis.tickPadding)
      );
    styleAxis(xAxisGroup, theme);
    const yAxisGroup = g.append("g").call(
      axisLeft(yScale)
        .ticks(tickCount)
        .tickSize(theme.style.axis.tickLength)
        .tickPadding(theme.style.axis.tickPadding)
    );
    styleAxis(yAxisGroup, theme);
  }

  svg
    .append("text")
    .attr("x", viewBoxW / 2)
    .attr("y", h - 8)
    .attr("text-anchor", "middle")
    .attr("fill", theme.palette.text.axisLabel)
    .attr("font-family", theme.style.fontFamily)
    .attr("font-size", theme.style.type.axisLabelSize)
    .attr("data-parler-palette-role", "axis-label")
    .text(
      `${chart.x_label ?? ""}${chart.x_label && chart.y_label ? " · " : ""}${chart.y_label ?? ""}`
    );

  return {
    hit: createChartHitModel({
      kind: chart.kind,
      series: hitSeries,
      references: hitReferences,
      layout,
      orientation: horizontal ? "horizontal" : "vertical",
    }),
    hiddenSeries: [...hidden].sort((a, b) => a - b),
    plotSize,
    ...(horizontal ? { orientation: "horizontal" } : {}),
    ...(xDomainOut ? { xDomain: xDomainOut } : {}),
  };
}
