# Legacy discovery — executor-only surface

**Status:** implemented — normative description of the default merged LLM tool surface vs executor-only legacy discovery tools and **`extended_tools.json`** **`executorOnly`**.

**Audience:** implementors changing **`parler-agent/`** built-in registration, **`/tools/extended_tools.json`** loading, merged LLM tool lists, and **`GetAgentRuntimeSnapshot`**.

## Related documents

| Document | Role |
|----------|------|
| **`./metadata_discovery.md`** | Legacy three-tool **wire shapes**, pagination, and **§8** default aggressive / discover-fallback workflow |
| **`./thing-member-discovery.md`** | **`discover_thing_members`** as **default** greenfield Thing inspection; legacy tools remain for replay / delegation |
| **`./configuration-repository.md`** | **`extended_tools.json`** normative layout, HITL, **`GetAgentRuntimeSnapshot`** operator notes |
| **`./CUSTOMIZED-TOOLS.md`** | Extended tools overview; points here for **`executorOnly`** |

---

## 1. Scope

This document is intentionally narrow: it covers moving legacy metadata / service-discovery surfaces out of the **default model-facing** tool list while preserving **executor** compatibility for replay and operator-controlled compatibility.

---

## 2. Background

The metadata tool surface has already been split into clearer primitives:

- **`describe_entity_schema`** describes schema entities (**`ThingTemplate`**, **`ThingShape`**, **`DataShape`**) through bounded facets.
- **`discover_thing_members`** discovers effective members on concrete **`Thing`** instances.
- **`get_entity`** is **executor-only**.

Three older tool names have historically been **model-facing** alongside the newer paths:

- **`discover_properties`**
- **`discover_services`**
- **`get_service_definition`**

Their **role** is mixed:

- **`discover_properties`** is legacy spelling for concrete Thing property discovery; the normal path is **`discover_thing_members`** (facet **`properties`**).
- **`discover_services`** and **`get_service_definition`** are legacy for concrete Thing service discovery, but they also remain the **generic non-Thing** service-discovery path.

Keeping all three **always advertised** overlaps newer tools with clearer names and bounded semantics. This design **separates executor availability from LLM advertisement** (see §**3**).

---

## 3. Policy framing

- **Executor availability** — an existing tool call name can still be executed through **`ToolRegistry.executeTool`** (or equivalent runtime dispatch): replay, continuation, HITL, internal routing.
- **LLM advertisement** — the tool’s JSON schema is included in the **merged tool list** sent to the model.

The default favors a **small, modern** tool surface. Compatibility and broad non-Thing generic service discovery via legacy names are **opt-in** where this doc says so.

---

## 4. Goals

Gate **which tool names appear in the merged LLM tool definition list** while preserving **executor execution** (replay, HITL, persisted sequences, internal routing). Default product posture: **prefer `discover_thing_members`** for greenfield Thing inspection; legacy names stay **callable** wherever the platform already executes tools.

---

## 5. Built-in tools

### 5.1 `discover_properties`

**Always** executor-only for LLM merge (no opt-in to advertise).

Rationale:

- Thing-only surface; the normal replacement is **`discover_thing_members(facet="properties")`**.
- Compatibility wrappers already delegate Thing-path behavior to the same engine.

Expected behavior:

- **Not** included in the merged LLM **`ToolDefinition`** list for the turn.
- Still accepted by **`executeTool("discover_properties", ...)`**.
- **`GetAgentRuntimeSnapshot`** lists it under **built-in executor-only** (or an equivalent unambiguous diagnostic bucket — see §**8**).

### 5.2 `discover_services` / `get_service_definition`

**Default:** executor-only for LLM merge.

**`AgentThing.advertiseLegacyServiceDiscoveryTools`** (BOOLEAN, **`AgentSettings`**, default **`false`**): when **`true`**, both tools **additionally** appear in the **merged** built-in **`ToolDefinition`** list. When **`false`**, they remain **executor-only** for the merge. **Executor availability is unchanged** in either mode.

Concrete Thing service discovery should route through **`discover_thing_members`**; schema entity service facets through **`describe_entity_schema`**. Generic non-Thing service discovery is **not** a default LLM-facing path unless this flag (or app-specific surfaces — §**6**) says otherwise.

---

## 6. App-developer guidance

For non-Thing service workflows, prefer **app-specific** entrypoints:

- **Skills** for procedural guidance and policy-heavy service workflows.
- **Extended tools** for concrete Thing services that should be exposed as explicit AI tools.

Generic service discovery is powerful but **app-semantics-poor**. App developers should encode business-safe entrypoints instead of relying on the model to explore arbitrary service surfaces.

---

## 7. Extended tools: `executorOnly`

Optional field in **`/tools/extended_tools.json`**:

```json
{
  "version": 1,
  "tools": [
    {
      "name": "internal_region_health_snapshot",
      "title": "Internal region health snapshot",
      "whenToUse": "Reserved for replay or orchestrated flows; not advertised to the model.",
      "target": {
        "entityName": "me",
        "serviceName": "RegionHealthSnapshot"
      },
      "hitl": false,
      "executorOnly": true
    }
  ]
}
```

Field semantics:

| Field | Required | Default | Meaning |
|-------|----------|---------|---------|
| **`executorOnly`** | no | **`false`** | When **`true`**, register the extended tool for **execution** but **omit** its **`ToolDefinition`** from the merged LLM tool list. |

**Orthogonality:** **`executorOnly`** does not relax **`hitl`**, **`playbookSafe`**, PASSWORD protection, schema generation, target resolution, or parameter coercion. It only changes whether the model **sees** the tool schema.

**Strict parsing:** invalid non-boolean **`executorOnly`** should **invalidate** the extended tools manifest (same strict style as **`hitl`** / **`playbookSafe`** when those are present and malformed).

