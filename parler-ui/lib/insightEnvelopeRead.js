/**
 * Loose read of agent tool **`insightEnvelope`** (Further Insight / `tabulate_cached_result` /
 * `summarize_cached_result` success JSON). Optional Parler wire **`tabular.tool_success`** carries a **compact**
 * projection of that success JSON; the reducer passes **`payload`** here. Not part of normative **`UI_CLIENT_PROTOCOL`**
 * embedding until D7 passive gate.
 *
 * **D7 — sole pre-gate read path:** do not read `payload.insightEnvelope.*` from `wireAdapter.js` or views; in
 * `chatSession.js` only via **`readInsightEnvelopeLoose(...)`** (e.g. **`assistant.insightEnvelope`**). See **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** **§View — `insightEnvelopeLoose` (D7)**
 * and **`CONTRACTS/TABULAR_INSIGHT.md`**.
 *
 * @typedef {object} InsightEnvelopeLoose
 * @property {string} schemaVersion
 * @property {string} [sourceCacheId]
 * @property {number} [rowEstimate]
 * @property {unknown[]} [columns]
 */

/** @param {unknown} v */
function isRecord(v) {
  return typeof v === "object" && v !== null;
}

/**
 * @param {unknown} toolSuccessOrPayload Root or nested object that may carry **`insightEnvelope`** (typically tool success JSON).
 * @returns {{ insightEnvelope: InsightEnvelopeLoose | null }}
 */
export function readInsightEnvelopeLoose(toolSuccessOrPayload) {
  if (!isRecord(toolSuccessOrPayload)) return { insightEnvelope: null };
  const env = toolSuccessOrPayload.insightEnvelope;
  if (!isRecord(env)) return { insightEnvelope: null };
  const schemaVersion = env.schemaVersion;
  if (typeof schemaVersion !== "string" || !schemaVersion.trim()) {
    return { insightEnvelope: null };
  }
  /** @type {InsightEnvelopeLoose} */
  const out = {
    schemaVersion: schemaVersion.trim(),
  };
  if (typeof env.sourceCacheId === "string" && env.sourceCacheId.length > 0) {
    out.sourceCacheId = env.sourceCacheId;
  }
  if (typeof env.rowEstimate === "number" && Number.isFinite(env.rowEstimate)) {
    out.rowEstimate = env.rowEstimate;
  }
  if (Array.isArray(env.columns)) {
    out.columns = env.columns;
  }
  return { insightEnvelope: out };
}
