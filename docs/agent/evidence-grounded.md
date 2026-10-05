# Evidence-grounded final answers

Status: **implemented** — model-facing evidence guidance (the final-answer evidence rule in the stable prompt plus
the data-only `Recent Tool Evidence` projection) and structured evidence classification (`AgentTaskState`,
`EvidenceAssessment`, `TaskStateErrorCode`, deterministic analysis envelopes). Parler does not adjudicate
natural-language answers: Bug 015 (Parler Agent 0.1.224) removed the language-specific prose predicates and the
final-answer detect/log/rewrite path, so successful assistant text passes through unchanged in every completion path.

This document defines how `parler-agent` keeps final assistant answers consistent with server-confirmed evidence. It builds on the implemented per-turn task-state ledger in [`task-state.md`](./task-state.md).

This is an answer-grounding design, not a planner design, not a chain-of-thought surface, and not a generic service risk policy.

## Current enforcement boundary

Parler deterministically enforces facts it owns as structured tool and data state: tool admission/execution,
authorization and HITL, PASSWORD-typed protection, cache/artifact lifecycle, schema and wire validation,
server-authored table/chart payloads, deterministic analysis, persisted-argument redaction, and egress. The model
receives compact structured evidence and instructions for narrating it, but Parler does not inspect arbitrary
natural-language output to decide whether it is causal, complete, ruled out, or otherwise semantically valid.

There is no prose-matching safeguard. Language packs, language detection, regex qualification/rewrite, or
LLM-as-judge gates would recreate an unsupported boundary.

## Evaluation

`docs/agent/evals/evidence_grounding.yaml` runs through `uv run agent-eval` (see §Evaluation Suite). Evaluation is
observational: text assertions measure response quality and never acquire runtime authority to block or rewrite
user-visible prose.

## Purpose

Final answers should stay attached to what ThingWorx tools actually returned.

The known failure pattern is:

1. The user asks for rows from a DataTable.
2. `GetDataTableEntries` runs successfully.
3. The result contains zero rows.
4. The final answer says the DataTable does not exist.

That is not a tool failure. It is a final-answer grounding failure: the model rewrote "successful empty result" into "missing entity".

The same class of failure can appear with:

- sample-only large tables
- missing or stale cache ids
- inferred totals
- tool errors with structured error codes
- permission or lookup errors
- protected-value omissions
- table/chart frames that are correct while the final prose contradicts them

## Design Principles

Evidence grounding follows these principles:

- **Java classifies evidence.** The LLM may phrase the final answer, but server-authored structural evidence determines whether a result was successful, empty, partial, inferred, blocked, or failed.
- **Current-turn evidence is authoritative for current-turn claims.** If a tool just returned `0 rows`, the final answer must not claim the target is missing unless an error code says that.
- **Do not upgrade evidence.** A sample is not a full table. An inferred total is not a platform-supplied total. A historical cache id is not a live cache id.
- **Do not rewrite error classes.** `CACHE_MISS`, `SERVICE_NOT_FOUND`, `ENTITY_NOT_FOUND`, and empty success are different facts.
- **No raw result storage.** Evidence summaries must be structural metadata, not raw rows, property values, or arbitrary JSON echoes.
- **No security boundary creep.** Protection handling remains limited to direct PASSWORD-backed metadata and existing protection codes. Evidence grounding must not invent new "sensitive-looking" classes.
- **Known error codes must survive the task-state renderer.** Do not solve missing codes by reading arbitrary raw tool JSON during rendering. Add stable `TaskStateErrorCode` enum values when final-answer policy depends on a code.

## Relationship to Task State

`docs/agent/task-state.md` already defines the substrate:

- per-turn `AgentTaskState`
- typed `AgentTaskEvidence`
- structural result fields such as status, target, operation, row counts, total counts, cache id, `sampleOnly`, error code, and protected omission markers
- ephemeral `Recent Tool Evidence` injection before each LLM call
- `task.state` UI progress frames for AlwaysOn

This document defines the next layer: how final answers consume that evidence.

The two documents must remain aligned:

- `task-state.md` owns the ledger shape, update lifecycle, and UI progress frames.
- `evidence-grounded.md` owns final-answer evidence guidance, the deterministic tool/data boundary, and answer-level
  evaluation guidance.

## Scope

### In Scope

- LLM-facing final-answer evidence rules.
- Java-rendered compact evidence summaries for the final answer stage.
- Result category semantics for success, empty success, partial success, inferred evidence, errors, blocked/protected outcomes.
- Evaluation cases that compare final prose with task evidence and UI table/chart metadata.

### Out of Scope

- Generic service risk policy storage and governance.
- Planner-generated task decomposition.
- Long-term memory or persisted raw tool results.
- Hidden chain-of-thought capture.
- Requiring citations for every simple non-platform answer.
- Rewriting every final answer through a second LLM pass.
- Expanding PASSWORD or protection boundaries beyond direct metadata-backed checks.

