# Appendix: Built-in tools

This appendix describes the **model-facing Parler built-in tools** after the
`legacy-discovery-executor-only` cleanup.

The important change is that Parler now separates two ideas:

- **Model-facing tools**: tool definitions sent to the LLM in the merged tool list.
- **Executor-only tools**: tool names still accepted by the runtime for replay, continuation, compatibility, or
  orchestrated flows, but not advertised to the model by default.

For application authors and workshop users, the practical rule is:

> Use the modern, bounded tools for new prompts. Legacy discovery names still execute when needed, but the model should
> not depend on seeing them in the default tool list.

## Current model-facing surface

The exact count is configuration-dependent. In a workshop environment with a loaded skill catalog, you may see about
**22 model-facing built-in tools** (one fewer than the pre-0.1.205 PoP/multi-series pair). In a cleaner environment with no skills or playbooks loaded, the count can be one or
two lower. If extended tools are configured and not executor-only, the merged model-facing tool list grows.

- **`get_entity`**, **`discover_properties`**, **`discover_services`**, and **`get_service_definition`** are
  executor-only compatibility surfaces.
- **`query_numeric_property_history`** and **`query_value_stream_property_history`** are executor aliases for
  **`query_property_history`**.
- **`get_agent_skill`** appears only when a skill catalog is loaded.
- **`start_playbook`** is optional and appears only when a playbook catalog is loaded.
- Extended tools from `/tools/extended_tools.json` appear only when configured and not marked **`executorOnly:true`**.

Do not treat model answers that mention **`parallel`** or **`multi_tool_use.parallel`** as Parler tool inventory. That is
an internal model/orchestration capability, not a Parler built-in tool.

Compatibility mode:

- If **`AgentThing.advertiseLegacyServiceDiscoveryTools=true`**, **`discover_services`** and
  **`get_service_definition`** are advertised again.
- **`discover_properties`** remains executor-only in both modes.

Normative implementation source: `parler-agent` `BuiltInTools.registerAll`, `ToolRegistry`, `AgentThing`, and
`GetAgentRuntimeSnapshot`.

Normative contracts and deep docs live in this repository. Workshop readers do not need to open them to use the
catalog below; treat the list as maintainer pointers for auditing tool behavior:

- `CONTRACTS/API_CONTRACT.md`
- `CONTRACTS/TAXONOMY_RESOLVER.md`
- `CONTRACTS/TABULAR_INSIGHT.md`
- `CONTRACTS/CHART_CONTRACT.md`
- `CONTRACTS/ENTITY_SET_TOOL.md`
- `docs/agent/AGENT-CONTEXT.md`
- `docs/agent/legacy-discovery-executor-only.md`
- `docs/agent/thing-member-discovery.md`
- `docs/agent/entity-schema-description.md`
- `docs/agent/query-spec.md`
- `docs/agent/time-interpretation.md`
- `docs/agent/history-overlay-chart.md`

## Default model-facing built-ins

| Group | Tools |
|-------|-------|
| Generic execution | `invoke_service` |
| Cached tables and charts | `fetch_cached_result`, `tabulate_cached_result`, `summarize_cached_result`, `build_chart_from_tabular_result`, `analyze_entity_set` |
| Entity/schema/member discovery | `describe_entity_schema`, `discover_thing_members`, `list_entities_by_type`, `spotlight_search` |
| Thing and taxonomy listing/resolution | `query_entities`, `query_entities_by_taxonomy`, `list_asset_types`, `resolve_asset_type`, `resolve_thing` |
| Properties, history, and overlay charts | `get_property_values`, `query_property_history`, `query_stream_data`, `build_history_overlay_chart` |
| Alerts and writes | `query_alert_summary`, `query_alert_history`, `acknowledge_alerts`, `set_property_value` |
| Agent configuration | `get_agent_skill` when a skill catalog is loaded |

---

## 1. Generic execution

### `invoke_service`

**Purpose:** Invoke a ThingWorx service on a named entity via the in-JVM SDK.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `entityType` | yes | Root entity type, usually `Thing` for concrete Things. |
| `entityName` | yes | Target entity name. |
| `serviceName` | yes | Service name. |
| `parameters` | no | All service inputs must be nested here. |

**Use it when:** A service is already known, a prior tool result gives an invocation path, or a configured workflow
requires a generic service call.

**Avoid it when:** You are listing Things, reading property values, discovering schema/member metadata, or trying to
dump full platform metadata. Use the narrower tools below.

