# Parler agent — application asset taxonomy

**Stable product doc** for taxonomy **field semantics**, **structured configuration** (`identity-types.json`), and **persistence boundaries**. It complements:

- **`CONTRACTS/AGENT_TAXONOMY_RENDERING.md`** — normative **optional repository Markdown** at **`/taxonomies/type-taxonomy.md`** in the stable leading system prompt (no generated pipe table).
- **`CONTRACTS/TAXONOMY_RESOLVER.md`** — normative JSON for **`list_asset_types`**, **`resolve_asset_type`**, **`resolve_thing`**, and operator refresh/diagnostics.
- **`/taxonomies/identity-types.json`** — structured source for resolver tools: **v2** uses a root object with **`version: 2`**; **v3** uses a root JSON array of identity rules. **`/taxonomies/asset-types.json`** (v3 object map of asset types) loads **independently**: identity rules can exist without asset-type rows (and vice versa); each side has its own diagnostics and tool availability per **`CONTRACTS/TAXONOMY_RESOLVER.md`**. Both feed in-process **`TaxonomyRow`** projection (key resolution + Playbooks) and property projection for **`query_entities_by_taxonomy`** / identifier flows where applicable.
- **`docs/agent/LLM-PERSISTENCE.md`** — Stream vs in-memory conversation history (including that failed turns may leave user rows in Stream while `_conversations` is rolled back).
- **`docs/agent/AGENT-CONTEXT.md`** — overall agent architecture.

**Not covered here:** wire JSON to `parler-ui`; chart payloads (`CONTRACTS/CHART_CONTRACT.md`).

---

## 1. Taxonomy rows (logical contract)

Each row describes one **asset type** the application exposes to the model: how to classify Things under that type and which properties to prefer when presenting results. The in-memory shape matches historical **`TaxonomyRow`** / **`query_entities_by_taxonomy`** arguments: **`AssetType`** (key), **`EntityType`**, **`EntityName`**, normalized synonym list, and **`CriticalProperties`** as a semicolon-separated string derived from **`identity-types.json`** **`criticalProperties[]`**.

### 1.1 `AssetType` (primary key)

- **Meaning:** Stable **business key** for the type. It may match a UI label string, but consumers should treat it as a **key** (unique per flattened `types[].key` within its `entities[].key` bucket; collisions across buckets are disambiguated per **`CONTRACTS/TAXONOMY_RESOLVER.md`**).
- **Uniqueness:** Application implementations **must** keep keys unique per the resolver contract. Duplicate keys make matching ambiguous without an **`entityKey`** hint.

### 1.2 `EntityType`

- **Allowed values (case-sensitive, no platform normalization):** `ThingTemplate`, `ThingShape` only.
- **Meaning:** How to interpret `EntityName` for membership of this asset type.

### 1.3 `EntityName`

- Fully qualified **ThingTemplate** or **ThingShape** name, consistent with `EntityType`.

### 1.4 Synonyms (normalized)

- **Source (Phase −1 / `TaxonomyRow`):** The normalized **type key** (`types[].key`) plus type-level **`types[].aliases[]`** only. **`entities[].aliases[]`** (entity-level hints) are **not** copied into row synonym sets — they are used by taxonomy **resolver** matching with **`entityKey`** / entity-hint paths per **`CONTRACTS/TAXONOMY_RESOLVER.md`**, not for Phase −1 equality on cached rows (see **`docs/agent/key-resolution.md`**).
- **Reserved platform ThingTemplate families:** Names such as **`Stream`**, **`DataTable`**, **`ValueStream`** (and similar shipped data-store templates) are **platform-reserved** — applications **cannot** register unrelated ThingTemplates under those exact names. Parler **`key-resolution`** **Phase −1** therefore treats a **taxonomy synonym hit** as **authoritative** when cached taxonomy rows are present: there is **no** “taxonomy vs platform `Stream` template” ambiguity to arbitrate in the agent. If aliases incorrectly map a reserved token to the wrong asset, that is **taxonomy data to fix**, not behavior Parler second-guesses. (External MCP-style stacks without structured taxonomy may need different disambiguation — **not** copied here.)
- **Plural / morphology:** Parler does **not** apply English (or other) stemming on synonym matching — equality is on Phase-0–normalized strings only. List variants explicitly in **`identity-types.json`** **`aliases[]`** (and entity-level **`entities[].aliases[]`**) when both should resolve to the same asset row.

### 1.5 `CriticalProperties`

- Suggested **Thing property names** to include when listing or detailing Things of this type.
- **Separator:** **ASCII semicolon (`;`) only.** Split tokens with `trim`; empty string means **no recommended list** (not a single empty property name).
- **Constraint:** Property names in ThingWorx do not contain `;`; do not use `;` inside a token for “compound” descriptors.

