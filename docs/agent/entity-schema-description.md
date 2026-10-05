# Entity schema description

**Status:** implemented (`DescribeEntitySchemaExecutor`).

## Tool Name

The AI-facing tool name is **`describe_entity_schema`**.

## Background

Parler's older `get_entity` tool (now executor-only) does schema-style inspection. It supports `ThingTemplate`,
`ThingShape`, and `DataShape`, and it explicitly rejects concrete `Thing` instances because a full Thing definition can
inflate replay context and blur runtime instance state with schema definition.

That rejection was the right direction, but the `get_entity` surface is too broad for an LLM-facing tool:

- the name sounds like a general entity dump;
- the output bundles properties, services, and events together even when the user needs only one facet;
- DataShape fields are conceptually different from Thing properties but share the same "entity" wrapper;
- service details can be needed one service at a time, while list views should stay compact;
- a broad surface makes it easy to drift back toward `GetMetadataAsJSON`-style giant payloads.

Schema inspection is therefore split into two operations:

- **describe schema entity**: inspect a known `ThingTemplate`, `ThingShape`, or `DataShape`;
- **discover concrete Thing members**: inspect what a specific `Thing` effectively exposes at runtime.

This document covers only the first operation.

Implementation guidance is grounded in the ThingWorx platform entity and member APIs, using visibility-aware lookup.

## Related documentation

- `docs/agent/entity-set-analysis.md` — Rationale for splitting **schema description** vs **Thing member discovery**.
- `docs/agent/AGENT-CONTEXT.md` — Shipped built-in tool matrix (current **`get_entity`** semantics).
- `docs/agent/context-compaction.md` — Tier B promotion for stamped entity metadata (`parler.entity.metadata.v1` → summary).
- `docs/agent/metadata_discovery.md` — Human-oriented discovery notes that reference **`get_entity`** today.

## Purpose

`describe_entity_schema` replaces the schema role of `get_entity` with a facet-bounded tool:

```text
describe_entity_schema
```

The tool answers questions such as:

- "What properties does ThingTemplate X define?"
- "Show the services on ThingShape Y."
- "What fields are in DataShape Z?"
- "Describe service S on template T."

It must not be a generic JSON export surface.

## Security Rule

When ThingWorx exposes both a visibility-aware API and a direct/bypass API for the same operation, this topic must use
the visibility-aware API. This preserves ThingWorx OOTB security behavior.

Concrete rule for this topic:

- Resolve entities through typed, visibility-aware lookup: `EntityUtilities.findEntity(...)` (`PlatformAccess.findAsUser`).
- Use public service definitions by default.
- Add a direct/bypass API only when no visibility-aware equivalent exists, and document that exception in code comments
  and tests.

## Scope

> **Design record (shipped @ 0.1.164).** Bullets below describe the original implementation scope and verification plan — not an open work list.

In scope:

- Add model-facing `describe_entity_schema`.
- Support `ThingTemplate`, `ThingShape`, and `DataShape`.
- Support facet-level reads instead of returning every member type every time.
- Support list facets and single-member detail facets.
- Use compact, paginated output for list facets.
- Return service parameters and result type for single service detail.
- Return DataShape fields under a `fields` facet, not as fake Thing properties.
- Keep output free of implementation code and runtime values.
- Update routing guide and schema docs so LLMs use `describe_entity_schema` for schema entities.
- Keep executor compatibility for `get_entity` during a transition if needed for historic tool-call replay.

Out of scope:

- Concrete `Thing` member discovery; that belongs to `thing-member-discovery`.
- Current property values; use `get_property_values`.
- Property history; use `query_property_history`.
- Generic entity listing/search; use `query_entities`, `list_entities_by_type`, or `spotlight_search`.
- Full ThingWorx XML/JSON export.
- Editing schema definitions.

## Desired Tool Semantics

Inputs:

| Field | Required | Meaning |
|-------|----------|---------|
| `entityType` | yes | `ThingTemplate`, `ThingShape`, or `DataShape`. |
| `entityName` | yes | Schema entity name. |
| `facet` | no | `summary`, `properties`, `services`, `events`, `fields`, `service`. Singular `property` / `event` / `field` and `subscriptions` are **not** supported. Default `summary`. |
| `memberName` | for singular `service` facet | Service name (required when `facet` is `service`). |
| `scope` | no | **`effective`** (default): inherited/effective definitions via platform **effective** / **GetPropertyDefinitions**-style paths. **`local`**: for **ThingTemplate** / **ThingShape**, members from **`getInstanceShape()`** collections only (not merged **`getInstanceServiceDefinitions`** / **`getInstanceEventDefinitions`** on the root entity). **DataShape** **`local`** uses **`getDataShape()`** before **`getEffectiveDataShape()`**. |
| `namePrefix` | no | Optional list filter. |
| `category` | no | Optional category filter for property/service/event **list** facets. |
| `baseType` | no | **v1:** Optional filter on **property** and **field** list facets only (not **services** / **events**). |
| `dataShape` | no | **v1:** Optional **INFOTABLE** data-shape filter on **property** and **field** list facets only. |
| `offset` | no | Zero-based list offset; clamped to **`[0, totalMatched]`** so empty pages never report negative **`returned`**. |
| `maxItems` | no | Bounded page size; default 80, max 200. |

