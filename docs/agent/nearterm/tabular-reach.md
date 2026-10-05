# Tabular reach — getting tool data into a table the agent can analyse and chart

Topic: `tabular-reach`

This document describes how Parler turns the results of platform reads and application services
into cached tables that `tabulate_cached_result`, `analyze_cached_result` and
`build_chart_from_tabular_result` can use, and how it reports when a read may not have returned the
whole source. Four behaviors belong to it, each identified by a stable label:

| Label | Behavior | Section |
| --- | --- | --- |
| TR-3 | `query_property_history` refuses a property that does not exist (`PROPERTY_NOT_FOUND`) instead of answering with the wide value-stream table | §7.1 |
| TR-1 | A complete JSON root table of more than 20 rows is promoted to a cached table with a `cacheId` | §7.2 |
| TR-2 | `tabulate_cached_result` mode `union_rows` appends 2 to 31 cached tables into one labelled table | §7.3 |
| TR-0 | A platform read whose returned table reaches its effective row limit says so on the tool result and on the cached table (`READ_LIMIT_REACHED`) | §7.10 |

Normative wire text lives in [`CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md)
(§5.1 `union_rows` counts, §5.2 registered `completeness.reasons`),
[`CONTRACTS/CHART_CONTRACT.md`](../../../CONTRACTS/CHART_CONTRACT.md),
[`docs/agent/cached_tabular_tools.md`](../cached_tabular_tools.md) and
[`docs/agent/CUSTOMIZED-TOOLS.md`](../CUSTOMIZED-TOOLS.md). This document explains the behavior
behind them and the rules the implementation keeps.

## 1. Purpose

Industrial users compare assets and time windows. Their data usually arrives as several small
results (one per day, one per device) or as JSON from an application service. The chart renderer
and the chart tool draw every supported kind correctly once they receive a table of the right
shape; the hard part is getting there. Parler must turn several results into one analysable table
without guessing, and must never present part of the data as the whole.

Each behavior here adds reach to an existing path without enlarging call chains, memory use or the
meaning of existing results. §5 states the rules every one of them obeys.

## 2. Terms

| Term | Meaning |
| --- | --- |
| Table | A cached INFOTABLE addressed by `cacheId`. |
| Promotion | Turning a validated JSON root table into a table. [tool-cache-integration](./tool-cache-integration.md) §4 decision 8: JSON is promoted to tabular only when a validated shape proves rows and typed columns; arrays or objects are not guessed into tables. |
| Complete cached artifact | Every row of this result is in the cache. This is **not** a proven-complete business source. |
| Source completeness | `COMPLETE`, `PARTIAL` or `UNKNOWN`: did the platform return the whole business result. `UNKNOWN` is never upgraded. |
| Output completeness | Whether a derived table is whole (for example, the append finished). A different fact from source completeness. |
| `ChartBlock.source.truncationApplied` | Top-N, `Other` or a point budget was applied to the chart. It does not mean the source was paged. |

## 3. Motivating cases

These are the questions the behaviors of this topic were built for. The ten-turn suite in
`test_scripts/context_compaction/chart_kinds/` asks them in their natural phrasing and is the
acceptance run for this topic.

| Id | Question | What used to go wrong | What happens now |
| --- | --- | --- | --- |
| B2 | Boxplot of `contactForce` across three devices, one of which (`MUC BenchScale 02`) has no such property | `query_property_history` answered with the whole value stream (222 rows of every logged property) as a plausible success | TR-3: `PROPERTY_NOT_FOUND` for that device; the other two histories are united with TR-2 and drawn as one grouped boxplot |
| L1 | Boxplot, stacked bar and 100% stacked bar of daily hours by state over twelve days | `get_utilization_records` returned 25 complete JSON rows (`hasMore: false`, `totalRows == returnedRows`). Above the 20-row JSON limit there was no handle, and the model saw a 20-row sample | TR-1 promotes the result to a cached table; the model still sees 20 sample rows plus the `cacheId` |
| L2 | Heatmap of state percentage by day and state | Twelve `get_utilization_state_summary` calls gave twelve cached tables and nothing combined them | TR-2 appends the twelve tables with a date label column |
| L3 | Events grouped by calendar day | No calendar-day column for event rows | `tabulate_cached_result` `mode=calendar_bucket` (§7.5) |
| L4 | Stacked bar by reason over seven days | 8 distinct reasons against a cap of 6 series: `TOO_MANY_SERIES` | Unchanged; see §7.6 |
| L8 | Any history or Stream read that stops at its row limit | The result and the cached table looked complete | TR-0 marks the result and the table (`READ_LIMIT_REACHED`) |

## 4. Background behavior

### 4.1 JSON results fall into three bands

`TabularChartRoundHooks.jsonRootTableQualification` (§7.2) and the `LARGE_JSON` classifier split JSON
tool results by row count and size:

| Band | What the platform does | What the model can do |
| --- | --- | --- |
| Up to 20 rows, no partial-page signal | Qualifies as a table (tier INLINE) and gets its own cache handle | chart, tabulate, reuse in later turns |
| More than 20 rows, whole envelope at most 65,536 characters | Promoted (tier PROMOTED) only with positive paging evidence and within the budgets of §7.2; otherwise no handle. Egress keeps 20 sample rows and marks `sampleOnly`, `rowsOmitted`, `truncated` with `_egress.reason = llm_evidence_cap` | with promotion: the whole table through its `cacheId`; without: 20 rows |
| Above `LargeJsonCaps.INVOKE_RESULT_CLASSIFY_CHAR_CAP` (65,536) | Stored as `LARGE_JSON`; `inspect_cached_payload` gives bounded reads | read, but not promote to a table |

**The 20 is a shared constant.** `InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD` is defined as
`ToolResultEgressGateway.llmArraySampleLimit()`, the LLM array sample limit. The same value splits
INFOTABLE INLINE from LARGE in `InvokeServiceExecutor`, sizes its sample rows, and is used by
`AlertSummaryMultiRollup` and `AnalyzeEntitySetExecutor`. It is not raised for promotion: above the
inline limit the model still sees 20 sample rows, now with a `cacheId`.

An INFOTABLE result above 20 rows gets `INFOTABLE_LARGE` with a `cacheId`. `CUSTOMIZED-TOOLS.md`
describes when a service should return JSON with scope, statistics or evidence gaps next to its
rows, and which paging fields it must declare for promotion.

### 4.2 Shape checks for small JSON tables are loose

The INLINE tier does not check that rows share columns. `ParlerTabularChartBuilder.infoTableFromJsonRows`
takes the column names from the first row, infers each column's kind from the values, and tolerates
missing cells. Small JSON tables rely on this. Because `infoTableFromJsonRows` walks the first row's
columns for every row, a sparse wide result expands (500 columns in the first row and 9,999 empty
rows is about 35,000 characters of JSON and 5,000,000 cell positions); the PROMOTED tier therefore
checks cells and encoded bytes before it builds anything (§7.2).

### 4.3 Order of registration and store

On the INLINE tier, `TabularChartRoundHooks.afterBuiltInToolResult` registers the qualifying rows as
the turn's inline `last_invoke` source first (`recordQualifyingTabular`), and only then does
`withJsonSourceHandle` build the table and call `storeInfotableInConversationCache`. If the store
throws, the original JSON is returned and the tool still succeeds; the inline registration stays,
and `TabularChartSourceResolver`'s inline branch can rebuild a table from the registered rows. The
PROMOTED tier uses the opposite order (§7.2).

### 4.4 The hook sees the full result

`TabularChartRoundHooks.augmentJsonSourceHandle` and `afterBuiltInToolResult` are called from
`AgentThing` and `AgentLoop` on the tool's result **before**
`ToolResultEgressGateway.compactForLlmAppend` reduces it for the model. A promoted table is built
from that full result, never from the 20-row egress sample.

### 4.5 Cached tables are source-UNKNOWN

Primary stores go through `SourceDescriptorSupport.forPrimaryStore`, which sets completeness
`UNKNOWN` and leaves `rowsAvailable` unset as unproven. This holds for promoted JSON tables too.
`hasExplicitPartialResultSignal` is a check for known counter-evidence only: a result with no paging
fields at all passes it.

### 4.6 Source completeness does not reach the chart

`BuildChartFromTabularResultExecutor` fills `ChartBlock.source` with `sourceResolved`,
`sourceCacheId`, columns and counts. It does not read a `SourceDescriptor` completeness.
`truncationApplied` is set from the builder's Top-N, `Other` and point-budget decisions, and
`parler-ui/lib/chartDataNotes.js` renders it as "Top-N / Other / sampling applied"; it has no notion
of a paged or limited source. See §7.6.

### 4.7 Budgets

Decision-mode scans allow 100,000 rows (`MAX_SCANNED_ROWS_DECISION`) and 2,000,000 cells
(`MAX_CELLS_FOR_TABULAR_TRANSFORM`). `execution/BudgetVector` defaults are 5,000 returned rows,
2,000,000 decode characters, 4,000,000 decode bytes, 50,000 projection nodes, 60 s wall time, and
storage of 200,000 items and 32,000,000 bytes. Promotion and `union_rows` reuse the cell budget and
the `BudgetVector` storage bytes and wall time; they do not define smaller limits.

"5000" appears as several unrelated constants: platform read caps
(`StreamValueStreamToolsExecutor.MAX_STREAM_ITEMS`, `HistorySeriesComposerSupport.MAX_HISTORY_ROWS`,
`QIT_MAX_ITEMS`), the chart builder's `MAX_CHART_ROWS`, and emitted points
(`MAX_TOTAL_EMITTED_POINTS`, `NUMERIC_CHARTBLOCK_PERSIST_MAX_POINTS`). There is no global switch
for them.

### 4.8 `query_property_history` routing

`PropertyToolsExecutor.queryPropertyHistoryAfterGate` reads the property definition (§7.1) and
takes the numeric branch for base type NUMBER, INTEGER or LONG and the value-stream branch
otherwise. The value-stream branch invokes the platform `QueryPropertyHistory`, which has no
property-name parameter: it returns every **logged** property the caller may read, merged on a
common time axis with previous-value fill. The named services (`QueryNamedPropertyHistory` and the
typed `Query*PropertyHistory`) look a property up by name and fail when it is absent. So an existing
non-numeric property legitimately succeeds through the wide table, and a property that exists but
is not logged is simply absent from it. Without the TR-3 guard, a property that does not exist
would also yield the wide table as a plausible success.

### 4.9 Platform read limits

| Read path | Effective limit | Signal on the tool result | What is cached |
| --- | --- | --- | --- |
| Numeric property history (`PropertyToolsExecutor.doQueryNumericPropertyHistory`) | `HistoryRowLimitPrecedence`: default **1000**; `maxItems`, then `maxRows`, then `maxPoints`; clamped to the hard cap `MAX_HISTORY_ROWS` 5000 | `pointsTruncated = rowCount >= maxRows` (unchanged); `readLimitReached` / `readLimitNote` (TR-0) | The numeric series, via `NumericHistoryCacheWriter` |
| Non-numeric property history (`doQueryValueStreamPropertyHistoryCompact`, platform `QueryPropertyHistory`) | same precedence, default **1000**, cap 5000 | `readLimitReached` / `readLimitNote` | The whole table, stored inside `InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact` |
| Stream rows (`StreamValueStreamToolsExecutor`, platform `QueryStreamData`) | default **500**, cap `MAX_STREAM_ITEMS` 5000 | `readLimitReached` / `readLimitNote` | Only a result above the 20-row inline threshold, inside `formatBuiltinInfotableResult`; `maxItems = 7` returning 7 rows is inline and has no `cacheId` |
| History overlay (`HistorySeriesComposerSupport.fetchNumericHistory`) | fixed request of `MAX_HISTORY_ROWS` 5000 per series | `maxItemsRequested` / `maxItemsEffective` always; per-series marking (§7.10) | Per series, after `extractPopNumericHistoryPoints` has dropped rows with an invalid value or timestamp |

The first three echo `maxItemsRequested` / `maxItemsEffective`. These limits are code constants,
not AgentThing settings.

### 4.10 The platform gives no total

`QueryPropertyHistory` and `QueryStreamData` return no total and no "more" flag. Among the platform
queries Parler uses, only the entity listing (`QueryImplementingThingsOptimizedWithTotalCount`, behind
`query_entities`) returns one, and that path evaluates completeness itself
(`BoundedQueryCompleteness`). `QueryPropertyHistory` is also a multi-property query: one stored
entry of an INFOTABLE property can expand to zero, one or several output rows, so when the platform
stops at its entry limit the returned table can have fewer rows than the limit, or more. Row count
against the limit is therefore a weak signal (§7.10).

## 5. Rules every behavior keeps

1. **Defaults do not move, and new limits are not set low.** Platform read limits, the LLM sample
   and character limits, browser point limits and the scan limits of existing modes keep their
   values; in particular `ARRAY_SAMPLE_LIMIT` / `LARGE_TABLE_ROW_THRESHOLD` stays 20. A new path
   reuses an existing generous budget (100,000 rows and 2,000,000 cells for decision scans, the
   `BudgetVector` storage defaults) rather than a smaller invented one. The protection is the order
   of checks in rule 2, not a small number.
2. **Limit first, allocate after.** Where a pre-check is possible, descriptors and row counts are
   compared before reading. Otherwise total rows, cells and **bytes** are bounded while reading,
   converting and accumulating. N maximal tables are never materialised before the sum is
   checked. One operation has one shared time and volume budget; it is not reset per input.
3. **Nothing automatic.** No automatic paging, no automatic multi-day or multi-device querying, no
   retry with a larger `maxItems`, no silent switch to another read path. A downstream refusal does
   not trigger a new query. The model makes N queries because the user asked for N days or N
   devices.
4. **No sentinel read.** A platform read is never enlarged (for example `maxItems + 1`) or repeated
   to find out whether more rows exist. A read that returns its limit is reported as "read limit
   reached, may be incomplete" and nothing more is claimed.
5. **Failure is local and leaves no residue.** A limit failure returns a clear error for that call.
   It writes no partial cache entry, registers no chart source, and does not modify or delete its
   inputs. Cache scope, permission and expiry rules are unchanged, and the whole-turn fault policy
   applies as for every tool.
6. **Old paths keep working.** A result that is not a table, exceeds the promotion budget, or is
   `LARGE_JSON` keeps its bounded JSON or `inspect_cached_payload` path and does not become a tool
   failure. Fields kept inline still pass the egress limits.
7. **Completeness is never inferred.** Returning all output rows does not prove the source was
   complete. `UNKNOWN` stays `UNKNOWN` through every derivation.

## 6. Code map

| Behavior | Main symbols (under `parler-agent/src/main/java/com/thingworx/things/agent/`) |
| --- | --- |
| TR-3 | `tools/PropertyToolsExecutor` (`queryPropertyHistoryAfterGate`, `resolvePropertyDefinitionOutcome`, `wrapHistoryFailure`) |
| TR-1 | `tools/TabularChartRoundHooks` (`jsonRootTableQualification`, `pagingFieldsWellTypedAndConsistent`, `hasPositivePagingEvidence`, `withinPromotionExpansionBudget`, `withinPromotionCharBand`, `buildAndStorePromotedTable`), `tools/TabularExpansionBudget` |
| TR-2 | `tools/CachedTabularToolsExecutor` (`tabulateUnionRows`, `derivedDescriptorForUnion`, `unionCompletenessReasons`), `tools/TabulateCachedResultToolSchema`, `tools/TabularChartRoundHooks.parseUnionRows` |
| TR-0 | `source/ReadLimitFact`, `source/SourceDescriptorSupport.withReadLimitFact`, `tools/InvokeServiceExecutor` (`putReadLimitEvidence`, `storePrimaryWithReadLimit`, the `ReadLimitFact` overloads of `formatBuiltinInfotableResult` and `formatPropertyHistoryValueStreamCompact`), `tools/PropertyToolsExecutor`, `tools/StreamValueStreamToolsExecutor`, `HistorySeriesComposerSupport`, `HistoryOverlayChartBuilder.ResolvedSeriesInput`, `tools/BuildHistoryOverlayChartExecutor`, `ToolResultEgressGateway` |

## 7. Behavior

Subsection numbers are stable identifiers; code comments cite §7.10.

### 7.1 TR-3 — `query_property_history` refuses a property that does not exist

After the existing Thing gate and the `propertyName` check, one definition read decides among the
outcomes below, before any platform service, cache write or chart registration. It runs on every
entry into the tool: the model-facing name and the executor-only aliases delegate to the same
method.

**The lookup.** `Thing.getInstancePropertyDefinitions()`, read by reflection, with **no catch**: a
reflection or platform exception propagates. The platform builds that collection from the Thing's
own definitions, its ThingTemplate chain, the ThingShapes it implements and its ThingPackage, so
inherited and shape properties are present. It is the same collection
`DiscoverThingMembersExecutor.listVisiblePropertyDefinitions` enumerates for `discover_thing_members`
facet `properties` before its visibility filter, so a name this guard rejects is a name discovery
would not list. The single-name `getInstancePropertyDefinition(name)` is not used, because it
returns `null` on an internal error and would turn "could not be determined" into "missing". Match
rule: exact, case-sensitive `getName()` equality.

Refusing an absent name changes no result that could have been right: `QueryPropertyHistory` takes
its property set from the same collection filtered to logged properties, and the named and typed
history services look the name up in the same tree.

| Definition read | Outcome | The tool |
| --- | --- | --- |
| Collection read throws (reflection or platform) | UNDETERMINED | The exception propagates to `executeQueryPropertyHistory` (`wrapHistoryFailure`), which returns `QUERY_PROPERTY_HISTORY_ERROR` with the exception message; `ArtifactCacheTurnFaults.rethrowRepositoryUnavailable` runs first. No history service is called. |
| Collection is `null` or its `values()` is not iterable | UNDETERMINED | `IllegalStateException("property definitions unavailable for <thing>")`, same wrapper, same code. No history service is called. |
| No definition named `propertyName` | ABSENT | `PROPERTY_NOT_FOUND` (below). No history service is called. |
| Definition found with base type NUMBER, INTEGER or LONG | FOUND numeric | `doQueryNumericPropertyHistory`, including its own `isNumericProperty` re-check. |
| Definition found with any other base type, or a base type that cannot be read | FOUND non-numeric | `nonNumericPropertyHistoryActionsError`, then `doQueryValueStreamPropertyHistoryCompact`. |

Visibility is not part of the guard. The collection is not visibility-filtered, so a defined
property the principal may not read is FOUND and follows the normal path, where the platform's own
read-authorization check inside the history service decides. Not-found is never reported for a
permission problem, and a permission problem is never reported as not-found. An existing property
that is not logged, an empty window and a missing read permission all behave exactly as they do
without the guard.

Precedence: `MISSING_PROPERTY_NAME` and the Thing gate (`IDENTITY_RESOLUTION_REQUIRED`) come before
the lookup. When the property does not exist, `PROPERTY_NOT_FOUND` precedes the `actions` and
time-specification errors of the value-stream branch.

**The not-found result.** The executor's `errorJson` envelope plus three fields:

```json
{
  "status": "error",
  "code": "PROPERTY_NOT_FOUND",
  "message": "Property \"contactForce\" is not defined on Thing \"MUC BenchScale 02\" (own, template, shape or package definitions). No history was queried. List the exact property names with discover_thing_members (facet \"properties\").",
  "thingName": "MUC BenchScale 02",
  "propertyName": "contactForce",
  "recoveryHint": { "tool": "discover_thing_members", "argument": "namePrefix" }
}
```

`thingName` is the gate's canonical name (`CONTRACTS/TAXONOMY_RESOLVER.md` §7.1 step 4).
`recoveryHint` has the two-field shape `DiscoverThingMembersExecutor.recoveryHint` emits. The code
is distinct from `PROPERTY_METADATA_UNRESOLVED`, which `get_property_values` returns for absent
**or** unreadable metadata. The tool definition and schema are unchanged by the guard.

**Test seam.** Offline tests cannot construct a platform `Thing`, so the tool is split into the
gate and a package-private `queryPropertyHistoryAfterGate(ToolCall, Thing, String)`, the failure
wrapper is the package-private `wrapHistoryFailure(Callable<String>)`, and a package-private static
`propertyDefinitionLookupOverrideForTests` (returning a `PropertyDefinitionOutcome`: `ABSENT`, or
`FOUND` with an optional `BaseTypes`) replaces the reflective read in tests. Production runs with
the seam unset. `PropertyToolsExecutorHistoryPropertyGuardTest` covers the outcome table and the
precedence rules; `isNumericProperty` keeps its other callers (`isChartableNumericProperty` for
`build_history_overlay_chart`).

### 7.2 TR-1 — a JSON root table above 20 rows becomes a cached table

For `resultKind: JSON` results whose decoded business object carries a root `rows` table,
`TabularChartRoundHooks.jsonRootTableQualification(JsonNode result)` returns `null` or a
`JsonRootTableQualification` with tier `INLINE` (at most `LARGE_TABLE_ROW_THRESHOLD` rows) or
`PROMOTED` (more rows). A promoted table is stored in the conversation tabular cache with a
`cacheId`, as `INFOTABLE_LARGE` is. Partial pages, `LARGE_JSON` results and results that fail
qualification keep their bounded JSON and do not become tool failures.

**Shared gates (both tiers).**

1. The business object decodes; business `status` is absent or `"success"`.
2. Root `rows` is a non-empty array and every element is an object.
3. `hasExplicitPartialResultSignal(business)` is false: no `hasMore: true`, no `truncated: true`, no
   numeric `offset > 0`, and no numeric `totalRows` different from `returnedRows`.

**Tier INLINE (at most 20 rows).** Returns the `rows` node; `afterBuiltInToolResult` registers the
rows, then `withJsonSourceHandle` stores (§4.3).

**Tier PROMOTED (more than 20 rows).** Evaluated only when `rows.size() > LARGE_TABLE_ROW_THRESHOLD`,
in this order, as a conjunction with the shared gates:

1. **Types** (`pagingFieldsWellTypedAndConsistent`). Every paging field that is present and not JSON
   `null` has its declared type: `hasMore` / `truncated` boolean; `totalRows` / `returnedRows` /
   `offset` numeric. One mistyped field refuses promotion even when another field is positive
   (`hasMore: "true", truncated: false` is not promoted).
2. **Consistency** (same method). `returnedRows`, when present, equals `rows.size()`; `totalRows`,
   when present, equals `rows.size()`. These are necessary conditions, never evidence: every page
   reports its own length, so `returnedRows` alone proves nothing.
3. **Positive evidence** (`hasPositivePagingEvidence`), at least one of:

| Evidence | Rule |
| --- | --- |
| `hasMore` present and boolean `false` | `readOptionalBool` is `Boolean.FALSE` |
| `truncated` present and boolean `false` | same |
| `totalRows` and `returnedRows` both numeric and equal | `decimalValue()` comparison |

Otherwise the result is not promoted.

**Expansion bound, before any table is built** (`withinPromotionExpansionBudget`). With
`columns` = the field count of `rows[0]`, promotion is refused when
`rows.size() * columns > MAX_CELLS_FOR_TABULAR_TRANSFORM` (2,000,000). Then a
`TabularExpansionBudget` is charged with an **upper bound** of what `TabularInfotableCodec` will
write: table and field headers, every column name on every row at its JSON-escaped size (a control
character is 6 bytes), `null` for a missing value, numbers as the writer prints them, and the
escaped text of every value. The codec builds a JSON tree, a string and a byte array before the
artifact writer checks its limit, so the writer's own refusal would come after the allocation and
cannot replace this bound. The bound is compared with the invocation's `BudgetVector` storage bytes
(default 32,000,000) and wall time (default 60 s), with time checked every 1,024 rows.
`TabularExpansionBudgetCodecBoundTest` holds bound ≥ real codec output for control characters,
quotes, solidus, multi-byte text, surrogates, nulls, extreme doubles, dates, nested tables and
sparse rows, and bound ≤ 1.5 × real output for an ordinary table. The 65,536-character cap does not
bound this by itself: a 62,049-character payload with one 32,000-character column name and 10,000
empty rows encodes to at least 320 MB. Refusal is silent (the qualification returns `null`, with a
debug log of row and column counts); the tool succeeds with the egress sample.

**Character band** (`withinPromotionCharBand`). Promotion applies only when the envelope is at most
`InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP` (65,536) characters. The hook enforces this
itself, on the normal and the approved path, because extended tools format through
`formatDirectServiceResultForLlm`, which has no `LARGE_JSON` classifier. A `cacheId` this hook added
earlier is not counted, so the second pass over an approved body measures what the first pass
measured. Above the band nothing is stored or registered.

**One deadline through build and store.** The `TabularExpansionBudget` charged during qualification
travels with the result (`JsonRootTableQualification.budget` → `QualifyingSnap.promotionBudget`).
`buildAndStorePromotedTable`, used by both paths, checks its deadline before
`infoTableFromJsonRows` and again before the table is copied, encoded and published. A passed
deadline abandons the promotion only: original JSON, prior chart target, no new cache entry. Time
the user spends on an approval does not count; the approved path starts its own operation.

**Order for PROMOTED: qualify → build and store → register on success.**

1. `ParlerTabularChartBuilder.infoTableFromJsonRows(rows)`.
2. `InvokeServiceExecutor.storeInfotableInConversationCache(table)` (`forPrimaryStore`,
   completeness `UNKNOWN`).
3. On store success only: `recordQualifyingTabular` with `hasCacheId=true`, the `cacheId` and **no
   inline rows**, so `last_invoke`, `cache_id` and the TOKEN mirror name one stored table; mirror the
   cache id; put `cacheId` on the outer envelope.
4. On store failure or build exception: return the original JSON body; no registration, no TOKEN
   mirror update, no partial cache entry. An earlier legal table keeps its `last_invoke` and TOKEN
   state.

**Approved-HITL replay** (`augmentJsonSourceHandle`). When the snap is PROMOTED and the envelope
already has `cacheId`, nothing happens. When it has no `cacheId`, the same qualify → build → store
→ augment sequence runs without touching round state. The later `afterBuiltInToolResult` pass sees
a body that already carries a handle; `AgentLoop` ignores that pass's return value, so it can only
register. It registers the carried id only while `ArtifactCacheLiveness.isIndexedForConversation`
holds (current conversation and principal, not expired, not invalidated, complete; no payload
read). A carried handle that is no longer live registers nothing, leaves the prior target, and is
never re-created from the transcript rows. A descriptor sidecar that outlives its artifact is not
liveness (`PromotedJsonReplayLivenessTest`). Only a body without a handle stores.

**What promotion does not claim.** The cached table's descriptor is `UNKNOWN`, as every primary
store is. Evidence that a page is not partial is not proof that the business window is complete. A
promoted result becomes the turn's `last_invoke` source and can take part in chart rescue exactly
as an INFOTABLE of that size does; `answerSetComplete`, rescue and the presentation budget are
unaffected.

**Examples.**

| Case | Result |
| --- | --- |
| 20 rows, no paging fields | INLINE tier |
| 21 rows, `hasMore:false`, `totalRows == returnedRows == 21` | PROMOTED; `cacheId` on the envelope; `last_invoke` registered; egress still 20 sample rows |
| 21 rows, no positive paging evidence; `hasMore:true` | Not promoted |
| 25 rows with only `returnedRows: 25`; `hasMore:false` with `returnedRows: 40`; `hasMore:false` with `totalRows: 100`; `hasMore:"true"` with `truncated:false`; any mistyped paging field | Not promoted |
| One 32,000-character column name on row 0, 10,000 rows | Not promoted on expansion bytes, before any table is built |
| Row 0 with 400 columns, 5,001 rows (2,000,400 cells) | Not promoted on cells |
| Complete 25-row envelope of exactly 65,536 characters; of 65,537 characters | Promoted at the cap; not promoted one character above it, on the normal and the approved path |
| Store failure on PROMOTED | Original JSON; nothing registered; prior legal table kept |

Tests: `TabularChartRoundHooksTest` (including `jsonRootRows_promotionTruthTable`),
`JsonChartSourceHandleTest`, `TabularChartJsonRootRowsPieTest` (25-row end-to-end chart),
`PromotedJsonReplayLivenessTest`, `TabularExpansionBudgetCodecBoundTest`.

### 7.3 TR-2 — `union_rows` mode on `tabulate_cached_result`

A resident `tabulate_cached_result` mode appends 2 to 31 conversation-cached InfoTables in the
order the model names them, adds one string label column, and returns the standard tabulate
envelope (`CACHED_TABULATE_INLINE` / `CACHED_TABULATE_LARGE` / `CACHED_TABULATE_EMPTY`). The
counts and completeness row is `CONTRACTS/TABULAR_INSIGHT.md` §5.1; parameters and error codes are
in `docs/agent/cached_tabular_tools.md` §1.1–§1.4.

**Parameters.**

| Field | Required | Rule |
| --- | --- | --- |
| `mode` | yes | `"union_rows"` |
| `sourceCacheIds` | yes | JSON array of 2–31 non-empty strings; each must resolve in the current conversation cache |
| `labelColumn` | yes | Non-empty string; must not already exist in any input schema |
| `labelValues` | no | When present: JSON array of strings, same length as `sourceCacheIds`; when absent: an empty-string label per input |
| `cacheId` | no | Not used by this mode (the executor dispatches on `mode` before the single-`cacheId` check) |

The model schema's `required` is `["mode"]` only; every other mode still answers
`MISSING_CACHE_ID` without a `cacheId`. Schema arrays declare `items`.

**Result fields.** The standard tabulate envelope plus `unionMeta.unionRowsNotDeduped: true`,
`unionMeta.inputCount`, `sourceCacheIds` (echo of the inputs), `sourceCacheId` (the first input, the
lineage anchor for the insight envelope), and the public `completeness` / `counts` from the derived
descriptor. The formatter flag `unionPackaging` is the only union-specific packaging: it stores a
non-empty INLINE union and puts its `cacheId` on the envelope, and it writes `returnedRows` /
`sampleOnly` / `rowsOmitted` on a LARGE union. The EMPTY, INLINE and LARGE envelopes of every other
mode, including the replay aliases `sort_topn`, `group_count` and `group_aggregate`, do not carry
these fields, so `TabularCompleteAnswerSetDetector` answers for them as it always has.

**Acceptance rules, in order.**

1. **Input count and argument shape.** Fewer than 2 → `MISSING_SOURCE_CACHE_IDS`; more than 31 →
   `TOO_MANY_UNION_INPUTS`; an entry of `sourceCacheIds` that is not a non-empty string →
   `INVALID_SOURCE_CACHE_IDS`; missing or empty `labelColumn` → `MISSING_LABEL_COLUMN`;
   `labelValues` present but not an array, of a different length, or with an entry that is not a
   string → `INVALID_LABEL_VALUES` (a number or `null` is refused, never turned into an empty
   label). The count is checked before any cache lookup.
2. **Pre-read budget.** For each input, `SourceDescriptor.rowsReturned()` is read **before** the
   InfoTable is loaded; a missing count → `SOURCE_ROW_COUNT_UNKNOWN`; a sum above
   `MAX_SCANNED_ROWS_DECISION` (100,000) → `SOURCE_TOO_LARGE`. A cache miss returns the standard
   `CACHE_MISS` error and prunes the TOKEN mirror if it pointed at that id.
3. **Schema match.** Column names and order are identical across inputs; each column's `BaseTypes`
   is equal; for an `INFOTABLE` column the nested local shape (names, order, base types,
   recursively) is equal too. The output column keeps the first input's local shape, which lets
   the cache prove the nested table has no PASSWORD column. Mismatch → `UNION_COLUMN_MISMATCH`
   naming the offending `cacheId`; label column already present → `LABEL_COLUMN_COLLISION`.
4. **Cells.** Projected cells `(columns + 1) × rows`, summed across inputs, must not exceed
   `MAX_CELLS_FOR_TABULAR_TRANSFORM` (2,000,000) → `TABLE_TOO_LARGE_FOR_TRANSFORM`.
5. **Bytes and time.** One `TabularExpansionBudget` per call, never reset per input, against the
   invocation's `BudgetVector` storage bytes (default 32,000,000) and wall time (default 60 s).
   Each input is charged after it is read and before any of its rows is appended, with the same
   codec upper bound as §7.2 (escaped column names, the escaped label name and value on every row,
   `null` for a missing value, the escaped text of every cell, nested tables included). The deadline
   is checked before every input read, at the start and end of each charge, every 1,024 rows while
   measuring and appending, and once more before the appended table is copied, encoded and
   published. Over bytes → `TABLE_TOO_LARGE_FOR_TRANSFORM`; over time →
   `UNION_TIME_BUDGET_EXCEEDED`. Nothing is cached or registered.
6. **Rows kept as they are.** Rows are appended in order: no coercion, no null fill, no
   de-duplication scan.

**Derived descriptor.** Status is the worst of the inputs: any `PARTIAL` → `PARTIAL`, else any
`UNKNOWN` → `UNKNOWN`, else `COMPLETE`; it is never upgraded. Every input `cacheId` is listed in
`parentSourceCacheIds`. `completeness.reasons` carries the reasons of **all** inputs, in input
order without duplicates (`unionCompletenessReasons`). `rowsAvailable` is not inherited and
`counts.totalAvailable` is absent: a union never claims that its inputs cover a business window or
come from one consistent snapshot. Since primary stores are `UNKNOWN`, unions of them are
`UNKNOWN`; this follows from the merge rule, not from an override.

**Registration** (`TabularChartRoundHooks.parseUnionRows`). The hook recognises a union by
`unionMeta`. Every non-empty union, INLINE or LARGE, carries its own derived `cacheId` for the whole
appended table and registers as the chartable `last_invoke` target and as a presentation artifact,
so `box_summary` or `group_metric` can use even a small union. That is separate from the sample
shown to the model (`sampleOnly` / `rowsOmitted` stay true on LARGE, and the loop's complete-answer
detector stays `false`) and from source completeness. A union `CACHED_TABULATE_EMPTY` clears the
chartable target, so `last_invoke` cannot chart an older unrelated table.

Tests: `CachedTabularUnionRowsExecutorTest`, `TabularChartUnionRowsBarTest` (real caches → union
→ hook → `build_chart_from_tabular_result`), `TabularUnionRowsLifecycleTest`,
`TabulateB16UnpublishModesTest`.

### 7.4 How the pieces combine

| Question | Path |
| --- | --- |
| 25-row records result: boxplot, stacked bar | TR-1 caches it as a table: it is under 65,536 characters and carries `hasMore: false` with equal `totalRows` and `returnedRows`. |
| Day-by-state heatmap and stacked bar | Twelve per-day summaries, each labelled with its date, united by TR-2 into one day-by-state table. |
| Boxplot across three devices | Three history queries, each labelled with its device, united by TR-2, then `box_summary` grouped by the label. A device without the property answers `PROPERTY_NOT_FOUND` (TR-3). |

The model pages explicitly when it needs more, and unites the pages with TR-2. Nothing is fetched
automatically (§5 rule 3).

### 7.5 Calendar-day column for event rows

A calendar-day column for event rows is provided by `tabulate_cached_result` `mode=calendar_bucket`
([`CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md) §6.6; design in
[computing-enhancement](./computing-enhancement.md) CF-03). Label the event's start column with
`calendarBucket=day`, then group by `bucketLabel`. The column means "grouped by start day" and
nothing more: an event that crosses midnight counts wholly on its start day. It is not duration
allocated per day and not daily utilization.

