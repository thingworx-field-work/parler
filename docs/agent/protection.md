# Protection - sensitive platform values

This document defines how the Parler agent protects sensitive ThingWorx values. The protected class is **`BaseTypes.PASSWORD`**. The file is named around the broader protection boundary because the same pattern (central predicate, fixed mask, stable error codes) is where any other sensitive base type or platform policy flag would plug in.

## 1. Goal

Parler must enforce this at runtime:

1. Users cannot view any cleartext value whose authoritative ThingWorx metadata says **`BaseTypes.PASSWORD`**.
2. Users cannot change any property whose authoritative ThingWorx metadata says **`BaseTypes.PASSWORD`**.
3. Users cannot supply PASSWORD values through generic agent tool parameters.
4. Tool results, approval cards, AgentMessageStream rows, logs, table/cache/chart follow-ups, and LLM-visible tool messages must not reintroduce cleartext after a primary tool blocks or masks it.

Prompt text is useful for steering, but it is not a security boundary. Protection must live in Java execution paths that use platform metadata.

## 2. Threat Model

The agent can expose sensitive values through several paths:

- direct property reads: **`get_property_values`**
- direct property writes: **`set_property_value`**
- generic service calls: **`invoke_service`** on platform services such as property read/write services, custom app services, or metadata services
- custom tools: **`_tool_*`** services harvested from the AgentThing
- table follow-ups: **`fetch_cached_result`**, **`tabulate_cached_result`**, **`summarize_cached_result`**, and chart/table sidecars over cached InfoTables
- observability surfaces: approval summaries, **`AgentMessageStream.toolCalls`**, tool results, HITL audit, protection audit, and tool argument previews in logs

Users may also type a secret into a prompt. The agent cannot erase a value the user already submitted in a user message, but it must not echo, persist again as a tool argument preview, write to a PASSWORD property, or return cleartext from the platform.

## 3. Policy Decisions

### 3.1 Metadata is Authoritative

LLM-supplied fields such as **`base_type`** / **`baseType`** are hints only. The server must resolve the real property or service definition in the JVM:

- property type: **`Thing.getInstancePropertyDefinitions()`** / effective property definition metadata
- service input/result type: **`IServiceProvider.getInstanceServiceDefinition(serviceName)`**
- table column type: **`InfoTable.getDataShape().getFields()`**

If metadata lookup fails, choose **operationally** conservative behavior — refuse the specific action that requires verified metadata — **not** “mask or block everything we could not classify”:

- **writes:** do not write
- **direct reads:** do not read; return a metadata-resolution error such as **`PROPERTY_METADATA_UNRESOLVED`** when the property definition cannot be verified, and reserve **`PROTECTED_VALUE_READ_BLOCKED`** for resolved PASSWORD-backed reads
- **`invoke_service` PASSWORD-parameter inputs:** **`PROTECTED_VALUE_INPUT_BLOCKED`** applies **only** when **`ServiceDefinition`** resolves **and** the LLM supplied a key for a parameter declared **`BaseTypes.PASSWORD`**. If **`ServiceDefinition`** cannot be loaded (**`SERVICE_NOT_FOUND`**, **`SERVICE_LOOKUP_FAILED`**, entity not found, …), that is a **normal invocation / lookup failure**, **not** a protection code — do **not** document or implement it as **`PROTECTED_VALUE_INPUT_BLOCKED`**.

For InfoTable output without usable DataShape metadata, protected columns are not inferred by name. It still masks any cell whose runtime primitive/base type is PASSWORD. See **§4.6**.

### 3.2 Fixed Mask

All LLM-visible protected values use one fixed, length-independent mask:

```text
***
```

This mask must not vary with the original secret length. UI surfaces may choose their own fixed visual mask for rendering, but server-side LLM/tool JSON uses **`***`**.

### 3.3 Error Codes

Protection uses three semantic codes:

| Code | Meaning |
|------|---------|
| **`PROTECTED_VALUE_READ_BLOCKED`** | A direct or generic read path attempted to expose a protected value. |
| **`PROTECTED_VALUE_WRITE_BLOCKED`** | A direct or generic write path attempted to mutate a protected property. |
| **`PROTECTED_VALUE_INPUT_BLOCKED`** | The LLM/user attempted to supply a protected service/tool input value. |

Masked successful outputs do not need a separate "redacted" error code. The call can succeed while protected cells or scalar outputs are represented as **`***`**.

Metadata lookup failures are not PASSWORD evidence. Tools that cannot verify a requested property definition should fail that row with a non-protection code such as **`PROPERTY_METADATA_UNRESOLVED`** and should not read the value.

### 3.4 Read Behavior

For a direct property read of a PASSWORD property:

- Do not call **`getPropertyValue`**.
- Return a per-property structured denial, for example:

```json
{
  "name": "apiKey",
  "ok": false,
  "code": "PROTECTED_VALUE_READ_BLOCKED",
  "baseType": "PASSWORD",
  "message": "This value is protected and cannot be read by the agent."
}
```

For service outputs and InfoTable cells that are already in hand:

- Mask scalar PASSWORD results as **`***`**.
- Mask InfoTable columns whose DataShape field base type is **`PASSWORD`**.
- Do not compute summaries, grouping keys, sort keys, chart labels, or categorical top values that reveal PASSWORD values.

### 3.5 Write Behavior

For a direct property write of a PASSWORD property:

- Reject before HITL enqueue.
- Do not snapshot the existing value.
- Do not show the proposed value in an approval card.
- Re-check inside the approved-write executor as defense in depth.

Recommended direct-write error:

```json
{
  "status": "error",
  "code": "PROTECTED_VALUE_WRITE_BLOCKED",
  "message": "This property is protected and cannot be changed by the agent."
}
```

Agent protection is intentionally stricter than ThingWorx permission. A user who can edit a PASSWORD property directly in Composer still cannot change it through Parler's LLM-mediated path.

### 3.6 Generic Service Input Behavior

For **`invoke_service`**, do **not** block every service whose definition declares a PASSWORD parameter. Some platform/app services define optional PASSWORD parameters but normally consume credentials from platform configuration.

The rule is:

- if a service parameter is declared **`BaseTypes.PASSWORD`** and the LLM-supplied **`parameters`** object contains that key, reject with **`PROTECTED_VALUE_INPUT_BLOCKED`**
- if the PASSWORD parameter is omitted, allow the call to proceed; the platform may use its own stored configuration
- if **`ServiceDefinition`** does **not** resolve, the PASSWORD input guard does **not** run; **`invoke_service`** fails with ordinary errors (**`SERVICE_NOT_FOUND`**, **`INVALID_PARAMETERS`**, …). Unresolved service metadata is **not** a PASSWORD protection event.

This keeps the core invariant: **the agent never originates or transports secret values from chat/tool arguments** when the platform has declared a PASSWORD slot **and** the model supplied that slot.

If an App Developer declares a credential-like service parameter as **STRING** / **TEXT** instead of **PASSWORD**, runtime protection cannot reliably guard the input flow. In that case the pessimistic observability redactor can still avoid repeating the value in logs or MessageStream, but the platform service may receive it. Credential inputs should be typed as **PASSWORD** in service definitions.

### 3.7 Custom Tools

For **`_tool_*`** services:

- Omit custom tools with PASSWORD input parameters from the LLM tool list by default.
- Log an INFO/WARN event identifying the omitted tool and parameter name, without any value.
- If a custom tool returns **PASSWORD**, mask the result.
- If a custom tool returns a secret as **STRING** / **TEXT**, the platform cannot reliably know it is sensitive. App Developers must not design custom tools that return secrets under non-sensitive base types.

The App Developer remediation is to move credentials into platform configuration and expose a narrow `_tool_*` surface that accepts non-secret business parameters only.

### 3.8 Metadata Visibility

Metadata stays visible: property names, service names, parameter names, and base types may be listed. The protected content is the cleartext value and the ability to mutate it through the agent.

