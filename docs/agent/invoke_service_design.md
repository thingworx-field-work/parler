# invoke_service — Design Notes

This document describes the built-in tool **`invoke_service`**: universal invocation of ThingWorx services from the Agent, plus large-result caching and follow-up tools.

**Product positioning (LLM-facing):** Prefer **`invoke_service`** when a **previous tool result** / the user names a **specific next service**, or after discovery via merged tools (**`discover_thing_members`**, **`describe_entity_schema`**). Legacy **`discover_services`** / **`get_service_definition`** apply only when **`advertiseLegacyServiceDiscoveryTools=true`** or replay. Do **not** use it to replace **`list_entities_by_type`** for **EntityServices.GetEntityList*** (metadata catalog by type). See **`BuiltInTools`** tool description and **`llm_tool_routing_guide.txt`**.

---

## 1. Scope

- **Default:** Invoke via **`processAPIServiceRequest(serviceName, params)`** on the resolved entity. **Do not** reject an entity just because it is a **RemoteThing** — most services on a Remote Thing still execute **on the platform** (Composer services, bindings, etc.).
- **Edge-only services:** Only when the **service definition** is explicitly marked (e.g. **Remote Service** in Composer) should the agent refuse early. Code checks **`ServiceDefinition` aspects** for **`isRemote`** (platform constant **`CommonPropertyNames.PROP_ISREMOTE`** in `thingworx-common`) plus fallbacks like `remoteService` / `isRemoteService`. Extend **`InvokeServiceExecutor.REMOTE_SERVICE_ASPECT_KEYS`** if needed. Otherwise attempt **`processAPIServiceRequest`** and surface platform errors.

---

## 2. Tool Arguments (LLM → Agent)

| Field | Required | Description |
|-------|----------|-------------|
| `entityType` | Yes | e.g. `Thing`, `ThingTemplate`, `ThingShape`, `Resource`, `Subsystem` — maps to `RelationshipTypes.ThingworxRelationshipTypes`. |
| `entityName` | Yes | Entity name. |
| `serviceName` | Yes | Service to invoke. |
| `parameters` | No | JSON object: service input name → value (see §4). **TAGS** values: JSON array of `{"vocabulary","vocabularyTerm"}` objects (parsed by **`TagJsonCodec`**). |

### 2.1 INFOTABLE parameters (generic JSON ↔ InfoTable)

For any INFOTABLE input, the same rules apply:

1. **To the LLM (tool schema):** **`InfotableJsonCodec.parameterSchemaForLlm(FieldDefinition)`** builds a JSON Schema fragment: `type: array`, each item `type: object` with **properties** = DataShape columns (name, JSON type, description). Used when building schemas for **`invoke_service`** arguments and for **extended tools** registered via **`/tools/extended_tools.json`**. For **`invoke_service`**, the static tool def still describes `parameters` as a generic object; the model should pass INFOTABLE args as **arrays of row objects** (or a **single object** for one row) keyed by column name.
2. **From the LLM (execution):** **`InfotableJsonCodec.jsonToInfoTable(JsonNode, DataShapeDefinition, paramName)`** resolves the shape via **`dataShape`** aspect (load named **DataShape** entity) or **`FieldDefinition.getDataShapeDefinition()`**, then coerces each cell per **BaseTypes** (including **nested INFOTABLE** columns as nested arrays / **`InfoTablePrimitive`**).

**Out of scope (hard error):** Unresolvable INFOTABLE / empty **fieldDefinitions**, **VARIANT** columns, **BLOB** / **IMAGE** cells, nested INFOTABLE beyond **3** levels — **`IllegalArgumentException`** with code **`INVALID_PARAMETERS`** (invoke_service) and message ending in **`InfotableJsonCodec.CUSTOM_TOOL_HINT`** (define a normal ThingWorx service and expose it through **`/tools/extended_tools.json`**). Extended tools whose INFOTABLE parameters cannot be schematized at registration time are omitted from discovery with **`ERROR`** logs (same shape rules).

---

## 3. Execution Flow (High Level)

