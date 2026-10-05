# Fleet Benchmarking and Evidence-Ranked RCA

This document describes two deterministic components in `parler-agent`:

- **G5 — fleet/cohort benchmarking**: where an asset stands among comparable peers under the
  same window, unit, grain and method, with the focus asset always reported.
- **G7 — bounded root-cause investigation (RCA)**: an evidence-ranked queue of investigation
  candidates and next checks for an incident.

"RCA" here means ranked candidates and next checks. It never means automatic proof of cause.

This is a design and behavior description, not a normative contract. Wire contracts live under
[`CONTRACTS/`](../../../CONTRACTS/).

## Identifier map

Code comments and tests use these labels.

| Label | Meaning |
|---|---|
| U4 | Time/quality/join layer and the base `analysisEnvelope` ([`time-quality-join.md`](time-quality-join.md)) |
| U5 | Deterministic statistics kernel ([`deterministic-insight-kernel.md`](deterministic-insight-kernel.md)) |
| U6 | The components in this document |
| G5 / G7 | Fleet benchmarking / bounded investigation |
| FRC-0 … FRC-4 | Component groups: FRC-0 vocabulary and contracts (batch source, coverage, rank rules, catalog, ledger, budget); FRC-1 cohort collection and gates; FRC-2 distribution, rank and focus; FRC-3 investigation engine; FRC-4 App runners, demo adapters and reference driver |
| D2 … D14 | Design rules in §5.1 |

## 1. Purpose

Industrial users ask questions such as:

- "How does this compressor compare with comparable compressors under the same conditions?"
- "Which assets are most unusual, and where is my focus asset if it is not in the top N?"
- "What should we check first for yesterday's line stop?"
- "What supports or weakens each candidate, and what has not been searched?"

G5 computes peer position in one batch, preserves the focus asset, and discloses coverage without
leaking unauthorized members. G7 turns bounded statistical and operational evidence into an
auditable investigation queue. Neither treats association, temporal proximity or relation
adjacency as proof of cause.

## 2. Foundations and boundaries

### 2.1 Consumed components

Paths are under `parler-agent/src/main/java/com/thingworx/things/agent/`.

| Capability | Component | Use |
|---|---|---|
| Opaque cache I/O | `cache.ArtifactCache`, `ArtifactReader`, `ArtifactWriter`, `ArtifactAccessContext` | Only data path; no FileRepository path or index access |
| Source and budget | `source.SourceDescriptor` (+ `CompletenessStatus`), `execution.BudgetVector` | Completeness; `InvestigationBudget` composes the one shared vector |
| Evidence and status | `evidence.EvidenceStatus`, `EvidenceAssessment` | Status vocabulary for envelopes and assessments |
| Base envelope | `analysis.AnalysisEnvelope`, `AnalysisEnvelopeBuilder`, `AnalysisEnvelopeValidator`, `AnalysisEnvelopeJson` | G5 results extend it |
| Operation enum | `analysis.AnalysisOperation.FLEET_BENCHMARK` (class `FLEET`) | G5 operation id |
| U5 conventions | `analysis.stats.LinearPercentile` (`h=(n-1)p`), robust-z constant `0.67448975` | Median, quartiles, MAD and robust-z in G5 |

### 2.2 Cache boundary

Cohort and RCA code reads data only through the cache reader and publishes derived data only
through the cache writer. It never inspects cache files, paths or the JVM index, never creates a
second cache, and never treats a `cacheId` as a durable run identity. ThingWorx authorizes the
containing Service call; the caller supplies the authorized member set.

## 3. Scope

### 3.1 Implemented

- G5: cohort membership frozen per execution, paged batch collection under a shared budget,
  comparability and quality gates, authorized-only coverage, distribution statistics, competition
  rank, statistical percentile, robust z-score, focus-preserving top N, and a compact
  `analysisEnvelope`.
- G7: resolved incident anchors, a validated relation/event candidate catalog, bounded candidate
  resolution, batched event and analysis evidence, event co-occurrence, hypothesis scorecards,
  searched/unsearched scope, next checks, and a compact `EvidenceAssessment`.
- Batch-source and evidence-source interfaces for App adapters, demo adapters, and a reference
  driver that runs G5 and G7 in sequence.

