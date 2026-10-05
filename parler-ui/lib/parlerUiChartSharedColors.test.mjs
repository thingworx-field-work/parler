import assert from "node:assert/strict";
import fs from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", { url: "http://localhost/", pretendToBeVisual: true });
for (const key of [
  "window", "document", "customElements", "HTMLElement", "HTMLImageElement", "Element", "Node",
  "Event", "CustomEvent", "KeyboardEvent", "MouseEvent", "FocusEvent", "MutationObserver", "CSSStyleSheet", "Document", "ShadowRoot", "CSS",
]) {
  globalThis[key] = dom.window[key];
}
globalThis.getComputedStyle = dom.window.getComputedStyle.bind(dom.window);
globalThis.requestAnimationFrame = (callback) => { callback(0); return 1; };
globalThis.cancelAnimationFrame = () => {};
globalThis.ResizeObserver = class { observe() {} disconnect() {} };

const { buildArtifactsFromLegacyBuckets, orderArtifactsForDisplay } = await import("./artifactPresentation.js");
const { parseHistoryRows } = await import("./historyHydrate.js");
const { reduceUiEvent } = await import("./chatSession.js");
await import("../parler-ui.js");

const pie = (chartId, labels, values = labels.map(() => 10)) => ({ kind: "pie", chartId, title: chartId, series: [{ name: "share", x: labels, y: values }], source: { sourceCacheId: "t1" } });
const member = (key, order, over = {}) => ({ key, order, name: key, expectedType: "chart", state: "pending", ...over });
const manifest = (revision, members, over = {}) => {
  const counts = { ready: 0, noData: 0, error: 0, cancelled: 0 };
  for (const m of members) {
    if (m.state === "ready") counts.ready += 1;
    if (m.state === "no-data") counts.noData += 1;
    if (m.state === "error") counts.error += 1;
    if (m.state === "cancelled") counts.cancelled += 1;
  }
  const fin = over.final ?? false;
  return { groupId: "g1", revision, title: "Utilization", layout: "auto", final: fin, members, summary: { expected: members.length, ...counts, final: fin }, ...over };
};
const shared = (keys) => ({ dimension: "utilization state", keys });

async function settle(element) {
  await element.updateComplete;
  for (const card of element.querySelectorAll("parler-ui-chart")) { await card.updateComplete; await card.updateComplete; }
  await element.updateComplete;
}

async function mount(rows, active = false) {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, ...rows], busy: active, activeRequestId: active ? rows[0].requestId : null };
  await settle(element);
  return { element, bubble: element.querySelector(".assistant-bubble") };
}

const rowOf = (charts, groups, requestId = "req-sc") => ({ kind: "assistant", requestId, markdown: "answer", charts, tables: [], artifacts: buildArtifactsFromLegacyBuckets(charts, []), activity: null, ...(groups ? { groups } : {}) });
const cardByKey = (root, key) => [...root.querySelectorAll("parler-ui-chart")].find((c) => c.getAttribute("data-parler-artifact-key") === key);
const slotMap = (card) => Object.fromEntries([...card.querySelectorAll(".chart-root [data-parler-category]")].map((n) => [n.getAttribute("data-parler-category"), n.getAttribute("data-parler-series-slot")]));
const legendSlots = (card) => Object.fromEntries([...card.querySelectorAll("li.chart-legend-item")].map((li) => [li.getAttribute("data-parler-category"), li.getAttribute("data-parler-series-slot")]));

