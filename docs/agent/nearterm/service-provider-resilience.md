# ThingWorx Service Capability Metadata and LLM Provider Resilience

This document covers two related areas of `parler-agent`:

1. **G13 — Service capability metadata.** Optional descriptor fields on entries of the existing
   extended-tool registry (`/tools/extended_tools.json`). They declare purpose, risk, dry-run,
   idempotency, data classification, admission and enablement for a governed ThingWorx Service, and
   the runtime applies them to model advertisement, HITL, Playbook eligibility, execution and egress.
2. **G16 — LLM Provider resilience.** Typed Provider failure classes, bounded same-Provider retry
   planning, route profiles, eligibility checks, a per-Provider circuit breaker and a deterministic
   degraded outcome.

"Service Provider" does not mean a connector product. External systems stay integrated inside
ThingWorx and reach Parler only through governed ThingWorx Services.

Code comments refer to this area as U7 and cite the labels `D2`–`D14` and the section numbers used
below.

Operator summary: [`service-provider-resilience-operator-runbook.md`](./service-provider-resilience-operator-runbook.md).
Related: [`../CUSTOMIZED-TOOLS.md`](../CUSTOMIZED-TOOLS.md),
[`../configuration-repository.md`](../configuration-repository.md),
[`../invoke_service_design.md`](../invoke_service_design.md),
[`../playbook-app-tool-workflows.md`](../playbook-app-tool-workflows.md),
[`../llm-api-provider.md`](../llm-api-provider.md), [`../rate-control.md`](../rate-control.md).

## 1. Current wiring

| Area | State |
|---|---|
| G13 descriptor parse and validation at registry load | active |
| G13 model advertisement, HITL, Playbook eligibility, execution block, dry-run enforcement | active |
| G13 `dataClassification` stamped on model-bound tool results | active |
| G13 fields on `GetAgentRuntimeSnapshot` and the Playbook runtime snapshot | active |
| G16 failure classifier, retry planner, route-profile parser, eligibility, circuit breaker, fallback planner, degraded outcome | implemented as a library in `com.thingworx.things.agent.llm` and covered by offline tests |
| G16 on the live request path (`AgentLoop`, Playbook final summary) | **not wired**: each request uses the Agent's single `llmApiProviderRef` Provider with its existing rate gate; no retry, fallback or circuit decision is taken |
| `/providers/route_profiles.json` | path reserved (`ConfigurationRepositoryPaths.PROVIDER_ROUTE_PROFILES`); the file is **not read** at runtime |
| Provider eligibility metadata | no runtime source; `LlmApiProviderDirectory` reports `contextWindowTokens=0` |

Sections 7.1–7.5 therefore describe the behavior of the G16 components themselves, which is what
tests verify; they are not yet observable in a live conversation.

## 2. Boundaries (D16)

- No connector SDK, MCP, A2A, HTTP proxy, arbitrary URL invocation, credential broker, or direct
  CMMS/ERP/email client in Parler.
- ThingWorx RBAC stays the authorization source. Capability metadata never grants permission; every
  invocation runs under the caller's current `SecurityContext`.
- No second Service registry (D2): G13 extends `ExtendedToolDefinition`, `ExtendedToolsManifest` and
  `ExtendedToolRegistrySnapshot`. There is no `ServiceCapabilityRegistry`.
- No second rate gate: token, request and concurrency control stay on the Provider-owned
  `LLMAPIProviderRateGate`.
- No approval-workflow, compensation, Service cost or latency fields (see §6.1 forbidden keys), no
  monetary budget or cost-based routing, no durable run or action store.
- No cache-internal access; no change to source/tool/cache row limits.
- Circuit state is local to the JVM; there is no cross-node coordination.

## 3. Configuration files

| Path | Content | Loaded |
|---|---|---|
| `/tools/extended_tools.json` | extended tools, root `version: 1`, with optional G13 keys per entry | yes, with the prompt-context cache |
| `/providers/route_profiles.json` | G16 route profiles (§7.1) | no (reserved) |

## 4. Existing paths this builds on