### 7.6 Limits of the current behavior

- **Source completeness is not shown on charts.** `ChartBlock.source`, the chart data notes,
  history replay and print carry no source completeness and no `READ_LIMIT_REACHED`. A chart built
  from a limited read, or from a union of such reads, looks whole; only the model's words (prompted
  by `readLimitNote`) say otherwise. This applies to the charts emitted straight from a read (the
  numeric history line and the history overlay) as well as to charts built from cached tables.
- **Partial JSON pages are not promoted.** A page with a partial signal keeps its bounded JSON and
  has no handle.
- **Grouped and stacked bars accept at most 6 series** (`ParlerTabularChartBuilder.BAR_MAX_SERIES`);
  more series are rejected with `TOO_MANY_SERIES`, and the user has to narrow the question.
- **Platform read limits are code constants** (§4.9), not AgentThing settings.
- **`query_property_history` on an existing non-numeric property** still returns the wide
  `QueryPropertyHistory` table of all logged properties (§4.8).

### 7.10 TR-0 — a read that reaches its limit says so

**Problem.** Four read paths stop at a row limit (§4.9), and without a mark what they return and
cache is indistinguishable from a complete read. The chart, and later a computed total, then looks
whole.

**The signal.** When the table the platform returned has **at least as many rows as the effective
limit of that read** (`rowCount >= effectiveLimit`, the comparison the numeric path uses for
`pointsTruncated`), the read carries the reason `READ_LIMIT_REACHED`. One value class states the
fact: `source/ReadLimitFact.observe(returnedTable, effectiveLimit)`; a non-positive limit means
unknown and never fires. No extra row is requested, no query is repeated, and no limit or default
changes.

