# Tool Result Egress Control

**Status:** implemented — `ToolResultEgressGateway` is the central LLM-append boundary; numeric history emits compact evidence (`parler.numeric_history.compact.v1`); first-party tabular/list tools share the gateway's inline-vs-large policy; numeric chart sidecars are bounded (§5.4).

Scope: Parler agent tool results before they enter LLM replay, UI wire artifacts, AgentMessageStream, and conversation caches.

## 1. Problem

Parler has several local protections for large data:

- `INFOTABLE_LARGE` returns `sampleRows` plus `cacheId`.
- `fetch_cached_result` replay control compacts large cached pages.
- `tabulate_cached_result` and `summarize_cached_result` compute over cached tables without paging all rows into the LLM.
- chart builders can emit UI chart wire frames without making the final answer invent chart data.

Without a central boundary, a built-in tool that handcrafts its own LLM-visible JSON can bypass the large-result/cache/sample
discipline. The motivating failure class: `query_property_history` with aggregate `actions` used to return both
`aggregates` and a full `points` array (232 samples per call). Two such results entered the next LLM prompt, and the
local provider gate rejected the call as a single request too large:

```text
LLM_RATE_REJECTION providerThingName=AzureOpenAI54
reason=single_request_too_large
estimatedInputTokens=45379
reservedTokens=56897
tokensPerMinuteLimit=50000
```

## 2. Goal

One runtime egress policy that every tool result passes through before it becomes LLM replay content:

- no tool can append an unbounded row list, point list, or large JSON blob directly to the LLM message list;
- UI artifacts can still receive the data they need for tables/charts;
- full data is held in conversation-scoped caches or UI/chart side channels, not in prompt text;
- AgentMessageStream remains useful for diagnostics without becoming the durable source of giant replay bodies;
- deterministic cached tools remain the path for full-table/full-series computation.

The policy addresses the class of failures, not just `query_property_history`.

## 3. Design Principle

Separate tool execution from tool-result egress.

Tool execution answers: "What did the platform return?"

Tool-result egress answers: "What is safe and useful to send to each consumer?"

Every successful tool call produces an internal result. The central gateway then emits consumer-specific lanes:

| Lane | Consumer | Payload rule |
| --- | --- | --- |
| LLM evidence lane | active LLM messages and later replay | compact JSON only: schema, counts, samples, handles, aggregates, warnings |
| UI artifact lane | table/chart wire frames | enough rows/points to render current UI artifacts, subject to UI caps |
| cache lane | deterministic follow-up tools and chart builders | full tables/series stored by opaque `cacheId` / artifact handle |
| audit lane | AgentMessageStream diagnostics | the LLM evidence body plus metadata; no giant raw row/point blobs by default; bounded numeric chart sidecars (§5.4) are the only exception |

The LLM evidence lane is the only lane appended to the chat message list.

## 4. Egress Gateway

`ToolResultEgressGateway` (`com.thingworx.things.agent`) is the boundary in the Java agent runtime. Its main entry points:

- `compactForLlmAppend(toolName, toolCallId, rawContent, log[, dataClassification])` — the backstop applied at the LLM
  append point (§4.4); returns an `EgressResult` with the LLM content and raw/compact sizes.
- `planTabularEvidence(...)` / `applyTabularEvidence(...)` with `TabularEvidencePlan` — the shared inline-vs-large
  decision for first-party tabular/list tools (§6.1).

The invariant: executors do not decide large-payload prompt shape on their own, and the LLM append point does not accept
a large success body that has not crossed the gateway.

### 4.1 Relationship to Existing Compaction and Fetch-Cached Split Lane

This design does not add a fourth independent compaction system.

Parler already has two relevant mechanisms:

1. **Fetch-cached split lane.** `fetch_cached_result` registers a full page body by tool call id in `AgentToolContext`.
   `FetchCachedStreamLaneHelper` later resolves different bodies for Stream persistence and table downlinks.