- **Extended tools.** An entry already declares `name`, `title`, `whenToUse`,
  `target.entityName`/`serviceName`, `hitl` (default `true`), `playbookSafe` (default `false`) and
  `executorOnly`. The manifest resolves the target Thing and effective `ServiceDefinition`, rejects
  PASSWORD exposure, and generates the tool schema. Execution goes through
  `executeExtendedToolOnThing` under the caller's `SecurityContext` (see
  [`../CUSTOMIZED-TOOLS.md`](../CUSTOMIZED-TOOLS.md) and
  [`../invoke_service_design.md`](../invoke_service_design.md)).
- **LLM Providers.** `LLMAPIProviderResolver` resolves the Agent's `llmApiProviderRef` to one enabled
  Provider Thing; `ProviderLlmClientBridge` applies request options and the Provider's
  `LLMAPIProviderRateGate` before the client call. Clients are synchronous, so no partial assistant or
  tool output is emitted before a call returns (see [`../llm-api-provider.md`](../llm-api-provider.md)
  and [`../rate-control.md`](../rate-control.md)).

## 5. G13 capability rules

### 5.1 Rules

1. **ThingWorx is the integration plane.** A descriptor points to one concrete Thing and Service.
   External authentication, API calls, retries and domain transactions stay inside that Service.
2. **Metadata is policy, not authority.** Availability at refresh does not mean a user may execute
   later; ThingWorx checks permission at invocation.
3. **Execution context is consumed, not invented.** Service calls use the current
   `RunInvocationContext`; no descriptor or App value mints or replaces it.
4. **Risk is App-declared and fails closed (D4).** ThingWorx `ServiceDefinition` parity proves
   target, parameters and result shape, not whether a Service mutates anything, so any entry with
   capability fields must declare `risk` as `READ_ONLY`, `MUTATING`, `DESTRUCTIVE` or `ADMIN`.
   A missing or invalid `risk` skips the entry.
5. **Exact ServiceDefinition parity.** Declared dry-run and idempotency parameters must exist on the
   Service, and a declared input schema digest must match the live one (§6.2). A mismatch skips the
   entry; the model is never asked to guess.
6. **HITL confirms the exact proposal and never replaces RBAC.** `MUTATING` capabilities always
   require HITL.
7. **Idempotency is declared metadata.** `NONE`, `CALLER_KEY` (with the key parameter) or
   `PLATFORM_NATIVE`. It is validated at load and reported on snapshots; the runtime does not retry
   extended-tool calls on its basis.
8. **Dry run is enforced on execute.** When `dryRun.supported=true`, the named Service parameter must
   be a boolean. On execution a missing value is injected as `true`; a non-boolean value refuses the
   call with `CAPABILITY_POLICY_BLOCKED`; an explicit boolean is kept. This applies to direct model
   dispatch and to HITL-approved execution.
9. **One runtime snapshot.** Model tools, Playbooks, HITL, egress and diagnostics read the same
   immutable `ExtendedToolRegistrySnapshot`.
10. **No resident-tool shortcut (D7).** Registering a descriptor never creates an always-advertised
    tool. Registered Services stay executor-only, admission-controlled, or advertised on the same
    terms as any extended tool.

### 5.2 `playbookSafe` (D5)

`playbookSafe` keeps its existing meaning and is not authorization. It defaults to `false`. An
extended tool is effectively Playbook-safe only when `playbookSafe:true` **and** `hitl:false`;
`playbookSafe:true` with `hitl:true` (or with `hitl` omitted, which means HITL) is ineligible and
logged. On top of that, a capability-bearing entry is Playbook-eligible only when it is enabled and
its risk is `READ_ONLY` (§6.3). Even an eligible Playbook binding re-resolves the target and runs
under current RBAC.

## 6. G13 capability contract

### 6.1 Descriptor (D3)

G13 keys are additive optional keys on an extended-tool entry under root `version: 1`. The wire
shape keeps the existing `name` and `target.entityName`:

```json
{
  "version": 1,
  "tools": [
    {
      "name": "create_maintenance_work_order",
      "title": "Create maintenance work order",
      "whenToUse": "Propose one CMMS work order (MUTATING; dry-run by default).",
      "target": { "entityName": "PlantCMMSIntegration", "serviceName": "CreateWorkOrder" },
      "hitl": true,
      "playbookSafe": false,
      "risk": "MUTATING",
      "purpose": "Create one governed CMMS work order through ThingWorx.",
      "capabilityVersion": "1",
      "dryRun": { "supported": true, "parameter": "dryRun" },
      "idempotency": { "mode": "CALLER_KEY", "parameter": "requestId" },
      "dataClassification": ["INTERNAL"],
      "admission": "LAZY",
      "enabled": true,
      "inputSemantics": { "schemaDigest": "<sha-256 hex>", "profileRef": "..." },
      "outputSemantics": { "schemaDigest": "...", "profileRef": "...", "businessErrors": ["DUPLICATE_ORDER"] }
    }
  ]
}
```

| Key | Rule | Runtime consumer |
|---|---|---|
| `risk` | required once any capability key is present; `READ_ONLY` \| `MUTATING` \| `DESTRUCTIVE` \| `ADMIN` | advertisement, HITL, Playbook, execution block |
| `purpose` | optional string | snapshot |
| `capabilityVersion` | optional string; an entry-level string `version` is accepted as a fallback | snapshot |
| `dryRun` | object; `supported` boolean required; `parameter` required when `supported=true` | dry-run enforcement (§5.1 item 8) |
| `idempotency` | object; `mode` `NONE` \| `CALLER_KEY` \| `PLATFORM_NATIVE`; `parameter` required for `CALLER_KEY`, forbidden for `NONE` | load validation, snapshots |
| `dataClassification` | non-empty array of non-empty strings | egress stamp (§6.4), snapshots |
| `admission` | `OFF` \| `NARROW` \| `LAZY` \| `DEFAULT` | `OFF` hides the tool from the model; the other values currently behave alike |
| `enabled` | boolean, default `true` | advertisement, Playbook, execution block |
| `inputSemantics.schemaDigest` | optional; must equal the live input digest | load validation (schema drift) |
| `inputSemantics.profileRef`, `outputSemantics.schemaDigest`, `outputSemantics.profileRef` | optional strings; recorded, not validated | — |
| `outputSemantics.businessErrors` | array of strings | reported on snapshots |

Presence of any of `risk`, `purpose`, `capabilityVersion`, `dryRun`, `idempotency`,
`dataClassification`, `admission`, `inputSemantics`, `outputSemantics` or `enabled` makes the entry
capability-bearing. Entries with none of them are legacy entries and keep the pre-G13 behavior
(`hitl`, `playbookSafe`, `executorOnly`).

A present key with the wrong JSON type skips the entry (it is never treated as absent). Unknown
entry keys are ignored. These keys are **forbidden** and skip the entry: `approvalWorkflow`,
`compensation`, `serviceCost`, `latencyClass`, `costClass`.

A descriptor contains no credentials, URL secrets, raw prompts or scripts.

### 6.2 Validation and refresh (D6)

At each registry load the manifest is validated in this order:

1. package structure (readable file, root `version: 1`, `tools` array); a structural defect
   invalidates the whole file under the existing configuration-repository policy;
2. per entry: name, `whenToUse`, `target`, `hitl`/`playbookSafe`/`executorOnly` types, name
   collisions with built-ins or other entries;
3. G13 JSON-only checks (types, enums, forbidden keys, required sub-fields);
4. target Thing resolution, PASSWORD protection, and Service parameter lookup;
5. G13 ServiceDefinition checks: the `dryRun.parameter` and `CALLER_KEY` parameter exist on the
   Service; `inputSemantics.schemaDigest`, when given, equals the live digest, else the entry is
   skipped with `SCHEMA_DRIFT: inputSemantics.schemaDigest mismatch`;
6. tool-schema generation.

The live input digest is the SHA-256 hex of the Service parameters written as `name:BASETYPE`,
sorted, and joined with `|`.

One invalid independent entry is skipped with a logged reason and never invalidates sibling
entries. The resulting registry snapshot is immutable and replaced atomically.

### 6.3 Runtime states and gates

