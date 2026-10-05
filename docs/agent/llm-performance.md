# LLM Performance

Status: **implemented** — `LLM_TURN_PERFORMANCE` telemetry, post-marker `tool_choice` behavior, presentation-phase fields, and the related `llm_usage` whitelist.

## Purpose

This document defines performance controls and turn telemetry for `parler-agent` LLM
turns. The motivating case was a utilization prompt that, even with a correct data path,
took about 116 seconds and 6 agent iterations.

Prompt:

```text
which assets have utilization 0% in 2025-09-10?
```

Observed correct data path:

```text
utilization_records -> 554 raw rows cached
tabulate_cached_result group_metric + having -> groups=172 matchCount=21
final answer -> 21 assets
```

Observed waste:

- `group_metric + having` already produced the complete filtered result set.
- The model still paged the 21-row result through `fetch_cached_result`.
- Several LLM rounds hit `LLM_RATE_ADMISSION action=wait`, around 19-21 seconds each.
- Routing rounds used the large previous requested-output default, typically `8192`.

The performance controls therefore target three things:

1. fewer LLM rounds after a complete answer set exists;
2. direct attribution of where turn time went, especially provider admission wait;
3. UI-visible diagnostics so manual live testing does not require log tailing.

## Design Rules

1. Routing and post-marker rounds use the operator-configured Provider / Agent
   max-output budget. `AgentSettings.maxTokens=-1` delegates to the Provider setting;
   positive values override. `AgentLoop` does not impose a hard-coded cap or default.
2. `rateWaitMs` is required. It is a real value aggregated from provider clients;
   placeholder values such as `-1` are not allowed. `llmWallMs` includes
   `rateWaitMs`; the two fields are not additive.
3. The UI Info window shows `turnWallMs` and `rateWaitMs` first, followed by supporting
   counters.
4. The performance fields are part of the wire contract (`CONTRACTS/UI_CLIENT_PROTOCOL.md`,
   `CONTRACTS/API_CONTRACT.md`).
5. There is no automated wall-time threshold; wall-time fields are diagnostics.
6. `firstToolCallCacheHit=unknown` is allowed only when the turn made no cached-tabular
   reader tool call at all.
7. The Agent runtime serializes tool execution at `maxConcurrency=1`. Multiple tool
   calls in a routing round are normal LLM behavior and are not flagged as a protocol
   violation. `toolExecutionMaxConcurrency` and `multiToolCallRoundsCount` expose turn
   telemetry.

## Non-Goals

Out of scope:

- lazy tool-schema loading;
- per-conversation quota or fair scheduling;
- provider failover or retry redesign;
- playbook rewrite of Utilization flows;
- batch or nightly eval infrastructure;
- chart-specific performance work;
- changing cached-table correctness semantics.

UI Info-window surfacing is in scope because it is the manual performance evaluation
path.

## Design Overview

The runtime behavior fits together as follows:

```text
routing rounds
  tools offered
  AgentLoop passes AgentSettings.maxTokens through (including -1 delegation)

cached-tabular tool result
  emits answerSetComplete=true only when it returns every output row safely

AgentLoop
  records completeAnswerSetSeen for this turn only
  forbids any later cached-result fetch from being silently invisible

post-marker final round
  empty tools list on the provider request (no `tools` / `tool_choice` on OpenAI/Azure;
  same omission on Anthropic); `LlmChatRequest` still flags `toolChoiceNone` for AgentLoop
  protocol checks
  model produces final answer from already-returned rows

terminal done frame
  carries sanitized llm_usage performance fields
  UI Info window shows turnWallMs + rateWaitMs prominently
```

The routing guide remains important, but it is not the only guard. The post-marker
request omits tool definitions on the wire; `toolChoiceNone` on `LlmChatRequest` is still
used for in-band protocol enforcement when the model returns tool calls on that round.

## Complete Answer Set Marker

The canonical marker contract lives in `docs/agent/query-spec.md` section 11. This design does
not redefine it; it depends on it.

Strict marker shape:

```json
{
  "answerSetComplete": true,
  "sampleOnly": false,
  "rowsOmitted": false,
  "returnedRows": 21,
  "totalRows": 21
}
```

`answerSetComplete=true` means:

```text
returnedRows == totalRows
and rows contains every output row
and row count / byte size / column count are within v1 caps
and no protected column is exposed
```

