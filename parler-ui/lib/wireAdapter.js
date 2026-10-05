/**
 * Wire JSON → UiEvent — see CONTRACTS/UI_CLIENT_PROTOCOL.md
 * @typedef {import('./types.js').UiEvent} UiEvent
 * @typedef {import('./types.js').ChartBlock} ChartBlock
 * @typedef {import('./types.js').TableBlock} TableBlock
 */

import { llmUsageFromWireRecord } from "./llmUsageWire.js";

const REF_ROLES = new Set([
  "usl",
  "ucl",
  "lcl",
  "lsl",
  "target",
  "limit",
  "warning",
]);

/** v1b defensive caps — docs/agent/task-state.md § v1b Budgets (UTF-16 code units). */
const TS_MAX_TITLE_CHARS = 200;
const TS_MAX_LABEL_CHARS = 200;
const TS_MAX_ITEM_SUMMARY_CHARS = 200;
const TS_MAX_ITEMS_PER_FRAME = 30;
const TS_MAX_FRAME_CHARS = 8000;

const TS_ITEM_STATUSES = new Set([
  "pending",
  "in-progress",
  "satisfied",
  "failed",
  "blocked-by-approval",
  "cancelled",
  "expired",
  "not-applicable",
]);

/** @param {string} s @param {number} max */
function capUtf16(s, max) {
  if (s.length <= max) return s;
  const suf = "... truncated";
  const take = Math.max(0, max - suf.length);
  return s.slice(0, take) + suf;
}

/** @param {unknown} v */
function isRecord(v) {
  return typeof v === "object" && v !== null;
}

/**
 * Stable client-side reasons for rejecting a chart (CHART_CONTRACT §4.2). Diagnostic only:
 * they are logged, never sent on the wire and never shown as user text.
 */
export const CHART_REJECTION_CODES = Object.freeze({
  KIND_INVALID: "CHART_KIND_INVALID",
  SERIES_EMPTY: "CHART_SERIES_EMPTY",
  SERIES_SHAPE: "CHART_SERIES_SHAPE",
  Y_NOT_FINITE_NUMBER: "CHART_Y_NOT_FINITE_NUMBER",
  PIE_SERIES_COUNT: "CHART_PIE_SERIES_COUNT",
  PIE_NEGATIVE: "CHART_PIE_NEGATIVE",
  PIE_ZERO_TOTAL: "CHART_PIE_ZERO_TOTAL",
  FIXED_DOMAIN_INVALID: "CHART_FIXED_DOMAIN_INVALID",
  ORIENTATION_INVALID: "CHART_ORIENTATION_INVALID",
  HISTOGRAM_INVALID: "CHART_HISTOGRAM_INVALID",
  BOXPLOT_INVALID: "CHART_BOXPLOT_INVALID",
  HEATMAP_INVALID: "CHART_HEATMAP_INVALID",
  STACK_MODE_INVALID: "CHART_STACK_MODE_INVALID",
  GROUP_INVALID: "CHART_GROUP_INVALID",
  GROUP_COLOR_INVALID: "CHART_GROUP_COLOR_INVALID",
});
/** Shared-colour keys a chart group may carry (CHART_CONTRACT §3.5, design §8.7): the palette slot count. */
export const CHART_GROUP_MAX_SHARED_KEYS = 24;
/** Chart group bounds and enums (CHART_CONTRACT §3.5, design §8.5). */
export const CHART_GROUP_MIN_MEMBERS = 2;
export const CHART_GROUP_MAX_MEMBERS = 6;
export const CHART_GROUP_LAYOUTS = Object.freeze(["auto", "stack", "grid"]);
export const CHART_GROUP_STATES = Object.freeze(["pending", "ready", "no-data", "error", "cancelled"]);

/** Bin counts a histogram payload may carry (CHART_CONTRACT §3.0 `HIST_MAX_BINS`). */
export const HIST_MAX_BINS = 50;
/** Registered binning methods (CHART_CONTRACT §3.0f). */
export const HISTOGRAM_METHODS = Object.freeze(["explicit_edges_v1", "equal_width_v1"]);
/** Relative tolerance of the density invariant; covers JSON round-trips, rejects magnitude errors. */
export const HISTOGRAM_DENSITY_REL_TOLERANCE = 1e-9;
/** Groups a boxplot payload may carry (CHART_CONTRACT §3.0 `BOX_MAX_GROUPS`). */
export const BOX_MAX_GROUPS = 24;
/** Outliers listed per group; the rest are only counted (CHART_CONTRACT §3.0 `BOX_MAX_SHOWN_OUTLIERS`). */
export const BOX_MAX_SHOWN_OUTLIERS = 20;
/** Registered box-summary methods (CHART_CONTRACT §3.0g). */
export const BOXPLOT_METHODS = Object.freeze(["tukey_1_5_iqr_linear_p_v1"]);
/** Heatmap payload bounds (CHART_CONTRACT §3.0 `HEATMAP_MAX_ROWS` / `HEATMAP_MAX_COLS`). */
export const HEATMAP_MAX_ROWS = 24;
export const HEATMAP_MAX_COLS = 48;

/**
 * Identity text for diagnostics from unvalidated metadata. Only the expected primitive types
 * are rendered; anything else (objects, symbols, null) becomes `-`, so the rejection path can
 * never throw on malformed producer or persisted data.
 * @param {unknown} v
 * @returns {string}
 */
function diagnosticIdentity(v) {
  if (typeof v === "string") return v.trim() || "-";
  if (typeof v === "number" && Number.isFinite(v)) return String(v);
  return "-";
}

/**
 * Log a rejected chart with its stable reason and identity, then yield the adapter's drop value.
 * Must not throw: the caller relies on it to drop exactly one chart (CHART_CONTRACT §4.2).
 * @param {string} code
 * @param {unknown} raw
 * @returns {null}
 */
