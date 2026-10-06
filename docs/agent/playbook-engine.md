# Playbook Engine

Status: implemented — static repository Playbooks (`cross_region_health`, `cross_asset_pair_health`, and customer
playbooks), the generic and service-orchestration derive ops (**`playbook-34-35`**), **`$infotable`** extended-tool
binding, structured validation, catalog routing, nested evidence projection, **`merge_row_sets`**, and **`fan_out`
`itemVar`**. There is no Provider service that generates playbooks.

This document defines a focused Playbook Engine for `parler-agent`.

The goal is to reduce high-latency multi-round skill execution for known workflows while preserving the final-answer quality that comes from LLM-written summaries over structured evidence.

Motivation: local rate control avoids upstream 429s, but a skill-driven workflow that needs many LLM rounds can still take minutes on a rate-limited provider. Rate control keeps the system from failing; Playbooks keep common workflows from needing so many LLM rounds.

## 1. Positioning

Parler has three workflow shapes:

| Shape | Who controls steps | What the LLM sees | Best for |
|-------|--------------------|-------------------|----------|
| Skill | LLM chooses each next tool call | Skill text plus tool results | Flexible guidance and natural final answers |
| Tool | Tool implementation controls everything inside one call | Usually final tool output only | Fast atomic business capability |
| Playbook | Parler runtime executes a registered DAG | Compact evidence ledger and selected summaries | Repeatable multi-step workflows with progress visibility |

A Playbook is not a classic planner. It does not synthesize arbitrary plans from preconditions and effects. It executes a registered workflow loaded from repository configuration.

The key design rule:

```text
Runtime controls step execution.
LLM controls language and limited interpretation over compact evidence.
```

## 2. Scope

The product direction is Playbook, not a one-off JS tool. The reason is product shape, not only speed: a Playbook gives Parler a visible, repeatable multi-step workflow with a compact evidence ledger and a final LLM-written answer. A black-box tool can be faster, but it does not establish that interaction model.

The engine surface grew in two named layers that the code and other documents still refer to:

### 2.1 V1a surface: cross-region health

The first layer covers the `cross_region_health` workflow (derived from the `region_health` skill):

- repository-backed static playbook registry
- Java validation of static `PlaybookJson`
- current-turn DAG execution
- `tool_call`, `derive`, `fan_out`, and final-only `llm_summary` nodes
- sequential fan-out loop
- compact playbook evidence ledger
- current-turn `task.state` progress frames for UI visibility
- final LLM response over compact playbook evidence
- `LLM_PLAYBOOK_RUN` telemetry

### 2.2 V1b surface: asset-pair and harder workflows

The second layer adds:

- `condition` nodes
- `cross_asset_pair_health` (skill baseline `asset_pair_health`)
- `match_entity_identifiers`, `require_exact_count`, `flatten_pair_assets`, `select_primary_problem_property`, and related derive ops
- clarification-oriented failures (`needs_clarification`)
- multi-playbook registry loading

**Playbook input resolution:** user-facing identifiers for built-in **`resolve_thing`** and utilization listing fallbacks are normalized in-engine before extended tools that require canonical Thing names. See **`docs/agent/playbook-input-resolution.md`**. Summary:

- **`$table`** remains valid **only** as the sole value of a **top-level** `tool_call.args` parameter bound to an INFOTABLE (ThingWorx service shape). Derive ops such as **`match_identifier_in_rows`** must bind **`rows`** via **`$ref`** to JSON arrays on a prior tool’s saved **`toolOutput`** (the current utilization path is **`machine_listing.toolOutput.result.rows`**), never via **`$table`** inside derive args. When a continuing `$ref` crosses `toolOutput.result` and that value is a JSON string, the resolver parses it before traversing the remaining segments; terminal `...toolOutput.result`, malformed JSON, and strings at other paths remain opaque.
- **`normalize_resolved_thing`** is **terminal-only** (it may emit **`needs_clarification`**). Playbooks that need a **`list_utilization_machines`** fallback must branch on raw **`resolve_thing`** **`toolOutput.status` / `code` / `resultKind`** with a **`condition`** node **before** any terminal normalize on the success path.
- **`pick_branch_output`** copies the derive/tool JSON result from the branch terminal selected by a prior **`condition`** (`thenNodeId` must equal the condition’s **`then`**; **`elseNodeId`** must be the **`else`** root or any node that **dependsOn**-transitively depends on that **`else`** root). Downstream **`tool_call`** parameters can then **`$ref`** a single merged node’s **`output.name`**.

### 2.3 Out of Scope

The engine does not support:

- classic planner / dynamic action synthesis
- arbitrary JavaScript or expression execution inside Java
- JVM restart resume
- cross-conversation resume
- background execution after Live Chat disconnect
- durable playbook state
- cross-turn playbook memory beyond normal conversation artifacts
- Provider services that generate `PlaybookJson`

If the JVM restarts, the AgentThing is disabled/restarted, or the current Live Chat disconnects, any in-flight playbook run is abandoned. It is not resumed.

## 3. Runtime Model

### 3.1 Registration

**Layout (directory packages):** each playbook lives under **`/playbooks/<playbook-id>/playbook.json`** (one merged JSON file per package). Discovery enumerates immediate child directories of **`/playbooks`**, skips dot-prefixed names, sorts, loads at most **32** successful packages, and supports partial success diagnostics. See **`docs/agent/playbook-directory-packaging.md`** for the normative contract. The root of `playbook.json` carries `id`, `title`, `description`, `whenToUse`, `inputSchema`, and `execution` next to the DAG fields (§4).

Rules:

- `id` uses the same short-id grammar as repository skills.
- The LLM sees `id`, title, and `whenToUse` through the per-turn **Agent playbooks** catalog (ephemeral system message) and through the registry-driven **`start_playbook`** tool description. Playbook `description` from `playbook.json` is operator/authoring metadata only — it is not injected into the per-turn catalog.
- The LLM may request only a registered `playbook_id` present in the loaded registry.
- The LLM cannot provide arbitrary playbook paths or provider names.
- Discovery enumerates `/playbooks/<id>/playbook.json` packages (up to **32** successful loads). There is no fixed demo-id allowlist at runtime — customer playbooks appear in catalog and `start_playbook` when loaded successfully.
- If Playbook loading fails closed (zero successful packages), log errors, do not expose Playbook metadata, and do not register `start_playbook`. The rest of the AgentThing remains usable.
- Registry entries or documents containing `provider` are rejected.

### 3.2 Command Namespace and Conflicts

Playbooks and skills share the same slash-command namespace.

Rules:

- A valid Playbook id has higher priority than a Skill id with the same short name.
- The effective Playbook catalog is loaded first.
- Skill loading receives the set of effective Playbook ids as reserved command names.
- If a Skill has the same id as an effective Playbook, the Skill is ignored, `logger.error` records the conflict, and remaining Skills continue loading.
- Ignored conflicting Skills must not appear in system prompt skill metadata, diagnostics, slash suggestions, or LLM-visible tool/skill catalogs.
- If Playbook loading fails closed and a Playbook id is not effective, it does not reserve the slash name; normal Skill loading may proceed.
- Conflict log format:

```text
PLAYBOOK_SKILL_NAME_CONFLICT skillPath=<path> ignoredSkillId=<id> reservedByPlaybook=<id>
```

