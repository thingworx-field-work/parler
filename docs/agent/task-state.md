# Agent task state

Status: **v1a**, **v1b (AlwaysOn)**, and **v1b.2 (mid-turn `get_agent_skill` checklist merge)** are implemented in `parler-agent`, `parler-ui`, and the normative contracts (`task.state` wire).

This document defines explicit task state for `parler-agent`.

The goal is to make the agent's intermediate work visible to the next LLM round as compact, server-authored evidence. This is not a planner design. Tool execution remains driven by the existing `AgentLoop`.

## Layers

Task state has three layers. Each later layer is additive to the one before it.

### v1a: evidence ledger and LLM injection

v1a is the base layer.

It includes:

- a per-turn `AgentTaskState`
- typed `AgentTaskEvidence` rows derived from real tool calls and real tool results
- compact ephemeral injection of recent evidence before each LLM API call
- unit tests for rendering, truncation, zero-row evidence, and protected-value non-leakage

v1a by itself has no skill checklist parsing, requirement-to-evidence matching, UI wire frames, persistence, or model-authored plans (the first three come from v1b).

v1a fixes the empty DataTable failure: if `GetDataTableEntries` succeeds with zero rows, the next LLM round sees structural evidence that the tool succeeded and returned `0 rows`.

Structured evidence adapters exist for `invoke_service` and `fetch_cached_result` only; there is no generic fallback for other tools in the LLM evidence ledger (see **v1a Evidence Adapters**).

### v1b: structured progress wire, UI panel, skill checklist matching

v1b adds user-visible progress on top of v1a.

It includes:

- `parler-task-checklist-v1` fenced JSON in slash-loaded skill bodies
- requirement rows with lifecycle state
- conservative requirement-to-outcome/evidence matching
- `task.state` server-to-client wire frames
- UI current-state panel for the active assistant turn

v1b builds on v1a's evidence schema, renderer, injection lifecycle, and tests. Its `task.state` wire frame is normative in `CONTRACTS/API_CONTRACT.md` and `CONTRACTS/UI_CLIENT_PROTOCOL.md`.

v1b applies to Parler AlwaysOn paths only. Synchronous `Chat` and `ChatAsync` service paths do not emit `task.state`.

### v1b.2: mid-turn dynamic skill checklist merge

v1b.2 extends v1b.

It includes:

- parsing `parler-task-checklist-v1` fences from successful mid-turn `get_agent_skill` tool results
- appending newly loaded skill checklist items to the current turn's `TaskProgressV1b`
- emitting a full replacement `task.state` snapshot after a successful merge
- preserving lightweight dynamic skill identity across HITL pause/resume so continuation can rebuild the same checklist shape
- regression tests for merge, invalid input, duplicate handling, and HITL continuation rebuild

It excludes:

- automatic skill selection
- parsing model-authored checklist text
- retroactively matching already-finished tool calls against newly loaded checklist rows
- per-tool match adapters such as `match.hierarchyNode`
- durable persistence or cross-turn task-state restoration
- any new `task.state` wire shape

## Non-Goals

Task state must not become:

- an authoritative LLM-authored plan
- hidden chain-of-thought storage
- a scheduler
- autonomous background work
- cross-turn project memory
- a raw transcript store
- a raw table snapshot store
- a general conditional workflow engine
- automatic skill selection

The LLM may read task state. The LLM is not the authoritative writer of task state.

## Turn Boundary

Task state is per user turn.

One turn means one user message and the complete cluster of LLM API calls, tool executions, and HITL pause/resume paths needed to finish that message.

The same `AgentTaskState` spans multiple LLM calls inside one `AgentLoop.run`. It is discarded when that turn ends.

Task state is not written to `_conversations`, `AgentMessageStream`, or HITL snapshots. It is in-memory dynamic context for the current turn only.

When a new turn starts after JVM restart or Stream rehydration, it starts with empty task state.

## Loop Limits

Task state does not define the loop budget.

Current execution remains bounded by:

- `maxIterations`: maximum LLM API rounds, default `10`
- `agentTimeout`: total wall-clock timeout, default `3600000` ms

There is no explicit `maxToolCallsPerTurn` today. If one LLM response contains multiple tool calls, the current loop executes all of them within that one iteration.

Task state makes incomplete evidence visible but does not change the loop contract.

## v1a Data Model

### AgentTaskState

Illustrative internal shape:

```json
{
  "schemaVersion": 1,
  "requestId": "req-123",
  "conversationId": "conv-456",
  "goal": "Please return 3 rows from PTCTS.KepwareHelper.ChannelPathCache_DT.",
  "status": "executing",
  "evidence": [
    {
      "evidenceId": "e1",
      "sequence": 1,
      "correlationKey": "call_abc",
      "toolCallId": "call_abc",
      "tool": "invoke_service",
      "status": "ok",
      "targetType": "Thing",
      "targetName": "PTCTS.KepwareHelper.ChannelPathCache_DT",
      "operation": "GetDataTableEntries",
      "resultKind": "INFOTABLE_INLINE",
      "rowCount": 0,
      "totalCount": 0,
      "totalCountInferred": false,
      "cacheId": null,
      "sampleOnly": false,
      "errorCode": null,
      "protectedOmissions": false,
      "whenEpochMillis": 1778342400000
    }
  ],
  "openIssues": []
}
```

This is not a wire contract. It is an implementation guide for Java classes and compact LLM rendering.

### AgentTaskEvidence

`AgentTaskEvidence` holds structural fields only:

| Field | Type | Meaning |
|-------|------|---------|
| `evidenceId` | string | Stable local id for rendering, such as `e1`. |
| `sequence` | int | Intra-turn order, starting at 1. |
| `correlationKey` | string | Internal pre/post execution update key. Use `toolCallId` when present; otherwise a synthetic key such as `seq:3`. |
| `toolCallId` | string nullable | Provider tool-call id when available. |
| `tool` | string | Canonical tool name after Parler routing. |
| `status` | enum | `in-progress`, `ok`, `error`, `blocked-by-approval`, `expired`. |
| `targetType` | string nullable | Known target entity type, such as `Thing`. |
| `targetName` | string nullable | Target from tool arguments or resolver, not arbitrary result content. |
| `operation` | string nullable | Service/property/query operation. |
| `resultKind` | string nullable | Structured result envelope kind. |
| `rowCount` | int | Returned rows, or `-1` when not applicable. |
| `totalCount` | int | Total rows/entities/points, or `-1` when unknown or not applicable. |
| `totalCountInferred` | boolean | True when total count is inferred rather than authoritative. |
| `cacheId` | string nullable | Cache id when the result is backed by a cache. |
| `sampleOnly` | boolean | True when visible rows are a sample or truncated subset. |
| `errorCode` | string nullable | `TaskStateErrorCode` value when `status=error`. |
| `protectedOmissions` | boolean | True when direct PASSWORD protection omitted data. |
| `whenEpochMillis` | long | Local server timestamp for ordering/debugging. |

The renderer must render only from typed structural fields. It must never reach back into raw tool result JSON or arbitrary result strings.

`targetName` is rendered in the LLM evidence block when it comes from the tool arguments or Parler resolver. The rationale is product value: the LLM already used the target to request the tool call, the evidence block is not persisted, and the empty-DataTable answer is not useful if the evidence says only "a Thing returned 0 rows." The v1b `task.state` wire frame does not carry `targetName` (see **v1b `task.state` Wire Frame**).

### Status Semantics

Evidence row statuses:

| Status | Meaning |
|--------|---------|
| `in-progress` | Tool call has been requested and recorded, but no result has returned yet. |
| `ok` | Tool returned successfully and the evidence row is complete. |
| `error` | Tool returned or threw an error; `errorCode` should be populated when available. |
| `blocked-by-approval` | Tool execution is paused by HITL approval. |
| `expired` | HITL approval expired before execution completed. |

v1a has no requirement rows, so `pending` and `satisfied` are not evidence statuses.

v1b requirement rows use:

- `pending`
- `in-progress`
- `satisfied`
- `failed`
- `blocked-by-approval`
- `cancelled`
- `expired`
- `not-applicable`

### TaskStateErrorCode

`TaskStateErrorCode` is a central enum with a single mapper (`TaskStateErrorMapper`). Adapters must not invent ad hoc error strings.

Current enum values (authoritative list: `parler-agent/.../taskstate/TaskStateErrorCode.java`; keep this table aligned):

| Code | Meaning |
|------|---------|
| `UNKNOWN` | Error shape is known, but no stable code can be mapped. |
| `TOOL_EXECUTION_FAILED` | Tool threw an unexpected exception. |
| `TOOL_RESULT_INVALID` | Tool returned a malformed result envelope. |
| `ENTITY_NOT_FOUND` | Target entity was not found. |
| `SERVICE_NOT_FOUND` | Target service was not found. |
| `SERVICE_LOOKUP_FAILED` | Service or metadata lookup failed before invocation. |
| `PERMISSION_DENIED` | Platform permission denied the operation. |
| `PARAMETER_INVALID` | Tool or service parameter validation failed (includes legacy `INVALID_PARAMETERS` JSON mapped at the adapter). |
| `PROPERTY_METADATA_UNRESOLVED` | Requested property metadata could not be resolved, so no value was read. |
| `CACHE_MISS` | Requested cache id is missing or expired. |
| `INVALID_TIME_RANGE` | Requested time window is invalid or unusable. |
| `PROTECTED_VALUE_OMITTED` | Requested evidence was fully blocked by direct PASSWORD protection. |
| `PROTECTED_VALUE_READ_BLOCKED` | Direct PASSWORD-backed read blocked. |
| `PROTECTED_VALUE_WRITE_BLOCKED` | Direct PASSWORD-backed write blocked. |
| `PROTECTED_VALUE_INPUT_BLOCKED` | Direct PASSWORD-backed input blocked. |
| `UPSTREAM_TIMEOUT` | Platform or provider timeout. |
| `HITL_REJECTED` | User rejected the approval request. |
| `HITL_CANCELLED` | User cancelled the approval request. |
| `HITL_EXPIRED` | Approval expired before execution completed. |

