import assert from "node:assert/strict";
import { test } from "node:test";
import { buildAssistantTurnInfoEntries } from "./assistantTurnInfo.js";

test("buildAssistantTurnInfoEntries includes conversation and ids", () => {
  const row = {
    kind: "assistant",
    requestId: "r1",
    assistantMessageId: "am-1",
    completedAt: "2026-05-21T00:00:00Z",
    markdown: "x",
    charts: [],
    tables: [],
  };
  const lines = buildAssistantTurnInfoEntries(row, "gw-1");
  const labels = lines.map((l) => l.label);
  assert.ok(labels.includes("Conversation"));
  assert.ok(labels.includes("Request"));
  assert.ok(labels.includes("Assistant message"));
  assert.ok(labels.includes("Completed"));
});

test("buildAssistantTurnInfoEntries adds chart and table counts", () => {
  const row = {
    kind: "assistant",
    requestId: "r",
    assistantMessageId: "a",
    markdown: "m",
    charts: [{ kind: "line", series: [] }],
    tables: [{ kind: "table", columns: [], rows: [], shownRows: 1, totalRows: 1 }],
  };
  const lines = buildAssistantTurnInfoEntries(row, "");
  const charts = lines.find((l) => l.label === "Charts");
  const tables = lines.find((l) => l.label === "Tables");
  assert.equal(charts?.value, "1");
  assert.equal(tables?.value, "1");
});

test("buildAssistantTurnInfoEntries includes token usage when llmUsage present", () => {
  const row = {
    kind: "assistant",
    requestId: "r1",
    assistantMessageId: "am-1",
    markdown: "x",
    charts: [],
    tables: [],
    llmUsage: {
      inputTokens: 100,
      outputTokens: 5,
      cacheReadInputTokens: 20,
      model: "claude-3",
    },
  };
  const lines = buildAssistantTurnInfoEntries(row, "");
  const labels = lines.map((l) => l.label);
  assert.ok(labels.includes("Input tokens"));
  assert.ok(labels.includes("Output tokens"));
  assert.ok(labels.includes("Cache read input"));
  assert.ok(labels.includes("Model"));
  assert.ok(lines.some((l) => l.label === "Input tokens" && l.value === "100"));
});

test("buildAssistantTurnInfoEntries includes repetitionBlockedCount when present", () => {
  const row = {
    kind: "assistant",
    requestId: "r1",
    markdown: "x",
    charts: [],
    tables: [],
    llmUsage: {
      promptTokens: 1,
      repetitionBlockedCount: 3,
    },
  };
  const lines = buildAssistantTurnInfoEntries(row, "");
  const rep = lines.find((l) => l.label === "Repetition blocked");
  assert.ok(rep);
  assert.equal(rep.value, "3");
});

test("buildAssistantTurnInfoEntries includes parlerChartWireEmittedCount when present", () => {
  const row = {
    kind: "assistant",
    requestId: "r1",
    markdown: "x",
    charts: [],
    tables: [],
    llmUsage: {
      promptTokens: 1,
      parlerChartWireEmittedCount: 2,
    },
  };
  const lines = buildAssistantTurnInfoEntries(row, "");
  const cw = lines.find((l) => l.label === "Chart wires emitted");
  assert.ok(cw);
  assert.equal(cw.value, "2");
});

