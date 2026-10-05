# query_entities — current design

**`query_entities`** lists the **Thing instances** that implement one **ThingTemplate** or one **ThingShape**, through the platform's **QueryImplementingThings\*** services on that parent. It is implemented by **`QueryEntitiesExecutor`**. For the shared QUERY grammar and platform QIT limits see **[`query_capability.md`](./query_capability.md)** (§8); for the **`QueryImplementingThingsOptimizedWithTotalCount`** result shape and column selection see **[`query_with_total_count.md`](./query_with_total_count.md)**.

---

## 1. Positioning

| Tool | Use it for |
|------|------------|
| **`query_entities`** | Structured listing of Things under a known ThingTemplate / ThingShape, with optional name mask, QUERY filters, model tags, column toggles, and name-set intersection. |
| **`spotlight_search`** | Fuzzy, cross-type keyword exploration (`SearchFunctions.SpotlightSearchV2`). Use it when the parent template/shape is not known. |
| **`list_entities_by_type`** | Metadata entities of one collection type (ThingTemplate, ThingShape, Mashup, DataShape, …) via **EntityServices** `GetEntityList` / `GetEntityListByRegEx`. The **Thing** collection is rejected there. |
| **`invoke_service`** | A service already known from prior results or member discovery, including a QIT call with parameters this tool does not expose. |
| **`query_entities_by_taxonomy`** | Asset-class listings driven by the application taxonomy (**`resolve_asset_type`** first). |

`query_entities` does not list "all Things"; a parent is always required.

## 2. Inputs