The playbook id `cross_region_health` deliberately differs from the `region_health` Skill id so both can coexist.

### 3.3 Invocation Paths

Trigger paths:

| Trigger | Example | Notes |
|---------|---------|-------|
| Natural language | `Compare USA and Germany Stacking Robots` | LLM calls `start_playbook` with structured params |
| Structured slash | `/cross_region_health {"assetType":"Stacking Robot","regions":["USA","Germany"],"timeWindow":"current"}` | Direct runner path; no routing LLM call |

Both paths normalize to:

```json
{
  "playbookId": "cross_region_health",
  "conversationId": "conv-123",
  "requestId": "req-456",
  "userGoal": "Compare USA and Germany Stacking Robots",
  "params": {
    "assetType": "Stacking Robot",
    "regions": ["USA", "Germany"],
    "timeWindow": "current"
  }
}
```

The internal request is current-turn only. It is not persisted for restart recovery.

Turn routing:

- Structured slash route: parse Playbook slash commands before Skill slash commands. A Playbook slash command with valid JSON params calls `PlaybookRunner.run(...)` directly and does not enter the normal AgentLoop tool-call loop.
- Free-text slash route: there is no hand-rolled natural-language parameter parsing. If `/cross_region_health` is used without JSON params, return a targeted clarification telling the user to use structured JSON or ask the same question in normal natural language so the LLM can call `start_playbook`.
- LLM route: `start_playbook` calls the same `PlaybookRunner.run(...)` path after tool argument validation.
- In one turn, if the LLM calls `start_playbook` more than once, the second call is rejected with a structured error.
- In one conversation, only one Playbook run may be active. A newer user request supersedes and cancels the older run according to §8.
- Structured slash route makes zero routing LLM calls and one final `llm_summary` call; the natural-language route adds one routing/extraction LLM call.
- **Playbook entry context:** Any surface that probes or runs a playbook **outside** the normal AgentLoop `try` block (structured slash) MUST bind conversation, agent, host context, and — for AlwaysOn — `requestId`, `remoteThingName`, and `ParlerRemoteConversation` **before** `tryExecutePlaybookSlashTurn` / `PlaybookRunner.run`, and MUST call `AgentToolContext.clear()` in a `finally` after the slash probe **even when the message is not a playbook slash**. The AgentLoop path re-binds the same fields immediately afterward. Without AlwaysOn binding, playbook `task.state` frames cannot flush on structured slash.

### 3.4 No Provider Service

The engine loads static `PlaybookJson` from the repository only. There is no Provider invocation path (not even a stub); registry entries or documents with a `provider` field are rejected.

## 4. PlaybookJson V1

Top-level shape:

```json
{
  "schema": "parler-playbook-v1",
  "title": "Cross-region operational diagnosis",
  "budgets": {
    "timeoutSeconds": 900,
    "maxNodes": 80,
    "maxToolCalls": 200,
    "maxEvidenceBytes": 12000
  },
  "vars": {},
  "nodes": [],
  "finalNode": "final_summary"
}
```

Validation:

- `schema` must be `parler-playbook-v1`.
- `nodes[].id` must be unique and match `[A-Za-z][A-Za-z0-9_-]*`.
- `dependsOn` must reference existing ids.
- The graph must be acyclic.
- Node count and fan-out expansion must stay within budgets.
- Unknown node kinds are rejected.
- Unknown tools are rejected.
- Tools that are not allowed in playbooks are rejected.
- Raw result rows, property values, hidden prompts, or chain-of-thought must never be emitted in progress frames.

Code-level hard guards:

- registry entries containing `provider` are rejected
- `llm_summary` node count is at most one, and that node must be `finalNode`
- `fan_out.maxConcurrency` must be absent or `1`; non-`1` values fail validation
- playbooks may call only tools whose merged `ToolDefinition.playbookSafe` is `true` (normative matrix:
  `docs/agent/playbook-engine-v1a-tool-allowlist.md`; tests pin parity via `PlaybookToolAllowlist` + `PlaybookValidatorTest`)
- non-`finalNode` outputs must be referenced by another node (orphan nodes fail validation)
- raw result rows, property values, hidden prompts, or chain-of-thought must never appear in progress frames

V1a surface (`cross_region_health`):

- derive ops: `pick_taxonomy_row`, `extract_field`, `flatten_region_entities`, `collect_thing_names_from_assets`, `build_property_union`, `group_alerts_by_source_property`, `summarize_current_values_by_region`, `summarize_region_health`
- node kinds: `tool_call`, `derive`, `fan_out`, `llm_summary`
- tools used: `query_entities_by_taxonomy`, `query_alert_summary`, `get_property_values`

The built-in `playbookSafe` surface is broader than those tools — see `docs/agent/playbook-engine-v1a-tool-allowlist.md`
(`playbook-builtin-capability-expansion`).

V1b surface:

- registry: directory discovery (up to **32** packages); runtime ids come from loaded `playbook.json` files — not a hardcoded enum
- `start_playbook.playbook_id`: registry-driven string (all loaded ids listed in tool schema description)
- node kind: `condition` with predicate ops `is_empty`, `is_present`, `eq`, `ne`, `gt`, `gte`, `lt`, `lte`, plus `and` / `or` / `any` / `not` (unary ops accept `value` or `left`)
- derive ops: `match_entity_identifiers`, `require_exact_count`, `flatten_pair_assets`, `select_primary_problem_property`, `union_property_names`, `trend_targets`, `trend_summary`, `summarize_asset_pair_health`
- tools: adds `query_property_history` (plus the other `playbookSafe` built-ins; see allowlist doc)

### 4.1 Node Kinds

Node kinds:

| Kind | Purpose |
|------|---------|
| `tool_call` | Call a registered Parler tool with resolved args |
| `derive` | Run a built-in deterministic transform over prior node outputs |
| `fan_out` | Run a child node over items from a prior result |
| `llm_summary` | Ask the LLM to write a compact interpretation from playbook evidence |
| `condition` | Choose one branch from simple predicates for clarification or optional work (§4.5) |

Not supported:

- `service_call`
- `subplaybook`
- arbitrary script node
- dynamic provider hook nodes
- write nodes with custom HITL semantics

Extended tools registered through `/tools/extended_tools.json` can be called by `tool_call` if they are present in the normal tool registry and pass the same policy/protection checks as any other tool. This avoids a separate `service_call` policy.

### 4.2 `tool_call`

```json
{
  "id": "list_candidates",
  "kind": "tool_call",
  "tool": "query_entities_by_taxonomy",
  "args": {
    "EntityType": { "$var": "taxonomy.EntityType" },
    "EntityName": { "$var": "taxonomy.EntityName" },
    "CriticalProperties": { "$var": "taxonomy.CriticalProperties" }
  },
  "evidence": {
    "label": "List candidate assets",
    "include": ["status", "rowCount", "totalCount", "cacheId"]
  }
}
```

**INFOTABLE tool evidence:** optional **`evidence.table`** projects **`rows`** / **`sampleRows`** (or a retained raw table) into **`llm_summary`** text. Some built-ins (notably **`query_alert_history`**) merge scalar window metadata at the **root** of the tool JSON (`appliedStartTime`, `appliedEndTime`, `rowCount`, …) rather than duplicating it into each row. For those tools, set **`evidence.includeToolOutputRootFields`** to a JSON array of root field names (scalar values only); **`PlaybookEvidenceFormatter`** prints those lines **before** any table projection. **`includeToolOutputRootFields`** does **not** require **`evidence.table`** — root-only evidence is allowed. When **`evidence.table`** is present but the tool returns **no** rows, a **`(no table rows; rowCount=N)`** note is still emitted so zero-row envelopes (e.g. no alerts in the window) keep root metadata visible to **`llm_summary`**.