---

## 2. Cached tables, set algebra, and charts

On the 0.1.224 course baseline these handles live in the Agent's mandatory, dedicated Artifact
Cache FileRepository. They are conversation-scoped working evidence, not application records:
always use the `cacheId` returned by a current tool result, preserve completeness/count metadata,
and handle `CACHE_MISS` rather than assuming a handle survives restart. This Artifact Cache is
separate from Provider prompt caching and from ConversationCheckpoint continuity.

### `fetch_cached_result`

**Purpose:** Page rows from a conversation-scoped tabular cache.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `cacheId` | yes | From a prior LARGE or transformed tabular result. |
| `offset` | no | Default 0. |
| `limit` | no | Bounded page size. |

**Use it when:** The user needs to inspect rows from a large cached result.

**Avoid it when:** You need full-table counts, grouping, filtering, ranking, or statistics. Use `tabulate_cached_result`
or `summarize_cached_result`.

### `tabulate_cached_result`

**Purpose:** Deterministic transforms over the entire cached table.

Typical modes include:

- `sort_topn`
- `group_count`
- `group_aggregate`
- `filter_count`
- `filter_rows`
- `filter_sort_topn`
- `group_metric`

**Use it when:** The user asks for counts, groups, filtered subsets, top-N, or exact table-derived evidence.

**Avoid it when:** You only need a row page. Use `fetch_cached_result`.

### `summarize_cached_result`

**Purpose:** Column-level statistics over a cached table.

**Use it when:** The user asks for distribution, null counts, numeric summary, categorical cardinality, or percentiles.

**Avoid it when:** You need row-level output or grouped business counts. Use `tabulate_cached_result`.

### `analyze_entity_set`

**Purpose:** Deterministic set algebra over two cached entity/list tables.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `operation` | yes | `difference`, `intersection`, `union`, or `symmetric_difference`. |
| `left.cacheId` | yes | Explicit cache id. No last-cache sentinel. |
| `right.cacheId` | yes | Explicit cache id. No last-cache sentinel. |
| `left.keyColumn`, `right.keyColumn` | no | Defaults to `name`. |
| `projectColumns` | no | Output columns, subject to safe scalar and PASSWORD rules. |
| `maxItems`, `offset` | no | Bound returned sample rows. |

**Use it when:** The user asks for "A minus B", overlap, union, or "Things missing from classification" after two
source tools have created caches.

**Avoid it when:** You only have one table. Use `tabulate_cached_result` or `summarize_cached_result`.

**Chart path:** First call `tabulate_cached_result` on the returned `cacheId`, then call
`build_chart_from_tabular_result`.

### `build_chart_from_tabular_result`

**Purpose:** Build a chart from a qualifying tabular result or cache.

See [Supported charts](./23-supported-charts-and-tradeoffs.md) for the current seven kinds, distribution preparation, history overlays, chart groups and encoding limits.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `source` | yes | `last_invoke` or `cache_id`. |
| `cacheId` | conditional | Required when `source=cache_id`. |
| `xColumn` | yes | X axis. |
| `kind` or `intent` | one required | `kind` is explicit; `intent` lets the server choose within supported chart types. |
| `yColumn`, `series`, `seriesColumn`, `title`, labels, reference lines | no | Chart-dependent. |

**Use it when:** A real tabular result exists and the user wants a visualization.

**Avoid it when:** You are trying to chart raw prose or a non-tabular answer.

---

## 3. Schema and concrete Thing member discovery

### `describe_entity_schema`

**Purpose:** Bounded schema-entity description for `ThingTemplate`, `ThingShape`, and `DataShape`.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `entityType` | yes | `ThingTemplate`, `ThingShape`, or `DataShape`. |
| `entityName` | yes | Schema entity name. |
| `facet` | yes | Examples: `summary`, `properties`, `services`, `events`, `fields`, singular supported facets. |
| `memberName`, `namePrefix`, `offset`, `maxItems`, `scope` | no | Facet-dependent. |

**Use it when:** You need to understand a model/schema definition.

**Avoid it when:** The target is a concrete Thing instance. Use `discover_thing_members`.

### `discover_thing_members`

**Purpose:** Discover effective members on one concrete Thing.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Canonical Thing name. Use `resolve_thing` first if needed. |
| `facet` | yes | `properties`, `property`, `services`, `service`, `events`, `event`, or `subscriptions`. |
| `memberName` | conditional | Required for singular facets. |
| `namePrefix`, `dataShape`, `offset`, `maxItems` | no | Facet-dependent filters/paging. |