**What the signal is and is not.** It is a conservative observation, not a test of completeness:

- It can fire on a complete read whose data has exactly that many rows. The wording is therefore
  "the read returned as many rows as its limit; the result may be incomplete", never
  "incomplete" or "truncated".
- It can stay silent on a cut read. Where the platform merges or expands entries (§4.10), a read
  that stopped at its entry limit may return fewer rows than the limit. **No reason is never
  evidence of completeness.**
- `completeness.status` does not move. The table stays `UNKNOWN`; the reason is added, not a
  status. `PARTIAL` is reserved for a producer that states it. Nothing is upgraded to `COMPLETE`.
- Narrowing the window is something the user may choose to do next. It is not an automatic
  re-read, and it does not prove completeness either.

**Tool-result fields.** Written only when the limit was reached, by
`InvokeServiceExecutor.putReadLimitEvidence`:

```json
{ "readLimitReached": true,
  "readLimitNote": "The platform read returned 1000 rows, reaching or exceeding the limit of 1000 rows for this read. Rows beyond the limit may exist and are not included, so this result may be incomplete; this is not proof that it is. Nothing was re-queried. Say so when you report totals, trends or charts from it, or offer a narrower time window." }
```

`build_history_overlay_chart` writes `maxItemsRequested` / `maxItemsEffective` (5000) on every
result. When a series' own read reached the limit it also writes `readLimitReached`,
`readLimitReachedSeries` (the labels of those series) and `ReadLimitFact.seriesNote`, a fixed
sentence that points at that field and claims no row count (each listed series has its own, and the
platform may return more rows than asked); the affected `seriesCaches[]` entries carry
`readLimitReached: true`. The root hint does not depend on a series having been cached.

