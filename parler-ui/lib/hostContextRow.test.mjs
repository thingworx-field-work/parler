import assert from "node:assert/strict";
import test from "node:test";

import {
  buildLiveHostContextFromWire,
  hostContextDisclosureSummaryLabel,
  parseHostContextSnapshot,
  resolveHostContextRawJson,
  shouldShowHostContextDisclosure,
  utf8ByteLength,
} from "./hostContextRow.js";

test("parseHostContextSnapshot accepts history wire object", () => {
  const snap = parseHostContextSnapshot({
    schema: "parler-host-context-snapshot-v1",
    accepted: true,
    outcome: "ACCEPTED",
    key: "asset_monitoring.query_scope",
    hash: "sha256:abc",
    utf8Bytes: 42,
    changedFromPreviousUserTurn: false,
    rawJsonStored: false,
  });
  assert.equal(snap?.key, "asset_monitoring.query_scope");
  assert.equal(snap?.rawJsonStored, false);
});

test("resolveHostContextRawJson anchors from prior user row", () => {
  const rows = [
    {
      kind: "user",
      text: "first",
      hostContext: {
        schema: "parler-host-context-snapshot-v1",
        accepted: true,
        outcome: "ACCEPTED",
        key: "k",
        hash: "sha256:h1",
        rawJsonStored: true,
        rawJson: '{"key":"k","context":{}}',
      },
    },
    {
      kind: "user",
      text: "second",
      hostContext: {
        schema: "parler-host-context-snapshot-v1",
        accepted: true,
        outcome: "ACCEPTED",
        key: "k",
        hash: "sha256:h1",
        rawJsonStored: false,
      },
    },
  ];
  const resolved = resolveHostContextRawJson(rows[1].hostContext, rows, 1);
  assert.equal(resolved.available, true);
  assert.equal(resolved.text, '{"key":"k","context":{}}');
});

test("buildLiveHostContextFromWire marks unchanged when wire matches prior rawJson", () => {
  const wire = '{"key":"k","context":{"page":"Asset Monitoring"}}';
  const prior = [
    {
      kind: "user",
      text: "before",
      hostContext: {
        schema: "parler-host-context-snapshot-v1",
        accepted: true,
        outcome: "ACCEPTED",
        key: "k",
        rawJsonStored: true,
        rawJson: wire,
      },
    },
  ];
  const snap = buildLiveHostContextFromWire(wire, prior);
  assert.equal(snap?.changedFromPreviousUserTurn, false);
  assert.equal(snap?.key, "k");
});

test("disclosure label uses changed flag", () => {
  const label = hostContextDisclosureSummaryLabel({
    schema: "parler-host-context-snapshot-v1",
    accepted: true,
    outcome: "ACCEPTED",
    key: "asset_monitoring.query_scope",
    utf8Bytes: 335,
    changedFromPreviousUserTurn: true,
  });
  assert.match(label, /changed/);
  assert.match(label, /335 bytes/);
});

test("shouldShowHostContextDisclosure hides absent", () => {
  assert.equal(
    shouldShowHostContextDisclosure({ schema: "parler-host-context-snapshot-v1", outcome: "ABSENT" }),
    false
  );
  assert.equal(utf8ByteLength("café"), 5);
});