| Argument | Behavior |
|----------|----------|
| **`entityType`** | Schema-required. Any value other than `Thing` is logged and the call still lists Thing instances. |
| **`thingTemplate`** / **`thingShape`** | Exactly one. Blank strings count as absent. Both → **`BOTH_TEMPLATE_AND_SHAPE`**; neither → **`MISSING_TEMPLATE_OR_SHAPE`** (message points to **`spotlight_search`**). The mutual exclusion is enforced in the executor, not as a root `oneOf` in the JSON Schema. |
| **`maxItems`** | Default **50**, clamped to **1–200**. Passed to the service's `maxItems` / `nMaxItems` parameter. |
| **`offset`** | Default **0**; negative values become 0. Passed when the service defines an `offset` parameter. |
| **`namePrefix`** | Passed as the platform `nameMask` when the service defines one. |
| **`withPermissions`** | Default false. Sets the Optimized services' `withPermissions` parameter. **`withData`** is accepted as a legacy alias for the same flag. |
| **`query`** | Optional ThingWorx QUERY object (filter tree under **`query.filters`**). Checked by **`QueryEntitiesQueryAdmission`** before parent resolution and before the QIT call (§5), then passed as a `JSONPrimitive`. Optional hierarchy expansion (`hierarchyNodeId` / `hierarchyNodeName`) runs earlier and may already call `ResolveNetworkID` / `GetAssetList`, so a hierarchy error can be returned before a predicate error. |
| **`modelTags`** | Optional `[{vocabulary, vocabularyTerm}]`; sets the service's TAGS parameter (AND). Distinct from `TAGGED` filters inside `query`. |
| **Column options** | **`includeDescription`**, **`includeIsSystemObject`**, **`includeTags`**, **`includeConcreteTemplate`** (ThingTemplate parent only), **`widePropertyColumns`**. Default is lean: `basicPropertyNames` = `name`, `propertyNames` = empty `EntityList`. Details: [`query_with_total_count.md` §10](./query_with_total_count.md#10-tool-implementation-defaults-llm-parameters-and-edge-cases). |
| **`intersectThingNames`** / **`intersectExpandHasMore`** | Optional Thing-name set (max **5000**); rows are kept only when `name` is in the set (exact `String.equals`). |
| **`hierarchyNodeId`** / **`hierarchyNodeName`** | Optional hierarchy scope. When no non-empty `intersectThingNames` is supplied, the server builds that set from `GetAssetList` (by id directly, or after `ResolveNetworkID` for a name fragment). Precedence and errors are normative in **`CONTRACTS/API_CONTRACT.md`** (*query_entities / query_entities_by_taxonomy — expand intersect*). |

## 3. Parent resolution

The supplied template or shape name goes through **`ModelKeyResolver.resolveQueryEntitiesParent`** (see [`key-resolution.md`](./key-resolution.md)):

1. **Taxonomy synonym** (when taxonomy rows are loaded): a matching row's `EntityType` / `EntityName` wins, even if it switches ThingTemplate ↔ ThingShape.
2. **GenericThing incoming dependencies**: exact model-key match (e.g. `Stream`, `DataTable`) to the implementing template or shape.
3. Exact lookup, hint-table canonical name, capitalized normalized key, then a wildcard `GetEntityList` lookup, all on the caller's parent kind.

Resolution errors:

| Code | When |
|------|------|
| `KEY_RESOLUTION_ERROR` | The resolver threw. |
| `TAXONOMY_ROW_INVALID` | A taxonomy synonym matched a row with unusable type/name data. |
| `TAXONOMY_ENTITY_UNRESOLVED` | A taxonomy synonym matched but its `EntityName` does not exist in the model. |
| `KEY_RESOLUTION_AMBIGUOUS` | The GenericThing dependency match was ambiguous. |
| `ENTITY_NOT_FOUND` | No parent matched. |
| `NOT_SERVICE_PROVIDER` | The resolved entity does not expose services. |

On success the output includes **`keyResolution`** when the GenericThing path was used, the resolved parent name differs from the input, a taxonomy synonym matched, or the effective parent kind differs from the requested one. It carries `inputKey` and `reason`, plus `resolvedName` (GenericThing path) and `requestedParentKind` / `effectiveParentKind` (kind switch).

## 4. Service selection

The executor uses the first service the parent defines, in this order:

1. **`QueryImplementingThingsOptimizedWithTotalCount`**
2. **`QueryImplementingThingsOptimized`**
3. **`QueryImplementingThings`**

None available → **`SERVICE_NOT_FOUND`**. Parameters are set by matching the chosen service's parameter definitions (name and base type); parameters the service does not define are skipped. `isSortFirst` and network filters are never set. A platform exception → **`QUERY_SERVICE_FAILED`**.

The result is unwrapped from the one-row **`ImplementedThingsWithTotalCount`** table (`rootEntityList` + `totalCount`) when present; other shapes are read as a plain Thing list without a total.

When `totalCount` is null on **`QueryImplementingThingsOptimizedWithTotalCount`**, the executor infers an exact total when it can. Here `returnedRows` means the rows the QIT page returned, before any `intersectThingNames` filtering. A non-empty short page (`0 < returnedRows < maxItems`) gives `offset + returnedRows`; a full page triggers one probe at `offset + maxItems` (`maxItems` 1), and an empty probe gives `offset + returnedRows`. An **empty** page is never used for inference: it may already lie past the last row, so `totalRows` stays `null` and `totalRowsInferred` is not set. Otherwise, too, the total stays unknown.

## 5. QUERY admission

**`QueryEntitiesQueryAdmission`** runs after optional hierarchy expansion and before parent resolution and the QIT call:

- `query` must parse to a QUERY object; `query.filters`, when present, must be an object. Violations → **`INVALID_PREDICATE`** (with `path`).
- At most **32** leaf conditions and composite depth **4** → otherwise **`TOO_MANY_PREDICATES`**.
- Allowed leaf types are ThingWorx-native: `EQ`, `NE`, `LT`, `LE`, `GT`, `GE`, `IN`, `NOTIN`, `BETWEEN`, `NOTBETWEEN`, `LIKE`, `NOTLIKE`, `NEAR`, `NOTNEAR`, `TAGGED`, `NOTTAGGED`, `MISSINGVALUE`, `NOTMISSINGVALUE`, inside `AND` / `OR` composites. Parler in-memory-only leaves (`CONTAINS`, `STARTSWITH`, `ENDSWITH`, `ISEMPTY`, and their negations) and any other type → **`UNSUPPORTED_OPERATOR`**.
- Composite `NOT` → **`UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES`** with `recoveryHint` `{tool: "analyze_entity_set", operation: "difference"}`.
- Leaf value keys are checked per type (`values` for IN, `from`/`to` for BETWEEN, `location`/`distance` for NEAR, `tags` for TAGGED, `value` for comparisons and LIKE; LIKE patterns up to 256 characters). Unknown leaf keys → **`INVALID_PREDICATE`**.

Admission errors carry `status`, `code`, `message`, and optional `path` / `recoveryHint`. Guardrail rationale: [`model-tool-admission-guardrails.md`](./model-tool-admission-guardrails.md).

## 6. Result

Success fields:

| Field | Meaning |
|-------|---------|
| `status` | `success` |
| `service` | QIT service actually called |
| `parentKind` / `parentName` | Effective parent kind (`ThingTemplate` / `ThingShape`) and resolved name |
| `keyResolution` | See §3 (conditional) |
| `returnedRows` | Rows in this response's page (after intersection, when active) |
| `offset`, `maxItems` | Effective paging values |
| `columnMode` | `lean` or `wide` |
| `totalRows` | Platform `totalCount` (or inferred total); `null` when unknown |
| `hasMore` | With a total: `offset + page rows < totalRows`; without one: `page rows ≥ maxItems` (heuristic). With intersection active it is `queryHasMore OR expandHasMore`. |
| `totalRowsInferred` | `true` when the total was inferred (§4); a `note` explains when that total is exact |
| `note` | Present when the total is unknown or inferred, or when intersect entries were dropped |
| `preIntersectMatchCount`, `intersectedRowCount`, `queryHasMore`, `expandHasMore` | Only when intersection is active; query-side values use the page before intersection |
| `resultKind` | `ENTITY_QUERY_EMPTY`, `ENTITY_QUERY_INLINE`, or `ENTITY_QUERY_LARGE` |
| `columns` | Column metadata for the slim rows (present when rows are returned) |
| `rows` / `sampleRows` | Slim rows: `entityType` (`Thing`), `name`, and when present `description` (truncated to 400 chars), `thingTemplate`, `tags` (truncated to 200 chars), `isSystemObject` |
| `cacheId`, `hint` | Only for `ENTITY_QUERY_LARGE` |

`totalRows` is the full match count, not the page size; a null `totalRows` means unknown, not zero.

The inline/large split comes from **`ToolResultEgressGateway.planTabularEvidence`**, which uses the shared egress array sample limit for first-party tabular results. At or below the limit the result is `ENTITY_QUERY_INLINE` with every row in `rows`. Above it the result is `ENTITY_QUERY_LARGE`: the returned page is stored in the conversation cache, the response carries `sampleRows` (up to the limit), `cacheId`, and a hint to browse with **`fetch_cached_result`** or compute with cached-table tools. The cache holds this call's page (at most `maxItems` rows), not every match; use `offset` for the next platform page.

## 7. Error codes

| Code | Source |
|------|--------|
| `BOTH_TEMPLATE_AND_SHAPE`, `MISSING_TEMPLATE_OR_SHAPE` | Parent argument validation |
| `INTERSECT_LIST_TOO_LARGE` | More than 5000 `intersectThingNames`, or a hierarchy expand above that cap |
| `HIERARCHY_RESOLVE_FAILED`, `HIERARCHY_RESOLVE_NOT_FOUND`, `HIERARCHY_RESOLVE_AMBIGUOUS`, `HIERARCHY_ASSET_LIST_FAILED`, `HIERARCHY_SCOPED_EMPTY` | Hierarchy scope expansion |
| `INVALID_PREDICATE`, `TOO_MANY_PREDICATES`, `UNSUPPORTED_OPERATOR`, `UNSUPPORTED_PREDICATE_FOR_QUERY_ENTITIES` | QUERY admission (§5) |
| `KEY_RESOLUTION_ERROR`, `TAXONOMY_ROW_INVALID`, `TAXONOMY_ENTITY_UNRESOLVED`, `KEY_RESOLUTION_AMBIGUOUS`, `ENTITY_NOT_FOUND`, `NOT_SERVICE_PROVIDER` | Parent resolution (§3) |
| `SERVICE_NOT_FOUND` | No QIT service on the parent |
| `INVALID_COLUMN_OPTIONS` | `includeConcreteTemplate` with a ThingShape parent |
| `QUERY_SERVICE_FAILED` | Platform service threw |
| `QUERY_ENTITIES_ERROR` | Any other unexpected failure |

## 8. Platform notes

- Prefer filters and sorts on properties defined on the parent template or shape; Optimized services only accept those fields (see [`query_capability.md` §8](./query_capability.md)).
- Visibility and permissions of the calling user filter both rows and totals.
- Platform reference: [Query Parameter for Query Services](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Composer/Things/ThingServices/QueryParameterforQueryServices.html).