**Egress.** `ToolResultEgressGateway` keeps these fields through last-resort compaction, for
example when a wide `columns` schema alone fills the budget. Eligibility depends on the field name
**and** the value shape, because the shape is what bounds the retention:

| Field | Kept only when the value is | Bound |
| --- | --- | --- |
| `readLimitReached` | a boolean | one token |
| `readLimitNote` | text | the 512-character last-resort text cap; both notes are under 450 characters, so they arrive whole |
| `readLimitReachedSeries` | an array of at most `HISTORY_OVERLAY_MAX_SERIES` (6) elements, all text | six labels, each under the same text cap; such a list is not sampled |

Any other value under these names (object, nested array, a list with a non-text element, more
than six entries, a scalar of another type) is ordinary data and is sampled, budgeted and omitted
exactly as the same value under any other name. The names are not in `PRIORITY_FIELDS`. One
predicate decides both the budget exemption and the sampling exemption; it is evaluated once, on
the value as the tool wrote it, and a sampled or excerpted value is never re-judged. No character
budget changes, and a result without the fields compacts exactly as before.

**The fact is stated by the reader, before anything is filtered.**

| Path | Where the fact is stated | How it reaches the store |
| --- | --- | --- |
| Numeric history | `doQueryNumericPropertyHistory`, on the returned table and `maxRows`, next to `pointsTruncated` | decorator passed to `NumericHistoryCacheWriter.store`, after `attachPropertyProvenance`; `compactNumericHistoryResult` copies the two fields |
| Non-numeric history | `doQueryValueStreamPropertyHistoryCompact`, on the returned table and `maxRows` | overload `formatPropertyHistoryValueStreamCompact(…, ReadLimitFact)` → `storePrimaryWithReadLimit`, the same call that caches the table |
| Stream rows | `query_stream_data`, on the returned table and the clamped `maxItems` | overload `formatBuiltinInfotableResult(…, ReadLimitFact)` → `cacheLargeTable(…, fact)` → `storePrimaryWithReadLimit`; an inline result gets the fields and no cache entry |
| History overlay | `HistorySeriesComposerSupport.fetchNumericHistory`, per series, on the platform's table **before** `extractPopNumericHistoryPoints` filters it | `FetchOutcome.readLimit` → `ResolvedSeriesInput.readLimit` → that series' `storeNumericHistoryPointsInConversationCache(…, fact)` |