Expansion is allowed, but all new codes must be added to the enum and mapper, not emitted as free-form strings.

## v1a Evidence Adapters

v1a uses a whitelist of structured adapters:

- `invoke_service`
- `fetch_cached_result`

### Adapter Contracts

`invoke_service`:

- for `INFOTABLE` or table-like result envelopes, set `rowCount` to returned row count
- set `totalCount` when the result envelope carries an authoritative total
- set `totalCount=-1` when unknown
- set `rowCount=0` for a successful empty table
- set `rowCount=-1` for scalar, JSON object, or non-row results where row count is not applicable
- set `operation` to the service name
- set `targetType` and `targetName` from resolved tool arguments, not service result content

`fetch_cached_result`:

- set `rowCount` to returned row count
- set `totalCount` when cache metadata carries it
- derive `sampleOnly` from the page metadata, not row values
- set `cacheId` from the requested or resolved cache id
- never render row values

Adapter JSON paths:

| Adapter | Condition | Evidence field | Source |
|---------|-----------|----------------|--------|
| `invoke_service` | top-level `status == "success"` | `resultKind` | top-level `resultKind` |
| `invoke_service` | `resultKind == "INFOTABLE"` | `rowCount` | top-level `rowCount`; fallback to `rows.length` only when `rowCount` is absent |
| `invoke_service` | `resultKind == "INFOTABLE"` | `totalCount` | same as `rowCount` |
| `invoke_service` | `resultKind == "INFOTABLE"` | `sampleOnly` | `false` |
| `invoke_service` | `resultKind == "INFOTABLE"` | `cacheId` | `null` |
| `invoke_service` | `resultKind == "INFOTABLE_LARGE"` | `rowCount` | top-level `sampleRows.length` |
| `invoke_service` | `resultKind == "INFOTABLE_LARGE"` | `totalCount` | top-level `totalRows` when present; otherwise `sampleRows.length` with `totalCountInferred=true` |
| `invoke_service` | `resultKind == "INFOTABLE_LARGE"` | `sampleOnly` | `true` |
| `invoke_service` | `resultKind == "INFOTABLE_LARGE"` | `cacheId` | top-level `cacheId` when non-empty; otherwise `null` |
| `invoke_service` | scalar / object / `void` / `null` result | `rowCount` | `-1` |
| `invoke_service` | error envelope | `errorCode` | top-level `errorCode`, top-level `code`, or exactly one-level nested `error.code`, mapped through `TaskStateErrorCode` |
| `fetch_cached_result` | top-level `status == "success"` | `rowCount` | top-level `returnedRows` |
| `fetch_cached_result` | top-level `status == "success"` | `totalCount` | top-level `totalRows` when present; otherwise `returnedRows` with `totalCountInferred=true` |
| `fetch_cached_result` | top-level `status == "success"` | `cacheId` | top-level `cacheId` |
| `fetch_cached_result` | top-level `status == "success"` | `sampleOnly` | `hasMore == true OR offset > 0 OR returnedRows < totalRows` |
| `fetch_cached_result` | top-level `status == "success"` | row values | never read for evidence |
| `fetch_cached_result` | error envelope | `errorCode` | top-level `errorCode`, top-level `code`, or exactly one-level nested `error.code`, mapped through `TaskStateErrorCode` |

Tools other than `invoke_service` and `fetch_cached_result` **do not** produce task-state evidence rows; the ledger stays empty for those calls (v1b still tracks them as progress items).

Error extraction is exact-key only: top-level `errorCode`, top-level `code`, or exactly one-level nested `error.code`. Deeper nested paths such as `error.cause.code` are not parsed. If no exact-key match exists, use `UNKNOWN`. Do not regex or string-search natural-language output.

Adapters should parse JSON envelopes and typed result objects. They should not use broad string matching against natural-language tool output.

## Renderer Rules

The LLM-facing task-state rendering is compact Markdown.

### Goal Line

The `Goal:` line is the only non-structural element in v1a rendering.

It exists for product value: it grounds the recent evidence in the current user request, which improves the model's chance of using a zero-row evidence row correctly in the final answer.

Normative rules:

- render the resolved current-turn user message used by `AgentThing.buildLlmTurnContext`: normally the slash-cleaned text, but the trimmed raw user-authored directive when registered slash tokens consume the whole cleaned message
- cap it with `maxGoalChars`
- suffix truncated text with `... truncated`
- do not claim the Goal line is secret detection or redaction
- do not apply semantic redaction
- no other non-structural text may be added to task-state rendering

Goal truncation controls prompt size. It does not make pasted credentials safe if the user places them inside the capped prefix.

Example:

```text
## Recent Tool Evidence

Goal: Please return 3 rows from PTCTS.KepwareHelper.ChannelPathCache_DT.

- e1 invoke_service Thing PTCTS.KepwareHelper.ChannelPathCache_DT GetDataTableEntries: ok, 0 rows.
```

Rendering rules:

- Structured answer evidence: `EvidenceAssessment` / `EvidenceAssessmentAggregator` feeds the model-facing task-state
  projection; see [`evidence-grounded.md`](./evidence-grounded.md). Bug 015 retired language-specific final-prose
  predicates and the runtime detect/log/rewrite hook, so successful assistant text passes through unchanged.
- render from `AgentTaskEvidence` structural fields only
- never echo raw `content` from tool results
- never echo raw property values
- never echo arbitrary DataTable/Stream rows
- never echo PASSWORD values
- never echo user-supplied secret strings from tool-derived evidence
- for result lists, render counts and cache ids, not item values
- use stable, short phrases generated by Java

Except for the capped `Goal:` line, structural evidence is the only data source for this new LLM-visible surface.

## Token and Size Caps

Hard caps:

| Cap | Default | Meaning |
|-----|---------|---------|
| `maxGoalChars` | `200` | Maximum chars rendered from the resolved current-turn user text. |
| `maxEvidenceRows` | `30` | Maximum rows rendered into the LLM task-state block. |
| `maxSummaryChars` | `200` | Maximum chars per rendered evidence bullet line (suffix `... truncated` when exceeded). |
| `maxTaskStateChars` | `8000` | Maximum complete framed task-state provider-row length; the data renderer reserves the `TaskStateLlmInjector.FRAMING_PREFIX` characters before truncating. |

Character counts use Java `String.length()` UTF-16 code units, matching the convention used by conversation-continuity truncation.

When over budget:

1. keep the newest evidence rows
2. drop oldest evidence rows first
3. keep any active `blocked-by-approval` row if possible
4. append one structural omission line such as `Older evidence omitted: 7 rows.`

Do not exceed `maxTaskStateChars`.

Log once per turn at DEBUG when truncation occurs:

- rendered task-state chars
- total evidence count
- rendered evidence count
- dropped evidence count
- whether the Goal line was truncated

## Prompt Injection Lifecycle

Task state is injected as ephemeral dynamic system context **immediately before each `LlmClient.chat` call** inside `AgentLoop.run`, using the same per-API-round pattern as `LlmUtcClockInjector`: insert the rendered block, call the LLM, then remove the row in a `finally` block. This differs from turn-scoped catalog/slash/time-anchor rows (which use `LlmTurnContext` indices and `EphemeralSystemStripHelper` at successful turn end).

**Implementation (normative):**

1. Create `AgentTaskState` when the user turn starts (`AgentToolContext` + `AgentThing` priming from the same resolved non-empty user text stored in the durable model-facing row, or the latest user row on HITL continuation).
2. Reserve `ParlerEphemeralSystemIndices.taskStateIdx` as `-1` at turn build — dynamic task-state rows are **not** part of the turn-end ephemeral strip bundle (they never survive past each LLM round).
3. Before each `llmClient.chat`, insert rendered `Recent Tool Evidence` first, then the UTC clock row — both immediately before the trailing `USER` message for that round (same pattern as other per-API-round ephemerals; task-state is not “after” UTC in the message list).
4. Remove the task-state row in `finally` for that round.
5. Do not persist injected task-state rows to `_conversations`, `AgentMessageStream`, or HITL snapshots.

The reset point for the **ledger** is the end of the user turn's `AgentLoop.run` cluster. The **injected Markdown row** is per LLM API round only.

On rollback of the conversation tail, task-state injection does not add persistent rows beyond the rollback checkpoint (same as other per-round ephemerals removed before history is trimmed).

**HITL continuation:** When `runParlerPostToolAgentLoop` runs after approval, cancel, or reject, the fresh `AgentTaskState` must be **seeded** with one completed evidence row for the gated tool (same `toolCallId` / `correlationKey` as the gated call) so the next LLM round sees structural evidence, not only raw `toolResult` JSON. Approve + `invoke_service` uses the real service JSON; cancel/reject use `error` rows with `HITL_CANCELLED` / `HITL_REJECTED`.

## Tool Execution Updates

### Before Tool Execution

