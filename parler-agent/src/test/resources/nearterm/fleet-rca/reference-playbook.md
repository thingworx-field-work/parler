# U6 reference Playbook (`u6_fleet_rca_reference`)

FRC-4 App/eval gate. This document is the operator-facing description of the §7.3 workflow.
Execution is `U6ReferencePlaybookDriver` over App adapters — **not** a pack registry entry and
**not** a new resident model tool.

## Packaging (D3)

| Surface | Packaging |
|---------|-----------|
| G5 `fleet_benchmark` | App / reference-Playbook invoked via `U6FleetBenchmarkAppRunner`. **Not** advertised on `analyze_cached_result` (`cacheId` + U5-only gate are hostile). |
| G7 RCA investigation | Playbook-only via `U6RcaInvestigationAppRunner`. No resident RCA tool. |

## Node sequence

```text
freeze_cohort_membership
  -> fleet_batch_collect_and_distribute   (DemoPeerCohortBatchAdapter)
  -> resolve_incident_anchor
  -> resolve_candidates                   (CandidateCatalog)
  -> fetch_events_and_analysis            (DemoRcaEvidenceAdapter)
  -> assemble_hypothesis_scorecards
  -> emit_compact_envelopes               (fleet AnalysisEnvelope + RCA EvidenceAssessment)
```

## Fixtures

- Peer digest: `u6-demo-fleet-peer-v1` (`U6DemoAppProfiles` / `DemoPeerCohortBatchAdapter`)
- RCA digest: `u6-demo-rca-investigation-v1` / evidence `u6-demo-rca-evidence-v1`
- Driver id: `u6_fleet_rca_reference`
- Offline tests: `U6ReferencePlaybookDriverTest`, `U6Frc4AppAdapterTest`, `U6Frc4RcaAppAdapterTest`
