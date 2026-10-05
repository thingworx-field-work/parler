# Semantics and Evidence Foundation

This document describes two related parts of `parler-agent`:

- **Part A — application semantic profile.** An App-authored file maps stable business property
  roles of a taxonomy asset type to one exact ThingWorx Property or Service, with unit, dimension,
  grain and expected cadence. Parler validates the file, keeps an immutable snapshot, resolves
  roles exactly, and records semantic provenance on the cached source.
- **Part B — evidence, recovery and governance vocabulary.** Typed metadata around stable tool
  error codes (G18), a bounded retry ledger, the server-authored `EvidenceAssessment` (G19), and the
  alignment of existing egress/audit paths with those meanings (G20).

Code comments refer to Part A as U3S and to Part B as U3E. The labels `SP1`–`SP7` (Part A) and
`EG1`–`EG6` (Part B) mark the rules below and are cited from code.

Related documents:

- [`semantics-evidence-foundation-app-authoring-walkthrough.md`](./semantics-evidence-foundation-app-authoring-walkthrough.md) — App Developer and operator guide for the profile file.
- [`../configuration-repository.md`](../configuration-repository.md) — repository paths and refresh behavior.
- [`../AGENT-TAXONOMY.md`](../AGENT-TAXONOMY.md) — taxonomy v3 identity (`assetTypeKey`).
- [`time-quality-join.md`](./time-quality-join.md) — the analysis envelope that composes `EvidenceAssessment`.
- [`../protection.md`](../protection.md) — PASSWORD and protected-value handling.

---

## Part A — Application semantic profile

### A1. Purpose

Industrial Apps use site-specific Property and Service names. A statement such as "for a
`StackingRobot`, the business role `operating_temperature` is Property `Wrst1`, measured in Celsius
every five seconds" is written once in the semantic profile. Parler can then:

1. resolve the business role to the exact ThingWorx source;
2. check the declared unit, dimension, grain and cadence;
3. carry the profile identity, version and digest with the cached source; and
4. fail with a diagnostic instead of guessing a property.

The profile is not a domain ontology, a formula language, or a unit-conversion engine. A
site-specific calculation is expressed as a governed ThingWorx Service and bound as a `SERVICE`
role.

### A2. Relationship to taxonomy v3

Taxonomy v3 remains the identity layer: Thing identity, `assetTypeKey`, and the aliases used by
`resolve_thing` do not move into the semantic profile. The profile joins the taxonomy only by the
exact `assetTypeKey` string (no alias folding or slug rewriting). An invalid semantic profile never
changes or disables the taxonomy snapshot; it only makes semantic-dependent behavior unavailable,
and exact-name tools keep their existing contracts.

### A3. File, schema and caps (SP1)

| Item | Value |
|---|---|
| Repository path | `/semantics/semantic-profile.json` in the Agent configuration FileRepository (`ConfigurationRepositoryPaths.SEMANTIC_PROFILE_JSON`) |
| Schema id | `parler-semantic-profile-v1` (any other value is rejected) |
| Files per AgentThing | one; one active immutable snapshot |
| Max asset types | 64 |
| Max property roles per asset type | 128 |
| Max aliases per role | 8 |
| Max file size | 256 KiB of UTF-8 |
| Unknown JSON fields | rejected at every level (root, asset type, role, binding) |
| Digest | SHA-256 hex of the file's UTF-8 bytes |

Validation is all-or-nothing: any error-severity diagnostic rejects the whole document. There is no
partial profile with some roles dropped.

### A4. Profile shape (SP3, SP4)

```json
{
  "schema": "parler-semantic-profile-v1",
  "profileId": "cell-a-operations",
  "version": "2026.07.1",
  "assetTypes": {
    "StackingRobot": {
      "propertyRoles": {
        "operating_temperature": {
          "aliases": ["robot temperature"],
          "binding": { "kind": "PROPERTY", "propertyName": "Wrst1" },
          "unit": "Cel",
          "dimension": "temperature",
          "grain": "sample",
          "expectedCadence": "PT5S"
        },
        "energy_per_cycle": {
          "binding": {
            "kind": "SERVICE",
            "thingName": "CellAAnalytics",
            "serviceName": "GetEnergyPerCycle",
            "resultField": "value"
          },
          "unit": "kWh",
          "dimension": "energy",
          "grain": "cycle"
        }
      }
    }
  }
}
```

