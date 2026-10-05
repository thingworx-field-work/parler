import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/", pretendToBeVisual: true });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.HTMLElement = dom.window.HTMLElement;
globalThis.Element = dom.window.Element;
const { focusTrapTarget, isKeyboardReachable, keyboardReachableControls } = await import("./keyboardReachable.js");

function mount(html) {
  const root = document.createElement("div");
  root.innerHTML = html;
  document.body.append(root);
  return root;
}

test("reachable controls exclude disabled, opted-out, hidden, inert and closed-disclosure content", (t) => {
  const root = mount(`
    <button id="a">a</button>
    <button id="b" disabled>b</button>
    <div id="c" tabindex="-1">c</div>
    <div id="d" tabindex="0">d</div>
    <input id="e" type="hidden">
    <input id="f" type="range">
    <div hidden><button id="g">g</button></div>
    <div inert><button id="h">h</button></div>
    <details id="closed">
      <summary id="s1">closed</summary>
      <button id="i">i</button>
      <details><summary id="s2">nested</summary><button id="j">j</button></details>
    </details>
    <details open>
      <summary id="s3">open</summary>
      <button id="k">k</button>
      <details><summary id="s4">nested closed</summary><button id="l">l</button></details>
    </details>
    <a id="m" href="#">m</a>
    <a id="n">n</a>
    <summary id="stray">stray summary outside details</summary>
  `);
  t.after(() => root.remove());
  assert.deepEqual(keyboardReachableControls(root).map((el) => el.id), ["a", "d", "f", "s1", "s3", "k", "s4", "m"]);
  assert.equal(isKeyboardReachable(root.querySelector("#s2"), root), false, "a summary inside a closed ancestor is not reachable");
  assert.equal(isKeyboardReachable(root.querySelector("#s4"), root), true, "a closed disclosure's own summary is reachable");
  assert.equal(isKeyboardReachable(root.querySelector("#l"), root), false);
  assert.equal(isKeyboardReachable(document.body, root), false, "elements outside the root are never reachable");
});

test("reachability follows disclosures as they open and close", (t) => {
  const root = mount(`<button id="first">first</button><details id="d"><summary id="s">s</summary><button id="inner">inner</button></details>`);
  t.after(() => root.remove());
  assert.deepEqual(keyboardReachableControls(root).map((el) => el.id), ["first", "s"]);
  root.querySelector("#d").setAttribute("open", "");
  assert.deepEqual(keyboardReachableControls(root).map((el) => el.id), ["first", "s", "inner"]);
  root.querySelector("#d").removeAttribute("open");
  assert.deepEqual(keyboardReachableControls(root).map((el) => el.id), ["first", "s"]);
});

test("focusTrapTarget wraps only at the reachable endpoints or when focus is outside", (t) => {
  const root = mount(`<button id="first">first</button><button id="mid">mid</button><details><summary id="last">last</summary><button id="hidden">hidden</button></details>`);
  t.after(() => root.remove());
  const byId = (id) => root.querySelector(`#${id}`);
  assert.equal(focusTrapTarget(root, { shiftKey: false, active: byId("last") }), byId("first"));
  assert.equal(focusTrapTarget(root, { shiftKey: false, active: byId("mid") }), null);
  assert.equal(focusTrapTarget(root, { shiftKey: true, active: byId("first") }), byId("last"), "the hidden button is never an endpoint");
  assert.equal(focusTrapTarget(root, { shiftKey: true, active: byId("mid") }), null);
  assert.equal(focusTrapTarget(root, { shiftKey: false, active: document.body }), byId("first"));
  assert.equal(focusTrapTarget(root, { shiftKey: true, active: null }), byId("last"));
  const empty = mount(`<p>nothing</p>`);
  t.after(() => empty.remove());
  assert.equal(focusTrapTarget(empty, { shiftKey: false, active: null }), null);
});