export function rejectChart(code, raw, detail = "") {
  const rec = isRecord(raw) ? raw : {};
  console.warn(
    `[parler-ui chart] rejected ${code} chartId=${diagnosticIdentity(rec.chartId)} kind=${diagnosticIdentity(rec.kind)}${detail ? ` (${detail})` : ""}`
  );
  return null;
}

const nonNegativeIntegral = (v) => typeof v === "number" && Number.isFinite(v) && v >= 0 && Number.isInteger(v);

/**
 * Local invariants of a histogram payload (CHART_CONTRACT §3.0f / §4.2), mirroring the server
 * builder's checks so live and replayed data get the same verdict. Only relations between the
 * numbers given are checked; nothing is re-binned. Returns a reason, or `null` when valid.
 * @param {unknown} h
 * @returns {string | null}
 */
export function chartHistogramRejection(h) {
  if (!isRecord(h)) return "histogram payload missing";
  const { edges, counts, densities } = h;
  if (!Array.isArray(counts) || counts.length < 1 || counts.length > HIST_MAX_BINS) return "counts length";
  if (!Array.isArray(edges) || edges.length !== counts.length + 1) return "edges length";
  if (!Array.isArray(densities) || densities.length !== counts.length) return "densities length";
  for (let i = 0; i < edges.length; i++) {
    const e = edges[i];
    if (typeof e !== "number" || !Number.isFinite(e)) return `edges[${i}] not finite`;
    if (i > 0 && !(e > edges[i - 1])) return `edges not strictly increasing at ${i}`;
  }
  if (!counts.every(nonNegativeIntegral)) return "counts not non-negative integers";
  for (const k of ["validCount", "excludedCount", "belowRangeCount", "aboveRangeCount"]) {
    if (!nonNegativeIntegral(h[k])) return `${k} not a non-negative integer`;
  }
  const sum = counts.reduce((a, b) => a + b, 0);
  if (sum !== h.validCount - h.belowRangeCount - h.aboveRangeCount) return "Σcounts ≠ validCount − below − above";
  if (!(sum > 0)) return "Σcounts = 0";
  for (let i = 0; i < counts.length; i++) {
    const d = densities[i];
    if (typeof d !== "number" || !Number.isFinite(d) || d < 0) return `densities[${i}] not finite`;
    const expected = counts[i] / (sum * (edges[i + 1] - edges[i]));
    const ok = expected === 0 ? d === 0 : Math.abs(d - expected) / Math.abs(expected) <= HISTOGRAM_DENSITY_REL_TOLERANCE;
    if (!ok) return `densities[${i}] ≠ count / (Σcounts × width)`;
  }
  if (h.mode !== "count" && h.mode !== "density") return "mode";
  if (!HISTOGRAM_METHODS.includes(h.method)) return "method not registered";
  return null;
}

const finiteNumber = (v) => typeof v === "number" && Number.isFinite(v);

/**
 * Local invariants of a boxplot payload (CHART_CONTRACT §3.0g / §4.2), mirroring the server builder's
 * checks so live and replayed data get the same verdict. Only relations between the numbers given are
 * checked; no quantile, whisker or fence is recomputed. Returns a reason, or `null` when valid.
 * @param {unknown} b
 * @returns {string | null}
 */
export function chartBoxplotRejection(b) {
  if (!isRecord(b)) return "boxplot payload missing";
  const groups = b.groups;
  if (!Array.isArray(groups) || groups.length < 1 || groups.length > BOX_MAX_GROUPS) return "groups length";
  if (!BOXPLOT_METHODS.includes(b.method)) return "method not registered";
  const keys = new Set();
  for (let i = 0; i < groups.length; i++) {
    const g = groups[i];
    if (!isRecord(g)) return `groups[${i}] not an object`;
    const key = typeof g.key === "string" ? g.key.trim() : "";
    if (!key) return `groups[${i}].key empty`;
    if (keys.has(key)) return `groups[${i}].key duplicate`;
    keys.add(key);
    if (!nonNegativeIntegral(g.n) || g.n < 1) return `groups[${i}].n not a positive integer`;
    if (!nonNegativeIntegral(g.excludedCount)) return `groups[${i}].excludedCount not a non-negative integer`;
    const stats = ["min", "whiskerLow", "q1", "median", "q3", "whiskerHigh", "max"];
    for (const k of stats) if (!finiteNumber(g[k])) return `groups[${i}].${k} not finite`;
    for (let k = 1; k < stats.length; k++) {
      if (!(g[stats[k - 1]] <= g[stats[k]])) return `groups[${i}] statistics out of order (${stats[k - 1]} > ${stats[k]})`;
    }
    if (!nonNegativeIntegral(g.outlierCount) || g.outlierCount > g.n) return `groups[${i}].outlierCount`;
    if (!Array.isArray(g.outliers) || g.outliers.length !== Math.min(g.outlierCount, BOX_MAX_SHOWN_OUTLIERS)) {
      return `groups[${i}].outliers length ≠ min(outlierCount, ${BOX_MAX_SHOWN_OUTLIERS})`;
    }
    for (let k = 0; k < g.outliers.length; k++) {
      const v = g.outliers[k];
      if (!finiteNumber(v)) return `groups[${i}].outliers[${k}] not finite`;
      if (!(v < g.whiskerLow || v > g.whiskerHigh)) return `groups[${i}].outliers[${k}] inside the whiskers`;
      if (v < g.min || v > g.max) return `groups[${i}].outliers[${k}] outside [min, max]`;
    }
    if (g.outlierCount === 0 && (g.whiskerLow !== g.min || g.whiskerHigh !== g.max)) {
      return `groups[${i}] whiskers must reach min and max without outliers`;
    }
  }
  return null;
}

