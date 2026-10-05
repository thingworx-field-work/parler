# Deterministic Insight Kernel

This document describes the deterministic statistics kernel in `parler-agent` and the
model-facing `analyze_cached_result` tool that exposes it. The kernel answers three kinds of
question with server-computed numbers:

- **G1** — "what changed?": robust outliers, change-point candidates, and SPC (I-MR) run rules;
- **G2** — "which signals move together?": aligned association and simple regression evidence;
- **G4** — "is the trend heading toward a threshold?": slope fits and a horizon-bounded threshold
  crossing estimate.

The model selects an operation and explains the result. It never computes a statistic, fits a
line, picks a change point or draws a crossing, and it never upgrades an association to a cause.

This is a design and behavior description, not a normative contract. Wire contracts live under
[`CONTRACTS/`](../../../CONTRACTS/).

## Identifier map

Code comments and tests use these labels.

| Label | Meaning |
|---|---|
| U4 | Time/quality/join layer: typed time series, quality findings, exact join, and the base `analysisEnvelope` builder/validator ([`time-quality-join.md`](time-quality-join.md)) |
| U5 | This kernel (G1, G2, G4) |
| U6 | Fleet benchmarking and bounded RCA ([`fleet-rca.md`](fleet-rca.md)), which consumes U5 conventions |
| G1 / G2 / G4 | Anomaly and SPC / relationship / trend capability groups |
| DIK-0 … DIK-5 | Component groups: DIK-0 vocabulary, method catalog and outcome precedence; DIK-1 shared numeric runtime; DIK-2 G1 methods; DIK-3 G2 methods; DIK-4 G4 methods; DIK-5 tool packaging, envelope factory and evidence integration |
| S6 | The rule for percentile ranges a compact summary does not expose (§8.4) |
| D1 … D12 | Design rules in §5.1 |

## 1. Purpose

Parler answers anomaly, relationship and trend questions with reproducible numbers, explicit
support, and honest limits. The kernel:

- detects material observations and process shifts without asking the model to inspect a chart;
- quantifies association without claiming causality;
- keeps control limits distinct from specification limits;
- refuses unsupported extrapolation instead of manufacturing a trajectory;
- returns a compact `analysisEnvelope` that charts, fleet comparison, RCA and final answers can
  cite.

## 2. Foundations and boundaries

### 2.1 Consumed components

Paths are under `parler-agent/src/main/java/com/thingworx/things/agent/`.

| Capability | Component | Use in the kernel |
|---|---|---|
| Opaque cache I/O | `cache.ArtifactCache`, `ArtifactReader`, `ArtifactWriter`, `ArtifactAccessContext` | Only path to cached data; no FileRepository path or JVM index access |
| Source and budget | `source.SourceDescriptor` (+ `CompletenessStatus`), `execution.BudgetVector` | Completeness, lineage, budget projection into the envelope |
| Evidence and status | `evidence.EvidenceStatus`, `EvidenceAssessment` | Envelope status vocabulary |
| Base envelope | `analysis.AnalysisEnvelope`, `AnalysisEnvelopeBuilder`, `AnalysisEnvelopeValidator`, `AnalysisEnvelopeJson`, `AnalysisMethodDescriptor`, `AnalysisBudgetAccounting` | Extended, never duplicated |
| Operation enum | `analysis.AnalysisOperation` | U5 adds `OUTLIER`, `CHANGE_POINT`, `SPC`, `THRESHOLD_CROSSING` (detection) and `RELATIONSHIP`, `TREND` (quantification) |
| Typed series | `TypedTabularStream`, `analysis.stats.NumericSeriesProjector` | Numeric/timestamp projection from a cache handle |
| Percentile convention | `analysis.stats.LinearPercentile` (`h=(n-1)p`) | Shared with `CachedTabularGroupMetricExecutor` and `CachedTabularDistributionExecutor` |

### 2.2 Artifact boundary

