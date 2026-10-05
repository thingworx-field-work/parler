# Appendix: Skills, evidence grounding, and playbooks syntax

This appendix is the **authoring syntax reference** for the three workflow layers: skills, evidence grounding, and
playbooks. It focuses on **evidence syntax** and the **complete set of tools and ops a playbook can use**. Chapters 11,
12, and 15 teach the concepts; this appendix lists the fields. The source of truth is the Parler agent's playbook
validator/runner and [`docs/agent/playbook-engine.md`](../../docs/agent/playbook-engine.md) in this repository; examples are the workshop `day3` /
`day4` files and the shipped `cross_asset_pair_health` playbook.

Feature-minimum note: the syntax below requires **`parler-agent` 0.1.191+**. In particular, service-oriented
playbooks that use `normalize_resolved_things`, `extract_from_tool_output`, nested payload assembly, time-window
derivation, computed fields, or **`$infotable`** binding require 0.1.190 or newer. The 0.1.191 baseline adds
customer-readiness diagnostics (`playbookRuntime`, structured validation reports, last-run collection) and the
Skill-to-Playbook converter knowledge pack. When a playbook works in the docs but fails in a student's runtime, check the
AgentThing version before debugging the JSON.

---

## 1. Skills (`/skills/<id>/SKILL.md`)

The directory id is the stable skill id (short, readable). Each skill is one `SKILL.md` file.

### 1.1 Front matter — simple `key: value`, not YAML

```markdown
---
name: asset_pair_health
title: Asset pair health comparison
description: Use when the user asks to compare the health or operational risk of two named assets.
skill_meta_version: 1
---
```

| Key | Required | Meaning |
|---|---|---|
| `name` | optional | If present, must **equal the directory id exactly** — a mismatch invalidates the skill. |
| `title` | optional | Display title; defaults to the directory id. |
| `description` | recommended | Routing hint — *when* to load the skill. |
| `when_to_use` | optional | Also a routing hint; if both `description` and `when_to_use` exist, `when_to_use` wins. |
| `skill_meta_version` | optional | Format version (currently `1`). |

Parsing is line-oriented: only the first `:` splits key and value, keys are lower-cased, `#` and blank lines are ignored,
and **real YAML (arrays, nested objects, anchors) is ignored** — keep it to flat `key: value`. A `parameters` or
schema-like key has no effect; a skill is guidance, not a tool.

### 1.2 Body sections

The body after the front matter is what `get_agent_skill` returns. A workshop skill body typically has: **Purpose →
Required inputs → Clarification rules → Tool route → Final answer shape → Evidence rules**.

### 1.3 Evidence grounding in skills (prose, not a schema)

Evidence grounding in a skill is a **prose convention in the body**, not a structured block. Write rules that say which
tool results are authoritative, which rows/columns matter, what to do when evidence is missing, and what the answer must
not invent:

```markdown
### Evidence rules

- Treat `query_alert_summary` rows as the source for current alert state.
- Treat `query_alert_history` rows as the source for time-window alert claims.
- Do not infer an alert count from prose in a previous answer.
- If both tools return empty rows, say "no evidence returned in this window" rather than "healthy".
- Bind each current alert to the current turn's parent `byThing[].thingName` and child `topAlerts[]` row.
- Treat property values and taxonomy `criticalProperties` as context, never as proof that an alert exists.
- List every `ok:false` property row, including `PROPERTY_METADATA_UNRESOLVED`, as a scoped evidence gap.
```

On `parler-agent` **0.1.202+**, `query_alert_summary` takes `thingNames[]`.
For one Thing, pass a one-element array; for a pair or region comparison, prefer
one array call when the set fits the 25-Thing limit. `query_alert_history` still
uses scalar `thingName`.

Teach students to distinguish the **evidence categories** a tool result can carry, because each means something
different in the answer:

| Category | Meaning | Answer rule |
|---|---|---|
| full success | rows returned | use them |
| **empty success** | `status=ok`, 0 rows | data absence, **not** "entity not found" or "healthy" |
| partial success | sampled / paged / truncated (often a `cacheId`) | say it is partial; use the cache id for full-table work |
| inferred | server-inferred count (`totalCountInferred=true`) | label it inferred |
| error | structured code (`ENTITY_NOT_FOUND`, `SERVICE_NOT_FOUND`, `CACHE_MISS`, `INVALID_TIME_RANGE`, …) | preserve the error identity |
| protection | PASSWORD-backed block/omission | keep the note narrow |
| HITL pending | approval/rejection/cancellation/expiry | not a business failure |

