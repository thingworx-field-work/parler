# Cached tabular tools (`tabulate_cached_result`, `summarize_cached_result`)

**Scope:** ThingWorx Java agent built-ins over the **same conversation-scoped cache** as `invoke_service` / `query_entities` / `query_entities_by_taxonomy` / `list_entities_by_type` LARGE paths and `fetch_cached_result` (see `InvokeServiceExecutor.storeInfotableInConversationCache` / `lookupCachedInfotable`).
**Backend prerequisite:** production access uses only the dedicated
`AgentSettings.artifactCacheFileRepository`; there is no automatic memory fallback. Missing live
handles are `CACHE_MISS`, known unreadable payloads are `PAYLOAD_FAULT`, and repository-wide
failure is fatal `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE` with no later LLM round or successful
final-assistant row.
**Normative bundle (agent JSON):** [`CONTRACTS/TABULAR_INSIGHT.md`](../../CONTRACTS/TABULAR_INSIGHT.md) + [`CONTRACTS/CONTRACT_VERSION.md`](../../CONTRACTS/CONTRACT_VERSION.md) — the **shared** success root fields, error shell, `insightEnvelope` and LARGE alignment rules. **Parameter tables, the full `resultKind` set, per-tool success fields and error-code tables** are defined by **this document**.
**Implementation:** `parler-agent/.../CachedTabularToolsExecutor.java` (+ `CachedTabularDecisionPredicate.java`, `CachedTabularNumericStringCoercion.java`, `CachedTabularGroupMetricExecutor.java`, `CachedTabulateLegacyKeyRejector.java`, `predicate/ParlerQueryFilterParser.java`, `CachedTabularFieldsProjection.java`, **`TabulateCachedResultToolSchema.java`** for the **`tabulate_cached_result`** OpenAI/Azure parameters map).
**LLM hosts (Azure / OpenAI):** strict function-calling validation requires every JSON Schema **`type: "array"`** to include **`items`**; without it the provider rejects the whole tools-bearing request with **`400 invalid_function_parameters`**. **`measures`** / **`derived`** / **`sort`** therefore always declare **`items`**. Regression: **`LlmJsonSchemaCompat`** + **`BuiltInToolsLlmSchemaRegistryTest`**.
**Predicate / decision semantics (filter modes, caps, errors):** [`cached-table-decision-tools.md`](./cached-table-decision-tools.md).
**Golden / negative catalog:** [`cached_tabular_golden.md`](./cached_tabular_golden.md) + [`golden_cached_tabular/`](./golden_cached_tabular/).
**`insightEnvelope`:** [`tabular_insight_envelope.md`](./tabular_insight_envelope.md).
**Last-cache sentinel (`TOKEN`):** [`p2_last_tabular_cache.md`](./p2_last_tabular_cache.md).
**Multi-turn `cacheId` platform check:** [`cached_tabular_g4_harness.md`](./cached_tabular_g4_harness.md).

**Query-spec test matrix:** offline acceptance for `docs/agent/query-spec.md` §§3–12 lives in `parler-agent/src/test/java/com/thingworx/things/agent/tools/predicate/*MatrixTest.java` and companion `.../tools/*MatrixTest.java` classes (see each file’s class-level Javadoc). Golden legacy rejection asserts stable substrings from `PredicateErrorMessages.rejectedLegacyPredicateShape()`; PASSWORD executor tests assert `PROTECTED_TABULAR_COLUMN_BLOCKED` and that secret `value` payloads never appear in `message`.

These tools always read the **entire** cached `InfoTable` for `cacheId`. `fetch_cached_result` is **read-only paging** only; it does not define a sub-table and must not be assumed to scope transforms.

---

## 1. `tabulate_cached_result`

### 1.1 Parameters

