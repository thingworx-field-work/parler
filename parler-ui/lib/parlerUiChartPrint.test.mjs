import assert from "node:assert/strict";
import test from "node:test";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><head></head><body></body></html>", {
  url: "http://localhost/",
  pretendToBeVisual: true,
});
for (const key of [
  "window", "document", "customElements", "HTMLElement", "HTMLImageElement", "Element", "Node",
  "Event", "CustomEvent", "MutationObserver", "CSSStyleSheet", "Document", "ShadowRoot",
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
const twoSeries = (id, title) => ({
  kind: "line",
  chartId: id,
  title,
  y_label: "Temp",
  series: [
    { name: "small", x: ISO, y: [1, 2] },
    { name: "big", x: ISO, y: [100, 200] },
  ],
});

async function mountThread(charts, tables) {
  const element = document.createElement("parler-ui");
  document.body.append(element);
  await element.updateComplete;
  const row = {
    kind: "assistant",
    requestId: "req-print",
    markdown: "answer text",
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
  return { element, row, cards, bubble: element.querySelector(".assistant-bubble") };
}

const yTickMax = (figure) => {
  const svg = figure.querySelector(".chart-root svg");
  const axes = [...svg.querySelectorAll("svg > g > g")].filter((g) => g.querySelector(".domain"));
  const yAxis = axes.find((g) => !g.getAttribute("transform")?.startsWith("translate(0,"));
  return Math.max(...[...yAxis.querySelectorAll(".tick text")].map((t) => Number(t.textContent.replace(/,/g, ""))));
};

test("printing rebuilds each chart with the full analysis view and states the on-screen deviations", async (t) => {
  const { element, row, cards, bubble } = await mountThread([twoSeries("c1", "Two series")], []);
  t.after(() => element.remove());
  const card = cards[0];
  card.querySelectorAll("button.chart-legend-toggle")[1].click();
  await element.updateComplete;
  await card.updateComplete;
  [...card.querySelectorAll(".chart-actions .chart-action")].find((b) => /Fit Y axis/.test(b.textContent)).click();
  await element.updateComplete;
  await card.updateComplete;
  assert.equal(card.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 1, "screen shows one series");
  const html = element.buildAssistantPrintHtml(row, 1, bubble);
  const printed = new JSDOM(html).window.document;
  const figures = printed.querySelectorAll(".print-root figure.chart-card");
  assert.equal(figures.length, 1);
  const figure = figures[0];
  assert.equal(figure.getAttribute("data-parler-artifact-key"), "chart:c1");
  assert.equal(figure.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 2, "print draws every series");
  assert.ok(yTickMax(figure) >= 200, "print uses the full Y domain");
  assert.equal(figure.querySelectorAll("[data-hidden], [data-parler-slice-dimmed], [data-parler-series-slot][opacity]").length, 0, "no hidden or dimmed marks");
  assert.equal(figure.querySelectorAll(".chart-legend-item").length, 2);
  assert.equal(figure.querySelector(".chart-legend-toggle"), null, "print legend has no controls");
  assert.equal(
    figure.querySelector(".chart-print-note").textContent,
    "Printed with the full analysis range and all series; on screen: 1 of 2 series hidden (big) · Y axis fitted to visible series."
  );
  assert.equal(printed.querySelector(".chart-view-note"), null, "the screen view note is not printed");
  assert.equal(printed.querySelector(".chart-actions"), null);
  assert.equal(figure.querySelector("details.chart-notes").hasAttribute("open"), true);
  assert.equal(figure.querySelector(".chart-card-title").textContent, "Two series");
  assert.ok(printed.querySelector(".print-root .md")?.textContent.includes("answer text"), "markdown still prints after the card");
  assert.equal(card.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 1, "screen DOM untouched");
  const print = printed.querySelector('.chart-root path[data-parler-palette-role="series"]');
  assert.match(print.getAttribute("stroke"), /^#/, "print palette applied to the rebuilt card");
});

test("a chart artifact whose card is not mounted still prints in its artifact position", async (t) => {
  const { element, row, cards, bubble } = await mountThread(
    [twoSeries("c1", "First"), twoSeries("c2", "Second")],
    [table("t1")]
  );
  t.after(() => element.remove());
  assert.equal(cards.length, 2);
  cards[1].remove();
  assert.equal(bubble.querySelectorAll("parler-ui-chart").length, 1, "the second card is absent from the bubble, as during expansion");
  const html = element.buildAssistantPrintHtml(row, 1, bubble);
  const printed = new JSDOM(html).window.document;
  const keys = [...printed.querySelectorAll(".print-root figure.chart-card")].map((f) => f.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(keys, ["chart:c1", "chart:c2"], "exactly the chart artifacts, in artifact order");
  const order = [...printed.querySelectorAll(".print-root [data-parler-artifact-key]")].map((el) => el.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(order, ["table:t1", "chart:c1", "chart:c2"], "table then charts, matching the display order");
  const second = printed.querySelector('figure.chart-card[data-parler-artifact-key="chart:c2"]');
  assert.equal(second.querySelector(".chart-card-title").textContent, "Second");
  assert.equal(second.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 2);
  assert.equal(second.querySelector(".chart-print-note"), null, "no deviation on the unmounted card");
  assert.equal(printed.querySelectorAll("parler-ui-chart").length, 0, "no live card elements remain in the print");
});

test("printing while a chart is expanded still prints every chart card in place and never the placeholder", async (t) => {
  const { element, row, cards, bubble } = await mountThread(
    [twoSeries("c1", "First"), twoSeries("c2", "Second")],
    [table("t1")]
  );
  t.after(() => element.remove());
  cards[1].querySelectorAll("button.chart-legend-toggle")[0].click();
  await element.updateComplete;
  await cards[1].updateComplete;
  [...cards[1].querySelectorAll(".chart-actions .chart-action")].find((b) => b.textContent === "Expand").click();
  await element.updateComplete;
  assert.ok(element.querySelector(".chart-expand-layer"), "the second chart is expanded");
  assert.equal(bubble.querySelector('.chart-expand-placeholder[data-parler-artifact-key="chart:c2"]')?.textContent.trim(), "Chart expanded");
  const html = element.buildAssistantPrintHtml(row, 1, bubble);
  const printed = new JSDOM(html).window.document;
  const order = [...printed.querySelectorAll(".print-root [data-parler-artifact-key]")].map((el) => el.getAttribute("data-parler-artifact-key"));
  assert.deepEqual(order, ["table:t1", "chart:c1", "chart:c2"]);
  const second = printed.querySelector('figure.chart-card[data-parler-artifact-key="chart:c2"]');
  assert.equal(second.querySelectorAll('.chart-root path[data-parler-palette-role="series"]').length, 2, "the expanded chart prints with every series");
  assert.match(second.querySelector(".chart-print-note").textContent, /1 of 2 series hidden/, "the on-screen view of the expanded chart is stated");
  assert.equal(printed.querySelector(".chart-expand-placeholder"), null);
  assert.equal(printed.body.textContent.includes("Chart expanded"), false, "no placeholder text in the print");
  assert.equal(printed.querySelector(".chart-expand-layer"), null);
  assert.ok(element.querySelector(".chart-expand-layer"), "printing leaves the expansion open");
});

test("host printing of a horizontal bar chart measures labels with the document's font on the detached card", async (t) => {
  const names = ["WWWW-MMMMMMMMMMMM-DEVICE-01", "MMMMMMMMMMMMMMMMMMMMMMMMMMMM", "gate-1"];
  const chart = { kind: "bar", chartId: "hb", orientation: "horizontal", title: "Wide", x_label: "Device", y_label: "Alarms", series: [{ name: "count", x: names, y: [1, 2, 3] }] };
  const { element, row, bubble } = await mountThread([chart], []);
  t.after(() => element.remove());
  const proto = dom.window.SVGElement.prototype;
  const original = proto.getComputedTextLength;
  proto.getComputedTextLength = function () { return [...(this.textContent ?? "")].length * 16; };
  try {
    const html = element.buildAssistantPrintHtml(row, 1, bubble);
    assert.equal(document.body.querySelectorAll(":scope > svg").length, 0, "no probe is left in the widget's document");
    const printed = new JSDOM(html).window.document;
    const svg = printed.querySelector('figure.chart-card[data-parler-artifact-key="chart:hb"] .chart-root svg');
    assert.ok(svg);
    const gutter = Number(/translate\(([\d.]+),/.exec(svg.querySelector("svg > g").getAttribute("transform"))[1]);
    const lines = [...svg.querySelectorAll("g.category-axis .tick text tspan")].map((tspan) => tspan.textContent);
    assert.ok(lines.length >= 3);
    for (const line of lines) assert.ok([...line].length * 16 <= gutter - 13 + 1e-6, `printed line "${line}" fits the ${gutter}px gutter`);
    assert.ok(lines.some((l) => l.endsWith("…")));
    assert.equal(printed.querySelector(".chart-plot-area").getAttribute("data-orientation"), "horizontal");
  } finally {
    proto.getComputedTextLength = original;
  }
});

test("LS-5: the host prints with the card's outer width, not the possibly narrowed plot host", async (t) => {
  const { element, row, cards, bubble } = await mountThread([{ kind: "bar", chartId: "b1", title: "Two", series: [{ name: "s", x: ["a", "b"], y: [10, 20] }] }], []);
  t.after(() => element.remove());
  const card = cards[0];
  card.querySelector("figure.chart-card").getBoundingClientRect = () => ({ left: 0, top: 0, width: 1000, height: 300 });
  card.querySelector(".chart-root").getBoundingClientRect = () => ({ left: 378, top: 0, width: 244, height: 240 });
  const html = element.buildAssistantPrintHtml(row, 1, bubble);
  const printed = new JSDOM(html).window.document;
  const svg = printed.querySelector('figure.chart-card[data-parler-artifact-key="chart:b1"] .chart-root svg');
  const draw = await import("../components/chart-draw.js");
  const theme = (await import("../components/chart-theme.js")).DEFAULT_CHART_RENDER_THEME;
  const labelRoom = draw.rotatedCategoryAxisBottom(["a", "b"], theme, (text) => draw.estimateTextWidth(text, theme.style.type.tickSize)).extra;
  assert.equal(svg.getAttribute("viewBox"), `0 0 244 ${240 + labelRoom}`, "the band cap applies at the card width of 1000; the height adds the room for the rotated labels");
  const lineRow = { ...row, requestId: "req-line", charts: [{ kind: "line", chartId: "l1", series: [{ name: "s", x: ["2026-09-12T00:00:00Z", "2026-09-12T00:01:00Z"], y: [1, 2] }] }], tables: [] };
  const { element: e2, row: r2, cards: c2, bubble: b2 } = await mountThread(lineRow.charts, []);
  t.after(() => e2.remove());
  c2[0].querySelector("figure.chart-card").getBoundingClientRect = () => ({ left: 0, top: 0, width: 1000, height: 300 });
  c2[0].querySelector(".chart-root").getBoundingClientRect = () => ({ left: 0, top: 0, width: 640, height: 240 });
  const printedLine = new JSDOM(e2.buildAssistantPrintHtml(r2, 1, b2)).window.document;
  assert.equal(printedLine.querySelector(".chart-root svg").getAttribute("viewBox"), "0 0 1000 240", "a full-width chart prints at the card width even when the host measured differently");
});