Every method reads typed rows through the cache reader. No algorithm reads a FileRepository path
or the in-memory cache index, and no method creates a private result cache.

## 3. Scope

### 3.1 Implemented

- **G1**: median/MAD modified-z outliers, IQR outliers, bounded binary segmentation for
  mean-shift change-point candidates, I-MR control limits, and a four-rule run-rule catalog.
- **G2**: exact timestamp alignment and nearest-within-tolerance alignment, pairwise missing
  counts, sample covariance, Pearson, Spearman with average ranks, simple OLS with R² and residual
  summary, and a bounded lag scan (Java API only; see §8.2).
- **G4**: OLS trend, exact budget-bounded Theil–Sen slope, fit/support evidence, and a
  horizon-bounded threshold-crossing judgment.
- A method capability catalog with per-method complexity bounds, the `analysisEnvelope`
  extension for these operations, and one model-facing tool, `analyze_cached_result`.

### 3.2 Non-goals

- No causal inference, causal graph, root-cause proof, or wording that implies causality.
- No general forecasting (ARIMA, Prophet, neural models), seasonality discovery, or unbounded
  extrapolation.
- No arbitrary Python/R/JavaScript, uploaded model, App-supplied bytecode, or free-form formula.
- No multivariate regression, classification, clustering, PCA, survival analysis, or Bayesian
  inference.
- No silent sampling, approximate percentile, randomized method, or model visual judgment.
- No fleet/cohort benchmarking or RCA orchestration (see [`fleet-rca.md`](fleet-rca.md)).
- No direct file/index access, second cache, or durable result store.
- No change to the global 5,000-row tabular default; each method carries its own limits.

## 4. Downstream consumers

- [`fleet-rca.md`](fleet-rca.md) reuses the percentile, MAD and robust-z conventions and consumes
  U5 observations; it does not reimplement the statistics.
- Playbooks may call `analyze_cached_result`: it is on `PlaybookToolAllowlist`.
- Final answers read the analysis assessments recorded in task state (§8.3).
- A `cacheId` is transient and is never a durable identity.

## 5. Design rules

1. **Deterministic code owns every number.** The model chooses an operation and explains output.
2. **Quality gates are method inputs.** A blocking quality result yields `INSUFFICIENT_EVIDENCE`,
   never `NO_FINDING`.
3. **No causality in server-authored results.** G2 output uses "association", "relationship
   evidence" and "aligned pairs". Relationship envelopes carry the applicability tokens
   `associational` and `not_tested_causal`. Parler does not inspect or rewrite final prose.
4. **No universal confidence score.** Each method reports its own support, effect, fit and
   limits. Absent uncertainty stays absent.
5. **Method identity is evidence.** Every result records method id/version, parameters, profile
   digest, source handles, completeness and budget consumption.
6. **Strict methods require complete eligible input.** A method never samples silently and
   claims a full-data result; a budget overrun is an error.
7. **Missing values are explicit.** Pairwise deletion is used only for G2 and reports
   considered/used/dropped pairs.
8. **Specification limits are not control limits.** I-MR metrics always carry
   `limitKind=control`.
9. **Bounded extrapolation only.** A crossing beyond the horizon is "not established within
   horizon", never a forecast farther out.
10. **No global 5,000.** Theil–Sen, lag scans, nearest alignment and change-point search have
    method-specific caps.
11. **Shared contracts stay singular.** The kernel extends the U4 envelope, validator and budget
    projection; it defines no second envelope, budget object or configuration loader.

### 5.1 Rule index (D1–D12)