| Field | Required | Notes |
|-------|----------|--------|
| `cacheId` | yes, except `union_rows` | The model schema's `required` list is `["mode"]` only; the executor answers `MISSING_CACHE_ID` for every mode but `union_rows`. Omitted for **`union_rows`** (which reads **`sourceCacheIds`** and is dispatched before the `cacheId` check). Source table in the current conversation cache. May be the literal sentinel **`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`** (trimmed, case-sensitive). Resolution: **per-turn** last qualifying `cacheId` (`TabularChartRoundState`, same as `build_chart_from_tabular_result` `last_invoke` cache arm), then **conversation-scoped** mirror (`AgentToolContext` — persistent **`conversation_id`**, or **`__single_turn__` + `request_id`** composite when bound; cleared on single-turn **`clear()`**, removed for a thread when **`ClearConversation`** runs). **Not** valid for `fetch_cached_result`. **Not** guaranteed across agent process restarts. |
| `mode` | yes | `sort_topn` \| `group_count` \| `group_aggregate` \| `filter_count` \| `filter_rows` \| `filter_sort_topn` \| `group_metric` \| **`bin_numeric`** \| **`box_summary`** \| **`union_rows`** \| **`exact_join`** \| **`quality`** \| **`resample`** \| **`rolling`** \| **`rate_of_change`** \| **`period_compare`** (U4; each advertised only while its admission flag is enabled) \| **`counter_delta`** \| **`rolling_stats`** \| **`time_weighted`** \| **`calendar_bucket`** (computing CF-05 / CF-52 / CF-01 / CF-03; same admission rule). |
| `sourceCacheIds` | for `union_rows` | Array of **2–31** conversation cache ids, appended in the given order. Explicit ids only (the last-tabular sentinel is not resolved here). |
| `labelColumn` | for `union_rows` | Name of the one STRING column added after the input columns; must not exist in any input. |
| `labelValues` | optional for `union_rows` | Array with **one entry per `sourceCacheIds` entry**; entry *i* is written on every row of input *i*. Every entry must be a string (a number or `null` is `INVALID_LABEL_VALUES`, never an empty label). Omitted → the label column is empty. |
| `rightCacheId` | for `exact_join` | Explicit right-table cache id (not the last-tabular sentinel). |
| `joinType` | optional for `exact_join` | `INNER` (default) or `LEFT`; other present values fail fast. |
| `timeColumn` / `windowStart` / `windowEnd` | for `quality` / `resample` / `rolling` / `rate_of_change` / `counter_delta` / `rolling_stats` / `time_weighted` | Temporal column + half-open ISO-8601 window. |
| `valueColumn` | optional for series U4 modes; **required for `counter_delta`, `rolling_stats` and `time_weighted`** | Numeric value column. |
| `entityColumn` | optional for `counter_delta` / `rolling_stats` | Column that separates series (at most 50 distinct values). |
| `statistic` | required for `rolling_stats` | `mean` \| `sum` \| `min` \| `max` \| `stddev` \| `count_values` \| `count_records`. One per call. |
| `counterModulus` / `maxRatePerSecond` / `maxGapSeconds` / `resetBaseline` | optional for `counter_delta` | Caller-supplied counter rules, no defaults: modulus and baseline in reading units, rate in reading units per second, gap in seconds; each at most 2^53, checked on the JSON number as written. Give a rule only when the user or App states it; omit it when unknown. `counterModulus` requires `maxRatePerSecond` and excludes `resetBaseline`. |
| `integrationMethod` / `maxGapSeconds` / `timeUnit` | required for `time_weighted` | `step_hold` \| `trapezoid`; longest usable interval between two readings in whole seconds (at most 2^53); `seconds` \| `minutes` \| `hours`, the time factor of the integral. No defaults: each decides the number. `entityColumn` is not accepted by this mode. |
| `timeZone` / `calendarBucket` | required for `calendar_bucket` | IANA zone id such as `Europe/Berlin`, or `UTC`; no default and no fixed offsets. `day` \| `hour`. This mode also needs `timeColumn` and accepts no `valueColumn`, `entityColumn`, `windowStart` or `windowEnd`. |
| `aggregation` | optional for `resample` / `period_compare` | `COUNT`\|`SUM`\|`MEAN`\|`MIN`\|`MAX`\|`FIRST`\|`LAST` (default `MEAN`). |
| `observationWindow` | optional for `rolling` | Positive integer observation count (demo default applies when omitted). |
| `originalWindowStart` / `originalWindowEnd` / `currentWindowStart` / `currentWindowEnd` | for `period_compare` | Dual half-open ISO-8601 windows (assessment-only). |

**Mode-specific fields** (canonical JSON per [`query-spec.md`](./query-spec.md); legacy `where` / `sort` / `limit` / `sortBy` / `direction` / measure `where` are **rejected**.)

