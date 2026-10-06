# Playbook App Tool Workflows

Status: **implemented** in **`parler-agent`**: app playbook catalog / extended-tool **`playbookSafe`** / **`$table`** (top-level service-parameter binding; validate-time unknown-node check) / raw row budget / evidence projection; **`PLAYBOOK_TOOL_NOT_EXTENDED`**. Utilization eval: playbook **`toolsCalledSubsequence`** follows **BFS**; skill baselines follow **`SKILL.md`** tool order.

This document defines how static Playbooks execute application-defined, read-only **extended tools** and chain their `INFOTABLE` results safely between steps.

The motivating case is the Utilization example application:

- service notes: `dev_data/sample_scpa_utilization_design/utilization_service.md`
- extended tools: `dev_data/sample_scpa_utilization_agent_configuration/tools/extended_tools.json`
- validated skill baselines:
  - `dev_data/sample_scpa_utilization_agent_configuration/skills/utilization_summary/SKILL.md`
  - `dev_data/sample_scpa_utilization_agent_configuration/skills/machine_utilization_summary/SKILL.md`
  - `dev_data/sample_scpa_utilization_agent_configuration/skills/utilization_overview/SKILL.md`
- playbook samples:
  - `dev_data/sample_scpa_utilization_agent_configuration/playbooks/utilization_summary/playbook.json`
  - `dev_data/sample_scpa_utilization_agent_configuration/playbooks/machine_utilization_summary/playbook.json`
  - `dev_data/sample_scpa_utilization_agent_configuration/playbooks/utilization_overview/playbook.json`

The intended onboarding order is skill first: the skill route proves the application workflow, tool schemas, time bounds, and `INFOTABLE` handoff before the same path is promoted to a deterministic Playbook.

## 1. Problem

The first Playbook support was built around a small set of built-in, read-only tools:

- `query_entities_by_taxonomy`
- `query_alert_summary`
- `get_property_values`
- `query_property_history`

That was enough for the first health workflows. It is not enough for application workflows such as Utilization, where the useful operations already exist as ThingWorx services and are exposed through `/tools/extended_tools.json`.

The Utilization workflow needs all of these:

1. Playbook `tool_call` can call repository extended tools, not only built-ins.
2. Only explicitly safe, read-only extended tools are eligible.
3. A service result `INFOTABLE` can be passed to the next service's `INFOTABLE` input without going through LLM text.
4. Final playbook evidence can include a compact projection of aggregate/stat rows, not just "tool returned N rows".
5. The playbook catalog can register app-specific static playbooks without hardcoded Java ids for every new app workflow.

The desired runtime shape is:

```text
User request
  -> route to registered Playbook
  -> runtime executes app tools deterministically
  -> runtime builds compact evidence
  -> one final LLM summary over that evidence
```

This is still not a planner. Playbooks are registered workflows. They do not synthesize arbitrary plans.

## 2. Motivating Workflows

The design was driven by these multi-step Utilization skill routes. (The example data in `dev_data/sample_scpa_utilization_agent_configuration/` has since moved some routes to a single state-summary service; the chain shapes below remain the pattern `$table` serves.)

### `utilization_summary`

Reference file:

```text
dev_data/sample_scpa_utilization_agent_configuration/skills/utilization_summary/SKILL.md
```

Validated route:

```text
utilization_records(StartDate, EndDate, ShiftID?)
  -> utilization_aggregate_by_state(UtilizationRecords)
    -> utilization_stats_for_aggregate(AggregatedByUtilizationStateData)
```

Meaning:

- Query all utilization-capable machines over a time window.
- Aggregate records by `UtilizationState`.
- Compute overall utilization statistics from the aggregate table.

### `machine_utilization_summary`

Reference file:

```text
dev_data/sample_scpa_utilization_agent_configuration/skills/machine_utilization_summary/SKILL.md
```

Validated route:

```text
utilization_records_by_machine(StartDate, EndDate, ShiftID?, Machine)
  -> utilization_aggregate_by_state(UtilizationRecords)
    -> utilization_stats_for_aggregate(AggregatedByUtilizationStateData)
```

Meaning:

- Resolve one machine.
- Query machine-scoped utilization records.
- Aggregate and summarize exactly as the all-machine detail workflow does.

Playbooks assume the `machine` input is already a usable machine identifier. Fuzzy display-name resolution is a skill / resolver concern.

### `utilization_overview`

Reference file:

```text
dev_data/sample_scpa_utilization_agent_configuration/skills/utilization_overview/SKILL.md
```

Validated route:

```text
utilization_machine_listing(UsesSelection=false)
utilization_machine_listing_with_dates(StartDate, EndDate, ShiftID?, Machines)
utilization_aggregate_by_state_time_fence(StartDate, EndDate, ShiftID?)
```

Meaning:

- Read utilization-capable machine coverage.
- Read machine effective date fences for the requested window.
- Read time-fenced state aggregation through the UI overview service path.

This workflow is deliberately not the same as `records -> aggregate -> stats`; it follows the app's overview service surface.

## 3. Scope

### In Scope

The runtime covers the smallest generic surface needed for app workflows like Utilization:

- static app-specific playbook catalog entries
- playbook-safe extended tool registration
- extended tool execution from Playbook `tool_call`
- raw `INFOTABLE` result capture inside the current playbook run
- raw `INFOTABLE` input binding into later extended tool calls
- configured compact table evidence for final `llm_summary`

### Out of Scope

- Property Role / semantic property mapping
- dynamic planner or provider-generated PlaybookJson
- write tools or HITL playbooks
- cross-restart resume
- cross-conversation resume
- automatic machine fuzzy matching beyond what the playbook input already supplies
- generic DataTable query tools
- chart generation for Utilization
- multi-series chart composition
- arbitrary expression language or JavaScript in playbooks

## 4. Design

### 4.1 App-Specific Static Playbooks

Playbook ids are not hardcoded in Java, so applications can onboard their own playbooks. Registry rules:

- Each playbook is a directory package:

```text
/playbooks/<id>/playbook.json
```

- `id` grammar:

```text
[A-Za-z][A-Za-z0-9_]{0,63}
```

- The document root `id` must match the parent directory name.
- Former catalog metadata (`title`, `description`, `whenToUse`, `inputSchema`, `execution`) lives in the same `playbook.json` root as the DAG.
- The `playbook.json` root rejects a `provider` field. Provider-backed / generated playbooks are not supported.
- Successfully loaded playbook packages are capped at 32 total entries.
- Existing skill/playbook slash-name conflict policy remains:
  - playbook wins;
  - conflicting skill is ignored;
  - conflict is logged once per conflicting id during prompt context refresh / registry load, not once per turn;
  - remaining skills still load.

The loader uses partial success: malformed package documents are reported as configuration diagnostics, while valid sibling packages still load.

### 4.2 Extended Tool Playbook Safety

Extended tools are LLM-visible app tools loaded from:

```text
/tools/extended_tools.json
```

Not every extended tool is Playbook-eligible. Optional field:

```json
{
  "name": "utilization_records",
  "hitl": false,
  "playbookSafe": true
}
```

Rules:

- `playbookSafe` defaults to `false`.
- `playbookSafe: true` means the author explicitly allows this tool to be used by static Playbooks.
- A tool is effective-playbook-safe only when:
  - it is a valid registered extended tool;
  - `playbookSafe` is `true`;
  - `hitl` is explicitly `false`;
  - PASSWORD discovery did not omit the tool;
  - schema generation succeeded.
- If `playbookSafe: true` is set on a `hitl: true` tool, the tool may still register as an LLM extended tool, but it must not be Playbook-safe. Log a warning diagnostic.
- Built-in tools keep their current Java `playbookSafe` flag.

This keeps the boundary tight:

```text
LLM can call registered extended tools.
Playbook can call only explicitly playbook-safe read-only extended tools.
```

