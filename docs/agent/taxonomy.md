# Parler Taxonomy

Status: **implemented (v3)**. Structured taxonomy uses two small files:

- `/taxonomies/identity-types.json` for unique `Thing` resolution.
- `/taxonomies/asset-types.json` for asset type resolution.

Scope: `parler-agent` application semantic layer, resolver tools, scoped asset-set construction, and custom-tool preflight.

The v3 files carry no version field; the loader recognizes them by repository path and root shape: `identity-types.json` is an array, and `asset-types.json` is an object map. The older v2 form (`identity-types.json` as a `version: 2` object with `entities[]` / `types[]`) still loads; when a v2 identity file is active, a sibling `asset-types.json` is ignored with the `TAXONOMY_LEGACY_FILE_PRESENT` warning. `CONTRACTS/TAXONOMY_RESOLVER.md` is normative for the resolver tool wire shapes.

Design rules:

1. Unique Thing identity rules are scoped by `baseThingTemplate`, not by ThingShape.
2. Identity rule evaluation is first non-empty rule wins.
3. Batch intersection covers asset-type set intersected with hierarchy scope only; condition-scoped batches such as `recently active Cutting` are not supported.
4. `STRING` parameters do not auto-resolve; tools that really take Thing identities should expose `THINGNAME` through a wrapper.
5. Explicit resolver calls are the normative workflow. AgentLoop only validates scalar `THINGNAME` arguments before business-service invocation; it never silently resolves and replaces non-canonical values.
6. Only the resolver tools in §8.4 are on the customer-visible LLM surface.

Related documents:

- `docs/architecture/host-context.md`
- `docs/agent/configuration-repository.md`
- `docs/agent/AGENT-TAXONOMY.md`
- `docs/agent/playbook-engine.md`

## 1. Positioning

In Parler, **taxonomy** means:

```text
Application semantic layer + resolver rules
```

Taxonomy does not execute workflows. It converts user-facing words into stable ThingWorx parameters:

- a user-facing asset instance such as `ORD-Contacting-01` into a canonical `ThingName`
- an asset type phrase such as `Stacking Robot` into a ThingShape or ThingTemplate
- a batch-scoped phrase such as `Cutting assets under USA` into an asset set boundary

Playbooks and tools still execute the work. Taxonomy only supplies the identity and set boundaries they should use.

Boundary summary:

| Area | Responsibility |
| --- | --- |
| `identity-types.json` | Unique `Thing` resolution from user input |
| `asset-types.json` | Asset type phrase to ThingShape / ThingTemplate |
| hierarchy services | Hierarchy node resolution and Thing set expansion |
| condition tools | Metric / state / time-window filtering such as low OEE or recently active |
| custom tools / wrappers | Non-Thing business objects such as customer, clinic, order, batch |
| playbooks | Multi-step execution, suspension, fan-out, evidence assembly |

## 2. Why Two Small Files

The older v2 taxonomy design let one `identity-types.json` describe entities, asset types, representation classes, default identifier profiles, and other domains. That was powerful, but too broad for the product need.

v3 narrows the problem:

1. Unique identity resolution is only for `Thing`.
2. Asset type resolution is a separate map.
3. `THINGNAME` parameters are the only default trigger for unique `Thing` resolution.
4. Ordinary `STRING` parameters do not auto-resolve.
5. `THINGSHAPENAME`, `THINGTEMPLATENAME`, `DATASHAPENAME`, and similar platform entity names are not routed through unique `Thing` resolution. They should use exact lookup or `list_entities_by_type`.
6. Customer, clinic, order, batch, and other business objects are out of the generic resolver. Applications provide custom tools or wrappers for those.

This keeps the built-in taxonomy layer aligned with ThingWorx's strengths: `Thing`, `ThingShape`, `ThingTemplate`, and hierarchy.

## 3. Repository Layout

Structured taxonomy files live in the configuration repository under:

```text
/taxonomies/identity-types.json
/taxonomies/asset-types.json
```

Development fixtures in this repository are listed in §11.

## 4. `identity-types.json`