| `mode` | Fields | Behavior |
|--------|--------|----------|
| `sort_topn` | **`sorts`** (1–3 keys: `fieldName`, `isAscending`, optional `isCaseSensitive`), optional **`maxItems`**, **`offset`**, **`fields`** | Sort **all** rows by `sorts` keys. **Tie-break:** ascending **original row index**. |
| `group_count` | `groupBy`, optional **`maxItems`** | Count rows per `groupBy` key. Output sorted by count **desc**, then key **asc**. |
| `group_aggregate` | `groupBy`, `fn`, optional `aggregateColumn`, optional **`maxItems`** | Same aggregate semantics as before; output row cap uses **`maxItems`**. |
| `filter_count` | **`filters`** (required), optional `groupBy` (string) | Counts rows matching **filters** over the **full** source table. Same `resultKind` patterns as before (`CACHED_FILTER_COUNT` / `CACHED_FILTER_GROUP_COUNT`). |
| `filter_rows` | **`filters`** (required), optional **`sorts`**, **`maxItems`**, **`offset`**, **`fields`** | Matching rows, optional re-sort, slice, then optional column projection. |
| `filter_sort_topn` | **`sorts`** (required), optional **`filters`**, **`maxItems`**, **`offset`**, **`fields`** | Filter (optional), stable-sort, slice, optional projection. |
| `bin_numeric` | **`column`** (required, numeric or uniformly numeric-parseable STRING), exactly one of **`binEdges`** (2–51 strictly increasing finite numbers) or **`binCount`** (1–50); **`rangeMin`** / **`rangeMax`** only with `binCount`; optional **`filters`**; no `groupBy`, `fields`, `sorts` | Method `explicit_edges_v1` or `equal_width_v1`. Bins `[edge[i], edge[i+1])`, last bin includes its right endpoint; equal width uses `rangeMin/rangeMax` or the valid values' min/max, a constant column gives one bin `[v − 0.5, v + 0.5]`. `validCount` = finite numeric cells after filters; null/non-numeric/non-finite → `excludedCount`; outside the edges → `belowRangeCount` / `aboveRangeCount` (not in any bin). `density = count / (Σcount × width)`. Output columns (scalars repeated per row): `binIndex, binStart, binEnd, count, density, validCount, excludedCount, belowRangeCount, aboveRangeCount, method`. `Σcount = 0` → `CACHED_BIN_NUMERIC_EMPTY`. Decision budgets (`SOURCE_TOO_LARGE`, `TABLE_TOO_LARGE_FOR_TRANSFORM`). |
| `box_summary` | **`column`** (required, as above), optional **`groupBy`** (one column, string or one-element array), optional **`filters`** | Method `tukey_1_5_iqr_linear_p_v1`: per group sort the valid values; `q1/median/q3` are type-7 linear percentiles (same as `group_metric` `percentile`); `whiskerLow` = smallest value ≥ `q1 − 1.5·IQR`, `whiskerHigh` = largest value ≤ `q3 + 1.5·IQR`; values beyond are outliers (`outlierCount` counts all; `outliers` lists at most 20, farthest from their fence first). `n = 1` → all seven statistics equal; `IQR = 0` → whiskers equal the quartiles and any different value is an outlier. Groups keep first-appearance order; without `groupBy` the single group is `All`; groups with no valid value emit no row. More than 24 groups → `TOO_MANY_GROUPS`; no group with values → `CACHED_BOX_SUMMARY_EMPTY`. Output columns: `groupKey, n, excludedCount, min, q1, median, q3, max, whiskerLow, whiskerHigh, outlierCount, outliers (JSON array text), method`. |
| `group_metric` | **`measures`** (required), optional **`filters`**, **`groupBy`**, **`derived`**, **`having`**, **`sorts`**, **`maxItems`**, **`offset`**, **`fields`** | Two `groupBy` keys plus one measure produce the long table that `build_chart_from_tabular_result` `kind: heatmap` pivots (`seriesColumn` = first key, `xColumn` = second, `yColumn` = the measure; at most 24 × 48, missing combinations stay empty). Per-measure optional **`filters`** (not `where`). Pipeline order per query-spec §5.4. For **`sum`** with measure-level **`filters`**, a non-empty group where **no** source row passes the measure filter yields **`0`** for that measure (not **`null`**); see the **`cached-table-decision-tools.md`** measure table. |
| `union_rows` | **`sourceCacheIds`** (required, 2–31), **`labelColumn`** (required), optional **`labelValues`** | Appends the input tables row by row in call order and adds one STRING label column (for example the day or the device each table came from). Rows are **never de-duplicated**. Every input must have the **same column names in the same order with the same base type**, and for a nested `INFOTABLE` column the same local shape (the output keeps it, which the cache needs to prove the nested table has no PASSWORD column); nothing is coerced or padded. Order of checks: input count and argument shape → each input's descriptor (`CACHE_MISS`, `SOURCE_ROW_COUNT_UNKNOWN`) and the **summed descriptor row count against 100_000 before any table is read** (`SOURCE_TOO_LARGE`) → per input, in order: label collision, schema match, then the running cell count `rows × (columns + 1)` against **`MAX_CELLS_FOR_TABULAR_TRANSFORM`**, then the call's shared byte and wall-time budget (an upper bound of what the cache codec writes: JSON-escaped column names and label on every row, `null` for a missing value, every cell's escaped text; the deadline is checked before every input read, while measuring and appending, and before the result is stored; against the invocation's storage bytes — default **32_000_000** — and wall time — default **60 s**), all before that input's rows are appended. A refusal caches and registers nothing. Result uses the plain `CACHED_TABULATE_EMPTY` / `_INLINE` / `_LARGE` kinds plus `sourceCacheIds` and `unionMeta`; **every non-empty union carries a derived `cacheId`** (INLINE too, unlike the other table modes), so the next `box_summary` / `group_metric` / chart can read the appended table; an EMPTY union clears the chartable `last_invoke` target. Completeness and counts: `CONTRACTS/TABULAR_INSIGHT.md` §5.1. |
| `exact_join` | **`rightCacheId`** (required), optional **`joinType`** | Governed U4 exact join: `cacheId` is left. U4 result shell — see `CONTRACTS/TABULAR_INSIGHT.md` §6. Alias: `exact_join_cached_result`. |
| `quality` | **`timeColumn`**, **`windowStart`**, **`windowEnd`**; optional **`valueColumn`** | G6 assessment-only (no `findingCacheId`). U4 result shell §6. Alias: `quality_cached_result`. |
| `resample` | **`timeColumn`**, **`windowStart`**, **`windowEnd`**; optional **`valueColumn`**, **`aggregation`** | G3 bucket resample; publishes derived table. U4 result shell §6. Alias: `resample_cached_result`. |
| `rolling` | **`timeColumn`**, **`windowStart`**, **`windowEnd`**; optional **`valueColumn`**, **`observationWindow`** | G3 rolling window; publishes derived table. U4 result shell §6. Alias: `rolling_cached_result`. |
| `rate_of_change` | **`timeColumn`**, **`windowStart`**, **`windowEnd`**; optional **`valueColumn`** | G3 pointwise rate; publishes derived table. U4 result shell §6. Alias: `rate_of_change_cached_result`. |
| `period_compare` | **`timeColumn`**, dual windows (`original*` / `current*`); optional **`valueColumn`**, **`aggregation`** | G3 period compare; assessment-only (no `findingCacheId`). U4 result shell §6. Alias: `period_compare_cached_result`. |
| `counter_delta` | **`timeColumn`**, **`valueColumn`**, **`windowStart`**, **`windowEnd`**; optional **`entityColumn`** and the four counter rules | CF-05 counter increments between real readings (`counter_delta_v1`); publishes a segment table. `knownDelta` sums exact segments, `lowerBoundDelta` sums lower bounds, neither is a window or shift total. U4 result shell plus root `warnings` / `completeness` — see `CONTRACTS/TABULAR_INSIGHT.md` §6.3. Timestamps must have whole-millisecond precision (`TIMESTAMP_UNSUPPORTED`). One operation-wide deadline and cancellation check (`TIME_BUDGET_EXCEEDED`, `OPERATION_CANCELLED`). No alias. Admission reason `CE_COUNTER_DELTA_DISABLED`. |
| `rolling_stats` | **`timeColumn`**, **`valueColumn`**, **`windowStart`**, **`windowEnd`**, **`statistic`**; optional **`entityColumn`**, **`rollingKind`**, **`observationWindow`**, **`durationWindowSeconds`**, **`minSupport`** | CF-52 rolling statistics (`rolling_stats_v1`): one row per source record with `value`, `valueStatus`, `support`, `records` and `warmedUp`. `warmedUp` is a frame-availability marker and is not coverage. Records with a missing value stay in the window. Values are bounded at 1e150. U4 result shell plus root `warnings` / `completeness` — see `CONTRACTS/TABULAR_INSIGHT.md` §6.4. No alias; the existing `rolling` mode is unchanged. Admission reason `CE_ROLLING_STATS_DISABLED`. |
| `time_weighted` | **`timeColumn`**, **`valueColumn`**, **`windowStart`**, **`windowEnd`**, **`integrationMethod`**, **`maxGapSeconds`**, **`timeUnit`** | CF-01 time-weighted integral and mean (`time_weighted_v1`) of **one numeric property-history cache**; any other source, every derived table included, is refused with `SOURCE_CONTINUITY_UNKNOWN`. One row per segment with `coverage` `OBSERVED` / `HELD` / `UNKNOWN`; `HELD` is a last-value estimate and is summed apart (`estimatedIntegral`, `estimatedSeconds`); a source with `READ_LIMIT_REACHED` or `PARTIAL` cancels the hold after the last reading. Never a window or shift total. To compare series, unite the output tables with `union_rows` and use `group_metric` with `sum(integral)`, `sum(supportedDuration)` and their `ratio`. U4 result shell plus root `warnings` / `completeness` — see `CONTRACTS/TABULAR_INSIGHT.md` §6.5. No alias. Admission reason `CE_TIME_WEIGHTED_DISABLED`. |
| `calendar_bucket` | **`timeColumn`**, **`timeZone`**, **`calendarBucket`** | CF-03 calendar labelling (`calendar_bucket_v1`): keeps every source row and column and appends `bucketStart`, `bucketEnd`, `bucketLabel`, `bucketSeconds` for the local day or hour of the row's own time. Aggregate afterwards with `group_metric` by `bucketLabel`. On an event's start column the label is the start day: an event crossing midnight counts wholly on that day; it is not duration allocated per day and not daily utilization. Rows without a usable time are kept with empty bucket cells, and `group_metric` gathers them in one group with an empty key. Days without rows are absent, not 0. `bucketSeconds` is the real length (82,800 / 90,000 s on switch days); a local date that recurs after a rollback keeps one label over two intervals, so one row's `bucketSeconds` is not the label's total. U4 result shell plus root `warnings` / `completeness` — see `CONTRACTS/TABULAR_INSIGHT.md` §6.6. No alias. Admission reason `CE_CALENDAR_BUCKET_DISABLED`. |