Stream and non-numeric history cache inside their formatter before the caller's `extras` are merged,
so the fact is passed to the store call itself; the tool text and the cached descriptor always
agree. The overlay compares per series and never against the sum over series, the emitted point
count or a sampled count: 5000 rows read with one invalid value are 4999 cached points, still
marked. Every old overload delegates with `ReadLimitFact.none()`, so callers that state no fact
(`invoke_service`, alert history, every other built-in) produce exactly what they produced before.
A result that is not otherwise cached (an inline Stream result under the 20-row threshold) is not
cached for the sake of the mark.

**Descriptor.** `SourceDescriptorSupport.withReadLimitFact(base, fact)` is a same-artifact rebuild
through `copyAll`: it adds `READ_LIMIT_REACHED` to `completenessReasons` once and keeps status
(`UNKNOWN`), counts, column roles, subject identity, semantic facts and lineage; a fact that was not
reached returns `base` itself. `forPrimaryStore` never infers the fact from a table's size, so a raw
JSON cache, a promoted JSON table or an ordinary `invoke_service` INFOTABLE that happens to have
5000 rows is not marked.

**Derivations keep it.** Single-parent derivations copy the parent's reasons
(`SourceDescriptorSupport.forDerivedStore`); `union_rows` carries the reasons of all inputs
(§7.3). A derived or united table is otherwise described as a new table (row counts recomputed, new
lineage, worst-of status, no inherited column roles or single subject identity). `bin_numeric` and
`box_summary` over a marked table carry the reason in `completeness.reasons`.