test("SC-1 / SC-4: Running and Down share a slot in both pies, Setup is its own; a late member appends without recolouring the others", async (t) => {
  const g = manifest(5, [member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2", colorShared: true })], { sharedCategories: shared(["Setup", "Down", "Running"]) });
  const { element, bubble } = await mount([rowOf([pie("c1", ["Setup", "Down", "Running"]), pie("c2", ["Running", "Down"])], [g])]);
  t.after(() => element.remove());
  const a = cardByKey(bubble, "chart:c1");
  const b = cardByKey(bubble, "chart:c2");
  assert.deepEqual(slotMap(a), { Setup: "0", Down: "1", Running: "2" });
  assert.deepEqual(slotMap(b), { Running: "2", Down: "1" });
  assert.deepEqual(legendSlots(a), { Setup: "0", Down: "1", Running: "2" });
  assert.deepEqual(legendSlots(b), { Running: "2", Down: "1" });
  assert.ok([...a.querySelectorAll("details.chart-notes dt")].some((n) => n.textContent === "Shared colours"));
  // A third member with a new category appends Idle at slot 3; A and B keep every slot.
  let state = element._chatState;
  state = reduceUiEvent(state, { type: "assistant.chart", requestId: "req-sc", chart: pie("c3", ["Idle", "Running"]) });
  state = reduceUiEvent(state, { type: "assistant.chartGroup", requestId: "req-sc", group: manifest(6, [...g.members, member("c", 2, { state: "ready", chartId: "c3", colorShared: true })], { sharedCategories: shared(["Setup", "Down", "Running", "Idle"]) }) });
  element._chatState = state;
  await settle(element);
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c1")), { Setup: "0", Down: "1", Running: "2" });
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c2")), { Running: "2", Down: "1" });
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c3")), { Idle: "3", Running: "2" });
  // Hiding is a pie focus here; the B chart keeps its slots regardless.
  a.querySelectorAll("button.chart-legend-toggle")[1].click();
  await settle(element);
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c2")), { Running: "2", Down: "1" });
});

test("SC-6: without a declared dimension, an unrelated group, or outside a group, colours are exactly today's per-chart slots", async (t) => {
  const plain = manifest(3, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "ready", chartId: "c2" })], { final: true });
  const { element, bubble } = await mount([rowOf([pie("c1", ["Setup", "Down", "Running"]), pie("c2", ["Running", "Down"]), pie("c9", ["Running", "Down"])], [plain])]);
  t.after(() => element.remove());
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c1")), { Setup: "0", Down: "1", Running: "2" });
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c2")), { Running: "0", Down: "1" });
  assert.deepEqual(slotMap(cardByKey(bubble, "chart:c9")), { Running: "0", Down: "1" });
  assert.equal(cardByKey(bubble, "chart:c2").colorKeys, null);
  assert.ok(![...cardByKey(bubble, "chart:c2").querySelectorAll("details.chart-notes dt")].some((n) => n.textContent === "Shared colours"));
});

test("SC-5: a capped member shows its note in the tooltip and data notes and keeps per-chart colours", async (t) => {
  const g = manifest(4, [member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2" })], { sharedCategories: shared(["Setup", "Down", "Running"]) });
  const { element, bubble } = await mount([rowOf([pie("c1", ["Setup", "Down", "Running"]), pie("c2", ["Running", "Down"])], [g])]);
  t.after(() => element.remove());
  const b = cardByKey(bubble, "chart:c2");
  assert.deepEqual(slotMap(b), { Running: "0", Down: "1" });
  const note = [...b.querySelectorAll("details.chart-notes dt")].find((n) => n.textContent === "Shared colours")?.nextElementSibling.textContent;
  assert.equal(note, "Colours not shared: more than 24 categories in this group");
  b.querySelector(".chart-plot-area").dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
  await settle(element);
  assert.equal(b.querySelector(".chart-tooltip-line").textContent, "Colours not shared: more than 24 categories in this group");
  assert.ok(b.querySelector(".chart-tooltip .chart-slot-swatch"), "the read-out carries the colour sample");
});

test("SC-8: focusing a category in one member dims the other categories in every shared member, and clears; nothing enters the view state or print", async (t) => {
  const g = manifest(5, [member("a", 0, { state: "ready", chartId: "c1", colorShared: true }), member("b", 1, { state: "ready", chartId: "c2", colorShared: true })], { sharedCategories: shared(["Setup", "Down", "Running"]) });
  const { element, bubble } = await mount([rowOf([pie("c1", ["Setup", "Down", "Running"]), pie("c2", ["Running", "Down"])], [g])]);
  t.after(() => element.remove());
  const a = cardByKey(bubble, "chart:c1");
  const b = cardByKey(bubble, "chart:c2");
  const dimmed = (card) => [...card.querySelectorAll(".chart-root [data-parler-category-dimmed]")].map((n) => n.getAttribute("data-parler-category")).sort();
  const runningToggle = [...a.querySelectorAll("li.chart-legend-item")].find((li) => li.getAttribute("data-parler-category") === "Running").querySelector("button");
  runningToggle.dispatchEvent(new dom.window.MouseEvent("mouseenter", { bubbles: false }));
  await settle(element);
  assert.deepEqual(dimmed(b), ["Down"], "B dims everything but Running");
  assert.deepEqual(dimmed(a), ["Down", "Setup"], "the originating card dims too");
  assert.equal([...b.querySelectorAll("li.chart-legend-item[data-parler-category-dimmed]")].length, 1);
  runningToggle.dispatchEvent(new dom.window.MouseEvent("mouseleave", { bubbles: false }));
  await settle(element);
  assert.deepEqual(dimmed(b), []);
  // Keyboard: the query item in B highlights Down in A; Escape clears.
  b.querySelector(".chart-plot-area").dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
  b.querySelector(".chart-plot-area").dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "ArrowRight", bubbles: true }));
  await settle(element);
  assert.deepEqual(dimmed(a), ["Running", "Setup"], "Down is the active item in B");
  b.querySelector(".chart-plot-area").dispatchEvent(new dom.window.KeyboardEvent("keydown", { key: "Escape", bubbles: true }));
  await settle(element);
  assert.deepEqual(dimmed(a), []);
  assert.deepEqual(a.viewState ?? null, null, "highlight never touches the view state");
  // Focus on a legend button also highlights; blur clears.
  runningToggle.dispatchEvent(new dom.window.FocusEvent("focus"));
  await settle(element);
  assert.deepEqual(dimmed(b), ["Down"]);
  runningToggle.dispatchEvent(new dom.window.FocusEvent("blur"));
  await settle(element);
  assert.deepEqual(dimmed(b), []);
});

