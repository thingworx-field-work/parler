# Multi-chart support and agent-loop thrashing safeguards

Status: **implemented** — Slice A (repetition guard), Slice B (multi-**`cacheId`** charts), and the Answer Presentation Phase (§4.4.1). Chart-rescue exposure details are normatively defined in **`prompt-to-chart.md`** §7.5–§7.6.

This document specifies two safeguards that together resolve a class of multi-chart failures. The slice labels (A, B) are stable identifiers cited from code and contracts.

## 1. Problem

A prompt such as "Show separate pie charts for the utilization-state breakdown of `ORD-Contacting-01` and `ORD-Contacting-02` on 2025-09-10" could enter an unrecoverable loop that ended only when the LLM provider's hard `single_request_too_large` cap fired. The observed sequence:

| Stage | Iterations | Behavior | Outcome |
|---|---|---|---|
| Identifier resolution | 1-3 | preflight → `resolve_thing` × 2 → records fetch × 2 | Correct |
| First aggregation | 4-5 | `utilization_aggregate_by_state` for `-01`, then `-02` | Correct, two distinct results |
| Thrashing | 6-9 | `utilization_aggregate_by_state` for `-01` repeated, identical args, identical results × 4 | No progress |
| Tool escape | 10 | Model gave up on the named extended tool and called `invoke_service` targeting the same underlying ThingWorx service | HITL pause |
| Approval loop | repeated | Each approval started a fresh `executeAgentLoop` whose model immediately re-issued `invoke_service` | Messages accumulated; no chart |
| Provider hard cap | terminal | Cumulative request body exceeded the provider limit | `single_request_too_large` |

Two defects addressed here compounded into this failure:

1. **Multi-chart was unsupported.** The model aggregated both machines but had no clean way to emit two pie charts in one turn: `source: "last_invoke"` is a single slot, and extended-tool results did not surface a conversation-cache `cacheId` the model could reference.
2. **No repetition detection.** The runtime allowed the model to call the same tool with identical arguments and consume identical results any number of times.

## 2. Scope

### 2.1 In scope

- **Slice A** — `consecutiveIdenticalToolCall` repetition detector.
- **Slice B** — multi-chart support via "many calls, one wire frame each" (one `type: "chart"` wire frame per `build_chart_from_tabular_result` call, each drawn from a distinct cached tabular result).

### 2.2 Non-goals

- A multi-dimension prompt execution budget (tokens / wall time / cumulative tool calls / cumulative iterations across HITL resumes).
- Fuzzy repetition matching (timestamp tolerance, semantic equivalence, cross-tool-name repetition). The detector uses exact deep-equal hashing only.
- HITL routing coherence between `invoke_service` and `extended_tools.json` (the same ThingWorx service can have different HITL policies depending on which dispatch path the model took).
- Multi-chart rescue for partial-N cases (e.g. 1 of 3 charts emitted, rescue for the other 2).
- `tabulate_cached_result` post-marker semantics extended for multi-chart turns.
- Tool schema description tightening for `invoke_service` or other catch-all tools.
- Server-side detection of "model never invoked the chart builder despite an explicit user chart request" via user-prompt or assistant-prose text matching. This is **unresolvable by runtime detection** under the standing principle that behavior gates never grep, regex, or keyword-match user input or LLM output. If the model declines to invoke the chart builder, that call is missed.

### 2.3 Coupling between slices

The slices touch independent code surfaces:

- Slice A lives in the tool-dispatch path (`AgentThing` tool execution) and a per-turn registry.
- Slice B lives in extended-tool result envelope shaping, the `AgentToolContext` pending-chart queue, `PARLER_CHART_WIRE_EMITTED` counting, and the `build_chart_from_tabular_result` schema description.

They share `LLM_TURN_PERFORMANCE` telemetry (§6).

## 3. Slice A — `consecutiveIdenticalToolCall` repetition detector

### 3.1 Goal

When the model calls the same tool with identical arguments and receives identical results two times in a row in one user prompt, the third attempt is not dispatched to the underlying tool. The dispatcher returns a synthetic tool result that tells the model to stop or change approach.

The detector is a runtime guard, not a model nudge.

### 3.2 Trigger conditions

The detector tracks, per turn, a map from `(toolName, argsHash)` to the most recent `(resultHash, count)`.

After a tool call completes:

- Compute `argsHash = sha256(stableJsonEncode(arguments))`.
- Compute `resultHash = sha256(stableJsonEncode(result))`.
- Look up the prior entry for `(toolName, argsHash)`.
- If the prior entry's `resultHash` equals the new `resultHash`, increment `count`. Otherwise, replace the entry with `(resultHash, 1)`.

