import assert from "node:assert/strict";
import test from "node:test";

import { asChartGroupManifest, chartGroupRejection, wireToUiEvent } from "./wireAdapter.js";
import { initialChatState, reduceUiEvent, startUserTurn } from "./chatSession.js";
import { buildArtifactsFromLegacyBuckets, chartGroupMemberStateText, chartGroupSummaryText, groupArtifactsForLayout, orderArtifactsForDisplay } from "./artifactPresentation.js";
import { parseHistoryRows } from "./historyHydrate.js";

const member = (key, order, over = {}) => ({ key, order, name: key.toUpperCase(), expectedType: "chart", state: "pending", ...over });
const manifest = (revision, members, over = {}) => {
  const counts = { ready: 0, noData: 0, error: 0, cancelled: 0 };
  for (const m of members) {
    if (m.state === "ready") counts.ready += 1;
    if (m.state === "no-data") counts.noData += 1;
    if (m.state === "error") counts.error += 1;
    if (m.state === "cancelled") counts.cancelled += 1;
  }
  const fin = over.final ?? false;
  return { groupId: "g1", revision, title: "Ovens", layout: "auto", final: fin, members, summary: { expected: members.length, ...counts, final: fin }, ...over };
};
const pie = (chartId, sourceCacheId) => ({ kind: "pie", chartId, series: [{ name: "s", x: ["a", "b"], y: [1, 2] }], source: { sourceCacheId } });

function silenced(fn) {
  const original = console.warn;
  const lines = [];
  console.warn = (m) => lines.push(String(m));
  try {
    return { result: fn(), lines };
  } finally {
    console.warn = original;
  }
}

test("GR-5 (adapter): a valid manifest is accepted; every field rule rejects with CHART_GROUP_INVALID and only the group is lost", () => {
  const ok = manifest(2, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "no-data", code: "EMPTY_AFTER_FILTER" })]);
  assert.equal(chartGroupRejection(ok), null);
  assert.equal(asChartGroupManifest(ok), ok);
  const variants = {
    noGroupId: { ...ok, groupId: " " },
    revisionZero: { ...ok, revision: 0 },
    revisionFloat: { ...ok, revision: 1.5 },
    badLayout: { ...ok, layout: "masonry" },
    finalNotBoolean: { ...ok, final: "yes" },
    oneMember: manifest(1, [member("a", 0)]),
    sevenMembers: manifest(1, Array.from({ length: 7 }, (_v, i) => member(`k${i}`, i))),
    duplicateKey: manifest(1, [member("a", 0), member("a", 1)]),
    orderGap: manifest(1, [member("a", 0), member("b", 2)]),
    badState: manifest(1, [member("a", 0, { state: "done" }), member("b", 1)]),
    readyWithoutChartId: { ...manifest(1, [member("a", 0, { state: "ready" }), member("b", 1)]), summary: { expected: 2, ready: 1, noData: 0, error: 0, cancelled: 0, final: false } },
    chartIdOnPending: manifest(1, [member("a", 0, { chartId: "c1" }), member("b", 1)]),
    duplicateChartId: manifest(1, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "ready", chartId: "c1" })]),
    errorWithoutCode: manifest(1, [member("a", 0, { state: "error" }), member("b", 1)]),
    summaryWrong: { ...ok, summary: { ...ok.summary, ready: 2 } },
    expectedWrong: { ...ok, summary: { ...ok.summary, expected: 3 } },
    summaryFinalMismatch: { ...ok, summary: { ...ok.summary, final: true } },
    expectedTypeTable: manifest(1, [member("a", 0, { expectedType: "table" }), member("b", 1)]),
    notObject: "g1",
  };
  const { lines } = silenced(() => {
    for (const [name, v] of Object.entries(variants)) {
      assert.notEqual(chartGroupRejection(v), null, `${name} has a reason`);
      assert.equal(asChartGroupManifest(v), null, `${name} is rejected`);
    }
  });
  assert.equal(lines.filter((l) => l.includes("CHART_GROUP_INVALID")).length, Object.keys(variants).length);
  const evt = wireToUiEvent({ type: "chart_group", request_id: "r1", group: ok });
  assert.deepEqual(evt, { type: "assistant.chartGroup", requestId: "r1", group: ok });
  assert.equal(silenced(() => wireToUiEvent({ type: "chart_group", request_id: "r1", group: variants.orderGap })).result, null);
  assert.equal(wireToUiEvent({ type: "chart_grouping", request_id: "r1" }), null, "an unknown frame type stays ignored");
});

