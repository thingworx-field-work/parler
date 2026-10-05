import assert from "node:assert/strict";
import test from "node:test";

import { createInitialChatState, reduceUiEvent, startUserTurn } from "./chatSession.js";
import { wireToUiEvent } from "./wireAdapter.js";

const empty = createInitialChatState();

test("wireToUiEvent maps session.cancelled", () => {
  const evt = wireToUiEvent({
    type: "session.cancelled",
    conversation_id: "gw-1",
    request_id: "req-stop",
    reason: "user_stop",
    message: "Stopped.",
  });
  assert.equal(evt?.type, "session.cancelled");
  assert.equal(evt?.requestId, "req-stop");
  assert.equal(evt?.conversationId, "gw-1");
  assert.equal(evt?.reason, "user_stop");
  assert.equal(evt?.message, "Stopped.");
});

test("wireToUiEvent maps approval.resolved hitl_resolution_source gateway_user_stop only", () => {
  const ok = wireToUiEvent({
    type: "approval.resolved",
    conversation_id: "gw-1",
    request_id: "req-h",
    pending_id: "pid-1",
    outcome: "cancelled",
    hitl_resolution_source: "gateway_user_stop",
  });
  assert.equal(ok?.type, "approval.resolved");
  assert.equal(ok?.hitlResolutionSource, "gateway_user_stop");
  const dropUnknown = wireToUiEvent({
    type: "approval.resolved",
    conversation_id: "gw-1",
    request_id: "req-h",
    pending_id: "pid-1",
    outcome: "cancelled",
    hitl_resolution_source: "approval_card",
  });
  assert.equal(dropUnknown?.hitlResolutionSource, undefined);
});

test("wireToUiEvent drops session.cancelled without conversation_id or request_id", () => {
  assert.equal(
    wireToUiEvent({ type: "session.cancelled", conversation_id: "", request_id: "r" }),
    null
  );
  assert.equal(
    wireToUiEvent({ type: "session.cancelled", conversation_id: "c", request_id: "" }),
    null
  );
});

test("reduceUiEvent session.cancelled clears busy without global error", () => {
  let s = startUserTurn(empty, "hi", "req-a");
  assert.equal(s.busy, true);
  s = reduceUiEvent(s, {
    type: "session.cancelled",
    requestId: "req-a",
    conversationId: "gw",
    reason: "user_stop",
  });
  assert.equal(s.busy, false);
  assert.equal(s.activeRequestId, null);
  assert.equal(s.error, null);
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.turnCancelled, true);
  assert.equal(row.activity, null);
  assert.equal(typeof row.completedAt, "string");
  assert.ok(row.completedAt.length > 0);
});

test("reduceUiEvent ignores late approval.required after session.cancelled (tombstone)", () => {
  let s = startUserTurn(empty, "hi", "req-ar");
  s = reduceUiEvent(s, {
    type: "approval.required",
    requestId: "req-ar",
    conversationId: "gw",
    pendingId: "p1",
    expiresAt: "",
    toolName: "t",
    summary: { title: "x", lines: [] },
    actions: ["cancel"],
  });
  assert.ok(s.approvalGate);
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-ar", conversationId: "gw" });
  assert.equal(s.approvalGate, null);
  assert.equal(s.cancelledRequestIds.has("req-ar"), true);
  s = reduceUiEvent(s, {
    type: "approval.required",
    requestId: "req-ar",
    conversationId: "gw",
    pendingId: "p2",
    expiresAt: "",
    toolName: "t2",
    summary: { title: "replay", lines: [] },
    actions: ["cancel"],
  });
  assert.equal(s.approvalGate, null);
});

test("reduceUiEvent ignores late approval.required after cancel without prior gate", () => {
  let s = startUserTurn(empty, "hi", "req-ar2");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-ar2", conversationId: "gw" });
  assert.equal(s.approvalGate, null);
  s = reduceUiEvent(s, {
    type: "approval.required",
    requestId: "req-ar2",
    conversationId: "gw",
    pendingId: "p9",
    expiresAt: "",
    toolName: "t",
    summary: { title: "late gate", lines: [] },
    actions: ["cancel"],
  });
  assert.equal(s.approvalGate, null);
});

test("reduceUiEvent busy stays until session.cancelled after approval.resolved", () => {
  let s = startUserTurn(empty, "hi", "req-hitl");
  s = reduceUiEvent(s, {
    type: "approval.required",
    requestId: "req-hitl",
    conversationId: "gw",
    pendingId: "p1",
    expiresAt: "",
    toolName: "t",
    summary: { title: "x", lines: [] },
    actions: ["cancel"],
  });
  assert.equal(s.busy, true);
  s = reduceUiEvent(s, {
    type: "approval.resolved",
    requestId: "req-hitl",
    conversationId: "gw",
    pendingId: "p1",
    outcome: "cancelled",
  });
  assert.equal(s.busy, true);
  s = reduceUiEvent(s, {
    type: "session.cancelled",
    requestId: "req-hitl",
    conversationId: "gw",
  });
  assert.equal(s.busy, false);
  assert.equal(s.approvalGate, null);
});