Record an `in-progress` evidence row when a known tool call is about to execute.

For **`invoke_service`**, run **`ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall`** (or the same resolver step the executor uses) **before** the pre-execution hook, and pass the **post-preparation** `ToolCall` into task-state tracking. Structural fields (`targetType`, `targetName`, `operation`) must reflect resolver-corrected arguments (for example GenericThing-derived template names normalized to root **`Thing`**), not the model's pre-correction `entityType` text. Early-error JSON paths still pair `beforeExecution` / `afterExecution` with the `ToolCall` returned from the prep object (same provider `toolCallId`); that `ToolCall` must carry merged arguments whenever the resolver's working copy differs from the original JSON (entity-type normalization, service-field hoisting into `parameters`), not only on the success path.

Correlation rule:

- exactly one open `in-progress` row is allowed per executing `correlationKey`
- `sequence` is incremented at pre-execution time, immediately before each tool call's `in-progress` row is created
- use provider `toolCallId` as `correlationKey` when present
- if `toolCallId` is absent, generate a synthetic key at pre-execution time, such as `seq:<sequence>`
- post-execution update must match the existing row by `correlationKey`
- if no matching row exists, append an `error` evidence row with `errorCode=TOOL_RESULT_INVALID`
- if a duplicate open row exists for the same key, close the newer row as `error` with `errorCode=TOOL_RESULT_INVALID`

Examples:

- `invoke_service`: entity type, entity name, service name
- `get_property_values`: thing name and property count
- `query_entities_by_taxonomy`: taxonomy target and hierarchy argument when available
- `query_*_history`: target, property, and time inputs
- `set_property_value`: write intent, target thing, and property name

Pre-execution rows do not count as successful evidence.

### After Tool Execution

Update the existing row to `ok` or `error`.

For tabular results, extract:

- `resultKind`
- returned row count
- total count when known
- whether total count is inferred
- `cacheId`
- `sampleOnly`

For entity queries, extract counts, not returned entity names.

For property reads, extract property count and omission flags, not property values.

For errors, extract:

- `TaskStateErrorCode`
- whether the error is retryable when that is structurally known
- no arbitrary error payload echo

### HITL Path

HITL gating applies to any whitelisted adapter whose target service or write operation is gated by data-operation policy. This includes `invoke_service`.

When a tool pauses for HITL:

- set evidence status to `blocked-by-approval`
- record target and operation
- preserve the same `correlationKey`
- do not store proposed protected values

After approval resolution:

- `approved`: continue execution and update the existing row by `correlationKey` to `ok` or `error`
- `cancelled` or `rejected`: update the existing row by `correlationKey` to `error` with a stable user-decision code or keep a separate decision evidence row
- `expired`: update to `expired`

The final answer must not claim the blocked operation completed unless later evidence says it did.

Do not update HITL rows by list index after resume; use `correlationKey` lookup so suspend/resume cannot attach the result to the wrong evidence row.

## Final Answer Rule

Canonical final-answer semantics and the injected **Final-answer evidence rule** text live in [`evidence-grounded.md`](./evidence-grounded.md) (**Final Answer Contract** and **Prompt Surface**). `LeadingStablePromptComposer` emits that deployment-stable rule in the leading system row. `AgentTaskStateRenderer` emits only the per-round **Recent Tool Evidence** data view; `TaskStateLlmInjector` frames it as server observations before planning. This section lists behavioral examples only.

Examples (evidence-driven behaviour):

- `status=ok`, `rowCount=0`: the query succeeded and returned no rows; do **not** claim the target entity/table/service is missing unless a matching structured error code says so.
- `sampleOnly=true` without a follow-up cache-aware tool using a live `cacheId`: do not claim full-table analysis, extrema over all rows, or "all rows".
- `cacheId` present on sample/large evidence: use cache-aware tools when the user asks for full-table work; if no live `cacheId` is available, do not promise paging or remaining cached rows.
- `status=error` with structured `errorCode`: preserve that category (for example `CACHE_MISS`, `ENTITY_NOT_FOUND`, `SERVICE_LOOKUP_FAILED`); do not rewrite one failure class into another.
- `errorCode=CACHE_MISS` or `INVALID_TIME_RANGE`: explain or repair per tool hints; do not present cached data or the window as valid when evidence says otherwise.
- `protectedOmissions=true` with `status=ok`: partial success with omissions; do not ask the user to reveal the protected value. See **Protected Omission Semantics** below for mutual exclusion with `errorCode=PROTECTED_VALUE_OMITTED`.
- `status=error`, `errorCode=PROTECTED_VALUE_OMITTED`: the requested read was fully blocked by direct PASSWORD protection.
- `blocked-by-approval`: wait for HITL resolution or explain that approval is pending.
- Emitted table/chart frames: final prose must not contradict their row counts or sample-only metadata.

## Security and Protection

Task state obeys the same direct protection boundary as other LLM-visible surfaces.

Rules:

- never store cleartext `BaseTypes.PASSWORD` values
- never store raw user-supplied secrets in tool-derived evidence
- do not infer secret status from unresolved metadata
- record that protected data was omitted, not the value
- render from structural fields only
- keep key-name redaction as observability hygiene, not as an expanded security boundary

The capped `Goal:` line is an explicit user-text carve-out. It is not sanitized and should not be described as protected against credentials pasted by the user.

The renderer should have a unit test that feeds a tool result containing a literal sentinel such as `topsecret-password-value` and asserts that the rendered task-state block contains zero occurrences of that substring.

### Protected Omission Semantics

Use `protectedOmissions` and `PROTECTED_VALUE_OMITTED` for different shapes:

| Evidence shape | Meaning |
|----------------|---------|
| `status=ok`, `protectedOmissions=true`, `errorCode=null` | The operation succeeded, but some direct PASSWORD data was omitted from otherwise useful evidence. |
| `status=error`, `protectedOmissions=false`, `errorCode=PROTECTED_VALUE_OMITTED` | The operation could not produce useful evidence because the requested target itself was directly PASSWORD-protected. |

A row must not carry both `protectedOmissions=true` and `errorCode=PROTECTED_VALUE_OMITTED`.

Do not expand this rule into semantic secret detection or "sensitive-looking" field names. It is only about direct PASSWORD protection.

## Persistence

Task state is not persisted.

Do not persist full raw rows in task state.

## v1a Test Matrix

### Renderer zero-row test

Construct:

```text
tool=invoke_service
status=ok
targetType=Thing
targetName=PTCTS.KepwareHelper.ChannelPathCache_DT
operation=GetDataTableEntries
rowCount=0
totalCount=0
```

Expected:

- rendered evidence contains `0 rows`
- rendered evidence does not contain `not found`
- final-answer rule text distinguishes zero rows from missing entity

### Renderer protected-value non-leak test

Construct a synthetic raw tool result that contains `topsecret-password-value`, then produce structural evidence from it and render task state.

Expected:

- rendered output contains zero occurrences of `topsecret-password-value`
- evidence may contain `protectedOmissions=true`

### Goal carve-out test

Construct:

- cleaned goal contains `topsecret-password-value` within `maxGoalChars`
- synthetic raw tool result also contains `topsecret-password-value`
- structural evidence does not include the sentinel

Expected:

- rendered output contains exactly one occurrence of `topsecret-password-value`
- that occurrence is in the `Goal:` line
- no evidence row contains the sentinel
- when the sentinel appears after `maxGoalChars`, rendered output contains zero occurrences

### Truncation test

Construct more than `maxEvidenceRows` rows and enough generated summaries to exceed `maxTaskStateChars`.

Expected:

- newest rows are kept
- oldest rows are omitted
- output length is at or below `maxTaskStateChars`
- omission line is present

### HITL lifecycle test

Construct a write-like tool call that enters HITL.

Expected:

- pre-approval status is `blocked-by-approval`
- rejected/cancelled path does not become `ok`
- expired path becomes `expired`
- approved path updates after real execution

### HITL correlationKey stability test

Construct a whitelisted tool call, such as `invoke_service`, that pauses for HITL.

Expected:

- pre-execution row has `correlationKey=k`
- pause transitions the same row to `blocked-by-approval`
- resume updates the row by `correlationKey=k`
- no orphan `in-progress` row remains
- no second row is created for the same completed operation

### Correlation test

Construct one LLM response with multiple tool calls, including duplicate tool names.

Expected:

- each pre-execution row has a unique `correlationKey`
- synthetic keys include a per-call `sequence` and remain unique when one LLM response contains multiple tool calls without provider `toolCallId`
- post-execution updates modify the matching row
- no orphan `in-progress` row remains after all tools complete

### Error code mapping test

Construct adapter failures for representative categories.

Expected:

- emitted `errorCode` values are members of `TaskStateErrorCode`
- unknown mapped errors use `UNKNOWN`
- no adapter emits free-form error code strings

### Error exact-key test

Construct tool error outputs with:

- top-level `errorCode`
- top-level `code`
- nested `error.code`
- natural-language text containing words like "not found"

Expected:

- exact JSON keys are mapped through `TaskStateErrorCode`
- nested `error.code` means exactly one level under top-level `error`
- deeper paths such as `error.cause.code` are not parsed
- natural-language text is not parsed
- unmatched failed shapes use `UNKNOWN`

### Protected omission mapping test

Construct:

- partial-success row with omitted PASSWORD values
- fully blocked direct PASSWORD target

Expected:

- partial success uses `status=ok`, `protectedOmissions=true`, and `errorCode=null`
- fully blocked target uses `status=error`, `protectedOmissions=false`, and `errorCode=PROTECTED_VALUE_OMITTED`
- no row has both `protectedOmissions=true` and `errorCode=PROTECTED_VALUE_OMITTED`

### Row-count contract test

Each adapter tests `rowCount` semantics:

- empty table result -> `0`
- non-empty table result -> returned row count
- non-row result -> `-1`
- error result -> `-1`

### Empty DataTable end-to-end check

This check needs a live ThingWorx server (it is not an in-tree unit test).

Prompt:

```text
Please return 3 rows from PTCTS.KepwareHelper.ChannelPathCache_DT.
```

Expected:

- `invoke_service` calls `GetDataTableEntries`
- task state injects structural evidence with `status=ok` and `rowCount=0`
- final answer says the DataTable has no rows
- final answer does not say the DataTable is missing or misspelled

## v1b Structured Progress

v1b turns v1a's server-authored evidence ledger into user-visible current-turn progress.

It does **not** add a planner. It does **not** let the LLM author task state. It does **not** persist task state as conversation memory. The server remains the only writer, using slash-loaded skill metadata and actual tool lifecycle events.

v1b has two surfaces:

- LLM surface: the existing v1a `Recent Tool Evidence` injection continues unchanged.
- UI surface: new `task.state` wire frames let the user see what the agent is doing now.

### v1b Scope

v1b includes:

- parse `parler-task-checklist-v1` fences from slash-loaded skill bodies
- create per-turn requirement items from the parsed checklist
- create ad-hoc tool-progress items for tool calls that do not match a requirement
- update requirement/tool item state from real tool lifecycle, generic tool outcomes, and v1a evidence rows when available
- emit compact `task.state` frames to the active Parler UI
- render a current-state panel on the active assistant row
- add contract, Java, and UI tests for the new wire surface

v1b excludes:

- model-authored plans
- automatic skill selection
- mid-turn checklist merging from `get_agent_skill` (added by v1b.2)
- durable task-state persistence
- cross-turn task restoration
- hidden reasoning or chain-of-thought display
- raw result rows, property values, or tool-result JSON in `task.state`
- proof that dynamic fan-out is complete

The last exclusion is deliberate. If a skill says "for each returned robot, query properties," v1b can show observed property-query calls and counts. It cannot prove all returned robots were covered.

### v1b State Model

`AgentTaskState` keeps v1a evidence rows and adds v1b progress items.

v1b progress tracking is broader than the v1a LLM evidence adapter set. Every canonical tool call may update a progress item from lifecycle events and a generic success/error outcome. Tools with v1a-style evidence adapters can additionally attach `evidenceIds` and richer structural summaries such as row counts or cache state. Tools without an adapter still produce safe progress metadata, such as `in-progress`, `satisfied`, or a stable error code, but no raw args/results.

Internal item shape:

```json
{
  "id": "usa_robot_scope",
  "source": "skill",
  "kind": "evidence",
  "label": "List Stacking Robot Things under USA",
  "status": "satisfied",
  "tool": "query_entities_by_taxonomy",
  "evidenceIds": ["e2"],
  "observedCount": 1,
  "summary": "4 rows",
  "updatedAtEpochMillis": 1778342400000
}
```

Fields:

| Field | Meaning |
|-------|---------|
| `id` | Stable item id within the turn. Skill ids come from the checklist; ad-hoc ids can be generated as `tool-<sequence>`. |
| `source` | `skill` for checklist items, `tool` for ad-hoc tool-progress items, **`playbook`** for Playbook Engine node rows (**`docs/agent/playbook-engine.md`** §7). |
| `kind` | `evidence`, `guidance`, `synthesis`, or `tool`. |
| `label` | Short user-visible label. Prefer skill-authored labels/descriptions; do not synthesize from raw result values. |
| `status` | `pending`, `in-progress`, `satisfied`, `failed`, `blocked-by-approval`, `cancelled`, `expired`, or `not-applicable`. |
| `tool` | Canonical tool name when the item is tool-backed. Nullable for guidance/synthesis items. |
| `match` | Optional exact structural match keys. See matching rules below. |
| `evidenceIds` | Evidence rows that updated this item; empty when only generic tool outcome is available. |
| `observedCount` | Number of matching successful evidence rows seen for this item. |
| `summary` | Compact structural summary, such as `0 rows`, `4 rows`, `sample-only`, or an error code. |
| `updatedAtEpochMillis` | Server timestamp for ordering/debugging. |

Turn-level status values:

- `idle`: state exists but no tool work has started
- `executing`: at least one item is pending or in-progress
- `blocked-by-approval`: an active HITL gate is waiting for user decision
- `completed`: the assistant turn completed
- `failed`: the assistant turn ended with a terminal error

### Playbook progress

During an active Playbook run (`start_playbook` or structured slash), **`parler-agent`** emits **`task.state`** snapshots with **`items[].source=playbook`** and **`summary.playbookId`**. Node rows use playbook evidence labels and compact structural summaries only (no raw tool args/results). Skill v1b checklist emission is **suppressed** for that turn so the playbook panel does not compete with slash-skill checklist wire. Normative emission points and example JSON: **`docs/agent/playbook-engine.md`** §7.

### v1b Budgets

Hard caps. Character counts use Java `String.length()` UTF-16 code units, matching v1a.

| Cap | Default | Rule |
|-----|---------|------|
| `maxSkillItems` | `30` | Parser rejects a union of slash-loaded checklist items larger than this. |
| `maxItemsPerFrame` | `30` | Snapshot renderer keeps skill items first and drops oldest ad-hoc `source=tool` items when needed. |
| `maxTitleChars` | `200` | Truncate `title` with `... truncated`. |
| `maxLabelChars` | `200` | Truncate each item `label` with `... truncated`. |
| `maxSummaryChars` | `200` | Truncate each item `summary` with `... truncated`. |
| `maxFrameChars` | `8000` | Maximum serialized `task.state` JSON length. |

When `maxItemsPerFrame` drops ad-hoc tool items, append one structural omission item:

```json
{
  "id": "__omitted_tool_items__",
  "source": "tool",
  "kind": "tool",
  "label": "Older tool progress omitted",
  "status": "not-applicable",
  "summary": "7 items omitted"
}
```

The omission item itself counts toward `maxItemsPerFrame`. Skill checklist items are never dropped from the wire frame; the parser prevents over-large skill lists by enforcing `maxSkillItems`.

If the frame still exceeds `maxFrameChars` after string caps and ad-hoc omission, emit the smallest valid snapshot: request ids, status, summary counts, and a single omission item. Do not include raw args/results as a fallback.

### v1b Skill Checklist Contract

A slash-loaded skill body may include one fenced JSON block:

````markdown
```parler-task-checklist-v1
{
  "schemaVersion": 1,
  "title": "Cross-region Stacking Robot diagnosis",
  "requiredEvidence": [
    {
      "id": "taxonomy_stacking_robot",
      "kind": "guidance",
      "description": "Use taxonomy row Stacking Robot -> ThingShape PTCTDD.CellfabDataset.StackingRobot_TS."
    },
    {
      "id": "usa_robot_scope",
      "tool": "query_entities_by_taxonomy",
      "description": "List Stacking Robot Things under USA."
    },
    {
      "id": "germany_robot_scope",
      "tool": "query_entities_by_taxonomy",
      "description": "List Stacking Robot Things under Germany."
    },
    {
      "id": "robot_current_values",
      "tool": "get_property_values",
      "description": "Read current operational properties for scoped robots.",
      "cardinality": "one_or_more"
    },
    {
      "id": "regional_root_cause_comparison",
      "kind": "synthesis",
      "description": "Compare regions by alert category, alert rows per robot, and current readings."
    }
  ]
}
```
````

Parser rules:

- JSON only inside the fence
- top-level `schemaVersion` required and must equal `1`
- top-level `requiredEvidence` required and must be an array
- top-level `title` optional and must be a string when present
- unknown top-level keys fail fast
- each item requires `id` and `description`
- `id` must be unique within the union of all slash-loaded skill bodies for the turn
- `kind` optional; default is `evidence` when `tool` is present, otherwise `guidance`
- `tool` optional only for `guidance` and `synthesis` items
- an explicit `kind=evidence` item without `tool` is rejected
- `cardinality` optional; allowed values are `one` and `one_or_more`; default `one`
- `match` optional; when present, it must be an object containing only exact structural fields allowed below
- unknown item keys fail fast in v1b
- after concatenating all slash-loaded skill checklists, item count must be at or below `maxSkillItems`

When multiple slash skills are loaded in one user message, concatenate their `requiredEvidence` arrays in slash declaration order. Duplicate ids across the union fail fast with an error that names both source skills.

Minimal schema fragment:

```json
{
  "type": "object",
  "required": ["schemaVersion", "requiredEvidence"],
  "additionalProperties": false,
  "properties": {
    "schemaVersion": { "const": 1 },
    "title": { "type": "string" },
    "requiredEvidence": {
      "type": "array",
      "items": {
        "type": "object",
        "required": ["id", "description"],
        "additionalProperties": false,
        "properties": {
          "id": { "type": "string" },
          "kind": { "enum": ["evidence", "guidance", "synthesis"] },
          "tool": { "type": "string" },
          "description": { "type": "string" },
          "cardinality": { "enum": ["one", "one_or_more"] },
          "match": {
            "type": "object",
            "additionalProperties": false,
            "properties": {
              "targetType": { "type": "string" },
              "targetName": { "type": "string" },
              "operation": { "type": "string" },
              "resultKind": { "type": "string" }
            }
          }
        }
      }
    }
  }
}
```