Demo App profile digests for the six U4 modes: `com.thingworx.things.agent.analysis.config.U4DemoAppProfiles`. Playbooks invoke the same modes (no second implementation).

**`maxItems` and `offset`**

- **`sort_topn` / `filter_rows` / `filter_sort_topn` / `group_metric`:** **`maxItems`** defaults to **50** where applicable; must satisfy **1 ≤ maxItems ≤ 500**. **`offset`** defaults to **0**; must be **≥ 0**. Out of range → **`LIMIT_OUT_OF_RANGE`** (or **`INVALID_PARAMETERS`** for bad sort key shape).
- **`group_count` / `group_aggregate`:** Output cap uses **`maxItems`** when present (same **1…500** bounds; omitted → up to **500** groups).

### 1.2 Success JSON

All successes include:

| Field | Type | Meaning |
|-------|------|---------|
| `status` | `"success"` | |
| `sourceCacheId` | string | Effective cache id used for the read (after P2 sentinel resolution when applicable). |
| `resultKind` | enum | See below. |
| `insightEnvelope` | object | Provenance block (`schemaVersion`, `sourceCacheId`, `rowEstimate`, `columns[]`). **Current implementation:** always present on success (see [`tabular_insight_envelope.md`](./tabular_insight_envelope.md)); clients should still tolerate future omission. |

