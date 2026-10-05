/**
 * Offline turn judging against reference golden fixtures (§11.3).
 */

import {
  ackBeforeDone,
  alertCountMatches,
  collectContentDeltaText,
  collectStructuredObservation,
  extractComparisonFromProse,
  findChartWire,
  findTableWire,
  objectSubsetEqual,
  arraysEqual,
  rowsSortedBy,
  validateChartBlock,
  validateTableBlock,
} from "./wire_contract.mjs";

const IMPLEMENTED_REFERENCE_KEYS = new Set([
  "chart",
  "comparison",
  "table",
  "explanation",
  "alerts",
  "summary",
  "statistics",
]);

/**
 * Manual judgments assess a captured run and must not live in reusable goldens.
 * @param {any} evidence
 * @param {string} family
 */
function manualJudgmentForFamily(evidence, family) {
  const entry = evidence?.manualJudgments?.[family] ?? evidence?.suppliedJudgments?.[family];
  if (!entry) return null;
  const conversationId = String(evidence.conversationId ?? "");
  const requestId = String(evidence.requestId ?? "");
  if (!conversationId || !requestId) return null;
  if (typeof entry === "string") return null;
  if (typeof entry !== "object") return null;
  const judgment = entry.judgment ?? entry.status;
  if (judgment !== "pass" && judgment !== "fail") return null;
  if (String(entry.conversationId ?? "") !== conversationId) return null;
  if (String(entry.requestId ?? "") !== requestId) return null;
  return judgment;
}

/** @param {any} turn @param {{ wireEvents: any[], terminal: any, requestId: string, conversationId?: string, manualJudgments?: Record<string, any> }} evidence @param {any} golden */
export function judgeTurn(turn, evidence, golden) {
  if (!golden) {
    return { judgmentStatus: "insufficient_evidence", details: { reason: "no_golden" } };
  }
  const reference = golden.reference;
  if (!reference || typeof reference !== "object") {
    return { judgmentStatus: "insufficient_evidence", details: { reason: "no_reference_evidence" } };
  }

  const unsupported = Object.keys(reference).filter((key) => !IMPLEMENTED_REFERENCE_KEYS.has(key));
  if (unsupported.length > 0) {
    return {
      judgmentStatus: "insufficient_evidence",
      details: { reason: "unsupported_reference_keys", keys: unsupported },
    };
  }

  const terminal = evidence.terminal?.kind ?? evidence.terminal;
  if (golden.terminal && golden.terminal !== terminal) {
    return {
      judgmentStatus: "fail",
      details: { reason: "terminal_mismatch", expected: golden.terminal, actual: terminal },
    };
  }

  const conversationId = String(evidence.conversationId ?? "");
  const requestId = String(evidence.requestId ?? "");
  const wireEvents = evidence.wireEvents ?? [];

  if (golden.requireAckBeforeDone) {
    if (!ackBeforeDone(wireEvents, conversationId, requestId)) {
      return { judgmentStatus: "fail", details: { reason: "ack_done_order" } };
    }
  }

  /** @type {string[]} */
  const unimplemented = [];
  for (const key of Object.keys(reference)) {
    const verdict = judgeReferenceFamily(key, reference[key], wireEvents, conversationId, requestId, evidence);
    if (verdict?.judgmentStatus === "fail") return verdict;
    if (verdict?.judgmentStatus === "insufficient_evidence") unimplemented.push(key);
  }

  if (unimplemented.length > 0) {
    return {
      judgmentStatus: "insufficient_evidence",
      details: { reason: "reference_family_not_extractable", families: unimplemented },
    };
  }

  return { judgmentStatus: "pass", details: { terminal } };
}