Field rules:

| Field | Rule |
|---|---|
| `schema`, `profileId`, `version` | required; `profileId` and `version` are App-authored stable strings |
| `assetTypes` keys | exact taxonomy `assetTypeKey` values; checked against the loaded taxonomy when it has keys |
| role key (`roleId`) | stable, non-blank, unique within the asset type |
| `aliases` | optional string array; lookup aids only, never alternative targets; an alias must not equal its own role id, another role id, or another role's alias |
| `binding.kind` | exactly `PROPERTY` or `SERVICE` (SP3) |
| `PROPERTY` binding | `propertyName` — exact property on the resolved Thing; no cross-Thing property binding |
| `SERVICE` binding | `thingName`, `serviceName`, `resultField` — one governed Service and one result field |
| `unit`, `dimension`, `grain` | required; closed vocabulary below; the unit must belong to the stated dimension |
| `expectedCadence` | optional positive ISO-8601 duration (for example `PT5S`); absent means unknown |

A binding cannot carry Java class names, expressions, credentials, cache paths, URLs,
authorization claims or prompt text: the closed field sets reject them as unknown fields.

Closed vocabulary (`SemanticProfileVocabulary`, SP4):

| Kind | Tokens |
|---|---|
| `dimension` | `temperature`, `energy`, `power`, `length`, `mass`, `time`, `pressure`, `speed`, `volume`, `electric_current`, `voltage`, `frequency`, `count`, `ratio`, `dimensionless` |
| `grain` | `sample`, `cycle`, `batch`, `event`, `window`, `aggregate` |

| Unit | Dimension | Unit | Dimension |
|---|---|---|---|
| `Cel`, `K` | temperature | `Pa`, `bar` | pressure |
| `kWh`, `J` | energy | `m/s` | speed |
| `W` | power | `L` | volume |
| `m`, `mm` | length | `A` | electric_current |
| `kg`, `g` | mass | `V` | voltage |
| `s`, `min`, `h` | time | `Hz` | frequency |
| `1` | dimensionless | `%` | ratio |

Unknown tokens fail validation. The `count` dimension exists in the vocabulary but has no unit
token, so no role can currently declare it.

### A5. Resolution (SP6)

`SemanticRoleResolver.resolve(snapshot, assetTypeKey, roleIdOrAlias)` accepts an exact role id or a
declared alias inside one exact `assetTypeKey` and returns one internal status:

| Status | Meaning |
|---|---|
| `RESOLVED` | exactly one role matched by id or alias |
| `AMBIGUOUS` | the id or alias matches more than one role in that asset type; no winner is picked |
| `NOT_FOUND` | no role or alias matches (a case-only difference is still `NOT_FOUND`) |
| `UNAVAILABLE` | no loaded profile snapshot |

A resolved result carries the role, its binding and facts, the stable reference
`<assetTypeKey>.propertyRole.<roleId>`, and the profile id, version and digest. The result is an
internal Java type, not a wire shape. The profile is not projected into the model prompt; the model
cannot override resolver output or guess around `AMBIGUOUS` or `NOT_FOUND`.

`SemanticSourceHandoff.resolvePropertyBindingUnderAssetType` performs the reverse lookup used for
provenance: find the single `PROPERTY` role under one proven `assetTypeKey` whose binding names a
given property.

### A6. Load, refresh and snapshot lifecycle (SP2)

The profile is loaded by the existing prompt-context cache build, right after the taxonomy. The
taxonomy's asset-type keys are passed in so unknown keys are rejected. The snapshot lives on the
prompt-context snapshot (`PromptContextCacheSnapshot.getSemanticProfile()`); there is no separate
loader or state machine.

| Snapshot status | When |
|---|---|
| `not_configured` | no configuration repository, or the file is missing or empty (and no prior loaded snapshot) |
| `unavailable` | repository unreadable or document invalid (and no prior loaded snapshot) |
| `loaded` | valid document with at least one role |
| `empty` | valid document with no roles |
| `stale` | the current file is missing or invalid, and a previously loaded snapshot is retained |

An invalid or missing file never replaces a loaded snapshot: the prior snapshot is kept with
`stale=true` and the new diagnostics prepended. It stays active until a valid file is loaded or the
AgentThing (or ThingWorx) restarts. A consumer reads the prompt-context snapshot reference once, so
one operation sees one profile version together with the taxonomy it was validated against.