### 3.2 Non-goals

- No causal inference, causal graph learning, root-cause proof, probability of cause, or
  autonomous diagnosis claim.
- No arbitrary graph traversal, unbounded candidate discovery, or model-generated property scans.
- No serial model tool calls across a fleet.
- No new anomaly/correlation/trend formulas; U5 conventions are reused.
- No fuzzy entity resolution: identities are resolved before execution.
- No durable run store, restart/resume, actions, work orders, property writes, approvals,
  scheduling, delivery or saved insights.
- No cache-internal access, second cache, cross-restart handle recovery or custom SQL.
- No change to the global 5,000-row tabular default and no silent cohort or sample truncation.

## 4. Invocation and packaging

G5 and G7 are in-process Java components. The model-visible tool surface is unchanged:

- `analyze_cached_result` does not list `fleet_benchmark`; it stays handle-only and U5-only.
- No resident tool, ThingWorx Service or Playbook node type invokes G5 or G7.
  `U6DemoAppProfiles.advertisedOperations()` is empty.

Java entry points:

| Entry point | Does |
|---|---|
| `fleet.U6FleetBenchmarkAppRunner.run(...)` | Collect (`CohortCollector`), distribute (`FleetDistributionEngine`), build the envelope (`U6FleetEnvelopeFactory`) and budget accounting |
| `investigation.U6RcaInvestigationAppRunner` | Run `BoundedInvestigationEngine` and build the compact RCA envelope (`U6RcaEnvelopeFactory`) |
| `playbook.U6ReferencePlaybookDriver` | Reference sequence `u6_fleet_rca_reference`: freeze cohort → G5 collect and distribute → G7 resolve, evidence, scorecard → compact envelopes |

The demo adapters `fleet.DemoPeerCohortBatchAdapter` (digest `u6-demo-fleet-peer-v1`) and
`investigation.DemoRcaEvidenceAdapter` (digest `u6-demo-rca-evidence-v1`) implement the adapter
interfaces over fixed rows. `U6DemoAppProfiles` names the two surfaces `fleet_benchmark` and
`rca_investigation`.

## 5. Design rules

1. **Cohorts are semantic, not name-derived.** Membership comes from authorized semantic ids and
   a peer profile; the model cannot create a cohort by guessing asset names.
2. **Membership freezes per execution.** `FrozenCohortMembership.freeze` sorts and de-duplicates
   the authorized ids and digests them with the peer profile, metric profile and window; mid-run
   changes do not alter denominators.
3. **Batch before fan-out.** Collection pages one batch source under the shared `BudgetVector`;
   the LLM never loops over members.
4. **Comparability is mandatory.** Unit, grain, window, method and blocking quality are checked
   before ranking; ineligible members are counted by reason.
5. **Permission does not become absence.** Unauthorized members make coverage partial; they are
   never labeled healthy, missing or no-data, and neither their names nor their count is
   disclosed.
6. **Focus preservation is explicit.** The focus asset is returned even outside the top N, with
   its actual position and a flag; the ranking is not distorted to include it.
7. **Ranking is deterministic.** Ties share a competition rank; the stable semantic id breaks
   presentation order only. Statistical percentile is independent of performance direction.
8. **No cause score.** `investigationPriority` orders next checks only.
9. **No-finding is not no-cause.** "No supported candidate in searched scope" is a valid result
   that still reports unsearched scope and next checks.
10. **Association remains association.** Co-occurrence, sequence, anomaly timing and relation
    distance never become causal language.
11. **No global 5,000.** Member, signal, event, relation, candidate, analysis-call, row and
    wall-time limits are independent and echoed.
12. **Shared contracts stay singular.** U6 extends the U4 envelope and validator and composes the
    shared `BudgetVector`; it defines no private ledger, envelope or semantic snapshot.

### 5.1 Rule index

