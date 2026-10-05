# Model key resolution (Parler agent)

This document defines **model key resolution** behavior for the Parler Java agent: resolving overloaded user phrases (`Stream`, `DataTable`, taxonomy labels) to the correct ThingWorx **ThingTemplate** / **ThingShape** (or taxonomy row) before counting, listing, or building queries—so the assistant does not infer false zeros or misuse `EntityServices` collection types.

## 0. Product context (read this first)

Parler agent is built for **application end users** in natural-language chat, not for integration engineers wiring raw platform APIs. That shapes tools and defaults:

1. **Audience — end user vs integrator-style tooling**  
   Integrator flows often tolerate broad discovery and generic `invoke_service`. Parler should prefer **opinionated first-class tools** (`query_entities`, taxonomy tools), **compact** results, cache/paging, and fewer paths that invite false conclusions (e.g. “zero Streams”). Optional narrow resolvers should favor **clarity over completeness** for the prompting user.

2. **Runtime — in-process Java on ThingWorx**  
   Parler runs **inside the platform JVM** (`processAPIServiceRequest`, entity lookup, **`ServiceDefinition`**, typed primitives). Prefer **semantic safety for App Users** and **platform-native hooks** (exact entity lookup, correct QUERY encoding — see [`query-construction.md`](query-construction.md)) over copying **integrator REST-client** “defend every bag” defaults that exist only because the caller is outside the JVM.

3. **Taxonomy-first is a Parler invariant (not broad external discovery)**  
   **Cached taxonomy rows** (projection from **`identity-types.json`**) are synchronous, in-process, and tied to how the **app was built**. **External** MCP / REST tools typically **lack** this data, so they may rely on broader overlap heuristics. **Do not** import those heuristics into Parler: see **Phase −1** and [`llm_tool_routing_guide.txt`](../../parler-agent/src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt).

## 1. Problem

ThingWorx names are overloaded:

| User key | Possible meaning |
|----------|------------------|
| `Stream` | A platform ThingTemplate entity, a Java class family, a generic user concept, or a substring in many app templates |
| `Data Table` / `DataTable` | A user-facing data-store concept and usually a registered ThingTemplate name |
| `Value Stream` / `ValueStream` | A platform concept; **end-user** questions usually map to **property history on the host Thing** (`query_property_history` / `Query*PropertyHistory`), not to counting implementors of a `ValueStream` ThingTemplate via QIT |
| `Stacking robot` | An app taxonomy label that may map to a ThingTemplate, ThingShape, hierarchy node, tag, or naming convention |
| `Northwind_KPI_Stream_ThingTemplate` | A concrete app ThingTemplate name |

The observed failure mode was:

1. The agent guessed `EntityServices.GetEntityList(type="Stream")`.
2. ThingWorx returned `Invalid Entity Type [Stream]`.
3. The agent tried `SearchModelEntities(types=["Stream"])`.
4. That returned no rows, or rows whose `type` was `Thing`, and the agent concluded incorrectly that there were zero Streams.

The core lesson: **failure of `GetEntityList(type=<key>)` is not evidence that no Things exist under that concept**. For data-store concepts, the key is often a **ThingTemplate name**, not an EntityServices collection type.

## 2. Target behavior

For ambiguous model keys, the agent should resolve the key before broad service invocation.

For example, a prompt like:

```text
How many Streams are defined?
```

should resolve `Stream` as:

```json
{
  "resolvedAs": {
    "kind": "ThingTemplate",
    "name": "Stream",
    "confidence": "high"
  },
  "count": 1096,
  "method": {
    "entityType": "ThingTemplate",
    "entityName": "Stream",
    "service": "QueryImplementingThingsOptimizedWithTotalCount"
  }
}
```

*(Numeric examples such as `1096` are illustrative only, not normative.)*

