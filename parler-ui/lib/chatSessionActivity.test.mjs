import assert from "node:assert/strict";
import test from "node:test";

import { reduceUiEvent, startUserTurn } from "./chatSession.js";

test("assistant.replace clears stale activity when final markdown is non-empty", () => {
  let state = startUserTurn(
    {
      rows: [],
      busy: false,
      error: null,
      activeRequestId: null,
      approvalGate: null,
    },
    "diagnose",
    "req-a"
  );
  state = reduceUiEvent(state, {
    type: "assistant.activity",
    requestId: "req-a",
    text: "Calling tools",
  });
  state = reduceUiEvent(state, {
    type: "assistant.replace",
    requestId: "req-a",
    markdown: "Final answer",
  });

  const row = state.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.activity, null);
  assert.equal(row.markdown, "Final answer");
});

test("assistant.replace preserves activity for empty markdown replacement", () => {
  let state = startUserTurn(
    {
      rows: [],
      busy: false,
      error: null,
      activeRequestId: null,
      approvalGate: null,
    },
    "diagnose",
    "req-a"
  );
  state = reduceUiEvent(state, {
    type: "assistant.activity",
    requestId: "req-a",
    text: "Calling tools",
  });
  state = reduceUiEvent(state, {
    type: "assistant.replace",
    requestId: "req-a",
    markdown: "   ",
  });

  const row = state.rows[1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.activity, "Calling tools");
});
