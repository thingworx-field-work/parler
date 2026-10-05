# Entity set analysis

Status: **implemented** (`AnalyzeEntitySetExecutor`). Normative tool JSON: **`CONTRACTS/ENTITY_SET_TOOL.md`**.

## Motivation

The 2026-06-05 live test exposed a class of prompt that current first-party tools do not handle deterministically:

> List the Things based on a ThingTemplate, subtract the Things already represented by taxonomy-backed asset mappings,
> then chart the remaining count by category.

The observed data was small enough for a human to reason about but large enough to break the LLM workflow:

- `query_entities` found 172 Things implementing the target base work-unit template.
- Taxonomy-backed asset mappings accounted for 136 Things.
- The expected uncategorized set was therefore 36 Things.
- The model paged rows and guessed from names, reported only 31, and could not guarantee the set.
- A later generated `query_entities` composite `NOT` filter failed at the platform boundary with
  `JSONObject["fieldName"] not found`.

This is not a one-prompt bug. It was a missing deterministic operation over cached entity lists.

## Tool Surface Rationale

`analyze_entity_set` is justified by a structural test on the built-in tool set: one operation = one tool; criteria,
granularity, and target kind = parameters of that tool; multi-step workflows = compositions of tools, never new
tools. Extended tools (per-deployment `/tools/extended_tools.json`) are outside this analysis, and call frequency is
not an argument for keeping or removing a tool.

### Non-atomic composites: retired

| Tool | Decomposition over the basis |
|------|------------------------------|
| `query_asset_count_under_hierarchy_node` | resolve scope (`resolve_asset_type`) → taxonomy-scoped entity query → exact cached group count |
| `compare_alert_status_between_hierarchy_nodes` | resolve scope → alert query per scope → cached compare/aggregate |

Both were workflow macros, not primitives, and they bypassed the cached/deterministic architecture this repo has
invested in (conversation cache + cached tabular compute + chart hooks). They were retired from the advertised built-in
surface. `analyze_entity_set` does not recreate either macro; it only supplies the missing
set primitive that lets those workflows be composed through existing entity, taxonomy, alert, tabular, and chart
tools.

### What stays separate: orthogonality justifications

- **Reads vs actions.** `set_property_value` and `acknowledge_alerts` keep dedicated shapes even though both are
  technically `invoke_service` specializations: the HITL/approval contract is a real axis, and collapsing reads and
  writes into one tool would trade schema count for a safety boundary.
- **`invoke_service`** stays as the atomic generic-execution escape hatch.
- **`query_entities_by_taxonomy`** stays: taxonomy projection and identity semantics are a distinct criteria system that
  `query_entities` does not replace; entity-set analysis consumes its cached results.
- **`query_stream_data`** stays: ThingWorx Stream entities are a different source/operand (arbitrary data-shape rows),
  not a granularity variant of property history.
- **The cached-result compute quartet** (`fetch_cached_result`, `tabulate_cached_result`, `summarize_cached_result`,
  `build_chart_from_tabular_result`) stays: page, transform, aggregate, and render are four distinct operations.
  `tabulate` and `summarize` are both contract-bearing (`CONTRACTS/TABULAR_INSIGHT.md`). Entity-set analysis is a cross-result
  primitive, not a replacement for single-table browsing and aggregation, and its output stays chartable through the
  same chart path.
- **`get_property_values`** stays: a current-value read has no time axis; it is not a history variant.
- **`get_agent_skill`** is a distinct operation, **conditionally advertised on configuration state**: when the skill
  catalog is empty it is omitted from the model-facing list — the same pattern applied to `start_playbook`, which
  registers only when playbooks load (see `docs/agent/model-tool-admission-guardrails.md`).

### Naming guidance

Schema description and concrete Thing member discovery are separate tools. Naming guidance for the LLM-facing
surface:

- Use **`describe_entity_schema`** when the user needs a model/schema definition for an already known entity such as a
  ThingTemplate, ThingShape, or DataShape.
