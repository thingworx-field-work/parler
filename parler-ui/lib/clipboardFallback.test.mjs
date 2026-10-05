import assert from "node:assert/strict";
import { test } from "node:test";
import { JSDOM } from "jsdom";
import { writeClipboardWithSelectionFallback } from "./clipboardFallback.js";

test("writeClipboardWithSelectionFallback writes html and plain text via copy event", () => {
  const dom = new JSDOM("<!doctype html><body><p id=\"before\">keep</p></body>");
  const { document, Event } = dom.window;
  /** @type {Record<string, string>} */
  let captured = {};
  document.execCommand = (command) => {
    if (command !== "copy") return false;
    captured = {};
    const ev = new Event("copy", { bubbles: true, cancelable: true });
    ev.clipboardData = {
      setData(type, value) {
        captured[type] = value;
      },
    };
    document.dispatchEvent(ev);
    return true;
  };

  const ok = writeClipboardWithSelectionFallback(document, {
    html: '<article class="parler-copy-html"><p>Hello</p></article>',
    plain: "Hello",
  });

  assert.equal(ok, true);
  assert.equal(captured["text/plain"], "Hello");
  assert.equal(captured["text/html"], '<article class="parler-copy-html"><p>Hello</p></article>');
  assert.equal(document.querySelectorAll("[contenteditable]").length, 0);
  assert.equal(document.getElementById("before")?.textContent, "keep");
});

test("writeClipboardWithSelectionFallback returns false when execCommand is unavailable", () => {
  const dom = new JSDOM("<!doctype html><body></body>");
  assert.equal(
    writeClipboardWithSelectionFallback(dom.window.document, {
      html: "<p>x</p>",
      plain: "x",
    }),
    false
  );
});
