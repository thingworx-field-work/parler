import assert from "node:assert/strict";
import { test } from "node:test";
import { initialChatState, reduceUiEvent, startUserTurn } from "./chatSession.js";
import { wireToUiEvent } from "./wireAdapter.js";

test("reduceUiEvent preserves cross-type artifact append order", () => {
  let state = startUserTurn(initialChatState, "charts please", "req-artifacts");
  state = reduceUiEvent(state, {
    type: "assistant.table",
    requestId: "req-artifacts",
    table: {
      kind: "entity-list",
      columns: [{ key: "a", label: "A", baseType: "STRING" }],
      rows: [],
      shownRows: 0,
      totalRows: 0,
      cacheId: "t1",
      sourceCacheId: null,
      exportStatus: "none",
      exportMessage: null,
      exportFile: null,
      exportRepository: null,
      exportDownloadUrl: null,
    },
  });
  state = reduceUiEvent(state, {
    type: "assistant.chart",
    requestId: "req-artifacts",
    chart: {
      kind: "pie",
      series: [{ name: "s", x: ["a"], y: [1] }],
      source: { sourceCacheId: "t1" },
    },
  });
  const row = state.rows.find(
    (r) => r.kind === "assistant" && r.requestId === "req-artifacts"
  );
  assert.ok(row && row.kind === "assistant");
  assert.equal(row.artifacts?.length, 2);
  assert.equal(row.artifacts?.[0]?.type, "table");
  assert.equal(row.artifacts?.[1]?.type, "chart");
  assert.equal(row.artifacts?.[0]?.seq, 0);
  assert.equal(row.artifacts?.[1]?.seq, 1);
});

test("invalid live chart frames between valid ones are dropped without disturbing the answer", () => {
  const rid = "req-interleave";
  const frames = [
    { type: "content.delta", request_id: rid, delta: "Before. " },
    { type: "chart", request_id: rid, chart: { kind: "bar", chartId: "c1", series: [{ name: "s", x: ["a"], y: [1] }] } },
    { type: "chart", request_id: rid, chart: { kind: "bar", chartId: { toString: null }, series: [{ name: "s", x: ["a"], y: [null] }] } },
    { type: "content.delta", request_id: rid, delta: "Middle. " },
    { type: "chart", request_id: rid, chart: { kind: "line", chartId: "c2", series: [{ name: "s", x: ["1", "2"], y: [5, "5"] }] } },
    {
      type: "table",
      request_id: rid,
      table: {
        kind: "entity-list",
        columns: [{ key: "a", label: "A", baseType: "STRING" }],
        rows: [],
        exportStatus: "none",
      },
    },
    { type: "chart", request_id: rid, chart: { kind: "pie", chartId: "c3", series: [{ name: "s", x: ["a", "b"], y: [0, 2] }] } },
    { type: "content.delta", request_id: rid, delta: "After." },
  ];
  const original = console.warn;
  const warnings = [];
  console.warn = (msg) => warnings.push(String(msg));
  let state = startUserTurn(initialChatState, "interleave", rid);
  let dropped = 0;
  try {
    for (const frame of frames) {
      const evt = wireToUiEvent(frame);
      if (!evt) {
        dropped += 1;
        continue;
      }
      state = reduceUiEvent(state, evt);
    }
  } finally {
    console.warn = original;
  }
  assert.equal(dropped, 2, "exactly the two invalid chart frames are dropped");
  assert.equal(warnings.length, 2);
  const row = state.rows.find((r) => r.kind === "assistant" && r.requestId === rid);
  assert.ok(row && row.kind === "assistant");
  assert.equal(row.markdown, "Before. Middle. After.");
  assert.deepEqual(row.charts.map((c) => c.chartId), ["c1", "c3"]);
  assert.equal(row.tables.length, 1);
  assert.deepEqual(row.artifacts.map((a) => a.type), ["chart", "table", "chart"]);
  assert.deepEqual(row.artifacts.map((a) => a.seq), [0, 1, 2]);
});
