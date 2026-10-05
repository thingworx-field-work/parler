# Tool surface simplification

Status: implemented — `get_entity` is executor-only (`ToolRegistry.registerExecutorOnly`), and model-facing schema and
routing text steer metadata questions to `describe_entity_schema` / `discover_thing_members`.

This document covers two related cleanups of the model-facing tool surface:

- `get_entity` is no longer a normal model-facing tool;
- first-party tool input schemas and routing prose no longer point the model at legacy or oversized paths.

The result is a smaller, clearer model-facing tool surface that is less likely to produce large metadata payloads.

## Background

Parler originally exposed `get_entity` as the practical way for the model to inspect ThingWorx schema metadata. That
made sense when there was no bounded schema tool, but it created long-term pressure:

- the name sounds like a general entity dump;
- the result shape is combined full metadata (`properties`, `services`, `events`) rather than a facet-sized answer;
- the payload can be large enough to pollute replay context and increase provider request size;
- recovery text and routing docs taught the model to use `get_entity` as the fallback for many metadata questions.

That broad role is split into two safer tools:

- `describe_entity_schema` describes known schema entities: `ThingTemplate`, `ThingShape`, and `DataShape`
  ([`entity-schema-description.md`](./entity-schema-description.md));
- `discover_thing_members` discovers effective members on a concrete `Thing` instance
  ([`thing-member-discovery.md`](./thing-member-discovery.md)).

Cleaning `get_entity` alone would have left stale schema text telling the model to use old paths, and cleaning schemas
alone would have left the largest metadata surface intact, so both are handled together.

## Related Documents

- [`legacy-discovery-executor-only.md`](./legacy-discovery-executor-only.md) — `discover_properties` is always
  executor-only; Thing-targeted `discover_services` / `get_service_definition` are model-facing only when
  `advertiseLegacyServiceDiscoveryTools=true`.
- [`tool-surface-budget.md`](./tool-surface-budget.md) — request-size budget view of the tool surface; per-round
  exposure is `toolAdmissionMode` (`../operations/tool-schema-admission-control.md`).
- [`entity-set-analysis.md`](./entity-set-analysis.md) — deterministic set algebra over cached entity/list results.

## Problem

Overlapping metadata concepts on the model-facing surface caused three problems:

| Need | Advertised tool | Legacy surface |
|------|-----------------|----------------|
| Describe a ThingTemplate / ThingShape / DataShape | `describe_entity_schema` | `get_entity` |
| Discover a concrete Thing's properties/services/events/subscriptions | `discover_thing_members` | `discover_properties`, `discover_services`, `get_service_definition` |
| Read current property values | `get_property_values` | sometimes confused with metadata discovery |
| Invoke one known service | `invoke_service` | sometimes preceded by broad metadata dumps |

1. **Routing ambiguity.** The model sees several tools that sound like metadata discovery and may choose the largest one.
2. **Fixed schema overhead.** Transitional descriptions repeat routing policy and compatibility notes in every request.
3. **Replay/context risk.** `get_entity` emits the full-metadata format used by Tier B compaction, so keeping it
   model-facing invites more large historic payloads even if compaction later shrinks them.

## Design Principles

1. **One user need, one advertised path.** Compatibility aliases may exist in code, but the model does not see several
   equivalent ways to do the same metadata job.
2. **Compatibility is executor-level, not schema-level.** Old tool names remain callable for persisted replay while
   being absent from the normal LLM schema registry.
3. **Descriptions say how, routing guide says when.** Tool schemas define required inputs and result shape. Broader
   policy lives in the routing guide or docs.
4. **Shrink without reducing correctness.** Schema text still gives the model the exact arguments needed for valid calls.
5. **Respect visibility-aware paths.** No wrapper or replacement path reintroduces visibility-free lookups (`*Direct`
   platform APIs) for model-facing introspection; `PlatformAccess` is the only entry.

## Current Surface

### Advertised metadata tools