**`resultKind` values**

| Value | When |
|-------|------|
| `CACHED_TABULATE_EMPTY` | Zero rows in the result table. **`union_rows`** uses the three `CACHED_TABULATE_*` kinds and adds root **`sourceCacheIds`** (all inputs, call order) and **`unionMeta`** `{ unionRowsNotDeduped: true, inputCount }`; root `sourceCacheId` is the first input. |
| `CACHED_TABULATE_INLINE` | `totalRows` ≤ large-row threshold (same as `InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD`, **20**). Includes `columns`, `rows`. |
| `CACHED_TABULATE_LARGE` | Above threshold. Includes `totalRows`, `columns`, `sampleRows`, **`cacheId`** (new entry for the **transformed** table), `hint` for paging via `fetch_cached_result`. **No** full `rows` array. |
| `CACHED_FILTER_COUNT` | `filter_count` without `groupBy`. Includes `matchCount`, `predicate`, `sampleRows` (up to **20**). |
| `CACHED_FILTER_GROUP_COUNT` | `filter_count` with `groupBy`. Includes `matchCount`, `predicate`, `groups[]`. |
| `CACHED_FILTER_ROWS_EMPTY` \| `CACHED_FILTER_ROWS_INLINE` \| `CACHED_FILTER_ROWS_LARGE` | `filter_rows` outcomes (same INLINE / LARGE row threshold as above). Success adds **`matchCount`** (full hits) and **`rowCount`** (source rows). |
| `CACHED_FILTER_SORT_TOPN_EMPTY` \| `CACHED_FILTER_SORT_TOPN_INLINE` \| `CACHED_FILTER_SORT_TOPN_LARGE` | `filter_sort_topn` outcomes. Success adds **`matchCount`** (rows matching optional `where`) and **`rowCount`** (source rows). |
| `CACHED_GROUP_METRIC_EMPTY` \| `CACHED_GROUP_METRIC_INLINE` \| `CACHED_GROUP_METRIC_LARGE` | `group_metric` outcomes: **INLINE** when the grouped output fits in a single inline payload (legacy threshold **≤20** rows, or **21–50** rows when query-spec **§11** complete-answer eligibility passes — full `rows[]` without going through **LARGE**). **LARGE** otherwise (sample rows + `cacheId` + fetch hint). **`totalRows`** is always the post-having grouped count (**`matchCount`**), not the truncated page size. Optional **§11** fields on **INLINE** only when the page is untruncated and eligibility passes (`answerSetComplete`, `sampleOnly`, `rowsOmitted`, `returnedRows`). JVM **`parler.agent.answerSetComplete.enabled`** (default **`true`**) gates the **21–50** extended-inline path; **`false`** keeps the legacy **LARGE** shape for that band. Success adds **`matchCount`**, **`groupCount`**, **`rowCount`** (source), optional metadata fields per §1.1. |

