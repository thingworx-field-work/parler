/**
 * Per-chart view state (chart-enhancement design §4.3, §4.4): UI-internal fields that never
 * enter the ChartBlock. The widget instance owns the states keyed by conversation + request +
 * artifact stable key; the card component reads a state and asks for changes. Series keys are
 * the original series indexes of the chart snapshot. Pure; no DOM.
 *
 * @typedef {object} ChartViewState
 * @property {string} snapshot signature of the chart snapshot the state was built for
 * @property {number[]} hiddenSeriesKeys original series indexes currently hidden (sorted)
 * @property {"full" | "visible"} yDomainPolicy `full` keeps every series and reference line in
 *   the Y domain; `visible` fits the Y domain to the visible series only
 * @property {number | null} focusedSlice pie: focused slice entry index
 * @property {boolean} notesOpen data-notes disclosure open
 * @property {[number, number] | null} viewXDomain zoomed X view, as numbers in the chart's X domain
 *   (epoch ms, elapsed seconds, normalized fraction, numeric value); null means the full domain
 * @property {[number, number] | null} selectedRange marked X range in the same units; display only
 */

import { chartXDomainInfo, clampXRange, clampXSelection, formatXRange } from "./chartXDomain.js";

export const Y_DOMAIN_POLICIES = Object.freeze(["full", "visible"]);

/** Storage key for a chart's view state inside one widget instance. */
export function chartViewStateKey(conversationId, requestId, artifactKey) {
  const part = (v) => String(v ?? "").trim();
  return `${part(conversationId)}|${part(requestId)}|${part(artifactKey)}`;
}

/** FNV-1a over every value of an array, so any interior change alters the signature. */
function hashValues(values) {
  let h = 0x811c9dc5;
  for (const v of values) {
    const s = `${typeof v}:${String(v)}\u0001`;
    for (let i = 0; i < s.length; i++) {
      h ^= s.charCodeAt(i);
      h = Math.imul(h, 0x01000193) >>> 0;
    }
  }
  return h.toString(16).padStart(8, "0");
}

/** @type {WeakMap<object, string>} */
const signatureCache = new WeakMap();

/**
 * Signature of the data snapshot: kind plus, per series in order, its name, length and a hash
 * of every x and y value. Any interior change, reorder or replacement therefore changes the
 * signature, and a different snapshot under the same identity resets series selection so
 * original indexes cannot point at the wrong series (§4.4). Cached per chart object.
 * @param {import('./types.js').ChartBlock | null | undefined} chart
 */
export function chartSnapshotSignature(chart) {
  if (!chart) return "";
  if (chart.kind === "heatmap") {
    const cachedHeat = signatureCache.get(chart);
    if (cachedHeat !== undefined) return cachedHeat;
    const h = chart.heatmap && typeof chart.heatmap === "object" ? chart.heatmap : {};
    const flat = [...(Array.isArray(h.rows) ? h.rows : []), "\u0002", ...(Array.isArray(h.cols) ? h.cols : []), "\u0002"];
    for (const row of Array.isArray(h.values) ? h.values : []) flat.push(...(Array.isArray(row) ? row : []), "\u0003");
    const sig = `heatmap|${String(h.valueLabel ?? "")}:${hashValues(flat)}`;
    signatureCache.set(chart, sig);
    return sig;
  }
  if (chart.kind === "boxplot") {
    const cachedBox = signatureCache.get(chart);
    if (cachedBox !== undefined) return cachedBox;
    const b = chart.boxplot && typeof chart.boxplot === "object" ? chart.boxplot : {};
    const groups = Array.isArray(b.groups) ? b.groups : [];
    const flat = [];
    for (const g of groups) {
      const r = g && typeof g === "object" ? g : {};
      flat.push(r.key, r.n, r.excludedCount, r.min, r.whiskerLow, r.q1, r.median, r.q3, r.whiskerHigh, r.max, r.outlierCount,
        ...(Array.isArray(r.outliers) ? r.outliers : []), "\u0002");
    }
    const sig = `boxplot|${String(b.method ?? "")}:${groups.length}:${hashValues(flat)}`;
    signatureCache.set(chart, sig);
    return sig;
  }
  if (chart.kind === "histogram") {
    const cachedHist = signatureCache.get(chart);
    if (cachedHist !== undefined) return cachedHist;
    const h = chart.histogram && typeof chart.histogram === "object" ? chart.histogram : {};
    const sig = `histogram|${String(h.mode ?? "")}:${hashValues(h.edges ?? [])}:${hashValues(h.counts ?? [])}:${hashValues(h.densities ?? [])}`;
    signatureCache.set(chart, sig);
    return sig;
  }
  if (!Array.isArray(chart.series)) return "";
  const cached = signatureCache.get(chart);
  if (cached !== undefined) return cached;
  const series = chart.series.map((s) => {
    const x = Array.isArray(s?.x) ? s.x : [];
    const y = Array.isArray(s?.y) ? s.y : [];
    return [String(s?.name ?? ""), x.length, hashValues(x), hashValues(y)].join(":");
  });
  const signature = `${chart.kind}|${series.join(";")}`;
  signatureCache.set(chart, signature);
  return signature;
}