`ServiceCapabilityRuntimeState` values: `ACTIVE`, `DISABLED`, `INVALID`, `TARGET_MISSING`,
`SCHEMA_DRIFT`, `POLICY_BLOCKED`. Loaded entries are `ACTIVE` or `DISABLED` (from `enabled`);
entries that fail validation are not in the snapshot. Request permission is never cached as
globally active.

`ServiceCapabilityRuntimePolicy` gates for a capability-bearing entry:

| Risk / state | Model-advertised | HITL | Playbook-eligible | Execution |
|---|---|---|---|---|
| `READ_ONLY` | yes, unless `admission:OFF` or `executorOnly` | per `hitl` | yes, if effectively `playbookSafe` | allowed |
| `MUTATING` | yes, unless `admission:OFF` or `executorOnly` | always | no | allowed after approval |
| `DESTRUCTIVE`, `ADMIN` | no | — | no | refused |
| `enabled:false` | no | — | no | refused |

Direct dispatch evaluates the block **before** any HITL enqueue, so a disabled, destructive or admin
capability never becomes an approval-pending action. A refused call returns typed error JSON:

```json
{
  "status": "error",
  "code": "CAPABILITY_POLICY_BLOCKED",
  "message": "Extended tool blocked by capability policy: <reason>",
  "category": "AUTHORIZATION",
  "reason": "<reason>",
  "retryable": false,
  "evidenceStillUsable": true,
  "recoveryActions": []
}
```

and records `ParlerProtectionAudit.blocked(CAPABILITY_POLICY_BLOCKED, tool, reason)`. Reasons
include `capability disabled`, `DESTRUCTIVE/ADMIN capability blocked (no G20 admit path)`, and
dry-run enforcement failures.

When a HITL approval executes an extended tool, the tool is re-resolved from the active registry.
If it is no longer registered, execution fails closed with `CAPABILITY_POLICY_BLOCKED` (`extended
tool not in active registry; cannot reapply capability policy`) instead of calling the raw target
Service, so dry-run and block rules cannot be bypassed.

Playbook execution refuses a tool that is not Playbook-eligible with `PLAYBOOK_TOOL_NOT_PLAYBOOK_SAFE`,
and a blocked capability with `CAPABILITY_POLICY_BLOCKED`. Playbook tool definitions are merged with
`playbookSafe=false` for ineligible capability entries.

### 6.4 Egress and diagnostics

When a tool result is compacted for the model (`ToolResultEgressGateway.compactForLlmAppend`), the
`dataClassification` declared for that extended tool is stamped onto JSON object results as
`_egress.dataClassification`.

`GetAgentRuntimeSnapshot` lists each extended tool under `tools.extended[]` with `name`,
`target.resolvedEntityName`, `target.serviceName`, effective `hitl`, `executorOnly`,
`modelAdvertisable`, `playbookEligible`, and, for capability-bearing entries, `risk`, `admission`,
`enabled`, `runtimeState`, `dryRunSupported`, `idempotencyMode`, `dataClassification` and
`businessErrors`. The Playbook runtime snapshot (`playbookRuntime.extendedTools[]`) lists only
Playbook-eligible extended tools, with `name`, `playbookSafe`, `hitl`, `executorOnly`, `risk`,
`dryRunSupported` and `idempotencyMode`.

## 7. G16 Provider resilience components

These components are implemented and tested but not called from the live request path (§1).

### 7.1 Route profile (D8)

`ProviderRouteProfilesParser` accepts either a JSON array of profiles or `{ "profiles": [ ... ] }`:

```json
{
  "profiles": [
    {
      "id": "interactive-industrial-tools-v1",
      "providers": ["PrimaryProviderThing", "FallbackProviderThing"],
      "requiredCapabilities": ["TOOLS", "STRICT_JSON_SCHEMA", "HITL_CONTINUATION"],
      "allowedDataClassifications": ["PUBLIC", "INTERNAL"],
      "maxSameProviderRetries": 1,
      "maxProvidersTried": 2,
      "maxTotalAttempts": 3,
      "maxCumulativeWaitMs": 30000,
      "maxWallTimeMs": 60000,
      "qualityTier": "APPROVED_INTERACTIVE",
      "fallbackVisible": true
    }
  ]
}
```

