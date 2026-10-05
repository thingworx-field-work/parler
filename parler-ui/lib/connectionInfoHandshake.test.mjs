import assert from "node:assert/strict";
import test from "node:test";
import {
  CONNECTION_INFO_SCHEMA,
  connectionInfoEpochStale,
  formatConnectionVersionSuffix,
  parseConnectionInfoJson,
} from "./connectionInfoHandshake.js";

test("parseConnectionInfoJson: success extracts extension and coerces implementation", () => {
  const j = JSON.stringify({
    schemaVersion: CONNECTION_INFO_SCHEMA,
    conversationId: "c1",
    agent: { thingName: "A", extensionVersion: "0.1.1", implementationVersion: "0.1.1-SNAPSHOT" },
    serverTime: "2026-01-01T00:00:00.000Z",
  });
  const r = parseConnectionInfoJson(j);
  assert.equal(r.ok, true);
  if (r.ok) {
    assert.equal(r.extensionVersion, "0.1.1");
    assert.equal(r.implementationVersion, "0.1.1-SNAPSHOT");
    assert.equal(r.supportsCancellation, false);
  }
});

test("parseConnectionInfoJson: implementation falls back to extension when absent", () => {
  const j = JSON.stringify({
    schemaVersion: CONNECTION_INFO_SCHEMA,
    agent: { extensionVersion: "0.2.0" },
  });
  const r = parseConnectionInfoJson(j);
  assert.equal(r.ok, true);
  if (r.ok) {
    assert.equal(r.extensionVersion, "0.2.0");
    assert.equal(r.implementationVersion, "0.2.0");
    assert.equal(r.supportsCancellation, false);
  }
});

test("parseConnectionInfoJson: wrong schemaVersion → schema failure", () => {
  const j = JSON.stringify({
    schemaVersion: "parler.connection-info.v2",
    agent: { extensionVersion: "x" },
  });
  const r = parseConnectionInfoJson(j);
  assert.equal(r.ok, false);
  if (!r.ok) assert.equal(r.reason, "schema");
});

test("parseConnectionInfoJson: empty → empty", () => {
  assert.deepEqual(parseConnectionInfoJson(""), { ok: false, reason: "empty" });
  assert.deepEqual(parseConnectionInfoJson("   "), { ok: false, reason: "empty" });
});

test("parseConnectionInfoJson: invalid JSON → parse", () => {
  const r = parseConnectionInfoJson("{");
  assert.equal(r.ok, false);
  if (!r.ok) assert.equal(r.reason, "parse");
});

test("connectionInfoEpochStale: mismatched epochs", () => {
  assert.equal(connectionInfoEpochStale(1, 2), true);
  assert.equal(connectionInfoEpochStale(3, 3), false);
});

test("parseConnectionInfoJson: supportsCancellation true only for JSON boolean true", () => {
  const base = {
    schemaVersion: CONNECTION_INFO_SCHEMA,
    agent: { extensionVersion: "0.1.0" },
  };
  assert.equal(parseConnectionInfoJson(JSON.stringify({ ...base })).supportsCancellation, false);
  assert.equal(
    parseConnectionInfoJson(JSON.stringify({ ...base, capabilities: { supportsCancellation: false } })).supportsCancellation,
    false
  );
  assert.equal(
    parseConnectionInfoJson(JSON.stringify({ ...base, capabilities: { supportsCancellation: true } })).supportsCancellation,
    true
  );
  assert.equal(
    parseConnectionInfoJson(JSON.stringify({ ...base, capabilities: { supportsCancellation: "true" } })).supportsCancellation,
    false
  );
});

test("formatConnectionVersionSuffix labels partial handshakes without question-mark versions", () => {
  assert.equal(formatConnectionVersionSuffix("disconnected", "0.1.217", "0.1.89"), "");
  assert.equal(formatConnectionVersionSuffix("connected", "0.1.217", "0.1.89"), ", 0.1.217:0.1.89");
  assert.equal(formatConnectionVersionSuffix("connected", "", "0.1.89"), ", widget 0.1.89");
  assert.equal(formatConnectionVersionSuffix("connected", "0.1.217", ""), ", agent 0.1.217");
  assert.equal(formatConnectionVersionSuffix("connected", "", ""), "");
});