| Id | Rule |
|---|---|
| D2 | `FLEET_BENCHMARK` (wire `fleet_benchmark`, class `FLEET`) extends `AnalysisEnvelope` through the builder/validator. A covered partial cohort may be `SUCCESS`; an empty comparable set is `NO_FINDING` with `NO_COMPARABLE_MEMBERS`. G7 uses no `AnalysisOperation` value. |
| D3 | Packaging: G5 is App/driver-invoked and not model-advertised; G7 is driver-only; no resident tool (§4). |
| D4 | Fleet position: competition rank `1 + count(strictly better)` under the profile direction; low-to-high percentile `100 * (count(lower) + 0.5*count(equal)) / n`, never relabeled by direction; median/quartiles/MAD/robust-z use U5 conventions; `MAD == 0` leaves robust-z undefined (`null`). |
| D5 | Coverage is reported for the authorized set only (§7.2). |
| D6 | Batch-first collection with explicit row and wall-time caps from the shared `BudgetVector` (§7.1). |
| D7 | Investigation priority uses bounded integer profile weights only when the profile enables them; otherwise lexicographic test order with zero contributions. Output never uses confidence, likelihood or probability wording (§7.4). |
| D8 | The candidate catalog supports exactly: typed asset relations bound to semantic ids with depth/node caps; candidate signal/KPI entries with optional business-state filters; governed event/maintenance/batch read-Service bindings with co-occurrence windows; deterministic ordering and fail-closed validation with no model-name fallback (§6.3). |
| D9 | The hypothesis ledger contains only what G7 outputs consume; it reserves no persistence fields (§6.4). |
| D10 | `InvestigationBudget` = shared `BudgetVector` + `InvestigationSearchLimits`; every cap and actual projects into envelope metrics and budget accounting (§6.2). |
| D13 | Freeze, focus, permission and no-cause semantics as in rules 2, 5, 6 and 9. |
| D14 | The non-goals in §3.2 are closed. |

## 6. Runtime and contract model

### 6.1 Fleet request and batch-source contract

`fleet.FleetBenchmarkRequest` carries `peerProfileId`, `metricProfileId`, `focusAssetId`,
`window` (`HalfOpenWindow`), `direction` (`HIGHER_IS_BETTER` | `LOWER_IS_BETTER`), `topN` (> 0),
`access` and `budget`. It never carries member rows.

`fleet.CohortBatchSource` is the adapter an App implements. Each call fetches one page for the
frozen membership digest, window, metric profile and page token, under the caller's access
context and budget. A page (`CohortBatchSourceResult`) carries:

- `rows`: `CohortBatchMemberRow` with `semanticAssetId`, `status`, `metricValue`, `unit`,
  `grain`, `methodId`, `reasonCode`;
- `completeness` (`COMPLETE` | `PARTIAL` | `UNKNOWN`), `hasMorePages`, `nextPageToken`;
- `permissionLimited`.

Member statuses (`CohortMemberStatus`):

| Status | Meaning |
|---|---|
| `ELIGIBLE_VALUE` | Authorized member with a finite metric that already passed the source's unit/grain/window/method checks |
| `NO_DATA` | Authorized member with no data in the window |
| `INSUFFICIENT_EVIDENCE` | Authorized member failed quality/support gates |
| `INCOMPARABLE` | Authorized member failed unit/grain/window/method comparability; sources must use this, not `ELIGIBLE_VALUE`, for mismatches |
| `ERROR` | Authorized member fetch or compute failed |
| `PERMISSION_LIMITED` | Not authorized under the current principal; the row omits identifying fields and only sets `permissionLimited` |

Sources must use this vocabulary; they may not invent statuses or report unauthorized members as
`NO_DATA`.

### 6.2 RCA request and budget

`investigation.RcaInvestigationRequest` carries an `IncidentAnchor` (`focusAssetId`, `eventId`,
`evidenceWindow`; a resolved identity, never free prose), `investigationProfileId`, `access`
and an `InvestigationBudget`.

`InvestigationSearchLimits` defaults:

| Limit | Default |
|---|---:|
| `relationDepth` | 2 |
| `relationNodes` | 32 |
| `candidateSignals` | 24 |
| `events` | 100 |
| `analysisCalls` | 16 |

All limits must be positive. `InvestigationBudget.searchLimitMetrics()` projects them into
envelope metrics; wall time, rows, bytes and the requested/effective/consumed/clamped accounting
stay on the shared `BudgetVector` and `AnalysisBudgetAccounting`.

### 6.3 Relation and event candidate catalog