`identity-types.json` is an ordered JSON array. Array order is priority. There is no `priority` field.

Each entry has exactly three top-level fields:

```json
[
  {
    "identityProperties": [
      { "name": "name", "match": "suffix" },
      { "name": "PTCDisplayName", "match": "equals" },
      { "name": "PTCSerialNumber", "match": "equals" }
    ],
    "baseThingTemplate": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": []
  }
]
```

Field semantics:

| Field | Required | Meaning |
| --- | --- | --- |
| `identityProperties` | yes | Properties used to match user input. Entries may be strings or objects. Object form is `{ "name": "...", "match": "..." }`. |
| `baseThingTemplate` | yes | Search only Things implementing this ThingTemplate, directly or through inheritance. |
| `criticalProperties` | yes | Extra properties returned with candidates. Use `[]` when no extra fields are needed. |

`baseThingTemplate` is deliberately the only generic identity scope. App Developers can reliably ensure that an identity property exists on a ThingTemplate, either directly or through template inheritance, and ThingTemplate-scoped search can use the faster platform query path. ThingShape is still valid for asset type membership in `asset-types.json`, but it is not reliable enough as a generic property-search contract. Deployments that need ThingShape-only property identity should use a custom tool or wrapper.

### 4.1 Property Rules

`identityProperties` and `criticalProperties` must exist on `baseThingTemplate`, either directly or inherited. The agent runtime does not validate this at config load time. Misconfiguration should surface as a structured runtime error or omitted property when the resolver runs.

`name` is special:

1. `name` is always returned as the first field even when omitted from `identityProperties`.
2. If `name` appears in `identityProperties`, it participates in matching using its configured `match`.
3. Returned fields are de-duplicated in this order: `name`, `identityProperties`, `criticalProperties`.

**Zippered pairing:** the *i*-th entry in `identityProperties` pairs only with the *i*-th `match` (same index). A candidate Thing matches when **any** pair matches the user text (OR across pairs), not when every pair matches.

Normative QIT filter construction for `resolve_thing` (OR of `EQ` / `LIKE` leaves, `isCaseSensitive:false`, suffix `LIKE` escaping) is specified in §4.1.1.

Supported `match` values (v3 `identity-types.json` — enforced at repository load; any other value fails parse with `TAXONOMY_ROW_INVALID`):

| Match | Meaning |
| --- | --- |
| `equals` | Equality on the trimmed query and raw property string. Case-insensitive: QIT uses `EQ` with `isCaseSensitive:false`, and the Java zippered post-filter uses the same semantics. |
| `suffix` | Suffix match on the raw property string, intended for canonical Thing `name` values with namespace prefixes. Case-insensitive: QIT uses `LIKE` with a leading `*` wildcard and an escaped user fragment (see §4.1.1); the Java zippered post-filter uses case-insensitive suffix comparison. |

If an `identityProperties` entry is a plain string, it means:

```json
{ "name": "<property>", "match": "equals" }
```

There are no `normalizedEquals`, `contains`, or `normalizedSuffix` modes; the parser and runtime accept only `equals` and `suffix`.

#### 4.1.1 QIT filter construction for `resolve_thing`

Each rule is developer-authored configuration. For one rule and the trimmed user text, `resolve_thing` takes these branches:

1. **Direct-name fast path.** When the rule's only identity property is `name` with `match: equals`, the resolver first looks up a Thing by that exact platform name, as `CONTRACTS/TAXONOMY_RESOLVER.md` §4 allows. This lookup is case-sensitive after trim. A hit resolves inline without QIT; a miss continues to the QIT filter path.
2. **QIT filter path (normal).** The rule is translated into one ThingWorx Query Implementing Things (QIT) call scoped to the rule's `baseThingTemplate`, with the filter constructed as described below. The platform query does the indexed lookup, and the Java zippered post-filter re-checks the returned rows with the same case-insensitive semantics.
3. **Degraded path.** If building the QIT service parameters with the non-null `query` fails (the local query conversion and validation before any service call), the runtime logs `taxonomy_qit_query_rejected`, rebuilds the parameters with a null `query`, and runs one unfiltered QIT call bounded by the QIT item cap; the Java zippered match then selects rows from that result, which may be truncated. A failure while the QIT service itself executes does not trigger this fallback: it propagates and `resolve_thing` returns `TAXONOMY_RESOLVE_FAILED`. This is existing fallback behavior, not a second normal path.