test("SC-11: the history document produced by the real exporter hydrates into a group whose members share slots on screen and in print", async (t) => {
  const raw = JSON.parse(fs.readFileSync(new URL("./fixtures/chart-group-shared-colors.history.json", import.meta.url), "utf8"));
  const rows = parseHistoryRows(raw);
  const answer = rows.find((r) => r.kind === "assistant");
  assert.equal(answer.groups.length, 1);
  const keys = answer.groups[0].sharedCategories.keys;
  assert.deepEqual([...keys].sort(), ["Down", "Running", "Setup"], "the exporter kept the three shared keys");
  const expect = (labels) => Object.fromEntries(labels.map((l) => [l, String(keys.indexOf(l))]));
  const { element, bubble } = await mount([answer]);
  t.after(() => element.remove());
  const cards = [...bubble.querySelectorAll("section.chart-group parler-ui-chart")];
  assert.equal(cards.length, 2, "both members sit in the group card");
  assert.deepEqual(slotMap(cards[0]), expect(["Setup", "Down", "Running"]));
  assert.deepEqual(slotMap(cards[1]), expect(["Running", "Down"]));
  assert.equal(slotMap(cards[0]).Running, slotMap(cards[1]).Running, "Running shares one slot");
  assert.equal(slotMap(cards[0]).Down, slotMap(cards[1]).Down, "Down shares one slot");
  const { renderChartPrintCard } = await import("../components/parler-ui-chart.js");
  const { chartColorContextFor, groupArtifactsForLayout } = await import("./artifactPresentation.js");
  const layout = groupArtifactsForLayout(orderArtifactsForDisplay(answer.artifacts), answer.groups);
  const printed = answer.charts.map((chart) => renderChartPrintCard({ chart, width: 640, viewSummary: "", doc: document, colorKeys: chartColorContextFor(layout, `chart:${chart.chartId}`).colorKeys }));
  assert.deepEqual(Object.fromEntries([...printed[1].querySelectorAll("[data-parler-category]")].map((n) => [n.getAttribute("data-parler-category"), n.getAttribute("data-parler-series-slot")])), expect(["Running", "Down"]));
  assert.deepEqual([...printed[1].querySelectorAll("li.chart-legend-item")].map((li) => li.getAttribute("data-parler-series-slot")), [String(keys.indexOf("Running")), String(keys.indexOf("Down"))], "the print legend follows the same slots");
});
