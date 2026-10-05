import assert from "node:assert/strict";
import test from "node:test";

import {
  APPROVAL_SUBMIT_MSG_ACCEPTED,
  APPROVAL_SUBMIT_MSG_SUBMITTING,
  APPROVAL_SUBMIT_MSG_UNCONFIRMED,
  approvalSubmitStatusText,
  isApprovalDecisionPending,
  submitApprovalDecision,
} from "./approvalDecisionSubmit.js";
import { createInitialChatState, reduceUiEvent, startUserTurn } from "./chatSession.js";

const PENDING = "pid-1";
const REQ = "req-1";
const CONV = "gw-1";

/** Opens a gate on a busy turn, the way a live approval arrives mid-stream. */
function gatedState(pendingId = PENDING, requestId = REQ) {
  const turn = startUserTurn(createInitialChatState(), "run it", requestId);
  return reduceUiEvent(turn, {
    type: "approval.required",
    requestId,
    conversationId: CONV,
    pendingId,
    expiresAt: "2026-09-13T17:00:00Z",
    toolName: "invoke_service",
    summary: { title: "Confirm action", lines: [{ label: "Service", value: "DoIt" }] },
    actions: ["approve", "cancel", "reject_with_comment"],
  });
}

/**
 * Drives the production submit path with a fake AlwaysOn client and a live reducer, so uplink
 * count and state transitions are observed together rather than asserted by hand-dispatching.
 */
function harness(initial = gatedState()) {
  const calls = [];
  /** @type {((err: unknown) => void)[]} */
  const pendingCallbacks = [];
  const h = {
    state: initial,
    calls,
    client: {
      invokeService(req, cb) {
        calls.push(req);
        pendingCallbacks.push(cb);
      },
    },
    /** @param {'approve'|'cancel'|'reject_with_comment'} decision */
    submit(decision = "approve", comment = "") {
      return submitApprovalDecision({
        gate: h.state.approvalGate,
        client: h.client,
        conversationId: CONV,
        connected: true,
        decision,
        comment,
        dispatch: (evt) => {
          h.state = reduceUiEvent(h.state, evt);
        },
      });
    },
    /** Completes the oldest outstanding invoke callback. */
    settle(err = null) {
      const cb = pendingCallbacks.shift();
      assert.ok(cb, "expected an outstanding invokeService callback");
      cb(err);
    },
    apply(evt) {
      h.state = reduceUiEvent(h.state, evt);
    },
  };
  return h;
}

test("a fresh gate starts idle with buttons enabled and no status line", () => {
  const s = gatedState();
  assert.equal(s.approvalGate.submitState, "idle");
  assert.equal(s.approvalGate.submitError, null);
  assert.equal(isApprovalDecisionPending(s.approvalGate), false);
  assert.equal(approvalSubmitStatusText(s.approvalGate), null);
});

test("first submit goes submitting then submitted; buttons disabled throughout", () => {
  const h = harness();

  assert.equal(h.submit("approve"), "submitted");
  assert.equal(h.calls.length, 1);
  assert.equal(h.calls[0].serviceName, "SubmitApprovalDecision");
  assert.equal(h.state.approvalGate.submitState, "submitting");
  assert.equal(isApprovalDecisionPending(h.state.approvalGate), true);
  assert.equal(approvalSubmitStatusText(h.state.approvalGate), APPROVAL_SUBMIT_MSG_SUBMITTING);

  h.settle(null);
  assert.equal(h.state.approvalGate.submitState, "submitted");
  assert.equal(isApprovalDecisionPending(h.state.approvalGate), true);
  assert.equal(approvalSubmitStatusText(h.state.approvalGate), APPROVAL_SUBMIT_MSG_ACCEPTED);
  assert.equal(h.state.approvalGate.submitError, null);
});

test("repeat triggers during submitting or submitted produce no second uplink", () => {
  const h = harness();

  h.submit("approve");
  assert.equal(h.submit("approve"), "skipped_already_pending");
  assert.equal(h.submit("cancel"), "skipped_already_pending");
  assert.equal(h.calls.length, 1);

  h.settle(null);
  assert.equal(h.submit("approve"), "skipped_already_pending");
  assert.equal(h.submit("reject_with_comment", "no"), "skipped_already_pending");
  assert.equal(h.calls.length, 1);
});

test("submit failure surfaces on the card without ending the turn, and allows retry", () => {
  const h = harness();

  h.submit("approve");
  h.settle(new Error("socket closed"));

  const gate = h.state.approvalGate;
  assert.ok(gate, "gate must stay open — the server may already be running the decision");
  assert.equal(gate.submitState, "idle");
  assert.equal(gate.submitError, "socket closed");
  assert.equal(isApprovalDecisionPending(gate), false);
  assert.equal(h.state.busy, true);
  assert.equal(h.state.activeRequestId, REQ);
  assert.equal(h.state.error, null);

  assert.equal(h.submit("approve"), "submitted");
  assert.equal(h.calls.length, 2);
  assert.equal(h.state.approvalGate.submitState, "submitting");
  assert.equal(h.state.approvalGate.submitError, null);
});

test("a blank error message falls back to the unconfirmed wording", () => {
  const h = harness();
  h.submit("approve");
  h.settle(new Error(""));
  assert.equal(h.state.approvalGate.submitError, APPROVAL_SUBMIT_MSG_UNCONFIRMED);
});

