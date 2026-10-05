/**
 * v1b task.state wire → reducer — see docs/agent/task-state.md § v1b Test Matrix (subset).
 */
import assert from "node:assert/strict";
import test from "node:test";

import {
  initialChatState,
  reduceUiEvent,
  startUserTurn,
} from "./chatSession.js";
import { shouldDropWireTypeDuringHistoryLoad } from "./historyBootstrap.js";
import { wireToUiEvent } from "./wireAdapter.js";

function sampleTaskStateWire(requestId, conversationId) {
  return {
    type: "task.state",
    schemaVersion: 1,
    request_id: requestId,
    conversation_id: conversationId,
    status: "executing",
    title: "Demo checklist",
    summary: {
      total: 2,
      satisfied: 0,
      inProgress: 1,
      failed: 0,
      blocked: 0,
    },
    items: [
      {
        id: "x",
        source: "skill",
        kind: "evidence",
        label: "Step one",
        status: "pending",
      },
    ],
  };
}

test("wireToUiEvent maps playbook source task.state to assistant.taskState", () => {
  const wire = {
    ...sampleTaskStateWire("req-pb", "conv-pb"),
    summary: {
      playbookId: "cross_region_health",
      total: 1,
      satisfied: 0,
      inProgress: 1,
      failed: 0,
      blocked: 0,
    },
    items: [
      {
        id: "taxonomy_row",
        source: "playbook",
        kind: "evidence",
        label: "Resolve asset type",
        status: "in-progress",
      },
    ],
  };
  const evt = wireToUiEvent(wire);
  assert.ok(evt);
  assert.equal(evt.snapshot.items[0].source, "playbook");
});

test("wireToUiEvent maps task.state to assistant.taskState", () => {
  const evt = wireToUiEvent(sampleTaskStateWire("req-a", "conv-b"));
  assert.ok(evt);
  assert.equal(evt.type, "assistant.taskState");
  assert.equal(evt.requestId, "req-a");
  assert.equal(evt.snapshot.schemaVersion, 1);
  assert.equal(evt.snapshot.status, "executing");
  assert.equal(evt.snapshot.items.length, 1);
});

test("reducer attaches snapshot to matching assistant row", () => {
  let s = startUserTurn(initialChatState, "hello", "req-a");
  const evt = wireToUiEvent(sampleTaskStateWire("req-a", "gw"));
  assert.ok(evt);
  s = reduceUiEvent(s, evt);
  const row = s.rows[s.rows.length - 1];
  assert.equal(row.kind, "assistant");
  assert.ok(row.taskState);
  assert.equal(row.taskState.title, "Demo checklist");
});

test("session.done clears busy but retains taskState on assistant row", () => {
  let s = startUserTurn(initialChatState, "hello", "req-a");
  const ts = wireToUiEvent(sampleTaskStateWire("req-a", "gw"));
  assert.ok(ts);
  s = reduceUiEvent(s, ts);
  s = reduceUiEvent(s, { type: "session.done", requestId: "req-a" });
  assert.equal(s.busy, false);
  const row = s.rows[s.rows.length - 1];
  assert.ok(row.kind === "assistant" && row.taskState);
});

test("done wire llm_usage maps through wireToUiEvent onto active assistant row", () => {
  let s = startUserTurn(initialChatState, "hello", "req-a");
  const doneWire = {
    type: "done",
    request_id: "req-a",
    conversation_id: "gw",
    assistant_message_id: "amid-1",
    llm_usage: {
      inputTokens: 100,
      outputTokens: 50,
      apiShapeId: "openai-v5",
    },
  };
  const evt = wireToUiEvent(doneWire);
  assert.ok(evt);
  assert.equal(evt.type, "session.done");
  assert.equal(evt.requestId, "req-a");
  assert.deepEqual(evt.llmUsage, {
    inputTokens: 100,
    outputTokens: 50,
    apiShapeId: "openai-v5",
  });
  s = reduceUiEvent(s, evt);
  const row = s.rows[s.rows.length - 1];
  assert.equal(row.kind, "assistant");
  assert.deepEqual(row.llmUsage, {
    inputTokens: 100,
    outputTokens: 50,
    apiShapeId: "openai-v5",
  });
  assert.equal(row.assistantMessageId, "amid-1");
  assert.equal(s.busy, false);
});

test("history loading drops task.state wire type", () => {
  assert.ok(shouldDropWireTypeDuringHistoryLoad("task.state"));
});

test("reducer replaces taskState snapshot on second frame for same requestId", () => {
  let s = startUserTurn(initialChatState, "hi", "req-r1");
  const first = sampleTaskStateWire("req-r1", "gw");
  const second = {
    ...sampleTaskStateWire("req-r1", "gw"),
    title: "Updated",
    summary: { total: 1, satisfied: 1, inProgress: 0, failed: 0, blocked: 0 },
  };
  const e1 = wireToUiEvent(first);
  const e2 = wireToUiEvent(second);
  assert.ok(e1 && e2);
  s = reduceUiEvent(s, /** @type {import('./types.js').UiEvent} */ (e1));
  s = reduceUiEvent(s, /** @type {import('./types.js').UiEvent} */ (e2));
  const row = s.rows[s.rows.length - 1];
  assert.equal(row.kind, "assistant");
  assert.equal(row.taskState?.title, "Updated");
  assert.equal(row.taskState?.summary?.satisfied, 1);
});