**JSON-envelope extended tools:** when a service returns business JSON under **`toolOutput.result`** (object or JSON string), use **`evidence.includeToolOutputPaths`** for nested scalar leaves and optional **`evidence.table.path`** for row projection. Authoring rules (see also **`training-stage-configuration-contracts.md`**):

| Key | Resolves from | Use for |
| --- | --- | --- |
| **`includeToolOutputRootFields`** | **`toolOutput` root** | Root scalars only (`rowCount`, `appliedStartTime`, …) |
| **`includeToolOutputPaths`** | **`toolOutput` root via bounded dot paths** | Nested scalars (`result.stats.utilizationPercent`, …) |
| **`evidence.table`** (no `path`) | Root **`rows`** / **`sampleRows`**, or retained raw **INFOTABLE** | Legacy/top-level tabular tools |
| **`evidence.table.path`** | Dot path to a JSON **array** under **`toolOutput`** | Nested row sets (`result.rows`, `result.stateSummary.rows`, …) |

Path grammar: dot-separated segments `[A-Za-z][A-Za-z0-9_]*`, max depth **8**, **no array indexing**. When a path traverses **`result`** and the value is a JSON string, the formatter parses it before projection. Precedence for table evidence: retained raw **`InfoTable`** at **`{nodeId}.result`** first, then **`evidence.table.path`**, then root **`rows`** / **`sampleRows`**. Do **not** introduce parallel multi-table syntax until a playbook demonstrably needs two tables from one tool call.

**Orchestration evidence fixture (C16):** the same
**`includeToolOutputPaths`** / **`evidence.table.path`** fields apply on the final
**`tool_call`** in multi-branch orchestration graphs. Proof fixture:
**`playbook-34-35-fixture/multi_optional_branch_merge/`** with
**`PlaybookRunnerMultiOptionalBranchMergeEvidenceTest`** — JUnit asserts projected evidence
reaches **`llm_summary`** input (utilization-evidence test pattern on a domain-neutral stub
service).

Execution:

- resolve args from literals, vars, item context, and prior node refs
- call the normal tool executor
- store raw result in run state or cache reference
- produce compact evidence from structural fields only

**Explicit evidence contract:** derive nodes referenced by `final_summary.evidenceRefs` should attach top-level **`evidenceLines`** (array of short strings) and **`evidenceText`** (joined lines) on the node result alongside `status` and `output`. `PlaybookEvidenceFormatter` appends those verbatim and does not interpret business-specific `output` shapes. Trend evidence must include bounded aggregates (e.g. first/last/min/max/mean) and a simple direction when safe (`rising`, `falling`, `flat`, `mixed`), not only row/point counts.
- record an internal node progress event, which feeds `task.state` progress (§7)

The engine must not route around existing protection, HITL, parameter validation, or tool policy.

Tool safety:

- `ToolDefinition` must expose a `playbookSafe` boolean; default `false`.
- Validator rejects any `tool_call.tool` whose effective merged `ToolDefinition.playbookSafe` is not `true`.
- Write/HITL-heavy built-ins are ineligible unless explicitly opted in on the definition (`set_property_value` is not;
  see `docs/agent/playbook-builtin-capability-expansion.md`).
- The current built-in matrix is documented in `docs/agent/playbook-engine-v1a-tool-allowlist.md`.
- `PlaybookValidatorTest.playbookToolAllowlist_matchesMergedPlaybookSafeBuiltIns` asserts `PlaybookToolAllowlist.TOOL_NAMES`
  matches the playbook-safe name set from `PlaybookToolDefinitionsMerge.merge(defaultBuiltIns, missingExt)`.

### 4.3 `fan_out`

Fan-out is intentionally simple and executes sequentially:

```json
{
  "id": "alert_summaries",
  "kind": "fan_out",
  "dependsOn": ["resolved_assets"],
  "items": { "$ref": "resolved_assets.output.assets" },
  "itemVar": "asset",
  "maxItems": 20,
  "maxConcurrency": 1,
  "node": {
    "kind": "tool_call",
    "tool": "query_alert_summary",
    "args": {
      "thingName": { "$item": "name" },
      "ackState": "all",
      "limit": 100
    }
  }
}
```

Rules:

- Only `maxConcurrency` absent or `1` is accepted; anything else fails validation.
- **`itemVar`** (optional) names the wrapper key for **primitive** fan-out items (strings and
  other non-object scalars). The runtime wraps each primitive as **`{ "<itemVar>": "<value>" }`**
  before resolving **`$item`** in the child **`tool_call`**. When **`itemVar`** is omitted, the
  default key is **`value`**. Object **`items`** arrays pass through unchanged — bind **`$item`**
  to a field on each row (for example **`name`**). See **`playbook-input-resolution.md`** §6.0.6.
- `maxItems` is required when `items` comes from runtime data.
- each item produces a child result entry
- failures are fail-fast
- the UI should show aggregate progress, e.g. `3 / 8 alert summaries completed`

Sequential fan-out still helps because it removes LLM rounds between tool calls.

### 4.4 `derive`

`derive` runs deterministic transforms that are deliberately not a general expression language.

```json
{
  "id": "taxonomy_row",
  "kind": "derive",
  "op": "pick_taxonomy_row",
  "args": {
    "assetType": { "$input": "assetType" },
    "taxonomyRows": { "$var": "assetTaxonomy.rows" }
  }
}
```

Built-in transforms for `cross_region_health` (V1a surface):

| Transform | Purpose |
|-----------|---------|
| `pick_taxonomy_row` | Resolve user asset type label to one injected taxonomy row |
| `extract_field` | Extract one named field such as `name` from each row |
| `flatten_region_entities` | Flatten region fan-out entity rows into `{region, name, row}` entries |
| `collect_thing_names_from_assets` | Collect bounded `thingNames[]` for one `query_alert_summary` call (default max 25); emits `gapNote` when capped |
| `build_property_union` | Build a capped read list from taxonomy critical properties and alert source properties |
| `group_alerts_by_source_property` | Group alert rows by `sourceProperty`, with `unknown` fallback; reads **`ALERT_SUMMARY_MULTI`** via **`alertSummaryNodeId`** or legacy per-Thing **`fanOutNodeId`** fan-out |
| `summarize_current_values_by_region` | Per-property rollup of `get_property_values` fan-out (counts, optional numeric min/max/mean, capped examples) for `llm_summary` evidence |
| `summarize_region_health` | Compact per-region counts, alert groups, current values, and gaps |

Transform boundaries:

- `pick_taxonomy_row`: reads `vars.assetTaxonomy.rows` from the same `PromptContextCacheSnapshot` used to build the current turn's prompt context. `PlaybookRunner` must not call taxonomy services directly to refill it. Missing rows, zero matches, or multiple matches return `needs_clarification`; the run ends with a targeted user question. It must not silently choose the first row.
- `extract_field`: `rows == null` or `[]` returns `[]`; rows missing `fieldName` are skipped; dotted paths are not supported; duplicate values are preserved.
- `flatten_region_entities`: preserves the source region for every row; skips failed fan-out children; emits a gap entry for failed regions instead of raw error payloads.
- `build_property_union`: input sources are taxonomy `CriticalProperties`, alert `sourceProperty` values, and any explicit small playbook property hints. Output is de-duplicated, order-stable, and capped at 24 property names.
- `collect_thing_names_from_assets`: reads **`assetsRef`**; output **`names`** is a JSON array suitable for **`query_alert_summary`** **`thingNames[]`**. When the cohort exceeds **`maxNames`** (default 25), output includes **`capped: true`** and a **`gapNote`** — do not silently drop assets.
- `group_alerts_by_source_property`: missing/blank `sourceProperty` groups under `unknown`; raw alert rows stay out of evidence and `task.state`. **Preferred path (multi-Thing alert summary):** set **`alertSummaryNodeId`** to the single **`tool_call`** node whose **`toolOutput.resultKind`** is **`ALERT_SUMMARY_MULTI`**; grouping uses each success entry's **`topAlerts[]`**. When **`totalAlerts`** exceeds **`topAlerts.length`**, emit a **`regionAlertGaps`** note that source-property counts are sample-based (full rows behind per-Thing **`cacheId`**). **Legacy path:** **`fanOutNodeId`** over per-asset **`query_alert_summary`** fan-out children remains for older fixtures.
- `summarize_current_values_by_region`: reads `propertyNames` after `$var` resolution as `JSONArray`, Java `Collection` (same shape as `ctx.putVar("propertyUnion.names", …)` from `build_property_union`), or semicolon-separated `String`. Optional **`excludePropertyNames`** removes identity/display fields before stats. Property order is capped by **`maxPropertiesPerRegion`** after the union list is resolved. For each retained property it scans **all** Things in the region; `maxExamplesPerProperty` limits **examples** only, not `successfulReads` / numeric aggregates. A row counts toward `successfulReads` only when the matching property cell exists and `ok` is true; `failedReads` counts cells present with `ok` false. Properties with **no** reads and **no** failed cells in that region are **omitted** from `output`. Evidence uses **`evidenceLines` only** (no redundant **`evidenceText`**) so the node stays within the **8KiB** serialized cap alongside the **`cross_region_health`** defaults (`maxPropertiesPerRegion` **12**, `maxExamplesPerProperty` **1**, identity excludes).
- `summarize_region_health`: output schema is fixed:

```text
{
  regions: [
    {
      name: string,
      assetCount: int,
      alertGroups: [{ property: string, alertCount: int }],
      topProperties: [{ name: string, value: string|number|boolean|null }],
      gaps: [string]  // 0-3 short human-readable evidence gaps
    }
  ],
  comparison: {
    higherAttentionRegion: string|null,
    reasons: [string]  // 0-5 short evidence-backed reason labels
  }
}
```

Only `gaps` and `comparison.reasons` may contain short free text. Longer narrative belongs in the final `llm_summary`.

`summarize_region_health` size caps:

- `regions[]`: <= 10 entries
- `regions[].alertGroups[]`: <= 10 entries, sorted by `alertCount` descending; overflow is folded into `_other`
- `regions[].topProperties[]`: <= 5 entries
- `regions[].gaps[]`: 0-3 entries
- `comparison.reasons[]`: 0-5 entries
- entire output JSON: <= 8 KB
- output over 8 KB fails the node with `errorCode=evidence_too_large`, run status `failed`, and `LLM_PLAYBOOK_RUN status=failed failureCode=evidence_too_large`
- failure summary should be: `Region summary exceeded 8KB cap; reduce regions or rerun with narrower scope.`

V1b transforms:

| Transform | Purpose |
|-----------|---------|
| `match_entity_identifiers` | Normalize and match user labels against candidate rows |
| `require_exact_count` | Convert zero/multiple matches into a clarification-needed failure |
| `select_primary_problem_property` | Pick a property from alert evidence and optional user dimension hints |
| `union_property_names` | Build a small property list from critical props, selected property, and alert properties |
| `summarize_asset_pair_health` | Compact pair comparison evidence for final LLM summary |

**Priority 1 generic derive ops** (normative spec `docs/agent/playbook-generic-ops-foundation.md`):

- These ops use stable JSON envelopes distinct from the bespoke transforms above.
- **`project`** (partial): implemented in `PlaybookGenericDeriveOps`; validator checks `args.rows` / `args.fields`,
  dotted `from` paths (`PlaybookGenericPathGrammar`), reserved `as`, and duplicate `as`; runtime uses
  `PlaybookGenericRowArrays.loadResolvedRowArgs` / `requireEachSlotIsObject`; row navigation via `PlaybookJsonRowPath`;
  expanding ops use `PlaybookGenericRowArrays.truncateIfNeeded` for output caps.
- **`filter`** (partial): same row loading; `args.where` row predicates via `PlaybookRowPredicate` (section 7 of the
  generic-ops doc); validator checks `where` shape, leaf `field` / `left` / `value` exclusivity, dotted `field`
  grammar, and binary ops require `right`.
- **`sort` / `top_n` / `pick_one`:** `sort` stable-sorts by `orderBy` keys (`PlaybookGenericRowOrdering`, nulls last);
  `top_n` optionally sorts then takes first `n` with a gap when fewer rows exist; `pick_one` selects at most one
  row via the same row predicate model, with `onZero` / `onMultiple` modes per the generic-ops doc (`output.row`
  on success). When `status` is `needs_clarification`, `output` still carries `row: null` and zero counts (section 6.1).
  **`pick_one`:** on success (`status: ok`), `$ref` paths MUST use `<nodeId>.output.row` — do not alias `selectedRow`.
- **`group_by` (partial):** `PlaybookGenericGroupBy` groups rows by **`keys`** (**v1:** each key is a **single-segment** field name — no dots; use **`project`** first for nested sources). **`keys[]`** entries and measure **`name`** / **`op`** / **`field`** must be JSON **strings** (validator + runtime; no boolean/number coercion). Optional **`measures`** via **`PlaybookGenericMeasures`** (`count`, `count_present`, `sum`, `min`, `max`, `mean`); if **`measures`** is present it MUST be a JSON array (otherwise validator + runtime **`GENERIC_INPUT_INVALID`**). Required literal **`maxGroups`** (validator-capped);
  overflow = gap + truncate in deterministic **sorted internal composite-key** order (not first-seen under overflow, not by group size); **`foldOverflowToOther`** is not supported
  (validator/runtime reject).