### v1b Skill Loading Boundary

v1b parses checklists only from slash-loaded skill bodies, such as `/cross_region_operational_diagnosis`.

Do not parse checklists from:

- metadata-only skill catalog rows
- implicit skill matches
- mid-turn `get_agent_skill` loads

Mid-turn `get_agent_skill` checklist merge is the v1b.2 layer below.

### v1b.2 Dynamic Skill Checklist Merge

v1b.2 lets task-state become visible after the model discovers a skill during the same turn.

Without v1b.2, a turn would only have structured checklist rows when the user explicitly slash-loads the skill at turn start. With it, a skill the model loads mid-turn through `get_agent_skill` also shows its procedure as checklist progress in the UI task panel.

v1b.2 does **not** make Java a planner. The model still decides whether to call `get_agent_skill` and which tools to call next. Java only recognizes a real tool result, parses a real skill body, and adds server-authored progress metadata for subsequent tool lifecycle events.

#### v1b.2 Trigger

The only v1b.2 trigger is a successful canonical `get_agent_skill` tool result that contains a concrete skill body returned by the server-side skill registry / service layer.

Do not trigger dynamic checklist merge from:

- natural-language assistant text
- user text
- model-authored JSON
- metadata-only skill search rows
- failed `get_agent_skill` tool results
- tool results from unrelated tools

The stable dynamic skill reference for v1b.2 is the normalized `skill_name` argument from the successful `get_agent_skill` tool call. Today `get_agent_skill` returns the skill body as a STRING, not a JSON object with a canonical id field, so the durable key must come from the server-validated request argument rather than from the body text or a display title.

Stable reference schema:

| Surface | Field | Rule |
|---------|-------|------|
| `get_agent_skill` tool arguments | `skill_name` | Required short id matching **`/skills/<id>/SKILL.md`** on **`configurationRepository`**; normalize with `trim()` and the same short-id validation used by slash skills. |
| `AgentTaskState` runtime v1b.2 merge state | `dynamicSkillShortNamesInOrder` | Ordered unique list of successfully merged dynamic skill short ids for the active turn. |
| `PendingApprovalRecord` | `dynamicSkillShortNamesSnapshot` | Immutable ordered copy of `dynamicSkillShortNamesInOrder`, analogous to `slashSkillShortNamesSnapshot`. |
| HITL continuation rebuild | `SkillRegistryLoader.loadBody(agentThing, reg, shortName)` | Re-read each preserved dynamic short id through the unified registry (`reg` from `getPromptContextSnapshot().getSkillRegistry()`). |

Example flow:

```json
{
  "tool": "get_agent_skill",
  "arguments": { "skill_name": "CrossRegionRobotDiagnosis" },
  "result": "<STRING skill body containing optional parler-task-checklist-v1 fence>"
}
```

The extracted stable reference is `CrossRegionRobotDiagnosis`; the pending snapshot stores `dynamicSkillShortNamesSnapshot = ["CrossRegionRobotDiagnosis"]`; continuation rebuild re-invokes **`get_agent_skill`** with the same **`skill_name`**. v1b.2 depends only on fields that exist in the current tool result.

#### v1b.2 Source Discipline

The parser input is the skill body returned by the successful `get_agent_skill` call. It uses the same `SkillChecklistParser` rules as slash-loaded skills:

- exactly the same `parler-task-checklist-v1` fence format
- exactly the same item schema
- exactly the same UTF-16 budgets
- exactly the same direct-PASSWORD boundary: no raw rows, property values, protected values, hidden prompts, or chain-of-thought in `task.state`

If the skill body has no checklist fence, v1b.2 performs no checklist merge. The `get_agent_skill` tool call may still appear as ad-hoc observed progress, but the absence of a checklist is not an error.

If the skill body has more than one checklist fence, invalid JSON, invalid schema, duplicate ids, or a budget violation, v1b.2 rejects only the incoming checklist. Existing task-state remains intact.

#### v1b.2 Merge Semantics

Merging is forward-only.

When a dynamic checklist is accepted:

1. validate the incoming checklist independently
2. validate the incoming ids against all current skill-sourced items in the turn
3. validate the merged item count against `maxSkillItems`
4. append the new items after all currently visible **skill-sourced** items, not after ad-hoc tool items
5. initialize new item statuses with the same defaults as v1b (`evidence=pending`, `guidance=not-applicable`, `synthesis=pending`)
6. mark task-state dirty and emit one full replacement `task.state` snapshot at the next legal flush point

The normative item order for both live merge and HITL continuation rebuild is:

```text
[slash-loaded skill rows in slash declaration order]
[dynamic skill rows in get_agent_skill merge order]
[ad-hoc source=tool rows in tool sequence order]
```

Do not reorder existing skill-sourced items within their group. Do not reorder existing ad-hoc rows within their group. Do not delete existing items. Do not rewrite existing ad-hoc tool items into newly loaded skill rows. Do not scan earlier completed tool calls to retroactively satisfy new rows. Because the wire frame is a full snapshot, accepting a dynamic checklist may cause skill rows to appear before earlier ad-hoc tool rows in the next snapshot; this preserves the v1b "skill first, ad-hoc second" invariant.

That forward-only rule is deliberate. Mid-turn skill discovery is usually followed by future tool calls; retroactive matching would make the UI jump, complicate HITL replay, and reintroduce hidden planner-like behavior. If the model already ran relevant tools before loading the skill, those tools remain visible as ad-hoc observed progress.

Repeated loading of the same dynamic skill in one turn should be idempotent:

- if the same stable skill reference was already merged, ignore the second merge request
- do not append duplicate rows
- do not compare raw skill body text as the idempotency key
- if the skill has changed in the registry during the same turn, the new body applies to a future turn, not the current already-merged dynamic checklist

Duplicate item ids across different dynamic skills, or between slash-loaded and dynamic skills, reject the incoming checklist. The current turn keeps its existing task-state.

#### v1b.2 Failure Handling

Dynamic checklist merge failure is not a tool execution failure unless `get_agent_skill` itself failed.

For checklist-only failures:

- log a server-side warning with the stable skill reference and compact reason
- keep existing task-state unchanged
- do not leak the raw skill body or parser input into logs at normal levels
- update the ad-hoc `get_agent_skill` progress item with a compact metadata-only summary for real checklist failures: `DYNAMIC_SKILL_CHECKLIST_INVALID`, `DYNAMIC_SKILL_DUPLICATE_ID`, or `DYNAMIC_SKILL_BUDGET_EXCEEDED`
- keep benign classifications log-only: `DYNAMIC_SKILL_NO_CHECKLIST` and `DYNAMIC_SKILL_ALREADY_MERGED` do not change the ad-hoc progress item summary
- continue the agent turn

Stable task-state merge error labels:

| Label | Meaning |
|-------|---------|
| `DYNAMIC_SKILL_CHECKLIST_INVALID` | the body contained a malformed or schema-invalid checklist |
| `DYNAMIC_SKILL_DUPLICATE_ID` | an incoming item id collided with an existing skill item id |
| `DYNAMIC_SKILL_BUDGET_EXCEEDED` | accepting the incoming checklist would exceed v1b budgets |
| `DYNAMIC_SKILL_ALREADY_MERGED` | the same stable skill reference was already merged this turn |
| `DYNAMIC_SKILL_NO_CHECKLIST` | the skill loaded successfully but had no checklist fence |

These labels describe task-state merge handling only. They must not be conflated with tool error codes returned to the model. `DYNAMIC_SKILL_NO_CHECKLIST` is a benign server-side classification for telemetry / logs only: the `get_agent_skill` tool call already succeeded, and the task panel should not show a warning merely because a skill body has no checklist fence.

#### v1b.2 Hook Order

On a successful `get_agent_skill` call, v1b and v1b.2 hooks run in this order:

1. v1b `onBeforeTool` creates or matches the normal progress item for `get_agent_skill`
2. the tool executes and returns the skill body string
3. v1b `afterTrackedTool` updates the `get_agent_skill` progress item from the generic tool outcome
4. v1b.2 dynamic merge reads the already-executed `ToolCall` arguments plus the returned body string, validates any checklist fence, and appends dynamic skill rows when accepted
5. one full replacement `task.state` snapshot emits at the next legal flush point

Steps 1-3 always run regardless of whether step 4 accepts, rejects, or finds no checklist. The ad-hoc `get_agent_skill` progress item remains a successful tool item when the tool itself succeeded; checklist-only failures are reflected only by the compact summary rule above.

**No-evidence outcome classification:** `get_agent_skill` success returns a plain STRING skill body, not `{ "status": "success" }` JSON. Generic v1b tool-outcome classification for tools without v1a evidence rows (`NoEvidenceToolOutcome`) therefore treats a non-null result that is not an explicit JSON error envelope as `satisfied`. Explicit `{ "status": "error", ... }` remains `failed`; a missing / null tool result remains `failed`. For `get_agent_skill`, an empty STRING body is still a successful load with no checklist and maps to `DYNAMIC_SKILL_NO_CHECKLIST`, not a failed tool call. Do not change the LLM-visible `get_agent_skill` success output shape just to satisfy task-state.

