import assert from "node:assert/strict";
import { test } from "node:test";
import { llmUsageFromWireRecord } from "./llmUsageWire.js";

test("llmUsageFromWireRecord drops unknown keys", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 3,
    systemPrompt: "x",
    nested: { a: 1 },
  });
  assert.ok(u);
  assert.equal(u.promptTokens, 3);
  assert.equal("systemPrompt" in u, false);
});

test("llmUsageFromWireRecord returns undefined for empty", () => {
  assert.equal(llmUsageFromWireRecord({}), undefined);
  assert.equal(llmUsageFromWireRecord(null), undefined);
});

test("llmUsageFromWireRecord keeps chartRescueAttempted boolean", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 1,
    chartRescueAttempted: true,
  });
  assert.ok(u);
  assert.equal(u.chartRescueAttempted, true);
});

test("llmUsageFromWireRecord keeps repetitionBlockedCount numeric", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 1,
    repetitionBlockedCount: 2,
  });
  assert.ok(u);
  assert.equal(u.repetitionBlockedCount, 2);
});

test("llmUsageFromWireRecord keeps parlerChartWireEmittedCount numeric", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 1,
    parlerChartWireEmittedCount: 2,
  });
  assert.ok(u);
  assert.equal(u.parlerChartWireEmittedCount, 2);
});

test("llmUsageFromWireRecord keeps chartExpectedButMissing boolean", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 1,
    chartExpectedButMissing: true,
  });
  assert.ok(u);
  assert.equal(u.chartExpectedButMissing, true);
});

test("llmUsageFromWireRecord keeps reasoningTokensTotal numeric", () => {
  const u = llmUsageFromWireRecord({
    completionTokensTotal: 2048,
    reasoningTokensTotal: 2048,
  });
  assert.ok(u);
  assert.equal(u.completionTokensTotal, 2048);
  assert.equal(u.reasoningTokensTotal, 2048);
});

test("llmUsageFromWireRecord tolerates absent reasoningTokensTotal", () => {
  const u = llmUsageFromWireRecord({ promptTokens: 1 });
  assert.ok(u);
  assert.equal("reasoningTokensTotal" in u, false);
});

test("llmUsageFromWireRecord keeps presentation phase telemetry", () => {
  const u = llmUsageFromWireRecord({
    promptTokens: 1,
    presentationPhaseEntered: true,
    presentationActionsRequested: 2,
    presentationActionsExecuted: 2,
    presentationActionsBlocked: 1,
  });
  assert.ok(u);
  assert.equal(u.presentationPhaseEntered, true);
  assert.equal(u.presentationActionsRequested, 2);
  assert.equal(u.presentationActionsExecuted, 2);
  assert.equal(u.presentationActionsBlocked, 1);
});