test("GR-4 (reducer): only a higher revision is applied, nothing after final, and cancelled requests ignore group frames", () => {
  let state = startUserTurn(initialChatState, "charts please", "r1");
  const row = () => state.rows.find((r) => r.kind === "assistant" && r.requestId === "r1");
  const rev1 = manifest(1, [member("a", 0), member("b", 1)]);
  const rev2 = manifest(2, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1)]);
  const rev3 = manifest(3, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "error", code: "MEMBER_NOT_PRODUCED" })], { final: true });
  state = reduceUiEvent(state, { type: "assistant.chartGroup", requestId: "r1", group: rev1 });
  assert.equal(row().groups.length, 1);
  assert.equal(row().groups[0].revision, 1);
  const afterRev2 = reduceUiEvent(state, { type: "assistant.chartGroup", requestId: "r1", group: rev2 });
  assert.equal(afterRev2.rows.find((r) => r.requestId === "r1").groups[0].revision, 2);
  const dup = reduceUiEvent(afterRev2, { type: "assistant.chartGroup", requestId: "r1", group: rev2 });
  assert.equal(dup, afterRev2, "a duplicate revision returns the prior state");
  const stale = reduceUiEvent(afterRev2, { type: "assistant.chartGroup", requestId: "r1", group: rev1 });
  assert.equal(stale, afterRev2, "a lower revision is ignored");
  const fin = reduceUiEvent(afterRev2, { type: "assistant.chartGroup", requestId: "r1", group: rev3 });
  assert.equal(fin.rows.find((r) => r.requestId === "r1").groups[0].final, true);
  const late = reduceUiEvent(fin, { type: "assistant.chartGroup", requestId: "r1", group: manifest(5, rev3.members, { final: true }) });
  assert.equal(late, fin, "nothing after final is applied");
  // A frame for a cancelled request is ignored like the other assistant frames.
  let cancelled = startUserTurn(initialChatState, "q", "r2");
  cancelled = reduceUiEvent(cancelled, { type: "session.cancelled", requestId: "r2", conversationId: "c" });
  const ignored = reduceUiEvent(cancelled, { type: "assistant.chartGroup", requestId: "r2", group: rev1 });
  assert.equal(ignored, cancelled);
  // The group frame clears the activity line like a chart frame.
  let busy = startUserTurn(initialChatState, "q", "r3");
  busy = reduceUiEvent(busy, { type: "assistant.activity", requestId: "r3", text: "working" });
  busy = reduceUiEvent(busy, { type: "assistant.chartGroup", requestId: "r3", group: rev1 });
  assert.equal(busy.rows.find((r) => r.requestId === "r3").activity, null);
});

