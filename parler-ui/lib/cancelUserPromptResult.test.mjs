import test from "node:test";
import assert from "node:assert/strict";
import { parseCancelUserPromptResult } from "./cancelUserPromptResult.js";
import { cancelUserPromptInfoTable } from "./parlerInfotableJson.js";

test("parseCancelUserPromptResult accepts schemaVersion 1 number", () => {
  const j = JSON.stringify({
    schemaVersion: 1,
    status: "accepted",
    conversationId: "gw",
    requestId: "r1",
    alreadyRequested: false,
  });
  const r = parseCancelUserPromptResult(j);
  assert.equal(r.ok, true);
  if (r.ok) {
    assert.equal(r.status, "accepted");
    assert.equal(r.alreadyRequested, false);
  }
});

test("parseCancelUserPromptResult accepts schemaVersion string 1", () => {
  const r = parseCancelUserPromptResult(
    JSON.stringify({ schemaVersion: "1", status: "not_active", conversationId: "c", requestId: "r" })
  );
  assert.equal(r.ok, true);
  if (r.ok) assert.equal(r.status, "not_active");
});

test("parseCancelUserPromptResult alreadyRequested true only for JSON true", () => {
  const okTrue = parseCancelUserPromptResult(
    JSON.stringify({ schemaVersion: 1, status: "accepted", alreadyRequested: true })
  );
  assert.equal(okTrue.ok && okTrue.alreadyRequested, true);
  const okStr = parseCancelUserPromptResult(
    JSON.stringify({ schemaVersion: 1, status: "accepted", alreadyRequested: "true" })
  );
  assert.equal(okStr.ok && okStr.alreadyRequested, true);
  const bad = parseCancelUserPromptResult(
    JSON.stringify({ schemaVersion: 1, status: "accepted", alreadyRequested: "yes" })
  );
  assert.equal(bad.ok && bad.alreadyRequested, false);
});

test("cancelUserPromptInfoTable row matches Java parameter order", () => {
  const t = cancelUserPromptInfoTable("rid-1", "AgentA", "user_stop");
  assert.equal(t.rows.length, 1);
  assert.equal(t.rows[0].fields.length, 3);
  assert.equal(t.rows[0].fields[0].value[1], "rid-1");
  assert.equal(t.rows[0].fields[1].value[1], "AgentA");
  assert.equal(t.rows[0].fields[2].value[1], "user_stop");
});
