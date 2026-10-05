# Parler Query Specification

Canonical JSON shape for **filter / sort / `maxItems` / `offset` / `fields` projection** on
**parler-owned cached-table tool arguments** (Tier 1, see §7). Aligned with ThingWorx Query JSON
where the platform already has equivalents (`com.thingworx.types.data.filters.FilterFactory`) and
layered with Parler extensions for the parts the platform does not cover.

Scope clarification:
- **Tier 1 — Parler-owned cached-table tools:** `tabulate_cached_result` (this spec is the canonical contract for root `filters` / `sorts` / `maxItems` / `offset` / `fields`; legacy `{op, column, value}` grammar **rejected**).
- **Tier 2 — platform-query passthrough tools:** (`query_entities`, `query_entities_by_taxonomy`,
  `query_alert_summary`, `query_alert_history`, etc.) accept ThingWorx Query JSON natively but **do not** support Parler extension filter types (composite `NOT`, `CONTAINS` family,
  `STARTSWITH` / `ENDSWITH` family, `ISEMPTY` / `NOTEMPTY`). Only the core grammar of §3 applies to them.
- **Tier 3 — out of scope:** `invoke_service` (parameters are service-specific),
  `build_chart_from_tabular_result` (no tool-arg filter today).

**Status:** implemented for Tier 1 (`tabulate_cached_result` query JSON per this spec). The older `{op, column, value}` predicate grammar and `{column, direction}` sort grammar are **rejected**.

## 1. Why ThingWorx Query JSON

Three independent reasons make the ThingWorx Query JSON shape the right canonical form for parler:

1. **LLM training-corpus prior.** ThingWorx Composer / Mashup query builder / Extension SDK examples have
   been public for ~10 years. The shape `{"type": "EQ", "fieldName": "x", "value": 30}` is present in
   training corpora for every major LLM. Our previous `{op, column, value}` form was zero-corpus
   parler-invented DSL that every LLM call had to learn from the prompt.

2. **Additive to the platform filter system.** Leaf-type constants and JSON keys are identical to
   `com.thingworx.types.data.filters.FilterFactory` for everything the platform already supports, and
   Parler-only additions (composite `NOT`, `CONTAINS` family, `ISEMPTY` family, query-level `fields`
   projection, `offset`) are layered on top. The extensions are **additive** to the platform filter
   system, not a fork.

3. **Customer / playbook author familiarity.** Customers writing playbooks, skills, and custom prompts
   already know ThingWorx Query JSON from Composer's query builder. One mental model across parler tools
   and platform tools.

**Scope of "TWX-compatible":** core leaf types (`EQ`/`NE`/`LT`/`LE`/`GT`/`GE`/`IN`/`NOTIN`/`BETWEEN`/
`NOTBETWEEN`/`LIKE`/`NOTLIKE`/`MISSINGVALUE`/`NOTMISSINGVALUE`/`NEAR`/`NOTNEAR`/`TAGGED`/`NOTTAGGED`) and
composite `AND` / `OR` match the platform `FilterFactory.createFilter(...)` JSON dispatch byte-for-byte.
Composite `NOT` and Parler-extension leaves (`CONTAINS`, `STARTSWITH`, `ENDSWITH`, `ISEMPTY`, and their
`NOT*` variants) are **Parler additions** — see §3.2.1, §3.3, §10. The Parler-agent envelope keys
`maxItems`, `offset`, and `fields` are **Parler tool-arg envelope**, not part of platform `Query` JSON.

## 2. Naming + Shape Conventions

All filter / sort / limit / offset JSON across all parler tools uses ThingWorx field names. Mapping vs.
prior parler vocabulary:

| Concept | Prior parler shape | Canonical (TWX-aligned) | Notes |
|---|---|---|---|
| Filter root key on tool args | `where` | `filters` | Top-level filter on source rows. |
| Composite filter shape | `{"all": [...], "any": [...], "not": ...}` | `{"type": "AND" \| "OR" \| "NOT", "filters": [...]}` | `NOT` wraps a single-element `filters` array — **Parler extension** vs current platform `FilterFactory` dispatch; see §3.2.1. |
| Leaf filter shape | `{"op": "lt", "column": "x", "value": 30}` | `{"type": "LT", "fieldName": "x", "value": 30}` | All `type` values UPPERCASE. |
| Leaf op set | `{lt, lte, gt, gte, eq, ne, ...}` | `{LT, LE, GT, GE, EQ, NE, LIKE, NOTLIKE, IN, NOTIN, BETWEEN, NOTBETWEEN, MISSINGVALUE, NOTMISSINGVALUE, NEAR, NOTNEAR, TAGGED, NOTTAGGED, CONTAINS, NOTCONTAINS, STARTSWITH, NOTSTARTSWITH, ENDSWITH, NOTENDSWITH, ISEMPTY, NOTEMPTY}` | LE/GE follow TWX names (not LTE/GTE). |
| BETWEEN bounds | `min`, `max` | `from`, `to` | Inclusive on both sides (TWX semantic). |
| IN / NOT IN value list | `values: [...]` | `values: [...]` | No change. |
| Single value | `value: ...` | `value: ...` | No change. |
| Case sensitivity flag | `case_sensitive: bool` | `isCaseSensitive: bool` | Defaults: **filter `false`, sort `true`** (matches each TWX sub-system). See §3.5. |
| Sort root key | `sort` | `sorts` | Array, even for single-key. |
| Sort element | `{"column": "x", "direction": "asc"\|"desc"}` | `{"fieldName": "x", "isAscending": true\|false}` | Boolean direction. |
| Result row limit | `limit: 500` | `maxItems: 500` | TWX convention. |
| Result row offset | `offset: 0` | `offset: 0` | Parler extension; TWX has no offset in most query shapes. |
| Output column projection | (implicit by mode) | `fields: ["c1", "c2"]` | New optional parler extension; see §5. |
| Post-aggregation filter (group_metric) | `having: {...}` | `having: {...}` | Same filter shape, kept SQL-conventional name. |

Parler-specific keys with no TWX analog stay unchanged: `cacheId`, `mode`, `groupBy`, `measures`,
`derived`, `aggregateColumn`, `fn`.

## 3. Filter Specification

### 3.1 Leaf filter

```json
{
  "type": "<TYPE>",
  "fieldName": "<column>",
  ...type-specific keys...
}
```

Required keys: `type` (uppercase string), `fieldName` (source / output column name).
Type-specific keys: `value`, `values`, `from`, `to`, `isCaseSensitive` per §3.3.

### 3.2 Composite filter

```json
{ "type": "AND" | "OR" | "NOT", "filters": [<filter>, <filter>, ...] }
```

`AND` / `OR` require `filters` length ≥ 1. `NOT` requires `filters` length = 1 (wraps a single child).
Composite depth is capped at 4 (`ParlerQueryFilterParser`; legacy decision-doc drafts said depth 3 / 20 leaves).

### 3.2.1 Composite `NOT` — Parler extension (platform `FilterFactory` gap)

The JSON shape `{"type":"NOT","filters":[<single child>]}` is **canonical for LLM-facing filters in this spec** (same
shape many authors expect from ThingWorx-style query examples and training corpora).

**Platform reality:** on the ThingWorx Java path, `com.thingworx.types.data.filters.FilterFactory` exposes composite
constants including `NOT`, but **`createFilter` / the common JSON entry path used for this composite shape does not
dispatch `NOT`** — only `AND` / `OR` are wired there today.

**Parler:** composite `NOT` is a **Parler extension**, not something `FilterFactory` dispatches. After parsing the
single child filter with the same machinery as other composites / leaves, Parler wraps it in a **`NotFilter`**
(`tools/predicate/NotFilter`) implementing `com.thingworx.types.data.filters.IFilter` that negates the
child’s row-match predicate. Requirements:

- `filters` length must be **exactly 1**; otherwise `INVALID_PREDICATE` (same rule as §3.2).
- Counts toward composite depth and leaf-count caps like any other composite node.
- Unit / integration tests cover negation semantics and error paths.

JSON and `IFilter` stay ThingWorx-shaped; the platform `FilterFactory` source is not modified.

### 3.3 Supported filter types

| `type` | Required keys (beyond `type` + `fieldName`) | Semantic |
|---|---|---|
| `EQ` / `NE` | `value`, optional `isCaseSensitive` | Equality / inequality. |
| `LT` / `LE` / `GT` / `GE` | `value` | Numeric / datetime comparison. |
| `BETWEEN` / `NOTBETWEEN` | `from`, `to` | Inclusive range on both sides. Both bounds required (Parler tightening — TWX `BetweenFilter` accepts single-sided; use `LT` / `GT` / `LE` / `GE` for open-ended). |
| `IN` / `NOTIN` | `values` (array, length ≥ 1), optional `isCaseSensitive` | Membership. |
| `LIKE` / `NOTLIKE` | `value` (pattern), optional `isCaseSensitive` | TWX wildcards: `*` (alias `%`) matches zero-or-more characters; `?` matches exactly one character. **All other characters in `value` — including `_` — are literal.** SQL `_`-as-single-char-wildcard is not a TWX convention; authors who want a single-char wildcard write `?`. |
| `MISSINGVALUE` / `NOTMISSINGVALUE` | (none) | Cell is null / not null. Applies to columns of any base type, including `LOCATION` and `TAGS`. |
| `NEAR` / `NOTNEAR` | `location` (`{latitude, longitude, elevation?}`), `distance` (number), `units` (string — see §3.8) | Geo-distance proximity. **Column type must be `LOCATION`** (see §3.7) — otherwise `TYPE_MISMATCH`. Semantic = strict `<` distance (`NEAR`) / `>=` distance (`NOTNEAR`), matching platform `NearFilter`'s `< distance` boundary. Distance unit normalized at the wrapper (§3.8). |
| `TAGGED` / `NOTTAGGED` | `tags` (array of `{vocabulary, vocabularyTerm}`) | Vocabulary-tag membership. **Column type must be `TAGS`** (see §3.7). Semantic follows TWX `TagFilter`: matches when the cell's tag collection intersects (`TAGGED`) / is disjoint from (`NOTTAGGED`) the given tag set. |
| `CONTAINS` / `NOTCONTAINS` | `value`, optional `isCaseSensitive` | **Parler extension.** Literal substring containment. The characters `*`, `%`, `?` in `value` are matched **literally** (no wildcard interpretation), so authors do not need to escape them — unlike `LIKE`. `_` is literal in both `CONTAINS` and `LIKE`. |
| `STARTSWITH` / `NOTSTARTSWITH` | `value`, optional `isCaseSensitive` | **Parler extension.** Literal prefix match. `*`, `%`, `?` literal in `value`. |
| `ENDSWITH` / `NOTENDSWITH` | `value`, optional `isCaseSensitive` | **Parler extension.** Literal suffix match. `*`, `%`, `?` literal in `value`. |
| `ISEMPTY` / `NOTEMPTY` | (none) | **Parler extension.** Cell is empty string `""` (distinct from null). Allowed on **string-family columns** — §3.9 category 3 (`STRING`, `TEXT`, `GUID`, every `*NAME` base type) and category 4 (`HYPERLINK`, `IMAGELINK`, `HTML`, `XML`). Non-string-family columns return `TYPE_MISMATCH`. |

### 3.4 Disabled TWX types

One TWX filter type that the platform supports is **rejected at the Parler wrapper entry point** for
the cached-table predicate paths (the platform `FilterFactory` still supports it for non-cached
queries; we do not modify the platform):

| `type` | Reason | Error message |
|---|---|---|
| `MATCHES` / `NOTMATCHES` | Regex DoS risk over 100k+ row scans. | "Regex (MATCHES) not supported in cached predicate; use LIKE with `*`/`?` wildcards or CONTAINS/STARTSWITH/ENDSWITH." |

The rejection lives only in the wrapper's rejection set (see §12); the platform `FilterFactory` source is not altered.

### 3.5 Case sensitivity

`isCaseSensitive` is optional and applies to `EQ`, `NE`, `IN`, `NOTIN`, `LIKE`, `NOTLIKE`, `CONTAINS`,
`NOTCONTAINS`, `STARTSWITH`, `NOTSTARTSWITH`, `ENDSWITH`, `NOTENDSWITH`, and sort entries (§4).

**Defaults — split by sub-system to match each TWX origin:**

| Surface | Default `isCaseSensitive` | TWX origin |
|---|---|---|
| Filter (every type that supports the flag) | **`false`** | TWX `RangeFilter` / `SetFilter` / `LikeFilter` (via `optBoolean` → `false`) |
| Sort (`sorts[].isCaseSensitive`) | **`true`** | TWX `SortParser` |

Rationale: industrial-data column values (equipment names, operator IDs, descriptions) routinely
arrive with inconsistent casing across upstream sources. Strict case-sensitive filter `EQ` as a
default would make the *same physical asset* match in one ingest and not another — that is the
disruptive non-determinism in practice, not case-folding. Case-insensitive filter matches user
expectation for "show me machines named Aachen" → matches `"Aachen"`, `"AACHEN"`, `"aachen"`.

Authors who need strict matching write `isCaseSensitive: true` explicitly on a per-filter basis.
Sort retains its case-sensitive default because deterministic ordering is more useful than
case-folded ordering when ranking values.

This is **zero deviation from each TWX sub-system's existing default** on case behavior.

### 3.6 Examples

Equality with case sensitivity opt-out:

```json
{"type": "EQ", "fieldName": "UtilizationState", "value": "Running", "isCaseSensitive": false}
```

Range:

```json
{"type": "LT", "fieldName": "utilization_pct", "value": 30}
```

Between:

```json
{"type": "BETWEEN", "fieldName": "Duration", "from": 0, "to": 86400}
```

Set membership:

```json
{"type": "IN", "fieldName": "EquipmentID", "values": ["A-01", "A-02", "A-03"]}
```

Substring search (parler extension):

```json
{"type": "CONTAINS", "fieldName": "EquipmentDesc", "value": "Aachen"}
```

Null check:

```json
{"type": "MISSINGVALUE", "fieldName": "ModifiedAt"}
```

Geo proximity (column type must be `LOCATION`):

```json
{
  "type": "NEAR",
  "fieldName": "currentLocation",
  "location": {"latitude": 42.3601, "longitude": -71.0589},
  "distance": 25,
  "units": "kilometers"
}
```

Vocabulary tag membership (column type must be `TAGS`):

```json
{
  "type": "TAGGED",
  "fieldName": "deviceTags",
  "tags": [
    {"vocabulary": "DeviceCategory", "vocabularyTerm": "Sensor"},
    {"vocabulary": "DeviceCategory", "vocabularyTerm": "Actuator"}
  ]
}
```

Composite:

```json
{
  "type": "AND",
  "filters": [
    {"type": "GT", "fieldName": "utilization_pct", "value": 0},
    {"type": "LT", "fieldName": "utilization_pct", "value": 30},
    {"type": "OR", "filters": [
      {"type": "EQ", "fieldName": "UtilizationState", "value": "Running"},
      {"type": "EQ", "fieldName": "UtilizationState", "value": "Idle"}
    ]}
  ]
}
```