| `CACHED_BIN_NUMERIC_EMPTY` \| `CACHED_BIN_NUMERIC_INLINE` | `bin_numeric` outcomes (never LARGE: ≤ 50 rows). **INLINE** always stores the output as its own derived cache and returns **`cacheId`** (derived) plus **`sourceCacheId`** (input), the real **`columns`**, **`rows`**, `totalRows` / `returnedRows`, `rowsOmitted: false`, `sampleOnly: false`, `answerSetComplete: true`, and the scalar mirrors `column`, `method`, `validCount`, `excludedCount`, `belowRangeCount`, `aboveRangeCount`, `inRangeCount`, `binCount`. **EMPTY** carries the scalar counts and no `cacheId`; registering it clears the chartable `last_invoke` target (a later `build_chart_from_tabular_result` with `source: last_invoke` gets `SOURCE_RESULT_NOT_TABULAR` instead of charting the previous table; an explicit `cache_id` still works). |
| `CACHED_BOX_SUMMARY_EMPTY` \| `CACHED_BOX_SUMMARY_INLINE` | `box_summary` outcomes (never LARGE: ≤ 24 rows). Same envelope rules as `bin_numeric`; scalar mirrors `column`, `groupBy` (when given), `method`, `groupCount`, `excludedCount`. |

LARGE semantics match the plan: the new `cacheId` refers to the **transformed** table, not the source.

### 1.3 Error `code` values (`tabulate_cached_result`)

One primary **`code`** per error response. Rows are **non-overlapping** by intended cause (do not read “missing field” into `LIMIT_OUT_OF_RANGE`).