2. **Replay compaction.** `LlmReplayCompactionGate` controls Tier A in-loop matrix sealing, Tier 0 cohort merging, and
   Tier B post-turn promotion. The current matrix path depends on eligible `$format` / tabular shapes.

How they fit together:

- the gateway is the single pre-append boundary immediately before tool results are appended to the active LLM message
  list;
- Tier A matrix sealing works on bodies behind the gateway, not as a separate defense for already-appended large bodies;
- the fetch-cached ThreadLocal split (`AgentToolContext` full-body registration by tool call id) is the UI/cache lane for
  page bodies and for numeric history (§5.3);
- Tier B post-turn promotion is a durability/long-session shrink pass for compact or matrix-shaped historic evidence; it
  is not the primary defense against same-turn provider rejection.

The gateway must not double-compact the same body or stamp incompatible `$format` markers. A compact body is stamped
once, in the format owned by its classifier.

### 4.2 Classifier

The gateway classifies result bodies into known payload families:

- tabular rows (`InfoTable`, `rows`, `rootEntityList`, `sampleRows`);
- numeric time series (`points`, `timestamp`/`value`, value-stream rows);
- chart-only data;
- scalar JSON/object results;
- large text/blob payloads;
- error envelopes.

Unclassified success payloads still get a byte/char cap before LLM egress. Classification improves fidelity; caps are the
backstop.

### 4.3 Central Thresholds

Thresholds are central constants in `ToolResultEgressGateway`, not repeated in each tool:

| Constant | Value | Applies to |
| --- | ---: | --- |
| `ARRAY_SAMPLE_LIMIT` | 20 | tabular result samples, numeric time-series samples, and inline-vs-large row decisions (more than 20 rows/points → cache/sample mode) |
| `LLM_EVIDENCE_CHARS_SOFT_CAP` | 8192 | serialized LLM-lane body; larger bodies (or any body containing an array) go through structural compaction |
| large text excerpt | 2000 chars | excerpt kept from an oversized text field |
| last-resort target | 6000 chars | target size when structural compaction alone is not enough; priority fields are kept |

Row/point count and serialized size both matter. The char cap applies to the serialized LLM-lane body after
sampling/format conversion.

### 4.4 Generic Backstop

The gateway applies a generic serialized-size backstop at the LLM append boundary.

If a success body reaches the gateway without a specialized classifier and exceeds the cap, the gateway compacts it
structurally, not by head/tail string truncation:

- preserve every small scalar/object field that is already present, including high-value answer fields such as
  `aggregates`, `columns`, `counts`, `status`, `code`, `resultKind`, identifiers, schema names, and warnings;
- reduce only the dominant oversized arrays or blobs by sampling or excerpting them;
- always emit valid JSON;
- set an explicit `omitted` / `truncated` / `rowsOmitted` marker on reduced fields;
- log raw chars, compact chars, and compact ratio (`TOOL_RESULT_EGRESS ... rawReplayChars= replayChars= compactRatio=`);
- append only the compact body to the LLM lane.

The backstop must preserve the answer when the answer is already a small structured field. For an aggregate-plus-points
body it keeps the tiny `aggregates` object and reduces only the large `points` array. The backstop is enforcement, not
observation: it closes the failure class even for tools without a specialized classifier.

## 5. Numeric History Shape

`query_property_history` (numeric branch) returns compact evidence plus a cached tabular series plus an optional chart
artifact, not full points JSON.

Numeric history uses the existing tabular vocabulary instead of a second "series" vocabulary. A point is a row. The
cache representation is a two-column table:

- `timestamp`
- `value`

It reuses the existing conversation `InfoTable` cache namespace and opaque `cacheId`. There is no
`seriesCacheId`; a second UUID namespace would bypass existing cached-table tools and chart-from-tabular support.

### 5.1 Tool result to LLM

For a raw trend request, the LLM-visible body is shaped like:

