# Property history unification

**Status:** implemented (unified **`query_property_history`**, including the ThingName preflight on history paths).

Model-facing tool: **`query_property_history`**. Legacy executor-only aliases: **`query_numeric_property_history`**, **`query_value_stream_property_history`**.

## Background

Parler used to expose property history through two built-in tools:

- `query_numeric_property_history`
- `query_value_stream_property_history`

The split came from real implementation pressure:

- Numeric history is the common chart/trend path. It supports aggregate `actions`, compact LLM evidence, cache-backed
  full-series follow-up, and automatic Parler chart wire emission.
- Non-numeric value-stream history covered STRING / BOOLEAN / DATETIME-like histories. It shared natural time parsing
  with numeric history but not the same egress-hardened lane.

That split was an AI-facing design smell. Whether a property is numeric is a property attribute, not a different
operation. Users ask for "history of property X" or "trend/status over time"; they did not know which platform service
or tool name mapped to the property's base type. That split forced the model to choose between two names
before it had reliable property metadata, and the routing guide had to teach negative rules such as "use the other tool
for numeric".

Tool result egress control (`tool-result-egress-control.md`) also sets the standard: a history tool must not dump a full
point array into the prompt or stream replay. The unified tool keeps that protection and extends it to all supported
property types.

## Purpose

One model-facing operation replaces the two history tools:

```text
query_property_history
```

The tool chooses the platform query path after resolving property metadata, then returns bounded, cache-backed,
egress-hardened evidence appropriate to the property type.

This is not just a rename. It is a unification of semantics and egress behavior.

## Scope

In scope:

- `query_property_history` is the model-facing built-in.
- `query_numeric_property_history` and `query_value_stream_property_history` are not advertised.
- Old tool names stay executable for tests and historic rows.
- One natural-time argument contract for all property history.
- One cache/sample/storage-budget discipline for all supported property types.
- Numeric aggregate actions and chart behavior.
- Bounded non-numeric evidence for STRING / BOOLEAN / DATETIME-style histories.

Out of scope:

- Redesigning current-value reads; `get_property_values` remains separate.
- Thing member discovery — use **`discover_thing_members`** for concrete Thing property metadata; schema entities use **`describe_entity_schema`**.
- Generic Stream entity querying; `query_stream_data` remains separate.
- Full persisted restoration of every raw history point after cache expiry.

## Tool Semantics

Inputs:

| Field | Meaning |
|-------|---------|
| `thingName` | Canonical Thing name. |
| `propertyName` | Property name on that Thing. |
| `startTime` / `endTime` | Optional ISO-8601 bounds. |
| `calendarPhrase` | Optional local calendar day phrase where supported today. |
| `relativeDuration` | Optional duration ending now, such as `30m` or `24h`. |
| `maxRows` | Bounded platform read cap. |
| `actions` | Numeric-only aggregate actions. Ignored or rejected for non-numeric properties with a clear error. |
| `requestedTimeRange` | Chart-domain fallback only if still needed by the numeric chart path. |

The runtime determines property type from metadata:

- NUMBER / INTEGER / LONG and other approved numeric base types use numeric history behavior.
- STRING / BOOLEAN / DATETIME and other supported non-numeric types use value-stream history behavior.
- Unsupported or missing metadata returns a stable tool error with recovery guidance.

## Egress-Hardened Output

All success paths return compact LLM evidence. The model-facing result must not contain an unbounded `points`
array.

Numeric output:

- exact aggregates when `actions` are requested;
- compact `columns`, `sampleRows`, `totalRows`, `sampleOnly`, `rowsOmitted`;
- `cacheId` for deterministic follow-up;
- chart wire support without requiring the LLM-visible body to carry every point.

Non-numeric output:

- compact `columns`, `sampleRows`, `totalRows`, `sampleOnly`, `rowsOmitted`;
- `cacheId` for follow-up paging or tabular transforms;
- type-appropriate summary where cheap and deterministic, for example top values for STRING/BOOLEAN or min/max timestamp
  range for DATETIME values;
- no aggregates that imply numeric math unless the property type supports them.

The storage budget rules from the egress-control work apply to both numeric and non-numeric results.

## Chart Behavior

Numeric history emits the automatic chart.

Non-numeric history does not emit an automatic chart; its compact cached rows remain available to explicit chart tools.

## Compatibility

- Old tool names remain **executor-only aliases**; they are not registered as model-facing built-ins.
- Historic stream rows with old names remain readable as audit evidence.
- Tests and playbooks use **`query_property_history`** unless intentionally exercising legacy executor keys.
- Routing guide text teaches the unified tool, not numeric-vs-non-numeric tool selection.

## Verification

Build and tests:

- `./gradlew test -PuseLocalTwxLib=true --no-daemon`
- `./gradlew assemble -PuseLocalTwxLib=true --no-daemon`
- Schema-list test confirms only `query_property_history` is advertised.
- Numeric history tests still cover aggregates, natural time, cache registration, compact LLM body, and chart wire.
- Non-numeric history tests cover natural time, compact output, cache registration, unsupported numeric actions, and
  bounded stream/audit persistence.
- Egress tests confirm neither numeric nor non-numeric history writes full large point/value arrays into LLM replay.
- Playbook allowlist tests use the new tool name.

Manual check after deployment:

- Query a numeric trend and confirm compact tool evidence plus chart wire.
- Query a non-numeric/status history and confirm bounded evidence with no full-value dump.
- Ask a property-history prompt without specifying type and confirm the model uses `query_property_history`.

## Notes

If metadata lookup is unreliable, errors carry recovery guidance toward **`discover_thing_members`** (concrete Things)
or **`describe_entity_schema`** (schema entities) per **`metadata_discovery.md`**.

## Implementation

- **Executor-only legacy aliases:** `ToolRegistry.registerExecutorAlias(alias, canonical)` registers additional
  executor keys **without** `ToolDefinition` entries. `AgentThing.builtinToolDefinitionNames()` unions
  `getExecutorOnlyAliases()` so extended tools **cannot** claim `query_numeric_property_history` or
  `query_value_stream_property_history`.
- **Single model-facing definition:** `BuiltInTools` registers **`query_property_history`** only; aliases delegate to
  `PropertyToolsExecutor.executeQueryPropertyHistory`.
- **Routing:** `PropertyToolsExecutor.doQueryPropertyHistory` → Thing gate → TR-3 definition guard
  (`resolvePropertyDefinitionOutcome`; absent → **`PROPERTY_NOT_FOUND`**, unreadable definitions → fail closed through
  **`QUERY_PROPERTY_HISTORY_ERROR`**) → numeric branch (`doQueryNumericPropertyHistory`) vs non-numeric compact branch
  (`doQueryValueStreamPropertyHistoryCompact` + **`InvokeServiceExecutor.formatPropertyHistoryValueStreamCompact`**).
- **Unsupported or missing metadata returns a stable tool error:** after the Thing gate, a property name absent from
  `getInstancePropertyDefinitions()` returns **`PROPERTY_NOT_FOUND`** with **`recoveryHint`** to
  **`discover_thing_members`**; a definition read failure or unavailable collection fails closed (no history service
  called, not reported as not-found); an existing definition dispatches as before.
- **Non-numeric contract:** `$format` **`parler.value_stream_history.compact.v1`**, **`resultKind`** **`VALUE_STREAM_HISTORY_INLINE`**,
  always **`cacheId`**, capped **`sampleRows`**, **no** `points` array, **`chartEmitted: false`**. Any **`actions`** value
  other than absent or **`[]`** is rejected (**`NUMERIC_ACTIONS_UNSUPPORTED_FOR_PROPERTY_TYPE`** for a non-empty array;
  **`INVALID_ACTIONS_SHAPE`** for null / non-array). **`CompactFetchStreamRehydrate`** accepts the same compact family for
  Stream stage-2 rehydration (native compact and **`parler.infotable.matrix.v1`** matrix-sealed bodies that keep
  **`VALUE_STREAM_HISTORY_INLINE`**).
- **Playbooks:** `PlaybookDeriveOpsV1b.trendSummary` reads **`totalRows`** / **`returnedRows`** / **`pointsReturned`** fallbacks;
  `PlaybookNodeEvidence.enrichTrendEntry` falls back to **`sampleRows`** when **`points`** are absent.
- **Dev playbook sample:** `dev_data/playbooks/cross_asset_pair_health/playbook.json` tool node uses **`query_property_history`**.

### Numeric lane (`PropertyToolsExecutor`)

- Resolves the Thing, then **`isNumericProperty`**; on mismatch returns **`NOT_NUMERIC_PROPERTY`** (NUMBER / INTEGER / LONG).
- Time stack, **`queryPropertyHistoryTable`**, compact numeric body, chart egress.

### Non-numeric lane

- Unified routing in **`PropertyToolsExecutor`**, compact **`formatPropertyHistoryValueStreamCompact`**
  with **`parler.value_stream_history.compact.v1`** / **`VALUE_STREAM_HISTORY_INLINE`**, always **`cacheId`** + capped
  **`sampleRows`**. Stream executor **delegates** legacy executor name to the unified path.

### Chart wire

- **`ParlerChartWireSupport`** builds **`ChartBlock`** only from numeric **`points`** +
  **`NUMERIC_HISTORY_*`** `resultKind`s. Non-numeric compact stays chart-ineligible.

### Egress gateway

- **`ToolResultEgressGateway.compactForLlmAppend`** remains the last-resort size net; executor-side compact is primary
  for both branches.

### Playbooks, routing, tests

- **`PlaybookToolAllowlist`** and tests use **`query_property_history`**. **`llm_tool_routing_guide.txt`** documents the
  unified tool.
