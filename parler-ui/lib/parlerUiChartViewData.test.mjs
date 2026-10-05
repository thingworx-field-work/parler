import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
for (const key of [
  "window", "document", "customElements", "HTMLElement", "HTMLImageElement", "Element", "Node",
  "Event", "CustomEvent", "MutationObserver", "CSSStyleSheet", "Document", "ShadowRoot", "CSS",
]) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.requestAnimationFrame = (callback) => {
  callback(0);
  return 1;
};
globalThis.cancelAnimationFrame = () => {};
globalThis.ResizeObserver = class {
  observe() {}
  disconnect() {}
};

await import("../parler-ui.js");
const { buildArtifactsFromLegacyBuckets } = await import("./artifactPresentation.js");

const ISO = ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"];
const table = (cacheId) => ({
  kind: "entity-list",
  columns: [{ key: "name", label: "Name", baseType: "STRING" }],
  rows: [{ name: "x" }],
  shownRows: 1,
  totalRows: 1,
  cacheId,
  sourceCacheId: null,
  exportStatus: "none",
  exportMessage: null,
  exportFile: null,
  exportRepository: null,
  exportDownloadUrl: null,
});
const chartOf = (id, sourceCacheId) => ({
  kind: "line",
  chartId: id,
  series: [{ name: "s", x: ISO, y: [1, 2] }],
  ...(sourceCacheId ? { source: { sourceCacheId } } : {}),
});

async function mountThread(charts, tables) {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  const row = {
    kind: "assistant",
    requestId: "req-view-data",
    markdown: "answer",
    charts,
    tables,
    artifacts: buildArtifactsFromLegacyBuckets(charts, tables),
    activity: null,
  };
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, row], busy: false, activeRequestId: null };
  await element.updateComplete;
  const cards = [...element.querySelectorAll("parler-ui-chart")];
  for (const card of cards) {
    await card.updateComplete;
    await card.updateComplete;
  }
  return { element, cards };
}

const viewDataButton = (card) => [...card.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "View data");

test("view data expands the direct parent table and moves focus to its disclosure", async (t) => {
  const { element, cards } = await mountThread([chartOf("c1", "t1")], [table("t1")]);
  t.after(() => element.remove());
  const wrap = element.querySelector('.parler-data-table-wrap[data-parler-artifact-key="table:t1"]');
  assert.ok(wrap, "the parent table is rendered with its artifact key");
  assert.equal(wrap.classList.contains("parler-data-table-wrap--collapsed"), true, "tables in a row with a chart start collapsed");
  viewDataButton(cards[0]).click();
  await element.updateComplete;
  await cards[0].updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  const expanded = element.querySelector('.parler-data-table-wrap[data-parler-artifact-key="table:t1"]');
  assert.equal(expanded.classList.contains("parler-data-table-wrap--collapsed"), false, "the parent table is expanded");
  const disclosure = expanded.querySelector(".parler-data-table-disclosure");
  assert.equal(disclosure.getAttribute("aria-expanded"), "true");
  assert.equal(document.activeElement, disclosure, "focus moves to the table disclosure");
  assert.equal(cards[0].querySelector(".chart-data"), null, "no in-card pager when the table was located");
  assert.equal(element.querySelectorAll(".parler-data-table-wrap").length, 1);
});

test("charts sharing a parent table locate the same single table", async (t) => {
  const { element, cards } = await mountThread([chartOf("c1", "t1"), chartOf("c2", "t1")], [table("t1")]);
  t.after(() => element.remove());
  assert.equal(cards.length, 2);
  assert.equal(element.querySelectorAll(".parler-data-table-wrap").length, 1, "one table in the DOM");
  viewDataButton(cards[1]).click();
  await element.updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  const wrap = element.querySelector('.parler-data-table-wrap[data-parler-artifact-key="table:t1"]');
  assert.equal(wrap.classList.contains("parler-data-table-wrap--collapsed"), false);
  assert.equal(document.activeElement, wrap.querySelector(".parler-data-table-disclosure"));
  assert.equal(cards[1].querySelector(".chart-data"), null);
});

test("a chart without a parent table opens its own paged chart-data view", async (t) => {
  const { element, cards } = await mountThread([chartOf("c1", "missing"), chartOf("c3", null)], [table("t1")]);
  t.after(() => element.remove());
  viewDataButton(cards[0]).click();
  await cards[0].updateComplete;
  await new Promise((r) => setTimeout(r, 0));
  const pager = cards[0].querySelector("section.chart-data[part=chart-data]");
  assert.ok(pager, "no matching table: the card shows its own data view");
  assert.match(pager.querySelector(".chart-data-heading").textContent, /^Showing 1–2 of 2 points received by this chart; not the full source record set\.$/);
  assert.equal(document.activeElement, pager.querySelector(".chart-data-heading"), "focus moves into the data view");
  const wrap = element.querySelector('.parler-data-table-wrap[data-parler-artifact-key="table:t1"]');
  assert.equal(wrap.classList.contains("parler-data-table-wrap--collapsed"), true, "an unrelated table is not touched");
});
