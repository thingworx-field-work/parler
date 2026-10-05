# Appendix: App eval methodology

This appendix explains how an application team should build an eval pack for its own ThingWorx App after deploying
Parler. The goal is not to create a CI gate. The goal is to make customer-facing agent behavior repeatable, diagnosable,
and comparable across models, configuration changes, and Parler upgrades.

The workshop-local eval pack lives under:

```text
workshop/day4/eval/
```

It contains the runner, copied Parler sample suites, and customer-suite templates referenced below. It was refreshed from
the source project on 2026-06-15 against commit `f25d4d47`. Treat the copied sample-suite inventory as "as of writing";
inside this workshop folder, list `docs/agent/evals/` for the current copied set.

## 1. What eval should prove

Do not start by writing random prompts. First decide which behavior you need confidence in.

| Layer | What to prove | Typical failure |
|---|---|---|
| Environment | AgentThing, provider, repository, taxonomy, skills, playbooks are loaded | the test is meaningless because the AgentThing is misconfigured |
| Identity | user labels resolve to canonical Things | display name is passed as `thingName`; asset label is treated as a hierarchy node |
| Asset type | business asset classes map to the intended ThingShape / ThingTemplate | model lists generic platform inventory instead of the App asset set |
| Property semantics | business words map to properties, KPIs, or wrapper tools | model guesses a property or says no health property exists |
| Tool route | the right built-in / extended tool / playbook is used | model uses `invoke_service` or a broad search instead of the App route |
| Evidence grounding | final answer follows tool evidence | empty result becomes "entity not found"; sampled rows become "all rows" |
| Model comparison | different providers behave acceptably on the same App prompts | one model routes correctly, another asks generic clarification |
| Regression | upgrade or config changes do not break core workflows | skill/playbook still loads but route silently changes |

Good evals test the path from **business language** to **tool evidence**, not whether the model writes one exact sentence.

## 2. Current eval assets in Day 4

In the workshop copy, the live eval harness and existing suites are here:

```text
workshop/day4/eval/pyproject.toml                         # uv entry point: agent-eval
workshop/day4/eval/test_scripts/agent_eval.py             # live REST runner
workshop/day4/eval/docs/agent/agent-evaluation-harness.md  # detailed runner design and schema notes
workshop/day4/eval/docs/agent/evals/                       # copied Parler sample suites
workshop/day4/eval/docs/agent/evals/fixtures/              # small fixture files / fixture notes
workshop/day4/eval/customer-evals/                         # App-specific templates used by this appendix
workshop/day4/eval/tmp/agent-eval/<timestamp>/             # default report output location
```

Copied sample-suite files, as of this workshop pack:

| File | Purpose |
|---|---|
| `docs/agent/evals/smoke.yaml` | Small live smoke suite for routing / identity / model drift. |
| `docs/agent/evals/evidence_grounding.yaml` | Checks cache miss, empty success, invalid time range, protected / error identity behavior. |
| `docs/agent/evals/cross_asset_pair_health_v1b.yaml` | Playbook and skill baseline for asset-pair health. |
| `docs/agent/evals/cross_region_health_v1a.yaml` | Cross-region playbook regression. |
| `docs/agent/evals/invoke_service_policy.yaml` | `invoke_service` policy allow / HITL behavior. |
| `docs/agent/evals/taxonomy_v2.yaml` | Legacy taxonomy v2 resolver behavior. |
| `docs/agent/evals/taxonomy_v2_unavailable.yaml` | Taxonomy-unavailable behavior. |
| `docs/agent/evals/utilization_v1.yaml` | Utilization-oriented App workflow checks. |
| `docs/agent/evals/playbook-regression.md` | Operator runbook for playbook regression commands. |

Fixture notes:

```text
docs/agent/evals/fixtures/README.md
docs/agent/evals/fixtures/identity-types-minimal.json
```

These are examples, not a universal customer fixture set. A customer App should create its own suites and fixture notes.
A ready-to-run copy of the recommended structure already ships in `workshop/day4/eval/`; students should start there
instead of trying to reconstruct paths from memory.

From the eval folder, run this to see the actual copied inventory:

```bash
find docs/agent/evals -maxdepth 2 -type f | sort
```

## 3. Minimum eval pack for a customer App

A useful customer eval pack should contain:

```text
workshop/day4/eval/
  .env.example
  pyproject.toml
  test_scripts/agent_eval.py
  customer-evals/
    README.md
    smoke.yaml
    identity.yaml
    asset-types.yaml
    workflows.yaml
    extended-tools.yaml
    playbooks.yaml
    errors.yaml
    fixtures.md
    reports/
```

