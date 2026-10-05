#!/usr/bin/env node
/**
 * Aggregate `LLM_TOOL_SCHEMA_USAGE` lines (Application Log paste or file tail).
 *
 * Usage:
 *   rg 'LLM_TOOL_SCHEMA_USAGE' app.log | node scripts/report-llm-tool-schema-usage.mjs
 *   node scripts/report-llm-tool-schema-usage.mjs < excerpt.txt
 *
 * **Smoke / dev helper only.** It aggregates per-line `schema` / `called` / `idle` hit counts per tool name.
 * Real decisions about lazy tool schema (PR2) require production or pilot logs over weeks — see
 * `docs/agent/llm-token-budget.md` §2. The five baseline
 * prompts are useful to prove the telemetry pipeline works, not to choose which tools to hide from the model.
 */

import { readFileSync } from "node:fs";

const input = readFileSync(0, "utf8");
const lines = input.split(/\r?\n/).filter(Boolean);

/** @type {Map<string, { called: number, idle: number, schema: number }>} */
const stats = new Map();

function bump(tool, field) {
  let s = stats.get(tool);
  if (!s) {
    s = { called: 0, idle: 0, schema: 0 };
    stats.set(tool, s);
  }
  s[field]++;
}

function splitTools(raw) {
  if (!raw) return [];
  return raw.split(",").map((t) => t.trim()).filter(Boolean);
}

const reSchema = /\bschemaTools=([^\s]*)/;
const reCalled = /\bcalledTools=([^\s]*)/;
const reIdle = /\bidleTools=([^\s]*)/;

for (const line of lines) {
  if (!line.includes("LLM_TOOL_SCHEMA_USAGE")) continue;
  const ms = line.match(reSchema)?.[1] ?? "";
  const mc = line.match(reCalled)?.[1] ?? "";
  const mi = line.match(reIdle)?.[1] ?? "";
  for (const t of splitTools(ms)) bump(t, "schema");
  for (const t of splitTools(mc)) bump(t, "called");
  for (const t of splitTools(mi)) bump(t, "idle");
}

const rows = [...stats.entries()]
  .map(([tool, s]) => ({ tool, ...s }))
  .sort((a, b) => a.tool.localeCompare(b.tool));

console.log("tool\tschemaLines\tcalledLines\tidleLines");
for (const r of rows) {
  console.log([r.tool, r.schema, r.called, r.idle].join("\t"));
}