Hiding the existence of sensitive properties would be a separate visibility / authorization policy; it is not part of value protection.

## 4. Implementation

### 4.1 Central Guard

**`ProtectedValuePolicy`** is the shared helper:

- **`MASK = "***"`**
- **`isProtectedBaseType(BaseTypes bt)`**: true for **`PASSWORD`**
- **`mask()`**: returns **`MASK`**
- **`propertyBaseType(Thing thing, String propertyName)`**: resolves effective property metadata
- **`isProtectedProperty(Thing thing, String propertyName)`**
- **`SENSITIVE_KEY_NAMES`**: a centrally declared list for pessimistic redaction keys, not inline ad hoc regexes; keep it easy to extend for deployment-specific names
- JSON helpers for protected read/write/input errors
- redaction helpers for JSON tool arguments, approval previews, and log previews

**`ProtectedValuePolicy` must not depend on `LogUtilities` static initialization.** It must be loadable in plain JUnit without a ThingWorx fixture.

### 4.2 `get_property_values`

Before reading each property:

1. Resolve the real base type.
2. If metadata cannot be resolved, return **`PROPERTY_METADATA_UNRESOLVED`** for that row and do not read the value.
3. If protected, return **`PROTECTED_VALUE_READ_BLOCKED`** for that row.
4. Do not call **`readPropertyPrimitive`** unless the metadata resolved and is not PASSWORD.

If a value is already an **`IPrimitiveType`** with base type **PASSWORD** because of a fallback path, **`primitiveToJsonNode`** must mask it.

### 4.3 `set_property_value`

Two layers:

1. **Pre-HITL preflight** in **`AgentThing.executeToolCall`** before **`tryEnqueueParlerHitlPending`**:
   - resolve Thing + property
   - if PASSWORD, return **`PROTECTED_VALUE_WRITE_BLOCKED`**
   - no pending approval, no stale snapshot
2. **Executor defense** in **`SetPropertyValueExecutor.doWrite`**:
   - resolve the real base type
   - if PASSWORD, reject even if the LLM supplied another base type

**`snapshotPropertyValueForStaleCheck`** returns **`null`** for protected properties without reading the current value.

The executor defense applies to both new pending records and pending records that survive a redeployment: an older queued `set_property_value` request is still rejected at approval time if the target property resolves to PASSWORD.

This two-layer pattern follows the established pre-HITL invariant in **`docs/agent/key-resolution.md` §13**. Protection rejects rather than rewriting, but the same "pre-HITL check + executor defense" discipline applies.

### 4.4 Approval Summary, Message Stream, and Logs

Approval summaries must not show protected values:

- For **`set_property_value`**, PASSWORD writes are blocked before a summary exists.
- If a fallback path still builds a summary, show tool name, Thing name, property name, and base type, but mask or omit the value.
- For **`invoke_service`**, generate the parameter preview through a redactor that knows service parameter metadata when available.

AgentMessageStream stores assistant tool calls with their **`arguments`**; a sanitizer runs before serialization. The same sanitizer is used for log **argsPreview** in **`ToolRegistry`** and for audit records.

Redaction runs in two layers:

1. **Metadata-driven redaction** when the tool target/service definition/property definition identifies PASSWORD.
2. **Pessimistic key-name redaction** as a safety net for logs and MessageStream, even when metadata is unavailable or says non-PASSWORD.

Initial key-name patterns:

```text
password, passcode, apiKey, api_key, token, secret, credential,
accessKey, access_key, clientSecret, client_secret, privateKey, private_key
```

Examples:

- `set_property_value.value` -> **`***`** when target is protected or base type hint/key name indicates a secret
- `invoke_service.parameters.<passwordParam>` -> **`***`**
- known property-write service payloads targeting PASSWORD -> mask the payload value

### 4.5 Protection Audit

Rejected protection events are logged for security review:

- event type / code
- user or principal when available
- agent Thing
- tool name
- target Thing/entity/property/service/parameter
- decision: blocked, masked, or omitted

