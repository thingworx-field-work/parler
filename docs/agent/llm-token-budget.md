# LLM token budget: observability, prompt caching, and replay compaction

This document describes how `parler-agent` makes LLM token consumption visible and how it keeps the
repeated part of each request small:

- **Provider observability** (§1–§2): safe HTTP failure and 429 diagnostics, provider usage and cache
  telemetry, and per-round tool-schema telemetry in the Application Log.
- **Provider prompt caching** (§3–§4): Anthropic `cache_control` breakpoints and the stable leading
  system row that OpenAI / Azure automatic prompt caching relies on.
- **Replay compaction** (§5–§9): deterministic rewriting of tool-result bodies in the in-memory
  conversation (`_conversations`) so later LLM rounds and turns replay less text — Tier A matrix
  encoding, Tier 0 cohort merging, and Tier B post-turn summary promotion.

Related documents:

| Topic | Document |
| --- | --- |
| Context budget planning (`LLM_CONTEXT_PLAN`), storage trimming, checkpoints | [`context-compaction.md`](context-compaction.md) |
| Anthropic breakpoint placement in detail | [`anthropic-breakpoint.md`](anthropic-breakpoint.md) |
| Stable system prompt prefix and cache hygiene | [`system-prompt-cache.md`](system-prompt-cache.md) |
| Durable usage fields on `AgentMessageStream` rows | [`llm-usage-stream-telemetry.md`](llm-usage-stream-telemetry.md) |
| Provider Things, request routing | [`llm-api-provider.md`](llm-api-provider.md) |
| Provider retry, failover, and client-side rate control | [`nearterm/service-provider-resilience.md`](nearterm/service-provider-resilience.md) |

Some Javadoc comments refer to the observability and caching work (§1–§4) as "Phase 1" and to replay
compaction (§5–§10) as "Phase 2". Those labels name the two halves of this document.

## Code map

| Concern | Classes |
| --- | --- |
| HTTP failure / 429 diagnostics | `LlmHttpDiagnostics` |
| Usage and schema telemetry lines | `LlmUsageTelemetry`, `LlmResponse`, `ToolSchemaSizer` |
| Anthropic request serialization and cache markers | `AnthropicMessagesApi`, `AnthropicMessagesLlmClient` |
| OpenAI / Azure request serialization | `ChatCompletionsApiMessages`, `OpenAiChatCompletionsClient`, `AzureOpenAILlmClient` |
| Shared stable-first-row predicate | `LeadingSystemRow` |
| Agent loop, round-boundary compaction hook | `AgentLoop` |
| Replay compaction | `LlmReplayCompactionGate`, `LlmToolResultReplayCompactor`, `LlmToolResultMatrixSealer`, `InfoTableMatrixCodec`, `LlmToolResultCohortMerger`, `LlmToolResultTierBPromoter`, `EntityMetadataSummaryCodec` |
| Post-turn replay normalization before `_conversations.put` | `ConversationsReplayNormalization` |
| Replay-format routing guide | `LlmRoutingGuide`, resource `llm_replay_format_routing_guide.txt` |

## 1. HTTP failure and 429 diagnostics

Every non-2xx LLM HTTP response is logged through `LlmHttpDiagnostics` before the failure is raised.
The original failure still reaches the caller; diagnostics never replace or mask it.

`LLM_HTTP_FAILURE` (`WARN`) carries:

- provider identity: `providerThingName`, `providerTemplateName`, `apiShapeId`, `model`
- `urlClass`, `status`, `rate_limited` (`true` only for HTTP 429)
- `elapsedMs`, configured `timeoutMs`
- request shape: `messages`, `tools`, requested output budget `maxTokens`
- `bodyPreview`: capped, single-line response body preview
- `safeHeaders=[...]`: allowlisted response headers only

On HTTP 429 a second `WARN` line, `LLM_RATE_LIMIT`, is always emitted with the provider identity,
`status`, `elapsedMs`, and `safeHeaders`. For 429s the headers usually carry the actionable detail
(retry hint, request id, remaining quota); some providers return an empty or generic body.

Header allowlist:

- `retry-after`
- `request-id`, `x-request-id`, `x-ms-request-id`, `apim-request-id`
- `openai-version`, `x-ms-deployment-target`
- any header starting with `anthropic-ratelimit-` or `x-ratelimit-`

A header the provider did not return is simply absent. API keys, auth headers, cookies, and request
bodies are never logged.

## 2. Usage, cache, and tool-schema telemetry

### 2.1 Response usage fields

`LlmResponse` carries provider usage beyond prompt / completion counts:

| Field | Meaning |
| --- | --- |
| `inputTokens` | Provider-reported input tokens. |
| `outputTokens` | Provider-reported output tokens (alias of `completionTokens`). |
| `cacheReadInputTokens` | Input tokens read from the provider prompt cache (Anthropic). |
| `cacheCreationInputTokens` | Input tokens written to the provider prompt cache (Anthropic). |
| `cachedPromptTokens` | Cached prompt tokens (OpenAI / Azure `usage.prompt_tokens_details.cached_tokens`). |
| `providerRequestId` | Provider request id from the response header or body, when present. |

Provider mapping and the no-double-counting rules:

- **OpenAI / Azure:** `promptTokens` and `inputTokens` both equal `usage.prompt_tokens`.
  `cachedPromptTokens` is a subset of that value and is never added again. A missing
  `prompt_tokens_details` yields `cachedPromptTokens=0`.
- **Anthropic:** `inputTokens` is the raw `usage.input_tokens` (fresh input after cache breakpoints).
  `promptTokens` is the aggregate `inputTokens + cacheCreationInputTokens + cacheReadInputTokens`, so
  aggregate turn events do not undercount the prompt side.
- `getTotalTokens()` is `promptTokens + completionTokens`. Callers must not add cache fields to it.

### 2.2 `LLM_USAGE`

Each successful LLM round logs one `INFO` line:

```text
LLM_USAGE providerThingName=... providerTemplateName=... apiShapeId=... model=... messages=... tools=...
  input=... prompt=... cacheRead=... cacheCreate=... cachedPrompt=... output=... completionTokens=...
  [reasoningTokens=...] [requestId=<provider-request-id>] parlerRequestId=<parler-turn-id> [rounds=...]
  [rawReplayChars=... replayChars=... compactRatio=...]
```

- `requestId` is the per-round provider response id, when the provider returned one.
- `parlerRequestId` is the Parler turn id (empty when unknown). It matches `LLM_CONTEXT_PLAN.requestId`
  and `LLM_TOOL_SCHEMA_USAGE.parlerRequestId`, so all lines of one turn can be joined.
- `rawReplayChars` / `replayChars` / `compactRatio` describe replay compaction of the previous tool
  batch (§5): total tool-result characters before and after compaction, and `replayChars / rawReplayChars`
  with four decimals. They are present only when `rawReplayChars > 0`.

When an LLM call throws before `LLM_USAGE` can be written (for example on HTTP 429), `AgentLoop` logs
`LLM_REPLAY_PENDING_COMPACTION` with the pending batch's replay statistics so they are not lost.

The values are observability only; no agent behavior branches on them.

### 2.3 `LLM_TOOL_SCHEMA_USAGE`

Every round sends the full registered tool list for that request. Alongside `LLM_USAGE`, each round
logs one `INFO` line that shows which offered tools the model actually used:

| Field | Meaning |
| --- | --- |
| `rounds` | Round index in the turn. |
| `parlerRequestId` | Same Parler turn id as `LLM_USAGE`. |
| `schemaCount` / `schemaTools=` | Tools whose definitions were sent on this request. |
| `calledCount` / `calledTools=` | Tools the model invoked this round (empty on a final text-only round). |
| `idleCount` / `idleTools=` | `schemaTools` minus `calledTools`. |
| `toolSchemaChars` | Serialized size of the tools array. |
| `toolSchemaSizes=` | Per-tool `name:chars`, largest first; `toolSchemaSizesSum` and `toolSchemaFramingChars` split the total. |
| `unknownCalls=` | Called names absent from `schemaTools`. Present only when non-empty; indicates a wiring bug. |
| `listTruncated=1` | A list field exceeded `LlmUsageTelemetry.MAX_TOOL_NAME_LIST_CHARS` (8192). |