Before dispatching a tool call:

- Compute `argsHash` for the incoming call.
- Look up the prior entry for `(toolName, argsHash)`.
- If `count >= 2` (this would be the third identical call), short-circuit: return the synthetic `REPETITION_BLOCKED` envelope (§3.4) WITHOUT dispatching to the tool.
- Otherwise, proceed with normal dispatch.

"Block on the 3rd" gives the model two chances to repeat (which can be legitimate — e.g. the model forgot it had already run a query) before the runtime intervenes.

`stableJsonEncode`:
- Sorts object keys recursively.
- Omits whitespace.
- Preserves number precision (does not collapse `1` and `1.0` if the source had them distinct).
- Encodes `null` and missing keys distinctly.

### 3.3 Normalization scope

Normalization is exact-bytes equality after stable JSON encoding. It does not detect semantically equivalent calls that differ trivially (timestamp precision, whitespace inside string values). This is intentional: false positives on a runtime guard are more damaging than false negatives.

### 3.4 Synthetic envelope on block

When the third identical call is short-circuited, the dispatcher returns:

```json
{
  "status": "error",
  "code": "REPETITION_BLOCKED",
  "message": "Tool '<toolName>' was called with identical arguments and returned identical results 2 times already in this turn. The data has not changed. Stop calling this tool with these arguments, and either finalize the answer with the data you have or take a different approach.",
  "toolName": "<toolName>",
  "repetitionCount": 2,
  "priorResultDigest": "<short hash, first 12 chars of resultHash>"
}
```

`REPETITION_BLOCKED` is distinct from existing error codes (`IDENTITY_RESOLUTION_REQUIRED`, `THINGNAME_VALUE_REQUIRED`, …), self-describing, and stable for telemetry and tests.

The synthetic result counts as a tool result for stream persistence and conversation history. The model sees it on the next round; a fourth identical attempt is blocked again (§3.6).

### 3.5 Tests

1. Two identical `(toolName, args)` calls with identical results, then a third: the envelope is returned, `recordBlocked` runs once, the underlying tool is NOT invoked, and the code is `REPETITION_BLOCKED`. A 4th identical call is also short-circuited.
2. A third call with different args (same tool name) is dispatched normally.
3. A third call with identical args but a different result on the second call is dispatched normally (the count was reset).
4. Detector state persists across simulated HITL pause/resume for the same turn key.
5. `LLM_TURN_PERFORMANCE` includes `repetitionBlockedCount` (int).

### 3.6 Idempotency and edge cases

- A blocked call itself advances the detector state, so a 4th, 5th, 6th identical retry continues to short-circuit. The map does not roll over.
- Cross-tool repetition is NOT detected. If the model calls `utilization_aggregate_by_state` and then `invoke_service` on the same underlying service, the arguments differ at the LLM-tool level and the detector treats them as distinct.
- Calls that return a structured error (e.g. `IDENTITY_RESOLUTION_REQUIRED`) DO count. A model that retries the same bad input and gets the same preflight rejection is blocked on the third attempt.
- A new user prompt starts with fresh state. Repeating tool calls across prompts is allowed; only within-a-prompt repetition is suspect.
- A model that intentionally retries the same call (e.g. to refresh a result it suspects is stale) is blocked on the third attempt; the envelope message is explicit, so the model can pivot.
- The synthetic envelope stays in the persisted conversation, so a follow-up prompt in the same conversation sees it.

### 3.7 Implementation

Storage:

- `ConsecutiveIdenticalToolCallRegistry` (package `com.thingworx.things.agent.tools`) — a static map from turn key to `ConsecutiveIdenticalToolCallTracker`. The turn key matches `FetchCachedReplayGuard.resolveCurrentTurnKey()` (conversation id plus request id, or a per-message nonce for Chat), so HITL continuation of the same turn reuses the tracker.
- The registry survives `AgentToolContext.clear()` and `AgentLoop` pause/resume boundaries. A tracker is created lazily on first dispatch in the turn.
- Cleanup: the entry is removed when the agent loop ends a turn without awaiting HITL (same condition as `FetchCachedReplayGuard.endTurn`), and on terminal Parler HITL paths that skip `AgentLoop.run` (cancel / reject early returns and approval expiry). The `AWAITING_APPROVAL` return path and `AgentToolContext.clear()` do NOT remove it.
- Defensive bound: at most 4096 turn keys; excess keys are dropped arbitrarily (not LRU).
- The registry is not serialized into HITL pending records.