| Tool | Role |
|------|------|
| `describe_entity_schema` | Schema-level facet reads for `ThingTemplate`, `ThingShape`, `DataShape`. |
| `discover_thing_members` | Concrete `Thing` member discovery for properties/services/events/subscriptions. |
| `get_property_values` | Current property value reads only. |
| `invoke_service` | Generic service invocation after service discovery or a user-provided exact service name. |

### Executor-only compatibility

| Legacy name | Behavior |
|-------------|----------|
| `get_entity` | Registered with `ToolRegistry.registerExecutorOnly`: absent from `getAllDefinitions()` and the merged LLM tool list; `executeTool("get_entity", …)` still works for persisted replay and HITL. Tier B compaction of historic `parler.entity.metadata.v1` results is unchanged (`GetEntityExecutor` unchanged). |
| `discover_properties` | Executor-only; Thing path delegates to `discover_thing_members(facet="properties")`. |
| `discover_services` for `Thing` | Delegates to `discover_thing_members(facet="services")`. |
| `get_service_definition` for `Thing` | Delegates to `discover_thing_members(facet="service")`. |

Compatibility wrappers return stable errors or recovery hints when an old call cannot be represented safely.

`get_entity` was de-advertised but kept as an executor (rather than removed, replaced with a summary-only wrapper, or
moved behind a diagnostics flag) because that stops new LLM calls with the lowest replay risk.
`BuiltInToolMergedDefinitionFootprintTest`, `PropertyHistoryToolRegistryAliasTest`, and `ToolRegistryExecutorOnlyTest`
lock this behavior.

### Schema and routing text

Model-facing copy steers normal inspection to `describe_entity_schema` / `discover_thing_members` first and frames
`get_entity` only as Tier B / persisted replay. The affected surfaces are `BuiltInTools` (`discover_properties`,
`describe_entity_schema` facet and main description, the off-list `getEntityDef()` description),
`InvokeServiceToolSchemaFragment`, `InvokeServiceExecutor` (`INVOKE_SERVICE_RESULT_TOO_LARGE` message;
`recoveryHint.alternatives` no longer lists `get_entity`), `llm_replay_format_routing_guide.txt`, the
`GetEntityExecutor` unsupported-Thing message, and the `AgentBaseThing` `enableBuiltInTools` aspect text. Legacy
executor-only argument names stay accepted by executors but are not in model-facing schemas.

## Measurement

Tool-surface changes are measured with:

- `LLM_CONTEXT_PLAN.toolSchemaChars`;
- `LLM_TOOL_SCHEMA_USAGE.schemaCount`;
- model-facing schema JSON char count from local schema registry tests;
- the list of removed advertised tools or schema fields.

Fewer characters is not the only metric: metadata prompts must route to the right tool without a large fallback payload.

## Verification

Automated checks:

- The tool schema registry does not advertise `get_entity` in normal built-in tool lists.
- Persisted/replay compatibility tests still handle historic `get_entity` tool result compaction.
- `describe_entity_schema` remains advertised and covers schema-entity prompts.
- `discover_thing_members` remains advertised and covers concrete Thing member prompts.
- Tool descriptions and recovery hints do not instruct the model to use `get_entity` as a normal path.
- Existing workflows still pass targeted tests for property history, current property values, invoke service with
  service discovery, cached tabular/chart follow-ups, and entity listing and taxonomy queries.

Manual checks on a ThingWorx server:

- Ask for properties on a known Thing instance; the model uses `discover_thing_members`.
- Ask for services on a known Thing instance; the model uses `discover_thing_members`, then `invoke_service` only if
  needed.
- Ask for the schema of a ThingTemplate; the model uses `describe_entity_schema`.
- Ask for a DataShape field list; the model uses `describe_entity_schema(facet="fields")`.
- Ask a broad "what is this entity?" question; the model asks for clarification or picks the target-specific tool, not a
  full metadata dump.
- No `unknown tool get_entity` failure appears in normal flows.

## Known Limitations

- Historic conversations may contain replay instructions that say "re-call `get_entity`"; the executor-only
  registration keeps those calls working.
- Any schema facet that `describe_entity_schema` does not support has no full-dump fallback on the model-facing surface.
- Over-trimming schemas can make tool calls invalid; tests validate required arguments and common prompts, not only
  char-count reduction.