`investigation.CandidateCatalog` has `catalogId`, `version`, `maxRelationDepth`,
`maxRelationNodes` and three entry lists:

| Entry | Fields |
|---|---|
| `CatalogRelationEntry` | `relationTypeId`, `fromAssetSemanticId`, `toAssetSemanticId`, `declaredDistance` |
| `CatalogSignalEntry` | `signalSemanticId`, `assetTypeKey`, `businessStateFilter` |
| `CatalogServiceBinding` | `kind` (`EVENT` \| `MAINTENANCE` \| `BATCH`), `thingName`, `serviceName`, `coOccurrenceWindowMillis` |

`CandidateCatalogValidator` rejects a catalog with no entries, a relation whose distance exceeds
`maxRelationDepth`, more relations than `maxRelationNodes`, and duplicate relations, signals or
bindings. Invalid data fails the path; it never falls back to guessing model names.

### 6.4 Hypothesis ledger

Each candidate produces a server-authored `HypothesisLedgerEntry`:

```json
{
  "candidateId": "signal:<id> | upstream:<assetId> | event:<Thing>.<Service> | ...",
  "candidateKind": "signal|event|maintenance|batch|upstream_asset",
  "statement": "bounded display text from registered metadata",
  "investigationPriority": 0,
  "priorityMeaning": "ORDER_FOR_NEXT_CHECKS_NOT_CAUSAL_PROBABILITY",
  "deprioritized": false,
  "supports": [{"evidenceRef": "...", "testId": "...", "contribution": 0}],
  "weakens": [{"evidenceRef": "...", "testId": "...", "contribution": 0}],
  "unknown": [{"testId": "...", "reason": "..."}],
  "nextChecks": [],
  "unsearchedScope": []
}
```

The entry rejects statements containing "confidence", "likelihood", "probability", "root-cause
score", "root cause score", "proves cause" or "causal probability". `deprioritized` marks a
candidate demoted by a blocking weakening test; the candidate is kept and ranked after ordinary
entries.

### 6.5 Result envelopes

**G5** (`U6FleetEnvelopeFactory`): an `AnalysisEnvelope` with operation `fleet_benchmark`, method
id `fleet_benchmark`, chart intent `u6.fleet_benchmark.distribution`, and metrics:

- coverage: `requestedAuthorizedN`, `returnedN`, `valuedN`, `comparableN`, `permissionLimited`,
  `batchSourcePartial`;
- distribution: `median`, `mad`, `q1`, `q3`, `zeroDispersion`;
- focus: `focusCompetitionRank`, `focusStatisticalPercentile`, `focusOutsideTopN`, and
  `focusMemberStatus` when an authorized focus asset did not enter the comparable set;
- `reasonCodes`, `budget.consumedRows`, `budget.consumedWallTimeMillis`, `budget.clamped`.

Per-member evidence (`FleetMemberEvidence`) and compact top-N/focus position rows
(`FleetPositionEvidence`, from `FleetBenchmarkResult.compactPositionRows()`) are typed rows ready
for derived-artifact publication. The validator allows fleet `SUCCESS` under `PARTIAL`/`UNKNOWN`
completeness because partial coverage is disclosed.

**G7** (`U6RcaEnvelopeFactory`): a compact envelope holding an `EvidenceAssessment` (method
`rca_investigation`, version `1`) with status, completeness, `n` = ledger size, applicability
`associational` and `not_tested_causal`, the reason codes as warnings, plus metrics
(`candidateN`, `supportedCandidateN`, `eventN`, `unsearchedN`, `deprioritizedN`, search limits,
budget, `outcomeCode`, `reasonCodes`).

## 7. Deterministic behavior

### 7.1 G5 — cohort resolution and metric collection

1. The caller resolves the peer profile, metric profile, focus identity and window.
2. `FrozenCohortMembership.freeze` fixes the authorized eligible set visible to the current
   principal and its opaque digest.
3. `CohortBatchFetcher` pages the batch source under that digest and enforces
   `BudgetVector.maxReturnedRows` and `maxWallTimeMillis` (`COHORT_BUDGET_EXCEEDED`). It
   propagates aggregate completeness.
