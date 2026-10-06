#!/usr/bin/env node
/**
 * Drift guard between the final sample application (`dev_data/sample_scpa_utilization_agent_configuration`) and the training course
 * (`training/`): the Day 4 course files must equal the final sample byte for byte, every training payload must parse,
 * post-final course material must not use the retired utilization tool names, and the utilization eval suites must
 * use the final four-tool surface. See docs/agent/training-content-parity.md.
 *
 * Run from the repository root:
 *   node scripts/check-training-content-parity.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { execSync } from "node:child_process";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const parlerRoot = path.resolve(path.join(scriptDir, ".."));
const trainingRoot = path.join(parlerRoot, "training");

const canonicalUtil = path.join(parlerRoot, "dev_data/sample_scpa_utilization_agent_configuration");
const canonicalManifest = path.join(canonicalUtil, "tools/extended_tools.json");

const DAY4_MIRROR_REL = [
  "workshop/day4/tools/extended_tools.json",
  "workshop/day4/skills/utilization_summary/SKILL.md",
  "workshop/day4/skills/utilization_overview/SKILL.md",
  "workshop/day4/skills/machine_utilization_summary/SKILL.md",
  "workshop/day4/playbooks/utilization_summary/playbook.json",
  "workshop/day4/playbooks/utilization_overview/playbook.json",
  "workshop/day4/playbooks/machine_utilization_summary/playbook.json",
];

const CANONICAL_DAY4_REL = [
  "tools/extended_tools.json",
  "skills/utilization_summary/SKILL.md",
  "skills/utilization_overview/SKILL.md",
  "skills/machine_utilization_summary/SKILL.md",
  "playbooks/utilization_summary/playbook.json",
  "playbooks/utilization_overview/playbook.json",
  "playbooks/machine_utilization_summary/playbook.json",
];

/** Old seven model-facing utilization tool names (post-Ch16 final artifacts must not use these). */
const OLD_MODEL_FACING = [
  "utilization_records",
  "utilization_records_by_machine",
  "utilization_aggregate_by_state",
  "utilization_stats_for_aggregate",
  "utilization_machine_listing",
  "utilization_machine_listing_with_dates",
  "utilization_aggregate_by_state_time_fence",
  "summarize_utilization",
];

/** Final four-tool names in the final sample manifest. */
const FINAL_UTIL_TOOLS = [
  "list_utilization_machines",
  "get_utilization_records",
  "get_utilization_state_summary",
  "get_utilization_overview",
];

/** Paths where legacy model-facing names are allowed (pre-Ch16 / maintainer / frozen). */
const LEGACY_NAME_ALLOW_PREFIXES = [
  "src/13-extended-tools-wrappers.md",
  "src/15-skills-evidence-grounding.md",
  "src/M-utilization-llm-tools.md",
  "slides/day3/",
  "workshop/day3/",
];

const STALE_HANDSHAKE = "0.1.191:0.1.81";

const POST_FINAL_SCAN_GLOBS = [
  "workshop/day4/tools",
  "workshop/day4/skills/utilization_summary",
  "workshop/day4/skills/utilization_overview",
  "workshop/day4/skills/machine_utilization_summary",
  "workshop/day4/playbooks/utilization_summary",
  "workshop/day4/playbooks/utilization_overview",
  "workshop/day4/playbooks/machine_utilization_summary",
  "workshop/day4/eval/customer-evals/extended-tools.yaml",
  "src/16-llm-friendly-interface.md",
];

/** Utilization eval suites that must use the final four-tool surface: [root, relative path]. */
const UTIL_EVAL_YAML = [
  [parlerRoot, "docs/agent/evals/utilization_v1.yaml"],
  [trainingRoot, "workshop/day4/eval/customer-evals/extended-tools.yaml"],
];

const failures = [];
const warnings = [];

function fail(msg) {
  failures.push(msg);
}

function warn(msg) {
  warnings.push(msg);
}

function readFile(p) {
  return fs.readFileSync(p, "utf8");
}

function fileExists(p) {
  return fs.existsSync(p);
}