`scripts/report-llm-tool-schema-usage.mjs` aggregates pasted lines into per-tool `schema` / `called` /
`idle` counts. It is a smoke aggregator: it does not infer session boundaries. A handful of test
prompts proves the telemetry pipeline works; judging which tools are rarely used needs representative
traffic over a meaningful period plus review of rare but safety-critical tools such as
`acknowledge_alerts`.

## 3. Anthropic prompt caching

`AnthropicMessagesApi` serializes Parler's message list so that stable content is cacheable and
volatile content is not.

**System field.** The first `SYSTEM` row is the stable prompt (configured prompt plus stable context
cache) when `LeadingSystemRow.isStableFirstSystemRow` holds: `messages[0]` is `SYSTEM`, has no tool calls,
and has no tool-call id. The same predicate drives `ChatCompletionsApiMessages`. When it holds, the
`system` field is an array of text blocks and the first block carries `cache_control`:

```json
[
  {
    "type": "text",
    "text": "...stable first system row...",
    "cache_control": { "type": "ephemeral" }
  }
]
```

When no stable first row exists, the system text is sent without a breakpoint (a plain string when
there is a single block). Classified dynamic `SYSTEM` rows (current time, host context, task state,
and similar per-round rows) are moved to the terminal user-content suffix as uncached text blocks.

**Breakpoints.** Parler uses at most four:

1. the stable first `system` block
2. the last tool definition in the `tools` array, when tools are offered
3. the current substantive user frontier, on AgentLoop requests
4. the previous substantive user frontier, when one exists

Anthropic's cache hierarchy is `tools` → `system` → `messages`. The frontier is the last `tool_result`
block of a grouped user row, otherwise the row's text block; suffix-only carriers are never candidates.
Counts are therefore conditional: with tools, 3 on an initial request and 4 in steady state; without
tools, 2 and 3. A change in the offered tool set invalidates cache reads even though the marker count
is valid.

Rules:

- Only AgentLoop requests get frontier markers. Task-state evidence, current time, host context, skill
  text, coverage guidance, and suffix-only carriers are never marked.
- AgentLoop user rows are serialized as block arrays for their whole lifetime, including older unmarked
  rows. Interactive entry points reject null or blank input; registered slash-only turns keep their
  trimmed directive. The serializer fails locally on any remaining null or blank ordinary user row.
  Probe, summary, playbook, and other non-AgentLoop callers send non-empty user strings and only the
  stable markers.
- Non-Anthropic providers are unaffected.
- If the provider rejects `cache_control`, the request fails with the provider error. Parler does not
  strip markers and resend.

Anthropic applies a model-specific minimum cacheable prefix size; see the vendor documentation for the
current table. A shorter marked prefix is processed without caching and without an error. If telemetry
shows `cacheCreate=0` and `cacheRead=0`, first check the model's minimum against the cumulative marked
prefix before suspecting serialization.

Expected pattern for repeated similar requests: the first shows `cacheCreate > 0`; later ones show
`cacheRead > 0`, provided the marked prefix stayed byte-stable.

See [`anthropic-breakpoint.md`](anthropic-breakpoint.md) for placement details and tests.

## 4. OpenAI / Azure prompt caching

OpenAI and Azure OpenAI cache long prompt prefixes automatically. `ChatCompletionsApiMessages` keeps the
stable leading system row first so the prefix stays byte-stable. Parler records `cachedPromptTokens`
from `usage.prompt_tokens_details.cached_tokens`, logs provider request ids, and logs 429 rate-limit
headers through §1. The request JSON shape is otherwise unchanged.

## 5. Replay compaction

Parler's workload is tool-heavy: a large stable prompt, many tool definitions, multi-round tool
execution, and tabular tool results that are replayed on every later round. A typical costly pattern is
homogeneous fan-out — the same tool with the same schema applied to several Things (for example
`get_property_values` or `query_alert_summary` across the robots of two regions). Replay compaction
exploits these known shapes deterministically rather than summarizing text with a model.

Compaction rewrites tool-result `content` in the in-memory `_conversations` list only. It runs at two
moments:

1. **Round boundary (Tier A, then Tier 0).** After a completed tool batch is appended and before the
   next LLM round of the same turn, `AgentLoop` calls `LlmToolResultReplayCompactor.compactBatch` with the
   index of the assistant `tool_calls` row that opened the batch. `LlmToolResultMatrixSealer` applies the
   matrix codec to each result, then `LlmToolResultCohortMerger` merges eligible cohorts. This shrinks the
   prompt within multi-round skills.
2. **Post-turn (Tier B).** Before `AgentThing` stores the transcript, `ConversationsReplayNormalization`
   may promote older matrix and cohort bodies to summaries (§8).

Compaction never touches `AgentMessageStream` rows; the persisted audit history stays raw.

**Gate.** `LlmReplayCompactionGate.isReplayCompactionEffective()` is true unless the JVM system
property `com.thingworx.parler.llmReplayCompaction.disableUnsafe=true` is set. That property is a
diagnostic switch: it suppresses Tier A, Tier 0, and Tier B for the whole process (telemetry keeps
working) and `LLM_CONTEXT_PLAN` reports `unsafeDisable=1`. There is no AgentSettings
field for compaction; a leftover `enableLlmReplayCompaction` entry in a deployed configuration table is
ignored with one startup `INFO`.

### 5.1 Recognized body formats

Every compacted body carries an explicit `$format` marker:

| `$format` | Produced by |
| --- | --- |
| `parler.infotable.matrix.v1` | Tier A |
| `parler.cohort.bundle.v1` | Tier 0, on the bundle-carrying member |
| `parler.cohort.member.v1` | Tier 0, on the other members |
| `parler.infotable.summary.v1` | Tier B, from a matrix or a cohort bundle's inner matrix |
| `parler.entity.metadata.summary.v1` | Tier B, from a `get_entity` body stamped `parler.entity.metadata.v1` |

A body that already carries one of these markers is passed through unchanged by the round-boundary
compactor.

## 6. Tier A: InfoTable matrix (`parler.infotable.matrix.v1`)

`InfoTableMatrixCodec` rewrites object rows into arrays ordered by a column header. It is driven by the
result's shape, not the tool name, so built-in tools, `invoke_service` INFOTABLE results, and custom
tools all benefit without tool-specific code.

```json
{
  "status": "success",
  "resultKind": "INFOTABLE",
  "rowCount": 18,
  "$format": "parler.infotable.matrix.v1",
  "columns": [
    {"name": "thingName", "baseType": "THINGNAME"},
    {"name": "alertType", "baseType": "STRING"},
    {"name": "timestamp", "baseType": "DATETIME"}
  ],
  "constants": {"ack": false},
  "rows": [
    ["RobotA", "Above", "2026-05-10T15:10:39Z"]
  ]
}
```

Encoding rules:

- `columns[k]` gives the name and `baseType` of value `k` in every row array. Semantic column names are
  kept; there are no opaque aliases.
- Columns whose value is identical in every row move to `constants` and are omitted from the row arrays.
  `constants` keys and `columns[].name` are disjoint.
- The row array is written back under the same key it came from: `rows`, `sampleRows`,
  `rootEntityList`, or `sampleRootEntityList` (first present, in that order).
- All other envelope fields are preserved: `cacheId`, `rowCount`, `totalRows` / `totalCount`, sample
  markers, hints. Large cache-backed envelopes keep their outer semantics; only the row array changes.
- Nested INFOTABLE cells are kept as their JSON value; they are not encoded recursively.
- **Protected values:** if any column has `baseType` `PASSWORD`, or any cell equals the masked
  placeholder `***`, the table is not matrix-encoded at all.
- **Savings threshold:** matrix form is used only when `compactChars + 80 <= rawChars` and
  `compactChars <= rawChars * 0.95`; otherwise the original object rows stay for readability.

Eligibility:

| Payload | Behavior |
| --- | --- |
| `status:"success"` with `resultKind` in: `INFOTABLE`, `INFOTABLE_LARGE`, `ENTITY_QUERY_INLINE` / `_LARGE`, `ENTITY_LIST_INLINE` / `_LARGE`, `ENTITY_TAXONOMY_QUERY_INLINE` / `_LARGE`, `CACHED_TABULATE_INLINE` / `_LARGE`, `CACHED_FILTER_ROWS_INLINE` / `_LARGE`, `CACHED_FILTER_SORT_TOPN_INLINE` / `_LARGE`, `CACHED_GROUP_METRIC_INLINE` / `_LARGE`, `NUMERIC_HISTORY_INLINE`, `NUMERIC_HISTORY_AGGREGATES`, `VALUE_STREAM_HISTORY_INLINE` | Eligible. |
| `status:"success"` without `resultKind` but with `columns` and a tabular row array (for example `fetch_cached_result`) | Eligible by shape. |
| Any other `resultKind` (including `*_EMPTY` and `CACHED_SUMMARY_*`) | Passed through. |
| Error envelopes, non-success status, already-array rows, empty row arrays | Passed through. |
| Success envelope with missing / empty `columns`, no row array, or non-object rows | Passed through; one `WARN` `LLM_COMPACT_MALFORMED reason=...`. |

The codec never throws to its caller. When a new executor adds a tabular `resultKind`, the eligibility
set and this table change in the same commit.

## 7. Tier 0: cohort compaction

`LlmToolResultCohortMerger` detects homogeneous fan-out within one completed assistant tool batch and
merges it into one bundle. It runs after Tier A on the same batch.

**Eligible tools and dimension.** Cohorts are formed only for:

| Tool | Cohort dimension (first column) | Row shape |
| --- | --- | --- |
| `get_property_values` | `thingName` | one row per member |
| `query_alert_summary` | `thingName` | many rows per member |
| `query_alert_history` | `thingName` | many rows per member |

`query_alert_summary` members may be live single-Thing calls (`thingNames:[name]`) or older transcripts
with scalar `thingName`; both merge under the same filter key. A single call with two or more
`thingNames` (`ALERT_SUMMARY_MULTI`) is not a cohort member. All other tools — including
`invoke_service`, custom tools, side-effecting tools such as `acknowledge_alerts`, and chart tools — never
join a cohort; their INFOTABLE results still receive Tier A.

**Detection rules.**

1. A cohort needs at least three successful sibling results of the same tool in the same batch that
   share a grouping key:
   - `get_property_values`: the same requested `propertyNames` set (order-independent);
   - alert tools: the same arguments apart from Thing identity (`thingName` / `thingNames`) and the same
     result columns.
2. Error envelopes and unrecognized bodies are excluded and stay individual. If fewer than three
   successful siblings remain in a group, that group is left alone.
3. Each member keeps only its discriminating argument (the Thing identity) in `args`.
4. The merge is skipped (`LLM_COHORT_SKIP reason=...`) when it would not save characters or when a safety
   check fails — for example non-OK property rows or mixed base types for `get_property_values`, or
   alert matrices that already carry `constants`.

**Placement.** The bundle is stored in the earliest member's tool-result message; every other
member's message is replaced by a member reference. Message order, roles, and provider `tool_call_id`
values are unchanged, so provider tool-call / tool-result pairing stays valid. The bundle's `result` is
always matrix form.

```json
{
  "$format": "parler.cohort.member.v1",
  "cohortId": "cohort-1",
  "memberIndex": 2,
  "bundleToolCallId": "call_abc",
  "toolName": "query_alert_summary",
  "args": {"thingNames": ["RobotC"]}
}
```

```json
{
  "$format": "parler.cohort.bundle.v1",
  "cohortId": "cohort-1",
  "toolName": "query_alert_summary",
  "memberCount": 3,
  "members": [
    {"memberIndex": 0, "toolCallId": "call_aaa", "args": {"thingNames": ["RobotA"]}},
    {"memberIndex": 1, "toolCallId": "call_bbb", "args": {"thingNames": ["RobotB"]}},
    {"memberIndex": 2, "toolCallId": "call_ccc", "args": {"thingNames": ["RobotC"]}}
  ],
  "result": {
    "$format": "parler.infotable.matrix.v1",
    "rowCount": 5,
    "columns": [
      {"name": "thingName", "baseType": "THINGNAME"},
      {"name": "alertType", "baseType": "STRING"},
      {"name": "name", "baseType": "STRING"}
    ],
    "rows": [
      ["RobotA", "Above", "High_Wrist_Temperature"],
      ["RobotA", "NotEqualTo", "EmergencyStop"],
      ["RobotB", "Above", "High_Wrist_Temperature"],
      ["RobotC", "Above", "High_Wrist_Temperature"],
      ["RobotC", "Below", "OperationalVoltageLow"]
    ]
  }
}
```

