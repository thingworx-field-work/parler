# Cached table decision tools

Status: **implemented** — `filter_count`, `filter_rows`, `filter_sort_topn` (predicate engine), `group_metric` with the extended measures (`count_distinct`, `weighted_avg`, `median`, `percentile`, `variance`, `stddev`, `first`, `last`, `mode`; `INVALID_WEIGHT` for negative weights; `first`/`last` default `direction` **asc** on `orderBy`), and the `bin_numeric` / `box_summary` distribution modes. `groupBy` accepts plain column names only. Agent JSON details: `docs/agent/cached_tabular_tools.md`.
Scope: deterministic decision operations over conversation-cached `InfoTable` results.

**Filter/sort wire grammar:** canonical JSON = **`docs/agent/query-spec.md`** (Tier 1 shipped @ extension **0.1.138**). Legacy `{column, op, value}`, root `where` / `sort`, composite `all` / `any` / `not`, and measure-level `where` are **rejected** at runtime (`CachedTabulateLegacyKeyRejector`). This doc explains decision semantics; wire shapes defer to query-spec §3–§7.

## 1. Problem

Large-table replay control prevents cached pages from flooding LLM context. That makes the system stable, but it also
exposes the next gap: the agent needs deterministic operations over the full cached table.

Representative questions:

- "Which machines have utilization below 30%?"
- "Which machines have utilization below 30% but not 0%?"
- "Which machines have positive utilization but below 30%?"
- "What is the asset with positive but lowest utilization?"
- "List top 3 machines by idle hours."
- "Which states account for more than 20% of duration?"
- "Show OEE by machine."
- "Show median repair time by machine."
- "Which machine has the most variable cycle time?"
- "Use the earlier table and show only rows where status contains `Down`."

If the only available path is `fetch_cached_result`, the model may page through raw rows and compare values in natural
language. That is expensive, fragile, and eventually hits local/provider token limits.

Parler already has:

- `fetch_cached_result`: page browsing over a cached table.
- `tabulate_cached_result`: deterministic `sort_topn`, `group_count`, `group_aggregate`.
- `summarize_cached_result`: column statistics.

This topic adds a deterministic cached-table decision layer. The goal is not SQL. The goal is to cover common public
filter/ranking/threshold/aggregate operators and the small derived-metric family needed by industrial table questions.

## 2. Design Principles

1. **Exact over full cache.** Decisions run over the full cached table, not the sample visible to the LLM.
2. **Small evidence payload.** Return counts, predicates, compact result samples, and new `cacheId`s. Do not send all rows
   to the LLM.
3. **General operators, no domain hardcode.** Utilization and OEE are motivating cases, but the executor must not know
   "SCPA utilization" semantics by name.
4. **Explicit predicates.** If the user says "below 30 but not zero", the predicate must encode `> 0 AND < 30`. Do not
   rely on implicit assumptions such as "utilization cannot be negative".
5. **Stable failure.** If exact computation requires unsupported interval algebra, cross-cache joins, or arbitrary
   formulas, return a structured error. Do not let the model guess from samples.
6. **UI and LLM lanes stay separate.** The full filtered/derived table can be cached and rendered/downloaded by the UI,
   while the LLM receives compact evidence.
7. **Cached-population honesty.** Results are about the rows/groups present in the cached table. If a machine never
   appears in the source cache, the tool cannot prove "zero events" for that machine without a separate universe table.

## 3. Tool Shape

Preferred implementation: extend `tabulate_cached_result` with decision-oriented modes.

Reasoning:

- It is already the deterministic cached-table transform tool.
- It already accepts `cacheId` and returns transformed table metadata.
- Keeping `fetch_cached_result`, `summarize_cached_result`, and `tabulate_cached_result` as the three cached-table tools
  avoids another similarly named built-in.

If implementation review finds the argument schema too broad for `tabulate_cached_result`, the fallback is a new
`decide_cached_table` tool with the same JSON schema. The semantics below remain the source of truth either way.

New modes:

| Mode | Purpose |
| --- | --- |
| `filter_count` | Count rows matching a predicate; optional group count. |
| `filter_rows` | Produce a filtered table and return inline/sample evidence. |
| `filter_sort_topn` | Filter first, then sort and return top/bottom/Nth rows. |
| `group_metric` | Group rows, compute measures/derived metrics, then optionally filter/sort groups. |

`filter_count`, `filter_rows`, and `filter_sort_topn` operate over existing columns. `group_metric` covers questions where
the target value is computed from multiple rows, such as utilization percent, OEE-style products, percentiles, variance,
and "latest per machine".

## 4. Predicate Schema

Predicates use the canonical filter object from **`docs/agent/query-spec.md`** §3. They apply to:

- root **`filters`** on source rows (`filter_count`, `filter_rows`, `filter_sort_topn`, optional `group_metric` source filter);
- per-measure **`filters`** inside `measures[]` (conditional aggregates);
- post-aggregation **`having`** on grouped output (`group_metric`).

`fieldName` may reference:

- a source column for row filters;
- a group output column;
- a measure name;
- a derived metric name.

Legacy root `where`, measure `where`, leaf `op` / `column`, and composite `all` / `any` / `not` are **rejected** — see query-spec §7.1.

### 4.1 Leaf Predicate

```json
{
  "type": "LT",
  "fieldName": "utilizationPercent",
  "value": 30
}
```

### 4.2 Boolean Composition

```json
{
  "type": "AND",
  "filters": [
    {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
    {"type": "LT", "fieldName": "utilizationPercent", "value": 30}
  ]
}
```

Supported composition:

- `AND`: logical AND.
- `OR`: logical OR.
- `NOT`: logical NOT over one child predicate (`filters` array length 1).

Limits (`ParlerQueryFilterParser`):

- Maximum composite depth: **4**.
- Maximum leaf predicates: **32**.

These caps cover normal user requests without turning the tool into a general query language.

### 4.3 Range Predicates

Canonical **`BETWEEN`** is **inclusive on both ends** (`from` / `to` per query-spec §2). For exclusive intervals, use **`AND(GT, LT)`** — do not map exclusive legacy `between` with `includeLow` / `includeHigh` flags to `BETWEEN`.

Inclusive example ("between 10 and 30", default inclusive):

```json
{
  "type": "BETWEEN",
  "fieldName": "utilizationPercent",
  "from": 10,
  "to": 30
}
```

Exclusive example ("strictly between 0 and 30" / "positive but below 30"):

```json
{
  "type": "AND",
  "filters": [
    {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
    {"type": "LT", "fieldName": "utilizationPercent", "value": 30}
  ]
}
```

Rules:

- "Below 30%" includes `0` unless the user says positive / non-zero / not 0.
- "Below 30 but not zero" means `> 0 AND < 30`.
- "Positive but below 30" means `> 0 AND < 30`.
- "Between 10 and 30" defaults inclusive (`BETWEEN` `from` / `to`).
- "Strictly between" means both endpoints exclusive (`AND(GT, LT)`).
- "At least 30" means `GE`; "at most 30" means `LE`.

## 5. Predicate Operator Catalog

Leaf filter **`type`** values are **UPPERCASE** ThingWorx-aligned names per **`query-spec.md`** §3.3. Legacy lowercase `op` strings (`lt`, `eq`, `between`, …) are **not** accepted on the wire. Measure / derived aggregator names (`sum`, `count`, `ratio_percent`, …) stay lowercase **`op`** — they are not predicate operators.

| `type` | Column types | Required keys | Meaning |
| --- | --- | --- | --- |
| `LT`, `LE`, `GT`, `GE` | numeric, datetime | `value` | Ordered comparison (`LE` / `GE`, not LTE / GTE). |
| `EQ`, `NE` | scalar, boolean | `value` | Equality / inequality. |
| `BETWEEN`, `NOTBETWEEN` | numeric, datetime | `from`, `to` | Inclusive range only. |
| `IN`, `NOTIN` | scalar, boolean | `values` | Set membership. `values` cap: 100. |
| `MISSINGVALUE`, `NOTMISSINGVALUE` | all | none | Null check. |
| `ISEMPTY`, `NOTEMPTY` | string/text | none | Empty-string check (query-spec §3.9). |
| `CONTAINS`, `NOTCONTAINS`, `STARTSWITH`, `NOTSTARTSWITH`, `ENDSWITH`, `NOTENDSWITH` | string/text | `value` | String patterns per query-spec §3. |
| `LIKE`, `NOTLIKE` | string/text | `value` | TWX `LIKE` semantics: `*` any run (alias `%`); `_` is **literal**; `?` single-char wildcard (differs from pre-0.1.138 decision-doc drafts that treated `_` as single-char). |

String leaves accept optional **`isCaseSensitive`** (defaults: filter **false**, sort **true** per query-spec §3.5). There is no canonical `trim` key.

Regex is **not** in V1. Java regex has reliability hazards, and the immediate utilization/reporting prompts are covered
by `CONTAINS`, prefix/suffix, and `LIKE`.

## 6. Modes

### 6.1 `filter_count`

Input:

```json
{
  "cacheId": "...",
  "mode": "filter_count",
  "filters": {
    "type": "AND",
    "filters": [
      {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
      {"type": "LT", "fieldName": "utilizationPercent", "value": 30}
    ]
  },
  "groupBy": "machine"
}
```

`groupBy` is optional.

Success without `groupBy`:

```json
{
  "status": "success",
  "sourceCacheId": "...",
  "resultKind": "CACHED_FILTER_COUNT",
  "rowCount": 545,
  "matchCount": 17,
  "filters": {"type": "LT", "fieldName": "utilizationPercent", "value": 30},
  "sampleRows": [...]
}
```

Success with `groupBy`:

```json
{
  "status": "success",
  "sourceCacheId": "...",
  "resultKind": "CACHED_FILTER_GROUP_COUNT",
  "rowCount": 545,
  "matchCount": 17,
  "groupBy": "machine",
  "groups": [
    {"key": "MachineA", "matchCount": 4},
    {"key": "MachineB", "matchCount": 3}
  ]
}
```

Group output is sorted by `matchCount desc`, then key asc. Cap groups at 500.

### 6.2 `filter_rows`

Input:

```json
{
  "cacheId": "...",
  "mode": "filter_rows",
  "filters": {"type": "GT", "fieldName": "utilizationPercent", "value": 60},
  "maxItems": 50,
  "offset": 0
}
```

Behavior:

- Evaluates predicate over the full cached table.
- Creates a new cached table containing matching rows.
- Returns inline rows only when output is small.
- Returns `cacheId` + sample when output is large.

Large output:

```json
{
  "status": "success",
  "sourceCacheId": "...",
  "resultKind": "CACHED_FILTER_ROWS_LARGE",
  "rowCount": 545,
  "matchCount": 240,
  "cacheId": "...new filtered cache...",
  "columns": [...],
  "sampleRows": [...]
}
```

### 6.3 `filter_sort_topn`

Input:

```json
{
  "cacheId": "...",
  "mode": "filter_sort_topn",
  "filters": {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
  "sorts": [
    {"fieldName": "utilizationPercent", "isAscending": true},
    {"fieldName": "EquipmentID", "isAscending": true}
  ],
  "offset": 0,
  "maxItems": 5
}
```

Use this for:

- "lowest positive utilization"
- "top 5 by idle hours"
- "4th most utilized" (`offset=3`, `maxItems=1`)
- "maximum / minimum after excluding zero"

Rules:

- `filters` is optional. If omitted, `matchCount == rowCount`.
- `sorts` accepts 1 to 3 keys (query-spec §4).
- Legacy root `sort`, `sortBy`, `direction`, and `limit` are **rejected** — use `sorts` / `maxItems`.
- Ties are broken by ascending original row index after all explicit sort keys.
- `maxItems` defaults to 50 and is capped at 500.
- `offset` defaults to 0 and must be non-negative.

### 6.4 `group_metric`

Use this when the user asks for a decision over a metric that must be computed from multiple rows.

Example: utilization percent from event rows.

```json
{
  "cacheId": "...",
  "mode": "group_metric",
  "groupBy": [
    "EquipmentID",
    "EquipmentDesc"
  ],
  "measures": [
    {
      "name": "totalDuration",
      "op": "sum",
      "column": "Duration"
    },
    {
      "name": "runningDuration",
      "op": "sum",
      "column": "Duration",
      "filters": {"type": "EQ", "fieldName": "UtilizationState", "value": "Running", "isCaseSensitive": false}
    }
  ],
  "derived": [
    {
      "name": "utilizationPercent",
      "op": "ratio_percent",
      "numerator": "runningDuration",
      "denominator": "totalDuration"
    }
  ],
  "having": {
    "type": "AND",
    "filters": [
      {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
      {"type": "LT", "fieldName": "utilizationPercent", "value": 30}
    ]
  },
  "sorts": [
    {"fieldName": "utilizationPercent", "isAscending": true}
  ],
  "maxItems": 100
}
```

`groupBy` entries are plain column names. Shift calendars are not inferred; use a `ShiftID` column or a domain tool when
shift semantics matter.

Every measure op supports optional **`filters`**. This is required for conditional counts:

```json
{
  "name": "downEventCount",
  "op": "count",
  "filters": {"type": "IN", "fieldName": "UtilizationState", "values": ["Down", "Unavailable"]}
}
```

When **`filters`** is present on any measure, it pre-filters rows inside each group before the aggregation step. For `count`,
`count(filters: P)` returns the number of rows in that group satisfying `P`.

`groupBy` may be omitted or `[]` to compute one global group. The output shape is the same as grouped output, with
`groupCount=1` and no group-key columns. **Empty source:** when `groupBy` is empty and the cached table has **zero**
rows, the executor still emits **one** output row (e.g. `count` measures are `0`); `groupCount` is **1**.

**Implemented measure ops (`group_metric`, agent Stages 2–3):**