- Use **`discover_thing_members`** when the user needs the effective properties/services/events/subscriptions exposed
  by a concrete Thing instance.
- Use **`query_property_history`** only for historical values of one property on one Thing; use `get_property_values`
  for current values.

The verb pair alone (`describe` vs `discover`) is not enough. The tool names include the target domain
(`entity_schema` vs `thing_members`) so the model does not confuse schema description, concrete Thing capability
discovery, and entity search/enumeration.

## Problem

Parler has good single-table primitives:

- `query_entities` and `query_entities_by_taxonomy` can produce cached entity-list results.
- `fetch_cached_result` can page cached rows.
- `tabulate_cached_result` and `summarize_cached_result` can filter, rank, and aggregate one cached table.
- `build_chart_from_tabular_result` can chart a single chartable tabular result.

`analyze_entity_set` covers cross-result entity set logic. Users naturally ask for:

- template set minus taxonomy set
- all assets missing a classification
- Things present in one source but absent from another
- overlap between a taxonomy category and a template/shape query
- counts grouped from the resulting set

Without exact set algebra, the model falls back to paging rows into context, inventing long negative
filters, or classifying by name. Those fallbacks are brittle and consume context budget.

## Goals

- Provide deterministic set operations over cached entity/list results.
- Keep full-row work inside the runtime cache; do not require the LLM to read every row.
- Return compact LLM-visible evidence with exact counts, sample rows, and a cache id for follow-up paging.
- Produce chartable tabular outputs by composing **`tabulate_cached_result`** (and then **`build_chart_from_tabular_result`**) on the **new `cacheId`** returned by **`analyze_entity_set`** — v1 does **not** treat raw entity-set JSON as a direct chart source.
- Make the common "uncategorized assets" workflow exact.
- Avoid giant generated `NOT` filter chains when a cached set difference is the right operation.

## Non-goals

- No arbitrary SQL engine.
- No broad fuzzy matching in v1. Entity identity should be exact, normally by `name`.
- No change to taxonomy semantics.
- No change to the existing chart contract.
- No attempt to make every platform `QUERY` expression portable across every ThingWorx service shape.

## V1 Tool Surface

One deterministic built-in tool:

```text
analyze_entity_set
```

The tool performs exact set algebra over **two already-cached entity/list results**. It does not query ThingWorx
directly and does not accept free-form platform query predicates. Upstream tools such as `query_entities`,
`query_entities_by_taxonomy`, and other compatible list tools are responsible for creating the cache entries.

### Input Contract

| Field | Required | Meaning |
|-------|----------|---------|
| `operation` | yes | JSON-Schema string **`enum`**: **`difference`**, **`intersection`**, **`union`**, **`symmetric_difference`** (B18; see **`CONTRACTS/ENTITY_SET_TOOL.md`** §1.1a). |
| `left` | yes | Operand object: **only** `cacheId` (required), optional `keyColumn`, optional `label`. No query specs, no last-cache sentinel. |
| `right` | yes | Same constraints as **`left`**. |
| `left.keyColumn` | no | Left identity column, default **`name`**. |
| `right.keyColumn` | no | Right identity column, default **`name`**. |
| `projectColumns` | no | For **`difference`** / **`intersection`**: output projection from the **left** operand only. For **`union`** / **`symmetric_difference`**: each name must exist on **at least one** operand; missing cells are **`null`** for keys present on only one side. When omitted, **`difference`** / **`intersection`** default to **scalar** columns on the left (same allowlist as today); **`union`** / **`symmetric_difference`** default to the **intersection** of scalar column names on both operands (left field order, left **`keyColumn`** first when present in the intersection). **`PASSWORD`** and non-scalar shapes are excluded from defaults. |
| `maxItems` | no | Bounds the **`rows`** / **`sampleRows`** slice returned in the tool JSON. **Default `50`**, **min `1`**, **max `500`** — same bounds as **`tabulate_cached_result`** (`docs/agent/cached_tabular_tools.md` §4). |
| `offset` | no | Zero-based offset into the **output** row sequence (default **`0`**). |