| Id | Rule |
|---|---|
| D1 | U5 fields/metrics extend `AnalysisEnvelope` through `AnalysisEnvelopeBuilder` and `AnalysisEnvelopeValidator`. Status is `EvidenceStatus`; completeness is `SourceDescriptor.CompletenessStatus`; budget is the `AnalysisBudgetAccounting` projection of `BudgetVector`. The envelope is internal to the Java agent and the tool result; there is no separate `CONTRACTS/` file for it. |
| D2 | Package layout: `analysis` (operation enum, catalog, registry, envelope factory, outcome precedence), `analysis.outlier`, `analysis.changepoint`, `analysis.spc`, `analysis.relationship`, `analysis.trend`, `analysis.stats` (shared accumulators and percentile/rank helpers). Tool schemas never name Java classes. |
| D3 | One percentile convention, `h=(n-1)p` linear interpolation (`LinearPercentile`), for median, MAD, IQR, Spearman ranks and the cached-table percentile metrics. |
| D4 | G1 defaults: modified-z threshold 3.5; IQR `k=1.5`; explicit zero-dispersion policy; binary segmentation with an SSE-reduction score that is not a probability; I-MR with a four-rule catalog where only R1 is enabled by default (§7.1–§7.3). |
| D5 | G2 alignment: exact timestamp by default; optional nearest-within-tolerance, one-to-one, deterministic tie-breaks, no interpolation; sample covariance `/(n-1)`; Pearson two-pass; Spearman average ranks; OLS with residuals; lag scan with a false-discovery caveat (§7.4–§7.5). |
| D6 | G4: OLS on elapsed seconds; exact Theil–Sen only within its pair budget; crossing only after the observed window and within the horizon; closed `outcomeCode` → status map; no uncertainty interval on the crossing point (§6.3.3, §7.6). |
| D7 | Status is singular and operation-scoped: each operation is detection or quantification, and each outcome maps to exactly one status (§6.3). |
| D8 | Methods are described by `U5MethodCapabilityCatalog`; `U5MethodRegistry` enforces complexity and reports `U5_METHOD_UNAVAILABLE` / `U5_METHOD_BUDGET_EXCEEDED`. |
| D9 | Model packaging is one tool, `analyze_cached_result`, with an `operation` enum and handle-only inputs (§8.2). |
| D10 | Compact summaries do not report evaluated/skipped percentile ranges; omission never implies coverage (§8.4). |
| D12 | The non-goals in §3.2 are closed. |

## 6. Common analysis contract

### 6.1 Inputs

The tool takes cache handles and column names, never rows or values. The executor opens the
cache through `TypedTabularStream`, projects the named timestamp and value columns with
`NumericSeriesProjector` in source order, and derives completeness from the cache's
`SourceDescriptor` (a series that was not fully scanned downgrades `COMPLETE` to `PARTIAL`).

The Java API of each method also accepts a configuration object (thresholds, support floors,
quality/applicability flags, policies). The tool uses fixed defaults (§8.2, §9).

### 6.2 Envelope

`AnalysisEnvelopeJson.toCompactJson` renders the envelope as:

```json
{
  "status": "SUCCESS|NO_FINDING|INSUFFICIENT_EVIDENCE|ERROR",
  "operation": "outlier|change_point|spc|relationship|trend|threshold_crossing",
  "sourceCacheIds": ["..."],
  "findingCacheId": null,
  "method": {"id": "robust_z", "version": "1", "profileDigest": "..."},
  "evidence": {"n": 0, "coverage": null, "completeness": "COMPLETE|PARTIAL|UNKNOWN", "...": "..."},
  "metrics": {"outcomeCode": "..."},
  "presentation": {"chartIntent": "...", "summaryFacts": ["method=...", "outcome=...", "supportN=..."]},
  "budget": {"requested": {}, "effective": {}, "consumed": {"rows": 0, "bytes": 0, "wallTimeMillis": 0}, "clamped": false},
  "rowsRead": 0,
  "rowsOutput": 0,
  "inputsFullyScanned": true
}
```

- `sourceCacheIds` for `relationship` lists the left then the right cache, each once; when both
  sides read the same cache the array holds one id, so the operand count must not be inferred
  from its length.
