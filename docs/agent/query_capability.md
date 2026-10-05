# ThingWorx Query capability (Query Parameter for Query Services)

This note summarizes, in our own words, the ThingWorx platform **`query`** parameter (as described in the PTC ThingWorx **R9** Help) as it applies to **`query_entities`**, **`invoke_service`** with a `query` parameter, and similar behavior in the Agent extension. The linked Help topics are authoritative for platform behavior.

**Official sources (direct links):**  
[Query Parameter for Query Services](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Composer/Things/ThingServices/QueryParameterforQueryServices.html)  
[Using the QueryImplementingThings Service (best practices)](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Best_Practices_for_Developing_Applications/usingthequeryimplementingthingsservice.html)  
[Using the QueryImplementingThingsOptimized Service](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Best_Practices_for_Developing_Applications/UsingTheQueryImplementingThingsOptimizedService.html)

> If you use Help Center shell links with `#page/...`, some browsers need DOM storage enabled; **direct HTML** links usually open cleanly (equivalent to shell content, e.g. [QIT shell link](https://support.ptc.com/help/thingworx/platform/r9/en/index.html#page/ThingWorx/Help/Best_Practices_for_Developing_Applications/usingthequeryimplementingthingsservice.html#), [Optimized shell link](https://support.ptc.com/help/thingworx/platform/r9/en/index.html#page/ThingWorx/Help/Best_Practices_for_Developing_Applications/UsingTheQueryImplementingThingsOptimizedService.html#)).

---

## 1. Overview

Many ThingWorx **Query-style services** accept an optional **`query`** parameter. It is a **values object** whose structure depends on the query type.

- Queries can combine with **AND / OR**.
- They can include **sorting (Sort)**.

---

## 2. Possible filter types

Documentation lists types including but not limited to:

| Category | Types |
|----------|-------|
| Match | `Matches`, `NotMatches` |
| Tags | `TaggedWith`, `NotTaggedWith` |
| Comparison | `GT`, `LT`, `GE`, `LE`, `NE`, `EQ`, `LIKE`, `NOTLIKE`, `IN`, `NOTIN` |
| Range | `Between`, `NotBetween` |
| Null / missing | `MissingValue`, `NotMissingValue` |
| Geo | `Near`, `NotNear` |

### Comparison operators

| Type | Meaning |
|------|---------|
| `EQ` | Equals |
| `NE` | Not equal |
| `GT` | Greater than |
| `GE` | Greater than or equal |
| `LT` | Less than |
| `LE` | Less than or equal |
| `LIKE` | Pattern match |
| `BETWEEN` / `NOTBETWEEN` | Inside range / outside range |

**LIKE / NOTLIKE:** the platform does not add wildcards; put them in the value yourself (`%` or `*` for any run of characters, `?` for one character).

---

## 3. Typical services that use the `query` parameter

Documentation states that many services whose names start with **Query** take a `query` parameter, for example:

### All Things

- `QueryThingStreamEntries`

### All streams

- `QueryStreamData`
- `QueryStreamEntries`
- `QueryStreamEntriesWithData`

### All Data Tables

- `QueryDataTableEntries`

**Note:** On Data Tables with INFOTABLE-valued columns, `QueryDataTableEntries` behavior may differ between **PostgreSQL** and **MSSQL** (exact match vs contains keywords, etc.—see platform docs).

### InfoTableFunctions Resource

- `Query`

### All Thing Templates

- `QueryImplementingThings`
- `QueryImplementingThingsWithData`
- `QueryImplementingThingsOptimized` / `QueryImplementingThingsOptimizedWithTotalCount` (see **§8**)

### All Thing Shapes

- `QueryImplementingThings`
- `QueryImplementingThingsWithData`
- `QueryImplementingThingsOptimized` / `QueryImplementingThingsOptimizedWithTotalCount` (see **§8**)

### Audit Subsystem

- `QueryAuditHistory` (docs link to Audit subsystem topics)

---

## 4. `query` object structure

`query` has two main configurable areas:

1. **`filters`** — filter conditions (single, compound And/Or, nested).
2. **`sorts`** — sorting (array of objects).

It is recommended to start from **snippet code** in the script configurator, then hand-tune the `query` object as needed.

---

## 5. Filter shapes

Every filter object has a **`type`**. Leaf filters also name the column in **`fieldName`**; the remaining keys depend on the type. When invoking a service, pass the whole object as the `query` parameter (a JSON object, or a JSON string where the service expects one).

| Type(s) | Keys besides `type` | Notes |
|---------|---------------------|-------|
| `Matches`, `NotMatches` | `fieldName`, `expression` | `expression` is a regular expression. |
| `Tagged`, `NotTagged` | `fieldName` (usually `tags`), `tags` | `tags` is an array of `{ "vocabulary", "vocabularyTerm" }` objects, or a `Vocabulary:Term` string. |
| `EQ`, `NE`, `GT`, `GE`, `LT`, `LE`, `LIKE`, `NOTLIKE` | `fieldName`, `value` | See §2 for LIKE wildcards. |
| `IN`, `NOTIN` | `fieldName`, `values` | `values` is an array. |
| `Between`, `NotBetween` | `fieldName`, `from`, `to` | |
| `MissingValue`, `NotMissingValue` | `fieldName` | |
| `Near`, `NotNear` | `fieldName`, `distance`, `units`, `location` | `units` is miles, kilometers or nautical miles (`M` / `K` / `N`); `location` has `latitude`, `longitude`, optional `elevation`, and a datum. |
| `And`, `Or` | `filters` | `filters` is an array of filter objects; And / Or can nest to any depth, and each level needs its own `type` and `filters`. |

### 5.1 Example

```json
{
  "filters": {
    "type": "And",
    "filters": [
      { "type": "GT", "fieldName": "Duration", "value": 12 },
      {
        "type": "Or",
        "filters": [
          { "type": "EQ", "fieldName": "Status", "value": "Idle" },
          { "type": "MissingValue", "fieldName": "Status" }
        ]
      }
    ]
  }
}
```

### 5.4 Empty IN arrays

With **`QueryImplementingThingsOptimized`**, a query that has several `IN` filters can fail when one of their `values` arrays is empty (documented platform limitation; see §8.2).

---

## 6. Sorting (`sorts`)

- **`sorts`** is an array of objects, applied in order.
- Each sort object has **`fieldName`** (required) and optional **`isAscending`** and **`isCaseSensitive`**; both default to **true**.
- `sorts` and `filters` can appear together in the same `query` object.

```json
{
  "sorts": [
    { "fieldName": "Plant" },
    { "fieldName": "OrderDate", "isAscending": false }
  ],
  "filters": { "type": "EQ", "fieldName": "Status", "value": "Running" }
}
```

---

## 7. Relationship to this extension

- **Fuzzy entity discovery**: built-in **`spotlight_search`** (`SearchFunctions.SpotlightSearchV2`) differs from **Query `query` syntax** in this document.
- **Structured listing / review**: **`query_entities`** calls **`QueryImplementingThingsOptimizedWithTotalCount`**, then **`QueryImplementingThingsOptimized`**, then **`QueryImplementingThings`** (first one the parent exposes), passing a `query` built from this doc's **`filters`** (or pass `query` via **`invoke_service`** for parameters the tool does not expose). **Platform limits and sort semantics**: **§8**; **current tool behavior, large result sets, and `totalRows` conventions**: **./query_entities_design.md**.
- Concrete field names (`maxItems`, `nameMask`, `query`, Optimized `offset`, etc.) must match **`getInstanceServiceDefinition`** and **platform version**.

---

## 8. QueryImplementingThings family (official best-practice summary)

Summary of the PTC R9 Help topics **[Using the QueryImplementingThings Service](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Best_Practices_for_Developing_Applications/usingthequeryimplementingthingsservice.html)**, **[Using the QueryImplementingThingsOptimized Service](https://support.ptc.com/help/thingworx/platform/r9/en/ThingWorx/Help/Best_Practices_for_Developing_Applications/UsingTheQueryImplementingThingsOptimizedService.html)**, as they affect how **`query_entities`** picks a service and which fields it may filter or sort on. Read the linked topics for the full platform description.

### 8.1 `QueryImplementingThings` / `QueryImplementingThingsWithData`

- **HA clusters**: Some formerly in-memory state (property values, Thing state, connection state, etc.) is shared across nodes; properties may live in caches such as **Apache Ignite**. The platform optimizes queries; behavior evolves with version—follow official docs.
- **Returned column scope**: Services **primarily return** property values defined on **Thing Shape / Thing Template**. Filtering on “implementation Thing–only” properties may still work per docs, but **fetching implementation Thing data** should use **`GetThingPropertyValues`** (batch properties, fewer cache calls), not expecting QIT to return everything in one call.
- **Recommended flow (official example pattern)**:
  1. Call **`QueryImplementingThings`** on Shape/Template (for performance prefer QIT with “base fields + name only” over WithData when possible).
  2. Collect Thing names.
  3. Prepare a **Data Shape** with needed columns.
  4. Batch-read properties with **`GetThingPropertyValues`** (`thingReference` + `dataShapeName`).
- **Filter and sort**: **Restrict to properties defined on Shape/Template**; avoid filter/sort on properties that exist only on implementation Things; consider promoting common fields to Shape/Template.
- **ThingWorx 9.4.0+**: `QueryImplementingThings` adds boolean **`isSortFirst`** (default **`false`**, same as older behavior). If **`true`**, **sort is applied before limit** (unlike default “limit then sort”)—watch pagination / Top-N semantics.

### 8.2 `QueryImplementingThingsOptimized` / `QueryImplementingThingsOptimizedWithTotalCount`

- **Invocation**: Similar to `QueryImplementingThings`, on **ThingShapes["…"]** / **ThingTemplates["…"]** (doc example: `ThingShapes["shape1"].QueryImplementingThingsOptimized`).
- **`QueryImplementingThingsOptimizedWithTotalCount`**: returns result rows plus **total row count**; that **total** is the count **before** applying `maxItems` / `offset` (e.g. `maxItems=5` may still yield `total=100`). For **`query_entities`** with accurate **`totalRows`**, prefer this service when the platform supports it.
- **Common extra parameters beyond base QIT** (per docs):
  - **`networkName`**: only Things on that network.
  - **`networkParentNode`**: subtree root on the network.
  - **`networkMaxDepth`**: subtree depth (`1` direct children, `2` grandchildren; `0`/default/null = full subtree).
  - **`offset`**: result offset; with **`maxItems`** enables paging (page 2: `offset=5`, `maxItems=5`, etc.). **Later pages may be slower**—docs warn explicitly.
- **Limits (Optimized uses cache, not DB)**:
  - **`propertyNames` etc.** may only reference properties on the **specified Shape/Template**; if a property lives on the implementation Thing but not that Shape, **the service throws**.
  - **No** filter/sort on **password** base type.
  - **Permissions**: Things with no visibility on a filter field may be **dropped entirely**; fields not used in filter but invisible may appear as **`null`**.
  - **No** filter/sort on base properties **`homeMashup`**, **`Avatar`**, **`projectName`**, **`isSystemObject`**.
- **Link to §5.4**: With Optimized and **multiple IN** filters, **empty IN arrays** can fail (documented)—tooling should validate or strip empty INs.

### 8.3 Implications for the `query_entities` tool (implementation notes)

| Goal | Suggestion |
|------|------------|
| Basic listing + volume control | **`QueryImplementingThings`** + `maxItems` + `nameMask` / simple `query`; default **`isSortFirst=false`** unless “sort then truncate” is required. |
| Need extra properties on implementation Things | **Do not** rely on WithData for every column; follow **§8.1**: **QIT for names + `GetThingPropertyValues`**. |
| Accurate `totalRows` + paging | Prefer **`QueryImplementingThingsOptimizedWithTotalCount`** when available; use returned total + `offset`/`maxItems`; else fall back to heuristic **`hasMore`** (as **`query_entities`** does; see **query_entities_design.md**). |
| Filter/sort validity | Default to **Shape/Template fields only**; if users pass arbitrary `query`, prepare **friendly errors** for **Optimized** (map official exception semantics). |