/** @param {string} key @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId @param {any} evidence */
function judgeReferenceFamily(key, expected, wireEvents, conversationId, requestId, evidence) {
  const manual = manualJudgmentForFamily(evidence, key);
  if (manual === "pass") return null;
  if (manual === "fail") {
    return { judgmentStatus: "fail", details: { reason: "manual_judgment_fail", family: key } };
  }

  switch (key) {
    case "chart":
      return judgeChartReference(expected, wireEvents, conversationId, requestId);
    case "comparison":
      return judgeComparisonReference(expected, wireEvents, conversationId, requestId);
    case "table":
      return judgeTableReference(expected, wireEvents, conversationId, requestId);
    case "explanation":
      return judgeExplanationReference(expected, wireEvents, conversationId, requestId);
    case "alerts":
      return judgeAlertsReference(expected, wireEvents, conversationId, requestId);
    case "summary":
      return judgeSummaryReference(expected, wireEvents, conversationId, requestId);
    case "statistics":
      return judgeStatisticsReference(expected, wireEvents, conversationId, requestId);
    default:
      return { judgmentStatus: "insufficient_evidence", details: { reason: "unsupported_reference", key } };
  }
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeChartReference(expected, wireEvents, conversationId, requestId) {
  const chart = findChartWire(wireEvents, conversationId, requestId);
  const valid = validateChartBlock(chart);
  if (!valid.ok) {
    return { judgmentStatus: "fail", details: { reason: valid.reason, field: valid.field ?? null } };
  }
  if (expected.kind && expected.kind !== chart.kind) {
    return {
      judgmentStatus: "fail",
      details: { reason: "chart_kind_mismatch", expected: expected.kind, actual: chart.kind },
    };
  }
  if (expected.source && !objectSubsetEqual(expected.source, chart.source)) {
    return {
      judgmentStatus: "fail",
      details: { reason: "chart_source_mismatch", expected: expected.source, actual: chart.source ?? null },
    };
  }
  if (Array.isArray(expected.series) && expected.series.length > 0) {
    const actualSeries = chart.series ?? [];
    if (actualSeries.length < expected.series.length) {
      return { judgmentStatus: "fail", details: { reason: "chart_series_count_mismatch" } };
    }
    for (let i = 0; i < expected.series.length; i += 1) {
      const exp = expected.series[i];
      const act = actualSeries[i] ?? {};
      if (Array.isArray(exp.x) && !arraysEqual(exp.x, act.x)) {
        return { judgmentStatus: "fail", details: { reason: "chart_series_x_mismatch", index: i } };
      }
      if (Array.isArray(exp.y) && !arraysEqual(exp.y, act.y)) {
        return { judgmentStatus: "fail", details: { reason: "chart_series_y_mismatch", index: i } };
      }
    }
  }
  return null;
}

/** @param {any} expected @param {any} observation @param {string} [text] */
function compareObservation(expected, observation, text = "") {
  if (expected.hoursByStatus && typeof expected.hoursByStatus === "object") {
    const actualHours = observation.hoursByStatus;
    if (!actualHours || typeof actualHours !== "object") {
      return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_hours_not_extractable" } };
    }
    for (const [status, hours] of Object.entries(expected.hoursByStatus)) {
      if (!Array.isArray(hours)) continue;
      if (!arraysEqual(hours, actualHours[status])) {
        return { judgmentStatus: "fail", details: { reason: "comparison_hours_mismatch", status } };
      }
    }
  }
  if (expected.percentByStatus && typeof expected.percentByStatus === "object") {
    const actualPercents = observation.percentByStatus;
    if (!actualPercents || typeof actualPercents !== "object") {
      return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_percent_not_extractable" } };
    }
    for (const [status, percents] of Object.entries(expected.percentByStatus)) {
      if (!Array.isArray(percents)) continue;
      if (!arraysEqual(percents, actualPercents[status])) {
        return { judgmentStatus: "fail", details: { reason: "comparison_percent_mismatch", status } };
      }
    }
  }
  if (expected.requiresPercentDenominator && Array.isArray(expected.percentDenominatorHours)) {
    const actualDenoms = observation.percentDenominatorHours;
    if (!Array.isArray(actualDenoms)) {
      return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_denominator_not_extractable" } };
    }
    if (!arraysEqual(expected.percentDenominatorHours, actualDenoms)) {
      return { judgmentStatus: "fail", details: { reason: "comparison_denominator_mismatch" } };
    }
  }
  if (Array.isArray(expected.devices)) {
    const actualDevices = observation.devices;
    if (Array.isArray(actualDevices)) {
      if (!arraysEqual(expected.devices, actualDevices)) {
        return { judgmentStatus: "fail", details: { reason: "comparison_devices_mismatch" } };
      }
    } else if (text) {
      const missing = expected.devices.filter((device) => !text.includes(String(device)));
      if (missing.length > 0) {
        return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_devices_not_extractable" } };
      }
    } else {
      return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_devices_not_extractable" } };
    }
  }
  return null;
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeComparisonReference(expected, wireEvents, conversationId, requestId) {
  const text = collectContentDeltaText(wireEvents, conversationId, requestId);
  if (!text.trim()) {
    return { judgmentStatus: "fail", details: { reason: "missing_comparison_text" } };
  }

  const structured = collectStructuredObservation(wireEvents, conversationId, requestId);
  let observation = structured ?? extractComparisonFromProse(text);
  if (!observation && Array.isArray(expected.devices) && !expected.hoursByStatus) {
    observation = {};
  }
  if (!observation) {
    return { judgmentStatus: "insufficient_evidence", details: { reason: "comparison_not_extractable" } };
  }
  return compareObservation(expected, observation, text);
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeTableReference(expected, wireEvents, conversationId, requestId) {
  const table = findTableWire(wireEvents, conversationId, requestId);
  const valid = validateTableBlock(table);
  if (!valid.ok) {
    return { judgmentStatus: "fail", details: { reason: valid.reason, field: valid.field ?? null } };
  }
  const rowOrder = expected.rowOrder ?? null;
  if (rowOrder?.sortBy && rowOrder?.sortOrder && Array.isArray(table.rows)) {
    if (!rowsSortedBy(table.rows, rowOrder.sortBy, rowOrder.sortOrder)) {
      return { judgmentStatus: "fail", details: { reason: "table_row_order_violation" } };
    }
  }
  if (Array.isArray(expected.rows)) {
    if (!Array.isArray(table.rows) || expected.rows.length !== table.rows.length) {
      return { judgmentStatus: "fail", details: { reason: "table_row_count_mismatch" } };
    }
    for (let i = 0; i < expected.rows.length; i += 1) {
      if (!objectSubsetEqual(expected.rows[i], table.rows[i])) {
        return { judgmentStatus: "fail", details: { reason: "table_row_mismatch", index: i } };
      }
    }
  }
  return null;
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeExplanationReference(expected, wireEvents, conversationId, requestId) {
  const text = collectContentDeltaText(wireEvents, conversationId, requestId);
  if (!text.trim()) {
    return { judgmentStatus: "fail", details: { reason: "missing_explanation_text" } };
  }
  if (expected.requiresWindowDistinction) {
    return {
      judgmentStatus: "insufficient_evidence",
      details: { reason: "window_distinction_requires_manual_judgment" },
    };
  }
  return null;
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeStatisticsReference(expected, wireEvents, conversationId, requestId) {
  const structured = collectStructuredObservation(wireEvents, conversationId, requestId);
  let observation = null;
  if (structured?.statistics && typeof structured.statistics === "object") {
    observation = structured.statistics;
  } else if (
    structured &&
    (structured.average != null || structured.min != null || structured.max != null)
  ) {
    observation = structured;
  }
  if (!observation || typeof observation !== "object") {
    return {
      judgmentStatus: "insufficient_evidence",
      details: { reason: "statistics_requires_manual_judgment" },
    };
  }
  /** @type {string[]} */
  const requiredKeys = ["average", "min", "max"].filter((key) => expected[key] != null);
  for (const key of requiredKeys) {
    const actual = observation[key];
    if (typeof actual !== "number" || !Number.isFinite(actual)) {
      return {
        judgmentStatus: "insufficient_evidence",
        details: { reason: "statistics_not_extractable", field: key },
      };
    }
    if (actual !== expected[key]) {
      return { judgmentStatus: "fail", details: { reason: "statistics_mismatch", field: key } };
    }
  }
  return null;
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeAlertsReference(expected, wireEvents, conversationId, requestId) {
  const text = collectContentDeltaText(wireEvents, conversationId, requestId);
  if (!text.trim()) {
    return { judgmentStatus: "fail", details: { reason: "missing_alert_text" } };
  }
  if (expected.requiresHistoricalQuery && /current summary/i.test(text)) {
    return { judgmentStatus: "fail", details: { reason: "used_current_summary_not_history" } };
  }
  if (expected.count != null) {
    if (!alertCountMatches(text, expected.count)) {
      return { judgmentStatus: "insufficient_evidence", details: { reason: "alert_count_not_established" } };
    }
  }
  if (expected.device && !text.includes(String(expected.device))) {
    return { judgmentStatus: "insufficient_evidence", details: { reason: "alert_device_not_established" } };
  }
  return null;
}

/** @param {any} expected @param {any[]} wireEvents @param {string} conversationId @param {string} requestId */
function judgeSummaryReference(expected, wireEvents, conversationId, requestId) {
  const text = collectContentDeltaText(wireEvents, conversationId, requestId);
  if (!text.trim()) {
    return { judgmentStatus: "fail", details: { reason: "missing_summary_text" } };
  }
  if (expected.requiresEvidenceLimits && !/evidence|limit|gap/i.test(text)) {
    return { judgmentStatus: "insufficient_evidence", details: { reason: "summary_evidence_limits_not_established" } };
  }
  if (expected.forbidsAlertToDowntimeOverreach && /alert.*\b(caused|led to|resulted in)\b.*downtime/i.test(text)) {
    return { judgmentStatus: "fail", details: { reason: "alert_to_downtime_overreach" } };
  }
  return null;
}
