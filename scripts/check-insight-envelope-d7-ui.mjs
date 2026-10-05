#!/usr/bin/env node
/**
 * Fail if `parler-ui` view / adapter code reads `insightEnvelopeLoose.<field>` outside the D7 allowance
 * (`readInsightEnvelopeLoose` in `chatSession.js` only). Run from repo root:
 *   node scripts/check-insight-envelope-d7-ui.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const parlerUi = path.join(root, "parler-ui");

/** Property access on the loose row attachment (forbidden outside allowlist). */
const forbidden = /insightEnvelopeLoose\??\.[a-zA-Z_$][\w$]*/g;

const allowFiles = new Set(
  [
    path.join(parlerUi, "lib", "insightEnvelopeRead.js"),
    path.join(parlerUi, "lib", "chatSession.js"),
  ].map((p) => path.normalize(p))
);

function walkJs(dir, out) {
  if (!fs.existsSync(dir)) return;
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    if (ent.name === "node_modules" || ent.name === "dist") continue;
    const full = path.join(dir, ent.name);
    if (ent.isDirectory()) walkJs(full, out);
    else if (ent.isFile() && ent.name.endsWith(".js")) out.push(full);
  }
}

let failed = false;
const files = [];
walkJs(parlerUi, files);

for (const file of files) {
  const norm = path.normalize(file);
  if (allowFiles.has(norm)) continue;
  const text = fs.readFileSync(file, "utf8");
  const matches = text.match(forbidden);
  if (matches?.length) {
    console.error(
      `check-insight-envelope-d7-ui: forbidden pattern(s) in ${path.relative(root, file)}: ${[...new Set(matches)].join(", ")}`
    );
    failed = true;
  }
}

if (failed) process.exit(1);
console.log(`check-insight-envelope-d7-ui: OK (${files.length} parler-ui/**/*.js scanned, allowlist: insightEnvelopeRead.js, chatSession.js)`);