/** @param {import('./types.js').ChartBlock | null | undefined} chart @returns {ChartViewState} */
export function createChartViewState(chart) {
  return {
    snapshot: chartSnapshotSignature(chart),
    hiddenSeriesKeys: [],
    yDomainPolicy: "full",
    focusedSlice: null,
    notesOpen: false,
    viewXDomain: null,
    selectedRange: null,
  };
}

/**
 * Bring a stored state in line with the chart it is applied to. A new snapshot under the same
 * identity keeps the Y policy and notes disclosure but resets series selection and slice focus.
 * @param {ChartViewState | null | undefined} state
 * @param {import('./types.js').ChartBlock | null | undefined} chart
 * @returns {ChartViewState}
 */
export function reconcileChartViewState(state, chart) {
  const fresh = createChartViewState(chart);
  if (!state || typeof state !== "object") return fresh;
  // Zoom and selection are clamped to the current snapshot's full X domain (the hard bound) and
  // survive a new snapshot. A zoom also honors the minimum span, and a whole-domain zoom is no
  // zoom; a selection equal to the whole domain is still a selection (§4.5).
  const info = chartXDomainInfo(chart);
  const viewXDomain = info ? clampXRange(state.viewXDomain, info.domain) : null;
  const selectedRange = info ? clampXSelection(state.selectedRange, info.domain) : null;
  if (state.snapshot !== fresh.snapshot) {
    return {
      ...fresh,
      yDomainPolicy: Y_DOMAIN_POLICIES.includes(state.yDomainPolicy) ? state.yDomainPolicy : "full",
      notesOpen: state.notesOpen === true,
      viewXDomain,
      selectedRange,
    };
  }
  const count = Array.isArray(chart?.series) ? chart.series.length : 0;
  const hidden = [...new Set((state.hiddenSeriesKeys ?? []).filter((k) => Number.isInteger(k) && k >= 0 && k < count))].sort((a, b) => a - b);
  return {
    ...fresh,
    ...state,
    hiddenSeriesKeys: hidden,
    yDomainPolicy: Y_DOMAIN_POLICIES.includes(state.yDomainPolicy) ? state.yDomainPolicy : "full",
    focusedSlice: Number.isInteger(state.focusedSlice) ? state.focusedSlice : null,
    notesOpen: state.notesOpen === true,
    viewXDomain,
    selectedRange,
  };
}

/**
 * Zoom the X view. `null` or a range covering the full domain restores the full view.
 * @param {ChartViewState} state @param {[number, number] | null} range @returns {ChartViewState}
 */
export function setViewXDomain(state, range) {
  return { ...state, viewXDomain: Array.isArray(range) ? [Number(range[0]), Number(range[1])] : null };
}

/** @param {ChartViewState} state @param {[number, number] | null} range @returns {ChartViewState} */
export function setSelectedRange(state, range) {
  return { ...state, selectedRange: Array.isArray(range) ? [Number(range[0]), Number(range[1])] : null };
}

