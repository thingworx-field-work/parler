/**
 * Minimal AlwaysOn client: WebSocket + @xudesheng/alwayson-js-codec.
 * After bind: periodic **application-level ping** (AlwaysOn protocol ping frame, default 10s).
 * Auth → Bind → dispatch inbound requests. **Required** platform→edge services on the bound name:
 * `GetMetadata`, `SynchronizeModelState`, `ReceiveMessage` (see `docs/architecture/agent-alwayson.md` §4).
 * `NotifyPropertyUpdate` is accepted as a no-op success when present.
 * **Auth** failures append a short-lived App Key hint for Mashup flows (see `docs/architecture/agent-alwayson.md`).
 */

import {
  build_auth_message,
  build_bind_message,
  build_response_message,
  build_service_request_message,
  build_success_response_message,
  encodeAlwaysOnPing,
  merge_multipart_chunks_json,
  parse_inbound_message,
  split_multipart_message_bytes,
} from "@xudesheng/alwayson-js-codec";
import {
  emptyNamedVtqInfoTable,
  getMetadataSuccessInfoTable,
} from "./parlerInfotableJson.js";

const MAX_BINARY = 8192;

/** Default AlwaysOn application-level ping interval. */
const DEFAULT_PING_INTERVAL_MS = 10000;

/** Appended to **auth** `fail` reasons — Mashup keys are often valid only briefly (~seconds). */
const SHORT_LIVED_APP_KEY_HINT =
  " Mashup App Keys are often short-lived (on the order of seconds); refresh the key (e.g. GetClientApplicationKey) and call ConnectAndBind again.";

/** @param {unknown} err */
function augmentAuthFailure(err) {
  const base = err instanceof Error ? err.message : String(err);
  return new Error(base + SHORT_LIVED_APP_KEY_HINT);
}

/** @param {unknown} item */
function coerceWireChunk(item) {
  if (item instanceof Uint8Array) return item;
  if (item instanceof ArrayBuffer) return new Uint8Array(item);
  if (ArrayBuffer.isView(item)) {
    return new Uint8Array(item.buffer, item.byteOffset, item.byteLength);
  }
  throw new Error("AlwaysOnParler: unexpected multipart chunk type from split_multipart_message_bytes");
}

function responseIsSuccess(code) {
  if (typeof code !== "string") return false;
  return new Set(["Success", "Created", "Accepted", "NoContent", "NotModified"]).has(code);
}

function wsUrlFromParsed(parsed) {
  const proto = parsed.ssl ? "wss" : "ws";
  return `${proto}://${parsed.host}:${parsed.port}${parsed.path}${parsed.endpoint}`;
}

function concatBuffers(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a, 0);
  out.set(b, a.length);
  return out;
}

function u32Signed(n) {
  return n >>> 0;
}

/** Protocol sentinel for session/endpoint-only success frames (`request_id` u32::MAX); JSON may be `-1` or `4294967295`. */
function isBroadcastOrMetaRequestId(rid) {
  return rid === -1 || u32Signed(rid) === 0xffffffff;
}

