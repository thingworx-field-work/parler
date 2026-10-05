/**
 * Chat state reducers — see CONTRACTS/UI_CLIENT_PROTOCOL.md
 * @typedef {import('./types.js').ChatRow} ChatRow
 * @typedef {import('./types.js').ChatUiState} ChatUiState
 * @typedef {import('./types.js').UiEvent} UiEvent
 * @typedef {import('./types.js').ApiChatMessage} ApiChatMessage
 */

import { makeRowArtifact } from "./artifactPresentation.js";
import { readInsightEnvelopeLoose } from "./insightEnvelopeRead.js";

/** Max stored **`request_id`** values ended by **`session.cancelled`** (FIFO evict; `docs/agent/turn-cancellation-control.md` §6.2). */
const MAX_CANCELLED_REQUEST_IDS = 32;

/** Max **`request_id`** values awaiting one late **`session.done`** after **`session.cancel_unsupported_local`** (same cap as cancel tombstones). */
const MAX_UNSUPPORTED_LOCAL_REQUEST_IDS = 32;

/**
 * @param {ChatUiState} prev
 * @param {string} requestId
 */
function isRequestIdCancelledTombstone(prev, requestId) {
  const rid = String(requestId ?? "").trim();
  if (!rid) return false;
  return (prev.cancelledRequestIds ?? new Set()).has(rid);
}

/**
 * @param {ChatUiState} prev
 * @param {string} requestId
 * @returns {Set<string>}
 */
function withCancelledRequestTombstone(prev, requestId) {
  const rid = String(requestId ?? "").trim();
  const next = new Set(prev.cancelledRequestIds ?? []);
  if (!rid) return next;
  next.add(rid);
  while (next.size > MAX_CANCELLED_REQUEST_IDS) {
    const oldest = next.keys().next().value;
    next.delete(oldest);
  }
  return next;
}

/**
 * @param {ChatUiState} prev
 * @param {string} requestId
 * @returns {Set<string>}
 */
function withUnsupportedLocalRequestMarker(prev, requestId) {
  const rid = String(requestId ?? "").trim();
  const next = new Set(prev.unsupportedLocalRequestIds ?? []);
  if (!rid) return next;
  next.add(rid);
  while (next.size > MAX_UNSUPPORTED_LOCAL_REQUEST_IDS) {
    const oldest = next.keys().next().value;
    next.delete(oldest);
  }
  return next;
}

/**
 * @param {ChatUiState} prev
 * @param {string} requestId
 * @returns {Set<string>}
 */
function withoutUnsupportedLocalRequestMarker(prev, requestId) {
  const rid = String(requestId ?? "").trim();
  const next = new Set(prev.unsupportedLocalRequestIds ?? []);
  next.delete(rid);
  return next;
}

/**
 * @param {import('./types.js').ChatRowAssistant} row
 * @param {'chart' | 'table'} type
 * @param {import('./types.js').ChartBlock | import('./types.js').TableBlock} payload
 */
function appendAssistantArtifact(row, type, payload) {
  const seq = Array.isArray(row.artifacts) ? row.artifacts.length : 0;
  const artifact = makeRowArtifact(type, payload, seq);
  return {
    ...row,
    artifacts: [...(row.artifacts ?? []), artifact],
    charts:
      type === "chart"
        ? [...row.charts, /** @type {import('./types.js').ChartBlock} */ (payload)]
        : row.charts,
    tables:
      type === "table"
        ? [...row.tables, /** @type {import('./types.js').TableBlock} */ (payload)]
        : row.tables,
  };
}

/** @returns {ChatUiState} Fresh shell with empty tombstone / marker sets (widget reset / history hydrate). */
export function createInitialChatState() {
  return {
    rows: [],
    busy: false,
    error: null,
    activeRequestId: null,
    approvalGate: null,
    cancelledRequestIds: new Set(),
    unsupportedLocalRequestIds: new Set(),
  };
}

/** @type {ChatUiState} module-default state; tests typically branch from `startUserTurn(initialChatState, ...)`. */
export const initialChatState = createInitialChatState();