/**
 * Reset view: full X domain, all series, `full` Y policy, no selection, no slice focus; the
 * notes disclosure is left as is. Never refetches or changes the answer (§4.3).
 * @param {ChartViewState} state @returns {ChartViewState}
 */
export function resetChartView(state) {
  return {
    ...state,
    hiddenSeriesKeys: [],
    yDomainPolicy: "full",
    focusedSlice: null,
    viewXDomain: null,
    selectedRange: null,
  };
}

/** True when the view differs from the full analysis in any way. @param {ChartViewState} state */
export function isChartViewDefault(state) {
  return (
    state.hiddenSeriesKeys.length === 0 &&
    state.yDomainPolicy === "full" &&
    state.focusedSlice === null &&
    state.viewXDomain === null &&
    state.selectedRange === null
  );
}

/** @param {ChartViewState} state @param {number} seriesIndex */
export function isSeriesHidden(state, seriesIndex) {
  return state.hiddenSeriesKeys.includes(seriesIndex);
}

/** @param {ChartViewState} state @param {number} seriesIndex @returns {ChartViewState} */
export function toggleSeriesVisibility(state, seriesIndex) {
  const set = new Set(state.hiddenSeriesKeys);
  if (set.has(seriesIndex)) set.delete(seriesIndex);
  else set.add(seriesIndex);
  return { ...state, hiddenSeriesKeys: [...set].sort((a, b) => a - b) };
}

/** @param {ChartViewState} state @returns {ChartViewState} */
export function showAllSeries(state) {
  return { ...state, hiddenSeriesKeys: [] };
}

/** @param {ChartViewState} state @param {"full" | "visible"} policy @returns {ChartViewState} */
export function setYDomainPolicy(state, policy) {
  return { ...state, yDomainPolicy: Y_DOMAIN_POLICIES.includes(policy) ? policy : "full" };
}

/** @param {ChartViewState} state @param {number} sliceIndex @returns {ChartViewState} */
export function toggleSliceFocus(state, sliceIndex) {
  return { ...state, focusedSlice: state.focusedSlice === sliceIndex ? null : sliceIndex };
}

/** @param {ChartViewState} state @param {boolean} open @returns {ChartViewState} */
export function setNotesOpen(state, open) {
  return { ...state, notesOpen: open === true };
}

/**
 * Plain-text summary of how the view differs from the full analysis, for the on-screen and
 * printed view note; empty when the view equals the default.
 * @param {ChartViewState} state
 * @param {import('./types.js').ChartBlock} chart
 * @param {{ label: string; slot: number }[]} legend legend entries in render order
 */
export function describeChartView(state, chart, legend) {
  const parts = [];
  const total = Array.isArray(chart?.series) ? chart.series.length : 0;
  const hidden = state.hiddenSeriesKeys.filter((k) => k < total);
  if (hidden.length && hidden.length >= total) {
    parts.push("No series selected");
  } else if (hidden.length) {
    const names = hidden.map((k) => String(chart.series[k]?.name ?? `Series ${k + 1}`).trim() || `Series ${k + 1}`);
    parts.push(`${hidden.length} of ${total} series hidden (${names.join(", ")})`);
  }
  if (state.yDomainPolicy === "visible") parts.push("Y axis fitted to visible series");
  if (chart?.kind === "pie" && Number.isInteger(state.focusedSlice)) {
    const item = legend[state.focusedSlice];
    if (item) parts.push(`Focused slice: ${item.label}`);
  }
  const info = chartXDomainInfo(chart);
  if (info) {
    const zoom = clampXRange(state.viewXDomain, info.domain);
    if (zoom) parts.push(`Zoomed to ${formatXRange(info.mode, zoom)} of ${formatXRange(info.mode, info.domain)}`);
    const sel = clampXSelection(state.selectedRange, info.domain);
    if (sel) parts.push(`Selected: ${formatXRange(info.mode, sel)}`);
  }
  return parts.join(" · ");
}
