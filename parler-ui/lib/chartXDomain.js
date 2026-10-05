/**
 * X-domain rules shared by the renderer and the view state (chart-enhancement design §4.5
 * C1b-2). Pure: parses line/scatter X cells and the fixed-domain modes exactly as the renderer
 * draws them, and expresses every X domain as numbers (absolute time as epoch milliseconds,
 * elapsed as seconds, normalized as a fraction, numeric as the value) so zoom and selection
 * ranges can be stored, clamped and described without DOM.
 */

import { displayTimeZoneLabel, formatFullLocalTime } from "./chartTimeFormat.js";

/**
 * Strict X for `line` / `scatter`: finite number after trim, or ISO-8601–parsable instant.
 * Matches `docs/architecture/flexible-chart-solution.md` §9.2 (no silent 0 for garbage strings).
 * @param {unknown} raw
 * @returns {{ t: "d", v: Date } | { t: "n", v: number } | null}
 */
export function parseLineScatterXCell(raw) {
  if (raw == null) return null;
  const s = String(raw).trim();
  if (!s) return null;
  const n = Number(s);
  if (Number.isFinite(n)) return { t: "n", v: n };
  const ms = Date.parse(s);
  if (!Number.isNaN(ms)) return { t: "d", v: new Date(ms) };
  return null;
}

/** Normalized X domain when xAxisMode === "normalized". @returns {[number, number] | null} */
export function parseNormalizedDomain(chart) {
  if (chart?.xAxisMode !== "normalized") return null;
  const d = chart?.normalizedDomain;
  if (d == null || typeof d !== "object") return null;
  const start = Number(d.start);
  const end = Number(d.end);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  return [start, end];
}

/** Elapsed X domain when xAxisMode === "elapsed". @returns {[number, number] | null} */
export function parseElapsedDomain(chart) {
  if (chart?.xAxisMode !== "elapsed") return null;
  const d = chart?.elapsedDomain;
  if (d == null || typeof d !== "object") return null;
  const start = Number(d.start);
  const end = Number(d.end);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) return null;
  return [start, end];
}

/** @returns {[Date, Date] | null} */
export function parseRequestedTimeRange(chart) {
  const r = chart?.requested_time_range;
  if (r == null || typeof r !== "object") return null;
  const s = r.start;
  const e = r.end;
  if (typeof s !== "string" || typeof e !== "string") return null;
  const t0 = Date.parse(s.trim());
  const t1 = Date.parse(e.trim());
  if (!Number.isFinite(t0) || !Number.isFinite(t1)) return null;
  let d0 = new Date(t0);
  let d1 = new Date(t1);
  if (d1 < d0) {
    const tmp = d0;
    d0 = d1;
    d1 = tmp;
  }
  return [d0, d1];
}

/**
 * Parse every series' X into one consistent scale type.
 * @returns {{ allDates: boolean, xsPerSeries: (Date[] | number[])[] } | null}
 */
export function lineScatterXs(seriesList) {
  const p0 = parseLineScatterXCell(seriesList[0]?.x?.[0]);
  if (!p0) return null;
  const t = p0.t;
  const xsPerSeries = [];
  for (const s of seriesList) {
    const xs = [];
    for (let i = 0; i < s.x.length; i++) {
      const p = parseLineScatterXCell(s.x[i]);
      if (!p || p.t !== t) return null;
      xs.push(p.v);
    }
    xsPerSeries.push(xs);
  }
  return { allDates: t === "d", xsPerSeries };
}

/**
 * @typedef {"absolute" | "elapsed" | "normalized" | "numeric"} ChartXMode
 * @typedef {{ mode: ChartXMode, domain: [number, number] }} ChartXDomainInfo
 */

/**
 * The full X domain of a line/scatter chart as numbers, using the same precedence as drawing:
 * absolute time uses `requested_time_range` when valid, else the data extent; elapsed and
 * normalized use their fixed domains; numeric uses the data extent. `null` for other kinds or
 * unparsable X.
 * @param {import('./types.js').ChartBlock | null | undefined} chart
 * @returns {ChartXDomainInfo | null}
 */