Never log the rejected value. Use **`***`** for any value preview. This is separate from HITL approval audit: many protection blocks happen before a pending approval exists.

### 4.6 `invoke_service`

Preserve existing output masking:

- scalar result base type PASSWORD -> **`***`**
- InfoTable PASSWORD columns -> **`***`**

Guards:

1. **Input guard:** when **`ServiceDefinition`** resolves, if **`getParameters()`** declares a PASSWORD parameter and the LLM supplied that key under **`parameters`**, return **`PROTECTED_VALUE_INPUT_BLOCKED`**. If the PASSWORD key is omitted, allow the call. If **`ServiceDefinition`** cannot be loaded, use normal **`invoke_service`** failure codes — **not** **`PROTECTED_VALUE_INPUT_BLOCKED`** (see **§3.1** / **§3.6**).
2. **Property write guard:** detect known platform write shapes:
   - **`UpdatePropertyValues`** on a Thing
   - any other Thing service whose INFOTABLE input parameter named **`values`** carries NamedVTQ rows (detection is by parameter shape, not by service name)
   - row field **`name`** identifies the target property
   - if any row targets a protected property, reject the entire call with **`PROTECTED_VALUE_WRITE_BLOCKED`** and identify the offending property in the message
3. **Known platform property read redaction:** for current-value/history services such as **`GetNamedProperties`**, **`GetPropertyValues`**, **`QueryNamedPropertyHistory`**, and similar services whose rows identify a property name plus value, mask rows whose property identifier points to a protected property, even when the value column is **VARIANT**.

Arbitrary app-service semantics are not inferred from names alone; only known platform shapes are covered.

**Persistence / observability (narrow scope):** Tool-call JSON persisted for **`invoke_service`** MUST mask declared **`PASSWORD`** parameters only when **`ServiceDefinition`** resolves — **PASSWORD-direct**. When metadata does not resolve, do **not** recursively mask arbitrary nested scalars under **`parameters`** as a substitute for PASSWORD typing; that pattern is not PASSWORD-direct and destroys legitimate operational detail. Key-name heuristics (**`SENSITIVE_KEY_NAMES`**) remain **observability hygiene** only, not the PASSWORD security boundary for arbitrary service payloads.

**NamedVTQ rows in persisted tool JSON:** Mask **`value`** only when the target **`Thing`** resolves and the row **`name`** identifies a **`PASSWORD`** / protected property. If **`Thing`** does not resolve or **`name`** is missing or empty, do **not** apply PASSWORD-protection masking to those rows (no verified property identity).

**Large INFOTABLE cache:** When preparing a server-side cached copy for **`fetch_cached_result`**, apply targeted row masking for resolved **`Thing`** + property. If that preparation fails, do **not** store a conservative “mask every value” cache; omit **`cacheId`** on the **`INFOTABLE_LARGE`** tool result instead (**`CONTRACTS/TABLE_CONTRACT.md`**).

The **`invoke_service`** guard must also run at execution time after HITL approval. A pending `invoke_service` record created before the policy lands must not bypass protection when it resumes after deployment.

### 4.7 Cached Tables, Tabulation, and Charts

Existing row serializers mask PASSWORD columns, but derived tools can still leak through derived values if they use raw cells:

- **`summarize_cached_result`**: do not build categorical top values for protected columns.
- **`tabulate_cached_result`**:
  - reject **`sortBy`** protected columns
  - reject **`groupBy`** protected columns; group keys would expose secrets
  - reject protected columns as aggregate columns
  - for **`group_metric`**, reject protected columns referenced as measure **`column`**, **`weightColumn`**, **`orderBy`**, measure-level **`where`**, output **`having`**, or output **`sort`** keys (same intent as filter-stage column references)
- **`build_chart_from_tabular_result`**: reject protected columns for labels, x-axis, y-axis, series names, or tool-generated text.
- **`fetch_cached_result`**: continue masking rows before returning.

