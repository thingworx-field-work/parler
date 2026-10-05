import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
for (const key of [
  "window", "document", "customElements", "HTMLElement", "HTMLImageElement", "Element", "Node",
  "Event", "CustomEvent", "KeyboardEvent", "MutationObserver", "CSSStyleSheet", "Document", "ShadowRoot", "CSS",
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
const twoSeries = (id, title, sourceCacheId) => ({
  kind: "line",
  chartId: id,
  title,
  series: [
    { name: "small", x: ISO, y: [1, 2] },
    { name: "big", x: ISO, y: [100, 200] },
  ],
  ...(sourceCacheId ? { source: { sourceCacheId } } : {}),
});

const rowOf = (requestId, charts, tables) => ({
  kind: "assistant",
  requestId,
  markdown: "answer text",
  charts,
  tables,
  artifacts: buildArtifactsFromLegacyBuckets(charts, tables),
  activity: null,
});

async function settleCards(element) {
  await element.updateComplete;
  for (const card of element.querySelectorAll("parler-ui-chart")) {
    await card.updateComplete;
    await card.updateComplete;
  }
  await element.updateComplete;
}

async function mountThread(charts, tables, requestId = "req-expand") {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  const row = rowOf(requestId, charts, tables);
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, row], busy: false, activeRequestId: null };
  await settleCards(element);
  return { element, row, bubble: element.querySelector(".assistant-bubble") };
}

const cardByKey = (root, key) => [...root.querySelectorAll("parler-ui-chart")].find((c) => c.getAttribute("data-parler-artifact-key") === key) ?? null;
const action = (card, text) => [...card.querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === text) ?? null;
const layerOf = (element) => element.querySelector(".chart-expand-layer");
async function expand(element, key) {
  action(cardByKey(element, key), "Expand").click();
  await settleCards(element);
}
const keydown = (target, key, init = {}) => {
  const ev = new dom.window.KeyboardEvent("keydown", { key, bubbles: true, cancelable: true, ...init });
  target.dispatchEvent(ev);
  return ev;
};

