import test from "node:test";
import assert from "node:assert/strict";
import {
  boundTransportString,
  boundTransportStringWire,
} from "./boundTransportString.js";
import { submitUserPromptInfoTable } from "./parlerInfotableJson.js";

/** @param {Record<string, unknown>} props @param {Record<string, string | null>} [attrs] */
function mockEl(props, attrs = {}) {
  return {
    ...props,
    /** @param {string} name */
    getAttribute(name) {
      return Object.prototype.hasOwnProperty.call(attrs, name)
        ? attrs[name]
        : null;
    },
  };
}

test("boundTransportString trims property and attribute", () => {
  const a = mockEl({ conversationId: "  abc  " });
  assert.equal(
    boundTransportString(/** @type {HTMLElement} */ (/** @type {unknown} */ (a)), "conversationId", "conversation-id"),
    "abc"
  );
  const b = mockEl({}, { "conversation-id": "  def  " });
  assert.equal(
    boundTransportString(/** @type {HTMLElement} */ (/** @type {unknown} */ (b)), "conversationId", "conversation-id"),
    "def"
  );
});

test("boundTransportStringWire preserves inner and edge whitespace on property", () => {
  const el = mockEl({ hostScopeJson: "  {\"kind\":\"kv\"}  " });
  assert.equal(
    boundTransportStringWire(/** @type {HTMLElement} */ (/** @type {unknown} */ (el)), "hostScopeJson", "host-scope-json"),
    "  {\"kind\":\"kv\"}  "
  );
});

test("boundTransportStringWire preserves whitespace-only property", () => {
  const el = mockEl({ hostScopeJson: "   " });
  assert.equal(
    boundTransportStringWire(/** @type {HTMLElement} */ (/** @type {unknown} */ (el)), "hostScopeJson", "host-scope-json"),
    "   "
  );
});

test("boundTransportStringWire falls back to attribute without trim", () => {
  const el = mockEl({}, { "host-scope-json": " \tx\t " });
  assert.equal(
    boundTransportStringWire(/** @type {HTMLElement} */ (/** @type {unknown} */ (el)), "hostScopeJson", "host-scope-json"),
    " \tx\t "
  );
});

test("boundTransportStringWire returns empty when unset", () => {
  const el = mockEl({});
  assert.equal(
    boundTransportStringWire(/** @type {HTMLElement} */ (/** @type {unknown} */ (el)), "hostScopeJson", "host-scope-json"),
    ""
  );
});

test("SubmitUserPrompt InfoTable hostContext column matches wire read (Send path contract)", () => {
  const raw = "  {\"kind\":\"kv\",\"pairs\":[]}  ";
  const el = mockEl({ hostScopeJson: raw });
  const hostCtx = boundTransportStringWire(
    /** @type {HTMLElement} */ (/** @type {unknown} */ (el)),
    "hostScopeJson",
    "host-scope-json"
  );
  const it = submitUserPromptInfoTable("hi", "MyAgent", "", "Europe/Berlin", hostCtx);
  const hostField = it.rows[0].fields[4];
  assert.equal(hostField.kind, "String");
  assert.deepEqual(hostField.value, ["String", raw]);
});
