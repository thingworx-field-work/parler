# All model-facing tools and context cost

This note is the working reference for context-budget and tool-admission decisions. It combines a **current built-in inventory** (measured **2026-09-28** @ extension **0.1.250**) with **historical incident evidence** from **2026-06-28** (different tool populations — see counting rules below).

**History overlay (2026-07):** model-facing **`build_period_over_period_chart`** and **`build_multi_series_history_chart`** are retired. Use **`build_history_overlay_chart`** for same-window cross-Thing comparison (`absolute_time`) and shifted-window overlays (`elapsed_time`). Design: [`history-overlay-chart.md`](./history-overlay-chart.md).

## Counting populations (read before comparing numbers)

Different figures in this file measure **different surfaces**. Do not compare them without the population label.

| Population | Count (baseline) | What it includes | How measured |
| --- | ---: | --- | --- |
| **(a) Built-in registry `registerAll`** | **28** | All first-party tools registered by **`BuiltInTools.registerAll`**, including **`get_agent_skill`** — **excludes** executor-only tools (listed after the rank table). The rank table and the built-in payload sections below measure this inventory. | Per-tool wire objects via **`ToolSchemaSizer`** + **`BuiltInToolMergedDefinitionFootprintTest`** (`anthropic-messages-v1`) |
| **(b) Conditional / meta merge additions** | varies | **`start_playbook`** (when playbook catalog loaded), document-knowledge tools when host context enables them, **`load_tool_schemas`** in `lazy` admission mode — added at runtime merge, **not** counted in the (a) rank table. **`get_agent_skill`** is **in (a)** but may be **omitted** from the merged model-facing list when no skills are loaded (`ModelFacingSkillAdmission.hasModelFacingSkills(...)` in **`AgentThing.getMergedToolDefinitions()`**). | Runtime merge + admission policy |
| **(c) Repository extended tools** | **manifest-dependent** | Entries from deployment **`/tools/extended_tools.json`** (sample: **4** post-LLM-friendly utilization tools in **`dev_data/scpa_utilization/tools/extended_tools.json`**) | Reconstructed from manifest + target service schemas at deploy time |
| **(d) Historical incident full surface** | **34** @ **`toolSchemaChars=68049`** | **2026-06-28** live run: (a)-like built-ins **plus** seven **pre-LLM-friendly** utilization extended tools **plus** **`start_playbook`** when playbooks loaded — **not** comparable to (a) alone | Live **`LLM_CONTEXT_PLAN_FAIL`** log (below) |

Training note — **utilization extended tools:** early workshop stages taught a **seven-tool** `utilization_*` surface (**pre-LLM-friendly**); the **post-LLM-friendly** / Day 4 training manifest consolidates to **four** tools (`list_utilization_machines`, `get_utilization_records`, `get_utilization_state_summary`, `get_utilization_overview`). See **`training-stage-configuration-contracts.md`** and §Repository Extended Tool Payloads below. The **2026-06-28** incident row (d) used the **seven-tool** generation.

## Historical incident (2026-06-28) — population **(d)**

The immediate incident was a live request on `SCPA_Agent_Sonnet` where the model call failed before history could be admitted:

```text
LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP
messages=28 tools=34 stableChars=31638 toolSchemaChars=68049 ephemeralChars=5242
currentUserChars=59 activeBatchReserveChars=13857 transcriptChars=0
evidenceRawChars=8372 evidenceChars=8372
configuredCapChars=750000 effectiveRequestCapChars=118622
historyBudgetChars=-223 historyClampedToZero=1
```

The important signal is not the raw configured cap. The provider/runtime effective request cap was `118622` chars, and non-history overhead alone exceeded that limit:

- stable prompt/context: `31638`
- all tool schemas: `68049`
- ephemeral context: `5242`
- active batch reserve: `13857`
- current user prompt: `59`
- evidence: `8372`

That leaves a negative history budget before conversation history is considered.

## Measurement Semantics

`toolSchemaChars` is not token count. It is the Java-side compact JSON character length used by the context planner for Anthropic-compatible tool wire objects:

```json
{
  "name": "...",
  "description": "...",
  "input_schema": {}
}
```

The full logged `toolSchemaChars=68049` includes:

- every model-facing tool object,
- JSON array punctuation,
- the provider cache marker on the last tool,
- small runtime serialization details.

The per-tool sizes in population **(d)** below are measured as individual compact tool objects. The sum of individual rows was `67938` chars; the logged full request total was `68049` chars.

## Current built-in default surface — population **(a)**

Measured **2026-09-28** @ extension **0.1.250** with **`ToolSchemaSizer`** (`anthropic-messages-v1`) over population **(a)** — all **`BuiltInTools.registerAll(registry, false, false)`** definitions (**28** tools, including **`get_agent_skill`** and the conditionally advertised **`analyze_cached_result`**). Array framing + Anthropic cache marker adds **`71443 − 71377 = 66`** chars vs the per-tool sum. Enum-bearing schemas can differ slightly on a platform with a different enum set.

| Rank | Tool | Size chars | Source |
|---:|---|---:|---|
| 1 | `tabulate_cached_result` | 13,268 | exact |
| 2 | `build_chart_from_tabular_result` | 6,702 | exact |
| 3 | `query_entities` | 5,157 | exact |
| 4 | `query_property_history` | 4,173 | exact |
| 5 | `invoke_service` | 3,850 | exact |
| 6 | `query_entities_by_taxonomy` | 3,435 | exact |
| 7 | `build_history_overlay_chart` | 3,303 | exact |
| 8 | `describe_entity_schema` | 2,868 | exact |
| 9 | `list_entities_by_type` | 2,544 | exact |
| 10 | `query_alert_history` | 2,532 | exact |
| 11 | `query_alert_summary` | 2,428 | exact |
| 12 | `discover_thing_members` | 2,378 | exact |
| 13 | `analyze_entity_set` | 2,320 | exact |
| 14 | `analyze_cached_result` | 2,297 | exact |
| 15 | `query_stream_data` | 1,962 | exact |
| 16 | `acknowledge_alerts` | 1,723 | exact |
| 17 | `fetch_cached_result` | 1,320 | exact |
| 18 | `declare_chart_group` | 1,303 | exact |
| 19 | `get_property_values` | 1,166 | exact |
| 20 | `list_asset_types` | 991 | exact |
| 21 | `summarize_cached_result` | 956 | exact |
| 22 | `get_agent_skill` | 866 | exact |
| 23 | `set_property_value` | 848 | exact |
| 24 | `inspect_cached_payload` | 763 | exact |
| 25 | `resolve_thing` | 678 | exact |
| 26 | `extract_nested` | 568 | exact |
| 27 | `spotlight_search` | 542 | exact |
| 28 | `resolve_asset_type` | 436 | exact |

Summary (population **(a)** only):

- tool count: **28**
- per-tool object sum: **71,377** chars
- serialized tools array total (with framing): **71,443** chars
- top 10 tools total: **47,832** chars (~67% of per-tool sum)
- top 5 tools total: **33,150** chars (~46% of per-tool sum)

Executor-only tools are **not** in population **(a)** but remain callable via **`executeTool`** / replay:

- `discover_properties`, `discover_services`, `get_entity`, `get_service_definition`
- `load_tool_schemas` (advertised only by `lazy` admission; see population **(b)**)
- `exact_join_cached_result`, `quality_cached_result`, `resample_cached_result`, `rolling_cached_result`, `rate_of_change_cached_result`, `period_compare_cached_result` (aliases of the `tabulate_cached_result` modes)

## Historical runtime surface — population **(d)** @ 2026-06-28

This live run exposed **34** model-facing tools:

- 26 always/conditionally available built-ins from `AgentThing` runtime configuration (pre-overlay merge; **no** `build_history_overlay_chart`),
- `start_playbook`, because playbooks were loaded,
- **7** repository extended tools from `/tools/extended_tools.json` (**pre-LLM-friendly** utilization generation).

### Per-tool context cost (2026-06-28 incident measurement)

`exact` means the size was computed directly from Java `ToolDefinition` serialization. `reconstructed` means the tool came from repository extended-tool configuration and was reconstructed from the live target service definitions, including service parameter schemas and INFOTABLE DataShape expansion.

| Rank | Tool | Size chars | Source |
|---:|---|---:|---|
| 1 | `query_entities` | 6,549 | exact |
| 2 | `tabulate_cached_result` | 6,241 | exact |
| 3 | `query_entities_by_taxonomy` | 4,594 | exact |
| 4 | `build_chart_from_tabular_result` | 4,232 | exact |
| 5 | `query_property_history` | 4,152 | exact |
| 6 | `invoke_service` | 3,667 | exact |
| 7 | `query_alert_history` | 2,901 | exact |
| 8 | `describe_entity_schema` | 2,868 | exact |
| 9 | `discover_thing_members` | 2,515 | exact |
| 10 | `analyze_entity_set` | 2,251 | exact |
| 11 | `list_entities_by_type` | 2,137 | exact |
| 12 | `utilization_machine_listing_with_dates` | 2,087 | reconstructed |
| 13 | `query_alert_summary` | 2,156 | exact |
| 14 | `acknowledge_alerts` | 1,723 | exact |
| 15 | `utilization_records_by_machine` | 1,537 | reconstructed |
| 16 | `query_stream_data` | 1,522 | exact |
| 17 | `search_document_chunks` | 1,516 | exact |
| 18 | `utilization_aggregate_by_state_time_fence` | 1,438 | reconstructed |
| 19 | `utilization_records` | 1,386 | reconstructed |
| 20 | `utilization_aggregate_by_state` | 1,170 | reconstructed |
| 21 | `get_property_values` | 1,126 | exact |
| 22 | `start_playbook` | registry-driven | varies with loaded playbooks |
| 23 | `utilization_machine_listing` | 1,082 | reconstructed |
| 24 | `utilization_stats_for_aggregate` | 1,037 | reconstructed |
| 25 | `fetch_cached_result` | 1,034 | exact |
| 26 | `summarize_cached_result` | 956 | exact |
| 27 | `set_property_value` | 921 | exact |
| 28 | `get_agent_skill` | 866 | exact |
| 29 | `resolve_document_set` | 759 | exact |
| 30 | `resolve_thing` | 678 | exact |
| 31 | `spotlight_search` | 610 | exact |
| 32 | `get_document_chunk` | 527 | exact |
| 33 | `resolve_asset_type` | 436 | exact |
| 34 | `list_asset_types` | 433 | exact |

Summary (population **(d)**):

- tool count: `34`
- individual tool object total: `67,938` chars
- logged full `toolSchemaChars`: `68,049` chars
- top 10 tools total: `39,970` chars, about 59% of all tool schema cost
- top 5 tools total: `25,768` chars, about 38% of all tool schema cost

## What This Means

The current context problem is not caused by conversation history alone. A large request can fail even with `transcriptChars=0` when these are all true:

- many model-facing tools are advertised at once,
- stable prompt/context is already large,
- evidence or host context is present,
- active batch reserve is held,
- the provider's effective request cap is much lower than the nominal configured cap.

This is why "just compact history" cannot solve every context failure. Tool schema is fixed overhead for a round; compaction can only reclaim transcript and retained evidence.

## High-Cost Tool Classes

The largest fixed costs come from four classes.

### Broad query and table tools

These tools are expensive because their schemas describe flexible predicates, table/cache contracts, or chart-ready data contracts:

- `query_entities`
- `tabulate_cached_result`
- `query_entities_by_taxonomy`
- `build_chart_from_tabular_result`
- `build_history_overlay_chart`
- `query_property_history`

They are valuable, but they should not all be assumed necessary for every turn.

### Generic metadata and invocation tools

These tools carry broader safety and input-shape guidance:

- `invoke_service`
- `describe_entity_schema`
- `discover_thing_members`

They are useful for exploratory turns, but they are rarely all needed once the route is clear.

### Repository extended tools

Utilization manifests differ by **training stage** (see counting table above):

| Stage | Tool count | Sample manifest | Notes |
| --- | ---: | --- | --- |
| **Pre-LLM-friendly** (early workshop) | **7** | legacy `utilization_*` names in §Historical utilization payloads | Population **(d)** / 2026-06-28 incident; ~**9.7K** chars combined |
| **Post-LLM-friendly** (Day 4 / current sample) | **4** | `dev_data/scpa_utilization/tools/extended_tools.json` | Population **(c)** for the shipped training bundle |

Extended-tool sizes come from manifest `whenToUse` text, target service parameter definitions, INFOTABLE DataShape expansion, and synthetic natural-time fields. They are acceptable on utilization turns but wasteful when unrelated tools are co-advertised (population **(d)** lesson).

### Document tools

The document tools are individually small, but they should still be conditional:

- `resolve_document_set`
- `search_document_chunks`
- `get_document_chunk`

They are not useful for normal ThingWorx operational questions unless document knowledge is in play.

## Tool admission

The runtime answer to this incident is turn-level tool admission rather than removing tools. Normative design: [`../operations/tool-schema-admission-control.md`](../operations/tool-schema-admission-control.md).