export class AlwaysOnParlerClient {
  /**
   * @param {object} opts
   * @param {import('./parseThingworxWsUrl.js').ParsedThingworxWsUrl} opts.parsed
   * @param {string} opts.appKey
   * @param {string} opts.gatewayName
   * @param {string} opts.gatewayType
   * @param {string} opts.conversationThingName
   * @param {(text: string) => void} opts.onReceiveMessageText
   * @param {(connected: boolean) => void} [opts.connectionCallback]
   * @param {(err: unknown) => void} [opts.onClientError]
   * @param {(phase: string, detail?: unknown) => void} [opts.debugLog]
   * @param {number} [opts.pingIntervalMs] Application-level AlwaysOn ping period (ms), same role as SDK `pingTimeout`. `0` disables.
   */
  constructor(opts) {
    this._parsed = opts.parsed;
    this._appKey = opts.appKey;
    this._gatewayName = opts.gatewayName;
    this._gatewayType = opts.gatewayType;
    this._conversationThing = opts.conversationThingName;
    this._onReceiveMessageText = opts.onReceiveMessageText;
    this._connectionCallback = opts.connectionCallback;
    this._onClientError = opts.onClientError;
    this._debugLog = opts.debugLog;
    this._pingIntervalMs =
      opts.pingIntervalMs !== undefined ? opts.pingIntervalMs : DEFAULT_PING_INTERVAL_MS;

    this._ws = null;
    this._closed = false;
    /** When true, pending auth/bind `fail` must not call `_onClientError` (avoids duplicate user errors during `close()` teardown). */
    this._muteUserErrorFromPendingFail = false;
    this._requestId = 0;
    this._endpointId = u32Signed(0xffffffff);
    this._sessionId = u32Signed(0xffffffff);
    /** @type {Uint8Array} */
    this._rx = new Uint8Array(0);

    /** @type {Map<number, { ok: (v?: unknown) => void, fail: (e: Error) => void, label: string }>} */
    this._pending = new Map();
    /** @type {Map<number, object[]>} */
    this._multipart = new Map();

    /** @type {ReturnType<typeof setInterval> | null} */
    this._pingTimer = null;
  }

  _log(phase, detail) {
    this._debugLog?.(phase, detail);
  }

  open() {
    const url = wsUrlFromParsed(this._parsed);
    this._log("AlwaysOnParler: WebSocket connect", { url });
    this._ws = new WebSocket(url);
    this._ws.binaryType = "arraybuffer";
    this._ws.onopen = () => this._onOpen();
    this._ws.onmessage = (ev) => this._onMessage(ev);
    this._ws.onerror = () => this._onWebSocketError();
    this._ws.onclose = () => this._onClose();
  }

  _onOpen() {
    this._log("AlwaysOnParler: open, sending auth");
    const authRid = this._requestId++;
    const bytes = build_auth_message(authRid, this._appKey);
    this._sendRaw(bytes);
    this._pending.set(authRid, {
      label: "auth",
      ok: () => {
        this._log("AlwaysOnParler: auth OK");
        this._startBind();
      },
      fail: (e) => {
        if (!this._muteUserErrorFromPendingFail) {
          this._onClientError?.(augmentAuthFailure(e));
        }
        this._connectionCallback?.(false);
      },
    });
  }

  _onWebSocketError() {
    const labels = new Set(Array.from(this._pending.values(), (p) => p.label));
    if (labels.has("auth")) {
      this._onClientError?.(augmentAuthFailure(new Error("WebSocket error during auth")));
      return;
    }
    if (labels.has("bind")) {
      this._onClientError?.(new Error("WebSocket error during bind"));
      return;
    }
    this._onClientError?.(new Error("WebSocket error"));
  }

  _onClose() {
    this._stopPingTimer();
    this._log("AlwaysOnParler: socket closed");
    this._failAllPending(new Error("Connection closed"));
    this._ws = null;
    if (!this._closed) {
      this._connectionCallback?.(false);
    }
  }

  _failAllPending(err) {
    for (const [, p] of this._pending) {
      try {
        p.fail(err);
      } catch {
        /* ignore */
      }
    }
    this._pending.clear();
    this._multipart.clear();
  }