```json
{
  "status": "success",
  "resultKind": "NUMERIC_HISTORY_INLINE",
  "thingName": "SE.CellFab.Model.Workunit.MUC-JetDryer-01",
  "propertyName": "dryingSpeed",
  "totalRows": 232,
  "returnedRows": 20,
  "sampleOnly": true,
  "rowsOmitted": true,
  "columns": [
    {"name": "timestamp", "baseType": "DATETIME"},
    {"name": "value", "baseType": "NUMBER"}
  ],
  "sampleRows": [
    {"timestamp": "2026-06-04T04:55:24.277Z", "value": 6.01}
  ],
  "cacheId": "...",
  "chartEmitted": true,
  "requested_time_range": {"start": "...", "end": "..."},
  "applied_time_window": {"source": "NATURAL_LANGUAGE_RELATIVE_DURATION", "start_utc": "...", "end_utc": "..."}
}
```

For a statistical request with `actions`, the LLM-visible body prioritizes aggregates:

```json
{
  "status": "success",
  "resultKind": "NUMERIC_HISTORY_AGGREGATES",
  "thingName": "SE.CellFab.Model.Workunit.MUC-JetDryer-01",
  "propertyName": "dryingSpeed",
  "totalRows": 232,
  "sampleOnly": true,
  "rowsOmitted": true,
  "columns": [
    {"name": "timestamp", "baseType": "DATETIME"},
    {"name": "value", "baseType": "NUMBER"}
  ],
  "aggregates": {
    "MEAN": 5.323706896551724,
    "MIN": 0.07,
    "MAX": 9.97,
    "STANDARD_DEVIATION": 2.910521004868213,
    "VARIANCE": 8.471132519779072,
    "MEDIAN": 5.375,
    "COUNT": 232.0,
    "FIRST": 6.01,
    "LAST": 1.64
  },
  "returnedRows": 0,
  "cacheId": "...",
  "chartEmitted": false,
  "hint": "Full rows were cached and not placed in the LLM prompt. Use deterministic cached-table tools or aggregate actions for further computation."
}
```

This makes the common statistical answer path small and exact. `chartEmitted=false` for aggregate-only statistical turns is
intentional unless the user also requested a chart.

For small results (`totalRows <= 20` and serialized evidence under the soft cap), the LLM-visible body may carry all rows
in `sampleRows`, set `sampleOnly=false`, set `rowsOmitted=false`, and omit `cacheId` when no downstream full-series
operation is needed.

`NUMERIC_HISTORY_INLINE` and `NUMERIC_HISTORY_AGGREGATES` are deliberate tool-specific `resultKind` values. They are in
the matrix codec's eligible result-kind allowlist (`InfoTableMatrixCodec`) so numeric-history compact bodies can still use
tabular matrix/summary machinery. They are not replaced with generic `INFOTABLE` because the result kind is useful
diagnostic provenance; the underlying table data uses the existing `cacheId` namespace.

### 5.2 Cache representation

Numeric history uses the existing conversation `InfoTable` cache namespace by storing a two-column table:

- `timestamp` (`DATETIME` or string ISO representation)
- `value` (`NUMBER`)

Benefits:

- `build_chart_from_tabular_result` can chart from the cached series.
- cached-table tools can read the same cache.
- the LLM sees one familiar handle model: `cacheId`.

The public LLM body does not carry a full `points` field; samples appear in `sampleRows` with `sampleOnly=true` /
`rowsOmitted=true`.

Timestamp cells and column metadata must agree. If `columns[].baseType` is `DATETIME`, row cells must be valid datetime
values for the table/chart code path. If the executor can only emit strings, use a string-compatible base type and ensure
chart builders parse the axis consistently.

### 5.3 Chart emission