function escapeRegex(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function tokenBoundaryPattern(name) {
  return new RegExp(`(?<![\\w])${escapeRegex(name)}(?![\\w])`);
}

const OLD_MODEL_FACING_PATTERNS = OLD_MODEL_FACING.map((name) => ({
  name,
  re: tokenBoundaryPattern(name),
}));

function cmpFiles(a, b, label) {
  if (!fileExists(a)) {
    fail(`missing: ${label} canonical ${a}`);
    return;
  }
  if (!fileExists(b)) {
    fail(`missing: ${label} mirror ${b}`);
    return;
  }
  const ca = fs.readFileSync(a);
  const cb = fs.readFileSync(b);
  if (!ca.equals(cb)) {
    fail(`byte mismatch: ${label}\n  canonical: ${a}\n  mirror:    ${b}`);
  }
}

function parseJsonFile(p, label) {
  try {
    JSON.parse(readFile(p));
  } catch (e) {
    fail(`${label} JSON parse error (${p}): ${e.message}`);
  }
}

function extractChecklistBlocks(content, filePath) {
  const re = /```parler-task-checklist-v1\n([\s\S]*?)```/g;
  const blocks = [];
  let m;
  while ((m = re.exec(content)) !== null) {
    blocks.push({ json: m[1], filePath });
  }
  return blocks;
}

function scanDirFiles(root, subdir, extensions) {
  const dir = path.join(root, subdir);
  if (!fileExists(dir)) return [];
  const out = [];
  const walk = (d, rel) => {
    for (const ent of fs.readdirSync(d, { withFileTypes: true })) {
      const relPath = rel ? `${rel}/${ent.name}` : ent.name;
      const full = path.join(d, ent.name);
      if (ent.isDirectory()) walk(full, relPath);
      else if (extensions.some((ext) => ent.name.endsWith(ext))) out.push({ full, rel: path.join(subdir, relPath) });
    }
  };
  walk(dir, "");
  return out;
}

function isLegacyAllowed(relPath) {
  const norm = relPath.replace(/\\/g, "/");
  return LEGACY_NAME_ALLOW_PREFIXES.some((p) => norm.includes(p.replace(/\\/g, "/")));
}

function checkOldNamesInFile(root, relPath, repoLabel) {
  if (isLegacyAllowed(relPath)) return;
  const full = path.join(root, relPath);
  if (!fileExists(full)) return;
  const text = readFile(full);
  for (const { name, re } of OLD_MODEL_FACING_PATTERNS) {
    if (re.test(text)) {
      fail(`stale model-facing name "${name}" in post-final path ${repoLabel}${relPath}`);
    }
  }
}

function scanPostFinalPaths(root, repoLabel) {
  for (const rel of POST_FINAL_SCAN_GLOBS) {
    const full = path.join(root, rel);
    if (fileExists(full) && fs.statSync(full).isFile()) {
      checkOldNamesInFile(root, rel, repoLabel);
    } else if (fileExists(full) && fs.statSync(full).isDirectory()) {
      for (const { rel: frel } of scanDirFiles(root, rel, [".md", ".json", ".yaml"])) {
        checkOldNamesInFile(root, frel, repoLabel);
      }
    }
  }
}

function rgMatches(root, pattern, glob) {
  try {
    const cmd = `rg -l ${JSON.stringify(pattern)} ${glob ? `-g ${JSON.stringify(glob)}` : ""} .`;
    const out = execSync(cmd, { cwd: root, encoding: "utf8", stdio: ["pipe", "pipe", "pipe"] }).trim();
    return out ? out.split("\n").filter(Boolean) : [];
  } catch (e) {
    if (e.status === 1) return [];
    throw e;
  }
}

function requireOnPath(cmd, label) {
  try {
    execSync(`command -v ${cmd}`, { stdio: "pipe" });
  } catch {
    fail(`${label} requires \`${cmd}\` on PATH`);
  }
}

function parseYamlFile(yamlPath, label) {
  try {
    execSync(`ruby -ryaml -e 'YAML.load_file(ARGV[0])' ${JSON.stringify(yamlPath)}`, {
      stdio: "pipe",
    });
  } catch (e) {
    const detail = e.stderr?.toString()?.trim() || e.message;
    if (detail.includes("command not found") || detail.includes("No such file")) {
      fail(`${label} YAML parse requires ruby with yaml gem on PATH`);
    } else {
      fail(`${label} YAML parse error: ${detail}`);
    }
  }
}

function checkUtilEvalYaml(root, rel, repoLabel) {
  const yamlPath = path.join(root, rel);
  if (!fileExists(yamlPath)) {
    fail(`missing utilization eval YAML: ${repoLabel}${rel}`);
    return;
  }
  const text = readFile(yamlPath);
  for (const { name, re } of OLD_MODEL_FACING_PATTERNS) {
    if (re.test(text)) {
      fail(`stale tool name "${name}" in ${repoLabel}${rel}`);
    }
  }
  parseYamlFile(yamlPath, `${repoLabel}${rel}`);
}

function scanStaleHandshake(root, repoLabel) {
  if (!fileExists(root)) return;
  const hits = rgMatches(root, STALE_HANDSHAKE, "");
  for (const h of hits) {
    fail(`stale handshake ${STALE_HANDSHAKE} in ${repoLabel}${h}`);
  }
}

/** Parse every course and final-sample SKILL.md, playbook.json and extended_tools.json. */
function collectPayloadFiles(root, subdir) {
  const base = path.join(root, subdir);
  const out = [];
  if (!fileExists(base)) return out;

  const walk = (dir, rel) => {
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      const relPath = rel ? `${rel}/${ent.name}` : ent.name;
      const full = path.join(dir, ent.name);
      if (ent.isDirectory()) {
        walk(full, relPath);
        continue;
      }
      if (ent.name === "playbook.json" || ent.name === "extended_tools.json") {
        out.push({ full, rel: path.join(subdir, relPath).replace(/\\/g, "/"), kind: "json" });
      } else if (ent.name === "SKILL.md") {
        out.push({ full, rel: path.join(subdir, relPath).replace(/\\/g, "/"), kind: "skill" });
      }
    }
  };
  walk(base, "");
  return out;
}