---

## 2. Platform services (`AgentThing`) and prompt surface

**Structured taxonomy** is **not** exposed as a Composer-overridable Infotable-backed taxonomy table on **`AgentThing`**. Authoritative application types live in **`/taxonomies/identity-types.json`** and are surfaced to the model via built-in resolver tools per **`CONTRACTS/TAXONOMY_RESOLVER.md`**.

**Repository Markdown prefix:** When **`configurationRepository`** is configured, Parler loads optional **`/taxonomies/type-taxonomy.md`** (UTF-8 text, v1 size cap) into the stable leading system prompt when **`AgentSettings.taxonomyPromptInjection`** is **`full_table`** (repository Markdown only — no appended pipe table). It is **not** a second system prompt and not for overriding built-in tool routing. Must be well-formed Markdown if using fences (platform does not repair unclosed fences). See **`CONTRACTS/AGENT_TAXONOMY_RENDERING.md`**.

**`GetAlertPrompt`:** Remains the dedicated override for the alert block; see **§2** historical pattern in **`docs/operations/alert-solution.md`**.

**SecurityContext:** Services execute as the user who initiated the chat; overrides must follow least-privilege data access.

### 2.1 Composer override and extension dispatch (alerts and other allow-override services)

- **`isAllowOverride = true`** services are called through the platform service entry so Composer overrides apply. **`GetAlertPrompt`**, run while the shared prompt is built, uses **`PlatformAccess.invokeProgrammatic`** (**`processServiceRequest`**: current user or System user); services a tool call reaches, such as **`ResolveDocumentSet`**, use **`PlatformAccess.invokeAsUser`** (**`processAPIServiceRequest`**: current user only).

### 2.2 LLM prompt surface: taxonomy precedence over catalog inference