  /**
   * @param {Uint8Array} bytes
   */
  _sendRaw(bytes) {
    if (!this._ws || this._ws.readyState !== WebSocket.OPEN) {
      throw new Error("WebSocket not open");
    }
    if (bytes.length > MAX_BINARY) {
      throw new Error(
        `AlwaysOnParler: outbound frame ${bytes.length} bytes exceeds ${MAX_BINARY}`
      );
    }
    this._ws.send(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength));
  }

  /**
   * @param {MessageEvent} ev
   */
  _onMessage(ev) {
    const ab = ev.data;
    if (!(ab instanceof ArrayBuffer)) return;
    const chunk = new Uint8Array(ab);
    this._rx = this._rx.length ? concatBuffers(this._rx, chunk) : chunk;
    this._drainRx();
  }

  _drainRx() {
    while (this._rx.length > 0) {
      let jsonStr;
      try {
        jsonStr = parse_inbound_message(this._rx);
      } catch {
        break;
      }
      let msg;
      try {
        msg = JSON.parse(jsonStr);
      } catch {
        this._rx = new Uint8Array(0);
        this._onClientError?.(new Error("parse_inbound_message JSON"));
        break;
      }
      const consumed = Number(msg.consumed) || 0;
      if (!consumed || consumed > this._rx.length) {
        this._rx = new Uint8Array(0);
        this._onClientError?.(new Error("invalid consumed length"));
        break;
      }
      this._rx = this._rx.slice(consumed);
      try {
        this._dispatch(msg);
      } catch (e) {
        this._onClientError?.(e);
      }
    }
  }

  /**
   * @param {object} msg
   */
  _dispatch(msg) {
    if (msg.kind === "multipart_chunk") {
      this._handleMultipartChunk(msg);
      return;
    }
    if (msg.kind === "request") {
      this._handleInboundRequest(msg);
      return;
    }
    if (msg.kind === "response") {
      this._handleResponse(msg);
    }
  }

  _handleMultipartChunk(parsed) {
    const rid = parsed.header?.request_id;
    if (rid == null) return;
    const body = parsed.body;
    const chunkObj = {
      header: { ...parsed.header, multipart: true },
      chunk_id: body.chunk_id,
      chunk_count: body.chunk_count,
      chunk_size: body.chunk_size,
      entity_type: body.entity_type ?? null,
      entity_name: body.entity_name ?? null,
      data: body.data ?? [],
    };
    let arr = this._multipart.get(rid);
    if (!arr) {
      arr = [];
      this._multipart.set(rid, arr);
    }
    arr[body.chunk_id - 1] = chunkObj;
    if (arr.filter(Boolean).length !== body.chunk_count) return;

    const merged = JSON.parse(merge_multipart_chunks_json(JSON.stringify(arr)));
    this._multipart.delete(rid);
    this._dispatch(merged);
  }

  _handleResponse(msg) {
    const hdr = msg.header;
    const reqId = hdr.request_id;
    const code = hdr.code;

    if (isBroadcastOrMetaRequestId(reqId)) {
      if (hdr.session_id !== undefined && u32Signed(hdr.session_id) !== 0xffffffff) {
        this._sessionId = u32Signed(hdr.session_id);
      }
      if (hdr.endpoint !== undefined && u32Signed(hdr.endpoint) !== 0xffffffff) {
        this._endpointId = u32Signed(hdr.endpoint);
      }
      return;
    }

    if (hdr.session_id !== undefined && u32Signed(hdr.session_id) !== 0xffffffff) {
      this._sessionId = u32Signed(hdr.session_id);
    }
    if (hdr.endpoint !== undefined && u32Signed(hdr.endpoint) !== 0xffffffff) {
      this._endpointId = u32Signed(hdr.endpoint);
    }

    const pending = this._pending.get(reqId);
    if (!pending) {
      this._log("AlwaysOnParler: unmatched response", { reqId, code });
      return;
    }
    this._pending.delete(reqId);

    if (responseIsSuccess(code)) {
      pending.ok(msg);
    } else {
      const body = msg.body;
      this._log("AlwaysOnParler: non-success response", { reqId, code, body });
      const fromBody =
        body &&
        typeof body === "object" &&
        (body.reason ?? body.message ?? body.error ?? body.localizedMessage);
      let text =
        fromBody != null && String(fromBody).trim() !== ""
          ? String(fromBody).trim()
          : String(code || "error");
      try {
        if (body != null && typeof body === "object") {
          const full = JSON.stringify(body);
          if (
            full &&
            full !== "{}" &&
            full.length < 2000 &&
            !text.includes(full.slice(0, Math.min(80, full.length)))
          ) {
            text = `${text} | ${full}`;
          }
        }
      } catch {
        /* ignore */
      }
      pending.fail(new Error(text));
    }
  }

  _handleInboundRequest(msg) {
    const hdr = msg.header;
    const body = msg.body;
    const reqId = hdr.request_id;
    const ep = u32Signed(hdr.endpoint);
    const sess = u32Signed(hdr.session_id);
    const entity = body.entity_name;
    const service = body.characteristic_name;

    const replySuccess = (paramsTable) => {
      const paramsJson = paramsTable != null ? JSON.stringify(paramsTable) : null;
      const out = build_success_response_message(reqId, ep, sess, paramsJson);
      this._sendRaw(out);
    };

    const replyError = (statusU8, reason) => {
      const out = build_response_message(reqId, ep, sess, statusU8, reason, null);
      this._sendRaw(out);
    };

    // Phase F: single bind name — conversationId equals transient ParlerGateway Thing name.
    const bound = new Set([this._conversationThing]);

    if (!bound.has(entity)) {
      replyError(0x84, `Thing ${entity} does not exist`);
      return;
    }

    if (service === "GetMetadata") {
      replySuccess(getMetadataSuccessInfoTable(entity));
      return;
    }

    if (service === "SynchronizeModelState") {
      replySuccess(emptyNamedVtqInfoTable());
      return;
    }

    if (service === "ReceiveMessage") {
      const payloadStr = extractReceiveMessagePayload(body);
      try {
        this._onReceiveMessageText(payloadStr);
      } catch (e) {
        replyError(0xa0, e instanceof Error ? e.message : String(e));
        return;
      }
      replySuccess(null);
      return;
    }

    if (service === "NotifyPropertyUpdate") {
      replySuccess(null);
      return;
    }

    replyError(0x85, `Method not permitted: ${service}`);
  }

  _startBind() {
    const names = JSON.stringify([this._conversationThing]);
    const rid = this._requestId++;
    const bytes = build_bind_message(
      rid,
      this._endpointId,
      this._sessionId,
      this._gatewayName,
      this._gatewayType,
      names
    );
    this._pending.set(rid, {
      label: "bind",
      ok: () => {
        this._log("AlwaysOnParler: bind OK");
        this._startPingTimer();
        this._connectionCallback?.(true);
      },
      fail: (e) => {
        if (!this._muteUserErrorFromPendingFail) {
          this._onClientError?.(e instanceof Error ? e : new Error(String(e)));
        }
        this._connectionCallback?.(false);
      },
    });
    this._sendRaw(bytes);
  }

  _startPingTimer() {
    this._stopPingTimer();
    if (this._pingIntervalMs <= 0 || !this._ws) return;
    this._pingTimer = setInterval(() => this._sendPingIfOpen(), this._pingIntervalMs);
    // First ping ASAP: server read-idle timeouts are often ~10–15s; waiting a full interval after bind
    // risks closing before the first setInterval tick (throttled timers in embedded hosts make this worse).
    queueMicrotask(() => this._sendPingIfOpen());
  }

  _stopPingTimer() {
    if (this._pingTimer != null) {
      clearInterval(this._pingTimer);
      this._pingTimer = null;
    }
  }

  _sendPingIfOpen() {
    if (this._closed || !this._ws || this._ws.readyState !== WebSocket.OPEN) return;
    // Only ping once a session is bound: sessionId !== -1 (unset / 0xffffffff stored as u32).
    if ((this._sessionId | 0) === -1) return;
    try {
      const bytes = encodeAlwaysOnPing(this._endpointId, this._sessionId);
      this._sendRaw(bytes);
    } catch (e) {
      this._log("AlwaysOnParler: ping send failed", e);
    }
  }

  close() {
    this._closed = true;
    this._stopPingTimer();
    try {
      this._ws?.close();
    } catch {
      /* ignore */
    }
    this._ws = null;
    this._muteUserErrorFromPendingFail = true;
    try {
      this._failAllPending(new Error("Client closed"));
    } finally {
      this._muteUserErrorFromPendingFail = false;
    }
  }

  /**
   * @param {object} serviceSpec
   * @param {(err: Error | null, result?: unknown) => void} callback
   * No invoke RPC timeout: `ReceiveMessage` may deliver stream frames before the invoke response returns
   * `request_id`; aborting the pending request would strand backlog and drop a late response (see
   * `docs/ui/load-history.md` §4.3 / `docs/architecture/agent-alwayson.md`). The widget/lab add a **UI-only** pre-`request_id` wait
   * (`PRE_REQUEST_ID_TIMEOUT_MS` in `parler-ui.js`) that blocks Send and surfaces an error without
   * cancelling the codec pending invoke.
   */
  invokeService(serviceSpec, callback) {
    // Codec `build_service_request_message` deserializes params as full `InfoTable` (`datashape` + `rows`).
    // Mashup-style `parameters: {}` stringify to `"{}"` and fail serde; no-arg services must use `Nothing` on the wire (`null` here).
    let paramsJson = null;
    if (serviceSpec.parameters != null) {
      const raw = JSON.stringify(serviceSpec.parameters);
      if (raw !== "{}") {
        paramsJson = raw;
      }
    }
    const rid = this._requestId++;
    let bytes;
    try {
      bytes = build_service_request_message(
        rid,
        this._endpointId,
        this._sessionId,
        serviceSpec.entityName,
        serviceSpec.serviceName,
        paramsJson
      );
    } catch (e) {
      callback(e instanceof Error ? e : new Error(String(e)), null);
      return;
    }
    try {
      if (bytes.length <= MAX_BINARY) {
        this._sendRaw(bytes);
      } else {
        const wireChunks = split_multipart_message_bytes(bytes);
        const n = wireChunks?.length ?? 0;
        if (!n) {
          callback(
            new Error("AlwaysOnParler: split_multipart_message_bytes returned no chunks"),
            null
          );
          return;
        }
        for (let i = 0; i < n; i++) {
          const chunk = coerceWireChunk(wireChunks[i]);
          if (chunk.length > MAX_BINARY) {
            callback(
              new Error(
                `AlwaysOnParler: outbound chunk ${i + 1}/${n} length ${chunk.length} exceeds ${MAX_BINARY}`
              ),
              null
            );
            return;
          }
          this._sendRaw(chunk);
        }
      }
    } catch (e) {
      callback(e instanceof Error ? e : new Error(String(e)), null);
      return;
    }
    this._pending.set(rid, {
      label: "invoke",
      ok: (m) => {
        callback(null, invokeResultShim(m));
      },
      fail: (e) => {
        callback(e, null);
      },
    });
  }
}

