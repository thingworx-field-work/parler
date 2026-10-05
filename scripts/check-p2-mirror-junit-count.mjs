#!/usr/bin/env node
/**
 * Ensures `docs/agent/cached_tabular_golden.md` §Repo baseline last-cache mirror offline test row matches the real
 * `@Test` count in `AgentToolContextLastQualifyingTabularCacheTest.java` (Further Insight #37 appendix off-by-one guard).
 *
 * Run from repo root: `node scripts/check-p2-mirror-junit-count.mjs`
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const testPath = path.join(
  root,
  "parler-agent",
  "src",
  "test",
  "java",
  "com",
  "thingworx",
  "things",
  "agent",
  "tools",
  "AgentToolContextLastQualifyingTabularCacheTest.java"
);
const goldenPath = path.join(root, "docs", "agent", "cached_tabular_golden.md");

if (!fs.existsSync(testPath)) {
  console.error(`check-p2-mirror-junit-count: missing ${path.relative(root, testPath)}`);
  process.exit(1);
}

const testSrc = fs.readFileSync(testPath, "utf8");
const testCount = (testSrc.match(/^\s*@Test\s*$/gm) || []).length;
if (testCount === 0) {
  console.error("check-p2-mirror-junit-count: no @Test lines found in mirror test class");
  process.exit(1);
}

const golden = fs.readFileSync(goldenPath, "utf8");
let docCount = null;
for (const line of golden.split(/\r?\n/)) {
  if (!line.includes("AgentToolContextLastQualifyingTabularCacheTest") || !line.includes("@Test")) continue;
  const m = /\*\*(\d+)\*\*\s*\*\*`@Test`\*\*/.exec(line);
  if (m) {
    docCount = parseInt(m[1], 10);
    break;
  }
}

if (docCount == null) {
  console.error(
    "check-p2-mirror-junit-count: could not parse **N** **`@Test`** in P2 mirror row of docs/agent/cached_tabular_golden.md §Repo baseline"
  );
  process.exit(1);
}

if (testCount !== docCount) {
  console.error(
    `check-p2-mirror-junit-count: Java has ${testCount} @Test lines but cached_tabular_golden.md claims ${docCount} — align §Repo baseline table or tests.`
  );
  process.exit(1);
}

console.log(`check-p2-mirror-junit-count: OK (${testCount} @Test matches docs/agent/cached_tabular_golden.md §Repo baseline)`);
