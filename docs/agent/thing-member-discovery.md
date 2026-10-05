# Thing member discovery

**Status:** implemented (`DiscoverThingMembersExecutor`), including the **`thingName`** scalar preflight on discovery tools.

The AI-facing tool name is **`discover_thing_members`**.

## Background

Before this tool, Parler exposed concrete Thing metadata through several tools:

- `discover_properties`
- `discover_services`
- `get_service_definition`

**Registration policy:** **`discover_properties`** stays **executor-only** in the merged LLM tool list **always**; **`discover_services`** / **`get_service_definition`** default to executor-only and become **model-facing** only when **`AgentThing.advertiseLegacyServiceDiscoveryTools`** is **`true`**. Details: **`docs/agent/legacy-discovery-executor-only.md`**.

These tools were added for good reasons. The model needs a way to find exact property names before calling
`get_property_values` or `query_property_history`, and it needs service definitions before safely building
`invoke_service` parameters.

That split created avoidable routing overhead:

- properties and services are both members of the same concrete Thing;
- events and subscriptions are missing from the same discovery family;
- `get_service_definition` is a singular-detail variant of service discovery, not a different operation;
- `discover_properties` has a Thing-only target while `discover_services` accepts broader entity types, which makes the
  model confuse concrete Thing discovery with schema/entity inspection;
- some code paths used direct lookup where visibility-aware lookup is required.

This operation is separate from schema description:

- **describe schema entity**: inspect a known `ThingTemplate`, `ThingShape`, or `DataShape`;
- **discover concrete Thing members**: inspect what a specific `Thing` effectively exposes at runtime.

This document covers only the concrete Thing operation.

Implementation guidance is grounded in the ThingWorx platform entity and member APIs, using visibility-aware lookup.

## Repository touchpoints

- **Extension code:** `parler-agent/` (tool registration, executor, tests).
- **Platform patterns:** `docs/agent/AGENT-CONTEXT.md` (visibility, services, executor conventions).
- **Normative contracts:** If any documented client or wire shape changes with this tool, update the relevant `CONTRACTS/*.md` and `CONTRACTS/CONTRACT_VERSION.md` in the same change.

## Purpose

Replace the concrete-Thing discovery role of `discover_properties`, `discover_services`, and `get_service_definition`
with one facet-based tool:

```text
discover_thing_members
```

The tool answers questions such as:

- "What properties are available on AC JetDryer 01?"
- "Find services on this Thing whose name starts with Query."
- "Show the definition of service S on Thing T."
- "What events or configured subscriptions does this Thing expose?"

It must not read property values, invoke services, refresh remote state, or export full Thing metadata.

## Security Rule

When ThingWorx exposes both a visibility-aware API and a direct/bypass API for the same operation, this tool uses
the visibility-aware API. This preserves ThingWorx OOTB security behavior.

Concrete rule for this tool:

- Resolve the target Thing through a visibility-aware lookup path.
- Prefer `getInstancePropertyDefinitionIfVisible(name)` for single-property detail.
- Default service discovery to public services.
- Use direct/bypass APIs only when no visibility-aware equivalent exists and the exception is documented in code
  comments and tests.

### Effective principal (visibility-aware tools)

ThingWorx APIs used by this tool do **not** all mean the same thing by “visibility”:

- **`EntityUtilities.findEntity`** and **`getInstancePropertyDefinitionIfVisible`** are evaluated in the **current
  request’s security context** (the same principal as other agent extension services). Under a **broad or elevated**
  principal, these calls typically return **more** rows, not fewer. Treat “visibility-aware” here as “honors platform
  rules for the executing identity,” not a promise of end-user row-level secrecy. The same boundary applies to sibling
  tools such as **`describe_entity_schema`** that share these APIs.
- **`getInstancePublicServiceDefinitions()`** returns service definitions that are **not marked private** on the
  Thing’s **effective** merged model. That filter is primarily **definition-level** (public vs private service metadata),
  not a second per-principal visibility pass over the same list. Whether a caller may **invoke** a listed service is
  still governed by **runtime permissions**; this tool only **lists** metadata and never invokes.
