import test from "node:test";
import assert from "node:assert/strict";
import {
  shouldRenderTaskStatePanelForRow,
  taskStateRequiresAttention,
  taskStateSeverity,
  validTaskState,
} from "./taskStatePanelVisibility.mjs";

const assistant = (taskState) => ({
  kind: /** @type {const} */ ("assistant"),
  requestId: "r1",
  markdown: "",
  charts: [],
  tables: [],
  taskState,
});

test("user row → false", () => {
  assert.equal(
    shouldRenderTaskStatePanelForRow(
      { kind: "user", text: "hi" },
      true
    ),
    false
  );
});

test("no taskState → false", () => {
  assert.equal(
    shouldRenderTaskStatePanelForRow(
      { kind: "assistant", taskState: null },
      true
    ),
    false
  );
});

test("empty status → false even when active", () => {
  assert.equal(
    shouldRenderTaskStatePanelForRow(
      assistant({ status: "   ", summary: {}, items: [] }),
      true
    ),
    false
  );
});

test("validity requires an object with a non-empty status", () => {
  assert.equal(validTaskState({ status: "executing" }), true);
  assert.equal(validTaskState({ status: "  " }), false);
  assert.equal(validTaskState({ status: 3 }), false);
  assert.equal(validTaskState(null), false);
});

test("active turn: completed + ad-hoc-only snapshot → true", () => {
  const row = assistant({
    status: "completed",
    summary: { satisfied: 0, total: 0, inProgress: 0, failed: 0, blocked: 0 },
    items: [
      {
        source: "tool",
        label: "invoke_service",
        status: "satisfied",
      },
    ],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, true), true);
});

test("inactive: completed + satisfied checklist + ad-hoc → false", () => {
  const row = assistant({
    status: "completed",
    summary: { satisfied: 2, total: 2, failed: 0, blocked: 0 },
    items: [{ source: "skill", status: "satisfied" }],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), false);
});

test("inactive: completed + ad-hoc only + zeros → false", () => {
  const row = assistant({
    status: "completed",
    summary: { satisfied: 0, total: 0 },
    items: [{ source: "tool", status: "satisfied", label: "t1" }],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), false);
});

test("inactive: stale executing → false (do not use status !== completed alone)", () => {
  const row = assistant({
    status: "executing",
    summary: {},
    items: [],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), false);
});

test("inactive: failed status → true", () => {
  const row = assistant({ status: "failed", summary: {}, items: [] });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), true);
});

test("inactive: blocked-by-approval → true", () => {
  const row = assistant({
    status: "blocked-by-approval",
    summary: {},
    items: [],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), true);
});

test("inactive: legacy invalid root statuses do not require attention", () => {
  for (const status of ["expired", "cancelled", "rejected"]) {
    assert.equal(
      shouldRenderTaskStatePanelForRow(
        assistant({ status, summary: {}, items: [] }),
        false
      ),
      false
    );
  }
});

test("inactive: actionable item statuses require attention", () => {
  for (const status of [
    "failed",
    "blocked-by-approval",
    "cancelled",
    "expired",
  ]) {
    const row = assistant({
      status: "completed",
      summary: {},
      items: [{ status }],
    });
    assert.equal(taskStateRequiresAttention(row.taskState), true, status);
    assert.equal(shouldRenderTaskStatePanelForRow(row, false), true, status);
  }
});

test("inactive: completed but summary.failed > 0 → true", () => {
  const row = assistant({
    status: "completed",
    summary: { satisfied: 1, total: 2, failed: 1, blocked: 0 },
    items: [],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), true);
});

test("inactive: completed but summary.blocked > 0 → true", () => {
  const row = assistant({
    status: "completed",
    summary: { failed: 0, blocked: 1 },
    items: [],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(row, false), true);
});

test("compact mode suppresses routine active detail but preserves attention", () => {
  const routine = assistant({
    status: "executing",
    summary: { failed: 0, blocked: 0 },
    items: [{ status: "in-progress" }],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(routine, true, "detailed"), true);
  assert.equal(shouldRenderTaskStatePanelForRow(routine, true, "compact"), false);

  const attention = assistant({
    status: "executing",
    summary: {},
    items: [{ status: "cancelled" }],
  });
  assert.equal(shouldRenderTaskStatePanelForRow(attention, true, "compact"), true);
});

test("projection and classification do not mutate retained task state", () => {
  const row = assistant({
    status: "executing",
    title: "Retained",
    summary: { failed: 0, blocked: 0 },
    items: [{ status: "in-progress", label: "Step" }],
  });
  const before = JSON.stringify(row.taskState);
  shouldRenderTaskStatePanelForRow(row, true, "compact");
  taskStateRequiresAttention(row.taskState);
  taskStateSeverity(row.taskState);
  assert.equal(JSON.stringify(row.taskState), before);
});

test("severity uses danger > warning > success > info precedence", () => {
  assert.equal(taskStateSeverity({ status: "executing", summary: {}, items: [] }), "info");
  assert.equal(taskStateSeverity({ status: "completed", summary: {}, items: [] }), "success");
  assert.equal(
    taskStateSeverity({
      status: "completed",
      summary: {},
      items: [{ status: "blocked-by-approval" }],
    }),
    "warning"
  );
  assert.equal(
    taskStateSeverity({
      status: "blocked-by-approval",
      summary: {},
      items: [{ status: "failed" }],
    }),
    "danger"
  );
});