If an InfoTable lacks DataShape metadata, protected columns are not inferred from column names. It still masks cells whose runtime primitive/base type is PASSWORD. This avoids inventing semantics for arbitrary legacy tables while preserving protection where the platform exposes type metadata.

The raw InfoTable may remain in server memory for cache operations, but every user-visible projection must enforce the policy.

### 4.8 Metadata Discovery

**`discover_properties`** and **`get_service_definition`** may show that a property or parameter is **PASSWORD**. They must never include value content for PASSWORD-typed fields. Only name and type are allowed.

For **`get_service_definition.invokeExample`**, omit PASSWORD parameters from **`invokeExample.arguments.parameters`**. This aligns with the rule that the agent should not supply those values. If the LLM supplies the parameter anyway, runtime guard rejects it.

## 5. Tests

Offline unit tests:

- protected base-type predicate and mask constant
- property read row returns **`PROTECTED_VALUE_READ_BLOCKED`**
- metadata lookup failure on direct property read returns **`PROPERTY_METADATA_UNRESOLVED`** and does not read the value
- property write preflight rejects before pending approval construction
- approved write executor rejects PASSWORD even when **`base_type`** lies
- stale snapshot skips protected properties
- approval-summary redactor masks protected values while preserving target names
- AgentMessageStream tool-call sanitizer masks sensitive arguments
- ToolRegistry args preview sanitizer masks sensitive arguments
- `invoke_service` scalar PASSWORD result masks
- InfoTable PASSWORD column row serialization masks
- cached tabular group/sort/chart helpers reject protected columns
- `get_service_definition.invokeExample` omits PASSWORD parameters
- custom `_tool_*` harvester omits tools with PASSWORD input parameters

Checks on a running ThingWorx server (no in-container harness exists, so these are manual):

- real Thing PASSWORD property cannot be read through **`get_property_values`**
- real Thing PASSWORD property cannot be written through **`set_property_value`**
- **`invoke_service(UpdatePropertyValues)`** cannot update a PASSWORD property
- known read services returning NamedVTQ rows mask protected property values
- MessageStream rows do not contain the secret in tool call arguments after a blocked attempt

## 7. Contract Impact

There is no change to the AlwaysOn event envelope shape in **`CONTRACTS/API_CONTRACT.md`** or **`CONTRACTS/UI_CLIENT_PROTOCOL.md`**. Protection errors are tool-result JSON payloads inside existing message/event channels.

Existing **`CONTRACTS/TABLE_CONTRACT.md` §4** already requires clients to render PASSWORD columns with a fixed mask. No contract bump is needed unless the table payload shape or column metadata changes.

The UI has no dedicated rendering for **`PROTECTED_VALUE_*`** codes; they appear as ordinary tool errors. Dedicated rendering would be a contract change (**`CONTRACTS/API_CONTRACT.md`** / **`UI_CLIENT_PROTOCOL.md`** / **`CONTRACTS/CONTRACT_VERSION.md`**).

## 8. Design Decisions

1. Metadata visibility is allowed (the existence of protected properties and parameters is not hidden); value and mutation are blocked.
2. **`invoke_service`** blocks supplied PASSWORD parameter values, not every service that merely declares an optional PASSWORD parameter.
3. Custom **`_tool_*`** services with PASSWORD inputs are omitted from the LLM tool list by default.
4. Use three protection error codes: **`PROTECTED_VALUE_READ_BLOCKED`**, **`PROTECTED_VALUE_WRITE_BLOCKED`**, **`PROTECTED_VALUE_INPUT_BLOCKED`**. Metadata-unresolved reads use a non-protection code such as **`PROPERTY_METADATA_UNRESOLVED`**.
5. Known platform NamedVTQ-like read/write shapes are covered; arbitrary App-service shapes are not inferred.
6. Logs and MessageStream use both metadata-driven and pessimistic key-name redaction.
7. Agent PASSWORD mutation is blocked even when the user has direct Composer permission.
8. Only **`BaseTypes.PASSWORD`** is protected; there are no other sensitive-value tags.