Operator services on the AgentThing:

| Service | Result |
|---|---|
| `RefreshSemanticProfileCache` | reloads the file (keeping a prior loaded snapshot as `stale` on failure) and returns diagnostics JSON with `refreshed:true` |
| `GetSemanticProfileDiagnostics` | returns the current snapshot's diagnostics JSON |
| `ValidateAgentConfigurationRepository` | includes a `semanticProfile` section (path, status, loaded, stale, profile id, version, digest, counts, diagnostics) |

Diagnostics JSON fields: `status` (`success` when loaded, else `error`), `loaded`, `stale`,
`snapshotStatus`, `profileId`, `version`, `digest`, `assetTypeCount`, `roleCount`, `sourcePath`,
`lastSuccessfulRefresh`, `lastAttemptedRefresh`, and `diagnostics[]` of
`{severity, code, message}`. The profile body is never dumped.

Diagnostic codes: `SEMANTIC_PROFILE_UNAVAILABLE`, `SEMANTIC_PROFILE_CONFIG_INVALID`,
`SEMANTIC_PROFILE_TAXONOMY_REF`, `SEMANTIC_PROFILE_AMBIGUOUS`, `SEMANTIC_PROFILE_UNIT` (unit,
dimension, grain or cadence), `SEMANTIC_PROFILE_TARGET` (binding target fields), and
`SEMANTIC_PROFILE_DIAGNOSTICS_ERROR` (serialization failure).

### A7. Invocation-time preflight and authorization (SP7)

Refresh validates structure, vocabulary and taxonomy references only. Live targets are checked at
use by `SemanticBindingPreflight`, which returns `OK`, `TARGET_NOT_FOUND`, `PASSWORD_PROTECTED`,
`TYPE_INCOMPATIBLE` or `UNAVAILABLE`:

- **PROPERTY:** the property must exist on the Thing and must not be PASSWORD-typed.
- **SERVICE:** the live Thing name must equal the binding `thingName`; the Service must exist; it
  must not return PASSWORD, declare a PASSWORD parameter, or declare a PASSWORD column in its
  INFOTABLE result. For an INFOTABLE result with a resolvable DataShape, `resultField` must be a
  field of that DataShape and must not be PASSWORD. A Service with no result type is
  `TYPE_INCOMPATIBLE`.

Profile validity never grants permission. Every read still runs under the caller's current
ThingWorx `SecurityContext`. There is no fallback from a role to a similarly named property.

### A8. Source provenance (SP5)

`SourceDescriptor` carries optional semantic provenance fields. They hold references and profile
identity only, never profile bodies:

| Field | Value |
|---|---|
| `propertyRoleRef` | `<assetTypeKey>.propertyRole.<roleId>` |
| `unitRef` | role unit token |
| `grainRef` | role grain token |
| `cadenceRef` | role `expectedCadence`, when declared |
| `semanticProfileId` | profile id |
| `semanticProfileVersion` | profile version |
| `semanticProfileDigest` | profile digest |

The producer is `query_numeric_property_history`. When it caches a history series it calls
`SemanticSourceHandoff.attachPropertyProvenance`, which adds the fields only when all of these hold:

1. the live Thing's taxonomy `assetTypeKey` is uniquely proven (`TaxonomyAssetTypeProof`);
2. exactly one `PROPERTY` role under that key binds the queried property name; and
3. Property preflight passes.

Otherwise (no Thing, unproven or ambiguous type, name collision across types, failed preflight)
the descriptor is left unchanged. Wrong provenance is never preferred over none. Other exact-name
tools do not set these fields. Derived artifacts copy the parent's semantic provenance with their
other lineage fields. No analysis operation currently reads `unitRef` or `cadenceRef`; the
time-series quality mode uses its own profile (see [`time-quality-join.md`](./time-quality-join.md) §4).

### A9. Core and App Developer responsibilities

| Owner | Owns |
|---|---|
| Parler Core | schema and caps, vocabulary, validator, snapshot lifecycle, resolver, preflight, provenance attach, diagnostics, reference fixtures |
| App Developer | profile id and version, exact taxonomy `assetTypeKey` values, role ids and aliases, Property or governed Service bindings, unit/dimension/grain/cadence facts, governed Services and DataShapes for site calculations, DEV/QA/PROD promotion |

