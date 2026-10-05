/**
 * Host Context turn-state UI helpers — docs/architecture/host-context-turn-state.md §4.
 * @typedef {import('./types.js').ChatRow} ChatRow
 * @typedef {import('./types.js').HostContextSnapshotWire} HostContextSnapshotWire
 */

export const HOST_CONTEXT_SNAPSHOT_SCHEMA = "parler-host-context-snapshot-v1";

/** @param {unknown} v */
function isRecord(v) {
  return typeof v === "object" && v !== null;
}

/**
 * @param {string} text
 * @returns {number}
 */
export function utf8ByteLength(text) {
  if (text == null || text === "") return 0;
  return new TextEncoder().encode(String(text)).length;
}

/**
 * @param {unknown} raw
 * @returns {HostContextSnapshotWire | undefined}
 */
export function parseHostContextSnapshot(raw) {
  if (!isRecord(raw)) return undefined;
  const schema = String(raw.schema ?? "").trim();
  if (schema && schema !== HOST_CONTEXT_SNAPSHOT_SCHEMA) return undefined;
  /** @type {HostContextSnapshotWire} */
  const out = {
    schema: HOST_CONTEXT_SNAPSHOT_SCHEMA,
    outcome: String(raw.outcome ?? "").trim() || undefined,
    accepted: raw.accepted === true,
  };
  if (typeof raw.key === "string" && raw.key.trim()) out.key = raw.key.trim();
  if (typeof raw.hash === "string" && raw.hash.trim()) out.hash = raw.hash.trim();
  if (typeof raw.utf8Bytes === "number" && Number.isFinite(raw.utf8Bytes)) {
    out.utf8Bytes = raw.utf8Bytes;
  }
  if (typeof raw.changedFromPreviousUserTurn === "boolean") {
    out.changedFromPreviousUserTurn = raw.changedFromPreviousUserTurn;
  }
  if (typeof raw.rawJsonStored === "boolean") out.rawJsonStored = raw.rawJsonStored;
  if (typeof raw.rawJson === "string") out.rawJson = raw.rawJson;
  if (typeof raw.rejectCode === "string" && raw.rejectCode.trim()) {
    out.rejectCode = raw.rejectCode.trim();
  }
  if (typeof raw.rejectDetail === "string" && raw.rejectDetail.trim()) {
    out.rejectDetail = raw.rejectDetail.trim();
  }
  if (!out.outcome && out.accepted) out.outcome = "ACCEPTED";
  return out;
}

/**
 * @param {HostContextSnapshotWire | undefined} snap
 * @returns {boolean}
 */
export function shouldShowHostContextDisclosure(snap) {
  if (!snap) return false;
  const outcome = String(snap.outcome ?? "").toUpperCase();
  return outcome !== "" && outcome !== "ABSENT";
}

/**
 * @param {HostContextSnapshotWire} snap
 * @returns {string}
 */
export function hostContextDisclosureSummaryLabel(snap) {
  const key = snap.key ? snap.key : "(no key)";
  const bytes =
    typeof snap.utf8Bytes === "number" && Number.isFinite(snap.utf8Bytes)
      ? snap.utf8Bytes
      : 0;
  const outcome = String(snap.outcome ?? "").toUpperCase();
  if (outcome && outcome !== "ACCEPTED") {
    const code = snap.rejectCode ? ` · ${snap.rejectCode}` : "";
    return `Host context: ${outcome.toLowerCase()}${code} · ${bytes} bytes`;
  }
  const change =
    snap.changedFromPreviousUserTurn === true
      ? "changed"
      : snap.changedFromPreviousUserTurn === false
        ? "unchanged"
        : "—";
  return `Host context: ${key} · ${change} · ${bytes} bytes`;
}

/**
 * Forward scan for anchor raw JSON (§4.1).
 * @param {HostContextSnapshotWire} snap
 * @param {ChatRow[]} rows
 * @param {number} rowIndex
 * @returns {{ available: boolean, text: string }}
 */
export function resolveHostContextRawJson(snap, rows, rowIndex) {
  if (!snap) return { available: false, text: "" };
  if (snap.rawJsonStored === true && typeof snap.rawJson === "string" && snap.rawJson.length > 0) {
    return { available: true, text: snap.rawJson };
  }
  const hash = typeof snap.hash === "string" ? snap.hash.trim() : "";
  if (!hash) {
    return { available: false, text: "" };
  }
  for (let i = rowIndex - 1; i >= 0; i--) {
    const r = rows[i];
    if (!r || r.kind !== "user" || !r.hostContext) continue;
    const prior = r.hostContext;
    if (
      prior.rawJsonStored === true &&
      typeof prior.rawJson === "string" &&
      prior.rawJson.length > 0 &&
      typeof prior.hash === "string" &&
      prior.hash.trim() === hash
    ) {
      return { available: true, text: prior.rawJson };
    }
  }
  return { available: false, text: "" };
}

/**
 * @param {string} raw
 * @returns {string}
 */
export function formatHostContextRawForDisplay(raw) {
  if (!raw) return "";
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

/**
 * @param {ChatRow[]} priorRows
 * @returns {HostContextSnapshotWire | undefined}
 */
function previousUserHostContext(priorRows) {
  for (let i = priorRows.length - 1; i >= 0; i--) {
    const r = priorRows[i];
    if (r?.kind === "user" && r.hostContext) return r.hostContext;
  }
  return undefined;
}

/**
 * @param {boolean} accepted
 * @param {string} wire
 * @param {HostContextSnapshotWire | undefined} previous
 * @returns {boolean}
 */
export function liveChangedFromPrevious(accepted, wire, previous) {
  if (!accepted) return false;
  if (!previous) return true;
  const prevOutcome = String(previous.outcome ?? "").toUpperCase();
  if (prevOutcome === "ABSENT") return true;
  if (prevOutcome === "ACCEPTED" || previous.accepted === true) {
    if (typeof previous.rawJson === "string" && previous.rawJson.length > 0) {
      return previous.rawJson !== wire;
    }
    return true;
  }
  return true;
}

/**
 * Best-effort live-turn snapshot from uplink wire bytes (server Stream row is authoritative).
 * @param {string} wire hostScopeJson wire read at Send
 * @param {ChatRow[]} priorRows rows before the new user line
 * @returns {HostContextSnapshotWire | undefined}
 */
export function buildLiveHostContextFromWire(wire, priorRows) {
  if (wire == null || wire === "") return undefined;
  const utf8Bytes = utf8ByteLength(wire);
  const previous = previousUserHostContext(priorRows);
  let key = "";
  let accepted = false;
  let outcome = "INVALID_JSON";
  try {
    const root = JSON.parse(wire);
    if (isRecord(root) && typeof root.key === "string" && root.key.trim()) {
      key = root.key.trim();
      accepted = true;
      outcome = "ACCEPTED";
    } else {
      outcome = "MISSING_KEY";
    }
  } catch {
    outcome = "INVALID_JSON";
  }
  const changed = liveChangedFromPrevious(accepted, wire, previous);
  /** @type {HostContextSnapshotWire} */
  const snap = {
    schema: HOST_CONTEXT_SNAPSHOT_SCHEMA,
    accepted,
    outcome,
    utf8Bytes,
    changedFromPreviousUserTurn: changed,
    rawJsonStored: true,
    rawJson: wire,
  };
  if (key) snap.key = key;
  return snap;
}