**Use it when:** You need exact property, service, event, or subscription metadata for a Thing.

**Avoid it when:** You need schema information for a template, shape, or data shape. Use `describe_entity_schema`.

### `list_entities_by_type`

**Purpose:** List metadata entities by collection type using ThingWorx `EntityServices`.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `entityCollectionType` | yes | Examples: `ThingTemplate`, `ThingShape`, `Mashup`, `DataShape`, `User`. |
| `nameMask`, `useRegEx`, `tags`, `maxItems` | no | Optional filters. |

**Use it when:** The user asks for metadata catalogs such as templates, shapes, mashups, or users.

**Avoid it when:** The user asks for Thing instances under a template or shape. Use `query_entities`.

### `spotlight_search`

**Purpose:** Fuzzy search across ThingWorx metadata.

**Use it when:** The exact entity name or entity type is unclear.

**Avoid it when:** A precise catalog/listing tool is available.

---

## 4. Things, taxonomy, and identity resolution

### `query_entities`

**Purpose:** List Things implementing one ThingTemplate or ThingShape.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingTemplate` or `thingShape` | one required | Exactly one parent dimension. |
| `entityType` | no/legacy | Usually `Thing`; the tool is Thing-instance listing. |
| `maxItems`, `offset`, `namePrefix`, `query`, `modelTags`, `withPermissions` | no | Platform QIT options. |
| `hierarchyNodeId`, `hierarchyNodeName`, `intersectThingNames`, `intersectExpandHasMore` | no | Hierarchy/intersect support. Use `hierarchyNodeId` for page-provided system node ids; use `hierarchyNodeName` for user-entered labels. |

**Use it when:** You know the ThingTemplate or ThingShape parent and need the Things under it.

**Avoid it when:** The user names a business asset type in natural language. Use `resolve_asset_type` then
`query_entities_by_taxonomy`.

### `query_entities_by_taxonomy`

**Purpose:** Query application asset sets using configured taxonomy rows, projected business columns, and optional exact
lookup properties.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `EntityType` | yes | Usually copied from `resolve_asset_type`. |
| `EntityName` | yes | Usually copied from `resolve_asset_type`. |
| `CriticalProperties`, `AdditionalProperties`, `LookupProperties` | no | Taxonomy-driven projections and exact lookup filters. |
| `hierarchyNodeId`, `hierarchyNodeName`, `intersectThingNames`, `intersectExpandHasMore` | no | Same hierarchy/intersect family as `query_entities`. Use `hierarchyNodeId` for Host Context selected node ids. |

**Use it when:** The user-facing concept is an application asset class such as a configured equipment type.

**Avoid it when:** You need only raw ThingTemplate/ThingShape implementors and already know the parent. Use
`query_entities`.

### `list_asset_types`

**Purpose:** List configured application asset types.

**Inputs:** `{}`.

**Use it when:** The user asks what asset classes exist, or the model needs a menu of configured business categories.

### `resolve_asset_type`

**Purpose:** Resolve user asset-type text to a configured taxonomy asset type and its ThingWorx parent.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `text` | yes | User phrase such as "Jet Dryer". |

**Use it when:** A user names a class/category of assets.

### `resolve_thing`

**Purpose:** Resolve user text to canonical Thing names using configured identity rules.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `text` | yes | Display name, serial, suffix, alias, or canonical name. |
| `assetTypeKey` | no | Optional taxonomy narrowing. |

**Use it when:** A downstream tool needs a canonical `thingName`.

---

## 5. Properties, history, and stream rows

### `get_property_values`

**Purpose:** Batch-read current property values on one Thing.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Canonical Thing name. |
| `propertyNames` | yes | Exact property names, bounded list. |

**Use it when:** The user asks for current values or current state.

**Avoid it when:** The property names are unknown. Use `discover_thing_members(facet="properties")` first.

### `query_property_history`

**Purpose:** Unified history query for one logged property on one Thing.

The runtime routes by property base type:

- numeric properties use the numeric history lane and may support numeric aggregate actions and chart helpers;
- non-numeric logged properties use the compact value-stream history lane.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Canonical Thing name. |
| `propertyName` | yes | Exact logged property name. |
| `startTime` / `endTime`, `calendarPhrase`, `relativeDuration` | no | Mutually constrained time-window family. |
| `maxRows` | no | Bounded. |
| `actions` | no | Numeric-only aggregate actions. |
| chart helper fields | no | Numeric lane may emit chart-compatible output. |

**Use it when:** The user asks for trend, history, or values over time for a Thing property.

**Avoid it when:** The user wants a Stream Thing table. Use `query_stream_data`.

**Compatibility:** `query_numeric_property_history` and `query_value_stream_property_history` remain executor aliases,
not default model-facing tools.

**Overlay charts:** For **2–6** live history traces on **one chart** (same or different Things, same or shifted windows),
use **`build_history_overlay_chart`** instead of repeated **`query_property_history`** calls. Retired model-facing tools
**`build_period_over_period_chart`** and **`build_multi_series_history_chart`** (pre-**0.1.205**) are replaced by this
single tool with **`xAxisMode`**: **`absolute_time`** (same window, cross-Thing), **`elapsed_time`** (shifted windows /
mixed overlays), or **`normalized_time`** (different-duration shape comparison; explicit model choice). Requires
**`parler-agent` 0.1.205+** and widget **0.1.89+** for normalized-axis rendering. Design:
**`docs/agent/history-overlay-chart.md`**; wire rules: **`CONTRACTS/CHART_CONTRACT.md`** §3.0e.

### `build_history_overlay_chart`

**Purpose:** Overlay **2–6** numeric property history series on one server-authored chart.

**Inputs (summary)**

| Argument | Required | Notes |
|----------|----------|-------|
| `propertyName` | yes | Same numeric property for every series. |
| `series` | yes | **2–6** entries; each has `label`, `thingName`, and a resolvable time window (`startTime`/`endTime`, `relativeDuration`, `anchorOffset`, or `calendarPhrase`). |
| `xAxisMode` | no | `absolute_time`, `elapsed_time`, or `normalized_time`. When omitted, server picks absolute when all windows match, else elapsed — not normalized. |
| `chart_kind` | no | `line` (default) or `scatter`. |
| `yReferenceLines` | no | Global upper/lower limits, targets, etc. |
| `title`, `yLabel`, `anchorTime` | no | Display labels and shared anchor for relative windows. |

**Use it when:** The user wants one chart comparing trends across Things and/or shifted windows (for example current
draw this hour vs last week on two robots).

**Avoid it when:** Only one Thing/property trend is needed — use **`query_property_history`**. When data is already in a
long cached table, prefer **`build_chart_from_tabular_result`** + **`seriesColumn`**.

### `query_stream_data`

**Purpose:** Query rows from a ThingWorx Stream Thing.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Stream Thing name. |
| time-window fields | no | Same natural-time family as property history. |
| `maxItems`, `oldestFirst`, `source` | no | Stream query controls. |

**Use it when:** The user asks for rows in a Stream entity, log-like stream data, or tabular stream records.

---

## 6. Alerts and writes

### `query_alert_summary`

**Purpose:** Current alert summary rows for one or more Things.

**Version note:** Starting with `parler-agent` **0.1.202**, this tool uses
`thingNames[]`. Even a single-Thing summary call must pass a one-element array,
for example `{"thingNames":["SE.CellFab.Model.Workunit.ORD-Contacting-01"]}`.
Older examples that pass scalar `thingName` to `query_alert_summary` must be
updated. `query_alert_history` and `acknowledge_alerts` still use scalar
`thingName`.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingNames` | yes | Array of canonical Thing names, 1-25 items. |
| `ackState` | no | `all`, `acknowledged`, or `unacknowledged`. |
| alert/property/priority filters | no | Optional narrowing. |
| `limit`, `sort`, `advancedQuery` | no | Bounded output and advanced filters. |