A new role that fits the Property/Service binding contract needs no Core change. A new binding kind
would be a Core code change.

### A10. Fixtures and tests

Reference fixtures live in `parler-agent/src/test/resources/nearterm/semantics/`: an annotated
template, a realistic App example (`stacking-robot.example.json`), and invalid-schema,
unknown-field, unit-mismatch, ambiguous-alias and invalid-Service-target cases. Unit fixtures use the
simplified key `StackingRobot`; production profiles must use the site taxonomy's literal key.

```bash
cd parler-agent
./gradlew test --no-daemon -PuseLocalTwxLib=true \
  --tests 'com.thingworx.things.agent.semantics.*' \
  --tests 'com.thingworx.things.agent.taxonomy.*'
```

---

## Part B — Evidence, recovery and governance

### B1. Overview

Part B supplies a small shared trust vocabulary:

- known tool error codes map to typed, bounded recovery metadata without parsing display text;
- evidence keeps status, completeness, caveats and lineage in a compact server-authored form; and
- existing egress, protection and audit paths keep those meanings intact, while ThingWorx remains
  the authorization and security boundary.

It adds no RBAC, audit store, encryption, FileRepository admission, data-classification framework,
cost or budget behavior, or evaluation-pack registry.

### B2. Typed error metadata (G18, EG1)

The outer error envelope (`status`, `code`, `message`) is unchanged and `code` remains the
compatibility identity. Typed fields are added beside it:

```json
{
  "status": "error",
  "code": "CACHE_MISS",
  "message": "No cached result for this cacheId in the current conversation (or expired).",
  "category": "LIFECYCLE",
  "reason": "NOT_FOUND",
  "retryable": true,
  "retryBudgetKey": "source-query",
  "evidenceStillUsable": false,
  "recoveryActions": [ { "type": "REEXECUTE_SOURCE", "argumentPatch": {} } ]
}
```

| Field | Meaning |
|---|---|
| `category` | `LIFECYCLE`, `ARGUMENT`, `IDENTITY`, `AUTHORIZATION`, `UPSTREAM`, `BUDGET`, `INTERNAL` |
| `reason` | emitted only when the current operation can prove it; otherwise omitted |
| `retryable` | whether a bounded retry path remains |
| `retryBudgetKey` | ledger key for that retry path, when `retryable` |
| `evidenceStillUsable` | whether evidence already gathered remains valid |
| `recoveryActions[]` | closed action types (B3) with a server-generated `argumentPatch` object |
| `message` | bounded presentation text; never used as a classifier |

`ErrorRecoveryMapper` maps outer codes (normalized to upper case, `-` to `_`) as follows. This table
is the frozen v1 mapping:

| Outer code | category | retryable | retryBudgetKey | recoveryActions | evidenceStillUsable |
|---|---|---|---|---|---|
| `CACHE_MISS` | `LIFECYCLE` | true | `source-query` | `REEXECUTE_SOURCE` | false |
| `LAST_TABULAR_CACHE_UNAVAILABLE` | `LIFECYCLE` | true | `source-query` | `ASK_USER`, `REEXECUTE_SOURCE` | false |
| `UPSTREAM_TIMEOUT` | `UPSTREAM` | true | `upstream-timeout` | `RETRY_SAME_CALL` | false |
| `PERMISSION_DENIED`, `PROTECTED_VALUE_OMITTED`, `PROTECTED_VALUE_READ_BLOCKED`, `PROTECTED_VALUE_WRITE_BLOCKED`, `PROTECTED_VALUE_INPUT_BLOCKED`, `TABULAR_PROTECTED_COLUMN` | `AUTHORIZATION` | false | — | `STOP_WITH_EVIDENCE` | true |
| `PARAMETER_INVALID`, `INVALID_PARAMETERS` | `ARGUMENT` | false | — | none | false |
| `ENTITY_NOT_FOUND`, `IDENTITY_RESOLUTION_REQUIRED` | `IDENTITY` | false | — | `RESOLVE_IDENTITY` | false |
| `THINGNAME_VALUE_REQUIRED` | `ARGUMENT` | false | — | `ASK_USER` | false |
| any other code | `INTERNAL` | false | — | none | false |
| missing code | `INTERNAL` (code `INTERNAL`) | false | — | none | false |

Producers that emit the typed fields today:

- **`CACHE_MISS`** on the cached tabular tools (`CachedTabularToolsExecutor`) and
  `inspect_cached_payload` (`CachedPayloadInspect`), through `TypedToolErrorJson.cacheMiss`.
- **`CAPABILITY_POLICY_BLOCKED`** for extended tools refused by capability policy (category
  `AUTHORIZATION`, not retryable, `evidenceStillUsable:true`; see
  [`service-provider-resilience.md`](./service-provider-resilience.md) §6.3).

Other tool errors currently carry only the outer envelope.

**`reason` on `CACHE_MISS`.** `NOT_FOUND` is emitted only when `CacheMissClassifier` proves a live
miss: the `cacheId` is a well-formed public UUID whose descriptor was registered under the current
principal's artifact namespace, and the artifact open now misses. Malformed, legacy, foreign
(including another principal in the same conversation), never-seen, pre-restart and disposed
handles return the same outer `CACHE_MISS` and re-fetch guidance with no `reason`. There is no
durable tombstone.

### B3. Recovery actions (EG2)

The closed v1 action set:

| Type | Meaning |
|---|---|
| `PATCH_ARGUMENT` | server-generated patch to an allow-listed argument |
| `RESOLVE_IDENTITY` | deterministic taxonomy/identity resolution |
| `REEXECUTE_SOURCE` | re-run the recorded safe source read (re-fetch) |
| `RETRY_SAME_CALL` | retry a transient idempotent call within budget |
| `ASK_USER` | the model asks the user among bounded choices or for missing input |
| `STOP_WITH_EVIDENCE` | stop repairing; usable evidence remains |

Actions are advice attached to the error. There is no generic JSON patch executor, and mutating
calls are never retried on the strength of this metadata.

### B4. Retry ledger (EG3)

`RetryLedger` is an in-process map keyed by `RunInvocationContext.invocationId` plus
`retryBudgetKey`. Its first consumer is `CACHE_MISS` → `REEXECUTE_SOURCE`:

1. When a typed error advises `REEXECUTE_SOURCE` and an invocation context exists, the ledger entry
   is created with an allowance of **1** (`DEFAULT_SOURCE_QUERY_ALLOWANCE`) if absent.
2. Each such error consumes one unit (the explicit decrement point).
3. When the allowance is exhausted, `REEXECUTE_SOURCE` is removed from `recoveryActions`, the
   message gains "Retry budget for re-execute is exhausted for this invocation.", and, if no retry
   action remains, `retryable` becomes `false` and `retryBudgetKey` is dropped (terminal outcome).
4. When the error stays retryable, the current `RunInvocationContext` is updated with that
   `retryBudgetKey`.

A repaired call cannot reset its own allowance. Without an invocation context (offline paths), the
advice is emitted without ledger accounting. The ledger survives only in-process retry and
current-JVM HITL continuation; a restart clears it.

### B5. Evidence assessment (G19, EG4)

`EvidenceAssessment` is a compact server-authored summary. Raw rows never enter it.

| Field | Values |
|---|---|
| `status` | `SUCCESS`, `NO_FINDING`, `INSUFFICIENT_EVIDENCE`, `ERROR` (`EvidenceStatus`) |
| `completeness` | `COMPLETE`, `PARTIAL`, `UNKNOWN` — the sole completeness enum, `SourceDescriptor.CompletenessStatus` |
| `coverage` | optional string |
| `n` | non-negative count |
| `quality[]`, `applicability[]`, `warnings[]`, `conflicts[]` | typed caveat tokens (empty lists omitted) |
| `sourceCacheIds[]` | lineage |
| `method` | optional `{id, version, semanticProfileDigest}` |

`EvidenceStatus` is distinct from the row-level `ok`/`error` status of `AgentTaskEvidence`.

`EvidenceAssessmentAggregator.fromTaskState` derives the assessment from the turn's task evidence
rows:

- **Row completeness** is the more conservative of the carried/descriptor completeness and what the
  row facts prove. Sample-only or protected omissions → `PARTIAL`; inferred totals → `UNKNOWN`;
  paged or large results are `COMPLETE` only when a non-inferred total equals the returned rows;
  `rowCount < totalCount` → `PARTIAL`.
- **Aggregate completeness** never upgrades: any `PARTIAL` fact → `PARTIAL`; otherwise any unknown
  fact or no successful row → `UNKNOWN`; `COMPLETE` only when every successful row is proven complete.
