import assert from "node:assert/strict";
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
const { reduceUiEvent } = await import("./chatSession.js");
await import("../parler-ui.js");

const ISO = ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"];
const pie = (chartId, sourceCacheId, title) => ({ kind: "line", chartId, title, series: [{ name: "small", x: ISO, y: [1, 2] }, { name: "big", x: ISO, y: [100, 200] }], source: { sourceCacheId } });
const table = (cacheId) => ({ kind: "entity-list", columns: [{ key: "a", label: "A", baseType: "STRING" }], rows: [{ a: "x" }], shownRows: 1, totalRows: 1, cacheId, sourceCacheId: null, exportStatus: "none" });
const member = (key, order, over = {}) => ({ key, order, name: key === "temp" ? "Temperature" : key === "pres" ? "Pressure" : key, expectedType: "chart", state: "pending", ...over });
const manifest = (revision, members, over = {}) => {
  const counts = { ready: 0, noData: 0, error: 0, cancelled: 0 };
  for (const m of members) {
    if (m.state === "ready") counts.ready += 1;
    if (m.state === "no-data") counts.noData += 1;
    if (m.state === "error") counts.error += 1;
    if (m.state === "cancelled") counts.cancelled += 1;
  }
  const fin = over.final ?? false;
  return { groupId: "g1", revision, title: "Oven overview", layout: "auto", final: fin, members, summary: { expected: members.length, ...counts, final: fin }, ...over };
};

async function settle(element) {
  await element.updateComplete;
  for (const card of element.querySelectorAll("parler-ui-chart")) { await card.updateComplete; await card.updateComplete; }
  await element.updateComplete;
}

async function mountThread(charts, tables, groups, { active = false } = {}) {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  const row = { kind: "assistant", requestId: "req-group", markdown: "answer text", charts, tables, artifacts: buildArtifactsFromLegacyBuckets(charts, tables), activity: null, ...(groups ? { groups } : {}) };
  element._chatState = { ...element._chatState, rows: [{ kind: "user", text: "q" }, row], busy: active, activeRequestId: active ? "req-group" : null };
  await settle(element);
  return { element, row, bubble: element.querySelector(".assistant-bubble") };
}

const slotStates = (root) => [...root.querySelectorAll(".chart-group-slot")].map((s) => [s.getAttribute("data-parler-chart-group-member"), s.getAttribute("data-parler-chart-group-state"), s.querySelector("parler-ui-chart")?.getAttribute("data-parler-artifact-key") ?? s.querySelector(".chart-group-note")?.textContent.trim()]);