| `code` | When |
|--------|------|
| `MISSING_CACHE_ID` | `cacheId` absent or empty. |
| `MISSING_MODE` | `mode` absent or empty. |
| `UNKNOWN_MODE` | `mode` is not one of the supported values (including misspelled `filter_*` names). |
| `CACHE_MISS` | Unknown or expired `cacheId` for this conversation. If the conversation **P2 TOKEN mirror** still pointed at that id, the server **removes** that mirror entry so later **`TOKEN`** does not keep resolving to it. |
| `LAST_TABULAR_CACHE_UNAVAILABLE` | `cacheId` is the P2 sentinel but neither **this agent turn** nor the **conversation mirror** holds a resolvable qualifying `cacheId` (or the last qualifying tabular was **inline-only**, clearing the mirror). |
| `TABLE_TOO_LARGE_FOR_TRANSFORM` | Legacy `sort_topn` / `group_*` / `summarize_*`: input rows **> 50_000** or cells exceed cap (§4). **`union_rows`:** cells, or the estimated encoded bytes of the appended table, exceed their budget. **Decision modes:** cells exceed **`MAX_CELLS_FOR_TABULAR_TRANSFORM`** (same cap as legacy). |
| `SOURCE_TOO_LARGE` | **`union_rows`**: the inputs' descriptor row counts sum to **> 100_000**, decided before any table is read. **Decision modes** (`filter_*`, **`group_metric`**, **`bin_numeric`**, **`box_summary`**): source row count **> 100_000** (`MAX_SCANNED_ROWS` per [`cached-table-decision-tools.md`](./cached-table-decision-tools.md) §7). |
| `CARDINALITY_TOO_HIGH` | More than **5_000** distinct groups during `group_count` / `group_aggregate` / **`group_metric`**. |
| `MISSING_SOURCE_CACHE_IDS` | **`union_rows`**: `sourceCacheIds` absent, not an array, or fewer than **2** entries. |
| `TOO_MANY_UNION_INPUTS` | **`union_rows`**: more than **31** entries; decided before any cache lookup. |
| `INVALID_SOURCE_CACHE_IDS` | **`union_rows`**: an entry is not a non-empty string. |
| `MISSING_LABEL_COLUMN` | **`union_rows`**: `labelColumn` absent or empty. |
| `INVALID_LABEL_VALUES` | **`union_rows`**: `labelValues` present but not an array, its length differs from `sourceCacheIds`, or an entry is not a string. |
| `SOURCE_ROW_COUNT_UNKNOWN` | **`union_rows`**: an input's descriptor carries no row count, so the row budget cannot be decided before reading. |
| `UNION_TIME_BUDGET_EXCEEDED` | **`union_rows`**: the call's shared wall-time budget ran out while inputs were being measured; nothing was cached. |
| `LABEL_COLUMN_COLLISION` | **`union_rows`**: `labelColumn` already exists in an input. |
| `UNION_COLUMN_MISMATCH` | **`union_rows`**: an input's column names, order or base types differ from the first input; the message names the offending `cacheId`. |
| `TOO_MANY_GROUPS` | **`box_summary`**: more than **24** distinct groups after filters (not silently truncated). |
| `LIMIT_OUT_OF_RANGE` | `sort_topn` / `filter_rows` / `filter_sort_topn` / **`group_metric`**: `limit` not in **1…500**, or `offset` negative. `group_count` / `group_aggregate`: explicit `limit` not in **1…500** (omitted `limit` is not an error; see §1.1). |
| `UNSORTABLE_COLUMN` | `sorts[].fieldName` not sortable, or `aggregateColumn` type not supported for the given `fn`. **`filter_sort_topn`** / **`group_metric`** additionally reject **JSON / INFOTABLE / TAGS** sort keys. |
| `DERIVED_SCALE_MISMATCH` | **`group_metric`**: `multiply` when any input is **percent-scaled**; `sum_values` when operands **mix** percent-scaled with other scales; `difference` when **only one** side is percent-scaled. Percent scale includes `ratio_percent`, `scale(ratio,100)`, and derived outputs that **inherited** that scale (e.g. `sum_values` over all-percent inputs, or `difference` of two percents). |
| `INVALID_WEIGHT` | **`group_metric`**: `weighted_avg` when any **used** weight is strictly negative. |
| `INVALID_PARAMETERS` | Other bad arguments: **root `arguments` string is not valid JSON** (parse failure); **root JSON value is not a JSON object** (e.g. array or bare primitive); invalid `direction`; unknown column name for `sortBy` / `groupBy` / `aggregateColumn`; missing `sortBy` for `sort_topn`; missing `groupBy`; bad `fn`; missing `aggregateColumn` when required; `filter_sort_topn` missing both `sort` and `sortBy`; **`group_metric`** **`percentile`** measure with **`p`** missing, non-numeric, NaN, or outside **`[0,100]`**; etc. (**Not** used for `limit`/`offset` range — that is **`LIMIT_OUT_OF_RANGE`**.) |
| `INVALID_PREDICATE` \| `TOO_MANY_PREDICATES` \| `UNSUPPORTED_OPERATOR` \| `TYPE_MISMATCH` \| `UNSUPPORTED_COLUMN_TYPE` \| `AMBIGUOUS_PERCENT_SCALE` \| `INVALID_COLUMN` | Predicate / column semantics for **`filter_*`**, **`group_metric`** `having`, and measure-level **`filters`**. Normative detail: [`cached-table-decision-tools.md`](./cached-table-decision-tools.md) §4–§9 and [`query-spec.md`](./query-spec.md). |
| `PROTECTED_TABULAR_COLUMN_BLOCKED` | **ThingWorx `PASSWORD`** (`DataShape`) column referenced via **`sorts[].fieldName`**, **`fields[]`**, **`groupBy`**, **`aggregateColumn`** (non-`count`), **`group_metric`** paths (`groupBy`, measure `column` / `weightColumn` / `orderBy`, measure **`filters`**), root **`filters`**, or **`having`**. See **`docs/agent/protection.md`** §4.7. |
| `TABULATE_ERROR` | Unexpected runtime failure after validation (**excluding** root-level malformed / non-object `arguments` JSON → **`INVALID_PARAMETERS`**). |

---

## 2. `summarize_cached_result`

### 2.1 Parameters

| Field | Required | Notes |
|-------|----------|--------|
| `cacheId` | yes | Table to summarize. Same P2 sentinel rules as §1.1 (`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`). |
| `percentileColumns` | no | JSON **array of non-empty strings** (column names). **p50** and **p95** are computed only for listed **numeric** columns. If **omitted** or JSON **null**, **p50/p95** apply only to the first **8** numeric columns in **DataShape declaration order**; other numeric columns still get **min / max / mean** only. If present but **not an array**, or any element is **null**, not a string, or an **empty** string after trim → **`INVALID_PARAMETERS`** (see §2.3). |

### 2.2 Success JSON