test("expanding renders the only card instance in a modal layer over the thread, leaves a placeholder, keeps the view state and moves focus to close", async (t) => {
  const { element, bubble } = await mountThread([twoSeries("c1", "Temperature"), twoSeries("c2", "Pressure")], []);
  t.after(() => element.remove());
  element.hideHeader = false;
  await element.updateComplete;
  assert.equal(layerOf(element), null);
  const thread = element.querySelector(".thread-scroll");
  const composer = element.querySelector(".composer");
  assert.equal(thread.hasAttribute("inert"), false);
  cardByKey(bubble, "chart:c1").querySelectorAll("button.chart-legend-toggle")[1].click();
  await settleCards(element);
  await expand(element, "chart:c1");
  const layer = layerOf(element);
  assert.ok(layer, "the layer is rendered");
  assert.equal(layer.getAttribute("part"), "chart-expand");
  assert.equal(layer.getAttribute("role"), "dialog");
  assert.equal(layer.getAttribute("aria-modal"), "true");
  assert.equal(layer.getAttribute("aria-label"), "Temperature");
  assert.ok(element.querySelector(".thread-wrap > .chart-expand-layer"), "the layer sits over the thread area inside the shell, not on document.body");
  assert.equal(document.body.querySelector(":scope > .chart-expand-layer"), null);
  const instances = [...element.querySelectorAll('parler-ui-chart[data-parler-artifact-key="chart:c1"]')];
  assert.equal(instances.length, 1, "exactly one instance of the expanded chart");
  assert.ok(layer.contains(instances[0]), "and it lives in the layer");
  assert.equal(instances[0].expanded, true);
  assert.equal(action(instances[0], "Close")?.textContent, "Close", "the layer card offers Close in its actions bar");
  assert.equal(instances[0].querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 1, "the hidden series stays hidden: the view state is host-owned");
  const placeholder = bubble.querySelector('.chart-expand-placeholder[data-parler-artifact-key="chart:c1"]');
  assert.ok(placeholder, "a placeholder stands where the card was");
  assert.equal(placeholder.getAttribute("part"), "chart-expand-placeholder");
  assert.ok(placeholder.hasAttribute("data-no-print"));
  assert.equal(placeholder.textContent.trim(), "Chart expanded");
  assert.equal(placeholder.getAttribute("tabindex"), "-1");
  assert.ok(cardByKey(bubble, "chart:c2"), "the other chart still renders in the answer");
  const order = [...bubble.querySelectorAll("[data-parler-artifact-key]")].map((el) => el.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(order, ["chart:c1", "chart:c2"], "the placeholder keeps the artifact position");
  assert.equal(thread.hasAttribute("inert"), true, "the thread is inert while expanded");
  assert.equal(thread.getAttribute("aria-hidden"), "true");
  assert.equal(composer.hasAttribute("inert"), true, "the composer is inert while expanded");
  assert.equal(element.querySelector(".hero").hasAttribute("inert"), true);
  assert.equal(layer.hasAttribute("inert"), false);
  const close = layer.querySelector(".chart-expand-close");
  assert.equal(close.getAttribute("part"), "chart-expand-close");
  assert.equal(document.activeElement, close, "focus moves to the layer's close control");
});

test("escape and the close controls restore the answer's card, the thread scroll position and focus", async (t) => {
  const { element, bubble } = await mountThread([twoSeries("c1", "Temperature")], []);
  t.after(() => element.remove());
  const thread = element.querySelector(".thread-scroll");
  thread.scrollTop = 120;
  await expand(element, "chart:c1");
  thread.scrollTop = 0;
  keydown(layerOf(element), "Escape");
  await settleCards(element);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(layerOf(element), null, "Escape closes the layer");
  assert.equal(bubble.querySelector(".chart-expand-placeholder"), null, "no placeholder remains");
  const restored = cardByKey(bubble, "chart:c1");
  assert.ok(restored, "the answer renders its card again");
  assert.equal(restored.expanded, false);
  assert.equal(element.querySelectorAll("parler-ui-chart").length, 1);
  assert.equal(thread.scrollTop, 120, "the thread scroll position is restored");
  assert.equal(thread.hasAttribute("inert"), false);
  assert.equal(element.querySelector(".composer").hasAttribute("inert"), false);
  assert.equal(document.activeElement, action(restored, "Expand"), "focus returns to the expand control that opened the layer");

  await expand(element, "chart:c1");
  layerOf(element).querySelector(".chart-expand-close").click();
  await settleCards(element);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(layerOf(element), null, "the layer's close button closes");
  assert.equal(document.activeElement, action(cardByKey(bubble, "chart:c1"), "Expand"));

  await expand(element, "chart:c1");
  action(cardByKey(layerOf(element), "chart:c1"), "Close").click();
  await settleCards(element);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(layerOf(element), null, "the card's own Close closes");
  assert.equal(document.activeElement, action(cardByKey(bubble, "chart:c1"), "Expand"));
});

test("keyboard traversal stays inside the layer: endpoints are the controls actually reachable, with disclosures closed and open", async (t) => {
  const { element } = await mountThread([twoSeries("c1", "Temperature")], []);
  const outside = document.createElement("button");
  outside.textContent = "outside";
  document.body.append(outside);
  t.after(() => {
    element.remove();
    outside.remove();
  });
  await expand(element, "chart:c1");
  const layer = layerOf(element);
  const close = layer.querySelector(".chart-expand-close");
  const notes = layer.querySelector("details.chart-notes");
  const notesSummary = notes.querySelector(":scope > summary");
  const diagnostics = notes.querySelector("details.chart-notes-diagnostics > summary");
  assert.equal(notes.hasAttribute("open"), false, "data notes start closed");
  assert.ok(diagnostics, "the nested diagnostics summary exists but is hidden inside the closed notes");

  // Closed notes: the last reachable control is the Data notes summary, not the hidden Diagnostics one.
  notesSummary.focus();
  assert.equal(document.activeElement, notesSummary);
  const forwardClosed = keydown(notesSummary, "Tab");
  assert.equal(forwardClosed.defaultPrevented, true, "Tab from the last reachable control is intercepted");
  assert.equal(document.activeElement, close, "and wraps to the close control instead of leaving for the outside button");
  const backwardClosed = keydown(close, "Tab", { shiftKey: true });
  assert.equal(backwardClosed.defaultPrevented, true);
  assert.equal(document.activeElement, notesSummary, "Shift+Tab from close lands on the Data notes summary, never on the hidden Diagnostics summary");

  // Open notes: the nested closed Diagnostics summary becomes reachable and is now the last control.
  notesSummary.click();
  await settleCards(element);
  assert.equal(layer.querySelector("details.chart-notes").hasAttribute("open"), true);
  const openDiagnostics = layer.querySelector("details.chart-notes-diagnostics > summary");
  const openNotesSummary = layer.querySelector("details.chart-notes > summary");
  openNotesSummary.focus();
  const middle = keydown(openNotesSummary, "Tab");
  assert.equal(middle.defaultPrevented, false, "Data notes is no longer the last control, so native traversal continues");
  openDiagnostics.focus();
  assert.equal(document.activeElement, openDiagnostics);
  const forwardOpen = keydown(openDiagnostics, "Tab");
  assert.equal(forwardOpen.defaultPrevented, true);
  assert.equal(document.activeElement, layer.querySelector(".chart-expand-close"), "Tab from Diagnostics wraps to close");
  const backwardOpen = keydown(layer.querySelector(".chart-expand-close"), "Tab", { shiftKey: true });
  assert.equal(backwardOpen.defaultPrevented, true);
  assert.equal(document.activeElement, openDiagnostics, "Shift+Tab from close lands on Diagnostics once it is reachable");

  // Focus that somehow left the layer is pulled back on the next Tab in either direction.
  outside.focus();
  assert.equal(document.activeElement, outside);
  keydown(layer, "Tab");
  assert.equal(document.activeElement, layer.querySelector(".chart-expand-close"));
  outside.focus();
  keydown(layer, "Tab", { shiftKey: true });
  assert.equal(document.activeElement, openDiagnostics);
});

test("view data from the expanded card closes the expansion first, then expands the parent table and focuses it", async (t) => {
  const { element, bubble } = await mountThread([twoSeries("c1", "Temperature", "t1")], [table("t1")]);
  t.after(() => element.remove());
  await expand(element, "chart:c1");
  action(cardByKey(layerOf(element), "chart:c1"), "View data").click();
  await settleCards(element);
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(layerOf(element), null, "the expansion is closed");
  assert.equal(bubble.querySelector(".chart-expand-placeholder"), null);
  assert.ok(cardByKey(bubble, "chart:c1"), "the answer's card is back");
  const wrap = bubble.querySelector('.parler-data-table-wrap[data-parler-artifact-key="table:t1"]');
  const disclosure = wrap.querySelector(".parler-data-table-disclosure");
  assert.equal(disclosure.getAttribute("aria-expanded"), "true", "the parent table is expanded");
  assert.equal(document.activeElement, disclosure, "focus lands on the table disclosure in the answer, not behind an overlay");
  assert.equal(element.querySelector(".thread-scroll").hasAttribute("inert"), false);
});

test("view data from an expanded card without a parent table closes the expansion and opens the answer card's data view", async (t) => {
  const { element, bubble } = await mountThread([twoSeries("c1", "Temperature")], []);
  t.after(() => element.remove());
  await expand(element, "chart:c1");
  action(cardByKey(layerOf(element), "chart:c1"), "View data").click();
  await settleCards(element);
  await new Promise((r) => setTimeout(r, 0));
  await settleCards(element);
  assert.equal(layerOf(element), null);
  const restored = cardByKey(bubble, "chart:c1");
  assert.ok(restored.querySelector("section.chart-data"), "the in-card data view opens on the answer's card");
  assert.ok(restored.contains(document.activeElement), "focus is inside the answer's card");
});

test("removing the source answer while expanded drops the layer and leaves no orphan", async (t) => {
  const { element } = await mountThread([twoSeries("c1", "Temperature")], []);
  t.after(() => element.remove());
  await expand(element, "chart:c1");
  assert.ok(layerOf(element));
  element._chatState = { ...element._chatState, rows: [] };
  await settleCards(element);
  assert.equal(layerOf(element), null, "the layer disappears with its answer");
  assert.equal(element.querySelectorAll("parler-ui-chart, .chart-expand-placeholder").length, 0, "nothing is reinserted");
  assert.equal(element.querySelector(".thread-scroll").hasAttribute("inert"), false);
  assert.equal(element.querySelector(".composer").hasAttribute("inert"), false);
  const row = rowOf("req-expand", [twoSeries("c1", "Temperature")], []);
  element._chatState = { ...element._chatState, rows: [row] };
  await settleCards(element);
  assert.equal(layerOf(element), null, "a re-added answer does not resurrect the expansion");
  assert.ok(cardByKey(element, "chart:c1"));
});

test("removing only the expanded chart artifact also drops the layer", async (t) => {
  const { element, row } = await mountThread([twoSeries("c1", "Temperature"), twoSeries("c2", "Pressure")], []);
  t.after(() => element.remove());
  await expand(element, "chart:c2");
  const trimmed = rowOf(row.requestId, [twoSeries("c1", "Temperature")], []);
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, trimmed] };
  await settleCards(element);
  assert.equal(layerOf(element), null);
  assert.equal(element.querySelectorAll("parler-ui-chart").length, 1);
  assert.equal(element.querySelector(".chart-expand-placeholder"), null);
});

