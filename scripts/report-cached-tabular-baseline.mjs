#!/usr/bin/env node
/**
 * Prints repository-local cached-tabular golden metrics for `docs/agent/cached_tabular_golden.md` §Repo baseline
 * (counts only — not platform pass rates). Verifies:
 * - `**Count (current):**` scenarios / positive / negative match table rows (`| G-xx |`, `| N-xx |`);
 * - `**Fixture files:**` count matches `golden_cached_tabular/*.json` on disk.
 * Run from repo root: `node scripts/report-cached-tabular-baseline.mjs`
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const mdPath = path.join(root, "docs", "agent", "cached_tabular_golden.md");
const goldenDir = path.join(root, "docs", "agent", "golden_cached_tabular");

const md = fs.readFileSync(mdPath, "utf8");
let scenarios;
let positive;
let negative;
for (const line of md.split(/\r?\n/)) {
  if (!/^\*\*Count \(current\):\*\*/.test(line.trim())) continue;
  const s = /\*\*(\d+)\*\* scenarios/.exec(line);
  const p = /scenarios — \*\*(\d+)\*\* positive/.exec(line);
  const n = /, \*\*(\d+)\*\* negative/.exec(line);
  if (!s || !p || !n) break;
  scenarios = s[1];
  positive = p[1];
  negative = n[1];
  break;
}
let docFixtures;
for (const line of md.split(/\r?\n/)) {
  const fm = /^\*\*Fixture files:\*\* \*\*(\d+)\*\* JSON/.exec(line.trim());
  if (fm) {
    docFixtures = Number(fm[1], 10);
    break;
  }
}

const jsonFiles = fs.readdirSync(goldenDir).filter((f) => f.endsWith(".json"));
const onDisk = jsonFiles.length;

if (scenarios == null || docFixtures == null) {
  console.error("report-cached-tabular-baseline: could not parse scenario/fixture lines in cached_tabular_golden.md");
  process.exit(1);
}
if (docFixtures !== onDisk) {
  console.error(
    `report-cached-tabular-baseline: golden_cached_tabular has ${onDisk} *.json but cached_tabular_golden.md claims ${docFixtures} — update doc or files.`
  );
  process.exit(1);
}

const posActual = md.split(/\r?\n/).filter((l) => /^\|\s*G-\d+\s*\|/.test(l)).length;
const negActual = md.split(/\r?\n/).filter((l) => /^\|\s*N-\d+\s*\|/.test(l)).length;
const scnNum = Number(scenarios, 10);
const posNum = Number(positive, 10);
const negNum = Number(negative, 10);
if (posNum !== posActual) {
  console.error(
    `report-cached-tabular-baseline: Count line positive=${posNum} but markdown has ${posActual} | G-xx | table rows — fix Count (current) or tables.`
  );
  process.exit(1);
}
if (negNum !== negActual) {
  console.error(
    `report-cached-tabular-baseline: Count line negative=${negNum} but markdown has ${negActual} | N-xx | table rows — fix Count (current) or tables.`
  );
  process.exit(1);
}
if (scnNum !== posActual + negActual) {
  console.error(
    `report-cached-tabular-baseline: Count line scenarios=${scnNum} but table rows sum to ${posActual + negActual} (${posActual} + ${negActual}) — fix Count (current) or tables.`
  );
  process.exit(1);
}

console.log(
  `cached-tabular baseline (repo-local): scenarios=${scenarios} (positive=${positive} table rows=${posActual}, negative=${negative} table rows=${negActual}); fixture JSON files=${onDisk} (matches cached_tabular_golden.md)`
);