/**
 * Local invariants of a heatmap payload (CHART_CONTRACT §3.0h / §4.2): unique non-empty row and column
 * keys within the caps, a full `rows × cols` matrix of finite numbers or explicit `null`, at least one
 * number, and `missingCount` equal to the `null` count. `null` here is explicit missing data and does
 * not conflict with the series-kind rule that `y` must be a number: different field, different meaning.
 * @param {unknown} h
 * @returns {string | null}
 */
export function chartHeatmapRejection(h) {
  if (!isRecord(h)) return "heatmap payload missing";
  const keys = (list, name, max) => {
    if (!Array.isArray(list) || list.length < 1 || list.length > max) return `${name} length`;
    const seen = new Set();
    for (let i = 0; i < list.length; i++) {
      const k = list[i];
      if (typeof k !== "string" || !k.trim()) return `${name}[${i}] empty`;
      if (seen.has(k)) return `${name}[${i}] duplicate`;
      seen.add(k);
    }
    return null;
  };
  const rowReason = keys(h.rows, "rows", HEATMAP_MAX_ROWS);
  if (rowReason) return rowReason;
  const colReason = keys(h.cols, "cols", HEATMAP_MAX_COLS);
  if (colReason) return colReason;
  const values = h.values;
  if (!Array.isArray(values) || values.length !== h.rows.length) return "values length";
  let missing = 0;
  let present = 0;
  for (let r = 0; r < values.length; r++) {
    const row = values[r];
    if (!Array.isArray(row) || row.length !== h.cols.length) return `values[${r}] length`;
    for (let c = 0; c < row.length; c++) {
      const v = row[c];
      if (v === null) missing += 1;
      else if (finiteNumber(v)) present += 1;
      else return `values[${r}][${c}] not a finite number or null`;
    }
  }
  if (present === 0) return "every cell missing";
  if (h.missingCount !== missing) return "missingCount ≠ null cells";
  if (typeof h.valueLabel !== "string") return "valueLabel";
  return null;
}

/**
 * Local invariants of a supplied `stackMode` (CHART_CONTRACT §3 / §4.2, design §7.4 C2a-2). The series
 * have already passed the shared shape rules. Returns a reason, or `null` when valid.
 * @param {unknown} kind @param {unknown} mode @param {unknown[]} series
 * @returns {string | null}
 */
export function chartStackModeRejection(kind, mode, series) {
  if (kind !== "bar") return "stackMode on a non-bar kind";
  if (mode !== "stacked" && mode !== "percent") return "stackMode value";
  if (!Array.isArray(series) || series.length < 2) return "stackMode needs two or more series";
  if (mode === "percent") {
    for (let si = 0; si < series.length; si++) {
      const ys = /** @type {{ y: number[] }} */ (series[si]).y;
      for (let i = 0; i < ys.length; i++) {
        if (ys[i] < 0) return `percent with a negative value (series ${si}, index ${i})`;
      }
    }
  }
  return null;
}

/**
 * Local invariants of a chart-group manifest (CHART_CONTRACT §3.5 / design §8.5). Returns a reason, or `null`.
 * @param {unknown} g
 * @returns {string | null}
 */
export function chartGroupRejection(g) {
  if (!isRecord(g)) return "manifest missing";
  if (typeof g.groupId !== "string" || !g.groupId.trim()) return "groupId";
  if (!Number.isInteger(g.revision) || g.revision < 1) return "revision";
  if (typeof g.title !== "string") return "title";
  if (!CHART_GROUP_LAYOUTS.includes(g.layout)) return "layout";
  if (typeof g.final !== "boolean") return "final";
  const members = g.members;
  if (!Array.isArray(members) || members.length < CHART_GROUP_MIN_MEMBERS || members.length > CHART_GROUP_MAX_MEMBERS) return "members length";
  const keys = new Set();
  const chartIds = new Set();
  const counts = { ready: 0, noData: 0, error: 0, cancelled: 0 };
  for (let i = 0; i < members.length; i++) {
    const m = members[i];
    if (!isRecord(m)) return `members[${i}] not an object`;
    if (typeof m.key !== "string" || !m.key.trim()) return `members[${i}].key`;
    if (keys.has(m.key)) return `members[${i}].key duplicate`;
    keys.add(m.key);
    if (m.order !== i) return `members[${i}].order`;
    if (typeof m.name !== "string") return `members[${i}].name`;
    if (m.expectedType !== "chart") return `members[${i}].expectedType`;
    if (!CHART_GROUP_STATES.includes(m.state)) return `members[${i}].state`;
    if (m.state === "ready") {
      if (typeof m.chartId !== "string" || !m.chartId.trim()) return `members[${i}] ready without chartId`;
      if (chartIds.has(m.chartId)) return `members[${i}].chartId duplicate`;
      chartIds.add(m.chartId);
      counts.ready += 1;
    } else if (m.chartId !== undefined) {
      return `members[${i}] chartId on a ${m.state} member`;
    }
    if (m.state === "no-data" || m.state === "error") {
      if (typeof m.code !== "string" || !m.code.trim()) return `members[${i}] ${m.state} without code`;
      counts[m.state === "no-data" ? "noData" : "error"] += 1;
    }
    if (m.state === "cancelled") counts.cancelled += 1;
    if (m.message !== undefined && typeof m.message !== "string") return `members[${i}].message`;
  }
  const s = g.summary;
  if (!isRecord(s)) return "summary";
  if (s.expected !== members.length) return "summary.expected";
  for (const k of ["ready", "noData", "error", "cancelled"]) {
    if (s[k] !== counts[k]) return `summary.${k}`;
  }
  if (s.final !== g.final) return "summary.final";
  return null;
}

