import assert from "node:assert/strict";
import test from "node:test";

import { createInitialChatState, reduceUiEvent, startUserTurn } from "./chatSession.js";
import { wireToUiEvent } from "./wireAdapter.js";

const empty = createInitialChatState();

test("wireToUiEvent maps rate_control.status waiting and resumed", () => {
  const w = wireToUiEvent({
    type: "rate_control.status",
    conversation_id: "c1",
    request_id: "r1",
    status: "waiting",
    reason: "tokens_per_minute",
    wait_ms: 100,
    retry_after_ms: 100,
  });
  assert.equal(w?.type, "assistant.rateControlStatus");
  assert.equal(w?.waiting, true);
  assert.equal(w?.requestId, "r1");
  assert.equal(w?.reason, "tokens_per_minute");
  assert.equal(w?.waitMs, 100);

  const r = wireToUiEvent({
    type: "rate_control.status",
    conversation_id: "c1",
    request_id: "r1",
    status: "resumed",
  });
  assert.equal(r?.type, "assistant.rateControlStatus");
  assert.equal(r?.waiting, false);
});

test("reduceUiEvent applies rateControlWaiting only for active requestId", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  const stale = wireToUiEvent({
    type: "rate_control.status",
    conversation_id: "c",
    request_id: "r-other",
    status: "waiting",
  });
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (stale));
  const row = s.rows[s.rows.length - 1];
  assert.equal(row.kind === "assistant" ? row.rateControlWaiting : null, undefined);

  const ok = wireToUiEvent({
    type: "rate_control.status",
    conversation_id: "c",
    request_id: "r-active",
    status: "waiting",
  });
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (ok));
  const row2 = s.rows[s.rows.length - 1];
  assert.equal(row2.kind === "assistant" && row2.rateControlWaiting, true);

  const resumed = wireToUiEvent({
    type: "rate_control.status",
    conversation_id: "c",
    request_id: "r-active",
    status: "resumed",
  });
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (resumed));
  const row3 = s.rows[s.rows.length - 1];
  assert.equal(row3.kind === "assistant" && row3.rateControlWaiting, false);
});

test("wireToUiEvent drops rate_control.status without conversation_id", () => {
  assert.equal(
    wireToUiEvent({
      type: "rate_control.status",
      request_id: "r1",
      status: "waiting",
    }),
    null
  );
});

test("reduceUiEvent clears rateControlWaiting on non-whitespace assistant.append", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (
    wireToUiEvent({
      type: "rate_control.status",
      conversation_id: "c",
      request_id: "r-active",
      status: "waiting",
    })
  ));
  let row = s.rows[s.rows.length - 1];
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, true);
  s = reduceUiEvent(s, { type: "assistant.append", requestId: "r-active", text: "x" });
  row = s.rows[s.rows.length - 1];
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, false);
});

test("reduceUiEvent clears rateControlWaiting on session.done", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (
    wireToUiEvent({
      type: "rate_control.status",
      conversation_id: "c",
      request_id: "r-active",
      status: "waiting",
    })
  ));
  s = reduceUiEvent(s, { type: "session.done", requestId: "r-active" });
  const row = s.rows[s.rows.length - 1];
  assert.equal(s.busy, false);
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, false);
});

test("reduceUiEvent clears rateControlWaiting on session.error", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (
    wireToUiEvent({
      type: "rate_control.status",
      conversation_id: "c",
      request_id: "r-active",
      status: "waiting",
    })
  ));
  s = reduceUiEvent(s, { type: "session.error", requestId: "r-active", message: "boom" });
  const row = s.rows[s.rows.length - 1];
  assert.equal(s.busy, false);
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, false);
});

test("reduceUiEvent clears rateControlWaiting on session.superseded", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (
    wireToUiEvent({
      type: "rate_control.status",
      conversation_id: "c",
      request_id: "r-active",
      status: "waiting",
    })
  ));
  s = reduceUiEvent(s, { type: "session.superseded", requestId: "r-active", message: "newer" });
  const row = s.rows[s.rows.length - 1];
  assert.equal(s.busy, false);
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, false);
});

test("reduceUiEvent clears rateControlWaiting on session.cancelled", () => {
  let s = startUserTurn(empty, "hi", "r-active");
  s = reduceUiEvent(s, /** @type {import("./types.js").UiEvent} */ (
    wireToUiEvent({
      type: "rate_control.status",
      conversation_id: "c",
      request_id: "r-active",
      status: "waiting",
    })
  ));
  s = reduceUiEvent(s, {
    type: "session.cancelled",
    requestId: "r-active",
    conversationId: "c",
  });
  const row = s.rows[s.rows.length - 1];
  assert.equal(s.busy, false);
  assert.equal(row.kind === "assistant" && row.rateControlWaiting, false);
});