The rest of this section specifies the QIT filter path.

Rule semantics:

- Rules are evaluated in array order. Zero rows (`IDENTITY_NOT_FOUND`) advances to the next rule; any other outcome, including `IDENTITY_AMBIGUOUS`, terminates rule iteration, so a later rule never masks an ambiguity in an earlier rule. Cross-template precedence is therefore the developer's responsibility.
- `identityProperties` entries are OR-ed within a rule, never AND-ed. Each entry is a direct `(field, match)` pair; there is no cross product between fields and match modes.
- `criticalProperties` are projection fields, not match predicates, unless they are also listed in `identityProperties`.

Input normalization:

- `text.trim()` is applied before any branch runs.
- No case folding is applied to `text`, and the resolver does not emit `LOWER(...)` predicates, which would bypass platform indexes. On the QIT filter path, case insensitivity comes from the query options below; the direct-name fast path stays case-sensitive.
- For `suffix`, each of `\`, `*`, `%`, and `?` in the trimmed text is prefixed with `\` so the fragment is literal in ThingWorx SQL-pattern `LIKE`; the resolver then prepends one leading `*` as the intentional multi-character wildcard. `_` is literal under the `LikeFilter` semantics used here and is not escaped.

Query options: every generated string leaf (`EQ` or `LIKE`) carries an explicit `isCaseSensitive: false` rather than relying on the platform default. The option is never placed on the query root or on the composite `OR`.

| `match` | QIT predicate |
| --- | --- |
| `equals` | `field EQ text` |
| `suffix` | `field LIKE "*<escapedText>"` |

Example: a rule on `PTC.MfgModel.DefaultWorkunit_TT` with `name`/`suffix`, `PTCDisplayName`/`equals`, and `PTCSerialNumber`/`equals`, and user text `ORD Contacting 02`, produces:

```json
{
  "filters": {
    "type": "OR",
    "filters": [
      { "fieldName": "name", "type": "LIKE", "value": "*ORD Contacting 02", "isCaseSensitive": false },
      { "fieldName": "PTCDisplayName", "type": "EQ", "value": "ORD Contacting 02", "isCaseSensitive": false },
      { "fieldName": "PTCSerialNumber", "type": "EQ", "value": "ORD Contacting 02", "isCaseSensitive": false }
    ]
  }
}
```

Projection for the same call:

```text
basicPropertyNames = ["name"]
propertyNames = dedup(identityProperties[*].name ∪ criticalProperties) − {"name"}
```

The deduplication is insertion-ordered so fixtures and log lines are deterministic. When `assetTypeKey` resolves to a ThingShape asset type, the identity predicates are still pushed into the QIT `query`; the zero / one / many decision is made after the ThingShape membership filter. Outcome codes and candidate caps are defined in `CONTRACTS/TAXONOMY_RESOLVER.md`.

### 4.2 Unique Thing Resolver Behavior

The resolver receives user text and returns zero, one, or many Thing candidates.

Algorithm:

```text
for each rule in identity-types.json order:
  list candidate Things under baseThingTemplate
  match user input against configured identityProperties
  if no candidates:
    continue to next rule
  if exactly one candidate:
    return UNIQUE with canonical ThingName and selected properties
  if multiple candidates:
    return AMBIGUOUS with selected properties
    stop

