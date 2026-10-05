# Taxonomy resolver — agent tool and service JSON

**Contract bundle:** [`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md).  
**Scope:** Normative JSON for application semantic taxonomy **built-in tools** and matching **`AgentThing`** services that return `STRING` JSON, **plus** structured error envelopes when **`THINGNAME`** / scalar Thing instance arguments fail model-facing preflight for:

- **Taxonomy built-ins:** `list_asset_types`, `resolve_asset_type`, `resolve_thing`, `RefreshTaxonomyCache`, `GetTaxonomyDiagnostics` (operator JSON; overlapping fields)
- **Alert built-ins (Phase B thingname-preflight-coverage):** **`query_alert_summary`** (**`thingNames[]`** list-gate — §**7.2**)
- **Alert built-ins (history / acknowledge):** **`query_alert_history`**, **`acknowledge_alerts`** (scalar **`thingName`** — §**7.2**)
- **Built-in `invoke_service` (Phase C):** when the resolved service target is a **Thing**, scalar **`entityName`** gate before HITL / execution (§**7.4**)
- **Built-in `set_property_value` (Phase C):** scalar **`thingName`** / **`thing_name`** gate before HITL enqueue + stale snapshot + approved execution (§**7.5**)
- **Built-in `discover_thing_members` (Phase D — thingname Option B):** scalar **`thingName`** gate before facet work; Thing-path legacy **`discover_properties`** / **`discover_services`** / **`get_service_definition`** align the same **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** surfaces (§**7.6**)
- **Extended / playbook extended tools:** configuration-repository extended tools and playbook-safe extended tools mapped to a ThingWorx service on a target **Thing** (§7)

**Structured taxonomy (runtime):** the agent loads **`/taxonomies/identity-types.json`** and **`/taxonomies/asset-types.json`** independently when each file is present and valid:

| Mode | Root JSON | Companion file | Notes |
|------|-----------|----------------|-------|
| **v3 identity** | Array of identity-rule objects | Optional **`/taxonomies/asset-types.json`** object map | When the identity array is valid and non-empty, **`resolve_thing`** (without **`assetTypeKey`**) is available even if **`asset-types.json`** is missing, empty, or failed to parse (scoped diagnostics). |
| **v3 asset types** | Identity file missing, empty, invalid v3 array, or invalid v2 object (no usable identity rules) | **`/taxonomies/asset-types.json`** non-empty valid object map | **`list_asset_types`** / **`resolve_asset_type`** work when the map loads; **`resolve_thing`** requires valid v3 identity rules. |
| **v2** | Object with **`version: 2`** and **`entities[]`** **when that object is valid** | **Valid v2:** **`asset-types.json`** is **not** parsed as taxonomy; non-empty presence → **`TAXONOMY_LEGACY_FILE_PRESENT`**. **Invalid v2 object:** if **`asset-types.json`** is a valid non-empty v3 map, asset tools use that map (same as **v3 asset types** row); legacy diagnostic does **not** apply on that salvage path. If **`asset-types.json`** is present but **not** a valid non-empty v3 map, asset-side read/parse failures are surfaced without **`TAXONOMY_LEGACY_FILE_PRESENT`** (the file is a candidate v3 map, not legacy-ignored). | **`resolve_thing`** requires v3 identity rules. **Valid v2:** asset tools use flattened rows from the v2 identity file. **Invalid v2** with a loaded v3 map: **`list_asset_types`** / **`resolve_asset_type`** use the map. **Invalid v2** with a broken companion asset file: cache **`unavailable`** with per-side diagnostics; no legacy warning on that path. |

**Per-side semantics:** malformed JSON, an unsupported root for that file, or an invalid v2 object / invalid v3 identity array for **`identity-types.json`** makes **that file’s** identity taxonomy side unavailable; **`asset-types.json`** still loads when it is independently a valid non-empty v3 map (see table). Conversely, **`asset-types.json`** failures do not block identity rules when **`identity-types.json`** is independently valid. When **both** sides are unusable for their respective tools, the semantic taxonomy cache is **unavailable** per loader rules in **`docs/agent/taxonomy.md`**.

**Design reference (non-normative):** [`docs/agent/taxonomy.md`](../docs/agent/taxonomy.md).  
**Optional Markdown:** [`AGENT_TAXONOMY_RENDERING.md`](./AGENT_TAXONOMY_RENDERING.md), **`docs/agent/AGENT-TAXONOMY.md`**.

**Breaking migration (v3 ship):** ThingWorx services **`ListTaxonomyAssetTypes`**, **`ResolveTaxonomyAssetType`**, **`ResolveTaxonomyAssetIdentifier`** and built-ins **`list_taxonomy_asset_types`**, **`resolve_taxonomy_asset_type`**, **`resolve_taxonomy_asset_identifier`** are **removed**. Replacements: **`ListAssetTypes`**, **`ResolveAssetType`**, **`ResolveThing`** (services) and **`list_asset_types`**, **`resolve_asset_type`**, **`resolve_thing`** (built-ins). Mashups, subscriptions, and scripts must be updated.

All JSON is UTF-8.

---

## 1. Common root fields

### 1.1 Success

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`success`**. |
| `resultKind` | string | yes | Tool-specific literal (see §3). |
| `stale` | boolean | yes* | `true` when a prior successful semantic taxonomy cache is still served after a later refresh failure. |

\* Omitted only when no cache has ever been committed (`TAXONOMY_UNAVAILABLE`).

Optional on success when identifier scope was truncated (§4):

| Field | Type | Meaning |
|-------|------|---------|
| `truncated` | boolean | `true` when underlying implementing-things `totalCount > 5000`. |
| `totalUnderlyingCount` | number | Platform total implementor count when truncated. |

When `truncated` is `false` or omitted, `totalUnderlyingCount` SHOULD be omitted.

### 1.2 Error

| Field | Type | Required | Meaning |
|-------|------|----------|---------|
| `status` | string | yes | Literal **`error`**. |
| `code` | string | yes | Machine code (§2). |
| `message` | string | yes | Human-readable detail. |
| `stale` | boolean | no | Present when a cache exists (same semantics as success). |
| `truncated` | boolean | no | Identifier / resolve-thing responses when scope truncated (§4). |
| `totalUnderlyingCount` | number | no | With `truncated: true`. |
| `ambiguousCandidatesTruncated` | boolean | no | On **`IDENTITY_AMBIGUOUS`** from **`resolve_thing`** / v3 identity scan: `true` when **`candidates[]`** omits rows because more than **10** candidates exist. **`totalCount`** remains the full ambiguous count. |

---

## 2. Error codes

| Code | Meaning |
|------|---------|
| `TAXONOMY_UNAVAILABLE` | No valid structured taxonomy cache is loaded, **or** **`resolve_thing`** was called while the cache has **no** v3 identity rules (including asset-types-only v3 load). |
| `TAXONOMY_CONFIG_INVALID` | Taxonomy JSON cannot be parsed or violates required shape. |
| `ASSET_TYPES_NOT_CONFIGURED` | **`list_asset_types`** / **`resolve_asset_type`**: semantic taxonomy is loaded but **`asset-types.json`** produced **zero** asset-type rows (file absent, empty object, or not yet configured). Distinct from whole-cache unavailable. |
| `ASSET_TYPE_NOT_FOUND` | No asset type matched user text, **or** (on **`resolve_thing`**) optional **`assetTypeKey`** is set but no asset-type taxonomy rows are loaded, **or** the key does not match a configured row. |
| `ASSET_TYPE_AMBIGUOUS` | Multiple asset types matched, **or** duplicate **`assetTypeKey`** rows without disambiguation where applicable. |
| `IDENTITY_NOT_FOUND` | **`resolve_thing`**: no unique Thing matched (including truncated scan prefix only — see §4). |
| `IDENTITY_AMBIGUOUS` | **`resolve_thing`**: multiple Things matched. |
| `ASSET_IDENTIFIER_NOT_FOUND` | Reserved / legacy: v2-style identifier scan (not returned by current built-in **`resolve_thing`**). |
| `ASSET_IDENTIFIER_AMBIGUOUS` | Reserved / legacy: same. |
| `TAXONOMY_RESOLVE_FAILED` | Unexpected exception during resolver tool execution (message carries detail). |
| `IDENTITY_RESOLUTION_REQUIRED` | **Built-in property tools** (`get_property_values`, `query_property_history`), **built-in alert tools** (`query_alert_summary`, `query_alert_history`, `acknowledge_alerts`), **built-in `invoke_service`** when the resolved target is a **Thing** (**`entityName`**), **built-in `set_property_value`** (**`thingName`** / **`thing_name`**), **built-in `discover_thing_members`** (**`thingName`**), **built-in `discover_properties`** (Thing-path **`thingName`**), **built-in `discover_services`** / **`get_service_definition`** when the resolved target is a **Thing** (**`entityName`**), **extended / playbook extended tool:** a **`thingName`** / **`entityName`** / declared **`THINGNAME`** input received a **non-blank** value that is **not** a **visible** exact **Thing** for the effective principal. When **`resolve_thing`** can run this turn (§7 — loaded semantic taxonomy with v3 identity rules), the agent MUST call **`resolve_thing`** and retry with the canonical Thing name; otherwise the envelope omits **`recoveryHint`** and the **`message`** directs configuration / exact-name correction. See §7. |
| `THINGNAME_VALUE_REQUIRED` | **Built-in property tools**, **built-in alert tools**, **built-in `invoke_service`** (Thing target **`entityName`**), **built-in `set_property_value`** (**`thingName`** / **`thing_name`**), **built-in `discover_thing_members`** (**`thingName`**), **built-in `discover_properties`** (Thing-path **`thingName`**), **built-in `discover_services`** / **`get_service_definition`** (Thing target **`entityName`**), **extended / playbook extended tool:** required **`thingName`** / **`entityName`** / **`THINGNAME`** input is missing, null, or blank. See §7. |

Diagnostics (not tool `code`): **`TAXONOMY_IDENTIFIER_SCOPE_TRUNCATED`**, **`TAXONOMY_LEGACY_FILE_PRESENT`** (v2: non-empty **`asset-types.json`** ignored alongside v2 identity).

---

## 3. Result kinds

| `resultKind` | Tool(s) |
|--------------|---------|
| `ASSET_TYPES_INLINE` | `list_asset_types` |
| `ASSET_TYPE_RESOLVED` | `resolve_asset_type` success |
| `THING_RESOLVED_INLINE` | `resolve_thing` success, one match (inline `matches[]`) |
| `THING_RESOLVED_LARGE` | `resolve_thing` success, match count &gt; `InvokeServiceExecutor.largeTableRowThreshold()` |

Legacy kinds **`TAXONOMY_*`** from pre-v3 tools are **not** emitted by the current built-ins.

---

### 3.1 `list_asset_types`

- **Success** includes **`assetTypes[]`** (same row shape as before: **`entityKey`**, **`key`**, **`aliases`**, **`entityType`**, **`entityName`**, optional **`queryParent`**, **`criticalProperties`**).
- **`sourcePath`**: repository path associated with the committed snapshot (often **`/taxonomies/identity-types.json`** when identity rules drove the load, or **`/taxonomies/asset-types.json`** when only asset types loaded).

---

### 3.2 `resolve_asset_type`

- **Arguments:** **`text`** (required). There is **no** `entityHint` in v3 public surface.
- **Matching:** user **`text`** against each asset type **`key`** and each type-level **`alias`** using **`exact`** and **`normalized`** (`ModelKeyResolutionNormalize.normalizePhase0`). Per-row **`identity.matchRules`** from v2 rows do **not** apply to asset-type resolution.
- **Success `assetType`:** **`entityKey`**, **`key`**, **`entityType`**, **`entityName`**, optional **`queryParent`**, **`criticalProperties`**, plus **`matchedBy`** `{ field, rule, value }`.

---

### 3.3 `resolve_thing`

- **Requires** a loaded semantic taxonomy snapshot with **at least one** v3 identity rule from **`identity-types.json`**. Otherwise **`TAXONOMY_UNAVAILABLE`**. A non-empty **`asset-types.json`** alone is **not** sufficient for **`resolve_thing`**.
- **Arguments:**
  - **`text`** (required) — user-facing identifier.
  - **`assetTypeKey`** (optional) — exact key from **`asset-types.json`** / **`list_asset_types`**; narrows identity rules to that asset class (template / shape compatibility per **`docs/agent/taxonomy.md`**).
- **Semantics:** After optional **`assetTypeKey`** filter, **first matching rule wins** in **`identity-types.json` array order** (see **`docs/agent/taxonomy.md`** §4.2). For each rule, evaluate the identifier against Things under that rule’s **`baseThingTemplate`**. When **`assetTypeKey`** names a **ThingShape** row, the runtime builds a synthetic **`shape_as_type`** scan with **`queryParent`** on the rule’s template so QIT narrows to the template then filters implementing Things by the shape (**the ThingTemplate entity itself is not required to `implementsShape` the asset-type shape**). The first rule that yields exactly one match → **`THING_RESOLVED_INLINE`**; the first that yields multiple matches below the large-table threshold → **`IDENTITY_AMBIGUOUS`** (**`candidates[]`** capped at **10**, full count in **`totalCount`**, optional **`ambiguousCandidatesTruncated`**); the first that yields a large match set → **`THING_RESOLVED_LARGE`**. If every evaluated rule yields **`IDENTITY_NOT_FOUND`**, return **`IDENTITY_NOT_FOUND`** (optionally with scan truncation fields when applicable).
- **Success object:** includes **`matches[]`** (single row for inline), **`assetTypeKey`** (echoes argument, may be empty string), **`stale`**, optional truncation fields (§4).
- **Property matching:** v3 zippered rules per **`docs/agent/taxonomy.md`** (identity property *i* pairs with match mode *i*). Normalized modes use **`TaxonomyV3Normalize`**.

---

## 4. Identifier truncation and fast path

- When platform `totalCount > 5000` for the scanned template parent, responses MUST include `truncated: true` and `totalUnderlyingCount` when applicable.
- **`name` + `exact` / `equals` (v3 zippered, single-property rule):** direct Thing lookup by platform name before QIT scan when configured; case-sensitive after trim.
- **Large match sets** use the same per-conversation cache as **`query_entities_by_taxonomy`** (`cacheId` + **`fetch_cached_result`**). `sampleMatches[]` rows omit `matchedBy` where applicable.

Inline / ambiguous branches include per-row **`matchedBy`**: `{ "field", "rule", "value" }` when present. Match rows include **`criticalProperties`** object (property name → string value).

---

## 5. `AgentThing` service parity

| Service | Parameters | Built-in |
|---------|------------|----------|
| **`ListAssetTypes`** | (none) | `list_asset_types` |
| **`ResolveAssetType`** | **`text`** (STRING) | `resolve_asset_type` |
| **`ResolveThing`** | **`text`** (STRING), optional **`assetTypeKey`** (STRING) | `resolve_thing` |

JSON outputs match the corresponding built-in tools.

---

## 6. Operator diagnostics JSON

`GetTaxonomyDiagnostics` / `RefreshTaxonomyCache` success objects include: `loaded`, `stale`, `assetTypeCount`, `lastSuccessfulRefresh`, `lastAttemptedRefresh`, `diagnostics[]`, and effective `taxonomyPromptInjection`. **`sourcePath`** when known.

---

## 7. Scalar `THINGNAME` / `thingName` / Thing-target `entityName` preflight (built-in property tools, built-in alert tools, built-in `invoke_service` Thing targets, built-in `set_property_value`, built-in `discover_thing_members` + Thing-path legacy discovery, extended tools, playbook extended tools)

When the agent invokes **`get_property_values`** or **`query_property_history`**, built-in alert tools, or **`invoke_service`** whose resolved **`entityType`** targets a **Thing**, or **`set_property_value`**, or **`discover_thing_members`**, or Thing-path **`discover_properties`** / **`discover_services`** / **`get_service_definition`** that gates a concrete **Thing** instance name, or a **configuration-repository extended tool** or **playbook-safe extended tool** mapped to a ThingWorx service on a target **Thing**, the runtime applies a **model-facing** Thing gate **before** property reads, history queries, alert **`AlertFunctions`** calls, acknowledge side effects, **`invoke_service`** HITL enqueue / **`processServiceRequestDirect`**, **`set_property_value`** HITL enqueue / stale snapshot / approved write, **Thing member discovery** (including delegated legacy paths), or `processServiceRequestDirect` on extended targets.

- **`query_alert_summary`:** required **`thingNames[]`** (1–25) with **list-gate** semantics — see §**7.2** (partial identity success, **`ALERT_SUMMARY_MULTI`** when N≥2).
- **`query_alert_history`** / **`acknowledge_alerts`:** scalar **`thingName`** via **`ScalarThingnamePreflight.gateApplicationThing`** — see §**7.2**.
- Other first-party built-ins listed above share the scalar gate sequence where applicable so **blank-after-trim** and **canonical-name** handoff stay consistent across call sites.

### 7.1 Built-in `get_property_values` / `query_property_history`

1. Argument **`thingName`** (required). Missing, null, or blank after trim → **`status: error`**, **`code: THINGNAME_VALUE_REQUIRED`**, **`parameterName: thingName`**, **`expectedBaseType: THINGNAME`**, **`message`**.
2. If **`thingName`** is non-blank and **`EntityUtilities.findEntity(trimmed, Thing)`** does not yield a **Thing** visible to the effective principal → **`status: error`**, **`code: IDENTITY_RESOLUTION_REQUIRED`**, **`parameterName: thingName`**, **`expectedBaseType: THINGNAME`**, **`suppliedValue`**, **`message`**, and **optionally `recoveryHint`** when **`resolve_thing`** is recoverable this agent turn (loaded semantic taxonomy snapshot with at least one v3 identity rule — same gate as the **`resolve_thing`** built-in). Otherwise **`recoveryHint` MUST be omitted** and **`message` MUST** explain that **`resolve_thing`** is not available (configure **`/taxonomies/identity-types.json`** or supply an exact Thing name).
3. The runtime MUST NOT silently call **`resolve_thing`** or substitute a canonical Thing name.
4. On success, property reads and success payloads MUST use the **canonical** ThingWorx Thing name for the target (preferred: non-blank **`Thing.getName()`** from the **`findEntity`** result; otherwise the trimmed **`thingName`** argument). Leading/trailing whitespace on the JSON argument MUST NOT be forwarded to platform property services or echoed as the final **`thingName`** when a non-blank **`Thing.getName()`** is available. The unified **`query_property_history`** implementation (numeric history branch and value-stream **`QueryPropertyHistory`** branch) MUST use this same canonical string for platform calls, logging, and success **`thingName`** echoes — it MUST NOT re-derive the name from **`Thing.getName()`** alone in a way that bypasses the gate’s blank-name fallback to the trimmed argument.

### 7.2 Built-in `query_alert_summary` / `query_alert_history` / `acknowledge_alerts`

#### `query_alert_summary` (multi-Thing, topic `multi-thing-alert-query`)

1. Argument **`thingNames`** (required array, `minItems: 1`, `maxItems: 25`). Missing, null, not an array, empty array, or any element blank after trim → **`status: error`**, **`code: THINGNAME_VALUE_REQUIRED`**, **`parameterName: thingNames`**, **`expectedBaseType: THINGNAME`**, **`message`**.
2. More than **25** names → **`status: error`**, **`code: THING_NAMES_LIMIT_EXCEEDED`**, **`parameterName: thingNames`**, **`maxAllowed: 25`**, **`suppliedCount`**.
3. For each non-blank name: if not a visible **Thing** → collect **`IDENTITY_RESOLUTION_REQUIRED`** (same envelope as §7.1 step 2, including optional **`recoveryHint`**) into **`identityErrors[]`** and a matching **`byThing[]`** error entry **without failing the whole call** when at least one name resolves.
4. When **zero** names resolve → **`status: error`**, **`code: IDENTITY_RESOLUTION_REQUIRED`**, **`parameterName: thingNames`**, plus **`identityErrors[]`** (no vacuous success).
5. **`thingNames.length == 1`** and identity passes → success **`resultKind`** **`INFOTABLE`** or **`INFOTABLE_LARGE`** (unchanged single-Thing envelope; **`thingName`** extra on root).
6. **`thingNames.length >= 2`** → success **`resultKind: ALERT_SUMMARY_MULTI`** with **`completeness`** (`complete` | `partial`), counters, **`byThing[]`** rollups ( **`topAlerts`** capped at **3**, ordered priority desc → timestamp desc → alertName asc), per-Thing **`cacheId`** when row count exceeds inline sample threshold, and per-Thing service errors as **`byThing[]`** entries with **`status: error`** without failing other Things. When all resolved sub-calls fail → whole-call **`status: error`**.
7. On success, **`AlertFunctions`** inputs MUST use **canonical** Thing names from the list gate.

#### `query_alert_history` / `acknowledge_alerts` (scalar `thingName` unchanged)

1. Argument **`thingName`** (required). Missing, null, or blank after trim → **`status: error`**, **`code: THINGNAME_VALUE_REQUIRED`**, **`parameterName: thingName`**, **`expectedBaseType: THINGNAME`**, **`message`**.
2. If **`thingName`** is non-blank and **`EntityUtilities.findEntity(trimmed, Thing)`** does not yield a **Thing** visible to the effective principal → **`status: error`**, **`code: IDENTITY_RESOLUTION_REQUIRED`**, **`parameterName: thingName`**, **`expectedBaseType: THINGNAME`**, **`suppliedValue`**, **`message`**, and **optionally `recoveryHint`** when **`resolve_thing`** is recoverable this agent turn (same gate as §7.1). Otherwise **`recoveryHint` MUST be omitted** and **`message` MUST** explain that **`resolve_thing`** is not available (configure **`/taxonomies/identity-types.json`** or supply an exact Thing name).
3. **`acknowledge_alerts`:** the runtime MUST NOT invoke **`AlertFunctions`** acknowledge or summary-probe services until §7.2 steps **1–2** pass (no side effects on preflight failure).
4. On success, **`AlertFunctions`** inputs, alert result extras, and acknowledge payloads MUST use the **canonical** Thing name (preferred: non-blank **`Thing.getName()`**; otherwise the trimmed **`thingName`** argument), not raw leading/trailing whitespace from the JSON argument when a non-blank **`Thing.getName()`** is available.

### 7.3 Extended / playbook extended tool `ServiceDefinition` parameters

When the agent invokes a **configuration-repository extended tool** or a **playbook-safe extended tool** mapped to a ThingWorx service on a target **Thing**, the runtime inspects the **platform `ServiceDefinition` input parameters** before invoking the service. The target **Thing** is looked up with Visibility for the current user (**`EntityUtilities.findEntity`**) and the service is invoked through **`processAPIServiceRequest`**, so the user needs **ServiceInvoke** on it; a target the user cannot see fails as target-not-found. Parameter preflight:

1. Only parameters whose declared **`baseType`** is **`THINGNAME`** participate. **`STRING`** parameters are never auto-resolved here.
2. If the parameter is **required** (per service field aspects) and the JSON argument is missing, null, or blank after trim → **`status: error`**, **`code: THINGNAME_VALUE_REQUIRED`**, **`parameterName`**, **`expectedBaseType: THINGNAME`**, **`message`**.
3. If the value is non-blank and **`EntityUtilities.findEntity(trimmed, Thing)`** does not yield a **Thing** visible to the effective principal → **`status: error`**, **`code: IDENTITY_RESOLUTION_REQUIRED`**, **`parameterName`**, **`expectedBaseType: THINGNAME`**, **`suppliedValue`** (trimmed supplied string), **`message`**, and **optionally `recoveryHint`** when **`resolve_thing`** is recoverable this agent turn (same gate as §§**7.1**–**7.2**). When **`recoveryHint`** is present:
   - **`recoveryHint.tool`** — literal **`resolve_thing`**.
   - **`recoveryHint.argument`** — literal **`text`** (pass the user-facing identifier as **`text`** on **`resolve_thing`**).
   - **`recoveryHint.assetTypeKey`** — **MAY** be present when the runtime has a **last successful `resolve_asset_type` match** for this agent turn (exact **`asset-types.json`** key); callers SHOULD pass it to **`resolve_thing`** when present to reduce ambiguity.
4. The runtime MUST NOT silently call **`resolve_thing`** or substitute a canonical Thing name; it returns the error JSON as the tool result.
5. Re-issuing the same non-canonical value MUST yield the same **`IDENTITY_RESOLUTION_REQUIRED`** outcome (no second-chance leniency).

### 7.4 Built-in `invoke_service` (Thing service targets)

When **`entityType`** resolves to **`Thing`** (including normalization from a GenericThing-derived template name such as **`DataTable`** to **`Thing`** per **`ServiceTargetEntityTypeResolver`**), the runtime applies **`ScalarThingnamePreflight.gateApplicationThing("entityName", …)`** on **`entityName`**:

1. Missing, null, or blank after trim → **`THINGNAME_VALUE_REQUIRED`**, **`parameterName: entityName`** (same envelope shape as §§**7.1**–**7.2** with **`expectedBaseType: THINGNAME`**).
2. Non-blank but not a visible **Thing** for the effective principal → **`IDENTITY_RESOLUTION_REQUIRED`** with **`parameterName: entityName`** and the same **`recoveryHint`** rules as §**7.1** step **2**.
3. The gate runs **before** HITL approval enqueue / merged **`ToolCall`** preparation in **`ServiceTargetEntityTypeResolver.prepareInvokeServiceToolCall`** and **again** in **`InvokeServiceExecutor.doInvokeService`** for **Thing** targets **before** platform service binding (defense in depth). On success for **Thing** roots, the runtime MUST use the **`Thing`** instance from **`ScalarThingnamePreflight.ApplicationThingGateOutcome`** (visibility-aware **`findEntity`** inside the gate) and MUST **not** perform a second **`EntityUtilities.findEntityDirect`** on the target **Thing** for that invocation.
4. On success, **`entityName`** in the effective **`ToolCall` arguments** and the platform **`processAPIServiceRequest`** call MUST use the **canonical** Thing name from the gate (preferred: non-blank **`Thing.getName()`** from the **`findEntity`** result; otherwise the trimmed argument), not raw leading/trailing whitespace from JSON when a non-blank **`Thing.getName()`** is available.

### 7.5 Built-in `set_property_value`

1. Argument **`thing_name`** or legacy **`thingName`** (required target Thing). Missing, null, or blank after trim → **`THINGNAME_VALUE_REQUIRED`**, **`parameterName: thingName`**.
2. Non-blank but not a visible **Thing** → **`IDENTITY_RESOLUTION_REQUIRED`** with **`parameterName: thingName`** and the same **`recoveryHint`** rules as §**7.1** step **2**.
3. The gate runs **before** PASSWORD / unknown-metadata preflight (**`ProtectedValuePolicy.setPropertyValuePreflightBlockedJson`**) on the **gated** **`ToolCall`** arguments in **`AgentThing`**, **before** pending-approval enqueue and **`snapshotPropertyValueForStaleCheck`** (rewritten **`ToolCall`**), and **again** in **`SetPropertyValueExecutor.executeApprovedWrite`** / **`doWrite`** at execution time.
4. On success, platform writes and success **`thing_name`** echoes MUST use the **canonical** Thing name from the gate.
5. A property whose definition is read-only → **`status: error`**, **`code: PROPERTY_READ_ONLY`**, **`message`**, both before pending-approval enqueue and again when the approved write runs. The approved write invokes **`UpdatePropertyValues`** through **`processAPIServiceRequest`** under the approving user's security context, so that user needs **ServiceInvoke** on **`UpdatePropertyValues`** and **PropertyWrite** on the property.

### 7.6 Built-in `discover_thing_members` and Thing-path legacy discovery (`discover_properties`, `discover_services`, `get_service_definition`)

1. **`discover_thing_members`:** argument **`thingName`** (required). Missing, null, or blank after trim → **`THINGNAME_VALUE_REQUIRED`**, **`parameterName: thingName`**, **`expectedBaseType: THINGNAME`**, **`message`**, per **`ScalarThingnamePreflight.thingNameValueRequiredJson`**. Non-blank but **`EntityUtilities.findEntity(trimmed, Thing)`** does not yield a **Thing** visible to the effective principal → **`IDENTITY_RESOLUTION_REQUIRED`** with **`parameterName: thingName`** and the same **`recoveryHint`** rules as §**7.1** step **2** (**`ScalarThingnamePreflight.gateApplicationThing("thingName", …)`**). The runtime MUST NOT run facet listing until this gate passes.
2. **Legacy `discover_properties`** on **Thing** targets MUST apply the same **`thingName`** preflight surfaces (**`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`**, **`parameterName: thingName`**) before delegation, and MUST preserve **`IDENTITY_RESOLUTION_REQUIRED`** / **`THINGNAME_VALUE_REQUIRED`** unchanged when remapping inner **`discover_thing_members`** error JSON to the legacy **`discover_properties`** row shape.
3. **Legacy `discover_services`** / **`get_service_definition`** when the resolved **`entityType`** is **Thing** MUST apply **`ScalarThingnamePreflight.gateApplicationThing("entityName", …)`** (same codes and **`parameterName: entityName`** as §**7.4** steps **1–2**) before service metadata work. When an inner **`discover_thing_members`**-shaped payload still carries historical **`THING_NOT_FOUND_OR_NOT_VISIBLE`** (synthetic or transitional), the **model-facing** legacy **outer** JSON MUST map it to **`IDENTITY_RESOLUTION_REQUIRED`** with **`parameterName: entityName`** and **`suppliedValue`** taken from the inner body when present.

---

## Changelog (this file)

| Revision | Notes |
|----------|--------|
| **2.0.17** | **Permission-checked platform calls:** §**7.3** — extended and playbook extended tools look up the target Thing with Visibility (**`findEntity`**) and invoke through **`processAPIServiceRequest`** (current-user **ServiceInvoke**), replacing the permission-free **`processServiceRequestDirect`**; §**7.4** step **4** names the **`processAPIServiceRequest`** call **`invoke_service`** already used; §**7.5** step **5** — **`PROPERTY_READ_ONLY`** before enqueue and at approved write, and the approved **`UpdatePropertyValues`** call needs the approving user's **ServiceInvoke** and **PropertyWrite**. Bundle **`0.1.181`**. |
| **2.0.16** | **`multi-thing-alert-query` M1:** §**7.2** — **`query_alert_summary`** uses **`thingNames[]`**, partial identity success, **`ALERT_SUMMARY_MULTI`** rollup (N≥2) vs flat INFOTABLE (N=1); history/acknowledge remain scalar **`thingName`**. Bundle **0.1.136**. |
| **2.0.15** | **Phase D Option B (`thingname-preflight-coverage`):** §**7.6** — **`discover_thing_members`** uses **`ScalarThingnamePreflight`** (**`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`**); Thing-path legacy **`discover_properties`** / **`discover_services`** / **`get_service_definition`** align the same model-facing codes (including remap of inner **`THING_NOT_FOUND_OR_NOT_VISIBLE`** on Thing service paths). §**2** + §**7** intro scope extended. Bundle **0.1.111**. |
| **2.0.14** | §**7.4** — Thing-target **`invoke_service`** execute path applies **`gateApplicationThing`** before legacy **`MISSING_ENTITY_NAME`** (null / empty **`entityName`** → **`THINGNAME_VALUE_REQUIRED`**); success uses gate **`Thing`** (no second **`findEntityDirect`**). §**7.5** — **`AgentThing`** runs **`ProtectedValuePolicy`** preflight **after** the ThingName gate on **gated** args. Bundle **0.1.110**. |
| **2.0.13** | **Phase C (`thingname-preflight-coverage`):** §**7** scope — **`invoke_service`** Thing-target **`entityName`** + **`set_property_value`** **`thingName`** / **`thing_name`** share **`ScalarThingnamePreflight`** (§**7.4**, §**7.5**); §**2** codes table aligned; supersede prior “orthogonal to **`invoke_service`**” note with normative §**7.4**. Bundle **0.1.109**. |
| **2.0.12** | §**7.1** step **4** — **`query_property_history`** numeric + value-stream branches MUST thread the **`gateApplicationThing`** canonical name (no second gate; no **`thing.getName()`**-only path that drops the trimmed fallback). Bundle **0.1.108**. |
| **2.0.11** | §**7** — shared **`ScalarThingnamePreflight.gateApplicationThing`** for first-party built-ins; **blank-after-trim** → **`THINGNAME_VALUE_REQUIRED`**; **`IDENTITY_RESOLUTION_REQUIRED`** **`suppliedValue`** is trimmed; on success, property + alert tools use **canonical** **`Thing.getName()`** (when non-blank) for platform calls and echoes. **`ExtendedToolThingnamePreflight`** **`suppliedValue`** trim. Bundle **0.1.107**. |
| **2.0.10** | **Phase B alert built-ins:** **`query_alert_summary`**, **`query_alert_history`**, **`acknowledge_alerts`** share **`ScalarThingnamePreflight`** **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** with property built-ins; **`acknowledge_alerts`** performs no **`AlertFunctions`** side effects until preflight passes. **`TaskStateErrorCode.THINGNAME_VALUE_REQUIRED`**. Bundle **0.1.106**. |
| **2.0.9** | **Thing-name preflight:** **`IDENTITY_RESOLUTION_REQUIRED`** omits **`recoveryHint`** when **`resolve_thing`** is not recoverable this turn (taxonomy missing / unavailable / no v3 identity rules); built-in **`thingName`** schema text aligned with runtime; shared test **`Function<String,Thing>`** seam. Bundle **0.1.105**. |
| **2.0.8** | **Thingname preflight coverage Phase A:** §7 — built-in **`get_property_values`** / **`query_property_history`** share **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** with extended tools; model-facing gate uses **`EntityUtilities.findEntity(..., Thing)`** (visibility-aware). **`TaskStateErrorCode.IDENTITY_RESOLUTION_REQUIRED`**. Bundle **0.1.104**. |
| **2.0.7** | invalid v2 identity + present-but-invalid **`asset-types.json`** — asset-side **`invalid`** / parser diagnostics (validator + runtime); **`TAXONOMY_LEGACY_FILE_PRESENT`** only when v2 identity is **valid** and a non-empty asset file is ignored. Bundle **0.1.92**. |
| **2.0.6** | **`ValidateAgentConfigurationRepository`** mirrors runtime for **invalid v2 object** identity + valid v3 **`asset-types.json`** map; runtime loaded snapshot no longer carries **`TAXONOMY_LEGACY_FILE_PRESENT`** on that asset-only salvage path; §intro — per-side unavailable vs companion load (replaces blanket “invalid v2 → unavailable”). Bundle **0.1.91**. |
| **2.0.5** | builder + **`ValidateAgentConfigurationRepository`** — invalid identity with valid **`asset-types.json`** still loads asset tools; malformed **`asset-types.json`** without identity yields **`unavailable`** with asset diagnostics; symmetric **warning** downgrade for companion-side **ERROR** diagnostics when the other taxonomy side is usable. Bundle **0.1.90**. |
| **2.0.4** | **Bug 012:** v3 **`identity-types.json`** array and **`asset-types.json`** map load **independently**; **`ASSET_TYPES_NOT_CONFIGURED`** for asset tools when no asset rows; **`resolve_thing`** / **`THINGNAME`** preflight semantics when only one side is configured. Bundle **0.1.89**. |
| **2.0.3** | §7 **Extended / playbook extended tool `THINGNAME` preflight** — **`IDENTITY_RESOLUTION_REQUIRED`**, **`THINGNAME_VALUE_REQUIRED`**, **`recoveryHint`**. **`ValidateAgentConfigurationRepository`** v3 **`items[]`** paths: identity diagnostics vs **`asset-types.json`** diagnostics. Bundle **0.1.80**. |
| **2.0.2** | **`resolve_thing`:** ThingShape **`assetTypeKey`** pre-filter does **not** require **`ThingTemplate.implementsShape`** on the rule’s template; shape intersection is on QIT rows. **`v3zip:`** synthetic rows keep PASSWORD-aware QIT projection via resolved template parent. Bundle **0.1.79**. |
| **2.0.1** | **`resolve_thing`:** first matching v3 identity rule wins; zippered identity pairs are **OR**; **`IDENTITY_AMBIGUOUS`** **`candidates[]`** cap **10** + **`ambiguousCandidatesTruncated`** + full **`totalCount`**; ThingShape **`assetTypeKey`** synthetic **`shape_as_type`** row. Bundle **0.1.78**. |
| **2.0.0** | **v3 public tools:** `list_asset_types`, `resolve_asset_type`, `resolve_thing`; **`IDENTITY_*`** errors and **`THING_RESOLVED_*`** kinds; v3 loader (array identity + **`asset-types.json`** map). **Removed** built-ins and services: `list_taxonomy_asset_types`, `resolve_taxonomy_asset_type`, `resolve_taxonomy_asset_identifier`, `ListTaxonomyAssetTypes`, `ResolveTaxonomyAssetType`, `ResolveTaxonomyAssetIdentifier`. **`entityHint`** removed from public resolver surface. Bundle **0.1.77**. |
| 1.0.8 | **§3.1:** Clarify **`TaxonomyRow` / Phase −1** synonym projection excludes **`entities[].aliases[]`** (reserved for **`entityHint`** in **§5**). Bundle **0.1.63**. |
| 1.0.7 | **`criticalProperties`** naming alignment. Bundle **0.1.61**. |
| 1.0.6 | **`AgentThing.ResolveTaxonomyAssetType`:** optional **`entityHint`**. Bundle **0.1.60**. |
| 1.0.0 | Initial V1a resolver contract; bundle **0.1.54**. |