- **Status:** errors only → `ERROR`; errors mixed with results → `INSUFFICIENT_EVIDENCE`; any
  in-progress, sample-only, protected or cache-miss row, or completeness other than `COMPLETE` →
  `INSUFFICIENT_EVIDENCE`; otherwise empty success only → `NO_FINDING`; rows returned → `SUCCESS`.
- **Caveats:** `quality` gets `sample_only`, `total_inferred`; `warnings` gets
  `protected_omissions`, `cache_miss`, `in_progress_rows` (or `no_evidence_rows` when there is no
  evidence); `applicability` gets `associational` and `not_tested_causal` when results exist and no
  method is known; `conflicts` gets `empty_success_under_incomplete` and
  `rows_under_unproven_completeness`.

Analysis assessments recorded on the task state (by `analyze_cached_result`) are merged: status
takes the more conservative rank (`ERROR` > `INSUFFICIENT_EVIDENCE` > `NO_FINDING` > `SUCCESS`),
completeness the more conservative value, `n` the maximum, and caveat lists and cache ids are
unioned. When there are no evidence rows, the analysis assessments alone are folded, preserving
their computed status.

Consumers:

- The model-facing task-state block (`AgentTaskStateRenderer`) ends with
  `EvidenceAssessment: status=<status> completeness=<completeness> n=<n>`.
- The analysis envelope embeds an `EvidenceAssessment` as its evidence body
  ([`time-quality-join.md`](./time-quality-join.md) §3).

The cached-tabular `insightEnvelope` ([`../../../CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md) §3)
is a separate, unrelated shape.

### B6. Answer evidence (EG5)

`EvidenceAssessment` and the task evidence rows are the server-authored, model-facing statement of
status, completeness, applicability, warnings and conflicts. Parler does not classify, correct,
warn on or block the model's final prose by language-specific pattern matching. Tool, protection,
cache, schema, wire, analysis and egress controls remain authoritative at their structured
boundaries.

### B7. Governance alignment (G20, EG6)

Part B aligns existing decision points and adds no new security layer:

- **HITL and RBAC.** Exact-proposal confirmation stays as it is, and ThingWorx re-checks
  authorization at execution. A cache handle, prior resolution, approval or audit event grants no
  permission.
- **Egress.** `ToolResultEgressGateway` keeps the typed error fields (`category`, `reason`,
  `retryable`, `retryBudgetKey`, `recoveryActions`, `evidenceStillUsable`) through last-resort
  compaction of tool results sent to the model.
- **PASSWORD.** The rule is unchanged: typed PASSWORD content is rejected before any cache-file
  creation. ThingWorx administrators own FileRepository permissions, placement, encryption, backup
  and retention.
- **Audit.** Blocks are recorded through the existing `ParlerProtectionAudit`,
  `ParlerHitlAuditLog`, `AgentMessageStreamAppender` and operational logs with bounded ids. Complete
  prompts, raw rows, credentials, PASSWORD values and unrestricted exception text are not written to
  audit projections.

There is no action-risk enum in this layer; extended-tool risk is App-declared capability metadata
([`service-provider-resilience.md`](./service-provider-resilience.md) §5.1).

### B8. Invariants

1. Existing public error codes are the primary compatibility identities; typed fields are additive.
2. Java maps codes to recovery; presentation messages are never classifiers.
3. Every automatic retry path has a key, a finite allowance, a decrement point and a terminal
   outcome; a repaired call cannot reset its own allowance.
4. `NO_FINDING` requires completed work over sufficient, proven-complete evidence.
   `INSUFFICIENT_EVIDENCE` is not narrated as no finding, and `ERROR` is not partial success.
5. Partial, unknown, sampled, blocked or protected evidence is never silently upgraded.
6. Core does not invent a universal confidence percentage; method-specific support and coverage
   stay explicit.
7. Retry and audit correlation use the current `RunInvocationContext`; a restart clears it.

### B9. Tests and rollback

Tests live beside the packages: `com.thingworx.things.agent.recovery.*` (including
`U3eM0VocabularyLockTest`, which locks the recovery action set, the status names and the sole
completeness enum) and `com.thingworx.things.agent.evidence.*`. Run the full suite with:

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

There is no feature flag. Rolling back means installing the previous compatible Extension and
restarting ThingWorx; the retry ledger and the cache are in-memory or restart-empty, so no migration
is needed.