The Day 2 vs Day 4 `asset_pair_health` skills differ only by adding this evidence-rules discipline.

When reusing an exact property name from previous evidence, first prove that the evidence belongs to the same
resolved asset type. Cross-turn prose and a different asset type's schema are not property metadata.

---

## 2. Playbooks (`/playbooks/<id>/playbook.json`)

A playbook moves a stable workflow into the runtime as a deterministic DAG. One merged JSON file per package.

### 2.1 Root structure

```json
{
  "schema": "parler-playbook-v1",
  "id": "cross_asset_pair_health",
  "title": "Cross-asset pair health",
  "description": "Compare current operational health for two named assets.",
  "whenToUse": "Use when the user compares health, alerts, or operational risk between two named assets.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "assetIdentifierA": { "type": "string" },
      "assetIdentifierB": { "type": "string" },
      "timeWindow": { "type": "string" }
    },
    "required": ["assetIdentifierA", "assetIdentifierB", "timeWindow"]
  },
  "budgets": {
    "timeoutSeconds": 900,
    "maxNodes": 80,
    "maxToolCalls": 200,
    "maxEvidenceBytes": 12000
  },
  "nodes": [ /* … */ ],
  "finalNode": "final_summary"
}
```

| Field | Required | Meaning |
|---|---|---|
| `schema` | yes | Must be exactly `"parler-playbook-v1"`. |
| `id` | yes (directory package) | Matches the package directory name. Grammar `[A-Za-z][A-Za-z0-9_]{0,63}`. |
| `title` | yes (directory package) | Human-readable name. |
| `description`, `whenToUse` | no | LLM-facing selection metadata. |
| `inputSchema` | no | JSON Schema for the playbook's input params (the `$input` source). |
| `budgets` | no | `timeoutSeconds`, `maxNodes`, `maxToolCalls`, `maxEvidenceBytes` (and `maxRawTableRows`, default 100000). `execution` may mirror it. |
| `nodes` | yes | Array of node objects. |
| `finalNode` | yes | Id of the single `llm_summary` node (the DAG exit). |
| `provider` | **forbidden** | Rejected by the validator. |

### 2.2 Node kinds (5)

Every node has `id` (grammar `[A-Za-z][A-Za-z0-9_-]*`), `kind`, optional `dependsOn` (array of node ids), and optional
`evidence`.

| `kind` | Required fields | Purpose |
|---|---|---|
| `tool_call` | `tool`, (`args`) | Call a playbook-safe tool (§2.4) with resolved args. |
| `derive` | `op`, (`args`) | Run a deterministic transform op (§2.5). |
| `fan_out` | `items`, `node` | Run a child `tool_call` over each item of an array. Optional **`itemVar`** names the wrapper key for **primitive** `string[]` items (default **`value`** when omitted); bind with **`{ "$item": "<itemVar>" }`**. Object items pass through unchanged — bind real fields (e.g. **`name`**). Also: `maxItems`, `maxConcurrency` (must be absent or `1`). |
| `condition` | `if`, `then`, `else` | Branch on a predicate (§2.6); `then`/`else` are node ids. |
| `llm_summary` | `prompt`, `evidenceRefs` | The single final node; writes the answer from compact evidence (§2.7). |

### 2.3 Value / reference syntax

A value in `args` (or `items`, predicate operands) is a literal **or** one binding object:

| Binding | Resolves to | Scope |
|---|---|---|
| `{ "$input": "assetType" }` | a playbook input param | anywhere |
| `{ "$var": "taxonomy.EntityName" }` | a named runtime var (dotted path) | anywhere |
| `{ "$ref": "nodeId.output.rows" }` | a prior node's output (dotted path) | anywhere |
| `{ "$item": "name" }` | the current fan-out item (object field, or primitive wrapper key per **`itemVar`**) | inside a `fan_out` child only |
| `{ "$table": "nodeId.result" }` | a prior tool's **raw InfoTable** | **only** as the entire value of a top-level `tool_call.args` parameter; must be exactly `<nodeId>.result` |
| `{ "$infotable": { "rows": { "$ref": "nodeId.output.rows" }, "dataShapeName": "MyShape" } }` | derived rows encoded as a ThingWorx `INFOTABLE` | **only** as the entire value of a top-level `tool_call.args` parameter for an extended tool |