Tracker surface (`ConsecutiveIdenticalToolCallTracker`):

- `Optional<String> interceptThirdIdentical(String toolName, JsonNode args)` — returns the synthetic envelope and records the block when the call would be the 3rd+ identical repeat; otherwise empty.
- `void recordCompletion(String toolName, JsonNode args, String resultJson)` — invoked after the underlying tool returns; updates the `(argsHash → {resultHash, count})` bucket.
- `void recordBlocked(String toolName, JsonNode args)` — advances the same bucket (so the map does not roll over) and increments the per-turn `repetitionBlockedCount` once.

`repetitionBlockedCount` semantics:

- Incremented exactly once per synthetic envelope returned. Four identical calls produce dispatch + dispatch + 2 short-circuits → `repetitionBlockedCount = 2`.
- Independent of the per-key count, which is the detector's own state machine.

Wiring (block-before-dispatch, record-after-dispatch-or-after-block):

- The block check runs at the single tool-dispatch site in `AgentThing` (built-in and extended tools) **before** the tool runs. If it blocks, no tool work happens, including tool-specific preflight such as the THINGNAME parameter preflight.
- Otherwise the tool runs and the dispatcher calls `recordCompletion`, including for preflight-error results, so identical bad inputs do not get unlimited retries.

Hashing: SHA-256 over the canonical JSON bytes (keys ordered, no indentation).

## 4. Slice B — Multi-chart support

### 4.1 Goal

A single user prompt can produce more than one `ChartBlock` in the assistant's final response, with each chart drawing from a different source dataset — for example "N pies for N machines in one turn".

### 4.2 Architectural shape

Every `build_chart_from_tabular_result` call produces exactly one `type: "chart"` wire frame, and a turn that wants N charts issues N such calls. The model:

1. Calls data-fetch tools, one per machine (or one with multi-machine support).
2. Optionally calls aggregation tools per dataset.
3. Calls `build_chart_from_tabular_result` N times, once per chart, each with `source: "cache_id"` and the top-level `cacheId` of the prior tabular tool result that feeds that chart.

This uses the executor's existing input shape (`source: "cache_id"` + top-level `cacheId`). There is no `compose_chart_set` tool and no `small_multiples` chart kind.

### 4.3 The four fixes

**Fix B1**: Extended-tool result envelopes surface the conversation-cache `cacheId` for any `INFOTABLE` result, so the model can pass it back to `build_chart_from_tabular_result`.

The shared conversation cache (`InvokeServiceExecutor.lookupCachedInfotable(cacheId)` plus the `storeInfotableInConversationCache(...)` family) generates an opaque `cacheId` for InfoTable results. Built-in tools like `query_entities` and `tabulate_cached_result` echo that `cacheId`; extended-tool envelopes (shaped by `InvokeServiceExecutor.formatDirectServiceResultForLlm`) echo it as a top-level `cacheId` too.

`cacheId` semantics:

- Opaque string generated by the conversation cache when the InfoTable is stored. **Not** derived from `callId`; each store yields a fresh id.
- Valid for the lifetime of the conversation cache entry.
- Resolvable by `build_chart_from_tabular_result` through `InvokeServiceExecutor.lookupCachedInfotable(cacheId)`. Extended-tool and built-in results share one namespace.
- Documented in `CHART_CONTRACT.md` as an output field on the tabular tool result envelope and reused as input `{"source": "cache_id", "cacheId": "<id>"}`.

**Fix B2**: The pending-chart slot holds multiple chart blocks.

`AgentToolContext` keeps pending chart blocks in a per-thread `ArrayDeque<JSONObject>`:
- `addPendingParlerChartBlock(JSONObject chart)` — enqueue.
- `drainPendingParlerChartBlocks()` — returns the full list and clears.
- The drainer emits each as a separate `type: "chart"` wire frame.

When the model batches `[build_chart_a, build_chart_b]` in one assistant message, both charts are emitted.

**Fix B3**: `PARLER_CHART_WIRE_EMITTED` is an integer counter, `parlerChartWireEmittedCount`.

`chartExpectedButMissing` does **not** depend on user-prompt keyword heuristics. It is computed from runtime tool behavior only:

```text
chartExpectedButMissing =
  chartBuildAttemptedThisTurn && parlerChartWireEmittedCount == 0
```

where `chartBuildAttemptedThisTurn` is set when `build_chart_from_tabular_result` is actually invoked this Parler stream turn.