function parseAllTrainingPayloads(root, subdir, repoLabel) {
  const files = collectPayloadFiles(root, subdir);
  if (files.length === 0) {
    warn(`no training payload files under ${repoLabel}${subdir}/`);
    return;
  }
  for (const { full, rel, kind } of files) {
    if (kind === "json") {
      parseJsonFile(full, `${repoLabel}${rel}`);
    } else {
      for (const block of extractChecklistBlocks(readFile(full), rel)) {
        try {
          JSON.parse(block.json);
        } catch (e) {
          fail(`checklist JSON parse error in ${repoLabel}${rel}: ${e.message}`);
        }
      }
    }
  }
}

function main() {
  console.log("training-content-parity drift check");
  console.log(`  repository: ${parlerRoot}`);

  requireOnPath("rg", "handshake scan");
  requireOnPath("ruby", "YAML eval parse");

  if (!fileExists(trainingRoot)) {
    fail(`training course not found: ${trainingRoot}`);
  }
  if (!fileExists(canonicalManifest)) {
    fail(`canonical manifest missing: ${canonicalManifest}`);
  }

  // 1. Byte parity: the final sample application vs the Day 4 course copies
  for (let i = 0; i < CANONICAL_DAY4_REL.length; i++) {
    const canon = path.join(canonicalUtil, CANONICAL_DAY4_REL[i]);
    const rel = DAY4_MIRROR_REL[i];
    cmpFiles(canon, path.join(trainingRoot, rel), `training/${rel}`);
  }

  // 2. Every training payload parses
  parseAllTrainingPayloads(parlerRoot, "dev_data/sample_scpa_utilization_agent_configuration", "");
  parseAllTrainingPayloads(trainingRoot, "workshop", "training/");

  // 3. Stale version-handshake examples
  scanStaleHandshake(trainingRoot, "training/");

  // 4. Retired model-facing names in post-final course paths
  scanPostFinalPaths(trainingRoot, "training/");

  // 5. Four-tool manifest tool names vs the final sample
  const manifest = JSON.parse(readFile(canonicalManifest));
  const expectedTools = new Set(manifest.tools.map((t) => t.name));
  for (const t of FINAL_UTIL_TOOLS) {
    if (!expectedTools.has(t)) fail(`canonical manifest missing tool ${t}`);
  }
  if (expectedTools.size !== 4) {
    warn(`canonical manifest has ${expectedTools.size} tools (expected 4)`);
  }

  // 6. Utilization eval suites
  for (const [root, rel] of UTIL_EVAL_YAML) {
    checkUtilEvalYaml(root, rel, root === trainingRoot ? "training/" : "");
  }

  console.log("");
  if (warnings.length) {
    console.log(`Warnings (${warnings.length}):`);
    for (const w of warnings) console.log(`  ⚠ ${w}`);
  }
  if (failures.length) {
    console.error(`Failures (${failures.length}):`);
    for (const f of failures) console.error(`  ✗ ${f}`);
    process.exit(1);
  }
  console.log("OK — no drift findings.");
}

main();