- **`aggregate` (partial):** `PlaybookGenericAggregate` — non-empty **`measures`** only, same measure ops as **`group_by`**; measure string fields follow the same JSON-string rule as **`group_by`**; **`output`** holds scalar measure fields plus **`gaps`** / counts (no **`rows`**).
- **`join_by_key` (partial):** `PlaybookGenericJoinByKey` — **`left`** / **`right`** via **`loadResolvedSide`**, dotted **`leftKey`** / **`rightKey`**, **`joinType`** `inner` \| `left`, optional **`rightPrefix`**, required **`maxRows`**; expanding output uses **`truncateIfNeededWithLogicalCount`** so **`totalCount`** is the full logical join size and **`returned`** reflects truncation with a deterministic gap (same rules as **`truncateIfNeeded`**). Duplicate right keys emit one row per match in first-seen right order. **Null / missing keys do not match** (SQL-style); evidence counts **`inner`** left rows with no right match (non-null keys only).
- **`build_targets` (partial):** `PlaybookGenericBuildTargets` — non-empty **`sources`**, required **`maxTargets`**, non-empty **`template`**; **`$path`** only inside **`template`** (validator rejects elsewhere). Cartesian nesting uses **sorted source names** (not `JSONObject` iteration order). Missing **`$path`** values still emit JSON null on the target but tally a **bounded `output.gaps`** entry; **empty** resolved source arrays yield a **no-targets** gap. **`truncateIfNeededWithLogicalCount`** when the logical product exceeds **`maxTargets`**. Sole-key **`$input`** / **`$var`** / **`$ref`** / **`$item`** use **`PlaybookExpressionResolver`**.
- **`collect_gaps` (partial):** `PlaybookGenericCollectGaps` — **`refs`** (dotted paths like **`$ref`**), **`maxItems`**; merges **`output.gaps`** arrays in order with string / structured dedupe (canonical four-key projection for equality; **`JSONObject`** wire order not guaranteed); rejects opaque nested values at runtime; **`output.totalCount`** vs **`returned`** when truncated by **`maxItems`**; evidence counts only.
- **`flatten_fan_out_rows`:** `PlaybookGenericFlattenFanOutRows` — required **`fanOutNodeId`** naming a **`fan_out`** whose inner node is **`tool_call`**; concatenates each successful child's **`toolOutput.rows`** into **`output.rows`** (standard row envelope). Optional **`injectFromItem`**: array of **`{ "from", "as" }`** (validator + runtime) — **`from`** is resolved on the fan-out **`item`**; **`as`** is written on the row only when absent. **`output.totalCount`** vs **`output.returned`** follow §6.2 when truncation hits **`MAX_GENERIC_INPUT_ROWS`**.

**Generic derive op quick reference** (normative detail in `playbook-generic-ops-foundation.md` §6):

| Op | Required / primary args |
| --- | --- |
| `project` | `rows`, non-empty `fields[]` (`from` / `as`, …) |
| `filter` | `rows`, `where` |
| `sort` | `rows`, non-empty `orderBy[]` |
| `top_n` | `rows`, `n`, optional `orderBy[]` |
| `pick_one` | `rows`, `where`, `onZero`, `onMultiple` |
| `group_by` | `rows`, non-empty `keys[]`, `maxGroups`, optional `measures[]` |
| `aggregate` | `rows`, non-empty `measures[]` |
| `join_by_key` | `left`, `right`, `leftKey`, `rightKey`, `joinType`, `maxRows`; optional `rightPrefix` |
| `build_targets` | non-empty `sources{}`, `template`, `maxTargets` |
| `flatten_fan_out_rows` | `fanOutNodeId`, optional `injectFromItem[]` (`from` / `as`) |
| `collect_gaps` | non-empty `refs[]`, `maxItems` |

**Service orchestration derive ops** (**`playbook-34-35`**; normative design
`docs/agent/playbook-34-35.md`; runner fixtures under
`parler-agent/src/test/resources/playbook-34-35-fixture/`):

- **`normalize_resolved_things`** — fan-out **`resolve_thing`** → canonical Thing rows +
  gaps; **`onUnresolved`** `gap` \| `clarify`; optional **`minResolvedRows`**.
- **`extract_from_tool_output`** — bounded extraction from non-`rows` tool envelopes
  (`mode`: `single` \| `fan_out_children`; **`arrayPath`** uses orchestration path grammar;
  optional **`where`**; **`fields[]`** projection). Distinct from generic dotted-row
  **`PlaybookJsonRowPath`** — see design §6.3.
  **Nested lookup envelopes:** when an extended tool returns criteria under
  **`toolOutput.result.Table0`** (JSON-envelope `resultKind`), set **`arrayPath`** to
  **`result.Table0`** in **`single`** mode on the **`tool_call`** node — no new op required.
  Fixture: **`playbook-34-35-fixture/nested_table0_extract/`**.
- **`build_nested_object`** — template-driven nested JSON from named **`sources`** (`$map` /
  `$src` / `$nodeRef` / `$path` / `$literal` directives per design §6.5.1); **`omitNull`**
  omits keys whose **`$nodeRef`** resolves null (mutually exclusive time fields).
- **`json_stringify`** — bounded serialization of an in-playbook JSON value to a string
  parameter (`maxBytes`).
- **`resolve_time_window_for_playbook`** — quick-interval row match or explicit UTC range
  via **`ParlerTimeResolver`**; default quick-interval name fall-through; calendar-day
  residue parity with built-in natural-time tools.
- **`empty_rows_if_skipped`** — when an optional **`condition`** branch skipped
  **`sourceNodeId`**, emit explicit empty **`output.rows`**; otherwise pass through source
  rows. Skipped-node **`$ref`** / **`$nodeRef`** resolve JSON null at runtime.
- **`merge_row_sets`** — concatenate multiple named row sources in stable order for
  downstream **`build_nested_object`** **`$src`** attachment (optional-branch merge). Each **`sources[]`** entry is **`{ "$ref": "<nodeId>.output.rows" }`**;
  **`output.sourceCounts`**, **`totalCount`**, **`returned`**, and bounded **`gaps`**
  follow §6.2 truncation accounting. Domain-neutral proof fixture:
  **`playbook-34-35-fixture/multi_optional_branch_merge/`**.
- **`add_computed_fields`** — per-row **`datetime_diff_minutes`** or numeric **`add`** /
  **`sub`** / **`mul`** / **`div`** over dotted operand paths; **`onNull`**: `null` \|
  `skip` \| `gap`.
- **`collect_values`** — unique values of a dotted **`field`** from rows (capped).
- **`join_values`** — delimited string from a JSON array (**`maxLength`** truncation).

#### 4.4.1 Multi-name fan-out per optional branch (C9)

When one optional filter branch must resolve **multiple** names (for example several
product codes), compose existing nodes — **no** dedicated multi-name op:

1. Supply a JSON **array** via **`$input`**, **`derive`** (`project` / `collect_values`), or a prior **`tool_call`** envelope.
2. **`fan_out`** over **`items`** with a **`tool_call`** lookup child; set **`itemVar`** to the
   wrapper key for primitive **`string[]`** inputs and bind **`{ "$item": "<itemVar>" }`**
   (see **`playbook-input-resolution.md`** §6.0.6).
3. **`flatten_fan_out_rows`** with **`fanOutNodeId`** → uniform **`output.rows`** for that branch.
4. Pair with **`condition`** + **`empty_rows_if_skipped`** when the whole branch is optional
   (same pattern as **`multi_optional_branch_merge/`**).
5. **`merge_row_sets`** when combining multiple optional branches before **`build_nested_object`**.

Illustrative branch tail (lookup tool names are application config):

```json
{
  "id": "product_lookup",
  "kind": "fan_out",
  "dependsOn": ["product_gate"],
  "items": { "$input": "productNames" },
  "itemVar": "productName",
  "maxItems": 20,
  "maxConcurrency": 1,
  "node": {
    "kind": "tool_call",
    "tool": "lookup_product",
    "args": { "productName": { "$item": "productName" } }
  }
},
{
  "id": "product_rows",
  "kind": "derive",
  "dependsOn": ["product_lookup"],
  "op": "flatten_fan_out_rows",
  "args": { "fanOutNodeId": "product_lookup" }
}
```