/**
 * C3b-2a (CHART_CONTRACT §3.5 / design §8.7): the shared-colour extension of a manifest — `sharedCategories`
 * absent or `{ dimension: non-empty string, keys: ≤ 24 unique non-empty strings }`, every member's `colorShared`
 * absent or boolean. Returns a reason, or `null` when the extension is valid or absent.
 * @param {Record<string, unknown>} g
 * @returns {string | null}
 */
export function chartGroupColorRejection(g) {
  const shared = g.sharedCategories;
  if (shared !== undefined) {
    if (!isRecord(shared)) return "sharedCategories not an object";
    if (typeof shared.dimension !== "string" || !shared.dimension.trim()) return "sharedCategories.dimension";
    if (!Array.isArray(shared.keys) || shared.keys.length > CHART_GROUP_MAX_SHARED_KEYS) return "sharedCategories.keys length";
    const seen = new Set();
    for (let i = 0; i < shared.keys.length; i++) {
      const k = shared.keys[i];
      if (typeof k !== "string" || !k.trim()) return `sharedCategories.keys[${i}] empty`;
      if (seen.has(k)) return `sharedCategories.keys[${i}] duplicate`;
      seen.add(k);
    }
  }
  const members = Array.isArray(g.members) ? g.members : [];
  for (let i = 0; i < members.length; i++) {
    const m = members[i];
    if (isRecord(m) && m.colorShared !== undefined && typeof m.colorShared !== "boolean") return `members[${i}].colorShared`;
    if (isRecord(m) && m.colorShared === true && shared === undefined) return `members[${i}].colorShared without sharedCategories`;
  }
  return null;
}

/**
 * Validate a `chart_group` manifest (wire frame or history `groups[]` entry); returns the manifest or `null`
 * after logging `CHART_GROUP_INVALID`. Invalid manifests only lose the group: the charts stay single cards.
 * An invalid shared-colour extension (C3b-2a) loses only the extension under `CHART_GROUP_COLOR_INVALID`: the
 * group card stays and its members keep their per-chart colours.
 * @param {unknown} raw
 * @returns {import('./types.js').ChartGroupManifest | null}
 */
export function asChartGroupManifest(raw) {
  const reason = chartGroupRejection(raw);
  if (reason) {
    const rec = isRecord(raw) ? raw : {};
    console.warn(`[parler-ui chart] rejected ${CHART_REJECTION_CODES.GROUP_INVALID} groupId=${diagnosticIdentity(rec.groupId)} (${reason})`);
    return null;
  }
  const rec = /** @type {Record<string, unknown>} */ (raw);
  const colorReason = chartGroupColorRejection(rec);
  if (colorReason) {
    console.warn(`[parler-ui chart] rejected ${CHART_REJECTION_CODES.GROUP_COLOR_INVALID} groupId=${diagnosticIdentity(rec.groupId)} (${colorReason}); group kept without shared colours`);
    const stripped = { ...rec, members: /** @type {Record<string, unknown>[]} */ (rec.members).map((m) => {
      const copy = { ...m };
      delete copy.colorShared;
      return copy;
    }) };
    delete stripped.sharedCategories;
    return /** @type {import('./types.js').ChartGroupManifest} */ (stripped);
  }
  return /** @type {import('./types.js').ChartGroupManifest} */ (raw);
}

/**
 * Shape and Y rules shared by every chart kind and X mode (CHART_CONTRACT §3.3 inv. 1 and 3):
 * each series must have non-empty `x` / `y` arrays of equal length, and every `y` must be a
 * finite JSON number. `null`, missing slots, booleans, strings (including numeric strings),
 * `NaN` and `Infinity` are rejected; the client never coerces. One invalid series rejects the
 * whole chart. Used by the live/history adapter and again by the renderer as defense in depth.
 *
 * @param {unknown} series
 * @returns {string | null} rejection code, or `null` when every series is valid
 */
export function chartSeriesRejection(series) {
  if (!Array.isArray(series) || series.length === 0) return CHART_REJECTION_CODES.SERIES_EMPTY;
  for (const s of series) {
    if (!isRecord(s)) return CHART_REJECTION_CODES.SERIES_SHAPE;
    const x = s.x;
    const y = s.y;
    if (!Array.isArray(x) || !Array.isArray(y) || x.length === 0 || x.length !== y.length) {
      return CHART_REJECTION_CODES.SERIES_SHAPE;
    }
    for (let i = 0; i < y.length; i++) {
      if (!(i in y)) return CHART_REJECTION_CODES.Y_NOT_FINITE_NUMBER;
      const v = y[i];
      if (typeof v !== "number" || !Number.isFinite(v)) {
        return CHART_REJECTION_CODES.Y_NOT_FINITE_NUMBER;
      }
    }
  }
  return null;
}

/** @param {unknown} raw */
function asYReferenceLines(raw) {
  if (!Array.isArray(raw) || raw.length === 0) return undefined;
  /** @type {{ y: number; label?: string; role?: string }[]} */
  const out = [];
  for (const item of raw.slice(0, 12)) {
    if (!isRecord(item)) continue;
    const y = Number(item.y);
    if (!Number.isFinite(y)) continue;
    const label =
      item.label === null || item.label === undefined
        ? undefined
        : String(item.label);
    let role;
    if (typeof item.role === "string" && REF_ROLES.has(item.role)) {
      role = item.role;
    }
    out.push(
      role !== undefined
        ? { y, label: label || undefined, role }
        : { y, label: label || undefined }
    );
  }
  return out.length ? out : undefined;
}

/** @param {unknown} raw @param {string} kind */
function normalizeRequestedTimeRange(raw, kind) {
  if (kind === "bar" || kind === "pie") return undefined;
  if (!isRecord(raw)) return undefined;
  const start = raw.start;
  const end = raw.end;
  if (typeof start !== "string" || typeof end !== "string") return undefined;
  const s = start.trim();
  const e = end.trim();
  if (!s || !e) return undefined;
  return { start: s, end: e };
}