| Field | Rule | Default |
|---|---|---|
| `id` | required, unique | — |
| `providers` | required non-empty ordered list of Provider Thing names | — |
| `requiredCapabilities` | tokens from §7.6 (D13) | none |
| `allowedDataClassifications` | strings; empty means no route-level constraint | empty |
| `maxSameProviderRetries` | ≥ 0 | 1 |
| `maxProvidersTried` | ≥ 1 | number of providers |
| `maxTotalAttempts` | ≥ 1 | 3 |
| `maxCumulativeWaitMs` | > 0 | 30000 |
| `maxWallTimeMs` | > 0 | 60000 |
| `qualityTier` | string | empty |
| `fallbackVisible` | boolean | true |

A profile containing any of the keys `apiKey`, `api_key`, `credential`, `credentials`, `password`,
`secret`, `endpoint`, `baseUrl`, `base_url`, `url`, `authorization` (case-insensitive) is skipped.
An invalid, duplicate or non-object profile is skipped with a diagnostic; valid siblings remain.
Non-positive budget ceilings are rejected (there is no "0 means unlimited").

### 7.2 Failure classes (D9)

`LlmProviderFailureClassifier` maps a typed `LlmProviderFailureSignal` (never message text alone):

| Class | Same-Provider retry | Fallback | Signal |
|---|---|---|---|
| `RATE_LIMITED` | yes, bounded | yes | rate-limited flag or HTTP 429 |
| `TIMEOUT_BEFORE_RESPONSE` | yes, bounded | yes | timeout before any output, or HTTP 408 |
| `TRANSIENT_UPSTREAM` | yes, bounded | yes | HTTP 5xx |
| `MODEL_UNAVAILABLE` | no | yes | HTTP 404 |
| `AUTHENTICATION_OR_CONFIG` | no | no | Provider resolve error, HTTP 401/403 |
| `INVALID_REQUEST_OR_SCHEMA` | no | no | HTTP 400/422 |
| `POLICY_OR_EGRESS_BLOCKED` | no | only to an eligible route | policy/egress block flag |
| `PARTIAL_OR_UNKNOWN_OUTCOME` | no | no | output already accepted, no signal, no status, or an unmapped status |
| `CANCELLED` | no | no | cancellation flag |

Precedence: cancellation, then accepted output, then policy block, then resolve error, then the
rate-limit and timeout flags, then HTTP status. Once assistant text, a tool call or an ambiguous
partial response has been accepted, no other Provider may regenerate that round.

### 7.3 Retry planning (D10)

`ProviderRouteCoordinator.planSameProviderRetry` returns `RETRY_SAME` (with a delay),
`FALLBACK_CANDIDATE`, `STOP_TERMINAL` or `STOP_EXHAUSTED`:

1. Cancellation → `STOP_TERMINAL`.
2. A class that is not same-Provider-retryable → `FALLBACK_CANDIDATE` if fallback-eligible,
   otherwise `STOP_TERMINAL`.
3. No attempts or wall time left → `STOP_EXHAUSTED`.
4. A `Retry-After` hint (seconds or HTTP date, from allow-listed safe headers) larger than the
   remaining wait/wall budget, a reached same-Provider retry cap, or a backoff that does not fit →
   `FALLBACK_CANDIDATE` when the class allows fallback and attempts, wall time and wait time remain;
   otherwise `STOP_EXHAUSTED`. A `Retry-After` is never shortened into an early retry.
5. Otherwise `RETRY_SAME` after exponential backoff with bounded full jitter: base 200 ms, doubling
   per retry, capped at 8000 ms; delay is uniform in `[0, exp]`, at least the `Retry-After` hint,
   and at most the remaining budget.

`ProviderRetryBudget` tracks attempts, same-Provider retries, cumulative wait (Provider rate-gate
waits count toward it) and wall time from the route profile. Randomness comes from an injected
`[0,1)` supplier and affects delay only, never Provider order, capability selection, evidence or
numbers. Each plan carries a sanitized telemetry token
`LLM_PROVIDER_RETRY class=<class> delayMs=<n>`.

### 7.4 Circuit breaker (D11)

