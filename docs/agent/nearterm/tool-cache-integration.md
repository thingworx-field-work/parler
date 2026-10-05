# Tool and artifact-cache integration

Topic: `tool-cache-integration`

This document describes how Parler's tools produce, reference and consume large results through the
single file-backed `ArtifactCache`: the public `cacheId` vocabulary, the EMPTY / INLINE / LARGE
result envelope, the runtime `SourceDescriptor`, the invocation context and operation budgets, large
JSON and nested drill-in, TOKEN (last qualifying table) handling, replay, and the fault policy.

The physical cache kernel (file layout, opaque byte streaming, in-memory index, close-then-publish,
namespace checks, invalidation, storage I/O limits, PASSWORD-before-create) is described in
[cache-correctness-foundation](./cache-correctness-foundation.md). This document covers only the
layer above it. Stable labels used in code and other documents: BP1–BP9 (§3), decisions 1–19 (§4)
and the E / B / S register ids (§5).

Related behavior documents:

- [`cached_tabular_tools.md`](../cached_tabular_tools.md),
  [`cached-table-decision-tools.md`](../cached-table-decision-tools.md)
- [`large-table-replay-control.md`](../large-table-replay-control.md),
  [`tool-result-egress-control.md`](../tool-result-egress-control.md)
- [`p2_last_tabular_cache.md`](../p2_last_tabular_cache.md), [`query-spec.md`](../query-spec.md),
  [`task-state.md`](../task-state.md), [`history-overlay-chart.md`](../history-overlay-chart.md)