### 4.3 Tool Registry and Validator

The effective tool registry used by Playbook validation must include:

- built-in tools whose `ToolDefinition.playbookSafe == true`;
- extended tools whose effective playbook-safe flag is true.

Validation rules:

- `tool_call.tool` must exist in the merged registry.
- The effective `ToolDefinition` must be playbook-safe.
- For fan-out child `tool_call`, the same rule applies.
- Unknown tools still fail validation.

`PlaybookToolAllowlist` is only a built-in compatibility anchor, not the global validation gate.

The validator makes the final decision from the merged effective registry:

```text
built-in ToolDefinition.playbookSafe == true
OR
extended tool effectivePlaybookSafe == true
```

Deprecated V1a aliases may continue to delegate to the helper if that avoids churn, but any hardcoded global list that blocks application-defined safe tools is a bug.

### 4.4 Extended Tool Execution in Playbooks

Playbook `tool_call` executes extended tools through the same service metadata, argument coercion, time resolver, and PASSWORD protection rules used by normal extended tool calls.

However, Playbook execution also needs access to raw tabular results for later nodes. A pure string JSON result is not enough when a later service expects an `INFOTABLE`.

Internal result shape:

```text
PlaybookToolResult
  jsonForEvidence: JSONObject
  tableOutputs: Map<String, InfoTable>
```

For an extended tool whose service result is `INFOTABLE`:

- retain the raw result InfoTable in the current run context;
- also retain the normal LLM JSON envelope for evidence and UI behavior;
- assign a stable table reference key, such as:

```text
<nodeId>.result
```

Example:

```text
records.result
aggregate_by_state.result
```

The raw table is current-run memory only. It is not persisted and is discarded when the playbook run finishes.

The execution path reuses the normal extended-tool metadata, argument coercion, time resolution, and protection checks; there is no second service invocation implementation for Playbooks. Raw `InfoTable` access before the normal LLM JSON formatting step goes through a helper shared with the extended-tool executor.

Raw table retention is bounded per playbook run:

- default `budgets.maxRawTableRows`: `100000` cumulative rows across all retained raw tables in one run;
- a playbook may lower this value;
- exceeding the cap fails the current node with a targeted table-budget error;
- no silent truncation is allowed for raw tables used by `$table`.

The row cap is intentionally simpler than a byte estimator. Evidence projection has its own byte cap; raw table retention has the row cap above.

Only one raw output key is required in v1:

```text
<nodeId>.result
```

Multiple raw output keys per node are out of scope until a real service returns more than one table that another node must consume.

### 4.5 InfoTable Chaining

JSON row references such as:

```json
{
  "UtilizationRecords": { "$ref": "records.toolOutput.rows" }
}
```

That works only when the prior output is small enough to be inline. It is not robust for large `INFOTABLE` results, because `INFOTABLE_LARGE` may have only `sampleRows` and `cacheId`, which are not sufficient for service-side aggregation.

Playbooks therefore use an explicit table-reference expression:

```json
{
  "UtilizationRecords": { "$table": "records.result" }
}
```

Rules:

- `$table` resolves to an in-memory `InfoTable` stored by a prior tool node.
- `$table` is valid only as the **entire** value of a **top-level** `tool_call.args` parameter (exactly `{ "$table": "<nodeId>.result" }` per service parameter). Nested `$table` (for example under a wrapper object) is rejected: service parameters are top-level bindings, not arbitrary JSON expressions. The validator rejects misplaced `$table` (derive args, `fan_out.items`, condition predicates, `llm_summary`, `tool_call` fields outside `args`, or nested placement inside `args`). Runtime `resolve()` rejects `$table` outside that binding shape with **`TABLE_REF_NOT_ALLOWED_HERE`**.
- `$table` is resolved during argument binding, not by JSON serialization.
- `$table` references count as graph dependencies. The same validation walker that prevents orphan nodes for `$ref` must also recognize `$table`.
- If the prior table is missing, fail the node with a structured playbook error.
- If the target parameter DataShape and prior result DataShape are both known named DataShapes, compare by DataShape name:
  - same name: pass;
  - different names: fail before service invocation;
  - either side missing / anonymous / field-only: allow and log a debug diagnostic.