- **Per-round telemetry.** Every LLM completion round logs one `LLM_TOOL_SCHEMA_USAGE` line with `toolSchemaChars` (the budget planner's charge for the tools array), `toolSchemaSizes` (per-tool sizes, largest first), `toolSchemaFramingChars`, and the schema / called / idle tool lists.
- **`AgentSettings.toolAdmissionMode`:** `off` (default; all tools advertised), `narrow` (drop irrelevant buckets up front by deterministic host-context / intent signals and keep the core set), or `lazy` (advertise the core set plus the `load_tool_schemas` meta-tool; see the addendum at the end of this file). Admission signals are deterministic; there is no LLM router.
- **Executor-only tools** (`discover_*`, `get_entity`, `get_service_definition`, the `*_cached_result` mode aliases) stay callable by the executor and replay but are never model-facing.
- Admission does not read the provider cap at runtime and does not degrade the tool list by budget.

The narrowing unit is the tool bucket:

| Bucket | Typical tools |
|---|---|
| Identity / routing | `resolve_thing`, `resolve_asset_type`, `list_asset_types`, `list_entities_by_type` |
| Entity set query | `query_entities`, `query_entities_by_taxonomy`, `analyze_entity_set` |
| Current values / trends | `get_property_values`, `query_property_history`, `query_stream_data`, chart/table tools |
| Alerts | `query_alert_summary`, `query_alert_history`, `acknowledge_alerts` |
| Metadata exploration | `describe_entity_schema`, `discover_thing_members`, `invoke_service` |
| Documents | `resolve_document_set`, `search_document_chunks`, `get_document_chunk` |
| Skills / playbooks | `get_agent_skill`, `start_playbook` |
| Utilization extension (manifest-dependent) | **4** current (`get_utilization_*`, `list_utilization_machines`) or **7** historical (`utilization_*`) — see §Repository Extended Tool Payloads |

## Immediate Reading of This Incident

For the captured failure, the main fixed-cost contributors were:

- broad query/table/chart tools: about 25.8K chars for the top 5 alone,
- utilization extended tools: about 9.7K chars,
- metadata/invoke tools: about 9.0K chars for `invoke_service`, `describe_entity_schema`, and `discover_thing_members`,
- `activeBatchReserveChars=13857`, which made an already tight request impossible.

The key issue is that utilization tools, document tools, broad entity tools, metadata tools, playbook tools, and chart/table tools were all advertised together. That is operationally convenient but not sustainable under smaller effective provider caps.

## Tool Context Payloads

This appendix lists model-facing JSON content. It is intentionally verbose: the goal is to inspect exactly which descriptions, enums, and parameter schemas consume context.

The built-in payloads are the current Java `ToolDefinition` objects, serialized as in population **(a)** (same measurement), followed by the three document-knowledge tools (registered when document knowledge is enabled) and an example `start_playbook`. The extended-tool payloads are reconstructed from a deployment's `/tools/extended_tools.json` plus the target service definitions and DataShape expansion; the seven-tool generation below is historical.

### Reading Guide

For each tool:

- `description` is always sent to the model with the tool.
- `input_schema` is always sent to the model with the tool.
- long `enum` values, repeated natural-time guidance, large table contracts, and INFOTABLE DataShape expansion are the most obvious optimization candidates.

## Built-In And Playbook Tool Payloads

### invoke_service

Size chars: `3850`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "entityType" : {
        "type" : "string",
        "enum" : [ "ApplicationKey", "Authenticator", "Dashboard", "DataShape", "DataTagVocabulary", "DirectoryService", "ExtensionPackage", "Group", "LocalizationTable", "Log", "Mashup", "MediaEntity", "Menu", "ModelTagVocabulary", "Network", "NotificationContent", "NotificationDefinition", "Organization", "PersistenceProvider", "PersistenceProviderPackage", "Project", "Resource", "ScriptFunctionLibrary", "StateDefinition", "StyleDefinition", "StyleTheme", "Subsystem", "Thing", "ThingGroup", "ThingPackage", "ThingShape", "ThingTemplate", "User", "Widget" ],
        "description" : "Root ThingWorx entity type. DataTable/Stream/ValueStream **Things** use entityType \"Thing\" plus instance name; server may normalize a ThingTemplate name to Thing."
      },
      "entityName" : {
        "type" : "string",
        "description" : "Name of the entity"
      },
      "serviceName" : {
        "type" : "string",
        "description" : "Name of the service to invoke"
      },
      "parameters" : {
        "type" : "object",
        "description" : "All target service inputs MUST be nested inside **parameters** — never at invoke_service top level (e.g. {\"parameters\":{\"maxItems\":3}}). Optional when the service has no inputs. INFOTABLE: row object array. TAGS: [{\"vocabulary\":\"...\",\"vocabularyTerm\":\"...\"}]."
      }
    },
    "required" : [ "entityType", "entityName", "serviceName" ],
    "additionalProperties" : false
  },
  "name" : "invoke_service",
  "description" : "Generic ThingWorx service call when you have a concrete service name from discovery or the task points to a specific service. Do NOT list metadata entities by collection type — use list_entities_by_type; do not list all Things — use query_entities or spotlight_search. Edge-only Remote Service calls blocked. All service arguments belong under **parameters**; never beside entityType / entityName / serviceName. Knowing a service **name** is not knowing its **inputs**. Before calling a service whose parameter definitions you have not seen, read them from the tool that covers that target: a concrete Thing → **discover_thing_members** (thingName, facet \"service\", memberName); a ThingTemplate or ThingShape → **describe_entity_schema** (entityType, entityName, facet \"service\", memberName). Those two are the input-definition tools advertised by default; use whichever ones your current tool list actually offers, and respect each one's target type: do not send a ThingTemplate or ThingShape to discover_thing_members, and do not present some other entity type as a Thing to reach it. When none of the tools you were given covers the target, say so and ask, rather than guessing parameter names, calling a tool that is not in your list, or running a broader substitute query. Skip discovery when you already hold sufficient definitions for that target's service; a service that genuinely takes no arguments is still called with no parameters. Keep the entityType / entityName / serviceName you were pointed at, and map the constraints the caller already stated onto that service's real input names in **this** call. Omitting a scope or filter parameter widens the query; other optional parameters follow their own definitions; ask instead of inventing a key or dropping the constraint. Do not switch to a different entity because it exposes a service with the same name, and do not split one constrained request into a separate query. Large INFOTABLE results return sampleRows and **may** include **cacheId**; **fetch_cached_result** pages **cacheId** for display only — use **tabulate_cached_result** or **summarize_cached_result** for full-table work. When **cacheId** is absent, answer from **sampleRows** / **hint** only. Avoid full-metadata dump services (GetMetadata, GetPropertyDefinitions, …) — use discover_thing_members / describe_entity_schema, then get_property_values. Oversized non-tabular results cache as LARGE_JSON — use inspect_cached_payload / extract_nested."
}
```

### fetch_cached_result

Size chars: `1320`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "cacheId" : {
        "type" : "string",
        "description" : "cacheId from a prior large tabular tool (e.g. invoke_service INFOTABLE_LARGE **when** the response included cacheId — omit paging when cacheId was absent)."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Zero-based row offset (default 0). Success echoes offsetRequested / offsetEffective after clamp against totalRows."
      },
      "limit" : {
        "type" : "integer",
        "description" : "Max rows to return (default 50, max 200). Success echoes limitRequested / limitEffective."
      }
    },
    "required" : [ "cacheId" ]
  },
  "name" : "fetch_cached_result",
  "description" : "After a large tabular tool (**invoke_service** INFOTABLE_LARGE, query_entities, query_entities_by_taxonomy, or list_entities_by_type) returned a **cacheId**, fetch a **page** from the in-memory cache for **this conversation** for UI display or browsing. Read-only paging — does not sort or aggregate. Paging is clamp-and-echo: **offset**/**limit** on success are effective values; also read **offsetRequested**/**limitRequested** when diagnosing overshoot. Do **not** call repeatedly to read the entire table into the model — use **tabulate_cached_result** or **summarize_cached_result** for full-table computation. If **cacheId** was omitted, **do not** call this tool — answer from **sampleRows** / **hint**."
}
```

### tabulate_cached_result

