# Minimal Implementation for Time-Series & Aggregation Use Cases

This document outlines the **minimal work** required to support natural-language questions over ThingWorx machine property history, such as:

- What was the **maximum pressure** observed on Machine3 during February 2025?
- What was the **average temperature** recorded on Machine2 on December 12th, 2024?
- What was the **highest speed** registered by Machine1 in January 2025?
- How many **hours** was Machine1 running on February 22nd, 2025?
- How many **times** did torque exceed 325 on Machine3 during the first week of January 2025?
- What was the **lowest RPM** recorded for Machine2 between 3pm and 6pm on December 31, 2024?

---

## 1. Current state (built-in tools)

| Implemented | Stub (call → throws) |
|-------------|----------------------|
| `invoke_service`, `fetch_cached_result`, **`spotlight_search`**, **`discover_thing_members`**, **`describe_entity_schema`**, **`get_property_values`**, **`query_property_history`**, **`query_entities`**, **`list_entities_by_type`** | (none) |
| Legacy discovery (**executor-only / replay**): **`discover_services`** / **`get_service_definition`** when **`advertiseLegacyServiceDiscoveryTools=true`** (merged) or replay; **`discover_properties`** always executor-only. **`get_entity`** executor-only. | |

**`set_property_value`:** **Parler AlwaysOn** — human approval (HITL) then **`SetPropertyValueExecutor`** real write. **Chat without Parler context** — returns `status: "blocked"`, `code: "PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT"` (no write). See **`./data-operation-solution.md`**, **`./AGENT-ALWAYSON-TWX.md`**.

**Custom tools:** AgentThing services named `_tool_*` are exposed to the LLM (see **./CUSTOMIZED-TOOLS.md**).

**Time-series:** **`query_property_history`** routes **NUMBER / INTEGER / LONG** through **`QueryNumberPropertyHistory`** +
**`NumericSeriesAggregator`** (compact LLM evidence + chart/cache lanes). **Other logged types** use **`QueryPropertyHistory`**
with compact **`VALUE_STREAM_HISTORY_INLINE`** evidence. **“Hours running”** or analytics outside those platform paths may
still need **`invoke_service`** or a dedicated **`_tool_*`**.

**Remaining gaps for the demo scenarios below:**
- **System prompt** may still need machine names, property names, time format/timezone, and when to use `query_property_history` vs `invoke_service`.
- Built-in discovery/listing tools (see table) cover **entity listing**, merged metadata discovery, and **global search** (`spotlight_search`) without crafting `invoke_service` calls. **`get_entity`** is **executor-only** for full combined schema dumps.

---

## 2. Work Required (Minimal Path)

### A. Time-Series Data Access

- **Done (built-in):** **`query_property_history`** — `thingName`, `propertyName`, optional `startTime`/`endTime`, `maxItems`
  (aliases `maxRows`/`maxPoints` still accepted),
  optional **`actions[]`** for aggregates **on numeric properties only** (non-numeric branch rejects non-empty **`actions`**).
  Numeric path uses **`QueryNumberPropertyHistory`**; non-numeric path uses **`QueryPropertyHistory`** with bounded
  **`sampleRows`** + **`cacheId`** (no full inline dumps).
- **Still use `invoke_service` or `_tool_*` when:** you need a **different platform service shape** than the built-in
  history services, or **full raw rows** beyond what the compact + **`cacheId`** path is meant to surface in the LLM.

- **Prerequisite:** Things exist and numeric logged properties are **bound to persistence** (Value Stream / appropriate store) so history services return data.

### B. Aggregation

- **Done (built-in):** Pass **`actions`** on **`query_property_history`** (e.g. mean, min, max, sum, stddev, count, …) so the extension aggregates in Java after fetching history.
- **Conditional logic:**
  - **Count with condition** (e.g. torque > 325): not an `actions` enum — run **`tabulate_cached_result`** (`filter_count`) over the cached history result by **`cacheId`**, or use **`invoke_service`** / **`_tool_*`**.
  - **“Hours running”**: needs a clear definition (which property, sampling); often a **custom `_tool_*`** or **`invoke_service`** on analytics you already have.

### C. Entity and Property Resolution

- **Machine names:** Prefer names that match Composer; otherwise document in the **system prompt**. Use **`spotlight_search`** for fuzzy discovery, **`query_entities`** (Thing under template/shape), **`list_entities_by_type`** (metadata by collection type), or **`invoke_service`** for other services (including permission-mask entity lists).
- **Property names:** Use **`discover_thing_members`** (facet `properties`) or executor-only **`discover_properties(thingName=…)`** for a Thing instance's property list; use **`describe_entity_schema`** for ThingTemplate / ThingShape / DataShape facets; **`get_entity`** only when a full combined schema dump is explicitly needed (**executor-only** / replay).

### D. Time Range Parsing

- All questions imply a **time range** (e.g. “February 2025”, “December 12th, 2024”, “first week of January 2025”, “between 3pm and 6pm on December 31, 2024”).
- The LLM can convert these to start/end timestamps; the tools should accept a clear **time format** (e.g. ISO or epoch) and document **timezone** (e.g. server or UTC) in the system prompt.

### E. System Prompt and Tool Descriptions

- **Default system prompt** should state that the agent runs in a **live ThingWorx** system and can query real Things, properties, and history.
- Document **machine names** (e.g. Machine1, Machine2, Machine3) and **property names** (Pressure, Temperature, Speed, Torque, RPM, Running, etc.) so the LLM fills tool parameters correctly.
- **Tool descriptions** for “property history” and “aggregate” should clearly describe parameters (time format, aggregation type, optional threshold) so the LLM knows when and how to call them.

---

## 3. Capability Status (Ordered by Dependency)

| # | Work Item | Status / Notes |
|---|-----------|----------------|
| 1 | **Property current value** | Done: **`get_property_values`**. |
| 2 | **Property history** | Done: **`query_property_history`** (numeric + non-numeric branches). Exotic stores / alternate service shapes → **`invoke_service`** or **`_tool_*`**. |
| 3 | **Aggregates (max/min/avg/…)** | Done: **`actions`** on **`query_property_history`**. Threshold counts → **`tabulate_cached_result`** filter modes; domain metrics → custom tool or **`invoke_service`**. |
| 4 | **“Hours running”** | App-specific: define the signal, then use **`_tool_*`** or a platform service. |
| 5 | Entity/property discovery | Done: **`discover_thing_members`**, **`describe_entity_schema`**; legacy **`discover_services`** / **`get_service_definition`** / **`discover_properties`** executor-only; **`get_property_values`**, **`spotlight_search`**, **`query_entities`**, **`list_entities_by_type`**. |
| 6 | **Property write** | **`set_property_value`**: Parler HITL + post-approve write; non-Parler **blocked**. Else **`invoke_service`** if policy allows. |
| 7 | Update **system prompt** | Recommended: machines, properties, time format, tool choice. |
| 8 | **Platform setup** | Things, logged properties, Value Stream (or equivalent) with sample data. |

---

## 4. Summary

For **numeric** history + **standard aggregates**, the extension already provides **`query_property_history`** (and **`get_property_values`** for snapshots). **Prompt + platform data** remain the main levers for demo quality.

Conditional counts (e.g. torque > 325) use the cached-table filter modes; stream entries use **`query_stream_data`**. “Hours running” stays app-specific. **`set_property_value`** needs **Parler + HITL** for writes (see §1).
