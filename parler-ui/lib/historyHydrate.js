/**
 * One-shot thread hydration (no wire deltas). See docs/ui/AI_PARLER_HISTORY.md.
 * @typedef {import('./types.js').ChatRow} ChatRow
 * @typedef {import('./types.js').ChatUiState} ChatUiState
 */

import { buildArtifactsFromLegacyBuckets } from "./artifactPresentation.js";
import { createInitialChatState } from "./chatSession.js";
import { asChartGroupManifest, asChartBlock, asTableBlock } from "./wireAdapter.js";
import { llmUsageFromWireRecord } from "./llmUsageWire.js";
import { parseHostContextSnapshot } from "./hostContextRow.js";

/** @param {unknown} v */
function isRecord(v) {
  return typeof v === "object" && v !== null;
}

/**
 * @param {unknown} raw Row from JSON
 * @param {number} index fallback id index
 * @returns {ChatRow | null}
 */
function normalizeRow(raw, index) {
  if (!isRecord(raw)) return null;
  const kind = raw.kind;
  if (kind === "user") {
    const text = String(raw.text ?? raw.content ?? "").trim();
    if (!text) return null;
    const hostContext = parseHostContextSnapshot(raw.hostContext);
    return hostContext ? { kind: "user", text, hostContext } : { kind: "user", text };
  }
  if (kind === "assistant") {
    const requestId = String(
      raw.requestId ?? raw.request_id ?? `hist-${index}`
    ).trim();
    const markdown = String(raw.markdown ?? raw.content ?? "");
    /** @type {import('./types.js').ChartBlock[]} */
    const charts = [];
    if (Array.isArray(raw.charts)) {
      for (const c of raw.charts) {
        const b = asChartBlock(c);
        if (b) charts.push(b);
      }
    }
    /** @type {import('./types.js').TableBlock[]} */
    const tables = [];
    if (Array.isArray(raw.tables)) {
      for (const t of raw.tables) {
        const b = asTableBlock(t);
        if (b) tables.push(b);
      }
    }
    /** @type {import('./types.js').ChartGroupManifest[]} */
    const groups = [];
    if (Array.isArray(raw.groups)) {
      for (const g of raw.groups) {
        const m = asChartGroupManifest(g);
        if (m && !groups.some((x) => x.groupId === m.groupId)) groups.push(m);
      }
    }
    let activity = null;
    if (raw.activity != null && String(raw.activity).trim() !== "") {
      activity = String(raw.activity);
    }
    const feedbackRaw = String(
      raw.feedbackRating ?? raw.feedback_rating ?? ""
    )
      .trim()
      .toLowerCase();
    const feedbackRating =
      feedbackRaw === "up" || feedbackRaw === "down" ? feedbackRaw : undefined;
    const llmUsage = llmUsageFromWireRecord(raw.llmUsage ?? raw.llm_usage);
    return {
      kind: "assistant",
      requestId: requestId || `hist-${index}`,
      markdown,
      charts,
      tables,
      artifacts: buildArtifactsFromLegacyBuckets(charts, tables),
      ...(groups.length ? { groups } : {}),
      activity,
      ...(String(raw.assistantMessageId ?? raw.assistant_message_id ?? "").trim()
        ? {
            assistantMessageId: String(
              raw.assistantMessageId ?? raw.assistant_message_id ?? ""
            ).trim(),
          }
        : {}),
      ...(String(raw.completedAt ?? raw.completed_at ?? "").trim()
        ? { completedAt: String(raw.completedAt ?? raw.completed_at ?? "").trim() }
        : {}),
      ...(feedbackRating ? { feedbackRating } : {}),
      ...(llmUsage ? { llmUsage } : {}),
    };
  }
  return null;
}

/**
 * @param {unknown} raw Parsed JSON root
 * @returns {ChatRow[]}
 */
export function parseHistoryRows(raw) {
  if (!isRecord(raw)) {
    throw new Error("History JSON must be an object");
  }
  const fmt = raw.format;
  const rowsRaw = raw.rows;
  if (!Array.isArray(rowsRaw)) {
    throw new Error('History JSON must contain a "rows" array');
  }
  if (fmt != null && fmt !== "" && fmt !== "ai-parler-history-v1") {
    throw new Error(`Unsupported history format: ${String(fmt)}`);
  }
  /** @type {ChatRow[]} */
  const out = [];
  let i = 0;
  for (const r of rowsRaw) {
    const row = normalizeRow(r, i);
    if (row) {
      out.push(row);
      i += 1;
    }
  }
  return out;
}

/**
 * @param {ChatRow[]} rows
 * @returns {ChatUiState}
 */
export function chatUiStateFromHistoryRows(rows) {
  return {
    ...createInitialChatState(),
    rows,
    busy: false,
    error: null,
    activeRequestId: null,
  };
}