export function chartXDomainInfo(chart) {
  if (!chart || (chart.kind !== "line" && chart.kind !== "scatter")) return null;
  const seriesList = Array.isArray(chart.series) ? chart.series : [];
  if (!seriesList.length || !seriesList.every((s) => s && Array.isArray(s.x) && s.x.length)) return null;
  const ls = lineScatterXs(seriesList);
  if (!ls) return null;
  const flat = ls.xsPerSeries.flat();
  if (ls.allDates) {
    const req = parseRequestedTimeRange(chart);
    const times = /** @type {Date[]} */ (flat).map((d) => d.getTime());
    const lo = req ? req[0].getTime() : Math.min(...times);
    const hi = req ? req[1].getTime() : Math.max(...times);
    return Number.isFinite(lo) && Number.isFinite(hi) ? { mode: "absolute", domain: [lo, hi] } : null;
  }
  const nums = /** @type {number[]} */ (flat);
  const elapsed = parseElapsedDomain(chart);
  if (elapsed) return { mode: "elapsed", domain: elapsed };
  const normalized = parseNormalizedDomain(chart);
  if (normalized) return { mode: "normalized", domain: normalized };
  return { mode: "numeric", domain: [Math.min(...nums), Math.max(...nums)] };
}

/** Resolution of the X view over the full domain: the minimum zoom span is one part in this. */
export const X_VIEW_STEPS = 1000;

/** The smallest zoom span allowed inside a full domain (§4.5: 1/1000 of the domain). @param {[number, number]} full */
export function minimumXSpan(full) {
  return (full[1] - full[0]) / X_VIEW_STEPS;
}

/**
 * Clamp a stored range to the hard bounds of the full domain. `null` when it is not an array of
 * two finite numbers, when the full domain is degenerate, or when the clamped range has no
 * positive extent inside the domain. A range equal to the full domain is returned as is.
 * @param {unknown} range
 * @param {[number, number]} full
 * @returns {[number, number] | null}
 */
export function clampXSelection(range, full) {
  if (!Array.isArray(range) || range.length !== 2) return null;
  const lo = Number(range[0]);
  const hi = Number(range[1]);
  if (!Number.isFinite(lo) || !Number.isFinite(hi)) return null;
  const [flo, fhi] = full;
  if (!(fhi > flo)) return null;
  const a = Math.max(flo, Math.min(fhi, lo));
  const b = Math.max(flo, Math.min(fhi, hi));
  if (!(b > a)) return null;
  return [a, b];
}

/**
 * Clamp a zoom to the full domain and to the minimum span: an undersized range is widened to
 * the minimum span inside the hard bounds. `null` means no zoom: invalid input, an inverted or
 * empty range, a range outside the domain, or a range covering the whole domain.
 * @param {unknown} range
 * @param {[number, number]} full
 * @returns {[number, number] | null}
 */
export function clampXRange(range, full) {
  const clamped = clampXSelection(range, full);
  if (!clamped) return null;
  const [flo, fhi] = full;
  const min = minimumXSpan(full);
  let [a, b] = clamped;
  if (b - a < min) {
    b = a + min;
    if (b > fhi) {
      b = fhi;
      a = fhi - min;
    }
  }
  if (a <= flo && b >= fhi) return null;
  return [a, b];
}

/** Elapsed seconds as m:ss. @param {number} sec */
export function formatElapsedTick(sec) {
  const s = Math.max(0, Math.round(sec));
  const m = Math.floor(s / 60);
  const r = s % 60;
  return `${m}:${String(r).padStart(2, "0")}`;
}

/** Normalized fraction as a percent. @param {number} d */
export function formatNormalizedTick(d) {
  return `${Math.round(d * 100)}%`;
}

/** Readable X value for a mode, in the display zone for absolute time. @param {ChartXMode} mode @param {number} value */
export function formatXValue(mode, value) {
  if (mode === "absolute") return formatFullLocalTime(new Date(value));
  if (mode === "elapsed") return `${formatElapsedTick(value)} elapsed`;
  if (mode === "normalized") return `${Number(value.toFixed(4))} (${formatNormalizedTick(value)})`;
  return String(value);
}

/** Readable X range; absolute ranges carry the display zone once. @param {ChartXMode} mode @param {[number, number]} range */
export function formatXRange(mode, range) {
  const [lo, hi] = range;
  const text = `${formatXValue(mode, lo)} – ${formatXValue(mode, hi)}`;
  return mode === "absolute" ? `${text} (${displayTimeZoneLabel(new Date(lo))})` : text;
}