- Every non-`ERROR` U5 envelope carries `metrics.outcomeCode` (validator-enforced).
- `findingCacheId` is `null` for tool results: finding rows are not published as a separate
  artifact by the tool path.

### 6.3 Status rules

Top-level `status` reuses `EvidenceStatus`. Every result also carries `outcomeCode`
(`AnalysisOutcomeCodes.METRIC_KEY`) so consumers can distinguish cases that share a status.

#### 6.3.1 Detection versus quantification

| Operation | Class | `NO_FINDING` allowed? | Valid "nothing found" result |
|---|---|---|---|
| `outlier` | detection | yes | `NO_FINDING` / `no_outlier` |
| `change_point` | detection | yes | `NO_FINDING` / `no_change_point` |
| `spc` | detection | yes | `NO_FINDING` / `no_spc_violation` |
| `threshold_crossing` | detection | yes | `NO_FINDING` per §6.3.3 |
| `relationship` | quantification | **no** | `SUCCESS` with the computed association, including r≈0 |
| `trend` | quantification | **no** | `SUCCESS` with the computed slope, including a flat slope |

`AnalysisEnvelopeValidator` rejects `NO_FINDING` on a quantification operation, and rejects a
detection `NO_FINDING` with `n <= 0` (a negative result must follow sufficient evidence).

#### 6.3.2 Shared status rows

| Condition | Status | Typical `outcomeCode` / reason |
|---|---|---|
| Invalid arguments, cache lifecycle, internal failure, exhausted hard budget | `ERROR` | tool `reason` such as `ARGUMENT_MISSING`, a cache error code, `U5_METHOD_BUDGET_EXCEEDED`; Theil–Sen pair budget → `budget_exceeded` |
| Support below minimum, zero variance, zero dispersion, blocking quality, applicability failure | `INSUFFICIENT_EVIDENCE` | `insufficient_support`, `insufficient_variance`, `ZERO_DISPERSION`, `quality_blocked`, `applicability_failed` |
| Detection ran on sufficient evidence and found ≥1 event | `SUCCESS` | `outlier`, `change_point`, `spc_violation` |
| Detection ran on sufficient evidence and found nothing | `NO_FINDING` | `no_outlier`, `no_change_point`, `no_spc_violation` |
| Quantification computed | `SUCCESS` | `association`, `trend` |

Completeness: `SUCCESS` normally requires `COMPLETE`. The validator allows `relationship` and
`trend` to succeed under `PARTIAL`/`UNKNOWN` sources, because pairwise used/dropped counts are
reported. A relationship with any dropped pair is reported as `PARTIAL`.

#### 6.3.3 `threshold_crossing` outcome → status

`ThresholdCrossingOutcome` fixes this map; the validator rejects any other pairing.

| `outcomeCode` | Meaning | Status |
|---|---|---|
| `crossing_within_horizon` | Direction reaches the threshold; gates pass; `estimatedCrossingAt` is after the observed window and within the horizon | `SUCCESS` |
| `already_crossed` | The observed window already meets or crosses the threshold | `SUCCESS` |
| `outside_horizon` | Gates pass; the crossing is after the window but beyond the horizon | `NO_FINDING` |
| `flat` | Gates pass; the slope cannot reach the threshold | `NO_FINDING` |
| `wrong_direction` | Gates pass; the slope moves away from the threshold | `NO_FINDING` |
| `insufficient_support` | Eligible n below the minimum | `INSUFFICIENT_EVIDENCE` |
| `insufficient_fit` | Fit gate failed (R² floor, zero variance, unfittable trend) | `INSUFFICIENT_EVIDENCE` |
| `quality_blocked` | Blocking quality flag | `INSUFFICIENT_EVIDENCE` |
| `applicability_failed` | Configuration/unit/horizon validation rejected the request | `INSUFFICIENT_EVIDENCE` |

`outside_horizon` also stamps the applicability token `outside_horizon` on the envelope.