/**
 * Client-only decision-submit substate transition (**`UI_CLIENT_PROTOCOL.md`** rule **10b**).
 * Matches on `pendingId` only, so a late callback from a superseded gate cannot touch the
 * current one. Never clears `busy`, `activeRequestId`, the global `error`, or the gate itself —
 * a failed uplink is not a turn terminal.
 *
 * @param {ChatUiState} prev
 * @param {string} pendingId
 * @param {'idle'|'submitting'|'submitted'} submitState
 * @param {string | null} submitError
 * @returns {ChatUiState}
 */
function withDecisionSubmitState(prev, pendingId, submitState, submitError) {
  const gate = prev.approvalGate;
  if (!gate || gate.pendingId !== pendingId) return prev;
  return { ...prev, approvalGate: { ...gate, submitState, submitError } };
}

/** @param {ChatRow[]} rows @param {string} requestId */
function findAssistantIndex(rows, requestId) {
  for (let i = rows.length - 1; i >= 0; i--) {
    const r = rows[i];
    if (r.kind === "assistant" && r.requestId === requestId) return i;
  }
  return -1;
}

/**
 * @param {ChatUiState} prev
 * @param {string} text
 * @param {string} requestId
 * @param {import('./types.js').HostContextSnapshotWire} [hostContext]
 * @returns {ChatUiState}
 */
export function startUserTurn(prev, text, requestId, hostContext) {
  return {
    ...prev,
    busy: true,
    error: null,
    activeRequestId: requestId,
    approvalGate: null,
    cancelledRequestIds: new Set(prev.cancelledRequestIds ?? []),
    unsupportedLocalRequestIds: new Set(prev.unsupportedLocalRequestIds ?? []),
    rows: [
      ...prev.rows,
      hostContext ? { kind: "user", text, hostContext } : { kind: "user", text },
      {
        kind: "assistant",
        requestId,
        markdown: "",
        charts: [],
        tables: [],
        artifacts: [],
      },
    ],
  };
}

/**
 * @param {ChatUiState} prev
 * @param {UiEvent} evt
 * @returns {ChatUiState}
 */
