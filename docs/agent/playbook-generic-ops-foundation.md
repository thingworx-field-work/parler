# Playbook generic ops foundation

Status: implemented — the Priority 1 generic derive ops below, plus the service-orchestration ops of
**`playbook-34-35`** (see §17).

## 0. Context

**Relationship to `playbook-builtin-capability-expansion`.** Built-in tool expansion
makes Playbook-safe calls such as `query_alert_history` available. Generic ops do not
replace that work; the reference generic path in section 11 relies on alert-history rows
being available to downstream `project` / `group_by` / `top_n`.

**Normative surfaces.** Primary specs stay in this file and `docs/agent/playbook-engine.md`.
`CONTRACTS/*` updates are required only if observable wire shapes or UI contracts change;
derive-only JSON stays in repository playbook files and Java validation.

**Runtime touchpoints (Java).**

| Area | Types |
| --- | --- |
| Derive execution | `PlaybookDeriveOps`, `PlaybookDeriveOpsV1b`, **`PlaybookGenericDeriveOps`** (called from `PlaybookDeriveOps.execute` before `PlaybookDeriveOpsV1b`) and the `PlaybookGeneric*` helpers |
| Validation | `PlaybookValidator` (`GENERIC_DERIVE_OPS` allowlist, per-op arg shape, caps); legacy business derive ops validate only at execution time |
| Expressions | `PlaybookExpressionResolver` (see sections 6.1 and 6.5) |
| Row predicates | `PlaybookRowPredicate` calling **`PlaybookPredicateValueOps`** — shared `isEmpty` / `compare` with `PlaybookConditionEvaluator` |
| Evidence | `PlaybookNodeEvidence` patterns consistent with existing derive ops; sizes per section 9 |
| Constants | `PlaybookGenericOpsConstants` (sections 6.3–6.4) |
| Fixtures / playbooks | Repository JSON under the playbook configuration paths in `playbook-engine.md` |

## 1. Background

Parler Playbooks started as focused workflows that proved the runtime:

- static repository Playbook catalog,
- `PlaybookJson`,
- DAG validation,
- `tool_call`,
- `fan_out`,
- `derive`,
- `condition`,
- final `llm_summary`,
- task progress,
- compact evidence handoff.

The current implementation can run real workflows such as `cross_region_health` and
`cross_asset_pair_health`. However, those workflows still depend heavily on
business-specific Java derive operations:

```text
pick_taxonomy_row
flatten_region_entities
build_property_union
group_alerts_by_source_property
summarize_region_health
match_entity_identifiers
select_primary_problem_property
trend_targets
summarize_asset_pair_health
```

If every new business Playbook required a new Java derive op, Playbooks would remain a
curated demo surface instead of an app-developer workflow runtime.

`playbook-builtin-capability-expansion` broadens which built-in tools can run inside
Playbooks, so Playbooks can call the same tool surface that Skills use. Generic ops close
the next gap:

> Once tools return rows, lists, and compact objects, Playbooks need generic operations
> to transform that evidence without adding Java code for each business workflow.

This is exactly why Python DAG libraries feel convenient. The developer writes a
workflow in a familiar style, and the framework gives them generic map/filter/group/topN
building blocks. Parler does not need to copy Airflow, Prefect, Dagster, or LangGraph.
It needs the same practical lesson: a DAG runtime becomes useful when common data-flow
steps are generic.

## 2. Problem statement

Graph structure alone is not enough; a Playbook engine also needs data-flow vocabulary.

For example, the `asset_pair_health` Skill naturally says:

```text
1. query alert history for both assets
2. count sourceProperty occurrences
3. pick the top two alert-driving properties
4. build trend targets for each asset x property
5. query property history for every target
6. summarize evidence and gaps
```

Without generic ops, this requires special Java code:

```text
group_alerts_by_source_property
select_primary_problem_property
trend_targets
summarize_asset_pair_health
```

The V1b business path selects one primary property, not the richer "top two properties x
two assets" workflow. That limitation is not a tool problem; it is an operations problem.

The Priority 1 generic operations express common Skill-to-Playbook conversions:

- select fields,
- filter rows,
- sort rows,
- pick top N rows,
- group rows,
- aggregate rows,
- build bounded fan-out targets,
- join bounded row sets,
- pick one row or clarify,
- collect evidence gaps.

## 3. Boundaries

Same delivery boundary as `docs/agent/playbook-builtin-capability-expansion.md` §2.

Rules:

- Implement deterministic operations, not a general programming language.
- Use the existing `derive` node kind; no new node kind.
- Use the existing expression/reference model where possible: literals, `$input`, `$var`,
  `$ref`, and `$item`.
- Keep path grammar simple: `identifier(.identifier)*`.
- No JavaScript execution inside `PlaybookJson`.
- No arbitrary JSONPath filters, regex engines, or script expressions.
- **Engine caps (normative):** Author **literals** (`n`, `maxGroups`, `maxRows`, `maxItems`,
  `maxTargets`, …) are capped per section **6.3**; the validator MUST reject above those
  ceilings. **Resolved** array sizes and max output row counts are bounded at **runtime**
  per section **6.4**. The runtime MUST enforce per-op output caps (truncate + deterministic
  gap, or fail-closed before execution where specified in section 6.2) so a single derive
  cannot allocate unbounded row/cartesian space. This does **not** relax tool-level fetch
  sizing; it bounds **derive outputs** and **in-engine cartesian expansion**.

The product principle:

> Make common row/list transformations easy, predictable, and bounded. Do not build a
> general expression language.

## 4. Operations

Priority 1 generic Playbook operations:

1. `project`
2. `filter`
3. `sort`
4. `top_n`
5. `group_by`
6. `aggregate`
7. `build_targets`
8. `join_by_key`
9. `pick_one`
10. `collect_gaps`

They are available as `derive` ops:

```json
{
  "id": "top_alert_properties",
  "kind": "derive",
  "dependsOn": ["alert_rows"],
  "op": "group_by",
  "args": { "...": "..." }
}
```

Every op has validator coverage, execution tests, and evidence line generation. The
reference Playbook in section 11 uses the generic ops instead of business-specific derive
ops, and `docs/agent/playbook-engine.md` carries the quick reference.

## 5. Non-goals

