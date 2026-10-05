# Property value & numeric history — design notes

This document describes how Parler **reads current values** and runs **numeric history queries + aggregation**, for implementation and system-prompt reference.

---

## 1. Division of labor with `discover_thing_members` / `discover_properties`

| Capability | Purpose | Token / semantics |
|------|------|----------------|
| **`discover_thing_members`** (facet `properties`) | **Preferred** listing of visible property definitions on a **Thing** (paging, optional prefix) | Metadata only; same engine Thing-path **`discover_properties`** delegates to |
| **`discover_properties`** | Legacy-compatible name for the same listing | Same row shape as **`discover_thing_members`** on success; keep for replay / continuation |
| **Read current value (dedicated tool)** | **Current snapshot** for **explicitly named** properties | Operations usually care about a small set (e.g. Pressure, Temperature, Flow); **names are supplied by the caller**, decoupled from discover |

**Conclusion:** **Do not fold current values into discovery tools.** Listing often includes many system properties; returning values there blows up tokens and noise; what matters is usually a **few explicitly named** quantities.

**Thing name preflight:** On **`discover_thing_members`** and Thing-path **`discover_properties`**, missing/blank or non-canonical **`thingName`** uses the same **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** envelopes as **`get_property_values`** when taxonomy allows (**`CONTRACTS/TAXONOMY_RESOLVER.md`** §7).

---

## 2. Current values: dedicated tool, **batch supported**

### 2.1 Tool shape

- **Name:** **`get_property_values`** (`propertyNames[]`; a single name can be a one-element array).
- **Inputs** (illustrative):
  - **`entityType`** / **`entityName`**: locate the Thing.
  - **`propertyNames`**: **string array** of business points to read (e.g. `["Pressure","Temperature","Flow"]`).
- **Cap:** at most **40** property names per call; more → **`TOO_MANY_PROPERTIES`**.

### 2.2 Returns and errors

- Return **per-property** structure, e.g. `{ "status":"success", "values":[{ "name":"Pressure", "baseType":"NUMBER", "value":... }, ...] }`.
- **Partial failure:** if one property is missing, unreadable, metadata-unresolved, or special-type, that row **`ok:false` + `code` + `message`**; **other properties still return** so the LLM can fix spelling or try another name.
- **Metadata-unresolved reads:** return **`PROPERTY_METADATA_UNRESOLVED`** and do not read the value. This is not evidence of PASSWORD protection; use **`discover_thing_members`** (facet `properties`) or **`discover_properties`** to resolve exact property names.
- **Workflow:** when unsure of names, **`discover_thing_members`** first, then **`get_property_values`** only for needed columns.

---

## 3. History query + aggregation (numeric compromise)

### 3.1 Type scope

- **Aggregation applies only to** **NUMBER**, **INTEGER**, **LONG** property history series.
- **BOOLEAN** and other logged types are not aggregated (their time-series semantics, such as ratios or transition counts, differ from numeric min/max/avg); **`query_property_history`** returns them as history rows (§4).

### 3.2 Aggregate actions: **multi-select + strict enum** (`NumericSeriesAggregateAction`)

- **Input:** **`actions`** as **string array** (e.g. `["MIN","MAX","MEAN","PERCENTILE_95","PERCENTILE_99"]`). Typical use: **min / max / avg / percentiles** in one call.
- **Empty array or omit:** **no aggregation**; tool returns **raw point series** only (no separate `NONE` enum).
- **Parse:** **`NumericSeriesAggregateAction.parseList(List<String>)`**; illegal entries **throw** with valid wire names listed. **`AVG`→MEAN**, **`STDDEV`→STANDARD_DEVIATION**.
- **Compute:** **`NumericSeriesAggregator.aggregateAll(double[], List<...>)`** → **`Map<String,Double>`** (keys are enum names). **Duplicate** actions in the list are deduplicated; single output each.

Implementation uses **Apache Commons Math 3** (bundled in shadow JAR). Allowed actions:

| Action | Note |
|--------|------|
| **`MIN`** / **`MAX`** / **`SUM`** / **`COUNT`** / **`MEAN`** | `DescriptiveStatistics` |
| **`GEOMETRIC_MEAN`** | Geometric mean; any sample ≤0 may yield NaN |
| **`VARIANCE`** / **`STANDARD_DEVIATION`** | Variance / standard deviation |
| **`SKEWNESS`** / **`KURTOSIS`** | Skew / kurtosis |
| **`MEDIAN`** | 50th percentile |
| **`PERCENTILE_5`** … **`PERCENTILE_99`** | 5, 10, 25, 75, 90, 95, 99 |
| **`FIRST`** / **`LAST`** | First/last by array order |

Single-stat can also use **`aggregate(double[], singleAction)`** (internally `aggregateAll`).

### 3.3 Implementation and library

- **Apache Commons Math 3.6.1**: `DescriptiveStatistics` + `Percentile`; extension **implementation** dependency, shipped via **shadowJar**.
- **Volume:** must cap **time range** and **max rows** (or maxPoints) to avoid OOM; huge windows should use **platform-side aggregated query** or **chunking + cacheId** (aligned with `invoke_service` large-table policy).

### 3.4 Tool shape

A single tool, **`query_property_history`** (numeric NUMBER / INTEGER / LONG branch), takes the `actions` array: **empty** → compact sample + cache; non-empty → **`aggregates` object** + compact evidence (full series on chart/cache lanes).

---

## 4. Relation to existing code

- **`BuiltInTools`:** **`get_property_values`** (batch) and **`query_property_history`** (property history; numeric vs non-numeric branches) are implemented.
- **History + aggregation:** **`query_property_history`** routes NUMBER / INTEGER / LONG to **`QueryNumberPropertyHistory`** + **`NumericSeriesAggregator`**; other logged types use **`QueryPropertyHistory`** with compact **`VALUE_STREAM_HISTORY_INLINE`** evidence. **`invoke_service`** remains for uncommon platform paths.
- **Model-facing Thing names (writes + generic invoke):** **`set_property_value`** and Thing-target **`invoke_service`** apply the same scalar preflight and canonical-name rules as reads — see normative **`CONTRACTS/TAXONOMY_RESOLVER.md`** §**7.4**–**7.5**.

---

## 5. Summary

| Topic | Decision |
|------|------|
| Current values | **Dedicated tool**; **multiple properties per call**; strictly separate from **`discover_properties`** |
| History + aggregation | Aggregation for **NUMBER / INTEGER / LONG only**; **aggregate as enum**; other types return history rows without aggregation |
| Scale | Large history: **row cap** + **cache** |
