/**
 * Normative AlwaysOn wire helpers for the context-compaction eval harness.
 * @see CONTRACTS/UI_CLIENT_PROTOCOL.md §2 wire union (Wire type column)
 * @see CONTRACTS/CHART_CONTRACT.md §2–3
 * @see CONTRACTS/TABLE_CONTRACT.md §2–3
 *
 * Shipped ParlerReceiveMessageSupport emits wire `done` / `error` (UiEvent session.done / session.error).
 */

export const WIRE_TERMINAL_TYPES = new Set([
  "done",
  "error",
  "session.cancelled",
  "session.superseded",
  "approval.required",
]);

const TERMINAL_KIND = {
  done: { kind: "done", completion: "completed" },
  error: { kind: "error", completion: "failed" },
  "session.cancelled": { kind: "session.cancelled", completion: "cancelled" },
  "session.superseded": { kind: "session.superseded", completion: "superseded" },
  "approval.required": { kind: "approval.required", completion: "approval_pending" },
};

/** @param {any} event @param {string} conversationId @param {string} requestId */
export function matchesRequest(event, conversationId, requestId) {
  const rid = String(event?.request_id ?? event?.requestId ?? "");
  if (!rid || rid !== requestId) {
    return false;
  }
  const cid = String(event?.conversation_id ?? event?.conversationId ?? "");
  if (!conversationId) {
    return Boolean(cid);
  }
  return cid === conversationId;
}