Paths are `identifier(.identifier)*` — no wildcards, filters, scripts, or regex.

### 2.4 Tools callable from `tool_call` — the playbook-safe built-ins

A `tool_call.tool` must be playbook-safe. The built-in allowlist is exactly these **24** tools:

```text
acknowledge_alerts            build_chart_from_tabular_result   query_alert_history          query_value_stream_property_history
analyze_entity_set            build_history_overlay_chart       query_alert_summary          resolve_asset_type
describe_entity_schema        discover_thing_members            query_entities               resolve_thing
fetch_cached_result           get_property_values               query_entities_by_taxonomy   spotlight_search
invoke_service                list_asset_types                  query_numeric_property_history  summarize_cached_result
                              list_entities_by_type             query_property_history       tabulate_cached_result
                                                                query_stream_data
```

(Alphabetical: `acknowledge_alerts`, `analyze_entity_set`, `build_chart_from_tabular_result`, `build_history_overlay_chart`,
`describe_entity_schema`, `discover_thing_members`, `fetch_cached_result`, `get_property_values`, `invoke_service`,
`list_asset_types`, `list_entities_by_type`, `query_alert_history`, `query_alert_summary`, `query_entities`,
`query_entities_by_taxonomy`, `query_numeric_property_history`, `query_property_history`, `query_stream_data`,
`query_value_stream_property_history`, `resolve_asset_type`, `resolve_thing`, `spotlight_search`,
`summarize_cached_result`, `tabulate_cached_result`.)

This is the **playbook execution allowlist**, not the normal model-facing tool list. The two historical history names
`query_numeric_property_history` and `query_value_stream_property_history` are executor aliases that route to the
unified `query_property_history`; new authoring should prefer `query_property_history` unless you are preserving an old
fixture or replay path.

**Your own** extended tools are callable from a playbook only when their `extended_tools.json` entry has
**`playbookSafe: true` and `hitl: false`** (see Appendix I §1.3). `executorOnly: true` does not block playbook use; it
only hides the tool from the model's open-ended tool list.

### 2.5 Derive ops — the full catalog

`derive` nodes run a named `op`. There are three families.

**(a) Generic transform ops** — general-purpose, for any playbook:

| `op` | Purpose | Key args |
|---|---|---|
| `project` | select / rename / default columns | `rows`, `fields:[{from,as,default?}]`, `dropNullOnlyRows?` |
| `filter` | keep rows matching a predicate | `rows`, `where` (predicate, §2.6) |
| `sort` | order rows | `rows`, `orderBy:[{field,direction:asc\|desc}]` |
| `top_n` | first N (optionally sorted) | `rows`, `n` (0–5000), `orderBy?` |
| `pick_one` | exactly-one-row guard | `rows`, `where`, `onZero?`, `onMultiple?` (→ `needs_clarification`/`gap`/…) |
| `group_by` | partition + measures | `rows`, `keys:[ident]`, `maxGroups` (1–5000), `measures?` |
| `aggregate` | one-row aggregation | `rows`, `measures` |
| `join_by_key` | inner/left join two row sets | `left`, `right`, `leftKey`, `rightKey`, `joinType:inner\|left`, `rightPrefix?`, `maxRows` (1–10000) |
| `build_targets` | build fan-out target objects | `sources`, `template` (with `$path` bindings), `maxTargets` (1–200) |
| `collect_gaps` | merge gap lists | `refs:["nodeId.output.gaps"]`, `maxItems` (1–64) |
| `flatten_fan_out_rows` | flatten a fan-out's child rows | `fanOutNodeId`, `injectFromItem?:[{from,as}]` |

**Measure specs** (used by `aggregate` / `group_by`): `{ "op": "count|count_present|sum|min|max|mean", "of": "field",
"as": "name", "default"?: … }`.

**(b) Service-orchestration ops** — added in **`parler-agent` 0.1.190** for App-service playbooks such as KPI retrieval
workflows:

| `op` | Purpose | Key args |
|---|---|---|
| `normalize_resolved_things` | turn resolver fan-out children into canonical Thing rows plus gaps | `fanOutNodeId`, `maxRows`, optional `onUnresolved`, `minResolvedRows` |
| `extract_from_tool_output` | extract rows/scalars from non-`rows` tool envelopes | `sourceNodeId`, `mode`, `arrayPath`, `fields[]`, `maxRows`, optional `where` |
| `build_nested_object` | build nested JSON service payloads from named sources | `sources`, `template`, `maxParents`, `maxChildren`, optional `omitNull` |
| `json_stringify` | serialize a bounded JSON value into a string argument | `value`, `maxBytes` |
| `resolve_time_window_for_playbook` | map quick-interval rows or explicit UTC ranges into service time arguments | `quickIntervalRows`, `timezone`, optional `phrase`, `defaultQuickIntervalName` |
| `empty_rows_if_skipped` | make an optional skipped branch produce explicit empty rows | `sourceNodeId`, optional `label` |
| `add_computed_fields` | add per-row numeric or datetime-derived fields | `rows`, `fields[]`, optional `onNull` |
| `collect_values` | collect unique values from a row field | `rows`, `field`, optional caps |
| `join_values` | join a JSON array into a bounded delimited string | `values`, optional `delimiter`, `maxLength` |

These ops are still deterministic Java ops. They are not arbitrary JavaScript or expression execution.

**(c) Domain ops** — shipped for the reference playbooks; reuse them when your workflow matches. They expect specific
upstream shapes, so read the shipped workshop `cross_asset_pair_health` playbook for exact args:

| `op` | Purpose |
|---|---|
| `pick_taxonomy_row` | match the user's asset-type hint to a taxonomy row (`EntityType`/`EntityName`/`CriticalProperties`); `whenAssetTypeMissing: clarify\|empty_taxonomy` |
| `extract_field` | pull one field from each row into a flat `values[]` (default field `name`) |
| `normalize_resolved_thing` | turn a `resolve_thing` result into a canonical asset row; emits `needs_clarification` on ambiguity/not-found |
| `match_entity_identifiers` | match a user identifier against a candidate list (single → ok; many → clarification) |
| `match_identifier_in_rows` | like the above but over `$ref` rows (not `$table`) |
| `require_exact_count` | guard: fail unless exactly one match |
| `flatten_pair_assets` | merge two resolved assets into a pair |
| `flatten_region_entities` | flatten per-region fan-out asset lists; track region gaps |
| `group_alerts_by_source_property` | partition alerts by originating property |
| `build_property_union` | bounded union of critical + alerting property names (≤24) |
| `union_property_names` | de-duplicate property names from multiple sources |
| `select_primary_problem_property` | pick the highest-priority problem property |
| `trend_targets` | build property-history fan-out targets for trend analysis |
| `trend_summary` | bounded trend aggregates (first/last/min/max + direction) |
| `summarize_current_values_by_region` | aggregate current values by region (bounded examples) |
| `summarize_region_health` | synthesize per-region health from alerts/values |
| `summarize_asset_pair_health` | side-by-side pair health |
| `pick_branch_output` | take the output of the branch a `condition` selected (`conditionNodeId`, `thenNodeId`, `elseNodeId`) |

### 2.6 Condition predicates (`condition.if`, and `filter`/`pick_one`.`where`)

A predicate object has exactly **one** of these. Leaf ops:

| Op | Operands |
|---|---|
| `is_empty` / `is_present` | `field` (dotted path) or `value`/`left` |
| `eq` / `ne` / `gt` / `gte` / `lt` / `lte` | `left` (or `field`/`value`) **and** `right` |

Composition ops: `and` / `or` / `any` (array of predicates), `not` (one predicate). Example:

```json
{ "op": "and", "and": [
  { "op": "eq", "left": { "$ref": "resolve_a.toolOutput.status" }, "right": "success" },
  { "op": "is_present", "field": "name" }
] }
```

### 2.7 Evidence syntax in playbooks

Evidence is what the final `llm_summary` actually reads — node outputs are **not** passed raw.

**On a `tool_call` node**, the `evidence` block selects what to keep:

| Field | Meaning |
|---|---|
| `label` | human-readable line in the evidence ledger |
| `includeToolOutputRootFields` | array of **root scalar** field names to copy from the tool output (e.g. `appliedStartTime`, `rowCount`); ≤ 24 entries; each `[A-Za-z][A-Za-z0-9_]*` |
| `table` | `{ "maxRows": <int, default 12>, "columns": ["…"] }` — project rows/sampleRows into the ledger |

