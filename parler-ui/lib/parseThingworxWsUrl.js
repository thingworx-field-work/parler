/**
 * Parse a full ThingWorx AlwaysOn WebSocket URL into host / port / ssl / path for the AlwaysOn client.
 * Example: `wss://host:443/Thingworx/WS`
 *
 * @param {string} raw
 * @returns {{ host: string, port: number, ssl: boolean, path: string, endpoint: string }}
 */
export function parseThingworxWsUrl(raw) {
  const s = String(raw || "").trim();
  if (!s) {
    throw new Error("WebSocket URL is empty.");
  }
  let u;
  try {
    u = new URL(s);
  } catch {
    throw new Error("Invalid WebSocket URL.");
  }
  const protocol = u.protocol.toLowerCase();
  if (protocol !== "ws:" && protocol !== "wss:") {
    throw new Error("URL must start with ws:// or wss://");
  }
  const ssl = protocol === "wss:";
  const host = u.hostname || "";
  if (!host) {
    throw new Error("URL must include a host.");
  }
  let port = u.port ? Number(u.port) : ssl ? 443 : 80;
  if (!Number.isFinite(port) || port <= 0) {
    throw new Error("Invalid port in URL.");
  }
  let pathname = u.pathname || "/";
  if (!pathname.endsWith("/")) {
    pathname += "/";
  }
  const parts = pathname.replace(/\/+$/, "").split("/").filter(Boolean);
  if (parts.length < 1) {
    throw new Error("URL path must include endpoint (e.g. …/Thingworx/WS).");
  }
  const endpoint = parts.pop();
  const path = "/" + parts.join("/") + (parts.length ? "/" : "");
  return { host, port, ssl, path, endpoint };
}