Generic ops do not:

- redesign the Playbook JSON schema;
- add a JavaScript DAG factory;
- replace existing business-specific derive ops (they remain for compatibility);
- provide subplaybooks;
- provide arbitrary scripting;
- provide a full query language inside Playbook derive nodes.

Existing business-specific ops can remain for compatibility. The goal is to stop needing
new ones for ordinary row/list work.

## 6. Common operation model

### 6.0 Legacy derive ops vs Priority 1 generic ops

The fifteen business / region derive ops return **bespoke** `output` shapes
(`pick_taxonomy_row`, `summarize_region_health`, `build_property_union`, …). Authors
already `$ref` those shapes per-playbook.

**Priority 1 generic ops** adopt the **normative envelopes** in
sections 6.1–6.2 so AI conversion and new playbooks can rely on stable paths such as
`node.output.rows`, `node.output.targets`, or `node.output.gaps`. Mixing families in one
playbook is allowed; authors MUST know which envelope they read.

### 6.1 Normative JSON envelope (generic ops)

**Required top-level keys:** every Priority 1 generic derive result MUST include **`status`**
(string) and **`output`** (object). All other top-level keys are optional unless an op’s
spec says otherwise. This applies even when **`status`** is **`needs_clarification`**:
**`output`** MUST still be present (for example **`pick_one`** uses the same zero-count
shell as **`gap`** / **`empty`**: **`row`** JSON null, **`totalCount`** / **`returned`** zero,
**`gaps`** array, plus a top-level **`message`**).

**`evidenceLines`:** when an op has one or more evidence strings, it MUST attach them via
the same mechanism as existing derive ops (`PlaybookNodeEvidence.attach` /
`attachLinesOnly`). When there are **zero** lines, the result **MAY omit** `evidenceLines`
(and `evidenceText`) so validator fixtures and tests match `attach` behavior
(absent vs empty is equivalent for consumers). Do **not** treat the illustrative JSON
snapshots below as requiring a physical `evidenceLines` key when empty.

**Illustrative** smallest shape (no evidence lines attached):

```json
{
  "status": "ok",
  "output": {}
}
```

**Illustrative** row-op `output` body (counts plus rows; evidence omitted when empty):

```json
{
  "status": "ok",
  "output": {
    "rows": [],
    "totalCount": 0,
    "returned": 0,
    "gaps": []
  }
}
```

Common rules:

- Input arrays are treated as ordered lists.
- Row inputs are JSON objects unless the op explicitly supports scalar lists.
  For resolved **arrays** of rows, each element MUST be a JSON object; non-object / null elements
  are a runtime **`GENERIC_INPUT_INVALID`** (shared **`PlaybookGenericRowArrays.requireEachSlotIsObject`**)
  — row ops MUST NOT silently skip bad slots.
- Missing fields evaluate to `null`.
- Null comparison behavior must be deterministic and tested.
- Output row order must be stable.
- Ops should not mutate prior node outputs.
- Ops should attach short `evidenceLines` when the result is meaningful.
- Ops should record gaps rather than inventing facts.

Common input source fields:

| Field | Meaning |
| --- | --- |
| `rows` | Explicit array or reference resolving to rows. |
| `source` | Alias for `rows` when the input is not necessarily row-shaped. |
| `fields` | Field selection / projection config. |
| `where` | Simple predicate object. |
| `orderBy` | Sort specification. |
| `maxItems` | Bounded output cap. |

The implementation can choose exact argument names, but they must be documented and
tested.

### 6.2 Normative per-op outputs, primary `$ref` paths, and overflow

All **literal** numeric caps in this table are subject to the **validator** ceilings in
section 6.3. **Resolved array sizes** (`$ref` inputs) are bounded only at **runtime**
(section 6.4) — the validator cannot see them. **Overflow** (too many output rows, groups,
or targets for an op’s output cap) means the runtime MUST emit a deterministic gap entry,
then **truncate** (never silent drop without a gap). `totalCount` reflects logical size
before truncation where feasible; `returned` is the serialized array length after truncation.

| Op | Primary consumer paths | `output` layout (beyond shared fields) | Overflow / special |
| --- | --- | --- | --- |
| `project` | `.output.rows`, `.output.gaps` | Standard row envelope | Input row slots ≤ `MAX_GENERIC_INPUT_ROWS` (**fail closed**). With `MAX_GENERIC_INPUT_ROWS` == `MAX_GENERIC_OUTPUT_ROWS`, **`project`** does not expand row count, so output-side truncation is **unreachable** here; use shared **`PlaybookGenericRowArrays.truncateIfNeeded`** in **expanding** ops (`join_by_key`, cartesian `build_targets`, …). |
| `filter` | `.output.rows` | Standard row envelope | Same |
| `sort` | `.output.rows` | Standard row envelope | Same |
| `top_n` | `.output.rows` | Standard row envelope; gap if fewer than requested `n` | `n` ≤ ceiling; input bounded |
| `group_by` | `.output.rows` | Standard row envelope; measures as columns | `maxGroups` **required**; on overflow default = **gap + truncate**; optional `_other` bucket **only** when `foldOverflowToOther: true` |
| `aggregate` | `.output.<measureName>`, `.output.gaps`, counts | Scalar fields per measure + `gaps` array; no `rows` | Empty input → numeric `null` + gap per measure rules |
| `build_targets` | `.output.targets`, `.output.gaps`, counts | `targets` array of objects built from template | **`maxTargets` required** (validator: ≤ `MAX_GENERIC_TARGETS`, section 6.3). Cartesian product capped at built size, then output length ≤ `MAX_GENERIC_TARGETS`. Because `targets` feed **`fan_out`**, this ceiling is kept **on the order of playbook tool budgets** (see `playbook-engine.md` `maxToolCalls` / `fan_out.maxItems` examples). There is no graph-aware cap: when downstream `fan_out` `maxItems` is lower than the built list, author discipline plus this literal cap apply. |
| `join_by_key` | `.output.rows` | Standard row envelope | **`maxRows` required**; duplicate **right** keys → **multiple output rows** (one per match), deterministic order: **left row order**, then **first-seen right row order** among duplicates |
| `flatten_fan_out_rows` | `.output.rows` | Standard row envelope | **`fanOutNodeId`** required; must name a **`fan_out`** whose inner node is **`tool_call`**. Concatenates each successful child's **`toolOutput.rows`** in iteration order; shallow row copy; optional **`injectFromItem`** array of **`{ "from", "as" }`** objects (same path rules as **`project`** `from`; values read from the fan-out **`item`** via **`PlaybookJsonRowPath`**) — only fills **`as`** when the copied row does not already have that key. **`output.totalCount`** = count of valid object row slots merged before the cap; **`output.returned`** = materialized **`rows.length()`** (≤ **`MAX_GENERIC_INPUT_ROWS`**); when **`totalCount` > `returned`**, a deterministic truncation gap is appended. |
| `pick_one` | **`.output.row`** (normative; documented under **`derive`** in `playbook-engine.md`) | When **`status: ok`**, exactly **one** object at **`output.row`** plus counts / gaps. When **`status: needs_clarification`**, **`output.row`** is JSON null with zero counts (same envelope shell as other no-pick branches); top-level **`message`** carries the human text. | Predicate uses row model in section 7 |
| `collect_gaps` | `.output.gaps`, `.output.totalCount`, `.output.returned` | Merged gap list | See section 8.10 — **no opaque nested blobs**; **`totalCount`** = deduped merge size before **`maxItems`** clip, **`returned`** = serialized **`gaps`** length |