Generic service risk policy is intentionally left separate. Its hard problem is storage and lifecycle, and it is not part of answer grounding.

## Evidence Categories

The final-answer policy should reason over a small set of evidence categories derived from `AgentTaskEvidence` and emitted UI frames.

### Full Success

The tool returned complete data needed to answer the user.

Examples:

- an inline `INFOTABLE` that contains all rows needed for the answer
- a property read where all requested non-protected properties resolved
- a count query with an exact platform count
- a cached full-table transform from `tabulate_cached_result`

Answer behavior:

- answer directly
- mention filters, hierarchy scope, or time window when they affect trust
- avoid unnecessary uncertainty

### Empty Success

The tool succeeded and returned no matching data.

Examples:

- `GetDataTableEntries` returns zero rows
- `query_stream_data` returns zero rows for the applied time window
- `query_entities` returns `totalRows=0`
- `query_entities_by_taxonomy` returns `totalCount=0`

Answer behavior:

- say no rows/items/data matched the request
- preserve the target and filter context
- do not say the entity, table, service, or stream is missing unless the evidence has a matching error code

Good:

```text
GetDataTableEntries ran successfully on PTCTS.KepwareHelper.ChannelPathCache_DT, but it returned 0 rows.
```

Bad:

```text
The DataTable PTCTS.KepwareHelper.ChannelPathCache_DT does not exist.
```

### Partial Success

The tool returned a sample, page, or truncated result.

Examples:

- `INFOTABLE_LARGE` with `sampleRows` and `cacheId`
- `INFOTABLE_LARGE` with `sampleRows` but no `cacheId`
- `fetch_cached_result` returns a page with `hasMore=true`
- a UI table frame represents only the visible sample

Answer behavior:

- if a live `cacheId` exists and the user asks for full-table work, use `fetch_cached_result`, `tabulate_cached_result`, or `summarize_cached_result` before making a full-table claim
- if no `cacheId` exists, answer only from the visible sample and say the evidence is sample-limited
- do not claim complete maxima, minima, group counts, Top-N, or "all rows" from a sample

### Inferred Evidence

The server inferred a count or completeness marker rather than receiving it directly from the platform.

Examples:

- `totalRowsInferred=true`
- last-page inference
- empty-probe inference

Answer behavior:

- use the inferred result when it is the best available evidence
- preserve the inference marker when wording would otherwise imply an authoritative platform count

### Error Evidence

The tool failed with a structured error.

Examples:

- `ENTITY_NOT_FOUND`
- `SERVICE_NOT_FOUND`
- `SERVICE_LOOKUP_FAILED`
- `PARAMETER_INVALID`
- `CACHE_MISS`
- `INVALID_TIME_RANGE`
- `PROTECTED_VALUE_READ_BLOCKED`

Answer behavior:

- preserve the error category through `TaskStateErrorCode`
- use repair hints when available
- ask a narrow follow-up only when the evidence cannot be repaired locally
- do not convert one error into another

`TaskStateErrorCode` includes the codes used by built-in tools, including:

- `CACHE_MISS`
- `INVALID_TIME_RANGE`
- `SERVICE_LOOKUP_FAILED`
- `PROTECTED_VALUE_READ_BLOCKED`
- `PROTECTED_VALUE_WRITE_BLOCKED`
- `PROTECTED_VALUE_INPUT_BLOCKED`

`PARAMETER_INVALID` is the canonical task-state spelling. Tool envelopes that say `INVALID_PARAMETERS` are mapped to `PARAMETER_INVALID` at the adapter boundary, which leaves existing tool error JSON untouched.

### Protection Events

Protection evidence is a verified direct PASSWORD-backed block or omission.

Examples:

- `PROTECTED_VALUE_READ_BLOCKED`
- `PROTECTED_VALUE_WRITE_BLOCKED`
- `PROTECTED_VALUE_INPUT_BLOCKED`
- `PROTECTED_VALUE_OMITTED`
- `protectedOmissions=true`

Answer behavior:

- explain the block without exposing protected values
- do not ask the user to paste cleartext passwords or secrets into chat
- do not expand the rule to unresolved metadata, key-name heuristics, or "sensitive-looking" fields

See [`task-state.md`](./task-state.md) section `Protected Omission Semantics` for the mutual-exclusion rule between `protectedOmissions=true` (partial success) and `errorCode=PROTECTED_VALUE_OMITTED` (full block).

### HITL Pending State

HITL pending is primarily a task-state/progress condition, not a successful evidence row.

Examples:

- `blocked-by-approval`
- `HITL_REJECTED`
- `HITL_CANCELLED`
- `HITL_EXPIRED`

Answer behavior:

- while approval is pending, the UI `task.state` panel should show the waiting state
- if a final answer is produced after rejection, cancellation, or expiry, preserve the corresponding HITL code
- do not claim the gated write completed unless later evidence says it did

### Historical Context

Conversation continuity may rehydrate prior answers and compact historical evidence. Historical context is useful for conversation flow, but it is not a live current-turn tool result.

Examples:

- prior answer summaries
- old cache ids from before reconnect or JVM restart
- historical tool success summaries

Answer behavior:

- use historical context to understand follow-up questions
- do not assume old cache ids are still usable
- run fresh tools when the user asks for fresh data, full-table computation, or live state

`AgentTaskEvidence` is per-turn and does not carry a `historical` marker, so there is no structural historical evidence category; the rules above are prompt guidance only.

## Final Answer Contract

The final answer must follow these rules:

1. **Use evidence first.** If current-turn evidence exists for the user question, answer consistently with it.
2. **Treat empty success as data absence, not target absence.** `0 rows` with `status=ok` is not `ENTITY_NOT_FOUND`.
3. **Respect partial evidence.** Sample-only data supports sample-level statements only.
4. **Use live cache ids for full-table work.** If no live `cacheId` exists, do not promise paging or full-table transforms.
5. **Preserve error identity.** Explain or repair the structured error that actually occurred.
6. **Align with UI frames.** Final prose must not contradict emitted table or chart metadata.
7. **Keep provenance compact.** Include target, operation, count, scope, or time window only when it changes the user's trust in the answer.
8. **Keep protection narrow.** Explain verified PASSWORD-backed protection events without expanding the boundary to unresolved metadata or key-name guesses.

## Prompt Surface

`LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE` is the single runtime owner of the rule below. It is deployment-stable and is appended once to the leading system row. The separate `Recent Tool Evidence` block in `task-state.md` is a data-only, per-round observation view.

```text
Final-answer evidence rule:
- Treat Recent Tool Evidence as authoritative for this turn.
- status=ok with 0 rows means no matching rows/data were returned, not that the entity is missing.
- NO_FINDING requires completed sufficient evidence; INSUFFICIENT_EVIDENCE must not be narrated as no finding; ERROR is not partial success.
- sampleOnly=true cannot support full-table claims unless a live cacheId was used by a cache-aware tool.
- CACHE_MISS means cached data is not available; do not say more cached rows can be loaded or that cached data supports the answer.
- Preserve structured error codes; do not rewrite one failure class into another.
- Do not contradict emitted table/chart metadata.
- Do not upgrade associational or not-tested evidence into cause or ruled-out claims.
```

The rule refers to the current round's separately injected evidence, but its own bytes do not depend on that evidence. Keeping it in the stable prompt avoids re-sending identical instructions every round. `LeadingStablePromptComposerTest` pins the bytes; `AgentTaskStateRendererTest` proves the dynamic renderer does not duplicate them.

## Deterministic boundaries versus natural-language answers

Structured evidence can deterministically say that a query returned zero rows, a cache is absent, a result is
partial, an analysis is associational, or a PASSWORD-backed value is protected. Those facts remain authoritative
inside tools, analysis envelopes, task state, server-authored table/chart output, and egress.

Mapping arbitrary prose back to those facts is semantic interpretation. The former phrase matchers produced false
positives and covered only selected languages, so Bug 015 removed them rather than treating them as deterministic
guards. Parler may instruct the model and evaluate outcomes offline, but successful final text is not blocked,
qualified, replaced, or logged based on language-specific matching.

## UI and Wire Interaction

Evidence-grounded final answers should align with the UI surfaces:

- `task.state` is progress visibility, not the final answer.
- table and chart frames are server-authored data views.
- final text should summarize or interpret those frames without contradicting their metadata.

Evidence grounding adds no wire shape.

## Implementation

- **Rule owner.** `LeadingStablePromptComposer.FINAL_ANSWER_EVIDENCE_RULE` owns the rule bytes (pinned by
  `LeadingStablePromptComposerTest`); `AgentTaskStateRenderer` is data-only (`AgentTaskStateRendererTest`).
- **Error identity.** `TaskStateErrorCode` carries the stable error codes listed in `Error Evidence`; the legacy
  `INVALID_PARAMETERS` spelling maps to `PARAMETER_INVALID` before rendering.
- **Evidence categories.** There is no separate category-normalization helper: the renderer, parsers, analysis
  assessment, and tool/data enforcement paths read structural `AgentTaskEvidence` fields directly, without raw row
  parsing or natural-language result scanning.
- **No prose adjudication.** The language-specific test predicates and the runtime final-prose preflight (`Chat`,
  `ChatAsync`, `ParlerStreamToRemoteThing`, post-HITL completion) were removed by Bug 015. Automatic language-based
  correction is outside the deterministic runtime boundary; any such mechanism would need a language-independent,
  machine-verifiable contract rather than phrase matching or an LLM judge.

