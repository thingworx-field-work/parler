# Playbook service-orchestration primitives (alarm events / KPI values)

Status: **implemented** in **`parler-agent`**. Normative reference for the service-orchestration Playbook
capabilities below. The two reference workflows — **alarm events** and **KPI values retrieval** — come from the
training material; the file name keeps their original discussion numbers (#34, #35).

## 0. Pointers and touchpoints

**Related agent docs.**

- `docs/agent/playbook-engine.md` — Playbook JSON model, node kinds, `$ref` / `$table`,
  validation, packaged playbook layout.
- `docs/agent/playbook-generic-ops-foundation.md` — row ops, predicates, path grammar,
  caps, evidence envelopes; the ops here stay consistent with it.
- `docs/agent/playbook-input-resolution.md` — singular **`normalize_resolved_thing`**
  envelope matrix and **`pick_branch_output`** patterns extended here to dynamic
  fan-out lists.
- `docs/agent/playbook-builtin-capability-expansion.md` — Playbook-safe built-ins such as
  `resolve_thing`, `get_property_values`, and `invoke_service`.
- `docs/agent/time-interpretation.md` — natural-time semantics reused by time-window
  derivation (§6.7).

**Java runtime sites.**

| Area | Types / notes |
| --- | --- |
| Derive allowlist | `PlaybookValidator` — orchestration ops in the derive allowlist; per-op arg validation fail-closed. |
| Derive execution | `PlaybookGenericDeriveOps` and package-local orchestration helpers dispatched from `PlaybookDeriveOps.execute`. |
| Fan-out runtime | `PlaybookRunner.executeFanOut` — child entries `{ item, status, toolOutput }`; orchestration ops read `output.children[]`, not `toolOutput.rows`. |
| Path grammar | `PlaybookGenericPathGrammar` (dotted identifiers) and `PlaybookOrchestrationPath` (§6.3). |
| Row predicates | `PlaybookRowPredicate`, `PlaybookPredicateValueOps` — reused for `extract_from_tool_output.where` and optional-branch predicates. |
| Expressions | `PlaybookExpressionResolver` — `$infotable` at **top-level** `tool_call.args` parameter sites (§6.4); `$table` semantics unchanged. |
| INFOTABLE codec | `InfotableJsonCodec`, `InvokeServiceArgumentCoercion` — row-shape validation before service invocation. |
| Time | `ParlerTimeResolver` — explicit UTC range leg of `resolve_time_window_for_playbook`; quick-interval matching stays row-driven. |
| Clarification stop | `PlaybookRunner` — derive ops with `status: needs_clarification` remain hard stops. |

**Wire / UI contracts.** Observable AlwaysOn / widget wire is unchanged (see §5). Playbook
diagnostics from these ops are not promoted to `done` or history JSON.

**Fixtures.** Reference fixtures live under `parler-agent/src/test/resources/`.

Both reference workflows started as Skills that work acceptably in the chat loop. They were
hard to convert into Playbooks because the engine was good at flat row/table analysis but
not at service orchestration:

```text
resolve user-facing equipment names
-> read scalar UIDs from tool envelopes
-> construct service-specific payloads
-> bind the payload into ThingWorx service parameters
-> summarize bounded evidence
```

These primitives let the Skill-to-Playbook converter produce useful Playbooks instead of
mostly reporting "not convertible".

## 1. Background

Earlier Playbook engine work built a workflow runtime:

- built-in tool capability expansion made most read-oriented built-ins Playbook-capable;
- generic ops added flat row/list transforms such as `project`, `filter`, `sort`,
  `top_n`, `group_by`, `aggregate`, `build_targets`, `join_by_key`, `pick_one`,
  `collect_gaps`, and `flatten_fan_out_rows`;
- artifact emission made chart/table outputs from Playbooks visible to the UI;
- directory packaging moved Playbooks to `/playbooks/<id>/playbook.json`;
- input resolution added resolver-first patterns such as `normalize_resolved_thing`,
  `match_identifier_in_rows`, and `pick_branch_output`.

With only that foundation, the engine can express:

```text
query rows -> group rows -> pick top N -> fan out over bounded targets
```

but not enough of:

```text
fan out resolver calls -> normalize multiple resolved Things
-> read scalar properties from non-rows envelopes
-> build nested app-service payloads
-> serialize/bind those payloads to service parameters
```

The answer is not to turn `derive` into a scripting language, but a small set of
deterministic, bounded, reusable primitives that cover common ThingWorx app service
orchestration.

Related documents:

- `docs/agent/playbook-engine.md`
- `docs/agent/playbook-generic-ops-foundation.md`
- `docs/agent/playbook-input-resolution.md`
- `docs/agent/playbook-builtin-capability-expansion.md`

## 2. Reference workflows

### 2.1 Alarm events

The alarm-events Skill needs to:

1. resolve one or more equipment identifiers;
2. read each equipment Thing's numeric `UID`;
3. build UID rows or an INFOTABLE-compatible payload;
4. call an app service such as `GetAlarmEvents_AI`;
5. optionally compute acknowledgement / closure duration metrics;
6. summarize the result.

Without the primitives below, a faithful multi-step Playbook is blocked because:

- `resolve_thing` returns a resolver envelope, not `toolOutput.rows`;
- `get_property_values` returns `properties[]`, not `toolOutput.rows`;
- `flatten_fan_out_rows` only concatenates child `toolOutput.rows`;
- dotted paths cannot select `properties[?name=UID].value`;
- rows-to-INFOTABLE binding is not an explicit Playbook authoring contract;
- duration metrics require computed fields before aggregation.

### 2.2 KPI values retrieval

The KPI Skill needs to:

1. resolve one or more equipment identifiers;
2. read equipment UIDs;
3. resolve a time window, either by quick interval UID or explicit UTC start/end;
4. optionally resolve product, job order, shift, and crew filters;
5. assemble a nested payload such as:

```json
{
  "Filters": [
    {
      "EquipmentUID": 4,
      "FilterCriterias": [
        { "FilterCriteria": "PRODUCT", "UIDValue": 8 },
        { "FilterCriteria": "SHIFT", "UIDValue": 4 }
      ]
    }
  ],
  "QuickTimeIntervalUID": 3
}
```

6. stringify that payload because `CallGetKPIs` expects a JSON string parameter;
7. call the KPI service and summarize the result.

Without the primitives below, a faithful Playbook is blocked because:

- the same dynamic resolution and property extraction gaps as alarm events apply;
- nested payload construction is beyond `project` / `build_targets` / `join_by_key`;
- there is no explicit `json_stringify` operation;
- natural time fallback needs deterministic timezone-aware time-window derivation;
- optional filter branches are expressible only with verbose, fragile graph patterns.

## 3. Delivery boundary

The same delivery-first boundary as the earlier Playbook engine work applies.

Rules:

- Deterministic Playbook primitives, not arbitrary scripts.
- Keep using existing `tool_call`, `fan_out`, `condition`, `derive`, and final
  `llm_summary` node kinds.
- No JavaScript execution inside Playbook JSON.
- No general JSONPath engine; only narrowly-scoped extraction for bounded arrays and
  first-party/tool envelopes.
- Keep the validator structural and capability-oriented. Tool executors and existing
  policies continue to own their normal behavior.
- Operation outputs are bounded and evidence compact.

## 4. Goals

| Capability | Required by | Goal |
| --- | --- | --- |
| `normalize_resolved_things` | alarm events, KPI values | Normalize `fan_out(resolve_thing)` results into canonical Thing rows with gaps / ambiguity handling. |
| Generic envelope extraction (`extract_from_tool_output`) | alarm events, KPI values | Extract rows or scalar values from non-`rows` tool envelopes such as `properties[]`, `matches[]`, and nested service rows. |
| Nested payload assembly (`build_nested_object`) | KPI values | Build bounded nested JSON objects/arrays for app-service payloads without bespoke Java ops. |
| Explicit JSON serialization (`json_stringify`) | KPI values | Serialize a bounded Playbook object to a JSON string for service parameters. |
| Explicit rows/object/INFOTABLE service-argument binding (`$infotable`) | alarm events, KPI values | Make derived JSON rows/objects usable as ThingWorx service parameters with a documented, validated contract. |
| Bounded orchestration paths | alarm events, KPI values | Array selection and row matching needed for scalar extraction, without arbitrary JSONPath. |
| Playbook time-window derivation (`resolve_time_window_for_playbook`) | KPI values | Resolve quick intervals and explicit UTC start/end ranges using user timezone rules where needed. |
| Optional-input branch ergonomics (`empty_rows_if_skipped`) | KPI values | Make optional product/job/shift/crew filters easier to skip, include, and report as gaps. |

Analytics helpers:

| Capability | Reason |
| --- | --- |
| `add_computed_fields` | Alarm acknowledgement / closure duration metrics before `aggregate`. |
| `collect_values` / `join_values` | App services that accept CSV/list scalar inputs instead of INFOTABLE/object payloads. |
| Primitive list item binding | `fan_out.itemVar` names the primitive wrapper key; `playbook-input-resolution.md` §6.0.6. |

## 5. Non-goals

These primitives do not:

- implement arbitrary JavaScript, Groovy, JSONPath, JMESPath, or regex scripting inside
  Playbooks;
- solve every possible nested object construction shape;
- make app-specific business payloads into built-in domain concepts;
- require every app service to avoid wrapper services;
- remove existing business-specific derive ops;
- rewrite all existing Playbooks;
- change AlwaysOn / UI wire contracts.

Wrapper services remain valid. The point is not to eliminate wrappers; the point is to
avoid forcing every ordinary multi-Thing resolution and payload-shaping step into a
wrapper.

## 6. Capabilities

### 6.1 `normalize_resolved_things`

Purpose: normalize dynamic resolver fan-out results.

Typical input:

```json
{
  "id": "resolved_equipment",
  "kind": "derive",
  "op": "normalize_resolved_things",
  "dependsOn": ["resolve_equipment"],
  "args": {
    "fanOutNodeId": "resolve_equipment",
    "inputField": "input",
    "onUnresolved": "clarify",
    "maxRows": 25
  }
}
```

Expected output shape:

```json
{
  "status": "ok",
  "output": {
    "rows": [
      {
        "input": "AMU CNC Mill",
        "name": "TDD.FSU.CNCMill",
        "displayName": "AMU CNC Mill",
        "resultKind": "thing"
      }
    ],
    "gaps": [],
    "totalCount": 1,
    "returned": 1
  }
}
```

Rules:

- It must understand the current `resolve_thing` result envelope.
- It must preserve the original input item where the fan-out framework exposes it.
- It must produce canonical Thing names for downstream tools.
- It must not guess when a resolver result is ambiguous.
- `onUnresolved` supports:
  - `clarify`: emit `needs_clarification` if any item is unresolved/ambiguous;
  - `gap`: continue with resolved rows and record unresolved items in `output.gaps`.
- It must be bounded by `maxRows` and runtime fan-out caps.

This is separate from the existing singular `normalize_resolved_thing`, which is useful
for fixed single-entity branches but not enough for dynamic equipment lists.

**Fan-out child envelope (runtime fact).** After `fan_out` over `resolve_thing`, each
child is stored as:

```json
{
  "item": { "input": "AMU CNC Mill" },
  "status": "ok",
  "toolOutput": { "status": "success", "resultKind": "...", "matches": [ ... ] }
}
```

(`PlaybookRunner.executeFanOut` — the `item` object is the fan-out list element; string
items are wrapped as `{ "region": "<string>" }`; Playbook authors should prefer
object items with a stable key such as `input` for equipment identifiers.)

**Per-child normalization matrix.** For each child with `status: ok` and non-null
`toolOutput`, classify using the same **decision table** as singular
`normalize_resolved_thing` (`docs/agent/playbook-input-resolution.md` §6.0.3), then
map the outcome into the plural **`rows[]` / `gaps[]`** envelope (not the singular
`needs_clarification` stop unless `onUnresolved: clarify` applies to that child):

| `toolOutput` condition | Row emitted | Gap / clarify |
| --- | --- | --- |
| `status: success`, exactly one `matches[]` row | `{ input, name, displayName, resultKind: "thing" }` | — |
| `status: success`, `resultKind` large / sampled | — | gap row `{ input, reason: "large", candidates? }` |
| `status: success`, zero or multiple `matches[]` | — | gap or clarify per `onUnresolved` |
| `status: error`, ambiguous / not-found codes | — | gap or clarify with bounded `candidates` when present |
| taxonomy / configuration errors | — | gap or clarify (same as singular op) |
| child `status: failed` or missing `toolOutput` | — | gap `{ input, reason: "child_failed" }` |

**Aggregate output.** Concatenate successful rows into `output.rows`; unresolved items
into `output.gaps`. Set `output.totalCount` to the logical child count and
`output.returned` to emitted rows after `maxRows` truncation (same envelope family as
`flatten_fan_out_rows`).

**`onUnresolved` default (see §14.3).** When omitted, default to **`gap`** (continue
with resolved rows). Playbooks that must hard-stop on any unresolved item set
`onUnresolved: "clarify"` explicitly.

**Relationship to singular `normalize_resolved_thing` (not inherited behavior).** The plural op is **new logic** with a different output envelope. The only
rule shared with the singular op is: exactly one successful `matches[]` row maps to one
canonical Thing name. Differences authors must know:

| Aspect | Singular `normalize_resolved_thing` | Plural `normalize_resolved_things` |
| --- | --- | --- |
| Input | One `resolve_thing` `tool_call` node | One `fan_out(resolve_thing)` node |
| Non-unique outcomes | Always `needs_clarification` (no gap mode) | `gap` or `clarify` per `onUnresolved` |
| Success envelope | `{ output: { name, row, … } }` | `{ output: { rows[], gaps[], totalCount, returned } }` |
| Per-item partial success | N/A | Supported when `onUnresolved: gap` |

Per-child classification may share private helpers, but the Playbook-facing contracts
above are normative.

**Required-input guard.** Optional arg `minResolvedRows` (integer ≥
0, default **0**). Compared against the **pre-`maxRows` resolved row count** (children
that produced a success row). When `resolvedCount < minResolvedRows`, emit
`needs_clarification`. Validator MUST reject `maxRows < minResolvedRows`. Downstream
binding ops (`$infotable`, `build_nested_object`) MUST also fail before service
invocation when a **required** row source resolves to an empty array unless the Playbook
sets an explicit `allowEmpty: true` on that binding step (see §6.4, §6.5).

**Authoring: fan-out `items` shape.** Prefer `inputSchema` arrays of objects, e.g.
`[{ "input": "AMU CNC Mill" }]`, so `item.input` is stable. Bare strings are wrapped as
`{ "region": "<string>" }` by the runtime; normalization reads `inputField` (default
`input`, falling back to `region` when `input` absent).

### 6.2 Generic envelope extraction

Purpose: turn bounded non-row envelopes into rows or scalar fields.

Generic row ops expect `rows`. Real tools often return other shapes:

- `get_property_values`: `properties[]`;
- `resolve_thing`: `matches[]` or resolver result fields;
- lookup services: nested result tables such as `Table0`;
- quick interval services: rows where one scalar UID must be selected.

One generic operation, `extract_from_tool_output`, covers these shapes.

Illustrative shape (`mode` must be `fan_out_children` — see context roots below):

```json
{
  "id": "uid_rows",
  "kind": "derive",
  "op": "extract_from_tool_output",
  "dependsOn": ["uid_reads"],
  "args": {
    "sourceNodeId": "uid_reads",
    "mode": "fan_out_children",
    "arrayPath": "properties",
    "where": { "field": "name", "op": "eq", "right": "UID" },
    "fields": [
      { "from": "value", "as": "UID", "type": "number" },
      { "from": "$parent.item.input", "as": "input" },
      { "from": "$parent.item.name", "as": "thingName" }
    ],
    "onUnresolved": "gap",
    "maxRows": 25
  }
}
```

Rules:

- It must support single tool results and fan-out child results.
- It must be bounded.
- It reuses the existing predicate model.
- It must not become arbitrary JSONPath.
- It must produce the same generic envelope family as other row ops:
  `status`, `output.rows`, `output.gaps`, `totalCount`, `returned`.
- Gap-vs-clarify for missing matches uses **`onUnresolved`** (same token as
  `normalize_resolved_things`; values `gap` | `clarify`; default `gap`).

**Op shape.** One public op `extract_from_tool_output` (not separate derive names per
envelope). Args:

| Field | Required | Purpose |
| --- | --- | --- |
| `sourceNodeId` | yes | Playbook node id (op-local handle — **not** a value `$ref`). The op reads `ctx.nodeOutput(sourceNodeId)` itself. |
| `mode` | yes | `single` (read one `tool_call` node's `toolOutput`) or `fan_out_children` (read `fan_out` `children[]`) |
| `arrayPath` | yes | Orchestration path (§6.3) relative to each `toolOutput` root — must resolve to a JSON **array** |
| `where` | no | Same predicate object model as `filter` (`field`, `op`, `value`) |
| `fields` | yes | `[{ "from", "as", "type"? }]` row projection; `$parent.*` allowed only in `fan_out_children` mode |
| `maxRows` | yes | Output cap |
| `onUnresolved` | no | `gap` (default) or `clarify` when array/where match missing |

**Context roots (normative).**

| `mode` | `sourceNodeId` must name | `arrayPath` root | `fields[].from` root | `$parent` |
| --- | --- | --- | --- | --- |
| `single` | A `tool_call` node | That node's **`toolOutput`** object | Each matched **array element** | **Forbidden** — validator error |
| `fan_out_children` | A `fan_out` node | Each child's **`toolOutput`** | Each matched array element | **`$parent.<dotted>`** navigates the child entry `{ item, status, toolOutput }` by dotted keys only (§6.3.4) |

**Fan-out provenance.** The UID-read `fan_out` MUST iterate
`normalize_resolved_things.output.rows` (objects carrying `name`, `input`, …). If it
iterates raw resolver children instead, `$parent.item.name` is absent and extraction
gaps. §7.1 DAG assumes normalized rows as the UID fan-out source.

**Canonical alarm-events UID preset** (authoring example; not a separate op):

```json
{
  "arrayPath": "properties",
  "where": { "field": "name", "op": "eq", "right": "UID" },
  "fields": [
    { "from": "value", "as": "UID", "type": "number" },
    { "from": "$parent.item.input", "as": "input" },
    { "from": "$parent.item.name", "as": "thingName" }
  ]
}
```

When `mode: fan_out_children`, missing arrays, failed children, or rows with no `where`
match produce bounded structured **`output.gaps`** entries (`{ input, reason, arrayPath,
sourceNodeId, childIndex? }`) instead of failing the whole derive unless
`onUnresolved: "clarify"`.

### 6.3 Bounded orchestration paths

Purpose: support the small amount of array access needed by service orchestration inside
**orchestration-path** consumers (§6.3.1). `$ref` navigation is not extended.

#### 6.3.1 Two path engines

| Engine | Used by | Grammar | Brackets |
| --- | --- | --- | --- |
| **Dotted navigate** (existing) | `$ref` in `PlaybookExpressionResolver.navigate` / `navigateRef`; generic op field names validated by `PlaybookGenericPathGrammar` | `segment ('.' segment)*` | **Rejected** — non-object segment throws |
| **Orchestration path** | `extract_from_tool_output.arrayPath`; `fields[].from` (non-`$parent`); §6.3.4 `$parent` roots | §6.3.2–6.3.4 | **Rejected** — dotted segments only |

Terminal arrays via dotted `$ref` already work today (`uid_only.output.rows` — `rows` is
the last segment). Bracket forms MUST NOT appear in `$ref` strings.

Validator tests MUST reject bracket syntax in `$ref` paths and reject orchestration
brackets outside `extract_from_tool_output` args.

#### 6.3.2 Orchestration path grammar

```text
orchPath   ::= segment ('.' segment)*
segment    ::= [A-Za-z_][A-Za-z0-9_]*
```

Example: `arrayPath: "properties"` + `where: { field: name, op: eq, right: UID }` +
`fields[].from: "value"`.

Filter array rows with **`where`** rather than path predicates. There is no scalar pick
without row materialization (for example `properties[name=UID].value`) and no fixed index
(`rows[0].UID`): extract rows with `where`, then use a row op such as `pick_one`.

Reject at validation: bracket segments, regex predicates, boolean combinations inside
paths, unbounded wildcards, `$parent` in `arrayPath`.

#### 6.3.3 Match semantics

When `where` matches zero rows for a child, the child produces a gap (or
`needs_clarification` when `onUnresolved: clarify`).

#### 6.3.4 `$parent` field roots (`fan_out_children` only)

When `fields[].from` starts with `$parent.`, the remainder MUST be a dotted path
(`item.input`, `item.name`, `toolOutput.status`, …) with **no bracket segments**.
Validator errors on `$parent` in `single` mode or in `arrayPath`.

Allowed sub-roots: `$parent.item.*` (fan-out list element), `$parent.toolOutput.*`
(read-only diagnostic fields — avoid for UID preset).

### 6.4 Rows/object/INFOTABLE service-argument binding

Purpose: make Playbook-derived data safe and explicit when passed to ThingWorx services.

`$table` handling (`PlaybookExpressionResolver.resolvePlaybookToolCallArgs`)
accepts `{ "$table": "<nodeId>.result" }` only as the **sole** value of a **top-level**
`tool_call.args` key and feeds the existing `PlaybookResolvedToolArgs.infotableArgs()`
map. Nested `$table` anywhere else is rejected (`TABLE_REF_NOT_ALLOWED_HERE`).

#### 6.4.1 Tool families and placement

| Tool family | Where INFOTABLE service parameters live | `$infotable` placement (v1) |
| --- | --- | --- |
| **Repository extended tools** (harvested custom tools) | Top-level `tool_call.args.<paramName>` | Sole value of that top-level key — **same slot as `$table`** |
| **`invoke_service` built-in** | Nested `tool_call.args.parameters.<serviceParam>` | **Not supported** — use a repository extended tool for UID INFOTABLE binding |

`$infotable` is an **additional producer into the existing `infotableArgs` channel** (not
a parallel binding mechanism). Scope: repository extended tools only, at top-level
`tool_call.args.<paramName>`. `CustomToolHarvester.buildValueCollectionForPlaybookExtendedTool`
merges `infotableArgs` overrides; `PlaybookInfotableBindingPolicy` and
`PlaybookValidator` fail closed when the tool is not in the extended-tool registry.
Nested `invoke_service.parameters.$infotable` is not supported.

**Extended-tool example (alarm events, when the app exposes a harvested tool):**

```json
{
  "tool": "GetAlarmEvents_AI",
  "args": {
    "EquipmentUIDs": {
      "$infotable": {
        "rows": { "$ref": "uid_only.output.rows" },
        "dataShapeName": "PTC.SCA.SCO.Utilities.UID"
      }
    }
  }
}
```

#### 6.4.2 `$infotable` object rules

- `$infotable` MUST be the **sole** key of the parameter object (mirror `$table`
  fail-closed pattern).
- `rows` MUST be `{ "$ref": "<nodeId>.output.rows" }` (or another dotted `$ref` whose
  terminal value is a JSON array of objects). Inline row arrays are rejected.
- `dataShapeName` is required when the target parameter is a named DataShape INFOTABLE.
- Optional `allowEmpty` (boolean, default **false**): when false, **zero rows** fails
  validation/runtime before the service call with a clear diagnostic (required-input
  guard — §6.1).
- Runtime builds the `InfoTable` via `InfotableJsonCodec`; invalid columns fail before
  invocation.
- Legacy `$table` semantics remain unchanged.
- There is no `coerce_rows_to_infotable` derive op.

**`$table` vs `$infotable`.** `$table` binds a retained platform
`InfoTable` from `ctx.rawTable("<nodeId>.result")` (pass-through). `$infotable.rows`
binds a JSON row array via `$ref` and **constructs** an `InfoTable` through
`InfotableJsonCodec`. Both feed `infotableArgs`, but only `$infotable` validates/builds
from derive output. Binding is also documented in `playbook-engine.md`.

### 6.5 Nested payload assembly (`build_nested_object`)

Purpose: build bounded nested JSON payloads such as the KPI `Filters` object.

This is not a general object-programming language. It covers a small family of
repeatable patterns:

- create one object per equipment row;
- attach a shared array of filter criteria to each object;
- optionally include top-level fields such as `QuickTimeIntervalUID`, `StartTime`,
  `EndTime`;
- skip absent optional branches cleanly.

Illustrative target:

```json
{
  "status": "ok",
  "output": {
    "object": {
      "Filters": [
        {
          "EquipmentUID": 4,
          "FilterCriterias": [
            { "FilterCriteria": "PRODUCT", "UIDValue": 8 },
            { "FilterCriteria": "SHIFT", "UIDValue": 4 }
          ]
        }
      ],
      "QuickTimeIntervalUID": 3
    }
  }
}
```

Supported patterns:

- map source rows to object rows with renamed fields;
- attach one or more shared child arrays to every parent object;
- merge a bounded set of top-level scalar/object fields;
- omit fields whose source branch is absent or skipped;
- record gaps for missing optional/required sources.

Without it, the KPI Skill would need a wrapper service even after identity and UID
extraction.

**Op shape (extends `build_targets`, not a second DSL).** `build_nested_object` shares the
**`sources` + `template` + caps** model of `build_targets` (`PlaybookGenericBuildTargets`,
`playbook-generic-ops-foundation.md` §6.5); the directives below are the delta, not a
parallel language.

| Field | Required | Purpose |
| --- | --- | --- |
| `sources` | yes | Named row sources `{ "name": { "$ref": "node.output.rows" }, ... }` |
| `template` | yes | Output object template (§6.5.1) |
| `maxParents` | yes | Cap on `$map` iterations |
| `maxChildren` | yes | Cap on **total row copies** from shared `$src` attachments across the payload |
| `omitNull` | no | When true, omit template keys whose resolved value is JSON null |
| `minParents` | no | When ≥ 1 and mapped parent count is below this minimum, emit `needs_clarification` (required-input guard; mirrors `minResolvedRows`) |

#### 6.5.1 Template directives vs literal output keys

Template keys such as `Filters`, `QuickTimeIntervalUID`, `StartTime` are **literal JSON
output keys**. Reserved **directive** keys (validator recognizes only these inside
directive objects):

| Directive | Allowed where | Meaning |
| --- | --- | --- |
| `$map` | Value of an output-array key | Object `{ "over": "<sourcesKey>", "each": { … } }` — iterate that source |
| `$src` | Inside `$map.each` only (v1) | Copy rows from `sources[$src]` (shared array attachment) |
| `$nodeRef` | Top-level template leaf or inside `each` | Dotted node path via `navigateRef` |
| `$path` | Inside `$map.each` only | Field on the **current mapped row** — single segment v1 (e.g. `"UID"`), no source prefix |
| `$literal` | Any template leaf | Explicit constant (alternative to bare JSON literals) |

**Directive placement (validator/runtime parity).** The table above is normative;
authoring MUST match validator admission:

| Directive | Top-level template | Inside `$map.each` |
| --- | --- | --- |
| `$map` | yes (output-array value) | no |
| `$src` | no | yes |
| `$nodeRef` | yes | yes |
| `$path` | no | yes |
| `$literal` | yes | yes |

**Skipped optional branches.** When a `condition` branch marks nodes skipped,
`$nodeRef` / `$ref` to those nodes resolves to JSON null at runtime (not a hard failure),
so `build_nested_object` with `omitNull: true` can omit optional filter/time fields.
Use `empty_rows_if_skipped` when a downstream step needs explicit empty `output.rows`
from an optional branch (§6.8).

Do **not** overload `$ref` for both source names and node paths. `$path` MUST NOT use
`sourceName.field` prefixes (that is the `build_targets` cartesian form — use `$map` +
`$path` instead).

**Null-leaf rule.** When `$nodeRef` resolves through
`navigateRef` to a missing object key, the value is **JSON null** (not an error). When
`omitNull: true`, keys whose resolved value is null are omitted from the output object.
This supports mutually exclusive time fields (`QuickTimeIntervalUID` vs
`StartTime`/`EndTime` — §6.7).

**`maxChildren` scope.** Counts every row copied from a `$src` attachment (e.g. shared
`criteria` rows attached to each equipment parent). Truncation emits a bounded gap when
the cap is exceeded.

Illustrative args for the KPI `Filters`:

```json
{
  "sources": {
    "equipment": { "$ref": "equipment_uids.output.rows" },
    "criteria": { "$ref": "filter_entries.output.rows" }
  },
  "template": {
    "Filters": {
      "$map": {
        "over": "equipment",
        "each": {
          "EquipmentUID": { "$path": "UID" },
          "FilterCriterias": { "$src": "criteria" }
        }
      }
    },
    "QuickTimeIntervalUID": { "$nodeRef": "time_window.output.QuickTimeIntervalUID" },
    "StartTime": { "$nodeRef": "time_window.output.StartTime" },
    "EndTime": { "$nodeRef": "time_window.output.EndTime" }
  },
  "maxParents": 25,
  "maxChildren": 100,
  "minParents": 1,
  "omitNull": true
}
```

Output: `{ "status": "ok", "output": { "object": { ... } } }`.

### 6.6 Explicit JSON serialization (`json_stringify`)

Purpose: serialize a bounded object/array into a string parameter.

Op:

```json
{
  "id": "filters_string",
  "kind": "derive",
  "op": "json_stringify",
  "dependsOn": ["filters_payload"],
  "args": {
    "value": { "$ref": "filters_payload.output.object" },
    "maxBytes": 12000
  }
}
```

Expected output:

```json
{
  "status": "ok",
  "output": {
    "value": "{\"Filters\":[...],\"QuickTimeIntervalUID\":3}",
    "byteCount": 183
  }
}
```

Rules:

- Serialize only JSON values already present in Playbook state.
- Enforce `maxBytes`.
- Do not serialize arbitrary Java objects.
- Evidence should include size and field names, not the full string by default.

### 6.7 Playbook time-window derivation (`resolve_time_window_for_playbook`)

Purpose: support app services that need either a quick interval UID or explicit UTC
start/end fields.

The KPI workflow shows a common pattern:

1. call `GetQuickTimeIntervals`;
2. match the user's phrase to `name` / `displayName`;
3. if matched, emit `QuickTimeIntervalUID`;
4. if not matched, derive `StartTime` / `EndTime` from a calendar phrase and user
   timezone;
5. if no time is supplied, default to Today and explain the default.

A deterministic Playbook primitive reuses the existing time-interpretation rules.

Op:

```json
{
  "id": "time_window",
  "kind": "derive",
  "op": "resolve_time_window_for_playbook",
  "dependsOn": ["quick_intervals"],
  "args": {
    "phrase": { "$input": "timeWindow" },
    "quickIntervalRows": { "$ref": "quick_intervals.toolOutput.rows" },
    "defaultQuickIntervalName": "Today",
    "timezone": { "$var": "user_timezone" },
    "roundTo": "hour"
  }
}
```

Expected output:

```json
{
  "status": "ok",
  "output": {
    "mode": "quickInterval",
    "QuickTimeIntervalUID": 3,
    "label": "Today",
    "defaulted": true
  }
}
```

or:

```json
{
  "status": "ok",
  "output": {
    "mode": "explicitRange",
    "StartTime": "2026-06-01T04:00:00.000Z",
    "EndTime": "2026-06-02T04:00:00.000Z",
    "timezone": "America/Toronto"
  }
}
```

Rules:

- Prefer quick interval match when available.
- Use the same user timezone semantics already documented for time interpretation.
- Make unsupported phrases explicit gaps or clarification stops.
- There is no second natural-language time system.

**Reuse split.**

| Leg | Mechanism |
| --- | --- |
| Quick interval UID | Deterministic row match: `match_identifier_in_rows` or `pick_one` over `GetQuickTimeIntervals` rows, then pass matched row into `resolve_time_window_for_playbook` OR fold matching into the op via `quickIntervalRows` + phrase |
| Explicit UTC range | `ParlerTimeResolver` with `timezone` from `{ "$var": "user_timezone" }` — same semantics as built-in natural-time tools |
| Default Today | When phrase empty and `defaultQuickIntervalName` set, select that row if present; set `defaulted: true` in output |
| Unsupported phrase | `needs_clarification` or gap per `onUnsupported` arg (default `clarify`) |

The derive op is a thin orchestration wrapper; it MUST NOT fork calendar parsing logic
outside `ParlerTimeResolver` / documented time-interpretation rules.

### 6.8 Optional-input branch ergonomics

Purpose: make optional filters less fragile.

In a Skill, the LLM can decide "the user mentioned Product X". In a Playbook, optional
product/job/shift/crew values must be declared inputs or extracted before the Playbook
starts. The engine makes this shape easy to express:

```text
if productNames is empty -> skip product lookup and emit no filter entries
if productNames present -> fan_out lookup -> normalize filter entries
```

Mechanisms:

- a standard predicate for missing/empty input arrays and blank strings;
- a compact way to mark a branch as optional and skipped;
- skipped optional branches should produce deterministic empty rows, not missing-node
  reference failures;
- **`empty_rows_if_skipped`** derive op: when `sourceNodeId` was skipped by branch planning,
  emit `{ rows: [], skipped: true }`; otherwise pass through `output.rows`;
- `$ref` / `$nodeRef` to skipped nodes resolve to null (optional fields omitted with
  `omitNull`) rather than failing the run;
- final evidence can say which optional filters were applied.

Together with `condition` + `pick_branch_output`, this makes optional filters authorable
without copying large graph fragments.

### 6.9 Computed fields (`add_computed_fields`)

Purpose: compute per-row values before aggregation.

Alarm events benefit from:

```text
ackMinutes = AcknowledgeTime - CreatedTime
closeMinutes = ClosedTime - CreatedTime
```

Expression support is deliberately tiny:

- datetime difference in minutes;
- numeric arithmetic over existing numeric fields;
- null behavior (`skip`, `gap`, or `null`);
- output caps.

### 6.10 Collect / join values

Purpose: support app services that accept primitive lists or delimited strings.

Ops:

- `collect_values`: rows -> unique array of a field.
- `join_values`: array -> delimited string with max length.

## 7. End-to-end target shapes

### 7.1 Alarm-events target DAG

Alarm events are expressible as:

```text
input equipmentIdentifiers[] (prefer [{ input }] objects)
-> fan_out resolve_thing(items = equipmentIdentifiers)
-> normalize_resolved_things(fanOutNodeId = resolve_equipment)
-> fan_out get_property_values(items = normalize.output.rows)  ← MUST use normalized rows
-> extract_from_tool_output(mode = fan_out_children, arrayPath = properties, …)
-> bind UID rows to GetAlarmEvents_AI EquipmentUIDs via $infotable
-> optional aggregate / computed metrics
-> final llm_summary
```

Duration metrics can use `add_computed_fields` or remain in the service or final LLM
summary; resolution + UID + service call do not require a wrapper.

### 7.2 KPI values target DAG

KPI values are expressible as:

```text
input equipmentIdentifiers[], timeWindow, optional product/job/shift/crew names
-> fan_out resolve_thing
-> normalize_resolved_things
-> fan_out get_property_values(items = normalize.output.rows)
-> extract_from_tool_output (equipment UID rows)
-> call quick interval service
-> resolve_time_window_for_playbook
-> optional lookup branches for product/job/shift/crew
-> normalize filter-entry rows
-> build_nested_object(Filters[], QuickTimeIntervalUID or StartTime/EndTime)
-> json_stringify
-> invoke_service CallGetKPIs
-> final llm_summary
```

Not every app needs the full DAG; a wrapper service may still be the pragmatic choice.
The engine makes this DAG possible when the app team wants visible deterministic
orchestration.

## 8. Validation and budgets

Every orchestration op has validator coverage.

Validation rules:

- reject unknown ops;
- reject missing required args;
- reject unbounded extraction / payload assembly;
- reject unsupported path/filter syntax;
- reject serialization without `maxBytes` or with a value above the configured cap;
- reject nested payload builders without explicit source caps;
- reject INFOTABLE binding without a source row ref and target parameter context;
- preserve existing graph rules: acyclic DAG, no orphan nodes, one final
  `llm_summary`, `fan_out.maxConcurrency: 1`.

Budget rules:

- all row/object-building ops need `maxRows`, `maxItems`, or equivalent caps;
- nested payload assembly needs a max parent count and max child count;
- serialization needs `maxBytes`;
- fan-out caps remain the primary defense against dynamic explosion;
- overflow should produce deterministic gaps where possible, not silent truncation.

## 9. Evidence expectations

These ops do not dump raw payloads into evidence by default.

Evidence:

| Capability | Evidence |
| --- | --- |
| `normalize_resolved_things` | resolved count, unresolved count, ambiguous count, sample names. |
| envelope extraction | source path, extracted count, missing count, sample field names. |
| INFOTABLE binding | row count, column names, target parameter name, dataShapeName when present. |
| nested payload assembly | parent count, child-entry count, top-level field names. |
| `json_stringify` | byte count, source node, omitted raw value. |
| time-window derivation | mode (`quickInterval` / `explicitRange`), label, defaulted flag, timezone. |
| optional branch skip | branch name, skipped/applied, row count. |

Final `llm_summary` receives enough to explain what was resolved, what filters
were applied, and what gaps occurred, without receiving huge raw JSON strings.

## 10. Tests

Unit / fixture tests cover:

1. `normalize_resolved_things`:
   - all resolved;
   - one unresolved with `onUnresolved=clarify`;
   - one unresolved with `onUnresolved=gap`;
   - one ambiguous result;
   - cap overflow.
2. envelope extraction:
   - `get_property_values.properties[]` -> `{ UID }` rows;
   - fan-out child extraction;
   - missing property records a gap;
   - nested row extraction from a service result fixture.
3. path/scalar extraction:
   - first equality match;
   - no match;
   - multiple matches where single required;
   - unsupported path syntax rejected.
4. INFOTABLE binding:
   - derived UID rows bind to a single-column DataShape parameter;
   - missing required column fails before service invocation;
   - raw `$table` legacy behavior still works.
5. nested payload assembly:
   - one equipment no filters;
   - multiple equipment shared filters;
   - optional branch skipped;
   - cap overflow.
6. `json_stringify`:
   - object serializes deterministically;
   - maxBytes overflow;
   - evidence excludes raw payload by default.
7. time-window derivation:
   - quick interval match;
   - Today default;
   - explicit range with timezone boundary;
   - unsupported phrase.

End-to-end reference tests:

- Alarm-events playbook fixture using resolver -> UID -> service parameter binding.
- KPI values playbook fixture through nested payload + stringify. The service call is
  stubbed; the assertion is that the generated parameter payload is correct.