- Do not do field-level subset comparison in v1. It is too fragile for schema evolution.
- `$ref` remains for JSON scalar/object/array use.

Structured error codes:

```text
TABLE_REF_NOT_FOUND
TABLE_REF_INVALID_FORMAT
TABLE_REF_NOT_ALLOWED_HERE
TABLE_REF_NOT_INFOTABLE_PARAM
TABLE_REF_DATASHAPE_MISMATCH
TABLE_REF_RAW_BUDGET_EXCEEDED
PLAYBOOK_TOOL_NOT_EXTENDED
PLAYBOOK_TOOL_NOT_PLAYBOOK_SAFE
```

In a records → aggregate → stats chain, the two handoff points are:

```json
{
  "UtilizationRecords": { "$table": "records.result" }
}
```

```json
{
  "AggregatedByUtilizationStateData": { "$table": "aggregate_by_state.result" }
}
```

This is the central difference between an LLM-facing tool transcript and a runtime workflow engine: Playbook should not serialize large tables through text just to pass them to the next Java service.

### 4.6 Time Parameters

The Utilization services use ThingWorx service-declared names:

```text
StartDate
EndDate
```

Parler's custom / extended tool time pair resolver already recognizes `startDate + endDate` and `startTime + endTime` case-insensitively when both are typed `DATETIME`. It preserves service-declared casing when writing resolved values.

Playbooks reuse that behavior; there is no separate time API:

- explicit bounds are passed as `StartDate` / `EndDate`;
- `calendarPhrase` / `relativeDuration` may be accepted for extended tools when schema augmentation applies;
- for deterministic Utilization tests, use fixed dates inside `2025-09-03` to `2025-09-26`.

### 4.7 Generic InfoTable Evidence Projection

The final LLM summary must receive useful evidence. For Utilization, row count alone is not enough.

Optional evidence projection config for `tool_call` nodes:

```json
{
  "evidence": {
    "label": "Aggregate utilization records by state",
    "table": {
      "maxRows": 12,
      "columns": [
        "UtilizationState",
        "SUM_Duration_Hours",
        "COUNT_Duration",
        "Percentage"
      ]
    }
  }
}
```

For one-row stats:

```json
{
  "evidence": {
    "label": "Compute utilization statistics",
    "table": {
      "maxRows": 1,
      "columns": [
        "UtilizationPercent",
        "DowntimePercent",
        "UptimeString",
        "DowntimeString",
        "TotalTimeString",
        "EventCount"
      ]
    }
  }
}
```

Rules:

- Evidence projection is read-only and capped.
- Projection source order is:
  1. raw current-run `InfoTable` for `<nodeId>.result`, when present;
  2. JSON envelope `rows`, for small inline table-like results;
  3. JSON envelope `sampleRows`, only with an explicit evidence note that the table is sampled and not complete.
- For playbook tool calls that returned an `INFOTABLE`, raw current-run table is the expected source. If only `sampleRows` is available for such a node, do not silently treat it as complete evidence.
- Missing columns are ignored with a short note, not fatal.
- Values are converted to compact strings / JSON scalars.
- Raw records should normally use row count only; aggregate/stat tables may project selected rows.
- The projection must respect the node's `maxEvidenceBytes`.
- Do not include arbitrary raw table dumps in final evidence.

This is intentionally simpler than a generic analytics engine. It only gives the LLM the selected evidence needed to write a grounded final answer.

### 4.8 Utilization Playbooks

App playbooks that chain tables use `$table` for handoffs and projected table evidence for aggregate/stat nodes. The Utilization example ids are:

```text
utilization_summary
machine_utilization_summary
utilization_overview
```