## Evaluation Suite

`docs/agent/evals/evidence_grounding.yaml` runs on the `agent-eval` harness. `skipUnlessEnv` accepts a string or a
list of strings (a list means skip unless all listed variables are set). Assertions prefer stream-visible tool
traces and structured metadata (`toolCalled` / `toolCalledTimesAtMost`, `toolResultRegex` for stable envelope
fields, narrow `finalNotRegex`, `traceParseErrorsAbsent`); text assertions measure answer quality and are not
mirrored into production runtime gates. Each skipped case carries `skipUnlessEnv`, `skipReason`, and a YAML comment
explaining the fixture requirement.

Case catalog:

| Case id | Mode | Trigger | Required assertions |
|---------|------|---------|---------------------|
| `cache_miss_not_available` | always | call `fetch_cached_result` with a deliberately bogus cache id | `toolCalled: fetch_cached_result`; `no_cached_data_available`; no claim that cached rows can still be loaded |
| `empty_datatable_success` | `skipUnlessEnv: AGENT_EVAL_HAS_EMPTY_DATATABLE` | `invoke_service` / `GetDataTableEntries` on the pinned empty DataTable fixture | `toolCalled: invoke_service`; `no_missing_target_claim`; final answer says zero/no rows |
| `taxonomy_zero_count_not_missing_entity` | always for the SCPA dev taxonomy | `query_entities_by_taxonomy` with `EntityType=ThingShape`, `EntityName=PTCTDD.CellfabDataset.StackingRobot_TS`, `LookupProperties={"PTCSerialNumber":"__parler_eval_zero_match_only__"}`, and no `hierarchyNodeName` | `toolCalled: query_entities_by_taxonomy`; `no_missing_target_claim`; final answer preserves zero-count semantics |
| `entity_not_found_not_service` | always | call `get_property_values` against literal bogus Thing `EvalNonExistentThing_parler_eval_only` for one harmless property such as `name` | `toolCalled: get_property_values`; `no_error_rewrite_entity_as_service`; final answer identifies missing Thing/entity rather than missing service |
| `invalid_time_range_not_no_data` | `skipUnlessEnv: [AGENT_EVAL_EVIDENCE_HISTORY_THING, AGENT_EVAL_EVIDENCE_HISTORY_PROPERTY]` | `query_property_history` with `startTime=2030-01-02T00:00:00Z`, `endTime=2030-01-01T00:00:00Z`, `thingName=${AGENT_EVAL_EVIDENCE_HISTORY_THING}`, and `propertyName=${AGENT_EVAL_EVIDENCE_HISTORY_PROPERTY}` | `toolCalled: query_property_history`; `toolResultRegex: INVALID_TIME_RANGE` only if visible in stream trace; otherwise final text must report invalid time range and must not claim ordinary no-data |

`taxonomy_zero_count_not_missing_entity` is always-runnable for the SCPA dev agents because their taxonomy is part of the test baseline; against an AgentThing without that taxonomy, gate it behind an explicit fixture instead of weakening the assertion.

Assertion groups (regexes live in the YAML):

| Group | Purpose |
|-------|---------|
| `no_missing_target_claim` | forbid missing entity/table/service wording after empty success |
| `no_cached_data_available` | forbid positive cache-availability and paging promises after `CACHE_MISS` or no live cache |
| `no_sample_overclaim` | forbid full-table claims from sample-only/no-cache evidence |
| `no_error_rewrite_entity_as_service` | forbid service-failure wording when the evidence class is missing entity/Thing |
| `no_error_rewrite_service_as_entity` | forbid entity/table-missing wording when the evidence class is missing service |
| `no_protected_solicitation` | forbid asking the user to paste/type password, token, secret, or credentials |

The object `agentMatrix` maps `gpt_5_5` → `SCPA_Demo_Agent` (GPT-5.5); use `--agent-matrix env` with
`AGENT_EVAL_AGENT_GPT_5_5` when the local AgentThing has another name.

## Test Matrix

| Case | Evidence | Expected final-answer behavior |
|------|----------|--------------------------------|
| Empty DataTable | `invoke_service` success, `rowCount=0` | says no rows; does not say DataTable missing |
| Missing DataTable | structured `ENTITY_NOT_FOUND` | says entity was not found |
| Missing service | structured `SERVICE_NOT_FOUND` | says service was not found |
| Service lookup failed | structured `SERVICE_LOOKUP_FAILED` | says service lookup failed; does not imply entity absence |
| Cache miss | `CACHE_MISS` | does not present cached data as available |
| Invalid time range | `INVALID_TIME_RANGE` | reports invalid time range or repairs it; does not claim no data |