`ProviderCircuitBreaker` keeps one entry per Provider Thing name in the current JVM:

```text
CLOSED --5 transient failures--> OPEN
OPEN --30 s cooldown elapsed, next admit--> HALF_OPEN (one probe admitted)
HALF_OPEN --probe success--> CLOSED
HALF_OPEN --failure--> OPEN
```

- Transient classes (`RATE_LIMITED`, `TIMEOUT_BEFORE_RESPONSE`, `TRANSIENT_UPSTREAM`,
  `MODEL_UNAVAILABLE`) advance the counter. Defaults: threshold 5, cooldown 30 000 ms
  (`ProviderCircuitDefaults`).
- `AUTHENTICATION_OR_CONFIG` and `INVALID_REQUEST_OR_SCHEMA` mark the Provider config-ineligible
  and hold it `OPEN` until the breaker is reset (a recorded success or a new breaker instance, for
  example after restart); they do not churn the transient counter.
- `CANCELLED`, `PARTIAL_OR_UNKNOWN_OUTCOME` and `POLICY_OR_EGRESS_BLOCKED` do not count; in
  `HALF_OPEN` they reopen the circuit.
- Only one half-open probe is admitted at a time; concurrent probes are denied.
- `operatorView` returns `providerThingName`, `state`, `transientFailures`, `configIneligible`,
  `openUntilEpochMs` (`-1` when held open for configuration) and `halfOpenProbeInFlight`.
- The breaker does not implement rate limiting; that stays on the rate gate.

### 7.5 Fallback selection and degraded outcome (D12, D14)

`ProviderRouteCoordinator.planFallback` returns `TRY_PROVIDER`, `DEGRADED` or `FORBIDDEN`:

- `FORBIDDEN` when output was already accepted or the last failure is
  `PARTIAL_OR_UNKNOWN_OUTCOME`, `CANCELLED`, or any class that does not allow fallback.
- `DEGRADED` when the route budget is exhausted, `maxProvidersTried` is reached, or no untried
  Provider passes directory lookup, config eligibility, the §7.6 checks and circuit admission.
- Otherwise `TRY_PROVIDER` with the next eligible Provider in profile order; skipped Providers and
  their reasons are recorded. `explainIneligibility` returns the per-Provider reason map.

`ProviderDegradedOutcome` (code `PROVIDER_ROUTE_EXHAUSTED`) states that the route was exhausted
(naming the last failure class), that completed deterministic evidence is preserved when available,
and that no new interpretation or actions were invented. It carries `fallbackVisible`,
`evidenceStillUsable`, `safeNextActions` (`retry_later`, `contact_operator`), `triedProviders` and
`lastFailureClass`. It never creates tool calls, action proposals, causal claims or new numbers.

### 7.6 Eligibility (D12, D13)

`ProviderRouteEligibility.ineligibilityReason` checks a Provider against the profile and the round
requirements before each attempt. Frozen v1 capability tokens:

| Token | Meaning |
|---|---|
| `TOOLS` | tool calling for this round |
| `STRICT_JSON_SCHEMA` | accepts the strict tool/JSON schema the round needs |
| `HITL_CONTINUATION` | can continue a HITL-paused turn |
| `CONTEXT_WINDOW` | effective context window covers the request |
| `DATA_EGRESS_REGION` | region/classification satisfies the round's policy |
| `STRUCTURED_OUTPUT` | structured/strict output controls |

Checks, fail-closed where metadata is missing:

| Reason | Condition |
|---|---|
| `provider_disabled` | Provider disabled |
| `quality_tier_missing` / `quality_tier_mismatch` | profile names a tier and the Provider has none or a different one |
| `capability_missing:<TOKEN>` | a required token (profile ∪ round; `HITL_CONTINUATION`, `CONTEXT_WINDOW`, `DATA_EGRESS_REGION` are added when the round needs them) is not offered |
| `context_window_insufficient` | the Provider's context window is `0` or smaller than the tokens needed |
| `profile_data_classification_denied` | the profile allowlist is non-empty and excludes the round classification |
| `provider_data_classification_missing` / `provider_data_classification_denied` | the Provider declares no classification allowlist, or excludes the round classification |
| `egress_region_missing` / `egress_region_denied` | the round needs a region and the Provider declares none, or excludes it |