It never means "mostly complete", "representative", or "probably enough".

The marker is emitted by `tabulate_cached_result group_metric` (for example
`group_metric + having` producing a complete 21-row answer set). Other cached-tabular modes
may emit it only when they can prove the same strict conditions.

## Runtime Behavior

### 1. Marker Kill Switch

The marker emitter is controlled by a JVM system property, default enabled:

```text
parler.agent.answerSetComplete.enabled=true
```

The effective value is logged as `markerEmitterEnabled`.

When disabled:

- the executor returns the plain sampled/paged result shape for this path: no inline
  complete rows, and no `sampleOnly=false` / `rowsOmitted=false` complete-result hint on
  small results;
- the tool omits `answerSetComplete=true`;
- forced no-tool final-answer mode is not triggered by this marker path.

Omitting only `answerSetComplete=true` while keeping the inline complete-row payload would
not be enough: the routing guide's `sampleOnly=false with returnedRows == totalRows`
fallback would still change behavior.

### 2. Small Complete Rows

When `group_metric` qualifies under query-spec section 11 and the kill switch is enabled, return
full projected rows with the strict marker:

```json
{
  "status": "success",
  "resultKind": "CACHED_GROUP_METRIC_INLINE",
  "sourceCacheId": "...",
  "groupCount": 172,
  "matchCount": 21,
  "totalRows": 21,
  "returnedRows": 21,
  "answerSetComplete": true,
  "sampleOnly": false,
  "rowsOmitted": false,
  "rows": [
    {
      "EquipmentID": "...",
      "EquipmentDesc": "...",
      "running_duration": 0,
      "total_duration": 86400,
      "utilization_pct": 0
    }
  ]
}
```

When the marker is not safe, preserve existing sampled or paged behavior and omit
`answerSetComplete=true`.

### 3. Routing Guide Stop Rule

The marker and routing text must land together.

Model-facing guidance:

```text
If a tabular tool result has answerSetComplete=true, or has sampleOnly=false with
returnedRows == totalRows, answer from that result. Do not call fetch_cached_result again
unless the user explicitly asks for more columns, raw page browsing, or export/download.
Never infer a full answer set from sampleRows when answerSetComplete is absent.
```

The text is a secondary guard. The hard guard is the provider-level no-tool final round
after the marker.

### 4. Complete-Answer State Tracking

`AgentLoop` keeps per-turn state:

```text
completeAnswerSetSeen
```

It becomes true only after a tool result contains `answerSetComplete=true` or the strict
equivalent:

```text
sampleOnly=false and rowsOmitted=false and returnedRows == totalRows
```

Do not infer this state from `matchCount`, `totalRows`, sorted rows, `sampleRows`, or row
appearance alone. The flag is turn-scoped and must not leak into the next user message in
the same conversation.

After `completeAnswerSetSeen=true`:

- the next LLM request uses the post-marker requested-output budget;
- the next LLM request disables tools at the provider request level;
- any later attempt to dispatch `fetch_cached_result` increments
  `fetchAfterCompleteAnswerSetCount`;
- the turn summary records whether forced no-tool mode was actually applied.

Increment rule:

```text
Increment fetchAfterCompleteAnswerSetCount immediately before AgentLoop dispatches
fetch_cached_result when completeAnswerSetSeen is already true for the same turn.
```

Because the Agent dispatches tool calls serially today (`maxConcurrency=1`), "after" is defined by round
order and cannot be ambiguous inside a single provider response.

### 5. Requested Output Budget Default

`AgentLoop` passes `AgentSettings.maxTokens` through to every LLM round unchanged,
including `-1` (delegate to Provider per `docs/agent/llm-api-provider-parameters.md`).
Positive values override the Provider default. There is no implicit `2048` cap or
routing-only default inside `AgentLoop`.

| Round category | Requested max-output tokens on the wire |
|---|---|
| Tool-routing round | same as `AgentSettings.maxTokens` (often `-1` → Provider-resolved) |
| Round after `answerSetComplete=true` (post-marker) | same |
| Normal final answer without complete-answer marker | same |
| Explicit long-answer prompt | same (long-answer detection may still steer prompts; it does not change this passthrough) |

`requestedMaxOutputTokensTotal` in turn telemetry is the sum of **strictly positive**
per-round values chosen by `AgentLoop` before Provider resolution. Delegated
non-positive values are omitted from that diagnostic (the Provider still resolves them
to a positive upstream budget).