Failure-envelope convention: any built-in tool that may fail and is not tracked by `AgentTaskStateHooks.shouldTrack` must return a JSON error envelope shaped like `{ "status": "error", ... }` on failure. For v1b.2 classification, an explicit JSON error envelope means a normal tool result whose top-level `status` string is `error`; other JSON failure idioms are outside the v1b.2 contract unless a specific evidence adapter adds them. Plain-text or empty success bodies are valid for tools with that contract, such as `get_agent_skill`. Plain-text failure output is outside the v1b.2 task-state contract and may be classified as `satisfied`; new built-in tools must follow the error-envelope convention or add explicit v1a evidence parsing. This convention covers executor results that reach task-state hooks as normal tool results; executor exceptions routed through task-state hooks must map to `failed`, not to the no-evidence success rule.

The `Goal:` line is unchanged by dynamic skill merge. It remains derived from the resolved user message for the current turn: normally slash-cleaned text, or the trimmed raw directive for a registered slash-only edge. Skill text must never author, replace, or append to the Goal line.

#### v1b.2 Matching After Merge

After a dynamic checklist is merged, subsequent tool lifecycle events use the same v1b matching contract:

- exact canonical tool name
- exact supported `match` keys
- earliest pending/in-progress matching item wins
- `rowCount=0` successful evidence still satisfies
- failed, cancelled, expired, or approval-blocked outcomes update status but do not satisfy
- ad-hoc tool item fallback when no requirement matches

There are no per-tool match adapters. In particular, `query_entities_by_taxonomy` follows the v1b rule: use distinct requirement ids and declaration order for region-scoped calls; do not rely on `match.targetName: "USA"`.

#### v1b.2 HITL Continuity

Dynamic checklist merge must remain compatible with HITL pause/resume.

When a turn enters HITL after one or more dynamic skills have been merged, `PendingApprovalRecord` preserves lightweight skill identity in order:

1. slash-loaded skill short names, as v1b already does
2. dynamic `get_agent_skill` short-name references, in merge order (`dynamicSkillShortNamesSnapshot`)

Do not store:

- parsed task items
- full `TaskProgressV1b` snapshots
- raw skill bodies
- raw tool results
- model-authored checklist text

On HITL continuation:

1. rebuild slash-loaded checklists from preserved slash short names, in slash declaration order
2. rebuild dynamic checklists by re-reading preserved `dynamicSkillShortNamesSnapshot` entries from the skill registry / service layer, in merge order
3. apply the same parser and union validation rules
4. initialize `TaskProgressV1b` from the rebuilt union using the same item order as live merge: slash skill rows first, dynamic skill rows second, ad-hoc rows last
5. apply v1a HITL seed rows and v1b replay helpers for the gated tool result / decision
6. emit a full `task.state` snapshot for the original `request_id`

If a dynamic skill cannot be re-read during continuation, log a warning with the stable short id and continue with the checklists that can be rebuilt. The approved/cancelled/rejected HITL outcome must still be delivered. The missing dynamic checklist may cause the gated outcome to appear as ad-hoc progress rather than satisfying its original dynamic row; that is acceptable for v1b.2 because task-state remains per-turn dynamic metadata, not durable workflow state.

Continuation rebuild applies the same incoming-dynamic-skill failure discipline as live merge. If a dynamic checklist rebuilt during continuation introduces a duplicate id against the slash union or an earlier dynamic checklist, log a warning naming the stable dynamic short id and the colliding id, skip only that dynamic checklist, and continue rebuilding the remaining checklists. This is the continuation equivalent of live `DYNAMIC_SKILL_DUPLICATE_ID`: the HITL decision must still complete, and existing rebuilt task-state must remain intact.

Continuation duplicate-id collisions are surfaced as server-side warnings only. The live-merge ad-hoc `get_agent_skill` summary update is not replayed during continuation because no new `get_agent_skill` tool call runs in that context.

#### v1b.2 Wire and UI Semantics

v1b.2 uses the existing `task.state` wire shape. No new frame type and no patch format are introduced.

After a successful dynamic merge, the server emits a full replacement snapshot with the newly appended items. The UI continues to replace the assistant row's prior `taskState` by `request_id`.

Because `task.state` is a full replacement snapshot, the item list may grow mid-turn after a v1b.2 `get_agent_skill` merge. This is not a patch stream and does not change the wire JSON shape.

The UI does not need a new component for v1b.2. It should naturally render the larger item list from the replacement snapshot. Existing rules still apply:

- `activity` remains chronological text
- `task.state` remains the authoritative **wire snapshot** for the active assistant row; the reducer **retains** `row.taskState` after `session.done` (see `chatSession.js`). **Final process-panel visibility** in `parler-ui` is separate: successful completed turns **hide** the `.task-state-panel` by default so ad-hoc-only progress does not sit under a misleading `satisfied: 0 / total: 0` header; see `lib/taskStatePanelVisibility.mjs`.
- `approval.required` remains the HITL gate
- `session.done` clears **`busy`**; it **does not** remove `row.taskState` from the assistant row (state retention for history / replay). That is **not** a requirement to keep the process panel visible in the final bubble after a successful answer.
- `session.error` may leave the last failed snapshot visible (and the view predicate keeps the panel for failed / attention-required snapshots)

`UiEventTaskState` carries no `conversationId`; the reducer attaches snapshots by `request_id`. Dynamic checklist merge does not change the UI event shape.

#### v1b.2 Implementation

Java:

1. `AgentTaskState` holds `dynamicSkillShortNamesInOrder`; it is mirrored into `PendingApprovalRecord.dynamicSkillShortNamesSnapshot` when a HITL approval is enqueued.
2. `TaskProgressV1bDynamicMerge` validates an incoming skill body, appends after the current skill-sourced rows, and marks the snapshot dirty without replacing existing state. `SkillChecklistDynamicBodyMerge` is the pure union helper used when bodies are appended to an existing slash union (continuation).
3. The merge runs on successful `get_agent_skill` completion, after v1b `afterTrackedTool` has handled the generic outcome and before the next LLM round.
4. Parser logic stays centralized in `SkillChecklistParser`; there is no second dynamic parser.
5. HITL continuation priming (`SkillChecklistContinuationMerge`) rebuilds slash + dynamic checklists before replaying the gated outcome.
6. All emissions use the existing legal flush points; nothing emits from background threads.

v1b.2 helpers (parser dispatch, merge logic, idempotency check, and HITL rebuild priming) live in the `taskstate/` package, not as private helpers on `AgentThing`.

UI: no dedicated component change; reducer tests prove a later full snapshot with appended items replaces the prior snapshot on the same assistant row.

#### v1b.2 Test Matrix

Java tests:

- successful `get_agent_skill` with one checklist appends rows and emits a full replacement snapshot
- successful `get_agent_skill` with no checklist leaves existing checklist intact
- successful `get_agent_skill` plain STRING result, including empty string, leaves the ad-hoc tool item `satisfied`; explicit `{"status":"error"}` leaves it `failed`
- repeated load of the same stable skill reference is idempotent
- stable dynamic skill reference is extracted from the normalized `skill_name` tool argument, not from the skill body or display title
- dynamic rows render after slash-loaded skill rows and before ad-hoc tool rows
- successful `get_agent_skill` hook order keeps the tool progress item satisfied even when checklist merge rejects
- duplicate id between slash-loaded and dynamic checklist rejects only the incoming checklist
- duplicate id between two dynamic checklists rejects the second checklist
- invalid dynamic checklist schema rejects only the incoming checklist
- `maxSkillItems` overflow rejects only the incoming checklist
- invalid / duplicate / budget failures update the ad-hoc `get_agent_skill` summary with compact metadata-only text
- no-checklist and already-merged cases remain log-only and do not show user-visible warnings
- already-finished ad-hoc tool rows are not retroactively matched after dynamic merge
- subsequent tool calls can satisfy dynamic checklist rows using normal v1b matching
- dynamic checklist + HITL approve/cancel/reject continuation rebuilds from preserved skill references and replays the gated outcome
- continuation still completes if a dynamic skill reference cannot be re-read, with compact warning and ad-hoc fallback
- continuation duplicate-id collision skips only the colliding dynamic checklist and still delivers the HITL outcome
- dynamic merge does not change the v1a Goal line
- `task.state` snapshots after dynamic merge contain no raw skill body, raw tool result, raw rows, property values, hidden prompts, or chain-of-thought

UI tests:

- later `task.state` snapshot with appended dynamic rows replaces the previous snapshot
- appended dynamic rows render in stable wire order
- `activity` and approval gate behavior is unchanged while the task panel grows

Contract / documentation checks:

- `API_CONTRACT.md` notes that `task.state` is a full replacement snapshot and may grow mid-turn after a v1b.2 `get_agent_skill` dynamic checklist merge; no JSON shape change is implied
- any change to `task.state` JSON or `UiEventTaskState` updates `CONTRACTS/API_CONTRACT.md`, `CONTRACTS/UI_CLIENT_PROTOCOL.md`, `CONTRACTS/CONTRACT_VERSION.md`, and `parler-ui/lib/types.js` in the same change

### v1b Matching Contract

Matching must be deterministic and conservative.

Only `kind=evidence` items are tool-matchable. `guidance` and `synthesis` items render in the item list but are excluded from `summary.total`, `summary.satisfied`, `summary.inProgress`, `summary.failed`, and `summary.blocked`.

Default non-counting statuses:

- `guidance`: `not-applicable`
- `synthesis`: `pending` while the turn is executing, `satisfied` on final assistant success, and `failed` on terminal `session.error`

Rules:

- a requirement with no `tool` is not tool-matchable
- `requirement.tool` must exactly equal the canonical outcome/evidence tool name
- tool names are canonical post-routing names
- optional `match` fields compare exactly against structural evidence fields (see **`query_entities_by_taxonomy`** note below)
- only successful tool outcomes can satisfy an evidence requirement
- when a row-count-capable adapter reports `rowCount=0`, that still satisfies the requirement because the tool completed and returned evidence
- failed, cancelled, expired, or approval-blocked outcomes update item status but do not satisfy it
- each tool outcome updates at most one pending/in-progress skill requirement
- if multiple pending requirements match the same tool outcome, choose the earliest checklist item
- if no requirement matches, create or update an ad-hoc `source=tool` item
- no fuzzy text matching
- no raw argument fingerprint matching
- no natural-language parsing of tool output

Use `match.resultKind` only for tools that return structured JSON success envelopes, such as `invoke_service` and `fetch_cached_result`. Do not set `match.resultKind` for tools that return non-JSON success bodies, currently `get_agent_skill`; the matcher cannot parse a result kind from a plain STRING body.

**`query_entities_by_taxonomy` (v1b):** The live tool uses PascalCase JSON arguments (`EntityType`, `EntityName`, …) and region scope is typically carried in `hierarchyNodeName`, not in `entityName`. The v1b matcher maps `match.targetName` / `match.targetType` / `match.operation` only to **`entityName`** / **`entityType`** / **`serviceName`** on the tool call JSON (the shape used by **`invoke_service`** and similar paths). Using `match.targetName: "USA"` (or any region label) **does not** match real `query_entities_by_taxonomy` calls in v1b. For multiple taxonomy queries in one checklist, use **distinct requirement ids and declaration order** without `match` on those rows: the first successful call satisfies the first still-pending row, the second call the next, and so on. There is no per-tool match adapter (for example a dedicated `hierarchyNode` key).

`summary` count semantics:

- `total`: count of `kind=evidence` skill items only
- `satisfied`: count of `kind=evidence` skill items with `status=satisfied`
- `inProgress`: count of `kind=evidence` skill items with `status=in-progress`
- `failed`: count of `kind=evidence` skill items with `status=failed`, `cancelled`, or `expired`
- `blocked`: count of `kind=evidence` skill items with `status=blocked-by-approval`
- ad-hoc `source=tool` items are not counted in `total`; they are displayed as observed progress

`cardinality` semantics:

- `one`: the first matching successful evidence row satisfies the item; later matching rows may be counted but do not change completion semantics.
- `one_or_more`: the first matching successful evidence row satisfies the item and later matching rows increment `observedCount`.

`one_or_more` is for display, not proof of complete fan-out.

Examples:

- USA and Germany scope queries should use two requirement ids and **checklist order** (omit `match` on those rows in v1b); do not use `match.targetName` for `query_entities_by_taxonomy`.
- A requirement for `query_entities_by_taxonomy` is not satisfied by `query_entities`.
- A successful empty table satisfies the requirement and renders as `0 rows`.
- A failed or approval-blocked tool call does not satisfy the requirement, but the UI should show the blocker or error code.

### v1b `task.state` Wire Frame

v1b adds a server-to-client wire frame:

```json
{
  "type": "task.state",
  "schemaVersion": 1,
  "request_id": "req-123",
  "conversation_id": "conv-456",
  "status": "executing",
  "title": "Cross-region Stacking Robot diagnosis",
  "summary": {
    "satisfied": 2,
    "total": 3,
    "inProgress": 1,
    "failed": 0,
    "blocked": 0
  },
  "items": [
    {
      "id": "usa_robot_scope",
      "source": "skill",
      "kind": "evidence",
      "label": "List Stacking Robot Things under USA.",
      "status": "satisfied",
      "tool": "query_entities_by_taxonomy",
      "evidenceIds": ["e2"],
      "observedCount": 1,
      "summary": "4 rows"
    }
  ]
}
```

Wire rules:

- `request_id` is required and must match the active assistant turn.
- `conversation_id` is required when the UI session has one.
- the frame is a full current-state snapshot for that request, not a patch
- the UI replaces the previous task state for that assistant row
- `items` order is stable: skill items first in checklist declaration order, then ad-hoc `source=tool` items in tool sequence order
- frames for other conversations must be dropped by the widget, same as other shared AlwaysOn frames
- `task.state` must be emitted only for the current turn; it is not history hydration in v1b
- no raw rows
- no property values
- no protected values
- no full tool result duplication
- no hidden reasoning text

`targetName` policy for v1b UI: the wire frame may include user-visible labels from the skill checklist and compact summaries from structural evidence. It does not expose a separate raw `targetName` field; the UI wire adapter also drops `targetName` on items.

Contract surfaces: `task.state` is specified in `CONTRACTS/API_CONTRACT.md` and, as a wire-to-UI event, in `CONTRACTS/UI_CLIENT_PROTOCOL.md`; the UI state shape is in `parler-ui/lib/types.js`.

### v1b Wire Emission Timing

Emit `task.state` after meaningful server-side state changes:

1. after slash-loaded skill checklist is parsed, before the first LLM round, if there are visible items
2. after v1b.2 dynamic `get_agent_skill` checklist handling, if the incoming checklist is accepted or a real checklist failure updates the ad-hoc `get_agent_skill` summary (`DYNAMIC_SKILL_CHECKLIST_INVALID`, `DYNAMIC_SKILL_DUPLICATE_ID`, `DYNAMIC_SKILL_BUDGET_EXCEEDED`); benign `DYNAMIC_SKILL_NO_CHECKLIST` and `DYNAMIC_SKILL_ALREADY_MERGED` remain log-only and do not require an extra snapshot
3. when a tool call starts
4. when a tool call is blocked by HITL
5. when an approval is **rejected**, **cancelled**, or **approved and resumed**; also when a user-thread HITL continuation runs after approval (**§ v1b HITL Continuity**). **Expiry:** when the gate closes because the pending approval **timed out**, the normal path is **`ParlerApprovalExpiryScheduler`** (or equivalent background delivery), which must **not** emit `task.state` (**deterministic coalescing rule** — no background-thread emission). The UI keeps the last snapshot (often `blocked-by-approval` on the gated row); **`approval.resolved`** with outcome `expired` plus **`session.done`** conveys timeout. v1b does **not** require a terminal `task.state` refresh on scheduler-driven expiry.
6. after a tool result is parsed into a generic outcome and, when available, evidence
7. before `session.done`, with final `status=completed`
8. before terminal `session.error`, with final `status=failed`

Emission should be coalesced when several changes happen in the same Java call stack. v1b does not need high-frequency streaming; correctness and stable ordering matter more than granular animation.

Deterministic coalescing rule:

- mark the snapshot dirty on any v1b state change
- emit at the next flush point: end of tool execution, entry into HITL pause, HITL continuation entry, before `session.done`, or before terminal `session.error`
- never emit from a timer, debounce worker, `ParlerApprovalExpiryScheduler`, or any other background thread
- if multiple state changes happen before the next flush point, emit one full snapshot

Emission runs inside the same conversation/request lock scope as the active AlwaysOn turn. The emitter should build the JSON snapshot while holding the lock and call `ParlerReceiveMessageSupport.send` through the same path used by other turn frames. Do not emit `task.state` after the turn lock has been released.

### v1b HITL Continuity

Do not snapshot v1b task items into `PendingApprovalRecord`. Task-state items remain per-turn dynamic state.

For HITL continuation, preserve only lightweight slash-skill identity needed to rebuild the checklist, such as `slashSkillShortNamesInOrder`, as pending metadata. This is not a task-state snapshot and does not store parsed items, evidence rows, raw tool results, or skill bodies.

v1b.2 adds lightweight dynamic skill references from successful mid-turn `get_agent_skill` merges. It still must not store parsed items, skill bodies, or task-state snapshots.

On HITL continuation:

1. rebuild slash-loaded checklists from preserved slash short ids, using the same parser and union rules as the original turn
2. when v1b.2 dynamic skill references are present, rebuild those dynamic checklists after slash-loaded checklists and in dynamic merge order
3. initialize v1b items from the rebuilt union using stable item order: slash skill rows first, dynamic skill rows second, ad-hoc rows last
4. replay safe tool outcomes from the continuation message history where available
5. apply the v1a HITL seed / approved gated-tool outcome to the matching item
6. emit a full `task.state` snapshot for the same `request_id`

Observed counts and `evidenceIds` after resume are best-effort. The UI may briefly replace the pre-HITL panel with a reconstructed snapshot, but it must stay attached to the same assistant row because `request_id` is unchanged.

### v1b UI Semantics

The UI should attach task state to the active assistant row by `request_id`.

Precedence rules:

- `activity` remains append-like chronological progress text
- `task.state` is the authoritative **wire snapshot** for the assistant row; the reducer keeps replacing **`row.taskState`** until the server stops sending frames for that `request_id`. **View:** while the turn is **active** (`busy` + matching `activeRequestId`), `parler-ui` shows the compact process panel when a snapshot exists. After **`session.done`**, the reducer **still stores** the final snapshot on the row, but **successful** completed turns **hide** the panel unless the snapshot is attention-required (failed / blocked / expired / cancelled / rejected **`status`**, or finite **`summary.failed`** / **`summary.blocked`** strictly greater than zero).
- `approval.required` remains the authoritative HITL decision gate
- `task.state` may show `blocked-by-approval`, but it must not replace the approval gate
- `session.done` clears **`busy`** and clears **`activeRequestId`**; it **must not** delete **`row.taskState`** (retention vs final panel visibility — do not conflate the two).
- `session.error` may leave the last `task.state` visible with `status=failed` (and the view keeps the panel for that case)