| Measure op | Required fields | Meaning |
| --- | --- | --- |
| `count` | optional `column` | Count rows. If `column` is omitted, count all rows in the group. If `column` is present, it must exist; rows where that cell is null are excluded from the count. |
| `count_non_null` | `column` | Count rows where the column is non-null. Column must exist and must be a scalar type suitable for tabular predicates (not JSON / INFOTABLE / TAGS / IMAGE / BLOB). |
| `sum` | `column` | Numeric sum. Column must exist and be numeric **or** a STRING/TEXT column whose **non-null** cells **all** parse as numbers (design §8). **Without** measure-level **`filters`**: empty contributing set (no usable numeric cells) → JSON **`null`**. **With** measure-level **`filters`**: if the group has at least one source row but **no** row passes the measure filter, the sum is **`0`** (conditional sum over an empty subset — Bug 005, Option G). If one or more rows pass the filter but every matching cell is null / non-numeric for aggregation, the result is **`null`**. |
| `avg` | `column` | Numeric average; same typing rules as `sum`. Empty contributing set → **`null`**. |
| `min` | `column` | Numeric minimum; same typing rules as `sum`. Empty contributing set → **`null`**. |
| `max` | `column` | Numeric maximum; same typing rules as `sum`. Empty contributing set → **`null`**. |
| `count_distinct` | `column` | Count **distinct** non-null scalar values per group (null cells excluded). Uses the same string keying as stable sorts. **Cap:** more than **10,000** distinct values in one group → **`INVALID_PARAMETERS`**. |
| `weighted_avg` | `column`, `weightColumn` | `sum(value * weight) / sum(weight)` over rows passing the measure **`filters`**. Skips rows with null **weight**. Skips rows with null **value**. **`INVALID_WEIGHT`** if any weight is **strictly negative**. Returns **`null`** when total weight is zero. `weightColumn` follows the same numeric / coercible-STRING rules as `sum`. |
| `median` | `column` | Median of numeric samples (same linear interpolation on sorted values as `summarize_cached_result` / `CachedTabularToolsExecutor` percentiles). Empty set → **`null`**. |
| `percentile` | `column`, `p` | `p` is a number in **`[0,100]`** (inclusive). Uses the same sorted-array linear interpolation as other cached numeric summaries. Empty set → **`null`**. |
| `variance` | `column` | **Population** variance (divide by N). Empty set → **`null`**. |
| `stddev` | `column` | Population standard deviation (sqrt of population variance). Empty set → **`null`**. |
| `first` | `column`, `orderBy`, optional `direction` | Value of `column` from the first row in the group after filtering by measure **`filters`**, ordered by `orderBy`. `direction` defaults **`asc`** when omitted or blank; `asc` / `desc` / `ascending` / `descending`. **Nulls last** on `orderBy` regardless of direction. `orderBy` must be a **sortable scalar** column (same family as `filter_sort_topn` sort keys). **`column`** must be **numeric**, **DATETIME** (output as epoch millis as a JSON number), or **BOOLEAN** (0/1). |
| `last` | `column`, `orderBy`, optional `direction` | Same as `first`, but the **last** row in that ordering. |
| `mode` | `column` | Most frequent **non-null** value among **numeric**, **BOOLEAN** (0/1), or **DATETIME** (millis) samples; ties broken by **ascending** value order. All-null group → **`null`**. **STRING** / **TEXT** columns are rejected at validate time (**`TYPE_MISMATCH`**); numeric-parseable string columns are not supported for `mode`. |

Unknown measure `op` values or unknown measure `column` names fail with **`INVALID_PARAMETERS`** / **`INVALID_COLUMN`** at validation time (no silent `0` / `NaN`).

**Measure details:**

- `percentile` uses the same linear interpolation convention as existing cached numeric summaries.
- `variance` / `stddev` are population metrics, not sample metrics.
- `weighted_avg` skips rows whose weight is null. It returns null when the total weight is zero or null. Negative weights
  are rejected with `INVALID_WEIGHT`.
- `first` / `last` default `direction` to `asc`; explicit `direction` accepts `asc` or `desc`. Rows with null `orderBy`
  values sort after non-null values regardless of direction (`NULLS LAST`).
- `mode` returns null when every value in the group is null.

**Group keys:** Unsupported complex base types match the predicate column rule (JSON / INFOTABLE / TAGS / IMAGE / BLOB /
HYPERLINK / LOCATION / VEC2–4 / NOTHING / etc. — see **`CachedTabularDecisionPredicate.isUnsupportedComplexBaseType`**).
Rejected with **`UNSUPPORTED_COLUMN_TYPE`**. Group identity is **value-based** (Java **`null`** cells are distinct from
empty strings and from delimiter collisions). Scalar group keys are written to the output `InfoTable` with **matching
base types** (NUMBER / INTEGER / LONG / DATETIME / BOOLEAN / STRING-like), not stringified into mismatched types.