### 6.3 Author literal ceilings (**validator**-enforced)

These values appear as numeric **literals** in `PlaybookJson`. The validator MUST reject
values above the ceiling. (Concrete constants live in Java.)

| Constant | Value | Where |
| --- | --- | --- |
| `MAX_GENERIC_TOP_N` | 5_000 | `top_n.n` |
| `MAX_GENERIC_GROUPS` | 5_000 | `group_by.maxGroups` |
| `MAX_GENERIC_TARGETS` | **200** | `build_targets.maxTargets` — aligned with representative playbook **`maxToolCalls`** / **`fan_out`** budgets so authors cannot validate a target list the next `fan_out` cannot execute |
| `MAX_GENERIC_JOIN_OUTPUT_ROWS` | 10_000 | `join_by_key.maxRows` |
| `MAX_COLLECT_GAPS_ITEMS` | 64 | `collect_gaps` merged entry count after `maxItems` |
| `MAX_GAP_TEXT_CHARS` | 512 | String gaps and string fields in structured gaps |

### 6.4 Resolved data size bounds (**runtime**-enforced only)

These limits apply to **resolved** arrays (`$ref` → node outputs) and output row counts.
The validator **cannot** see resolved lengths at load time. Runtime MUST enforce:

| Constant | Value | Behavior |
| --- | --- | --- |
| `MAX_GENERIC_INPUT_ROWS` | 10_000 | If resolved input `rows` / `left` / `right` **or each array value inside `build_targets.sources`** exceeds this length **before** the op runs, the op MUST **fail closed** with an actionable error — do not silently truncate upstream evidence. |
| `MAX_GENERIC_OUTPUT_ROWS` | 10_000 | If an op would emit more than this many rows in `output.rows`, truncate and emit a deterministic gap (section 6.2 overflow). |

**`build_targets` cartesian materialization:** the runtime MUST **short-circuit**
target construction — it MUST NOT allocate the full Cartesian product in memory and
then truncate to `maxTargets`. Iterate sources in **ascending lexicographic name order**
(§8.7; not `JSONObject` key order) and stop as soon as
`maxTargets` targets are produced (or inputs exhaust).

Serialized evidence: reuse the same **per-node evidence byte budget** discipline as heavy
derive ops (8 KiB class pattern in `PlaybookDeriveOps` / `PlaybookDeriveOpsV1b` summaries)
and the playbook **`maxEvidenceBytes`** ledger budget documented in `playbook-engine.md`
— generic `evidenceLines` MUST NOT embed raw rows or unbounded JSON; `collect_gaps`
MUST reject or stringify-safe bounded entries only (section 8.10).

### 6.5 Expression model and `build_targets`

`PlaybookExpressionResolver` resolves literals and **`$input`**, **`$var`**, **`$ref`**,
**`$item`** (and isolated **`$table`** rules for tool calls). **`$path`** is a narrow extra
binding form valid **only** inside `build_targets` **`template`** subtrees, written as
**`{ "$path": "sourceName.dottedField" }`**; the validator rejects it everywhere else.
During the cartesian loop, each template value resolves against the current combination of
named **`sources`** rows — the multi-list analogue of `fan_out`’s **`$item`** / `itemVar`
binding (see `playbook-engine.md` **fan_out** rules under section 4.3). Pre-shaped rows
without `$path` remain a valid author style.

## 7. Predicate model

`filter`, `pick_one`, and possibly `join_by_key` need simple predicates.

Use the **same operator set and composition shapes** as `condition` nodes, evaluated
by `PlaybookConditionEvaluator` semantics (`PlaybookValidator` already enumerates allowed
leaf ops). Composite predicates use **`and`**, **`or`**, **`any`**, **`not`** JSON keys
with nested predicate objects — **not** `{ "op": "and", "predicates": [...] }`, which does
not match the existing condition wire shape.

Row-level evaluation differs from graph-level `condition` nodes: leaves need a **field**
(or equivalent) that resolves **relative to the current JSON row object** (dotted path,
same grammar as `project.from`):

- `PlaybookRowPredicate` resolves the unary/binary operand for each leaf, then delegates
  comparisons to **`PlaybookPredicateValueOps`** (`isEmpty`, `compare`) — the same
  primitives **`PlaybookConditionEvaluator`** calls. There is no second copy of the
  comparison logic.

**Fail-closed leaf rules (validator + runtime):**

- Exactly **one** left-value source among `field` (row-relative dotted path), `left`, and
  `value`. More than one MUST be rejected. Zero leaves `left` unset → reject for non-`not`
  leaves.
- `field`, when present, MUST use the same dotted identifier grammar as `project.from`;
  it supplies the implicit **`left`** operand for the leaf (never mixed with a second
  `left`/`value`).
- `right` remains a literal or any object the **`PlaybookExpressionResolver`** already
  accepts (no row-to-row comparisons; `$path` is not overloaded for them).