/**
 * Pie-specific rules on top of {@link chartSeriesRejection}: exactly one series, no negative
 * value, positive total.
 * @param {unknown[]} series already shape/Y validated
 * @returns {{ series: import('./types.js').ChartSeries[] } | { code: string }}
 */
function validatedPieSeries(series) {
  if (series.length !== 1) return { code: CHART_REJECTION_CODES.PIE_SERIES_COUNT };
  const s0 = /** @type {{ x: unknown[]; y: number[] }} */ (series[0]);
  let sum = 0;
  for (const n of s0.y) {
    if (n < 0) return { code: CHART_REJECTION_CODES.PIE_NEGATIVE };
    sum += n;
  }
  if (!(sum > 0)) return { code: CHART_REJECTION_CODES.PIE_ZERO_TOTAL };
  return { series: /** @type {import('./types.js').ChartSeries[]} */ ([{ ...s0 }]) };
}

/**
 * Elapsed-mode X rule (non-negative integer seconds) on top of {@link chartSeriesRejection};
 * `y` is kept as validated numbers, never coerced.
 * @param {unknown[]} series already shape/Y validated
 * @returns {import('./types.js').ChartSeries[] | null}
 */
function validatedElapsedLineSeries(series) {
  /** @type {import('./types.js').ChartSeries[]} */
  const out = [];
  for (const raw of series) {
    const s = /** @type {{ name?: unknown; x: unknown[]; y: number[] }} */ (raw);
    for (const xv of s.x) {
      if (!/^\d+$/.test(String(xv).trim())) return null;
    }
    out.push({
      ...s,
      name: String(s.name ?? "series"),
      x: s.x.map((v) => String(v).trim()),
      y: s.y,
    });
  }
  return out;
}

/**
 * Normalized-mode X rule (fraction in [0, 1]) on top of {@link chartSeriesRejection};
 * `y` is kept as validated numbers, never coerced.
 * @param {unknown[]} series already shape/Y validated
 * @returns {import('./types.js').ChartSeries[] | null}
 */
function validatedNormalizedLineSeries(series) {
  /** @type {import('./types.js').ChartSeries[]} */
  const out = [];
  for (const raw of series) {
    const s = /** @type {{ name?: unknown; x: unknown[]; y: number[] }} */ (raw);
    for (const xv of s.x) {
      const xn = Number(String(xv).trim());
      if (!Number.isFinite(xn) || xn < 0 || xn > 1) return null;
    }
    out.push({
      ...s,
      name: String(s.name ?? "series"),
      x: s.x.map((v) => String(v).trim()),
      y: s.y,
    });
  }
  return out;
}

/** Fixed-domain overlay modes apply to line/scatter charts only (CHART_CONTRACT §3). */
function isLineScatterKind(kind) {
  return kind === "line" || kind === "scatter";
}