Negation:

```json
{"type": "NOT", "filters": [
  {"type": "EQ", "fieldName": "Reason", "value": "Maintenance"}
]}
```

### 3.7 Column-type constraints

Most filter types accept any sortable column type. Two filter types and one parler-extension type have
**column-type restrictions** enforced at validate time:

| Filter type | Required column base type | Error code if mismatched |
|---|---|---|
| `NEAR` / `NOTNEAR` | `LOCATION` | `TYPE_MISMATCH` |
| `TAGGED` / `NOTTAGGED` | `TAGS` | `TYPE_MISMATCH` |
| `ISEMPTY` / `NOTEMPTY` | string-family (§3.9 category 3 or 4) | `TYPE_MISMATCH` |

Conversely, `LOCATION` and `TAGS` column types are **only** valid for their respective filter pair
plus `MISSINGVALUE` / `NOTMISSINGVALUE` (null check is always allowed):

| Column base type | Allowed filter types |
|---|---|
| `LOCATION` | `NEAR`, `NOTNEAR`, `MISSINGVALUE`, `NOTMISSINGVALUE` |
| `TAGS` | `TAGGED`, `NOTTAGGED`, `MISSINGVALUE`, `NOTMISSINGVALUE` |

All other operations on `LOCATION` / `TAGS` columns (sort, `groupBy`, measure `column`,
projection `fields`) return `UNSUPPORTED_COLUMN_TYPE` — these column types are not orderable, hashable
as group keys, or aggregable in the cached-tabular layer.

### 3.8 `NEAR` units normalization

The wrapper normalizes `units` to TWX `LocationUtilities` single-letter codes before delegating to
platform `NearFilter`. Unknown input strings are **rejected**, never silently coerced to miles
(which is the platform fallback for unknown strings).

| Accepted input (case-insensitive) | Normalized to | Meaning |
|---|---|---|
| `M`, `mile`, `miles` | `M` | Statute miles (TWX default) |
| `K`, `km`, `kilometer`, `kilometers`, `kilometre`, `kilometres` | `K` | Kilometers |
| `N`, `nm`, `nautical_mile`, `nautical_miles`, `nauticalmile`, `nauticalmiles` | `N` | Nautical miles |
| anything else | rejected | `INVALID_PREDICATE` with echoed input value |