test("reduceUiEvent ignores late assistant.append after session.cancelled (tombstone)", () => {
  let s = startUserTurn(empty, "hi", "req-late");
  s = reduceUiEvent(s, {
    type: "session.cancelled",
    requestId: "req-late",
    conversationId: "gw",
  });
  assert.equal(s.cancelledRequestIds.has("req-late"), true);
  const mdBefore = s.rows[1].kind === "assistant" ? s.rows[1].markdown : "";
  s = reduceUiEvent(s, { type: "assistant.append", requestId: "req-late", text: "late" });
  assert.equal(s.rows[1].kind === "assistant" ? s.rows[1].markdown : "", mdBefore);
});

test("reduceUiEvent ignores late assistant.activity after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-act");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-act", conversationId: "gw" });
  s = reduceUiEvent(s, { type: "assistant.activity", requestId: "req-act", text: "Still working" });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.activity, null);
});

test("reduceUiEvent ignores late assistant.chart after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-ch");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-ch", conversationId: "gw" });
  const chart = {
    kind: "pie",
    title: "",
    series: [{ name: "n", x: ["a"], y: [1] }],
  };
  s = reduceUiEvent(s, { type: "assistant.chart", requestId: "req-ch", chart });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.charts.length, 0);
});

test("reduceUiEvent ignores late assistant.rateControlStatus waiting after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-rc");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-rc", conversationId: "c" });
  s = reduceUiEvent(
    s,
    /** @type {import("./types.js").UiEvent} */ (
      wireToUiEvent({
        type: "rate_control.status",
        conversation_id: "c",
        request_id: "req-rc",
        status: "waiting",
        reason: "tokens_per_minute",
      })
    )
  );
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.notEqual(row.rateControlWaiting, true);
});

test("reduceUiEvent ignores late assistant.replace after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-rep");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-rep", conversationId: "gw" });
  s = reduceUiEvent(s, { type: "assistant.replace", requestId: "req-rep", markdown: "replaced" });
  assert.equal(s.rows[1].kind === "assistant" ? s.rows[1].markdown : "", "");
});

test("reduceUiEvent session.cancelled tombstones requestId when assistant row missing", () => {
  let s = {
    ...createInitialChatState(),
    busy: true,
    activeRequestId: "req-orphan",
    rows: [{ kind: "user", text: "solo" }],
  };
  s = reduceUiEvent(s, {
    type: "session.cancelled",
    requestId: "req-orphan",
    conversationId: "gw",
  });
  assert.equal(s.busy, false);
  assert.equal(s.cancelledRequestIds.has("req-orphan"), true);
});

test("reduceUiEvent ignores late session.error after session.cancelled (tombstone)", () => {
  let s = startUserTurn(empty, "hi", "req-err");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-err", conversationId: "gw" });
  assert.equal(s.error, null);
  s = reduceUiEvent(s, { type: "session.error", requestId: "req-err", message: "stray" });
  assert.equal(s.error, null);
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.turnCancelled, true);
});

test("reduceUiEvent ignores late session.superseded after session.cancelled (tombstone)", () => {
  let s = startUserTurn(empty, "hi", "req-super");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-super", conversationId: "gw" });
  assert.equal(s.error, null);
  s = reduceUiEvent(s, { type: "session.superseded", requestId: "req-super", message: "newer" });
  assert.equal(s.error, null);
});

test("reduceUiEvent ignores late session.done after session.cancelled (tombstone)", () => {
  let s = startUserTurn(empty, "hi", "req-done");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-done", conversationId: "gw" });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.turnCancelled, true);
  assert.equal(typeof row.completedAt, "string");
  const cancelledAt = row.completedAt;
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-done",
    completedAt: "2020-01-01T00:00:00Z",
  });
  const row2 = s.rows[1];
  assert.equal(row2.kind, "assistant");
  assert.equal(row2.completedAt, cancelledAt);
});

test("reduceUiEvent session.cancel_unsupported_local clears busy without tombstone; late session.done applies", () => {
  let s = startUserTurn(empty, "hi", "req-us");
  assert.equal(s.busy, true);
  s = reduceUiEvent(s, {
    type: "session.cancel_unsupported_local",
    requestId: "req-us",
    conversationId: "gw",
  });
  assert.equal(s.busy, false);
  assert.equal(s.activeRequestId, null);
  assert.equal(s.cancelledRequestIds.has("req-us"), false);
  assert.equal(s.unsupportedLocalRequestIds.has("req-us"), true);
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.turnCancelled, undefined);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-us",
    assistantMessageId: "aid-1",
    completedAt: "2020-01-02T00:00:00Z",
  });
  assert.equal(s.busy, false);
  const row2 = s.rows[1];
  assert.equal(row2.kind, "assistant");
  assert.equal(row2.assistantMessageId, "aid-1");
  assert.equal(row2.completedAt, "2020-01-02T00:00:00Z");
  assert.equal(s.unsupportedLocalRequestIds.has("req-us"), false);
});