#### 6.3.4 Determinism

The same input, configuration and method version produce the same status, `outcomeCode`,
metrics, ordered findings, chart intent and model-facing assessment.

#### 6.3.5 `threshold_crossing` evaluation precedence

When several outcomes could apply, `ThresholdCrossingOutcomeResolver` takes the first match
(hard `ERROR` faults are outside this order):

1. `applicability_failed`
2. `quality_blocked`
3. `insufficient_support`
4. `insufficient_fit`
5. `already_crossed`
6. `crossing_within_horizon`
7. `outside_horizon`
8. `flat`
9. `wrong_direction`

Gate checks therefore precede `already_crossed`.

## 7. Algorithm catalog

Method ids and complexity bounds come from `U5MethodCapabilityCatalog`. All method versions are
`1`.

| Method id | Operation | Max eligible points | Other bound |
|---|---|---:|---|
| `robust_z` | outlier | 100,000 | |
| `iqr` | outlier | 100,000 | |
| `binary_segmentation` | change_point | 50,000 | O(k·n) with candidate cap |
| `imr` | spc | 100,000 | |
| `spc_run_r1` | spc | 100,000 | enabled by default |
| `spc_run_r2` … `spc_run_r4` | spc | 100,000 | opt-in |
| `pearson` | relationship | 100,000 | |
| `spearman` | relationship | 50,000 | ranks materialized |
| `ols_pair` | relationship | 100,000 | |
| `lag_scan` | relationship | 50,000 | 2,000 materialized pairs |
| `ols_trend` | trend | 100,000 | |
| `theil_sen` | trend | 2,000 | 1,999,000 pairwise slopes |
| `threshold_crossing` | threshold_crossing | 100,000 | |

### 7.1 G1 — robust outliers

**Modified z-score by MAD** (`RobustZDetector`, minimum support 3):

1. `m = median(x)` with the `h=(n-1)p` convention.
2. `MAD = median(|x_i - m|)`.
3. `score_i = 0.67448975 * (x_i - m) / MAD`.
4. A finding requires `|score_i| >= threshold` (default 3.5).

**IQR rule** (`IqrDetector`, minimum support 4): `Q1`, `Q3` with the same convention;
`IQR = Q3 - Q1`; fences `Q1 - k*IQR` and `Q3 + k*IQR` (default `k = 1.5`).

**Zero dispersion** (`ZeroDispersionPolicy`): when MAD or IQR is zero the method does not divide.
`INSUFFICIENT` returns `INSUFFICIENT_EVIDENCE` with outcome `ZERO_DISPERSION`; `FALLBACK_IQR`
makes robust-z fall back to IQR. The tool uses `INSUFFICIENT`. Neither method calls a
specification violation an outlier.

### 7.2 G1 — change points

`BinarySegmentation` works over the ordered numeric series:

1. Each segment needs a minimum finite support (default 3) and the search stops at a candidate
   cap (default 3).
2. For each eligible split, compute the reduction in within-segment squared error relative to
   one mean, using prefix sums of x and x².
3. Choose the greatest reduction; ties choose the earliest source ordinal.
4. Accept only when the absolute mean shift (default minimum 0.0), relative SSE reduction
   (default minimum 0.05) and both segment supports pass.
5. Recurse left and right until the candidate cap, support floor or score threshold stops it.

Output reports the split, before/after support and means, shift and SSE-reduction score. The
score is not a probability.

### 7.3 G1 — I-MR and run rules

- Centre line is the mean of eligible ordered values.
- Moving ranges are `|x_i - x_{i-1}|`; sigma is `MRbar / 1.128`.
- Individual limits are `mean ± 3σ`; the MR upper limit is `3.267 * MRbar`, lower limit zero.
- Fewer than two points, no moving ranges, or `sigma == 0` (`ZERO_DISPERSION`) yields
  `INSUFFICIENT_EVIDENCE`.