4. `CohortGatePipeline` applies the `MetricComparabilitySpec` (expected unit, grain, method):
   mismatches become `INCOMPARABLE` (`UNIT_OR_GRAIN_OR_METHOD_MISMATCH`); a `MemberQualityGate`
   BLOCKING result demotes a valued member to `INSUFFICIENT_EVIDENCE` (`QUALITY_BLOCKING`); rows
   outside the frozen set are rejected (`NOT_IN_FROZEN_AUTHORIZED_SET`). The App runner uses
   `MemberQualityGate.allowAll()`.
5. The pipeline reconciles membership and builds per-member evidence rows before any position is
   computed. A duplicate member row fails with `COHORT_MEMBER_DUPLICATE`. When the source claims
   `COMPLETE`, every frozen authorized member must appear exactly once as a non-permission row, or
   collection fails with `COHORT_MEMBER_OMITTED`; this includes a frozen member returned as
   `PERMISSION_LIMITED`. Under `PARTIAL`/`UNKNOWN` a shortfall is reported as
   `batchSourcePartial`.

No member with a mismatched unit, grain or method enters the numeric distribution.

### 7.2 G5 — distribution, position and coverage

For the `n` comparable members:

- sort by metric, then stable semantic id;
- competition rank is `1 + count(strictly better)` under the direction; ties share a rank;
- statistical percentile is `100 * (count(lower) + 0.5*count(equal)) / n`, low to high;
- median and quartiles use the `h=(n-1)p` convention;
- robust z-score is `0.67448975 * (x - median) / MAD`; `MAD == 0` sets `zeroDispersion` and
  leaves robust-z `null`;
- top N follows direction then stable id; a comparable focus asset outside the top N is appended
  with `focusOutsideTopN = true`.

Status and reasons (`FleetDistributionEngine`):

| Condition | Result |
|---|---|
| Comparable set non-empty | `SUCCESS` |
| Comparable set empty | `NO_FINDING`, reason `NO_COMPARABLE_MEMBERS` |
| `permissionLimited` or `batchSourcePartial` | reason `COHORT_PARTIAL` |
| Focus asset outside the frozen authorized set | reason `FOCUS_NOT_IN_COHORT` |
| Focus authorized but not comparable | `focusMemberStatus` (`NO_DATA`, `INCOMPARABLE`, `INSUFFICIENT_EVIDENCE`, …), not `FOCUS_NOT_IN_COHORT` |

Coverage (`CohortCoverageCounts`) is reported over the authorized set only:

- `requestedAuthorizedN`, `returnedN` (≤ `requestedAuthorizedN`);
- `valuedN` (finite values from the source) and `comparableN` (after U6 gates, ≤ `valuedN`);
- exclusions `noDataN`, `incomparableN`, `insufficientEvidenceN`, `errorN`;
- the partition invariant `valuedN + noDataN + incomparableN + insufficientEvidenceN + errorN ==
  returnedN` (a demoted member moves out of `valuedN`);
- `permissionLimited` when the peer set could not be fully authorized.

There is no `unauthorizedN` or peer-set-size field, and no unauthorized name or id. When
coverage is partial, prose must say "among the authorized comparable members covered", never
"across the fleet" or "across the plant".

### 7.3 G7 — bounded investigation workflow

`BoundedInvestigationEngine.investigate` requires the profile id to match the request and runs:

1. If the evidence source reports cancellation: `ERROR` with `CANCELLED`.
2. `CandidateResolver` admits candidates from the catalog around the focus asset: related assets
   (`upstream:<id>`) within `min(relationDepth, maxRelationDepth)` and
   `min(relationNodes, maxRelationNodes)`, signals up to `candidateSignals`, and one candidate per
   Service binding (`event:`, `maintenance:` or `batch:` + `<Thing>.<Service>`). Anything beyond a cap becomes an unsearched-scope item (`relation_depth:…`,
   `relation_nodes:…`, `signal:…`) and adds `SEARCH_BOUNDARY_EXCEEDED`.
3. Fetch events in one batch. Source unavailable → `CANDIDATE_SOURCE_UNAVAILABLE` and
   `events:source_unavailable`; permission limited → `PERMISSION_LIMITED`; non-`COMPLETE` history
   → `PARTIAL_EVENT_HISTORY`; more than `events` → truncated with `events:truncated_to_<n>`.
