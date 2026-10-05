/**
 * Data notes for a chart card (chart-enhancement design §4.1, §6.1): what the UI already knows
 * about the chart's source, window, columns, transform, counts and limits, read only from the
 * ChartBlock. Unknown facts are stated as not provided; nothing is inferred. Identifiers that
 * belong to diagnostics (cache id, tool call id, chart id) are kept in a separate list. Pure.
 *
 * @typedef {{ label: string; value: string }} ChartNoteRow
 */

import { displayTimeZoneLabel, formatFullLocalTime, localOffsetLabel } from "./chartTimeFormat.js";

export const NOT_PROVIDED = "Not provided by source";
/** Read-out of a missing heatmap cell (design §7.4: missing is not zero). */
export const NO_DATA = "No data";

/** Rows per page of the in-card chart-data view. */
export const CHART_DATA_PAGE_SIZE = 20;

/**
 * The values this chart received, one row per point or slice with raw x/y text, in series and
 * data order (design §4.1: "data in the chart", never the full source record set).
 * @param {import('./types.js').ChartBlock} chart
 * @returns {{ columns: string[], rows: { series: string; x: string; y: string }[] }}
 */
export function chartDataRows(chart) {
  if (chart?.kind === "histogram" && chart.histogram && typeof chart.histogram === "object") {
    const h = chart.histogram;
    const edges = Array.isArray(h.edges) ? h.edges : [];
    const counts = Array.isArray(h.counts) ? h.counts : [];
    const densities = Array.isArray(h.densities) ? h.densities : [];
    const rows = counts.map((c, i) => ({
      series: `Bin ${i + 1}`,
      x: histogramBinLabel(edges, i),
      y: `${String(c)} (density ${String(densities[i])})`,
    }));
    return { columns: ["Bin", text(chart?.x_label) || "Range", "Count (density)"], rows };
  }
  if (chart?.kind === "heatmap" && chart.heatmap && typeof chart.heatmap === "object") {
    const h = chart.heatmap;
    const rowsKeys = Array.isArray(h.rows) ? h.rows : [];
    const colsKeys = Array.isArray(h.cols) ? h.cols : [];
    const values = Array.isArray(h.values) ? h.values : [];
    const rows = [];
    rowsKeys.forEach((rk, r) => {
      colsKeys.forEach((ck, c) => {
        const v = values[r]?.[c];
        rows.push({ series: text(rk), x: text(ck), y: v === null || v === undefined ? NO_DATA : String(v) });
      });
    });
    return { columns: [text(chart?.y_label) || "Row", text(chart?.x_label) || "Column", text(h.valueLabel) || "Value"], rows };
  }
  if (chart?.kind === "boxplot" && chart.boxplot && typeof chart.boxplot === "object") {
    const groups = Array.isArray(chart.boxplot.groups) ? chart.boxplot.groups : [];
    const rows = [];
    for (const g of groups) {
      const key = text(g?.key) || "Group";
      const stat = (label, value) => rows.push({ series: key, x: label, y: String(value) });
      stat("n", g?.n);
      stat("Excluded", g?.excludedCount);
      stat("Min", g?.min);
      stat("Lower whisker", g?.whiskerLow);
      stat("Q1", g?.q1);
      stat("Median", g?.median);
      stat("Q3", g?.q3);
      stat("Upper whisker", g?.whiskerHigh);
      stat("Max", g?.max);
      stat("Outliers", boxplotOutlierText(g));
    }
    return { columns: [text(chart?.x_label) || "Group", "Statistic", text(chart?.y_label) || "Value"], rows };
  }
  const seriesList = Array.isArray(chart?.series) ? chart.series : [];
  const xHeader = chart?.kind === "pie" ? "Slice" : (text(chart?.x_label) || "X");
  const yHeader = text(chart?.y_label) || "Value";
  const rows = [];
  seriesList.forEach((s, si) => {
    const name = text(s?.name) || `Series ${si + 1}`;
    const xs = Array.isArray(s?.x) ? s.x : [];
    const ys = Array.isArray(s?.y) ? s.y : [];
    const n = Math.min(xs.length, ys.length);
    for (let i = 0; i < n; i++) rows.push({ series: name, x: text(xs[i]), y: String(ys[i]) });
  });
  return { columns: ["Series", xHeader, yHeader], rows };
}