return NOT_FOUND
```

This is **first non-empty rule wins**. If a higher-priority rule returns multiple candidates, the resolver must not continue to lower-priority rules and mix different candidate domains.

Ambiguity responses are bounded: at most 10 candidate rows go to the LLM, with the total candidate count (`totalCount`) and `ambiguousCandidatesTruncated` when more candidates exist. This is a token and latency guardrail, not a ranking guarantee. A large match set returns `THING_RESOLVED_LARGE` instead (`CONTRACTS/TAXONOMY_RESOLVER.md`).

### 4.3 Invocation Policy

Only these cases trigger unique Thing resolution:

1. A tool parameter has ThingWorx base type `THINGNAME`.
2. A playbook step explicitly declares it needs a canonical `ThingName`.
3. A custom wrapper explicitly calls the resolver as an escape hatch.

Ordinary `STRING` parameters do not trigger resolver. If an application defined a Thing identifier as `STRING`, the recommended product fix is to wrap it with a custom tool/service whose public surface uses `THINGNAME` or does explicit validation.

The normative workflow is explicit:

```text
resolve user-facing identifier
-> UNIQUE: bind canonical ThingName
-> call business tool with canonical ThingName
```

Skills and playbooks should model resolver calls as explicit pre-steps. This keeps skill-to-playbook conversion testable: the resolver step appears in the trace and can be converted into a playbook node.

AgentLoop and **configuration-repository / playbook extended tool** execution perform a defensive preflight for scalar **`THINGNAME`** service parameters immediately before invoking the downstream ThingWorx service (extended tools: `AgentThing` extended-tool path; see **`CONTRACTS/TAXONOMY_RESOLVER.md`** §7):

1. If the provided value is an exact accessible Thing name, pass it through.
2. If exact Thing lookup fails, return a structured preflight error and do not invoke the business service.
3. The preflight never calls the resolver itself and never replaces the value.

Canonical means exact ThingWorx Thing lookup succeeds in the current execution context. It is not determined by string shape.

### 4.4 Single-Thing Outcomes

When the resolver returns:

| Outcome | Runtime behavior |
| --- | --- |
| `UNIQUE` | Bind canonical `ThingName`; call downstream tool. |
| `NOT_FOUND` | Do not call downstream tool; ask the user to clarify or provide a better identifier. |
| `AMBIGUOUS` | Do not call downstream tool; show candidates and ask the user to choose. |

`NOT_FOUND` and `AMBIGUOUS` are not business tool failures. They are pre-invocation binding states.

## 5. `asset-types.json`

`asset-types.json` is a JSON object. Each map key is the canonical user-facing asset type label.

Example:

```json
{
  "Stacking Robot": {
    "aliases": ["stacking robots", "stacker robot"],
    "entityType": "ThingShape",
    "entityName": "PTCTDD.CellfabDataset.StackingRobot_TS",
    "criticalProperties": ["PTCDisplayName", "PTCMake", "PTCModel", "PTCSerialNumber"]
  },
  "Workunit": {
    "aliases": ["work unit", "machine", "asset"],
    "entityType": "ThingTemplate",
    "entityName": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": ["PTCDisplayName", "PTCMake", "PTCModel", "PTCSerialNumber"]
  }
}
```

Field semantics:

| Field | Required | Meaning |
| --- | --- | --- |
| map key | yes | Canonical asset type label. |
| `aliases` | yes | Other user phrases for the same asset type. Use `[]` if none. |
| `entityType` | yes | `ThingShape` or `ThingTemplate`. |
| `entityName` | yes | The ThingShape or ThingTemplate entity name. |
| `criticalProperties` | yes | Properties to include when listing or returning asset-set rows. Use `[]` if none. |

`asset-types.json` does not resolve a unique Thing. It resolves an asset type boundary.

## 6. Single Thing Resolution Scenarios

### 6.1 Explicit User Identifier

Prompt:

```text
Show the utilization-state breakdown for ORD-Contacting-01 on 2025-09-10.
```

Expected flow:

```text
user text contains candidate identifier
-> target tool parameter is THINGNAME
-> run unique Thing resolver using identity-types.json
-> UNIQUE: call tool with canonical ThingName
-> NOT_FOUND / AMBIGUOUS: ask user, do not call tool
```

The LLM may extract `ORD-Contacting-01` from the prompt, but the decision to resolve it must be enforced by runtime/tool metadata. The LLM should not be the only guard.

### 6.2 Host Context Singleton

When `hostContext.currentThings` contains exactly one canonical Thing name and the user prompt omits an identifier or uses a singular reference, use that canonical Thing directly.

Prompt:

```text
Show the utilization-state breakdown for today.
```

Host context:

```json
{
  "currentThings": ["SE.CellFab.Model.Workunit.ORD-Contacting-01"]
}
```

Expected flow:

```text
hostContext has exactly one current Thing
-> prompt has no explicit different identifier
-> use hostContext ThingName directly
-> do not run unique Thing resolver
```

If the user explicitly names another asset, the user prompt wins and the explicit identifier goes through the normal resolver path.

### 6.3 Multi-Thing Host Context

When `hostContext.currentThings` contains more than one Thing:

| Prompt type | Expected behavior |
| --- | --- |
| Bulk prompt, e.g. "compare them" | Use all current Things. |
| Singular prompt, e.g. "how is it doing?" | Ask for clarification or summarize all, but do not silently pick one. |
| Explicit named asset | Resolve/use the explicit asset; hostContext does not override it. |

## 7. Batch Asset Resolution

Batch resolution means the user asks for a set of Things, not a single Thing.

Examples:

```text
Show all Stacking Robots.
Compare Cutting machines in USA and Germany.
Which recently active Cutting machines have low OEE?
```

Batch resolution is based on `asset-types.json`, plus optional hierarchy scope and condition scope.

### 7.1 Base Asset Set

First resolve the asset type phrase.

```text
"Stacking Robots" -> asset-types.json["Stacking Robot"]
```

Then build the base set:

| `entityType` | Base set rule |
| --- | --- |
| `ThingShape` | Things implementing `entityName` as a ThingShape. |
| `ThingTemplate` | Things implementing `entityName` as a ThingTemplate. |

Returned rows should include:

1. `name`
2. any available `criticalProperties`

If a critical property is unavailable or unreadable, the runtime should omit or null that field according to existing table/result conventions; it should not turn asset type resolution into a config-load failure.

### 7.2 Hierarchy-Scoped Batch

Prompt:

```text
Compare Stacking Robots in USA and Germany.
```

Expected flow:

```text
resolve asset type "Stacking Robot"
-> build base asset set for its ThingShape / ThingTemplate
-> resolve hierarchy scope "USA"
-> get hierarchy Thing set
-> intersect base asset set with hierarchy Thing set
-> repeat for "Germany"
-> call downstream tool / playbook with scoped sets
```

The hierarchy resolver owns hierarchy node semantics. `asset-types.json` only supplies the asset type boundary needed for the intersect.

This uses the hierarchy-scoped asset query path, such as `query_entities_by_taxonomy` with a hierarchy node argument. There is no general-purpose `intersect_asset_sets` tool.

### 7.3 Condition-Scoped Batch (not supported)

Condition-scoped prompts define a subset by state, metrics, activity, or business rules, for example `recently active Cutting`, `the machines with low OEE`, or `Cutting machines with utilization lower than 30% yesterday`.

Taxonomy does not define "recently active", "low OEE", "bad quality", or "high scrap". Those are condition semantics and belong in tools, playbooks, or application services. Taxonomy can help such a tool by resolving `Cutting` to a ThingShape / ThingTemplate, telling the query which critical properties to return, and providing the base asset set; it is not a metric rule engine, and there is no built-in condition-scoped intersection.

## 8. Tool and Playbook Routing

The semantic contract below follows the explicit-resolver rule: the LLM, skill, or playbook should call resolver tools before business tools that require `THINGNAME`. AgentLoop is a guardrail, not an invisible resolver.

### 8.1 Tool Calls

For a tool call with a `THINGNAME` parameter:

1. If the parameter is already a canonical Thing name and exists, pass it through.
2. If it is user-facing text, the caller should first invoke the explicit resolver.
3. If the caller failed to resolve it and exact Thing lookup fails, return a structured preflight error and do not call the business tool.
4. The caller may recover in the same conversation turn by invoking the resolver, then retrying the business tool with the canonical `ThingName`.

For platform entity name parameters:

| BaseType | Recommended route |
| --- | --- |
| `THINGSHAPENAME` | exact lookup / `list_entities_by_type` |
| `THINGTEMPLATENAME` | exact lookup / `list_entities_by_type` |
| `DATASHAPENAME` | exact lookup / `list_entities_by_type` |
| `MASHUPNAME`, `MENUNAME`, etc. | exact lookup / `list_entities_by_type` as needed |

These are not unique Thing identities and should not use `identity-types.json`.

Host context is a separate source of already-canonical names. If `hostContext.currentThings` supplies a canonical `ThingName`, runtime may bind that value directly for a compatible `THINGNAME` parameter without checking whether it matches any `identity-types.json` rule.

### 8.2 Playbooks

Playbooks should model resolution as a pre-step when identity is required.

Unique Thing:

```text
resolveThing(input)
  UNIQUE -> bind thingName
  NOT_FOUND / AMBIGUOUS -> suspend and ask user