Run-rule catalog (`SpcRunRule`, `SpcRunRuleEvaluator`): R1 one point beyond 3σ; R2 two of three
consecutive points beyond 2σ on the same side; R3 four of five beyond 1σ on the same side; R4
eight consecutive points on one side. R2–R4 findings record `rule@version;involved=<ordinals>`,
and R4 emits one finding per maximal same-side streak of eight or more. The tool evaluates R1
only. Metrics always carry `limitKind=control`.

### 7.4 G2 — alignment

`SeriesAligner`:

- `exact`: pairs on equal canonical timestamps.
- `nearest_within_tolerance`: one-to-one matching; ties break by smallest absolute delta, then
  earlier timestamp, then source ordinal. No interpolation. The candidate space `|left|×|right|`
  is capped at 2,000,000 (`MAX_NEAREST_CANDIDATE_PAIRS`); above that the call fails with
  `U5_METHOD_BUDGET_EXCEEDED`.

Pairs with a non-finite value on either side are dropped and counted (`PairwiseFinitePairs`);
metrics report considered, used and dropped pairs.

### 7.5 G2 — covariance, correlation, ranks, and OLS

`AssociationEvidence`:

- Sample covariance divides by `n-1`.
- `pearson` uses stable two-pass centred sums.
- `spearman` assigns average ranks to ties, then applies Pearson to the ranks.
- `ols_pair` fits `y = intercept + slope*x` and reports slope, intercept, R², SSE/MSE when
  defined, and residual mean/stddev/min/max.
- Fewer than two used pairs → `insufficient_support`; zero variance → `insufficient_variance`.

`LagScan` evaluates an explicit lag grid after alignment, keeps each attempted lag's support and
metric, picks the greatest absolute association with deterministic tie-breaks, and carries the
caveat `selecting_lag_increases_false_discovery_risk`. `RelationshipWording` builds the compact
summaries and rejects causal phrasing.

### 7.6 G4 — trend and threshold crossing

- `ols_trend` fits OLS on elapsed seconds from the first observation (`TrendSeriesProjection`
  uses seconds plus nanoseconds, no millisecond truncation) and reports slope per second.
  `SimpleOls.mse = sse/(n-2)` for `n > 2` (`NaN` when `n == 2`) and metrics echo
  `mseDenominator=n-2`. A constant-y perfect fit reports `rSquared=1`.
- `theil_sen` computes the median of all pairwise finite slopes only when `n*(n-1)/2` fits the
  pair budget; otherwise `ERROR` with outcome `budget_exceeded`. It never samples pairs. Its
  crossing fit gate is support-only (no R²).
- `trend` is quantification: a valid fit is `SUCCESS` even when flat.
- `threshold_crossing` (`ThresholdCrossingEvaluator`) is detection. Defaults: minimum support 3,
  minimum R² 0.0, horizon 1 hour, flat-slope epsilon `1e-12`. `already_crossed` inspects every
  sample of the eligible window relative to the first sample's side, not only the endpoints.
  An estimated crossing is emitted only for `crossing_within_horizon`. There is no confidence
  interval on the crossing point.

## 8. Runtime, tooling, and presentation

### 8.1 Streaming and complexity

- Mean, covariance, Pearson and OLS use stable accumulators (`StableMeanVariance`,
  `StablePearson`, `SimpleOls`).
- Median, MAD, IQR, Spearman and Theil–Sen materialize eligible finite values within the catalog
  bound; above it they fail with a budget error rather than sample.
- Change-point search is O(k·n) with a candidate cap.

### 8.2 Model-facing tool: `analyze_cached_result`

One tool, registered in `BuiltInTools` when `U5OperationAdmission.analyzeEnabled()` is true (the
default); when disabled it is withdrawn from advertisement, stays executable, and returns
`U5_ANALYZE_DISABLED`. Schema: `AnalyzeCachedResultToolSchema`.