/**
 * The listed outliers of one boxplot group, with the count of those not listed (design §7.4: "another N not shown").
 * @param {{ outliers?: unknown, outlierCount?: unknown } | null | undefined} g
 */
export function boxplotOutlierText(g) {
  const shown = Array.isArray(g?.outliers) ? g.outliers : [];
  const count = typeof g?.outlierCount === "number" ? g.outlierCount : shown.length;
  if (count === 0) return "None";
  const hidden = count - shown.length;
  return `${shown.map(String).join(", ")}${hidden > 0 ? ` (another ${hidden} not shown)` : ""}`;
}

/** `[a, b)` for every bin but the last, which is closed: `[a, b]`. @param {number[]} edges @param {number} i */
export function histogramBinLabel(edges, i) {
  const last = i === edges.length - 2;
  return `[${String(edges[i])}, ${String(edges[i + 1])}${last ? "]" : ")"}`;
}

/**
 * One page of chart-data rows.
 * @template T
 * @param {T[]} rows @param {number} page zero-based @param {number} [size]
 * @returns {{ page: number, pageCount: number, from: number, to: number, total: number, rows: T[] }}
 */
export function chartDataPage(rows, page, size = CHART_DATA_PAGE_SIZE) {
  const total = rows.length;
  const pageCount = Math.max(1, Math.ceil(total / size));
  const current = Math.min(Math.max(0, Math.floor(page)), pageCount - 1);
  const start = current * size;
  const slice = rows.slice(start, start + size);
  return { page: current, pageCount, from: total ? start + 1 : 0, to: start + slice.length, total, rows: slice };
}

const text = (v) => (v == null ? "" : String(v).trim());
const number = (v) => (typeof v === "number" && Number.isFinite(v) ? String(v) : "");

const parseInstant = (v) => {
  const d = new Date(String(v ?? ""));
  return Number.isFinite(d.getTime()) ? d : null;
};

/**
 * A time range in the browser display zone, labelled with that zone (design §5.2: present in
 * local time and say which zone). When the two endpoints fall under different UTC offsets (a DST
 * transition inside the range) both offsets are shown so neither endpoint is ambiguous.
 * @param {Date} start @param {Date} end
 */
function displayRange(start, end) {
  const zone =
    localOffsetLabel(start) === localOffsetLabel(end)
      ? displayTimeZoneLabel(start)
      : `${displayTimeZoneLabel(start)} → ${displayTimeZoneLabel(end)}`;
  return `${formatFullLocalTime(start)} – ${formatFullLocalTime(end)} (${zone})`;
}

function windowText(chart) {
  const range = chart.requested_time_range;
  if (range && typeof range.start === "string" && typeof range.end === "string") {
    const start = parseInstant(range.start);
    const end = parseInstant(range.end);
    if (start && end) return displayRange(start, end);
  }
  const windows = (chart.series ?? [])
    .map((s) => {
      const w = s?.sourceWindow;
      if (!w || typeof w !== "object") return "";
      const start = parseInstant(w.start);
      const end = parseInstant(w.end);
      if (!start || !end) return "";
      const zone = text(w.resolvedTimeZone);
      const name = text(s.name) || "series";
      // The source zone is what the producer resolved the window in; it is stated separately and
      // never used to label the display-zone times above.
      return `${name}: ${displayRange(start, end)}${zone ? `; source window zone ${zone}` : ""}`;
    })
    .filter(Boolean);
  return windows.length ? windows.join("; ") : "";
}

