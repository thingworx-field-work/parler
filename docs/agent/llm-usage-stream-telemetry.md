# LLM Usage Stream Telemetry

Status: implemented (Java persistence + eval harness aggregation and reset modes).  
Document type: agent persistence and evaluation support.

## Purpose

Persist provider usage telemetry with the assistant rows in `AgentMessageStream`, so token usage, prompt-cache behavior, and compaction effects remain available after the Application Log rolls over or a conversation is reviewed through evaluation tooling.

This document complements `docs/agent/llm-token-budget.md`. That document defines provider observability and compaction strategy. This document defines the durable row-level shape in `AgentMessageStream` and the implementation path that carries usage fields from the LLM response into persisted assistant rows.

## Non-Goals

- No migration of existing Stream rows or DataShape rows. This project is still in active development.
- No attempt to reconstruct usage telemetry for old rows.
- No change to final answer behavior based on usage values.
- No pricing calculation in the runtime. Cost analysis belongs in reporting tools.
- No provider-specific columns beyond the legacy aggregate columns. Provider-specific details live in JSON.

## Current State

The provider parsers already capture richer usage details:

- Anthropic: `input_tokens`, `output_tokens`, `cache_read_input_tokens`, `cache_creation_input_tokens`.
- OpenAI / Azure OpenAI Chat Completions: `prompt_tokens`, `completion_tokens`, and optional `prompt_tokens_details.cached_tokens`.

`LlmResponse` already has fields for:

- `inputTokens`
- `outputTokens`
- `cacheReadInputTokens`
- `cacheCreationInputTokens`
- `cachedPromptTokens`
- `providerRequestId`

The missing persistence path is after `LlmResponse`. `AgentLoop` currently reduces the response to `StreamTokenUsage(promptTokens, completionTokens)`, and `AgentMessageStreamAppender` writes only `promptTokens` and `completionTokens` into `AgentMessageData`.

## Stream Schema

Keep the existing aggregate columns:

| Field | Base type | Meaning |
| --- | --- | --- |
| `promptTokens` | `INTEGER` | Legacy aggregate prompt-side tokens for the LLM round. For Anthropic this includes fresh input + cache read + cache creation tokens. |
| `completionTokens` | `INTEGER` | Legacy aggregate output/completion tokens for the LLM round. |

Add one field:

| Field | Base type | Meaning |
| --- | --- | --- |
| `llmUsageJson` | `TEXT` | Compact provider usage JSON for assistant rows. Empty string for user/tool rows and for rows without telemetry. |

Affected DataShapes:

- `parler-agent/Entities/DataShapes/AgentMessageData.xml`
- `parler-agent/Entities/DataShapes/ParlerConversationHistory.xml`
- `AgentMessageStreamAppender.buildInlineAgentMessageDataShape()`

No migration is required. If a row has no `llmUsageJson`, readers treat it as absent telemetry.

## Usage JSON Shape

Persist a compact object with stable field names for the **base** counters (`inputTokens`, `outputTokens`, `promptTokens`, `completionTokens`, plus string metadata such as `provider`, `model`, `requestId`, `apiShapeId`). **Optional** provider-specific cache dimensions are written only when they apply to the provider family (see `StreamTokenUsage.buildUsageJson` in `parler-agent`): Anthropic cache read/create fields appear only when `apiShapeId` is in the **`anthropic-`** family; `cachedPromptTokens` appears only for **`openai-`** / **`azure-openai-`** shapes. Do **not** emit cross-provider zero placeholders—omission keeps wire JSON, history hydrate, and UI turn-details popovers honest. Aggregators and the UI should treat a missing numeric field as “not reported for this provider,” not as zero.

Recommended shape (OpenAI / Azure family — note omitted Anthropic-only fields and no `reasoningTokens` until parsed):

```json
{
  "provider": "AZURE_OPEN_AI",
  "model": "gpt-5.4-mini",
  "requestId": "51697811-43e8-4189-971e-694b29537a18",
  "apiShapeId": "azure-openai-chat-completions",
  "inputTokens": 30000,
  "outputTokens": 800,
  "promptTokens": 30000,
  "completionTokens": 800,
  "cachedPromptTokens": 24000
}
```

