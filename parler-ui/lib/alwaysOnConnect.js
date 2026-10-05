/**
 * Promise wrapper: WebSocket + alwayson-js-codec AlwaysOnParlerClient (auth + bind).
 * @see docs/architecture/agent-alwayson.md
 */

import { AlwaysOnParlerClient } from "./alwaysOnParlerClient.js";

/**
 * @param {object} opts
 * @param {{ host: string, port: number, ssl: boolean, path: string, endpoint: string }} opts.parsed from {@link parseThingworxWsUrl}
 * @param {string} opts.appKey
 * @param {string} opts.conversationThingName
 * @param {(text: string) => void} opts.onReceiveMessageText
 * @param {(connected: boolean) => void} [opts.connectionCallback]
 * @param {(err: unknown) => void} [opts.onClientError]
 * @param {(phase: string, detail?: unknown) => void} [opts.debugLog]
 * @param {number} [opts.pingIntervalMs] Passed to {@link AlwaysOnParlerClient} (SDK-style keepalive; `0` disables).
 * @returns {Promise<{ client: AlwaysOnParlerClient }>}
 */
export function promiseAlwaysOnConnectBind(opts) {
  const {
    parsed,
    appKey,
    conversationThingName,
    onReceiveMessageText,
    connectionCallback,
    onClientError,
    debugLog,
    pingIntervalMs,
  } = opts;

  const cid =
    conversationThingName != null && String(conversationThingName).trim()
      ? String(conversationThingName).trim()
      : "";
  if (!cid) {
    return Promise.reject(
      new Error("AlwaysOn: conversationThingName (conversationId / Gateway name) is required")
    );
  }
  const gatewayName = cid;
  const gatewayType = "ParlerGateway";

  return new Promise((resolve, reject) => {
    let settled = false;

    debugLog?.("AlwaysOn: creating Parler client", {
      host: parsed.host,
      port: parsed.port,
      ssl: parsed.ssl,
      path: parsed.path,
      endpoint: parsed.endpoint,
      gatewayName,
    });

    const client = new AlwaysOnParlerClient({
      parsed,
      appKey,
      gatewayName,
      gatewayType,
      conversationThingName,
      onReceiveMessageText,
      connectionCallback(connected) {
        connectionCallback?.(connected);
        if (connected && !settled) {
          settled = true;
          resolve({ client });
        }
      },
      onClientError(err) {
        if (settled) return;
        settled = true;
        onClientError?.(err);
        try {
          client.close();
        } catch {
          /* ignore */
        }
        reject(err instanceof Error ? err : new Error(String(err)));
      },
      debugLog,
      pingIntervalMs,
    });

    try {
      client.open();
    } catch (e) {
      if (!settled) {
        settled = true;
        try {
          client.close();
        } catch {
          /* ignore */
        }
        reject(e instanceof Error ? e : new Error(String(e)));
      }
    }
  });
}