```json
{
  "id": "alert_history_by_asset",
  "kind": "fan_out",
  "items": { "$ref": "pair_assets.output.assets" },
  "itemVar": "asset",
  "maxItems": 2,
  "maxConcurrency": 1,
  "node": {
    "kind": "tool_call",
    "tool": "query_alert_history",
    "args": { "thingName": { "$item": "name" }, "relativeDuration": { "$input": "timeWindow" }, "limit": 100 },
    "evidence": {
      "label": "Read alert history window",
      "includeToolOutputRootFields": ["thingName", "appliedStartTime", "appliedEndTime", "rowCount"],
      "table": { "maxRows": 8, "columns": ["sourceProperty", "timestamp", "severity"] }
    }
  }
}
```

**On a `derive` node**, the op attaches `evidenceLines` (array of short strings) and usually `evidenceText` (joined) to
its result. The formatter prefers `evidenceLines` when both are present, and trend evidence should carry bounded
aggregates (first/last/min/max) and a direction (`rising`/`falling`/`flat`/`mixed`), never raw rows.

Do not request a dimension from the summarizer that the derive discarded. For multi-Thing alerts, retain bounded
per-Thing attribution and its omission marker; property-count groups alone cannot support a claim about which Thing
raised an alert. Current-value examples remain value evidence even when their Thing names are visible.

**On the `llm_summary` node**, `evidenceRefs` lists the node ids whose evidence to include, and `maxEvidenceBytes` caps
the serialized ledger (UTF-8 safe truncation; default 8000):

```json
{
  "id": "final_summary",
  "kind": "llm_summary",
  "dependsOn": ["pair_summary"],
  "prompt": "Write an evidence-grounded ranked health assessment. Use only the provided playbook evidence.",
  "evidenceRefs": ["resolve_a", "resolve_b", "alert_history_by_asset", "trend_summary", "pair_summary"],
  "maxEvidenceBytes": 8000
}
```

### 2.8 Hard guards (the validator rejects the playbook otherwise)

- Exactly **one** `llm_summary` node, and it **must** be `finalNode`.
- No `provider` field anywhere.
- Node ids unique and grammar-valid; the graph is **acyclic**; **no orphan nodes** (every node must be reachable from
  `finalNode` via `dependsOn` / branch / `evidenceRefs`).
- `fan_out.maxConcurrency` must be absent or `1`; the `fan_out` child must be a `tool_call`.
- `tool_call.tool` must be playbook-safe (§2.4).
- `$table` only as a whole top-level `tool_call.args` value, exactly `<nodeId>.result`; `$infotable` only as a whole
  top-level `tool_call.args` value with explicit `dataShapeName`; `dependsOn` / `then` / `else` /
  `evidenceRefs` / `collect_gaps.refs` must reference existing nodes.

### 2.9 Worked references

Read the shipped workshop playbook end-to-end as the canonical example:

- `cross_asset_pair_health` (workshop `day3`) — pair resolution, alert history plus current alert summary, current
  alert-summary property selection, trend charting via `build_targets` + `top_n`, and an evidence-grounded final prompt
  with many `evidenceRefs`.

---

## 3. Skill vs playbook

### Semantic profile references are server-owned facts

When a tool or Playbook consumes a semantic profile, treat exact bindings, units, grain, cadence, evidence status,
and completeness as server-authored facts. A skill may explain how to use them but must not invent a role binding,
convert incompatible units, or turn partial/permission-limited evidence into a complete population claim.

| Question | Skill | Playbook |
|---|---|---|
| Who plans the next tool call? | the LLM | the runtime DAG |
| Who passes typed tables between steps? | the LLM remembers / re-fetches | the runtime (`$ref` / `$table`) |
| Evidence to the final answer | tool results the model chose | the compact ledger (`evidenceRefs`) |
| Best for | flexible guidance | a stable, repeatable workflow |

> Start with a skill. Promote to a playbook only after the workflow is stable enough to encode as a graph — and convert
> the skill's evidence rules into the playbook's `evidence` blocks and final-summary prompt.

## 4. See also

- Chapter 11 — From built-in tools to a skill. Chapter 12 — Playbooks. Chapter 15 — Skills and evidence grounding.
- Appendix I — Extended tools (`playbookSafe` + `hitl: false` to use your own tool in a playbook).
- Parler `docs/agent/playbook-engine.md`, `docs/agent/skill-management.md` — maintainer references for normative detail.
