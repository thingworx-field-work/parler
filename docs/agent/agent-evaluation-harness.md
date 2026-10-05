# Agent Evaluation Harness

Status: v1a live runner implemented (`uv run agent-eval`); reset modes and `llmUsageJson` aggregation implemented per **`docs/agent/llm-usage-stream-telemetry.md`**.  
Document type: operator reference for repeatable `parler-agent` behavior evaluation.

## Purpose

The harness tests the Agent as an Agent: model routing, tool selection, tool arguments, evidence use, multi-turn repair, and final answers.

It is not a UI test runner. The widget should keep its own coverage for rendering, chart/table placement, Copy, Print, and websocket behavior. Agent behavior should be tested through a repeatable non-UI path.

## Motivation

Manual Parler UI testing is too slow for model and prompt iteration. A recent example:

- Sonnet correctly handled `/asset_pair_health Compare the health status of Jet Dryer assets ORD JetDryer 02 and AC JetDryer 01 over the past 24 hours`.
- `gpt-5.4-mini` misread `ORD JetDryer 02` as a hierarchy node and failed with `HIERARCHY_RESOLVE_NOT_FOUND`.

This is a tool-routing and argument-construction regression, not a UI regression. We need a batchable harness that can compare providers/models and preserve the tool trace for review.

## Design Principles

1. **Conversation-level, not final-text-only.** A case may contain one or more user turns. Assertions can inspect final assistant text and the persisted tool trace.
2. **Structured assertions over exact prose.** Exact natural-language equality is too brittle. Check key evidence, forbidden claims, tool names, and argument constraints.
3. **Multi-outcome scoring.** Some prompts have more than one acceptable behavior: infer the asset type for full credit, ask a targeted clarification for partial credit, or fail for generic confusion.
4. **Live first, fixtures later.** The first useful harness should run against the development ThingWorx system with real tools and real model providers. Recorded fixtures can follow once the behavior is stable enough to promote into CI.
5. **No runtime mutation of model config in v1.** Compare models by targeting different AgentThing names that are already configured for those providers/models.
6. **Reports must be reviewable.** Every run should produce a JSON report and a Markdown summary with conversation id, final answer excerpt, tool calls, pass/fail reason, token use when available, and latency.

## Scope

### v1a - Live REST Harness

A Python runner under `test_scripts/` (`test_scripts/agent_eval.py`), exposed through `uv` like the `get-application-log` helper.

Command:

```bash
uv run agent-eval --suite docs/agent/evals/smoke.yaml --agent-matrix env
```

Inputs:

- `DEV_SERVER` and `DEV_KEY` from `.env`
- suite file path
- agent matrix, mapping logical model labels to AgentThing names
- optional case id filter
- optional max cases / max turns / timeout
- optional `--stream-buffer-wait-s` (default 11; aligns post-`Chat` stream read with platform flush bound)
- optional reset mode: `fresh`, `stable_clear`, or `stable_hard_reset`
- optional `--agent-filter <label[,label...]>` to run a subset of the resolved matrix

The committed suites under `docs/agent/evals/` use one matrix label, `gpt_5_5`, mapped to the SCPA sample's
only AgentThing, `SCPA_Demo_Agent` (provider `AzureFoundryGPT55`, GPT-5.5). If your server uses another Thing
name, use ``--agent-matrix env`` and set ``AGENT_EVAL_AGENT_<LABEL>`` for each suite label (see
``test_scripts/agent_eval.py`` docstring). Example: ``AGENT_EVAL_AGENT_GPT_5_5=MyAgentThing``.

Outputs:

- `tmp/agent-eval/<timestamp>/report.partial.json` while running or after interruption
- `tmp/agent-eval/<timestamp>/report.partial.md` while running or after interruption
- `tmp/agent-eval/<timestamp>/model-comparison.partial.md` while running or after interruption
- `tmp/agent-eval/<timestamp>/report.json`
- `tmp/agent-eval/<timestamp>/report.md`
- `tmp/agent-eval/<timestamp>/model-comparison.md`

When `--out-dir <base>` is provided, the runner still creates a timestamped child directory under that base, for example `<base>/20260515-020301Z/`. This keeps repeated runs from overwriting earlier reports.