- [`CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md),
  [`CONTRACTS/ENTITY_SET_TOOL.md`](../../../CONTRACTS/ENTITY_SET_TOOL.md),
  [`CONTRACTS/TABLE_CONTRACT.md`](../../../CONTRACTS/TABLE_CONTRACT.md)

## 1. Data flow

Every large-result producer and consumer uses one `ArtifactCache` handle contract, while LLM
context, replay, UI and tool schemas stay bounded:

```text
query / invoke / list
  -> EMPTY | INLINE | LARGE public result
  -> opaque cacheId for LARGE
  -> fetch / summarize / tabulate / inspect / extract
  -> derived cacheId with lineage
  -> chart or compact final-answer evidence
```

No step exposes a file path, and no step puts a complete large payload into LLM context.

Roles are fixed: `fetch_cached_result` pages rows; `tabulate_cached_result` transforms;
`summarize_cached_result` produces compact column statistics; `build_chart_from_tabular_result` and
`build_history_overlay_chart` present server-calculated rows; `inspect_cached_payload` and
`extract_nested` drill into cached JSON and nested tables. Inspection never becomes filesystem
access.

Producers and consumers that use the cache hub include `InvokeServiceExecutor`,
`QueryEntitiesExecutor`, `QueryEntitiesByTaxonomyExecutor`, `ListEntitiesByTypeExecutor`,
`PropertyToolsExecutor` (history paths, through `NumericHistoryCacheWriter`),
`TaxonomyIdentifierResolver`, `AlertSummaryMultiRollup`, `CachedTabularToolsExecutor`,
`CachedTabularGroupMetricExecutor`, `CachedTabularDistributionExecutor`, `AnalyzeEntitySetExecutor`,
`TabularChartSourceResolver`, `TabularChartRoundHooks`, `PresentationArtifactRegistry`,
`ParlerTableFileExportHook`, `CachedPayloadInspect`, `ExtractNestedCachedResult` and the history
overlay. No executor keeps a second large-result map.

## 2. Configuration and lifecycle

- **Repository.** The AgentThing setting `artifactCacheFileRepository` names a dedicated
  FileRepository Thing. It is required: when it is empty or unavailable, user turns are rejected
  with the public code `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`. There is no in-memory fallback in
  production.
- **Restart.** A ThingWorx restart begins with an empty cache index. Pre-restart handles are not
  migrated or resolved; they return `CACHE_MISS` with re-execution guidance.
- **Conversation clear.** `ClearConversation` and a history cutoff call
  `TabularCacheHandleMirror.clearForConversationId`, which drops the TOKEN mirror and invalidates
  that conversation's cache scope, so its handles stop resolving.
- **Single-turn requests** (no conversation id) share one Core-minted scope per principal. Their
  TOKEN mirror is keyed per request id and is removed when the request context clears. Their cache
  entries are not invalidated at request end.
- **Expiry.** Tool paths publish entries without a logical expiry; an entry stays usable until its
  scope is invalidated or ThingWorx restarts. Removal of old payload files from the repository is an
  administrator operation (see the cache kernel document).

## 3. Binding rules

### BP1 — Public `cacheId` vocabulary

- The internal identity is `ArtifactRef.artifactId()` (lowercase UUID text). The public `cacheId`
  of a live entry is that same UUID string. `cache/ArtifactCacheIds` maps `ArtifactRef` ↔
  `cacheId` without encoding a path, principal, scope or `ArtifactKind`.
- `TOKEN` (`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`) is a live alias only. It resolves to a
  concrete UUID `cacheId` or to the uniform `CACHE_MISS` path.
- Malformed non-UUID strings, foreign-namespace UUIDs and disposed ids share the uniform miss path.
  A `cacheId` is never parsed as a path or namespace key.

### BP2 — Cache API use and the public fault map

Adapters call only `ArtifactCache` create / publish / open / invalidate / invalidateScope,
`ArtifactAccessContextFactory` (a Core-created context; no App or model principal or scope),
`ArtifactIoLimits` (storage bytes, items, time and a fixed internal buffer),
`ArtifactWriter` / `ArtifactReader` (opaque chunks), `ArtifactCreateRequest` with
`PasswordSchemaPreflight` (PASSWORD rejected before create), and `ArtifactKind` as producer format
metadata (TABULAR, JSON, TEXT). No tool or public surface receives `ArtifactPathLayout`, repository
paths, index maps, or `ArtifactRecord` path and namespace fields.

| Condition | Public result | Recovery posture |
| --- | --- | --- |
| Absent, malformed, guessed, foreign-namespace, post-disposal, pre-restart or string-only handle | `CACHE_MISS`, no `reason` | re-fetch or re-run the source; never restore the handle |
| Well-formed UUID whose descriptor is still registered under the current principal's namespace while the artifact open misses (`recovery/CacheMissClassifier.isLiveNotFoundProven`) | `CACHE_MISS` with `reason: "NOT_FOUND"` | re-fetch |
| Indexed entry whose payload is missing or corrupt (`PAYLOAD_FAULT`) | structured fault, never remapped to `CACHE_MISS` | fail closed; do not claim rows |
| `PASSWORD_REJECTED`, `IO_LIMIT_EXCEEDED`, `CREATE_COLLISION` | structured fault | fail closed; no partial publish |
| `REPOSITORY_UNAVAILABLE` | turn-level fault (BP3) | the turn ends with `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE` |

`CACHE_MISS` errors are built by `recovery/TypedToolErrorJson.cacheMiss` and carry category
`LIFECYCLE`, `retryable: true`, `retryBudgetKey: "source-query"` and one recovery action
`REEXECUTE_SOURCE`. Expiry and explicit invalidation are not reported as separate reasons.

### BP3 — Code landings and fault boundary

All paths are under `parler-agent/src/main/java/com/thingworx/things/agent/`.

| Concern | Landing |
| --- | --- |
| `SourceDescriptor` | `source/SourceDescriptor.java` (immutable), `source/SourceDescriptorSupport.java`, `source/ReadLimitFact.java` |
| `RunInvocationContext` / `ExecutionScope` | `execution/RunInvocationContext.java`, `execution/ExecutionScope.java` |
| `BudgetVector` | `execution/BudgetVector.java` |
| TOKEN mutation facade | `tools/TabularCacheHandleMirror.java` (`tools/CachedTabularLastCacheHandle` holds only the sentinel) |
| Public `cacheId` bridge | `cache/ArtifactCacheIds.java` |
| Tabular and JSON hubs | `cache/TabularArtifactHub.java`, `cache/JsonArtifactHub.java`, `cache/TabularInfotableCodec.java`, `cache/LargeJsonCaps.java`, `cache/NestedPathGrammar.java`; they stream through opaque `ArtifactReader` / `ArtifactWriter` only |
| Nested drill-in | `tools/CachedPayloadInspect.java`, `tools/ExtractNestedCachedResult.java` |
| History overlay, cohort merge | `HistorySeriesComposerSupport.java`, `tools/BuildHistoryOverlayChartExecutor.java`, `compaction/LlmToolResultCohortMerger.java` |
| Liveness probe | `cache/ArtifactCacheLiveness.java` |
| Turn fault policy | `cache/ArtifactCacheTurnFaults.java` |

**Production selector.** `TabularArtifactHub` uses either an explicit test override or
`ArtifactCacheCore.requireCache` for the bound AgentThing. It never creates a JVM memory cache,
never synthesizes a fallback principal, and never treats absent Agent context as a test signal.
Unit tests opt in through the test-only `ArtifactCacheTestFixtures`;
`ArtifactCacheProductionWiringGuardTest` guards production sources.

**Hub fault boundary.** `TabularArtifactHub` and `JsonArtifactHub` return `null` only for
`CACHE_MISS`, propagate every typed non-miss fault, and report unexpected payload read or decode
failures as `PAYLOAD_FAULT`. `ArtifactCacheException` is unchecked so the typed signal can cross
older helper APIs.

**Turn fault policy.** `ArtifactCacheTurnFaults` classifies a typed fault, directly or inside a
bounded cause chain, without parsing messages:

| Adapter class | Artifact-local fault | `REPOSITORY_UNAVAILABLE` |
| --- | --- | --- |
| Explicit cache tools, chart source, derived, time-quality-join and analyze paths | the specific structured tool code | rethrown to `AgentLoop` |
| Entity, list, taxonomy, property, alert and invoke producers; history-overlay cache registration | the existing bounded fallback or error | rethrown to `AgentLoop`, including inside broad catches |
| Compact Stream continuity annotation | the annotation for that row is omitted; rehydration continues and no miss is claimed | propagated to the entry path |
| Presentation registry, chart-rescue availability, table CSV full-cache lookup | the optional presentation or export decision degrades | propagated to the entry path |

`AgentLoop` handles the repository signal before its generic failure behavior. It pairs the
executing call with a bounded `REPOSITORY_UNAVAILABLE` tool result, appends skipped rows for later
emitted sibling calls, runs no later sibling or LLM round, and returns `AgentResult.Status.ERROR`
with the public code `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`, current counts and no successful
assistant metadata. Artifact-local typed faults reaching `AgentLoop` stay tool evidence and the loop
continues. Entry adapters consume the terminal result: synchronous `Chat` throws, `ChatAsync` fires
`AgentResponseEvent(status=ERROR, errorCode=…)`, and AlwaysOn and HITL send an error-only terminal on
the same request id. Already-started user and tool rows stay replayable; no successful final
assistant row is appended.

### BP4 — `RunInvocationContext`, `ExecutionScope`, `BudgetVector`

- `RunInvocationContext` carries an invocation id, an `ExecutionScope` kind (`CONVERSATION`,
  `REQUEST` or `HEADLESS`), a Core-generated opaque scope id, a `BudgetVector`, and an optional
  `retryBudgetKey`. `TabularArtifactHub.ensureRunInvocationBoundToConversation` binds it to the
  current conversation: the opaque scope id is derived deterministically from the conversation id
  (single-turn requests use kind `REQUEST`), and the principal always comes from ThingWorx, never
  from request JSON.
- `retryBudgetKey` is never read from tool JSON, App input, Playbook state or a durable run id. It
  is set only by the recovery layer from a typed error (`TypedToolErrorJson`), and the in-process
  `recovery/RetryLedger` is keyed by invocation id plus that key.
- A durable run id or Playbook run identity is never a cache namespace and never authorizes an
  open.
- `BudgetVector` defaults (`defaultsForTabular`): `maxDecodeChars` 2,000,000, `maxDecodeBytes`
  4,000,000, `maxReturnedRows` 5,000, `maxProjectionNodes` 50,000, `maxNestingDepth` 32,
  `maxWallTimeMillis` 60,000, storage 32,000,000 bytes and 200,000 items, internal buffer at most
  65,536 bytes. Only the storage slice is mapped into `ArtifactIoLimits`
  (`toArtifactIoLimits`); decoder, returned-object and projection budgets stay in this layer.
- Adapters propagate the context into `SourceDescriptor` lineage and into `ArtifactAccessContext`
  creation; App-supplied principal or scope overrides are rejected.

### BP5 — `SourceDescriptor`

One immutable runtime type records source facts; unknown facts stay absent. Its fields:

| Group | Fields | Used for |
| --- | --- | --- |
| Route | `sourceRouteId`, `thingOrServiceKind`, `sanitizedParamDigest` | audit and evidence labels; never authorize a cache open |
| Counts and coverage | `rowsExamined`, `rowsReturned`, `rowsAvailable`, `completenessStatus` (`COMPLETE`, `PARTIAL`, `UNKNOWN`), `completenessReasons[]` | EMPTY / INLINE / LARGE formatting, public `counts` and `completeness`, union pre-read budgets |
| Execution lineage | `principalBinding`, `executionScopeId`, `producerToolCallId`, `requestId`, `parentSourceCacheIds[]` | same-scope checks, derive lineage, audit |
| Semantic provenance | `propertyRoleRef`, `unitRef`, `grainRef`, `cadenceRef`, `semanticProfileId`, `semanticProfileVersion`, `semanticProfileDigest` | semantic-profile resolution; references only, never profile bodies |
| Column roles | `timeColumn`, `valueColumn` | writer-declared roles of a stored series |
| Subject identity | `subjectThingName`, `subjectPropertyName` | the canonical Thing and property a table was read from |

Rules:

- `forPrimaryStore` sets completeness `UNKNOWN` and leaves `rowsAvailable` unset unless proven.
- Formatters never infer completeness from `resultKind`, sample length or the presence of a handle.
- `forDerivedStore` / `composeDerived` build a new descriptor for a derived table: output row count,
  parent lineage, the parent's status and reasons, the parent's `rowsAvailable` only when it was
  proven; column roles and subject identity are dropped.
- `appendParent`, `withSemanticProvenance`, `withReadLimitFact`, `withColumnRoles` and
  `withSubjectIdentity` keep every other fact of the same artifact.
- Descriptor lineage neither authorizes access nor selects a cache namespace.

### BP6 — Public versus agent-only fields

| Field | Visibility |
| --- | --- |
| `resultKind` (`*_EMPTY`, `*_INLINE`, `*_LARGE`) | public |
| `cacheId`, `sourceCacheId` | public when LARGE or derived |
| `counts.rowsRead`, `counts.rowsOutput`, `counts.totalAvailable` | public; `totalAvailable` only when proven |
| `completeness.status`, `completeness.reasons` | public |
| `truncated`, `sampleOnly`, `rowsOmitted` | public when applicable |
| `sample` / `sampleRows` | public, bounded |
| `requestedBudget`, `effectiveBudget` | agent-only tool JSON |
| full `SourceDescriptor` | runtime only; envelopes expose the subset above |
| `ArtifactKind`, namespace keys, paths | never public |

The normative text is `CONTRACTS/TABULAR_INSIGHT.md` §5 (public `completeness` and `counts` on
`tabulate_cached_result` and `summarize_cached_result` success, built by
`SourceDescriptorSupport.putPublicEnvelopeFields`). `API_CONTRACT.md` and `UI_CLIENT_PROTOCOL.md`
change only when a UI or external consumer reads a field.

### BP7 — Large JSON and nested drill-in

Two resident, model-visible tools, both `playbookSafe=false`:

- **`inspect_cached_payload`** (`cacheId`, `mode`, `path`) works on a cached JSON or TEXT payload
  (a `LARGE_JSON` `cacheId` or an earlier extract). Mode `inspect` returns bounded structure hints
  (at most 32 keys, arrays sampled to 8, depth 4, 4,096 characters). Mode `extract` returns a
  path-selected subtree; a subtree over the cap is re-cached under a new handle rather than cut into
  apparently complete JSON.
- **`extract_nested`** (`sourceCacheId`, `cellPath`) promotes a nested INFOTABLE cell of a cached
  table into a new `cacheId` with lineage (`sourceCacheId` plus cell path), so the nested table has
  its own fetch, tabulate and chart path.

Path grammar (`NestedPathGrammar`): dotted keys and explicit `[index]` segments only, for example
`[0].children[1].detail`; at most 512 characters, 16 segments, index at most 100,000. `..`, `/`,
`\`, globs, recursive descent and expressions are rejected. Playbook row paths
(`PlaybookJsonRowPath`) do not use this grammar and do not traverse nested arrays.

Both tools count against the resident tool-schema budget (§8).

### BP8 — Aliases and compatibility

A field, mode or tool that stops being advertised in product release N keeps its executor and
replay alias throughout N; removal needs a later release, a clean usage sweep (schemas, executors,
Skills, Playbooks, replay fixtures, exported configuration, `dev_data/`) and passing fixtures. Cache
contents themselves have no migration window.

| Alias or form | Current state |
| --- | --- |
| `tabulate_cached_result` modes `sort_topn`, `group_count`, `group_aggregate` | Not advertised (B16); the executor still accepts them as aliases of `filter_sort_topn` and `group_metric` |
| `query_alert_history` `timePreset`, `oldestFirst` | Not advertised (B8); the executor and replay still accept them, because publishers remain in tests and replay fixtures |
| History `maxRows`, `maxPoints` | Accepted aliases of the published `maxItems`, in the order `maxItems`, `maxRows`, `maxPoints` (`HistoryRowLimitPrecedence`); requested and effective values are echoed |
| Chart `requestedTimeRange` aliases | `start` / `end` are typed; the documented builder aliases are still accepted (E12 / B3) |
| `TOKEN` | Live alias only |
| `get_utilization_overview` | An extended-tool manifest entry in the `dev_data/` utilization sample; it stays advertised |
| `query_stream_data` `oldestFirst` | A Stream-tool parameter, unrelated to the alert alias |

### BP9 — Replay and TOKEN mutation authority

- Compact Stream rehydrate (`CompactFetchStreamRehydrate`) never writes to the cache. Numeric
  `chartBlock` sidecars are used only to hydrate a previously shown history chart;
  `InvokeServiceExecutor.restoreInfotableInConversationCache` and `TabularArtifactHub.restore`
  always return false.
- `parlerRehydratedCacheLive` is set only when the `cacheId` already resolves live;
  `parlerRehydratedCacheHistorical` marks evidence whose handle is not live. A persisted handle is
  never proof that an entry is live.
- Every TOKEN mutation goes through `TabularCacheHandleMirror`: qualifying-result record,
  `summarize_cached_result` update, inline clear, miss prune, conversation clear (which also
  invalidates the conversation's cache scope). Hooks and `AgentToolContext` keep no independent
  mirror.

## 4. Decisions and invariants

1. **One physical cache.** All adapters call the `ArtifactCache`; no executor maintains a second
   large-result map.
2. **Opaque handles.** `cacheId` reveals neither file path nor owner. Every open rechecks the
   current principal and execution scope.
3. **No migration or transcript resurrection.** A restart invalidates every pre-restart handle.
   Replay keeps compact historical presentation, creates no handle from transcript evidence, and a
   later fetch of an old handle returns `CACHE_MISS`.
4. **Stable roles.** Fetch reads pages; tabulate transforms; summarize produces compact evidence;
   chart presents server-calculated rows. Inspection never becomes filesystem access.
5. **Explicit result semantics.** EMPTY, INLINE and LARGE describe packaging, not source
   completeness, and are the only packaging kinds. `sampleOnly`, `rowsOmitted`, counts, truncation
   and completeness are separate server-authored fields: EMPTY does not prove a complete source or
   the absence of a finding, INLINE may be partial, and LARGE may be complete.
6. **Bounded LLM lane.** A complete large payload never enters tool replay, task evidence,
   final-answer context or an error message.
7. **Derived lineage.** Any operation that changes rows, order, projection or nested scope returns a
   new entry and records its parent handles.
8. **Conservative table promotion.** JSON is promoted to tabular only when a validated shape proves
   rows and typed columns. Arrays or objects are not guessed into tables. (The JSON root-table
   promotion rules are in [tabular-reach](./tabular-reach.md) §7.2.)
9. **Nested values are counted.** Size accounting traverses nested INFOTABLE and JSON cells under
   depth, node, byte and time budgets. An oversize nested table is reached through `extract_nested`.
10. **TOKEN is an alias, not identity.** Resolution yields a concrete live `cacheId`; a miss prunes
    a stale mirror. `fetch_cached_result` requires an explicit handle.
11. **Schema changes ship together.** A published schema or prose change lands with its routing
    text, Skills, Playbooks, evals and measurements in one release, so the prompt cache is
    invalidated once.
12. **Compatibility is bounded.** See BP8.
13. **Drill-in fits the tool budget.** `inspect_cached_payload` and `extract_nested` are resident
    and counted in the tool-schema measurement (§8).
14. **No silent repair.** A model-visible or tool-schema field is either implemented, unpublished,
    or answered with a structured warning or error; it is never accepted and ignored.
15. **One source descriptor.** `SourceDescriptor` is the only runtime source-facts type. It records
    observed facts and semantic references; it does not infer business meaning or choose analysis
    methods. Derived artifacts compose it conservatively.
16. **One invocation context.** There is one `RunInvocationContext` / `ExecutionScope` and one shared
    `BudgetVector`. Adapters never accept an App-supplied principal, `SecurityContext`, scope or
    durable run id.
17. **PASSWORD never reaches a cache file.** A ThingWorx `BaseTypes.PASSWORD` value is rejected
    before any cache-file creation on every route that writes cache bytes: invoke, query and list
    LARGE stores; tabulate, `group_metric` and `analyze_entity_set` derived stores; `extract_nested`
    promotion; export hooks. Replay rehydrate writes nothing. There is no broader cache-specific
    secret classifier.
18. **Lifecycle reasons are typed only when proven.** `CACHE_MISS` carries `reason: "NOT_FOUND"`
    only under the condition of BP2; every other miss is uniform and never restores the handle.
19. **One TOKEN mutation authority.** See BP9.

## 5. Delivered behavior register

These ids are cited from code comments and tests.

| Id | Behavior |
| --- | --- |
| E4 | `tabulate_cached_result` publishes `percent_of_total`, `percent_of_group` and `groupBy`, with schema/executor parity tests. |
| E5 | Extended-tool required-parameter aspects are read by one versioned helper (`ExtendedToolRequiredAspects`). |
| E6 | Oversized `invoke_service` JSON or STRING results are classified `LARGE_JSON` with bounded structure hints and `inspect_cached_payload` drill-in. |
| E7 | Every cached-tabular producer and consumer, including TOKEN, derive, fetch and chart paths, uses the `ArtifactCache`. |
| E8 | One EMPTY / INLINE / LARGE and completeness vocabulary; established empty-success semantics are unchanged. |
| E9 / B1 | `maxItems` is published; documented aliases are accepted; requested and effective values are echoed. |
| E10 | Published schema and documentation follow the shared history row-limit precedence (`HistoryRowLimitPrecedence`). |
| E11 / B10 | Filter schemas state the supported ThingWorx Query dialect; alert admission is aligned with it. |
| E12 / B3 | Chart `requestedTimeRange.start` / `end` are typed; documented builder aliases are accepted. |
| E13 / B7 / S17 | Model-visible sources name only tools that are advertised or loadable in that context (for example, `get_property_values` no longer points at the executor-only `discover_properties`), checked by a lint against the admission snapshot. |
| E14 / B6 | Document-turn narrowing classifies `resolve_document_set` correctly, from one bucket table (`DocumentTurnToolNarrowing`). |
| E15 / B4 | `query_entities` has correct required fields and enforces the template/shape XOR in preflight. |
| E16 / B5 / S3 | Sibling tools share count, `hasMore`, paging clamp-and-echo (`TabularPagingEcho`) and name-column formatters; `list_entities_by_type` and `query_entities` both type `name` as `THINGNAME`. `fetch_cached_result` paging uses the same clamp-and-echo. |
| E17 | `spotlight_search.entityTypes` is not advertised; the description says type filters are unsupported. |
| E18 | Nested INFOTABLE values are counted and compacted, and promoted through `extract_nested` with lineage. |
| B2 | Both property-history branches use the same limit-alias precedence. |
| B8 | Alert `timePreset` / `oldestFirst` are not advertised; the executor still parses them (BP8). |
| B9 | `load_tool_schemas` tells the model the loaded tools are available "now / in the next step of this turn". |
| B11 | Validation vocabulary is aligned, and TOKEN failures carry targeted recovery. |
| B12 | `list_asset_types` is always bounded, also when `maxItems` is omitted. |
| B13 / S10 | Discovery siblings share envelope, key, case and result behavior through shared helpers. |
| B14 | `query_stream_data` uses the same scalar Thing preflight as the property and alert tools. |
| B15 | Taxonomy routing prose is aligned with the tools and checked for drift. |
| B16 | `sort_topn`, `group_count`, `group_aggregate` are not advertised; executor aliases remain (BP8). |
| B17 | The shared natural-time prose for datetime parameter pairs is emitted once on the tool description by `CustomToolHarvester`, not once per field. |
| B18 | `analyze_entity_set.operation` is a JSON-Schema enum with executor and `ENTITY_SET_TOOL` parity. |
| S1 | `list_entities_by_type` documents its collection-type repair trigger in the tool description. |
| S2 | The value-stream history path always queries oldest-first; `sampleRows` are a prefix of that order. |
| S4 | `build_history_overlay_chart` caches each fetched series (`seriesCaches[]`, §6.4) and reports non-window defects with their own codes instead of `HISTORY_OVERLAY_INVALID_TIME_WINDOW`. |
| S8 | `SKILL_NOT_FOUND` carries a hint when a Playbook shares the requested id. |
| S9 | Extended-tool and service-capability manifest entries are validated independently: one bad entry is skipped without removing the others; structural file defects still invalidate the file. |
| S11 | `resolve_asset_type` uses the shared validation vocabulary (a missing required argument is `INVALID_PARAMETERS`). |
| S16 | `LlmToolResultCohortMerger` merges live single-Thing `query_alert_summary` fan-out calls that use `thingNames: [canonical]`, and still accepts the scalar `thingName` form in replay. Multi-element `thingNames[]` and `ALERT_SUMMARY_MULTI` are not merged. |

`query_entities_by_taxonomy` stays a capability-gated tool alongside `query_entities`.

## 6. Envelope, descriptor and drill-in details

### 6.1 Result envelope

Converted producers supply server-authored fields that distinguish packaging from coverage:

```json
{
  "resultKind": "*_EMPTY|*_INLINE|*_LARGE",
  "cacheId": null,
  "sourceCacheId": null,
  "counts": {"rowsRead": 0, "rowsOutput": 0, "totalAvailable": null},
  "completeness": {"status": "COMPLETE|PARTIAL|UNKNOWN", "reasons": []},
  "truncated": false,
  "sample": []
}
```

A formatter omits fields that do not apply, and never infers completeness from `resultKind`,
sample length or the presence of a handle. Result kind is packaging only; `sampleOnly`,
`rowsOmitted`, counts, `truncated` and `completeness.status` / `reasons` describe coverage
independently. Registered `completeness.reasons` values are listed in
`CONTRACTS/TABULAR_INSIGHT.md` §5.2.

### 6.2 Source facts from producers

Core source adapters populate only facts they can prove. An App-supplied Service may declare paging
and truncation in its result (see [`CUSTOMIZED-TOOLS.md`](../CUSTOMIZED-TOOLS.md)), but runtime
output and live permissions stay authoritative; Parler never marks a source `COMPLETE` because a
service said so. The execution fields are copied from the current `RunInvocationContext`, never
from request JSON or a durable run id.

### 6.3 Size caps

| Cap | Unit | Meaning |
| --- | --- | --- |
| `LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP` = 65,536 | Java `String.length()` (UTF-16 code units) | an `invoke_service` result above it is `LARGE_JSON` |
| `LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP` = 500,000 | UTF-16 code units | `AgentMessageStreamAppender` content truncation; a persistence boundary, not a replay-admission threshold |
| UTF-8 bytes | bytes | reported separately by `LargeJsonCaps.classify`; never interchangeable with the UTF-16 caps |
| `ArtifactIoLimits` internal buffer | at most 65,536 bytes | mapped from the `BudgetVector` storage slice |

Above-cap packaging stays out of LLM replay. The row limit of 5,000 used by several read paths is
unrelated to these caps and is not a global switch.

### 6.4 History overlay series caches

`build_history_overlay_chart` keeps its chart success shape (`code=CHART_EMITTED`, as
`build_chart_from_tabular_result`) and has no `resultKind`. After the chart builds successfully it
adds `seriesCaches[]`, one entry per fetched series with at least one point:

| Field | Meaning |
| --- | --- |
| `label`, `thingName`, `propertyName` | the series identity from the resolved series input |
| `cacheId` | an ordinary conversation cache id of the full, pre-subsample `timestamp` / `value` table |
| `totalRows` | points cached for the series |
| `columns[]` | the written columns (`name`, `baseType`) |
| `timeColumn`, `valueColumn` | the writer-declared column roles, when present |
| `windowStart`, `windowEnd`, `resolvedTimeZone` | the resolved request window, when present (not a coverage claim) |
| `readLimitReached` | present when that series' read reached its limit ([tabular-reach](./tabular-reach.md) §7.10) |

Rules: zero-point series are omitted; a store failure skips that entry and does not fail the chart;
caches are stored only after the chart builds, so a post-fetch validation fault leaves no partial
cache; there is no TOKEN update and no public `completeness` / `counts` on the chart JSON. The ids
share the uniform `CACHE_MISS` path of BP2 through `fetch_cached_result` and
`tabulate_cached_result`; there are no overlay-specific miss codes. `HISTORY_OVERLAY_NO_DATA` stays
a hard tool error; partially empty series stay on `CHART_EMITTED` through `missingSeries` /
`missingPeriods`.

## 7. App developer contract

App developers may:

- return typed data through governed ThingWorx Services and DataShapes;
- declare output shape and paging or truncation fields in their results;
- provide Skills, Playbooks, extended-tool manifests and eval cases that use the documented public
  handles and envelopes.

App developers may not:

- read or write cache files, repository paths, indexes or Java cache objects;
- derive authorization from possession of a `cacheId`;
- supply a cache backend or a `SecurityContext` setter;
- supply or override `RunInvocationContext`, scope id, principal or service identity, or a durable
  run-to-cache mapping;
- mark a partial source complete, or bypass protected-column and egress checks;
- pass a ThingWorx `BaseTypes.PASSWORD` value into a cache-file write;
- override Core-authored counts, authorization, lineage or completeness;
- persist or share transient `cacheId` values as durable business objects.

## 8. Verification

Java changes run:

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

The tool-schema size of the advertised built-in tool list is measured with `ToolSchemaSizer`,
`BuiltInToolMergedDefinitionFootprintTest` and `M3Slice3R11TrueAggregateCloseTest`; the last asserts
that the aggregate for each provider format stays under 75,000 characters. Other focused tests
include `ArtifactCacheProductionWiringGuardTest`, `TabularArtifactHubTest` (no resurrection),
`CompactFetchStreamRehydrateTest`, `M4OverlaySeriesCachesMissAlignmentTest` (overlay ids hit on
fetch and tabulate, miss after scope invalidation, malformed or foreign ids miss, no `resultKind`
on the chart JSON), `M3Slice2Bp6PublicCountsTest`, `TabulateEnvelopeNoPrematurePublicCountsTest`,
`E8InternalCompletenessVocabularyTest`, `M3Slice3E16NameColumnTypeParityTest` and
`AlertHistorySortOrderTest`.

A representative manual check on a configured AgentThing: a multi-Thing
`build_history_overlay_chart` returns `CHART_EMITTED` with `seriesCaches[]`; `fetch_cached_result`
and `tabulate_cached_result` on one of those ids succeed with ordinary `CACHED_*` packaging; the
same id in a new or cleared conversation returns `CACHE_MISS`; and an AgentThing with an empty or
missing `artifactCacheFileRepository` ends a turn with the stable repository-unavailable terminal,
with no LLM-composed explanation and no memory fallback.

## 9. Rollback

- There is no dual-path switch: producers use the single cache path.
- Product rollback installs the prior compatible Extension and restarts ThingWorx. The restart
  clears transient cache entries, so no cache-data migration or dual read is needed.
- Documented replay aliases stay accepted for their compatibility window in the release that ships
  them.
- Oversized JSON and nested data always take the bounded path or return a structured error; there is
  no unbounded serialization path.
- ThingWorx authorization, PASSWORD rejection, egress controls and cache namespace checks stay
  mandatory.