**Cache lifetime is unchanged.** Whether a handle is usable is decided only by the existing rules
(conversation and principal scope, invalidation, artifact completeness; `ArtifactCacheLiveness`).
A descriptor, or the words of a tool result kept in history, may outlive the artifact and is not
evidence that it is usable. After invalidation or a restart the handle is not re-registered, and
neither the artifact nor its descriptor is rebuilt from the transcript.

**Contract.** `completeness.reasons` is a public field; `READ_LIMIT_REACHED` is registered in
`CONTRACTS/TABULAR_INSIGHT.md` §5.2. There is no `ChartBlock` field for it (§7.6).

Tests: `ReadLimitMarkingTest` covers the threshold (999/1000 and 6/7 not reached; 1000/1000,
1001/1000, 7/7, 500/500, 5000/5000 and 5002/5000 reached; limit 0 or −1 never), the descriptor
decoration, the four formatters, the overlay's per-series marking, union in both orders,
`bin_numeric` and `box_summary`, unmarked 5000-row stores, retention through last-resort compaction
of an 80-column table, and name-versus-shape differentials for the egress exemption.
`HistoryRowLimitPrecedenceTest` pins the alias precedence and clamping of the history limit. Each
reader passing its own table and effective limit sits behind a live `Thing` and is covered by code
review, not by a unit test.

