/**
 * Parses {@code ParlerGateway.CancelUserPrompt} JSON result (schemaVersion 1).
 * @see CONTRACTS/API_CONTRACT.md — ParlerGateway.CancelUserPrompt
 * @param {string} raw trimmed text from {@code stringFromInvokeResult}
 * @returns {{ ok: true, status: string, alreadyRequested: boolean } | { ok: false, reason: "empty" | "parse" | "schema" }}
 */
export function parseCancelUserPromptResult(raw) {
  const s = raw != null ? String(raw).trim() : "";
  if (!s) {
    return { ok: false, reason: "empty" };
  }
  let o;
  try {
    o = JSON.parse(s);
  } catch {
    return { ok: false, reason: "parse" };
  }
  if (!o || typeof o !== "object") {
    return { ok: false, reason: "parse" };
  }
  const sv = o.schemaVersion;
  if (sv !== 1 && sv !== "1") {
    return { ok: false, reason: "schema" };
  }
  const status = o.status != null ? String(o.status).trim() : "";
  if (!status) {
    return { ok: false, reason: "parse" };
  }
  const ar = o.alreadyRequested;
  const alreadyRequested = ar === true || ar === "true";
  return { ok: true, status, alreadyRequested };
}