/**
 * Readable text for one missing-series / missing-period entry: strings as given; contract
 * objects (CHART_CONTRACT §3.0a: `label`, `thingName`, `propertyName`, `start`, `end`) as
 * "label — thing.property, start – end (zone)" using only the fields present.
 */
function missingEntryText(entry) {
  if (entry == null) return "";
  if (typeof entry !== "object") return text(entry);
  const parts = [];
  const label = text(entry.label);
  if (label) parts.push(label);
  const thing = text(entry.thingName);
  const prop = text(entry.propertyName);
  if (thing || prop) parts.push([thing, prop].filter(Boolean).join("."));
  const start = parseInstant(entry.start);
  const end = parseInstant(entry.end);
  if (start && end) parts.push(displayRange(start, end));
  else if (start || end) parts.push(formatFullLocalTime(/** @type {Date} */ (start ?? end)));
  return parts.join(" — ") || "unnamed entry";
}

/**
 * @param {import('./types.js').ChartBlock} chart
 * @returns {{ rows: ChartNoteRow[], diagnostics: ChartNoteRow[], meta: string }}
 */
export function chartDataNotes(chart) {
  const source = chart?.source && typeof chart.source === "object" ? chart.source : {};
  const seriesList = Array.isArray(chart?.series) ? chart.series : [];
  const counted = seriesList.reduce((n, s) => n + (Array.isArray(s?.y) ? s.y.length : 0), 0);
  const or = (v) => v || NOT_PROVIDED;

  const sourceText = [text(source.sourceResolved), text(source.sourceResultKind)].filter(Boolean).join(" · ");
  const columns = Array.isArray(source.sourceColumns) ? source.sourceColumns.map(text).filter(Boolean).join(", ") : "";
  const truncation =
    source.truncationApplied === true
      ? "Applied (top-N, Other, or point budget)"
      : source.truncationApplied === false
        ? "Not applied"
        : "";
  const missing = [
    ...(Array.isArray(source.missingSeries) ? source.missingSeries : []),
    ...(Array.isArray(source.missingPeriods) ? source.missingPeriods : []),
  ]
    .map(missingEntryText)
    .filter(Boolean);
  const pointCount = number(source.pointCount);

  const histogram = chart?.kind === "histogram" && chart.histogram && typeof chart.histogram === "object" ? chart.histogram : null;
  const boxplot = chart?.kind === "boxplot" && chart.boxplot && typeof chart.boxplot === "object" ? chart.boxplot : null;
  const heatmap = chart?.kind === "heatmap" && chart.heatmap && typeof chart.heatmap === "object" ? chart.heatmap : null;
  const rows = [
    { label: "Source", value: or(sourceText) },
    { label: "Window", value: or(windowText(chart)) },
    { label: "Columns", value: or(columns) },
    { label: "Transform", value: or(text(source.transformSummary)) },
    { label: "Input rows", value: or(number(source.rowCount)) },
    { label: "Emitted points", value: pointCount || `${counted} (counted in chart)` },
    { label: "Top-N / Other / sampling", value: or(truncation) },
    { label: "Zero-filled combinations", value: or(number(source.filledMissingCombinations)) },
    { label: "Zero-value categories", value: or(number(source.zeroValueCategoryCount)) },
    { label: "Missing series", value: missing.length ? missing.join(", ") : "None reported" },
  ];
  if (histogram) {
    rows.push(
      { label: "Binning method", value: or(text(histogram.method)) },
      { label: "Bins", value: String(Array.isArray(histogram.counts) ? histogram.counts.length : 0) },
      { label: "Bar height", value: histogram.mode === "density" ? "Density (count / (Σcount × width))" : "Count per bin" },
      { label: "Valid values", value: or(number(histogram.validCount)) },
      { label: "Excluded values", value: or(number(histogram.excludedCount)) },
      { label: "Below range", value: or(number(histogram.belowRangeCount)) },
      { label: "Above range", value: or(number(histogram.aboveRangeCount)) }
    );
  }
  if (boxplot) {
    const groups = Array.isArray(boxplot.groups) ? boxplot.groups : [];
    const sum = (pick) => groups.reduce((acc, g) => acc + (typeof pick(g) === "number" ? pick(g) : 0), 0);
    const shown = sum((g) => (Array.isArray(g?.outliers) ? g.outliers.length : 0));
    const total = sum((g) => g?.outlierCount);
    rows.push(
      { label: "Summary method", value: or(text(boxplot.method)) },
      { label: "Groups", value: String(groups.length) },
      { label: "Whiskers", value: "Farthest observed values within 1.5 × IQR of the quartiles" },
      { label: "Values summarised", value: String(sum((g) => g?.n)) },
      { label: "Excluded values", value: String(sum((g) => g?.excludedCount)) },
      { label: "Outliers", value: total === 0 ? "None" : `${total} (${shown} shown${total > shown ? `, another ${total - shown} not shown` : ""})` }
    );
  }
  const stackMode = chart?.kind === "bar" && (chart.stackMode === "stacked" || chart.stackMode === "percent") ? chart.stackMode : null;
  if (stackMode) {
    const categories = seriesList.reduce((m, s) => Math.min(m, Array.isArray(s?.y) ? s.y.length : 0), Infinity);
    const zeroTotal = [];
    for (let i = 0; i < (Number.isFinite(categories) ? categories : 0); i++) {
      const total = seriesList.reduce((acc, s) => acc + Number(s.y[i]), 0);
      if (stackMode === "percent" && !(total > 0)) zeroTotal.push(text(seriesList[0]?.x?.[i]) || `#${i + 1}`);
    }
    rows.push({
      label: "Stacking",
      value: stackMode === "percent"
        ? "Percent of the category total: each share = value / sum of all emitted series in that category (hiding a series never changes shares or totals)"
        : "Stacked: positive values accumulate upward from zero and negative values downward; hiding a series closes its gap without changing the other values",
    });
    if (stackMode === "percent") {
      rows.push({ label: "Categories with no share", value: zeroTotal.length ? `${zeroTotal.join(", ")} (total is 0; no bar is drawn, not 100%)` : "None" });
    }
  }
  if (heatmap) {
    const rowsN = Array.isArray(heatmap.rows) ? heatmap.rows.length : 0;
    const colsN = Array.isArray(heatmap.cols) ? heatmap.cols.length : 0;
    const missing = typeof heatmap.missingCount === "number" ? heatmap.missingCount : 0;
    rows.push(
      { label: "Cell value", value: or(text(heatmap.valueLabel)) },
      { label: "Rows × columns", value: `${rowsN} × ${colsN}` },
      { label: "Cells with data", value: String(rowsN * colsN - missing) },
      { label: "Missing cells", value: missing === 0 ? "None" : `${missing} (shown hatched, read as "${NO_DATA}"; missing is not zero)` }
    );
  }
  const diagnostics = [
    { label: "Chart id", value: or(text(chart?.chartId)) },
    { label: "Cache id", value: or(text(source.sourceCacheId)) },
    { label: "Tool call id", value: or(text(source.sourceToolCallId)) },
  ];
  const metaParts = [];
  const window = windowText(chart);
  if (window) metaParts.push(`Window: ${window}`);
  const unit = text(chart?.y_label);
  if (unit) metaParts.push(`Values: ${unit}`);
  if (histogram) metaParts.push(histogram.mode === "density" ? "Height: density" : "Height: count");
  if (boxplot) metaParts.push(`Groups: ${Array.isArray(boxplot.groups) ? boxplot.groups.length : 0}`);
  if (heatmap) metaParts.push(`Cells: ${text(heatmap.valueLabel) || "value"}`);
  if (stackMode) metaParts.push(stackMode === "percent" ? "Stacked: percent of category total" : "Stacked");
  if (source.truncationApplied === true) metaParts.push("Top-N / Other / sampling applied");
  return { rows, diagnostics, meta: metaParts.join(" · ") };
}
