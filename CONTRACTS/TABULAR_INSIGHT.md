# Tabular insight — agent tool JSON (`tabulate_cached_result` / `summarize_cached_result`)

**Contract bundle:** [`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md).  
**Scope:** Normative **common root and error shell + `insightEnvelope`** for the **Parler ThingWorx Java agent** built-in tools **`tabulate_cached_result`** and **`summarize_cached_result`** (shared success-path fields, error payload fields, `insightEnvelope` schema revision **1**, and LARGE-branch alignment principle), **except** **`tabulate_cached_result`** U4 modes (**`exact_join`**, **`quality`**, **`resample`**, **`rolling`**, **`rate_of_change`**, **`period_compare`**) and the computing modes **`counter_delta`**, **`rolling_stats`**, **`time_weighted`** and **`calendar_bucket`**, which use the alternate U4 result shell in **§6**. **Tool-specific** argument schemas, `resultKind` enumerations, per-tool success-only fields, and full error **code** catalogs remain in [`docs/agent/cached_tabular_tools.md`](../docs/agent/cached_tabular_tools.md) as implementation-side reference. This contract describes **agent ↔ LLM tool-call surfaces**, not the **`parler-ui`** streaming reducer wire (see [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) / [`API_CONTRACT.md`](./API_CONTRACT.md)). UI **`insightEnvelope`** field semantics and the D7 gate live in [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) **§View — `insightEnvelopeLoose` (D7)**.

**Implementation-side reference (outside contract bundle):** [`docs/agent/cached_tabular_tools.md`](../docs/agent/cached_tabular_tools.md), [`docs/agent/tabular_insight_envelope.md`](../docs/agent/tabular_insight_envelope.md). **Golden / harness fixtures:** [`docs/agent/cached_tabular_golden.md`](../docs/agent/cached_tabular_golden.md).

All JSON is UTF-8.

---

## 1. Error payload (both tools)

When the tool returns an error string to the LLM pipeline, it MUST be a JSON object with:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`error`**. |
| `code` | string | yes | Machine code (e.g. `INVALID_PARAMETERS`, `CACHE_MISS`, `LIMIT_OUT_OF_RANGE`, `TABLE_TOO_LARGE_FOR_TRANSFORM`, `PROTECTED_TABULAR_COLUMN_BLOCKED`, …). Full sets are tool-specific; see **`docs/agent/cached_tabular_tools.md`**. |
| `message` | string | yes | Human-readable detail (may be empty string). |

**P2 mirror lifecycle (normative, both tools):** When **`code`** is **`CACHE_MISS`**, if the conversation-scoped P2 **`TOKEN`** mirror (same keying described under **`sourceCacheId`** in **§2**) currently equals the **`cacheId`** that **`lookupCachedInfotable`** attempted (after **`TOKEN`** resolution when applicable), implementations MUST remove that mirror entry.

---

## 2. Success — common root fields

On success, the root object MUST include:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`success`**. |
| `sourceCacheId` | string | yes | The **effective** cache id used for `lookupCachedInfotable` (after P2 sentinel resolution when the request used **`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`** — **per-turn** state first, then **conversation-scoped** last qualifying `cacheId` in the agent JVM when the turn snapshot is empty). The conversation-scoped mirror uses the wire **`conversation_id`** for persistent threads; for **`__single_turn__`** paths with an AlwaysOn **`request_id`**, implementations use a per-request composite key so adhoc turns do not share one map entry. On **`summarize_cached_result`** success, implementations MUST set this mirror to the root **`sourceCacheId`** without updating per-turn **`TabularChartRoundState`** last qualifying **`cacheId`** used for **`build_chart_from_tabular_result`** **`last_invoke`**. MUST be present even when `insightEnvelope` is omitted in a future revision. |
| `resultKind` | string | yes | One of the tool-specific literals documented in **`cached_tabular_tools.md`** (e.g. `CACHED_TABULATE_INLINE`, `CACHED_SUMMARY_EMPTY`, …). |

---

## 3. `insightEnvelope` (current P1 behavior)

On **every** success today for the `resultKind` values listed in **`tabular_insight_envelope.md`**, the root object MUST include **`insightEnvelope`** as an object with:

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `schemaVersion` | string | yes | Literal **`1`** for this revision. |
| `sourceCacheId` | string | yes | Same value as root `sourceCacheId`. |
| `rowEstimate` | number | yes | Row count semantics per tool / mode (see **`tabular_insight_envelope.md`**). |
| `columns` | array | yes | Array of `{ "name", "baseType" }` objects; column set semantics per tool (see **`tabular_insight_envelope.md`**). |

Clients SHOULD tolerate a future server omitting `insightEnvelope` while still requiring root `sourceCacheId`.

---

## 4. LARGE branch alignment

When `resultKind` ends with **`_LARGE`**, the payload MUST follow the same structural conventions as other tabular LARGE tool results in this extension (e.g. `cacheId`, `sampleRows`, `hint` / row estimate) as documented in **`docs/agent/AGENT-TAXONOMY.md`** §5.2.1 and **`cached_tabular_tools.md`**.

---

## 5. Public `completeness` and `counts` (BP6)

On **every** success for **`tabulate_cached_result`** and **`summarize_cached_result`**, the root
object MUST include public packaging fields derived from the runtime
`SourceDescriptor` (agent-only full descriptor is never dumped):

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `completeness.status` | string | yes | One of **`COMPLETE`**, **`PARTIAL`**, **`UNKNOWN`**. |
| `completeness.reasons` | array of string | yes | May be empty. Machine-readable notes; clients MUST NOT invent reasons. |
| `counts.rowsRead` | number | when known | Rows examined / scanned from the source cache for this call (`SourceDescriptor.rowsExamined`). |
| `counts.rowsOutput` | number | when known | Rows (or match cardinality) produced by this call (`SourceDescriptor.rowsReturned`). |
| `counts.totalAvailable` | number | **only when proven** | Source total when the descriptor carries a proven `rowsAvailable`. MUST be **absent** when unproven — implementations MUST NOT set it equal to `rowsRead`/`rowsOutput` while `completeness.status` is **`UNKNOWN`**. |

### 5.1 Per-mode `counts` semantics (`tabulate_cached_result`)

| Mode / result family | `counts.rowsRead` | `counts.rowsOutput` | `counts.totalAvailable` |
|----------------------|-------------------|---------------------|-------------------------|
| Transform table modes (`filter_rows`, `filter_sort_topn`, `group_metric` table, legacy sort/group aliases that emit a result table) | Source cache row count examined (parent examined/returned when composing) | Output table row count | Present only if parent descriptor already had a proven total |
| `filter_count` (scalar `CACHED_FILTER_COUNT`) | Source cache row count scanned | **`matchCount`** | Same provenance rule as above |
| `filter_count` with `groupBy` (`CACHED_FILTER_GROUP_COUNT`) | Source cache row count scanned | Sum of per-group match counts (full match cardinality) | Same provenance rule as above |
| `summarize_cached_result` | Source cache row count | Source cache row count (summary is over the full input table) | Same provenance rule as above |
| Distribution modes `bin_numeric` / `box_summary` (`CACHED_BIN_NUMERIC_*`, `CACHED_BOX_SUMMARY_*`) | Source cache row count scanned | Output rows (bins or groups); `0` on the `_EMPTY` kinds | Same provenance rule as above. The output table is complete by construction (`answerSetComplete: true` on `_INLINE`); the input's completeness is carried unchanged by the derived descriptor and never upgraded by the small output. The `_INLINE` kinds always carry a derived `cacheId`; the `_EMPTY` kinds clear the chartable `last_invoke` target (design §7.4). |
| Append mode `union_rows` (`CACHED_TABULATE_*`) | Sum of the inputs' descriptor row counts (`rowsReturned`, else `rowsExamined`) | Output table row count (the sum of the input tables' rows; rows are appended, never de-duplicated) | **Always absent.** A proven total on one input says nothing about the appended table, so the derived descriptor drops `rowsAvailable`. `completeness.status` is the worst of the inputs: any `PARTIAL` → `PARTIAL`, else any `UNKNOWN` (or a missing status) → `UNKNOWN`, else `COMPLETE`; an append never upgrades completeness. The derived descriptor lists **every** input in `parentSourceCacheIds`; root `sourceCacheId` and `insightEnvelope.sourceCacheId` name the **first** input, and the success root adds `sourceCacheIds` (all inputs, in call order) and `unionMeta` `{ unionRowsNotDeduped: true, inputCount }`. **Every non-empty result** (`_INLINE` and `_LARGE`) carries a derived `cacheId` for the whole appended table; it is the chartable `last_invoke` target and a presentation artifact in both cases, independently of `sampleOnly` / `rowsOmitted` on `_LARGE`. `_EMPTY` from `union_rows` clears the chartable `last_invoke` target. The shared `CACHED_TABULATE_*` envelopes of every other mode — `_EMPTY`, `_INLINE` and `_LARGE` — are unchanged: `returnedRows` / `sampleOnly` / `rowsOmitted` on `CACHED_TABULATE_LARGE` are written for `union_rows` only. |

`resultKind` EMPTY/INLINE/LARGE packaging remains independent of `completeness.status` — formatters
MUST NOT infer completeness from `resultKind`, sample length, or handle presence.

### 5.2 Registered `completeness.reasons` values

| Value | Set by | Meaning |
|-------|--------|---------|
| `READ_LIMIT_REACHED` | The reader of a platform read that ran under a row limit (property history, numeric and non-numeric; Stream rows; each series of the history overlay), on the descriptor of the table it caches. | The table the platform returned had **at least as many rows as the effective limit of that read**. It is an observation, **not** a synonym of "truncated": it can be present on a complete read whose data has exactly that many rows, and it can be absent on a cut read where the platform merges or expands stored entries into rows. It never changes `completeness.status` (such a table stays `UNKNOWN`), and its **absence is never evidence of completeness**. No read is enlarged or repeated to obtain it. It is stated by the reader only and is never inferred from the size of a table, so a JSON cache, a promoted JSON table or an ordinary service result of the same size does not carry it. Derivations keep it: a single-parent derivation copies its parent's reasons, and `union_rows` carries the reasons of **all** its inputs, in input order without duplicates. |

The tools that perform such a read also say it on their own result, outside this envelope: `readLimitReached: true`
and `readLimitNote` (one sentence for the model) are present **only** when the limit was reached, next to the
existing `maxItemsRequested` / `maxItemsEffective` echo; `build_history_overlay_chart` adds
`readLimitReachedSeries` (labels) and the same flag on the affected `seriesCaches[]` entries. A result small
enough to stay inline carries the hint and is not cached for the sake of the mark.

---

## 6. U4 governed modes — alternate result shell

When **`tabulate_cached_result`** is invoked with **`mode`** one of **`exact_join`**,
**`quality`**, **`resample`**, **`rolling`**, **`rate_of_change`**, **`period_compare`**, or the computing
modes **`counter_delta`** (§6.3), **`rolling_stats`** (§6.4), **`time_weighted`** (§6.5) and **`calendar_bucket`** (§6.6),
**§§1–5 do not apply**. The tool MUST return the same U4 result JSON as the matching demoted
executor-only alias (shared executor / runner / admission / vocabulary per mode):

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | **`OK`** or **`ERROR`** (not the §1/§2 `success` / `error` literals). |
| `reason` | string | yes | Operation / admission reason (e.g. **`OK`**, **`QUALITY_BLOCKING`**, **`PERCENT_UNDEFINED`**, **`U4_*_DISABLED`**, **`ARGUMENT_MISSING`**, **`JOIN_TYPE_INVALID`**, **`WINDOW_INVALID`**, **`TIME_AXIS_MISSING`**, **`AGGREGATION_INVALID`**, **`ROLLING_KIND_INVALID`**). |
| `mayPublish` | boolean | yes | Whether a derived handle was allowed; MUST be **`false`** on failure / disable. **`quality`** and **`period_compare`** NEVER publish a derived handle (`mayPublish` always **`false`** on success). |
| `findingCacheId` | string | no | Present only when a derived table was published (`exact_join` / `resample` / `rolling` / `rate_of_change`). |
| `analysisEnvelope` | object | no | Compact U4 analysis envelope when produced. |
| `detail` | string | no | Optional human-readable detail on some error paths. |
| `warnings` | string[] | computing modes, success only | At most four entries, identical to `analysisEnvelope.evidence.warnings` (§6.3, §6.4, §6.5, §6.6). |
| `completeness` / `counts` / `sourceCacheId` | object / object / string | computing modes, success only | The §5 public packaging of the published segment table's descriptor, or of the source when no table was published. `completeness.reasons` carries the source's reasons unchanged (§5.2). |

### 6.1 Mode-specific arguments

| Mode | Required | Optional | Notes |
|------|----------|----------|-------|
| **`exact_join`** | `cacheId` (left), `rightCacheId` | `joinType` (`INNER` default / `LEFT`) | `rightCacheId` MUST be explicit (sentinel rejected). Invalid present `joinType` → **`JOIN_TYPE_INVALID`**. |
| **`quality`** | `cacheId`, `timeColumn`, `windowStart`, `windowEnd` | `valueColumn` | Half-open window ISO-8601 instants. Assessment-only. |
| **`resample`** | `cacheId`, `timeColumn`, `windowStart`, `windowEnd` | `valueColumn`, `aggregation` | Missing `aggregation` defaults to App profile **`MEAN`**; present invalid → **`AGGREGATION_INVALID`**. Publishes derived bucket table on success. |
| **`rolling`** | `cacheId`, `timeColumn`, `windowStart`, `windowEnd` | `valueColumn`, `rollingKind`, `observationWindow`, `durationWindowSeconds`, `minSupport` | Default kind **`OBSERVATION_COUNT`**. Invalid present `rollingKind` → **`ROLLING_KIND_INVALID`**. Publishes derived table. |
| **`rate_of_change`** | `cacheId`, `timeColumn`, `windowStart`, `windowEnd` | `valueColumn` | Publishes derived rate table. |
| **`period_compare`** | `cacheId`, `timeColumn`, `originalWindowStart`, `originalWindowEnd`, `currentWindowStart`, `currentWindowEnd` | `valueColumn`, `aggregation` | Dual half-open windows. Assessment-only (metrics on envelope). |

`cacheId` uses the same P2 last-tabular sentinel resolution as other tabulate modes. Shared
single-window property descriptions MUST name only currently advertised series modes (never a
disabled sibling). Dual-window props are withdrawn with **`period_compare`**.

### 6.2 Advertisement / admission

When the matching U4 admission flag is **disabled**, the model-visible **`mode`** enum MUST
**omit** that mode (and its mode-only properties), and any direct execution (mode or demoted
alias) MUST reject with the stable **`U4_*_DISABLED`** reason and **`mayPublish`=`false`**:

| Mode | Disabled reason | Demoted alias |
|------|-----------------|---------------|
| `exact_join` | `U4_EXACT_JOIN_DISABLED` | `exact_join_cached_result` |
| `quality` | `U4_QUALITY_DISABLED` | `quality_cached_result` |
| `resample` | `U4_RESAMPLE_DISABLED` | `resample_cached_result` |
| `rolling` | `U4_ROLLING_DISABLED` | `rolling_cached_result` |
| `rate_of_change` | `U4_RATE_OF_CHANGE_DISABLED` | `rate_of_change_cached_result` |
| `period_compare` | `U4_PERIOD_COMPARE_DISABLED` | `period_compare_cached_result` |

### 6.3 Computing mode `counter_delta`

Increments of a cumulative counter between real readings (method **`counter_delta_v1`**). It has no
demoted alias. It never extrapolates past a reading and never reports a window or shift total.

| Item | Rule |
|------|------|
| Required | `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`. A missing `valueColumn` is **`ARGUMENT_MISSING`**; an unknown column is **`COLUMN_NOT_FOUND`**. |
| Optional | `entityColumn` (partition key), and four caller-supplied counter rules with **no defaults**: `counterModulus`, `maxRatePerSecond`, `maxGapSeconds`, `resetBaseline`. `counterModulus` and `resetBaseline` are in reading units and `maxRatePerSecond` in reading units per second: finite, at most 2^53, modulus and rate positive, baseline non-negative. `maxGapSeconds` is in **seconds**: a positive whole number, at most 2^53. The cap is checked on the JSON number as written, before it narrows to a double, so `9007199254740993` is refused rather than rounded onto the permitted `9007199254740992`; a non-integer literal at or above 2^53 is refused too. `counterModulus` requires `maxRatePerSecond` and excludes `resetBaseline`. Violations are **`COUNTER_RULE_INVALID`** and publish nothing. The executor cannot verify where a rule value came from; it echoes the values in `metrics.assumptions`. |
| Reading domain | Every included reading must satisfy `0 ≤ reading < 2^53`, else the whole request fails with **`COUNTER_DOMAIN_UNSUPPORTED`** (the cache stores doubles; a reading at or above 2^53 may already be rounded). A reading at or above a declared modulus, or below a declared baseline, fails with **`COUNTER_RULE_CONTRADICTED`**. More than 100,000 readings in the window is **`INPUT_TOO_LARGE`**; more than 50 partitions is **`TOO_MANY_PARTITIONS`**. Nothing is truncated or sampled. |
| Timestamps | Every included instant must have whole-millisecond precision and a year between 0001 and 9999, else the whole request fails with **`TIMESTAMP_UNSUPPORTED`**: the segment table stores DATETIME cells, and a finer instant would be published as a different endpoint than the one the delta was computed from. Elapsed time has no upper range limit; instants centuries apart are decided by the gap rule. |
| Operation budget | One wall-time deadline from the invocation `BudgetVector` covers reading, computing and publishing, and cancellation is checked at the same points, the last one immediately before publication. Exhaustion is **`TIME_BUDGET_EXCEEDED`**, a cancelled (interrupted) call is **`OPERATION_CANCELLED`**; neither returns a success nor publishes a table. `analysisEnvelope.budget` carries the requested, effective and consumed facts of the whole operation. |
| Rows | Rows with an unparseable time, a null / non-numeric / non-finite value, or an empty `entityColumn` are counted and excluded, never turned into 0. Identical readings at one instant collapse. **Different readings at one instant are a continuity barrier:** no segment ends at or spans that instant. |
| Segment `classification` | `NORMAL`, `ROLLOVER` (exact); `RESET`, `POSSIBLE_HIDDEN_RESET` (`lowerBound=true`); `IMPLAUSIBLE_INCREASE`, `UNCERTAIN_NEGATIVE_JUMP`, `WRAP_UNIDENTIFIABLE`, `GAP_EXCEEDED`, `BROKEN_BY_CONFLICT`, `SINGLE_READING` (no `delta`). With a declared modulus an interval carries a delta only when rate × elapsed is below the modulus, whatever the sign of the difference. The declared rate bounds every candidate increment, and it is checked before the hidden-reset test. |
| Published table | `entity`, `segmentStart`, `segmentEnd`, `startReading`, `endReading`, `delta`, `classification`, `lowerBound`, `elapsedSeconds`. Its descriptor never upgrades the source's completeness and copies its reasons. Published whenever at least one segment exists. |
| Envelope | `operation` **`counter_delta`**. `status` is **`SUCCESS`** when at least one segment carries a delta, also under `UNKNOWN` or `PARTIAL` completeness; otherwise **`INSUFFICIENT_EVIDENCE`**. Root `reason` repeats `metrics.outcome`: **`OK`**, **`NO_READINGS`** or **`NO_KNOWN_SEGMENT`**. |
| Metrics | `knownDelta` sums exact segments only; `lowerBoundDelta` sums lower-bound segments only; the two are never added. Also `scope` (always `observed_span`), `arithmetic` (`exact_integer` when every reading and the given `counterModulus` / `resetBaseline` are integer-valued, else `float64`; `maxRatePerSecond` and `maxGapSeconds` only bound comparisons and do not affect it; an exact running total reaching 2^53 fails the request), `assumptions`, per-classification segment counts, exclusion counts, and for a single partition `firstReading`, `lastReading`, `uncoveredHeadSeconds`, `uncoveredTailSeconds`. |
| `warnings` | Fixed order. (1) Always: a sentence starting with **`SCOPE_OBSERVED_SPAN`**, extended with "not a window or shift total" when the source carries `READ_LIMIT_REACHED` or is `PARTIAL`. (2) When segments without a delta exist. (3) When lower-bound segments exist **or** no `maxRatePerSecond` was given (a reset between two readings cannot then be ruled out). (4) When rows were excluded or conflicting instants exist. The source's read-limit reason changes only this text, never a classification, delta or total. |
| Admission | Disabled reason **`CE_COUNTER_DELTA_DISABLED`**; while disabled the `mode` value and its mode-only properties leave the model-visible schema, as in §6.2. |

### 6.4 Computing mode `rolling_stats`

One statistic over record-anchored rolling windows (method **`rolling_stats_v1`**). It has no demoted alias and
does not change **`rolling`**, whose mode description, result and status behavior stay as in §6.1. Windows hold
only records that were read; nothing is extrapolated, filled or turned into a plateau.

| Item | Rule |
|------|------|
| Required | `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`, `statistic`. A missing `valueColumn` or `statistic` is **`ARGUMENT_MISSING`**; an unknown column is **`COLUMN_NOT_FOUND`**. |
| Optional | `entityColumn` (partition key), and the window properties shared with **`rolling`**, with the same defaults and error codes: `rollingKind` (`OBSERVATION_COUNT` default / `ELAPSED_DURATION`), `observationWindow` (5), `durationWindowSeconds` (3600), `minSupport` (1). |
| `statistic` | `mean`, `sum`, `min`, `max`, `stddev` (sample, n−1), `count_values`, `count_records`; anything else is **`STATISTIC_INVALID`**. One statistic per call. |
| Window members | Records are ordered by (time, source ordinal). The window anchored at a record holds records **at or before it in that order**: the last N for an observation window; those not earlier than `t − D` for an elapsed window, both ends inclusive. Records sharing an instant are all kept, and an earlier one does not include the peers ordered after it: `(0 s, 1)`, `(60 s, 3)`, `(60 s, 5)` with D = 60 s give sums 1, 4, 9. Windows never cross `entityColumn` values. |
| Rows | Rows with an unparseable time or an empty `entityColumn` are counted and excluded. A row with a null / non-numeric / non-finite value **stays a record** and is only left out of the valid values. Timestamps follow the §6.3 rule (**`TIMESTAMP_UNSUPPORTED`**). |
| Support | `records` is the member count (at least 1), `support` the valid values among them, with that meaning for every statistic. `mean` / `sum` / `min` / `max` need `support ≥ minSupport`; `stddev` also needs two valid values; `count_values` and `count_records` compare **`records`** with `minSupport`, and a count of 0 is a value. A row without a value has `valueStatus` **`BELOW_MIN_SUPPORT`** or **`BELOW_MIN_SAMPLE`**, otherwise **`OK`**. An all-missing `sum` is empty, never 0. |
| Numeric domain | The absolute value of every valid value must be at most `1e150`, else the whole request fails with **`VALUE_MAGNITUDE_UNSUPPORTED`**. Each window is recomputed from its members with compensated summation. `stddev` treats the rounded mean as a centre `c` only, never as the exact mean: with `d = v − c` the sample variance is `(Σd² − (Σd)²/n) / (n − 1)`, so a small spread on a large common offset is not inflated by the rounding of `c` (`1e16` and `1e16 + 2` give `sqrt(2)`, as `0` and `2` do). It is computed in scaled form (deviations divided by their largest magnitude, the scale multiplied back after the square root), so small spreads do not underflow to zero. A window whose `min` equals its `max` has that `mean` and a `stddev` of exactly 0. A non-finite result, or a zero `stddev` for a window whose members differ, fails the whole request with **`NUMERIC_RESULT_UNSUPPORTED`**. Numerical failure is never written as a missing value. |
| `warmedUp` | Frame-availability marker only: for an elapsed window, `t − D` is not before the partition's first record; for an observation window, `records` equals N. **It does not establish that the window's interior was observed or that the window is covered.** Rows that are not warmed up still carry a value. |
| Published table | `entity`, `timestamp`, `windowStart`, `windowEnd` (instants of the first and last member), `value`, `valueStatus`, `support`, `records`, `warmedUp`. Its descriptor never upgrades the source's completeness and copies its reasons. |
| Envelope | `operation` **`rolling_stats`**. `status` is **`SUCCESS`** when at least one row has a value, also under `UNKNOWN` or `PARTIAL` completeness; otherwise **`INSUFFICIENT_EVIDENCE`**. Root `reason` repeats `metrics.outcome`: **`OK`**, **`NO_READINGS`** or **`NO_SUPPORTED_WINDOW`**. |
| `warnings` | Fixed order. (1) Always: a sentence starting with **`SCOPE_OBSERVED_SPAN`** that says window coverage is not established, extended with "not statistics of a complete window or shift" when the source carries `READ_LIMIT_REACHED` or is `PARTIAL`. (2) Rows without a value, counted per `valueStatus`. (3) Rows that are not warmed up. (4) Excluded rows. The source's read-limit reason changes only this text, never a value. |
| Limits | At most 100,000 records in the window (**`INPUT_TOO_LARGE`**) and 50 partitions (**`TOO_MANY_PARTITIONS`**). The total window work, the sum of member counts including records without a value, is counted exactly before any aggregation and capped at 5,000,000 (**`WINDOW_WORK_TOO_LARGE`**). Nothing is sampled or approximated. The §6.3 operation budget applies unchanged (**`TIME_BUDGET_EXCEEDED`**, **`OPERATION_CANCELLED`**). |
| Admission | Disabled reason **`CE_ROLLING_STATS_DISABLED`**. The four window properties are advertised while `rolling` or `rolling_stats` is enabled, `entityColumn` while `counter_delta` or `rolling_stats` is enabled, and their descriptions name enabled modes only. |

### 6.5 Computing mode `time_weighted`

Time-weighted integral and time mean of **one numeric property-history series** inside one window (method
**`time_weighted_v1`**). It has no demoted alias. Every millisecond of the window is **`OBSERVED`**, **`HELD`**
or **`UNKNOWN`**, the three are summed apart, and a partly covered integral is never called a window or shift
total.

| Item | Rule |
|------|------|
| Required | `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`, `integrationMethod` (`step_hold` or `trapezoid`), `maxGapSeconds` (positive integer, at most 2^53), `timeUnit` (`seconds`, `minutes` or `hours`). None of the three method arguments has a default; the effective values are echoed in `metrics`. Missing: **`ARGUMENT_MISSING`**; invalid: **`INTEGRATION_METHOD_INVALID`**, **`TIME_UNIT_INVALID`**, **`MAX_GAP_INVALID`**, **`WINDOW_INVALID`**. `entityColumn` is not accepted: **`ARGUMENT_UNSUPPORTED`**. |
| Window edges | Segments are clipped to the window and its edges are published as DATETIME cells, so `windowStart` and `windowEnd` must be whole milliseconds with a year from 0001 to 9999, else **`TIMESTAMP_UNSUPPORTED`**. The check is local to this mode; the other series modes keep their window parsing. |
| Source admission | This mode integrates across intervals, and `maxGapSeconds` cannot prove that two readings belong to one continuous series. The source descriptor must exist, carry **both** `subjectThingName` and `subjectPropertyName`, have an **empty** parent list, and declare column roles equal to the requested `timeColumn` and `valueColumn`. Anything else is refused with **`SOURCE_CONTINUITY_UNKNOWN`** and nothing is published. An empty parent list alone is no evidence, and no route name is trusted. In effect only the cache of a numeric property-history read of one Thing property is admitted; service result tables, Stream rows, multi-device tables and every derived table (`union_rows`, `group_metric`, `filter_rows`, this mode's own output) are refused. |
| Unit | The integral is in "value unit × `timeUnit`" (kW with `hours` gives kWh). The executor knows no value unit and converts nothing; `metrics.integralUnit` echoes `value × hours`. The mean has the value's unit. Durations are always seconds. |
| Rows | Rows with an unparseable time, and rows with a null / non-numeric / non-finite value, are counted and excluded, never turned into 0. Identical rows at one instant collapse. Differing values at one instant make that instant a **continuity barrier**: it ends no segment and both neighbouring stretches are `UNKNOWN`. Reading timestamps follow the §6.3 rule (**`TIMESTAMP_UNSUPPORTED`**); a valid value above an absolute value of `1e150` fails the request with **`VALUE_MAGNITUDE_UNSUPPORTED`**. |
| Anchors | Besides the readings inside the window, the nearest **timestamp group** on each side is used as an anchor: the latest instant before `windowStart` and the earliest instant at or after `windowEnd` (a reading exactly at `windowEnd` is the right anchor). Identical anchor rows collapse; a conflicting anchor is a barrier like any other and is **not** skipped in favour of a farther reading. An anchor group is validated as a whole: a value above the admitted magnitude refuses the request wherever it stands among the group's rows, also after the group already became a conflict; a group replaced by a nearer one is outside the calculation and is not validated. Other rows outside the window are ignored. Anchors only supply end evidence: integral and durations are clipped to `[windowStart, windowEnd)`. |
| `trapezoid` | Two neighbouring readings at most `maxGapSeconds` apart give an `OBSERVED` segment, the mean of its end values times its duration; at a clipped window edge the value is interpolated linearly between the two real readings. A longer interval is `UNKNOWN` as a whole. This method never estimates: before the first and after the last reading the window is `UNKNOWN` unless an anchor exists. |
| `step_hold` | A value is valid from **its own instant** for at most `maxGapSeconds`; the validity does not restart at a window edge. Neighbours at most `maxGapSeconds` apart give an `OBSERVED` segment, the **left** value times its duration. Farther apart, the first `maxGapSeconds` from the left reading are `HELD` and the rest `UNKNOWN`. After the last reading the same holds up to `windowEnd`. Before the first reading there is nothing to hold. `HELD` is an **estimate**: no later reading confirms the value. Example: anchor 10 s before the window with value 2, next reading at 100 s, `maxGapSeconds` 30, window 0 to 60 s gives `HELD` 0 to 20 s with integral 40 (`seconds`) and `UNKNOWN` 20 to 60 s. |
| Limited source | When the source carries `READ_LIMIT_REACHED` or is `PARTIAL`, the `HELD` stretch **after the last reading** is cancelled and is `UNKNOWN`: unread data may follow, so holding would cross the observed boundary. `HELD` stretches between two readings are unaffected. An `UNKNOWN` source without a reason is estimated normally and labelled. Which end is uncovered follows from the timestamps only. This is the one place where a source reason changes a number; `metrics.cancelledTailHoldSeconds` states by how much. |
| Totals | `observedIntegral` sums `OBSERVED` segments only, `estimatedIntegral` sums `HELD` segments only, `integral` is their sum. Whether the result contains an estimate is decided by **`estimatedSeconds` greater than 0** (`containsEstimate`), never by `estimatedIntegral`: a held 0, or holds that cancel out, integrate to 0 over a non-zero estimated time. `observedSeconds`, `estimatedSeconds` and `unknownSeconds` add up to `windowSeconds`, exact to the millisecond. `timeWeightedMean` is `integral` divided by the covered time in `timeUnit`; with no covered time the three integrals and the mean are **absent**, not 0. `coverage` is covered time over window time. `scope` is always `observed_span`. |
| Published table | One row per segment, tiling the window, adjacent `UNKNOWN` stretches merged: `segmentStart`, `segmentEnd` (clipped to the window), `startValue`, `endValue` (both the held value for `step_hold`), `seconds`, `supportedDuration` (the duration in `timeUnit`), `integral`, `coverage`. On `UNKNOWN` rows the two values, `supportedDuration` and `integral` are empty. The table is always published, an all-`UNKNOWN` one included. Its descriptor has route `ce.time_weighted`, never upgrades the source's completeness, copies its reasons, and carries no subject identity, so it cannot be fed back into this mode. |
| Several series | One call integrates one series. To compare series, call once per series with the **same `timeUnit`**, unite the **output tables** with `union_rows` and a label column, then `group_metric` by the label with `sum` of `integral`, `sum` of `supportedDuration`, and `derived` `ratio` of the two. Both columns are empty on `UNKNOWN` rows, so unknown time never enters the denominator, and an all-unknown series has an empty mean, not 0. |
| Envelope | `operation` **`time_weighted`**. `status` is **`SUCCESS`** when any time is covered, also under `UNKNOWN` or `PARTIAL` completeness and also when the window holds no record and is covered from its two anchors; otherwise **`INSUFFICIENT_EVIDENCE`**. Root `reason` repeats `metrics.outcome`: **`OK`**, **`NO_READINGS`** (no reading in the window and no non-conflicting anchor) or **`NO_COVERED_SEGMENT`**. |
| `warnings` | Fixed order. (1) Always: a sentence starting with **`SCOPE_OBSERVED_SPAN`** with the covered and unknown seconds, extended with "not a window or shift total" when the source carries `READ_LIMIT_REACHED` or is `PARTIAL`. (2) Number and total seconds of unknown segments. (3) The estimate: its seconds and `estimatedIntegral` when `estimatedSeconds` is above 0, and the cancelled seconds when the hold after the last reading was cancelled. (4) Excluded rows, collapsed duplicates and conflicting instants, anchors included. |
| Numeric | Segment areas are formed from the two original readings and the time offsets, and are accumulated in value × milliseconds as **exact rationals**: sums and products are exact, an unclipped segment needs no division, and a segment clipped by a window edge keeps its elapsed time as a denominator. No term is rounded before the sum, so large segments that cancel leave their exact residual (`A`, `1`, `−A` one second apart integrate to 1 with mean 0.5 for every admitted `A`). Each published number is formed by **one** division of the exact total (50 significant digits, then binary64) and is therefore rounded **once**: `integral`, `observedIntegral`, `estimatedIntegral` and the `integral` cells after scaling to `timeUnit`; `timeWeightedMean` from the unscaled area and the covered milliseconds. Hence a clipped end value that rounds to 0 does not lose a representable area, the mean is identical in the three units, and an integral that rounds to 0 in a coarse unit still has its mean. `startValue` / `endValue` are rounded display cells and are not inputs of the area. The largest admitted values over the longest admitted window stay finite. No minimum magnitude is imposed. A mean recomposed by `group_metric` from the rounded `integral` and `supportedDuration` cells carries the rounding of those cells. A non-finite result fails the whole request with **`NUMERIC_RESULT_UNSUPPORTED`** and publishes nothing. |
| Limits | At most 100,000 readings inside the window (**`INPUT_TOO_LARGE`**); the two anchors are not counted. The §6.3 operation budget applies unchanged through publication (**`TIME_BUDGET_EXCEEDED`**, **`OPERATION_CANCELLED`**). |
| Admission | Disabled reason **`CE_TIME_WEIGHTED_DISABLED`**; the mode, `integrationMethod` and `timeUnit` then leave the schema. `maxGapSeconds` is advertised while `counter_delta` or `time_weighted` is enabled and its description names enabled modes only. |

### 6.6 Computing mode `calendar_bucket`

Labels every row of a cached table with the **local calendar day or hour** its time falls in (method
**`calendar_bucket_v1`**). It labels and aggregates nothing: aggregation is the existing `group_metric` over
`bucketLabel`. It has no demoted alias and is not a single-window series mode.

| Item | Rule |
|------|------|
| Required | `cacheId`, `timeColumn`, `timeZone`, `calendarBucket` (`day` or `hour`). Missing: **`ARGUMENT_MISSING`** (`timeColumn`: **`TIME_AXIS_MISSING`**); invalid: **`TIME_ZONE_INVALID`**, **`CALENDAR_BUCKET_INVALID`**; unknown column: **`COLUMN_NOT_FOUND`**. `valueColumn`, `entityColumn`, `windowStart` and `windowEnd` are not accepted (**`ARGUMENT_UNSUPPORTED`**): ignoring one would let the caller believe rows were filtered or partitioned. |
| `timeZone` | An IANA region id (`Europe/Berlin`) or `UTC`. **No default**, never the server zone: the zone decides which day a row belongs to. A fixed offset (`+02:00`, `GMT+2`) is refused because it carries no daylight-saving rule, and so is an id that only **names** one non-zero offset instead of a place (`Etc/GMT-2`, `Etc/GMT+5`, `SystemV/EST5`): all of them are **`TIME_ZONE_INVALID`** and publish nothing. Zero-offset aliases of UTC (`Etc/UTC`, `GMT`) and geographical ids are admitted, a place whose rules have no clock change included (`Asia/Tokyo`, `America/Phoenix`). |
| Source | Any cached table. Nothing is computed across rows, so no continuity or coverage is claimed and the §6.5 source restriction does not apply. |
| Assignment | A row is assigned by the instant in its own `timeColumn` only (DATETIME, epoch-millisecond number or ISO-8601 text). Used on an event's **start** column with `day`, `bucketLabel` is the event's start day: an event that crosses midnight counts wholly on its start day. The column is **not** duration allocated per day and **not** daily utilization; allocation across days is interval semantics of other operations. |
| Bucket | The maximal contiguous interval of instants sharing one local key: the local date for `day`; (local date, local hour, UTC offset) for `hour`. Half-open; an instant on an edge belongs to the later bucket. Every bucket contains its instant, buckets never overlap and they tile the time axis. `bucketSeconds` is the real length: a day is 82,800 or 90,000 s on switch days, an hour is below 3,600 s where a sub-hour transition cuts it (`Australia/Lord_Howe`, 1,800 s). A nonexistent local hour has no bucket; a repeated one is two buckets with different offsets. |
| Recurring date | A rollback after midnight makes a local date occur twice (`America/Goose_Bay` 1988-10-30: intervals of 86,400, 60, 7,140 and 86,400 s alternating between October 29 and 30). Each occurrence is its own interval and both carry the **same `bucketLabel`**, so grouping by label gathers all rows of that local date. One row's `bucketSeconds` is therefore **not** the label's total length. |
| Published table | Every source column in its order and type, every row in its order, then `bucketStart`, `bucketEnd` (exclusive), `bucketLabel`, `bucketSeconds`. `bucketLabel` is the ISO local date (`2026-03-29`) or the local hour with its offset (`2026-10-25T02:00+02:00`), also for a bucket whose start was clipped. A source column with one of the four names is refused with **`COLUMN_NAME_CONFLICT`**. Source rows come from the ordinary cache read, so **every source cell equals the cell an ordinary cache read returns**, LOCATION, TAGS and nested INFOTABLE cells included; the mode adds no loss and repairs none that the cache representation already has. |
| Unassigned rows | A row with an empty or unparseable time, a **non-finite numeric time** included (`NaN`, `±Infinity`; never read as epoch 0), is **kept**, with the four bucket cells empty, and counted. Rows are never dropped. `group_metric` gathers such rows in one group with an empty key, as it does for any empty key. |
| No empty buckets | A day or hour without rows does not appear; it is absent, not 0. |
| Envelope | `operation` **`calendar_bucket`**. `status` is **`SUCCESS`** when at least one row got a bucket, also under `UNKNOWN` or `PARTIAL` completeness; otherwise **`INSUFFICIENT_EVIDENCE`**. Root `reason` repeats `metrics.outcome`: **`OK`**, **`NO_ROWS`** or **`NO_ASSIGNED_ROW`**. The table is published whenever the source has rows. Its descriptor has route `ce.calendar_bucket`, never upgrades the source's completeness, copies its reasons and carries no column roles or subject identity. |
| `metrics` | `timeZone`, `calendarBucket`, `rows`, `assignedRows`, `unassignedRows`, `distinctBuckets` (intervals, by start), `distinctLabels`, `recurringLabels` (labels with more than one interval), `firstBucketStart`, `lastBucketEnd`, `nonStandardDayBuckets` (`day` only), `tzdbVersion`, `bytesMetered` (`false`: the ordinary cache read meters no bytes, so consumed bytes are not a 0-byte claim). |
| `warnings` | Fixed order, at most three. (1) Always: a sentence starting with **`SCOPE_OBSERVED_SPAN`**: a label describes the row's own instant, a bucket with rows is not thereby fully observed, a bucket without rows is absent and not 0; extended with "counts and sums per bucket are not that day's (hour's) total" when the source carries `READ_LIMIT_REACHED` or is `PARTIAL`. (2) Rows without a usable time, when any. (3) Always: the zone and bucket kind, plus the number of day buckets that are not 86,400 s and of labels covering more than one interval, when non-zero. |
| Limits | At most 100,000 rows (**`INPUT_TOO_LARGE`**), judged on the row count before any work. A bucket edge outside years 0001 to 9999 fails with **`TIMESTAMP_UNSUPPORTED`**. The §6.3 operation budget applies unchanged through publication (**`TIME_BUDGET_EXCEEDED`**, **`OPERATION_CANCELLED`**). |
| Admission | Disabled reason **`CE_CALENDAR_BUCKET_DISABLED`**; the mode, `timeZone` and `calendarBucket` then leave the schema. `timeColumn` is advertised while any mode that reads it is enabled. |

---

## Changelog (this file)

| Revision | Notes |
|----------|--------|
| 1.0.26 | **§6.6** two clarifications from the pre-merge audit, both already implied by the rules: zone ids that only name a non-zero offset (`Etc/GMT±N`, `SystemV/*`) are refused like any fixed offset, UTC aliases and places without clock changes stay admitted; a non-finite numeric time is an unusable time and leaves the row unassigned instead of becoming 1970-01-01. Bundle **`0.1.178`**. |
| 1.0.25 | **§6.6** (new) computing mode **`calendar_bucket`** (`calendar_bucket_v1`): local calendar day or hour columns per row, aggregation left to `group_metric`; required IANA `timeZone` with no default; a bucket is the maximal contiguous interval sharing one local key, with real `bucketSeconds`, offset-tagged hour labels and one label for a recurring local date; rows without a usable time are kept; source cells equal an ordinary cache read; the event-start-day reading and its limits; new codes **`TIME_ZONE_INVALID`**, **`CALENDAR_BUCKET_INVALID`**, **`COLUMN_NAME_CONFLICT`**; admission reason `CE_CALENDAR_BUCKET_DISABLED`. **§6** shell rows name the fourth computing mode. Bundle **`0.1.177`**. |
| 1.0.24 | Two numerical corrections from the pre-merge audit. **§6.4** `stddev`: the rounded mean is a centre only and the variance carries the `(Σd)²/n` correction; a small spread on a large offset was reported up to 41 % too high. **§6.5** Numeric: areas are exact rationals and each published number comes from one division of the exact total; the former per-segment 50-digit quotient lost the residual of large cancelling segments. No domain change. Bundle **`0.1.176`**. |
| 1.0.23 | **§6.5** Numeric row rewritten after implementation review: areas are accumulated unit-independently in decimal arithmetic and rounded to binary64 once; the mean is formed before unit scaling and is identical in the three units; end-value cells are display only; recomposition from rounded cells is stated. No domain change. Bundle **`0.1.175`**. |
| 1.0.22 | **§6.5** two implementation-review corrections, stated as rules: the Numeric row fixes the multiply-before-divide ordering that keeps representable integrals of very small values (no domain narrowing); the Anchors row states that the selected anchor group is magnitude-validated as a whole, independently of row order. Bundle **`0.1.174`**. |
| 1.0.21 | **§6.5** (new) computing mode **`time_weighted`** (`time_weighted_v1`): time-weighted integral and mean of one numeric property-history series; source admission by declared subject identity (**`SOURCE_CONTINUITY_UNKNOWN`**); `OBSERVED` / `HELD` / `UNKNOWN` segments summed apart; window anchors as timestamp groups; the hold after the last reading cancelled for a limited source; estimate judged by `estimatedSeconds`; new codes **`INTEGRATION_METHOD_INVALID`**, **`TIME_UNIT_INVALID`**, **`MAX_GAP_INVALID`**, **`ARGUMENT_UNSUPPORTED`**; admission reason `CE_TIME_WEIGHTED_DISABLED`. **§6** shell rows name the third computing mode. Bundle **`0.1.173`**. |
| 1.0.20 | **§6.4** rendering fix, no rule changed: the Numeric domain row and the `1.0.19` changelog row wrote the magnitude bound with bare vertical bars inside a code span. A table row is split on those bars before inline spans are parsed, so the rendered Numeric domain rule was cut off after its first words and lost the bound, `VALUE_MAGNITUDE_UNSUPPORTED`, the scaled `stddev` and `NUMERIC_RESULT_UNSUPPORTED`. Both rows now say "absolute value". The `durationWindowSeconds` range is unchanged; the implementation now handles its largest admitted value instead of failing with a generic error. Bundle **`0.1.172`**. |
| 1.0.19 | **§6.4** (new) computing mode **`rolling_stats`** (`rolling_stats_v1`): closed statistic set, stable-prefix window members, records with missing values kept, per-statistic support with `valueStatus`, numeric domain bounded at an absolute value of `1e150`, with a scaled `stddev`, `warmedUp` as a frame-availability marker that claims no coverage, exact window-work cap, admission reason `CE_ROLLING_STATS_DISABLED`. **§6.3** renames three codes to mode-neutral names shared by both computing modes: `COUNTER_TIMESTAMP_UNSUPPORTED` → **`TIMESTAMP_UNSUPPORTED`**, `COUNTER_TIME_BUDGET_EXCEEDED` → **`TIME_BUDGET_EXCEEDED`**, `COUNTER_INPUT_TOO_LARGE` → **`INPUT_TOO_LARGE`**; no aliases. Bundle **`0.1.171`**. |
| 1.0.18 | **§6.3** `counter_delta` corrections: `exact_integer` depends on readings, modulus and baseline only, not on the rate; `maxGapSeconds` is in seconds with its own 2^53 cap; rule caps are checked on the JSON literal before narrowing; new **Timestamps** row (`COUNTER_TIMESTAMP_UNSUPPORTED`) and **Operation budget** row (`COUNTER_TIME_BUDGET_EXCEEDED`, `OPERATION_CANCELLED`, budget facts on the envelope). Bundle **`0.1.170`**. |
| 1.0.17 | **§6.3** (new) computing mode **`counter_delta`** (`counter_delta_v1`): arguments, caller-supplied counter rules, reading domain, segment classifications, separate `knownDelta` / `lowerBoundDelta`, `SUCCESS` under unproven completeness with the mandatory `SCOPE_OBSERVED_SPAN` warning, root `warnings` / `completeness` on success, admission reason `CE_COUNTER_DELTA_DISABLED`. Bundle **`0.1.169`**. |
| 1.0.16 | **§5.2** (new) registers the `completeness.reasons` value **`READ_LIMIT_REACHED`**: meaning, who sets it, that it never changes the status and that its absence proves nothing; `union_rows` now carries the reasons of all inputs; tool-result fields `readLimitReached` / `readLimitNote` / `readLimitReachedSeries`. Bundle **`0.1.168`**. |
| 1.0.15 | **§5.1** `union_rows`: the `returnedRows` / `sampleOnly` / `rowsOmitted` fields on `CACHED_TABULATE_LARGE` are union-only, restoring the LARGE envelope of every other mode; the byte budget is an upper bound of the cache codec's output (JSON-escaped names, labels and values); the wall-time budget covers the whole call. Bundle **`0.1.164`**. |
| 1.0.14 | **§5.1** `union_rows`: derived `cacheId` on every non-empty result (INLINE too), registered as chart target and presentation artifact; `_EMPTY` clears the chartable target; error union gains **`UNION_TIME_BUDGET_EXCEEDED`**, and **`TABLE_TOO_LARGE_FOR_TRANSFORM`** also covers the expanded-bytes budget; non-string `labelValues` entries are **`INVALID_LABEL_VALUES`**; model schema `required` is `["mode"]`. Bundle **`0.1.163`**. |
| 1.0.13 | **§5.1** append mode **`union_rows`**: counts row (`totalAvailable` always absent, worst-of completeness, all inputs in `parentSourceCacheIds`, first input as `sourceCacheId`, success-root `sourceCacheIds` + `unionMeta`); error union gains **`MISSING_SOURCE_CACHE_IDS`**, **`TOO_MANY_UNION_INPUTS`**, **`INVALID_SOURCE_CACHE_IDS`**, **`MISSING_LABEL_COLUMN`**, **`INVALID_LABEL_VALUES`**, **`SOURCE_ROW_COUNT_UNKNOWN`**, **`LABEL_COLUMN_COLLISION`**, **`UNION_COLUMN_MISMATCH`** (`cached_tabular_tools.md` §1.3). Bundle **`0.1.162`**. |
| 1.0.12 | **§5.1** D1 distribution modes **`bin_numeric`** / **`box_summary`**: counts row, derived `cacheId` on `_INLINE`, `_EMPTY` clears the chartable `last_invoke` target; error union gains **`TOO_MANY_GROUPS`** (`cached_tabular_tools.md` §1.3). Bundle **`0.1.154`**. |
| 1.0.11 | **§6** TQJ-5: add **`rolling`**, **`rate_of_change`**, **`period_compare`** (publish vs assessment-only rules; dual-window args). Bundle **`0.1.149`**. |
| 1.0.10 | **§6** TQJ-5: generalize U4 alternate shell to **`exact_join`**, **`quality`**, **`resample`** with per-mode args/admission. Bundle **`0.1.148`**. |
| 1.0.9 | **§6** TQJ-5 Option A: **`mode=exact_join`** alternate U4 join result shell; admission withdraws enum. Bundle **`0.1.147`**. |
| 1.0.8 | **§5** BP6: public root **`completeness`** / **`counts`** on tabulate/summarize success; **`totalAvailable`** only when proven; per-mode count table. Bundle **`0.1.146`**. |
| 1.0.7 | **§1** error **`code`** union includes **`PROTECTED_TABULAR_COLUMN_BLOCKED`** (PASSWORD-typed columns — **`tabulate_cached_result`** / **`summarize_cached_result`** stage 4; **`docs/agent/protection.md` §4.7). Bundle **`0.1.43`**. |
| 1.0.6 | **§1** P2 mirror: **`CACHE_MISS`** MUST prune mirror when it still pointed at the missed id; **§2** **`summarize_cached_result`** success MUST update mirror from **`sourceCacheId`** without advancing per-turn **`last_invoke`** snapshot (Further Insight **#36**). |
| 1.0.5 | **§2** `sourceCacheId`: document **`__single_turn__` + `request_id`** mirror key vs persistent **`conversation_id`**; **`ClearConversation`** clears mirror (Further Insight **#35**). |
| 1.0.4 | **§2** `sourceCacheId`: P2 **`TOKEN`** resolution order — per-turn then conversation-scoped snapshot (Further Insight **#34**). |
| 1.0.3 | **§2** `sourceCacheId`: clarify **effective** cache id after P2 sentinel resolution (Further Insight **#33**). |
| 1.0.2 | L7 link line: **"Non-normative …"** → **"Implementation-side reference (outside contract bundle)"** — aligns with **§Scope** L4 wording (Claude **#15** P3 polish). |
| 1.0.1 | Clarify **§Scope**: common root / error / `insightEnvelope` / LARGE principle only; tool-specific arguments and code catalogs stay in **`cached_tabular_tools.md`**. |
| 1.0.0 | Initial normative registration (Further Insight P1 surface); bundle **`0.1.3`**. |