This is the pack shape already created under `workshop/day4/eval/customer-evals/`. The important part is that the pack is
versioned together with the App configuration it tests.

`README.md` should say:

- which ThingWorx server is used;
- which AgentThing names are expected;
- which provider/model each AgentThing represents;
- which ConfigurationRepository files must be uploaded;
- which AgentThing refresh services must be called;
- which fixture Things, properties, DataTables, streams, or services must exist;
- which command to run;
- how to read pass/fail results;
- how to collect log / stream diagnostics when a case fails.

`workshop/day4/eval/.env.example` should list required values, without secrets:

```text
DEV_SERVER=https://example/Thingworx
DEV_KEY=<app-key>
AGENT_EVAL_AGENT_GPT_5_4=Customer_Agent_GPT54
AGENT_EVAL_AGENT_SONNET_4_6=Customer_Agent_Sonnet46
CUSTOMER_HAS_EMPTY_DATATABLE=
CUSTOMER_HAS_HISTORY=
CUSTOMER_HAS_PROTECTED_FIXTURE=
CUSTOMER_HAS_NO_ALERT_PAIR=
```

## 4. Suite shape

Each suite is YAML:

```yaml
version: 1
suite: customer-workflow-smoke

agentMatrix:
  gpt_5_4:
    thingName: Customer_Agent_GPT54
    defaultEnabled: true
  sonnet_4_6:
    thingName: Customer_Agent_Sonnet46
    defaultEnabled: false

cases:
  - id: asset_current_status
    tags: [identity, current-values]
    turns:
      - user: "For ORD Contacting 01, what is the current value of property currentDraw?"
        acceptableOutcomes:
          - name: resolved_and_read
            score: 1.0
            assertions:
              - toolCalled: resolve_thing
              - toolCalled: get_property_values
              - finalRegex: "(?i)currentDraw|current\\s+draw"
```

The current-value vs history distinction is intentional:

| User intent | Preferred built-in |
|---|---|
| current property snapshot | `get_property_values` |
| property trend / history | `query_property_history` |
| alert events over a window | `query_alert_history` |
| current alert state | `query_alert_summary` |

Run it with:

```bash
cd workshop/day4/eval
uv run agent-eval --suite customer-evals/smoke.yaml --agent-matrix env
```

Use `--agent-matrix env` when each deployment has different AgentThing names. The runner maps suite labels to
environment variables:

```text
gpt_5_4      -> AGENT_EVAL_AGENT_GPT_5_4
sonnet_4_6   -> AGENT_EVAL_AGENT_SONNET_4_6
```

Useful options:

```bash
uv run agent-eval --suite customer-evals/workflows.yaml --agent-matrix env --agent-filter gpt_5_4
uv run agent-eval --suite customer-evals/workflows.yaml --agent-matrix env --case asset_current_status
uv run agent-eval --suite customer-evals/workflows.yaml --agent-matrix env --reset-mode stable_clear
uv run agent-eval --suite customer-evals/workflows.yaml --agent-matrix env --out-dir customer-evals/reports
```

## 5. Question design

Write questions the way the App's users will actually ask them. Then classify each question by what it is meant to
exercise.

Start with 10 to 15 high-value cases:

| Suite | Case type | Example |
|---|---|---|
| `smoke.yaml` | basic connection / simple read | "who are you"; "read current value for this asset" |
| `identity.yaml` | display name, suffix, serial number, ambiguous labels | "ORD Contacting 01" resolves to canonical Thing |
| `asset-types.yaml` | asset-class terms | "show Jet Dryers in MUC" |
| `workflows.yaml` | core business questions | health comparison, utilization, downtime, quality summary |
| `extended-tools.yaml` | App wrapper service calls | utilization records by machine, KPI summary by line |
| `playbooks.yaml` | stable conversational workflows | asset-pair health, region comparison |
| `errors.yaml` | empty, missing, invalid, protected, unauthorized | no rows is not "Thing missing" |

Do not assert exact live values unless the fixture is intentionally stable. For live production-like data, assert the
route and evidence category instead:

- correct canonical Thing name;
- correct tool called;
- correct tool argument;
- answer mentions trend / alert / empty / invalid time in the right category;
- answer does not claim facts unsupported by sampled evidence.

Environment checks should be explicit, not implied. The Day 4 template includes two examples in
`customer-evals/smoke.yaml`:

```yaml
- id: environment_agent_responds
  turns:
    - user: "who are you?"
      acceptableOutcomes:
        - name: agent_identity_response
          score: 1.0
          assertions:
            - finalRegex: "(?i)parler|thingworx|agent"
            - traceParseErrorsAbsent: true

- id: environment_taxonomy_loaded
  turns:
    - user: "Please list the configured asset types available to you."
      acceptableOutcomes:
        - name: asset_type_catalog_available
          score: 1.0
          assertions:
            - toolCalled: list_asset_types
            - finalRegex: "(?i)asset\\s+type|configured|available"
```

