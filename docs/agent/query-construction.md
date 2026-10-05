# ThingWorx QUERY construction (Parler agent)

**Status:** implemented — QUERY grammar/validation in **`QueryJsonPrimitiveMapper`**; **`query_entities`** QUERY admission layer per **`model-tool-admission-guardrails.md`**.

Parler agents often need a **structured QUERY JSON** object (ThingWorx `baseType = QUERY`): filters, optional sorts, optional pagination. The **same wire shape** appears in multiple places, including:

- **`QueryImplementingThings*`** — filter/sort **implementing Things** under a ThingTemplate or ThingShape (`query_entities` exposes this as the optional **`query`** argument).
- **`DataTableThing.QueryDataTableEntries`** — filter **Data Table rows** (typically via `invoke_service` today).
- **`StreamThing.QueryStreamEntriesWithData`** / **`QueryStreamData`** — filter **stream entries** or values (via `invoke_service`, sometimes combined with time bounds).

This document sets **product-level rules** for Parler (user-facing chat). The **full filter-type catalog** (every leaf type, composite quirks, platform edge cases) is defined by the ThingWorx platform's QUERY parser; a summary of the documented catalog with links to the ThingWorx Help is in [`query_capability.md`](query_capability.md). Do not duplicate that catalog here; cite platform behavior when implementing tests.

## 0. Product context

1. **Audience — end user chat**  
   **Users** speak conditions in natural language. Parler should prefer **narrow first-class tools** that accept a small typed subset (and return applied filters in metadata) where product demand is high; reserve **full QUERY trees** for `invoke_service` or advanced modes, and always stress **field names must come from real metadata**, not guesses.

2. **Runtime — in-process Java on ThingWorx**  
   Pass QUERY parameters as **`JSONPrimitive(org.json.JSONObject)`** on `processAPIServiceRequest` paths so the platform parses filters the same way as Composer-built services. **`QueryJsonPrimitiveMapper`** (used from **`InvokeServiceArgumentCoercion`** on **`InvokeServiceExecutor`** paths) maps **`BaseTypes.QUERY`** like **`QueryEntitiesExecutor`**: **preferred** shape is a **structured JSON object** in the tool call; the runtime also accepts a **textual JSON object** (string whose trimmed content starts with `{` … `}`) as a **lenient** recovery path when the model stringifies by mistake — **`InvokeServiceExecutor`** logs at **debug** on that path. **Reject** values that are arrays, bare primitives, or textual JSON that does not parse to a **JSON object** at the root. **Carrier split:** **`llm_tool_routing_guide.txt`** teaches the structured **`query_entities.query`** object shape (filter tree under **`query.filters`**). Generic **`invoke_service`** QUERY parameters — object shape, textual **`{...}`** lenience, and array/non-object rejection — are taught in **this document** and, when the legacy pair is merged, in the **`get_service_definition`** tool description. Lenience is for App User outcomes, not Conflu-style wire pedantry.

## 1. Mental model for users and agents