test("GR-3 (projection): the same slots result whether charts precede or follow the manifest; unreferenced charts keep the C3a rules", () => {
  const charts = [pie("c1", "t1"), pie("c2", "t1"), pie("c3", "t1")];
  const ordered = orderArtifactsForDisplay(buildArtifactsFromLegacyBuckets(charts, [{ kind: "entity-list", columns: [{ key: "a", label: "A", baseType: "STRING" }], rows: [{ a: "x" }], shownRows: 1, totalRows: 1, cacheId: "t1", sourceCacheId: null, exportStatus: "none" }]));
  const group = manifest(3, [member("a", 0, { state: "ready", chartId: "c2" }), member("b", 1, { state: "ready", chartId: "c1" }), member("c", 2, { state: "no-data", code: "EMPTY_AFTER_FILTER" })], { final: true });
  const slots = groupArtifactsForLayout(ordered, [group]);
  assert.deepEqual(slots.map((s) => s.type), ["artifact", "chart-group", "artifact"], "the group sits at the first arrived member's position; c3 stays a single card outside");
  const g = slots[1];
  assert.equal(g.key, "group:g1");
  assert.deepEqual(g.slots.map((s) => s.artifact?.key ?? null), ["chart:c2", "chart:c1", null], "slots follow the declared order, not arrival order");
  assert.equal(slots[2].artifact.key, "chart:c3");
  // Manifest before any chart: the group slot goes to the end with waiting placeholders.
  const pendingOnly = groupArtifactsForLayout(ordered, [manifest(1, [member("a", 0), member("b", 1)])]);
  assert.deepEqual(pendingOnly.map((s) => s.type), ["artifact", "chart-grid", "chart-group"]);
  assert.equal(pendingOnly[1].charts.length, 3, "unreferenced charts still grid");
  assert.deepEqual(pendingOnly[2].slots.map((s) => s.artifact), [null, null]);
  // A ready member whose chart has not arrived reads as loading; other states read their text.
  assert.equal(chartGroupMemberStateText(member("a", 0), false), "Waiting for A");
  assert.equal(chartGroupMemberStateText(member("a", 0, { state: "ready", chartId: "c9" }), false), "Loading A");
  assert.equal(chartGroupMemberStateText(member("a", 0, { state: "ready", chartId: "c9" }), true), "");
  assert.equal(chartGroupMemberStateText(member("a", 0, { state: "no-data", code: "EMPTY_AFTER_FILTER" }), false), "A: no data (EMPTY_AFTER_FILTER)");
  assert.equal(chartGroupMemberStateText(member("a", 0, { state: "error", code: "MEMBER_NOT_PRODUCED", message: "never built" }), false), "A: failed (MEMBER_NOT_PRODUCED) — never built");
  assert.equal(chartGroupMemberStateText(member("a", 0, { state: "cancelled" }), false), "A: cancelled");
  assert.equal(chartGroupSummaryText(group), "2 / 3 ready · 1 no data");
  assert.equal(chartGroupSummaryText(manifest(1, [member("a", 0), member("b", 1)])), "0 / 2 ready · in progress");
  assert.deepEqual(groupArtifactsForLayout(ordered), groupArtifactsForLayout(ordered, []), "no manifest: C3a behaviour unchanged");
});

test("GR-6 (hydrate): history groups[] pass the same validation; an invalid one is dropped and its charts stay single cards", () => {
  const good = manifest(3, [member("a", 0, { state: "ready", chartId: "c1" }), member("b", 1, { state: "error", code: "TURN_INCOMPLETE" })], { final: true });
  const exported = {
    format: "ai-parler-history-v1",
    rows: [
      { kind: "assistant", assistantMessageId: "am-1", markdown: "x", charts: [pie("c1", "t1")], tables: [], groups: [good, { ...good, groupId: "g1" }], activity: null },
      { kind: "assistant", assistantMessageId: "am-2", markdown: "y", charts: [pie("c1", "t1")], tables: [], groups: [{ ...good, summary: { ...good.summary, ready: 5 } }], activity: null },
      { kind: "assistant", assistantMessageId: "am-3", markdown: "z", charts: [pie("c1", "t1")], tables: [], activity: null },
    ],
  };
  const { result: rows, lines } = silenced(() => parseHistoryRows(structuredClone(exported)));
  assert.equal(rows[0].groups.length, 1, "a repeated groupId hydrates once");
  assert.equal(rows[0].groups[0].revision, 3);
  assert.equal(rows[1].groups, undefined, "the invalid group is dropped");
  assert.equal(rows[1].charts.length, 1, "its chart survives");
  assert.equal(rows[2].groups, undefined, "old rows have no groups");
  assert.equal(lines.filter((l) => l.includes("CHART_GROUP_INVALID")).length, 1);
});