Partial reports are flushed after each completed case row and on interruption. A clean completion writes final report files and removes stale `.partial.*` files.

The runner executes cases serially. There is no repeat/flakiness mode; provider rate limits are part of what a run observes.

Live ThingWorx runs are diagnostic; they are not a CI gate.

## Runtime Path

For each model/AgentThing and each case:

1. Choose the conversation title:
   - `fresh`: create a fresh title such as `eval:<suite>:<case-id>:<model-label>:<uuid>`.
   - `stable_clear` / `stable_hard_reset`: use a stable title such as `eval:<suite>:<case-id>:<model-label>`.
2. Call `Things["<AgentThing>"].GetOrCreateConversationId({ title })`.
3. Require exactly one returned row and read `result.rows[0].conversationId`. If zero or multiple rows are returned, mark the run as infrastructure failure instead of guessing.
4. If reset mode is `stable_clear` or `stable_hard_reset`, call `Things["<AgentThing>"].ClearConversation({ conversationId })` before the first turn.
5. If reset mode is `stable_hard_reset`, physically remove existing `AgentMessageStream` rows for that conversation by querying `AgentMessageStream.QueryStreamEntries(source=conversationId, maxItems=<large>, oldestFirst=true)`, then calling `AgentMessageStream.DeleteStreamEntry({ streamEntryId: row.id })` for each row. Repeat until no rows remain or a safety loop limit is reached. This is evaluation-only cleanup for comparable test baselines; it must not change product `ClearConversation` semantics.
6. Use the returned `conversationId` for all turns in that case.
7. For each user turn, call `Things["<AgentThing>"].Chat({ message, conversationId, hostContext? })`.
8. Treat the synchronous `Chat` result string as the final assistant text for that turn.
9. After each turn's synchronous `Chat` returns, wait a fixed **stream buffer interval** (default **11 seconds**: platform *Maximum wait time before flushing stream buffer* is commonly 10s, plus 1s margin), then call `Things["AgentMessageStream"].QueryStreamData` **once** where row metadata `source` is exactly the same `conversationId`. Operators with a different flush bound should set `--stream-buffer-wait-s` accordingly. If the returned delta does not yet contain a complete turn trace (final `assistant` row with blank `toolCalls` and content consistent with the `Chat` return, truncation-safe, ignoring any trailing internal `context_checkpoint` rows), mark the turn as infrastructure failure and do not run semantic assertions.
10. Parse the persisted rows observed for this turn into:
   - user messages
   - assistant tool calls
   - tool results
   - token usage when present
11. Apply the turn's assertions and choose the best acceptable outcome.
12. Continue only the turns whose `whenPreviousOutcome` condition matches, if present.

In `fresh` mode, the runner must embed a unique random suffix in every title so `GetOrCreateConversationId` takes the create-new path. It should not depend on the returned title field for anything. In stable reset modes, the title intentionally has no random suffix so repeated runs reuse the same conversation id.

By default, omit the optional `systemPrompt` parameter on `Chat`. A case may opt into `systemPrompt` only when it is explicitly testing override behavior.

For trace parsing, each turn's trace is the set of stream rows for the conversation that were not already observed before that turn's `Chat` call. After the buffer wait, the runner skips any **trailing** rows whose role is exactly `context_checkpoint` — an internal continuity row that `docs/core/advanced-compact.md` §10.2 appends *after* the final assistant — and then accepts the trace only if the row beneath them is `role=assistant` with **blank** `toolCalls` (the final answer row, not an intermediate assistant tool-call row) and its `content` matches the synchronous `Chat` string truncation-safely. Otherwise the turn is `infra_error` (`stream_rows_not_visible_after_chat`) and semantic assertions are skipped.

That tolerance is one named role, not a general "skip what I do not recognise": any other unknown trailing role still fails the turn closed. The checkpoint row stays in the raw delta for audit, and it never becomes an assistant round — usage aggregation continues to gate on `role=assistant`, and a `context_checkpoint` row carries an empty `llmUsageJson` with zero token columns.

Use `Chat` rather than the AlwaysOn websocket path in v1. This keeps evaluation focused on Agent runtime behavior and avoids UI/websocket noise.

`Chat` serializes calls per `conversationId` inside the AgentThing. The harness still uses a fresh conversation id per case/model run, so future parallelism can be reasoned about at the conversation level.

