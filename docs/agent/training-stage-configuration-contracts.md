# Training stage configuration contracts

The training course in [`training/`](../../training/) teaches the SCPA utilization sample application in stages. Each
workshop day uploads a different configuration-repository payload, and each payload is declared by a small
`stage.json` contract so that the stages stay explicit, parseable and testable.

## 1. Where configuration lives

| Path | Role |
| --- | --- |
| `dev_data/scpa_utilization/` | The **final** sample configuration: tools, policies, skills, Playbooks, taxonomies and host-context examples, kept mutually consistent and importable as current best practice. It does not hold stage history. |
| `training/workshop/dayN/` | The payload for one course stage. A stage may intentionally differ from the final configuration when it teaches an intermediate step; such stages are labelled `pre-llm-friendly`. |
| `training/workshop/dayN/stage.json` | The stage contract (§5). |
| `training/dev_data/` | ThingWorx exports used by the course (`uv run import-dev --apply --import_control import_training`). |

Day 4 is the final stage: its utilization tools, skills and Playbooks are byte-for-byte copies of the final sample
(§6.1). Add a new `dayN` folder when the course needs another stage instead of folding distinct stages into one.

## 2. The two course phases

| Phase | Where | Utilization teaching |
| --- | --- | --- |
| `pre-llm-friendly` | Days 1–3, chapters 13–15 (first pass) | The seven underlying ThingWorx services as a prose inventory and wrapper patterns. Service-aligned model-facing tool names may appear only in labelled first-pass exercises, never as the final upload target. |
| `post-llm-friendly` | Day 4, chapter 16 | The four user-intent tools `list_utilization_machines`, `get_utilization_records`, `get_utilization_state_summary`, `get_utilization_overview`, and the skills, Playbooks and eval suites built on them. |

## 5. Stage contract file

Every `training/workshop/dayN/stage.json` uses schema `parler-training-stage-v1`:

```json
{
  "schema": "parler-training-stage-v1",
  "stage": "day4",
  "phase": "post-llm-friendly",
  "uploads": [
    { "repoPath": "workshop/day4/tools/extended_tools.json", "target": "/tools/extended_tools.json" },
    { "repoPath": "workshop/day4/skills/utilization_summary/SKILL.md", "target": "/skills/utilization_summary/SKILL.md" }
  ],
  "expectedRuntime": {
    "extendedTools": ["list_utilization_machines", "get_utilization_records",
                      "get_utilization_state_summary", "get_utilization_overview"],
    "skills": ["asset_pair_health", "utilization_summary", "utilization_overview", "machine_utilization_summary"],
    "playbooks": ["utilization_summary", "utilization_overview", "machine_utilization_summary"],
    "shadowedSkills": ["utilization_summary", "utilization_overview", "machine_utilization_summary"],
    "policyRules": ["allow-utilization-reads"]
  },
  "tests": {
    "static": ["parse-payloads", "byte-parity-final-utilization", "stage-manifest"],
    "thingworxRuntime": ["list-machines", "state-summary-all-shift-normalization", "records-all-machines"],
    "agent": ["which-machines", "raw-records-all-machines", "state-summary-all-machines", "overview"]
  }
}
```

- `uploads[].repoPath` is relative to `training/`; `target` is the configuration-repository path.
- `expectedRuntime` lists the tool, skill, Playbook and policy-rule ids the stage must produce. A Playbook with the
  same id as a skill shadows that skill in the model-facing catalog (`shadowedSkills`).
- A `pre-llm-friendly` stage must not list the final four tools and must not request
  `byte-parity-final-utilization`.

## 6. Tests

### 6.1 Static contract and parity checks

Run locally without ThingWorx:

```bash
node scripts/check-training-stage-contracts.mjs
```

It validates every `stage.json` (upload paths exist; declared tools, skills, Playbooks and policy rules match the files
of that stage; phase rules above), then runs `scripts/check-training-content-parity.mjs`, which:

- parses every `extended_tools.json`, `playbook.json` and the `parler-task-checklist-v1` fences in every `SKILL.md`
  under `training/workshop/` and `dev_data/scpa_utilization/`;
- compares the Day 4 utilization tools, skills and Playbooks byte-for-byte with `dev_data/scpa_utilization/`;
- rejects the retired service-aligned tool names in post-final course material;
- checks that the utilization eval suites (`docs/agent/evals/utilization_v1.yaml` and the course's
  `customer-evals/extended-tools.yaml`) use only the four final tools.

The checks need `rg` and `ruby` (for YAML parsing) on `PATH`.

### 6.2 Java registry and Playbook tests

JUnit tests (no ThingWorx) cover the Playbook validator on the sample Playbooks, same-id skill/Playbook shadowing, the
extended-tool manifest parser, the invoke-service policy parser, and the Playbook evidence projection of §7.

### 6.3 ThingWorx runtime services

The sample's helper services are ThingWorx JavaScript and are checked against a real server, not simulated. Expected
behavior of `SCPA_Utilization_helper`:

- `ListUtilizationMachines`: a plain listing returns the machines; `IncludeEffectiveDates=true` without dates falls
  back to the base listing with an evidence gap; an empty date-aware listing falls back to the base listing when the
  base listing has machines.
- `GetUtilizationRecords`: an omitted or blank `Machine` means all machines; a ThingName means one machine; pagination
  fields bound the page.
- `GetUtilizationStateSummary`: an omitted or blank `Machine` means all machines; `ShiftID` `All`, `any` and `*` mean no
  shift filter; the result carries `stats.utilizationPercent` and state `rows`.
- `GetUtilizationOverview`: returns `machineCoverage`, `stateSummary`, `stats` and `evidenceGaps`.

### 6.4 Agent live smoke

`uv run utilization-stage-smoke` runs `docs/agent/evals/utilization_stage_contracts_v1.yaml` through `agent-eval`
against the server in `.env` (it prints `SKIP` without `DEV_SERVER` / `DEV_KEY` unless `--require-live` is given). The
cases ask which machines are available, list raw records for all machines, summarize utilization by state, and ask for
the overview, and assert that the stage tools are called, results are non-empty for the known dataset, and the final
answer cites returned evidence.

## 7. Playbook evidence projection

Playbook `tool_call` nodes project tool output into the evidence text that later nodes (for example `llm_summary`)
receive:

- **Root scalars:** `evidence.includeToolOutputRootFields`.
- **Nested scalars:** `evidence.includeToolOutputPaths`, bounded dot paths without indexing, for example
  `result.stats.utilizationPercent`.
- **Nested tables:** `evidence.table` with an optional `path` to an array (for example `result.rows` or
  `result.stateSummary.rows`), `columns`, and `maxRows` (1–100).

A JSON string under `toolOutput.result` is parsed and supports the same paths; opaque or malformed strings fail
closed. `PlaybookValidator` enforces the path grammar and bounds. Existing evidence fields (`evidenceLines`,
`evidenceText`, fan-out child projection, UI artifact evidence lines) behave as before.
