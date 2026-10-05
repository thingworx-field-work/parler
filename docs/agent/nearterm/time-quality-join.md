# Time, Quality, and Exact Join

This document describes the deterministic data-plane operations over cached tables:

- **G3 — time transforms:** resample into buckets, rolling mean, rate of change, period comparison;
- **G6 — time-series quality:** timestamp validity, duplicates, ordering, freshness, cadence,
  coverage, flatline, range and cadence drift;
- **G8 — exact join:** typed exact inner/left join of two cached tables; and
- the base **analysis envelope** (`AnalysisEnvelope`) that these operations return and that later
  analysis families extend.

The model reaches these operations as governed modes of `tabulate_cached_result`. The wire contract
for those modes is normative in [`../../../CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md) §6;
this document explains the internals. Java performs every timestamp conversion, bucket assignment,
quality metric, join, count and completeness decision; the model chooses an operation and explains
the result.

Code comments refer to this area as U4 and cite the labels `B1`–`B10` and the section numbers used
below.

Related: [`../cached_tabular_tools.md`](../cached_tabular_tools.md),
[`../time-interpretation.md`](../time-interpretation.md),
[`../history-overlay-chart.md`](../history-overlay-chart.md),
[`semantics-evidence-foundation.md`](./semantics-evidence-foundation.md) (evidence vocabulary),
[`deterministic-insight-kernel.md`](./deterministic-insight-kernel.md),
[`computing-enhancement.md`](./computing-enhancement.md) and [`fleet-rca.md`](./fleet-rca.md)
(later operation families on the same envelope).

## 1. Model-facing surface

| `tabulate_cached_result` mode | Executor-only alias | Publishes a derived table | Admission flag |
|---|---|---|---|
| `exact_join` | `exact_join_cached_result` | yes, on success | `U4_EXACT_JOIN_DISABLED` |
| `quality` | `quality_cached_result` | never | `U4_QUALITY_DISABLED` |
| `resample` | `resample_cached_result` | yes | `U4_RESAMPLE_DISABLED` |
| `rolling` | `rolling_cached_result` | yes | `U4_ROLLING_DISABLED` |
| `rate_of_change` | `rate_of_change_cached_result` | yes | `U4_RATE_OF_CHANGE_DISABLED` |
| `period_compare` | `period_compare_cached_result` | never | `U4_PERIOD_COMPARE_DISABLED` |

The mode and its alias share one executor. Mode arguments, the result shell (`status` `OK`/`ERROR`,
`reason`, `mayPublish`, `findingCacheId`, `analysisEnvelope`, `detail`) and the admission behavior
are specified in the contract. Playbooks call the same modes; there is no second implementation.
The modes are added to an existing tool, so no new resident tool is advertised.

Admission flags live in `U4OperationAdmission`. They are process-level switches that default to
enabled. When one is off, the mode is dropped from the advertised `mode` enum and any direct call
(mode or alias) returns the stable `U4_*_DISABLED` reason with `mayPublish:false`. There is no
ThingWorx property or service to change them; they are set only in code and tests.

## 2. Data flow

1. **Read.** `TypedTabularStream` opens the cached table by `cacheId` in the current conversation
   scope and parses the artifact with a streaming JSON parser over `ArtifactReader` chunks. It
   projects columns, supports early stop, skips PASSWORD columns, and reports `inputsFullyScanned`.
   It never materializes an `InfoTable` and never opens a FileRepository path or the cache index.
2. **Time axis.** `TimeAxisNormalizer` turns the time column into a UTC `Instant`: DATETIME cells,
   numeric epoch milliseconds, or ISO-8601 instant strings. Null or unparseable timestamps are
   counted, not guessed. The value column, when given, must hold numbers; other values become null.
3. **Operate.** The operator runs on the extracted points (G3/G6) or on typed rows (G8).
4. **Publish.** A derived table is written through `DerivedTabularPublisher` (cache API only) with a
   `SourceDescriptor` from `DerivedArtifactLineage`: the parent (or both join parents), the route id
   (`u4.resample`, `u4.rolling`, `u4.rate_of_change`, `u4.exact_join`) and conservative completeness
   (§5 item 6).
5. **Envelope.** The operation builds an `AnalysisEnvelope`, which the builder validates, and the
   executor returns its compact JSON as `analysisEnvelope`.

Failures publish no handle. A missing or expired `cacheId` fails the call with the cache's
`CACHE_MISS` fault, and nothing is published.

## 3. Analysis envelope (B1)

`com.thingworx.things.agent.analysis.AnalysisEnvelope` is the single base result type for analysis
operations. It is internal and tool-facing; the only normative wire text for it is the
`analysisEnvelope` row in the contract. Later families (outlier, change point, SPC, threshold
crossing, relationship, trend, fleet benchmark, and the computing modes) add operation values and
metrics to this type; they do not define another envelope.

Compact JSON (`AnalysisEnvelopeJson`):

```json
{
  "status": "SUCCESS|NO_FINDING|INSUFFICIENT_EVIDENCE|ERROR",
  "operation": "resample|rolling|rate_of_change|period_compare|quality|exact_join",
  "sourceCacheIds": ["..."],
  "findingCacheId": "...",
  "method": { "id": "u4.resample", "version": "1", "profileDigest": "demo-resample-v1" },
  "evidence": {
    "n": 0,
    "coverage": null,
    "completeness": "COMPLETE|PARTIAL|UNKNOWN",
    "quality": [],
    "warnings": []
  },
  "metrics": {},
  "presentation": { "chartIntent": null, "summaryFacts": [] },
  "budget": { "requested": {}, "effective": {}, "consumed": {}, "clamped": false },
  "rowsRead": 0,
  "rowsOutput": 0,
  "inputsFullyScanned": true
}
```

- `status` uses `EvidenceStatus` and `evidence.completeness` uses
  `SourceDescriptor.CompletenessStatus`; there are no private duplicate enums.
- `evidence` is an `EvidenceAssessment`
  ([`semantics-evidence-foundation.md`](./semantics-evidence-foundation.md) §B5).
- `rowsRead`, `rowsOutput` and `inputsFullyScanned` are kept separate from completeness.
- The U4 modes do not fill `budget` (it is emitted with empty objects and `clamped:false`) or
  `presentation`.

`AnalysisEnvelopeValidator` runs on every build and rejects:

- `evidence.status` different from the envelope `status`;
- `SUCCESS` without proven `COMPLETE` completeness (except the documented relationship/trend, fleet
  and measurement carve-outs, where a measurement must carry a `SCOPE_OBSERVED_SPAN` warning);
- `SUCCESS` with `n <= 0`;
- `NO_FINDING` with `COMPLETE` and positive `n` (detection families instead require positive `n`);
- a method whose operation differs from the envelope operation;
- negative row or budget counters, and missing outcome codes on the later detection/quantification
  families.

Status rule used by the U4 operations: `INSUFFICIENT_EVIDENCE` when completeness is not `COMPLETE`
or the input was not fully scanned; otherwise `NO_FINDING` when the result is empty (no output rows,
no join matches) and `SUCCESS` when it is not. Quality and period comparison report `SUCCESS` for a
complete, fully scanned source. Validation or execution failures report `ERROR`.

U4 envelopes are returned in the tool result; they are not recorded in the task-state evidence
assessments (only `analyze_cached_result` records those).

## 4. Operation profiles (B7)

Each operation runs with a fixed built-in profile. The profile digest appears as
`method.profileDigest`:

| Operation | Profile digest | Built-in settings |
|---|---|---|
| `exact_join` | `demo-exact-join-v1` | key column `id` on both sides, cardinality `1:1`, collision policy `ERROR`; `INNER` builds the left side, `LEFT` builds the right side; build and output caps 5 000 rows each |
| `quality` | `demo-quality-v1` | expected cadence 5 min, freshness max age 15 min, flatline epsilon 0 over ≥ 15 min and ≥ 4 points, cadence-drift factor 2.0 (`WARNING` when the recent median sampling interval is at least 2× or at most ½ the expected cadence), no range bounds; severities: duplicates and out-of-order `WARNING`, freshness and flatline `BLOCKING`, range `WARNING` |
| `resample` | `demo-resample-v1` | fixed 1-hour UTC buckets anchored at `windowStart`, default aggregation `MEAN`, missing policy `NULL` |
| `rolling` | `demo-rolling-v1` | default kind `OBSERVATION_COUNT` with window 5; elapsed-duration default 1 hour; default minimum support 1 |
| `rate_of_change` | `demo-rate-of-change-v1` | — |
| `period_compare` | `demo-period-compare-v1` | default aggregation `MEAN` |

Tool arguments can override only what the contract lists (for example `joinType`, `aggregation`,
`rollingKind`, `observationWindow`, `durationWindowSeconds`, `minSupport`). App-supplied profile
files are not loaded, and the semantic-profile provenance on the source (`unitRef`, `cadenceRef`)
is not read by these operations; quality uses the cadence of its built-in profile.

## 5. Binding rules

1. **Half-open windows (B3).** Computation windows are `[startInclusive, endExclusive)`
   (`HalfOpenWindow`). A point at `endExclusive` belongs to the next window or bucket.
2. **UTC computation.** Instants are normalized to UTC. The resample mode uses fixed UTC durations.
   `BucketAssigner` also supports calendar day and hour buckets in a declared IANA zone (never the
   server default; a missing zone is `TIMEZONE_REQUIRED`); those are used by the `calendar_bucket`
   computing mode, not by the modes here.
3. **Observed order is not trusted.** Series are ordered by timestamp, then stable source ordinal.
   Duplicate timestamps stay visible to quality and are never dropped silently.
4. **No hidden fill.** Empty buckets are `NULL`, or `0` only under the `ZERO` policy, which is
   allowed only for `COUNT` and `SUM` (otherwise `MISSING_POLICY_NOT_ALLOWED`). Filled buckets are
   marked `synthesized=true` with `support=0`. There is no forward fill or interpolation.
5. **Quality precedes analysis (B4).** Every quality finding carries severity
   `INFO`/`WARNING`/`BLOCKING`, threshold, observed value, support and the profile digest. A
   `BLOCKING` finding is always written as `BLOCKING:<metric>` in `evidence.quality`; the builder
   fails if one is dropped. Presentation may still render with explicit warnings.
6. **Completeness is monotone-conservative (B6).** A transform keeps the parent's completeness when
   the declared window was fully scanned and the window/policy is proven; otherwise the result drops
   to `UNKNOWN` (never upgrades). A join takes the worse of both parents (`UNKNOWN` is worse than
   `PARTIAL`, which is worse than `COMPLETE`), and `UNKNOWN` if either input was not fully scanned.
   Observation gaps inside a fully scanned `COMPLETE` window are quality findings, not a
   completeness downgrade.
7. **Exact join means exact typed keys.** No case folding, fuzzy matching or stringification across
   type families. App-owned normalization happens upstream in a governed Service.
8. **Cardinality is enforced (B5).** Declared `1:1`, `1:n` or `n:1` is validated before output;
   `n:n` is not supported. Violations fail with bounded diagnostics and no handle.
9. **Memory-bounded join.** Only the build side is held in memory, bounded by `maxBuildRows`; the
   probe side is streamed once. Exceeding the bound returns `BUDGET_EXCEEDED`. There is no spill to
   disk.
10. **No global 5000 change (B10).** Operation caps are per operation (§8). The shared
    `BudgetVector.maxReturnedRows` default stays 5 000 and is not redefined here.

## 6. Reasons

Tool-level `reason` values are listed in the contract (§6 and §6.1). Operation-specific values:

- time and window: `TIME_AXIS_MISSING`, `WINDOW_INVALID`, `ARGUMENT_MISSING`,
  `AGGREGATION_INVALID`, `ROLLING_KIND_INVALID`, `MISSING_POLICY_NOT_ALLOWED`;
- quality: `OK`, `QUALITY_BLOCKING` (at least one blocking finding; `status` stays `OK`);
- period comparison: `PERCENT_UNDEFINED` when the percentage cannot be computed;
- join (`ExactJoinReason`): `SUCCESS`, `JOIN_KEY_MISSING`, `JOIN_TYPE_MISMATCH`,
  `CARDINALITY_VIOLATION`, `COLUMN_COLLISION`, `OUTPUT_BUDGET_EXCEEDED`, `BUDGET_EXCEEDED`,
  `PASSWORD_COLUMN`, `INVALID_CONFIG`, plus `JOIN_TYPE_INVALID` for a bad `joinType` argument;
- admission: `U4_*_DISABLED`.

`INSUFFICIENT_EVIDENCE` is a normal analytical status, never rewritten as "no issue".

## 7. Algorithms

### 7.1 G3 — time transforms

**Resample.** Buckets come from `BucketAssigner` (fixed duration from an explicit anchor, or
calendar day/hour in an IANA zone). For each bucket overlapping the window, points inside both the
bucket and the window are aggregated with `COUNT`, `SUM`, `MEAN`, `MIN`, `MAX`, `FIRST` or `LAST`
(`FIRST`/`LAST` by timestamp then source ordinal). Output columns: `bucketStart`, `bucketEnd`,
`value`, `synthesized`, `support`.

**Rolling.** A rolling mean at every in-window point, over either the last N observations
(`OBSERVATION_COUNT`) or the elapsed duration ending at that point (`ELAPSED_DURATION`). The mean is
`sum / count(non-null)` (no null-as-zero). When support is below `minSupport` the value is null and
the actual support is still reported. Output uses the same columns as resample, with
`bucketStart = bucketEnd =` the point instant.

**Rate of change.** Pointwise `(current - previous) / elapsed seconds`. The first point, a
duplicate timestamp, or non-positive elapsed time yields a null rate marked `insufficient=true`
(never a division by zero). Output columns: `timestamp`, `ratePerSecond`, `insufficient`.

**Period comparison.** Aggregates two explicit half-open windows with the same aggregation and
returns `metrics.original`, `current`, `delta`, `percent` (`undefined` when the original is missing
or zero, or the current is missing), `unequalOrPartial` (different window lengths, or a window with
no support), `originalSupport` and `currentSupport`. Warnings `percent_undefined` and
`unequal_or_partial_windows` are added when applicable. `n` is 2.

### 7.2 G6 — time-series quality

Quality reads points in source order, evaluates them against the window, and uses the window end as
the evaluation anchor. The closed metric set (`QualityMetricId`):

| Metric | Computation | Severity |
|---|---|---|
| `timestamp_validity` | missing or unparseable timestamps | `BLOCKING` if any, else `INFO` |
| `duplicates` | repeated canonical timestamps | profile (`WARNING`) if any |
| `out_of_order` | count and maximum backward displacement in source order | profile (`WARNING`) if any |
| `freshness` | anchor − latest in-window observation, against max age | profile (`BLOCKING`) when late or when there is no in-window observation |
| `cadence` | median positive delta against expected cadence | `WARNING` when median > 2 × expected |
| `gaps_coverage` | occupied cadence slots / expected slots (`ceil(window / cadence)`) | `WARNING` when coverage < 0.9 |
| `flatline` | longest run within epsilon meeting minimum duration and support | profile (`BLOCKING`) when stuck |
| `range_violation` | values below/above specification and control bounds, counted separately | profile (`WARNING`) when any bound is configured and violated |
| `cadence_drift` | median of the recent half of positive deltas / expected cadence | `WARNING` when ratio ≥ r or ≤ 1/r, where r is the profile's drift factor (> 1) |

If the expected cadence is absent, `cadence`, `gaps_coverage` and `cadence_drift` are reported as
`UNKNOWN:<metric>` and coverage is never invented.

In the envelope, every finding appears in `evidence.quality` (`INFO` as `<metric>`, `WARNING` as
`<metric>` and also in `warnings`, `BLOCKING` as `BLOCKING:<metric>`), preceded by
`profile:<digest>`. `n` is the number of findings, `rowsOutput` is 0 and nothing is published.

### 7.3 G8 — exact join

1. Open both inputs with `TypedTabularStream`; ThingWorx has already authorized the containing tool
   call, and the cache enforces the conversation namespace.
2. Reject PASSWORD columns on either side (`PASSWORD_COLUMN`), resolve key columns
   (`JOIN_KEY_MISSING`) and require the same type family on both sides (`NUMBER` for
   NUMBER/INTEGER/LONG, `BOOLEAN`, `DATETIME`, otherwise `STRING`; else `JOIN_TYPE_MISMATCH`).
3. Plan output columns. Key columns shared by name appear once; other same-named columns follow the
   collision policy: `ERROR` (fail with `COLUMN_COLLISION`), `PREFIX_LEFT_RIGHT` (`left_<name>`,
   `right_<name>`), or an explicit rename map with `left:<name>` and `right:<name>` entries.
   Silent last-write-wins is not possible.
4. Materialize the caller-declared build side up to `maxBuildRows`; beyond that return
   `BUDGET_EXCEEDED`. A `LEFT` join must build the right side so unmatched left rows can be emitted
   while streaming.
5. Stream the probe side once. Null keys never match (left rows with null keys stay unmatched in a
   `LEFT` join). Probe-side uniqueness, when the cardinality requires it, is checked with a key-only
   set. Cardinality violations return `CARDINALITY_VIOLATION` with at most 8 representative
   duplicate keys and counts, never row payloads.
6. Stop with `OUTPUT_BUDGET_EXCEEDED` before output exceeds `maxOutputRows`.
7. Publish the output with both parents, the route id `u4.exact_join` and merged completeness.
   Any failure returns `mayPublish:false` and no handle; the error envelope lists the reason, detail
   and `duplicateKey:<key>` warnings. A successful join with no matches is `NO_FINDING` with warning
   `no_join_matches`.

## 8. Limits

| Limit | Value | Where |
|---|---|---|
| Join build side | 5 000 rows (`maxBuildRows`) | `ExactJoinConfig` |
| Join output | 5 000 rows (`maxOutputRows`) | `ExactJoinConfig` |
| Duplicate keys reported | 8 | `ExactJoin` |
| Typed stream batch | 64 rows (join build), 256 rows (series extract) | runners |

Series operations scan the whole source table; their output size follows the window and bucket
width. Cached-table and history fetch caps are unchanged (see
[`../cached_tabular_tools.md`](../cached_tabular_tools.md)).

## 9. App Developer surface

An App Developer shapes inputs upstream: a governed ThingWorx Service or tool that produces the
cached table with a time column, numeric value columns, and (for joins) an `id` key column of the
same type family on both sides. Proprietary calculations or source-side joins belong in a typed
ThingWorx Service. App code cannot pass file paths, open cache files, inject a `SecurityContext`,
upload executable code, or relax these rules.

## 10. Code map

| Concern | Classes |
|---|---|
| Envelope | `analysis.AnalysisEnvelope`, `AnalysisEnvelopeBuilder`, `AnalysisEnvelopeValidator`, `AnalysisEnvelopeJson`, `AnalysisMethodDescriptor`, `AnalysisOperation`, `AnalysisBudgetAccounting` |
| Time and lineage | `analysis.HalfOpenWindow`, `TimeAxisNormalizer`, `CompletenessPropagation`, `DerivedArtifactLineage`, `DerivedTabularPublisher`; `cache.TypedTabularStream` |
| G3 | `transform.time.BucketAssigner`, `Resampler`, `RollingOperator`, `RateOfChange`, `PeriodComparator`, `TimeSeriesOrdering`, `TypedTimeSeries`, `*CacheRunner`, `Demo*AppProfile` |
| G6 | `quality.TimeSeriesQualityEvaluator`, `QualityProfile`, `QualityFinding`, `QualityAssessment`, `QualityEvidenceAttachment`, `QualityCacheRunner`, `DemoQualityAppProfile` |
| G8 | `join.ExactJoin`, `ExactJoinConfig`, `ExactJoinCacheRunner`, `JoinKeySpec`, `JoinCardinality`, `CollisionPolicy`, `BuildSide`, `DemoExactJoinAppProfile` |
| Tools and admission | `tools.*CachedResultExecutor`, `tools.U4SeriesToolArgs`, `join.U4OperationAdmission`, `analysis.config.U4DemoAppProfiles` |

## 11. Tests

Unit and reference tests live beside the packages (`com.thingworx.things.agent.analysis`, `.transform.time`,
`.quality`, `.join`, `.cache`) and cover envelope goldens, DST and window boundaries, ordering,
duplicates, gaps, flatline, unknown cadence, join keys, types, cardinality, collisions, unmatched
rows, PASSWORD columns, output budgets and completeness propagation. Fixture notes are in
`parler-agent/src/test/resources/nearterm/time-quality-join/`.

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```

## 12. Disable and rollback

- Each operation has an admission flag (§1): when off, the mode is not advertised and direct
  execution returns the stable `U4_*_DISABLED` reason.
- Earlier chart and cached-tabular behavior (`build_history_overlay_chart`, other
  `tabulate_cached_result` modes) is unaffected.
- Derived artifacts are transient cache entries; a ThingWorx restart clears the cache index, and no
  migration is involved.
- A disabled mode is never left in the advertised schema.