For a customer App, extend this idea to the repository assets that must be present: taxonomy, policy, extended tools,
skills, and playbooks. Do not wait until a business workflow fails to discover that the AgentThing was not refreshed.

## 6. Expected answers

Expected answers are written as **acceptable outcomes**, not full answer text.

Full credit:

```yaml
acceptableOutcomes:
  - name: correct_route
    score: 1.0
    assertions:
      - toolCalled: resolve_thing
      - toolCalled: query_property_history
      - finalRegex: "(?i)trend|rising|falling|flat|stable"
```

Partial credit:

```yaml
acceptableOutcomes:
  - name: asks_targeted_clarification
    score: 0.6
    assertions:
      - finalRegex: "(?i)which\\s+asset|which\\s+property|clarify"
      - finalRegex: "(?i)ORD|Contacting"
```

This is useful because some models should not be punished for asking a precise clarification when the prompt is genuinely
underspecified.

Scoring rule: for each turn, the runner selects the highest-scoring acceptable outcome whose assertions pass and whose
`rejectIf` rules do not fire. A case then applies its `scoreMode` and `passThreshold`; for simple single-turn cases,
write the suite so full success is `1.0` and the pass threshold is effectively `>= 1.0`. Use partial scores to compare
model behavior, not to hide critical failures.

## 7. Assertions to use first

Prefer deterministic assertions over prose matching.

Final answer assertions:

```yaml
- finalContains: "SE.CellFab.Model.Workunit.ORD-Contacting-01"
- finalRegex: "(?i)invalid\\s+time|time\\s+range"
- finalNotRegex: "(?i)does\\s+not\\s+exist|cannot\\s+be\\s+found"
```

Tool route assertions:

```yaml
- toolCalled: resolve_thing
- toolCalled: query_alert_history
- toolNotCalled: start_playbook
- toolsCalledSubsequence:
    - resolve_thing
    - query_alert_history
    - query_property_history
```

Tool argument assertions:

```yaml
- toolArgEquals:
    tool: query_entities_by_taxonomy
    path: EntityName
    value: PTCTDD.CellfabDataset.StackingRobot_TS

- toolArgAbsent:
    tool: query_entities_by_taxonomy
    path: hierarchyNodeName
```

Tool result assertions:

```yaml
- toolResultRegex: CACHE_MISS
- toolResultRegex: INVALID_TIME_RANGE
- toolResultNotContains: cleartext-password
```

Count assertions:

```yaml
- toolCalledTimesAtMost: { tool: query_property_history, count: 0 }
- toolCallCountAtLeast: 1
- traceParseErrorsAbsent: true
```

## 8. `rejectIf`

`rejectIf` is often more important than positive assertions. It catches known bad routes.

Example: an individual asset label must not become a hierarchy node:

```yaml
rejectIf:
  - toolArgEquals:
      tool: query_entities_by_taxonomy
      path: hierarchyNodeName
      value: "ORD Contacting 01"
```

Example: an intermediate failure is acceptable only if the model recovered:

```yaml
rejectIf:
  - allOf:
      - finalContains: "HIERARCHY_RESOLVE_NOT_FOUND"
      - finalNotContains: "SE.CellFab.Model.Workunit.ORD-Contacting-01"
```

Use `rejectIf` for:

- wrong tool route;
- wrong argument shape;
- unsupported business claim;
- "empty result" rewritten as "entity missing";
- sampled rows described as all rows;
- protected or secret values requested from the user.

## 9. Fixture management

Every suite should say which data must exist.

`fixtures.md` should include:

| Fixture | Purpose | How to verify | Env flag |
|---|---|---|---|
| known Thing A | identity resolution | Thing exists and display name is stable | none |
| known numeric property | trend query | property is logged and has recent data | `CUSTOMER_HAS_HISTORY=1` |
| empty DataTable | empty success behavior | table exists, returns zero rows | `CUSTOMER_HAS_EMPTY_DATATABLE=1` |
| no-alert asset pair | no-alert workflow branch | both assets have no current alerts | `CUSTOMER_HAS_NO_ALERT_PAIR=1` |
| protected property/service | protection baseline | BaseTypes.PASSWORD exists in controlled fixture | `CUSTOMER_HAS_PROTECTED_FIXTURE=1` |

Gate environment-dependent cases:

```yaml
skipUnlessEnv: CUSTOMER_HAS_EMPTY_DATATABLE
skipReason: fixture_missing_empty_datatable
```