Chart data does not depend on the LLM-visible body. The executor registers the full numeric-history body (still in the
`points` shape) for the call id through the same `AgentToolContext` split mechanism used by `fetch_cached_result`
(`setToolEgressFullJsonForToolCall`). Compact evidence goes to the LLM and Stream lanes; the chart/table downlink
resolver reads the registered full body for live UI artifact emission, so `ParlerChartWireSupport` produces the same
ChartBlock as before compaction. The full body is never appended to the LLM or audit lanes.

### 5.4 Numeric chart sidecar storage budget

There is one narrow exception to the audit-lane rule: compact numeric-history Stream rows may carry a `chartBlock`
sidecar so history export can hydrate the previously shown chart. That sidecar is **history-chart-hydrate only**
(rule BP9 in [`nearterm/tool-cache-integration.md`](./nearterm/tool-cache-integration.md)) — it MUST NOT restore or recreate a live conversation `cacheId` /
`ArtifactCache` entry after JVM reload or Stage-2 Stream rehydrate. A persisted handle is never proof
that a cache entry is live.

That exception is bounded and never changes the LLM lane:

- the persisted row remains a compact numeric-history body;
- `chartBlock` is stripped before Stage-2 assistant-prose replay;
- history export hydrates charts only when `chartEmitted=true`;
- Stage-2 rehydrate NEVER writes live cache from `chartBlock`; `parlerRehydratedCacheLive`
  is set only when the `cacheId` already resolves in the live JVM conversation cache;
- aggregate-only rows with `chartEmitted=false` do not hydrate a chart and MUST NOT carry a sidecar
  for cache restore;
- old full `points` arrays remain invalid Stream rehydrate evidence.

Storage budget (`FetchCachedStreamLaneHelper`):

| Budget | Value | Behavior when exceeded |
| --- | ---: | --- |
| numeric chart sidecar points | 5000 x/y pairs | omit `chartBlock`; keep compact evidence and budget metadata |
| numeric chart sidecar serialized bytes | 256 KiB UTF-8 | omit `chartBlock`; keep compact evidence and budget metadata |

When either budget is exceeded, the Stream row must keep the compact evidence but omit `chartBlock` and add explicit
metadata:

```json
{
  "chartBlockPersisted": false,
  "chartBlockOmittedReason": "storage_budget_exceeded",
  "chartBlockPointCount": 5000,
  "chartBlockBytes": 300000,
  "chartBlockPointLimit": 5000,
  "chartBlockByteLimit": 262144
}
```

The degraded row is still valid compact historical evidence for the LLM. It cannot hydrate a history chart.
Rehydrate must mark the cache as historical if no live cache entry exists (never recreate one from the sidecar).

This budget is deliberately per Stream tool row. It prevents a single month-long or high-frequency trend query from
turning `AgentMessageStream` into a bulk time-series store. Long-term retention of high-cardinality trend data belongs in
ThingWorx value streams or a dedicated artifact/blob repository, not in conversation history rows.

Under-budget sidecars are persisted inline in `AgentMessageStream`. For example, a 242-point trend persists compact LLM
evidence (`returnedRows=20`, `sampleOnly=true`, `cacheId`) while the Stream row also carries the full 242-point
`chartBlock` for UI history hydrate. That is expected under this design; the LLM boundary is unaffected.

## 6. Generic Payload Policies

### 6.1 Tabular results

First-party tabular tools share one behavior:

- small inline result: full rows allowed only under row and char caps;
- large result: `cacheId`, `totalRows`, `columns`, `sampleRows`;
- deterministic computation: use `tabulate_cached_result` / `summarize_cached_result` or the cached decision tools;
- `fetch_cached_result`: display/browse only, with compact LLM evidence for large pages.

The inline-vs-large decision comes from `ToolResultEgressGateway.planTabularEvidence` / `applyTabularEvidence`
(`TabularEvidencePlan`): `invoke_service` INFOTABLE formatting, `query_entities`, `query_entities_by_taxonomy`,
`list_entities_by_type`, and taxonomy identifier large/ambiguous evidence share the result-kind, row-key, sample-size,
`cacheId`, and hint application path while keeping their existing public JSON keys. Some executors still build their
own JSON shape around that plan. Split-lane details for cached pages are in `large-table-replay-control.md`.