- **Event definitions** (`getInstanceEventDefinitions` / `getInstanceEventDefinition`) follow the same **read-only
  metadata** pattern as **`describe_entity_schema`** event facets: list effective instance events without firing or
  queue inspection.

### Operator validation

Offline Gradle tests prove executor wiring against fabricated lookups; they do **not** prove platform exclusion under a
real principal. To validate a deployment, use a real **`Thing`**, confirm the executing principal, and look for at least
one member the platform **actually excludes** (for example a **non-public** service absent from **`facet=services`**, or
a property dropped by **`getInstancePropertyDefinitionIfVisible`**). Under a broad or elevated principal there may be no
excluded member to find; that is expected (see **Effective principal**).

Two list facets can be noisy: **`facet=events`** on a Thing with several properties is often dominated by **built-in /
auto-generated** events (for example per-property **`DataChange`**), and **`facet=subscriptions`** returns only
configured multi-event rows (see **Configured subscriptions**).

## Scope

In scope:

- Model-facing `discover_thing_members`.
- Only concrete `Thing` targets.
- Facets for properties, services, events, and configured subscriptions.
- List facets and single-member detail facets.
- Bounded, paginated output for list facets.
- Service parameters and result type for singular service detail.
- Property/event metadata without runtime values.
- Configured subscription metadata without invoking or refreshing anything.
- Executor compatibility for the older discovery tool names, for historic tool-call replay.

Out of scope:

- Schema entity description; that belongs to `entity-schema-description`.
- Current property values; use `get_property_values`.
- Property history; use `query_property_history`.
- Service invocation; use `invoke_service`.
- Dynamic remote subscription management.
- Model synchronization, edge refresh, or property value reads from remote Things.
- Generic entity search or ThingTemplate/ThingShape listing.

## Tool Semantics

Inputs:

| Field | Required | Meaning |
|-------|----------|---------|
| `thingName` | yes | Canonical Thing name. If the user gives a label or loose text, route through `resolve_thing` first. |
| `facet` | no | `properties`, `property`, `services`, `service`, `events`, `event`, and **`subscriptions`** (list only; default `properties`). |
| `memberName` | for singular facets only | Property, service, or event member name. **Omit** on **`properties`**, **`services`**, **`events`**, and **`subscriptions`** — the executor returns **`UNSUPPORTED_TOOL_PARAMETER`** with a **`namePrefix`** recovery hint. |
| `namePrefix` | no | Optional list filter. |
| `category` | no | Optional property/service/event category filter where applicable. |
| `baseType` | no | Optional property base type or service result base type filter. |
| `dataShape` | no | Optional filter on **property** and **`events`** list facets (property INFOTABLE aspect; event payload shape via **`EventDefinition.getDataShapeName()`**). **Ignored** for **`services`**, **`subscriptions`**, and singular facets. |
| `offset` | no | Zero-based list offset. |
| `maxItems` | no | Bounded page size; default 80, max 200. |

**Rejected parameters:** `includePrivateServices` and `subscriptionScope` are **not** part of the schema. The executor rejects them with **`UNSUPPORTED_TOOL_PARAMETER`** (and rejects any unknown root-level key). **`memberName`** on any **list** facet is rejected the same way. There are no private-service or dynamic-subscription facets.

Facet behavior:

| Facet | Output |
|-------|--------|
| `properties` | Effective property definitions applicable to the Thing. |
| `property` | One property definition, visibility checked. |
| `services` | Effective public service definitions applicable to the Thing. |
| `service` | One service definition with parameters and result type. |
| `events` | Effective event definitions applicable to the Thing. |
| `event` | One event definition. |
| `subscriptions` | Effective configured subscription metadata applicable to the Thing. |

Invalid target types are not accepted. If the user wants a `ThingTemplate`, `ThingShape`, or `DataShape`, the
tool returns a stable recovery hint pointing to `describe_entity_schema`.

## Implementation

Thing resolution:

- Use the same model-friendly key-resolution discipline as existing Thing tools.
- Final entity lookup must be visibility-aware (`EntityUtilities.findEntity`, via `PlatformAccess.findAsUser`).
- Return **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** (via **`ScalarThingnamePreflight`**) when the Thing gate fails — same model-facing codes as property / alert / invoke / write built-ins; facet-specific codes (**`PROPERTY_NOT_FOUND_OR_NOT_VISIBLE`**, etc.) remain for member-not-found cases after the Thing resolves.

Properties:

- List facet: use `Thing.getInstancePropertyDefinitions()` **filtered** so each listed name has a non-null
  `getInstancePropertyDefinitionIfVisible(name)` (list/singular visibility parity).
- Single facet: call `getInstancePropertyDefinitionIfVisible(memberName)` first; if null, resolve by **case-insensitive**
  match among names that already pass the **`properties`** list visibility filter, then re-query visibility with the
  **canonical** name (parity with **`service`** / **`event`** singular name matching).
- Include name, description, baseType, dataShape, readOnly, persistent, logged, indexed, and category where available.
- Do not include current values.

Services:

- List facet: default to `Thing.getInstancePublicServiceDefinitions()`.
- Single facet: resolve **only** from that same public collection by name and return parameters/result metadata.
- Do not include service implementation bodies or handlers.

Events:

- Use `Thing.getInstanceEventDefinitions()` and `Thing.getInstanceEventDefinition(memberName)`.
- The ThingWorx platform exposes **no** `getInstanceEventDefinitionIfVisible` / public-vs-private event-definition API
  analogous to properties and public services — **effective instance event definitions are the complete read-only
  metadata source**. This is **not** a documented-bypass situation under the Security Rule: there is no narrower
  visibility-aware substitute to prefer.
- Include event dataShape and category where available.
- Do not fire events or inspect event runtime queues.

Configured subscriptions:

- **`discover_thing_members` facet `subscriptions`:** read **`Thing.getInstanceMultiEventSubscriptions()`** and emit
  narrow JSON rows (name, enabled, multi-event, scope label **`configured`**, owner, owner type, handler name,
  **`eventDescriptors`**) **without** **`EntityMetadataUtilities.toInfoTable(MultiEventSubscriptionCollection)`** (legacy
  multi-event conversion with known limitations).
- The tool does **not** mirror **`Thing.GetInstanceSubscriptions()`** when the multi-event collection is empty; callers see an
  empty list unless the platform populates **`getInstanceMultiEventSubscriptions()`**.
- Runtime dynamic subscriptions are not reported.

## Output Contract

All success responses include:

| Field | Meaning |
|-------|---------|
| `status` | `success`. |
| `entityType` | Always `Thing` for this tool (drop-in with `describe_entity_schema` identity fields). |
| `entityName` | Resolved Thing name (canonical). |
| `facet` | Returned facet. |
| `scope` | `effective` (concrete Thing member discovery reads the effective instance collections). |
| `totalMatched` | Exact count for list facets. |
| `offset` / `returned` / `hasMore` | Pagination metadata for list facets. |
| `items` | Bounded list rows for `properties`, `services`, `events`, and **`subscriptions`** facets. |
| `property` / `service` / `event` | Singular detail object for `property` / `service` / `event` facets (plus `invokeExample` on `service`). |

List rows are compact and route-oriented. The model usually needs enough metadata to choose the next tool, not a
full Thing definition.

Errors use **`{status:"error", code, message, recoveryHint?}`** with **`recoveryHint`** shaped like `describe_entity_schema`
(`tool` + `argument` strings). Singular **not-found** hints for `property` / `service` / `event` use **`argument: "namePrefix"`** so the model retries a **list** facet with narrowing instead of a self-referential `facet` hint. **`memberName` on a list facet** also uses **`argument: "namePrefix"`** under **`UNSUPPORTED_TOOL_PARAMETER`**. Documented codes include **`MISSING_THING_NAME`** (legacy copy only), **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** for the Thing target gate (aligned with **`ScalarThingnamePreflight`**), **`MISSING_MEMBER_NAME`**, **`UNSUPPORTED_TOOL_PARAMETER`**, **`INVALID_FACET`** (unknown facet string), **`PROPERTY_NOT_FOUND_OR_NOT_VISIBLE`**, **`SERVICE_NOT_FOUND_OR_NOT_VISIBLE`**, **`EVENT_NOT_FOUND_OR_NOT_VISIBLE`**, plus generic **`DISCOVER_THING_MEMBERS_ERROR`** on unexpected failures.

