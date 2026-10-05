#!/usr/bin/env node
/**
 * Stage contract guard for the training course: every `training/workshop/dayN/stage.json` must be internally
 * consistent (docs/agent/training-stage-configuration-contracts.md §6.1), then the content-parity check runs.
 *
 * Run from the repository root:
 *   node scripts/check-training-stage-contracts.mjs
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const parlerRoot = path.resolve(path.join(scriptDir, ".."));
const trainingRoot = path.join(parlerRoot, "training");

/** Final four-tool utilization names — must not appear on pre-llm-friendly stages. */
const FINAL_UTIL_TOOLS = new Set([
  "list_utilization_machines",
  "get_utilization_records",
  "get_utilization_state_summary",
  "get_utilization_overview",
]);

const failures = [];
const warnings = [];

function fail(msg) {
  failures.push(msg);
}

function warn(msg) {
  warnings.push(msg);
}

function readJson(p) {
  return JSON.parse(fs.readFileSync(p, "utf8"));
}

function fileExists(p) {
  return fs.existsSync(p);
}

function listStageManifests(repoRoot) {
  const workshop = path.join(repoRoot, "workshop");
  if (!fileExists(workshop)) {
    return [];
  }
  const out = [];
  for (const ent of fs.readdirSync(workshop, { withFileTypes: true })) {
    if (!ent.isDirectory() || !/^day\d+$/.test(ent.name)) {
      continue;
    }
    const stagePath = path.join(workshop, ent.name, "stage.json");
    if (fileExists(stagePath)) {
      out.push({ day: ent.name, stagePath });
    }
  }
  return out.sort((a, b) => a.day.localeCompare(b.day, undefined, { numeric: true }));
}

function parsePolicyRuleIds(policyPath) {
  if (!fileExists(policyPath)) {
    return null;
  }
  const data = readJson(policyPath);
  return new Set((data.rules || []).map((r) => r.id).filter(Boolean));
}

function validatePhaseConstraints(stage, label) {
  const phase = stage.phase || "";
  const tools = stage.expectedRuntime?.extendedTools || [];
  if (phase !== "pre-llm-friendly") {
    return;
  }
  for (const name of tools) {
    if (FINAL_UTIL_TOOLS.has(name)) {
      fail(`${label} pre-llm-friendly stage must not declare final utilization tool ${name}`);
    }
  }
  const staticTests = stage.tests?.static || [];
  if (staticTests.includes("byte-parity-final-utilization")) {
    fail(`${label} pre-llm-friendly stage must not require byte-parity-final-utilization`);
  }
}

function validatePolicyRules(stage, stageDir, label) {
  const declared = stage.expectedRuntime?.policyRules || [];
  if (!declared.length) {
    return;
  }
  const policyPath = path.join(stageDir, "policies/invoke_service.json");
  const ids = parsePolicyRuleIds(policyPath);
  if (!ids) {
    fail(`${label} policyRules declared but missing ${policyPath}`);
    return;
  }
  for (const id of declared) {
    if (!ids.has(id)) {
      fail(`${label} policyRules lists ${id} but invoke_service.json has no such rule id`);
    }
  }
}

function validateStageManifest(stagePath, repoRoot, label) {
  if (!fileExists(stagePath)) {
    fail(`missing ${label} stage manifest: ${stagePath}`);
    return null;
  }
  let stage;
  try {
    stage = readJson(stagePath);
  } catch (e) {
    fail(`invalid JSON ${label} stage manifest: ${stagePath} (${e.message})`);
    return null;
  }
  if (stage.schema !== "parler-training-stage-v1") {
    fail(`${label} stage.schema must be parler-training-stage-v1 (got ${stage.schema})`);
  }
  if (stage.stage && path.basename(path.dirname(stagePath)) !== stage.stage) {
    fail(`${label} stage field ${stage.stage} does not match folder ${path.basename(path.dirname(stagePath))}`);
  }
  validatePhaseConstraints(stage, label);

  const uploads = stage.uploads || [];
  for (const u of uploads) {
    const rel = u.repoPath;
    if (!rel) {
      fail(`${label} upload missing repoPath`);
      continue;
    }
    const abs = path.join(repoRoot, rel);
    if (!fileExists(abs)) {
      fail(`${label} upload path missing: ${rel}`);
    }
  }

  const rt = stage.expectedRuntime || {};
  const tools = rt.extendedTools || [];
  const skills = rt.skills || [];
  const playbooks = rt.playbooks || [];
  const shadowed = rt.shadowedSkills || [];
  const stageDir = path.dirname(stagePath);

  validatePolicyRules(stage, stageDir, label);

  const toolsFile = path.join(stageDir, "tools/extended_tools.json");
  if (tools.length && fileExists(toolsFile)) {
    const manifest = readJson(toolsFile);
    const declared = new Set((manifest.tools || []).map((t) => t.name));
    for (const name of tools) {
      if (!declared.has(name)) {
        fail(`${label} expectedRuntime.extendedTools lists ${name} but tools/extended_tools.json does not declare it`);
      }
    }
  } else if (tools.length && !fileExists(toolsFile)) {
    fail(`${label} expectedRuntime.extendedTools non-empty but ${toolsFile} is missing`);
  }

  for (const id of skills) {
    const skillPath = path.join(stageDir, "skills", id, "SKILL.md");
    if (!fileExists(skillPath)) {
      fail(`${label} expectedRuntime.skills lists ${id} but ${skillPath} is missing`);
    }
  }
  for (const id of playbooks) {
    const pb = path.join(stageDir, "playbooks", id, "playbook.json");
    if (!fileExists(pb)) {
      fail(`${label} expectedRuntime.playbooks lists ${id} but ${pb} is missing`);
    }
  }
  for (const id of shadowed) {
    if (!skills.includes(id)) {
      fail(`${label} shadowedSkills ${id} must also appear in expectedRuntime.skills`);
    }
    if (!playbooks.includes(id)) {
      fail(`${label} shadowedSkills ${id} must have a same-id playbook in expectedRuntime.playbooks`);
    }
  }
  return stage;
}

/** Mirror checks for uploads present in both repos (shared student-facing paths). */
function validateRepoStages(repoRoot, repoLabel) {
  const stages = listStageManifests(repoRoot);
  if (!stages.length) {
    warn(`${repoLabel}: no stage.json manifests under workshop/dayN/`);
  }
  for (const { day, stagePath } of stages) {
    validateStageManifest(stagePath, repoRoot, `${repoLabel}/${day}`);
  }
  return stages;
}

if (!fileExists(trainingRoot)) {
  fail(`training course not found: ${trainingRoot}`);
} else {
  validateRepoStages(trainingRoot, "training");
}

const parity = spawnSync(process.execPath, [path.join(scriptDir, "check-training-content-parity.mjs")], {
  cwd: parlerRoot,
  env: process.env,
  encoding: "utf8",
});
if (parity.status !== 0) {
  fail(`check-training-content-parity.mjs failed:\n${parity.stdout || ""}${parity.stderr || ""}`);
}

if (warnings.length) {
  console.warn("Warnings:");
  for (const w of warnings) {
    console.warn(`  - ${w}`);
  }
}

if (failures.length) {
  console.error("Stage contract check FAILED:");
  for (const f of failures) {
    console.error(`  - ${f}`);
  }
  process.exit(1);
}

console.log("Stage contract check passed.");