**Output column names:** `groupBy` column names, every measure **`name`**, and every derived **`name`** share one
namespace; duplicates fail validation with **`INVALID_PARAMETERS`**.

Supported derived ops:

| Derived op | Required fields | Meaning |
| --- | --- | --- |
| `ratio` | `numerator`, `denominator` | `numerator / denominator`; null when denominator is zero/null. |
| `ratio_percent` | `numerator`, `denominator` | `100 * numerator / denominator`; null when denominator is zero/null. |
| `difference` | `left`, `right` | `left - right`. If **either** operand is percent-scaled, **both** must be (same
  **`DERIVED_SCALE_MISMATCH`** family as mixed-scale `sum_values`); when both are percent-scaled, the result is tracked
  as **percent-scaled** for downstream validation. |
| `sum_values` | `inputs` | Sum measure/derived values (null **resolved** inputs skipped). **Every** `inputs[]` element must be a **non-blank string** naming a measure or earlier derived value (`INVALID_PARAMETERS` otherwise — no silent ignore of numbers/objects). **All** referenced inputs must share the
  same scale class: either **all** percent-scaled (`ratio_percent`, `scale(ratio,100)`, or a prior derived already marked
  percent) **or** **none** percent-scaled — mixing percent with fraction/raw (`DERIVED_SCALE_MISMATCH`). When all inputs
  are percent-scaled, the **`sum_values` output** is tracked as **percent-scaled**. |
| `multiply` | `inputs` | Product of measure/derived values. **Every** `inputs[]` element must be a **non-blank string** name (`INVALID_PARAMETERS` otherwise). **`multiply` rejects if any input is percent-scaled**
  (`ratio_percent`, `scale(ratio,100)`, **`sum_values` whose result is percent-scaled**, or **`difference` of two
  percents**). |
| `scale` | `input`, `factor` | `input * factor`. When `input` is a **`ratio`** (`0..1`) and `factor` is **100**, the result is tracked as **percent-scaled** for later `multiply` validation (same class as `ratio_percent`). |
| `percent_of_total` | `input` | `100 * measure / sum(measure across all grouped output rows)`. `input` must name a **measure** (not another derived). Zero total sum → **`ZERO_DENOMINATOR`**. |
| `percent_of_group` | `input`, `groupBy` | `100 * measure / sum(measure within the partition)`. `input` must name a **measure**. Derived-level **`groupBy`** is a non-empty array that is a **subset of the mode-level `groupBy` columns** (partition keys). All-null / zero partition sum → **`ZERO_DENOMINATOR`**. |

Derived inputs may reference measure names or previously declared derived names only. They do not reference source
columns; lift source columns into per-group values with a measure first. Chaining is allowed with a **maximum depth of 3**
(counted from measures). `sum_values` / `multiply` accept at most **10** entries in `inputs`. This supports OEE-style metrics without arbitrary formulas:

```json
{
  "derived": [
    {"name": "availability", "op": "ratio", "numerator": "runningTime", "denominator": "plannedTime"},
    {"name": "performance", "op": "ratio", "numerator": "idealOutput", "denominator": "actualOutput"},
    {"name": "quality", "op": "ratio", "numerator": "goodOutput", "denominator": "actualOutput"},
    {"name": "oee", "op": "multiply", "inputs": ["availability", "performance", "quality"]},
    {"name": "oeePercent", "op": "scale", "input": "oee", "factor": 100}
  ]
}
```

OEE-style multi-factor products require careful scale management:

- Use `ratio` (`0..1`) for each component factor.
- Use `multiply` over those fraction components.
- Apply `scale(100)` at the end to produce a percent.
- Do **not** multiply percent-scaled components directly: **`multiply` rejects percent-scaled inputs**, including
  `ratio_percent`, values produced by `scale(ratio, 100)`, **`sum_values` results that inherited the percent scale**,
  and **`difference` when both sides are percent-scaled**. Summing **only** percent metrics with `sum_values` remains
  valid, but **`multiply` must not** be applied to that sum without first rescaling (e.g. back to fractions).

The executor tracks a simple **scale class** on measure and derived outputs for `multiply` / mixed-operand validation.
If `multiply` receives a percent-scaled input, return **`DERIVED_SCALE_MISMATCH`** instead of producing a silently
scaled-out value such as `750000`.

**Numeric undefined values:** grouped measure / derived cells that are mathematically undefined use JSON **`null`**
(not IEEE `NaN` string or non-JSON numbers). Predicates treat null numeric cells as non-matching for ordered comparisons;
output `sorts` uses **nulls last** for numeric keys.

`LIKE` / `NOTLIKE` matching is implemented with a **tokenizing + iterative DP** matcher (pattern length, cell length,
and step budgets); no unbounded recursion.