Member `args` preserve each call's wire shape: `thingNames:[name]` for live calls, `thingName` for older
scalar calls. `members[]` follows the original sibling order. For one-row-per-member tools,
`result.rows[i]` corresponds to `members[i]` and `rowCount == memberCount`. For many-rows-per-member tools,
`result.rows` is the union of all members' rows and the `thingName` column identifies the source member.
The row shape is fixed per tool, not inferred from runtime row counts.

## 8. Tier B: post-turn summary promotion

`LlmToolResultTierBPromoter` shrinks historic tool results once they are no longer part of the current
turn.

**When it runs.** From `ConversationsReplayNormalization`, immediately before `AgentThing` stores the
transcript in `_conversations`, and only when all of the following hold:

- the turn ended with status `SUCCESS`;
- replay compaction is effective (§5);
- the replay exceeds the `llmContextMaxChars` storage budget before normalization — within-budget
  transcripts stay append-only so an already-written provider prompt-cache prefix is not invalidated;
- the conversation has no active HITL pending approval (promotion is deferred so replay stays aligned
  with the pending snapshot).

Tier B does not run during stream rebuild. Storage trimming and checkpoints that follow it are
described in [`context-compaction.md`](context-compaction.md).

**What it rewrites.** Only messages strictly before the latest user message:

- `parler.infotable.matrix.v1` tool results → `parler.infotable.summary.v1`;
- a `parler.cohort.bundle.v1` keeps its envelope and `members[]`, and its inner matrix `result` becomes a
  `parler.infotable.summary.v1`; `parler.cohort.member.v1` references are unchanged;
- `get_entity` bodies stamped `parler.entity.metadata.v1` → `parler.entity.metadata.summary.v1`.

A rewrite is applied only when the new body is strictly smaller than the original; otherwise the body is
kept and `LLM_TIER_B_SKIP reason=no_shrink` is logged. Assistant `tool_calls` arguments are never
changed. Each pass logs `LLM_TIER_B_PROMOTED` with `promoted`, `entityMetadataPromoted`,
`skippedNoShrink`, `beforeChars`, and `afterChars`. JSON failures log `LLM_TIER_B_SKIP reason=parse` or
`reason=summary_serialize`; an unsafe or unserializable cohort summary leaves the bundle unchanged with
`LLM_TIER_B_COHORT_UNCHANGED`.

**Summary envelope.**

```json
{
  "$format": "parler.infotable.summary.v1",
  "status": "success",
  "originalToolName": "query_alert_summary",
  "originalToolCallId": "call_abc",
  "resultKind": "INFOTABLE",
  "rowCount": 18,
  "totalCount": 18,
  "cacheId": null,
  "sourceCacheIds": [],
  "sampleOnly": false,
  "protectedOmissions": false,
  "cohortDimension": "thingName",
  "memberSummaries": [
    {"memberIndex": 0, "dimensionValue": "RobotA", "rowCount": 7},
    {"memberIndex": 1, "dimensionValue": "RobotB", "rowCount": 5}
  ],
  "columns": [
    {"name": "thingName", "baseType": "THINGNAME", "cardinality": 5, "top": [["RobotA", 7], ["RobotB", 5]]},
    {"name": "priority", "baseType": "INTEGER", "numericStats": {"min": 1, "max": 5, "mean": 3.2}},
    {"name": "timestamp", "baseType": "DATETIME", "range": ["2026-05-08T00:00:00Z", "2026-05-10T15:10:39Z"]}
  ]
}
```

Two forms:

- **With a non-empty `cacheId` (pointer summary).** Keeps `rowCount`, `totalCount`, `sampleOnly`,
  `cacheId`, `constants`, and `columns[]` with `name` and `baseType` only — no statistics. A cache entry
  can expire, so stale aggregates are not carried; a follow-up that needs rows re-runs the tool or uses
  the cached-result tools while the `cacheId` is valid. A cohort bundle whose inner matrix has a
  `cacheId` omits `memberSummaries`, because sample row counts are not authoritative per-member totals.