Provider / model-family note: OpenAI Chat Completions `max_tokens` is closer to a
visible completion budget on GPT-4.1-style models; `max_completion_tokens` on GPT-5.x /
reasoning models can include reasoning tokens as well as visible completion. Anthropic
`max_tokens` similarly bounds total generation including extended thinking. Parler keeps
the neutral internal name `requestedMaxOutputTokens` and does not promise uniform
"visible answer length" semantics across families.

Explicit long-answer prompts include phrases such as:

- `detailed report`;
- `explain thoroughly`;
- `write a report`;
- `export`;
- `full narrative`.

`AgentLoop` uses a conservative string detector for these phrases. Long-answer intent is
never inferred from hidden model state.

### 6. Tool Execution Concurrency

Today the Agent runtime executes tool calls with **`maxConcurrency=1`**, so multiple
tool calls returned in one LLM round are run **serially in provider array order**. That
is normal model behavior, not a protocol violation.

Wire-level `parallel_tool_calls: false` (OpenAI family) and
`disable_parallel_tool_use: true` (Anthropic) are **not** sent. Providers use their
default parallel-tool behavior on the wire; Parler still serializes execution at the
Agent until concurrency is raised.

Turn telemetry exposes:

- `toolExecutionMaxConcurrency` — currently always `1`.
- `multiToolCallRoundsCount` — number of LLM rounds (excluding post-marker) where the
  model returned more than one tool call (diagnostic only).

### 7. Forced No-Tool Final-Answer Round

After `completeAnswerSetSeen=true`, the next LLM request must be sent with **no tool
definitions** on the wire. OpenAI and Azure reject `tool_choice` when the `tools` array
is absent or empty, so Parler omits **both** `tools` and `tool_choice` for that round;
the model cannot invoke tools it does not see. `LlmChatRequest.toolChoiceNone` remains
`true` so AgentLoop can still detect `tool_call_after_tool_none` if the model returns tool
calls anyway.

Provider mapping:

| Provider family | Post-marker behavior |
|---|---|
| OpenAI Chat Completions | Omit `tools` and `tool_choice` when the round's tool list is empty. When tools are present and a no-tool policy is required, send `tools` plus `tool_choice: "none"`. |
| Azure OpenAI Chat Completions | same as OpenAI |
| Anthropic Messages | Same rule: omit `tools` and `tool_choice` when the tool list is empty; when tools are present with a no-tool policy, send `tools` plus `tool_choice: {"type":"none"}`. |

Requirements:

- `LlmChatRequest` carries an explicit tool-use policy (`toolChoiceNone` for post-marker);
  routing rounds use normal tool-auto unless overridden.
- Provider clients translate this policy into their request JSON **subject to vendor
  rules** (`tool_choice` only alongside a non-empty `tools` array on OpenAI/Azure).
- Provider request-body tests fixture-lock the exact OpenAI, Azure, and Anthropic
  JSON shapes for the configured API revisions. If an API revision requires a different
  accepted shape, the fixture and this document change together; the client never
  silently falls back to tool-enabled behavior.
- `noToolFinalAnswerApplied=true` is logged when the post-marker round is sent (empty
  tools on the provider wire, `toolChoiceNone=true` on `LlmChatRequest`).
- If a provider returns tool-call structures despite no tool definitions on the wire, the agent records the
  violation in `toolProtocolViolation` and **does not** execute the unexpected tool
  call. The user-facing final response is synthesized from the marker rows already in
  context - the end user receives a normal answer derived from `answerSetComplete=true`
  data, not a protocol-error message.
- The synthesized fallback is **LLM-less** and template-driven. It must not make another
  emergency LLM call and must not execute any tool. It emits concise Markdown derived
  from the complete rows and row count already in memory. The format can be less polished
  than the normal LLM answer, but it must preserve the correct answer set.

User-facing rationale: the Info window is primarily a **skill-developer surface**, not an
end-user surface. End users consume the final response stream. Protocol-violation
visibility therefore lives in `toolProtocolViolation`, which is carried on the
`llm_usage` wire and in the `LLM_TURN_PERFORMANCE` log line; the Info window does not
display it.

This is intentionally stricter than routing text. Once the complete answer set is in
context, another tool round is wasted work unless the user explicitly asked for paging,
extra columns, or export/download before the marker path was chosen.