/** @param {unknown} v @returns {ChartBlock | null} */
export function asChartBlock(v) {
  if (!isRecord(v)) return null;
  const kind = v.kind;
  if (kind !== "line" && kind !== "bar" && kind !== "scatter" && kind !== "pie" && kind !== "histogram" && kind !== "boxplot" && kind !== "heatmap") {
    return rejectChart(CHART_REJECTION_CODES.KIND_INVALID, v);
  }
  if (kind === "heatmap") {
    // CHART_CONTRACT §3.0h: a kind-specific payload, no series, no reference lines, no fixed X mode.
    if (v.series !== undefined) return rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, v, "series present");
    if (v.histogram !== undefined || v.boxplot !== undefined) return rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, v, "other kind payload on heatmap");
    if (v.y_reference_lines !== undefined) return rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, v, "y_reference_lines present");
    if (v.xAxisMode === "elapsed" || v.xAxisMode === "normalized") return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    if (v.orientation !== undefined) return rejectChart(CHART_REJECTION_CODES.ORIENTATION_INVALID, v);
    const reason = chartHeatmapRejection(v.heatmap);
    if (reason) return rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, v, reason);
    const block = { ...v };
    delete block.requested_time_range;
    return /** @type {ChartBlock} */ (block);
  }
  if (v.heatmap !== undefined) {
    return rejectChart(CHART_REJECTION_CODES.HEATMAP_INVALID, v, "heatmap payload on another kind");
  }
  if (kind === "boxplot") {
    // CHART_CONTRACT §3.0g: a kind-specific payload, no series, no fixed X mode; reference lines allowed.
    if (v.series !== undefined) return rejectChart(CHART_REJECTION_CODES.BOXPLOT_INVALID, v, "series present");
    if (v.histogram !== undefined) return rejectChart(CHART_REJECTION_CODES.BOXPLOT_INVALID, v, "histogram payload on boxplot");
    if (v.xAxisMode === "elapsed" || v.xAxisMode === "normalized") return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    if (v.orientation !== undefined) return rejectChart(CHART_REJECTION_CODES.ORIENTATION_INVALID, v);
    const reason = chartBoxplotRejection(v.boxplot);
    if (reason) return rejectChart(CHART_REJECTION_CODES.BOXPLOT_INVALID, v, reason);
    const block = { ...v };
    const refs = asYReferenceLines(v.y_reference_lines);
    if (refs) block.y_reference_lines = refs;
    else delete block.y_reference_lines;
    delete block.requested_time_range;
    return /** @type {ChartBlock} */ (block);
  }
  if (v.boxplot !== undefined) {
    return rejectChart(CHART_REJECTION_CODES.BOXPLOT_INVALID, v, "boxplot payload on another kind");
  }
  if (kind === "histogram") {
    // CHART_CONTRACT §3.0f: a kind-specific payload, no series, no reference lines, no fixed X mode.
    if (v.series !== undefined) return rejectChart(CHART_REJECTION_CODES.HISTOGRAM_INVALID, v, "series present");
    if (v.y_reference_lines !== undefined) return rejectChart(CHART_REJECTION_CODES.HISTOGRAM_INVALID, v, "y_reference_lines present");
    if (v.xAxisMode === "elapsed" || v.xAxisMode === "normalized") return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    if (v.orientation !== undefined) return rejectChart(CHART_REJECTION_CODES.ORIENTATION_INVALID, v);
    const reason = chartHistogramRejection(v.histogram);
    if (reason) return rejectChart(CHART_REJECTION_CODES.HISTOGRAM_INVALID, v, reason);
    const block = { ...v };
    delete block.requested_time_range;
    return /** @type {ChartBlock} */ (block);
  }
  if (v.histogram !== undefined) {
    return rejectChart(CHART_REJECTION_CODES.HISTOGRAM_INVALID, v, "histogram payload on a series kind");
  }
  const series = v.series;
  const seriesCode = chartSeriesRejection(series);
  if (seriesCode) return rejectChart(seriesCode, v);
  const validSeries = /** @type {unknown[]} */ (series);
  const fixedDomainMode = v.xAxisMode === "elapsed" || v.xAxisMode === "normalized";
  if (fixedDomainMode && !isLineScatterKind(kind)) {
    return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
  }
  // CHART_CONTRACT §3/§4.2: `orientation` is defined for bar only and takes exactly two strings.
  if (v.orientation !== undefined) {
    const o = v.orientation;
    if (kind !== "bar" || (o !== "vertical" && o !== "horizontal")) {
      return rejectChart(CHART_REJECTION_CODES.ORIENTATION_INVALID, v);
    }
  }
  // CHART_CONTRACT §3/§4.2 (C2a-2): `stackMode` is bar-only, exactly `stacked` / `percent` on the wire
  // (grouped is the absent default), needs two or more series, and percent needs non-negative values.
  if (v.stackMode !== undefined) {
    const reason = chartStackModeRejection(kind, v.stackMode, validSeries);
    if (reason) return rejectChart(CHART_REJECTION_CODES.STACK_MODE_INVALID, v, reason);
  }
  /** @type {unknown[]} */
  let seriesOut = validSeries;
  if (kind === "pie") {
    const pie = validatedPieSeries(validSeries);
    if ("code" in pie) return rejectChart(pie.code, v);
    seriesOut = pie.series;
  } else if (v.xAxisMode === "elapsed") {
    const dom = v.elapsedDomain;
    if (!isRecord(dom)) return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    const start = Number(dom.start);
    const end = Number(dom.end);
    if (!Number.isFinite(start) || !Number.isFinite(end) || end < start) {
      return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    }
    const elapsedOk = validatedElapsedLineSeries(validSeries);
    if (!elapsedOk) return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    seriesOut = elapsedOk;
  } else if (v.xAxisMode === "normalized") {
    const dom = v.normalizedDomain;
    if (!isRecord(dom)) return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    const start = Number(dom.start);
    const end = Number(dom.end);
    if (!Number.isFinite(start) || !Number.isFinite(end) || start !== 0 || end !== 1) {
      return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    }
    const normalizedOk = validatedNormalizedLineSeries(validSeries);
    if (!normalizedOk) return rejectChart(CHART_REJECTION_CODES.FIXED_DOMAIN_INVALID, v);
    seriesOut = normalizedOk;
  }
  const refs = kind === "pie" ? undefined : asYReferenceLines(v.y_reference_lines);
  const rtr =
    fixedDomainMode
      ? undefined
      : normalizeRequestedTimeRange(v.requested_time_range, kind);
  const block = { ...v, series: seriesOut };
  if (refs) block.y_reference_lines = refs;
  else delete block.y_reference_lines;
  if (rtr) block.requested_time_range = rtr;
  else delete block.requested_time_range;
  if (fixedDomainMode) delete block.requested_time_range;
  return /** @type {ChartBlock} */ (block);
}

/** @param {unknown} v @returns {TableBlock | null} */
export function asTableBlock(v) {
  if (!isRecord(v)) return null;
  const kind = String(v.kind ?? "").trim();
  if (kind !== "entity-list") return null;
  const colsRaw = v.columns;
  if (!Array.isArray(colsRaw) || colsRaw.length === 0) return null;
  /** @type {import('./types.js').TableColumn[]} */
  const columns = [];
  for (const c of colsRaw) {
    if (!isRecord(c)) return null;
    const key = String(c.key ?? "").trim();
    if (!key) return null;
    const label = String(c.label ?? "").trim() || key;
    const baseType = String(c.baseType ?? "STRING").trim() || "STRING";
    columns.push({ key, label, baseType });
  }
  const rowsRaw = v.rows;
  if (!Array.isArray(rowsRaw)) return null;
  /** @type {Record<string, unknown>[]} */
  const rows = [];
  for (const r of rowsRaw) {
    if (!isRecord(r)) return null;
    rows.push({ ...r });
  }
  const exportStatus = String(v.exportStatus ?? "").trim();
  if (!exportStatus) return null;
  const exportMessage =
    v.exportMessage == null || String(v.exportMessage).trim() === ""
      ? null
      : String(v.exportMessage);
  const exportFile =
    v.exportFile == null || String(v.exportFile).trim() === ""
      ? null
      : String(v.exportFile);
  const exportRepository =
    v.exportRepository == null || String(v.exportRepository).trim() === ""
      ? null
      : String(v.exportRepository);
  const exportDownloadUrl =
    v.exportDownloadUrl == null || String(v.exportDownloadUrl).trim() === ""
      ? null
      : String(v.exportDownloadUrl);
  const shownRows =
    typeof v.shownRows === "number" && Number.isFinite(v.shownRows)
      ? v.shownRows
      : rows.length;
  const totalRows =
    typeof v.totalRows === "number" && Number.isFinite(v.totalRows)
      ? v.totalRows
      : rows.length;
  /** @type {TableBlock} */
  const block = {
    kind,
    columns,
    rows,
    shownRows,
    totalRows,
    exportStatus,
    exportMessage,
    exportFile,
    exportRepository,
    exportDownloadUrl,
  };
  if (v.sourceCacheId != null && String(v.sourceCacheId).trim() !== "") {
    block.sourceCacheId = String(v.sourceCacheId).trim();
  }
  if (v.cacheId !== undefined) {
    block.cacheId = v.cacheId === null ? null : String(v.cacheId);
  }
  if (v.presentationTitle != null && String(v.presentationTitle).trim() !== "") {
    block.presentationTitle = String(v.presentationTitle).trim();
  }
  return block;
}

