# Metadata discovery — design notes

This document records the design for **entity metadata discovery** in Agent built-in tools: **`discover` / `get_definition` tools** without **serializing full metadata packages**, working with **`invoke_service`** and property read/write. **Default workflow is aggressive** (call directly first, use discover on failure)—see **§8**.

**Concrete Thing member discovery:** see **`docs/agent/thing-member-discovery.md`** and built-in **`discover_thing_members`** — visibility-aware Thing lookup, **public** service lists, **effective** event lists, **configured subscription** list (**`subscriptions`**), facets **`properties`** / **`property`** / **`services`** / **`service`** / **`events`** / **`event`** / **`subscriptions`**. **`discover_properties`** and Thing-targeted **`discover_services`** / **`get_service_definition`** delegate to the same engine (tests: **`ThingMemberDiscoveryPhase5WireCompatTest`**, **`MetadataDiscoveryLegacyThingDelegateMappingTest`**).

---

## 1. Background and platform APIs

- **Services (effective instance set)**  
  Aligned with **`invoke_service`**: use **`IServiceProvider.getInstanceServiceDefinitions()`** (or **`getInstanceServiceDefinition(name)`** per service), matching callable platform services and avoiding parsing large JSON metadata blobs.

- **Properties (effective instance set)**  
  Symmetrically use **`getInstancePropertyDefinitions()`** (or platform-equivalent for Thing-like entities) for property definitions on the current instance.

- **Problem**  
  Feeding full **ServiceDefinition / PropertyDefinition** JSON to an LLM costs **many tokens**, and many fields are irrelevant to “what to call next”.

---

## 2. Tool split: three thin tools

| # | Tool | Role | Required |
|---|------|------|----------|
| 1 | **`discover_services`** | List **Service** **name**, **description** on an entity (filterable, paginated) | Yes |
| 2 | **`get_service_definition`** | For **one** service, return a **reduced** definition (to build `invoke_service` `parameters`) | Yes |
| 3 | **`discover_properties`** | List **Property** **name**, **description**, **baseType** on a **Thing instance** only — input **`thingName`** (filterable, paginated) | Yes |

### 2.1 Why split “list + single definition”?

- **`discover_services`** only exposes **name + description**: model picks a service name **without** stuffing full **parameterDefinitions** into context.
- **`get_service_definition`** runs **once** after **`serviceName`** is known: returns **trimmed inputs/outputs**, ties to **`invoke_service`**, **token-bounded**.

Same for properties: **discover** covers typical **get/set property**; there is no separate per-property definition tool.

### 2.2 vs. “one discover does everything”

- **Multiple tools**: clearer intent, one category or one row per call, easier throttling and caching.

---

## 3. Input conventions (aligned with `invoke_service`)

Each tool must locate the entity; align with existing design:

- **`entityType`**: `RelationshipTypes.ThingworxRelationshipTypes` name (e.g. `Thing`, `ThingTemplate`, …)
- **`entityName`**: entity name

Optional:

- **`namePrefix`**: only return items whose names start with this prefix (narrow the list)
- **`maxItems`** / **`offset`** or **`cursor`**: pagination; response includes **`hasMore`**

---

## 4. Return shape (reduced schema)

Principle: **fixed fields, row arrays** for documentation; if you add caching or symmetric tool encoding later, use the same **one-way projection** (Java → JSON) as **InfoTable parameters**; **decode** only when round-tripping.

### 4.1 `discover_services`

```json
{
  "status": "success",
  "entityType": "Thing",
  "entityName": "MyThing",
  "services": [
    { "name": "GetMetadata", "description": "..." }
  ],
  "hasMore": false
}
```

- **Does not include** parameter lists; use **`get_service_definition`** when needed.

### 4.2 `get_service_definition`

Reduced **ServiceDefinition**, suggested fields:

- **`serviceName`**
- **`description`** (optional max length)
- **`parameters`**: `[{ "name", "baseType", "required", "dataShape?" }]`  
  - For **INFOTABLE**, **`dataShape`** is the DataShape name (same as **`InfotableJsonCodec`** / `invoke_service`)
- **`result`**: `{ "baseType", "dataShape?" }`

Omit: full aspects, implementation details, remote flags, etc. (unless later narrowed fields are added).

### 4.3 `discover_properties`

**Thing instances only.** Arguments: **`thingName`** (required; canonical ThingName), optional **`namePrefix`**, **`offset`**, **`maxItems`**. There is no **`entityType`** parameter. When the Thing is missing or the name is not canonical, the tool returns **`IDENTITY_RESOLUTION_REQUIRED`** with **`recoveryHint`** → **`resolve_thing`** (then **`spotlight_search`** if needed). For **ThingTemplate** / **ThingShape** / **DataShape** schema, use **`describe_entity_schema`**; **`get_entity`** remains the full combined metadata path when facets are insufficient.

```json
{
  "status": "success",
  "entityType": "Thing",
  "entityName": "<thingName>",
  "properties": [
    { "name": "Temperature", "description": "...", "baseType": "NUMBER" }
  ],
  "hasMore": false
}
```

---

## 5. Implementation notes (memo)

