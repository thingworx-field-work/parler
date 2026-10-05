---
name: skill_to_playbook_converter
title: Skill-to-Playbook converter
description: Use when converting a repository SKILL.md workflow into a packaged parler-playbook-v1 playbook.json with an honest conversion report and ValidatePlaybookDocument check.
when_to_use: Convert a Skill workflow to a Playbook; validate against runtime snapshot; emit parler-playbook-conversion-report-v1.
skill_meta_version: 1
---

### Purpose

Convert a **source Skill** (`/skills/<id>/SKILL.md`) into a **candidate Playbook** (`playbook.json`) plus a **conversion report**. You produce a useful draft and an honest report — not a guarantee of perfection.

Read bundled knowledge (same directory as this skill after staging):

- `knowledge/feature-version-table.json` — compute **`requiredAgentVersion`**
- `knowledge/op-selection-guide.md` — `match_identifier_in_rows` vs `match_candidates`
- `knowledge/conversion-workflow.md` — step order

Canonical design: `docs/agent/playbook-customer-readiness.md` §10.6.

### Required workflow

1. **Inputs** — source `SKILL.md`, optional example prompts, optional existing Playbook to revise.
2. **Snapshot** — call **`GetAgentRuntimeSnapshot`** with `includePlaybooks: true`. Record `playbookRuntime.agentVersion` and `playbookRuntime.deriveOps`.
3. **Draft** — build `playbook.json` (`schema: parler-playbook-v1`). Use only tools/ops present on the snapshot. Preserve Skill evidence rules, not only tool order.
4. **Validate** — call **`ValidatePlaybookDocument`** with the draft JSON. Embed the full §6.2 structured report in the conversion report `validation` field.
5. **Report** — emit `parler-playbook-conversion-report-v1` with all required fields (§10.6.3): `deriveOpsUsed`, `runnable`, `converted`, `partiallyConverted`, `notConverted`, `runtimeRequirements`, `authorActions`, `suggestedLiveTests`.
6. **Stage** — tell the author to install at `/playbooks/<id>/playbook.json` and run **`RefreshPromptContextCache`**. Do not mutate the repository yourself.

### Converter rules

- Set **`requiredAgentVersion`** to the **maximum** `minimumAgentVersion` from `feature-version-table.json` for features the draft uses — not an echo of the snapshot version.
- When snapshot `agentVersion` **<** `requiredAgentVersion`, add an **`authorActions`** line explaining the mismatch before install steps.
- Set **`runnable: true`** only when `validation.status` is `valid`.
- Key remediation on §6.3.1 `errors[].code`, not message text.
- Do not invent extended tools or app services. Prefer `playbookSafe: true` extended tools when the Skill used them.
- For utilization-style Skills with non-safe extended tools, use **`notConverted`** / **`partiallyConverted`** honestly.

### Reference conversions (examples)

Study shipped examples under `docs/agent/playbook-converter/examples/`:

- `asset_pair_health` — golden path aligned with `cross_asset_pair_health` packaging fixture
- `region_health` — honest partial (multi-node fan-out)
- `version_warning` — version-mismatch `authorActions`

### Acceptance (self-check)

- [ ] Snapshot read before draft
- [ ] Every derive `op` in draft listed in `deriveOpsUsed`
- [ ] Validation embedded; `runnable` matches `validation.status`
- [ ] `requiredAgentVersion` derived from feature table
- [ ] No pretend-runnable report when validation fails