/** @param {any} event */
export function wireEventType(event) {
  return String(event?.type ?? "");
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function terminalFromEvents(events, conversationId, requestId) {
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const ev = events[i];
    if (!matchesRequest(ev, conversationId, requestId)) continue;
    const mapped = TERMINAL_KIND[wireEventType(ev)];
    if (mapped) return mapped;
  }
  return null;
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function findChartWire(events, conversationId, requestId) {
  for (const ev of events) {
    if (!matchesRequest(ev, conversationId, requestId)) continue;
    if (wireEventType(ev) !== "chart") continue;
    const chart = ev?.chart;
    if (chart && typeof chart === "object") return chart;
  }
  return null;
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function findTableWire(events, conversationId, requestId) {
  for (const ev of events) {
    if (!matchesRequest(ev, conversationId, requestId)) continue;
    if (wireEventType(ev) !== "table") continue;
    const table = ev?.table;
    if (table && typeof table === "object") return table;
  }
  return null;
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function collectContentDeltaText(events, conversationId, requestId) {
  let text = "";
  for (const ev of events) {
    if (!matchesRequest(ev, conversationId, requestId)) continue;
    if (wireEventType(ev) === "content.delta") {
      text += String(ev?.text ?? ev?.delta ?? ev?.message ?? "");
    }
  }
  return text;
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function collectStructuredObservation(events, conversationId, requestId) {
  const text = collectContentDeltaText(events, conversationId, requestId).trim();
  if (!text.startsWith("{")) return null;
  try {
    const parsed = JSON.parse(text);
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

const CHART_KINDS = new Set(["line", "bar", "scatter", "pie"]);

/** @param {unknown} value */
export function isFiniteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

/** @param {any[]} expected @param {any[]} actual */
export function arraysEqual(expected, actual) {
  if (!Array.isArray(expected) || !Array.isArray(actual) || expected.length !== actual.length) {
    return false;
  }
  for (let i = 0; i < expected.length; i += 1) {
    const e = expected[i];
    const a = actual[i];
    if (typeof e === "number" || typeof a === "number") {
      if (!isFiniteNumber(e) || !isFiniteNumber(a) || e !== a) return false;
    } else if (String(e) !== String(a)) {
      return false;
    }
  }
  return true;
}

/** @param {any} expected @param {any} actual */
export function objectSubsetEqual(expected, actual) {
  if (!expected || typeof expected !== "object") return true;
  if (!actual || typeof actual !== "object") return false;
  for (const [key, value] of Object.entries(expected)) {
    if (Array.isArray(value)) {
      if (!arraysEqual(value, actual[key])) return false;
    } else if (value && typeof value === "object") {
      if (!objectSubsetEqual(value, actual[key])) return false;
    } else if (actual[key] !== value) {
      return false;
    }
  }
  return true;
}

/** @param {any} series @param {string} kind */
function validatePieSeries(series, kind) {
  if (kind !== "pie") return null;
  if (series.length !== 1) {
    return { ok: false, reason: "malformed_chart", field: "pie_series_count" };
  }
  const first = series[0];
  let sum = 0;
  for (const y of first.y) {
    if (y < 0) {
      return { ok: false, reason: "malformed_chart", field: "pie_negative_y" };
    }
    sum += y;
  }
  if (!(sum > 0)) {
    return { ok: false, reason: "malformed_chart", field: "pie_zero_total" };
  }
  return null;
}

/** @param {any} chart */
export function validateChartBlock(chart) {
  if (!chart || typeof chart !== "object") {
    return { ok: false, reason: "missing_chart" };
  }
  const kind = String(chart.kind ?? "");
  if (!CHART_KINDS.has(kind)) {
    return { ok: false, reason: "malformed_chart", field: "kind" };
  }
  if (!Array.isArray(chart.series) || chart.series.length === 0) {
    return { ok: false, reason: "malformed_chart", field: "series" };
  }
  for (const series of chart.series) {
    if (!series || typeof series !== "object") {
      return { ok: false, reason: "malformed_chart", field: "series_item" };
    }
    if (!Array.isArray(series.x) || !Array.isArray(series.y) || series.x.length === 0 || series.y.length === 0) {
      return { ok: false, reason: "malformed_chart", field: "series_xy" };
    }
    if (series.x.length !== series.y.length) {
      return { ok: false, reason: "malformed_chart", field: "series_length_mismatch" };
    }
    for (const y of series.y) {
      if (!isFiniteNumber(y)) {
        return { ok: false, reason: "malformed_chart", field: "series_y_non_numeric" };
      }
    }
  }
  const pieIssue = validatePieSeries(chart.series, kind);
  if (pieIssue) return pieIssue;
  return { ok: true };
}

/** @param {any} table */
export function validateTableBlock(table) {
  if (!table || typeof table !== "object") {
    return { ok: false, reason: "missing_table" };
  }
  if (table.kind !== "entity-list") {
    return { ok: false, reason: "malformed_table", field: "kind" };
  }
  if (!Array.isArray(table.columns) || table.columns.length === 0) {
    return { ok: false, reason: "malformed_table", field: "columns" };
  }
  if (!Array.isArray(table.rows)) {
    return { ok: false, reason: "malformed_table", field: "rows" };
  }
  for (const col of table.columns) {
    if (!col || typeof col.key !== "string" || typeof col.label !== "string" || typeof col.baseType !== "string") {
      return { ok: false, reason: "malformed_table", field: "columns_item" };
    }
  }
  for (const row of table.rows) {
    if (!row || typeof row !== "object" || Array.isArray(row)) {
      return { ok: false, reason: "malformed_table", field: "rows_item" };
    }
  }
  return { ok: true };
}

/** @param {any[]} events @param {string} conversationId @param {string} requestId */
export function ackBeforeDone(events, conversationId, requestId) {
  let ackIdx = -1;
  let doneIdx = -1;
  for (let i = 0; i < events.length; i += 1) {
    const ev = events[i];
    if (!matchesRequest(ev, conversationId, requestId)) continue;
    const type = wireEventType(ev);
    if (type === "session.ack" && ackIdx < 0) ackIdx = i;
    if (type === "done" && doneIdx < 0) doneIdx = i;
  }
  return ackIdx >= 0 && doneIdx >= 0 && ackIdx < doneIdx;
}

/** @param {string} text */
export function extractComparisonFromProse(text) {
  /** @type {Record<string, number[]>} */
  const hoursByStatus = {};
  for (const match of text.matchAll(/\b(Running|Idle|Down)=(\d+(?:\.\d+)?)\b/g)) {
    const status = match[1];
    const value = Number(match[2]);
    if (!hoursByStatus[status]) hoursByStatus[status] = [];
    hoursByStatus[status].push(value);
  }
  if (Object.keys(hoursByStatus).length === 0) return null;
  const percentDenominatorHours = [...text.matchAll(/\bdenominator\s+(\d+(?:\.\d+)?)\b/gi)].map((m) =>
    Number(m[1])
  );
  /** @type {Record<string, number[]>} */
  const percentByStatus = {};
  for (const match of text.matchAll(/\b(Running|Idle|Down)=\d+(?:\.\d+)?\s*\((\d+(?:\.\d+)?)%\)/g)) {
    const status = match[1];
    const value = Number(match[2]);
    if (!percentByStatus[status]) percentByStatus[status] = [];
    percentByStatus[status].push(value);
  }
  return {
    hoursByStatus,
    percentDenominatorHours: percentDenominatorHours.length ? percentDenominatorHours : undefined,
    percentByStatus: Object.keys(percentByStatus).length ? percentByStatus : undefined,
  };
}

/** @param {string} text @param {number} expectedCount */
export function alertCountMatches(text, expectedCount) {
  const count = Number(expectedCount);
  const patterns = [
    new RegExp(`\\b${count}\\s+(?:warnings?|alerts?)\\b`, "i"),
    new RegExp(`\\b(?:count|total)\\s*[:=]?\\s*${count}\\b`, "i"),
  ];
  return patterns.some((pattern) => pattern.test(text));
}

/** @param {any[]} rows @param {string} sortBy @param {"asc"|"desc"|string} sortOrder */
export function rowsSortedBy(rows, sortBy, sortOrder) {
  if (!Array.isArray(rows) || rows.length < 2) return true;
  for (let i = 1; i < rows.length; i += 1) {
    const prev = Number(rows[i - 1]?.[sortBy]);
    const curr = Number(rows[i]?.[sortBy]);
    if (!Number.isFinite(prev) || !Number.isFinite(curr)) return false;
    if (sortOrder === "desc" && prev < curr) return false;
    if (sortOrder === "asc" && prev > curr) return false;
  }
  return true;
}