| Field | Type | Meaning |
|-------|------|---------|
| `status` | `"success"` | |
| `sourceCacheId` | string | Effective cache id used for the read (after P2 sentinel resolution when applicable). On success, the agent also **updates** the conversation **P2 TOKEN mirror** to this id without changing **`last_invoke`** / per-turn qualifying chart state. |
| `resultKind` | `CACHED_SUMMARY_EMPTY` \| `CACHED_SUMMARY_INLINE` | EMPTY when input row count is **0** (empty `columns` array). |
| `rowCount` | number | Input row count. |
| `columns` | array | One object per column (declaration order when shape exists). |
| `insightEnvelope` | object | Provenance block; **`rowEstimate`** = input row count; **`columns`** describe **input** table columns. **Current implementation:** always on success; see [`tabular_insight_envelope.md`](./tabular_insight_envelope.md) for client tolerance. |

**Per-column object** (typical)

- Always: `name`, `baseType`, `nullCount`; optional `nullRatio` when `rowCount` is greater than zero.
- **Numeric** (`NUMBER` / `INTEGER` / `LONG`): `numericStats`: `min`, `max`, `mean`, and **`p50` / `p95`** when that column is in the percentile set (§2.1).
- **`PASSWORD`:** `protectedColumnSummary`: **`true`**; **`unsupportedStatsReason`**: **`password_column`** (no `categoricalStats` “top” lists or percentile stats). See **`docs/agent/protection.md`** §4.7.
- **Categorical** (`STRING` / `TEXT` / `GUID` / `HTML` / `BOOLEAN`): `categoricalStats`: `cardinality`, `top` (bounded list of `{ value, count }`).
- **`DATETIME`:** `datetimeStats`: `min`, `max` (ISO-style strings).
- **Other / complex types:** `unsupportedStatsReason`: `"non-primitive"` (no throw for the whole tool).

### 2.3 Errors (`summarize_cached_result`)

| `code` | When |
|--------|------|
| `MISSING_CACHE_ID` | `cacheId` absent or empty. |
| `LAST_TABULAR_CACHE_UNAVAILABLE` | P2 sentinel present but no resolvable last qualifying `cacheId` in this agent turn or conversation mirror (or last qualifying tabular was inline-only). |
| `CACHE_MISS` | Unknown or expired `cacheId`. Same **P2 TOKEN mirror** prune on miss as **`tabulate_cached_result`** (see §1.3). |
| `TABLE_TOO_LARGE_FOR_TRANSFORM` | Same caps as tabulate (§4). |
| `INVALID_PARAMETERS` | **Root `arguments` string is not valid JSON** (parse failure); **root JSON value is not a JSON object** (e.g. array or bare primitive); or **`percentileColumns`**: not a JSON array; any element not a non-empty string; unknown column name; or column not **NUMBER / INTEGER / LONG**. |
| `PROTECTED_TABULAR_COLUMN_BLOCKED` | Explicit **`percentileColumns`** lists a **`PASSWORD`** column. See **`docs/agent/protection.md`** §4.7. |
| `SUMMARIZE_ERROR` | Unexpected runtime failure after validation (**excluding** root-level malformed / non-object `arguments` JSON → **`INVALID_PARAMETERS`**). |

---

## 3. Routing

Model-facing rules live in **`parler-agent/src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt`** (cached tabular section): use tabulate/summarize for aggregations, thresholds, filtered subsets, and ranked rows over cached tables; prefer these over re-querying when the dataset is unchanged.

---

## 4. Implementation constants (defaults)

| Constant | Value |
|----------|--------|
| Max input rows | **50_000** (`sort_topn`, `group_count`, `group_aggregate`, `summarize_cached_result`) |
| Max source rows scanned (**`filter_*`**, **`group_metric`**, **`bin_numeric`**, **`box_summary`**) | **100_000** (`SOURCE_TOO_LARGE` when exceeded) |
| Max input cells (rows × columns) | **2_000_000** |
| Max `union_rows` inputs | **31** (`MAX_UNION_INPUTS`; summed rows share the **100_000** budget, cells `rows × (columns + 1)` share the **2_000_000** budget) |
| Max distinct groups | **5_000** |
| Max `limit` (tabulate output) | **500** |
| Default `sort_topn` limit | **50** |
| Max numeric columns for default percentiles | **8** |
| LARGE vs INLINE row threshold | **20** (shared with `InvokeServiceExecutor`) |

---

## 5. Charts

`tabulate_cached_result` success (INLINE or LARGE) is wired into **`TabularChartRoundHooks`** so `build_chart_from_tabular_result` can use `last_invoke` or the returned `cacheId` like other tabular tools.

`summarize_cached_result` is **not** a row tabular source for charts.
