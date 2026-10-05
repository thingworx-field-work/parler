import assert from "node:assert/strict";
import test from "node:test";

import {
  activeTurnIndicatorText,
  DEFAULT_PROGRESS_LABEL,
  DEFAULT_RATE_CONTROL_LABEL,
  normalizeProgressLabel,
  normalizeProgressPresentation,
} from "./activeTurnIndicator.mjs";

const assistant = (overrides = {}) => ({
  kind: "assistant",
  requestId: "req-a",
  markdown: "",
  charts: [],
  tables: [],
  ...overrides,
});

const activeState = (row) => ({
  rows: [{ kind: "user", text: "hi" }, row],
  busy: true,
  error: null,
  activeRequestId: "req-a",
  approvalGate: null,
});

test("shows default text for active assistant turn with only intermediate state", () => {
  const row = assistant({ taskState: { status: "executing", items: [] } });
  assert.equal(activeTurnIndicatorText(activeState(row), row, 1), "Working");
});

test("stays visible after chart or table appears before final markdown", () => {
  const row = assistant({
    charts: [{ kind: "line", title: "T", series: [] }],
    tables: [{ kind: "entity-list", columns: [], rows: [] }],
  });
  assert.equal(activeTurnIndicatorText(activeState(row), row, 1), "Working");
});

test("uses latest activity text when present", () => {
  const row = assistant({ activity: "Calling tools" });
  assert.equal(activeTurnIndicatorText(activeState(row), row, 1), "Calling tools");
});

test("normalizes progress mode and labels", () => {
  assert.equal(normalizeProgressPresentation("compact"), "compact");
  for (const value of [undefined, null, "", "COMPACT", "detailed", "other"]) {
    assert.equal(normalizeProgressPresentation(value), "detailed");
  }
  assert.equal(normalizeProgressLabel("  Custom  ", "Fallback"), "Custom");
  assert.equal(normalizeProgressLabel("  ", "Fallback"), "Fallback");
});

test("compact mode replaces routine activity and preserves rate-control meaning", () => {
  const ordinary = assistant({ activity: "Calling tools" });
  assert.equal(
    activeTurnIndicatorText(activeState(ordinary), ordinary, 1, {
      presentation: "compact",
      progressLabel: "  Please wait  ",
      rateControlLabel: "Capacity pause",
    }),
    "Please wait"
  );

  const rateControlled = assistant({
    activity: "Retrying in 12 seconds",
    rateControlWaiting: true,
  });
  assert.equal(
    activeTurnIndicatorText(activeState(rateControlled), rateControlled, 1, {
      presentation: "compact",
      progressLabel: "",
      rateControlLabel: "   ",
    }),
    DEFAULT_RATE_CONTROL_LABEL
  );
  assert.equal(DEFAULT_PROGRESS_LABEL, "Thinking...");
});

test("detailed mode retains activity and its existing fallback", () => {
  const withActivity = assistant({ activity: "Calling tools" });
  assert.equal(
    activeTurnIndicatorText(activeState(withActivity), withActivity, 1, {
      presentation: "detailed",
      progressLabel: "Ignored",
    }),
    "Calling tools"
  );
  const withoutActivity = assistant();
  assert.equal(
    activeTurnIndicatorText(activeState(withoutActivity), withoutActivity, 1, {
      presentation: "unknown",
      detailedFallback: "Working",
    }),
    "Working"
  );
});

test("stays visible while final markdown streams and the turn remains busy", () => {
  const row = assistant({ markdown: "Final answer" });
  assert.equal(activeTurnIndicatorText(activeState(row), row, 1), "Working");
});

test("hides when turn is not active or not last row", () => {
  const row = assistant();
  assert.equal(
    activeTurnIndicatorText({ ...activeState(row), busy: false }, row, 1),
    null
  );
  assert.equal(
    activeTurnIndicatorText(
      { ...activeState(row), rows: [row, { kind: "user", text: "next" }] },
      row,
      0
    ),
    null
  );
});