call business step with thingName
```

In this pseudocode, `resolveThing` means the public tool `resolve_thing`.

Batch asset set:

```text
resolveAssetType(input)
buildBaseAssetSet(assetType)
applyHierarchyScope?()
call business step with ThingName set or cached table
```

In this pseudocode, `resolveAssetType` means the public tool `resolve_asset_type`.

There are no condition-scoped taxonomy steps in playbooks.

This lets playbooks suspend before side effects or expensive tool calls.

### 8.3 `STRING`-Typed Thing Identifiers

The resolver does not automatically fix tools whose public parameter is `STRING` even though the business meaning is a Thing identity.

For example, if a tool exposes:

```text
Machine: STRING
```

but expects a canonical Thing name, display names or suffixes are not auto-resolved into that parameter. The recommended fix is a thin wrapper whose public surface exposes:

```text
Machine: THINGNAME
```

or whose wrapper explicitly validates and rejects bad input before calling the legacy service. This is an accepted cost. It avoids heuristic "STRING that looks like a Thing" behavior, which would be hard to make safe and predictable across customer services.

See also:

- `docs/agent/CUSTOMIZED-TOOLS.md` (extended-tool registration and service wrappers)

### 8.4 Resolver Tool Surface

The LLM-visible resolver tools are:

| Tool | Purpose | Supersedes |
| --- | --- | --- |
| `list_asset_types` | List configured application asset types from `asset-types.json`. | `list_taxonomy_asset_types` |
| `resolve_asset_type` | Resolve user text to an asset-type boundary. | `resolve_taxonomy_asset_type` |
| `resolve_thing` | Resolve user-facing text to a unique canonical `ThingName`. | `resolve_taxonomy_asset_identifier` |

The AgentThing service wrappers match the tools; the older wrappers do not exist any more:

| Removed | Replacement |
| --- | --- |
| `ListTaxonomyAssetTypes` | `ListAssetTypes` |
| `ResolveTaxonomyAssetType` | `ResolveAssetType` |
| `ResolveTaxonomyAssetIdentifier` | `ResolveThing` |

Mashups, subscriptions, scripts, or tests that call the old service names must use the replacements. `entityHint` is not part of the LLM-visible resolver surface (v3 `asset-types.json` has no `entities[]` bucket), so prompt guidance and tool schemas do not mention it.

If identity and asset-type resolution both contribute `criticalProperties` for the same returned Thing, the implementation should merge property names before projection and read each property once. If already-materialized rows must be merged and the same property name has different values, the identity-resolution value wins for unique-Thing results; the asset-type value wins for batch asset-set rows. The implementation should record a diagnostic for such conflicts, but must not turn this into `IDENTITY_AMBIGUOUS`.

## 9. Error Shapes

Error codes:

| Code | Meaning |
| --- | --- |
| `IDENTITY_NOT_FOUND` | Unique Thing resolver found no candidates. |
| `IDENTITY_AMBIGUOUS` | Unique Thing resolver found multiple candidates. |
| `ASSET_TYPE_NOT_FOUND` | Asset type phrase did not match key or aliases. |
| `ASSET_TYPE_AMBIGUOUS` | Asset type phrase matched multiple entries. |
| `IDENTITY_RESOLUTION_REQUIRED` | Scalar **`THINGNAME`** / Thing-target **`entityName`** preflight for first-party built-ins and extended tools (**`CONTRACTS/TAXONOMY_RESOLVER.md`** §7 — property, alert, **`invoke_service`** (Thing), **`set_property_value`**, **`discover_thing_members`**, Thing-path legacy discovery) — value is not an accessible exact ThingName; call **`resolve_thing`** then retry when recovery is available. |
| `TAXONOMY_CONFIG_INVALID` | JSON is malformed or violates required shape. |
| `TAXONOMY_UNAVAILABLE` | Config missing or cache unavailable. |
| `HIERARCHY_SCOPED_EMPTY` | Hierarchy scope expansion is valid but produced no Things (`query_entities` / `query_entities_by_taxonomy`). |

Candidate rows for ambiguity should contain enough context for user clarification:

```json
{
  "name": "SE.CellFab.Model.Workunit.ORD-Contacting-01",
  "PTCDisplayName": "ORD Contacting 01",
  "PTCSerialNumber": "SN-001",
  "matchedBy": {
    "property": "name",
    "match": "suffix"
  }
}
```

Minimum resolver diagnostics, whether logged or returned in structured tool/preflight payloads, should include outcome, rule index when applicable, matched property when applicable, candidate count, and truncation status. This is the smallest useful signal for tuning `identity-types.json` without turning taxonomy into a full analytics feature.

## 10. LLM Guidance

The system prompt and tool descriptions teach these compact rules:

1. Specific user-facing machine names must be resolved to canonical `ThingName` before calling tools that take `THINGNAME`.
2. Asset type phrases come from `asset-types.json`.
3. Hierarchy phrases are separate scopes and should be intersected with asset sets.
4. Metric / state phrases such as `low OEE` or `recently active` require a condition tool; taxonomy only provides the base asset set.
5. If `hostContext.currentThings` has exactly one Thing and the user omitted the asset, use that Thing directly.
6. Do not invent customer / clinic / order / batch resolution from taxonomy; use app tools.

## 11. Development Fixtures

v3 taxonomy files in this repository:

```text
dev_data/sample_scpa_utilization_agent_configuration/taxonomies/identity-types.json
dev_data/sample_scpa_utilization_agent_configuration/taxonomies/asset-types.json
parler-agent/src/test/resources/taxonomy/cellfab-v3/identity-types.json
parler-agent/src/test/resources/taxonomy/cellfab-v3/asset-types.json
training/dev_data/taxonomies/  (training copies)
```

The v2 schema fixture used by `IdentityTypesJsonParser` tests and the v2 eval suites is `docs/agent/evals/fixtures/identity-types-minimal.json`.

## 12. Sample Fixture

The CellFab sample `identity-types.json` is:

```json
[
  {
    "identityProperties": [
      { "name": "name", "match": "suffix" },
      { "name": "PTCDisplayName", "match": "equals" },
      { "name": "PTCSerialNumber", "match": "equals" }
    ],
    "baseThingTemplate": "PTC.MfgModel.DefaultWorkunit_TT",
    "criticalProperties": []
  }
]
```

The CellFab sample `asset-types.json` contains 20 canonical asset type labels, including `Stacking Robot`, `Contacting`, `Cutting`, `Slurry Pump`, `Vacuum Dryer`, and `Testing`.

## 13. Not Supported

Taxonomy does not provide:

1. a knowledge graph
2. generalized natural-language entity search
3. customer / clinic / order / batch resolution
4. automatic resolver for all `STRING` parameters
5. metric semantics such as `low OEE`
6. state semantics such as `recently active`
7. hierarchy node matching
8. UI candidate selection controls
9. cross-conversation identity memory
10. broad privacy / DLP rules beyond existing `PASSWORD` handling
11. ThingShape-only property identity models
12. general-purpose condition-scoped batch intersection