| Argument | Type | Notes |
|---|---|---|
| `operation` | string, required | `outlier`, `change_point`, `spc`, `relationship`, `trend`, `threshold_crossing` |
| `cacheId` | string, required | Primary series handle |
| `timeColumn` | string | Exact column name declared by the cache result; required by the executor |
| `valueColumn` | string | Exact numeric column name; required by the executor |
| `methodId` | string | Optional: `robust_z` (default) or `iqr`; `pearson` (default), `spearman` or `ols_pair`; `ols_trend` (default) or `theil_sen`; `theil_sen` also switches the crossing fit |
| `rightCacheId` | string | Required for `relationship` |
| `rightValueColumn` | string | Relationship right column; defaults to `valueColumn` |
| `alignment` | `exact` \| `nearest` | Relationship alignment, default `exact` |
| `toleranceMillis` | integer | Nearest tolerance; the executor uses 1,000 ms when omitted |
| `threshold` | number | Required for `threshold_crossing` |
| `horizonSeconds` | number | Crossing horizon; default 3,600 |

`additionalProperties` is false. Non-empty `values`, `epochMillis` or `rightValues` arrays are
rejected: the tool is handle-only.

Fixed tool defaults: IQR `k=1.5`; robust-z threshold 3.5; zero dispersion → insufficient;
binary segmentation defaults; SPC rule R1 only. `change_point` and `spc` ignore `methodId`, and
an unrecognized `methodId` on the other operations runs the operation's default method. The lag
scan is not reachable from the tool.

Result shape:

```json
{
  "status": "OK|ERROR",
  "reason": "SUCCESS|NO_FINDING|INSUFFICIENT_EVIDENCE|ERROR",
  "mayPublish": false,
  "analysisEnvelope": { "...": "see §6.2" },
  "sources": [{"role": "left|right", "cacheId": "...", "timeColumn": "...", "valueColumn": "...",
               "thingName": "...", "propertyName": "..."}]
}
```

`sources` has one row per operand actually read (a same-cache pair yields two rows); Thing and
property appear only when the cache writer declared them. On errors the result is
`{status:"ERROR", reason, detail?, mayPublish:false}`. A column-projection miss adds structured
feedback (which side, which parameter, and whether the value column was explicit or inherited)
from `AnalyzeProjectionDiagnostics`.

### 8.3 Charts and final answers

- The tool emits a server-built `presentation.chartIntent` string: `u5.outlier.findings`,
  `u5.change_point.findings`, `u5.spc.findings`, `u5.relationship.pairs`, `u5.trend.fit`,
  `u5.trend.crossing`. No chart payload is built from these intents by the tool itself; charts
  are drawn by the chart tools from cached tables.
- The executor records `envelope.evidence()` into `AgentTaskState` (`recordAnalysisAssessment`),
  even though `mayPublish=false`. `EvidenceAssessmentAggregator` folds these assessments: when an
  analysis is the sole evidence it preserves the U5 status (`NO_FINDING`, `SUCCESS`) and
  completeness, and otherwise merges them with row-derived assessments.
- Model-facing evidence distinguishes association from causation (`associational`,
  `not_tested_causal`), completeness, insufficient versus no finding, and `outside_horizon`
  applicability. Parler does not inspect or rewrite final prose.

### 8.4 Omitted percentile ranges (S6)

`summarize_cached_result` does not report which requested percentile ranges were evaluated or
skipped (there are no `percentilesEvaluated` / `percentilesSkipped` fields). A summary that omits
a percentile range must never be read as having evaluated it, and omission never implies complete
percentile coverage.

## 9. Configuration and extension

- No AgentSettings field or App configuration surface tunes these methods today; the tool uses
  the defaults in §8.2. Envelopes record the method descriptor with profile digest `u5-demo`.