Size chars: `13268`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "cacheId" : {
        "type" : "string",
        "description" : "Source cacheId — entire cached InfoTable is transformed. May be __PARLER_LAST_QUALIFYING_TABULAR_CACHE__ (see docs/agent/p2_last_tabular_cache.md). Omit for mode=union_rows (use sourceCacheIds instead)."
      },
      "sourceCacheIds" : {
        "type" : "array",
        "items" : {
          "type" : "string"
        },
        "description" : "Required for mode=union_rows: 2–31 conversation cacheIds to append in order."
      },
      "labelValues" : {
        "type" : "array",
        "items" : {
          "type" : "string"
        },
        "description" : "Optional for mode=union_rows: one label value per sourceCacheIds entry (same length when present)."
      },
      "labelColumn" : {
        "type" : "string",
        "description" : "Required for mode=union_rows: name of the appended label column (must not exist in the input tables)."
      },
      "mode" : {
        "description" : "filter_count: required filters; optional groupBy (string). filter_rows: required filters; optional sorts, maxItems, offset, fields. filter_sort_topn: required sorts; optional filters, maxItems, offset, fields (preferred Top-N / sort path). group_metric: required measures; optional filters, groupBy, derived, having, sorts, maxItems, offset, fields (preferred group count/aggregate path). bin_numeric: required column plus exactly one of binEdges or binCount (rangeMin/rangeMax only with binCount); optional filters; no groupBy. Returns one row per bin with count and density (histogram source). box_summary: required column; optional groupBy (one column) and filters. Returns per-group n, min/q1/median/q3/max, whiskers and outliers (boxplot source). union_rows: required sourceCacheIds (2–31 cache ids) and labelColumn; optional labelValues (one label per input, same order). Appends rows in order with the label column; columns must match by name and base type across inputs. Rows are not de-duplicated. exact_join: required rightCacheId; optional joinType (INNER|LEFT, default INNER); cacheId is the left table. quality: required timeColumn, windowStart, windowEnd; optional valueColumn. Assessment-only (no findingCacheId). resample: required timeColumn, windowStart, windowEnd; optional valueColumn, aggregation (COUNT|SUM|MEAN|MIN|MAX|FIRST|LAST, default MEAN). rolling: required timeColumn, windowStart, windowEnd; optional valueColumn, rollingKind, observationWindow, durationWindowSeconds, minSupport. rate_of_change: required timeColumn, windowStart, windowEnd; optional valueColumn. counter_delta / rolling_stats / time_weighted: valueColumn required; results cover only what was read, never a window or shift total. counter_delta: optional entityColumn, counter rules; increments between real readings. Give a rule only when the user or App states it; never guess. rolling_stats: required statistic; optional entityColumn, rollingKind and its window props. One statistic per source record; warmedUp is not coverage. time_weighted: required integrationMethod, maxGapSeconds, timeUnit. Integral and time mean of one property-history cache; held time is an estimate. calendar_bucket: required timeColumn, timeZone, calendarBucket. Adds local day/hour columns by each row's own time; aggregate with group_metric. period_compare: required timeColumn, originalWindowStart/End, currentWindowStart/End; optional valueColumn, aggregation. Assessment-only. U4 modes return analysisEnvelope (not insightEnvelope). See query-spec.md.",
        "enum" : [ "filter_count", "filter_rows", "filter_sort_topn", "group_metric", "bin_numeric", "box_summary", "union_rows", "exact_join", "quality", "resample", "rolling", "rate_of_change", "period_compare", "counter_delta", "rolling_stats", "time_weighted", "calendar_bucket" ],
        "type" : "string"
      },
      "rightCacheId" : {
        "type" : "string",
        "description" : "Required for mode=exact_join: conversation cacheId of the right join table. Must be an explicit cache id (not the last-tabular sentinel)."
      },
      "joinType" : {
        "type" : "string",
        "description" : "Optional for mode=exact_join: INNER (default when omitted) or LEFT. Any other present value fails fast."
      },
      "timeColumn" : {
        "type" : "string",
        "description" : "Required for mode=quality / mode=resample / mode=rolling / mode=rate_of_change / mode=counter_delta / mode=rolling_stats / mode=time_weighted / mode=period_compare / mode=calendar_bucket: temporal column name."
      },
      "valueColumn" : {
        "type" : "string",
        "description" : "Optional numeric value column for mode=quality / mode=resample / mode=rolling / mode=rate_of_change / mode=counter_delta / mode=rolling_stats / mode=time_weighted / mode=period_compare."
      },
      "windowStart" : {
        "type" : "string",
        "description" : "Required for mode=quality / mode=resample / mode=rolling / mode=rate_of_change / mode=counter_delta / mode=rolling_stats / mode=time_weighted: ISO-8601 half-open window start."
      },
      "windowEnd" : {
        "type" : "string",
        "description" : "Required for mode=quality / mode=resample / mode=rolling / mode=rate_of_change / mode=counter_delta / mode=rolling_stats / mode=time_weighted: ISO-8601 half-open window end (exclusive)."
      },
      "aggregation" : {
        "type" : "string",
        "description" : "Optional for mode=resample / mode=period_compare: COUNT|SUM|MEAN|MIN|MAX|FIRST|LAST (default MEAN). Present invalid values fail fast."
      },
      "rollingKind" : {
        "type" : "string",
        "description" : "Optional for mode=rolling / mode=rolling_stats: OBSERVATION_COUNT (default) or ELAPSED_DURATION. Present invalid values fail fast."
      },
      "observationWindow" : {
        "type" : "integer",
        "description" : "Optional for mode=rolling / mode=rolling_stats OBSERVATION_COUNT: window size (default 5)."
      },
      "durationWindowSeconds" : {
        "type" : "integer",
        "description" : "Optional for mode=rolling / mode=rolling_stats ELAPSED_DURATION: window seconds (default 3600)."
      },
      "minSupport" : {
        "type" : "integer",
        "description" : "Optional for mode=rolling / mode=rolling_stats: minimum support (default 1)."
      },
      "statistic" : {
        "enum" : [ "mean", "sum", "min", "max", "stddev", "count_values", "count_records" ],
        "type" : "string"
      },
      "entityColumn" : {
        "type" : "string",
        "description" : "counter_delta / rolling_stats: column separating series."
      },
      "counterModulus" : {
        "type" : "number",
        "description" : "counter_delta: rollover modulus; needs maxRatePerSecond."
      },
      "maxRatePerSecond" : {
        "type" : "number",
        "description" : "counter_delta: largest plausible increase per second."
      },
      "resetBaseline" : {
        "type" : "number",
        "description" : "counter_delta: restart value; not with counterModulus."
      },
      "maxGapSeconds" : {
        "type" : "integer",
        "description" : "counter_delta / time_weighted: longest usable interval between readings."
      },
      "integrationMethod" : {
        "enum" : [ "step_hold", "trapezoid" ],
        "type" : "string"
      },
      "timeUnit" : {
        "enum" : [ "seconds", "minutes", "hours" ],
        "type" : "string"
      },
      "timeZone" : {
        "type" : "string",
        "description" : "IANA zone id or UTC; no default."
      },
      "calendarBucket" : {
        "enum" : [ "day", "hour" ],
        "type" : "string"
      },
      "originalWindowStart" : {
        "type" : "string",
        "description" : "Required for mode=period_compare: ISO-8601 original window start."
      },
      "originalWindowEnd" : {
        "type" : "string",
        "description" : "Required for mode=period_compare: ISO-8601 original window end (exclusive)."
      },
      "currentWindowStart" : {
        "type" : "string",
        "description" : "Required for mode=period_compare: ISO-8601 current window start."
      },
      "currentWindowEnd" : {
        "type" : "string",
        "description" : "Required for mode=period_compare: ISO-8601 current window end (exclusive)."
      },
      "filters" : {
        "type" : "object",
        "description" : "Row predicate (filter_* modes; optional for filter_sort_topn, group_metric, bin_numeric, box_summary). ThingWorx-shaped Query dialect (query-spec §3): leaf \"type\" (EQ, NE, LT, LE, GT, GE, LIKE, IN, BETWEEN, …), \"fieldName\", type keys \"value\", \"from\"/\"to\", \"values\"; composites {\"type\":\"AND\",\"filters\":[...]} (OR/NOT similarly); optional \"isCaseSensitive\"."
      },
      "column" : {
        "type" : "string",
        "description" : "bin_numeric / box_summary: numeric source column (or uniformly numeric-parseable STRING) whose distribution is computed."
      },
      "binEdges" : {
        "type" : "array",
        "items" : {
          "type" : "number"
        },
        "description" : "bin_numeric: 2–51 strictly increasing finite bin edges (explicit_edges_v1). Bins are [edge[i], edge[i+1]); the last bin includes its right endpoint. Mutually exclusive with binCount."
      },
      "binCount" : {
        "type" : "integer",
        "description" : "bin_numeric: number of equal-width bins 1–50 (equal_width_v1) over [rangeMin, rangeMax], defaulting to the valid values' min/max. Mutually exclusive with binEdges.",
        "minimum" : 1,
        "maximum" : 50
      },
      "rangeMin" : {
        "type" : "number",
        "description" : "bin_numeric with binCount only: lower bound of the equal-width range."
      },
      "rangeMax" : {
        "type" : "number",
        "description" : "bin_numeric with binCount only: upper bound of the equal-width range (must exceed rangeMin)."
      },
      "sorts" : {
        "type" : "array",
        "description" : "1–3 sort keys for filter_rows / filter_sort_topn / group_metric output.",
        "items" : {
          "type" : "object",
          "properties" : {
            "fieldName" : {
              "type" : "string"
            },
            "isAscending" : {
              "type" : "boolean",
              "description" : "Optional; default true (ascending) per query-spec §4.1."
            },
            "isCaseSensitive" : {
              "type" : "boolean",
              "description" : "Optional; default true for string sort keys (query-spec §4)."
            }
          },
          "required" : [ "fieldName" ]
        }
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Output row cap (1–500) after sort/filter; default 50 for sort-style modes."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Non-negative slice offset after sort (default 0)."
      },
      "fields" : {
        "type" : "array",
        "description" : "Optional output column projection (query-spec §5.3): array of source or output column names, order preserved. Allowed on filter_rows, filter_sort_topn, group_metric only.",
        "items" : {
          "type" : "string"
        }
      },
      "measures" : {
        "type" : "array",
        "description" : "group_metric: up to 10 measures.",
        "items" : {
          "type" : "object",
          "properties" : {
            "name" : {
              "type" : "string"
            },
            "op" : {
              "enum" : [ "count", "count_non_null", "sum", "avg", "min", "max", "count_distinct", "weighted_avg", "median", "percentile", "variance", "stddev", "first", "last", "mode" ],
              "type" : "string"
            },
            "column" : {
              "type" : "string"
            },
            "filters" : {
              "type" : "object",
              "description" : "Optional per-measure row filter; omit this field entirely when unconditional (no TRUE/ALL/MATCH_ALL placeholders). ThingWorx-shaped Query dialect (query-spec §3): leaf \"type\" (EQ, NE, LT, LE, GT, GE, LIKE, IN, BETWEEN, …), \"fieldName\", type keys \"value\", \"from\"/\"to\", \"values\"; composites {\"type\":\"AND\",\"filters\":[...]} (OR/NOT similarly); optional \"isCaseSensitive\"."
            },
            "weightColumn" : {
              "type" : "string"
            },
            "p" : {
              "type" : "number",
              "description" : "percentile only: p in [0,100]."
            },
            "orderBy" : {
              "type" : "string",
              "description" : "first / last only: sortable column name."
            },
            "direction" : {
              "description" : "first / last only; default asc when omitted or blank.",
              "enum" : [ "asc", "desc", "ascending", "descending" ],
              "type" : "string"
            }
          },
          "required" : [ "name", "op" ]
        }
      },
      "derived" : {
        "type" : "array",
        "description" : "group_metric: derived metrics (max 10), including percent_of_total / percent_of_group.",
        "items" : {
          "type" : "object",
          "properties" : {
            "name" : {
              "type" : "string"
            },
            "op" : {
              "description" : "Derived metric op. percent_of_total / percent_of_group require input (measure name); percent_of_group also requires groupBy[].",
              "enum" : [ "ratio", "ratio_percent", "difference", "sum_values", "multiply", "scale", "percent_of_total", "percent_of_group" ],
              "type" : "string"
            },
            "numerator" : {
              "type" : "string"
            },
            "denominator" : {
              "type" : "string"
            },
            "left" : {
              "type" : "string"
            },
            "right" : {
              "type" : "string"
            },
            "inputs" : {
              "type" : "array",
              "items" : {
                "type" : "string"
              },
              "description" : "sum_values / multiply: non-blank measure or derived names (max 10)."
            },
            "input" : {
              "type" : "string",
              "description" : "scale: measure or prior derived name. percent_of_total / percent_of_group: measure name only."
            },
            "factor" : {
              "type" : "number",
              "description" : "scale: numeric factor."
            },
            "groupBy" : {
              "type" : "array",
              "items" : {
                "type" : "string"
              },
              "description" : "percent_of_group only: non-empty subset of the mode-level groupBy columns used as the percent partition."
            }
          },
          "required" : [ "name", "op" ]
        }
      },
      "having" : {
        "type" : "object",
        "description" : "group_metric only: post-aggregate filter on grouped output rows. **Prefer `having`** for metric equality or threshold questions; do **not** sort and read **`sampleRows`** — previews only; **`totalRows`** is output size, not full membership proof. ThingWorx-shaped Query dialect (query-spec §3): leaf \"type\" (EQ, NE, LT, LE, GT, GE, LIKE, IN, BETWEEN, …), \"fieldName\", type keys \"value\", \"from\"/\"to\", \"values\"; composites {\"type\":\"AND\",\"filters\":[...]} (OR/NOT similarly); optional \"isCaseSensitive\"."
      },
      "groupBy" : {
        "oneOf" : [ {
          "type" : "string"
        }, {
          "type" : "array",
          "items" : {
            "type" : "string"
          }
        } ],
        "description" : "filter_count: optional string column. group_metric: array of 0–5 source column names (or []). box_summary: optional single column (string or one-element array). Not supported by bin_numeric."
      }
    },
    "required" : [ "mode" ]
  },
  "name" : "tabulate_cached_result",
  "description" : "Deterministic transforms over a **conversation-cached** tabular result (full table per cacheId; fetch_cached_result is paging only). LARGE outputs get a new cacheId. Modes: **filter_count**, **filter_rows**, **filter_sort_topn**, **group_metric** (cached-table-decision-tools.md). Distribution: **bin_numeric** (histogram bins with count/density) and **box_summary** (per-group quartiles, whiskers, outliers) — run these before charting a distribution. Also **exact_join** (INNER/LEFT). Also **quality** (window assessment). Also **resample**. Also **rolling**. Also **rate_of_change**. Also **period_compare** (assessment-only). U4 modes return analysisEnvelope, not insightEnvelope. Use for Top-N, group metrics, thresholds, and filtered tables — not sample-row guessing."
}
```

### analyze_entity_set

Size chars: `2320`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "operation" : {
        "type" : "string",
        "enum" : [ "difference", "intersection", "union", "symmetric_difference" ],
        "description" : "Set operation: **difference**, **intersection**, **union**, or **symmetric_difference**."
      },
      "left" : {
        "type" : "object",
        "properties" : {
          "cacheId" : {
            "type" : "string",
            "description" : "Explicit conversation cache id from a prior list/tabular tool."
          },
          "keyColumn" : {
            "type" : "string",
            "description" : "Identity column on this operand (default **name**)."
          },
          "label" : {
            "type" : "string",
            "description" : "Optional diagnostic label."
          }
        },
        "required" : [ "cacheId" ]
      },
      "right" : {
        "type" : "object",
        "properties" : {
          "cacheId" : {
            "type" : "string",
            "description" : "Explicit conversation cache id from a prior list/tabular tool."
          },
          "keyColumn" : {
            "type" : "string",
            "description" : "Identity column on this operand (default **name**)."
          },
          "label" : {
            "type" : "string",
            "description" : "Optional diagnostic label."
          }
        },
        "required" : [ "cacheId" ]
      },
      "projectColumns" : {
        "description" : "Optional projection. For **difference** / **intersection**, names columns on the **left** operand only. For **union** / **symmetric_difference**, each name must exist on **at least one** operand; missing cells are **null** for keys present on only one side.",
        "items" : {
          "type" : "string"
        },
        "type" : "array"
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Page size into output rows (default 50, max 500)."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Zero-based offset into output rows."
      }
    },
    "required" : [ "operation", "left", "right" ]
  },
  "name" : "analyze_entity_set",
  "description" : "Deterministic set algebra over two **already cached** entity/list tables: **difference**, **intersection**, **union**, or **symmetric_difference**. Does **not** query ThingWorx. Operands must supply explicit **cacheId** values (never the last-tabular sentinel). Every success returns a **new cacheId** for the full transformed table (including small inline rows) so the next step can call **tabulate_cached_result** / **build_chart_from_tabular_result** without paging the set. Do **not** pass **groupBy** here — use **tabulate_cached_result(mode=group_metric)** on the returned cacheId. Typical flows: template-minus-taxonomy → **difference**; overlap → **intersection**; combine two lists → **union**; items in exactly one list → **symmetric_difference**; then **tabulate_cached_result** → chart."
}
```

### summarize_cached_result

Size chars: `956`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "cacheId" : {
        "type" : "string",
        "description" : "Cached table to summarize (full table). May be __PARLER_LAST_QUALIFYING_TABULAR_CACHE__ (same resolution order as tabulate_cached_result: per-turn then conversation mirror)."
      },
      "percentileColumns" : {
        "description" : "Optional JSON array of non-empty strings (column names) for p50/p95 on numeric columns only. Must be an array of strings (no nulls, numbers, or objects); empty strings invalid. If omitted, p50/p95 apply only to the first 8 numeric columns in declaration order; others still get min/max/mean.",
        "items" : {
          "type" : "string"
        },
        "type" : "array"
      }
    },
    "required" : [ "cacheId" ]
  },
  "name" : "summarize_cached_result",
  "description" : "Column-level stats (null counts, numeric min/max/mean and capped p50/p95, categorical cardinality/top) over the **full** cached InfoTable. Success includes sourceCacheId. Use for summaries and null-heavy questions instead of eyeballing row JSON."
}
```

### analyze_cached_result

Size chars: `2297`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "operation" : {
        "description" : "U5 analysis operation: outlier | change_point | spc | relationship | trend | threshold_crossing.",
        "enum" : [ "outlier", "change_point", "spc", "relationship", "trend", "threshold_crossing" ],
        "type" : "string"
      },
      "methodId" : {
        "type" : "string",
        "description" : "Optional versioned method id (e.g. robust_z, pearson, ols_trend). Defaults per operation."
      },
      "cacheId" : {
        "type" : "string",
        "description" : "Conversation cache handle for the primary series (handle-only; never pass rows)."
      },
      "rightCacheId" : {
        "type" : "string",
        "description" : "Right-side cache handle for relationship (required when operation=relationship)."
      },
      "timeColumn" : {
        "type" : "string",
        "description" : "Timestamp column name. Use the exact column name declared by that cache's result (`columns[]`, and `timeColumn` when the result declares it); do not infer it from a property label."
      },
      "valueColumn" : {
        "type" : "string",
        "description" : "Numeric value column name. Use the exact column name declared by that cache's result (`columns[]`, and `valueColumn` when the result declares it); do not infer it from a property label."
      },
      "rightValueColumn" : {
        "type" : "string",
        "description" : "Right-side value column for relationship (defaults to valueColumn)."
      },
      "alignment" : {
        "description" : "Relationship alignment mode. Default exact.",
        "enum" : [ "exact", "nearest" ],
        "type" : "string"
      },
      "toleranceMillis" : {
        "type" : "integer",
        "description" : "Nearest-alignment tolerance in milliseconds (required when alignment=nearest)."
      },
      "threshold" : {
        "type" : "number",
        "description" : "Threshold value for threshold_crossing."
      },
      "horizonSeconds" : {
        "type" : "number",
        "description" : "Approved forecast horizon in seconds for threshold_crossing."
      }
    },
    "required" : [ "operation", "cacheId" ],
    "additionalProperties" : false
  },
  "name" : "analyze_cached_result",
  "description" : "Deterministic U5 analysis over a **conversation-cached** series handle. One tool with **operation** enum: outlier | change_point | spc | relationship | trend | threshold_crossing. Requires **cacheId**, **timeColumn**, and **valueColumn** (plus **rightCacheId** for relationship). Returns compact **analysisEnvelope** (status, outcomeCode, method, support, metrics). Does **not** invent causality; relationship is association evidence only. Handle inputs only — never pass row/value arrays."
}
```

