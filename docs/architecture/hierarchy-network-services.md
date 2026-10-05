# Hierarchy Network — five overridable services

**Scope:** this document defines the ThingWorx service surface for the asset hierarchy (Network). It is not part
of the `parler-ui` wire protocol. It is the source of truth for the five service names, their parameters and
their semantic boundaries.

**Purpose:** give the agent an override-friendly, orthogonal set of services for **resolve → expand →
intersect with `query_entities*`**.

**Relation to existing application code:** existing ThingWorx applications often have JavaScript services such
as `GetAssetHierarchy`, `GetHierarchyChildData` or `GetAssetModelForView`. They are useful as analogies only.
Do not copy how they read the Network name (for example a `GetConfiguredAssetNetworkName()` helper or an
arbitrary `GetConfigurationTable({ tableName: … })` call). In Parler, the effective `networkName` and the
description property name come from agent configuration and overrides, as described in
[`entity-hierarchy.md`](entity-hierarchy.md) §8.

---

## 1. `NetworkID` (the same in every service)

**`NetworkID`** is the stable primary key of a node in the Network. In a typical ThingWorx implementation it is
the `name` of the Thing that represents the node (byte-for-byte as the platform stores it).

Do not put `NetworkID` and a UI-only display name into the same column without saying which one it is.

---

## 2. Row shapes: `HierarchyNode_DS` and `EntityList`

- **`HierarchyNode_DS`** has two fields: **`id`** (STRING, primary key, the `NetworkID`) and **`name`**
  (STRING, the display text of the node in the hierarchy).
- **`EntityList`** keeps the standard ThingWorx `name` + `description` meaning and is used for asset lists.

All five services are declared with **`isAllowOverride: true`** so that an application can supply the effective
implementation (see [`entity-hierarchy.md`](entity-hierarchy.md) §7).

---

## 3. The five services

The service names below match the Java constants in `HierarchyNetworkServiceNames`. Applications override the
implementation; they should not rename the services (a rename requires changing the agent constants as well).

The AgentThing declares all five with default implementations that return an empty table. An application
overrides them on its own Thing (derived from the AgentThing template) to supply real data. The agent calls
them on the current AgentThing through `IServiceProvider#processAPIServiceRequest`; the thin wrapper is
`HierarchyNetworkServiceFacade`, which only assembles the `name` / `id` parameter and calls the service name.
Parler does not hard-code a Network name.

Parameters: `ResolveNetworkID(name)`, `GetAssetList(id)`, `GetChildNodes(id)`. `GetFlattenNameDescription` and
`GetRootNode` take no parameters.

### 3.1 `GetFlattenNameDescription`

| Item | Definition |
|------|------------|
| **Role** | Returns a bounded flat list of the effective Network: one row per node, `id` = node id, `name` = display text. It is the default material for name resolution; an application may override it with an external index. |
| **Parameters** | None. |
| **Returns** | `INFOTABLE` (`dataShape:HierarchyNode_DS`). |
| **Row limit** | No fixed limit is imposed on this service. The design assumes the number of nodes is of the order a person can browse in a UI; a hierarchy split so finely that this is not true is not usable anyway. If an implementation needs a hard cap, it defines one in its override and either returns an explicit error or truncates with metadata. It must not drop nodes silently. |

### 3.2 `ResolveNetworkID`

| Item | Definition |
|------|------------|
| **Role** | Takes a display-name candidate and returns **0..N** `HierarchyNode_DS` rows. |
| **Parameters** | **`name`** (STRING, required, `isRequired:true`). |
| **Name vs id** | The service name stresses the output key (`id`). The input `name` is a natural-language or display-name candidate: a fragment, abbreviation or alias. The `name` column of each output row is the canonical display string of the node. |
| **Matching** | A default implementation can filter the `GetFlattenNameDescription` rows by `name`. Overrides may add fuzzy matching, synonyms or external master data. |
| **0 rows** | A valid result. The calling tool decides how to report it (see `CONTRACTS/API_CONTRACT.md` and [`entity-hierarchy.md`](entity-hierarchy.md) §5). |
| **Returns** | `INFOTABLE` (`dataShape:HierarchyNode_DS`). |

### 3.3 `GetRootNode`

| Item | Definition |
|------|------------|
| **Role** | Returns the root of the current context: **0 or 1** `HierarchyNode_DS` row. |
| **Parameters** | None. |
| **Returns** | `INFOTABLE` (`dataShape:HierarchyNode_DS`). |

### 3.4 `GetAssetList`

| Item | Definition |
|------|------------|
| **Role** | Takes a node **`id`** and returns the **union of the business Things related to that node and to every node in its subtree**, as `EntityList` rows (`name` + `description`). It does **not** return child Network nodes (that is `GetChildNodes`). |
| **Parameters** | **`id`** (STRING, required, `isRequired:true`). |
| **Row limit** | For a single tenant and a single plant or site, a root-node call returning the whole subtree with **up to 5000 rows** is acceptable. An implementation should support at least 5000 rows or return a stable error when it would exceed its limit. It must not truncate silently unless it also returns a truncation flag (for example `hasMore`) that its override documentation defines. The agent rejects more than 5000 names with `INTERSECT_LIST_TOO_LARGE`. |
| **Wide-area fleets** | For fleets that span many sites, 5000 rows from the root is not a safe assumption. Such deployments should use non-root ids or their own paging in the override. |
| **Returns** | `INFOTABLE` (`dataShape:EntityList`). |

