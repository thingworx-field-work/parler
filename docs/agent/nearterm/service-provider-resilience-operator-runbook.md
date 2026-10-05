# Service capability metadata and Provider resilience — operator runbook

Short operator notes for G13 capability descriptors and the G16 Provider resilience components.
Full behavior: [`service-provider-resilience.md`](./service-provider-resilience.md).

## Capability descriptors (G13)

- Author entries in FileRepository `/tools/extended_tools.json` (root `version: 1`).
- Any entry that uses capability keys MUST declare `risk` (`READ_ONLY` | `MUTATING` | `DESTRUCTIVE` |
  `ADMIN`); otherwise the entry is skipped at load.
- `MUTATING` always requires HITL and is never Playbook-eligible.
- `DESTRUCTIVE` / `ADMIN` are refused with `CAPABILITY_POLICY_BLOCKED` before any HITL request is
  created; they are also hidden from the model.
- When `dryRun.supported=true`, name the Service's boolean parameter. On execution a missing value is
  set to `true`; a non-boolean value refuses the call.
- `dataClassification` is stamped onto model-bound tool results as `_egress.dataClassification`.
- `playbookSafe` defaults to `false`; it takes effect only with an explicit `true` **and** `hitl:false`.
- Keys `approvalWorkflow`, `compensation`, `serviceCost`, `latencyClass`, `costClass` are forbidden
  and skip the entry.
- A refresh skips bad independent entries and keeps valid siblings; a structurally broken file
  follows the configuration-repository package policy.
- If an approved HITL action's tool is no longer in the active registry, execution is refused with
  `CAPABILITY_POLICY_BLOCKED`.

## Provider routes (G16)

- The live Agent uses one Provider: the Thing named by `llmApiProviderRef`, with its own rate gate
  (`LLMAPIProviderRateGate`). There is no automatic retry, fallback or circuit breaking on the live
  request path.
- `/providers/route_profiles.json` is reserved but not read. Editing it has no effect.
- The G16 classifier, retry planner, route-profile parser, eligibility checks, circuit breaker and
  degraded outcome (`PROVIDER_ROUTE_EXHAUSTED`) exist as tested components only.

## Disable / rollback

- Disable a capability with `enabled:false`; hide it from the model only with `admission:OFF`.
- Removing all capability keys from an entry returns it to legacy extended-tool behavior.
- No data migration is involved.

## Diagnostics

- `GetAgentRuntimeSnapshot` → `tools.extended[]` shows effective `hitl`, `modelAdvertisable`,
  `playbookEligible`, `risk`, `admission`, `enabled`, `runtimeState`, `dryRunSupported`,
  `idempotencyMode`, `dataClassification` and `businessErrors`.
- Blocked capability calls are recorded by `ParlerProtectionAudit` with code
  `CAPABILITY_POLICY_BLOCKED`.
- Skipped extended-tool entries are logged with the reason (for example
  `SCHEMA_DRIFT: inputSemantics.schemaDigest mismatch`).
- Never log credentials or raw Provider bodies.