Anthropic example (note omitted OpenAI-only `cachedPromptTokens` and no `reasoningTokens`):

```json
{
  "provider": "ANTHROPIC",
  "model": "claude-sonnet-4-6",
  "requestId": "msg_...",
  "apiShapeId": "anthropic-messages",
  "inputTokens": 1234,
  "outputTokens": 456,
  "promptTokens": 9000,
  "completionTokens": 456,
  "cacheReadInputTokens": 7000,
  "cacheCreationInputTokens": 766
}
```

Field semantics:

| JSON field | Meaning |
| --- | --- |
| `provider` | `LlmProvider.name()` for the AgentThing's provider. |
| `model` | Provider model/deployment label known to the runtime. |
| `requestId` | Provider request id when available. Empty or omitted if unavailable. |
| `apiShapeId` | Wire/API shape label used for provider-family gating of optional fields (see `StreamTokenUsage`). |
| `inputTokens` | Provider-reported fresh input / prompt tokens. Anthropic reports this separately from cache read/create tokens. |
| `outputTokens` | Provider-reported output / completion tokens. |
| `promptTokens` | Legacy aggregate prompt-side tokens, equal to the persisted `promptTokens` column. |
| `completionTokens` | Legacy output tokens, equal to the persisted `completionTokens` column. |
| `cacheReadInputTokens` | Anthropic cache-read input tokens; **omitted** unless the Anthropic family applies. |
| `cacheCreationInputTokens` | Anthropic cache-creation input tokens; **omitted** unless the Anthropic family applies. |
| `cachedPromptTokens` | OpenAI/Azure cached prompt tokens from `prompt_tokens_details.cached_tokens`; **omitted** unless the OpenAI/Azure family applies or the value is absent. |
| `reasoningTokens` | OpenAI/Azure output reasoning tokens when parsed from `completion_tokens_details.reasoning_tokens`; **omit entirely** until the parser populates a real value (UI guards with `typeof x === "number"`). |

Do not add cache fields to `promptTokens` again. `promptTokens + completionTokens` remains the legacy total.

## Reasoning Tokens

OpenAI / Azure reasoning models report `usage.completion_tokens_details.reasoning_tokens`. When the provider reports it, Parler surfaces:

- **`reasoningTokens`** — per round, on `LLM_USAGE` and in the round's usage JSON;
- **`reasoningTokensTotal`** — per turn, on `LLM_TURN_PERFORMANCE`, the sanitized terminal `done.llm_usage`, the assistant row's persisted `llmUsageJson`, and the UI turn-details view. It is emitted only when it is greater than zero.

These fields are **additive**. `completionTokens` / `completionTokensTotal` stay the provider-reported **total** output count, and reasoning tokens are a **subset** of that total. Never derive "visible output" by subtracting one from the other, and treat a missing reasoning field as absent, not zero.

Reasoning and visible answer text share the same output budget (`max_completion_tokens`). When a round's `reasoningTokens` equals its `completionTokens`, the whole output budget went to reasoning; an empty final answer with `finish_reason=length` on such a round is the reasoning-exhaustion fingerprint.

## Implementation Path

### 1. Extend `StreamTokenUsage`

`StreamTokenUsage` should carry the same usage fields needed by `llmUsageJson`, not only prompt/completion totals.

Suggested shape:

- keep `ZERO`
- keep the current two-int constructor for simple tests and compatibility
- add a constructor or factory from `LlmResponse`, provider, and model
- expose `toUsageJson()` or equivalent compact serialization helper

The helper should return `""` for `ZERO` so user/tool rows remain compact.

### 2. Carry Usage Through `AgentLoop`

When an LLM round returns `LlmResponse`, build `StreamTokenUsage` from the full response and pass it to the stream appender for assistant rows.

This applies to:

- assistant rows that contain tool calls
- final assistant rows
- awaiting-approval assistant-tool-call rows