When **`items`** is an array of row objects instead of strings, use the object field name
(for example **`{ "$item": "name" }`** on normalized **`resolve_thing`** rows).

**Service-argument binding (extended tools):** top-level **`tool_call.args`** parameters
may use **`$infotable`** to bind derived JSON rows to ThingWorx **`infotableArgs`**
(**`dataShapeName` required**). Nested object/string parameters use normal **`$ref`**
to derive outputs (for example **`json_stringify.output.value`**). Validator rejects
**`$infotable`** inside derive args.

| Op | Required / primary args |
| --- | --- |
| `normalize_resolved_things` | `fanOutNodeId`, `maxRows`; optional `onUnresolved`, `minResolvedRows` |
| `extract_from_tool_output` | `sourceNodeId`, `mode`, `arrayPath`, `fields[]`, `maxRows` |
| `build_nested_object` | `sources{}`, `template`, `maxParents`, `maxChildren`, `minParents`; optional `omitNull` |
| `json_stringify` | `value`, `maxBytes` |
| `resolve_time_window_for_playbook` | `quickIntervalRows`, `timezone`; optional `phrase`, `defaultQuickIntervalName`, `onUnsupported` |
| `empty_rows_if_skipped` | `sourceNodeId`; optional `label` |
| `merge_row_sets` | non-empty `sources[]` (`$ref` only), `maxSources`, `maxRows` |
| `add_computed_fields` | `rows`, non-empty `fields[]` (`as`, `expr` with `op` + dotted `left`/`right`); optional `onNull`, `maxRows` |
| `collect_values` | `rows`, dotted `field`; optional `maxValues` |
| `join_values` | `values` (array or `$ref`); optional `delimiter`, `maxLength` |

**Generic asset-pair reference:** `docs/agent/playbook-engine-cross-asset-pair-health-generic.json` — authored DAG after taxonomy / pair resolution using Priority 1 generic ops + **`query_alert_history`** / **`query_property_history`** (validated in **`PlaybookReferenceGenericAssetPairHealthTest`**).

`select_primary_problem_property` contains business judgment, so it uses a conservative deterministic heuristic:
  - prefer exact `sourceProperty` values that appear in current alerts
  - prefer a user-named dimension only when it maps to an exact property name from evidence
  - avoid translated labels or free-form business phrases
  - if no safe property exists, mark trend as not applicable and surface an evidence gap

It is part of the V1b surface only; keeping such judgment in named, bounded transforms prevents business logic from leaking into the generic engine.

### 4.5 `condition`

`condition` (V1b surface) branches for clarification, optional history reads, or workflows such as `cross_asset_pair_health`. `cross_region_health` is linear and does not use it.

Conditions use a tiny predicate set:

```json
{
  "id": "need_clarification",
  "kind": "condition",
  "dependsOn": ["resolved_assets"],
  "if": {
    "any": [
      { "op": "is_empty", "value": { "$ref": "resolved_assets.output.assetA" } },
      { "op": "is_empty", "value": { "$ref": "resolved_assets.output.assetB" } },
      { "op": "gt", "left": { "$ref": "resolved_assets.output.ambiguousCount" }, "right": 0 }
    ]
  },
  "then": "clarify_asset_identity",
  "else": "alert_summaries"
}
```

Supported predicates:

- `is_empty`
- `is_present`
- `eq`
- `ne`
- `gt`
- `gte`
- `lt`
- `lte`
- `and`
- `or`
- `any` (disjunctive; evaluated the same as `or` in the Java runtime)
- `not`

No regex, no arbitrary functions, no JavaScript evaluation.

**Service error guard (C13 — recipe, no `guard_tool_result` op).** A **`tool_call`** node
always records top-level **`status: "ok"`** when the Playbook runtime finished dispatch;
extended-tool and built-in failures appear on the inner envelope as
**`toolOutput.status`**, **`toolOutput.code`**, and **`toolOutput.message`**. Branch with
**`condition`** **before** downstream derives that assume success rows or scalar fields:

```json
{
  "id": "lookup_ok",
  "kind": "condition",
  "dependsOn": ["product_lookup"],
  "if": {
    "op": "ne",
    "left": { "$ref": "product_lookup.toolOutput.status" },
    "right": "success"
  },
  "then": "record_lookup_gap",
  "else": "extract_product_rows"
}
```

Use **`eq` / `ne` / `is_empty` / `is_present`** on **`$ref`** paths such as
**`<nodeId>.toolOutput.status`** or **`<nodeId>.toolOutput.code`**. The **`then`** branch
can terminate in **`llm_summary`** with an evidence gap, **`collect_gaps`**, or a stub
derive — the engine does **not** ship a compact **`guard_tool_result`** wrapper op; this
predicate pattern is the normative guard. Same inner-envelope rule applies to built-in
**`resolve_thing`** (see **`playbook-input-resolution.md`** §6.0.2) before terminal
**`normalize_resolved_thing`**.

### 4.6 `llm_summary`

`llm_summary` is where Playbook gets back the "human response" advantage of the LLM:

```json
{
  "id": "final_summary",
  "kind": "llm_summary",
  "dependsOn": ["region_summary"],
  "prompt": "Write a concise cross-region operational diagnosis. Use only the provided playbook evidence.",
  "evidenceRefs": ["region_summary"],
  "maxEvidenceBytes": 8000
}
```

Rules:

- the LLM receives compact evidence, not raw node outputs
- final answer must cite evidence gaps
- at most one `llm_summary` is allowed, and it must be the final node

## 5. Expression and Reference Model

Playbooks support references, not a general expression language.

Allowed value forms:

```json
"literal string"
123
true
{ "$input": "assetType" }
{ "$var": "taxonomy.EntityName" }
{ "$ref": "list_candidates.output.rows" }
{ "$ref": "machine_listing.toolOutput.result.rows" }
{ "$item": "name" }
```

`$ref` normally navigates already-structured JSON only. The single bounded normalization exception is a continuing path through `toolOutput.result`: if that segment is a JSON string, it is parsed as an object or array before the remaining segments are resolved. This matches JSON-returning extended-tool envelopes without changing the saved raw tool output.

**`$item` binding (C17).** Inside a **`fan_out`** child **`tool_call`**, **`{ "$item": "<path>" }`**
navigates the current fan-out item object by dotted path. When **`items`** resolves to a
**primitive `string[]`** (or other non-object scalars), the runtime wraps each element as
**`{ "<itemVar>": "<string value>" }`** where **`itemVar`** is the fan-out node's declared
**`itemVar`** field (default **`value`** when omitted). Bind with **`{ "$item": "<itemVar>" }`** —
for example **`cross_region_health`** **`assets_by_region`** uses **`itemVar: "region"`** and
**`{ "$item": "region" }`**. When **`items`** is an array of row objects, bind the actual field
name (for example **`"name"`**, **`"thingName"`**). Normative detail:
**`playbook-input-resolution.md`** §6.0.6.

```json
{ "$infotable": { "rows": { "$ref": "uid_rows.output.rows" }, "dataShapeName": "PTC.SCA.SCO.Utilities.UID" } }
```