After normalization, `NEAR` evaluates as `great_circle_distance(point, location) < distance` (strict
less-than, matching `NearFilter`'s boundary). `NOTNEAR` evaluates as `>= distance`. A test fixture proves
`"kilometers"` is **not** silently interpreted as miles.

### 3.9 Base-type support matrix

Every cached-table column has a ThingWorx `BaseType`. The matrix below assigns each base type to one
category and lists what operations the category accepts. The categories are mutually exclusive —
each base type belongs to exactly one row. Operations not listed in the "accepts" column return
`UNSUPPORTED_COLUMN_TYPE` (or `TYPE_MISMATCH` when a known filter type is misapplied within its
category's allowed set).

| # | Category | Members | Accepted operations |
|---|---|---|---|
| 1 | **Protected** | `PASSWORD` | **None.** Always returns `PROTECTED_TABULAR_COLUMN_BLOCKED` at §8.1 layer 1 before any other type evaluation. |
| 2 | **Scalar comparable** | `INTEGER`, `LONG`, `NUMBER`, `BOOLEAN`, `DATETIME`, `TIMESPAN` | All scalar leaf types (`EQ`/`NE`/`LT`/`LE`/`GT`/`GE`/`BETWEEN`/`NOTBETWEEN`/`IN`/`NOTIN`/`MISSINGVALUE`/`NOTMISSINGVALUE`); sort; `groupBy`; projection; measure `column`. |
| 3 | **Plain string-like (orderable)** | `STRING`, `TEXT`, `GUID`, **plus every `BaseTypes` enum constant whose name ends with `NAME`** (entity-name reference types — e.g. `THINGNAME`, `THINGSHAPENAME`, `DATASHAPENAME`, `USERNAME`, `PROPERTYNAME`, `SERVICENAME`, `EVENTNAME`, `THINGGROUPNAME`, `CATEGORYNAME`, `ROLENAME`, `STYLETHEMENAME`, etc. — non-exhaustive). | All scalar leaf types plus `LIKE`/`NOTLIKE`, `CONTAINS`/`NOTCONTAINS`, `STARTSWITH`/`NOTSTARTSWITH`, `ENDSWITH`/`NOTENDSWITH`, `ISEMPTY`/`NOTEMPTY`; sort; `groupBy`; projection; measure `column` (string-aggregator subset). |
| 4 | **Rich-string content (filter-only)** | `HYPERLINK`, `IMAGELINK`, `HTML`, `XML` | All string-pattern filter types: `EQ`/`NE`/`IN`/`NOTIN`/`LIKE`/`NOTLIKE`/`CONTAINS`/`NOTCONTAINS`/`STARTSWITH`/`NOTSTARTSWITH`/`ENDSWITH`/`NOTENDSWITH`/`ISEMPTY`/`NOTEMPTY`/`MISSINGVALUE`/`NOTMISSINGVALUE`. **Reject** sort / `groupBy` / projection / measure `column` — the natural ordering of a URL / image link / HTML blob rarely matches business intent, and grouping by raw HTML is almost never useful. Returns `UNSUPPORTED_COLUMN_TYPE` for the rejected operations. |
| 5 | **Special-only** | `LOCATION`, `TAGS` | Only the special-pair filters: `LOCATION` → `NEAR` / `NOTNEAR` + null check; `TAGS` → `TAGGED` / `NOTTAGGED` + null check. **Not** valid for sort / `groupBy` / projection / measure `column`. |
| 6 | **Never-operable** | `INFOTABLE`, `JSON`, `IMAGE`, `BLOB`, `VEC2`, `VEC3`, `VEC4`, `NOTHING`, `VARIANT`, `THINGCODE`, `SCHEDULE`, `QUERY` | None in this layer. Always returns `UNSUPPORTED_COLUMN_TYPE`. These types have no canonical comparison, hashing, or ordering semantic in cached InfoTable filtering. |

Notes:
- `TIMESPAN` (category 2) is scalar-comparable; comparison is numeric on the millisecond
  representation.
- `GUID` lives in category 3 (orderable string-like) because lexical ordering is well-defined and
  occasionally useful, even though the most common use is `EQ` / `IN`.
- Entity-name types (`*NAME` family) are treated identically to `STRING` for filter / sort /
  groupBy / projection purposes. The implementation **must** identify category 3 membership via a
  suffix rule on `BaseTypes` enum constant names — checking `baseType.name().endsWith("NAME")` —
  **not** by copying the example list above as a closed enumeration. The ThingWorx `BaseTypes`
  enum has evolved (e.g. `THINGGROUPNAME`, `CATEGORYNAME`, `STYLETHEMENAME`, `ROLENAME`,
  `DATATAGVOCABULARYNAME`, `QUEUEPROVIDERNAME`, `QUEUEPROVIDERPACKAGENAME`,
  `PERMISSIONGROUPNAME`, `THINGPACKAGENAME`, and others); any
  hand-maintained list will drift. The suffix rule is authoritative; the names in the table above
  are illustrative examples only.
- `IMAGELINK` (category 4) stores a URL referencing an image; `IMAGE` (category 6) stores binary
  image content. The two are intentionally in different categories.
- Implementation should categorize via a single helper (`baseTypeCategory(BaseTypes) →
  Category enum`) shared with the PASSWORD-guard sweep (§8.2). The same helper drives
  `UNSUPPORTED_COLUMN_TYPE` at §8.1 layer 5.

Test coverage: at least one
representative test per category — `STRING` (cat 3), `TEXT` (cat 3), one `*NAME` type explicitly
named in the table above (e.g. `THINGNAME` — cat 3), **plus one `*NAME` type that is NOT named in
the table** (e.g. `THINGGROUPNAME` or `ROLENAME` — cat 3 via suffix rule), `GUID` (cat 3), one
rich-string type from cat 4, `PASSWORD` (cat 1), `LOCATION` (cat 5), `TAGS` (cat 5), and one
never-operable type (cat 6). The "not-named-in-table" `*NAME` test verifies the implementation
uses the suffix rule, not a literal copy of the example list.

## 4. Sort Specification

### 4.1 Shape

```json
{
  "sorts": [
    {
      "fieldName": "<col>",
      "isAscending": true | false,
      "isCaseSensitive": true | false
    }
  ]
}
```

`sorts` is an array of 1 to 3 sort keys. Each element requires `fieldName`. Optional:

- `isAscending` — defaults to `true`.
- `isCaseSensitive` — defaults to `true` (matches TWX `SortParser`). Applies to STRING-typed columns;
  ignored for numeric / datetime / TIMESPAN columns.

### 4.2 Tie-break

Sorting is **stable** by original row index (or by group-key construction order for `group_metric`).
When two rows compare equal across all `sorts` keys, prior row order is preserved. This matches
existing parler behavior and TWX `Sort.compare` stability semantics.

### 4.3 Duplicate handling

Two `sorts[]` elements referencing the same `fieldName` (with any combination of `isAscending` /
`isCaseSensitive`) is rejected with `INVALID_PARAMETERS` and an echoed message naming the duplicated
field. This catches accidental LLM repetition and avoids the ambiguity of "second key on same field
is unreachable because the first one already disambiguated."

### 4.4 Examples

Single sort:

```json
{"sorts": [{"fieldName": "utilization_pct", "isAscending": true}]}
```

Multi-key sort (primary asc, secondary asc as tie-break):

```json
{
  "sorts": [
    {"fieldName": "utilization_pct", "isAscending": true},
    {"fieldName": "EquipmentID", "isAscending": true}
  ]
}
```

Descending:

```json
{"sorts": [{"fieldName": "running_duration", "isAscending": false}]}
```

Case-insensitive string sort:

```json
{"sorts": [{"fieldName": "EquipmentDesc", "isAscending": true, "isCaseSensitive": false}]}
```

## 5. Limit / Offset / Projection

### 5.1 `maxItems` (limit)

Replaces prior parler `limit`. Same per-mode range constraints:
- `sort_topn`, `filter_rows`, `filter_sort_topn`, `group_metric` output: 1–500
- Out-of-range → `LIMIT_OUT_OF_RANGE`

### 5.2 `offset`

Parler extension; preserved from prior shape. Optional, default 0. TWX query JSON has no equivalent in
the standard `QueryDataTableEntries` family (offsetting is done via continuation tokens or stream
pagination in TWX). Parler keeps `offset` for tool-level pagination control.

When merged back, `offset` becomes a parler-contributed extension to TWX query shape.

### 5.3 `fields` (projection)

**New optional parler extension.** Restricts the columns returned in the tool output table.

```json
{ "fields": ["EquipmentID", "EquipmentDesc", "utilization_pct"] }
```

Semantics:
- Applies to `filter_rows`, `filter_sort_topn`, and `group_metric` output (after measure / derived
  computation).
- Order matters: output columns appear in the order listed.
- For `filter_rows` / `filter_sort_topn`: each element must be a column name present in the source
  `DataShape`. Unknown name → `INVALID_COLUMN`.
- For `group_metric`: each element must be a name in the **post-aggregation output schema**, i.e.
  one of the `groupBy[]` entries, measure `name`, or derived `name`. Referencing a source column
  that is not in the output schema (e.g. a column that was filtered or aggregated away) returns
  `INVALID_COLUMN` with a message stating which valid output names exist for the call.
- Omitting `fields` returns the full implicit output (current behavior).
- Empty array `[]` is rejected as `INVALID_PARAMETERS`.
- Duplicate entries in `fields[]` (same name listed twice) are rejected as `INVALID_PARAMETERS`
  with an echoed message naming the duplicated field. Avoids confusing duplicate columns in output
  table.

Rationale: lets LLM / playbook author trim large rows (e.g. cached table with 30 columns) down to the
3 relevant ones in the same tool call, instead of relying on prose-level filtering downstream. Useful
for evidence-token efficiency.

TWX has `ProjectFields` as a separate service rather than a query-level projection key. Parler
introduces query-level `fields` because chaining a separate projection call inside a tabulate decision
would double the tool-call cost.

### 5.4 Operation order

For deterministic test fixtures and predictable cache-id semantics, each mode evaluates its
operations in a documented sequence. Implementations must follow this order verbatim:

| Mode | Order of evaluation |
|---|---|
| `sort_topn` | source rows → **sort** → offset → maxItems → fields |
| `filter_count` | source rows → **filters** → (optional `groupBy` group-counting) → result |
| `filter_rows` | source rows → **filters** → sort (if present) → offset → maxItems → fields |
| `filter_sort_topn` | source rows → **filters** (optional) → **sort** → offset → maxItems → fields |
| `group_count` | source rows → group by key → result (no `maxItems` semantic beyond cap) |
| `group_aggregate` | source rows → group by key → aggregate (`fn` over `aggregateColumn`) → result |
| `group_metric` | source rows → **filters** (top-level, optional) → group by `groupBy[]` → compute `measures[]` (each with optional measure-level `filters` over source subset for that group) → compute `derived[]` → **having** (optional filter on output rows) → **sort** (optional) → offset → maxItems → **fields** |

Notes:
- `offset` and `maxItems` apply **after** any sort / having filter; never before.
- `fields` always applies **last** so the projection sees the post-aggregation column set.
- Each pipeline stage produces a deterministic row order before the next stage runs; this ensures
  golden-test stability across reruns.

## 6. Group / Measure / Derived / Having (parler-specific)

These remain parler-specific because TWX query JSON has no comparable structured aggregate spec. They
appear at the root of `tabulate_cached_result` `group_metric` mode args:

```json
{
  "cacheId": "...",
  "mode": "group_metric",
  "filters": {...},                  // optional pre-aggregation filter
  "groupBy": ["k1", "k2"],
  "measures": [
    {
      "name": "running_duration",
      "op": "sum",
      "column": "Duration",
      "filters": {                   // ← was "where"; now uses filter shape
        "type": "EQ",
        "fieldName": "UtilizationState",
        "value": "Running"
      }
    }
  ],
  "derived": [...],
  "having": {                        // post-aggregation filter; same shape as filters
    "type": "LT", "fieldName": "utilization_pct", "value": 30
  },
  "sorts": [...],
  "maxItems": 500,
  "offset": 0,
  "fields": ["k1", "running_duration", "utilization_pct"]  // optional projection
}
```

Notes:
- `measure.filters` replaces `measure.where`. Same TWX filter shape. Same semantic (per-measure source
  row predicate).
- For **`sum`** with **`measure.filters`**: when the group has at least one source row but **no** row
  passes the measure filter, the measure is **`0`** (conditional sum over an empty subset), not **`null`**.
  **`sum`** without measure filters over all-null numeric inputs remains **`null`**. Other measure ops
  (`avg`, `min`, `max`, …) keep **`null`** when the post-filter contributing set is empty. Normative:
  **`cached-table-decision-tools.md`** (Bug 005, Option G).
- `having` replaces no prior name. Same TWX filter shape applied to grouped output. The name `having`
  is kept (SQL-conventional, universally understood; TWX has no analog).
- Measure `op` field stays lowercase (`sum`, `avg`, etc.) — distinct from filter `type`, which is
  uppercase TWX-style. The two namespaces (aggregator op vs filter type) are clearly separated by
  context (inside `measures[]` vs inside `filters`).

## 7. Tool-by-tool Application

This spec is the canonical shape for **all** filter / sort / limit / offset / projection that parler
tools accept from the LLM. The three categories below are not equal in impact — be explicit about
which tools change code, which only update documentation, and which the spec does not touch.

### 7.1 Tier 1 — canonical shape

**`tabulate_cached_result`** parses the canonical shape in every mode and strictly rejects the prior shape — see
the rejection list below.

Per-mode allowed keys:

| Mode | Required | Optional |
|---|---|---|
| `sort_topn` | `cacheId`, `mode`, `sorts` (1–3 keys) | `maxItems`, `offset`, `fields` |
| `group_count` | `cacheId`, `mode`, `groupBy` (string) | `maxItems` |
| `group_aggregate` | `cacheId`, `mode`, `groupBy`, `fn`, `aggregateColumn` (except `fn=count`) | `maxItems` |
| `filter_count` | `cacheId`, `mode`, `filters` | `groupBy` (string) |
| `filter_rows` | `cacheId`, `mode`, `filters` | `maxItems`, `offset`, `fields`, `sorts` |
| `filter_sort_topn` | `cacheId`, `mode`, `sorts` (1–3 keys) | `filters`, `maxItems`, `offset`, `fields` |
| `group_metric` | `cacheId`, `mode`, `measures` | `filters`, `groupBy`, `derived`, `having`, `sorts`, `maxItems`, `offset`, `fields` |

**Rejected legacy keys (strict clean break).** Every mode rejects all of the following with the
echo error per §8.3 — never silently mapped, never ignored, regardless of whether the request also
contains canonical keys (mixed old+new payloads also reject, not partial-accept):

| Rejected location | Rejected key | Returned code |
|---|---|---|
| Root | `where` (use `filters`) | `INVALID_PREDICATE` |
| Root | `sort` (use `sorts`) | `INVALID_PARAMETERS` |
| Root | `limit` (use `maxItems`) | `INVALID_PARAMETERS` |
| Root | `sortBy` (use `sorts[].fieldName`) | `INVALID_PARAMETERS` |
| Root | `direction` (use `sorts[].isAscending`) | `INVALID_PARAMETERS` |
| Leaf predicate | `op` (use `type`) | `INVALID_PREDICATE` |
| Leaf predicate | `column` (use `fieldName`) | `INVALID_PREDICATE` |
| Leaf predicate | `operator`, `field`, `fieldname`, `term`, `compare`, `predicate` (any non-canonical key) | `INVALID_PREDICATE` |
| Composite predicate | `all` / `any` / `not` as root keys (use `{"type":"AND/OR/NOT","filters":[...]}`) | `INVALID_PREDICATE` |
| Composite predicate | `predicates` array (use `filters`) | `INVALID_PREDICATE` |
| BETWEEN predicate | `min`, `max` (use `from`, `to`) | `INVALID_PREDICATE` |
| Sort element | `column` (use `fieldName`) | `INVALID_PARAMETERS` |
| Sort element | `direction: "asc"\|"desc"` (use `isAscending: true\|false`) | `INVALID_PARAMETERS` |
| Measure | `where` (use `filters`) | `INVALID_PREDICATE` |

The wrapper enforces this rejection list **before** any shape parsing, and the JSON Schema exposed
to the LLM does not include any of these legacy keys as positive-path options.

### 7.2 Tier 2 — platform-query passthrough

`query_entities` / `query_entities_by_taxonomy` / `query_alert_summary` / `query_alert_history` and
related platform-query passthrough tools **accept TWX Query JSON natively** — they hand the query
parameter to platform services that parse it via `com.thingworx.types.data.filters.FilterFactory`
directly. Their LLM-facing descriptions call it a ThingWorx-shaped Query dialect (query-spec subset) and
point to `docs/agent/query_capability.md`.

Caveat: these tools do **not** support Parler-extension types (`NOT`, `CONTAINS`, `STARTSWITH`,
`ENDSWITH`, `ISEMPTY`, `NOTEMPTY`) because the platform `FilterFactory` does not know about them.
Authors targeting Tier 2 tools must restrict themselves to TWX-native types. (`query_entities` also
applies its own admission rules — see `query_entities_design.md` §5.)

### 7.3 Tier 3 — out of scope

**`invoke_service`** parameters come from the target ThingWorx ServiceDefinition; their shape is
service-specific and not controllable from this spec. If a target service accepts a TWX Query
parameter, that parameter naturally matches §3 grammar (since the platform parses it that way), but
the spec does not require modifying `invoke_service`'s tool description for each downstream service.

**`build_chart_from_tabular_result`** has no filter / sort at the tool-arg level (chart payload is
constructed from cached table plus axis selection).

## 8. Error Codes

Filter / sort / projection validation errors share the existing parler structured error catalog from
`cached_tabular_tools.md`. No new codes.

### 8.1 Layered evaluation order

Error checking is **layered**; each layer is non-overlapping with the next so the tool returns
exactly one `code`. Order of evaluation:

1. **`PROTECTED_TABULAR_COLUMN_BLOCKED`** — referenced column is `PASSWORD`. Sweep covers
   **canonical and legacy** column-reference-like keys (see §8.2), so secret references via the old
   shape still block here, never swallowed by any later layer.
2. **Legacy-shape rejection** — any rejected legacy key from §7.1 present in the request returns the
   corresponding code (`INVALID_PREDICATE` for filter-shape keys, `INVALID_PARAMETERS` for envelope
   / sort keys) with the §8.3 echo message. Critically, **this precedes `INVALID_COLUMN`**: a
   legacy leaf like `{"op":"eq","column":"doesNotExist","value":"x"}` returns
   `INVALID_PREDICATE rejected legacy shape`, not `INVALID_COLUMN`. The legacy-rejection layer
   does **not** validate whether the column name referenced via the legacy key exists — that check
   only applies to canonical paths (layer 3).
3. **`INVALID_COLUMN`** — column name referenced via a canonical key (`fieldName`, `fields[]`,
   `groupBy[]`, measure `column` / `weightColumn` / `orderBy`, `aggregateColumn`) does not exist in
   the cached `DataShape`. Reached only after PASSWORD sweep and legacy-shape rejection.
4. **`INVALID_PREDICATE`** — filter shape problems on the canonical path: missing required `type`;
   **unknown `type`**; disabled `type` (MATCHES); leaf missing `fieldName`; `NEAR` missing
   `location` / `distance` / malformed `location` / unknown `units` (per §3.8); `TAGGED` missing
   `tags`; composite `NOT` with non-1 `filters` length; depth > 4. **Unknown `type` precedes
   column-type checks** — `{"type":"BOGUS","fieldName":"locationCol"}` returns `INVALID_PREDICATE`
   for the unknown type, not `UNSUPPORTED_COLUMN_TYPE` for the LOCATION column.
5. **`UNSUPPORTED_COLUMN_TYPE`** — column exists with a base type that is **never operable** in the
   cached-tabular layer (per §3.9 "Never-operable" row). Also returned when `LOCATION` / `TAGS`
   columns are referenced **outside** their allowed filter pair (see §3.7) — i.e. used in sort /
   groupBy / measure column / projection, or used with a known non-NEAR / non-TAGGED filter type.
   Reached only after layer 4 has confirmed `type` is a known supported value.
6. **`TOO_MANY_PREDICATES`** — composite leaf count > 32 across the whole tree.
7. **`UNSUPPORTED_OPERATOR`** — known filter `type` whose semantic is undefined for the column's
   declared base type (e.g. `LIKE` on a NUMBER column).
8. **`TYPE_MISMATCH`** — known filter `type`, operable column type, but the operand cannot be coerced
   to the column's base type (`value`, `values[]`, `from` / `to` type disagreement). **`NEAR` units
   not in the §3.8 normalization set return `INVALID_PREDICATE` at layer 4, not `TYPE_MISMATCH`** —
   the units name is a filter-shape concern, not a column-type coercion problem.
9. **`INVALID_PARAMETERS`** — envelope / shape outside the filter tree on the canonical path:
   `sorts[]` > 3 keys; `sorts[].fieldName` missing; duplicate `sorts[].fieldName`; `fields` empty
   array; duplicate `fields[]` entry; `maxItems` non-integer; `offset` negative; measure `p` outside
   `[0, 100]` (etc., from existing cached-table catalog). (Legacy envelope shapes — root `sort` /
   `limit` / `sortBy` / `direction`; sort element `column` / `direction` — are rejected at layer 2,
   not here.)
10. **`LIMIT_OUT_OF_RANGE`** — `maxItems` outside per-mode allowed range. Not used for `percentile p`
    (that uses `INVALID_PARAMETERS` per `cached_tabular_tools.md`).

### 8.2 PASSWORD sweep + legacy-shape rejection scope

The PASSWORD sweep (layer 1) and the legacy-shape rejection (layer 2) walk the request tree
**before** any other validation. Their scopes are deliberately different:

**Layer 1 PASSWORD sweep** scans column-reference-like keys across **canonical and legacy** paths,
because a secret reference written in any shape must block first. Keys scanned:

| Location | Canonical key | Also scanned (legacy / variant) |
|---|---|---|
| Leaf filter | `fieldName` | `column`, `field`, `fieldname` |
| Sort element | `fieldName` | `column` |
| Root | (sorts elements above) | `sortBy` (string value) |
| Envelope | `fields[]` items | — |
| Group | `groupBy[]` items / `groupBy` (string) | — |
| Measure | `column`, `weightColumn`, `orderBy` | (no legacy alias) |
| Measure-level filter | (recurse into filter subtree, including `where` legacy subtree) | `where` |
| Having | (recurse into filter subtree) | — |
| Aggregate | `aggregateColumn` | — |

Any string extracted from these positions that names a `PASSWORD` column in the cached `DataShape`
→ `PROTECTED_TABULAR_COLUMN_BLOCKED`. The error message **never echoes the `value` content** of a
PASSWORD-referencing leaf (a leaked secret in `value` could otherwise appear in logs / model
context).

**Layer 2 legacy-shape rejection** then scans for the presence of any rejected legacy key from §7.1
(root `where` / `sort` / `limit` / `sortBy` / `direction`; leaf `op` / `column` / `field` /
`fieldname` / `operator` / `term` / `compare` / `predicate`; composite `all` / `any` / `not` as
root keys / `predicates` array; BETWEEN `min` / `max`; sort element `column` / `direction`;
measure `where`). If any are present, the request is rejected with the §8.3 echo message — without
checking whether the column names referenced via those legacy keys actually exist. That existence
check is a canonical-path-only concern (layer 3).

This split is important. A legacy leaf like `{"op":"eq","column":"doesNotExist","value":"x"}`
returns `INVALID_PREDICATE rejected legacy shape` (layer 2), **not** `INVALID_COLUMN` (layer 3) —
the strict clean break dominates over column-existence diagnostics for legacy shape. Only canonical
references that name a non-existent column return `INVALID_COLUMN`.

Tests must include:
- PASSWORD via every canonical key path → `PROTECTED_TABULAR_COLUMN_BLOCKED`;
- PASSWORD via every legacy key path → `PROTECTED_TABULAR_COLUMN_BLOCKED` (overrides legacy
  rejection);
- legacy `column` referencing a missing non-PASSWORD column → `INVALID_PREDICATE rejected legacy
  shape` (not `INVALID_COLUMN`);
- canonical `fieldName` referencing a missing column → `INVALID_COLUMN`;
- PASSWORD-referencing leaf error message does **not** contain the `value` content.

### 8.3 Error message conventions (required)

The `message` field on every error MUST echo the offending fragment AND list the recognized
alternatives so the LLM can self-correct on its next call without out-of-band documentation lookup.
In practice this collapses multi-round mis-shape loops to a single retry.

Required echo patterns by error class:

| Error class | Required message shape |
|---|---|
| **Unknown leaf key** (`INVALID_PREDICATE`) | `Leaf filter requires key 'X' (got keys: ['Y', 'Z', ...]). Recognized leaf keys: type, fieldName, value, values, from, to, isCaseSensitive, location, distance, units, tags. See docs/agent/query-spec.md §3.` |
| **Unknown filter `type`** (`INVALID_PREDICATE`) | `Unknown filter type '<got>'. Common types: EQ, NE, LT, LE, GT, GE, IN, NOTIN, BETWEEN, LIKE, MISSINGVALUE. Full list: query-spec.md §3.3.` |
| **Rejected `MATCHES`** (`INVALID_PREDICATE`) | `Filter type 'MATCHES' is not supported in cached predicate (regex DoS risk). Use LIKE with * / ? wildcards, or CONTAINS / STARTSWITH / ENDSWITH.` |
| **Unknown sort key** (`INVALID_PARAMETERS`) | `Sort entry requires 'fieldName' (got keys: ['X', 'Y', ...]). Recognized sort keys: fieldName, isAscending, isCaseSensitive. See query-spec.md §4.` |
| **Rejected legacy predicate shape** (`INVALID_PREDICATE`) | `Filter uses rejected legacy shape '{op, column, value}'. Canonical: '{type, fieldName, value}'. See query-spec.md §3.1.` |
| **Rejected legacy sort shape** (`INVALID_PARAMETERS`) | `Sort uses rejected legacy keys ('sortBy' / 'direction' / sort element 'column' / 'direction'). Canonical: 'sorts': [{'fieldName': '<col>', 'isAscending': true \| false}]. See query-spec.md §4.` |
| **Column-type mismatch on `NEAR`** (`TYPE_MISMATCH`) | `Filter type 'NEAR' requires a LOCATION column (got base type '<actual>' for column '<name>'). See query-spec.md §3.7.` |
| **Column-type mismatch on `TAGGED`** (`TYPE_MISMATCH`) | `Filter type 'TAGGED' requires a TAGS column (got base type '<actual>' for column '<name>'). See query-spec.md §3.7.` |
| **Column-type mismatch on `ISEMPTY`** (`TYPE_MISMATCH`) | `Filter type 'ISEMPTY' requires a string-family column (STRING, TEXT, GUID, *NAME, HYPERLINK, IMAGELINK, HTML, or XML — see query-spec.md §3.9); got base type '<actual>' for column '<name>'.` |

Implementation: each shape-class error inside `ParlerQueryFilterParser` and `InfoTableRowMatcher`
constructs its message from a small helper (`PredicateErrorMessages` or similar) so the exact
wording is centrally tested and stable. The structured fields (`status`, `code`) remain machine-
readable; the `message` is the model-readable echo.

Tests must assert both the `code` AND that the `message` contains the offending input echo
(matching the JSON keys / type / value the caller actually sent), so future refactors cannot quietly
strip the echo and re-introduce the mis-shape loop.

## 9. LLM-facing Tool Description Template

Replaces the prior `PREDICATE_JSON_GUIDE` text in `TabulateCachedResultToolSchema.java`. Designed to
fit in tool descriptions without bloating prompt tokens: a **short common path** that covers the 90%
case, plus a one-line pointer to the full grammar in this spec.

### 9.1 Common-path template (filter)

Applies to all filter-bearing fields (top-level `filters`, `measure.filters`, `having`):

```
ThingWorx Query JSON filter.
Leaf:      {"type":"<TYPE>","fieldName":"<col>", ...type-specific keys}
Composite: {"type":"AND"|"OR"|"NOT","filters":[<filter>, ...]}

Common types:
  EQ, NE, LT, LE, GT, GE  → "value"
  IN, NOTIN               → "values":[...]
  BETWEEN, NOTBETWEEN     → "from" + "to"
  LIKE, NOTLIKE           → "value" with * or % as multi-char wildcards, ? as one-char wildcard;
                            _ is literal (TWX `LikeFilter` convention; not SQL `_` semantics)
  MISSINGVALUE, NOTMISSINGVALUE → no extra keys (null check)

Example: {"type":"LT","fieldName":"x","value":30}
Example: {"type":"AND","filters":[
  {"type":"GT","fieldName":"x","value":0},
  {"type":"LT","fieldName":"x","value":30}]}

Additional supported types and full grammar: docs/agent/query-spec.md §3.
Optional "isCaseSensitive": true|false (filter default false; set true for strict matching).
Not supported: MATCHES (regex). Use LIKE or the additional types in §3.3.
```

### 9.2 Sort template

```
{"sorts":[{"fieldName":"<col>","isAscending":true|false}, ...]}
1–3 keys; stable tie-break by original row order.
Optional per key: "isCaseSensitive": true|false (default true; set false for case-folded order).
```

### 9.3 Projection template

```
{"fields":["col1","col2","col3"]}
Optional. Restricts and orders output columns. Empty array is invalid.
```

The "additional supported types" — `NEAR` / `NOTNEAR`, `TAGGED` / `NOTTAGGED`, `CONTAINS` family,
`STARTSWITH` / `ENDSWITH` family, `ISEMPTY` / `NOTEMPTY` — are documented in §3.3 only, not in the
tool description, to keep the inline LLM prompt small. Authors needing them can follow the spec
pointer.

## 10. Implementation Reference

The reference Java implementation is a **Parler-owned parser / wrapper**, not a copy of platform
`FilterFactory`. The wrapper:

1. Validates JSON shape, depth caps, leaf-count caps, column-existence, column-type policy (§3.7,
   §3.9), and `PASSWORD` guard (§8.1 / §8.2).
2. **Owns composite recursion itself.** `AND` / `OR` / `NOT` are walked by the parser, which
   recursively parses each child and assembles the resulting `IFilter` instances into the
   appropriate composite (`AndFilterCollection` for `AND`, `OrFilterCollection` for `OR`,
   Parler-owned `NotFilter` for `NOT`). The parser does **not** delegate composites to platform
   `FilterFactory.createFilter`, because a composite may contain Parler-extension leaves
   (e.g. `AND(EQ, CONTAINS)`) that the platform factory does not know how to construct.
3. For **TWX-native leaf types** (`EQ`/`NE`/`LT`/`LE`/`GT`/`GE`/`IN`/`NOTIN`/`BETWEEN`/`NOTBETWEEN`/
   `LIKE`/`NOTLIKE`/`MISSINGVALUE`/`NOTMISSINGVALUE`/`NEAR`/`NOTNEAR`/`TAGGED`/`NOTTAGGED`),
   **delegates each leaf** to `com.thingworx.types.data.filters.FilterFactory.createFilter(...)`.
   This keeps platform leaf semantics authoritative (range comparison, set membership, LIKE
   wildcard handling, NearFilter great-circle math) and removes drift risk if PTC fixes a bug in
   their leaf filters.
4. For **Parler-extension leaf types** (`CONTAINS`/`NOTCONTAINS`, `STARTSWITH`/`NOTSTARTSWITH`,
   `ENDSWITH`/`NOTENDSWITH`, `ISEMPTY`/`NOTEMPTY`), constructs Parler-owned `IFilter` instances
   directly. These classes implement `com.thingworx.types.data.filters.IFilter` so they compose
   transparently inside parser-built `AND` / `OR` / `NOT` trees alongside platform-built leaf
   filters.
5. For `MATCHES` / `NOTMATCHES`, rejects at the wrapper entry with `INVALID_PREDICATE`. The platform
   `FilterFactory` source is **not** modified — the platform retains regex support for its own
   queries; only the Parler cached-table path declines it.
6. For `NEAR`, normalizes `units` per §3.8 **before** delegating to platform `NearFilter`. Rejects
   unknown units with `INVALID_PREDICATE` so they cannot silently fall through to platform's
   miles-default behavior.

Layout (illustrative; exact class names may vary during implementation):

```
parler-agent/src/main/java/com/thingworx/things/agent/tools/predicate/
├── ParlerQueryFilterParser.java // entry: validates, delegates TWX-native to FilterFactory,
│                                //         constructs Parler extension filters, rejects MATCHES
├── NotFilter.java               // Parler extension: composite NOT — wraps one child IFilter (§3.2.1)
├── ContainsFilter.java          // implements IFilter; negate flag for NOTCONTAINS
├── StartsWithFilter.java        // implements IFilter; negate flag for NOTSTARTSWITH
├── EndsWithFilter.java          // implements IFilter; negate flag for NOTENDSWITH
├── EmptyFilter.java             // implements IFilter; negate flag for NOTEMPTY (string-family per §3.9 cat 3 + cat 4)
├── InfoTableRowMatcher.java     // adapter: applies IFilter to a row of an InfoTable + DataShape
└── ParlerSort.java              // TWX-style sort wrapper over Comparator<ValueCollection>
```

Each Parler extension `predicate/*.java` class documents that it intentionally mirrors ThingWorx Query Filter
naming and shape (`com.thingworx.types.data.filters.IFilter` / `FilterFactory` constants). `NotFilter` additionally
cites §3.2.1 — composite `NOT` is a Parler extension because platform `FilterFactory` has no equivalent JSON dispatch.

Because the wrapper *delegates* to platform `FilterFactory` for native types and only *adds* extension filter classes
alongside, Parler never modifies or renames anything in the platform filter system.

## 11. Complete Answer Set Marker

`tabulate_cached_result` and related cached-tabular tools may mark a small returned result as a complete answer set for
the LLM. This marker is a **strict tool-result contract** used by routing guidance and AgentLoop performance logic. It is
not a UI wire contract.

### 11.1 Shape

When the marker is present and true, the tool result MUST include the following fields:

```json
{
  "answerSetComplete": true,
  "sampleOnly": false,
  "rowsOmitted": false,
  "returnedRows": 21,
  "totalRows": 21,
  "rows": []
}
```

Field semantics:

| Field | Meaning |
|---|---|
| `answerSetComplete` | Strict boolean marker. `true` means `rows[]` contains every output row for the query the tool executed. |
| `sampleOnly` | MUST be `false` when `answerSetComplete=true`. |
| `rowsOmitted` | MUST be `false` when `answerSetComplete=true`. |
| `returnedRows` | Count of rows returned in `rows[]`. MUST equal `totalRows` when `answerSetComplete=true`. |
| `totalRows` | Count of rows in the tool output after filters / `having` / projection semantics for this request. |
| `rows` | Complete output rows. Not a preview, sample, or representative subset. |

### 11.2 Strict Eligibility

A tool may emit `answerSetComplete=true` only when **all** of these are true:

1. `returnedRows == totalRows`.
2. `rows[]` contains every output row after the tool's own filtering, aggregation, `having`, sorting, offset, and projection semantics.
3. Row count is at most `50`.
4. Serialized `rows[]` payload is at most `8192` bytes/chars by the implementation's JSON serialization check.
5. Projected column count is at most `8`.
6. No projected column is protected or policy-blocked (for example `PASSWORD`).
7. The marker emitter is enabled for the turn/deployment.

If any condition fails, the tool MUST omit `answerSetComplete` or set it to `false`. It MUST NOT use the marker for
"mostly complete", "sample looks representative", or "first N rows are probably enough" cases.

### 11.3 `group_metric` Semantics

For `group_metric`, `totalRows` under the marker is the count of the grouped output rows after `having` is applied.

Example:

```text
groupCount=172
having utilization_pct == 0
matchCount=21
totalRows=21
returnedRows=21
answerSetComplete=true
```

The marker does not imply all source rows matched. It means all **output rows for the executed grouped query** are present.

Regardless of the marker, `group_metric` success JSON uses **`totalRows` = post-having grouped row count** (the same figure as **`matchCount`** on the result), including when the paged **`rows[]`** slice is empty because **`offset`** is at or past the end (`rows` may be `[]` while **`matchCount` > 0**). When `maxItems` / `offset` truncate the page placed in `rows[]` or in the **LARGE** cache, **`answerSetComplete=true` MUST NOT** be emitted, because `rows[]` is not the full post-having set.

### 11.4 LLM Routing Rule

Model-facing routing text should treat the marker as terminal:

```text
If a tabular tool result has answerSetComplete=true, or has sampleOnly=false with returnedRows == totalRows,
answer from that result. Do not call fetch_cached_result again unless the user explicitly asks for more columns,
raw page browsing, or export/download. Never infer a full answer set from sampleRows when answerSetComplete is absent.
```

This rule is deliberately tied to the strict marker. `sampleRows`, sorted previews, and `totalRows` without complete rows
remain insufficient proof of membership.

### 11.5 Runtime enforcement (`AgentLoop`)

Server-side enforcement of post-marker no-tool rounds, protocol-violation accounting, and
turn-performance telemetry on the terminal assistant row is normative in
**`docs/agent/llm-performance.md`** (this query spec defines marker **shape and eligibility**
only; the agent loop applies the kill-switch and wire merge). Post-marker provider
requests use an **empty** tool list on the wire (vendors reject `tool_choice` without
tools; Parler omits both — see **`docs/agent/llm-performance.md`** §7).

`fetchAfterCompleteAnswerSetCount` and related ordering semantics rely on **Agent runtime
tool dispatch order** (today: serial execution, `maxConcurrency=1`), not on wire-level
`parallel_tool_calls` / `disable_parallel_tool_use` flags (Parler does not send those on
routing rounds).

## 12. Intentional Trade-offs

Design choices and their rationale.

| Decision | Choice | Rationale |
|---|---|---|
| `isCaseSensitive` default | Filter `false`, sort `true` (split per TWX sub-system) | Zero deviation from each TWX origin (`RangeFilter` / `SetFilter` / `LikeFilter` vs `SortParser`); matches industrial-data linguistic expectations. See §3.5. |
| `MATCHES` / `NOTMATCHES` | Rejected at Parler wrapper entry; platform `FilterFactory` unchanged | Regex DoS over 100k rows. The rejection lives only in the wrapper's rejection set; the platform retains regex for its own queries. |
| `LIKE` wildcards | TWX convention (`*` / `%` multi-char, `?` single-char) | Match `com.thingworx.types.data.filters.LikeFilter` 1:1. SQL `_` single-char wildcard is **not** supported (would require parler-specific deviation; rejected on TWX parity). |
| `BETWEEN` requires both bounds | Yes (parler tightening over TWX) | TWX `BetweenFilter` accepts single-sided ranges, but a single-sided BETWEEN is identical to `LT`/`GT`/`LE`/`GE`. Requiring both bounds keeps the LLM's expected shape unambiguous and avoids "did you mean LT?" ambiguity. |
| `CONTAINS` / `STARTSWITH` / `ENDSWITH` as separate types | Yes, not just `LIKE *v*` | LLM-friendlier JSON; escape-safe (no need to encode `*` / `?` in `value`). |
| `ISEMPTY` / `NOTEMPTY` separate from `MISSINGVALUE` | Yes | Distinct semantic: null vs empty string. TWX `MISSINGVALUE` doesn't distinguish. |
| `fields` projection | New parler extension | LLM evidence-token efficiency; one round-trip cost saved vs separate ProjectFields call. |
| Per-measure filter key | `filters` (TWX-aligned), not `where` | Consistency: every filter-shaped field uses `filters`. |
| `having` (post-aggregation filter) | Kept name `having`, uses filter shape | SQL-universal name, TWX has no analog, filter SHAPE is canonical. |
| `offset` retained | Yes | Parler-extension; useful for tool-level pagination. |
| `sorts[]` always array (no single-object form); **no legacy `sortBy` / `direction` accepted** | Strict canonical-only | Clean break. Legacy keys are rejected with `INVALID_PARAMETERS` pointing at this spec. No silent mapping. |
| Composite `NOT` | **Parler extension** | JSON shape is TWX-style for LLM / authors; platform `FilterFactory` does not dispatch composite `NOT` — Parler implements `NotFilter` + tests (§3.2.1). |
| Implementation strategy | **Wrapper + delegation**, not source copy | Avoids fork / drift: `ParlerQueryFilterParser` validates and delegates TWX-native types to platform `FilterFactory.createFilter(...)`; Parler-only extensions are added as new `IFilter` classes that compose transparently. Platform `FilterFactory` source is not modified. See §10. |
| Error messages echo unknown keys / types | **Required** (see §8.3) | Live observation: LLM sampled `{type, field, value}` / `{type, fieldName, value}` / `{type, operator, value}` before landing the canonical shape. Error messages that echo the offending keys + list the recognized set collapse the convergence loop to a single retry. Implementation enforces this by constructing every shape-class error's `message` field from a central `PredicateErrorMessages` helper (single `message` field — no new tool-result fields introduced); tests assert echo content, not just `code`. |

## 13. Versioning

The canonical shape covers:

- filter shape (`filters`, `having`, `measure.filters`);
- sort shape (`sorts[]`);
- envelope (`maxItems`, `offset`, `fields`);
- `NEAR`;
- `TAGGED`;
- `CONTAINS` / `NOTCONTAINS` / `STARTSWITH` / `NOTSTARTSWITH` / `ENDSWITH` / `NOTENDSWITH`;
- `ISEMPTY` / `NOTEMPTY`;
- composite `NOT`;
- error-message echo per §8.3.

**No backward-compatibility shim.** Any caller submitting any of the rejected legacy shapes
enumerated in §7.1 (root `where` / `sort` / `limit` / `sortBy` / `direction`; leaf `op` / `column` /
non-canonical keys; composite `all` / `any` / `not` root keys or `predicates` array; BETWEEN
`min` / `max`; sort element `column` / `direction`; measure `where`) receives `INVALID_PREDICATE`
(filter shape) or `INVALID_PARAMETERS` (sort / envelope shape) with the corresponding echo message
per §8.3. Mixed payloads (some canonical + some legacy keys) also reject — never silently ignore
legacy keys.

This spec defines shape only. Business semantics (for example whether `lower than 30%` should include
`0%` utilization assets, or how `ratio_percent` handles a `null` numerator / denominator) are defined by the
cached-table tools, not here.

Later revisions extend this file by appending sections; field names and shapes defined here are stable.