test("two widget instances expand independently", async (t) => {
  const a = await mountThread([twoSeries("c1", "A chart")], [], "req-a");
  const b = await mountThread([twoSeries("c1", "B chart")], [], "req-b");
  t.after(() => {
    a.element.remove();
    b.element.remove();
  });
  await expand(a.element, "chart:c1");
  assert.ok(layerOf(a.element));
  assert.equal(layerOf(b.element), null, "the other widget has no layer");
  assert.equal(b.element.querySelector(".thread-scroll").hasAttribute("inert"), false, "the other widget stays interactive");
  assert.ok(cardByKey(b.bubble, "chart:c1"), "the other widget's card is untouched");
  assert.equal(document.querySelectorAll(".chart-expand-layer").length, 1);
  await expand(b.element, "chart:c1");
  assert.equal(document.querySelectorAll(".chart-expand-layer").length, 2, "each instance owns its own layer");
  keydown(layerOf(a.element), "Escape");
  await settleCards(a.element);
  assert.equal(layerOf(a.element), null);
  assert.ok(layerOf(b.element), "closing one leaves the other open");
});

test("view changes made in the layer persist in the answer's card after closing", async (t) => {
  const { element, bubble } = await mountThread([twoSeries("c1", "Temperature")], []);
  t.after(() => element.remove());
  await expand(element, "chart:c1");
  const inLayer = cardByKey(layerOf(element), "chart:c1");
  inLayer.querySelectorAll("button.chart-legend-toggle")[0].click();
  await settleCards(element);
  keydown(layerOf(element), "Escape");
  await settleCards(element);
  const restored = cardByKey(bubble, "chart:c1");
  assert.equal(restored.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 1, "the series hidden while expanded stays hidden");
  assert.match(restored.querySelector(".chart-view-note").textContent, /1 of 2 series hidden/);
});

