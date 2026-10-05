import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", { url: "http://localhost/", pretendToBeVisual: true });
for (const key of [
  "window", "document", "customElements", "HTMLElement", "HTMLImageElement", "Element", "Node",
  "Event", "CustomEvent", "KeyboardEvent", "MutationObserver", "CSSStyleSheet", "Document", "ShadowRoot", "CSS",
]) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.requestAnimationFrame = (callback) => { callback(0); return 1; };
globalThis.cancelAnimationFrame = () => {};
globalThis.ResizeObserver = class { observe() {} disconnect() {} };

const { buildArtifactsFromLegacyBuckets } = await import("./artifactPresentation.js");
await import("../parler-ui.js");

const pie = (chartId, sourceCacheId, title) => ({ kind: "pie", chartId, title, series: [{ name: "s", x: ["a", "b"], y: [1, 2] }], source: { sourceCacheId } });
const line = (chartId, sourceCacheId, title) => ({ kind: "line", chartId, title, series: [{ name: "s", x: ["1", "2"], y: [1, 2] }], source: { sourceCacheId } });
const table = (cacheId) => ({ kind: "entity-list", columns: [{ key: "a", label: "A", baseType: "STRING" }], rows: [{ a: "x" }], shownRows: 1, totalRows: 1, cacheId, sourceCacheId: null, exportStatus: "none" });

const rowOf = (requestId, charts, tables, withArtifacts = true) => ({
  kind: "assistant", requestId, markdown: "answer text", charts, tables,
  artifacts: withArtifacts ? buildArtifactsFromLegacyBuckets(charts, tables) : undefined, activity: null,
});

async function settle(element) {
  await element.updateComplete;
  for (const card of element.querySelectorAll("parler-ui-chart")) { await card.updateComplete; await card.updateComplete; }
  await element.updateComplete;
}

async function mountThread(charts, tables, withArtifacts = true) {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  const row = rowOf("req-grid", charts, tables, withArtifacts);
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, row], busy: false, activeRequestId: null };
  await settle(element);
  return { element, row, bubble: element.querySelector(".assistant-bubble") };
}

const keysIn = (root) => [...root.querySelectorAll("[data-parler-artifact-key]")].map((n) => n.getAttribute("data-parler-artifact-key"));

test("C3a: two charts sharing a parent table render in one grid after the table, in artifact order, with nothing duplicated", async (t) => {
  const { element, bubble } = await mountThread([pie("c1", "t1", "Oven-01"), pie("c2", "t1", "Oven-02")], [table("t1")]);
  t.after(() => element.remove());
  const grids = bubble.querySelectorAll(".chart-grid");
  assert.equal(grids.length, 1);
  assert.equal(grids[0].getAttribute("data-parler-chart-grid"), "2");
  assert.deepEqual([...grids[0].querySelectorAll("parler-ui-chart")].map((c) => c.getAttribute("data-parler-artifact-key")), ["chart:c1", "chart:c2"]);
  assert.deepEqual(keysIn(bubble), ["table:t1", "chart:c1", "chart:c2"], "the table stays before the grid and appears once");
  assert.equal(bubble.querySelectorAll("parler-ui-chart").length, 2);
  assert.equal(grids[0].previousElementSibling.classList.contains("parler-data-table-wrap"), true);
});

test("C3a: four pies form one grid; line + pie share layout only; a table between charts keeps them out of a grid; a single chart has no grid", async (t) => {
  const four = await mountThread([pie("c1", "t1"), pie("c2", "t1"), pie("c3", "t1"), pie("c4", "t1")], [table("t1")]);
  t.after(() => four.element.remove());
  assert.equal(four.bubble.querySelector(".chart-grid").getAttribute("data-parler-chart-grid"), "4");
  assert.equal(four.bubble.querySelectorAll(".chart-grid parler-ui-chart").length, 4);
  const mixed = await mountThread([line("c1", "t1"), pie("c2", "t1")], [table("t1")]);
  t.after(() => mixed.element.remove());
  const cards = [...mixed.bubble.querySelectorAll(".chart-grid parler-ui-chart")];
  assert.equal(cards.length, 2);
  assert.notEqual(cards[0].chart.kind, cards[1].chart.kind);
  assert.deepEqual(cards.map((c) => c.viewState), [null, null], "no shared view state");
  const separate = await mountThread([pie("c1", "t1"), pie("c2", "t2")], [table("t1"), table("t2")]);
  t.after(() => separate.element.remove());
  assert.equal(separate.bubble.querySelector(".chart-grid"), null);
  assert.deepEqual(keysIn(separate.bubble), ["table:t1", "chart:c1", "table:t2", "chart:c2"]);
  const single = await mountThread([pie("c1", "t1")], [table("t1")]);
  t.after(() => single.element.remove());
  assert.equal(single.bubble.querySelector(".chart-grid"), null);
});

test("C3a: a legacy history row without artifacts uses the same deterministic projection and grid", async (t) => {
  const { element, bubble } = await mountThread([pie("c1", "t1"), pie("c2", "t1")], [table("t1")], false);
  t.after(() => element.remove());
  assert.equal(bubble.querySelector(".chart-grid")?.getAttribute("data-parler-chart-grid"), "2");
  assert.deepEqual(keysIn(bubble), ["table:t1", "chart:c1", "chart:c2"]);
});

test("C3a: expanding a grid member leaves its placeholder inside the grid and closing restores the card in place", async (t) => {
  const { element, bubble } = await mountThread([pie("c1", "t1", "One"), pie("c2", "t1", "Two")], [table("t1")]);
  t.after(() => element.remove());
  const card = [...bubble.querySelectorAll("parler-ui-chart")].find((c) => c.getAttribute("data-parler-artifact-key") === "chart:c2");
  [...card.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "Expand").click();
  await settle(element);
  const grid = bubble.querySelector(".chart-grid");
  const placeholder = grid.querySelector(".chart-expand-placeholder");
  assert.ok(placeholder, "the placeholder sits in the grid");
  assert.equal(placeholder.getAttribute("data-parler-artifact-key"), "chart:c2");
  assert.equal(grid.querySelectorAll("parler-ui-chart").length, 1);
  assert.ok(element.querySelector(".chart-expand-layer"));
  const close = [...element.querySelector(".chart-expand-layer").querySelectorAll(".chart-action")].find((b) => b.textContent === "Close");
  close.click();
  await settle(element);
  assert.equal(bubble.querySelector(".chart-grid .chart-expand-placeholder"), null);
  assert.deepEqual([...bubble.querySelectorAll(".chart-grid parler-ui-chart")].map((c) => c.getAttribute("data-parler-artifact-key")), ["chart:c1", "chart:c2"]);
});

test("C3a: the stylesheet realises the same two-column rule and drops the card margin inside a grid", () => {
  const css = fs.readFileSync(new URL("../styles/parler-ui.css", import.meta.url), "utf8");
  const rule = css.slice(css.indexOf("parler-ui .chart-grid {"));
  assert.match(rule, /grid-template-columns: repeat\(auto-fit, minmax\(max\(360px, calc\(50% - 8px\)\), 1fr\)\)/);
  assert.match(rule, /gap: 16px/);
  assert.ok(css.includes("parler-ui .chart-grid .chart-card {\n  margin: 0;\n}"));
});
