# QueryImplementingThingsOptimizedWithTotalCount — Result Shape, Column Selection, and Maintainer Notes

This document records the **platform contract** for `QueryImplementingThingsOptimizedWithTotalCount` on **ThingTemplate** and **ThingShape**, how **`rootEntityList`** columns are determined, and how to choose **`basicPropertyNames`** / **`propertyNames`** for performance and downstream use (e.g. semantic similarity, Data Insights). Maintainers use it to check agent tools, prompts, and code changes against ThingWorx behavior. The **`query_entities`** tool itself is described in [`query_entities_design.md`](./query_entities_design.md).

Platform behavior this document relies on:

1. **`basicPropertyNames`** accepts **seven** basic property names, including **`projectName`**.
2. **`projectName`** in `basicPropertyNames` is **accepted but does not appear in output**; treat it as a no-op for this result.
3. **`thingTemplate`** in `propertyNames` is **valid for ThingTemplate parents**.
4. **`thingTemplate`** in `propertyNames` is **invalid for ThingShape parents**; the service throws that the requested property does not exist.
5. **`totalCount`** can be **null** on the service result.

---

## 1. Service result: `ImplementedThingsWithTotalCount`

### 1.1 Shape and row layout

The service returns an **InfoTable** whose **DataShape is `ImplementedThingsWithTotalCount`**.

- **Exactly one row** in normal success cases.
- That row contains **two fields**:
  - **`rootEntityList`** — type **INFOTABLE** (nested table of matching Things).
  - **`totalCount`** — type **NUMBER** (global count of matches **before** applying `maxItems` / `offset` pagination on the result set, per platform documentation; aligns with pre-limit cardinality used for paging).

**Checks**

- Any client that needs “how many Things match?” must read **`totalCount`** from this single row (not from `rootEntityList.getRowCount()` alone).
- **`totalCount`** is a **NUMBER**; JSON/REST consumers may see integer or float; normalize to a long where appropriate.
- Do not assume a second wrapper row unless your invoke path explicitly wraps results; in-process `Thing.processAPIServiceRequest` typically returns this table **directly**.

### 1.2 What this service does *not* guarantee (operational)

- The platform always builds the outer `ImplementedThingsWithTotalCount` row, but it fills **`totalCount`** only when the underlying query path produced a count. **`totalCount` can therefore be null** even when `rootEntityList` has rows. Agents should tolerate null and use fallbacks (extension-specific inference, second page probe, or documented heuristics).
- **Visibility and permissions** still filter rows; counts reflect what the **current security context** can see.

---

## 2. Nested table: `rootEntityList` and dynamic columns

### 2.1 DataShape vs actual columns

- The nested **`rootEntityList`** is an **InfoTable** whose **logical shape is `RootEntityList`**.
- **Column set is dynamic**: it is **not** always the full static `RootEntityList` definition. The effective columns depend on:
  - which **basic** fields were requested via **`basicPropertyNames`**,
  - which **template/shape-defined** fields were requested via **`propertyNames`**,
  - plus permission columns when **`withPermissions`** is true.

### 2.2 When neither `basicPropertyNames` nor `propertyNames` is supplied

If **both** are omitted (platform `null` / not provided), the optimized path treats that as “return them all”:

- The platform includes the **full default set** of basic columns as defined for **`RootEntityList`**, and **all** relevant defined properties for the query’s **parent** (ThingTemplate or ThingShape), subject to `withPermissions` and internal rules.

**Implication:** Omitting both is the **maximal payload** path — convenient for debugging, **expensive** for bandwidth and serialization in Data Insights or high-frequency listing.

---

## 3. `basicPropertyNames` — 7 accepted names, 6 returned columns

### 3.1 The closed set

**`basicPropertyNames`** is validated against a fixed set of **seven** basic property names:

| Name             | Role |
|------------------|------|
| `name`           | **Critical** — identity, joins, follow-up invokes, caching keys. |
| `description`    | **Important** for **semantic similarity**, labeling, and human/agent matching. |
| `tags`           | Sometimes useful (model tags, filtering context). |
| `avatar`         | Often **low value** in Data Insights / agent loops; adds payload. |
| `homeMashup`     | Often **low value** in Data Insights / agent loops; adds payload. |
| `isSystemObject` | Sometimes useful (filtering system vs user objects). |
| `projectName`    | Accepted, but not returned (see below). |

The **`RootEntityList`** DataShape defines only `name`, `description`, `isSystemObject`, `tags`, `homeMashup`, and `avatar`; it has no `projectName` field. Requesting `projectName` raises no error, but the output has no `projectName` column or value. Treat it as equivalent to not requesting it.

### 3.2 Practical guidance

- **Default lean path:** request **`name` only** in `basicPropertyNames` when you only need identity and pagination.
- Add **`description`** when the next step is **embedding / similarity / RAG-style matching**.
- Add **`tags`** / **`isSystemObject`** when the task explicitly needs them.
- **Avoid** `avatar` and `homeMashup` unless the UX truly needs them — they are pure traffic cost in most analytics and agent scenarios.

### 3.3 Parameter type

- **`basicPropertyNames`** is an **InfoTable** using DataShape **`EntityList`**.
- Each row is one field: column **`name`** (string) holds the property name (e.g. `name`, `description`). `EntityList` also has a `description` column, but the platform reads only `name`.
- **Check:** confirm tool builders construct a valid `EntityList` InfoTable, not a loose JSON array, when calling through `invoke_service` or `query_entities`.

---

## 4. `propertyNames` — template vs shape semantics

### 4.1 ThingTemplate as parent

When the queried parent is a **ThingTemplate**:

- The platform can surface **`thingTemplate`** on each row — the **effective / leaf-most template name** for that Thing (the **final** implementing template in the inheritance chain), **independent of which ancestor template** you used as the query parent.
- **Other columns** in `propertyNames` come from **properties defined on that parent ThingTemplate** (including inherited definitions from its hierarchy), as exposed by the platform’s result builder.

**Use case:** Querying **`RemoteThing`** but wanting each row to show the **concrete** template (e.g. `RemoteThingWithTunnelsAndFileTransfer`) — include **`thingTemplate`** in `propertyNames`.

`thingTemplate` is a Thing built-in property; it is neither a `RootEntityList` field nor a property definition on the parent. Requesting it in `propertyNames` works for **ThingTemplate** parents and returns the expected column; for **ThingShape** parents the service throws that the requested property does not exist.

### 4.2 ThingShape as parent

When the parent is a **ThingShape**:

- There is **no** `thingTemplate` column from this mechanism in the same way; rows reflect **properties defined on that ThingShape** (and inherited shape properties per platform rules).
- **Check:** do not assume `thingTemplate` appears when `thingShape` is the parent, and do not request it.

### 4.3 Omission vs empty InfoTable (critical distinction)

- **`propertyNames` omitted / null:** platform treats as “**all** defined properties (beyond the basic set already controlled by `basicPropertyNames`)” — **heavy** result.
- **`propertyNames` = empty InfoTable (`EntityList` with zero rows):** explicitly requests **no extra property columns** — **lean** result (only what `basicPropertyNames` and defaults require).

**Default recommendation when not specified otherwise:**

- Pass an **empty `EntityList` InfoTable** for `propertyNames` to avoid pulling every property.
- When the scenario needs the concrete template under an abstract template query, add **`thingTemplate`** (and only other properties you need) — **ThingTemplate parents only**.

### 4.4 Interaction with `basicPropertyNames`

- Do **not** duplicate basic fields inside `propertyNames`; basic columns are controlled only via **`basicPropertyNames`**.
- **Check:** validate no overlap and no invalid basic names in `propertyNames`.

---

## 5. Query techniques and multi-turn workflows

### 5.1 Building lean `basicPropertyNames` / `propertyNames`

1. Start with **`basicPropertyNames` = [`name`]** and **`propertyNames` = empty EntityList**.
2. Add **`description`** if semantic or display text is needed.
3. For **ThingTemplate-parent** queries where concrete type matters, add **`thingTemplate`** to **`propertyNames`** (it fails for ThingShape parents).
4. Add further **`propertyNames`** only after **discovering** which properties exist on the parent (see §6).

