/**
 * Read Lit-backed widget properties with Mashup HTML-attribute fallback.
 * Used by `<parler-ui>` for AlwaysOn invoke parameters.
 */

/**
 * Prefer Lit-backed property; fall back to HTML attribute (some Mashup paths set attributes only).
 * Trims leading/trailing whitespace (legacy behavior for ids and human-entered fields).
 * **Do not** use for **wire-byte** contracts (e.g. **`hostContext`** / **`API_CONTRACT.md`** § `hostContext` — **Wire bytes**); use {@link boundTransportStringWire} instead.
 * @param {HTMLElement} el
 * @param {string} prop
 * @param {string} attr
 */
export function boundTransportString(el, prop, attr) {
  const rec = /** @type {Record<string, unknown>} */ (/** @type {unknown} */ (el));
  const v = rec[prop];
  const fromProp =
    v != null && typeof v !== "undefined" ? String(v).trim() : "";
  if (fromProp) return fromProp;
  const a = el.getAttribute(attr);
  return a != null ? String(a).trim() : "";
}

/**
 * Same resolution order as {@link boundTransportString} without trimming — wire-byte contracts.
 * **Used by:** **`hostScopeJson`** → **`hostContext`** on Send (`parler-ui.js`).
 * @param {HTMLElement} el
 * @param {string} prop
 * @param {string} attr
 */
export function boundTransportStringWire(el, prop, attr) {
  const rec = /** @type {Record<string, unknown>} */ (/** @type {unknown} */ (el));
  const v = rec[prop];
  if (v != null && typeof v !== "undefined") {
    return String(v);
  }
  const a = el.getAttribute(attr);
  return a != null ? String(a) : "";
}