**`$infotable`** is valid only as the sole value of a **top-level** extended-tool
**`tool_call.args`** parameter (routes to **`infotableArgs`**). Derive ops bind row inputs
via **`$ref`** / inline arrays, not **`$infotable`**.

Path grammar:

```text
identifier(.identifier)*
```

No arbitrary JSONPath:

- no recursive descent
- no filters
- no script expressions
- no regex

For arrays, use transform nodes such as `extract_field`, `group_alerts_by_source_property`, or `match_entity_identifiers` rather than inline selectors.

Service-orchestration playbooks (**`playbook-34-35`**) add orchestration path grammar for **`extract_from_tool_output`**, nested-object template directives, and **`add_computed_fields`** expr operands — all validated at load time (see §4 generic / service-orchestration tables and `docs/agent/playbook-34-35.md` §6.3).

## 6. Evidence Ledger

Every node produces a compact `PlaybookNodeEvidence` record:

```json
{
  "nodeId": "alert_summaries",
  "kind": "tool_call",
  "status": "ok",
  "label": "Read current alert summaries",
  "summary": "Read alert summaries for 6 assets; 17 alert rows; grouped by 4 source properties.",
  "tool": "query_alert_summary",
  "rowCount": 17,
  "totalCount": 17,
  "cacheId": null,
  "chartRefs": [],
  "tableRefs": [],
  "errorCode": null,
  "elapsedMs": 124
}
```

Evidence rules:

- no raw rows in evidence
- no raw property values in `task.state`
- compact values may appear in final LLM evidence when they are necessary and not protected
- raw node outputs remain in in-memory run state or existing cache/artifact references
- evidence is current-turn only

The final LLM call receives a compact block like:

```text
Playbook evidence:
- taxonomy_row: matched Stacking Robot -> ThingShape PTCTDD.CellfabDataset.StackingRobot_TS
- list_assets: USA has 4 Stacking Robots; Germany has 2 Stacking Robots
- alert_summaries: USA has more alert rows and more emergency-stop alerts; Germany has fewer rows but wrist temperature alerts remain active
- current_values: read current operational properties for scoped robots
Evidence gaps: no current speed alerts were found; live values may be missing for disconnected assets.
```

This is why Playbook can still produce a human final answer without putting the LLM in charge of every step transition.

## 7. UI Wire and Progress

Playbook progress uses the existing `task.state` frame shape:

```json
{
  "type": "task.state",
  "conversation_id": "conv-123",
  "request_id": "req-456",
  "schemaVersion": 1,
  "status": "executing",
  "title": "Cross-region operational diagnosis",
  "summary": {
    "playbookId": "cross_region_health",
    "total": 6,
    "satisfied": 3,
    "inProgress": 1,
    "failed": 0,
    "blocked": 0
  },
  "items": [
    {
      "id": "taxonomy_row",
      "source": "playbook",
      "kind": "evidence",
      "label": "Resolve asset type",
      "status": "satisfied",
      "summary": "Matched Stacking Robot"
    }
  ]
}
```

Implementation notes:

- There is no separate playbook frame type.
- `source=playbook` is a semantic extension to `task.state`, specified in `CONTRACTS/API_CONTRACT.md`, `CONTRACTS/UI_CLIENT_PROTOCOL.md`, and `docs/agent/task-state.md`.
- The UI already replaces full `task.state` snapshots and can render unknown `source` strings defensively.
- Progress is active-turn only.
- `PlaybookTaskProgress` / `PlaybookTaskProgressEmitter` map playbook nodes to `task.state` snapshots with `source=playbook`; skill v1b hooks are suppressed during active playbook turns.
- During a playbook turn, playbook progress is the primary process panel for that turn. Skill checklist `task.state` output does not compete with it in the same response bubble.
- Successful final turns follow existing UI behavior: hide the process panel after final response unless failed/blocked/attention-required.
- During execution, Playbook progress updates after meaningful node lifecycle changes.

Emission points:

1. playbook accepted / validated
2. node started
3. node completed
4. fan-out item batch progress changed
5. node failed
6. playbook failed
7. before final LLM summary starts
8. before `session.done`

Coalesce updates inside a Java call stack to avoid noisy frames.

## 8. Lifecycle and Cancellation

Lifecycle:

```text
requested -> loaded -> validated -> running -> summarizing -> completed
                                  -> failed
                                  -> cancelled
```

Cancellation rules:

- If Live Chat disconnects, the active playbook run is cancelled.
- If a new request supersedes the active request, the active playbook run is cancelled.
- In one conversation, PlaybookRunner shares the same serial turn constraint as AgentLoop: only one Playbook run may be active.
- If one LLM turn calls `start_playbook` more than once, the second call is rejected with a structured error.
- The single-run constraint is per conversation. Parallel Playbook runs in different conversations are independent.
- A non-playbook user message in the same conversation while a Playbook is active cancels the Playbook with status `cancelled`, then the new message proceeds through the normal AgentLoop.
- If the AgentThing stops, the run is abandoned.
- If the JVM restarts, the run is lost.
- No attempt is made to resume from Stream, DataTable, or previous run id.

The engine checks cancellation between nodes and between fan-out items. It does not interrupt an already-running ThingWorx service call.

User-facing behavior:

- disconnect / Agent restart / JVM restart: report that the workflow was cancelled and should be started again if the UI is still able to show a final error
- `needs_clarification`: end the current run with a targeted question; the next user message starts a new run
- HITL-triggering tools are not playbook-safe; a playbook never half-pauses across reconnects

**AgentLoop transcript invariant (successful `start_playbook` terminal handoff):** When `AgentLoop` ends a turn mid-batch because `start_playbook` returned a terminal playbook answer, it must not leave assistant `tool_calls` rows with unmatched tool-call ids. Sibling tools in the same batch are not executed; instead, Parler appends synthetic `tool` rows with `code: PLAYBOOK_TERMINAL_HANDOFF` for each skipped sibling before compaction and return. This keeps OpenAI/Azure and Anthropic replay valid on the next turn and after AlwaysOn Stream rehydrate. Any early return from inside the tool-call loop without a resume path must follow the same rule.

## 9. Rate-Control and Telemetry

Playbook does not bypass Provider rate-control. It reduces pressure by reducing LLM calls.

Emit one summary log at run end:

```text
LLM_PLAYBOOK_RUN playbookId=cross_region_health runId=... status=completed
elapsedMs=... nodeCount=... toolCallCount=... llmCallCount=...
promptTokens=... completionTokens=... llmUsageJsonPresent=true
localAdmissionWaitMs=... finalEvidenceBytes=-1 failureCode=-
```

`PlaybookRunner` accumulates token usage in **`PlaybookRunContext`** during the run; the
**`PlaybookRunResult`** carries **`StreamTokenUsage`** for the final assistant row on slash /
playbook terminal turns. **`llmUsageJsonPresent`** is true when the accumulated usage JSON is
non-empty. **`finalEvidenceBytes`** is a reserved placeholder and always logs **`-1`**
(the evidence ledger size is not surfaced on this line). AgentLoop-lane usage telemetry
remains in **`docs/agent/llm-usage-stream-telemetry.md`**.

`localAdmissionWaitMs` accumulates local rate-control wait from LLM calls made by the playbook, not tool calls; it logs `-1` when no value was recorded (use the `LLM_RATE_ADMISSION` logs then).