### 5.2 Efficiency

- Narrow columns **reduce cache work, payload size, and JSON cost** — especially important for **high-frequency** “count + sample” loops and Data Insights exports.
- Prefer **one** Optimized WithTotalCount call with tight columns over **wide** calls plus client-side dropping.

### 5.3 Pagination

- Use **`totalCount`** with **`offset`** and **`maxItems`** for correct “page X of Y”.
- If **`totalCount`** is missing, use platform- or extension-documented fallbacks; do not pretend `rootEntityList.getRowCount()` is the global total.

### 5.4 Common query scenarios (canonical recipes)

These are the **most frequent** intent → parameter mappings. In all cases, **`basicPropertyNames`** and **`propertyNames`** are **InfoTables** with DataShape **`EntityList`** (one column **`name`** per row listing a field to return). **`propertyNames` empty** means an **empty** EntityList (no rows), **not** omitted — omission pulls **all** extra properties (see §4.3).

1. **Names + total count only**
   - **`basicPropertyNames`:** include only **`name`**.
   - **`propertyNames`:** **empty** EntityList.
   - **Use when:** listing identities, pagination, or answering “how many?” with **`totalCount`** while minimizing payload.
   - **Read:** **`totalCount`** on the outer `ImplementedThingsWithTotalCount` row; **`name`** in each `rootEntityList` row.

2. **Whether each Thing is a system object**
   - **`basicPropertyNames`:** **`name`** and **`isSystemObject`**.
   - **`propertyNames`:** **empty** EntityList (unless you also need §5.4.3 or custom defined properties).
   - **Use when:** filtering Composer/system noise, audits, or agent policies that treat system Things differently.

3. **Concrete (leaf) ThingTemplate per row**
   - **`propertyNames`:** include **`thingTemplate`**.
   - **`basicPropertyNames`:** at minimum **`name`**; add others per §5.4.4 / §3.
   - **Parent must be a ThingTemplate**. With a ThingShape parent the service fails with “requested property does not exist” (§4.1–§4.2).
   - **Use when:** querying an **abstract** ancestor template (e.g. `RemoteThing`) but needing each instance’s **effective** template (e.g. `RemoteThingWithTunnelsAndFileTransfer`).

4. **Description (semantic similarity, labels, UI)**
   - **`basicPropertyNames`:** add **`description`** alongside **`name`** (and optionally **`isSystemObject`**, **`tags`**, etc.).
   - **`propertyNames`:** still **empty** unless you also need **`thingTemplate`** or other defined properties.
   - **Use when:** embeddings, fuzzy matching, tooltips, or any step that consumes human-readable text.

**Further combinations (compose as needed)**

| Need | `basicPropertyNames` | `propertyNames` |
|------|----------------------|-----------------|
| Identity + system flag + concrete template | `name`, `isSystemObject` | `thingTemplate` |
| Identity + text for RAG + concrete template | `name`, `description` | `thingTemplate` |
| Tags for filtering context (not model `tags` service param) | add `tags` to basic | empty or as needed |
| Avoid payload bloat | omit `avatar`, `homeMashup` unless required | avoid omitting `propertyNames` — use **empty** table |

**Rule of thumb:** start from **(1)**; add **(2)**–**(4)** only when the user story or next tool step requires that column.

---

## 6. Discovering properties on ThingTemplate / ThingShape (for better multi-turn queries)

Before adding arbitrary `propertyNames`, the agent (or developer) should know **which property definitions exist** on the parent entity.

**In this extension (typical tools):**

