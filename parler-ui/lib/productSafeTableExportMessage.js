/**
 * Product-safe table export footer text: allow known agent messages only; everything else maps to a generic string.
 * @see CONTRACTS/TABLE_CONTRACT.md
 * @param {string | null | undefined} msg
 * @returns {string}
 */
export function productSafeTableExportMessage(msg) {
  const s = String(msg ?? "").trim();
  if (!s) return "";

  /** Exact strings from {@code ParlerTableFileExportHook} and related agent paths. */
  const exact = new Set([
    "CSV export unavailable.",
    "Full CSV export requires a conversation cacheId for the complete table; only a sample is on the wire.",
    "CSV export produced no content.",
    "CSV export skipped: result exceeds configured size limit.",
    "CSV export aborted: target path already exists and alternate paths could not be reserved.",
    "Table export skipped: AgentThing exportFileRepository (FileRepository Thing name) is empty.",
  ]);
  if (exact.has(s)) return s;

  if (s.startsWith("FileRepository Thing not found:")) {
    return "Export repository was not found.";
  }
  if (s.startsWith("Configured exportFileRepository is not a Thing:")) {
    return "Export repository is not a valid FileRepository Thing.";
  }

  return "CSV export unavailable.";
}