1. Parse `call.getArguments()` (JSON).
2. Resolve **`RootEntity`** by `entityType` + `entityName` (visibility-aware `EntityUtilities.findEntity`, via `PlatformAccess.findAsUser`). Must implement **`IServiceProvider`** for local invocation.
3. **Load the invokable service definition** for `serviceName` — see **§3.1** (preferred: **`IServiceProvider.getInstanceServiceDefinition(name)`**).
4. Build **`ValueCollection`** from supplied `parameters` + **`ServiceDefinition.getParameters()`** (type coercion for supplied values only; omitted values are left absent so ThingWorx can apply service defaults or raise the platform's own error).
5. Call **`processAPIServiceRequest(serviceName, params)`**.
6. Interpret return value per **`ServiceDefinition.getResultType()`** (§5).  
7. If inner result is a large **INFOTABLE**, apply cache policy (§6).

---

### 3.1 Resolving inputs/outputs: **`getInstanceServiceDefinition(name)`** (preferred)

The Agent runs **inside** the ThingWorx JVM. For **directly invokable** services on the resolved entity, use the **instance-effective** definition:

```java
ServiceDefinition sd = ((IServiceProvider) entity).getInstanceServiceDefinition(serviceName);
if (sd == null) { /* service not available on this instance */ }
```

**Why this over metadata JSON:**  
`getInstanceServiceDefinition` returns exactly the **merged** service set the instance can run (e.g. on **Thing**: own + template + shapes). That matches “services you can call with **`processAPIServiceRequest`**”.

**Java shape (no InfoTable round-trip):**

| API | Use in `invoke_service` |
|-----|-------------------------|
| **`sd.getParameters()`** | **`FieldDefinitionCollection`** — each **`FieldDefinition`**: name, **baseType**, description, **aspects** (`isRequired`, …). Build **`ValueCollection`** from LLM `parameters` JSON. |
| **`sd.getResultType()`** | **`FieldDefinition`** — **`getBaseType()`** drives §5 (`NOTHING`, `INFOTABLE`, `STRING`, …). For **`INFOTABLE`**, nested column defs validate the inner **`result`** table. |

**Missing inputs:**
Do **not** treat `isRequired` metadata as an agent-side hard failure. ThingWorx services often mark a parameter as required while also providing a default value; Composer and platform service invocation paths inject those defaults before execution. `invoke_service` should therefore coerce only values the LLM actually supplied and pass missing inputs through to the platform. If a truly required value has no usable default, the platform service execution error is surfaced as the authoritative failure.

**Relation to `GetServiceDefinition` (platform service):**  
On the platform, the **`GetServiceDefinition(name)`** service on a root entity does:

1. `ServiceDefinition serviceDefinition = provider.getInstanceServiceDefinition(name);`
2. If null → throw “does not exist”.
3. Else `result.addRow(serviceDefinition.toValueCollection());`

So the **InfoTable** row (columns like **`parameterDefinitions`**, **`resultType`**, **`name`**, **`description`**) is just **serialization** of the same **`ServiceDefinition`**. In extension code, call **`getInstanceServiceDefinition`** directly and use **`ServiceDefinition`** — simpler and type-safe.

**`ThingTemplate`:** **`ThingTemplate.getInstanceServiceDefinition`** merges **instance metadata** net shape + super; still the right notion of “what this template instance exposes.”

**Fallback:** If needed, **`GetServiceDefinition`** service or **`GetMetadataAsJSON` → serviceDefinitions** — same logical definition, more parsing.

---

## 4. ThingWorx Return Shape (Critical)

- Regardless of the service’s declared **RESULT** type in Composer, the Java API returns an outer **`InfoTable`** (normalized wrapper).
- The logical return value is always in a field named **`result`** (fixed) on **row 0** of that outer table (when present).

---

## 5. Output Interpretation by **`getResultType()`**

The declared return shape is **`ServiceDefinition.getResultType()`** (**`FieldDefinition`**). The **invoke** API still returns an outer **`InfoTable`** wrapper; logical value is in **`result`** (§4). Use **`getResultType().getBaseType()`** for NOTHING / INFOTABLE / scalars.

### 5.1 `NOTHING`

- Treat as **void**. No need to inspect rows/columns.
- Tool return example: `{"status":"success","result":null}` or explicit `"void"`.

### 5.2 Not `NOTHING` and not `INFOTABLE` (scalar / simple types)

- **Outer InfoTable row count = 0** → logical **`null`** (like Java `Integer` method returning null).
- **Row count > 0** → read **row 0**, field **`result`**, coerce to declared **baseType**, serialize for LLM (string / number / boolean / ISO datetime, etc.).  
- Sensitive types (e.g. **PASSWORD**): mask or omit in returned JSON.

### 5.3 `INFOTABLE` (nested table)

- **Row 0**, field **`result`** holds the **inner `InfoTable`**.
- **Assume** inner columns match **`resultType`** nested **fieldDefinitions** (names + base types).  
  - If **mismatch** → **do not silently fix**; return error, e.g.  
    `OUTPUT_SHAPE_MISMATCH` with details (missing/extra/wrong-type columns).
- Serialize inner table for LLM (see §6 for large tables).

---

## 6. Large INFOTABLE Results (>20 rows)

### 6.1 Threshold

- If inner table **row count > 20** (configurable):  
  - Do **not** return full JSON in one tool message.  
  - Return **summary**; include **`cacheId`** (UUID) **when** the server persisted a conversation-scoped cache entry for **`fetch_cached_result`**. If cache preparation failed or was skipped, **`cacheId`** may be absent — see **`CONTRACTS/TABLE_CONTRACT.md`** ( **`INFOTABLE_LARGE`** ).

### 6.2 Conversation-scoped artifact cache

- **Storage:** large INFOTABLE results are written to the file-backed artifact cache (**`TabularArtifactHub`** over **`ArtifactCache`**, payloads in the AgentThing's configured FileRepository). The design is in [`nearterm/cache-correctness-foundation.md`](nearterm/cache-correctness-foundation.md) and [`nearterm/tool-cache-integration.md`](nearterm/tool-cache-integration.md).
- **Key space:** an opaque scope derived from **`conversationId`** + **`cacheId`**; a `cacheId` from another conversation or principal is a cache miss.
- **Lifetime:** clearing the conversation invalidates its scope. The in-memory index is empty after a server restart, so an older `cacheId` returns a cache miss and the source call must be re-run. File retention on disk is an administrator task; the extension has no per-conversation entry quota.

### 6.3 Summary Returned to LLM

Example shape:

- `totalRows`, `columns[]` (name + baseType), `sampleRows` (≤20),  
- optional `cacheId` when paging is available; `hint` (e.g. use **`fetch_cached_result`** when **`cacheId`** is set, or work from the sample only when it is not).

### 6.4 Follow-Up Tool: `fetch_cached_result`

- **Purpose:** Page into a previously cached large result.  
- **Inputs:** `cacheId`, `offset`, `limit` (and implicitly scoped by **same `conversationId`** as current Chat).  
- **Returns:** JSON slice of rows (+ `hasMore` if useful).

### 6.5 Reuse Cache for Computation

- **`summarize_cached_result`**, **`tabulate_cached_result`** and **`analyze_cached_result`** compute column statistics, grouped tables and deterministic series analyses from a cached result by **`cacheId`** without re-invoking ThingWorx. **`build_chart_from_tabular_result`** charts from the same cache.

### 6.6 Result size cap for non-tabular results

Non-tabular **`invoke_service`** success bodies can exceed the per-request context budget (e.g. platform **`GetMetadata`** dumps). Such bodies are cached and returned as a bounded **`LARGE_JSON`** envelope instead of inline.

- **Placement:** **`InvokeServiceExecutor.maybeRejectOversizedInvokeServiceResult`** runs on the **`invoke_service`** return path only (after **`formatToolResult`**, before the body reaches **`AgentLoop`**). Not applied inside shared formatters used by direct/built-in service paths.
- **Cap:** **65,536** UTF-16 code units (**64 kB**). Bodies **`≤ cap`** pass through unchanged.
- **Tabular exemption (JSON parse):** exempt when **`resultKind`** starts with **`INFOTABLE`**, or top-level **`cacheId`** is a non-empty string. Malformed JSON is **not** exempt (cap applies).
- **Cached envelope (normal path):** the body is stored through **`JsonArtifactHub`** and the tool returns `status: success`, **`resultKind: LARGE_JSON`**, **`cacheId`**, **`utf16Chars`**, **`utf8Bytes`**, **`sizeClass`**, **`limitChars`**, **`originalToolName`**, **`entityType`**, **`entityName`**, **`serviceName`**, a **`hint`**, best-effort bounded **`structure`** (or **`contentExcerpt`** when the body is not parseable JSON), and **`recoveryHint.alternatives`** → **`inspect_cached_payload`**, **`extract_nested`**, **`discover_thing_members`**, **`describe_entity_schema`**, **`get_property_values`**. The model drills in with **`inspect_cached_payload`** (`mode=inspect|extract`) and **`extract_nested`** for nested INFOTABLE cells.
- **Rejection envelope (fallback when caching fails):** **`INVOKE_SERVICE_RESULT_TOO_LARGE`** with **`resultChars`**, **`limitChars`**, **`originalToolName`**, **`entityType`**, **`entityName`**, **`serviceName`**, **`thingName`** (alias of **`entityName`** only when **`entityType=Thing`**; otherwise empty), and **`recoveryHint.alternatives`** → **`inspect_cached_payload`**, **`discover_thing_members`**, **`describe_entity_schema`**, **`get_property_values`**.
- **Telemetry:** **`LOG.info`** `invoke_service LARGE_JSON cached` on the cached path; a single **`LOG.warn`** line **`INVOKE_SERVICE_RESULT_TOO_LARGE`** with entity/service and char counts on the fallback path.

---

## 7. LLM Consumption of INFOTABLE as JSON

- Small tables: full JSON array-of-objects is easy for the model.  
- Large tables: use **summary**; use **`cacheId` + pagination** when the tool returns **`cacheId`**; if **`cacheId`** is absent, the model must not assume **`fetch_cached_result`** is available — work from **`sampleRows`** / **`hint`** only.

---

## 8. Related Code (Current State)

- **`invoke_service` / `fetch_cached_result`:** **`src/main/java/com/thingworx/things/agent/tools/InvokeServiceExecutor.java`**; tool defs in **`BuiltInTools.java`**. Session scope: **`AgentToolContext`** (set from **AgentThing** Chat / ChatAsync).  
- Platform invocation patterns: see §8.1.

### 8.1 ThingWorx in-platform invocation patterns

The `invoke_service` tool calls the target entity's platform service entry through `IServiceProvider.processAPIServiceRequest` (`InvokeServiceExecutor`), under the current `SecurityContext`, so platform permission checks and parameter validation apply. The target service may execute on the platform or forward work to a connected edge.

The platform offers these in-process patterns; the table is a reference for maintainers, and `invoke_service` itself dispatches only through `processAPIServiceRequest`.

| Scenario | Platform API |
| --- | --- |
| Another service on the executing Thing (JavaScript overrides apply) | `this.processServiceRequest(serviceName, params)` (current user or System user needs ServiceInvoke) |
| A service on any local entity (Thing, Resource, Subsystem, …) | `EntityUtilities.findEntity(name, type)`, then `((IServiceProvider) entity).processAPIServiceRequest(serviceName, params)` |
| The currently executing entity | `ThreadLocalContext.getMeContext()` |

A RemoteThing-family template can declare **local** services (platform JVM only, often `isLocalOnly = true`) and **remote** services whose platform-side Java entry forwards to the bound edge through `executeRemoteService` or `callService`. `invoke_service` never calls `callService` / `executeRemoteService` itself: it calls the platform-side service entry, and the platform decides whether the call is local or forwarded to a connected edge.

AlwaysOn downlink is a separate path: `ParlerReceiveMessageSupport` invokes `RemoteThing.callService` through reflection (so the extension does not need the communications API on its compile classpath) to deliver `ReceiveMessage` to the bound session endpoint. See `docs/architecture/agent-alwayson.md`.
