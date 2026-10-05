/**
 * Whitelist {@code llm_usage} / {@code llmUsage} objects from wire or history JSON.
 * @typedef {import('./types.js').LlmUsageWire} LlmUsageWire
 */

/** @param {unknown} v */
function isRecord(v) {
  return typeof v === "object" && v !== null;
}

const NUM_KEYS = [
  "inputTokens",
  "outputTokens",
  "promptTokens",
  "completionTokens",
  "cacheReadInputTokens",
  "cacheCreationInputTokens",
  "cachedPromptTokens",
  "reasoningTokens",
  "reasoningTokensTotal",
  "rateWaitMs",
  "turnWallMs",
  "agentIterations",
  "toolCallCount",
  "llmWallMs",
  "toolWallMs",
  "promptTokensTotal",
  "completionTokensTotal",
  "requestedMaxOutputTokensTotal",
  "finalAnswerRoundIndex",
  "fetchAfterCompleteAnswerSetCount",
  "roundsHitMaxOutput",
  "toolExecutionMaxConcurrency",
  "multiToolCallRoundsCount",
  "repetitionBlockedCount",
  "parlerChartWireEmittedCount",
  "presentationActionsRequested",
  "presentationActionsExecuted",
  "presentationActionsBlocked",
];

const STR_KEYS = [
  "providerThingName",
  "providerTemplateName",
  "apiShapeId",
  "model",
  "requestId",
  "firstToolCallCacheHit",
  "toolProtocolViolation",
];

const BOOL_KEYS = [
  "markerEmitterEnabled",
  "noToolFinalAnswerApplied",
  "chartExpectedButMissing",
  "chartRescueAttempted",
  "presentationPhaseEntered",
];

const MAX_STR = 256;

/**
 * @param {unknown} raw
 * @returns {LlmUsageWire | undefined}
 */
export function llmUsageFromWireRecord(raw) {
  if (!isRecord(raw)) return undefined;
  /** @type {LlmUsageWire} */
  const o = {};
  for (const k of STR_KEYS) {
    if (!(k in raw)) continue;
    const v = raw[k];
    if (typeof v === "string") {
      const s = v.length > MAX_STR ? v.slice(0, MAX_STR) : v;
      if (s.length > 0) o[k] = s;
    }
  }
  for (const k of NUM_KEYS) {
    if (!(k in raw)) continue;
    const v = raw[k];
    if (typeof v === "number" && Number.isFinite(v)) {
      o[k] = Math.trunc(v);
    }
  }
  for (const k of BOOL_KEYS) {
    if (!(k in raw)) continue;
    const v = raw[k];
    if (typeof v === "boolean") {
      o[k] = v;
    }
  }
  return Object.keys(o).length > 0 ? o : undefined;
}