### inspect_cached_payload

Size chars: `763`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "cacheId" : {
        "type" : "string",
        "description" : "Conversation cacheId from a LARGE_JSON invoke_service result (or a prior extract)."
      },
      "mode" : {
        "description" : "inspect = bounded structure hints; extract = path-selected subtree (re-caches when over cap).",
        "enum" : [ "inspect", "extract" ],
        "type" : "string"
      },
      "path" : {
        "type" : "string",
        "description" : "Required for mode=extract. Dotted keys + explicit [index] only (no glob/JSONPath)."
      }
    },
    "required" : [ "cacheId", "mode" ]
  },
  "name" : "inspect_cached_payload",
  "description" : "Bounded inspect/extract over a **cached JSON/TEXT** payload (LARGE_JSON cacheId). Does not load the full oversize body into the model. For nested INFOTABLE cells inside a cached **table**, use **extract_nested** instead."
}
```

### extract_nested

Size chars: `568`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "sourceCacheId" : {
        "type" : "string",
        "description" : "Cached tabular cacheId that contains a nested INFOTABLE cell."
      },
      "cellPath" : {
        "type" : "string",
        "description" : "Cell path: [rowIndex].fieldName… (dotted keys + explicit indexes only)."
      }
    },
    "required" : [ "sourceCacheId", "cellPath" ]
  },
  "name" : "extract_nested",
  "description" : "Promote a nested INFOTABLE cell from a cached table into a **new** cacheId with lineage (sourceCacheId + cellPath). Use after inspect/tabulate shows nested tables that need their own fetch/tabulate/chart path."
}
```

### build_chart_from_tabular_result

Size chars: `6702`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "source" : {
        "type" : "string",
        "description" : "last_invoke — latest qualifying tabular result this turn (latest-wins). cache_id — chart a prior table via top-level cacheId (same cache as fetch_cached_result). Multiple charts in one turn: cache_id + each result's cacheId."
      },
      "cacheId" : {
        "type" : "string",
        "description" : "Required when source=cache_id: cacheId from a prior tabular tool envelope — not tool call ids."
      },
      "xColumn" : {
        "type" : "string",
        "description" : "Column name for X (category for bar; numeric or ISO time for line/scatter; the column dimension for heatmap). Required for line, bar, scatter, pie and heatmap; must be omitted for histogram and boxplot (and for intent distribution), whose source table already carries the bins or the box statistics."
      },
      "yColumn" : {
        "type" : "string",
        "description" : "Column name for Y (single series). Omit when using series[]."
      },
      "series" : {
        "description" : "Multi-series: [{ name, yColumn }], shared xColumn. Mutually exclusive with yColumn.",
        "items" : {
          "required" : [ "name", "yColumn" ],
          "properties" : {
            "name" : {
              "type" : "string"
            },
            "yColumn" : {
              "type" : "string"
            }
          },
          "type" : "object"
        },
        "type" : "array"
      },
      "title" : {
        "type" : "string"
      },
      "xLabel" : {
        "type" : "string"
      },
      "yLabel" : {
        "type" : "string"
      },
      "yReferenceLines" : {
        "type" : "array",
        "maxItems" : 12,
        "description" : "Horizontal lines (max 12): { y, label?, role? }. Allowed on line, bar, scatter and boxplot; INVALID_PARAMETERS on histogram and heatmap.",
        "items" : {
          "type" : "object",
          "properties" : {
            "y" : {
              "type" : "number"
            },
            "label" : {
              "type" : "string"
            },
            "role" : {
              "description" : "Optional line role (SPC / threshold).",
              "enum" : [ "usl", "ucl", "lcl", "lsl", "target", "limit", "warning" ],
              "type" : "string"
            }
          },
          "required" : [ "y" ]
        }
      },
      "requestedTimeRange" : {
        "type" : "object",
        "description" : "Optional ISO-8601 bounds for time line/scatter X domain (tabular charts).",
        "properties" : {
          "start" : {
            "type" : "string",
            "description" : "Inclusive range start (ISO-8601). Alias key: startTime."
          },
          "end" : {
            "type" : "string",
            "description" : "Exclusive or inclusive range end per chart contract (ISO-8601). Alias key: endTime."
          }
        }
      },
      "seriesColumn" : {
        "type" : "string",
        "description" : "Optional for kind bar, line, or scatter: long-format column whose distinct values become multiple series (grouped bar or line/scatter pivot). Required for kind heatmap: the row dimension (xColumn is the column dimension). Mutually exclusive with series[]. Requires yColumn."
      },
      "pieSliceMode" : {
        "description" : "Optional for kind pie: slice policy (default top_with_other). Ignored for other kinds.",
        "enum" : [ "top_with_other", "all_nonzero" ],
        "type" : "string"
      },
      "pieMaxSlices" : {
        "type" : "integer",
        "description" : "Optional for kind pie: max slices 2..12 (default 8). Ignored for other kinds.",
        "minimum" : 2,
        "maximum" : 12
      },
      "histogramMode" : {
        "description" : "Optional, only when the explicit or intent-resolved kind is histogram: which bin value the bar height shows (default count). Use density to compare distribution shape when bins have unequal widths. Rejected (INVALID_PARAMETERS) for other kinds or other values.",
        "enum" : [ "count", "density" ],
        "type" : "string"
      },
      "orientation" : {
        "description" : "bar only (default vertical). Use horizontal only when the user explicitly asks; INVALID_PARAMETERS for other kinds.",
        "enum" : [ "vertical", "horizontal" ],
        "type" : "string"
      },
      "groupMemberKey" : {
        "type" : "string",
        "description" : "Optional: the member key of the chart group declared this turn with declare_chart_group; binds this chart to that slot (each key once). INVALID_PARAMETERS when no group is declared, the key is unknown, or the member is already filled."
      },
      "stackMode" : {
        "description" : "bar only, with at least two series (seriesColumn or series[]); default grouped. stacked accumulates positive and negative values separately; percent shows each series' share of the category total and needs non-negative values (STACK_PERCENT_NEGATIVE). Use only when the user explicitly asks for a stacked or 100% chart; INVALID_PARAMETERS for other kinds or a single series. May be combined with orientation.",
        "enum" : [ "grouped", "stacked", "percent" ],
        "type" : "string"
      },
      "kind" : {
        "description" : "Explicit chart kind (line|bar|scatter|pie|histogram|boxplot|heatmap). histogram takes a bin_numeric result table and boxplot a box_summary result table as its source, with no column bindings. heatmap takes a long table (e.g. group_metric with two groupBy keys) with xColumn = column dimension, seriesColumn = row dimension, yColumn = cell value; missing combinations stay empty cells. Optional when using intent instead. Mutually exclusive with intent — supply exactly one of kind or intent; runtime rejects both or neither.",
        "enum" : [ "line", "bar", "scatter", "pie", "histogram", "boxplot", "heatmap" ],
        "type" : "string"
      },
      "intent" : {
        "description" : "Visual intent; server selects line|bar|scatter (or CHART_FALLBACK for phase-only intents). Optional when using explicit kind instead. Mutually exclusive with kind.",
        "enum" : [ "time_trend", "rank", "compare_groups", "correlation", "distribution", "status_timeline", "composition" ],
        "type" : "string"
      }
    },
    "required" : [ "source" ]
  },
  "name" : "build_chart_from_tabular_result",
  "description" : "Build a Parler chart from a tabular tool result (invoke_service INFOTABLE*, query_entities, query_entities_by_taxonomy, list_entities_by_type, fetch_cached_result, tabulate_cached_result, or an extended-tool JSON result whose decoded `result` object is a complete small single table: business `status` absent or `success`, non-empty root `rows`, no partial-page signal, within inline limit). That JSON shape is a valid `last_invoke` source — do not re-query the same service through another entry point. From **analyze_entity_set**, tabulate (or fetch) its **cacheId** first. Supply **either** `kind` (line|bar|scatter|pie) **or** `intent` — never both or neither (provider-safe root schema). `source: \"last_invoke\"` = **most recent** qualifying tabular result of the **current request** (latest-wins; several qualifying results in one turn do **not** make it ambiguous). `source: \"cache_id\"` charts an earlier table via a returned top-level `cacheId` (qualifying JSON single tables **may** carry one on its outer envelope too). Evidence ids (`e1`, `e2`, …), tool call ids and `chartId` are **not** cacheIds. Use only a handle a result actually returned; never invent one; do not retry the same invalid id. A **new user request** starts `last_invoke` empty — history does not make them chartable without a fresh query. A `cacheId` you still hold stays usable with `source: \"cache_id\"` rather than re-running the query. Query A → chart A → query B → chart B; **one turn may emit several charts**. `CHART_EMITTED` means count it as done — do **not** rebuild it or claim only one chart per turn is allowed. See chart-intent.md and CHART_CONTRACT.md §2.5."
}
```

### declare_chart_group

Size chars: `1303`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "title" : {
        "type" : "string",
        "description" : "Group title (1-120 chars)."
      },
      "members" : {
        "type" : "array",
        "minItems" : 2,
        "maxItems" : 6,
        "items" : {
          "type" : "object",
          "properties" : {
            "key" : {
              "description" : "Unique slot key you will pass as groupMemberKey when building that chart.",
              "pattern" : "^[a-z0-9_-]{1,32}$",
              "type" : "string"
            },
            "name" : {
              "type" : "string",
              "description" : "Readable member name (1-80 chars)."
            }
          },
          "required" : [ "key", "name" ]
        },
        "description" : "2-6 chart slots in display order; declare only members you will build this turn."
      },
      "layout" : {
        "description" : "Layout hint (default auto).",
        "enum" : [ "auto", "stack", "grid" ],
        "type" : "string"
      },
      "sharedCategoryDimension" : {
        "type" : "string",
        "description" : "Optional (1-80 chars). Give it only when the members share one category dimension (for example device state) so the same category takes the same colour across the group; omit it when the members measure different or unrelated categories."
      }
    },
    "required" : [ "title", "members" ]
  },
  "name" : "declare_chart_group",
  "description" : "Declare one chart group (2-6 named slots) before building its charts, only when the user asks for a set of comparable charts; then build each member with build_chart_from_tabular_result and its groupMemberKey. One group per request; slots you do not build end as errors."
}
```

### build_history_overlay_chart