Stable reset modes are intended for repeatable model comparisons and compaction A/B tests. `stable_hard_reset` is also useful when accumulated Stream rows would make read latency differ across test runs. It should be opt-in because it deletes persisted `AgentMessageStream` rows for the selected evaluation conversation.

If `ClearConversation` fails because active pending approvals exist, the runner should record `infra_error` with `phase = "clear_conversation"` and `code = "pending_approval_blocks_clear"`. It must not auto-resolve pending approvals in v1. For `stable_hard_reset`, the runner assumes no other process is appending rows to the selected `conversationId`; operators must not run two stable-reset jobs against the same suite/case/agent title concurrently. The default `QueryStreamEntries` page size should be large, e.g. `20000`, and configurable; the runner keeps deleting pages until a follow-up query returns zero rows or a safety loop limit is reached.

## Suite Format

Use YAML for scenario files. The repo already depends on `pyyaml`, and YAML is easier than JSON for multi-turn scripts, regexes, and comments.

Scenario files should live under `docs/agent/evals/`. They represent documented product expectations. The runner implementation lives under `test_scripts/`.

`version: 1` identifies the suite schema. Backward-incompatible suite-shape changes should bump this number.

Suite-level optional keys:

- `assertionGroups`: local reusable assertion lists, expanded during suite loading.
- `fixtures`: environment-dependent values used by cases. Missing fixture variables should skip the dependent case with a clear reason, not fail the run.

`agentMatrix` values may be plain strings or objects. A plain string means `{ thingName: <value>, defaultEnabled: true }`. Object values require `thingName`; `defaultEnabled` defaults to `true` when omitted. `defaultEnabled: false` labels are skipped unless explicitly selected with `--agent-filter` or the documented opt-in environment switch.

Example:

```yaml
version: 1
suite: asset-routing-smoke

assertionGroups:
  no_missing_target_claim:
    - finalNotRegex: "(?i)does\\s+not\\s+exist|cannot\\s+be\\s+found|misspelled"

agentMatrix:
  # Example Thing name; use --agent-matrix env when local names differ.
  gpt_5_5:
    thingName: SCPA_Demo_Agent
    defaultEnabled: true

cases:
  - id: asset_pair_jetdryer_display_names
    skill: asset_pair_health
    tags: [asset-resolution, multi-tool, chart-candidate]
    turns:
      - user: >
          /asset_pair_health Compare the health status of Jet Dryer assets
          ORD JetDryer 02 and AC JetDryer 01 over the past 24 hours
        acceptableOutcomes:
          - name: resolved_pair
            score: 1.0
            assertions:
              - finalContains: "SE.CellFab.Model.Workunit.ORD-JetDryer-02"
              - finalContains: "SE.CellFab.Model.Workunit.AC-JetDryer-01"
              - useAssertionGroup: no_missing_target_claim
              - toolCalled: query_entities_by_taxonomy
              - toolCalled: query_alert_summary
              - toolCalled: get_property_values
        rejectIf:
          - allOf:
              - finalContains: "HIERARCHY_RESOLVE_NOT_FOUND"
              - finalNotContains: "SE.CellFab.Model.Workunit.ORD-JetDryer-02"
              - finalNotContains: "SE.CellFab.Model.Workunit.AC-JetDryer-01"
          - toolArgEquals:
              tool: query_entities_by_taxonomy
              path: hierarchyNodeName
              value: "ORD JetDryer 02"
          - toolArgEquals:
              tool: query_entities_by_taxonomy
              path: hierarchyNodeName
              value: "AC JetDryer 01"
```

Multi-turn example:

```yaml
  - id: asset_pair_missing_type_then_clarify
    tags: [clarification, multi-turn]
    turns:
      - user: "Compare ORD 02 and AC 01 health over the past 24 hours"
        acceptableOutcomes:
          - name: inferred_asset_type
            score: 1.0
            assertions:
              - finalRegex: "(?i)Jet\\s*Dryer"
              - finalContains: "SE.CellFab.Model.Workunit."
          - name: targeted_clarification
            score: 0.6
            assertions:
              - finalRegex: "(?i)asset\\s*type|what\\s+type|which\\s+asset"
              - finalRegex: "(?i)ORD\\s*02|AC\\s*01"
        rejectIf:
          - finalRegex: "(?i)send more information|provide more details"
          - allOf:
              - finalContains: "HIERARCHY_RESOLVE_NOT_FOUND"
              - finalNotContains: "SE.CellFab.Model.Workunit."
      - user: "They are Jet Dryer assets."
        whenPreviousOutcome: targeted_clarification
        acceptableOutcomes:
          - name: resolved_after_clarification
            score: 1.0
            assertions:
              - finalContains: "SE.CellFab.Model.Workunit.ORD-JetDryer"
              - finalContains: "SE.CellFab.Model.Workunit.AC-JetDryer"
              - toolCalled: query_entities_by_taxonomy
```

Host-context example:

```yaml
  - id: scoped_region_count
    turns:
      - user: "How many Stacking Robots are in this region?"
        hostContext:
          key: asset_monitoring.query_scope
          context:
            page: Asset Monitoring
            queryParameters:
              networkName: PTCTDD.Cellfab.AssociationNetwork_NW
              selectedNetworkNode: SE.CellFab.Model.Region.USA
              selectedEntityTypes:
                - EntityType: ThingShape
                  EntityName: PTCTDD.CellfabDataset.StackingRobot_TS
        acceptableOutcomes:
          - name: used_host_scope
            score: 1.0
            assertions:
              - toolCalled: query_entities_by_taxonomy
```

`hostContext` is per turn. The runner passes it to `Chat` as a UTF-8 JSON string. Omit it unless the case explicitly tests Mashup scope behavior. Host Context v2 is prompt-only page guidance: the model must still pass explicit tool arguments such as `hierarchyNodeName` or `intersectThingNames` when a tool needs scope.

Case-level `skipReason`, `skipUnlessEnv`, and `requiresFixture` are allowed for fixture-dependent live cases. `skipUnlessEnv` may be a single environment variable name or a list of names. A string skips when that variable is unset; a list skips unless **all** listed variables are set. `requiresFixture` skips when the named suite `fixtures:` entry is not fully resolved. A skipped case should produce `status="skipped"` and should not count as a semantic failure. Use this for missing local fixtures such as allow-policy test services, an empty DataTable fixture, or a history thing/property pair; do not make the runner guess replacement entities.

## Assertion Types

v1a should support a small, useful set first.

### Assertion Groups

Suites may define `assertionGroups` and reference them with `useAssertionGroup`.

Rules:

- group expansion happens at suite load, before assertion validation
- unknown group names are suite-load errors
- groups may be referenced inside `acceptableOutcomes[i].assertions` and `turns[i].rejectIf`
- groups are not valid at case top level
- groups do not nest in v1
- there is no cross-suite shared group file in v1

### Final Text Assertions

- `finalContains`
- `finalNotContains`
- `finalRegex`
- `finalNotRegex`

These apply to the latest assistant final answer for the current turn.

### Tool Trace Assertions

- `toolCalled`
- `toolNotCalled`
- `toolArgEquals`
- `toolArgNotEquals`
- `toolArgContains`
- `toolArgRegex`
- `toolArgAbsent`
- `toolResultContains`
- `toolResultNotContains`
- `toolResultRegex`

Tool arguments should be parsed as JSON from persisted assistant tool calls. For `toolArg*`, `path` is a simple dotted path or JSONPath subset. v1 does not need full JSONPath.

If a persisted `toolCalls` field contains the truncation marker `...[truncated]`, the runner must not crash while parsing it. Assertions that need truncated tool-call JSON should fail with a structured trace reason such as `tool_args_truncated_in_stream`. Other per-row JSON parse failures should also become structured trace failures, not Python tracebacks.

`toolResultRegex` runs against concatenated role=`tool` content for the current turn.

### Count Assertions

- `toolCallCountAtLeast`
- `toolCallCountAtMost`
- `toolCalledTimesAtLeast`
- `toolCalledTimesAtMost`
- `turnCountEquals`
- `traceParseErrorsAbsent`

`toolCallCountAtLeast` and `toolCallCountAtMost` count total tool calls in the turn. `toolCalledTimesAtLeast` and `toolCalledTimesAtMost` count calls to one named tool only:

```yaml
- toolCalledTimesAtLeast: { tool: "start_playbook", count: 1 }
- toolCalledTimesAtMost: { tool: "query_property_history", count: 0 }
```