If these collide with skills in the same repository, the namespace rule applies: playbook wins and the same-name skill is ignored. For A/B testing, load skills and playbooks separately or use separate AgentThing / configurationRepository snapshots.

The Java runtime does not depend on the contents of `dev_data/sample_scpa_utilization_agent_configuration/*`.

## 5. Testing

### Unit Tests

Tests cover:

- `playbookSafe` default false.
- `playbookSafe: true` with `hitl: false` becomes effective playbook-safe.
- `playbookSafe: true` with `hitl: true` does not become playbook-safe.
- directory discovery accepts non-hardcoded app playbook ids that pass grammar/path validation.
- package metadata rejects `provider`.
- playbook document body rejects `provider`.
- package id mismatch with parent directory is rejected.
- directory discovery caps successfully loaded packages at 32 entries.
- playbook validator accepts effective playbook-safe extended tools.
- playbook validator rejects non-safe extended tools.
- `$table` resolves only to existing raw table outputs; unknown source node ids are rejected at document validation time (in addition to runtime `TABLE_REF_NOT_FOUND` when a retained table is missing).
- `$table` references are counted by graph dependency / orphan validation.
- `$table` cannot be used for scalar parameters.
- DataShape name mismatch, when both sides are known named DataShapes, fails before invocation.
- unknown / anonymous DataShapes are allowed with diagnostic logging.
- raw table row budget overflow fails the current node without truncation.
- evidence table projection caps rows and ignores missing columns safely.
- evidence projection uses raw table before JSON envelope rows / sampleRows.

### Eval Coverage

The eval suite `docs/agent/evals/utilization_v1.yaml` makes the Utilization baselines machine-checkable. It does not check prose equality; it asserts:

- expected playbook or skill route is used;
- required tool calls appear in order;
- `invoke_service` fallback is not used;
- final answer contains utilization state / percentage evidence;
- no table-ref, validation, HITL, or PASSWORD error appears.

For **`toolsCalledSubsequence`**, distinguish **playbook** cases from **skill** baselines:

- **Playbook NL** cases should follow **`PlaybookRunner.topologicalOrder`** (BFS over the DAG; multiple zero-indegree roots follow document insertion order in the queue).
- **Skill** baselines should follow the tool order written in each repository **`SKILL.md`** (LLM-driven). That order may differ from the static playbook when the playbook has parallel roots (for example **`utilization_overview`**).

### Invariants

These behaviors are preserved:

- existing `cross_region_health` and `cross_asset_pair_health` still load and run;
- same-name skill conflict behavior is unchanged;
- malformed `extended_tools.json` still fails closed;
- malformed playbook catalog still fails closed;
- `hitl: true` extended tools remain callable by normal LLM flow but not by Playbook.

## 6. Design Constraints

### Risk: Making Every Extended Tool Playbook-Safe

Do not infer Playbook safety from existence alone. Require explicit `playbookSafe: true` and `hitl: false`.

### Risk: Serializing Large InfoTables Through JSON

Do not rely on `toolOutput.rows` for runtime chaining. It breaks when output becomes `INFOTABLE_LARGE`. Use raw in-memory table refs inside the current playbook run.

### Risk: Final LLM Sees Too Little Evidence

Row count is not enough for aggregate/stat tables. Add configured table projection, but keep it capped and explicit.

### Risk: Turning This into a Generic ETL Engine

Playbooks only support current-run table handoff between registered read-only tools. No durable table store, no arbitrary transforms, no SQL-like query layer.

### Risk: Namespace Confusion With Skills

For A/B testing, do not load same-name skills and playbooks in the same repository unless the test intentionally validates conflict behavior. Playbook priority over skill is expected.

## 7. Onboarding Path

Parler onboards a ThingWorx App workflow using this sequence:

```text
App services
  -> extended tools
  -> skill validation
  -> static playbook using the same tools
  -> final LLM summary over compact evidence
```

Application teams can expose their existing read-only ThingWorx services as extended tools, prove the workflow as a skill, then promote the stable path to Playbook for lower latency and better progress visibility.