test("retry after an ambiguous failure keeps the turn alive and the late resolve still closes the card", () => {
  const h = harness();

  // First uplink actually landed server-side; the response never came back.
  h.submit("approve");
  h.settle(new Error("timeout"));
  // Retry draws UNKNOWN_PENDING because the decision was already consumed.
  h.submit("approve");
  h.settle(new Error("Unknown or expired pending_id."));

  assert.equal(h.state.busy, true);
  assert.equal(h.state.activeRequestId, REQ);
  assert.equal(h.state.error, null);
  assert.equal(h.state.approvalGate.submitError, "Unknown or expired pending_id.");

  h.apply({
    type: "approval.resolved",
    requestId: REQ,
    conversationId: CONV,
    pendingId: PENDING,
    outcome: "approved",
    executed: true,
  });
  assert.equal(h.state.approvalGate, null);
  assert.equal(h.state.busy, true, "approval.resolved alone must not end the turn (rule 10)");
});

test("submitted persists across a delayed business operation until approval.resolved", () => {
  const h = harness();
  h.submit("approve");
  h.settle(null);

  // Stream frames keep arriving while the gated service runs for ~11s.
  h.apply({ type: "assistant.activity", requestId: REQ, text: "Running…" });
  assert.equal(h.state.approvalGate.submitState, "submitted");

  h.apply({
    type: "approval.resolved",
    requestId: REQ,
    conversationId: CONV,
    pendingId: PENDING,
    outcome: "approved",
    executed: true,
  });
  assert.equal(h.state.approvalGate, null);
});

test("approval.resolved for a different pendingId neither closes the gate nor moves the substate", () => {
  const h = harness();
  h.submit("approve");
  h.settle(null);

  h.apply({
    type: "approval.resolved",
    requestId: REQ,
    conversationId: CONV,
    pendingId: "pid-other",
    outcome: "approved",
    executed: true,
  });
  assert.ok(h.state.approvalGate);
  assert.equal(h.state.approvalGate.submitState, "submitted");
});

test("a late callback from a superseded gate cannot touch the current one", () => {
  const h = harness();
  h.submit("approve");

  // A new gate opens before the first uplink answers.
  h.apply({
    type: "approval.required",
    requestId: "req-2",
    conversationId: CONV,
    pendingId: "pid-2",
    expiresAt: "2026-09-13T17:05:00Z",
    toolName: "invoke_service",
    summary: { title: "Confirm action", lines: [] },
    actions: ["approve", "cancel"],
  });
  assert.equal(h.state.approvalGate.pendingId, "pid-2");
  assert.equal(h.state.approvalGate.submitState, "idle");

  h.settle(new Error("stale"));
  assert.equal(h.state.approvalGate.pendingId, "pid-2");
  assert.equal(h.state.approvalGate.submitState, "idle");
  assert.equal(h.state.approvalGate.submitError, null);

  // The new gate is still submittable.
  assert.equal(h.submit("approve"), "submitted");
  assert.equal(h.calls.length, 2);
});

test("cancel and reject_with_comment are gated exactly like approve", () => {
  for (const decision of ["cancel", "reject_with_comment"]) {
    const h = harness();
    assert.equal(h.submit(decision, "because"), "submitted");
    assert.equal(h.submit(decision, "because"), "skipped_already_pending");
    assert.equal(h.calls.length, 1);
    h.settle(null);
    assert.equal(h.state.approvalGate.submitState, "submitted");
    assert.equal(h.submit(decision, "because"), "skipped_already_pending");
    assert.equal(h.calls.length, 1);
  }
});

test("the accepted line never claims an approved operation is executing, for any decision", () => {
  for (const decision of ["approve", "cancel", "reject_with_comment"]) {
    const h = harness();
    h.submit(decision, "because");
    h.settle(null);
    const text = approvalSubmitStatusText(h.state.approvalGate);
    assert.equal(text, APPROVAL_SUBMIT_MSG_ACCEPTED);
    assert.doesNotMatch(text, /executing|running|approved operation/i);
  }
});

test("missing transport or gate produces no uplink and no substate change", () => {
  const s = gatedState();
  const calls = [];
  const client = { invokeService: (req) => calls.push(req) };
  let state = s;
  const dispatch = (evt) => {
    state = reduceUiEvent(state, evt);
  };

  assert.equal(
    submitApprovalDecision({
      gate: s.approvalGate,
      client,
      conversationId: CONV,
      connected: false,
      decision: "approve",
      comment: "",
      dispatch,
    }),
    "skipped_no_transport"
  );
  assert.equal(
    submitApprovalDecision({
      gate: s.approvalGate,
      client: null,
      conversationId: CONV,
      connected: true,
      decision: "approve",
      comment: "",
      dispatch,
    }),
    "skipped_no_transport"
  );
  assert.equal(
    submitApprovalDecision({
      gate: null,
      client,
      conversationId: CONV,
      connected: true,
      decision: "approve",
      comment: "",
      dispatch,
    }),
    "skipped_no_gate"
  );
  assert.equal(calls.length, 0);
  assert.equal(state.approvalGate.submitState, "idle");
});

test("session.error still ends the turn and closes the gate (rule 8 unchanged)", () => {
  const h = harness();
  h.submit("approve");
  h.apply({ type: "session.error", requestId: REQ, message: "Backend failed." });

  assert.equal(h.state.approvalGate, null);
  assert.equal(h.state.busy, false);
  assert.equal(h.state.activeRequestId, null);
  assert.equal(h.state.error, "Backend failed.");
});

test("server executed=false with an error is unchanged by the submit substate", () => {
  const h = harness();
  h.submit("approve");
  h.settle(null);

  h.apply({
    type: "approval.resolved",
    requestId: REQ,
    conversationId: CONV,
    pendingId: PENDING,
    outcome: "approved",
    executed: false,
    error: { code: "STALE_TARGET_VALUE", message: "Value changed." },
  });
  assert.equal(h.state.approvalGate, null);
  assert.equal(h.state.busy, true);
  assert.equal(h.state.error, null);
});