test("reduceUiEvent late session.done after session.error does not merge metadata", () => {
  let s = startUserTurn(empty, "hi", "req-err-late");
  s = reduceUiEvent(s, {
    type: "session.error",
    requestId: "req-err-late",
    message: "boom",
  });
  assert.equal(s.busy, false);
  assert.equal(s.error, "boom");
  assert.equal(s.unsupportedLocalRequestIds.has("req-err-late"), false);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-err-late",
    assistantMessageId: "aid-stale",
    completedAt: "2020-01-01T00:00:00Z",
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.assistantMessageId, undefined);
  assert.equal(row.completedAt, undefined);
});

test("reduceUiEvent late session.done after session.superseded does not merge metadata", () => {
  let s = startUserTurn(empty, "hi", "req-sup-late");
  s = reduceUiEvent(s, {
    type: "session.superseded",
    requestId: "req-sup-late",
    conversationId: "gw",
    message: "newer session",
  });
  assert.equal(s.busy, false);
  assert.equal(s.unsupportedLocalRequestIds.has("req-sup-late"), false);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-sup-late",
    assistantMessageId: "aid-stale",
    completedAt: "2020-01-03T00:00:00Z",
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.assistantMessageId, undefined);
});

test("reduceUiEvent second late session.done after cancel_unsupported_local is ignored", () => {
  let s = startUserTurn(empty, "hi", "req-2x");
  s = reduceUiEvent(s, {
    type: "session.cancel_unsupported_local",
    requestId: "req-2x",
    conversationId: "gw",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-2x"), true);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-2x",
    assistantMessageId: "aid-first",
    completedAt: "2020-01-01T00:00:00Z",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-2x"), false);
  const row = s.rows[1];
  assert.equal(row.assistantMessageId, "aid-first");
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-2x",
    assistantMessageId: "aid-second",
    completedAt: "2020-01-02T00:00:00Z",
  });
  const row2 = s.rows[1];
  assert.equal(row2.assistantMessageId, "aid-first");
  assert.equal(row2.completedAt, "2020-01-01T00:00:00Z");
});

test("reduceUiEvent unsupported_local then session.error strips marker; late session.done ignored", () => {
  let s = startUserTurn(empty, "hi", "req-ued");
  s = reduceUiEvent(s, {
    type: "session.cancel_unsupported_local",
    requestId: "req-ued",
    conversationId: "gw",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-ued"), true);
  s = reduceUiEvent(s, {
    type: "session.error",
    requestId: "req-ued",
    message: "server failed",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-ued"), false);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-ued",
    assistantMessageId: "aid-stale",
    completedAt: "2020-01-01T00:00:00Z",
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.assistantMessageId, undefined);
});

test("reduceUiEvent unsupported_local then session.superseded strips marker; late session.done ignored", () => {
  let s = startUserTurn(empty, "hi", "req-usup");
  s = reduceUiEvent(s, {
    type: "session.cancel_unsupported_local",
    requestId: "req-usup",
    conversationId: "gw",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-usup"), true);
  s = reduceUiEvent(s, {
    type: "session.superseded",
    requestId: "req-usup",
    conversationId: "gw",
    message: "replaced",
  });
  assert.equal(s.unsupportedLocalRequestIds.has("req-usup"), false);
  s = reduceUiEvent(s, {
    type: "session.done",
    requestId: "req-usup",
    assistantMessageId: "aid-stale",
    completedAt: "2020-01-02T00:00:00Z",
  });
  const row = s.rows[1];
  assert.equal(row.assistantMessageId, undefined);
});

test("reduceUiEvent ignores late assistant.table after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-tbl");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-tbl", conversationId: "gw" });
  s = reduceUiEvent(s, {
    type: "assistant.table",
    requestId: "req-tbl",
    table: {
      kind: "entity-list",
      columns: [{ key: "a", label: "A", baseType: "STRING" }],
      rows: [],
      shownRows: 0,
      totalRows: 0,
      cacheId: "t1",
      sourceCacheId: null,
      exportStatus: "none",
      exportMessage: null,
      exportFile: null,
      exportRepository: null,
      exportDownloadUrl: null,
    },
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.artifacts?.length ?? 0, 0);
});

test("reduceUiEvent ignores late assistant.insightEnvelope after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-env");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-env", conversationId: "gw" });
  s = reduceUiEvent(s, {
    type: "assistant.insightEnvelope",
    requestId: "req-env",
    toolSuccessPayload: { insightEnvelope: { schemaVersion: "1" } },
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.insightEnvelopeLoose, undefined);
});

test("reduceUiEvent ignores late assistant.taskState after session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "req-ts");
  s = reduceUiEvent(s, { type: "session.cancelled", requestId: "req-ts", conversationId: "gw" });
  s = reduceUiEvent(s, {
    type: "assistant.taskState",
    requestId: "req-ts",
    snapshot: {
      schemaVersion: 1,
      status: "executing",
      title: "t",
      summary: { total: 1, satisfied: 0, inProgress: 1, failed: 0, blocked: 0 },
      items: [],
    },
  });
  const row = s.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.taskState, undefined);
});
