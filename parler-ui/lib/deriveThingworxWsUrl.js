/**
 * Derive ThingWorx AlwaysOn WebSocket URL when Mashup binding leaves it empty.
 * Path segment `/Thingworx/WS` is fixed per platform convention.
 *
 * @param {Window | undefined} [win]
 * @returns {string} e.g. `wss://host:8443/Thingworx/WS` or empty if `window` / host missing
 */
export function deriveThingworxWsUrlFromWindow(win) {
  const w = win ?? (typeof window !== "undefined" ? window : undefined);
  if (!w?.location) return "";
  const loc = w.location;
  const ssl = loc.protocol === "https:";
  const scheme = ssl ? "wss" : "ws";
  const host = String(loc.hostname || "").trim();
  if (!host) return "";
  const port = String(loc.port || "").trim();
  const hostPort = port ? `${host}:${port}` : host;
  return `${scheme}://${hostPort}/Thingworx/WS`;
}

/**
 * @param {string | undefined | null} binding
 * @param {Window | undefined} [win]
 */
export function resolveThingworxWsUrlBinding(binding, win) {
  const b = String(binding ?? "").trim();
  if (b) return b;
  return deriveThingworxWsUrlFromWindow(win);
}