`group_metric` output:

```json
{
  "status": "success",
  "sourceCacheId": "...",
  "resultKind": "CACHED_GROUP_METRIC",
  "rowCount": 554,
  "groupCount": 172,
  "matchCount": 9,
  "groupBy": ["EquipmentID", "EquipmentDesc"],
  "measures": ["totalDuration", "runningDuration"],
  "derived": ["utilizationPercent"],
  "having": {
    "type": "AND",
    "filters": [
      {"type": "GT", "fieldName": "utilizationPercent", "value": 0},
      {"type": "LT", "fieldName": "utilizationPercent", "value": 30}
    ]
  },
  "cacheId": "...new grouped-result cache...",
  "sampleRows": [
    {
      "EquipmentID": "SE.CellFab.Model.Workunit.AC-BenchScale-01",
      "EquipmentDesc": "AC BenchScale 01",
      "totalDuration": 86400,
      "runningDuration": 12000,
      "utilizationPercent": 13.89
    }
  ]
}
```

Multi-key group output should keep one output column per group key. Do not collapse multi-key groups into a composite
string. If a `groups[].key` field is emitted for summary metadata, it should be a JSON object:

```json
{"EquipmentID": "...", "EquipmentDesc": "..."}
```

The new grouped-result cache is important: UI tables, charts, downloads, and later user follow-ups should use the compact
derived table, not the original event table.

### 6.5 `bin_numeric` and 6.6 `box_summary` (D1 distribution operators)

Decision-class modes (same `MAX_SCANNED_ROWS` / cell budgets and the shared root `filters`) that turn a
numeric column into a self-describing fixed-column table for the distribution charts of
`docs/agent/nearterm/chart-enhancement.md` §7.4. Parameters, output columns, methods and result kinds are
catalogued in [`cached_tabular_tools.md`](./cached_tabular_tools.md) §1.1–§1.3. Two rules distinguish them from
the modes above: the `_INLINE` result always stores its output as a derived cache (so the chart builder's
`last_invoke` and `cache_id` both reach the same table), and the `_EMPTY` result clears the chartable
`last_invoke` target so an empty distribution never charts the previous table. Method identifiers
(`explicit_edges_v1`, `equal_width_v1`, `tukey_1_5_iqr_linear_p_v1`) are persisted with every result.

## 7. Caps, Defaults, and Ordering

| Setting | Value |
| --- | --- |
| `sampleRows` cap | 20 for every decision-mode output |
| `filter_rows.maxItems` default / cap | 50 / 500 |
| `filter_sort_topn.maxItems` default / cap | 50 / 500 |
| `group_metric.maxItems` default / cap | 50 / 500 |
| `group_metric` max groups scanned/emitted | 5,000 scanned, 500 emitted |
| `groupBy` key cap | 5 |
| `sorts` key cap | 3 |
| `measures` cap | 10 |
| `derived` cap | 10 |
| `derived` chain depth cap | 3 |
| `sum_values` / `multiply` input list cap | 10 |
| `IN` / `NOTIN` values cap | 100 |
| Boolean predicate cap | depth **4**, **32** leaves (`ParlerQueryFilterParser`) |
| `MAX_SCANNED_ROWS` per decision call | 100,000 |

Default ordering:

- `filter_count` with `groupBy`: `matchCount desc`, then key asc.
- `filter_rows`: original row order.
- `filter_sort_topn`: explicit `sorts`, then original row index asc.
- `group_metric` without `sorts`: group keys ascending with **type-aware** comparison on declared column base types
  (numeric / datetime / boolean vs lexicographic string-like), nulls ordered like predicate-style nulls-last for
  ordered primitives where applicable.
- `group_metric` with `sorts`: explicit `sorts`, then **type-aware group keys** (same comparator family as the no-`sorts`
  path), then stable **grouped-output row index** asc as an absolute tie-break.

## 8. Type Handling

Use `InfoTable` DataShape column metadata when available.

Numeric comparison:

- Accept `INTEGER`, `LONG`, `NUMBER`.
- Parse numeric strings only if the column base type is numeric or all observed non-null values parse as numbers.
- Numeric string parsing uses `Locale.ROOT` conventions: `.` decimal separator, no thousands separators, no comma decimal.
- Reject ambiguous mixed values with `TYPE_MISMATCH`; do not compare lexicographically.

Boolean comparison:

- Accept `BOOLEAN` columns for `EQ`, `NE`, `IN`, and `NOTIN`.
- Predicate values should be JSON booleans. String `"true"` / `"false"` may be accepted case-insensitively.
- Do not coerce numeric `0` / `1` into booleans in V1.

Datetime comparison:

- Accept `DATETIME`.
- Predicate values must be ISO-8601 strings.
- Date-only or relative user phrases must be resolved before calling the tool.

Percent handling:

- Do not silently convert between `0..1` and `0..100`.
- Values computed by `ratio_percent` always use `0..100`.
- Raw columns are compared as stored unless ambiguous.
- Trigger `AMBIGUOUS_PERCENT_SCALE` when all are true:
  1. metadata does not explicitly define percent scale;
  2. every observed non-null numeric value is between `0` and `1`;
  3. the predicate threshold is greater than `1`.

String comparison:

- Use Unicode string values as received.
- Default case-insensitive pattern matching uses locale-stable lowercasing.
- No fuzzy matching.
- Keep original values in output.

Null/empty:

- `MISSINGVALUE` checks Java/null JSON null.
- `ISEMPTY` treats null and trimmed empty string as empty (query-spec §3.9).
- Numeric zero is not empty.

Complex values:

- `INFOTABLE`, JSON/list/object-valued cells, tags/list-valued cells, and nested values are unsupported for V1 predicates,
  grouping, measures, and sorting.
- Return `UNSUPPORTED_COLUMN_TYPE` rather than stringifying nested data.

Protected columns:

- If a predicate, group key, measure, derived input, sort column, or output projection references a
  PASSWORD column, return `PROTECTED_TABULAR_COLUMN_BLOCKED`.
- If output includes PASSWORD columns, preserve existing cached-tabular masking/omission policy.

## 9. Error Codes

Recommended structured error codes:

| Code | Meaning |
| --- | --- |
| `CACHE_MISS` | Source cache id unavailable. |
| `INVALID_MODE` | Unknown mode. |
| `INVALID_COLUMN` | Referenced column does not exist. |
| `INVALID_PREDICATE` | Predicate shape is invalid. |
| `TOO_MANY_PREDICATES` | Boolean predicate exceeds cap. |
| `UNSUPPORTED_OPERATOR` | Operator is not supported for this mode/type. |
| `TYPE_MISMATCH` | Operator cannot be applied to the column type. |
| `UNSUPPORTED_COLUMN_TYPE` | Column type is complex/nested or otherwise unsupported. |
| `AMBIGUOUS_PERCENT_SCALE` | Percent threshold cannot be interpreted safely. |
| `UNSUPPORTED_DERIVED_METRIC` | Requested metric cannot be computed with supported derived ops. |
| `INVALID_DERIVED_INPUT` | Derived op references an unknown source, a raw source column, a later derived value, or too many inputs. |
| `DERIVED_CYCLE` | Derived metric references create a cycle. |
| `DERIVED_SCALE_MISMATCH` | Known percent-scaled derived values are used in a product where fraction inputs are required. |
| `INVALID_WEIGHT` | `weighted_avg` receives negative weights or otherwise invalid weight values. |
| `CARDINALITY_TOO_HIGH` | Distinct groups or distinct values exceed cap. |
| `SOURCE_TOO_LARGE` | Source table row count exceeds `MAX_SCANNED_ROWS` for this decision mode. |
| `LIMIT_OUT_OF_RANGE` | `maxItems` / `offset` outside allowed range for modes that use those fields (e.g. `sort_topn`, `filter_rows`, `filter_sort_topn`, grouped output `maxItems`/`offset`). Does **not** apply to **`group_metric`** **`percentile`** measure **`p`** — use **`INVALID_PARAMETERS`** when **`p`** is missing, non-numeric, NaN, or outside **`[0,100]`**. |
| `PROTECTED_TABULAR_COLUMN_BLOCKED` | PASSWORD-protected column referenced. |

Derived division by zero yields null, not an error.

## 10. Routing Guidance

Model-facing rules:

- If the user asks for threshold, below/above, less than, greater than, matching rows, percent cutoff, "which rows meet
  condition", "but not zero", top/bottom/Nth, median, percentile, variability, or per-group KPI over a cached table, use
  cached-table decision modes. Do not page with `fetch_cached_result`.
- If the target value is an existing column, use `filter_count`, `filter_rows`, or `filter_sort_topn`.
- If the target value must be computed per group, use `group_metric`.
- If the relevant table is not yet cached, first call the domain tool that produces it, then run the cached-table decision.
- For "Nth most/least", use `filter_sort_topn` with `offset=N-1`, `maxItems=1`.
- For "machines with no Down events", use `group_metric` with conditional `count` and `having count == 0`, but only over
  groups present in the cached table.
- For OEE / multi-factor percent KPIs, use `ratio` for each factor and apply `scale(100)` at the end. Do not multiply
  `ratio_percent` components directly.