function extractReceiveMessagePayload(body) {
  const params = body.params;
  if (!params?.rows?.length) return "";
  const row = params.rows[0];
  const fields = row.fields;
  if (!fields?.length) return "";
  const f0 = fields[0];
  if (f0?.kind === "String" && Array.isArray(f0.value) && f0.value.length >= 2) {
    return String(f0.value[1] ?? "");
  }
  return "";
}

function invokeResultShim(msg) {
  const params = msg.body?.params;
  if (!params) return { type: "STRING", content: "" };
  return {
    type: "INFOTABLE",
    content: codecInfoTableToSdkLike(params),
  };
}

function codecInfoTableToSdkLike(params) {
  const defs = params.datashape?.entries || {};
  const fieldDefinitions = {};
  for (const [k, v] of Object.entries(defs)) {
    fieldDefinitions[k] = {
      name: v.name,
      baseType: v.baseType,
    };
  }
  const keys = Object.keys(defs);
  const rows = (params.rows || []).map((row) => {
    const out = {};
    keys.forEach((key, i) => {
      const f = row.fields?.[i];
      if (f?.kind === "String" && Array.isArray(f.value)) {
        out[key] = { type: "STRING", content: String(f.value[1] ?? "") };
      } else {
        out[key] = f;
      }
    });
    return out;
  });
  return {
    dataShape: { fieldDefinitions },
    rows,
    getRowCount() {
      return rows.length;
    },
  };
}