Size chars: `3303`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "propertyName" : {
        "type" : "string",
        "description" : "Same numeric property on every series Thing (NUMBER/INTEGER/LONG)."
      },
      "series" : {
        "type" : "array",
        "minItems" : 2,
        "maxItems" : 6,
        "items" : {
          "type" : "object",
          "properties" : {
            "label" : {
              "type" : "string",
              "description" : "Legend label for this trace."
            },
            "thingName" : {
              "type" : "string",
              "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
            },
            "calendarPhrase" : {
              "type" : "string",
              "description" : "Optional single-day phrase resolved from anchorTime + user_timezone."
            },
            "relativeDuration" : {
              "type" : "string",
              "description" : "Optional duration ending at the series anchor (e.g. 2h). Mutually exclusive with calendarPhrase and explicit bounds."
            },
            "anchorOffset" : {
              "type" : "string",
              "description" : "Optional shift back from shared anchorTime before resolving this series window (e.g. 7d). Use with relativeDuration for shifted windows. Mutually exclusive with explicit startTime/endTime."
            },
            "startTime" : {
              "type" : "string",
              "description" : "Optional ISO-8601 window start (alias: start)."
            },
            "endTime" : {
              "type" : "string",
              "description" : "Optional ISO-8601 window end (alias: end)."
            }
          },
          "required" : [ "label", "thingName" ]
        },
        "description" : "2–6 history traces. Each entry resolves its own time window from shared anchorTime."
      },
      "xAxisMode" : {
        "description" : "X-axis mode. absolute_time = real timestamps (same-window cross-Thing). elapsed_time = seconds from each series window start (shifted windows). normalized_time = map each series window to 0..1 for shape/profile comparison (different durations). When omitted, server picks absolute_time when all windows match, else elapsed_time. Set normalized_time explicitly for shape comparisons — not inferred.",
        "enum" : [ "absolute_time", "elapsed_time", "normalized_time" ],
        "type" : "string"
      },
      "chart_kind" : {
        "description" : "Chart kind (default line).",
        "enum" : [ "line", "scatter" ],
        "type" : "string"
      },
      "anchorTime" : {
        "type" : "string",
        "description" : "Optional ISO anchor for resolving all series windows (default: agent turn clock now)."
      },
      "title" : {
        "type" : "string",
        "description" : "Optional chart title."
      },
      "yLabel" : {
        "type" : "string",
        "description" : "Optional Y-axis label."
      },
      "yReferenceLines" : {
        "type" : "array",
        "maxItems" : 12,
        "items" : {
          "type" : "object",
          "properties" : {
            "y" : {
              "type" : "number",
              "description" : "Y-axis value (finite numeric)."
            },
            "label" : {
              "type" : "string",
              "description" : "Optional line label."
            },
            "role" : {
              "type" : "string",
              "description" : "Optional role: usl, ucl, lcl, lsl, target, limit, warning (default limit)."
            }
          },
          "required" : [ "y" ]
        }
      }
    },
    "required" : [ "propertyName", "series" ]
  },
  "name" : "build_history_overlay_chart",
  "description" : "Overlay **2–6 numeric property history traces** (each series: own Thing + window). **absolute_time** = same real window; **elapsed_time** = shifted windows; **normalized_time** = shape/profile (0..1). Prefer over repeated query_property_history. Long cached tables: use build_chart_from_tabular_result with seriesColumn instead. Result `seriesCaches[]` lists each series' `cacheId`, columns, `timeColumn` / `valueColumn` and the resolved request window (not coverage) for reuse by cached-result tools."
}
```

### discover_thing_members

Size chars: `2378`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical Thing name. If uncertain, use resolve_thing first; on no match, try spotlight_search."
      },
      "facet" : {
        "description" : "Member facet. v1: properties (list), property (singular, requires memberName), services (public list), service (singular public service, requires memberName), events (list), event (singular, requires memberName), subscriptions (configured multi-event subscription list, read-only).",
        "enum" : [ "properties", "property", "services", "service", "events", "event", "subscriptions" ],
        "type" : "string"
      },
      "memberName" : {
        "type" : "string",
        "description" : "Required for singular facets property, service, and event — member name (case-insensitive fallback after exact match for property, service, and event). Must be omitted for list facets (properties, services, events, subscriptions); use namePrefix to narrow a list — passing memberName on a list facet returns UNSUPPORTED_TOOL_PARAMETER."
      },
      "namePrefix" : {
        "type" : "string",
        "description" : "Optional list filter on member name prefix."
      },
      "category" : {
        "type" : "string",
        "description" : "Optional category filter where applicable."
      },
      "baseType" : {
        "type" : "string",
        "description" : "Optional filter: property base type, or service result base type for facet=services (ignored for other facets when present)."
      },
      "dataShape" : {
        "type" : "string",
        "description" : "Optional INFOTABLE dataShape filter for facet=properties (property aspect) and facet=events (EventDefinition.getDataShapeName); ignored for services and subscriptions."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Zero-based list offset (default 0)."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Max list rows (default 80, max 200)."
      }
    },
    "required" : [ "thingName" ]
  },
  "name" : "discover_thing_members",
  "description" : "**Preferred** built-in for **concrete Thing** member metadata (properties, **public** services, **events**, and **configured subscriptions** in v1): visibility-aware lookup, bounded pagination, list and singular facets, same success/error envelope family as describe_entity_schema. This is the default greenfield path for Thing member metadata (properties / public services / events / subscriptions). Does not read property values, invoke services, fire events, refresh subscriptions, or return schema-entity definitions — for ThingTemplate / ThingShape / DataShape use describe_entity_schema."
}
```

### describe_entity_schema

Size chars: `2868`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "entityType" : {
        "description" : "Schema entity type. Thing instances are not accepted — use discover_thing_members + get_property_values for concrete Things.",
        "enum" : [ "ThingTemplate", "ThingShape", "DataShape" ],
        "type" : "string"
      },
      "entityName" : {
        "type" : "string",
        "description" : "Canonical name of the ThingTemplate, ThingShape, or DataShape."
      },
      "facet" : {
        "description" : "Facet to return. v1: summary (counts), list facets properties/services/events (ThingTemplate/ThingShape), fields (DataShape), singular service (memberName required). Singular property/event/field and subscriptions are not supported in v1 — use additional **describe_entity_schema** calls per facet; do not treat a single combined metadata dump as the default path. Persisted replay may still invoke **get_entity** (off-list) only when a stamped **parler.entity.metadata.v1** payload is explicitly required.",
        "enum" : [ "summary", "properties", "services", "events", "fields", "service" ],
        "type" : "string"
      },
      "memberName" : {
        "type" : "string",
        "description" : "Required when facet is \"service\" — exact service name."
      },
      "scope" : {
        "description" : "effective (default): inherited definitions via platform effective APIs. local: ThingTemplate/ThingShape — members from getInstanceShape() only (not merged instance service/event lists); DataShape — getDataShape() before effective merge.",
        "enum" : [ "effective", "local" ],
        "type" : "string"
      },
      "namePrefix" : {
        "type" : "string",
        "description" : "Optional name prefix filter for list facets."
      },
      "category" : {
        "type" : "string",
        "description" : "Optional category filter for property/service/event list facets."
      },
      "baseType" : {
        "type" : "string",
        "description" : "Optional base type filter for property/field list facets."
      },
      "dataShape" : {
        "type" : "string",
        "description" : "Optional INFOTABLE dataShape filter for property/field list facets."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Zero-based offset for list facets (default 0)."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Page size for list facets (default 80, max 200)."
      }
    },
    "required" : [ "entityType", "entityName" ]
  },
  "name" : "describe_entity_schema",
  "description" : "Facet-bounded schema read for ThingTemplate, ThingShape, or DataShape: summary, paginated property/service/event lists, DataShape fields, or one public service definition (parameters + result type). Uses visibility-aware entity resolution. Does not return service implementation bodies. Code DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE means required platform schema APIs were missing, failed, or threw during template/shape member reads (local or effective list facets) — not an authoritative empty schema. **Default** inspection for schema entities: stay on this tool's facets. Tier B / persisted replay may still run **get_entity** (executor-only, not merged into the LLM tool list) when one stamped **parler.entity.metadata.v1** combined payload is required."
}
```

### query_entities

Size chars: `5157`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "entityType" : {
        "type" : "string",
        "description" : "Use Thing (default semantics: list Thing instances implementing the template or shape)."
      },
      "thingTemplate" : {
        "type" : "string",
        "description" : "ThingTemplate name. Exactly one of thingTemplate or thingShape is required."
      },
      "thingShape" : {
        "type" : "string",
        "description" : "ThingShape name. Exactly one of thingTemplate or thingShape is required."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Page size / max rows (default 50, max 200). Maps to platform maxItems / nMaxItems."
      },
      "offset" : {
        "type" : "integer",
        "description" : "Row offset for pagination when using QueryImplementingThingsOptimized* (default 0)."
      },
      "namePrefix" : {
        "type" : "string",
        "description" : "Optional name mask / prefix passed to platform nameMask when supported."
      },
      "withPermissions" : {
        "type" : "boolean",
        "description" : "Maps to platform withPermissions on Optimized services (include read/update/delete permission columns). Default false."
      },
      "withData" : {
        "type" : "boolean",
        "description" : "Deprecated alias: treated like withPermissions for backward compatibility (Optimized services have no withData parameter)."
      },
      "includeDescription" : {
        "type" : "boolean",
        "description" : "Add basic column description (semantic / UI). Default false; default basic columns are name only."
      },
      "includeIsSystemObject" : {
        "type" : "boolean",
        "description" : "Add basic column isSystemObject. Default false."
      },
      "includeTags" : {
        "type" : "boolean",
        "description" : "Add basic column tags. Default false."
      },
      "includeConcreteTemplate" : {
        "type" : "boolean",
        "description" : "Add propertyNames column thingTemplate (leaf template per row). Only when thingTemplate is set; invalid with thingShape."
      },
      "widePropertyColumns" : {
        "type" : "boolean",
        "description" : "If true, omit basicPropertyNames/propertyNames so the platform may return all columns (heavy). Default false: pass name-only basics + empty propertyNames EntityList (docs/agent/query_with_total_count.md §10)."
      },
      "query" : {
        "type" : "object",
        "description" : "Optional platform query object (filters/sorts) — ThingWorx-shaped Query dialect / query-spec subset (see docs/agent/query_capability.md). Only Shape/Template fields for Optimized."
      },
      "modelTags" : {
        "type" : "array",
        "items" : {
          "type" : "object",
          "properties" : {
            "vocabulary" : {
              "type" : "string"
            },
            "vocabularyTerm" : {
              "type" : "string"
            }
          },
          "required" : [ "vocabulary", "vocabularyTerm" ]
        },
        "description" : "Optional platform Model tags on the service `tags` parameter (AND). For tag-based row filters inside a query object use the `query` parameter instead — different mechanism."
      },
      "hierarchyNodeId" : {
        "type" : "string",
        "description" : "Optional **hierarchy node id** (NetworkID) — **direct path** from Host Context or the page; calls **GetAssetList(hierarchyNodeId)** without **ResolveNetworkID**. Precedence after explicit **intersectThingNames**: **hierarchyNodeId** before **hierarchyNodeName**. Failures: **HIERARCHY_ASSET_LIST_FAILED** / **HIERARCHY_SCOPED_EMPTY** — **no** fallback to **hierarchyNodeName**."
      },
      "hierarchyNodeName" : {
        "type" : "string",
        "description" : "Optional **hierarchy node display-name fragment** from the **user message / conversation** (e.g. region or site). **Absolute first priority** for hierarchy-scoped intersect: when non-blank, the server calls **ResolveNetworkID(hierarchyNodeName)** then **GetAssetList** on the **single** resolved **NetworkID** (**0** rows → **HIERARCHY_RESOLVE_NOT_FOUND**; **2+** → **HIERARCHY_RESOLVE_AMBIGUOUS**; service failure → **HIERARCHY_RESOLVE_FAILED**). Omit when no hierarchy scope applies."
      },
      "intersectThingNames" : {
        "type" : "array",
        "maxItems" : 5000,
        "items" : {
          "type" : "string"
        },
        "description" : "Optional Thing-name set **B**: keep rows whose **name** is in the set (String.equals). **preIntersectMatchCount** = this QIT page count before ∩. Adds **queryHasMore**, **expandHasMore**, **hasMore** (API_CONTRACT / entity-hierarchy §6)."
      },
      "intersectExpandHasMore" : {
        "type" : "boolean",
        "description" : "When **intersectThingNames** is used: sets **expandHasMore** (expand side may list more Things). **hasMore** ORs **queryHasMore** (QIT / internal listing truncation) with **expandHasMore**. Default false."
      }
    },
    "required" : [ "entityType" ]
  },
  "name" : "query_entities",
  "description" : "List Things implementing a ThingTemplate or ThingShape. Model keys (**Stream**, **DataTable**, …) map to templates/shapes (key-resolution.md Phase 0.5). For taxonomy-scoped asset-class lists use **resolve_asset_type** then **query_entities_by_taxonomy** — taxonomy first, not template guesswork. Provide **exactly one** of thingTemplate or thingShape (both or neither → BOTH_TEMPLATE_AND_SHAPE / MISSING_TEMPLATE_OR_SHAPE). Root JSON Schema is provider-safe; mutual exclusion is not expressed as a root-level `oneOf`. Default columns are lean (name only); widen via includeDescription/includeTags/widePropertyColumns. Uses QueryImplementingThingsOptimizedWithTotalCount when available. Optional modelTags, query filters, **hierarchyNodeId**/**hierarchyNodeName** (GetAssetList intersect), and **intersectThingNames**. **totalRows** reflects full match count when known (totalRowsInferred when inferred). Large sets cache like fetch_cached_result. Not fuzzy search — use spotlight_search."
}
```

### query_entities_by_taxonomy