### 3.5 `GetChildNodes`

| Item | Definition |
|------|------------|
| **Role** | Takes a node **`id`** and returns its **immediate** child Network nodes as `HierarchyNode_DS` rows; grandchildren are not included. |
| **Parameters** | **`id`** (STRING, required, `isRequired:true`). |
| **Paging** | The service has no `afterId` parameter. This differs from the child-node paging described in [`entity-hierarchy.md`](entity-hierarchy.md) §9.1 (`afterId`, `maxChildNodes=200`); this section is authoritative for the service. An override that sorts or pages rows should document whether its order matches the UTF-8 `id` order of §9.1. |
| **Returns** | `INFOTABLE` (`dataShape:HierarchyNode_DS`). |

---

## 4. How the agent calls the services

`parler-agent` calls the services on the AgentThing through
**`IServiceProvider#processAPIServiceRequest(String, ValueCollection)`**. `HierarchyNetworkServiceFacade` builds
the `name` or `id` parameter and calls the service by its constant name. There is no Parler-side Network name.

---

## 5. Design boundaries

1. `GetFlattenNameDescription` and `ResolveNetworkID` can share one default implementation, but they stay two
   services so that each can be overridden on its own.
2. `GetChildNodes` paging and ordering follow §3.5 of this document, not [`entity-hierarchy.md`](entity-hierarchy.md)
   §9.1.
3. Existing application JavaScript is an analogy only. The Network name and configuration come from
   [`entity-hierarchy.md`](entity-hierarchy.md) §8 and §2 of this document.
4. There is no separate `GetNodeByNetworkID` or `DescribeNode` service. Checking that a node exists, or getting
   its display string, is done by filtering the `GetFlattenNameDescription` rows by `id` (or `name`). Do not add
   a sixth service that overlaps with this.

---

## 6. Orchestration: `query_entities*`, `intersectThingNames` and empty results

**Intersect contract** (normative in `CONTRACTS/API_CONTRACT.md`): when `intersectThingNames` is absent, omitted
or an empty array, no intersection is applied from that argument, and the success JSON does not carry the
intersect field group. An empty array therefore cannot mean "zero matches in scope"; like omission, it turns the
model-supplied intersection off (see `EntityHierarchyIntersectHelper`).

**Precedence** (`API_CONTRACT.md` 2.4.16), applied by `HierarchyQueryEntitiesIntersectAugment` before
`query_entities` and `query_entities_by_taxonomy` run:

1. An explicit non-empty `intersectThingNames` from the model wins and is never replaced.
2. Otherwise, a non-blank **`hierarchyNodeId`** (a `NetworkID` taken from the page's Host Context) calls
   **`GetAssetList(id)`** directly; `ResolveNetworkID` is not called.
3. Otherwise, a non-blank **`hierarchyNodeName`** (a name the model took from the user's words) calls
   **`ResolveNetworkID(name)`** and then **`GetAssetList(resolvedId)`**.
4. Otherwise the query is unscoped.

The Mashup `hostContext` is never injected into the intersection on the server. It is only rendered into the
per-turn system prompt; the model reads the scope there and passes `hierarchyNodeId`, `hierarchyNodeName` and/or
`intersectThingNames` explicitly. When a hierarchy path fails, the tool returns an error. It does not fall back to
`hostContext`, it does not fall back from `hierarchyNodeId` to `hierarchyNodeName`, and it does not fall back to
an unscoped listing. The direct-id and name-resolve paths are compared in
[`host-context-turn-state.md`](host-context-turn-state.md) §5.4.

| Situation | Tool result |
|-----------|-------------|
| `GetAssetList(id)` returns no usable Thing `name` rows | Error **`HIERARCHY_SCOPED_EMPTY`**: the subtree has no related business Things. The query is not run unscoped. |
| `GetAssetList` throws or fails | Error **`HIERARCHY_ASSET_LIST_FAILED`**. |
| `ResolveNetworkID` returns 0 rows | Error **`HIERARCHY_RESOLVE_NOT_FOUND`**. |
| `ResolveNetworkID` returns 2 or more rows | Error **`HIERARCHY_RESOLVE_AMBIGUOUS`**. |
| `ResolveNetworkID` throws or fails | Error **`HIERARCHY_RESOLVE_FAILED`**. |
| More than 5000 Thing names in the intersect set (from the model or from `GetAssetList`) | Error **`INTERSECT_LIST_TOO_LARGE`**. |
| No hierarchy argument at all | No intersection; `query_entities*` runs with its normal unscoped behavior. |

**Several ids:** when results of several `GetAssetList` calls are combined, subtrees can nest. A caller should
drop child ids already covered by an ancestor id, or accept the redundant calls; in either case the combined
Thing `name` list must be de-duplicated.

**Truncated `GetAssetList`:** an override that returns a truncated subset should say so through a stable error or
an extra column (for example a `hasMore` BOOLEAN) and document it.

---

## 7. References

- [`host-context.md`](host-context.md) — rendered Mashup scope (advisory).
- [`entity-hierarchy.md`](entity-hierarchy.md) — hierarchy model, Network name configuration, error semantics.
- `CONTRACTS/API_CONTRACT.md` — intersect fields and hierarchy error `code` values.