export function reduceUiEvent(prev, evt) {
  switch (evt.type) {
    case "session.ack":
      return prev;
    case "assistant.activity": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      const t = evt.text.trim();
      rows[idx] = {
        ...row,
        activity: t.length > 0 ? evt.text : null,
      };
      return { ...prev, rows };
    }
    case "assistant.append": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      const clearActivity = evt.text.trim().length > 0;
      rows[idx] = {
        ...row,
        markdown: row.markdown + evt.text,
        ...(clearActivity ? { activity: null, rateControlWaiting: false } : {}),
      };
      return { ...prev, rows };
    }
    case "assistant.replace": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      const clearActivity = evt.markdown.trim().length > 0;
      rows[idx] = {
        ...row,
        markdown: evt.markdown,
        ...(clearActivity ? { activity: null, rateControlWaiting: false } : {}),
      };
      return { ...prev, rows };
    }
    case "assistant.chart": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = appendAssistantArtifact(row, "chart", evt.chart);
      rows[idx] = {
        ...rows[idx],
        activity: null,
        rateControlWaiting: false,
      };
      return { ...prev, rows };
    }
    case "assistant.chartGroup": {
      // C3b-1 (design §8.5): identity is (row, groupId); only a higher revision is applied; nothing after final.
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const row = prev.rows[idx];
      if (row.kind !== "assistant") return prev;
      const groups = Array.isArray(row.groups) ? row.groups : [];
      const at = groups.findIndex((g) => g.groupId === evt.group.groupId);
      const current = at >= 0 ? groups[at] : null;
      if (current && (current.final || evt.group.revision <= current.revision)) return prev;
      const nextGroups = at >= 0 ? groups.map((g, i) => (i === at ? evt.group : g)) : [...groups, evt.group];
      const rows = [...prev.rows];
      rows[idx] = { ...row, groups: nextGroups, activity: null, rateControlWaiting: false };
      return { ...prev, rows };
    }
    case "assistant.table": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = appendAssistantArtifact(row, "table", evt.table);
      rows[idx] = {
        ...rows[idx],
        activity: null,
        rateControlWaiting: false,
      };
      return { ...prev, rows };
    }
    case "assistant.insightEnvelope": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      const { insightEnvelope } = readInsightEnvelopeLoose(evt.toolSuccessPayload);
      if (insightEnvelope == null) {
        return prev;
      }
      /**
       * D7: Assigning the loose object here does **not** trigger the passive gate.
       * **View** code (`parler-ui.js`, `components/*`) that reads **`insightEnvelopeLoose.<field>`**
       * **does** — coordinate **`UI_CLIENT_PROTOCOL.md`** + **`CONTRACTS/CONTRACT_VERSION.md`**
       * + **`TABULAR_INSIGHT.md`** first (`CONTRACTS/UI_CLIENT_PROTOCOL.md` §View — `insightEnvelopeLoose` (D7)).
       */
      rows[idx] = { ...row, insightEnvelopeLoose: insightEnvelope };
      return { ...prev, rows };
    }
    case "assistant.taskState": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = { ...row, taskState: evt.snapshot, rateControlWaiting: false };
      return { ...prev, rows };
    }
    case "assistant.rateControlStatus": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) return prev;
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      if (!evt.waiting) {
        rows[idx] = { ...row, rateControlWaiting: false };
        return { ...prev, rows };
      }
      if (!prev.busy || !prev.activeRequestId || prev.activeRequestId !== evt.requestId) {
        return prev;
      }
      rows[idx] = { ...row, rateControlWaiting: true };
      return { ...prev, rows };
    }
    case "session.done": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const rid = evt.requestId;
      const idx = findAssistantIndex(prev.rows, rid);
      const rowAt = idx >= 0 ? prev.rows[idx] : null;
      const activeMatches = !!prev.activeRequestId && prev.activeRequestId === rid;
      const lateRowCompletion =
        !prev.busy &&
        !prev.activeRequestId &&
        rowAt &&
        rowAt.kind === "assistant" &&
        !rowAt.assistantMessageId &&
        (prev.unsupportedLocalRequestIds ?? new Set()).has(rid);
      if (!activeMatches && !lateRowCompletion) {
        return prev;
      }
      if (idx < 0) {
        return {
          ...prev,
          busy: false,
          activeRequestId: null,
          approvalGate:
            prev.approvalGate && prev.approvalGate.requestId === evt.requestId
              ? null
              : prev.approvalGate,
          unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
        };
      }
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = {
        ...row,
        activity: null,
        rateControlWaiting: false,
        ...(evt.assistantMessageId
          ? { assistantMessageId: evt.assistantMessageId }
          : {}),
        ...(evt.completedAt ? { completedAt: evt.completedAt } : {}),
        ...(evt.llmUsage ? { llmUsage: evt.llmUsage } : {}),
      };
      let approvalGate = prev.approvalGate;
      if (approvalGate && approvalGate.requestId === evt.requestId) {
        approvalGate = null;
      }
      return {
        ...prev,
        rows,
        busy: false,
        activeRequestId: null,
        approvalGate,
        unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
      };
    }
    case "session.error": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      if (prev.activeRequestId && prev.activeRequestId !== evt.requestId) {
        return prev;
      }
      const rid = prev.activeRequestId ?? evt.requestId;
      const idx = rid ? findAssistantIndex(prev.rows, rid) : -1;
      if (idx < 0) {
        return {
          ...prev,
          busy: false,
          activeRequestId: null,
          approvalGate: null,
          error: evt.message,
          unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
        };
      }
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = { ...row, activity: null, rateControlWaiting: false };
      return {
        ...prev,
        rows,
        busy: false,
        activeRequestId: null,
        approvalGate: null,
        error: evt.message,
        unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
      };
    }
    case "session.superseded": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      const rid = prev.activeRequestId ?? evt.requestId;
      const idx = rid ? findAssistantIndex(prev.rows, rid) : -1;
      if (idx < 0) {
        return {
          ...prev,
          busy: false,
          activeRequestId: null,
          approvalGate: null,
          error: evt.message,
          unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
        };
      }
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = { ...row, activity: null, rateControlWaiting: false };
      return {
        ...prev,
        rows,
        busy: false,
        activeRequestId: null,
        approvalGate: null,
        error: evt.message,
        unsupportedLocalRequestIds: withoutUnsupportedLocalRequestMarker(prev, rid),
      };
    }
    case "session.cancelled": {
      if (!prev.activeRequestId || prev.activeRequestId !== evt.requestId) {
        return prev;
      }
      const cancelledIds = withCancelledRequestTombstone(prev, evt.requestId);
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) {
        return {
          ...prev,
          busy: false,
          activeRequestId: null,
          approvalGate:
            prev.approvalGate && prev.approvalGate.requestId === evt.requestId
              ? null
              : prev.approvalGate,
          cancelledRequestIds: cancelledIds,
        };
      }
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = {
        ...row,
        activity: null,
        rateControlWaiting: false,
        completedAt:
          typeof row.completedAt === "string" && row.completedAt.trim() !== ""
            ? row.completedAt
            : new Date().toISOString(),
        turnCancelled: true,
      };
      let approvalGate = prev.approvalGate;
      if (approvalGate && approvalGate.requestId === evt.requestId) {
        approvalGate = null;
      }
      return {
        ...prev,
        rows,
        busy: false,
        activeRequestId: null,
        approvalGate,
        cancelledRequestIds: cancelledIds,
      };
    }
    // CancelUserPrompt unsupported — local idle without cancel tombstone (UI_CLIENT_PROTOCOL rule 7c).
    case "session.cancel_unsupported_local": {
      if (!prev.activeRequestId || prev.activeRequestId !== evt.requestId) {
        return prev;
      }
      const idx = findAssistantIndex(prev.rows, evt.requestId);
      if (idx < 0) {
        return {
          ...prev,
          busy: false,
          activeRequestId: null,
          approvalGate:
            prev.approvalGate && prev.approvalGate.requestId === evt.requestId
              ? null
              : prev.approvalGate,
        };
      }
      const unsupportedIds = withUnsupportedLocalRequestMarker(prev, evt.requestId);
      const rows = [...prev.rows];
      const row = rows[idx];
      if (row.kind !== "assistant") return prev;
      rows[idx] = {
        ...row,
        activity: null,
        rateControlWaiting: false,
      };
      let approvalGate = prev.approvalGate;
      if (approvalGate && approvalGate.requestId === evt.requestId) {
        approvalGate = null;
      }
      return {
        ...prev,
        rows,
        busy: false,
        activeRequestId: null,
        approvalGate,
        unsupportedLocalRequestIds: unsupportedIds,
      };
    }
    case "approval.required": {
      if (isRequestIdCancelledTombstone(prev, evt.requestId)) return prev;
      return {
        ...prev,
        approvalGate: {
          requestId: evt.requestId,
          conversationId: evt.conversationId,
          pendingId: evt.pendingId,
          expiresAt: evt.expiresAt,
          toolName: evt.toolName,
          summary: evt.summary,
          actions: evt.actions,
          submitState: "idle",
          submitError: null,
        },
      };
    }
    case "approval.resolved": {
      if (!prev.approvalGate || prev.approvalGate.pendingId !== evt.pendingId) {
        return prev;
      }
      return { ...prev, approvalGate: null };
    }
    case "approval.decisionSubmitting":
      return withDecisionSubmitState(prev, evt.pendingId, "submitting", null);
    case "approval.decisionAccepted":
      return withDecisionSubmitState(prev, evt.pendingId, "submitted", null);
    case "approval.decisionSubmitFailed":
      return withDecisionSubmitState(prev, evt.pendingId, "idle", evt.message);
    default:
      return prev;
  }
}

/** @param {ChatRow[]} rows @returns {ApiChatMessage[]} */
export function transcriptsForApi(rows) {
  /** @type {ApiChatMessage[]} */
  const out = [];
  for (const r of rows) {
    if (r.kind === "user") {
      out.push({ role: "user", content: r.text });
    } else {
      out.push({ role: "assistant", content: r.markdown });
    }
  }
  return out;
}

/**
 * @param {string} requestId
 * @param {ApiChatMessage[]} messages
 * @param {string | null} [model]
 * @param {string | null} [conversationId] non-empty → included as `conversation_id` for server routing / single-live policy
 */
export function buildChatRequestJson(
  requestId,
  messages,
  model = null,
  conversationId = null
) {
  const payload = /** @type {Record<string, unknown>} */ ({
    messages,
    model,
  });
  const cid =
    conversationId != null && String(conversationId).trim() !== ""
      ? String(conversationId).trim()
      : null;
  if (cid) payload.conversation_id = cid;
  return JSON.stringify({
    type: "chat.request",
    request_id: requestId,
    payload,
  });
}
