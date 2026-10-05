/** @see CONTRACTS/API_CONTRACT.md § GetConnectionInfo */
export const CONNECTION_INFO_SCHEMA = "parler.connection-info.v1";

/**
 * Parse `GetConnectionInfo` JSON after `stringFromInvokeResult` (trimmed).
 * @param {string} jsonText
 * @returns {{ ok: true, extensionVersion: string, implementationVersion: string, supportsCancellation: boolean } | { ok: false, reason: "empty" | "parse" | "schema" }}
 */
export function parseConnectionInfoJson(jsonText) {
  if (!jsonText || !String(jsonText).trim()) {
    return { ok: false, reason: "empty" };
  }
  let o;
  try {
    o = JSON.parse(jsonText);
  } catch {
    return { ok: false, reason: "parse" };
  }
  if (o.schemaVersion !== CONNECTION_INFO_SCHEMA) {
    return { ok: false, reason: "schema" };
  }
  const ag = o.agent && typeof o.agent === "object" ? o.agent : null;
  const ext =
    ag && typeof ag.extensionVersion === "string" ? ag.extensionVersion.trim() : "";
  const implRaw =
    ag && typeof ag.implementationVersion === "string"
      ? ag.implementationVersion.trim()
      : "";
  const impl = implRaw || ext;
  const caps = o.capabilities && typeof o.capabilities === "object" ? o.capabilities : null;
  const supportsCancellation = caps != null && caps.supportsCancellation === true;
  return { ok: true, extensionVersion: ext, implementationVersion: impl, supportsCancellation };
}

/**
 * Late `GetConnectionInfo` callback guard (pairs with `#historyLoadEpoch`).
 * @param {number} epochAtStart
 * @param {number} currentEpoch
 */
export function connectionInfoEpochStale(epochAtStart, currentEpoch) {
  return epochAtStart !== currentEpoch;
}

/**
 * Connected-status suffix. Keep the compact `agent:widget` form when both handshakes succeed,
 * but name a lone version instead of presenting the missing peer as an unknown `?` version.
 *
 * @param {string} connectionStatus
 * @param {string} agentVersion
 * @param {string} widgetVersion
 */
export function formatConnectionVersionSuffix(
  connectionStatus,
  agentVersion,
  widgetVersion
) {
  if (connectionStatus !== "connected") return "";
  const agent = String(agentVersion || "").trim();
  const widget = String(widgetVersion || "").trim();
  if (agent && widget) return `, ${agent}:${widget}`;
  if (agent) return `, agent ${agent}`;
  if (widget) return `, widget ${widget}`;
  return "";
}
