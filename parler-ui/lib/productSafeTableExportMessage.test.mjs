import test from "node:test";
import assert from "node:assert/strict";
import { productSafeTableExportMessage } from "./productSafeTableExportMessage.js";

test("allowlists known export messages verbatim", () => {
  assert.equal(
    productSafeTableExportMessage("Full CSV export requires a conversation cacheId for the complete table; only a sample is on the wire."),
    "Full CSV export requires a conversation cacheId for the complete table; only a sample is on the wire."
  );
  assert.equal(productSafeTableExportMessage("CSV export unavailable."), "CSV export unavailable.");
});

test("maps dynamic repo errors to short product strings", () => {
  assert.equal(
    productSafeTableExportMessage("FileRepository Thing not found: MyRepo"),
    "Export repository was not found."
  );
  assert.equal(
    productSafeTableExportMessage("Configured exportFileRepository is not a Thing: X"),
    "Export repository is not a valid FileRepository Thing."
  );
});

test("unknown raw messages never pass through", () => {
  assert.equal(
    productSafeTableExportMessage("Directory does not exist: /Administrator/20260521/"),
    "CSV export unavailable."
  );
  assert.equal(productSafeTableExportMessage("java.lang.RuntimeException: boom"), "CSV export unavailable.");
});