**`is_present` / blank strings:** `is_empty` treats **blank strings** as empty (same as
`PlaybookPredicateValueOps.isEmpty`). Therefore `is_present` is false for `""`. Authors
should not add redundant `ne` / `not` pairs against `""` when `is_present` already
captures the intent.

Leaf ops (same strings as condition):

- `is_empty`
- `is_present`
- `eq`
- `ne`
- `gt`
- `gte`
- `lt`
- `lte`

Composition:

- `and` → array of nested predicates
- `or` → array of nested predicates
- `any` → array of nested predicates (same disjunctive meaning as `or` in current runtime)
- `not` → single nested predicate object

Each predicate object MUST have **exactly one** structural branch among `and`, `or`, `any`, `not`, and a
non-blank leaf `op` — multiple present keys are a validator and runtime error (no priority-based
ignore). `and` / `or` / `any` arrays MUST be **non-empty** JSON **arrays** whose elements are JSON **objects**;
each element MUST satisfy the same structural rules recursively. `not` MUST bind a JSON **object** (not a
scalar or array). **Implementation:** validator and runtime both call **`PlaybookRowPredicate.requireValidShape`**
on the full predicate tree (single DFS) so malformed payloads cannot diverge between load-time checks and
`filter` / `pick_one` execution.

Example (composition that is not redundant — `is_present` on name **and** numeric floor on count):

```json
{
  "and": [
    { "op": "is_present", "field": "sourceProperty" },
    { "op": "gt", "field": "alertCount", "right": 0 }
  ]
}
```

Unary/binary value rules: graph-level `condition` leaves use `value` or `left` resolved
through `PlaybookExpressionResolver`. Row-level leaves use **`field`** as the implicit
`left` from the current row object; `right` still resolves through the resolver when it is
a JSON object.

There is no regex. String matching uses exact, case-insensitive, or normalized equality
only when explicitly specified.

## 8. Operation specifications

### 8.1 `project`

Purpose: select and rename fields from rows or objects.

Example:

```json
{
  "op": "project",
  "args": {
    "rows": { "$ref": "alert_rows.output.rows" },
    "fields": [
      { "from": "thingName", "as": "thingName" },
      { "from": "sourceProperty", "as": "property" },
      { "from": "severity", "as": "severity" }
    ],
    "dropNullOnlyRows": false
  }
}
```

Rules:

- `from` uses simple dotted path grammar: `identifier(.identifier)*` with no empty segments (each segment
  `[A-Za-z_][A-Za-z0-9_]*`). Malformed paths MUST fail at playbook load (`PlaybookGenericPathGrammar` /
  `PlaybookValidator`), not only at runtime.
- Resolved `rows` MUST be a JSON **array** of row objects or a single JSON **object** (treated as one row).
  Other resolved types MUST fail closed at runtime (`GENERIC_INPUT_INVALID`). Each array slot MUST be an
  object (not null, not scalar); enforced by **`PlaybookGenericRowArrays.requireEachSlotIsObject`**.
- `as` must be a non-blank output field name (same identifier grammar as node ids).
- **`as` values MUST be unique** within `fields`; duplicates are a validator error (no silent last-write-wins).
- **`as` MUST NOT** equal a reserved envelope or synthetic field name: `rows`, `totalCount`,
  `returned`, `gaps`, `status`, `targets`, `row`, `output`, `_other` (same rule applies to
  `aggregate` measure `name` and `group_by` measure names vs `keys` — see section 13).
- Missing input field yields JSON `null` (the output row MUST retain the key with JSON null) unless `default`
  is provided. Java `JSONObject.put(key, null)` omits keys; the implementation MUST use explicit JSON-null
  encoding (`JSONObject.NULL`) for missing paths.
- `default` is applied before evaluating `dropNullOnlyRows` (a row filled only by defaults is not “all-null”).
- Output order follows input row order.
- When projected row count exceeds `MAX_GENERIC_OUTPUT_ROWS`, expanding ops MUST use
  **`PlaybookGenericRowArrays.truncateIfNeeded`** (deterministic gap, `totalCount` / `returned`). **`project`**
  does not expand rows and is bounded by the input cap equal to the output cap, so it does not apply
  output truncation at runtime; input over `MAX_GENERIC_INPUT_ROWS` fails closed.
- Evidence MUST report input row-slot count, emitted row count after projection (before output-cap truncation),
  and field-spec count; when `dropNullOnlyRows` skips rows, the evidence line MUST say how many were skipped.

**Java:** `project` is implemented in `PlaybookGenericDeriveOps` with
`PlaybookGenericOpsConstants` ceilings, `PlaybookGenericPathGrammar` for `from`, `PlaybookGenericRowArrays`
for strict row slots, and `PlaybookValidator` arg checks.

### 8.2 `filter`

Purpose: keep rows matching a simple predicate.

Example:

```json
{
  "op": "filter",
  "args": {
    "rows": { "$ref": "project_alerts.output.rows" },
    "where": { "op": "is_present", "field": "property" }
  }
}
```

Rules:

- Predicate language is the shared model in section 7.
- Missing fields are treated as `null`.
- Output order is stable.
- Evidence MUST report input row-slot count, kept row count, and dropped row count (e.g. `filter: input N …; kept K …; dropped D …`).

**Java:** `filter` is implemented in `PlaybookGenericDeriveOps` with `PlaybookRowPredicate`
(row-relative leaves + `PlaybookPredicateValueOps`), `PlaybookGenericRowArrays.loadResolvedRowArgs`,
`PlaybookJsonRowPath` for `field` paths, and `PlaybookValidator` `where` / row-predicate checks.

### 8.3 `sort`

Purpose: sort rows by one or more fields.

Example:

```json
{
  "op": "sort",
  "args": {
    "rows": { "$ref": "property_counts.output.rows" },
    "orderBy": [
      { "field": "alertCount", "direction": "desc" },
      { "field": "property", "direction": "asc" }
    ]
  }
}
```

Rules:

- Stable sort.
- Supported directions: `asc`, `desc`.
- Null ordering must be deterministic; recommended: nulls last for ascending and
  descending.
- Compare numbers as numbers and strings as strings; mixed types fall back to string
  comparison.

