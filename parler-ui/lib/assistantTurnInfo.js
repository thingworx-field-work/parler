/**
 * Gateway turn "info" popover — only fields already on the hydrated assistant row (no new wire).
 * @typedef {{ label: string, value: string }} TurnInfoLine
 */

/** @param {unknown} v */
function nonEmptyString(v) {
  const s = v == null ? "" : String(v).trim();
  return s.length > 0 ? s : "";
}

/**
 * @param {import('./types.js').ChatRow} row
 * @param {string} conversationId
 * @returns {TurnInfoLine[]}
 */
export function buildAssistantTurnInfoEntries(row, conversationId) {
  /** @type {TurnInfoLine[]} */
  const out = [];
  if (!row || row.kind !== "assistant") return out;

  const cid = nonEmptyString(conversationId);
  if (cid) out.push({ label: "Conversation", value: cid });

  const rid = nonEmptyString(row.requestId);
  if (rid) out.push({ label: "Request", value: rid });

  const aid = nonEmptyString(row.assistantMessageId);
  if (aid) out.push({ label: "Assistant message", value: aid });

  const comp = nonEmptyString(row.completedAt);
  if (comp) out.push({ label: "Completed", value: comp });

  const fr = nonEmptyString(row.feedbackRating).toLowerCase();
  if (fr === "up" || fr === "down") out.push({ label: "Feedback", value: fr });

  const nc = Array.isArray(row.charts) ? row.charts.length : 0;
  if (nc > 0) out.push({ label: "Charts", value: String(nc) });

  const nt = Array.isArray(row.tables) ? row.tables.length : 0;
  if (nt > 0) out.push({ label: "Tables", value: String(nt) });

  const ts = row.taskState;
  if (ts && typeof ts === "object") {
    const st = nonEmptyString(ts.status);
    if (st) out.push({ label: "Task status", value: st });
    const tl = nonEmptyString(ts.title);
    if (tl) out.push({ label: "Task title", value: tl });
    const sum = ts.summary && typeof ts.summary === "object" ? ts.summary : null;
    if (sum) {
      for (const k of ["failed", "blocked", "completed", "total"]) {
        if (typeof sum[k] === "number") {
          out.push({ label: `Task ${k}`, value: String(sum[k]) });
        }
      }
    }
    const items = Array.isArray(ts.items) ? ts.items.length : 0;
    if (items > 0) out.push({ label: "Task items", value: String(items) });
  }

  const usage = row.llmUsage;
  if (usage && typeof usage === "object") {
    if (typeof usage.turnWallMs === "number") {
      out.push({ label: "Total time (ms)", value: String(usage.turnWallMs) });
    }
    if (typeof usage.rateWaitMs === "number") {
      out.push({ label: "Rate-gate wait (ms)", value: String(usage.rateWaitMs) });
    }
    if (typeof usage.toolExecutionMaxConcurrency === "number") {
      out.push({ label: "Tool concurrency", value: String(usage.toolExecutionMaxConcurrency) });
    }
    if (typeof usage.multiToolCallRoundsCount === "number") {
      out.push({ label: "Multi-tool rounds", value: String(usage.multiToolCallRoundsCount) });
    }
    if (typeof usage.repetitionBlockedCount === "number") {
      out.push({ label: "Repetition blocked", value: String(usage.repetitionBlockedCount) });
    }
    if (typeof usage.parlerChartWireEmittedCount === "number") {
      out.push({ label: "Chart wires emitted", value: String(usage.parlerChartWireEmittedCount) });
    }
    if (typeof usage.chartRescueAttempted === "boolean") {
      out.push({ label: "Chart rescue attempted", value: String(usage.chartRescueAttempted) });
    }
    if (typeof usage.presentationPhaseEntered === "boolean") {
      out.push({ label: "Presentation phase entered", value: String(usage.presentationPhaseEntered) });
    }
    if (typeof usage.presentationActionsRequested === "number") {
      out.push({
        label: "Presentation actions requested",
        value: String(usage.presentationActionsRequested),
      });
    }
    if (typeof usage.presentationActionsExecuted === "number") {
      out.push({
        label: "Presentation actions executed",
        value: String(usage.presentationActionsExecuted),
      });
    }
    if (typeof usage.presentationActionsBlocked === "number") {
      out.push({
        label: "Presentation actions blocked",
        value: String(usage.presentationActionsBlocked),
      });
    }
    if (typeof usage.inputTokens === "number") {
      out.push({ label: "Input tokens", value: String(usage.inputTokens) });
    } else if (typeof usage.promptTokens === "number") {
      out.push({ label: "Prompt tokens", value: String(usage.promptTokens) });
    }
    if (typeof usage.outputTokens === "number") {
      out.push({ label: "Output tokens", value: String(usage.outputTokens) });
    } else if (typeof usage.completionTokens === "number") {
      out.push({ label: "Completion tokens", value: String(usage.completionTokens) });
    }
    if (typeof usage.cacheReadInputTokens === "number") {
      out.push({ label: "Cache read input", value: String(usage.cacheReadInputTokens) });
    }
    if (typeof usage.cacheCreationInputTokens === "number") {
      out.push({
        label: "Cache creation input",
        value: String(usage.cacheCreationInputTokens),
      });
    }
    if (typeof usage.cachedPromptTokens === "number") {
      out.push({ label: "Cached prompt", value: String(usage.cachedPromptTokens) });
    }
    const reasoningTokens =
      typeof usage.reasoningTokensTotal === "number"
        ? usage.reasoningTokensTotal
        : typeof usage.reasoningTokens === "number"
          ? usage.reasoningTokens
          : undefined;
    if (typeof reasoningTokens === "number" && reasoningTokens > 0) {
      out.push({ label: "Reasoning tokens", value: String(reasoningTokens) });
    }
    const prov = nonEmptyString(usage.providerThingName);
    if (prov) out.push({ label: "Provider Thing", value: prov });
    const model = nonEmptyString(usage.model);
    if (model) out.push({ label: "Model", value: model });
    const prid = nonEmptyString(usage.requestId);
    if (prid) out.push({ label: "Provider request id", value: prid });
  }

  return out;
}