4. Compute event co-occurrence per event/maintenance/batch candidate (§7.3.1).
5. Fetch pre-resolved analysis observations (support, weaken or unknown per candidate and test)
   in one batch; more than `analysisCalls` → truncated with `analysis:truncated_to_<n>`. The
   engine consumes these observations; it does not call U5 executors itself.
6. Assemble the ledger and rank it (§7.4).
7. Status: `ERROR` when the event source is unavailable and no candidate was admitted;
   `NO_FINDING` with `NO_SUPPORTED_CANDIDATE` when no candidate has support; otherwise
   `SUCCESS`. Searched scope lists the admitted candidate ids and `events:<count>`.

#### 7.3.1 Event co-occurrence

For a candidate of kind event, maintenance or batch, `EventCoOccurrence` counts events of the
same kind (and, when set, the same related asset) inside the half-open evidence window, and
reports the count, the matched events and the minimum absolute delta from the window start. These
are association facts only.

### 7.4 Investigation priority

The profile (`InvestigationProfile`) declares a closed list of `EvidenceTestSpec` entries
(`testId`, `supportWeight`, `weakenWeight`, `blockingWeaken`, `nextCheckTemplate`,
`lexicographicOrder`, `highPriority`) and whether numeric weights are enabled.

- A supporting test adds its bounded weight; a weakening test subtracts its weight; unknown or
  not-run tests contribute zero and stay visible.
- With weights disabled, every contribution is `0` and ranking uses the best completed support
  test's `lexicographicOrder`; no default weights are invented.
- A blocking weakening test sets `deprioritized`; the candidate is kept.

Ranking order: ordinary entries before deprioritized entries; then `investigationPriority`
descending (weights enabled) or best support lexicographic order (weights disabled); then more
completed high-priority tests; then fewer unknowns; then shorter declared relation distance; then
candidate id.

This is a work-queue order. It is never calibrated or displayed as a probability.

## 8. Reason codes, evidence and security

| Group | Codes |
|---|---|
| Collection failures (`CohortCollectionException`) | `COHORT_BUDGET_EXCEEDED`, `COHORT_MEMBER_DUPLICATE`, `COHORT_MEMBER_OMITTED` |
| Member gate reasons (`CohortGatePipeline`) | `NOT_IN_FROZEN_AUTHORIZED_SET`, `UNIT_OR_GRAIN_OR_METHOD_MISMATCH`, `QUALITY_BLOCKING` |
| Fleet reasons (`FleetOutcomeCodes`) | `NO_COMPARABLE_MEMBERS`, `FOCUS_NOT_IN_COHORT`, `COHORT_PARTIAL` |
| RCA reasons (`RcaOutcomeCodes`) | `SEARCH_BOUNDARY_EXCEEDED`, `CANDIDATE_SOURCE_UNAVAILABLE`, `NO_SUPPORTED_CANDIDATE`, `PERMISSION_LIMITED`, `PARTIAL_EVENT_HISTORY`, `CANCELLED`; `INCIDENT_UNRESOLVED` is declared but not emitted by the engine (an `IncidentAnchor` must already be resolved) |

Inherited authorization, cache lifecycle, upstream and budget errors keep their shared codes.

Evidence distinguishes:

- member not eligible versus no data versus not authorized;
- no statistical finding versus method not applicable;
- candidate weakened versus candidate untested;
- searched scope versus omitted or unavailable scope.

Protected (PASSWORD) columns cannot become metric inputs, join keys, candidate labels, evidence
excerpts, chart rows or exports. Text inside events, documents or tool results is untrusted data
and cannot change a profile or the workflow.

## 9. App extension surface

An App integrates by supplying, in Java:

- a `CohortBatchSource` that returns typed member rows with paging, completeness, unit, grain,
  method and permission behavior (§6.1);
- a `MetricComparabilitySpec` and, optionally, a `MemberQualityGate`;
- the authorized semantic ids for `FrozenCohortMembership.freeze`, the ranking direction, top N
  and focus asset;
- a `CandidateCatalog` (§6.3), an `InvestigationProfile` (§7.4) and an
  `InvestigationEvidenceSource` that returns event batches and pre-resolved analysis observations
  within the search limits.