**Java:** `sort` is implemented in `PlaybookGenericDeriveOps` with stable
`List.sort`, `PlaybookGenericRowOrdering` (nulls-last field compare), `PlaybookJsonRowPath` for
`orderBy[].field`, and `PlaybookValidator` checks on `orderBy` entries (dotted path, `asc`/`desc`).

### 8.4 `top_n`

Purpose: select the first N rows after optional sort.

Example:

```json
{
  "op": "top_n",
  "args": {
    "rows": { "$ref": "property_counts.output.rows" },
    "orderBy": [{ "field": "alertCount", "direction": "desc" }],
    "n": 2
  }
}
```

Rules:

- `n` must be non-negative and **≤ `MAX_GENERIC_TOP_N`** (section 6.3); validator rejects above the ceiling.
- `n = 0` returns an empty list.
- If `orderBy` is supplied, apply stable sort first.
- If fewer than N rows exist, return all rows and add a gap such as
  `top_n returned fewer rows than requested`.

**Java:** `top_n` shares row loading and optional `orderBy` handling with `sort`;
validator enforces integer `n` in `0 … MAX_GENERIC_TOP_N` and rejects empty `orderBy` arrays.

### 8.5 `group_by`

Purpose: group rows by one or more keys and optionally compute measures.

Example:

```json
{
  "op": "group_by",
  "args": {
    "rows": { "$ref": "filtered_alerts.output.rows" },
    "keys": ["property"],
    "measures": [
      { "name": "alertCount", "op": "count" }
    ],
    "maxGroups": 100
  }
}
```

Output rows should look like:

```json
[
  { "property": "operationalVoltage", "alertCount": 46 },
  { "property": "contactForce", "alertCount": 31 }
]
```

Supported measure ops:

- `count`
- `count_present`
- `sum`
- `min`
- `max`
- `mean`

Rules:

- **`keys`:** each entry MUST be a **single path segment** (identifier matching `PlaybookGenericPathGrammar`,
  **no dots**). Nested source values require a prior **`project`** (or equivalent) that exposes a flat top-level
  field for grouping — dotted paths in **`keys`** are rejected so output column names stay navigable via **`$ref`**
  and dotted paths elsewhere.
- When **`measures`** is **omitted**, there are no measures. When **`measures`** is **present**, it MUST be a JSON
  **array** (validator + runtime fail closed if it is a string, object, or other non-array).
- Each **`keys[i]`** and each measure **`name`**, **`op`**, and **`field`** (when present) MUST be a JSON **string**
  value at the JSON layer — not a boolean, number, or object coerced into text by the host library.
- Group key values may be string, number, boolean, or null.
- Group order follows **first-seen** composite-key order when the distinct group count is **≤ `maxGroups`**.
- `maxGroups` is **required**, structural, and **≤ `MAX_GENERIC_GROUPS`** (section 6.3).
- Overflow (distinct groups exceed `maxGroups`): default = **emit gap + truncate** to
  `maxGroups` rows. The truncated subset is chosen in **deterministic sorted order of the internal encoded composite key**
  (lexicographic over the encoded key), **not** first-seen arrival order and **not** ranked by group size — authors
  needing ranked groups follow with **`sort`** + **`top_n`**. **`foldOverflowToOther`** (a
  synthetic `_other` group) is not supported; validator and runtime reject it.
- Each measure **`name`** MUST be unique, MUST NOT duplicate any **`keys`** entry, and MUST
  NOT collide with reserved envelope names (section 13 / same set as `project.as`).

**Java:** `group_by` is implemented in **`PlaybookGenericGroupBy`** (invoked from
**`PlaybookGenericDeriveOps`**) with **`PlaybookGenericRowArrays.loadResolvedRowArgs`**, composite
group keys (string / number / boolean / null encoding), first-seen group order when under
**`maxGroups`**, deterministic lexicographic composite-key order when truncated, standard row
envelope with **`totalCount`** = distinct group count and **`returned`** = emitted row count,
**`PlaybookGenericMeasures`** for optional **`measures`** (fail closed when **`measures`** is present but not an array),
**`keys`** restricted to **single-segment** identifiers, and validator coverage for **`keys`**, **`maxGroups`**, **`measures`**, and reserved names.
**`foldOverflowToOther`** is rejected at validation and runtime.

### 8.6 `aggregate`

Purpose: compute measures over a full row set without grouping.

Example:

```json
{
  "op": "aggregate",
  "args": {
    "rows": { "$ref": "trend_rows.output.rows" },
    "measures": [
      { "name": "sampleCount", "op": "count" },
      { "name": "meanValue", "op": "mean", "field": "value" },
      { "name": "minValue", "op": "min", "field": "value" },
      { "name": "maxValue", "op": "max", "field": "value" }
    ]
  }
}
```

Rules:

- Same measure ops as `group_by`.
- Each measure **`name`**, **`op`**, and **`field`** (when present) MUST be a JSON **string** (same rule as `group_by` measures).
- Each measure **`name`** MUST be unique and MUST NOT collide with reserved envelope names
  (section 13 / same set as `project.as`).
- Numeric measures ignore non-numeric and null values, and report counted/ignored
  values where useful.
- Empty inputs return `null` for numeric aggregates and a gap.

**Java:** `aggregate` is implemented in **`PlaybookGenericAggregate`** (from **`PlaybookGenericDeriveOps`**) with **`PlaybookGenericRowArrays.loadResolvedRowArgs`**, required non-empty **`measures`** (**`PlaybookGenericMeasures`**; **`measures`** MUST be a JSON array), scalar fields on **`output`** (no **`rows`**), **`totalCount`** / **`returned`** mirroring input row slot count, and gaps for empty input plus aggregate-level numeric-undefined notes.

### 8.7 `build_targets`

Purpose: build bounded fan-out targets from one or more small input lists.

Primary use case:

```text
assets x selectedProperties -> query_property_history targets
```

Example:

```json
{
  "op": "build_targets",
  "args": {
    "sources": {
      "assets": { "$ref": "pair_assets.output.assets" },
      "properties": { "$ref": "top_alert_properties.output.rows" }
    },
    "template": {
      "thingName": { "$path": "assets.name" },
      "propertyName": { "$path": "properties.property" },
      "relativeDuration": { "$input": "timeWindow" }
    },
    "maxTargets": 4
  }
}
```