**Use it when:** The user asks what is currently firing on one Thing, or wants a
current alert comparison across several Things.

**Avoid it when:** The user asks when alerts happened. Use `query_alert_history`.

### `query_alert_history`

**Purpose:** Time-bounded alert history for one Thing.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Canonical Thing name. |
| time-window fields | no | Explicit bounds, calendar phrase, relative duration, or preset. |
| alert/property filters, order, limit, advancedQuery | no | Optional narrowing. |

**Use it when:** The user asks for historical alert timeline, post-mortems, or flapping.

### `acknowledge_alerts`

**Purpose:** Acknowledge alerts for one Thing.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thingName` | yes | Canonical Thing name. |
| `mode`, `propertyName`, `alertName`, `message` | varies | Side-effecting alert acknowledgement. |

**Use it when:** The user explicitly asks to acknowledge alerts.

**Avoid it when:** The task is read-only analysis.

### `set_property_value`

**Purpose:** Request a write to one Thing property.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `thing_name` | yes | Snake-case key. |
| `property_name` | yes | Snake-case key. |
| `value` | yes | Literal value. |
| `base_type` | no | Strongly recommended when known. |

**Use it when:** The user explicitly asks to change a value and the HITL/approval flow applies.

**Avoid it when:** The user only asks for observation or analysis.

---

## 7. Agent configuration

### `get_agent_skill`

**Purpose:** Load the full body of a registered skill from the AgentThing configuration repository.

**Inputs**

| Argument | Required | Notes |
|----------|----------|-------|
| `skill_name` | yes | Registered skill short id. |

**Use it when:** The user asks for a workflow that a loaded skill advertises, and the skill body is needed.

**Avoid it when:** No skill catalog is loaded or the prompt does not require skill-specific procedure.

### Optional `start_playbook`

**Purpose:** Start a configured playbook.

This tool is not always present. It is exposed only when the playbook catalog loads.

---

## 8. Executor-only compatibility surfaces

The following names are still callable by the runtime but are not part of the default model-facing built-in list.

| Name | Default status | Modern path |
|------|----------------|-------------|
| `get_entity` | executor-only | `describe_entity_schema` for schema facets; `discover_thing_members` for concrete Things. |
| `discover_properties` | executor-only | `discover_thing_members(facet="properties")`. |
| `discover_services` | executor-only by default; advertised only when `advertiseLegacyServiceDiscoveryTools=true` | `discover_thing_members(facet="services")` for Things; `describe_entity_schema(facet="services")` for schema entities; app-specific skills or extended tools for non-Thing services. |
| `get_service_definition` | executor-only by default; advertised only when `advertiseLegacyServiceDiscoveryTools=true` | `discover_thing_members(facet="service", memberName=...)` for Thing services; `describe_entity_schema` singular service facets where supported; app-specific extended tools for business-safe service invocation. |
| `query_numeric_property_history` | executor alias | `query_property_history`. |
| `query_value_stream_property_history` | executor alias | `query_property_history`. |

This is compatibility, not a recommended greenfield route. If a replayed conversation contains one of these names, the
runtime can still execute it. If a new prompt needs the same capability, prefer the modern path.

---

## 9. Extended tools and `executorOnly`

Application-defined extended tools live in the AgentThing configuration repository under `/tools/extended_tools.json`.

Each extended tool may include:

```json
{
  "name": "internal_region_health_snapshot",
  "title": "Internal region health snapshot",
  "whenToUse": "Reserved for replay or orchestrated flows; not advertised to the model.",
  "target": {
    "entityName": "me",
    "serviceName": "RegionHealthSnapshot"
  },
  "hitl": false,
  "executorOnly": true
}
```

Rules:

- Missing `executorOnly` means `false`.
- `executorOnly:true` registers the tool for execution but omits it from the merged LLM tool list.
- It does not bypass HITL, PASSWORD protection, target validation, parameter coercion, or policy checks.
- `GetAgentRuntimeSnapshot` should show whether each extended tool is model-facing or executor-only.

Use executor-only extended tools for replay, orchestration, migration, or internal flows that should not widen the
LLM-facing surface.

---

## 10. Choosing the right metadata path

| User intent | Preferred path |
|-------------|----------------|
| "What properties/services/events/subscriptions does this Thing expose?" | `discover_thing_members` |
| "What fields/properties/services are defined by this ThingTemplate/ThingShape/DataShape?" | `describe_entity_schema` |
| "What is the current value of these properties?" | `get_property_values` |
| "What is the trend/history of this property?" | `query_property_history` |
| "Overlay/compare 2–6 history traces on one chart" | `build_history_overlay_chart` |
| "Which Things implement this template or shape?" | `query_entities` |
| "Which assets of this configured business type exist?" | `resolve_asset_type` then `query_entities_by_taxonomy` |
| "Which Things are in A but not B?" | source queries, then `analyze_entity_set(operation="difference")` |
| "How many rows/groups/top items in this large result?" | `tabulate_cached_result` or `summarize_cached_result` |
| "Can I call this business operation safely?" | Prefer a skill or extended tool; use `invoke_service` only when the target service is already known. |

The product direction is deliberate: fewer overlapping tool names, more exact bounded operations, and compatibility kept
behind executor-only dispatch.
