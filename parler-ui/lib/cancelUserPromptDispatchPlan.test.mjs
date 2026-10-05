import assert from "node:assert/strict";
import test from "node:test";

import { planCancelUserPromptDispatch } from "./cancelUserPromptDispatchPlan.js";

test("plan: accepted schedules fallback", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "accepted", alreadyRequested: false },
    "initial",
    false
  );
  assert.equal(p.kind, "schedule_fallback");
});

test("plan: not_active initial with no retry yet schedules retry", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "not_active", alreadyRequested: false },
    "initial",
    false
  );
  assert.equal(p.kind, "schedule_retry");
});

test("plan: not_active after retry consumed schedules fallback", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "not_active", alreadyRequested: false },
    "retry",
    true
  );
  assert.equal(p.kind, "schedule_fallback");
});

test("plan: not_active initial when retry already used schedules fallback", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "not_active", alreadyRequested: false },
    "initial",
    true
  );
  assert.equal(p.kind, "schedule_fallback");
});

test("plan: unparseable clears stopping transient", () => {
  const p = planCancelUserPromptDispatch(
    { ok: false, reason: "parse" },
    "initial",
    false
  );
  assert.equal(p.kind, "clear_stopping_transient");
  assert.ok(p.message);
});

test("plan: unknown status clears stopping transient", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "weird", alreadyRequested: false },
    "initial",
    false
  );
  assert.equal(p.kind, "clear_stopping_transient");
});

test("plan: unsupported uses local end state without tombstone path", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "unsupported", alreadyRequested: false },
    "retry",
    true
  );
  assert.equal(p.kind, "apply_unsupported_local");
  assert.ok(p.message);
});

test("plan: already_terminal", () => {
  const p = planCancelUserPromptDispatch(
    { ok: true, status: "already_terminal", alreadyRequested: false },
    "initial",
    false
  );
  assert.equal(p.kind, "apply_already_terminal");
});