Tool result rows should continue to use `StreamTokenUsage.ZERO`.

### 3. Update `AgentMessageStreamAppender`

Update the append method and `toValueRow` to write `llmUsageJson`.

The persisted `toolCalls` redaction behavior must remain unchanged. Usage JSON must never include prompts, tool arguments, tool results, API keys, or raw provider response bodies.

### 4. Provider Parsing Enhancements

Current minimum parser coverage is acceptable for initial persistence:

- Anthropic cache read/create fields are already parsed.
- OpenAI/Azure cached prompt tokens are already parsed.

`reasoningTokens` is optional. Do not make reasoning-token parsing a blocker for this topic. If the OpenAI/Azure response parser already exposes `completion_tokens_details.reasoning_tokens`, it may add a numeric `reasoningTokens` to `llmUsageJson`; otherwise **omit** the key (do not persist `0` as a placeholder). Track richer output-token details as a later enhancement.

### 5. History Export and UI Hydrate

`ParlerConversationHistory` should include `llmUsageJson` so history export can carry it when needed. Inline transcript layout does not need to surface raw JSON; the gateway **turn details** popover may show sanitized numeric fields when present (see `CONTRACTS` / `parler-ui` turn-actions topic).

## Eval Harness Integration

The eval harness should parse `llmUsageJson` from assistant rows when present and fall back to the legacy columns when absent.

Per turn, report:

- aggregate `promptTokens`
- aggregate `completionTokens`
- aggregate `inputTokens`
- aggregate `outputTokens`
- aggregate `cacheReadInputTokens` (sum only values present in JSON for that turn)
- aggregate `cacheCreationInputTokens` (sum only values present)
- aggregate `cachedPromptTokens` (sum only values present)
- aggregate `reasoningTokens` (sum only when the field is present; treat missing as absent, not zero)
- provider request ids observed in that turn

Aggregation is over assistant rows in the turn delta. Do not sum user/tool rows.

Provider token fields do not have identical semantics across providers. In particular, Anthropic `inputTokens` is fresh input and excludes cache read/create tokens, while OpenAI/Azure `inputTokens` is the total prompt token count with cached tokens reported as a subset. The report may aggregate one turn or one AgentThing result, but cross-provider summaries must group by provider/agent label and must not publish one mixed `totalInputTokens` value across Anthropic and OpenAI/Azure rows.

For rows without `llmUsageJson`, use:

- `promptTokens` column as both `promptTokens` and `inputTokens`
- `completionTokens` column as both `completionTokens` and `outputTokens`
- treat optional cache and reasoning dimensions as **absent** (do not invent zeros for providers that never reported them)

This keeps old traces readable while requiring no migration.

If `llmUsageJson` is malformed or contains the stream truncation marker, the eval harness should record a structured trace parse error such as `llm_usage_json_parse_error` or `llm_usage_json_truncated_in_stream`. It should not raise a Python traceback. The semantic assertions for that turn may still run when the rest of the trace is complete, but usage/cache telemetry for the malformed row is ignored.

An empty-string `llmUsageJson` is equivalent to a missing field. The eval harness should use the same legacy fallback for both shapes.

## Reset Modes for Eval Fairness

The telemetry work should land together with eval reset modes because repeated model comparisons need comparable Stream state.

The harness should support:

| Mode | Behavior | Use case |
| --- | --- | --- |
| `fresh` | Current behavior: random title, new conversation id. | Lowest side effect diagnostic runs. |
| `stable_clear` | Stable title per suite/case/agent, then `ClearConversation(conversationId)`. | Repeatable compaction and behavior comparisons. |
| `stable_hard_reset` | Stable title, `ClearConversation`, then physical deletion of existing `AgentMessageStream` rows for that `conversationId`. | Fair performance baseline when long Stream history would skew access time. |

`stable_hard_reset` is an evaluation-only operation. It must not change product `ClearConversation` semantics.

Physical reset algorithm:

1. Use `GetOrCreateConversationId(title)` to obtain the stable `conversationId`.
2. Call `ClearConversation(conversationId)` on the target AgentThing.
3. Call `AgentMessageStream.QueryStreamEntries` with `source = conversationId`, large configurable `maxItems`, and `oldestFirst = true`.
4. For each returned row, read `id`.
5. Call `AgentMessageStream.DeleteStreamEntry({ streamEntryId: id })`.
6. Repeat until no rows remain or a configurable safety loop limit is reached.
7. If rows remain after the safety limit, mark the case as `infra_error` before Chat.

If `ClearConversation` fails because active pending approvals exist for that conversation, the eval harness should mark the case as `infra_error` with `phase = "clear_conversation"` and `code = "pending_approval_blocks_clear"`. It should not auto-approve, auto-reject, or otherwise resolve pending approvals in v1. Manual cleanup is safer and keeps evaluation reset from changing HITL audit semantics.

`stable_hard_reset` assumes the selected `conversationId` is not being written by another process during reset. The harness does not add a product-side lock. Operators must not run two `stable_*` eval jobs against the same suite/case/agent title at the same time.

Use a large configurable page size for `QueryStreamEntries`; default to `20000`, matching the existing stream history hard cap used elsewhere in the agent code. The deletion loop should continue querying and deleting pages until zero rows remain, not assume one page drains the conversation.

Report reset metadata:

- `conversationMode` / `resetMode`
- `conversationTitle`
- `conversationId`
- `streamRowsDeleted`
- `streamRowsRemainingAfterReset`

## Compaction Validation

With durable usage telemetry, compaction should be validated by paired eval runs:

- same suite
- same logical model/provider configuration
- same reset mode, preferably `stable_hard_reset`
- one configured AgentThing with replay compaction disabled
- one configured AgentThing with replay compaction enabled

The report should compare:

- semantic pass/fail
- selected outcomes
- tool call path
- prompt/input token totals
- output token totals
- provider cache fields
- 429 / provider infra failures

A compaction improvement is not valid if token usage drops but semantic behavior regresses on the same case.

The harness should not toggle replay compaction per `Chat` call in v1. Use separately configured AgentThings, for example `Demo_gpt54mini_NoCompact_Agent` and `Demo_gpt54mini_Compact_Agent`, and map them as separate labels in the eval matrix.

## Tests

Java tests should cover:

- `LlmResponse` usage fields still parse Anthropic cache read/create tokens.
- OpenAI/Azure parsing still captures cached prompt tokens.
- `StreamTokenUsage` serializes compact JSON and `ZERO` serializes empty.
- `AgentMessageStreamAppender` inline DataShape includes `llmUsageJson`.
- Appender writes empty usage JSON for user/tool rows and populated JSON for assistant rows.

Python tests should cover:

- eval parser aggregates `llmUsageJson` fields from assistant rows.
- eval parser falls back to legacy columns when `llmUsageJson` is absent or an empty string.
- eval parser records structured trace parse errors for malformed or truncated `llmUsageJson`.
- eval parser aggregates `reasoningTokens` when Java includes it and treats a **missing** `reasoningTokens` as absent (not as zero).
- `stable_hard_reset` uses `QueryStreamEntries` row `id` as `DeleteStreamEntry.streamEntryId`.
- `stable_hard_reset` drains multiple `QueryStreamEntries` pages.
- reset metadata appears in `report.json` / `report.md`.

## Guarantees

1. New assistant Stream rows include `llmUsageJson` when provider usage telemetry is available.
2. User and tool rows do not carry usage JSON.
3. Existing `promptTokens` and `completionTokens` columns still work.
4. Eval reports show cache/token fields without parsing Application Log.
5. Eval can run with `fresh`, `stable_clear`, and `stable_hard_reset` reset modes.
6. `stable_hard_reset` physically deletes only rows whose Stream `source` equals the selected `conversationId`.
7. No migration code is added for old rows.

## Report Layout

The Markdown report shows per-turn aggregate usage only; per-round details stay in `report.json` so Markdown remains readable. The report does not compute derived cache deltas or a dedicated compaction-delta table; paired runs are compared from the raw fields.