- `analysis.config.U5DemoAppProfiles` lists the six operations with demo digests
  (`u5-demo-outlier`, …) and the advertised operation list; it does not open caches or run
  analysis.
- Java callers can pass the method configuration objects directly (thresholds, `k`, zero
  dispersion policy, enabled run rules, segmentation limits, crossing support/fit/horizon and
  quality/applicability flags, lag grid).
- A proprietary algorithm belongs in a typed ThingWorx Service with its own method metadata; it
  does not receive cache paths or internal Java objects.

## 10. Code map

Under `parler-agent/src/main/java/com/thingworx/things/agent/`:

| Area | Classes |
|---|---|
| Vocabulary and precedence | `analysis.AnalysisOperation`, `AnalysisOperationClass`, `AnalysisOutcomeCodes`, `ThresholdCrossingOutcome`, `ThresholdCrossingOutcomeResolver` |
| Catalog and registry | `analysis.U5MethodCapability`, `U5MethodCapabilityCatalog`, `U5MethodRegistry`, `U5OperationAdmission` |
| Shared numeric runtime | `analysis.stats.NumericSeries`, `NumericObservation`, `FiniteMissingAccounting`, `NumericSeriesProjector`, `StableMeanVariance`, `StablePearson`, `SimpleOls`, `AverageRanks`, `LinearPercentile`, `AnalysisUnitPropagation`; `analysis.FindingRow`, `FindingRowWriter` |
| G1 | `analysis.outlier.RobustZDetector`, `IqrDetector`, `ZeroDispersionPolicy`; `analysis.changepoint.BinarySegmentation`; `analysis.spc.ImrLimits`, `SpcRunRule`, `SpcRunRuleEvaluator`; `analysis.G1DetectionResult` |
| G2 | `analysis.relationship.SeriesAligner`, `PairwiseFinitePairs`, `AssociationEvidence`, `LagScan`, `G2RelationshipResult`, `RelationshipWording` |
| G4 | `analysis.trend.TrendSeriesProjection`, `OlsTrend`, `TheilSenTrend`, `ThresholdCrossingEvaluator`, `G4TrendResult`, `G4CrossingResult` |
| Packaging | `analysis.U5AnalysisEnvelopeFactory`; `tools.AnalyzeCachedResultToolSchema`, `AnalyzeCachedResultExecutor`, `AnalyzeProjectionDiagnostics`; `evidence.EvidenceAssessmentAggregator`; `taskstate.AgentTaskState` |

Related docs: [`../cached_tabular_tools.md`](../cached_tabular_tools.md),
[`../cached-table-decision-tools.md`](../cached-table-decision-tools.md),
[`../evidence-grounded.md`](../evidence-grounded.md), [`../chart-intent.md`](../chart-intent.md),
[`../history-overlay-chart.md`](../history-overlay-chart.md).

## 11. Tests

Tests use independent reference values, never the production formula, to produce expectations.
Coverage includes synthetic positives and negatives, no-finding and insufficient cases for every
operation, the closed `threshold_crossing` matrix and precedence
(`ThresholdCrossingOutcomeResolverTest`), vocabulary locks (`Dik0VocabularyLockTest`), the
shared runtime (`Dik1RuntimeTest`), tool packaging and schema (`Dik5PackagingTest`), and evidence
aggregation (`Dik5EvidenceAssessmentTest`). Reference fixtures live under
`parler-agent/src/test/resources/nearterm/dik/`.

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

## 12. Disable and rollback

- Turning `U5OperationAdmission` off withdraws `analyze_cached_result` from advertisement and
  makes direct calls return `U5_ANALYZE_DISABLED`. The switch is an internal flag, not an
  AgentSettings field.
- In the Java API, `U5MethodRegistry.requireEnabled` rejects an unknown or disabled method id
  with `U5_METHOD_UNAVAILABLE`.
- Results are transient; there is nothing to migrate. Persisted transcript evidence is compact
  historical text and is never treated as a live handle after restart.