- **Without a `cacheId` (self-contained summary).** Column statistics are computed from the replay matrix:
  - numeric columns: `numericStats` `min` / `max` / `mean`;
  - string-like columns: `cardinality` and `top` buckets (ties broken by key); scalar cells use their text
    form, with `(null)` and `(complex)` marking missing and container cells;
  - `DATETIME` columns: `range: [isoMin, isoMax]`, ordered by parsed instant and reported with the original
    cell strings; `range` is omitted if any non-empty cell fails to parse;
  - cohort bundles set `cohortDimension` and `memberSummaries` from the member `args`.

Common rules: `constants` are copied verbatim; `totalCount` / `sampleOnly` follow the large and taxonomy
envelope semantics; row arrays are read with the same key resolution as the matrix codec. Protected
columns are omitted from `columns`, `memberSummaries`, and `top` buckets, and `protectedOmissions: true`
records it (an all-`PASSWORD` matrix promotes to empty `columns`). `sourceCacheIds` may be empty and is
never filled with an invented id.

After promotion, exact rows are no longer in replay. The replay keeps aggregate evidence and enough
member context to route a follow-up; exact rows come from a re-query or a still-valid `cacheId`. The
summary must still satisfy the guards in [`evidence-grounded.md`](evidence-grounded.md), notably
empty-success and tool-error handling.

A property value is never treated as unchanged just because its metadata has `dataChangeType=NEVER`;
that setting controls data-change events, not immutability.

## 9. Invariants

- **Idempotent.** Running any tier twice yields the same bodies.
- **Message-safe.** Message order, roles, tool-call ids, and provider tool-call / tool-result pairing are
  preserved. Assistant `tool_calls` rows are never rewritten.
- **Round-boundary single seal.** A tool result sealed at round N is immutable for the rest of that user
  turn. This keeps batch indices stable and preserves the byte identity required by Anthropic's
  current / previous frontier markers (§3). The round-boundary compactor passes every recognized
  `$format` body through unchanged.
- **Tier B is the only exception** to single seal: it runs after the turn's final answer, may rewrite an
  already sealed matrix or bundle, and is itself idempotent. Later turns treat its output as sealed.
- **Completed batches only.** A batch is compacted only after every tool call in the assistant row has
  its tool-result message; an approval-pending batch is compacted when it completes on continuation.
- **Raw-snapshot ordering.** Every in-process consumer of a tool result reads the raw object-row JSON
  before replay compaction: task-state evidence merge (`AgentTaskEvidence`), `AgentMessageStreamAppender`,
  table and chart downlinks, tabular cache mirrors, history export (`AgentMessageStreamHistoryExporter`),
  and logs. Table wire classes such as `ParlerInvokeServiceInfotableTableWire`,
  `ParlerQueryEntitiesEntityListTableWire`, `ParlerListEntitiesEntityListTableWire`,
  `ParlerFetchCachedResultTableWire`, `ParlerTaxonomyEntityListTableWire`, and
  `ParlerTabulateEntityListTableWire` read object rows and therefore stay on the raw side. Any future
  consumer that reads rows from `_conversations` after compaction uses the shared `InfoTableMatrixCodec`
  decoder rather than its own matrix parsing.
- **Evidence-safe.** Evidence is merged from raw tool JSON; it is never re-derived from matrix, cohort, or
  summary bodies.
- **Protection-safe.** Protected columns are never lifted into constants, cohort headers, or summary
  statistics.
- **Prompt-cache-safe.** The leading stable system row and dynamic system rows are never rewritten.
- **Transient rows untouched.** Rows injected only into the outbound request (task state, time row) are
  not compacted.
- **Stream rebuild.** A transcript rebuilt from `AgentMessageStream` starts raw; no compaction runs during
  rebuild, and it resumes at the next round boundary.

## 10. Replay-format routing guide

The model is told how to read compacted bodies by `llm_replay_format_routing_guide.txt`, which
`LlmRoutingGuide.appendReplayFormatRoutingGuide` appends to the stable leading system prompt after the
built-in tool routing guide. It explains:

- matrix v1: `columns[k]` maps to value `k` of each row in whichever row field the envelope uses (`rows`,
  `sampleRows`, `rootEntityList`, `sampleRootEntityList`); `constants` apply to every row; `cacheId` and
  totals keep their meaning;
- cohort v1: a member reference points to the bundle in the tool-result message identified by
  `bundleToolCallId`; how rows map to members; that a promoted bundle result is a summary;
- summary v1: use column statistics and `memberSummaries` for aggregate evidence; re-query or use a valid
  `cacheId` for exact rows.

The guide is part of the stable, provider-cacheable prefix. It is appended together with the built-in
tool routing guide, that is, when the `appendBuiltInToolRoutingGuide` AgentSetting is true.

## 11. Configuration

| Setting | Effect |
| --- | --- |
| JVM `com.thingworx.parler.llmReplayCompaction.disableUnsafe=true` | Diagnostic only: disables Tier A, Tier 0, and Tier B for the process. Telemetry is unaffected. |
| AgentSetting `appendBuiltInToolRoutingGuide` | Appends the built-in tool routing guide and the replay-format routing guide (§10) to the stable prompt. |
| AgentSetting `llmContextMaxChars` | Storage budget for `_conversations`; Tier B runs only when the replay exceeds it (§8). |

## 12. Verification

Unit tests cover request serialization through extracted helpers (no live HTTP): Anthropic `system`
blocks and breakpoint placement, the last-tool marker, uncached dynamic rows, usage parsing for both
provider families (including missing `prompt_tokens_details`), header redaction, the 429 path logging
`LLM_RATE_LIMIT` while raising the original failure, matrix codec fixtures, cohort merging, Tier B
promotion, and the raw-snapshot ordering invariant.

```bash
cd parler-agent
./gradlew test --no-daemon -PuseLocalTwxLib=true
```

Manual check on a live server:

1. Configure an Agent Thing with an Anthropic provider and run `TestConnection`.
2. Run the same tool-heavy prompt twice; confirm `LLM_USAGE` shows `cacheCreate > 0`, then `cacheRead > 0`.
3. On a 429, confirm `LLM_HTTP_FAILURE` and `LLM_RATE_LIMIT` identify the provider, model, status,
   request id, and rate-limit headers.
4. Repeat with an Azure OpenAI provider and confirm `cachedPrompt` and rate-limit headers when available.
5. For multi-round prompts, confirm `rawReplayChars` / `replayChars` / `compactRatio` on later
   `LLM_USAGE` lines and that the final answer is unchanged in substance.

Prompts that exercise the main paths (run on both provider families when credentials are available):

```text
how many asset types do you have? please list them
```

```text
how many Stacking Robot in USA vs in Germany?
```

```text
/region_health Compare the Stacking Robots in the USA and Germany — what are the main issues on each side right now: speed, voltage, e-stop, or wrist temperature? Which region needs attention first?
```

```text
/asset_pair_health Compare the health status of Jet Dryer assets ORD JetDryer 02 and AC JetDryer 01 over the past 24 hours
```

```text
/asset_pair_health Compare the MUC JetDryer 02 and BOS JetDryer 01 health status
```

```text
please show me 3 rows from AutoLaunchConfigTable
```

```text
please show me the trend of currentDraw in the last 30 minutes
```

For each prompt, capture provider / model, the token and cache fields, round count, tool-call count, the
`LLM_TOOL_SCHEMA_USAGE` lines, the replay character fields, and whether the final answer changed.

## References

- Anthropic rate limits: https://docs.anthropic.com/en/api/rate-limits
- Anthropic prompt caching: https://platform.claude.com/docs/en/docs/build-with-claude/prompt-caching
- Anthropic Messages API: https://platform.claude.com/docs/en/api/messages/create
- [`conversation-continuity.md`](conversation-continuity.md), [`task-state.md`](task-state.md),
  [`evidence-grounded.md`](evidence-grounded.md)
- [`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md),
  [`CONTRACTS/TABLE_CONTRACT.md`](../../CONTRACTS/TABLE_CONTRACT.md),
  [`CONTRACTS/CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md)