## Turn Performance Summary

Emit one compact log line after `AgentLoop` completes, times out, or errors.

Prefix:

```text
LLM_TURN_PERFORMANCE
```

Required fields:

| Field | Meaning |
|---|---|
| `conversationId` | Conversation/thread key when available. |
| `requestId` | Current turn request id when available. |
| `agentIterations` | Number of provider LLM calls attempted in the turn, including a post-marker round that returns a protocol-violating tool call. |
| `toolCallCount` | Total tool calls executed. |
| `llmWallMs` | Sum of provider-call wall time, measured from entering the provider request path, through local admission/rate-gate wait, network, provider processing, and response parse. Includes `rateWaitMs`. |
| `toolWallMs` | Sum of tool executor wall time. |
| `rateWaitMs` | Subset of `llmWallMs`: time spent waiting in local provider admission/rate gates for TPM/RPM or equivalent provider quota controls. Must be real; never `-1`. |
| `turnWallMs` | Total wall time from AgentLoop start to terminal result. |
| `promptTokensTotal` | Sum of prompt/input tokens reported by providers. |
| `completionTokensTotal` | Sum of completion/output tokens reported by providers (provider **total** output count — not “visible only”). LLM-less synthesized fallback text does not add to this field. |
| `reasoningTokensTotal` | Optional. Sum of per-round **`reasoningTokens`** when OpenAI/Azure exposes **`usage.completion_tokens_details.reasoning_tokens`**. **Additive** to **`completionTokensTotal`**, not subtracted from it. Omitted from JSON/logs when **0**. |
| `requestedMaxOutputTokensTotal` | Sum of strictly **positive** per-round max-output budgets chosen by `AgentLoop` before Provider resolution; delegated non-positive values are omitted from this diagnostic. |
| `finalAnswerRoundIndex` | Round index that drove the terminal assistant response, or `-1` when no LLM round was involved. For LLM-less fallback after a post-marker tool-none violation, use the violated post-marker round index because that round triggered the terminal synthesized response. |
| `fetchAfterCompleteAnswerSetCount` | Number of `fetch_cached_result` dispatches after `completeAnswerSetSeen=true`. |
| `repetitionBlockedCount` | Number of synthetic `REPETITION_BLOCKED` tool result envelopes returned this `AgentLoop` run (identical tool name + arguments + tool result JSON twice already — see `docs/agent/multi-chart-and-thrashing-safeguards.md` §3). |
| `parlerChartWireEmittedCount` | Number of `type: "chart"` frames successfully downlinked on the Parler AlwaysOn stream during this `AgentLoop` run (`0` on non-Parler paths). See `docs/agent/multi-chart-and-thrashing-safeguards.md` §4. |
| `roundsHitMaxOutput` | Number of provider rounds whose finish reason indicates output-token truncation. |
| `firstToolCallCacheHit` | JSON `true`, JSON `false`, or string `"unknown"`. `"unknown"` only means no cached-tabular reader tool call happened. `true` means the reader used cache content that was warm at turn start; `false` means the needed cache was populated or reacquired during this same turn before the reader used it. |
| `markerEmitterEnabled` | Effective marker kill-switch value. |
| `noToolFinalAnswerApplied` | Whether a post-marker no-tool final round or the forced tool-free summary round after a post-marker / chart-rescue tool batch was applied. Must **not** be set for a presentation tool round that offered `build_chart_from_tabular_result`; it may be set only for a true zero-tool final text round. |
| `toolExecutionMaxConcurrency` | Agent runtime tool execution concurrency (today `1` = serial). |
| `multiToolCallRoundsCount` | Count of LLM rounds (excluding post-marker and chart end-turn rescue singleton rounds) where the model returned more than one tool call. |
| `chartExpectedButMissing` | True when `build_chart_from_tabular_result` was invoked this Parler stream turn and no `type: chart` frame was successfully downlinked yet (`parlerChartWireEmittedCount == 0`). **Does not** inspect the user’s natural-language prompt. Always `false` on non-Parler paths (e.g. sync Chat). See `docs/agent/multi-chart-and-thrashing-safeguards.md` §4.3 Fix B3 and `docs/agent/prompt-to-chart.md` §7.6. |
| `chartRescueAttempted` | When `true`, `AgentLoop` scheduled one end-of-turn singleton `build_chart_from_tabular_result` round after the model returned prose without tool calls while a chart was still expected and complete tabular data was available (`docs/agent/prompt-to-chart.md` §7.6). Otherwise `false`. |
| `presentationPhaseEntered` | When `true`, a post-marker round exposed `build_chart_from_tabular_result` via the Answer Presentation Phase (aggregate-first path — `docs/agent/multi-chart-and-thrashing-safeguards.md` §4.4.1). |
| `presentationActionsRequested` | Count of `build_chart_from_tabular_result` tool calls the model requested in presentation-phase rounds this `AgentLoop` run. |
| `presentationActionsExecuted` | Count of `build_chart_from_tabular_result` dispatches actually executed (not blocked by the presentation action cap) in presentation-phase rounds. |
| `presentationActionsBlocked` | Count of `build_chart_from_tabular_result` calls blocked by the presentation action cap (`PRESENTATION_ACTION_LIMIT`). |
| `toolProtocolViolation` | Optional bounded string code when the provider returns tool calls on a post-marker no-tool round (`tool_call_after_tool_none`). Empty or absent means no violation. |