Size chars: `3435`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "EntityType" : {
        "type" : "string",
        "description" : "Exactly \"ThingTemplate\" or \"ThingShape\" (case-sensitive). Only one parent dimension per call."
      },
      "EntityName" : {
        "type" : "string",
        "description" : "ThingTemplate or ThingShape name. Prefer the **exact** strings returned by **resolve_asset_type** when the user refers to an application asset class. Existence is not pre-checked; invalid names fail at runtime."
      },
      "CriticalProperties" : {
        "type" : "string",
        "description" : "Semicolon-separated property names (trimmed; empty segments dropped). These columns are included in the projected rootEntityList (along with name). Duplicate \"name\" is allowed."
      },
      "AdditionalProperties" : {
        "type" : "string",
        "description" : "Optional extra semicolon-separated property names; same parsing rules as CriticalProperties."
      },
      "LookupProperties" : {
        "type" : "object",
        "description" : "JSON object: propertyName → value. A Thing matches if **any** entry matches the live property value (**OR**). Exact match only (no wildcards/regex yet). Omit or {} to skip lookup filtering."
      },
      "hierarchyNodeId" : {
        "type" : "string",
        "description" : "Optional **hierarchy node id** (NetworkID) — **direct path** from Host Context or the page; calls **GetAssetList(hierarchyNodeId)** without **ResolveNetworkID**. Precedence after explicit **intersectThingNames**: **hierarchyNodeId** before **hierarchyNodeName**. Failures: **HIERARCHY_ASSET_LIST_FAILED** / **HIERARCHY_SCOPED_EMPTY** — **no** fallback to **hierarchyNodeName**."
      },
      "hierarchyNodeName" : {
        "type" : "string",
        "description" : "Optional **hierarchy node display-name fragment** from the **user message / conversation** (e.g. region or site). **Absolute first priority** for hierarchy-scoped intersect: when non-blank, the server calls **ResolveNetworkID(hierarchyNodeName)** then **GetAssetList** on the **single** resolved **NetworkID** (**0** rows → **HIERARCHY_RESOLVE_NOT_FOUND**; **2+** → **HIERARCHY_RESOLVE_AMBIGUOUS**; service failure → **HIERARCHY_RESOLVE_FAILED**). Omit when no hierarchy scope applies."
      },
      "intersectThingNames" : {
        "type" : "array",
        "maxItems" : 5000,
        "items" : {
          "type" : "string"
        },
        "description" : "Optional Thing-name set **B**: keep rows whose **name** is in the set (String.equals). **preIntersectMatchCount** = full post-LookupProperties count before ∩. Adds **queryHasMore**, **expandHasMore**, **hasMore** (API_CONTRACT / entity-hierarchy §6)."
      },
      "intersectExpandHasMore" : {
        "type" : "boolean",
        "description" : "When **intersectThingNames** is used: sets **expandHasMore** (expand side may list more Things). **hasMore** ORs **queryHasMore** (QIT / internal listing truncation) with **expandHasMore**. Default false."
      }
    },
    "required" : [ "EntityType", "EntityName" ]
  },
  "name" : "query_entities_by_taxonomy",
  "description" : "List Things under one ThingTemplate or ThingShape with taxonomy column projection. Use **resolve_asset_type** first for asset-type text — copy **entityType**/**entityName** (do not invent parents). Join resolver **criticalProperties** into **CriticalProperties** (semicolon-separated) when projecting names/columns. QIT listing + LookupProperties OR-filter; success per AGENT-TAXONOMY.md §5.2.1 (EMPTY | INLINE | LARGE; LARGE adds cacheId + sample). Success JSON uses **totalCount** (filtered count), not **totalRows**. Optional **hierarchyNodeName**, **intersectThingNames** / **intersectExpandHasMore** (same as query_entities). LARGE uses session cache like fetch_cached_result."
}
```

### list_asset_types

Size chars: `991`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "maxItems" : {
        "type" : "integer",
        "description" : "Optional page size (default 500, hard cap 500). Omitted maxItems still uses the default cap — the full catalog is never dumped inline. When truncated, success includes hasMore=true, resultKind=ASSET_TYPES_LARGE, and totalCount of the full catalog."
      }
    }
  },
  "name" : "list_asset_types",
  "description" : "List application asset types from `/taxonomies/asset-types.json` when that v3 object map is configured and loaded (v3 identity array may be absent). For v2 object identity-types.json (version 2) this lists flattened rows from that file. Use when the user asks how many asset types exist or wants the catalog without guessing ThingTemplates. Always bounded: default **maxItems=500** (hard cap 500) even when the argument is omitted. Truncated catalogs return **ASSET_TYPES_LARGE** with **hasMore**; **totalCount** remains the full catalog size. Success echoes **maxItemsRequested** / **maxItemsEffective**."
}
```

### resolve_asset_type

Size chars: `436`

```json
{
  "input_schema" : {
    "required" : [ "text" ],
    "properties" : {
      "text" : {
        "type" : "string",
        "description" : "User-facing asset type phrase to resolve to a configured asset type key and ThingWorx parent."
      }
    },
    "type" : "object"
  },
  "name" : "resolve_asset_type",
  "description" : "Map user asset type text to an asset type key, ThingTemplate/ThingShape parent, and **criticalProperties** name list. Call **resolve_thing** when the user names a specific asset instance."
}
```

### resolve_thing

Size chars: `678`

```json
{
  "input_schema" : {
    "required" : [ "text" ],
    "properties" : {
      "text" : {
        "type" : "string",
        "description" : "User-facing identifier (display name, serial, suffix, or canonical Thing name)."
      },
      "assetTypeKey" : {
        "type" : "string",
        "description" : "Optional exact **key** from **list_asset_types** / asset-types.json to narrow identity rules to that asset class."
      }
    },
    "type" : "object"
  },
  "name" : "resolve_thing",
  "description" : "Resolve user text to a unique canonical Thing name using v3 identity-types.json array rules. Requires at least one loaded v3 identity rule (asset-types.json is optional unless you pass **assetTypeKey**). Optional **assetTypeKey** narrows matching rules when asset-type rows are loaded."
}
```

### list_entities_by_type

Size chars: `2544`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "entityCollectionType" : {
        "type" : "string",
        "description" : "ThingWorx collection type string (e.g. ThingTemplate, ThingShape, Mashup, DataShape, User). NOT Thing — use query_entities or spotlight_search."
      },
      "nameMask" : {
        "type" : "string",
        "description" : "Optional name pattern. Semantics depend on useRegEx: SQL LIKE vs regex."
      },
      "useRegEx" : {
        "type" : "boolean",
        "description" : "If true, calls GetEntityListByRegEx; if false, GetEntityList (SQL LIKE). Default false."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Max rows (default 50, max 200). Platform may stop earlier when enough matches."
      },
      "tags" : {
        "type" : "array",
        "items" : {
          "type" : "object",
          "properties" : {
            "vocabulary" : {
              "type" : "string"
            },
            "vocabularyTerm" : {
              "type" : "string"
            }
          },
          "required" : [ "vocabulary", "vocabularyTerm" ]
        },
        "description" : "Optional Model tags filter (AND). Same as invoke_service TAGS: [{\"vocabulary\":\"...\",\"vocabularyTerm\":\"...\"}]. Omit = no tag filter."
      }
    },
    "required" : [ "entityCollectionType" ]
  },
  "name" : "list_entities_by_type",
  "description" : "List metadata entities of a given collection type via Resource EntityServices (GetEntityList or GetEntityListByRegEx). Never pass **`entityCollectionType=Stream`**, **`DataTable`**, or **`ValueStream`** when the user asks how many/list **Things** — those are **`query_entities`** **thingTemplate**/ **thingShape** keys, not **`GetEntityList`** **`type`** strings (success would mislead toward zero). If this tool returns **`code=ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY`**, **immediately** call **`query_entities`** again using **`repair.arguments`** **before** telling the user the count is zero. **Repair trigger (S1, documented):** when the platform rejects the collection type, the executor detects English failure text containing both **invalid** and **entity** and **type** (substring match on the platform message — not a structured fault code) and may emit the Phase 0.5 **repair** envelope via GenericThing.GetIncomingDependencies. Treat that as a retry signal, not as proof of an empty catalog. This is **not** a substitute for **list_asset_types** / **resolve_asset_type**: do not use name-mask listing here to answer “how many asset types” or to invent **AssetType** inventory when application taxonomy is available (see llm_tool_routing_guide.txt **Asset taxonomy**). For Thing instances under a template/shape use query_entities instead. Optional tags filter uses platform Model tags (empty = match all). Large results use the same session cache as invoke_service / fetch_cached_result."
}
```

### get_property_values

Size chars: `1166`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
      },
      "propertyNames" : {
        "type" : "array",
        "maxItems" : 40,
        "items" : {
          "type" : "string"
        },
        "description" : "Property names to read (max 40)"
      }
    },
    "required" : [ "thingName", "propertyNames" ]
  },
  "name" : "get_property_values",
  "description" : "Read current values of multiple Thing properties in one call (batch, max 40). **thingName** must be a canonical ThingWorx name; non-canonical labels return **IDENTITY_RESOLUTION_REQUIRED**; **`recoveryHint`** (to **resolve_thing**) is included only when v3 identity rules are loaded for this agent turn. propertyNames must be exact ThingWorx property names; use discover_thing_members (facet=properties or facet=property) first when only a business label is known. Returns per-property ok/value or error; PROPERTY_METADATA_UNRESOLVED means the name was not resolved and no value was read."
}
```

### query_property_history

Size chars: `4173`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
      },
      "propertyName" : {
        "type" : "string",
        "description" : "Property name. The server resolves the base type: NUMBER/INTEGER/LONG use the numeric trend path (optional aggregates + Parler auto chart when applicable); other logged types use value-stream QueryPropertyHistory with bounded compact evidence (no auto chart in 0.1.162)."
      },
      "startTime" : {
        "type" : "string",
        "description" : "Start time (ISO-8601). Omit with endTime for platform default window. Alias: start."
      },
      "endTime" : {
        "type" : "string",
        "description" : "End time (ISO-8601). Optional. Alias: end."
      },
      "calendarPhrase" : {
        "type" : "string",
        "description" : "Optional: **today** / **yesterday** / **tomorrow** (single day, user's **user_timezone** IANA). Mutually exclusive with startTime/endTime and relativeDuration."
      },
      "relativeDuration" : {
        "type" : "string",
        "description" : "Optional: duration ending **now** (e.g. **30m**, **24h**) — closed-open semantics per `docs/agent/time-interpretation.md`. Mutually exclusive with startTime/endTime and calendarPhrase."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Max rows to read from the platform history service (default 1000, cap 5000). Aliases (still accepted): maxRows, maxPoints — when several are present, precedence is maxItems > maxRows > maxPoints. Success extras echo maxItemsRequested / maxItemsEffective. LLM-visible success bodies are always compact (sampleRows + cacheId; aggregates when requested)."
      },
      "actions" : {
        "type" : "array",
        "items" : {
          "type" : "string"
        },
        "description" : "Optional aggregate actions for **numeric** properties only (mean, min, max, sum, stddev, variance, median, count, first, last). Empty or omit for raw series. **Non-numeric:** omit actions — non-empty actions return NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE."
      },
      "y_reference_lines" : {
        "type" : "array",
        "maxItems" : 12,
        "description" : "Optional horizontal Y-axis lines (SPC limits, thresholds). Drawn on auto chart wire frame (numeric path).",
        "items" : {
          "type" : "object",
          "properties" : {
            "y" : {
              "type" : "number"
            },
            "label" : {
              "type" : "string"
            },
            "role" : {
              "description" : "usl/lsl = spec limits; ucl/lcl = control limits; target = center line; limit = generic; warning = advisory.",
              "enum" : [ "usl", "ucl", "lcl", "lsl", "target", "limit", "warning" ],
              "type" : "string"
            }
          },
          "required" : [ "y" ]
        }
      },
      "kind" : {
        "description" : "Chart kind for automatic chart frame on numeric trends (default line).",
        "enum" : [ "line", "bar", "scatter" ],
        "type" : "string"
      },
      "title" : {
        "type" : "string",
        "description" : "Optional chart title override (numeric path)."
      },
      "x_label" : {
        "type" : "string",
        "description" : "Optional X-axis label (numeric path)."
      },
      "y_label" : {
        "type" : "string",
        "description" : "Optional Y-axis label (numeric path)."
      },
      "requestedTimeRange" : {
        "type" : "object",
        "properties" : {
          "start" : {
            "type" : "string",
            "description" : "ISO-8601 instant (UTC …Z recommended). Chart X-axis span when query bounds omitted."
          },
          "end" : {
            "type" : "string",
            "description" : "ISO-8601 instant (UTC …Z recommended)."
          }
        },
        "description" : "Optional chart window for the auto ChartBlock when startTime/endTime are not passed (numeric path). Prefer always setting startTime and endTime so the query and chart share the same bounds."
      }
    },
    "required" : [ "thingName", "propertyName" ]
  },
  "name" : "query_property_history",
  "description" : "Query time-series history for a Thing property. **thingName** must be canonical (else **IDENTITY_RESOLUTION_REQUIRED**; **recoveryHint** to **resolve_thing** when v3 rules loaded). Server picks numeric vs value-stream path from property metadata. Prefer ISO **startTime**/**endTime** (UTC …Z) so chart and query bounds align; or **calendarPhrase** / **relativeDuration** when ISO omitted. Numeric: optional **actions** + auto chart wire. Non-numeric: compact **VALUE_STREAM_HISTORY_INLINE** (sampleRows + **cacheId**, no points array). Value-stream uses **oldestFirst=true**; **sampleRows** are first N ascending rows (**historyOrder=oldest_first**)."
}
```

### query_stream_data

Size chars: `1962`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result. Must name a **Stream** Thing (implements Stream / RemoteStream QueryStreamData)."
      },
      "startTime" : {
        "type" : "string",
        "description" : "Start time (ISO-8601). Omit both bounds for platform-default window. Alias: start."
      },
      "endTime" : {
        "type" : "string",
        "description" : "End time (ISO-8601). Alias: end."
      },
      "calendarPhrase" : {
        "type" : "string",
        "description" : "Optional: **today** / **yesterday** / **tomorrow** (user **user_timezone** IANA). Mutually exclusive with startTime/endTime and relativeDuration."
      },
      "relativeDuration" : {
        "type" : "string",
        "description" : "Optional: duration ending **now** (e.g. **30m**, **24h**). Mutually exclusive with ISO bounds and calendarPhrase."
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Cap rows returned (default 500, max 5000). Maps to QueryStreamData maxItems."
      },
      "oldestFirst" : {
        "type" : "boolean",
        "description" : "Sort oldest-first when true (default false)."
      },
      "source" : {
        "type" : "string",
        "description" : "Optional stream entry source filter (QueryStreamData **source** parameter)."
      }
    },
    "required" : [ "thingName" ]
  },
  "name" : "query_stream_data",
  "description" : "Query tabular stream rows via platform **QueryStreamData** on a Stream Thing. **thingName** uses the same canonical Thing preflight as other scalar Thing tools (non-canonical → **IDENTITY_RESOLUTION_REQUIRED** with optional **recoveryHint**). Prefer this over invoke_service for natural-language time windows: **calendarPhrase**, **relativeDuration**, or ISO **startTime**/**endTime** (aliases start/end) — same resolver as query_property_history. Large results use the INFOTABLE_LARGE envelope (sampleRows; cacheId when server cached)."
}
```