Rules:

- This is a bounded cartesian-product helper (or single-list projection when one source).
- **`maxTargets` is always required** (1 … `MAX_GENERIC_TARGETS`); validator rejects absent
  or out-of-range values even for a single `sources` entry, so a single source cannot
  expand without bound.
- **Each resolved array** in `sources` is bounded by **`MAX_GENERIC_INPUT_ROWS`** (section 6.4),
  same as `rows` / `left` / `right` on other ops.
- **Lazy cartesian (section 6.4):** the runtime MUST stop building targets once `maxTargets`
  is reached; it MUST NOT materialize the full Cartesian product first.
- **Deterministic source nesting:** `org.json.JSONObject` does **not** preserve key
  insertion order for iteration. The runtime MUST **not** rely on declaration order in the JSON
  object. Nesting order is **ascending lexicographic order of source names** (Unicode string
  `compareTo`); outermost = smallest name. Authors SHOULD pick names so this order matches their
  intent (e.g. `assets` then `properties` lexicographically).
- Missing `$path` resolutions (null / missing field on the current source row) MUST increment a
  counter and emit a **single bounded gap** on `output.gaps` (fields are still serialized as JSON
  null). Rows with missing fields are not skipped.
- **Empty source:** when any resolved source array has length zero, the logical product is zero;
  the runtime MUST emit a deterministic gap noting that no targets were produced.
- Evidence should report target count and source counts.
- Template binding uses the narrow **`$path` inside `template` only** decision (section 6.5).

This op is critical for replacing business-specific `trend_targets`.

**Java:** `PlaybookGenericBuildTargets` from `PlaybookGenericDeriveOps` — **`sources`** (named sides resolved like **`rows`** / **`join_by_key`** sides, each ≤ **`MAX_GENERIC_INPUT_ROWS`**), required **`maxTargets`**, **`template`** with **`$path`** only as **`{ "$path": "sourceName.dottedField" }`** (plus sole-key **`$input`** / **`$var`** / **`$ref`** / **`$item`**), lazy deterministic cartesian with **lexicographic source-name nesting** (not `JSONObject` key order), **missing-`$path` gap** + **empty-source gap**, **`truncateIfNeededWithLogicalCount`** on **`output.targets`**; validator rejects **`$path`** outside **`args.template`** (section 6.5). **`collect_gaps`** (§8.10) is in **`PlaybookGenericCollectGaps`**. **`flatten_fan_out_rows`** (`PlaybookGenericFlattenFanOutRows`) concatenates **`fan_out`** child **`toolOutput.rows`** into one bounded **`output.rows`** array (§6.2). Reference JSON: **`docs/agent/playbook-engine-cross-asset-pair-health-generic.json`** (section 11).

### 8.8 `join_by_key`

Purpose: join two bounded row sets by stable key.

Example:

```json
{
  "op": "join_by_key",
  "args": {
    "left": { "$ref": "assets.output.rows" },
    "right": { "$ref": "alert_counts.output.rows" },
    "leftKey": "name",
    "rightKey": "thingName",
    "joinType": "left",
    "rightPrefix": "alerts_",
    "maxRows": 100
  }
}
```

Supported `joinType`:

- `inner`
- `left`

Rules:

- Both sides must be bounded arrays (input length ≤ `MAX_GENERIC_INPUT_ROWS`).
- **`maxRows` is required** on the join op (≤ `MAX_GENERIC_JOIN_OUTPUT_ROWS`); overflow =
  gap + truncate to `maxRows` in the deterministic order in section 6.2.
- **Duplicate right keys → multiple output rows** (normative): for each left row,
  emit one joined row per matching right row; iterate matches in **first-seen right order**.
- **Null / missing join keys:** values that are JSON **`null`** or a **missing** path (same as **`null`** from
  **`getAtPath`**) **do not match** any row, including each other (SQL **`NULL` ≠ `NULL`** semantics). Such right
  rows are omitted from the join index; for **`left`** joins, a null- or missing-key left row is emitted as a
  **left-only** output row; for **`inner`** joins it produces no output row.
- Field collisions use prefixes or fail validation. Authors SHOULD set a non-empty **`rightPrefix`** whenever
  left and right rows may share top-level field names — an empty prefix and overlapping names fail at merge time
  with **`GENERIC_INPUT_INVALID`** (row shapes are not visible to the validator).
- Evidence should report left count, right count, logical output count, and unmatched count where applicable:
  for **`left`** joins, **unmatched** = left rows with no right match; for **`inner`** joins, evidence SHOULD also
  report how many left rows (with **non-null** join keys) had no right match (they produce no output rows).
- String args **`leftKey`**, **`rightKey`**, **`joinType`**, and optional **`rightPrefix`** follow the same JSON-string
  typing rules as **`group_by`** measures (no boolean/number coercion).

**Java:** `join_by_key` is implemented in **`PlaybookGenericJoinByKey`** (from **`PlaybookGenericDeriveOps`**)
with **`PlaybookGenericRowArrays.loadResolvedSide`** for **`left`** / **`right`**, **`PlaybookGenericJsonSchemaStrings`**
for string fields, **`maxRows`** overflow via **`truncateIfNeededWithLogicalCount`** (logical join size vs materialized
prefix), **`inner`** / **`left`** semantics, **null / missing join keys never match**, duplicate-right expansion in first-seen right order, prefixed right columns,
and validator **`validateJoinByKeyDerive`**.

### 8.9 `pick_one`

Purpose: select exactly one row by predicate or rank, otherwise produce clarification /
gap output.

Example:

```json
{
  "op": "pick_one",
  "args": {
    "rows": { "$ref": "candidate_assets.output.rows" },
    "where": { "op": "eq", "field": "name", "right": { "$input": "assetIdentifierA" } },
    "onZero": "needs_clarification",
    "onMultiple": "needs_clarification",
    "label": "asset A"
  }
}
```

Rules:

- `onZero`: `needs_clarification`, `gap`, or `empty`.
- `onMultiple`: `needs_clarification`, `gap`, or `first`.
- Default should be conservative: `needs_clarification`.
- Output should include the selected row at **`output.row`** when exactly one row is selected (`status: ok`). Do not use alternate keys (`selectedRow`, …).
- When **`onZero`** / **`onMultiple`** resolve to **`needs_clarification`**, the result still includes **`output`** with **`row`** JSON null and zero counts (section 6.1); **`message`** is top-level alongside **`status`**.
- This op is a generic replacement for parts of `match_entity_identifiers` and
  `require_exact_count`, but those existing ops may remain.

**Java:** `pick_one` uses `PlaybookGenericRowArrays.loadResolvedRowArgs`, calls
`PlaybookRowPredicate.requireValidShape(where)` once before counting matches, then
`evaluateWithoutShapeCheck` per row (same contract as `filter`). Validator reuses
`requireValidShape` for `where`; runtime rejects invalid `onZero` / `onMultiple` tokens.
`needs_clarification` branches include the normative `output` shell (`row` null, zero counts, `gaps`) plus `message`.

### 8.10 `collect_gaps`

Purpose: merge gaps from prior nodes into one final limitations block.

Example:

```json
{
  "op": "collect_gaps",
  "args": {
    "refs": [
      "alert_history.output.gaps",
      "top_alert_properties.output.gaps",
      "trend_summary.output.gaps"
    ],
    "maxItems": 10
  }
}
```

Rules:

- Each string in `refs` uses the same dotted path convention as a **`$ref`** string (for
  example `alert_history.output.gaps`); resolve like `PlaybookExpressionResolver` node
  navigation, not free-form aliases.
- After resolution, each collected gap entry MUST be one of:
  - a **non-null string** with length ≤ `MAX_GAP_TEXT_CHARS` (section 6.3), or
  - a **flat JSON object** with at most four keys, each value a string or number, string
    values ≤ `MAX_GAP_TEXT_CHARS`, **no nested objects or arrays**, and optional keys
    limited to `code`, `message`, `kind`, `detail` (validator rejects other keys).
- **Reject at validation or execution** (fail-closed) any resolved value that is a large
  arbitrary JSON blob, raw tool row, or nested structure — do not pass opaque objects
  through to `llm_summary` evidence.
- Preserve order by refs, then item order.
- Deduplicate exact string entries. For structured gap objects, compute a **deduplication
  canonical projection** using only allowed keys (`code`, `detail`, `kind`, `message`) in
  **alphabetical order** when testing equality; do not use raw `JSONObject.toString()`
  on arbitrary maps for dedupe. **Wire / `org.json` note:** structured gaps are stored as
  `JSONObject`; key **iteration order and `toString()` serialization order are not guaranteed**
  to match alphabetical order (HashMap-backed). Consumers MUST treat each structured gap as an
  unordered record keyed by field name; semantic equality and dedupe use the canonical projection,
  not serialized key order.
- `maxItems` caps merged list length before `MAX_COLLECT_GAPS_ITEMS` (section 6.3).
- Output **`gaps`**, **`returned`**, and **`totalCount`** (deduped logical size before the **`maxItems`** clip).
- Evidence should report gap count only (no embedded gap bodies).

**Java:** `PlaybookGenericCollectGaps` from `PlaybookGenericDeriveOps` — **`refs`** (non-empty array of dotted paths, same navigation as **`$ref`**); each path resolves to a **`JSONArray`** of entries (non-null string ≤ **`MAX_GAP_TEXT_CHARS`**, or flat object with keys **`code`**, **`detail`**, **`kind`**, **`message`** only, string or number values, strings bounded); per-ref array length ≤ **`MAX_GENERIC_INPUT_ROWS`**; merge order = **refs order** then **array item order**; **dedupe** exact strings and structured objects via a **canonical alphabetical projection** (not raw `JSONObject` iteration); structured gaps are **`JSONObject`** on the wire — **field iteration / serialization order is not guaranteed alphabetical** (see §8.10 rules); required **`maxItems`** (validator: 1 … **`MAX_COLLECT_GAPS_ITEMS`**); **`output.gaps`**, **`output.returned`** (clipped length), **`output.totalCount`** (deduped size before clip); when deduped count exceeds **`maxItems`**, evidence notes truncation (no extra synthetic gap row). Validator checks **`refs`** / **`maxItems`** and that each ref’s leading **`nodeId`** exists in the playbook document.

## 9. Evidence behavior

Every generic op should attach evidence lines. They should be short and deterministic.

**Size discipline (normative):** follow the same budgets as existing heavy derive ops and
the playbook evidence ledger — approximately **8 KiB per derive node serialized evidence**
pattern (`PlaybookDeriveOps` / `PlaybookDeriveOpsV1b` summaries) plus the configured
**`maxEvidenceBytes`** on `llm_summary` / task-state paths documented in
`playbook-engine.md`. `evidenceLines` entries are short strings only; they MUST NOT embed
raw rows, full gap payloads, or property values.

Examples:

```text
project: input 113 row slot(s); emitted 113 row(s) to 3 field spec(s).
filter: input 113 row slot(s); kept 91 row(s); dropped 22 row(s).
group_by: grouped 91 rows into 6 groups by property.
top_n: selected 2 of 6 rows by alertCount desc.
build_targets: built 4 targets from 2 assets x 2 properties.
join_by_key: left join produced 2 rows; 0 unmatched left rows.
collect_gaps: collected 3 evidence gaps.
```

Evidence lines must not include raw large row dumps. They can include small selected
names, counts, and field names.

The final `llm_summary` should be able to reference these generic evidence lines without
knowing the business-specific Java output shape.

## 10. Relationship to cached-table tools

Parler already has `tabulate_cached_result` and `summarize_cached_result`, which are
deterministic table tools over cached result sets. Generic Playbook ops should not
replace them.

Difference:

| Capability | Cached-table tools | Playbook generic ops |
| --- | --- | --- |
| Input | cached table by `cacheId` | in-memory node output / bounded rows |
| Purpose | user-visible table analysis and transforms | internal DAG evidence shaping |
| Execution | normal tool executor | `derive` op |
| Output | tool result, cache/table refs, insight envelope where applicable | node output + evidence lines |

Use cached-table tools when the user asks for table analysis or when the dataset should
remain in the tool/cache lane. Use Playbook generic ops when the workflow needs small,
bounded intermediate transformations to decide next nodes.

## 11. Reference workflow: `asset_pair_health`