test("keyboard query in the expand layer reveals the mark through the layer, which is the scrolling ancestor", async (t) => {
  const names = Array.from({ length: 24 }, (_v, i) => `SE.CellFab.Model.Workunit.AC-BenchScale-${String(i + 1).padStart(2, "0")}`);
  const chart = { kind: "bar", chartId: "hb", orientation: "horizontal", title: "Tall", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: names, y: names.map((_n, i) => i + 1) }] };
  const { element } = await mountThread([chart], []);
  t.after(() => element.remove());
  await expand(element, "chart:hb");
  const layer = layerOf(element);
  const card = cardByKey(layer, "chart:hb");
  await card.updateComplete;
  const area = card.querySelector(".chart-plot-area");
  const host = card.querySelector(".chart-root");
  const height = Number(host.querySelector("svg").getAttribute("viewBox").split(" ")[3]);
  assert.ok(height > 600, "the expanded chart keeps its full height");
  // In the layer the plot area has no height limit; the layer scrolls (clientHeight 400).
  Object.defineProperty(layer, "scrollHeight", { value: height + 200, configurable: true });
  Object.defineProperty(layer, "clientHeight", { value: 400, configurable: true });
  Object.defineProperty(layer, "clientWidth", { value: 700, configurable: true });
  layer.getBoundingClientRect = () => ({ left: 0, top: 0, width: 700, height: 400 });
  area.getBoundingClientRect = () => ({ left: 0, top: 40 - layer.scrollTop, width: 640, height });
  host.getBoundingClientRect = () => ({ left: 0, top: 40 - layer.scrollTop, width: card._drawn.hit.layout.viewBoxW, height });
  const plot = card.querySelector(".chart-plot-area");
  plot.focus();
  const press = (k) => plot.dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: k, bubbles: true, cancelable: true }));
  press("ArrowRight");
  await card.updateComplete;
  assert.equal(layer.scrollTop, 0);
  press("End");
  await card.updateComplete;
  const markY = 40 + card._drawn.hit.layout.marginTop + card._active.cy;
  assert.ok(Math.abs(layer.scrollTop - (markY + 12 - 400)) < 1e-6, `End scrolls the layer so the last mark is visible (${layer.scrollTop})`);
  assert.equal(area.scrollTop, 0, "the plot area itself does not scroll inside the layer");
  assert.equal(document.activeElement, plot, "focus stays on the plot's keyboard stop");
  const tip = card.querySelector(".chart-tooltip");
  const tipTop = Number.parseInt(tip.style.top, 10);
  const tipH = Math.ceil(3 * 12 * 1.35 + 14);
  const visibleTop = layer.scrollTop - 40;
  assert.ok(tipTop >= visibleTop - 1e-6 && tipTop + tipH <= visibleTop + 400 + 1e-6, `the tooltip is inside the layer's visible region (${tipTop})`);
  press("Home");
  await card.updateComplete;
  assert.ok(layer.scrollTop >= 0 && layer.scrollTop < 40, `Home scrolls the layer back to the first mark's reserved room (${layer.scrollTop})`);
  const firstMarkTop = 40 - layer.scrollTop + card._drawn.hit.layout.marginTop + card._active.cy;
  assert.ok(firstMarkTop >= 0 && firstMarkTop + 12 <= 400, "the first mark is inside the layer's viewport");
  assert.ok(layerOf(element), "the layer stays open");
});