- For pattern matching, use `CONTAINS`, `STARTSWITH`, `ENDSWITH`, or `LIKE` (query-spec §3). V1 does not support regex; if the user
  supplies regex-like syntax, translate it to `LIKE` / `CONTAINS` only when the translation is obvious, otherwise return
  `UNSUPPORTED_OPERATOR`.
- When `AMBIGUOUS_PERCENT_SCALE` is returned, ask the model/user to choose a `0..1` fraction threshold or compute a
  `ratio_percent` derived column, which is always `0..100`.
- If exact computation is unsupported, return/describe the structured unsupported reason. Do not infer from samples.

Skill authors should be able to say:

```text
For threshold, ranking, "which assets match", or per-machine KPI questions over utilization records, use cached-table
decision modes over the full cache. For "positive but below 30%" use an explicit > 0 and < 30 predicate. Do not page
cached rows into the LLM.
```

## 11. Relationship to Playbook

Playbook should reuse these operations without depending on LLM reasoning between deterministic steps.

Playbooks call `tabulate_cached_result` decision modes as ordinary built-in tools, so skills, normal chat, and
playbooks share one executor.

Important playbook behavior:

- A playbook table node should expose its full table/cache handle to a later decision node.
- The final LLM summary should receive the compact decision result, not the raw event table.
- The compact decision result should be chart/table/download friendly through the new result cache.

## 12. UI / Chart / Export Expectations

Decision outputs should be usable by the UI without asking the LLM to rewrite rows:

- Small result: inline rows can render directly.
- Large result: return a new `cacheId`, `sampleRows`, `totalRows` / `matchCount`, and columns.
- Table path: render the filtered/derived result table, not the original raw event table.
- Chart path: chart tools should prefer the derived/filtered result cache over the original raw event cache.
- Download path: CSV export should use the derived/filtered cache where available.
- Final response: LLM should cite `rowCount`, `matchCount`, `filters` / `having`, and key sample/aggregate rows.

## 13. Tests

Unit / golden tests:

- `filter_count` numeric `< 30`.
- `filter_count` numeric `> 0 AND < 30` (`AND(GT, LT)`).
- exclusive range: `AND(GT, LT)` for `(0, 30)`.
- inclusive range: `BETWEEN` `from` / `to` for `[0, 30]`.
- `IN` / `NOTIN`.
- boolean `EQ` / `NE`.
- `MISSINGVALUE`, `ISEMPTY`, and numeric zero not treated as empty.
- `CONTAINS`, `NOTCONTAINS`, `STARTSWITH`, `ENDSWITH`, `LIKE`.
- `LIKE` escape sequences per query-spec §3.
- Boolean `AND`, `OR`, and `NOT`.
- Boolean cap rejection.
- `filter_rows` small inline.
- `filter_rows` large with new cache id.
- `filter_sort_topn` with pre-filter.
- `filter_sort_topn` Nth item using `offset`.
- stable sort tie-break.
- `group_metric` utilization percent: `sum(Duration filters: Running) / sum(Duration) * 100`.
- `group_metric` positive but below 30.
- `group_metric` lowest positive utilization.
- `group_metric` top 5 by idle hours.
- `group_metric` conditional count with `having count == 0`.
- `group_metric` percentile / median.
- `group_metric` stddev / variance.
- `group_metric` first / last by timestamp.
- `group_metric` mode.
- `group_metric` weighted average.
- `group_metric` weighted average with null weight rows skipped.
- `group_metric` weighted average with all-zero total weight returns null.
- `group_metric` weighted average with negative weight rejected.
- `group_metric` chained derived OEE-style multiply.
- `group_metric` rejects `multiply` over `ratio_percent` inputs with `DERIVED_SCALE_MISMATCH`.
- `group_metric` with no `groupBy` produces one global group.
- multi-key group output columns.
- `first` / `last` with null `orderBy` values sorts nulls last.
- `mode` with all-null group returns null.
- sampleRows cap.
- `MAX_SCANNED_ROWS` cap rejection.
- invalid derived input.
- invalid column.
- unsupported op for type.
- unsupported complex column type.
- ambiguous percent scale.
- PASSWORD predicate blocked.
- `CACHE_MISS`.
- P2 last-cache sentinel works.

## 14. Non-goals

Out of scope for these tools:

- Full SQL.
- Cross-cache joins.
- Arbitrary JavaScript / formula expressions.
- Domain-specific utilization/OEE summaries hardcoded into the executor.
- Fuzzy entity resolution. Use taxonomy/entity resolver tools before cached-table decisions.
- Interval algebra: no union length of overlapping time intervals.
- Calendar-denominator semantics: no implicit "planned shift duration" unless it is a column or computed by a domain tool.
- Shift calendars: no inferred shift schedule from timestamps. Use `ShiftID` if present or a domain tool.
- Nested `INFOTABLE` / JSON / list querying.
- Regex matching in V1.