There is no `chartEmitted` field. **`chartExpectedButMissing`** is the bounded prompt-to-chart observability boolean for the same problem class. **`chartRescueAttempted`** distinguishes “singleton rescue was tried after prose” from “rescue never attempted.”

### Timing Accounting Convention

Use this accounting convention everywhere: log line, Javadoc, wire contract, UI labels,
and tests.

```text
turnWallMs = outer AgentLoop wall time
llmWallMs  = total time spent in provider request paths
rateWaitMs = subset of llmWallMs spent waiting for TPM/RPM/admission gates
toolWallMs = total time spent executing local tools
```

`rateWaitMs` is not added to `llmWallMs`; it is already contained inside it. Under normal
sequential execution:

```text
0 <= rateWaitMs <= llmWallMs <= turnWallMs
```

`llmWallMs + toolWallMs` is diagnostic, not a required equality with `turnWallMs`, because
`turnWallMs` also includes Java orchestration overhead, streaming, serialization, and other
small gaps. There is no separate `rateWallMs` wire/log field in v1; use `rateWaitMs` for
the TPM/RPM wait subset.

## UI And Wire Surfacing

The existing UI Info window already reads assistant-row metadata, especially `llmUsage`.
Use that path rather than adding a separate Info-specific query or a new raw diagnostic
payload.

### Wire Shape

Extend terminal `session.done.llm_usage` and history `llmUsage` with a flat whitelisted
performance subset:

```json
{
  "turnWallMs": 116000,
  "rateWaitMs": 82000,
  "agentIterations": 6,
  "toolCallCount": 4,
  "llmWallMs": 110000,
  "toolWallMs": 450,
  "promptTokensTotal": 12345,
  "completionTokensTotal": 678,
  "requestedMaxOutputTokensTotal": 49152,
  "fetchAfterCompleteAnswerSetCount": 0,
  "roundsHitMaxOutput": 0,
  "firstToolCallCacheHit": true,
  "markerEmitterEnabled": true,
  "noToolFinalAnswerApplied": true,
  "toolExecutionMaxConcurrency": 1,
  "multiToolCallRoundsCount": 0,
  "repetitionBlockedCount": 0,
  "parlerChartWireEmittedCount": 0
}
```

This is intentionally an extension of the existing sanitized `llm_usage` object:

- it reuses the existing reducer and history hydrate path;
- it keeps the Info window rule that only row/bind-context fields are shown;
- it keeps prompts, tool bodies, headers, raw provider responses, and secrets out of the
  UI wire;
- unknown fields continue to be dropped at the UI adapter boundary.

The v1 shape is deliberately flat under `llm_usage`, not nested under `perf` or `turn`.
These Parler-owned keys are reserved by `CONTRACTS/API_CONTRACT.md` and
`CONTRACTS/UI_CLIENT_PROTOCOL.md`. If a future provider usage field collides with one of
these names, resolve it with a contract-version bump rather than silently renaming the
Parler field.

The example values are illustrative (an `8192`-per-round run). Production uses Provider /
Agent `maxTokens` semantics described in §5.

Timing values stay raw milliseconds on the wire. Formatting belongs in the UI.

### Protocol Violation Codes

Stable `toolProtocolViolation` code:

| Code | Meaning | User-facing response |
|---|---|---|
| `tool_call_after_tool_none` | Provider returned tool-call structures on a post-marker final-answer round sent with tool use disabled. | Do not execute the tool; synthesize final answer from marker rows; no user-visible protocol error. |

Absent or empty means no known protocol violation. New codes require contract and UI
whitelist updates in the same change.

Within one turn, the singular `toolProtocolViolation` field keeps the first non-empty
code observed (today only `tool_call_after_tool_none` is used). The full log line may
include additional implementation-local detail, but `llm_usage.toolProtocolViolation`
follows this rule.

### Info Window Presentation

The Info window (`parler-ui/lib/assistantTurnInfo.js`) lists the timing pair first:

| Field | Label |
|---|---|
| `turnWallMs` | Total time (ms) |
| `rateWaitMs` | Rate-gate wait (ms) |

It then shows, when present on `llmUsage`: `toolExecutionMaxConcurrency`,
`multiToolCallRoundsCount`, `repetitionBlockedCount`, `parlerChartWireEmittedCount`,
`chartRescueAttempted`, the `presentation*` counters, the provider token fields (input /
output, cache read / creation, cached prompt, reasoning), Provider Thing, model, and
provider request id.

The remaining whitelisted performance fields (`agentIterations`, `toolCallCount`,
`llmWallMs`, `toolWallMs`, `requestedMaxOutputTokensTotal`,
`fetchAfterCompleteAnswerSetCount`, `roundsHitMaxOutput`, `firstToolCallCacheHit`,
`markerEmitterEnabled`, `noToolFinalAnswerApplied`, `toolProtocolViolation`) are kept on the
stored row and in the `LLM_TURN_PERFORMANCE` log line but are not displayed. Stored values
stay raw numeric milliseconds.

## Code Touchpoints

Implementation areas:

| Area | Files / classes |
|---|---|
| Agent loop round policy and summary | `AgentLoop`, `AgentResult` or adjacent result objects |
| LLM request controls | `LlmChatRequest` tool-use policy, requested output budget |
| Provider request JSON | `OpenAiChatCompletionsClient`, `AzureOpenAILlmClient`, `AnthropicMessagesLlmClient` |
| Rate-wait aggregation | provider clients plumbing real wait time back to AgentLoop |
| Finish reason mapping | provider response parsers / `LlmResponse` finish reason |
| Cached-tabular marker | `CachedTabularGroupMetricExecutor` and related response helpers |
| Routing text | LLM routing guide resource |
| Marker contract | `docs/agent/query-spec.md` section 11 |
| UI wire and Info window | `CONTRACTS/UI_CLIENT_PROTOCOL.md`, `CONTRACTS/API_CONTRACT.md`, `CONTRACTS/CONTRACT_VERSION.md`, `parler-ui/lib/llmUsageWire.js`, `parler-ui/lib/assistantTurnInfo.js`, UI tests |
| Versioning | `parler-ui-widget/input/widgets.json`, `parler-agent/build.gradle` |

## Tests

### Unit And Component Tests

Required:

1. `group_metric + having` emits `answerSetComplete=true` when rows are complete and
   within caps.
2. Boundary: `returnedRows=50`, `totalRows=50` -> marker true.
3. Boundary: `returnedRows=50`, `totalRows=51` -> marker false.
4. Boundary: 9 projected columns -> marker false.
5. Boundary: serialized rows payload above `8192` -> marker false.
6. Protected column in projection -> marker false.
7. Marker kill switch disabled -> marker omitted and `markerEmitterEnabled=false`.
8. Kill-switch-off path produces the plain sampled/paged result shape on a known prompt,
   using a fixture LLM rather than a live provider.
9. `completeAnswerSetSeen` does not change behavior when no marker appears.
10. `completeAnswerSetSeen` does not leak into the next user message in the same
    conversation.
11. `AgentLoop` passes `AgentSettings.maxTokens` through for all round categories (no
    implicit Agent-side cap).
12. Long-answer prompts keep the configured default.
13. `roundsHitMaxOutput` increments on provider output-token truncation.
14. Routing guide contains the stop rule.
15. `LLM_TURN_PERFORMANCE` includes all required fields.
16. `rateWaitMs` is real and never a placeholder.
17. Timing accounting obeys `rateWaitMs <= llmWallMs <= turnWallMs` on sequential turns,
    and tests/Javadoc state that `rateWaitMs` is contained in `llmWallMs`, not additive.
