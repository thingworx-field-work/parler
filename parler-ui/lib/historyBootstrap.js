/**
 * History bootstrap after ConnectAndBind — see docs/ui/load-history.md v2.
 * @param {unknown} text ReceiveMessage payload (JSON string, possibly batch array)
 * @returns {unknown[] | null} parsed batch, or null if not JSON
 */
export function tryParseWireBatch(text) {
  if (typeof text !== "string") return null;
  try {
    const parsed = JSON.parse(text);
    return Array.isArray(parsed) ? parsed : [parsed];
  } catch {
    return null;
  }
}

/** Wire `type` values dropped while historyPhase === loading (turn stream + approval). */
export const WIRE_TYPES_DROPPED_DURING_HISTORY_LOAD = new Set([
  "session.ack",
  "activity",
  "content.delta",
  "chart",
  "table",
  "tabular.tool_success",
  "task.state",
  "rate_control.status",
  "done",
  "error",
  "session.cancelled",
  "approval.required",
  "approval.resolved",
]);

/**
 * @param {string | undefined} wireType
 * @returns {boolean}
 */
export function shouldDropWireTypeDuringHistoryLoad(wireType) {
  return WIRE_TYPES_DROPPED_DURING_HISTORY_LOAD.has(String(wireType ?? ""));
}