**Note:** `includePrivateServices` is **not** a supported parameter — rejected with **`UNSUPPORTED_TOOL_PARAMETER`** if present.

Facet behavior:

| Facet | Entity types | Output |
|-------|--------------|--------|
| `summary` | all supported | Entity name/type, description, base/inheritance hints, available facet counts. |
| `properties` | `ThingTemplate`, `ThingShape` | Compact property list (no singular `property` facet). |
| `services` / `service` | `ThingTemplate`, `ThingShape` | Compact public service list or one service definition with parameters/result. |
| `events` | `ThingTemplate`, `ThingShape` | Compact event list (no singular `event` facet). |
| `fields` | `DataShape` | Compact field list (no singular `field` facet). |

Configured **subscriptions** are intentionally **out of scope** — they blur schema vs configured runtime behavior.

Invalid combinations should return stable tool errors. Example: `facet="fields"` on `ThingTemplate` should not silently
return properties.

## Implementation Direction

Entity resolution (schema tools only — **do not** use `ServiceTargetEntityTypeResolver` for raw `entityType`; it normalizes GenericThing-derived template names to `Thing` and is unsafe here):

- Normalize `entityType` with a **closed allowlist** `{ThingTemplate, ThingShape, DataShape}` (case-insensitive) **before** any GenericThing-style normalization.
- Map the normalized name to `RelationshipTypes.ThingworxRelationshipTypes` via `ThingworxRootEntityTypes.parseRootEntityType(...)` **only after** the allowlist check (the parse result is then necessarily one of the three schema roots).
- Use `EntityUtilities.findEntity(...)` for typed, visibility-aware lookup.
- Return `ENTITY_NOT_FOUND_OR_NOT_VISIBLE` when lookup returns null (distinct name from legacy `ENTITY_NOT_FOUND` on `get_entity` so tests and operators can tell visibility-aware tools apart).

**Baseline note (`get_entity`):** `GetEntityExecutor` and **`describe_entity_schema`** both use visibility-aware **`findEntity`**. **`get_entity`** de-advertising from the merged LLM tool list shipped @ **0.1.169** per **`legacy-discovery-executor-only.md`**.

`ThingTemplate`:

- Local scope: read `getInstanceShape()` collections.
- Effective scope: use effective facet APIs where available:
  - `getEffectivePropertyDefinitions()`
  - `getEffectiveServiceDefinitions()`
  - `getEffectiveEventDefinitions()`
  - `getEffectiveSubscriptions()`
- Use public service definitions by default.

`ThingShape`:

- Local scope: read `getInstanceShape()` collections.
- Effective scope: prefer `getEffectiveShape()` facet collections.
- Avoid broad `getInstanceMetadata()` unless a specific detail needs derived platform service output shapes.

`DataShape`:

- Local scope: `getDataShape()`.
- Effective scope: `getEffectiveDataShape()` or `DataShapeManager.getDataShapeDefinition(name)`.
- Return fields sorted by ordinal.

Service detail (v1):

- Resolve service detail from **public** service definitions only (`getInstancePublicServiceDefinitions` / effective public collections, with `isPrivate` filtering when the platform exposes it). **No** `includePrivateServices` toggle in v1.
- Return parameter names, base types, required flag, dataShape for `INFOTABLE`, and result base type/dataShape.
- Do not return service implementation script/body/handler internals.

**Subscriptions:** not part of `describe_entity_schema`.

## Output Contract

All success responses should include:

| Field | Meaning |
|-------|---------|
| `status` | `success`. |
| `entityType` / `entityName` | Normalized target. |
| `facet` | Returned facet. |
| `scope` | `local` or `effective`. |
| `totalMatched` | Exact count for list facets. |
| `offset` / `returned` / `hasMore` | Pagination metadata for list facets. **`offset`** is the clamped start index (**`0 … totalMatched`**); **`returned`** is **`end − offset`** and is **never negative**. |
| `items` or facet-specific array | Bounded result rows. |

Compact list rows should include only fields useful for routing:

- name;
- description truncated to a fixed limit;
- base/result type;
- dataShape where relevant;
- category where relevant;
- source/provenance if reliably available.

Singular detail rows may include deeper schema fields but still must not include runtime values, service bodies, or
unbounded nested metadata.