With Playbook-safe `query_alert_history` and the generic ops, the asset-pair workflow no
longer needs a special primary-property-only path.

Generic flow:

```text
1. resolve_asset_type / resolve_thing or equivalent asset setup
2. fan_out query_alert_history for both assets
2b. flatten_fan_out_rows (concatenate each child's toolOutput.rows for downstream generic ops)
3. project alert rows to thingName/sourceProperty/severity/state/timestamp
4. filter rows with sourceProperty present
5. group_by sourceProperty, optionally also by thingName
6. sort/top_n to select top 2 properties
7. build_targets from assets x topProperties
8. fan_out query_property_history over targets
9. aggregate or trend_summary compact evidence
10. collect_gaps
11. final llm_summary ranked assessment
```

This supports the story:

```text
Two assets x top two properties = up to four trend calls / charts.
```

`summarize_asset_pair_health` remains available. The reference JSON
**`docs/agent/playbook-engine-cross-asset-pair-health-generic.json`** (taxonomy + pair resolution → **`fan_out` `query_alert_history`** → **`flatten_fan_out_rows`** → **`filter`** / **`group_by`** / **`top_n`** → **`build_targets`** → **`fan_out` `query_property_history`** → **`flatten_fan_out_rows`** → **`aggregate`** → **`collect_gaps`** → **`llm_summary`**) proves the generic path. **`PlaybookReferenceGenericAssetPairHealthTest`** validates its structure; **`PlaybookReferenceGenericAssetPairHealthExecutionTest`** runs the DAG with canned tool bodies. Companion op **`flatten_fan_out_rows`** (`PlaybookGenericFlattenFanOutRows`) is the fan-out → row-array bridge.

## 12. Shared helpers

The generic ops share helpers for row extraction (`PlaybookGenericRowArrays`), dotted path lookup
(`PlaybookGenericPathGrammar`, `PlaybookJsonRowPath`), row predicate evaluation (`PlaybookRowPredicate` →
`PlaybookPredicateValueOps`), stable sort (`PlaybookGenericRowOrdering`), measures (`PlaybookGenericMeasures`),
evidence line attachment, and gap collection. Existing business derive ops keep working unchanged.

## 13. Validation rules

The validator must:

- accept all new **generic** op names (`GENERIC_DERIVE_OPS` set, disjoint naming from
  unknown strings);
- reject unknown op names;
- reject malformed op arguments where shape is required (per-op arg validators; legacy
  derive ops validate only at execution time);
- reject missing required fields (`fields`, `keys`, `measures`, `sources`, **`maxTargets`**
  on every `build_targets`, **`maxRows`** on every `join_by_key`, **`maxGroups`** on every
  `group_by`, **`refs`** / **`maxItems`** on every `collect_gaps`, **`fanOutNodeId`** on every
  `flatten_fan_out_rows`, …);
- reject numeric author literals **above** the ceilings in section **6.3** (fail-closed);
- reject invalid sort directions;
- reject unsupported measure ops;
- reject unsupported join types;
- reject **`$path` (or chosen binding key) outside `build_targets.template`**;
- reject illegal **row predicate leaves** (more than one of `field` / `left` / `value`, or
  none where a leaf operand is required);
- reject **`collect_gaps` entries** that violate section 8.10 (opaque blobs, oversized strings,
  nested JSON);
- reject **reserved / colliding output names** on generic ops where validators exist today
  (e.g. `project` `as` must not duplicate and must not equal `rows`, `totalCount`, `returned`,
  `gaps`, `status`, `targets`, `row`, `output`, `_other`; `group_by` measure names vs `keys`
  and reserved names);
- ensure references still obey existing expression resolver rules.

**Runtime (not validator):** enforce resolved input / output row count bounds per section **6.4**
(`MAX_GENERIC_INPUT_ROWS`, `MAX_GENERIC_OUTPUT_ROWS`).

The validator should not:

- infer business meaning from field names;
- require every op to have a business-specific evidence template.

## 14. Tests

Test classes:

```text
PlaybookGenericDeriveOpsTest
PlaybookGenericOpsProjectTest
PlaybookGenericOpsFilterTest
PlaybookGenericOpsSortTopNTest
PlaybookGenericOpsGroupByAggregateTest
PlaybookGenericOpsBuildTargetsTest
PlaybookGenericOpsJoinByKeyTest
PlaybookGenericOpsPickOneTest
PlaybookGenericOpsCollectGapsTest
PlaybookGenericOpsEvidenceLinesTest
PlaybookValidatorGenericOpsTest
PlaybookReferenceGenericAssetPairHealthTest
PlaybookReferenceGenericAssetPairHealthExecutionTest
```

Important cases:

- missing fields,
- null values,
- mixed numeric/string values,
- empty input rows,
- top N fewer than requested,
- duplicate join keys,
- build target missing required path,
- unsupported predicate op,
- evidence lines do not include raw row dumps,
- existing V1a/V1b Playbooks still validate and run.

## 15. Design properties

- There is no general scripting language.
- Each op is deterministic and documented.
- Output shapes are stable (`output.rows`, `output.targets`, `output.row`, `output.gaps`).
- Evidence lines are compact.
- Existing business-specific derive ops still work.
- The asset-pair workflow expresses top-N property selection and asset x property fan-out
  without a special Java op.
- Validator errors are actionable.

## 17. Service orchestration ops (`playbook-34-35`)

Normative design: **`docs/agent/playbook-34-35.md`**. The runtime catalog is documented in
**`docs/agent/playbook-engine.md`** (service-orchestration derive ops + **`$infotable`**
binding). Beyond flat Priority 1 rows these add:

- dynamic resolver fan-out normalization (**`normalize_resolved_things`**);
- non-`rows` tool envelope extraction (**`extract_from_tool_output`**);
- nested payload assembly (**`build_nested_object`**) and **`json_stringify`**;
- extended-tool **`$infotable`** service-argument binding;
- playbook time-window derivation and optional-branch ergonomics;
- analytics helpers (**`add_computed_fields`**, **`collect_values`**, **`join_values`**).

Runner fixtures: **`parler-agent/src/test/resources/playbook-34-35-fixture/`**
(alarm-events and KPI-values scenarios, including an optional-filter branch proof).