or:

```yaml
skipUnlessEnv:
  - CUSTOMER_HAS_HISTORY
  - CUSTOMER_HAS_PROTECTED_FIXTURE
skipReason: fixture_missing_history_or_protected_fixture
```

Skipped means "fixture not available." It should not count as a semantic failure.

## 10. Reset strategy

Choose reset behavior deliberately.

| Mode | Use when |
|---|---|
| `fresh` | normal smoke; each case gets a new conversation title |
| `stable_clear` | repeated model comparison; same title, logical conversation cleared |
| `stable_hard_reset` | strict repeatability; also deletes eval conversation Stream rows |

Default to `fresh` or `stable_clear` for customer teams. Use `stable_hard_reset` only when the operator understands that
it physically deletes `AgentMessageStream` rows for the selected eval conversation id.

## 11. Reports

Default output:

```text
tmp/agent-eval/<timestamp>/
  report.json
  report.md
  model-comparison.md
```

With:

```bash
cd workshop/day4/eval
uv run agent-eval --suite customer-evals/workflows.yaml --agent-matrix env --out-dir customer-evals/reports
```

the runner writes:

```text
customer-evals/reports/<timestamp>/
```

Read reports this way:

| Report | Use |
|---|---|
| `report.md` | quick triage; failed cases first, concise final excerpt, tool path, assertion failure |
| `model-comparison.md` | compare model quality; full final answers, selected outcomes, token/cache usage, tool path |
| `report.json` | automation; aggregate pass rate, failures, latency, usage, provider errors |

A run result can be:

| Status | Meaning |
|---|---|
| `ok` | assertions passed |
| `fail` | semantic/assertion failure |
| `infra_error` | provider, REST, stream, reset, or report infrastructure failed |
| `skipped` | fixture or environment was intentionally unavailable |
| `interrupted` | run was stopped |

Do not mix `infra_error` with model quality. A provider 429 or missing AgentThing does not prove the prompt is bad.

## 12. How to judge the whole App

For a customer App, evaluate more than pass rate:

- **route stability:** same prompt uses the same intended tool route across runs;
- **model spread:** GPT and Claude both pass, or the team knows which model requires skill/playbook support;
- **clarification quality:** underspecified prompts ask targeted questions, not generic "provide more details";
- **grounding quality:** final answer preserves error identity and does not overclaim sampled data;
- **latency:** `chatMs` and provider waits are acceptable for the workflow;
- **token use:** usage is within the provider's operating budget;
- **configuration sensitivity:** failures point to missing taxonomy/skill/tool config rather than random behavior;
- **upgrade safety:** the same suite passes before and after Parler upgrades.

For early customer rollout, a practical target is:

```text
smoke.yaml       -> 100% ok or skipped
identity.yaml    -> no wrong canonical names
workflows.yaml   -> core business workflows pass on the chosen production model
errors.yaml      -> no misleading "entity missing" / "no data" rewrites
playbooks.yaml   -> stable workflows complete or clearly report evidence gaps
```

## 13. Debug loop when a case fails

Use this order:

1. Open `report.md` and identify the failed assertion.
2. Open `model-comparison.md` to compare other model responses.
3. Check the ordered tool path.
4. Check the exact tool arguments.
5. Check tool result evidence and error codes.
6. If the route is wrong, fix taxonomy, tool descriptions, skill, or playbook selection.
7. If the route is right but answer is wrong, tighten evidence rules or final answer instructions.
8. If evidence is missing, fix the tool, wrapper service, policy, or fixture.
9. If it is infrastructure, collect log and stream diagnostics for the same `conversationId`.

Do not fix every failure by adding prompt text. First decide whether the missing piece belongs in:

- taxonomy / semantic configuration;
- extended tool wrapper;
- skill;
- playbook;
- policy;
- application fixture data;
- model/provider configuration.

## 14. Recommended customer rollout sequence

1. Create `smoke.yaml` with 2-3 environment checks.
2. Create `identity.yaml` with 5-10 labels users actually say.
3. Create `workflows.yaml` with the top 5 business questions.
4. Add `extended-tools.yaml` for every App wrapper service.
5. Add `errors.yaml` for empty, missing, invalid, protected, and permission cases.
6. Add `playbooks.yaml` only after stable skill/workflow routes exist.
7. Run against one production-intended model.
8. Run the same suite against one alternate model.
9. Fix configuration/tooling before expanding the case count.
10. Keep the suite with the App and run it before every Parler upgrade or major configuration refresh.

This discipline lets an implementation team say, with evidence, "Parler understands this App's business language well
enough to support users," instead of relying on a few successful manual demos.
