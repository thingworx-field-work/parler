/**
 * Opens an auxiliary window for assistant-row print (`document.write` of built HTML).
 *
 * **Do not** pass `noopener` in `window.open`'s feature string: when `noopener` is set, the
 * HTML Living Standard requires the return value to be `null` in the opener window while the
 * new browsing context may still be created — the main Print path would always look
 * "popup blocked" and strand a blank tab. Use `rel="noopener noreferrer"` on `<a>` links
 * (e.g. Blob fallback) instead.
 *
 * @param {Pick<Window, "open">} [win] Defaults to `globalThis.window` in the browser.
 * @returns {Window | null}
 */
export function openPrintDocumentWindow(win) {
  const w = win ?? (typeof globalThis !== "undefined" ? globalThis.window : undefined);
  if (!w || typeof w.open !== "function") return null;
  return w.open("", "_blank");
}