Minimal UI (when the panel is **shown** per the visibility rule above):

- compact panel under the assistant bubble
- title from the skill checklist when present
- counts from the `summary` object
- one row per item with status icon/text and compact summary
- collapsed by default only if the list is long; otherwise visible

Do not render:

- raw JSON
- raw rows
- raw property values
- hidden prompts
- chain-of-thought

Example: an `activity` frame may say `Looking up USA Stacking Robot assets...` while the `task.state` snapshot marks `usa_robot_scope` as `in-progress`. The UI should keep the activity line in chronological progress text and update the task panel row separately; do not render both as duplicate rows in the same region.

### v1b Implementation reference

Existing v1a classes (unchanged role):

- `AgentTaskState` — per-turn ledger; also holds optional `TaskProgressV1b`
- `AgentTaskEvidence`, `AgentTaskStateRenderer`, `TaskStateLlmInjector`, `AgentTaskStateHooks`, `TaskStateInvokeFetchParsers`, `TaskStateErrorMapper`, `TaskStateErrorCode`

v1b Java (`parler-agent/.../taskstate/` and call sites):

| Component | Role |
|-----------|------|
| `TaskProgressV1b` | Skill + ad-hoc progress items; exact matching (`matchToolArgs`, structural + deferred `resultKind`); wire-oriented caps and omission row |
| `TaskProgressV1b.TaskProgressItem` | Inner item type (there is no separate `AgentTaskItem` type) |
| `SkillChecklistParser`, `SkillChecklistParseException` | `parler-task-checklist-v1` fences, union, validation |
| `TaskProgressV1bHooks` | `onBeforeTool` / `afterTrackedTool` / `onBlocked` from tool execution and HITL |
| `TaskProgressV1bHitlReplay` | HITL continuation replay for approved `invoke_service` and terminal cancel/reject |
| `TaskProgressWireEmitter` | Build `task.state` JSON (`buildWireJson` / minimal overflow path), `flushSnapshot`, `emitFailedTurnEnd` |
| `ParlerReceiveMessageSupport.wireTaskState` | Wire frame helper (method on existing support class) |

Java integration points:

1. **`AgentThing`**: `primeAgentTaskStateForTurn`, `primeAgentTaskStateFromHistory` (slash snapshot → `SkillChecklistParser.unionFromSlashSkills` → `TaskProgressV1b.fromChecklist`); `emitParlerTaskStateTurnEnd`; post-approval `runParlerPostToolAgentLoop` with HITL seed + `TaskProgressV1bHitlReplay`; `TaskProgressWireEmitter.emitFailedTurnEnd` on loop failure (original turn and continuation).
2. **`AgentToolContext`**: active `AgentTaskState`, Parler stream ids, `Thing` downlink target for `TaskProgressWireEmitter`.
3. **Tool paths**: v1a hooks remain; v1b adds `TaskProgressV1bHooks` from `executeToolCall` / custom tools where tracked.
4. **HITL**: `PendingApprovalRecord` slash short-name snapshot; continuation rebuild + v1a seed rows + v1b replay helpers; **§ v1b Wire Emission Timing** item 4 for scheduler expiry (no new `task.state` from background).
5. **Terminal paths**: final `task.state` before `session.done` / failed snapshot before terminal `session.error` where applicable.

UI integration points:

1. `parler-ui/lib/wireAdapter.js` — map `task.state` wire to UI events
2. `parler-ui/lib/chatSession.js` — attach `taskState` to assistant row by `requestId`
3. `parler-ui/lib/types.js` — `TaskStateBlock` typing
4. `parler-ui/parler-ui.js` — `renderTaskStatePanel`; `lib/taskStatePanelVisibility.mjs` — final assistant-row visibility + bubble block order
5. `parler-ui/lib/taskStateWire.test.mjs` — adapter + reducer cases (see § *v1b Test Matrix*)
6. `parler-ui/lib/taskStatePanelVisibility.test.mjs` — render predicate for completed vs active turns

### v1b Test Matrix

**In-tree coverage:**

- **Java:** `SkillChecklistParserTest`, `TaskProgressV1bMatchToolArgsTest`, `TaskProgressV1bResultKindDeferTest`, `TaskProgressV1bHitlReplayTest`, `TaskStateInvokeFetchParsersTest`, `AgentTaskStateCorrelationTest`, `AgentTaskStateRendererTest`, `TaskStateErrorMapperTest`, `InvokeServiceResolvedTargetEvidenceTest` (invoke/evidence contract where applicable). Not covered by a dedicated unit test: an oversize `maxSkillItems` union, an end-to-end `AgentThing` AlwaysOn loop, exhaustive `maxFrameChars` / minimal-snapshot assertions, and multi-skill slash unions beyond the parser units.
- **UI:** `parler-ui/lib/taskStateWire.test.mjs` (wire → `UiEvent`, reducer attach and replace, `session.done`, history bootstrap drops `task.state`); `parler-ui/lib/taskStatePanelVisibility.test.mjs` (task-state panel show/hide predicate). Not covered automatically: drop-other-conversation, approval gate vs panel precedence, and full DOM layout of the assistant bubble.

The lists below are the behaviors the layer must satisfy.

Java tests:

- checklist parser accepts the Stacking Robot demo shape, including `guidance`, `synthesis`, and `one_or_more`
- parser rejects unknown top-level keys and duplicate ids
- parser rejects `kind=evidence` without `tool`
- parser rejects a slash-skill checklist union larger than `maxSkillItems`
- multiple slash-loaded skill checklists concatenate in slash declaration order and reject cross-skill id collisions
- matcher satisfies a `rowCount=0` successful evidence row
- matcher does not satisfy on error, HITL blocked, cancel, reject, or expired
- matcher uses exact tool names and optional exact structural `match` fields
- summary counts exclude `guidance`, `synthesis`, and ad-hoc `source=tool` items
- unmatched tool calls create ad-hoc tool items without raw args/results
- item order is stable: skill declaration order, then ad-hoc tool sequence order
- wire snapshot contains no raw rows, property values, or tool-result JSON
- wire snapshot enforces `maxTitleChars`, `maxLabelChars`, `maxSummaryChars`, `maxItemsPerFrame`, and `maxFrameChars`
- HITL flow emits `blocked-by-approval` and terminal resolved status
- HITL continuation reconstructs checklist from slash skill ids without storing parsed task items in `PendingApprovalRecord`
- coalescing emits one full snapshot per flush point and never emits from background threads
- final success emits `status=completed`; terminal error emits `status=failed`
- synchronous `Chat` / `ChatAsync` paths do not emit `task.state`

UI tests:

- `task.state` wire maps to a typed UI event
- reducer attaches the snapshot to the correct assistant row by `request_id`
- reducer replaces older snapshots with newer full snapshots
- reducer preserves stable item order from the wire snapshot
- frames for another `conversation_id` are dropped
- `session.done` clears **`busy`** and leaves **`row.taskState`** on the assistant row (reducer retention); **`taskStateWire.test.mjs`** covers that. **Final** process-panel visibility after a successful completed turn is **`taskStatePanelVisibility.test.mjs`** (view layer — panel hidden unless attention-required).
- approval gate remains authoritative when task state says `blocked-by-approval`
- `activity` text and `task.state` panel render in separate UI regions without duplicate rows

Contract tests:

- wire fixture with `task.state` round-trips through adapter/reducer without unknown-field dependence

## Guarantees

v1a guarantees:

- empty query results are represented as successful zero-row evidence
- final answers distinguish zero rows from missing entities
- sample-only and cache-backed result distinctions survive into the next LLM round
- tool errors remain structural and do not become invented successes
- HITL blocked/expired paths are visible to the model
- task-state injection never persists to Stream or HITL snapshots
- renderer tests prove protected sentinel strings do not leak

v1b guarantees:

- checklist parsing is deterministic
- matching is exact and testable
- `task.state` is a full current-state snapshot, not a patch stream
- UI progress is attached to the active assistant row by `request_id`
- `activity` and HITL approval gates keep their existing roles
- successful zero-row evidence is displayed as successful progress, not failure
- dynamic multi-call work can be displayed with observed counts without claiming complete fan-out proof
- slash-loaded skill procedures become visible without pretending Java is a planner
- no raw rows, raw property values, hidden prompts, or chain-of-thought appear in `task.state`

v1b.2 guarantees:

- mid-turn `get_agent_skill` checklists become visible as appended task-state rows without a new wire shape
- only registry/service-returned skill bodies are parsed; model-authored checklist text is ignored
- dynamic merge is forward-only and does not retroactively satisfy already-finished tool calls
- duplicate, invalid, or over-budget dynamic checklists leave existing task-state intact
- repeated loads of the same stable skill reference are idempotent
- dynamic rows keep the stable wire order `[slash skill rows][dynamic skill rows][ad-hoc tool rows]`
- real checklist failures are visible as compact metadata-only summaries, while no-checklist and already-merged cases stay quiet
- HITL continuation can rebuild dynamic checklist rows from lightweight skill references without storing skill bodies or parsed task snapshots
- failure to rebuild a dynamic skill during continuation does not block the HITL decision from completing
- no raw skill body, raw tool result, raw rows, property values, hidden prompts, or chain-of-thought appear in `task.state`