- **`describe_entity_schema`** — facet-bounded reads for **ThingTemplate** / **ThingShape** / **DataShape** (`entityType` + `entityName` = parent). **Prefer this** when listing schema fields before stuffing `propertyNames` / `extraPropertyNames`.
- **`get_service_definition`** — confirms exact parameter names and base types for `QueryImplementingThingsOptimizedWithTotalCount` when **`advertiseLegacyServiceDiscoveryTools=true`** (merged) or when **replaying** a prior sequence. In default merged mode, use **this document** (§3–§4 parameter tables) for that service signature; use **`describe_entity_schema`** for parent **schema fields** and QUERY **row-shape** sources — **not** for entity-level service definitions.
- **`get_entity`** — full combined schema metadata (escape hatch / Tier B replay) when **`describe_entity_schema`** facets are insufficient — not the default discovery path.
- For a specific **Thing** instance's effective properties use **`discover_thing_members`** (properties facet) or legacy executor-only **`discover_properties(thingName=…)`** after **`resolve_thing`** when replaying a prior sequence.
- **`invoke_service`** — alternative path when a bespoke platform service is required.

**Checks**

- Multi-turn flows should **discover** property names before stuffing `propertyNames`.
- Distinguish **basic properties** (§3) from **defined properties** (§4); only the latter belong in `propertyNames`.
- Request `thingTemplate` only for **ThingTemplate** parents.
- Expect `thingTemplate` under a **ThingShape parent** to fail as “requested property does not exist”.

---

## 7. What agents / tools can and cannot do

### 7.1 Can do

- Obtain **exact global cardinality** when **`totalCount`** is present.
- List implementing Things with **tightly controlled** columns via **`basicPropertyNames`** and **`propertyNames`**.
- Combine with **`query`** (filters/sorts) and **model `tags`** per service signature — subject to Optimized-query field support: filters and sorts must target fields defined on the parent template or shape; some basic fields cannot be filtered or sorted on the optimized path (see [`query_capability.md` §8](./query_capability.md)).

### 7.2 Cannot do (without a different API)

- Infer **inherited property definitions** from a **child** Thing instance alone without consulting the **ThingTemplate** / **ThingShape** metadata.
- Assume **`totalCount`** is always non-null (see §1.2).
- Use **`propertyNames`** to request the basic fields — use **`basicPropertyNames`** instead.
- Use `thingTemplate` in `propertyNames` when the parent is a **ThingShape**.

### 7.3 `query_entities` in **`parler-agent`** (current scope)

- **`QueryEntitiesExecutor`** sets **maxItems, offset, nameMask, withPermissions, query, and tags** from tool arguments, and in lean mode also builds explicit **`basicPropertyNames`** / **`propertyNames`** `EntityList` InfoTables per §10 (§10.6).
- **`widePropertyColumns: true`** omits both INFOTABLE parameters; use **`invoke_service`** only when a caller needs arbitrary `propertyNames` beyond the tool's toggles.
- Parsing of **`ImplementedThingsWithTotalCount`** and optional **total inference** live in `QueryEntitiesExecutor` — keep in sync with this document when changing unwrap logic.

---

## 8. Scenario cookbook

Quick reference; **prescriptive detail** is in **§5.4**.

| Scenario | `basicPropertyNames` | `propertyNames` | Notes |
|----------|----------------------|-----------------|--------|
| §5.4.1 — names + count only | `name` only | empty `EntityList` | Read `totalCount`; minimal traffic. |
| §5.4.2 — system object | `name`, `isSystemObject` | empty | |
| §5.4.3 — concrete template | `name` (+ optional basic) | `thingTemplate` | **ThingTemplate** parent only. |
| §5.4.4 — description / semantics | `name`, `description` | empty or `thingTemplate` if needed | Embeddings / similarity; use `thingTemplate` only for **ThingTemplate** parent. |
| Semantic + type | `name`, `description` | `thingTemplate` | Same parent restriction as §5.4.3. |
| Shape-only listing | `name` | empty or shape-defined props from discover | No `thingTemplate`; §4.2. |
| Debug / exploratory | omit | omit | Heavy; not for production high QPS. |
| Data Insights export | `name`, optional `description`, `tags` | minimal | Drop `avatar`, `homeMashup` unless required; ignore `projectName`. |

---

## 9. Summary (one screen)