**Chart rescue / post-marker `build_chart_from_tabular_result` exposure** (so the model is not stuck behind `tool_choice: none` with zero tools when a chart can still be built) uses the same behavior-derived gate, plus a recoverable chart-build failure flag and an available chartable tabular source (see **`docs/agent/prompt-to-chart.md`** §7.6 and **`AgentToolContext.eligibleChartRescueToolExposureForTurn()`**). Recoverable tool error: **`DUPLICATE_SLICE_LABEL`**.

This does **not** enable partial-N chart rescue (e.g. 1 of 3 charts emitted, then the model finalizes); that would need an expected-chart-count signal.

Result:

- `parlerChartWireEmittedCount` counts charts emitted in the turn; `chartExpectedButMissing` (boolean) flags "model invoked the chart builder but no chart wire succeeded" without parsing the user's prompt.
- The single-chart happy path is unchanged.

**Fix B4**: The `build_chart_from_tabular_result` schema description tells the model how to reference distinct cached results when emitting multiple charts. In substance:

> When emitting multiple charts in one turn that each draw from a different prior tool result, pass `source: "cache_id"` and `cacheId: "<id>"`, where `<id>` is the `cacheId` field surfaced by the relevant tabular tool result envelope. Use `source: "last_invoke"` only when the chart draws from the most recent tool result. Calling multiple `build_chart_from_tabular_result` invocations all with `source: "last_invoke"` gives every chart the same source.

The description directs the model to the executor's actual input shape (`source: "cache_id"` plus top-level `cacheId`); output-envelope names such as `sourceResolved` / `sourceCacheId` are not accepted as input. `last_invoke` is mentioned only as the single-chart shortcut.

### 4.4 Post-marker / `answerSetComplete` interaction

Under multi-chart, `tabulate_cached_result` may fire `answerSetComplete=true` after chart 1 emits but before chart 2 is requested. Marker semantics are not extended for multi-chart:

- Multi-chart turns usually flow through extended-tool aggregations directly (e.g. `utilization_aggregate_by_state` returns a chartable INFOTABLE; no `tabulate_cached_result` in between).
- If the model interleaves `tabulate_cached_result` calls in a multi-chart turn, marker semantics fire as usual, and the result may be a single-chart turn; the Answer Presentation Phase (§4.4.1) then offers the chart builder for remaining artifacts.

### 4.4.1 Answer Presentation Phase (aggregate-first)