An empty Provider allowlist never means "accept any".

## 8. App Developer and operator responsibilities

**App Developer**

- Integrate CMMS/EAM/ERP/email/historian systems as normal ThingWorx Things and Services with
  typed DataShapes and stable business error names.
- Author G13 keys on extended-tool entries: `risk` always, plus dry-run, idempotency,
  classification, admission and enablement as needed.
- Bind Playbooks to tool names, never to URLs, credentials or cache paths.

**Operator**

- Configure Provider Things and their credentials in Composer, as today.
- Disable a capability with `enabled:false`, or hide it from the model with `admission:OFF`.
- Read `GetAgentRuntimeSnapshot` for effective risk, HITL, dry-run, classification and admission.

Neither role may put secrets in descriptor files, bypass the current `SecurityContext`, or register
arbitrary URLs.

## 9. Code map

| Concern | Classes |
|---|---|
| G13 descriptor | `configrepo.ServiceCapabilityDescriptorParser`, `ServiceCapabilityMetadata`, `ServiceCapabilityManifestPolicy`, `ServiceCapabilityRisk`, `ServiceCapabilityAdmission`, `ServiceIdempotencyMode`, `ServiceCapabilityRuntimeState`, `ServiceDefinitionParameterLookup` |
| G13 registry | `configrepo.ExtendedToolsManifest`, `ExtendedToolDefinition.capability()`, `ExtendedToolRegistrySnapshot` |
| G13 runtime | `configrepo.ServiceCapabilityRuntimePolicy`, `ServiceCapabilityDryRunEnforce`; `AgentThing` dispatch, HITL approve and Playbook paths; `playbook.PlaybookToolDefinitionsMerge`; `ToolResultEgressGateway` |
| Demo descriptors | `configrepo.U7DemoCapabilityProfiles` (one `READ_ONLY` and one `MUTATING`/HITL/dry-run entry, and a route-profile fixture) |
| G16 | `llm.LlmProviderFailureClass`, `LlmProviderFailureSignal`, `LlmProviderFailureClassifier`, `ProviderRouteCoordinator`, `ProviderRetryBudget`, `ProviderRetryBackoff`, `ProviderSameProviderRetryPlan`, `ProviderRouteProfile`, `ProviderRouteProfilesParser`, `ProviderRoundRequirements`, `ProviderEligibilityView`, `ProviderRouteEligibility`, `ProviderCapabilityToken`, `ProviderCircuitBreaker`, `ProviderCircuitState`, `ProviderCircuitDefaults`, `ProviderFallbackPlan`, `ProviderDegradedOutcome` |

## 10. Tests

Offline tests cover descriptor parsing and ServiceDefinition parity
(`ServiceCapabilityDescriptorParserTest`), runtime gates including block-before-HITL
(`ServiceCapabilityRuntimePolicyTest`), dry-run enforcement (`ServiceCapabilityDryRunEnforceTest`),
the demo App/Provider matrix (`Spr5AppProviderMatrixTest`), the failure-class table
(`LlmProviderFailureClassifierTest`), retry planning (`ProviderSameProviderRetryPlannerTest`),
route profiles (`ProviderRouteProfilesParserTest`), circuit transitions (`ProviderCircuitBreakerTest`),
fallback planning (`ProviderFallbackPlannerTest`), fail-closed eligibility
(`ProviderRouteEligibilityFailClosedTest`) and budget bounds (`ProviderRetryBudgetBoundsTest`).

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

Live destructive or admin Services are never invoked to prove a test.

## 11. Disable and rollback

- Per capability: `enabled:false` refuses execution and removes advertisement; `admission:OFF`
  removes model advertisement only. Removing all G13 keys from an entry returns it to legacy
  behavior.
- A failing refresh skips only the bad entries; a structurally broken file follows the
  configuration-repository package policy. Refresh never falls back to arbitrary discovery or URLs.
- Provider routing is unchanged by G16 today; every request uses the primary `llmApiProviderRef`
  Provider and its rate gate.
- No data migration is involved; circuit state is in memory and resets on restart.
