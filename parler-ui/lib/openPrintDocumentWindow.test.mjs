import assert from "node:assert/strict";
import { test } from "node:test";
import { openPrintDocumentWindow } from "./openPrintDocumentWindow.js";

test("openPrintDocumentWindow uses two-arg window.open (no noopener feature string)", () => {
  /** @type {unknown[][]} */
  const calls = [];
  const win = {
    open(...args) {
      calls.push(args);
      return /** @type {Window} */ ({});
    },
  };
  openPrintDocumentWindow(win);
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], ["", "_blank"]);
});