When **resolver tools** report **`TAXONOMY_UNAVAILABLE`**, the model must **not** invent application asset-type inventories from generic **`list_entities_by_type`** scans. When taxonomy **is** available (**`list_asset_types`** success) and/or optional **`type-taxonomy.md`** is injected, treat those surfaces as authoritative for application “asset type” semantics relative to guessed **ThingTemplate** names or ad-hoc **`query_entities`** parent selection. **`llm_tool_routing_guide.txt`** states the precedence; **`BuiltInTools`** reinforces it for **`query_entities`**, **`query_entities_by_taxonomy`**, and **`list_entities_by_type`**. Typical failure mode: answering “how many asset types” with unrelated **`*_TT`** templates instead of calling **`list_asset_types`** or using **`resolve_asset_type`** + **`query_entities_by_taxonomy`** with **`EntityType`**/**`EntityName`** from the resolver.

---

## 3. Runtime validation

The agent **does not** validate arbitrary **`EntityType`** / **`EntityName`** pairs inside user tool JSON beyond runtime platform lookups. Invalid pairs surface as tool errors, costing tokens and latency. **Validate in application code and CI**, not only in production chat.

---

## 4. Ephemeral injections and `_conversations`

On each successful agent loop completion, per-turn **system** injections (ephemeral skill catalog on continuing threads, slash-loaded skills block, time anchor, taxonomy block) are **removed** from the in-memory message list so they do not accumulate. On failure, the list is truncated back to the size captured after `resolveConversation` and before those injections.

**Stream:** User (and possibly partial assistant/tool) rows may already be appended to **`AgentMessageStream`** before the loop; failure does **not** roll those back. See **`LLM-PERSISTENCE.md`** for the full split between Stream history and `_conversations`.

---

## 5. Built-in tool `query_entities_by_taxonomy`

**Stable design record** for the ThingWorx agent built-in that lists **Things** under one **ThingTemplate** or **ThingShape**, projects columns aligned with taxonomy-style workflows, and optionally filters by exact property equality. Implementation: `parler-agent/src/main/java/com/thingworx/things/agent/tools/QueryEntitiesByTaxonomyExecutor.java` (Javadoc summarizes the interim platform workaround).

This section intentionally **does not** duplicate **`identity-types.json`** field definitions (see **`docs/agent/taxonomy.md`**); it documents how a **runtime tool** maps membership + **`criticalProperties[]`** to platform services and JSON results.

### 5.1 Motivation and platform gap

| Topic | Description |
|------|-------------|
| **Taxonomy row** | Resolver + cached rows give the model `EntityType` / `EntityName` / **`CriticalProperties`** (from **`criticalProperties[]`**) so it can classify Things and prefer certain properties in results. |
| **`query_entities` gap** | A single `query_entities` call binds **one** of `thingTemplate` **or** `thingShape`. It does not perform a **single** platform query intersecting **both** parents. |
| **Desired platform capability** | A first-class service or tool that queries Things constrained by **both** ThingTemplate and ThingShape together. |
| **Current workaround** | Until that exists, `query_entities_by_taxonomy` runs in-JVM: call `QueryImplementingThingsOptimizedWithTotalCount` on **one** parent (`maxItems` 5000, name-only QIT column lists), walk `rootEntityList`, project `name` + `CriticalProperties` + `AdditionalProperties`, optionally filter rows where **any** `LookupProperties` entry equals the live Thing (**OR**), and return success JSON per **§5.2.1** (conceptually aligned with the platform **`ImplementedThingsWithTotalCount`** shape; **field set is branch-specific** — not every payload includes `rootEntityList`). |

### 5.2 Tool contract (parameters)

| Item | Rule |
|------|------|
| **Tool name** | `query_entities_by_taxonomy` |
| **`EntityType`** | Exactly `ThingTemplate` or `ThingShape` (case-sensitive). |
| **`EntityName`** | Parent template or shape name. The tool does **not** pre-validate existence; failures surface at runtime. |
| **`CriticalProperties`** | Semicolon-separated property names; `trim`; drop empty segments; merged with `name` and `AdditionalProperties` for projection. Duplicate `name` in the list is allowed. Columns missing on the **sample** Thing used for shape inference are **omitted** (implementation logs a warning). |
| **`AdditionalProperties`** | Same parsing rules as `CriticalProperties`. |
| **`LookupProperties`** | JSON object: property name → expected value. A row is kept if **any** entry matches the Thing’s current value for that property (**OR** across keys). Exact match only (no wildcards or regex in v1). Omit or `{}` skips filtering. |
| **Internal QIT** | Only `QueryImplementingThingsOptimizedWithTotalCount`; internal `maxItems` = 5000; `basicPropertyNames` = `name` only; `propertyNames` = empty `EntityList`. |

### 5.2.0 `rootEntityList` row shape (per Thing)

Each row object includes **`name`** plus every projected column that survived the **sample Thing** shape pass (**§5.2** **`CriticalProperties`** / **`AdditionalProperties`**); columns missing on the sample Thing remain **omitted** from the projection with a warning — unchanged).

**String-like nulls (extension v0.1.7+):** When the projected **BaseTypes** are **STRING**, **TEXT**, **HTML**, or **GUID**, a live read on a particular implementor Thing that yields **`null`** is encoded as **`""`** so the JSON key stays present (stable schema for the LLM; avoids sparse objects that invited “fields unavailable” mis-answers). For **NUMBER**, **INTEGER**, **LONG**, **BOOLEAN**, **DATETIME**, **INFOTABLE**, and other non-text types, a **`null`** read continues to **omit** the key on that row.

**Wire export (extension v0.1.8+):** Row JSON must include the primitives actually stored per column; see `QueryEntitiesByTaxonomyExecutor` (`valueCollectionToObject` / `primitiveCell`).

**Semantic taxonomy identifier resolver (related):** `resolve_thing` (v3) reuses the same QIT column projection and PASSWORD exclusion rules via `TaxonomyPropertyProjection` / `QueryEntitiesExecutor.createTaxonomyProjectionPick` (never omit empty `propertyNames`). Normative wire JSON: **`CONTRACTS/TAXONOMY_RESOLVER.md`**; design: **`docs/agent/taxonomy.md`**.

### 5.2.1 Success response: `resultKind` and JSON payload (contract)

**`resultKind` (enumeration — contract):** the success payload includes exactly one of:

`ENTITY_TAXONOMY_QUERY_EMPTY` · `ENTITY_TAXONOMY_QUERY_INLINE` · `ENTITY_TAXONOMY_QUERY_LARGE`

**Semantic note:** `resultShape` is always the string **`ImplementedThingsWithTotalCount`** (namesake alignment with the platform DataShape). **Do not** infer that every success body contains `rootEntityList`; only **EMPTY** and **INLINE** do. **LARGE** omits `rootEntityList` and uses `sampleRootEntityList` + `cacheId` instead.

**Common fields (all three success branches):**

| Field | Description |
|-------|-------------|
| `status` | `"success"` |
| `resultShape` | `"ImplementedThingsWithTotalCount"` |
| `parentKind` | Parent relationship kind (e.g. `ThingTemplate`, `ThingShape`). |
| `parentName` | Parent entity name. |
| `totalCount` | Integer — **filtered** row count (including **0** for EMPTY). **Unlike `query_entities`**, read **`totalCount`** here — not `totalRows`. |
| `service` | `"QueryImplementingThingsOptimizedWithTotalCount"` |
| `resultKind` | One of the three enumeration values above. |

**Branch-specific fields:**

| `resultKind` | Table / list payload | Additional fields |
|--------------|----------------------|---------------------|
| `ENTITY_TAXONOMY_QUERY_EMPTY` | `rootEntityList`: **[]** | — |
| `ENTITY_TAXONOMY_QUERY_INLINE` | `rootEntityList`: full array of row objects | — |
| `ENTITY_TAXONOMY_QUERY_LARGE` | **No `rootEntityList` key** | `cacheId` (string), `sampleRootEntityList` (first *N* rows, same shape as INLINE rows), `hint` (string; English operational text for the model — current literal: `Use fetch_cached_result with this cacheId, offset, and limit to page additional filtered rows from the cached result table.`) |

*`N`* is the agent large-table threshold (`InvokeServiceExecutor.largeTableRowThreshold()`), same session cache as **§5.5**. See **§5.4** for how `TabularChartRoundHooks` keys off **`rootEntityList`** vs **`cacheId`**.

### 5.3 Choosing `query_entities` vs `query_entities_by_taxonomy`

| Scenario | Prefer |
|----------|--------|
| Pagination, platform `query` / `modelTags`, lean/wide column toggles, `totalRows` / inferred totals | **`query_entities`** |
| Taxonomy row gives a **single** parent dimension plus critical/additional columns and optional **exact** property filters; acceptable to cap the name-only listing at 5000 and filter client-side | **`query_entities_by_taxonomy`** |
| Must intersect template **and** shape in **one** platform query | **Not supported** — this tool does not claim equivalence. |

### 5.4 Routing and tabular chart coupling

These artifacts must stay aligned when the tool’s wire JSON or `resultKind` values change:

| Location | Behavior |
|----------|----------|
| **`parler-agent/src/main/resources/.../llm_tool_routing_guide.txt`** | Documents when to use this tool vs `query_entities`; includes it in the tabular-chart bullet alongside `query_entities` / `list_entities_by_type` / `fetch_cached_result`. |
| **`TabularChartRoundHooks.java`** | Records qualifying tabular success for `ENTITY_TAXONOMY_QUERY_INLINE` using **`rootEntityList`**, and for `ENTITY_TAXONOMY_QUERY_LARGE` using **`cacheId`**, so `build_chart_from_tabular_result` can use `last_invoke` or `cache_id`. |
| **`BuiltInTools.java`** | Tool registration and schema; `fetch_cached_result` / `build_chart_from_tabular_result` descriptions must mention this producer. |
| **`AgentBaseThing`** (`appendBuiltInToolRoutingGuide`) | Composer metadata should remain consistent with the bundled routing guide. |
| **`BuiltInTools.java`** — `query_entities_by_taxonomy` `ToolDefinition` description + **`llm_tool_routing_guide.txt`** taxonomy bullet | MUST restate **§5.2.1** three-branch success JSON. Do **not** describe success as only “`rootEntityList` + `totalCount`” (LARGE omits `rootEntityList`). |

**Regression check (repo root):** after edits, `rg "ImplementedThingsWithTotalCount-shaped JSON: rootEntityList"` should return **no** matches — that phrasing was retired as a single-line summary.

### 5.5 Large results and shared session cache (invariant)

`ENTITY_TAXONOMY_QUERY_LARGE` stores the **projected** `InfoTable` in the **same per-conversation cache** used by `invoke_service` (large INFOTABLE), `query_entities` (`ENTITY_QUERY_LARGE`), and **`fetch_cached_result`** readers (`InvokeServiceExecutor.storeInfotableInConversationCache`).

**Invariant:** Anyone changing cache **key format**, **TTL**, **large-row thresholds**, or **`fetch_cached_result` paging semantics** must **also** validate the `query_entities_by_taxonomy` large-result path (`sampleRootEntityList` + `cacheId`). Silent regressions here break charts and follow-up paging for taxonomy-filtered listings.

**`hint` (LARGE branch):** The `hint` string is **prompt-surface contract** for the LLM (operational guidance tied to `fetch_cached_result`). Treat **wording changes** like any other contract-visible agent string: update **§5.2.1** and the executor in the same change set, and re-validate chart / cache flows.

### 5.6 Implementation index

- `parler-agent/.../tools/QueryEntitiesByTaxonomyExecutor.java` — executor.  
- `parler-agent/.../tools/QueryEntitiesExecutor.java` — shared `buildImplementingThingsParams`, `parseImplementingThingsOutput`, `createLeanNameOnlyPick`.  
- `parler-agent/.../tools/BuiltInTools.java` — `ToolDefinition` / registration.  
- `parler-agent/.../llm_tool_routing_guide.txt`, `TabularChartRoundHooks.java`, `AgentBaseThing.java` — routing and chart round-trip (see **§5.4**).