18. `firstToolCallCacheHit=unknown` appears only when the turn made no cached-tabular
    reader tool call; same-turn cache population before the first reader reports
    `false`, not `true`.
19. Routing provider bodies do **not** send `parallel_tool_calls` or
    `disable_parallel_tool_use`; multi-tool LLM responses increment `multiToolCallRoundsCount`
    and are executed serially.
20. `toolExecutionMaxConcurrency=1` is logged on turns (today always serial execution).
21. Provider response with multiple tool calls in a routing round is processed in
    deterministic array order without setting `toolProtocolViolation`.
22. Post-marker request body is sent with provider-specific no-tool mode for OpenAI,
    Azure, and Anthropic.
23. `noToolFinalAnswerApplied=true` is logged on turns that use forced no-tool mode.
24. Provider response with tool-call structures despite post-marker tool-none:
    `toolProtocolViolation == "tool_call_after_tool_none"`, the unexpected tool call is
    **not** executed, and the user-facing final response is synthesized by the LLM-less
    template fallback from the marker rows already in context. The end-user stream does
    not surface a protocol-error message; the violation is observable through the Info
    `toolProtocolViolation` field on `llm_usage` and in the log line. The violated post-marker round still increments
    `agentIterations`, becomes `finalAnswerRoundIndex`, and contributes provider-reported
    completion tokens from that provider response; the later LLM-less synthesized text
    contributes no completion tokens.
25. (Reserved — single violation code in v1.)
26. `done.llm_usage` / history `llmUsage` whitelist accepts the new numeric, boolean,
    and bounded-string / enum performance fields and drops unknown fields.
27. UI Info window lists `turnWallMs` and `rateWaitMs` first and shows the supporting
    fields named under Info Window Presentation when present.

`firstToolCallCacheHit` scope for tests and implementation: "cached-tabular reader"
means `tabulate_cached_result`, `fetch_cached_result`, `summarize_cached_result`, and
other cached-table reader tools. Cache-populating data-acquisition tools such as
`utilization_records` are not in scope for this field. If no cached-tabular reader tool is
called, the value is `"unknown"`. If a non-scoped tool populates cache earlier in the same
turn and the first cached-tabular reader then reads that cache, the value is `false`,
because the cache was not warm at turn start.

### Live Regression

Live regression runs pin the requested-output budget to `8192` so marker and routing
behavior is measured independently of budget tuning.

Primary live prompt:

```text
which assets have utilization 0% in 2025-09-10?
```

Expected:

- final answer says 21 assets;
- stream contains `group_metric + having`;
- qualifying tool result has `answerSetComplete=true`;
- `fetchAfterCompleteAnswerSetCount == 0`;
- `agentIterations <= 3`;
- `noToolFinalAnswerApplied == true`;
- `toolExecutionMaxConcurrency == 1`;
- `roundsHitMaxOutput == 0`;
- `LLM_TURN_PERFORMANCE` appears with real `rateWaitMs`;
- UI Info window displays the turn summary with `turnWallMs` and `rateWaitMs` as headline
  fields.

Secondary live prompt:

```text
which assets have utilization lower than 30% but not 0% in the same day?
```

Expected:

- final answer says 12 assets;
- `having` has `GT utilization_pct 0` and `LT utilization_pct 30`;
- `fetchAfterCompleteAnswerSetCount == 0`;
- `agentIterations <= 3`;
- `noToolFinalAnswerApplied == true`;
- `toolExecutionMaxConcurrency == 1`;
- `roundsHitMaxOutput == 0`;
- no correctness regression.

### Diagnostic A/B

Run paired tests in the same environment:

```text
Run A: marker emitter disabled
Run B: marker emitter enabled
```

Run A still includes full performance telemetry and Agent serial tool execution. Only marker emission is disabled;
because forced no-tool mode is triggered by the marker path, forced no-tool mode is also
inactive in Run A. This isolates the marker contribution.

Record:

- `agentIterations`;
- `fetchAfterCompleteAnswerSetCount`;
- `rateWaitMs`;
- `turnWallMs`;
- `firstToolCallCacheHit`;
- `noToolFinalAnswerApplied`;
- `toolExecutionMaxConcurrency`;
- `multiToolCallRoundsCount`.

These values support manual judgment; they are not automated gates.