### 6.2 Numeric series

Numeric series are table-like for egress purposes. A point is a row.

Rules:

- more than 20 points or more than the evidence char cap triggers cache/sample mode;
- aggregate-only requests do not include full points in the LLM lane;
- chart wire may receive full points through the UI/artifact lane;
- AgentMessageStream persists compact evidence, not raw `points`; the only permitted numeric-series sidecar is the
  bounded `chartBlock` described in §5.4.
- numeric-history compact bodies carry `$format: "parler.numeric_history.compact.v1"` before persistence so
  diagnostics and rehydrate can recognize them without heuristics. If a later matrix-sealing step converts the body to
  `parler.infotable.matrix.v1`, the matrix marker owns the persisted body and the numeric compact marker must not be
  duplicated.

### 6.3 Large JSON/text

Unclassified large JSON and text results are compacted before LLM egress:

- keep `status`, `code`, `resultKind`, counts, names, ids, schema, and warnings;
- keep a bounded excerpt or sample;
- register a cache/artifact handle when the runtime can safely do so;
- include an explicit `omitted`/`truncated` marker.

This backstop catches tools that have no specialized classifier.

### 6.4 Errors

Error envelopes remain inline unless they exceed the cap. Long platform stack traces are shortened for the LLM lane and
preserved only in logs/diagnostics when appropriate.

## 7. Runtime Insertion Points

The gateway sits after tool execution and before all of these side effects:

1. append tool result to active LLM conversation;
2. persist tool result to `_conversations` / `AgentMessageStream`;
3. emit table/chart UI wire frames;
4. update task evidence and chart rescue state;
5. update last qualifying tabular cache mirrors.

Code paths that must route through it:

- `AgentLoop` tool execution path;
- `BuiltInTools` executor adapters;
- `PropertyToolsExecutor` (numeric history);
- `InvokeServiceExecutor` and `fetch_cached_result`;
- `QueryEntitiesExecutor`, `QueryEntitiesByTaxonomyExecutor`, `ListEntitiesByTypeExecutor`;
- `CachedTabularToolsExecutor`;
- `ParlerChartWireSupport`;
- `ParlerToolTableWireUtil` / table wire helpers;
- `AgentMessageStream` persistence and history rehydrate.

When changing any of these, the question is not "did this tool become smaller?" but "can any tool still bypass the
gateway and append a large success body directly to the LLM lane?"

## 8. Durability and rehydrate

- History rehydrate consumes compact persisted evidence and does not reintroduce full rows/points.
- Persisted compact numeric/table evidence is restored through the Stage-2 compact rehydrate normalization in
  `CompactFetchStreamRehydrate`: as assistant prose with the established prefix, not as orphaned `role=tool` messages
  that would violate provider tool/assistant pairing rules.
- Numeric-series compact bodies carry `$format: "parler.numeric_history.compact.v1"` unless they were already converted
  to a matrix format.
- Numeric `chartBlock` sidecars are for history-chart hydrate only (§5.4); the LLM replay path strips the sidecar even
  when it is present in Stream.
- Executor-local caps that predate the gateway are removed only after the gateway path is proven to produce the same
  output (route through the gateway first, then delete dead local cap code), so one bug cannot disable both protections.

## 9. Contract Impact

- Numeric-history compaction added no UI wire fields; the emitted ChartBlock is unchanged because the chart lane reads the
  registered full `points` body. `BuiltInTools` schema descriptions and `llm_tool_routing_guide.txt` state that the LLM
  receives compact evidence, not all points.
- A change to chart data source/provenance or chart history hydration that is wire-visible updates
  `CONTRACTS/CHART_CONTRACT.md`.
- New wire-visible `llm_usage`, table, or chart fields update `CONTRACTS/API_CONTRACT.md` /
  `CONTRACTS/UI_CLIENT_PROTOCOL.md`.
