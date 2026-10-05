/**
 * Live AlwaysOn transport for User-operated context-compaction eval runs.
 */

import { randomUUID } from "node:crypto";
import { pathToFileURL, fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { terminalFromEvents } from "./wire_contract.mjs";
import { loadAlwaysOnJsCodec } from "./codec_loader.mjs";

const REPO_ROOT = join(dirname(fileURLToPath(import.meta.url)), "..", "..");
const PARLER_UI = join(REPO_ROOT, "parler-ui", "lib");

/**
 * Derive AlwaysOn WebSocket URL from the same HTTP base used for REST
 * (`{base}/Thingworx/Things/...`). Mirrors `invoke_thing_service` path convention.
 * @param {string} httpBase
 */
export function wsUrlFromHttpBase(httpBase) {
  const u = new URL(httpBase);
  const ssl = u.protocol === "https:";
  const host = u.hostname;
  const port = u.port ? Number(u.port) : ssl ? 443 : 80;
  const pathPrefix = u.pathname.replace(/\/$/, "");
  const proto = ssl ? "wss" : "ws";
  return `${proto}://${host}:${port}${pathPrefix}/Thingworx/WS`;
}

/**
 * Bootstrap a fresh conversation and validate Gateway binding (§11.4).
 * @param {object} cmd
 */
export async function bootstrapLiveSession(cmd) {
  const { server, appKey } = cmd.credentials;
  const agentThing = cmd.agentThing;
  const title = `cc-m1a-${Date.now()}-${randomUUID().slice(0, 8)}`;
  const conversationId = await restGetOrCreateConversationId(server, appKey, agentThing, title);
  return { conversationId, title, agentThing, userTimezone: cmd.userTimezone ?? "", hostContext: cmd.hostContext ?? "" };
}

/**
 * @param {object} cmd
 * @param {{ server: string, appKey: string }} cmd.credentials
 * @param {string} cmd.agentThing
 * @param {string} [cmd.conversationId]
 * @param {(obj: Record<string, unknown>) => void} [cmd.emit]
 */
export async function createLiveTransport(cmd) {
  const bootstrap = cmd.conversationId
    ? {
        conversationId: cmd.conversationId,
        title: cmd.conversationTitle ?? cmd.conversationId,
        agentThing: cmd.agentThing,
        userTimezone: cmd.userTimezone ?? "",
        hostContext: cmd.hostContext ?? "",
      }
    : await bootstrapLiveSession(cmd);

  const { parseThingworxWsUrl } = await import(pathToFileURL(join(PARLER_UI, "parseThingworxWsUrl.js")).href);
  const { promiseAlwaysOnConnectBind } = await import(pathToFileURL(join(PARLER_UI, "alwaysOnConnect.js")).href);
  const { buildSubmitUserPromptParams, buildGetConnectionInfoParams } = await import(
    pathToFileURL(join(PARLER_UI, "alwaysOnInvokeParams.js")).href
  );
  const { stringFromInvokeResult } = await loadAlwaysOnJsCodec();
  const { parseConnectionInfoJson } = await import(pathToFileURL(join(PARLER_UI, "connectionInfoHandshake.js")).href);

  const wsUrl = cmd.credentials.wsUrl ?? wsUrlFromHttpBase(cmd.credentials.server);
  const parsed = parseThingworxWsUrl(wsUrl);
  /** @type {any[]} */
  const pendingWire = [];

  cmd.emit?.({
    kind: "run_identity",
    conversationId: bootstrap.conversationId,
    conversationTitle: bootstrap.title,
    agentThing: bootstrap.agentThing,
    userTimezone: bootstrap.userTimezone,
    hostContextPresent: Boolean(String(bootstrap.hostContext ?? "").trim()),
  });

  const { client } = await promiseAlwaysOnConnectBind({
    parsed,
    appKey: cmd.credentials.appKey,
    conversationThingName: bootstrap.conversationId,
    onReceiveMessageText(text) {
      try {
        const event = JSON.parse(text);
        pendingWire.push(event);
        listener?.(event);
      } catch {
        /* ignore non-json frames */
      }
    },
  });

  const connectionInfoRaw = await invokeAlwaysOnService(
    client,
    bootstrap.conversationId,
    "GetConnectionInfo",
    buildGetConnectionInfoParams(bootstrap.agentThing, "")
  );
  const connectionInfoText = stringFromInvokeResult(connectionInfoRaw).trim();
  const parsedInfo = parseConnectionInfoJson(connectionInfoText);
  if (!parsedInfo.ok) {
    throw new Error(`GetConnectionInfo failed: ${parsedInfo.reason}`);
  }
  cmd.emit?.({
    kind: "connection_info",
    conversationId: bootstrap.conversationId,
    extensionVersion: parsedInfo.extensionVersion,
    implementationVersion: parsedInfo.implementationVersion,
    supportsCancellation: parsedInfo.supportsCancellation,
  });

  /** @type {((event: any) => void) | null} */
  let listener = null;

  return {
    agentThing: bootstrap.agentThing,
    gatewayThing: bootstrap.conversationId,
    conversationId: bootstrap.conversationId,
    onWireEvent(fn) {
      listener = fn;
      for (const event of pendingWire) fn(event);
      return () => {
        if (listener === fn) listener = null;
      };
    },
    async submitTurn({ turn, conversationId, agentThing }) {
      const result = await invokeAlwaysOnService(
        client,
        conversationId,
        "SubmitUserPrompt",
        buildSubmitUserPromptParams(
          turn.expandedPrompt ?? turn.prompt ?? "",
          agentThing,
          bootstrap.userTimezone,
          bootstrap.hostContext
        )
      );
      const requestId = stringFromInvokeResult(result).trim();
      if (!requestId) {
        throw new Error("SubmitUserPrompt returned empty request_id");
      }
      return { requestId };
    },
    async pollTerminal(requestId, conversationId) {
      return terminalFromEvents(pendingWire, conversationId, requestId);
    },
    async close() {
      client.close();
    },
  };
}

/** @param {any} client @param {string} entityName @param {string} serviceName @param {any} parameters */
function invokeAlwaysOnService(client, entityName, serviceName, parameters) {
  return new Promise((resolve, reject) => {
    client.invokeService(
      {
        entityName,
        serviceName,
        entityType: "Things",
        parameters,
      },
      (err, result) => {
        if (err) reject(err);
        else resolve(result);
      }
    );
  });
}

/** @param {string} server @param {string} appKey @param {string} agentThing @param {string} title */
async function restGetOrCreateConversationId(server, appKey, agentThing, title) {
  const url = `${server.replace(/\/$/, "")}/Thingworx/Things/${encodeURIComponent(agentThing)}/Services/GetOrCreateConversationId`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Accept: "application/json",
      appKey,
    },
    body: JSON.stringify({ title }),
  });
  if (!response.ok) {
    throw new Error(`GetOrCreateConversationId HTTP ${response.status}`);
  }
  const parsed = await response.json();
  const rows = extractRows(parsed);
  if (rows.length !== 1) {
    throw new Error(`GetOrCreateConversationId expected 1 row, got ${rows.length}`);
  }
  const conversationId = rows[0]?.conversationId;
  if (!conversationId || !String(conversationId).trim()) {
    throw new Error("GetOrCreateConversationId missing conversationId");
  }
  return String(conversationId);
}

/** @param {any} body */
function extractRows(body) {
  if (body && Array.isArray(body.rows)) {
    return body.rows;
  }
  if (body && body.data && Array.isArray(body.data.rows)) {
    return body.data.rows;
  }
  return [];
}