When a **cached taxonomy row** **`Synonyms`** token matches the user phrase, **Phase −1** applies first — the counted **EntityType**/**EntityName** come from that row (see **§3**), not from this platform-`Stream` illustration.

The agent should not first call `GetEntityList(type="Stream")`, and it should not infer zero from `SearchModelEntities`.

## 3. Resolution order

Use this order for a live Java implementation. **The order is normative** for any future resolver or routing logic. Parler runs **in-process**: prefer **exact entity lookup** (see **Phase 1a — Java API**) before expensive wildcard catalog scans.

### Phase −1: Taxonomy hit (application catalog) — **highest priority**

When the turn includes **cached taxonomy rows** (from loaded **`identity-types.json`**) and/or optional **`type-taxonomy.md`** prose in the stable system prompt:

1. Normalize the **resolver input key** (the phrase the agent is resolving — not the full user utterance unless that is the key) using the **same rules as Phase 0** (trim, ASCII lower, strip whitespace and `-` `_` `.` `/`) → `normalized_user`.
2. For **each** taxonomy row, split **`Synonyms`** on **`;`**, **trim** each fragment, and apply the **same Phase 0 normalization** to each fragment → `normalized_synonym_i`. **v1 match rule:** `normalized_user` **equals** `normalized_synonym_i` for **any** `i`. **Do not** use substring / “contains” matching on normalized strings (too many false positives for App User chat). Plural vs singular (`robot` / `robots`) is **out of v1** unless listed as separate synonym tokens — fix taxonomy or extend Java later. **Caller branch does not filter rows:** evaluate synonym equality across **all** rows **before** applying any notion of “this call was `thingTemplate` vs `thingShape`.” **Do not** discard a row because the tool argument chose the opposite branch from that row’s **`EntityType`**.
3. If any row matches, use that row’s **`EntityType`** and **`EntityName`** as the resolution and **skip Phase 0.5 and Phases 1–3** for choosing the parent template/shape. Infer **`ThingworxRelationshipTypes`** from **`EntityType`** (`ThingTemplate` vs `ThingShape`) for **`findEntity`** and QIT even when that **overrides** a mismatched caller branch. **No second pass** through platform template search for the same key in the same turn.
4. **Evaluate `Synonyms` before rejecting incomplete rows.** If **`Synonyms`** matched but **`EntityType`** / **`EntityName`** are missing, blank, or **`EntityType`** is not exactly **`ThingTemplate`** or **`ThingShape`**, treat this as **taxonomy row data error**. The resolver returns a row-invalid outcome; tool callers map it to **`TAXONOMY_ROW_INVALID`** — **do not** fall through to Phase 0 hints or wildcard **`GetEntityList`** for the same key (that would mask a mis-authored row).
5. If **`Synonyms`** matched, the row is well-formed, but **`findEntity(EntityName, …)`** returns **`null`**, treat this as **taxonomy points at missing model entity**. The resolver returns an entity-unresolved outcome; callers map it to **`TAXONOMY_ENTITY_UNRESOLVED`** — **do not** fall through to Phase 0 hints or wildcard **`GetEntityList`** for the same key.
6. **No “taxonomy hijack” or dual-candidate flow.** Parler does **not** treat a taxonomy hit as competing with “the platform `Stream` / `DataTable` ThingTemplate meaning.” At app-design time, names like **`Stream`**, **`DataTable`**, **`ValueStream`** are **reserved** platform ThingTemplate families; application developers **cannot** reuse those strings as unrelated template names. If taxonomy **`Synonyms`** incorrectly include such tokens, that is a **taxonomy data bug** — **fix the taxonomy**, do **not** add resolver logic to detect or arbitrate overlaps.
7. **Contrast with external MCP stacks:** tools outside ThingWorx (e.g. Conflu) usually **do not** receive cached **`identity-types.json`** rows and may need extra disambiguation. **Do not** copy their overlap rules into Parler.

If **no** cached taxonomy rows are available for this turn, **skip Phase −1** and start at Phase 0. If rows exist but **no** row matches, continue with Phase 0.

### Phase 0.5: GenericThing incoming dependencies (exact `EntityDescriptor.name`)

Runs **after** Phase −1 miss and **before** Phase **`findEntity` / wildcard** paths for the same resolver input key.

1. Resolve ThingTemplate **`GenericThing`** and invoke **`GetIncomingDependencies(maxItems=2000)`** (platform default **`maxItems`** is lower; Parler overrides to **`2000`** for v1).
2. Build the same **candidate name set** as Phase 1a uses for lookups (see Phase 0 + Phase **1a** ordering): **`trimmed_raw_key`**, then Phase 0 **hint-table** canonical when present (**`datatable` → `DataTable`**, etc.), then **capitalize-first** on the Phase-0–normalized key when non-empty.
3. **`String.equals`** against the dependency row **`name`** field only (no substring / wildcard).
4. When multiple rows match one candidate **`name`**, prefer **`ThingTemplate`** over **`ThingShape`** over treating the row as a QIT parent. Rows whose **`type`** is **`Thing`** (or non–template/shape model kinds used only as dependents) **do not** satisfy parent resolution — continue candidates / fall through.
5. On success, **`findEntity(name, inferred ThingTemplate | ThingShape)`** must succeed; callers map **`KeyResolutionReason.GENERIC_THING_INCOMING_DEPENDENCY`** (**§10**) and may emit **`keyResolution`** **`requestedParentKind` / `effectiveParentKind`** when the tool argument branch differed (**same wire pattern as taxonomy branch override**).
6. **`list_entities_by_type`:** when **`GetEntityList*`** fails with an invalid **`type`** (**`Stream`**-style misuse), rerun this dependency match on **`entityCollectionType`**; if it hits **`ThingTemplate` / `ThingShape`**, return structured **`ENTITY_COLLECTION_TYPE_RESOLVED_AS_MODEL_KEY`** repair JSON ( **`repair.tool` = `query_entities`** ) rather than implying zero instances.

Phase 0.5 is **skipped** whenever Phase −1 matched — taxonomy remains strictly higher priority.

**Prompt-context cache (v1):** When the **`AgentThing`** in-memory snapshot is populated (`docs/agent/system-prompt-cache.md`), **`ModelKeyResolver`** reads **ThingTemplate** `name` values from the cache for Phase 0.5 without calling **`GetIncomingDependencies`** on every tool invocation. The cached catalog intentionally lists **ThingTemplate** rows only (deployment-stable model keys). If the snapshot is absent (startup refresh failed), the resolver falls back to the live dependency table — **`IncomingDependencyMatcher`** may still observe **ThingShape** rows on that live path.

### Phase 0: Normalize and apply platform hints

Normalize for comparison:

- trim
- lowercase ASCII
- remove **all** whitespace characters inside the key (so e.g. `Data Table` → `datatable`, not merely trimming ends)
- remove common separators: `-`, `_`, `.`, `/`

Recommended built-in hints (extend when new overloaded keys are observed; hints are **not** contracts—always verify live):

| Normalized key | Preferred candidate |
|----------------|---------------------|
| `datatable`, `datatablething` | ThingTemplate `DataTable` |
| `stream`, `streamthing` | ThingTemplate `Stream` |
| `valuestream`, `valuestreamthing` | ThingTemplate `ValueStream` |

Hints are not enough by themselves. Verify against live model rows or direct entity lookup.

### Phase 1: ThingTemplate resolution

Only after **Phase −1** miss **and Phase 0.5** miss, continue template/shape **`findEntity`** and wildcard (**1b**) as below.

**1a — Exact lookup (JVM).** Try **`EntityUtilities.findEntity`** using the **caller-requested branch** (**`ThingTemplate` vs `ThingShape`** from **`thingTemplate` vs `thingShape`** on **`query_entities`**) in this **fixed order** until one call returns non-null (then skip **1b**):

1. **`findEntity(trimmed_raw_key, ThingworxRelationshipTypes.ThingTemplate)`** — case-sensitive platform name as the user typed it (after trim).
2. If Phase 0’s **hint table** maps the normalized key to a canonical template name (e.g. `datatable` → **`DataTable`**), **`findEntity(canonical_name, ThingworxRelationshipTypes.ThingTemplate)`**.
3. **First-character uppercase on normalized key:** if the Phase 0–normalized string is **non-empty**, try **`findEntity(Character.toUpperCase(normalized.charAt(0)) + normalized.substring(1), ThingworxRelationshipTypes.ThingTemplate)`** (e.g. `stream` → `Stream`). Step **2** (hint table) already maps reserved platform names such as `datatable` → **`DataTable`**; step **3** mainly helps **app-specific** templates whose names follow a simple capitalize-first pattern. Residual misses go to **1b**.

**Phase 1a — Java API (authoritative)**

- **Class:** `com.thingworx.entities.utils.EntityUtilities`
- **Method:** `public static RootEntity findEntity(String name, com.thingworx.relationships.RelationshipTypes.ThingworxRelationshipTypes type)`, called through **`PlatformAccess.findAsUser`**
- **ThingTemplate parent:** `ThingworxRelationshipTypes.ThingTemplate`
- **ThingShape parent (Phase 2):** `ThingworxRelationshipTypes.ThingShape`
- **Miss behavior:** returns **`null`** (does not throw for “not found”).
- **Visibility:** honors the current user's **Visibility** permission, as REST does. A template or shape the user cannot see resolves like a missing one; the resolver does not look past ThingWorx visibility.
- **Cast:** on success, narrow `RootEntity` to **`ThingTemplate`** / **`ThingShape`** as appropriate for the call site.
- **SecurityContext:** the lookup and the subsequent **`processAPIServiceRequest`** / QIT calls all run under the **current user's** platform security context.

This is the concrete JVM advantage over external REST clients that cannot call `EntityUtilities` in-process.

**Java (`parler-agent`):** **`ModelKeyResolver`** + **`KeyResolutionReason`** implement §3 for **`query_entities`** — Phase −1 scans **all** taxonomy rows for synonym equality (caller **`thingTemplate`** vs **`thingShape`** does not pre-filter rows); then **`GenericThingIncomingDependencyResolver`** / **`IncomingDependencyMatcher`** (Phase **0.5**); **`findEntity`** ordering; wildcard **`GetEntityList`**. Optional **`keyResolution`** wire: **§10**.

**1b — Wildcard `GetEntityList`.** If 1a misses, use `Resources/EntityServices/Services/GetEntityList`:

```json
{
  "type": "ThingTemplate",
  "nameMask": "*<key>*",
  "maxItems": 500
}
```

Rank results:

1. exact case-sensitive name
2. exact case-insensitive name
3. normalized match
4. substring match

For `Stream`, this phase should find the platform template `Stream` without ever needing an entity-type probe.

### Phase 2: ThingShape resolution

Repeat **1a / 1b** with **`ThingShape`** instead of **`ThingTemplate`**.

**When Phase 1 already found a ThingTemplate**, Phase 2 does not override it; run the shape branch only when Phase 1 produced **no** ThingTemplate candidate.

**Precedence:** when both template and shape yield equally strong normalized-or-better matches, **ThingTemplate wins**, unless app taxonomy explicitly points at a shape.

### Phase 3: Spotlight search (fuzzy only)

Use `SearchFunctions.SpotlightSearchV2` with `searchDescriptions=true`.

Useful for fuzzy labels; **not** authoritative for counts. Rank behind Phases 1–2.

### Diagnostics only (not part of the default App User phase list)

The following are **developer / troubleshooting** paths only. **Do not** expose them as numbered steps to the chat model in normal Parler routing.

**Entity-type probe** — `GetEntityList` with `"type": "<raw key>"` is the same failure mode as treating `Stream` as an entity collection type; it produces `Invalid Entity Type [Stream]` for template-family keys. Empty rows or errors here are **not** evidence of zero instances.

**`SearchModelEntities`** — broad fallback only. It is **not** proof of zero instances; Stream **Things** often appear as rows with `type = "Thing"`. Absence of `type = "Stream"` is meaningless for counting Stream **instances**.

## 4. Intents and output shape

Parler has no separate resolver tool: these three intents are served by existing tools (`query_entities` with optional **`keyResolution`** — §10 — and `includeConcreteTemplate`, taxonomy tools, `spotlight_search`). The JSON below illustrates the information each intent must convey; it is not a tool wire format. The intents should still stay distinct.

### `resolve`

Return ranked candidates and warnings. This is for ambiguity inspection.

Example:

```json
{
  "key": "Stream",
  "intent": "resolve",
  "candidates": [
    {
      "kind": "ThingTemplate",
      "name": "Stream",
      "confidence": "high",
      "reason": "Exact case-sensitive name match"
    }
  ],
  "warnings": []
}
```

Phase −1 (taxonomy synonym) example:

```json
{
  "key": "Stacking robot",
  "intent": "resolve",
  "candidates": [
    {
      "kind": "ThingTemplate",
      "name": "ACME_StackingRobot_TT",
      "confidence": "high",
      "reason": "Taxonomy synonym match (AssetType=StackingRobot; normalized key matched Synonyms row)"
    }
  ],
  "warnings": []
}
```

### `count_instances`

After a high-confidence ThingTemplate or ThingShape is found, call:

- ThingTemplate parent: `ThingTemplates/<name>/Services/QueryImplementingThingsOptimizedWithTotalCount`
- ThingShape parent: `ThingShapes/<name>/Services/QueryImplementingThingsOptimizedWithTotalCount`

Return compact output. Do not return a full candidate flood.

Example:

```json
{
  "key": "Stream",
  "intent": "count_instances",
  "resolvedAs": {
    "kind": "ThingTemplate",
    "name": "Stream",
    "confidence": "high"
  },
  "count": 1096,
  "method": {
    "entityType": "ThingTemplate",
    "entityName": "Stream",
    "service": "QueryImplementingThingsOptimizedWithTotalCount"
  },
  "queryComplete": true,
  "warnings": []
}
```

If the platform returns the outer `ImplementedThingsWithTotalCount` row but `totalCount` is null, return `queryComplete=true` but include a note such as:

```json
{
  "count": null,
  "note": "Platform returned rootEntityList but omitted totalCount; do not interpret this as zero."
}
```

Parler's existing `query_entities` design already documents this null-total case.

### `list_instances`

Same query as `count_instances`, but request enough rows for a bounded sample.

Return `name` and, when present, `thingTemplate`.

Example:

```json
{
  "key": "Stream",
  "intent": "list_instances",
  "count": 1096,
  "sample": [
    {
      "name": "A_DDA220503_01_DE_X_VibrationAnalysis_FrequencyTrend_Stream",
      "thingTemplate": "Northwind_VibrationAnalysis_FrequencyTrend_VariableSpeed_Stream_ThingTemplate"
    }
  ]
}
```

Do not attach "next actions" telling the LLM to repeat the same platform query when the resolver already executed it.

## 5. Parsing `QueryImplementingThingsOptimizedWithTotalCount`

The service returns an `ImplementedThingsWithTotalCount` InfoTable.

Normal success shape:

- outer InfoTable: one row
- field `totalCount`: NUMBER, may be null on some platform paths
- field `rootEntityList`: nested InfoTable of Things

Pseudo-Java:

```java
InfoTable outer = invoke(parent, "QueryImplementingThingsOptimizedWithTotalCount", params);
ValueCollection row = outer.getFirstRow();
InfoTable things = (InfoTable) row.getValue("rootEntityList");
Long total = readNullableLong(row.getValue("totalCount"));

for (ValueCollection thingRow : things.getRows()) {
    String name = stringValue(thingRow, "name");
    String thingTemplate = stringValue(thingRow, "thingTemplate");
}
```

Implementation rules:

- Read actual Thing rows from `rootEntityList.rows`, not from the outer wrapper row.
- Read `totalCount` from the same outer row.
- If `totalCount` is null, do not call it zero.
- Permissions and current security context still apply.
- Use paging and Parler's existing large-table cache for large result sets.

See also:

- [`query_entities_design.md`](query_entities_design.md)
- [`query_with_total_count.md`](query_with_total_count.md)
- [`query-construction.md`](query-construction.md) — shared **QUERY** JSON used inside QIT and other services

## 6. Breakdown by direct concrete `thingTemplate`

This breakdown behavior was the most important follow-up from early integration testing on live models.

When a user asks:

```text
Break down Streams by template.
Group Streams by ThingTemplate.
Which Stream templates have the most instances?
```

the default interpretation should be:

> Group each implementing Thing by its direct concrete `thingTemplate` field.

Do not attempt to discover "derived templates" by guessing services such as `GetDerivedThingTemplates` or `GetDescendingTemplates`. Those guesses caused errors and large irrelevant payloads in testing.

### Correct workflow

1. Resolve the base key:

```json
{
  "key": "Stream",
  "intent": "count_instances"
}
```

2. Query the resolved parent with enough rows:

```json
{
  "entityType": "Thing",
  "thingTemplate": "Stream",
  "maxItems": 5000,
  "includeConcreteTemplate": true
}
```

For a lower-level implementation, this means `QueryImplementingThingsOptimizedWithTotalCount` on `ThingTemplate=Stream`, with:

- `basicPropertyNames`: `name`
- `propertyNames`: `thingTemplate`
- `maxItems`: enough to cover the requested page or full result
- `offset`: page offset

3. Read every returned row:

```json
{
  "name": "ThingName",
  "thingTemplate": "ConcreteTemplateName"
}
```

4. Group locally:

```java
Map<String, Integer> counts = new TreeMap<>();
for (ValueCollection row : things.getRows()) {
    String template = stringValue(row, "thingTemplate");
    if (template == null || template.isBlank()) {
        template = "(missing)";
    }
    counts.merge(template, 1, Integer::sum);
}
```

5. Report top groups and, when useful, examples.

### Important distinction

Direct concrete `thingTemplate` grouping is the normal and most useful behavior.

Ancestor roll-up is different. If the user explicitly asks for "roll up by ancestor template family", the implementation also needs template inheritance data. Do not infer ancestor roll-ups from `thingTemplate` alone.

**User-facing behavior (v1):** If the user insists on ancestor / inheritance rollup, the assistant should **say clearly** that Parler v1 supports **direct concrete `thingTemplate` grouping only**, ask whether that breakdown is acceptable, and **do not** silently substitute a direct histogram for an ancestor rollup.

## 7. Java implementation mapping for Parler

Parler covers these needs with existing tools:

| Need | Parler surface |
|------|--------------------------------|
| Structured Thing listing | `query_entities` (parent key via **`ModelKeyResolver`**) |
| Exact total and paging | `QueryImplementingThingsOptimizedWithTotalCount` path in `QueryEntitiesExecutor` |
| Direct concrete template column | `includeConcreteTemplate=true` for ThingTemplate parent |
| Large result paging | existing cached tabular / `fetch_cached_result` pattern |
| Taxonomy-supplied parent | `query_entities_by_taxonomy` |
| Fuzzy search | `spotlight_search` |
| Metadata entity listing | `list_entities_by_type`, not for Thing instances |

There is no dedicated breakdown tool: routing guidance steers "breakdown by template" to `query_entities` with `includeConcreteTemplate=true`, then local aggregation. No skill restates this workflow; this document is the single rule set.

## 8. Tool routing guidance

Recommended system/tool description guidance:

- Use `query_entities` for Thing instances under a ThingTemplate or ThingShape.
- Use `list_entities_by_type` for metadata entities such as ThingTemplate, ThingShape, Mashup, DataShape. Do not use it to list Thing instances.
- For "how many Streams/DataTables/ValueStreams", resolve the key as a ThingTemplate first, then count implementors.
- For "breakdown/group by template", request direct concrete `thingTemplate` rows and aggregate locally.
- For app taxonomy questions, prefer the taxonomy row's explicit parent type/name over guessing from user text.
- Use `spotlight_search` for fuzzy name discovery, not for authoritative counts.
- **`Stream` / `DataTable` (and similar)** in user chat usually mean the **ThingTemplate** name for data-store Things—**not** `EntityServices.GetEntityList(type="Stream")`. Treat `Invalid Entity Type [Stream]` (and similar) as **not** proof of zero instances.
- **Default “breakdown by template”** means **direct** `thingTemplate` on each implementing Thing (`includeConcreteTemplate=true` on a ThingTemplate parent), not ancestor template families unless the user asks for inheritance rollup.

Shipped routing text: see **`parler-agent/src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt`** (must stay aligned with §8–§9).

## 9. Anti-patterns observed in testing

Avoid these paths:

| Anti-pattern | Why it is wrong |
|--------------|-----------------|
| `GetEntityList(type="Stream")` as first step | `Stream` is usually a ThingTemplate name, not an EntityServices collection type. |
| `SearchModelEntities(types=["Stream"])` | `Stream` is not an entity row type; Stream instances are Things. |
| Inferring zero from absence of `type="Stream"` | The row type for instances is `Thing`. |
| `GetDerivedThingTemplates` / `GetDescendingTemplates` guesses | Not reliable platform services for this workflow; caused invalid-service errors. |
| Pulling `GetServiceDefinitions` to discover a count path | Huge payload and irrelevant once QIT WithTotalCount is known. |
| Repeating low-level invoke after a resolver already returned count | Wastes calls and can confuse the model. |
| Grouping by ancestor template unless requested | Most user prompts mean direct concrete `thingTemplate`. |
| `GetEntityList(type=<raw key>)` in normal App User flows (even “just to check”) | Same trap as the first row; use only behind **`diagnostics=true`** or operator tooling—not default chat routing. |

## 10. KeyResolutionReason (`query_entities` wire)

Successful **`query_entities`** responses may include **`keyResolution`** when Phase **0.5** ran, when the tool input differed from the resolved platform name, when **Phase −1** taxonomy synonym applied (even if names match), or when taxonomy **or Phase 0.5** **overrode** the caller’s template vs shape branch. Field **`reason`** is the Java enum name (stable contract):

| `KeyResolutionReason` | Meaning |
|----------------------|---------|
| `TAXONOMY_SYNONYM` | Phase −1: **`Synonyms`** matched; parent **`EntityType`** / **`EntityName`** from that row. |
| `GENERIC_THING_INCOMING_DEPENDENCY` | Phase 0.5: exact **`name`** on **`GenericThing.GetIncomingDependencies`** rows; **`resolvedName`** is the authoritative parent (**ThingTemplate** or **ThingShape** entity name on the **live** dependency path; **ThingTemplate** when resolved from the **cached** ThingTemplate-name list). |
| `EXACT_RAW_LOOKUP` | Phase 1a: **`findEntity`** on trimmed raw key. |
| `HINT_TABLE` | Phase 1a: canonical name from built-in hint (`stream` → **`Stream`**, etc.). |
| `CAPITALIZE_NORMALIZED` | Phase 1a: capitalize-first on Phase-0–normalized key. |
| `WILDCARD_GET_ENTITY_LIST` | Phase 1b: wildcard **`GetEntityList`** + §3 ranking. |

When taxonomy **or Phase 0.5** overrides **`thingTemplate`** vs **`thingShape`**, **`keyResolution`** also includes **`requestedParentKind`** and **`effectiveParentKind`** (`ThingTemplate` / `ThingShape`).

**Examples (success payloads):**

```json
{
  "keyResolution": {
    "inputKey": "stacking robot",
    "reason": "TAXONOMY_SYNONYM"
  }
}
```

```json
{
  "keyResolution": {
    "inputKey": "stacking robot",
    "reason": "TAXONOMY_SYNONYM",
    "requestedParentKind": "ThingTemplate",
    "effectiveParentKind": "ThingShape"
  }
}
```

Phase **0.5** branch correction (caller passed **`thingShape`** but **`Stream`** resolves as **`ThingTemplate`**):

```json
{
  "keyResolution": {
    "inputKey": "Stream",
    "reason": "GENERIC_THING_INCOMING_DEPENDENCY",
    "requestedParentKind": "ThingShape",
    "effectiveParentKind": "ThingTemplate",
    "resolvedName": "Stream"
  }
}
```

## 11. Suggested tests

Add or keep tests covering:

1. A **taxonomy synonym** hit **short-circuits** wildcard template search (Phase −1 before Phase 1b); **synonym strings** are normalized with the **same rules as the user key** before comparison; **no substring** match; **synonym match on a malformed row** (**`TAXONOMY_ROW_INVALID`**) does **not** fall through to Phase 0 / 1b.
2. **`EntityUtilities.findEntity`** runs **before** `GetEntityList` wildcard inside **`ModelKeyResolver`** — integration tests should assert **call sequence** (or observable behavior such as **`keyResolution.reason`** not equal to **`WILDCARD_GET_ENTITY_LIST`** when an exact path should win), not only final **`parentName`**.
3. `Stream` resolves as a ThingTemplate before any raw `GetEntityList(type="Stream")` probe.
4. `Invalid Entity Type [Stream]` is warning/diagnostic only, not zero proof.
5. `QueryImplementingThingsOptimizedWithTotalCount` parsing uses outer `totalCount` and nested `rootEntityList`.
6. `list_instances` / `query_entities includeConcreteTemplate` returns `name` and `thingTemplate`.
7. `count_instances` compact output has no redundant "call this next" instruction.
8. `thingTemplate` column is requested only for ThingTemplate-parent queries, not ThingShape-parent queries.
9. Direct concrete template breakdown groups rows by `thingTemplate` and preserves total row count.

## 12. Product boundary (summary)

Parler should stay **safer and more opinionated** than integrator-first stacks: first-class tools and taxonomy over arbitrary invoke, compact user-relevant summaries, cache/paging for large tables, default **direct concrete `thingTemplate`** breakdowns. See **§0** for product context. For **QUERY** JSON shared across QIT, Data Tables, and Streams, see [`query-construction.md`](query-construction.md).

## 13. Pre-HITL `invoke_service` rewrite and downstream metadata

**Invariant:** When `AgentThing` (or equivalent) mutates **`ToolCall` arguments before HITL approval** — e.g. normalizing **`entityType`** (GenericThing template name → `Thing`) or hoisting declared service inputs into **`parameters`** — **any wire metadata that the executor would normally attach after that rewrite MUST still appear** on success or error envelopes after approval and execution.

**Why:** Downstream serializers compare the **effective** invocation (post-rewrite `ToolCall`) to **`ServiceDefinition`**. Once args are already rewritten, **no second-pass merge may run**, so recording “what correction happened” exclusively in transient prep state **drops telemetry and LLM correction signals** unless it is carried explicitly.

**Canonical pattern (mirror for each distinct metadata dimension):**

1. **`bind(toolCallId, …)` before** `tryEnqueueParlerHitlPending` / `executeTool` on the dispatch thread, keyed by **`ToolCall.getId()`** (single-slot ThreadLocals are keyed by id to prevent bleed when multiple calls share a JVM context thread).
2. **`PendingApprovalRecord` field(s)** preserving the **same semantic object(s)** across the approval boundary (ThreadLocal alone is insufficient — the awaiting thread differs from the resumed continuation).
3. On **HITL approval continuation**, **`bind`** again from the record before **`InvokeServiceExecutor.executeInvokeService`**.
4. In the consumer (`InvokeServiceExecutor` or equivalent): **`applyIfAny(locallyDerived, toolCall)`** — prefer freshly derived state when present; otherwise use the bound pre-HITL state when **`toolCall` id matches**.
5. **`unbind()` in `finally`** on both non-HITL and continuation paths.

**Existing bindings in `parler-agent`:**

- **`InvokeServiceEntityTypeNormalization`** — **`entityTypeNormalized`** after **`entityType` → `Thing`** rewrite.
- **`InvokeServiceParameterRepairBinding`** — **`parametersNormalized`** after top-level→**`parameters`** hoisting (`InvokeServiceParameterNormalizer.Repair`).

**Maintainer check:** *If a change adds or extends a **pre-HITL** **`ToolCall` argument rewrite**, does every serializer that depends on comparing “raw vs effective” inputs still emit the intended metadata (`entityTypeNormalized`, `parametersNormalized`, …) on success **and** on structured execution errors — including paths that enqueue HITL?*
