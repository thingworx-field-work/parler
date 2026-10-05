import test from "node:test";
import assert from "node:assert/strict";
import { parseHistoryRows } from "./historyHydrate.js";

test("parseHistoryRows carries feedbackRating on assistant rows", () => {
  const rows = parseHistoryRows({
    format: "ai-parler-history-v1",
    rows: [
      { kind: "user", text: "Hi" },
      {
        kind: "assistant",
        assistantMessageId: "am-1",
        markdown: "Hello",
        feedbackRating: "up",
        charts: [],
        tables: [],
        activity: null,
      },
    ],
  });
  assert.equal(rows.length, 2);
  const a = rows[1];
  assert.equal(a.kind, "assistant");
  assert.equal(a.assistantMessageId, "am-1");
  assert.equal(a.feedbackRating, "up");
});

test("rejects invalid feedbackRating values", () => {
  const rows = parseHistoryRows({
    format: "ai-parler-history-v1",
    rows: [
      {
        kind: "assistant",
        assistantMessageId: "am-2",
        markdown: "x",
        feedbackRating: "maybe",
        charts: [],
        tables: [],
      },
    ],
  });
  const a = rows[0];
  assert.equal(a.kind, "assistant");
  assert.equal("feedbackRating" in a, false);
});

test("parseHistoryRows carries hostContext on user rows", () => {
  const rows = parseHistoryRows({
    format: "ai-parler-history-v1",
    rows: [
      {
        kind: "user",
        text: "how about now?",
        hostContext: {
          schema: "parler-host-context-snapshot-v1",
          accepted: true,
          outcome: "ACCEPTED",
          key: "asset_monitoring.query_scope",
          utf8Bytes: 10,
          changedFromPreviousUserTurn: false,
          rawJsonStored: false,
        },
      },
    ],
  });
  assert.equal(rows.length, 1);
  assert.equal(rows[0].kind, "user");
  assert.equal(rows[0].hostContext?.key, "asset_monitoring.query_scope");
});