After a complete-answer boundary (`tabulate_cached_result` / `answerSetComplete` semantics), when **no** chart-rescue gate applies (`AgentToolContext.eligibleChartRescueToolExposureForTurn()` is false) but at least one **complete chartable** tabular artifact is registered for the turn (`PresentationArtifactRegistry`, keyed by transformed **`cacheId`**) **that no chart downlinked this turn was built from** (matched by the chart's `source.sourceCacheId`; a chart without one, such as the numeric-history line that `query_property_history` emits by itself, charts no artifact), the post-marker LLM round exposes exactly **`build_chart_from_tabular_result`** (singleton tool list). The model may issue **multiple** `build_chart_from_tabular_result` calls in that round; the runtime executes them serially and enforces a presentation action limit of **6** — excess calls receive deterministic `PRESENTATION_ACTION_LIMIT` error JSON. After any post-marker or chart-rescue singleton round where the model used tools, **`AgentLoop`** schedules one forced **tool-free** round (`tool_choice: none`, empty tools) for a prose summary; only that round may set **`noToolFinalAnswerApplied`**. Telemetry fields: **`presentationPhaseEntered`**, **`presentationActionsRequested`**, **`presentationActionsExecuted`**, **`presentationActionsBlocked`**. Registry cleanup mirrors **`ConsecutiveIdenticalToolCallRegistry`** (terminal success / timeout / max-iterations / terminal HITL cancel-reject-expiry — not on `AWAITING_APPROVAL`).

### 4.5 Contract surfaces

- `CHART_CONTRACT.md` — (1) qualifying tabular tool result envelopes MAY carry an additive optional top-level `cacheId` field (Fix B1); (2) `build_chart_from_tabular_result` accepts `{"source": "cache_id", "cacheId": "<id>"}` as input; (3) multiple `ChartBlock` wire frames per turn are valid. There is no new chart wire frame type and no new chart-builder input field.
- `API_CONTRACT.md` — `chartExpectedButMissing` is behavior-derived (§4.3 Fix B3); `parlerChartWireEmittedCount` and `repetitionBlockedCount` are documented `llm_usage` fields, allowed on the wire by the server-side `ParlerLlmUsageWireSanitizer` whitelist.
- **`parler-ui` client whitelist** — `parler-ui/lib/llmUsageWire.js` enforces its own allowlist and silently drops unknown keys, so server-side sanitization alone is not sufficient. `parlerChartWireEmittedCount` and `repetitionBlockedCount` are in the numeric allowlist, and `CONTRACTS/UI_CLIENT_PROTOCOL.md` enumerates them under `llmUsage`.

### 4.6 Implementation

- `InvokeServiceExecutor.formatDirectServiceResultForLlm` (extended-tool envelope shaping) routes INFOTABLE results through `storeInfotableInConversationCache(...)` and echoes the resulting `cacheId` — Fix B1.
- `AgentToolContext` — pending chart deque (Fix B2) and the integer chart-wire counter plus `chartBuildAttemptedThisTurn` (Fix B3).
- The chart wire sender drains and emits each pending block (Fix B2).
- `build_chart_from_tabular_result` schema description (Fix B4).
- `LLM_TURN_PERFORMANCE` carries `parlerChartWireEmittedCount`.

### 4.7 Tests

1. An extended-tool `INFOTABLE` result envelope includes a non-empty top-level `cacheId`; `InvokeServiceExecutor.lookupCachedInfotable(cacheId)` returns the original `InfoTable`, and `build_chart_from_tabular_result` with `{"source": "cache_id", "cacheId": "<that-id>"}` succeeds. (Re-formatting the same raw result yields a fresh `cacheId`; only resolvability is asserted.)
2. `build_chart_from_tabular_result` accepts `{"source": "cache_id", "cacheId": "<id>"}` and produces a `CHART_EMITTED` envelope when the referenced entry exists.
3. Two `build_chart_from_tabular_result` calls in one turn, each with `source: "cache_id"` and a distinct `cacheId`, produce two `type: "chart"` wire frames; each frame's `sourceResolved` is `"cache_id"` and each `sourceCacheId` matches its input `cacheId`.
4. `parlerChartWireEmittedCount` increments correctly across multiple emissions.

Behavioral expectation: the two-machine prompt in §1 produces two pie charts in one turn — one per machine, each correctly labeled — with no HITL pause and no `invoke_service` fallback; a single-chart prompt still produces exactly one chart and `parlerChartWireEmittedCount=1`.

## 5. Runtime checks

Representative prompts:

| Prompt | Expected behavior |
|---|---|
| "Show me the utilization-state breakdown for `ORD-Contacting-01` on `2025-09-10` as a pie chart." | One pie. No HITL. |
| "Show separate pie charts for the utilization-state breakdown of `ORD-Contacting-01` and `ORD-Contacting-02` on `2025-09-10`." | Two pies. No HITL. No `invoke_service` escape. |
| "Show separate pie charts for `ORD-Contacting-01` and `ORD-Contacting-02` and `ORD-Contacting-03` on `2025-09-10`." | Three pies if data exists for all three; a machine with no data is omitted with a brief note in the prose. |

Defect signals in ApplicationLog around a turn:
- `Tool .* paused for approval` for an extended tool registered as `hitl: false`.
- `parlerChartWireEmittedCount` of 0 when the prompt asked for charts and the model finalized.
- A `REPETITION_BLOCKED` envelope in a turn that completed naturally without thrashing (false positive).

## 6. Telemetry

These fields are in `LLM_TURN_PERFORMANCE` and in the sanitized `llm_usage` wire whitelist, so they appear in `assistant.llmUsage`:

| Field | Type | Slice | Meaning |
|---|---|---|---|
| `repetitionBlockedCount` | int | A | Number of synthetic `REPETITION_BLOCKED` envelopes returned in this turn. Incremented exactly once per `recordBlocked(...)` invocation, independent of the per-key repetition count. |
| `parlerChartWireEmittedCount` | int | B | Number of `type: "chart"` wire frames emitted. |

Existing fields (`chartExpectedButMissing`, `chartRescueAttempted`, `agentIterations`, etc.) are unchanged. `agentIterations` means "iterations within the current `executeAgentLoop` call"; each HITL continuation runs its own loop with its own iteration cap.

## 7. References

- `CONTRACTS/CHART_CONTRACT.md` — chart contract (`source: "cache_id"` input shape, multiple `ChartBlock` frames per turn).
- `CONTRACTS/API_CONTRACT.md` — `llm_usage` telemetry fields.
- `docs/agent/prompt-to-chart.md` — chart-rescue and tabular flow.
- `docs/agent/llm-performance.md` — `LLM_TURN_PERFORMANCE`.