- A QUERY is **one object** with optional **`filters`**, **`sorts`**, **`pagination`**, and rarely **`optimizationDisabled`**.
- **`filters`** is a **single JSON object** (a leaf filter or an **AND/OR** composite). It is **not** an array at the top level.
- **`sorts`** is always an **array** (possibly one element).
- **`pagination`** is a **JSON object** with optional **`pageSize`** and **`pageNumber`**. When either is set, it must be a **positive integer**. The local validator rejects wrong shapes before invoke (see §6 #3). **Lenience:** JSON numbers that are **numerically integral** (e.g. **`10.0`**) are accepted as recovery; **this document** and merged tool descriptions are the carriers that should teach plain integers (**`10`**) — the routing guide does not currently carry pagination-integer teaching.

If the user says “newest first”, map to **`sorts`** with a field that **exists on the target row shape**. If they say “where status is open”, map to an **`EQ`** (or appropriate leaf) on a **real column name**.

## 2. Surfaces in Parler today

| Use case | Typical Parler path | QUERY usage |
|----------|---------------------|-------------|
| Filter Things under a template/shape | **`query_entities`** | Optional **`query`** merged into QIT-style services (see `QueryEntitiesExecutor`) |
| Filter Data Table rows | **`invoke_service`** on the Data Table Thing | Parameter often named **`query`** — confirm service signature and row shape via known **DataShape** metadata (**`describe_entity_schema`**) when applicable; on a concrete Thing use **`discover_thing_members`** (`service` facet) or merged **`get_service_definition`** only when **`advertiseLegacyServiceDiscoveryTools=true`**; otherwise skills / extended tools / prior results |
| Filter Stream entries / values | **`invoke_service`** on the Stream Thing | Same metadata posture as Data Table rows; often combined with **`startDate`/`endDate`** (see [`time-interpretation.md`](time-interpretation.md)) |
| Alerts summary/history | **`query_alert_summary`**, **`query_alert_history`** | Typed filters + optional **`advancedQuery`** — see `docs/operations/alert-solution.md` |

There is **no** dedicated built-in today that wraps Data Table / Stream QUERY for end users; **`invoke_service`** is the generic path.

### 2.1 Which tool for which listing job

Pick the listing tool before building any QUERY:

| Job | Tool | Notes |
|-----|------|-------|
| **Thing instances** under a ThingTemplate or ThingShape | **`query_entities`** | Structured listing through **QueryImplementingThings\*** (prefers **`QueryImplementingThingsOptimizedWithTotalCount`**); optional **`query`**, name mask, model tags, paging. See [`query_entities_design.md`](query_entities_design.md). |
| **Non-Thing metadata entities** by collection type (ThingTemplate, ThingShape, Mashup, DataShape, User, …) | **`list_entities_by_type`** | Wraps **EntityServices** **`GetEntityList`** (SQL-LIKE `nameMask`) or **`GetEntityListByRegEx`** (`useRegEx: true`), with optional model **`tags`**. **`maxItems`** defaults to **50** and is clamped to **1–200**; no total count is returned. **`entityCollectionType=Thing`** is rejected with **`THING_NOT_SUPPORTED_HERE`** (scanning the Thing collection does not scale). A model key such as `Stream` passed as a collection type returns **`ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY`** with a **`repair`** pointing to **`query_entities`**. |
| **Fuzzy exploration** — type or parent unknown, vague keyword | **`spotlight_search`** | Cross-type metadata search via **`SearchFunctions.SpotlightSearchV2`**. |
| A **known or discovered service** (including follow-ups named in prior results) | **`invoke_service`** | Not a substitute for **`list_entities_by_type`** on type-scoped directory listings. |

Keep **`query_entities`** narrow: Thing instances under one template/shape, not a universal “list any entity” tool. The runtime routing text in **`llm_tool_routing_guide.txt`** carries the condensed form of this split (see [`LLM_CONTEXT.md`](LLM_CONTEXT.md)); keep both aligned.

## 3. Rules that prevent silent failure

1. **Resolve field names from metadata**  
   Before building `filters` or `sorts`, obtain column/property names from **verified** metadata — **MUST NOT** invent field names: wrong names fail type promotion or return empty sets without a clear user-facing explanation. Default merged-tool paths (**[`metadata_discovery.md`](metadata_discovery.md)** §8, **[`legacy-discovery-executor-only.md`](legacy-discovery-executor-only.md)**, **`llm_tool_routing_guide.txt`**):

   - **Concrete Things** (properties, public services, events, subscriptions): **`discover_thing_members`** (appropriate facet).
   - **ThingTemplate / ThingShape / DataShape** (schema entities): **`describe_entity_schema`** (appropriate facet), including **DataShape** field lists used as QUERY row-shape sources.
   - **Data Table / Stream** entry services (typically via **`invoke_service`**): use known **DataShape** / schema metadata (**`describe_entity_schema`**) when the row shape is a declared schema entity; for service **signatures** on a concrete Thing, **`discover_thing_members`** (`service` facet) or merged **`get_service_definition`** / **`discover_services`** only when **`AgentThing.advertiseLegacyServiceDiscoveryTools=true`**; otherwise prefer **skills**, **extended tools** (`/tools/extended_tools.json`), or app-specific entrypoints — **`describe_entity_schema`** is **not** a generic service-definition reader for arbitrary Data Table / Stream Things.
   - **Legacy replay / executor-only:** **`discover_properties`**, Thing-targeted **`discover_services`** / **`get_service_definition`** remain **executable** but are **omitted from the default merged LLM tool list**; **`discover_properties`** stays executor-only **even when** the flag is **`true`**. Use only when continuing a prior tool sequence or explicit replay — not for greenfield model turns.

2. **LIKE wildcards**  
   ThingWorx **`LIKE`** uses `*` or `%` (multi-char) and `?` (single char). **`_` is not** an SQL-style single-char wildcard in this platform family.

3. **Composite NOT**  
   Do not rely on a top-level **`NOT`** composite in QUERY JSON where the platform parser does not implement it; express negation with **`NE`**, **`NOTLIKE`**, **`NOTIN`**, etc.

4. **QIT vs Data Table vs Stream**  
   The JSON shape is shared, but **field semantics differ** by entity: a filter valid on **Thing** rows may be meaningless on **stream values**. Teach the model to **scope** field names to the service result shape. The same rule is repeated in **`llm_tool_routing_guide.txt`** — keep both aligned.

5. **Pagination and large results**  
   Prefer bounded **`maxItems`** / **`pageSize`** consistent with Parler cache policy (see `docs/agent/p2_last_tabular_cache.md` and tabular tool design). Large **`invoke_service`** INFOTABLE results use **`cacheId`** + **`fetch_cached_result`** — same pattern applies to heavy Data Table / Stream queries.

6. **`optimizationDisabled`**  
   Set only when a verified platform case requires disabling the QUERY optimizer; not a default for agents.

7. **Null / missing-value filters**  
   For “is null” / “field missing” conditions, use the platform leaf types (**`MISSINGVALUE`**, **`NOTMISSINGVALUE`**) exactly as the platform QUERY parser defines them—**do not** invent alternate JSON. **Leaf shape:** `{"type": "MISSINGVALUE", "fieldName": "Status"}`.

8. **Composite depth**  
   Nested **AND/OR** trees are allowed; in practice keep depth **≤ 3** for readability and predictable optimizer behavior. Deeper trees are a maintenance and model-risk problem for App User chat.

## 4. Relationship to other agent docs

Canonical order of operations for data questions:

1. **[`key-resolution.md`](key-resolution.md)** — resolve **which** ThingTemplate, ThingShape, Data Table Thing, or Stream Thing applies (avoid false “zero” conclusions).
2. **Metadata discovery** — obtain **verified** field/column names per §3 rule 1 (**`discover_thing_members`**, **`describe_entity_schema`**, or qualified legacy paths).
3. **This document** — build **QUERY** from those verified names.
4. **[`time-interpretation.md`](time-interpretation.md)** — resolve **DATETIME** bounds where the service uses `startDate`/`endDate` (or equivalents) alongside QUERY.

## 5. Anti-patterns (quick reference)

| Anti-pattern | Why it is wrong |
|--------------|-----------------|
| Top-level `filters` as an array | `filters` must be one object (leaf or AND/OR tree). |
| Using `_` as one-char wildcard in LIKE | Platform uses `?`; `_` is literal. |
| `pageSize: 0` or `pageNumber: 0` when pagination is present | **`QueryJsonPrimitiveMapper.validateQueryObject`** rejects first (**`IllegalArgumentException`**, LLM-correctable); platform would also throw. |
| Top-level `NOT` composite | Prefer `NE`, `NOTLIKE`, `NOTIN`, etc., per platform parser support. |
| Reusing column names from another Thing or service | Names must match the **target** service row shape. |
| Passing **`query`** as a **string** that *contains* JSON text in **`invoke_service`** | **Preferred:** pass a **structured JSON object**. **Lenient:** a textual **`{...}`** object may be accepted for recovery; arrays / non-objects are rejected. Do not rely on stringification — **`invoke_service`** QUERY shape is taught in **this document** and in legacy **`get_service_definition`** when merged; **`llm_tool_routing_guide.txt`** carries **`query_entities.query`** only. |

## 6. Suggested tests (agent / Java)

1. **`query_entities`** with composite **AND** filter round-trips through QIT and returns expected row counts on a fixture template (where available).
2. **`invoke_service`** with **`query`** object: parameter reaches the platform as **QUERY** (not double-encoded string) for at least one Data Table test service — **`BaseTypes.QUERY`** path must match `QueryEntitiesExecutor`.
3. **Invalid QUERY pagination** — when **`pagination`** is present it must be a **JSON object** (not string/array/number); if **`pageSize`** / **`pageNumber`** are set, values must be **positive integers** in effect: reject fractions, booleans, and non-numeric shapes; **numerically integral JSON doubles** (e.g. **`10.0`**) are **accepted** as lenience (locked by **`pageSizeIntegralDoubleAccepted`** in **`QueryJsonPayloadValidationTest`**). **`QueryJsonPrimitiveMapper.validateQueryObject`** enforces **pagination shape + values only** for **`invoke_service`** and **`query_entities`** (clear **`IllegalArgumentException`**). Other §3 rules (filters root shape, NOT composite, LIKE wildcards, composite depth) are **platform/parser + LLM guidance** only; they are not enforced locally.
4. Large Data Table query exceeds inline threshold → **`cacheId`** returned; **`fetch_cached_result`** pages without re-executing QUERY.
5. **Unit tests:** `InvokeServiceExecutorQueryPrimitiveTest` — **`QueryJsonPrimitiveMapper.toJsonPrimitive`** **rejection** paths (array / primitive / null / non-`{` text / whitespace-only textual) — fail **before** **`JSONPrimitive`**. **`QueryJsonPayloadValidationTest`** — **`parseQueryObject`** structured round-trip plus **`validateQueryObject`** pagination rules (**no** **`JSONPrimitive`**). **Success** paths (object → **`JSONPrimitive`**) still need **in-container / integration** coverage on a live ThingWorx stack.

## 7. References

- Parler — QIT / `query_entities`: [`query_entities_design.md`](query_entities_design.md), [`query_with_total_count.md`](query_with_total_count.md)
- Parler — generic invoke + **QUERY encoding:** `InvokeServiceExecutor.java` + **`QueryJsonPrimitiveMapper.java`** (**`BaseTypes.QUERY`** → **`JSONPrimitive(JSONObject)`**, same idea as **`QueryEntitiesExecutor`**), [`invoke_service_design.md`](invoke_service_design.md) §8.1
- Parler — alerts QUERY: `docs/operations/alert-solution.md`
- ThingWorx — QUERY / filter wire behavior: [`query_capability.md`](query_capability.md) (summary with links to the ThingWorx Help)