test("GR-7: the group card shows the title, ordered slots with charts or state text, the summary, and leaves unreferenced charts outside", async (t) => {
  const group = manifest(4, [member("temp", 0, { state: "ready", chartId: "c1" }), member("pres", 1, { state: "no-data", code: "EMPTY_AFTER_FILTER" }), member("flow", 2, { state: "error", code: "MEMBER_NOT_PRODUCED" })], { final: true });
  const { element, bubble } = await mountThread([pie("c1", "t1", "Temp"), pie("c9", "t1", "Other")], [table("t1")], [group]);
  t.after(() => element.remove());
  const card = bubble.querySelector("section.chart-group");
  assert.ok(card);
  assert.equal(card.getAttribute("data-parler-chart-group"), "g1");
  assert.equal(card.getAttribute("data-parler-chart-group-final"), "true");
  assert.equal(card.querySelector(".chart-group-title").textContent, "Oven overview");
  assert.deepEqual(slotStates(card), [["temp", "ready", "chart:c1"], ["pres", "no-data", "Pressure: no data (EMPTY_AFTER_FILTER)"], ["flow", "error", "flow: failed (MEMBER_NOT_PRODUCED)"]]);
  assert.match(card.querySelector(".chart-group-summary").textContent, /1 \/ 3 ready · 1 no data · 1 error/);
  assert.ok(!card.querySelector(".chart-group-summary").textContent.includes("interrupted"));
  assert.ok(card.querySelector(".chart-group-slots").classList.contains("chart-grid"), "auto layout reuses the C3a grid rule");
  const outside = [...bubble.querySelectorAll("parler-ui-chart")].map((c) => c.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(outside, ["chart:c1", "chart:c9"], "each chart is rendered once");
  assert.equal(bubble.querySelector("section.chart-group parler-ui-chart[data-parler-artifact-key='chart:c9']"), null, "the unreferenced chart is outside the group");
  assert.equal(card.previousElementSibling?.classList.contains("parler-data-table-wrap"), true, "the parent table stays before the group");
});

test("GR-3 / GR-8: manifest before charts shows waiting slots, a ready-but-absent chart reads loading, the view state survives the move, and an unfinished group after the turn ends reads interrupted", async (t) => {
  const pending = manifest(1, [member("temp", 0), member("pres", 1)]);
  const { element, bubble } = await mountThread([], [], [pending], { active: true });
  t.after(() => element.remove());
  assert.deepEqual(slotStates(bubble), [["temp", "pending", "Waiting for Temperature"], ["pres", "pending", "Waiting for Pressure"]]);
  assert.match(bubble.querySelector(".chart-group-summary").textContent, /0 \/ 2 ready · in progress/);
  // The chart arrives, then a ready revision for it; the other member's ready revision precedes its chart.
  let state = element._chatState;
  state = reduceUiEvent(state, { type: "assistant.chart", requestId: "req-group", chart: pie("c1", "t1", "Temp") });
  element._chatState = state;
  await settle(element);
  const card = bubble.querySelector("parler-ui-chart[data-parler-artifact-key='chart:c1']");
  assert.ok(card, "the chart shows as a single card before the manifest references it");
  card.querySelectorAll("button.chart-legend-toggle")[1].click();
  await settle(element);
  state = reduceUiEvent(element._chatState, { type: "assistant.chartGroup", requestId: "req-group", group: manifest(2, [member("temp", 0, { state: "ready", chartId: "c1" }), member("pres", 1, { state: "ready", chartId: "c2" })]) });
  element._chatState = state;
  await settle(element);
  assert.deepEqual(slotStates(bubble), [["temp", "ready", "chart:c1"], ["pres", "ready", "Loading Pressure"]]);
  const moved = bubble.querySelector("section.chart-group parler-ui-chart[data-parler-artifact-key='chart:c1']");
  assert.ok(moved, "the chart moved into its slot");
  assert.equal(bubble.querySelectorAll("parler-ui-chart").length, 1, "no duplicate card");
  assert.deepEqual(moved.viewState?.hiddenSeriesKeys ?? [], [1], "the hidden series survives the move into the slot");
  // The turn ends without a final revision: the summary states the interruption and keeps the member states.
  element._chatState = { ...element._chatState, busy: false, activeRequestId: null };
  await settle(element);
  assert.match(bubble.querySelector(".chart-group-summary").textContent, /Transport interrupted/);
  assert.deepEqual(slotStates(bubble).map((s) => s[1]), ["ready", "ready"], "member states are not invented");
});

test("GR-7 (print): the print clone keeps the group title, the member chart in place and the state text of unready members", async (t) => {
  const group = manifest(3, [member("temp", 0, { state: "ready", chartId: "c1" }), member("pres", 1, { state: "cancelled" })], { final: true });
  const { element, bubble } = await mountThread([pie("c1", "t1", "Temp")], [table("t1")], [group]);
  t.after(() => element.remove());
  const { assembleChartCardsForPrint } = await import("./assistantResponseActions.js");
  const { renderChartPrintCard } = await import("../components/parler-ui-chart.js");
  const { orderArtifactsForDisplay } = await import("./artifactPresentation.js");
  const clone = bubble.cloneNode(true);
  const artifacts = orderArtifactsForDisplay(element._chatState.rows[1].artifacts);
  const count = assembleChartCardsForPrint(clone, artifacts, (artifact) => renderChartPrintCard({ chart: artifact.chart, width: 640, viewSummary: "", doc: document }));
  assert.equal(count, 1);
  const printed = clone.querySelector("section.chart-group");
  assert.ok(printed);
  assert.equal(printed.querySelector(".chart-group-title").textContent, "Oven overview");
  assert.ok(printed.querySelector(".chart-group-slot[data-parler-chart-group-member='temp'] figure.chart-card"), "the member chart prints in its slot");
  assert.equal(printed.querySelector(".chart-group-slot[data-parler-chart-group-member='pres'] .chart-group-note").textContent.trim(), "Pressure: cancelled");
  assert.equal(printed.querySelector("parler-ui-chart"), null);
});