`llm_summary` must use the same Provider resolution and rate-control path as the normal AgentLoop. `PlaybookRunner` must resolve the configured Provider through the Provider/LLM client bridge used by Agent turns; it must not instantiate a provider client directly or bypass local rate-control admission.

`llmCallCount` definition:

- count Provider chat/completion calls made to produce the turn, including final `llm_summary`
- do not count deterministic Java node execution
- do not count `start_playbook` dispatch itself when slash routing avoids an LLM routing call
- for natural-language `start_playbook` evaluation, count the routing/extraction LLM call and the final `llm_summary`

Evaluation: `docs/agent/evals/cross_region_health_v1a.yaml` compares the playbook against the `region_health` skill on
the fixed happy path (taxonomy resolution, region-scoped assets, current alerts, current values, concise comparison, and
evidence gaps) — `llmCallCount`, wall-clock time, upstream 429s, `task.state` leakage (none allowed), and final-answer
quality. Unrelated expansion queries the skill sometimes performs do not count against the playbook.

## 10. Reference Conversion Shapes

### 10.1 `cross_region_health`

**Current shape** (multi-Thing alert summary):

1. `derive: pick_taxonomy_row`
2. `fan_out` hierarchy nodes:
   - `query_entities_by_taxonomy`
3. `derive: flatten_region_entities`
4. `derive: collect_thing_names_from_assets` — bounded **`thingNames[]`** for alert comparison
5. `tool_call: query_alert_summary` with **`thingNames`** ref → **`ALERT_SUMMARY_MULTI`**
6. `derive: group_alerts_by_source_property` with **`alertSummaryNodeId`** (not per-asset alert fan-out)
7. `derive: build_property_union` when current-value reads need a bounded property list
8. `fan_out` resolved Things:
   - `get_property_values` (property reads still fan out per Thing)
9. `derive: summarize_current_values_by_region` (rollup of the `get_property_values` fan-out for `llm_summary`)
10. `derive: summarize_region_health`
11. `llm_summary`

The legacy per-asset alert pattern (a **`fan_out`** calling **`query_alert_summary`** per Thing, then **`group_alerts_by_source_property`** with **`fanOutNodeId`**) is not used for new authoring; the **`fanOutNodeId`** derive path remains for compatibility tests only.

Reference artifacts:

- `docs/agent/playbook-engine-cross-region-health.json`: reference DAG
- `dev_data/playbooks/cross_region_health/playbook.json`: importable runtime copy

The two files must stay structurally equivalent; `PlaybookReferenceParityTest` fails `./gradlew test` on a structural diff.

Reference DAG outline:

```json
{
  "_note": "Non-normative outline. The full reference JSON file is the source of truth.",
  "schema": "parler-playbook-v1",
  "title": "Cross-region operational diagnosis",
  "nodes": [
    { "id": "taxonomy_row", "kind": "derive", "op": "pick_taxonomy_row" },
    { "id": "assets_by_region", "kind": "fan_out", "items": { "$input": "regions" } },
    { "id": "region_entities", "kind": "derive", "op": "flatten_region_entities" },
    { "id": "alert_thing_names", "kind": "derive", "op": "collect_thing_names_from_assets" },
    { "id": "alerts_by_region", "kind": "tool_call", "tool": "query_alert_summary" },
    { "id": "alert_groups", "kind": "derive", "op": "group_alerts_by_source_property", "args": { "alertSummaryNodeId": "alerts_by_region" } },
    { "id": "property_union", "kind": "derive", "op": "build_property_union" },
    { "id": "values_by_asset", "kind": "fan_out", "items": { "$ref": "region_entities.output.assets" } },
    { "id": "current_value_stats", "kind": "derive", "op": "summarize_current_values_by_region" },
    { "id": "region_summary", "kind": "derive", "op": "summarize_region_health" },
    { "id": "final_summary", "kind": "llm_summary" }
  ],
  "finalNode": "final_summary"
}
```

The actual JSON must include full `dependsOn`, tool names, argument refs, evidence labels, and budgets. The outline above is not sufficient as the runtime file.

### 10.2 `cross_asset_pair_health` (V1b surface)

Playbook id **`cross_asset_pair_health`** (skill baseline **`asset_pair_health`** keeps the short id). Runtime file: `dev_data/playbooks/cross_asset_pair_health/playbook.json` (taxonomy candidate-list variant below). The resolver-first variant (`resolve_thing` → `normalize_resolved_thing` → `flatten_pair_assets`, `assetType` optional) is in `dev_data/sample_scpa_utilization_agent_configuration/playbooks/cross_asset_pair_health/` and the packaging fixture; see `playbook-input-resolution.md`.

Candidate-list shape:

1. `derive: pick_taxonomy_row`
2. `tool_call: query_entities_by_taxonomy` with exact `LookupProperties` for each identifier when possible
3. if exact lookup fails:
   - `tool_call: query_entities_by_taxonomy` without lookup to list candidates
   - `derive: match_entity_identifiers`
4. `condition`: if zero/multiple match, ask clarification and end the current run with a targeted question
5. `derive: collect_thing_names_from_assets` → one `tool_call: query_alert_summary` with **`thingNames[]`**
6. `derive: group_alerts_by_source_property` with **`alertSummaryNodeId`**
7. `fan_out` two resolved Things:
   - `get_property_values`
8. `derive: select_primary_problem_property`
9. if numeric/safe:
   - `fan_out` two resolved Things:
     - `query_property_history`
10. `derive: summarize_asset_pair_health`
11. `llm_summary`

Clarification stance:

- A Playbook may fail with `needs_clarification` and a targeted question.
- It does not preserve run state across the user's next turn.
- The next user turn starts a new playbook run with the clarified input.

User clarification is comparatively rare, and the no-resume rule keeps the runtime simple.

## 11. Implementation Map

| Area | Classes |
|------|---------|
| Registry and slash conflicts | `PlaybookRegistryBuilder`, `PlaybookIds`, `PlaybookCatalogEntry`; skill loading receives `reservedSlashIds()` |
| Validation | `PlaybookValidator` (hard guards, `playbookSafe` allowlist, derive-op arg checks), `PlaybookToolAllowlist`, `PlaybookToolDefinitionsMerge` |
| Runtime | `PlaybookRunner`, `PlaybookRunContext`, `PlaybookExpressionResolver`, `PlaybookConditionEvaluator` |
| Derive ops | `PlaybookDeriveOps` (V1a), `PlaybookDeriveOpsV1b`, `PlaybookGenericDeriveOps` and `PlaybookGeneric*` helpers, service-orchestration ops (`playbook-34-35.md`) |
| Evidence | `PlaybookEvidenceFormatter` |
| Progress wire | `PlaybookTaskProgress`, `PlaybookTaskProgressEmitter` |
| Reference DAG tests | `PlaybookReferenceParityTest`, `PlaybookReferenceGenericAssetPairHealthTest`, `PlaybookValidatorTest` |

Design choices that hold across the engine:

1. Progress uses existing `task.state` frames, not a new `playbook.*` wire type.
2. Fan-out executes sequentially.
3. Runs do not resume after JVM restart, AgentThing restart, or Live Chat disconnect, and run state is not persisted.
4. There are no arbitrary `service_call` nodes; Playbooks call registered tools.
5. Playbooks are static repository documents, not Provider-generated.