- **Code**: **`MetadataDiscoveryExecutor.java`**; registration in **`BuiltInTools.registerAll(ToolRegistry, boolean)`** (legacy discovery visibility: **`./legacy-discovery-executor-only.md`**).
- **Resolve entity**: same as **`InvokeServiceExecutor`** (visibility-aware **`EntityUtilities.findEntity`** via **`PlatformAccess.findAsUser`**).
- **Service**: **`getInstanceServiceDefinitions()`** / **`getInstanceServiceDefinition(name)`**.
- **Property**: **`discover_properties`** always targets **`Thing`** via **`thingName`** (GetPropertyDefinitions / getInstancePropertyDefinitions on that instance). Unsupported entity shapes → **`PROPERTIES_NOT_AVAILABLE`**.
- **Paging**: **`offset`**, **`maxItems`** (default 80, cap 200); response **`hasMore`**, **`totalMatched`**.

---

## 6. Relationship to existing docs

- **`./invoke_service_design.md`**: `invoke_service` and INFOTABLE parameter conventions.
- **`InfotableJsonCodec`**: **`dataShape`** for INFOTABLE parameters in **`get_service_definition`** should match for easier parameter construction.

---

## 7. Summary

| Item | Content |
|------|---------|
| **Tools** | **`discover_services`** + **`get_service_definition`** + **`discover_properties`** (**LLM merge** policy: **`./legacy-discovery-executor-only.md`**) |
| **Principle** | Very thin lists, signatures on demand; fixed JSON shapes; control tokens; Java-side projection |

---

## 8. LLM workflow: **default aggressive**, discover as fallback

For validation order across **Entity Type**, **Entity Name**, **Service**, **Property**, the product default is (document in **system prompt**).

### 8.1 Default (aggressive)

- When **`entityType`**, **`entityName`**, **`serviceName`** (or **`propertyName`**) can be **reasonably inferred** from the user: **prefer direct** **`invoke_service`** (or **`get_property_values` / `set_property_value`**); **no** discover pass first.
- When **entity name is unknown** and you need fuzzy Thing/resource lookup: built-in **`spotlight_search`** (platform `SearchFunctions.SpotlightSearchV2`, same source as REST Spotlight); **list Thing instances by template/shape** with **`query_entities`**; **list metadata entities by collection type** (ThingTemplate, Mashup, etc.—not Things) with **`list_entities_by_type`**.
- Platform and **`invoke_service`** already validate entity existence, service existence, and parameters; **failures return structured errors** (e.g. `ENTITY_NOT_FOUND`, `SERVICE_NOT_FOUND`, `INVALID_PARAMETERS`).

### 8.2 After failure (fallback) — **default model-facing** paths

Under default **`AgentThing.advertiseLegacyServiceDiscoveryTools=false`**, legacy **`discover_properties`**, **`discover_services`**, and **`get_service_definition`** are **not** in the merged LLM tool list (they remain **executor-only** for replay / continuation). Prompt and error-hint authors must steer the **model** toward tools that **are** merged:

- **Concrete Thing (properties / public services / events / subscriptions):** use **`discover_thing_members`** (facets) to refine names, then retry **`invoke_service`** / **`get_property_values`** as appropriate.
- **Schema entities (`ThingTemplate` / `ThingShape` / `DataShape`):** use **`describe_entity_schema`** for facet-bounded metadata, then **`invoke_service`** when parameters are known.
- **Non-Thing or app-specific service flows:** prefer **skills** or **`/tools/extended_tools.json`** entrypoints rather than asking the model to run generic legacy service discovery that is off-list by default.

When **`advertiseLegacyServiceDiscoveryTools`** is **`true`**, **`discover_services`** and **`get_service_definition`** return to the merged list; **`discover_properties`** stays executor-only for merge in **all** modes. Persisted replay may still invoke any of the three legacy names regardless of the flag.

### 8.3 When to discover first

- User **explicitly unsure** of names; or **repeated aggressive attempts** still fail to align—**discover first** using **merged** tools (**`discover_thing_members`** for Things, **`describe_entity_schema`** for schema entities), then call.

### 8.4 Implementation support

- Tool errors should be **readable and actionable** (`code` + `message`). Hints should name **merged** discovery tools (**`discover_thing_members`**, **`describe_entity_schema`**, **`spotlight_search`**, **`query_entities`**, …) — **not** legacy **`discover_services`** / **`discover_properties`** unless **`advertiseLegacyServiceDiscoveryTools`** is enabled or the hint explicitly targets **executor / replay** paths.

---

## 9. Legacy discovery — LLM merge vs executor-only

Normative policy for **which** legacy discovery and extended tool names appear in the **merged LLM tool list** (vs executor-only registration), **`advertiseLegacyServiceDiscoveryTools`**, **`extended_tools.json`** **`executorOnly`**, and **`GetAgentRuntimeSnapshot`** observability is **`./legacy-discovery-executor-only.md`**. This document (**`metadata_discovery.md`**) keeps **wire semantics** and the **§8** workflow; do not duplicate registration policy here.

---

## 10. Document index

| Document | Content |
|----------|---------|
| **./invoke_service_design.md** | `invoke_service`, INFOTABLE parameters |
| **./query_capability.md** | Generic **`query`** for Query services (filters / sorts, service list) |
| **./legacy-discovery-executor-only.md** | Legacy three-tool + extended **`executorOnly`**: **LLM merge vs executor-only**, runtime snapshot fields |
| **./metadata_discovery.md** | This doc: discover tool **shapes**, **§8** default aggressive + discover fallback |