Singular detail rows may include deeper schema fields but never include:

- current property values;
- service implementation source/body;
- unbounded nested metadata;
- dynamic subscription state.

## Legacy tool compatibility

The older discovery tools stay registered as executors so historic tool-call replay keeps working:

- `discover_properties(thingName, ...)` is executed via the **same** visibility-aware / filtered-property / public-service engine as `discover_thing_members` (delegation in `MetadataDiscoveryExecutor`).
- `discover_services(entityType="Thing", entityName, ...)` and `get_service_definition` on **Thing** use **`EntityUtilities.findEntity`** and the same **public** service metadata as `discover_thing_members` (no visibility-free lookup on any path).
- Non-Thing `discover_services` / `get_service_definition` calls keep the older generic path; for schema entities, `describe_entity_schema` is the preferred tool.

The inner **`discover_thing_members`** payloads are remapped to the legacy **`discover_properties`** / **`discover_services`** / **`get_service_definition`** success and error shapes, including the Thing-gate **`THINGNAME_VALUE_REQUIRED`** / **`IDENTITY_RESOLUTION_REQUIRED`** codes. **`ThingMemberDiscoveryPhase5WireCompatTest`** pins the offline-safe preflight error envelopes via **`ToolRegistry.executeTool`**; **`MetadataDiscoveryLegacyThingDelegateMappingTest`** pins the pure JSON remaps (no **`EntityUtilities.findEntity`**).

Model-facing registration of the legacy names follows **`legacy-discovery-executor-only.md`**: **`discover_properties`** is never advertised; **`discover_services`** / **`get_service_definition`** are advertised only when **`advertiseLegacyServiceDiscoveryTools`** is **`true`**. The routing guide and **`BuiltInTools`** descriptions steer the model to **`discover_thing_members`** for concrete Thing metadata. No extra **`registerExecutorAlias`** entries exist for metadata discovery; add one in the same change if replayed conversations surface another historic `function.name`.

## Verification

Tests cover:

- Schema registration advertises `discover_thing_members`.
- Visibility-aware Thing lookup is used (`PlatformAccessGuardTest` fails if production code calls a `*Direct` platform API outside `PlatformAccess`).
- Property list facet returns effective metadata and no values.
- Property singular facet uses visibility-aware lookup and returns stable not-found/not-visible errors.
- Service list facet defaults to public services.
- Service singular facet includes parameters/result and no implementation code.
- Event list and singular facets return effective event definitions (`getInstanceEventDefinitions` / `getInstanceEventDefinition`).
- Configured subscription facet handles multi-event subscriptions without legacy conversion.
- List facets reject **`memberName`** with **`UNSUPPORTED_TOOL_PARAMETER`** and **`namePrefix`** recovery.
- Pagination caps large Things.
- Old discovery tools map to the new executor with compatibility tests (**`ThingMemberDiscoveryPhase5WireCompatTest`**: offline-safe **`ToolRegistry`** preflight errors; **`MetadataDiscoveryLegacyThingDelegateMappingTest`**: pure inner-JSON → legacy success/error remaps without **`findEntity`**). Resolved-Thing behavior end to end needs a live ThingWorx server (see **Operator validation**).

Manual smoke:

- Resolve a Thing by user-facing text, then discover properties.
- Discover service names by prefix, then inspect one service and invoke it separately if appropriate.
- Discover events and configured subscriptions on a Thing that has inherited definitions.
- Confirm a not-visible Thing behaves like not found instead of leaking metadata.

## Known limitations

- Generic `discover_services` still supports broader entity types; only the Thing path delegates to this tool.
- Defaulting to public services can list fewer services than older code paths did. That is aligned with OOTB security.
- Subscription metadata has legacy and multi-event paths; this tool reports only the configured multi-event
  representation and no dynamic subscription state.