### query_alert_summary

Size chars: `2428`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingNames" : {
        "type" : "array",
        "minItems" : 1,
        "maxItems" : 25,
        "items" : {
          "type" : "string",
          "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
        },
        "description" : "One or more canonical ThingWorx names. Pass a single-element array for one Thing. Non-canonical names are reported per-Thing in identityErrors[] (partial success when at least one resolves). Maximum 25 names per call."
      },
      "ackState" : {
        "description" : "Default all. Use unacknowledged for active-only.",
        "enum" : [ "all", "acknowledged", "unacknowledged" ],
        "type" : "string"
      },
      "propertyName" : {
        "type" : "string",
        "description" : "Optional: filter to one source property."
      },
      "alertName" : {
        "type" : "string",
        "description" : "Optional QUERY EQ on alert name."
      },
      "alertType" : {
        "type" : "string",
        "description" : "Optional QUERY EQ on alertType."
      },
      "priorityMin" : {
        "type" : "integer"
      },
      "priorityMax" : {
        "type" : "integer"
      },
      "sort" : {
        "description" : "Optional QUERY sort for summary rows. Mutually exclusive with **advancedQuery.sorts**.",
        "enum" : [ "default", "timestamp_asc", "timestamp_desc", "priority_asc", "priority_desc" ],
        "type" : "string"
      },
      "limit" : {
        "type" : "integer",
        "description" : "maxItems (default 100, max 500)."
      },
      "advancedQuery" : {
        "type" : "string",
        "description" : "Optional raw ThingWorx-shaped Query dialect JSON (query-spec subset) merged AND with typed filters. See docs/agent/query_capability.md."
      }
    },
    "required" : [ "thingNames" ]
  },
  "name" : "query_alert_summary",
  "description" : "Current alert **summary** for one or more Things via Resource AlertFunctions.QueryAlertSummaryForThing (in-memory snapshot, not history). **thingNames** must be canonical ThingWorx names; non-canonical labels return per-Thing **IDENTITY_RESOLUTION_REQUIRED** entries when other Things succeed. Multiple Things return **ALERT_SUMMARY_MULTI** rollups (counts + topAlerts); a single Thing returns the usual INFOTABLE envelope. **`recoveryHint`** (to **resolve_thing**) is included only when v3 identity rules are loaded for this agent turn. Optional **sort** maps to QUERY **sorts** (not combinable with **advancedQuery.sorts**). Large single-Thing tables use INFOTABLE_LARGE (sampleRows; cacheId when server cached). Prefer over invoke_service for correct parameter mapping."
}
```

### query_alert_history

Size chars: `2532`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
      },
      "startTime" : {
        "type" : "string",
        "description" : "ISO-8601 start; optional (default window applied)."
      },
      "endTime" : {
        "type" : "string",
        "description" : "ISO-8601 end; optional (defaults to now)."
      },
      "calendarPhrase" : {
        "type" : "string",
        "description" : "Optional English phrase naming **one** local calendar day: **today**, **yesterday**, or **tomorrow** (word-boundary match). Uses host **user_timezone** (IANA). Mutually exclusive with startTime/endTime and relativeDuration."
      },
      "relativeDuration" : {
        "type" : "string",
        "description" : "Optional Parler duration ending **now** (e.g. **30m**, **24h**, **7d**) — closed-open window per `docs/agent/time-interpretation.md` §4.4. Mutually exclusive with startTime/endTime and calendarPhrase."
      },
      "alertName" : {
        "type" : "string"
      },
      "propertyName" : {
        "type" : "string",
        "description" : "QUERY EQ on sourceProperty."
      },
      "alertType" : {
        "type" : "string"
      },
      "priorityMin" : {
        "type" : "integer"
      },
      "priorityMax" : {
        "type" : "integer"
      },
      "order" : {
        "description" : "Optional sort direction (newest events first vs oldest first). Prefer this over legacy replay-only boolean sort aliases.",
        "enum" : [ "newest_first", "oldest_first" ],
        "type" : "string"
      },
      "limit" : {
        "type" : "integer",
        "description" : "maxItems (default 100, max 500)."
      },
      "advancedQuery" : {
        "type" : "string",
        "description" : "Optional raw ThingWorx-shaped Query dialect JSON (query-spec subset) merged AND with typed filters. See docs/agent/query_capability.md."
      }
    },
    "required" : [ "thingName" ]
  },
  "name" : "query_alert_history",
  "description" : "Alert **history** timeline for one Thing via AlertFunctions.QueryAlertHistory. **thingName** must be canonical; non-canonical labels return **IDENTITY_RESOLUTION_REQUIRED**; **`recoveryHint`** (to **resolve_thing**) is included only when v3 identity rules are loaded for this agent turn. Always bounded; response includes appliedStartTime/appliedEndTime, timeRangeSource, optional appliedTimePreset / implicitDefaultWindowDays, sort order, and **historyQueryResource** (resource-backed path; uses platform summary-manager filtered stream per installed server). Prefer **startTime**/**endTime** (ISO-8601), or **calendarPhrase** / **relativeDuration** when bounds are omitted; prefer **order** for sort direction."
}
```

### acknowledge_alerts

Size chars: `1723`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thingName" : {
        "type" : "string",
        "description" : "Canonical ThingWorx Thing name (exact platform **Thing** name). If the user supplied a display label, serial number, suffix, or any uncertain asset identifier, call **resolve_thing** first (v3 identity taxonomy), then retry with **matches[0].name** from a **UNIQUE** result."
      },
      "mode" : {
        "description" : "Default specific_alerts: summary probe then AcknowledgeAlertFromSummary; when exactly one unacked row matches propertyName without alertName, may use narrow AcknowledgeAlert (same property scope only).",
        "enum" : [ "specific_alerts", "property_all" ],
        "type" : "string"
      },
      "propertyName" : {
        "type" : "string",
        "description" : "Required for specific_alerts and property_all."
      },
      "alertName" : {
        "type" : "string",
        "description" : "Optional: narrow specific_alerts to one alert name."
      },
      "message" : {
        "type" : "string",
        "description" : "Optional ack message."
      }
    },
    "required" : [ "thingName" ]
  },
  "name" : "acknowledge_alerts",
  "description" : "Acknowledge alerts on AlertFunctions. **thingName** must be a canonical ThingWorx name; non-canonical labels return **IDENTITY_RESOLUTION_REQUIRED** before any acknowledge or summary probe (**no** side effects on preflight failure). Default **specific_alerts** requires propertyName; **property_all** is explicit bulk on that property. Success JSON may include **platformAckService**, **countsAvailable** (boolean), **ackMessage** (echo of optional **message** argument), and **note**; **specific_alerts** summary probe (**QueryAlertSummaryForThing**) and ack service failures return **status** error with normalized **code** (e.g. PLATFORM_ALERT_PERMISSION). empty **specific_alerts** uses **message** for the human-readable status line."
}
```

### set_property_value

Size chars: `848`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "thing_name" : {
        "type" : "string",
        "description" : "Target Thing name"
      },
      "property_name" : {
        "type" : "string",
        "description" : "Property name"
      },
      "base_type" : {
        "type" : "string",
        "description" : "ThingWorx BaseType enum (STRING, NUMBER, INTEGER, LONG, BOOLEAN, DATETIME, …). Omit only if unsure — server may infer from property metadata."
      },
      "value" : {
        "anyOf" : [ {
          "type" : "string"
        }, {
          "type" : "number"
        }, {
          "type" : "integer"
        }, {
          "type" : "boolean"
        } ],
        "description" : "Literal matching base_type (STRING→string, NUMBER→number, BOOLEAN→boolean, DATETIME→ISO-8601 string)."
      }
    },
    "required" : [ "thing_name", "property_name", "value" ]
  },
  "name" : "set_property_value",
  "description" : "Request to write a Thing property. Requires human approval on Parler AlwaysOn before execution. Always set base_type when known (discover_thing_members / get_property_values)."
}
```

### spotlight_search

Size chars: `542`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "query" : {
        "type" : "string",
        "description" : "Spotlight search text (names, descriptions, etc.)"
      },
      "maxItems" : {
        "type" : "integer",
        "description" : "Max hits (default 30, max 100)"
      }
    },
    "required" : [ "query" ]
  },
  "name" : "spotlight_search",
  "description" : "Fuzzy search across ThingWorx metadata via platform SearchFunctions.SpotlightSearchV2 (same as REST Spotlight; invoked in-JVM, not HTTP). For structured filters use query_entities. Type filters via entityTypes are **not supported** in this release — omit them."
}
```

### search_document_chunks

Size chars: `1516`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "query" : {
        "type" : "string",
        "description" : "Natural-language query built from the user question or normalized health issue."
      },
      "signals" : {
        "type" : "array",
        "description" : "Optional alarms, properties, symptoms, or components.",
        "items" : {
          "type" : "object",
          "properties" : {
            "kind" : {
              "type" : "string"
            },
            "name" : {
              "type" : "string"
            },
            "value" : {
              "type" : "string"
            }
          }
        }
      },
      "assetContext" : {
        "type" : "object",
        "description" : "Optional asset context such as asset model, component, or document type hints."
      },
      "documentTypes" : {
        "type" : "array",
        "description" : "Optional document type filters such as operations_manual or troubleshooting_guide.",
        "items" : {
          "type" : "string"
        }
      },
      "documentIds" : {
        "type" : "array",
        "description" : "Optional explicit document id filter. When resolve_document_set returns a non-empty documents[], pass those documents[].documentId values here to scope this search to the resolved set (selectionMode documentIds-filter).",
        "items" : {
          "type" : "string"
        }
      },
      "limit" : {
        "type" : "integer",
        "description" : "Maximum matches to return. Clamped to configured bounds."
      }
    },
    "required" : [ ]
  },
  "name" : "search_document_chunks",
  "description" : "Find document chunks relevant to a health issue, alarm, component, or user question. Use after live status is known when the user needs manual/troubleshooting guidance. When resolve_document_set returned a non-empty documents[], pass those ids as documentIds to scope this search to the resolved set. Returns ranked metadata and snippets; call get_document_chunk for full markdown to cite."
}
```

### get_document_chunk

Size chars: `527`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "docId" : {
        "type" : "string"
      },
      "chunkId" : {
        "type" : "string"
      }
    },
    "required" : [ "docId", "chunkId" ]
  },
  "name" : "get_document_chunk",
  "description" : "Fetch full markdown and FileRepository source links for one chunk from search results. sourceLinks[].href is the canonical clickable PDF target (includes #page= when applicable); when citing this chunk in the final answer, copy each href into a markdown link [label](href). Do not cite manual text unless it came from this tool or a search snippet."
}
```

### resolve_document_set

Size chars: `759`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "key" : {
        "type" : "string",
        "description" : "Asset/Thing identifier to scope retrieval — typically the bound Thing's asset model or name."
      }
    },
    "required" : [ "key" ]
  },
  "name" : "resolve_document_set",
  "description" : "Resolve the bounded set of documents that apply to a given asset, to scope document search before search_document_chunks. Pass the asset model or Thing name as key. Returns documents[] (the in-scope document ids) and a resolverSource diagnostic. When documents is non-empty, pass documents[].documentId as the documentIds argument to search_document_chunks to scope retrieval. If documents is empty (resolverSource default-empty), no confident scope was found; proceed with a normal search_document_chunks call."
}
```

### get_agent_skill

Size chars: `866`

```json
{
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "skill_name" : {
        "type" : "string",
        "description" : "Short skill id of a **registered** agent skill. Skills are loaded from the configured `configurationRepository` FileRepository (`/skills/<id>/SKILL.md`). The per-turn catalog lists each skill’s `source` (always `repository` when present). Pass the short id only (same token as `/SkillName` in the user message), never the full Service name. Example ids: OrderWorkflow, alert_query."
      }
    },
    "required" : [ "skill_name" ]
  },
  "name" : "get_agent_skill",
  "description" : "Loads the full instructions text for a registered agent skill (Service or repository source). The chat system prompt lists skills with metadata only; call this tool to retrieve the complete body when relevant. On success the result is the skill body string; on failure a JSON object with `status`, `code`, and `message`."
}
```

### start_playbook

Registry-driven at runtime when the playbook catalog is loaded. Example wire shape (two loaded ids `alpha`, `beta`):

```json
{
  "name" : "start_playbook",
  "description" : "Start a registered Playbook workflow with structured parameters. See the per-turn Agent playbooks catalog for titles and routing hints.\n• alpha: When alpha\n• beta: When beta",
  "input_schema" : {
    "type" : "object",
    "properties" : {
      "playbook_id" : {
        "type" : "string",
        "description" : "Registered playbook id. Loaded ids: alpha, beta."
      },
      "params" : {
        "type" : "object",
        "description" : "Inputs for the selected playbook_id per that playbook's inputSchema; invalid params fail at start_playbook.",
        "additionalProperties" : true
      }
    },
    "required" : [ "playbook_id", "params" ]
  }
}
```

