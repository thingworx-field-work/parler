#!/usr/bin/env node
/**
 * Validates every *.json under docs/agent/golden_cached_tabular/ parses as JSON.
 * Run from repo root: node scripts/check-golden-cached-tabular-json.mjs
 */
import { readdir, readFile } from "node:fs/promises";
import { join, dirname } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, "..", "docs", "agent", "golden_cached_tabular");

const files = (await readdir(root)).filter((f) => f.endsWith(".json"));
let ok = 0;
for (const f of files) {
  const text = await readFile(join(root, f), "utf8");
  try {
    JSON.parse(text);
  } catch (e) {
    console.error(`FAIL ${f}: ${e.message}`);
    process.exit(1);
  }
  ok++;
}
console.log(`OK ${ok} JSON file(s) under docs/agent/golden_cached_tabular/`);
