# Hierarchy composite retirement

**Status:** implemented (extension **0.1.161**) — the built-in tools **`query_asset_count_under_hierarchy_node`** and
**`compare_alert_status_between_hierarchy_nodes`** no longer exist. This document records the replacement paths.

The change is intentionally narrow. It is not a broad tool cleanup and not a hierarchy rewrite. It removed two
built-in tools that were composites over more fundamental operations.

## Related documents

- **`docs/agent/entity-set-analysis.md`** — background rationale (non-atomic composites).
- **`docs/operations/tool-schema-admission-control.md`** — lazy / narrow tool admission.

## Background

Parler grew a few specialized built-in tools while the generic agent tool basis was still incomplete:

- `query_asset_count_under_hierarchy_node` counted Things of one asset class under one hierarchy node.
- `compare_alert_status_between_hierarchy_nodes` compared current alert load for one asset class between two hierarchy
  nodes.

Both ran inside **`AlertToolsExecutor`**: they resolved the hierarchy node with
**`HierarchyNetworkServiceFacade.resolveNetworkId`**, loaded subtree Things with **`getAssetList`**, intersected them with
the implementor list for a **ThingTemplate** or **ThingShape**, and then either counted or fanned out
**`QueryAlertSummaryForThing`** per scoped Thing. That chain duplicated capabilities already exposed as composable
steps: **`query_entities` / `query_entities_by_taxonomy`** accept **`hierarchyNodeName`** and perform the same
ResolveNetworkID → GetAssetList intersection (see **`HierarchyQueryEntitiesIntersectAugment`** and the host-scope /
hierarchy sections of **`CONTRACTS/API_CONTRACT.md`**).

The rest of the system supports composition:

- `resolve_asset_type` / taxonomy rows identify application asset classes without guessing ThingTemplate names.
- `query_entities` and `query_entities_by_taxonomy` support hierarchy scoping through `hierarchyNodeName`.
- Cached tabular tools perform deterministic grouping, counting, filtering, and chartable transforms.
- `analyze_entity_set` provides the cross-result set primitive.
- Skills and playbooks are the layer for workflow-shaped recipes.

The two tools were therefore workflow macros, not primitives. Keeping them as always-advertised built-ins added
schema and routing overhead to every tool-capable round and taught the model a special-case path instead of the
composable one. The goal was not to remove hierarchy capability, only the hard-coded workflow macros.

## Current state

- The LLM does not receive schemas for `query_asset_count_under_hierarchy_node` or
  `compare_alert_status_between_hierarchy_nodes`, and the built-in routing guide does not recommend them.
- Hierarchy-scoped entity prompts use `resolve_asset_type` plus `query_entities` / `query_entities_by_taxonomy` with
  `hierarchyNodeName`.
- Alert comparison workflows use alert primitives, cached tabular analysis, or a skill/playbook layer.
- Alert summary / history / acknowledgement tools and `hierarchyNodeName` support on `query_entities*` are unchanged.
- **`HierarchyNetworkServiceFacade`** and the **`QueryEntitiesExecutor`** / **`QueryEntitiesByTaxonomyExecutor`**
  hierarchy augment remain in use; the private helpers that served only the retired tools were removed.

## Replacement paths

- **Counts under a hierarchy node.** After **`resolve_asset_type`**, use **`query_entities_by_taxonomy`** with
  **`hierarchyNodeName`** and read **`totalCount`** from the success JSON (use **`cacheId`** +
  **`tabulate_cached_result`** when an exact deterministic count is required over a LARGE result). For
  **ThingTemplate** / **ThingShape** listing (non-taxonomy path), use **`query_entities`** with **`hierarchyNodeName`**
  and read **`totalRows`** when non-null, honoring **`totalRowsInferred`**, **`note`**, and **`hasMore`** per
  **`docs/agent/query_with_total_count.md`**. **`totalCount`** belongs to the taxonomy tool, not to
  **`query_entities`**.
- **Alert comparison across nodes.** Build the scoped Thing set first (**`query_entities_by_taxonomy`** or
  **`query_entities`** with **`hierarchyNodeName`**), then call **`query_alert_summary`** with **`thingNames[]`** in
  batches of up to **25** (built-in **`ALERT_SUMMARY_MULTI`** rollup when N≥2). Split batches or use
  **`tabulate_cached_result`** / **`summarize_cached_result`** when the cohort is larger. There is no hierarchy-scoped
  **`query_alert_summary`** mode — summary filters apply per call, not per hierarchy node. History and acknowledge
  remain scalar **`thingName`**.

`LlmToolRoutingGuideChartWordingTest` asserts that the retired names are absent from `llm_tool_routing_guide.txt`.

## Compatibility

The tools were removed from the built-in surface outright, not profile-gated. Persisted conversations may still
contain historic tool calls/results with these names; they are audit/history rows and are not re-executed during
replay. Tool execution is **`ToolRegistry.executeTool`** → `executors.get(functionName)`; a missing name throws
**`IllegalArgumentException("Unknown tool: …")`**. There is no separate allow-list that keeps removed built-ins
registered for history display, so a persisted row that tries to re-invoke a removed tool fails at execution time.

No normative wire shape in **`CONTRACTS/`** names these tools. Sample data under `dev_data/` may still mention the
retired names.