`traceParseErrorsAbsent` passes only when collected tool-call and usage JSON parse errors are empty. The boolean value is ignored; key presence is the assertion. These assertions help catch runaway loops, missing tool use, and malformed trace handling.

### Composite Assertions

- `allOf`
- `anyOf`
- `not`

Composite assertions may be used anywhere a normal assertion can appear, including inside `acceptableOutcomes[].assertions` and `rejectIf`.

Use `allOf` when a failure signal only matters in combination with missing recovery evidence. For example, a model may mention an intermediate `HIERARCHY_RESOLVE_NOT_FOUND` while still resolving the assets through taxonomy fallback. That should not be rejected if the final answer contains both real Thing names:

```yaml
rejectIf:
  - allOf:
      - finalContains: "HIERARCHY_RESOLVE_NOT_FOUND"
      - finalNotContains: "SE.CellFab.Model.Workunit.ORD-JetDryer-02"
      - finalNotContains: "SE.CellFab.Model.Workunit.AC-JetDryer-01"
```

Use `anyOf` for equivalent acceptable phrasings or multiple possible evidence markers. Use `not` sparingly when a positive assertion is clearer than adding another `finalNot*` / `toolNot*` primitive.

### Scoring

Each turn selects the highest-scoring acceptable outcome whose assertions pass and whose `rejectIf` assertions do not fire. If two passing outcomes have the same score, the one listed earlier in YAML wins. A failed `rejectIf` overrides acceptable outcomes for that turn.

If no acceptable outcome matches, the turn gets score `0` with a structured failure reason. Later turns with `whenPreviousOutcome` are skipped unless their condition matches the selected prior outcome.

Case score can be:

- sum of turn scores
- average of turn scores
- pass/fail threshold, default `>= 1.0` for single-turn cases

v1 should report both raw score and pass/fail.

## Initial Smoke Suite

Start with a deliberately small suite. It should catch model drift without becoming expensive.

1. **Taxonomy asset count**
   - Prompt: `how many asset types do you have? please list them`
   - Expected: taxonomy rows, not generic ThingTemplate inventory.

2. **Jet Dryer pair health**
   - Prompt: `/asset_pair_health Compare the health status of Jet Dryer assets ORD JetDryer 02 and AC JetDryer 01 over the past 24 hours`
   - Expected: resolve exact Thing names, do not use asset labels as hierarchy nodes.

3. **Missing asset type clarification**
   - Prompt: `Compare ORD 02 and AC 01 health over the past 24 hours`
   - Expected: infer asset type or ask targeted clarification.

4. **Stacking Robot cross-region diagnosis**
   - Prompt: `/cross_region_operational_diagnosis ... USA ... Germany ... Stacking Robot ...`
   - Expected: hierarchy nodes are valid only for region names, not individual asset labels.

5. **DataTable via invoke_service**
   - Prompt: `please show me 3 rows from AgentThreadDataTable by using invoke_service tool as it is a datatable`
   - Expected: `invoke_service` with parameters nested under `parameters`; no missing `maxItems` claim when the platform provides defaults.

6. **Trend chart**
   - Prompt: `please show me the trend of currentDraw in the last 30 minutes`
   - Expected: numeric history tool and chart-capable response.

7. **Empty DataTable**
   - Prompt: a known empty DataTable row request, using dev fixture `PTCTS.KepwareHelper.ChannelPathCache_DT` when present and empty.
   - Expected: empty data, not "entity does not exist".
   - If the fixture is missing or not empty, skip with `fixture_missing_empty_datatable`.

8. **Protection baseline**
   - Prompt: attempt to read a direct `PASSWORD` property, using a controlled test entity.
   - Expected: protected-value block/mask, not cleartext.

Focused suites should remain separate from `smoke.yaml`:

- `evidence_grounding.yaml`: empty DataTable success and bogus cache id `eval-bogus-cache-00000000-0000-0000-0000-000000000000`.
- `taxonomy_v2.yaml`: semantic resolver tools for **`identity-types.json`** v2; requires `AGENT_EVAL_TAXONOMY_V2=1` and `/taxonomies/identity-types.json` on the agent configuration repository (`docs/agent/taxonomy.md` §16). Includes **`queryParent`** echo checks for **Workunit Jet Dryer** (`resolve` + `list` cases). Fixture: `docs/agent/evals/fixtures/README.md`.
- `taxonomy_v2_unavailable.yaml`: `TAXONOMY_UNAVAILABLE` / no `stale` field; **must** use `--agent-matrix env` with `AGENT_EVAL_AGENT_GPT_5_5` pointing at an AgentThing whose repository has **no** readable `/taxonomies/identity-types.json`. If env is unset the case skips; if env is set but you run with the default yaml matrix, the placeholder thingName fails loudly (it does not auto-skip).
- `invoke_service_policy.yaml`: allow-hit and allow-miss fixtures supplied by environment variables; missing fixtures skip.
- existing Playbook suites stay as focused files. Do not add `includes:` or a `playbook_core.yaml` composition layer in v1. Use `docs/agent/evals/playbook-regression.md` as the operator runbook for the existing Playbook regression commands.

Do not expand to 50 cases before v1a is proven useful. Ten good cases are better than a large suite nobody runs.

## Reports

`report.json` should include:

- suite id
- timestamp
- environment URL host only, not full secrets
- agent/model label
- AgentThing name
- case id
- turn index
- reset mode
- conversation title
- conversationId
- reset metadata (`streamRowsDeleted`, `streamRowsRemainingAfterReset`) when applicable
- selected outcome
- score
- pass/fail
- assertion failures
- reject hits
- status: `ok`, `fail`, `infra_error`, `skipped`, or `interrupted`
- optional `failureKind`: `semantic_failed`, `assertion_failed`, `provider_error`, or `null`
- phase and code for infrastructure/interruption failures
- final answer excerpt
- full final answer text
- ordered tool call summary
- token usage when available
- usage/cache telemetry when `AgentMessageStream.llmUsageJson` is present
- latency per turn: `chatMs` (synchronous `Chat`), `streamBufferWaitMs` (fixed sleep aligning with stream flush bound), `streamQueryMs` (single post-wait `QueryStreamData` round-trip)

Use this phase taxonomy for infrastructure and interruption records:

| Phase | Meaning |
|-------|---------|
| `get_or_create_conversation` | resolving the conversation id |
| `clear_conversation` | `ClearConversation` reset call |
| `stream_hard_reset` | evaluation-only stream deletion loop |
| `chat` | synchronous AgentThing `Chat` REST call |
| `stream_query` | `QueryStreamData` / `QueryStreamEntries` reads |
| `assertion` | assertion evaluation |
| `report_write` | partial or final report file write |
| `interrupted` | interrupt handling path |

Finer details belong in `code`, not new phase values. Examples: `stream_rows_not_visible_after_chat`, `stream_query_before_chat`, `interrupted_between_chat_and_assert`, `keyboard_interrupt`, `sigterm`.

For multi-turn case rollup, `infra_error` takes precedence over semantic assertions because assertions on incomplete traces are invalid. Then apply reject hits as `failureKind="assertion_failed"`, score/pass-threshold failures as `failureKind="semantic_failed"`, and successful cases as `failureKind=null`.

For multi-turn cases, report each turn explicitly:

```json
{
  "turns": [
    {
      "turnIndex": 0,
      "skipped": false,
      "selectedOutcome": "targeted_clarification",
      "score": 0.6,
      "chatMs": 12034,
      "streamBufferWaitMs": 11000,
      "streamQueryMs": 112,
      "finalExcerpt": "Which asset type are ORD 02 and AC 01?",
      "finalText": "Which asset type are ORD 02 and AC 01?"
    },
    {
      "turnIndex": 1,
      "skipped": false,
      "selectedOutcome": "resolved_after_clarification",
      "score": 1.0
    }
  ]
}
```

Latency should split `chatMs`, `streamBufferWaitMs`, and `streamQueryMs`. The buffer wait is not HTTP time; it aligns the read with the platform stream flush configuration. `turnWallMs` sums these for the turn wall clock.

Token usage is per assistant round in `AgentMessageStream`. Per-turn totals should sum `promptTokens` and `completionTokens` across assistant rows observed for that turn.

When `llmUsageJson` exists on assistant rows, the runner should also aggregate:

- `inputTokens`
- `outputTokens`
- `cacheReadInputTokens`
- `cacheCreationInputTokens`
- `cachedPromptTokens`
- `reasoningTokens`
- provider request ids

When `llmUsageJson` is absent or an empty string, the runner falls back to the legacy columns: `promptTokens` also counts as `inputTokens`, `completionTokens` also counts as `outputTokens`, and provider cache fields are `0`. This fallback keeps old traces readable and does not require migration.

Provider usage fields should be summarized per AgentThing/provider. Do not publish a single cross-provider `totalInputTokens`, because Anthropic `inputTokens` means fresh input excluding cache read/create, while OpenAI/Azure `inputTokens` means total prompt tokens with cached prompt tokens reported as a subset. If `llmUsageJson` is malformed or contains a truncation marker, record a structured trace parse error and ignore usage telemetry for that row instead of raising a Python traceback.

`report.md` should be human-oriented:

- summary table by model
- failed cases first
- for each failure: prompt, final answer excerpt, tool path, and exact failed assertion
- links or copied conversationId for log lookup

`model-comparison.md` should also be emitted for every run. It is intentionally more verbose than `report.md` and is meant for model selection / prompt-quality review:

- one summary table across case + model rows
- status, score, token/cache usage, and ordered tool path
- each turn's full final answer text

Keep `report.md` compact for first-pass CI/review triage; put full answer bodies in `model-comparison.md` so humans can compare response quality without re-querying `AgentMessageStream`.

Exit codes:

| Code | Meaning |
|------|---------|
| `0` | all executed cases are `ok` or `skipped` |
| `1` | at least one semantic/assertion failure (`status="fail"`) |
| `2` | at least one `infra_error`, including provider errors |
| `3` | run interrupted by `KeyboardInterrupt` or `SIGTERM` |
| `4` | `--agent-filter` matched zero labels |
| `5` | final `report_write` failed after recovery attempt |

## Handling Rate Limits and Provider Errors

Provider 429 / 400 / timeout should be reported as infrastructure failures, not semantic failures, unless a suite explicitly tests error handling.

The runner should:

- mark provider HTTP failures as `infra_error`
- set `failureKind="provider_error"` when the failure happened in `phase="chat"`, the underlying error is HTTP 400/401/403/408/429/500/502/503/504, and the safe body indicates rate limit, quota, billing, context length, overload, or provider-side failure
- keep the raw safe error summary in the report
- continue with remaining cases unless `--fail-fast` is set
- avoid automatic retry in v1 unless explicitly requested

This preserves evidence while the system is under active development.

Do not scrape `ApplicationLog` inside the runner for provider classification in v1. The report should rely on the direct REST error and safe response body it already has.

## Host Context

Cases may specify `hostContext` when the intended behavior depends on Mashup scope. Default is omitted.

The Jet Dryer pair case should not pass `hostContext`, because the failure we want to catch is exactly that individual asset labels must not be treated as hierarchy nodes.

## Data Stability

Live cases run against a development ThingWorx system whose data can change. Therefore:

- smoke suite assertions should prefer stable identifiers and routing constraints over exact numeric values
- exact counts should be used only for intentionally stable fixtures, such as taxonomy row count when the demo app is known
- numeric alert values should be checked as optional evidence, not usually exact equality

Smoke cases should not assert exact live numeric values from mutable development data.

## Non-Goals

v1 does not:

- replace unit tests
- replace UI testing
- require exact final-answer prose
- mutate AgentThing provider/model configuration
- depend on an LLM judge for critical pass/fail decisions
- run as a mandatory CI gate
- validate every custom skill or every custom tool
- test scheduler behavior
- implement trace snapshotting, repeat/flakiness loops, or inspect subcommands in v1a

## Compaction A/B Validation

- Run the same suite twice for the same logical provider/model: once with replay compaction on (the default) and once on a server started with the JVM diagnostic property `com.thingworx.parler.llmReplayCompaction.disableUnsafe=true` (it applies to the whole JVM, not to one AgentThing).
- Prefer `stable_hard_reset` so both runs start from comparable Stream state.
- Compare semantic pass/fail before considering token savings successful.
- Compare `promptTokens`, `inputTokens`, cache fields, output tokens, tool path, and provider infra failures.
- There is no per-`Chat` compaction override.