## Relationship to `get_entity`

- `get_entity` is not advertised in the merged LLM tool list; it stays registered executor-only so persisted replay
  and Tier B summary re-fetch still work.
- `get_entity(entityType=Thing)` rejects with recovery guidance toward `discover_thing_members`.

## Compaction and replay-oriented text

Tier B compaction today promotes historic **`get_entity`** tool bodies stamped **`"$format": "parler.entity.metadata.v1"`** to **`parler.entity.metadata.summary.v1`** (`EntityMetadataSummaryCodec`, `LlmToolResultTierBPromoter`; see `docs/agent/context-compaction.md` §16). `llm_replay_format_routing_guide.txt` instructs the model to re-call **`get_entity`** for full definitions after reading a summary.

`describe_entity_schema` uses **facet-bounded JSON only** and **does not** emit `"$format": "parler.entity.metadata.v1"`. Tier B entity-metadata promotion therefore stays **`get_entity`‑only**. **`get_entity`** is not in the merged LLM tool list; **`llm_replay_format_routing_guide.txt`** references **`get_entity`** for Tier B summary re-fetch on replay paths.

## Coupled surfaces (non-exhaustive)

| Area | Representative paths |
|------|------------------------|
| Tool registration + JSON schema | `BuiltInTools.java`, **`DescribeEntitySchemaExecutor`** next to **`GetEntityExecutor`**, built-in dispatch wiring (same patterns as existing tools). |
| Recovery / routing prose | `InvokeServiceExecutor.java`; **`discover_properties`** tool text in **`BuiltInTools`** now mentions **`describe_entity_schema`** for faceted schema reads while **`get_entity`** remains for full-metadata transition. |
| Compaction + tests | `EntityMetadataSummaryCodec.java`, `LlmToolResultTierBPromoter.java`, `EntityMetadataSummaryCodecTest`, `LlmToolResultTierBPromoterTest`, `CompactionTestFixtures`. |
| Replay guide | `parler-agent/src/main/resources/.../llm_replay_format_routing_guide.txt`. |

**`get_entity`** is **executor-only**.

Built-in tool success/error JSON is extension-local; it is not cataloged in **`CONTRACTS/`**.

## Stable errors

- Errors use the established built-in envelope: **`status`**, **`code`**, **`message`**, and optional **`recoveryHint`** (object with **`tool`** / **`argument`** keys) — aligned with **`GetEntityExecutor`** patterns.
- Singular **`service`** facet requires non-blank **`memberName`**; return **`MISSING_MEMBER_NAME`** when absent.
- Invalid **`facet`** for **`entityType`** (e.g. **`fields`** on **`ThingTemplate`**) must return **`INVALID_FACET_FOR_ENTITY_TYPE`** — **never** succeed with an empty list that could be misread as “zero members.”
- **`DESCRIBE_ENTITY_SCHEMA_PLATFORM_UNAVAILABLE`** — local **`ThingTemplate`** / **`ThingShape`** reads require a non-null **`getInstanceShape()`**; local collection reads that throw map here. **Effective** template/shape property/service/event list reads that **throw** (after unwrap and fallbacks) map here too. **Do not** treat this code as proof the entity has zero members. Truly empty definitions still return **`status:success`** with zero counts when no exception occurred.
- Unsupported facets (e.g. singular **`property`**) return **`INVALID_FACET_FOR_ENTITY_TYPE`** with an explicit “not implemented in describe_entity_schema v1” message.

## Verification

Test coverage:

- Schema registration advertises `describe_entity_schema`.
- Visibility-aware lookup is used; `PlatformAccessGuardTest` fails if production code calls a `*Direct` platform API outside `PlatformAccess`.
- `ThingTemplate` local and effective property/service/event facets return bounded rows (JUnit covers pagination, effective-definition unwrap, local null-shape failure, and **ThingTemplate** **`getEffective*Definitions()`** throw propagation).
- `ThingShape` local and effective property/service/event facets return bounded rows.
- `DataShape` fields return ordinal-sorted local/effective rows.
- Singular service detail includes parameters and result type but not implementation code.
- Invalid facet/entity combinations return stable errors.
- `GetMetadataAsJSON` is not called by the new executor path.
- List pagination: **`offset`** clamping and non-negative **`returned`** (**`DescribeEntitySchemaExecutorTest`** covers **`computeListPageBounds`**, **`ensureNonNullLocalInstanceShape`**, effective-definition unwrap helpers). Compact list rows and list filters (**`DescribeEntitySchemaExecutorRowAndFilterTest`**).

## Notes

- Provenance may be partial when platform definitions do not consistently expose source metadata; compact discovery
  is preferred over fragile provenance reconstruction.
- Service facets default to **public** services, unlike the legacy `discover_services`, which reads all instance
  services.