- Normative wire/tool-result shape changes bump `CONTRACTS/CONTRACT_VERSION.md`.

Provider shape direction is compacting-only, but model-facing tool-result schemas are still contracts for this project.

## 10. Operational Checks

- A statistical follow-up over two 24-hour numeric histories finishes without `single_request_too_large`, and the answer
  uses exact `aggregates` from the tools, not sample inference.
- Trend prompts still emit chart wire frames.
- ApplicationLog records raw vs LLM egress sizes (`TOOL_RESULT_EGRESS ... rawReplayChars replayChars compactRatio`) for
  compacted tool results.
- `llmUsage.promptTokens` for follow-up rounds stays bounded by compact evidence, not by full point arrays.
- `AgentMessageStream` contains compact evidence plus cache/chart metadata; month-long or high-frequency trends record
  `chartBlockPersisted=false` instead of an unbounded sidecar.
- Existing `INFOTABLE_LARGE` behavior still returns `cacheId` + sample; cached tabular tools still compute over the full
  cached table; `build_chart_from_tabular_result` still emits charts from cached tabular data.

## 11. Tests

Unit tests cover:

- numeric history with no `actions`, 5 points: inline rows allowed.
- numeric history with no `actions`, 232 points: LLM body has `sampleRows` capped at 20, `sampleOnly=true`,
  `rowsOmitted=true`, `cacheId`, and chart lane has full data.
- numeric history with `actions`, 232 points: LLM body has `aggregates`, no full `points`, and either `returnedRows=0` or
  a deliberately capped `sampleRows`.
- generic backstop over an aggregate-plus-points body keeps `aggregates` intact, reduces `points`, and emits valid JSON.
- chart support does not require the public LLM body to contain full `points`; the full-lane numeric-history body keeps
  the `points` shape and produces the same ChartBlock.
- `AgentMessageStream` persists compact numeric history evidence, not the full point list.
- gateway backstop compacts an unclassified large JSON array.
- the append path applies the backstop before a later LLM call can be built.
- first-party tool execution path cannot bypass the gateway for success bodies.
- compact numeric-history Stream rows rehydrate through the Stage-2 assistant-prose path and do not create orphan tool
  messages.
- over-budget numeric chart sidecars are omitted from Stream persistence; under-budget sidecars never restore cache on
  rehydrate.
- `NUMERIC_HISTORY_INLINE` and `NUMERIC_HISTORY_AGGREGATES` are eligible for matrix encoding when they carry tabular
  `columns` plus row/sample arrays.

## 12. Non-goals

- Provider token estimator redesign.
- Making every possible statistical operation available as a tool action.
- Replacing `tabulate_cached_result` / `summarize_cached_result`.
- Removing chart wire frames.
- Using `AgentMessageStream` as an unbounded durable store for every UI page or chart data point. Bounded numeric
  `chartBlock` sidecars are the exception and must obey §5.4.
- Prompt-only steering as a substitute for runtime enforcement.

## 13. Invariants

1. Compact numeric-history evidence uses `$format: "parler.numeric_history.compact.v1"` when persisted in its compact
   numeric shape. If later matrix-sealed, the body uses the existing `parler.infotable.matrix.v1` marker instead.
2. Numeric history uses tool-specific result kinds `NUMERIC_HISTORY_INLINE` and `NUMERIC_HISTORY_AGGREGATES`; both are in
   the matrix codec eligible result-kind allowlist.
3. Tool schema text and the routing guide change in lockstep with any LLM-visible numeric-history result shape change.
4. Numeric-history Stream `chartBlock` sidecars are allowed only under the §5.4 storage budget for history-chart hydrate.
   Over-budget rows remain compact evidence but cannot hydrate charts. No `chartBlock` path restores or recreates a live
   cache entry.
5. First-party tabular/list tools take inline-vs-large evidence thresholds from `ToolResultEgressGateway`, not private
   executor constants.