The App cannot inject arbitrary queries or code, access cache internals, widen the current
principal's permissions, reveal inaccessible cohort membership, or mark an association as causal.

`U6FleetCapabilityCatalog` describes the internal `fleet_benchmark` capability (declared maximum
500 cohort members, enabled by default); it is not model-visible admission.

## 10. Code map

Under `parler-agent/src/main/java/com/thingworx/things/agent/`:

| Area | Classes |
|---|---|
| G5 contracts | `fleet.CohortBatchSource`, `CohortBatchMemberRow`, `CohortBatchSourceResult`, `CohortMemberStatus`, `CohortCoverageCounts`, `RankingDirection`, `ComparableMemberMetric`, `MemberPosition`, `U6FleetCapability`, `U6FleetCapabilityCatalog`, `FleetBenchmarkRequest` |
| G5 collection | `fleet.FrozenCohortMembership`, `CohortBatchFetcher`, `CohortGatePipeline`, `GatedMemberOutcome`, `MemberQualityGate`, `MetricComparabilitySpec`, `CohortCollector`, `CohortCollectionResult`, `CohortCollectionException`, `FleetMemberEvidence` |
| G5 distribution | `fleet.CompetitionRank`, `FleetDistributionStats`, `FleetDistributionEngine`, `FleetBenchmarkResult`, `FleetPositionEvidence`, `FleetOutcomeCodes`, `U6FleetEnvelopeFactory` |
| G7 | `investigation.IncidentAnchor`, `InvestigationProfile`, `EvidenceTestSpec`, `InvestigationSearchLimits`, `InvestigationBudget`, `CandidateCatalog`, `CandidateCatalogValidator`, `CatalogRelationEntry`, `CatalogSignalEntry`, `CatalogServiceBinding`, `CandidateResolver`, `ResolvedCandidate`, `InvestigationEvidenceSource`, `InvestigationEvent`, `CandidateEvidenceObservation`, `EventCoOccurrence`, `HypothesisLedgerEntry`, `HypothesisEvidenceRef`, `HypothesisScorecardAssembler`, `BoundedInvestigationEngine`, `RcaInvestigationRequest`, `RcaInvestigationResult`, `RcaOutcomeCodes`, `CandidateKind`, `U6RcaEnvelopeFactory` |
| Runners and demos | `fleet.U6FleetBenchmarkAppRunner`, `DemoPeerCohortBatchAdapter`; `investigation.U6RcaInvestigationAppRunner`, `DemoRcaEvidenceAdapter`; `playbook.U6ReferencePlaybookDriver`; `analysis.config.U6DemoAppProfiles` |

Related docs: [`../entity-set-analysis.md`](../entity-set-analysis.md),
[`../cached-table-decision-tools.md`](../cached-table-decision-tools.md),
[`../playbook-engine.md`](../playbook-engine.md),
[`../playbook-generic-ops-foundation.md`](../playbook-generic-ops-foundation.md),
[`../evidence-grounded.md`](../evidence-grounded.md).

## 11. Tests

Tests live under the `fleet`, `investigation` and `playbook` test packages, with fixtures and the
reference sequence description under `parler-agent/src/test/resources/nearterm/fleet-rca/`.
They cover independent rank/percentile/robust-z references and ties; small, complete, partial,
permission-limited, ineligible, no-data, zero-dispersion and focus-outside-top-N cohorts;
collection integrity (duplicate, omitted, budget, completeness); known, competing, weakened,
unsupported and unsearched RCA candidates; partial event history; search-limit faults;
cancellation; ledger wording guards; vocabulary locks (`Frc0VocabularyLockTest`); App adapters
(`U6Frc4AppAdapterTest`, `U6Frc4RcaAppAdapterTest`); and the reference driver
(`U6ReferencePlaybookDriverTest`).

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

## 12. Disable and rollback

- Nothing is model-advertised, so no tool admission change is needed to disable G5 or G7; a
  caller simply does not invoke the runners.
- An invalid catalog or profile fails closed; there is no fallback to model-generated peer or
  candidate logic.
- Derived rows are transient and need no migration; restart invalidates their handles under the
  common cache contract.