/** @param {unknown} raw @returns {UiEvent | null} */
export function wireToUiEvent(raw) {
  if (!isRecord(raw)) return null;
  const t = raw.type;
  const requestId = String(raw.request_id ?? "").trim();
  switch (t) {
    case "session.ack":
      return { type: "session.ack", requestId };
    case "activity":
      return {
        type: "assistant.activity",
        requestId,
        text: String(raw.message ?? ""),
      };
    case "content.delta":
      return {
        type: "assistant.append",
        requestId,
        text: String(raw.delta ?? ""),
      };
    case "chart": {
      const chart = asChartBlock(raw.chart);
      if (!chart) return null;
      return { type: "assistant.chart", requestId, chart };
    }
    case "table": {
      const table = asTableBlock(raw.table);
      if (!table) return null;
      return { type: "assistant.table", requestId, table };
    }
    case "chart_group": {
      const group = asChartGroupManifest(raw.group);
      if (!group) return null;
      return { type: "assistant.chartGroup", requestId, group };
    }
    case "done": {
      const assistantMessageId = String(raw.assistant_message_id ?? "").trim();
      const completedAt = String(raw.completed_at ?? "").trim();
      const llmUsage = llmUsageFromWireRecord(raw.llm_usage);
      return {
        type: "session.done",
        requestId,
        ...(assistantMessageId ? { assistantMessageId } : {}),
        ...(completedAt ? { completedAt } : {}),
        ...(llmUsage ? { llmUsage } : {}),
      };
    }
    case "error":
      return {
        type: "session.error",
        requestId,
        message: String(raw.message ?? "error"),
        code: raw.code !== undefined ? String(raw.code) : undefined,
      };
    case "session.superseded": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      if (!conversationId) return null;
      return {
        type: "session.superseded",
        requestId: String(raw.request_id ?? "").trim(),
        message: String(raw.message ?? "This session is no longer live."),
        code: raw.code !== undefined ? String(raw.code) : undefined,
        conversationId,
      };
    }
    case "session.cancelled": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      const requestIdCancelled = String(raw.request_id ?? "").trim();
      if (!conversationId || !requestIdCancelled) return null;
      return {
        type: "session.cancelled",
        requestId: requestIdCancelled,
        conversationId,
        reason: raw.reason !== undefined ? String(raw.reason) : undefined,
        message: raw.message !== undefined ? String(raw.message) : undefined,
      };
    }
    case "approval.required": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      const pendingId = String(raw.pending_id ?? "").trim();
      if (!conversationId || !requestId || !pendingId) return null;
      return {
        type: "approval.required",
        requestId,
        conversationId,
        pendingId,
        expiresAt: String(raw.expires_at ?? ""),
        toolName: String(raw.tool_name ?? ""),
        summary: asApprovalSummary(raw.summary),
        actions: asApprovalActions(raw.actions),
      };
    }
    case "approval.resolved": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      const pendingId = String(raw.pending_id ?? "").trim();
      if (!conversationId || !requestId || !pendingId) return null;
      const oc = raw.outcome;
      const outcome =
        oc === "approved" || oc === "cancelled" || oc === "expired" || oc === "rejected"
          ? oc
          : "cancelled";
      /** @type {{ code?: string, message: string } | undefined} */
      let err;
      if (isRecord(raw.error)) {
        err = {
          code:
            raw.error.code !== undefined ? String(raw.error.code) : undefined,
          message: String(raw.error.message ?? ""),
        };
      }
      const hrs = raw.hitl_resolution_source;
      /** @type {'gateway_user_stop' | undefined} */
      const hitlResolutionSource =
        hrs === "gateway_user_stop" ? "gateway_user_stop" : undefined;
      return {
        type: "approval.resolved",
        requestId,
        conversationId,
        pendingId,
        outcome,
        executed: raw.executed === undefined ? undefined : Boolean(raw.executed),
        error: err,
        ...(hitlResolutionSource ? { hitlResolutionSource } : {}),
      };
    }
    case "tabular.tool_success": {
      const payload = raw.payload;
      if (!isRecord(payload)) return null;
      return {
        type: "assistant.insightEnvelope",
        requestId,
        toolSuccessPayload: payload,
      };
    }
    case "task.state": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      if (!conversationId || !requestId) return null;
      const sv = raw.schemaVersion;
      if (sv !== 1 && sv !== "1") return null;
      const snapshot = asTaskStateWireSnapshot(raw);
      if (!snapshot) return null;
      return { type: "assistant.taskState", requestId, snapshot };
    }
    case "rate_control.status": {
      const conversationId = String(raw.conversation_id ?? "").trim();
      if (!conversationId || !requestId) return null;
      const st = String(raw.status ?? "").trim();
      if (st === "waiting") {
        const reasonRaw = raw.reason;
        const reason =
          reasonRaw !== undefined && String(reasonRaw).trim() !== ""
            ? String(reasonRaw).trim()
            : undefined;
        const waitMs =
          typeof raw.wait_ms === "number" && Number.isFinite(raw.wait_ms)
            ? raw.wait_ms
            : undefined;
        const retryAfterMs =
          typeof raw.retry_after_ms === "number" && Number.isFinite(raw.retry_after_ms)
            ? raw.retry_after_ms
            : undefined;
        return {
          type: "assistant.rateControlStatus",
          requestId,
          waiting: true,
          ...(reason ? { reason } : {}),
          ...(waitMs !== undefined ? { waitMs } : {}),
          ...(retryAfterMs !== undefined ? { retryAfterMs } : {}),
        };
      }
      if (st === "resumed") {
        return { type: "assistant.rateControlStatus", requestId, waiting: false };
      }
      return null;
    }
    default:
      return null;
  }
}

