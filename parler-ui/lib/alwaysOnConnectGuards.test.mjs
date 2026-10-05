import assert from "node:assert/strict";
import test from "node:test";

import {
  AUTH_FAILURE_RETRY_SUPPRESS_MS,
  isLikelyAlwaysOnAuthFailure,
  makeBindContext,
  sameBindContext,
  shouldSuppressRepeatedAuthFailure,
} from "./alwaysOnConnectGuards.js";

test("sameBindContext compares conversation, agent, and URL only", () => {
  const a = makeBindContext(" c1 ", " AIAgent ", " ws://example/Thingworx/WS ");
  const b = makeBindContext("c1", "AIAgent", "ws://example/Thingworx/WS");
  const c = makeBindContext("c2", "AIAgent", "ws://example/Thingworx/WS");

  assert.equal(sameBindContext(a, b), true);
  assert.equal(sameBindContext(a, c), false);
});

test("auth failure detection covers platform application-key errors", () => {
  assert.equal(isLikelyAlwaysOnAuthFailure("Missing application key!"), true);
  assert.equal(isLikelyAlwaysOnAuthFailure("Unauthorized credentials"), true);
  assert.equal(isLikelyAlwaysOnAuthFailure("WebSocket error during auth"), true);
  assert.equal(isLikelyAlwaysOnAuthFailure("Thing abc does not exist"), false);
});

test("repeated auth failures are suppressed only for the same key within the window", () => {
  const recent = { appKey: "k1", atMs: 1_000, message: "Missing application key!" };

  assert.equal(shouldSuppressRepeatedAuthFailure(recent, "k1", 2_000), true);
  assert.equal(shouldSuppressRepeatedAuthFailure(recent, "k2", 2_000), false);
  assert.equal(
    shouldSuppressRepeatedAuthFailure(recent, "k1", 1_000 + AUTH_FAILURE_RETRY_SUPPRESS_MS + 1),
    false
  );
});