## 8. Verification

Java changes run `./gradlew test assemble --no-daemon -PuseLocalTwxLib=true` in `parler-agent/`.
The focused test classes are named in each subsection of §7.

Any field added to a tool result for the model to read must be tested through the real
`ToolResultEgressGateway.compactForLlmAppend` on an input proven to reach last-resort compaction (an
`omitted: true` marker in `_egress.reducedFields`). A formatter already cuts rows to a 20-row sample
before the gateway, so a table with many rows and few columns proves nothing; a wide schema does. If
a field is exempted from a size rule, the exemption is tested by value shape as well as by name,
with the budget already spent as well as with room to spare.

`test_scripts/context_compaction/chart_kinds/` (suite `chart-kinds-ten-turns-v2`) is the regression
and acceptance run for this topic: it asks the §3 questions in their natural phrasing (three devices
in turn 3, the full twelve-day records window in turns 4 to 6, one summary per day in turn 10). Run
it in a fresh conversation, with the widget disconnected from it.

## 9. What this topic deliberately does not do

- No automatic paging, no automatic multi-day or multi-device querying, no retry with a larger
  limit, no sentinel read (§5).
- No query language or free-form JSON path extraction beyond `inspect_cached_payload`, and no schema
  inference for arbitrary JSON.
- No promotion of `LARGE_JSON` results to tables.
- No global limit switch.
- No change to `answerSetComplete`, chart rescue or the presentation budget.