/**
 * Validates **`task.state`** (v1b) with defense-in-depth caps; drops **`targetName`** on items (wire omits raw target).
 * @param {Record<string, unknown>} raw
 * @returns {import('./types.js').TaskStateSnapshot | null}
 */
function asTaskStateWireSnapshot(raw) {
  const status = String(raw.status ?? "").trim();
  if (!status) return null;
  const summary = raw.summary;
  if (!isRecord(summary)) return null;
  const itemsRaw = raw.items;
  if (!Array.isArray(itemsRaw)) return null;
  /** @type {unknown[]} */
  const itemsOut = [];
  for (let i = 0; i < itemsRaw.length && itemsOut.length < TS_MAX_ITEMS_PER_FRAME; i++) {
    const it = asTaskStateWireItem(itemsRaw[i]);
    if (it != null) itemsOut.push(it);
  }
  let title =
    raw.title === undefined || raw.title === null
      ? undefined
      : capUtf16(String(raw.title), TS_MAX_TITLE_CHARS);
  /** @type {import('./types.js').TaskStateSnapshot} */
  const snap = {
    schemaVersion: 1,
    status,
    title,
    summary: sanitizeSummaryObject(summary),
    items: itemsOut,
  };
  const frameLen = estimateTaskStateSnapshotUtf16(snap);
  if (frameLen > TS_MAX_FRAME_CHARS) {
    return null;
  }
  return snap;
}

/**
 * Shallow summary copy: numbers and small strings only; caps string values.
 * @param {Record<string, unknown>} summary
 */
function sanitizeSummaryObject(summary) {
  /** @type {Record<string, unknown>} */
  const out = {};
  for (const k of Object.keys(summary)) {
    const v = summary[k];
    if (typeof v === "number" && Number.isFinite(v)) {
      out[k] = v;
    } else if (typeof v === "string") {
      out[k] = capUtf16(v, 64);
    } else if (typeof v === "boolean") {
      out[k] = v;
    }
  }
  return out;
}

/**
 * @param {import('./types.js').TaskStateSnapshot} snap
 */
function estimateTaskStateSnapshotUtf16(snap) {
  try {
    return JSON.stringify({
      type: "task.state",
      schemaVersion: snap.schemaVersion,
      status: snap.status,
      title: snap.title,
      summary: snap.summary,
      items: snap.items,
      request_id: "",
      conversation_id: "",
    }).length;
  } catch {
    return TS_MAX_FRAME_CHARS + 1;
  }
}

/**
 * @param {unknown} raw
 * @returns {Record<string, unknown> | null}
 */
function asTaskStateWireItem(raw) {
  if (!isRecord(raw)) return null;
  const id = String(raw.id ?? "").trim();
  if (!id) return null;
  let st = String(raw.status ?? "pending").trim();
  if (!TS_ITEM_STATUSES.has(st)) {
    st = "pending";
  }
  const labelRaw = raw.label != null ? String(raw.label) : "";
  const label = capUtf16(labelRaw, TS_MAX_LABEL_CHARS);
  /** @type {Record<string, unknown>} */
  const out = {
    id,
    source: String(raw.source ?? "skill"),
    kind: String(raw.kind ?? "evidence"),
    label,
    status: st,
  };
  if (raw.tool != null && String(raw.tool).trim() !== "") {
    out.tool = String(raw.tool).trim();
  }
  if (isRecord(raw.match)) {
    const m = { ...raw.match };
    if ("targetName" in m) delete m.targetName;
    if (Object.keys(m).length > 0) out.match = m;
  }
  if (Array.isArray(raw.evidenceIds)) {
    out.evidenceIds = raw.evidenceIds
      .slice(0, 32)
      .map((x) => String(x))
      .filter((s) => s.length > 0);
  }
  if (typeof raw.observedCount === "number" && Number.isFinite(raw.observedCount)) {
    out.observedCount = raw.observedCount;
  }
  if (raw.summary != null) {
    out.summary = capUtf16(String(raw.summary), TS_MAX_ITEM_SUMMARY_CHARS);
  }
  if (typeof raw.updatedAtEpochMillis === "number" && Number.isFinite(raw.updatedAtEpochMillis)) {
    out.updatedAtEpochMillis = raw.updatedAtEpochMillis;
  }
  return out;
}

/** @param {unknown} raw */
function asApprovalSummary(raw) {
  if (!isRecord(raw)) {
    return { title: "", lines: [] };
  }
  const title = String(raw.title ?? "");
  /** @type {{ label: string, value: string }[]} */
  const lines = [];
  if (Array.isArray(raw.lines)) {
    for (const x of raw.lines) {
      if (!isRecord(x)) continue;
      lines.push({
        label: String(x.label ?? ""),
        value: String(x.value ?? ""),
      });
    }
  }
  return { title, lines };
}

/** @param {unknown} raw @returns {('approve'|'cancel'|'reject_with_comment')[]} */
function asApprovalActions(raw) {
  if (!Array.isArray(raw)) return [];
  /** @type {('approve'|'cancel'|'reject_with_comment')[]} */
  const out = [];
  for (const x of raw) {
    if (x === "approve" || x === "cancel" || x === "reject_with_comment") {
      out.push(x);
    }
  }
  return out;
}