**Root allowlist:** the tool arguments object accepts **only** the keys in the input table above (**`operation`**, **`left`**, **`right`**, **`projectColumns`**, **`maxItems`**, **`offset`**). Any other top-level key MUST be rejected with **`INVALID_PARAMETERS`** (see **`CONTRACTS/ENTITY_SET_TOOL.md`** §1.1) so query-shaped hallucinations are never ignored.

**Not supported:** there is **no** `groupBy` on **`analyze_entity_set`** — use **`tabulate_cached_result(mode="group_count", …)`** on the returned **`cacheId`**. There is **no** custom **`sort`** — output rows are ordered by the effective key ascending. Operands **must not** use **`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`** (or any other last-cache sentinel); both sides require **concrete** ids from upstream tools.

Operand object:

| Field | Required | Meaning |
|-------|----------|---------|
| `cacheId` | yes | Conversation-scoped cached result id. |
| `keyColumn` | no | Entity identity column for this operand; defaults to `name`. |
| `label` | no | Human-readable label echoed in diagnostics, for example `templateThings` or `taxonomyThings`. |

V1 deliberately excludes these inputs:

- direct `entityType` / `thingTemplate` / `thingShape` query specs;
- free-form filters;
- fuzzy key matching;
- more than two operands;
- joins on multiple key columns;
- numeric aggregates in **`analyze_entity_set`** (aggregation belongs in **`tabulate_cached_result`** / **`summarize_cached_result`**).

These exclusions keep the tool an exact set primitive, not a second query engine.

### Set Semantics

- Keys are compared after normalization: **STRING** / **THINGNAME** / **GUID** / **DATETIME** use trimmed `String.valueOf` of the cell; **BOOLEAN** uses **`true`** / **`false`**; **INTEGER**, **LONG**, and **NUMBER** use a shared plain numeric canonical form (strip trailing zeros) so the same logical id matches across operands (e.g. integer `1` vs double `1.0`). Unsupported key-column base types are rejected with **`INVALID_PARAMETERS`** before row scan. No case folding or fuzzy matching in v1.
- The key column must exist in both cached inputs.
- Rows with missing, blank, or non-scalar key values make the operation fail with a stable tool error such as
  `ENTITY_SET_KEY_UNUSABLE`; the tool does not silently drop them and still claim an exact answer.