Per-playbook `inputSchema` validation happens at execution time; the advertised schema uses a generic `params` object only.

## Repository Extended Tool Payloads

Canonical manifest for the **current** utilization training bundle: **`dev_data/scpa_utilization/tools/extended_tools.json`** (**4** tools, post-LLM-friendly / Day 4). Reconstruct per-tool wire objects at deploy time (sizes are not fixed in this doc).

### Current utilization tools (4 — post-LLM-friendly)

| Tool | Target service | Role |
| --- | --- | --- |
| `list_utilization_machines` | `SCPA_Utilization_helper.ListUtilizationMachines` | Machine coverage before records/summaries |
| `get_utilization_records` | `GetUtilizationRecords` | Raw event rows (optional single-machine scope) |
| `get_utilization_state_summary` | `GetUtilizationStateSummary` | Aggregate / percent-by-state questions |
| `get_utilization_overview` | `GetUtilizationOverview` | Combined coverage + state summary evidence |

Stage contracts: **`training-stage-configuration-contracts.md`**. Training-material mirror: **`training/workshop/day4/tools/extended_tools.json`**.

### Historical utilization tools (7 — pre-LLM-friendly training)

**Historical evidence only** — these names powered population **(d)** / the 2026-06-28 incident measurement. Early workshop stages used the seven-tool surface before the LLM-friendly consolidation to the four-tool manifest above. Payload sections below are **frozen incident snapshots**, not the current **`dev_data/scpa_utilization`** SoT.

### utilization_records

Size chars: `1386`

```json
{
  "name": "utilization_records",
  "description": "Use when the user asks for raw utilization event records for all utilization-capable machines over a time range. Provide StartDate and EndDate as ISO-8601 datetimes; ShiftID is optional when the user scopes by shift. Title: Utilization records Natural-time fields **calendarPhrase** (today/yesterday/tomorrow) and **relativeDuration** (e.g. 30m, 24h) are available as alternatives to StartDate/EndDate; set at most one and do not combine with the explicit pair (same contract as query_alert_history / query_numeric_property_history).",
  "input_schema": {
    "type": "object",
    "properties": {
      "StartDate": {
        "type": "string"
      },
      "ShiftID": {
        "type": "string"
      },
      "EndDate": {
        "type": "string"
      },
      "calendarPhrase": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: **today** / **yesterday** / **tomorrow** (single day, host user_timezone IANA). Mutually exclusive with StartDate/EndDate and relativeDuration. Resolved to the closed-open day window per docs/agent/time-interpretation.md §4.2."
      },
      "relativeDuration": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: closed-open duration ending **now** (e.g. **30m**, **24h**, **7d**). Mutually exclusive with StartDate/EndDate and calendarPhrase. Same grammar as built-in tools — see docs/agent/time-interpretation.md §5."
      }
    },
    "required": [
      "ShiftID"
    ]
  }
}
```

### utilization_records_by_machine

Size chars: `1537`

```json
{
  "name": "utilization_records_by_machine",
  "description": "Use when the user asks for raw utilization event records for one specific machine over a time range. **Machine** must be the canonical ThingWorx Thing name of the machine (not a display name, short equipment label, alias, or serial). Provide StartDate and EndDate as ISO-8601 datetimes; ShiftID is optional. Title: Utilization records by machine Natural-time fields **calendarPhrase** (today/yesterday/tomorrow) and **relativeDuration** (e.g. 30m, 24h) are available as alternatives to StartDate/EndDate; set at most one and do not combine with the explicit pair (same contract as query_alert_history / query_numeric_property_history).",
  "input_schema": {
    "type": "object",
    "properties": {
      "StartDate": {
        "type": "string"
      },
      "ShiftID": {
        "type": "string"
      },
      "EndDate": {
        "type": "string"
      },
      "Machine": {
        "type": "string"
      },
      "calendarPhrase": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: **today** / **yesterday** / **tomorrow** (single day, host user_timezone IANA). Mutually exclusive with StartDate/EndDate and relativeDuration. Resolved to the closed-open day window per docs/agent/time-interpretation.md §4.2."
      },
      "relativeDuration": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: closed-open duration ending **now** (e.g. **30m**, **24h**, **7d**). Mutually exclusive with StartDate/EndDate and calendarPhrase. Same grammar as built-in tools — see docs/agent/time-interpretation.md §5."
      }
    },
    "required": [
      "ShiftID",
      "Machine"
    ]
  }
}
```

### utilization_aggregate_by_state

Size chars: `1170`

```json
{
  "name": "utilization_aggregate_by_state",
  "description": "Use after retrieving utilization records when the user asks for utilization grouped by state, total duration, counts, averages, min/max duration, or duration percentage. Title: Utilization aggregate by state",
  "input_schema": {
    "type": "object",
    "properties": {
      "UtilizationRecords": {
        "type": "array",
        "description": " Each array element is one table row. Column keys and types below.",
        "items": {
          "type": "object",
          "properties": {
            "Comment": {
              "type": "string"
            },
            "EquipmentDesc": {
              "type": "string"
            },
            "EquipmentID": {
              "type": "string"
            },
            "ShiftID": {
              "type": "string"
            },
            "Duration": {
              "type": "number"
            },
            "ProductID": {
              "type": "string"
            },
            "OperatorID": {
              "type": "string"
            },
            "ModifiedBy": {
              "type": "string"
            },
            "ReasonGroup": {
              "type": "string"
            },
            "Reason": {
              "type": "string"
            },
            "ModifiedAt": {
              "type": "string"
            },
            "DurationString": {
              "type": "string"
            },
            "UtilizationState": {
              "type": "string"
            },
            "id": {
              "type": "string"
            },
            "EventStart": {
              "type": "string"
            }
          },
          "required": [
            "Comment",
            "EquipmentDesc",
            "EquipmentID",
            "ShiftID",
            "Duration",
            "ProductID",
            "OperatorID",
            "ModifiedBy",
            "ReasonGroup",
            "Reason",
            "ModifiedAt",
            "DurationString",
            "UtilizationState",
            "id",
            "EventStart"
          ]
        }
      }
    },
    "required": [
      "UtilizationRecords"
    ]
  }
}
```

### utilization_stats_for_aggregate

Size chars: `1037`

```json
{
  "name": "utilization_stats_for_aggregate",
  "description": "Use after aggregating utilization records by state when the user asks for overall utilization percent, uptime, downtime, total time, event count, or average event duration. Title: Utilization stats for aggregate data",
  "input_schema": {
    "type": "object",
    "properties": {
      "AggregatedByUtilizationStateData": {
        "type": "array",
        "description": " Each array element is one table row. Column keys and types below.",
        "items": {
          "type": "object",
          "properties": {
            "AVERAGE_Duration": {
              "type": "number"
            },
            "Percentage": {
              "type": "number"
            },
            "COUNT_Duration": {
              "type": "integer"
            },
            "SUM_Duration_Hours": {
              "type": "number"
            },
            "UtilizationState": {
              "type": "string"
            },
            "SUM_Duration": {
              "type": "number"
            },
            "SUM_Duration_Minutes": {
              "type": "number"
            },
            "MIN_Duration": {
              "type": "number"
            },
            "MAX_Duration": {
              "type": "number"
            }
          },
          "required": [
            "AVERAGE_Duration",
            "Percentage",
            "COUNT_Duration",
            "SUM_Duration_Hours",
            "UtilizationState",
            "SUM_Duration",
            "SUM_Duration_Minutes",
            "MIN_Duration",
            "MAX_Duration"
          ]
        }
      }
    },
    "required": [
      "AggregatedByUtilizationStateData"
    ]
  }
}
```

### utilization_machine_listing

Size chars: `1082`

```json
{
  "name": "utilization_machine_listing",
  "description": "Use when the user asks which machines are available for utilization reporting or needs a machine list before querying utilization records. Pass Machines only when the user already provided a candidate machine list; UsesSelection controls whether to use that selection. Title: Utilization machine listing",
  "input_schema": {
    "type": "object",
    "properties": {
      "UsesSelection": {
        "type": "boolean"
      },
      "Machines": {
        "type": "array",
        "description": " Each array element is one table row. Column keys and types below.",
        "items": {
          "type": "object",
          "properties": {
            "isSystemObject": {
              "type": "boolean",
              "description": "Indicates if a system object or not"
            },
            "name": {
              "type": "string",
              "description": "Entity name"
            },
            "description": {
              "type": "string",
              "description": "Entity description"
            },
            "homeMashup": {
              "type": "string",
              "description": "Home mashup"
            },
            "avatar": {
              "type": "string",
              "description": "Avatar image"
            },
            "tags": {
              "type": "string",
              "description": "Tags"
            }
          },
          "required": [
            "isSystemObject",
            "name",
            "description",
            "homeMashup",
            "avatar",
            "tags"
          ]
        }
      }
    },
    "required": [
      "UsesSelection",
      "Machines"
    ]
  }
}
```

### utilization_machine_listing_with_dates

Size chars: `2087`

```json
{
  "name": "utilization_machine_listing_with_dates",
  "description": "Use when the user asks for utilization-capable machines with effective start and end dates for a specific time range. Provide StartDate and EndDate as ISO-8601 datetimes; ShiftID is optional; pass Machines only when the user already supplied a candidate machine list. Title: Utilization machine listing with dates Natural-time fields **calendarPhrase** (today/yesterday/tomorrow) and **relativeDuration** (e.g. 30m, 24h) are available as alternatives to StartDate/EndDate; set at most one and do not combine with the explicit pair (same contract as query_alert_history / query_numeric_property_history).",
  "input_schema": {
    "type": "object",
    "properties": {
      "StartDate": {
        "type": "string"
      },
      "ShiftID": {
        "type": "string"
      },
      "EndDate": {
        "type": "string"
      },
      "Machines": {
        "type": "array",
        "description": " Each array element is one table row. Column keys and types below.",
        "items": {
          "type": "object",
          "properties": {
            "isSystemObject": {
              "type": "boolean",
              "description": "Indicates if a system object or not"
            },
            "name": {
              "type": "string",
              "description": "Entity name"
            },
            "description": {
              "type": "string",
              "description": "Entity description"
            },
            "homeMashup": {
              "type": "string",
              "description": "Home mashup"
            },
            "avatar": {
              "type": "string",
              "description": "Avatar image"
            },
            "tags": {
              "type": "string",
              "description": "Tags"
            }
          },
          "required": [
            "isSystemObject",
            "name",
            "description",
            "homeMashup",
            "avatar",
            "tags"
          ]
        }
      },
      "calendarPhrase": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: **today** / **yesterday** / **tomorrow** (single day, host user_timezone IANA). Mutually exclusive with StartDate/EndDate and relativeDuration. Resolved to the closed-open day window per docs/agent/time-interpretation.md §4.2."
      },
      "relativeDuration": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: closed-open duration ending **now** (e.g. **30m**, **24h**, **7d**). Mutually exclusive with StartDate/EndDate and calendarPhrase. Same grammar as built-in tools — see docs/agent/time-interpretation.md §5."
      }
    },
    "required": [
      "ShiftID",
      "Machines"
    ]
  }
}
```

### utilization_aggregate_by_state_time_fence

Size chars: `1438`

```json
{
  "name": "utilization_aggregate_by_state_time_fence",
  "description": "Use when the user asks for an overview of utilization aggregated by state across machines over a time range without first needing raw event rows. Provide StartDate and EndDate as ISO-8601 datetimes; ShiftID is optional. Title: Utilization aggregate by state over time range Natural-time fields **calendarPhrase** (today/yesterday/tomorrow) and **relativeDuration** (e.g. 30m, 24h) are available as alternatives to StartDate/EndDate; set at most one and do not combine with the explicit pair (same contract as query_alert_history / query_numeric_property_history).",
  "input_schema": {
    "type": "object",
    "properties": {
      "StartDate": {
        "type": "string"
      },
      "ShiftID": {
        "type": "string"
      },
      "EndDate": {
        "type": "string"
      },
      "calendarPhrase": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: **today** / **yesterday** / **tomorrow** (single day, host user_timezone IANA). Mutually exclusive with StartDate/EndDate and relativeDuration. Resolved to the closed-open day window per docs/agent/time-interpretation.md §4.2."
      },
      "relativeDuration": {
        "type": "string",
        "description": "Optional natural-time alternative to StartDate/EndDate: closed-open duration ending **now** (e.g. **30m**, **24h**, **7d**). Mutually exclusive with StartDate/EndDate and calendarPhrase. Same grammar as built-in tools — see docs/agent/time-interpretation.md §5."
      }
    },
    "required": [
      "ShiftID"
    ]
  }
}
```

---

## Addendum — `load_tool_schemas` meta-tool

The `lazy` tool-admission mode adds one **meta-tool**, `load_tool_schemas(names[])`, which is
**not** part of the population **(d)** measurement above and is **only advertised in `lazy` mode** (registered
executor-only, so it never appears under `off`/`narrow`). In `lazy`, the first request advertises
only the core set plus this meta-tool, whose description carries a size-capped catalog
(`name: whenToUse`) of the deferred tail; the model calls it to load the full schemas it needs,
which are then advertised natively on subsequent rounds. See
`docs/operations/tool-schema-admission-control.md` §2.8.