1. **Result:** one-row **`ImplementedThingsWithTotalCount`**: **`rootEntityList`** + **`totalCount`** (NUMBER, may be null).
2. **Nested columns:** dynamic; driven by **`basicPropertyNames`** and **`propertyNames`**. `basicPropertyNames` accepts **7 names**; `projectName` is accepted but not returned.
3. **Empty `propertyNames` InfoTable** ≠ omitted: empty means **no extra properties**; omitted means **all** extras.
4. **Common cases (same order as §5.4):** (1) name + empty `propertyNames` + read `totalCount`; (2) add `isSystemObject`; (3) add `thingTemplate` in `propertyNames` **only for ThingTemplate parent**; (4) add `description` when needed for semantics/UI.
5. **Discover** properties on the parent **ThingTemplate** / **ThingShape** before expanding `propertyNames`; for **ThingShape** parents, do not request `thingTemplate`.
6. **Extension:** `query_entities` passes explicit **`basicPropertyNames`** / **`propertyNames`** per §10 (see §10.6).

---

## 10. Tool implementation: defaults, LLM parameters, and edge cases

This section is **product / engineering guidance** for **`query_entities`** (a thin wrapper over `QueryImplementingThingsOptimizedWithTotalCount`). It does **not** tie statements to a specific ThingWorx release number; behavior follows the platform contract described above.

### 10.1 Do not mirror platform “defaults”

On the platform, **omitting** `basicPropertyNames` and/or `propertyNames` tends to mean “return the **widest** sensible column set” (all relevant basics + all defined properties for the parent). For an **agent tool**, that is almost always **wrong**: it wastes bandwidth, blows context windows, and slows cache-backed queries.

**Tool rule:** the implementation passes **explicit** `basicPropertyNames` and **`propertyNames`**. For the lean default, `propertyNames` is an **empty `EntityList` InfoTable** (zero rows), **not** `null`/omitted — omission triggers the heavy path (§4.3).

**Fallback services:** the executor prefers `QueryImplementingThingsOptimizedWithTotalCount`, then falls back to `QueryImplementingThingsOptimized` and `QueryImplementingThings`. It sets the two INFOTABLE parameters only when the chosen service defines them. On a service without them the platform returns its default columns, and success JSON still reports **`columnMode: "lean"`**; no note flags that the narrowing was not applied.

### 10.2 Tool default (minimum columns)

| Built platform parameter | Tool default |
|--------------------------|--------------|
| `basicPropertyNames` | **`EntityList` with a single row:** `name` = **`name`**. |
| `propertyNames` | **Empty `EntityList`** (no rows). |
| `withPermissions` | **`false`** unless the call sets **`withPermissions`** (or its legacy alias **`withData`**). |
| Service preference | **`QueryImplementingThingsOptimizedWithTotalCount`** when the entity exposes it, so **`totalCount`** / tool-level **`totalRows`** are available (with null-safe inference). |

This matches §5.4.1: **identity + global count**, minimal traffic. It **excludes** `avatar`, `homeMashup`, and **does not rely on** `projectName` (§3).

### 10.3 How the LLM requests **more than the default**

The tool keeps a **small, explicit surface**; the model never hand-builds raw `EntityList` InfoTables.

**Boolean toggles**

Toggles map to rows in `basicPropertyNames` / `propertyNames` inside the executor (deterministic code, not LLM string parsing):

| Tool argument | Effect |
|---------------|--------|
| `includeDescription` | Add **`description`** to `basicPropertyNames`. |
| `includeIsSystemObject` | Add **`isSystemObject`** to `basicPropertyNames`. |
| `includeTags` | Add **`tags`** to `basicPropertyNames`. |
| `includeConcreteTemplate` | Add **`thingTemplate`** to `propertyNames` **only if** the call uses **`thingTemplate`** (parent is a ThingTemplate). With **`thingShape`** the tool returns a validation error instead of sending `thingTemplate` to the platform (§4.1–§4.2). |
| `widePropertyColumns` | Omit both INFOTABLE parameters (platform-wide columns; heavy). |