- Duplicate keys on the **left** operand (the sole projection source for **`difference`** and **`intersection`**) are allowed **only** when all projected non-key column values **agree** across duplicate rows for that key; otherwise the tool fails with **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`** (no silent “pick first row” when projections conflict). For **`difference`** / **`intersection`**, the **right** operand is **membership-only**: duplicate keys on the right with differing non-key values do **not** affect the exact answer and MUST NOT trigger **`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`**. For **`union`** and **`symmetric_difference`**, duplicate-key ambiguity applies on **each** operand for projected columns that exist on that table, and **`union`** additionally requires that for keys present in **both** operands, non-null values agree on every projected column that exists on **both** shapes (see **`CONTRACTS/ENTITY_SET_TOOL.md`** §**1.3**). **`symmetric_difference`** does not compare cell values for keys that appear on both sides — those keys are omitted from the output.
- `difference`: keys in left but not right; row projection comes from the left source.
- `intersection`: keys present in both; row projection comes from the left source.
- `union` / `symmetric_difference`: when schemas differ, output columns are the **ordered union** of projected column names; for keys present only on one side, missing cells are **`null`** in the row JSON. “Common safe scalar columns” for default projection means the **intersection** of scalar columns (same allowlist as **`difference`** defaults), always listing the left **`keyColumn`** first when it appears in that intersection. Output keys for **`union`** and **`symmetric_difference`** are emitted in **lexical order of the canonical key string** (Unicode `String.compareTo`), which is a total order: **`compare(a,b) == 0`** only when **`a.equals(b)`**, so distinct STRING spellings such as **`"1"`** vs **`"01"`** remain distinct output rows. Logical numeric equality across operands is still handled when **indexing** rows via the shared numeric canonical form (see above).

### Output Contract

| Field | Meaning |
|-------|---------|
| `status` | `success` or `error`, matching current built-in style for new tools. |
| `resultKind` | `ENTITY_SET_EMPTY`, `ENTITY_SET_INLINE`, or `ENTITY_SET_LARGE` (INLINE/LARGE split uses the same **20-row** threshold as **`InvokeServiceExecutor.largeTableRowThreshold()`** / `docs/agent/cached_tabular_tools.md` §4). |
| `operation` | Echoed operation (**`difference`**, **`intersection`**, **`union`**, or **`symmetric_difference`**). |
| `left` / `right` | Source summaries: `cacheId`, `keyColumn`, `rowCount`, `uniqueKeys`, `duplicateKeyRows`, optional `label`. |
| `matchedKeys` | Count of keys in the set result — **`difference`**: in **left** and not in **right**; **`intersection`**: in **both**; **`union`**: in **either**; **`symmetric_difference`**: in **exactly one** operand. |
| `totalRows` | Same as **`matchedKeys`** when each output key maps to one row. |
| `rows` / `sampleRows` | Bounded slice of output rows (`rows` for EMPTY/INLINE, `sampleRows` for LARGE), controlled by **`maxItems`** / **`offset`**. |
| `columns` | Column metadata for the output table. |
| `cacheId` | **New** cache id for the **full** transformed output table — **required on every success**, including EMPTY and INLINE-sized results, so the **`analyze_entity_set` → `tabulate_cached_result` → chart** path never depends on inline row payloads alone. |
| `inputsFullyScanned` | Always **`true`** on success: both operand caches were read in full for exactness. |
| `answerSetComplete` | **`true`** only when the **`rows`** array contains **all** output rows with no omission (tabulate/query-spec §11 inline completeness semantics). LARGE samples set **`sampleOnly`**, **`rowsOmitted`**, matching counts (`returnedRows` / `totalRows`). **Do not** overload this field to mean “inputs scanned”; use **`inputsFullyScanned`** for that. |
| `hint` | Follow-up guidance pointing at **`fetch_cached_result`** / **`tabulate_cached_result`**. |

**Charting:** v1 **does not** register **`analyze_entity_set`** with chart round-hooks or **`TabularCompleteAnswerSetDetector`**. Treat the tool as producing a **cacheable entity list**; run **`tabulate_cached_result`** on the emitted **`cacheId`** when the user needs **`insightEnvelope`**, group counts, or a **`build_chart_from_tabular_result`**-compatible envelope (**`CONTRACTS/CHART_CONTRACT.md`** §2.5, **`CONTRACTS/ENTITY_SET_TOOL.md`**).

### Supported Question Classes

The tool covers these classes:

- "Things from template A that are not represented in taxonomy category B" -> `difference`.
- "Overlap between taxonomy category A and template/shape query B" -> `intersection`.
- "List Things present in either source without duplicates" -> `union`.
- "Show Things only found by one source or the other" -> `symmetric_difference`.
- "Count the uncategorized result by a projected category/status column" -> `difference` then **`tabulate_cached_result(mode="group_count", cacheId=<new id>, …)`**.

The model still uses existing tools for:

- current values (`get_property_values`);
- property history (`query_property_history`);
- schema/member discovery (`describe_entity_schema`, `discover_thing_members`);
- single-table filtering/ranking/aggregation (`tabulate_cached_result`, `summarize_cached_result`).

## Primary Workflow

For the live-test class of prompt:

1. `query_entities` returns cached Things implementing the requested ThingTemplate.
2. `query_entities_by_taxonomy` or a taxonomy-backed entity query returns cached Things already represented by asset
   taxonomy mappings.
3. `analyze_entity_set(operation="difference", left={"cacheId":"A"}, right={"cacheId":"B"})` returns the exact
   uncategorized Things and a cache id.
4. `tabulate_cached_result` on the new **`cacheId`** (for example `mode="group_count"`) materializes grouped counts.
5. `build_chart_from_tabular_result` renders the count table.

The model receives exact counts and bounded samples, not all rows.

## Query Filter Boundary

The live-test failure where `query_entities` passed a model-generated composite `NOT` shape to the platform (rejected
with a low-level `JSONObject["fieldName"] not found`) is handled by `query_entities.query` predicate admission
(`docs/agent/model-tool-admission-guardrails.md`). The routing guide steers cross-list and negative multi-name
questions to `analyze_entity_set` instead of generated `NOT LIKE` chains.

## Storage And Replay

Entity-set outputs follow the existing large-result cache rules:

- Full transformed rows live in the conversation-scoped runtime cache.
- Successful v1 calls always create a new transformed-result `cacheId`, including small inline results, under the same
  per-conversation cache TTL/count budget as other cached tabular results.
- Stream/audit persistence keeps compact evidence: counts, columns, sample rows, cache id, and omitted-row metadata.
- Follow-up pages use `fetch_cached_result`.
- Rehydration does not attempt to restore full set rows after cache expiry.

## Normative contracts and implementation touchpoints

Normative tool JSON for all shipped **`analyze_entity_set`** operations is **`CONTRACTS/ENTITY_SET_TOOL.md`** (bundle **`CONTRACTS/CONTRACT_VERSION.md`**). Keep **`docs/agent/entity-set-analysis.md`** (this file) aligned with that contract when the wire shape changes.

- **`CONTRACTS/CHART_CONTRACT.md`** §2.5 — charts **should** consume **`tabulate_cached_result`**-class envelopes built on the **`cacheId`** from **`analyze_entity_set`**, not raw entity-set JSON.
- **`CONTRACTS/TABULAR_INSIGHT.md`** — unchanged for **`analyze_entity_set`** v1 (**no** `insightEnvelope` on entity-set success); **`tabulate_cached_result`** continues to own Further Insight **`insightEnvelope`** on the follow-up hop.
- **`docs/agent/cached_tabular_tools.md`**, **`docs/agent/AGENT-TAXONOMY.md`** — reuse PASSWORD blocking (`PROTECTED_TABULAR_COLUMN_BLOCKED`) and paging numeric limits (**`maxItems`**, INLINE/LARGE threshold **20**) consistent with cached tabular tools.

**`TabularCompleteAnswerSetDetector` / `TabularChartRoundHooks`:** v1 **does not** add a **`tabulate_cached_result`-parallel** chart detector branch for **`analyze_entity_set`**. P2 last-tabular mirror updates still run so **`__PARLER_LAST_QUALIFYING_TABULAR_CACHE__`** can resolve to the **new** transformed **`cacheId`** before **`tabulate_cached_result`** runs.

## Behavior Decisions

- **No `groupBy`** on **`analyze_entity_set`**; grouped counts are always **`tabulate_cached_result`**.
- **Operands are `cacheId`-only** with explicit ids — **no** last-tabular sentinel on **`left` / `right`**.
- **Always emit a new `cacheId`** on success, including EMPTY / INLINE, before returning JSON to the LLM.
- **`answerSetComplete`** follows **inline row completeness** semantics (tabulate §11); **`inputsFullyScanned`** captures “both inputs fully read”.
- **Duplicate keys** fail closed when projected values conflict (**`ENTITY_SET_DUPLICATE_KEY_AMBIGUOUS`**).
- **Union / symmetric_difference default projection** for heterogeneous schemas: **intersection** of scalar columns + key; cell-level **`null`** padding for missing sides.
- PASSWORD columns are blocked on **`keyColumn`** and **`projectColumns`**.