**Diagnostics (illustrative):** runtime snapshot should show extended tools with an **`executorOnly`** boolean, for example:

```json
{
  "tools": {
    "extended": [
      {
        "name": "internal_region_health_snapshot",
        "target": {
          "resolvedEntityName": "SCPA_Demo_Agent",
          "serviceName": "RegionHealthSnapshot"
        },
        "hitl": false,
        "executorOnly": true,
        "status": "registered"
      }
    ]
  }
}
```

Merged LLM tool construction includes only extended tools where **`executorOnly`** is **`false`** (or omitted).

**Playbook catalog validation:** **`PlaybookToolDefinitionsMerge`** still merges **all** extended **`ToolDefinition`** rows (including **`executorOnly:true`**) so static playbooks can reference tools the model does not see; **`playbookSafe`** / HITL semantics remain the gates for playbook eligibility, not **`executorOnly`**.

---

## 8. `GetAgentRuntimeSnapshot` observability

The snapshot **`tools`** object **MUST** include (additive; backward compatible):

| Field | Type | Meaning |
|-------|------|---------|
| **`advertiseLegacyServiceDiscoveryTools`** | boolean | Effective **`AgentSettings.advertiseLegacyServiceDiscoveryTools`** after load. |
| **`builtIn`** | array of string | Sorted names of built-ins with a **`ToolDefinition`** merged to the LLM (**model-facing**). |
| **`executorAliases`** | object | Alias → canonical (unchanged). |
| **`executorOnly`** | array of string | Sorted **direct** executor-only built-in names (includes **`discover_properties`** always; **`discover_services`** / **`get_service_definition`** when the flag is **`false`**). |
| **`extended`** | array of object | Each element **`name`**, **`target`**, **`hitl`**, **`status`**, and **`executorOnly`** (boolean; default **`false`** when omitted in manifest). |

Together, **`builtIn`**, **`executorOnly`**, and **`advertiseLegacyServiceDiscoveryTools`** unambiguously describe legacy service-discovery advertisement without inferring from side effects.

---

## 9. Expected tool counts (illustrative)

Counts **drift** with every built-in add/remove — treat this as **structure**, not a permanent number. Pattern:

- **Baseline** = current merged built-in count from **`BuiltInTools`** / footprint tests.
- **Default (`advertiseLegacyServiceDiscoveryTools=false`):** **`discover_properties`**, **`discover_services`**, and **`get_service_definition`** are **omitted** from **`getAllDefinitions()`** (direct **executor-only** registrations); **`executeTool`** on each name remains supported.
- **With `advertiseLegacyServiceDiscoveryTools=true`:** merged count **adds back** **`discover_services`** and **`get_service_definition`** only (**two** names). **`discover_properties`** stays **executor-only** for merge in **both** modes.

---

## 10. Implementation summary

1. **`advertiseLegacyServiceDiscoveryTools`** lives on **`AgentThing`** / **`AgentSettings`** with default **`false`**.
2. Built-in registration:
   - **`discover_properties`** is **always** executor-only for merge;
   - **`discover_services`** / **`get_service_definition`** are executor-only for merge **unless** the flag is **`true`**.
3. JSON schema compatibility tests cover executor-only tool definitions (pattern similar to **`get_entity`**).
4. **`GetAgentRuntimeSnapshot`** exposes **built-in model-facing vs executor-only** and **extended `executorOnly`** (§**8**).
5. **`ExtendedToolDefinition`** / **`ExtendedToolsManifest`** / **`ExtendedToolRegistrySnapshot`** carry **`executorOnly`**.
6. Merged LLM tool construction **omits** executor-only extended tools while execution lookup still finds them; **`PlaybookToolDefinitionsMerge`** does **not** omit them (playbook catalog validation — **§7**).
7. Routing guides and agent docs steer to **`discover_thing_members`** for concrete Thing services, **`describe_entity_schema`** for schema entity service facets, and skills / extended tools for app-specific non-Thing workflows.

---

## 11. Verification

**Unit tests (targets):**

- Default configuration: merged list **omits** **`discover_properties`**, **`discover_services`**, and **`get_service_definition`** (names not in merged definitions).
- Default configuration: **`executeTool`** on all three names still succeeds on compatibility / delegation paths.
- With **`advertiseLegacyServiceDiscoveryTools=true`**: **`discover_services`** and **`get_service_definition`** appear in merged definitions; **`discover_properties`** remains **omitted** from merged definitions.
- **`GetAgentRuntimeSnapshot`** reports expected **built-in** executor-only vs model-facing buckets and **extended** **`executorOnly`** flags.
- **`extended_tools.json`** with **`executorOnly:true`** registers execution metadata but **omits** the tool from the merged **LLM** list; playbook catalog validation still sees the **`ToolDefinition`** (**§7**).
- Invalid non-boolean **`executorOnly`** invalidates the manifest (strict parsing).
- Extended executor-only tools still honor **HITL** and **PASSWORD** protection.

**Runtime checks:**

- Concrete Thing property / service prompts use **`discover_thing_members`** (when exercising greenfield paths).
- Schema service prompts use **`describe_entity_schema`** where applicable.
- Default runtime snapshot shows legacy service discovery **hidden** from the model-facing built-in list except when the flag is on.
- Optional compatibility flag restores **`discover_services`** and **`get_service_definition`** to the **model-facing** list only.

---

## 12. Non-goals

**Non-goals:**

- Do **not** remove executor compatibility for legacy discovery names.
- Do **not** redesign **`invoke_service`** in this topic.
- Do **not** add a **new** generic service-discovery tool here.
- Do **not** solve profile-based tool budgeting; this is a structural default plus an explicit compatibility flag.