**Not provided:** a preset enum (e.g. `resultColumns`) and free-form `extraBasicPropertyNames` / `extraPropertyNames` arrays are not part of the tool. Callers needing arbitrary `propertyNames` use **`invoke_service`** with a fully specified `EntityList`, after discovering valid names with **`describe_entity_schema`** on the parent.

**Teach through errors:** the `includeConcreteTemplate` + `thingShape` error states that the option is only valid when `thingTemplate` is set, so the model can correct the call.

### 10.4 Other cases the tool accounts for

| Topic | Guidance |
|-------|----------|
| **Output projection** | Requesting extra columns from the platform is **not enough** on its own. `QueryEntitiesExecutor.slimThingRow(...)` emits `entityType`, `name`, `description` (truncated), `thingTemplate`, `tags` (truncated), and `isSystemObject`, for both inline `rows` and `sampleRows`. Other returned columns, such as permission columns from `withPermissions`, are not projected; they remain in the cached table for `ENTITY_QUERY_LARGE` results. |
| **Parent kind** | ThingTemplate vs ThingShape decides whether `thingTemplate` may appear in `propertyNames`. |
| **Pagination** | **`maxItems`** capped at 200; **`offset`** exposed; **`totalRows`** / **`hasMore`** / **`totalRowsInferred`** surfaced in JSON. |
| **Filters** | **`namePrefix`**, **`query`**, **`modelTags`** are separate from column selection; they do not replace lean column defaults. |
| **Large row sets** | Session cache + **`fetch_cached_result`** pattern; column width still matters for cache payload size. |
| **“Give me everything”** | The tool never treats an omitted `propertyNames` as “all properties” by accident; the wide path requires the explicit **`widePropertyColumns: true`** flag. |
| **Multi-turn discovery** | Before requesting arbitrary properties, use **`describe_entity_schema`** on the same ThingTemplate/ThingShape used as query parent (or **`discover_thing_members`** / executor-only **`discover_properties(thingName=…)`** after **`resolve_thing`** for a specific Thing's effective properties when replaying). Use **`get_entity`** only when facets are insufficient. |
| **`withPermissions`** | Default off; when on, platform rows widen and cost increases. |
| **Platform null `totalCount`** | The executor infers totals in bounded cases; tool JSON distinguishes **platform-supplied** vs **inferred** (`totalRowsInferred`) so the LLM does not over-trust. |
| **Consistency with §5.4** | Default = §5.4.1; toggles map to §5.4.2–§5.4.4 without contradicting ThingShape rules. |

### 10.5 Fallback without column parameters

When the invoked service has **no** `basicPropertyNames` / `propertyNames` parameters, the executor **does not** set them and the platform returns its default columns; success JSON **`columnMode`** is still **`lean`** (§10.1). Prefer parents that expose the Optimized* services.

### 10.6 Current behavior (**`parler-agent`**)

| Area | Behavior |
|------|----------|
| **Lean default** | Unless **`widePropertyColumns`** is true, the executor sets **`basicPropertyNames`** to an `EntityList` with **`name`** only and **`propertyNames`** to an **empty** `EntityList`, matching §10.2 / §4.3. |
| **Wide escape** | **`widePropertyColumns: true`** omits both INFOTABLE params so the platform may return the wide column set (§10.1). |
| **Toggles** | **`includeDescription`**, **`includeIsSystemObject`**, **`includeTags`** add rows to **`basicPropertyNames`**. **`includeConcreteTemplate`** adds **`thingTemplate`** to **`propertyNames`** only when the parent is **`thingTemplate`**; with **`thingShape`**, the tool returns **`"status":"error"`**, **`"code":"INVALID_COLUMN_OPTIONS"`**. |
| **Tool schema** | **`BuiltInTools`** documents defaults and toggles; success responses include **`columnMode`**: **`lean`** or **`wide`**. |
| **Rows** | **`slimThingRow`** emits **`isSystemObject`** when present in the result table (with **`includeIsSystemObject`**). |
| **Routing** | **`llm_tool_routing_guide.txt`** states that `query_entities` defaults to lean name-only columns and that `includeConcreteTemplate=true` on a ThingTemplate query gives the per-row concrete template. |
