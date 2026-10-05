# Computing enhancement — measurement modes of `tabulate_cached_result`

This document describes the deterministic measurement operators that Parler adds to
`tabulate_cached_result`: counter increments, rolling statistics, time-weighted integrals and calendar
labels. It explains what each operator computes, how it treats incomplete or read-limited input, and how
the operators combine with the existing cached-table tools.

The normative tool JSON (arguments, result fields, error codes) is
[`CONTRACTS/TABULAR_INSIGHT.md`](../../../CONTRACTS/TABULAR_INSIGHT.md) §6.3–§6.6. The model-facing parameter
reference is [cached_tabular_tools.md](../cached_tabular_tools.md). This document carries the behavioral
explanation behind those tables and does not repeat every field.

## 1. Scope and identifiers

| Mode | Method | Capability id | Delivered slice | Contract |
| --- | --- | --- | --- | --- |
| `counter_delta` | `counter_delta_v1` | CF-05 | CE-1 | TABULAR_INSIGHT §6.3 |
| `rolling_stats` | `rolling_stats_v1` | CF-52 | CE-2 | TABULAR_INSIGHT §6.4 |
| `time_weighted` | `time_weighted_v1` | CF-01 | CE-3 | TABULAR_INSIGHT §6.5 |
| `calendar_bucket` | `calendar_bucket_v1` | CF-03 | CE-4 | TABULAR_INSIGHT §6.6 |

The CF and CE identifiers are stable labels used in code comments (`AnalysisOperation`, the kernels'
Javadoc) and test data. They name the capability and the delivered operator; they are not version numbers.

Design principles that hold for all four modes:

- **Deterministic Java only.** The computation runs in Parler's own code (Java time and collection APIs,
  the Commons Math 3.6.1 dependency Parler already ships). Nothing depends on ThingWorx Analytics, which is
  not assumed to be installed.
- **No executable input.** The model selects a mode and supplies parameters. It never supplies expressions,
  scripts or code, and the operators never evaluate any.
- **No guessed rules.** Every rule that decides a number (counter modulus, reset baseline, maximum gap,
  integration method, time unit, time zone) is supplied by the caller and has no default. The effective
  values are echoed in the result metrics. The tool descriptions tell the model to omit a rule it does not
  know rather than guess it.
- **Descriptive results over read data.** The operators compute over the rows that were actually read and
  cached. They never call the platform again, never page, and never claim that a window or shift total is
  complete (§3).

Each mode is one value of the `mode` argument of `tabulate_cached_result`. There is no separate tool and no
demoted alias.

## 2. Inputs and data path

### 2.1 Where the input comes from and what a read limit means

The operators consume a cached table (a `cacheId`) produced by an earlier read or transformation. The
platform reads that feed them have these row limits:

| Input path | Default / hard limit | Read direction |
| --- | --- | --- |
| Numeric property history (`query_numeric_property_history`) | 1,000 / 5,000 rows; `maxItems` takes precedence over the legacy `maxRows` and `maxPoints` (`HistoryRowLimitPrecedence.resolve`) | Always `oldestFirst=true`; a read that hits its limit usually keeps the earlier data. |
| Non-numeric property history | Same as numeric history | Always `oldestFirst=true`. |
| Stream rows | 500 / 5,000 rows (`maxItems`) | `oldestFirst` chosen by the caller, default false; a read that hits its limit usually keeps the later data. |
| History overlay chart, per series | 5,000 rows requested per series (`HistorySeriesComposerSupport.fetchNumericHistory`) | As numeric history. The read-limit fact is recorded per series. |

How the read-limit fact travels:

1. **It is observed at read time.** `ReadLimitFact.observe` compares the number of rows the platform
   returned with the effective limit of that read (`returnedRows >= effectiveLimit`), before any filtering
   or sampling. It only says the limit was reached: the data may be exactly complete or may have more rows.
   Operators never reconstruct the fact from cached row counts, from the constant 5,000 or from the 20-row
   sample the model sees.
2. **Status and reason are separate.** `SourceDescriptorSupport.forPrimaryStore` stores platform reads as
   `UNKNOWN` completeness; `withReadLimitFact` adds the reason `READ_LIMIT_REACHED` to
   `completenessReasons` without changing status, counts or source identity. `COMPLETE` is only used when a
   producer has positive evidence; no platform read currently declares it.
3. **Public fields.** The registered reason values are in
   [TABULAR_INSIGHT §5.2](../../../CONTRACTS/TABULAR_INSIGHT.md#52-registered-completenessreasons-values).
   Read tools report `readLimitReached: true` and `readLimitNote`; the overlay chart also reports
   `readLimitReachedSeries`. `SourceDescriptorSupport.putPublicEnvelopeFields` writes `completeness` and
   `counts`, and omits `counts.totalAvailable` when the total is unknown.
4. **Derivations keep the reason.** `SourceDescriptorSupport.forDerivedStore` copies a single parent's
   reasons; `CachedTabularToolsExecutor.unionCompletenessReasons` merges the reasons of all parents of
   `union_rows` in input order without duplicates and keeps the weaker status. Aggregating, filtering or
   re-windowing never clears a reason or upgrades a status.
5. **The analysis window comes from the request.** Descriptors carry no requested window, read direction
   or per-segment coverage. Each operator takes its window as an argument and derives coverage from the real
   timestamps in its input (§3.2).

The computation path is: valid `cacheId` → scope, permission and liveness checks → budgeted read of the
full cached table → deterministic kernel → derived table published with a derived descriptor → bounded tool
summary passed through `ToolResultEgressGateway.compactForLlmAppend` to the model. The kernel always works
on the full cached table, never on the model-visible sample. The `completeness` object and the `warnings`
list are priority fields of the egress gateway and survive last-resort compaction.

### 2.2 Data-plane constraints

- **The cache stores numbers as doubles.** `NumericHistoryCacheWriter` writes the value column as NUMBER
  with `Double` values, `TabularInfotableCodec.putTyped` writes INTEGER, LONG and NUMBER as `doubleValue()`,
  and `TypedCell` only has a double NUMBER form. An operator cannot see the original LONG; an integer above
  2^53 may already be rounded when it enters the cache. `counter_delta` therefore rejects readings at or
  above 2^53 (§4).
- **Measurement envelopes may succeed under non-COMPLETE input.** `AnalysisEnvelopeValidator` normally
  requires proven `COMPLETE` completeness for `SUCCESS`. The four modes belong to
  `AnalysisOperationClass.MEASUREMENT`, which is an explicit exception: a measurement envelope may be
  `SUCCESS` under `UNKNOWN` or `PARTIAL` completeness only if `evidence.warnings` contains a warning that
  starts with the fixed token `SCOPE_OBSERVED_SPAN`. Other warnings do not satisfy the rule. The existing
  U4 series modes (`rolling`, `resample`, …) keep their own rule and report `INSUFFICIENT_EVIDENCE` on
  non-COMPLETE input.
- **No turn-level evidence aggregation.** Like the other `tabulate_cached_result` series modes, the
  measurement modes return their envelope in the tool result only; they do not call
  `AgentTaskState.recordAnalysisAssessment` (only `analyze_cached_result` does).
- **Admission.** `analysis.ComputingOperationAdmission` has one flag per mode, all enabled by default.
  While a flag is off, the mode and its mode-only properties leave the model-visible schema and a direct
  call is rejected with `CE_COUNTER_DELTA_DISABLED`, `CE_ROLLING_STATS_DISABLED`,
  `CE_TIME_WEIGHTED_DISABLED` or `CE_CALENDAR_BUCKET_DISABLED` and `mayPublish=false`. The flags are
  process-wide switches used by code and tests; there is no operator setting for them in this release.
  Shared properties (`entityColumn`, `maxGapSeconds`, `timeColumn`, the rolling window properties) are
  advertised while any mode that uses them is enabled, and their descriptions name only enabled modes.
- **No persistent rule registry.** Counter rules, gaps and time zones reach the executor only as tool
  arguments, supplied by the user's statement or a Playbook step. There is no configuration-repository file
  for them.

## 3. Shared semantics

### 3.1 Four kinds of evidence

Four facts are kept apart and never inferred from each other:

- **Source completeness** — whether the producer proved that the business source is complete
  (`completeness.status` and `completeness.reasons`).
- **Inputs fully scanned** — whether the operator read the whole cached table it was given.
- **Time coverage** — which parts of the analysis window are supported by evidence under the method's rules.
- **Statistical applicability** — sampling unit, independence, population and denominator.

Reading an `UNKNOWN` cache to the end, having first and last timestamps close to the window edges, or
reaching 100 % coverage never turns the source into `COMPLETE`.

All windows are half-open, `[windowStart, windowEnd)`, and elapsed time is computed on `Instant` values.

### 3.2 Completeness of the input and what the operators do with it

| Input status and reasons | Computation | Completeness and naming |
| --- | --- | --- |
| `COMPLETE`, no contradicting evidence | Computed normally. | The producer's status is kept. |
| `UNKNOWN`, no reason | Computed normally over the read observations, with the method's own hold rules (for example a `step_hold` tail estimate that is labelled as an estimate). | Status stays `UNKNOWN`. The absence of `READ_LIMIT_REACHED` is not a proof of completeness. |
| `UNKNOWN` with `READ_LIMIT_REACHED`, or `PARTIAL` | Computed over the data that was read. Holding, interpolation and accumulation never cross the actual observed boundary (§3.3). | Status and every reason are carried to the derived descriptor and to the model-visible result. The result is never called a window or shift total. |

A computation that is valid in permissions, liveness, numeric domain and budget succeeds as a descriptive
result under any of these statuses; it is named as covering the observed span (`scope: observed_span`).

### 3.3 Time boundary of read-limited input

When the input carries `READ_LIMIT_REACHED` or is `PARTIAL`, the parts of the analysis window before the
first and after the last real observation are unknown, whatever hold or gap parameter the caller gives.
Which end is uncovered follows only from comparing real timestamps with the window, never from the tool
name or read direction. A permissive hold parameter is not evidence for a missing end point, and
interpolated, synthesized or previously estimated points are not new observations.

For multi-source input (for example after `union_rows`), partitions are separated by the entity column;
one device's later data never fills another device's tail. After a union the reasons are a table-level
fact and apply to every partition: operators never guess from per-partition row counts which parent
reached its limit.

Shared reference case (used by the tests of `counter_delta`, `rolling_stats` and `time_weighted`): an
analysis window 06:00–14:00, valid observations of 1 kW from 06:00 to 09:40, and a hold long enough to
reach 14:00. With `READ_LIMIT_REACHED`, only the 220 observed minutes are integrated (11/3 kWh), the 260
minutes after 09:40 are unknown and coverage is 11/24; the operator does not report 8 kWh or 100 %. The
same data without the reason gives the 8 kWh in-window estimate under the declared hold, still labelled
`UNKNOWN` and with the tail marked as an estimate. Symmetrically, observations only from 10:20 to 14:00
leave 06:00–10:20 unknown, and nothing is filled backwards.

### 3.4 No extra platform reads

Operators read the descriptor of their input cache and never rebuild `READ_LIMIT_REACHED` from summaries
or row counts. A limited input never triggers another platform read, a larger limit, a sentinel row,
automatic paging or a retry.

### 3.5 Common mechanics of the four modes

| Item | Rule |
| --- | --- |
| Result shell | The U4 result shell of TABULAR_INSIGHT §6 (`status`, `reason`, `mayPublish`, `findingCacheId`, `analysisEnvelope`), plus root `warnings` (identical to `analysisEnvelope.evidence.warnings`) and the §5 `completeness` / `counts` / `sourceCacheId` packaging on success. |
| Envelope status | `SUCCESS` when the mode produced at least one usable value (per mode below), also under `UNKNOWN` or `PARTIAL` completeness; otherwise `INSUFFICIENT_EVIDENCE`, with `metrics.outcome` naming the case. `NO_FINDING` is not used. Argument, rule, domain and budget failures use the U4 error shell and publish nothing. |
| Scope warning | The first warning always starts with `SCOPE_OBSERVED_SPAN`. When the source carries `READ_LIMIT_REACHED` or is `PARTIAL`, it adds that the result is not a window or shift total. The envelope construction writes it unconditionally. |
| Warnings | At most four (three for `calendar_bucket`), in a fixed order per mode, each present only under its stated condition. |
| Derived table | Published through `DerivedArtifactLineage.forTransform` with route `ce.counter_delta`, `ce.rolling_stats`, `ce.time_weighted` or `ce.calendar_bucket`. The parent status is never upgraded; parent reasons are copied unchanged. Derived tables carry no subject identity. |
| Timestamps | Instants that the operator publishes as DATETIME cells must have whole-millisecond precision and a year from 0001 to 9999, else `TIMESTAMP_UNSUPPORTED`. Elapsed time is computed as seconds plus a nanosecond fraction, so it cannot overflow. |
| Row limits | At most 100,000 readings or rows (`INPUT_TOO_LARGE`) and, where partitions exist, 50 partitions (`TOO_MANY_PARTITIONS`). Limits fail explicitly; nothing is truncated, sampled or approximated. |
| Operation budget | One wall-time deadline from the invocation's `BudgetVector` covers reading, computing and publishing. Deadline and thread interruption are checked per read batch, every 1,024 kernel items, per partition and immediately before publication (`OperationGuard`). The deadline travels with the output through `cache.PublicationGuard` into `TabularArtifactHub.store` / `DerivedTabularPublisher.publish`, so the writer only gets the remaining time. Exhaustion is `TIME_BUDGET_EXCEEDED`, cancellation is `OPERATION_CANCELLED` (the interrupt flag is kept); neither publishes a table. `analysisEnvelope.budget` carries requested, effective and consumed values. |
| Columns | A missing `timeColumn`, `valueColumn` or `entityColumn` in the cached table is `COLUMN_NOT_FOUND`, not a silent missing value. |

## 4. CE-1 — `counter_delta` (CF-05): counter increments with reset and rollover rules

Computes the increments of a cumulative counter (energy meter, production counter) between real readings.
It is not `rate_of_change`: it separates exact increments from lower bounds and from intervals whose
increment cannot be determined. Contract: TABULAR_INSIGHT §6.3.

**Arguments.** Required: `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`. Optional:
`entityColumn` (partition key) and four caller-supplied rules without defaults:

| Rule | Meaning and domain |
| --- | --- |
| `counterModulus` | Rollover modulus, in reading units; positive. Requires `maxRatePerSecond`; excludes `resetBaseline`. |
| `maxRatePerSecond` | Largest plausible increase, in reading units per second; positive. |
| `maxGapSeconds` | Longest interval that may carry an increment; positive whole number of seconds. |
| `resetBaseline` | Value the counter restarts from after a reset; non-negative. |

Each rule is finite and at most 2^53. The cap is checked on the JSON number as written, before it narrows
to a double: `9007199254740993` is refused rather than rounded to the permitted `9007199254740992`, and a
non-integer literal at or above 2^53 is refused too. Violations are `COUNTER_RULE_INVALID`. The executor
cannot verify units; it echoes the effective rules in `metrics.assumptions`. Omitting the rules never
blocks ordinary increments; it only leaves negative jumps undetermined.

**Reading domain.** Every included reading must be finite and satisfy `0 ≤ reading < 2^53`, else the whole
request fails with `COUNTER_DOMAIN_UNSUPPORTED`. The strict upper bound matters: the raw integer 2^53+1 is
rounded to exactly 2^53 in the cache, so rejecting only values above 2^53 would let it through. Because
rounding is monotonic and 2^53 is representable, every raw integer above 2^53−1 is at least 2^53 after
caching and is rejected, and every raw integer up to 2^53−1 is exact in a double. A reading at or above a
declared modulus, or below a declared baseline, contradicts the caller's rule and fails with
`COUNTER_RULE_CONTRADICTED`.

**Arithmetic.** `metrics.arithmetic` is `exact_integer` when every included reading and the given modulus
and baseline are integer-valued: differences, rollover corrections `(modulus − previous) + next` and
`next − baseline` are then exact, and a running total reaching 2^53 fails the request with
`COUNTER_DOMAIN_UNSUPPORTED` instead of reporting a rounded total. Otherwise it is `float64`, ordinary binary64
arithmetic. `maxRatePerSecond` and `maxGapSeconds` only bound comparisons and do not affect the arithmetic
mode; comparisons with the rate bound use binary64 and "not more than".

**Rows.** Rows with an unparseable time, a null / non-numeric / non-finite value or an empty `entityColumn`
are counted and excluded, never turned into 0; neighbouring valid readings still form a segment, subject
to `maxGapSeconds`. After filtering by the half-open window, each partition is sorted stably by
(time, source ordinal). Identical readings at one instant collapse and are counted. Different readings at
one instant are a **continuity barrier**: that instant is not an endpoint of any valid segment and no
segment spans it.

**Segment classification.** Let `d` be the later reading minus the earlier one and `B` be
`maxRatePerSecond` × elapsed seconds (only when a rate is given). Segments form between adjacent instants of
a partition, and the first matching rule applies:

1. Either end is a conflicting instant → `BROKEN_BY_CONFLICT`, no increment. One row is written on each
   side of the conflict, so the span stays visible as unknown.
2. Elapsed time exceeds `maxGapSeconds` → `GAP_EXCEEDED`, no increment.
3. A modulus is declared. If `B` is not below the modulus, the number of wraps cannot be decided →
   `WRAP_UNIDENTIFIABLE`, no increment, whatever the sign of `d` (also when `d` is 0). Otherwise at most one
   wrap happened: the candidate is `d` when `d ≥ 0` and `(modulus − previous) + next` when `d < 0`. A
   candidate within `B` is `NORMAL` or `ROLLOVER` respectively; one above `B` is `IMPLAUSIBLE_INCREASE` or
   `UNCERTAIN_NEGATIVE_JUMP`, no increment.
4. A baseline is declared. For `d < 0` the candidate is `next − baseline`: above `B` (when a rate is given)
   it is `UNCERTAIN_NEGATIVE_JUMP`, otherwise `RESET` with that increment and `lowerBound=true`, because the
   amount before the reset is unknown and is not filled with zero. For `d ≥ 0`: `d` above `B` is
   `IMPLAUSIBLE_INCREASE`; if a rate is given and `next − baseline` is within `B`, an unobserved reset inside
   the interval also fits every constraint, so the segment is `POSSIBLE_HIDDEN_RESET` with increment `d` and
   `lowerBound=true`; otherwise `NORMAL`. The rate check comes before the hidden-reset check.
5. A plain counter (no modulus, no baseline): `d ≥ 0` is `NORMAL`, or `IMPLAUSIBLE_INCREASE` when above `B`;
   `d < 0` is always `UNCERTAIN_NEGATIVE_JUMP`.

A partition with a single valid reading gives one `SINGLE_READING` row without an increment.

**Known limitation of the method.** Without `maxRatePerSecond`, a reset between two readings followed by a
climb past the previous value cannot be detected, for a plain counter and for one with a baseline. This is
inherent in sampled data. Whenever the request has no rate, the result carries this limitation in warning
(3), even if every segment is `NORMAL`.

**Totals.** `knownDelta` sums exact segments (`NORMAL`, `ROLLOVER`); `lowerBoundDelta` sums lower-bound
segments (`RESET`, `POSSIBLE_HIDDEN_RESET`). The two are never added. `scope` is always `observed_span`: no
current producer declares `COMPLETE`, so the mode never claims a window or shift total.

**Read-limit invariant.** The method only takes differences between two real readings and never
extrapolates, so `READ_LIMIT_REACHED` or `PARTIAL` changes no classification, increment or total; it only
changes the scope text. For a single partition, `metrics` also give `firstReading`, `lastReading`,
`uncoveredHeadSeconds` and `uncoveredTailSeconds`; with several partitions each partition's boundaries are
the first and last segments of the output table.

**Output table.** `entity`, `segmentStart`, `segmentEnd`, `startReading`, `endReading`, `delta`,
`classification`, `lowerBound`, `elapsedSeconds`. Reading cells are empty where a conflicting instant is an
endpoint. Downstream steps read `segmentEnd`, `delta` and `lowerBound`; per-partition sums come from
`group_metric` over `delta`.

**Envelope.** `SUCCESS` with `n` = segments carrying an increment (exact or lower bound) when there is at
least one; otherwise `INSUFFICIENT_EVIDENCE` with `outcome` `NO_READINGS` (no valid reading in the window)
or `NO_KNOWN_SEGMENT`.

**Warnings, in order.** (1) `SCOPE_OBSERVED_SPAN`, always. (2) Segments without an increment, by
classification, when any. (3) Lower-bound segments, and the undetectable-reset limitation when no rate was
given; present when either applies. (4) Excluded rows and conflicting instants, when any.

**Metrics.** `knownDelta`, `lowerBoundDelta`, per-classification counts, partition count, exclusion
counts, `assumptions`, `arithmetic`, `scope`, `method=counter_delta_v1`.

Worked examples (all in the reference data):

- Plain counter 100 → 130 is `NORMAL`, 30. A negative jump without rules is `UNCERTAIN_NEGATIVE_JUMP`.
- Modulus 100, `B` = 20: 95 → 3 is `ROLLOVER`, 8. With `B` = 5 the same readings are
  `UNCERTAIN_NEGATIVE_JUMP`. Modulus 100, 10 → 20 over 10 s with rate 20 gives `B` = 200 ≥ modulus, so 10 and
  110 both fit: `WRAP_UNIDENTIFIABLE`, not `NORMAL` 10.
- Baseline 0, no rate: 500 → 20 is `RESET`, 20, `lowerBound=true`, counted in `lowerBoundDelta` only.
  Baseline 0, rate 1, 100 → 150: over 10 s `IMPLAUSIBLE_INCREASE`; over 50 s or 100 s `NORMAL` 50; over 150 s
  or 200 s `POSSIBLE_HIDDEN_RESET` 50 as a lower bound.
- Conflict: t0 = 100, t1 has both 200 and 10, t2 = 150 → two `BROKEN_BY_CONFLICT` rows and no increment of
  50. If t1 holds a null value instead, t0 → t2 is one segment.
- A LONG column holding 9007199254740991 and 9007199254740993 caches as …991 and …992; the request is
  rejected with `COUNTER_DOMAIN_UNSUPPORTED` rather than reporting an increment of 1.
- §3.3 case: readings 600 at 06:00 and 820 at 09:40, window to 14:00. With and without
  `READ_LIMIT_REACHED` the table, `knownDelta` (220) and `lowerBoundDelta` are identical and the uncovered
  tail is 15,600 s; only the scope text and `completeness.reasons` differ.

## 5. CE-2 — `rolling_stats` (CF-52): rolling statistics per source record

Computes one statistic over rolling windows anchored at each source record. It leaves the existing
`rolling` mode (mean only, U4 status behavior) unchanged. Contract: TABULAR_INSIGHT §6.4.

**Arguments.** Required: `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`, `statistic`.
Optional: `entityColumn` and the window properties shared with `rolling`, with the same defaults and error
codes: `rollingKind` (`OBSERVATION_COUNT` default, or `ELAPSED_DURATION`), `observationWindow` (5),
`durationWindowSeconds` (3600), `minSupport` (1). One statistic per call keeps the output shape fixed.

**Statistics.** `mean`, `sum`, `min`, `max`, `stddev` (sample standard deviation, n−1), `count_values`,
`count_records`; anything else is `STATISTIC_INVALID`.

**Window members.** Records in a partition are ordered by (time, source ordinal). The window anchored at a
record contains records at or before it in that order: the last N for an observation window; for an
elapsed window, those whose elapsed time to the anchor is at most D, both ends inclusive (`[t−D, t]`, as in
the existing `rolling`). Membership compares the elapsed time between two real records rather than
constructing `t − D`, so very large `durationWindowSeconds` values work. Records sharing an instant are all
kept, and an earlier one does not include peers ordered after it: `(0 s, 1)`, `(60 s, 3)`, `(60 s, 5)` with
D = 60 s give sums 1, 4, 9. Windows never cross `entityColumn` values. Duplicate instants are not
continuity barriers here because no differences are taken.

**Rows.** Rows with an unparseable time or an empty `entityColumn` are counted and excluded. A row with a
null / non-numeric / non-finite value **stays a record** and is only left out of the valid values (unlike
`counter_delta`, which excludes it).

**Support and empty values.** `records` is the number of window members (at least 1, the anchor) and
`support` the number of valid values among them.

| Statistic | `minSupport` compared with | Minimum sample | `valueStatus` without a value |
| --- | --- | --- | --- |
| `mean`, `sum`, `min`, `max` | `support` | 1 valid value | `BELOW_MIN_SUPPORT` |
| `stddev` | `support` | 2 valid values | `BELOW_MIN_SUPPORT`, or `BELOW_MIN_SAMPLE` when `minSupport` is met with one value |
| `count_values` | `records` | none | `BELOW_MIN_SUPPORT` |
| `count_records` | `records` | none | `BELOW_MIN_SUPPORT` |

The two counts use `records` as the admission denominator, otherwise records with missing values could
never be counted; a count of 0 is a measured value. An all-missing `sum` is empty, not 0. Rows with a value
have `valueStatus=OK`.

**Numeric domain and precision.** Every valid value must have an absolute value of at most 1e150, else the
request fails with `VALUE_MAGNITUDE_UNSUPPORTED` and nothing is published. Each window is recomputed from
its members (no add-new/subtract-old sliding sums) with compensated (Neumaier) summation. `stddev` uses the
rounded mean only as a centre `c`: with `d = v − c` the variance is `(Σd² − (Σd)²/n)/(n−1)`, which is exact
for any `c`, so a small spread on a large offset is not inflated (`1e16` and `1e16 + 2` give √2). It is
computed in scaled form (deviations divided by their largest magnitude, the scale multiplied back after the
square root), so small spreads do not underflow (`[−1e-200, 1e-200]` gives about 1.414e-200). A window whose
`min` equals its `max` has that `mean` and a `stddev` of exactly 0. A non-finite result, or a zero `stddev`
for a window whose members differ, fails the request with `NUMERIC_RESULT_UNSUPPORTED`; a numeric failure
is never written as a missing value. The mode does not use `analysis.stats.StableMeanVariance`, whose
unscaled sums overflow on large finite values.

**`warmedUp`.** A frame-availability marker only: for an elapsed window, the elapsed time from the
partition's first record to the anchor is at least D; for an observation window, `records` equals N. It
does **not** establish that the window interior was observed. With records only at 10:00 and 12:00 and
D = 1 h, the 12:00 row is warmed up with `records=1`. Rows that are not warmed up still carry values;
nothing is filled, padded or extended past the last record.

**Read-limit invariant.** Windows only hold records that were read, so `READ_LIMIT_REACHED` or `PARTIAL`
changes no value, only the scope text.

**Output table.** `entity`, `timestamp`, `windowStart`, `windowEnd` (instants of the first and last
member), `value`, `valueStatus`, `support`, `records`, `warmedUp`.

**Envelope.** `SUCCESS` when at least one row has a value (a count of 0 is a value); otherwise
`INSUFFICIENT_EVIDENCE` with `outcome` `NO_READINGS` or `NO_SUPPORTED_WINDOW`.

**Warnings, in order.** (1) `SCOPE_OBSERVED_SPAN`, always, stating that windows contain only read records
and window coverage is not established. (2) Rows without a value, per `valueStatus`. (3) Rows not warmed
up. (4) Excluded rows.

**Limits.** 100,000 records and 50 partitions. The total window work, the sum of member counts including
records without a value, is counted exactly with two pointers before any aggregation and capped at
5,000,000 (`WINDOW_WORK_TOO_LARGE`), so a sparse elapsed input is not rejected for its nominal window size.
At 100,000 records with an observation window of 50 the work is 4,998,775 and runs in well under a second.

## 6. CE-3 — `time_weighted` (CF-01): time-weighted integral and time mean

Computes the integral and the time mean of **one numeric property-history series** inside one window.
Every millisecond of the window is classified `OBSERVED`, `HELD` or `UNKNOWN`. Contract:
TABULAR_INSIGHT §6.5.

Unlike `counter_delta` and `rolling_stats`, this mode contains an estimate (the `step_hold` tail), so the
source's read-limit reason changes one numeric component. The mode reports that component separately.

**Arguments.** Required: `cacheId`, `timeColumn`, `valueColumn`, `windowStart`, `windowEnd`,
`integrationMethod` (`step_hold` or `trapezoid`), `maxGapSeconds` (positive integer, at most 2^53),
`timeUnit` (`seconds`, `minutes`, `hours`). None of the three method arguments has a default. `entityColumn`
is refused (`ARGUMENT_UNSUPPORTED`). Invalid values are `INTEGRATION_METHOD_INVALID`, `TIME_UNIT_INVALID`,
`MAX_GAP_INVALID` or `WINDOW_INVALID`. Because segments are clipped to the window and the window edges are
published as DATETIME cells, `windowStart` and `windowEnd` must also be whole milliseconds with a year from
0001 to 9999 (`TIMESTAMP_UNSUPPORTED`); this check is local to the mode.

**Source admission.** Integration across intervals needs evidence that two readings belong to one
continuous series, and `maxGapSeconds` cannot provide it. The mode therefore relies on a fact the writer
declares: the descriptor's subject identity (`subjectThingName` and `subjectPropertyName`). It is set only
by `SourceDescriptorSupport.withSubjectIdentity`, called only from `NumericHistoryCacheWriter` for a
numeric property-history read of one Thing property (routes `query_numeric_property_history` and
`build_history_overlay_chart`), which also declares the column roles. Derivations never copy it
(`SourceDescriptor.composeDerived`, `forDerivedStore`).

The source is admitted only when the descriptor exists, carries both subject fields, has an empty parent
list, and declares column roles equal to the requested `timeColumn` and `valueColumn`. Anything else fails
with `SOURCE_CONTINUITY_UNKNOWN` before any row is read, and nothing is published. An empty parent list
alone is not evidence (a `group_metric` over a `union_rows` result is stored with an empty parent list and
route `invoke_service`), and no route name is trusted. In effect service result tables, Stream rows,
multi-device tables and every derived table (`union_rows`, `group_metric`, `filter_rows`, this mode's own
output) are refused. A requested column that differs from the declared roles is reported as
`SOURCE_CONTINUITY_UNKNOWN`, so `COLUMN_NOT_FOUND` is effectively unreachable in this mode.

**Units.** The integral is in "value unit × `timeUnit`" (kW with `hours` gives kWh); `metrics.integralUnit`
echoes for example `value × hours`. The executor knows no value unit and converts nothing. The mean has the
value's unit. Durations are always reported in seconds.

**Rows.** Rows with an unparseable time and rows with a null / non-numeric / non-finite value are counted
and excluded (as in `counter_delta`). Identical rows at one instant collapse; differing values at one
instant make it a continuity barrier and both neighbouring stretches are `UNKNOWN`. Reading timestamps must
be whole milliseconds; values above an absolute value of 1e150 fail with `VALUE_MAGNITUDE_UNSUPPORTED`.

**Anchors outside the window.** Besides the readings inside the window, the reader keeps the nearest
**timestamp group** on each side: the latest instant before `windowStart` and the earliest instant at or
after `windowEnd` (a reading exactly at `windowEnd` is the right anchor). Identical anchor rows collapse; a
conflicting anchor is a barrier and is not skipped in favour of a farther reading. An anchor group is
validated as a whole, so a value above the admitted magnitude refuses the request wherever it stands among
the group's rows; a group replaced by a nearer one is not validated. The per-side state is bounded. Anchors
only supply end evidence; integral and durations are clipped to `[windowStart, windowEnd)`.

**`trapezoid`.** Two neighbouring readings at most `maxGapSeconds` apart form an `OBSERVED` segment whose
integral is the mean of the end values times the duration. At a clipped window edge the value is
interpolated linearly between the two real readings. A longer interval is entirely `UNKNOWN`. This method
never estimates: before the first and after the last reading the window is `UNKNOWN` unless an anchor
exists.

**`step_hold`.** A value is valid from its own instant for at most `maxGapSeconds`; validity does not
restart at a window edge. Neighbours at most `maxGapSeconds` apart form an `OBSERVED` segment worth the
left value times the duration. Farther apart, the first `maxGapSeconds` after the left reading are `HELD`
and the rest `UNKNOWN`. After the last reading the value is `HELD` for at most `maxGapSeconds` and not past
`windowEnd`. Before the first reading nothing can be held unless there is a left anchor, which follows the
same rule: anchor 10 s before a window `[0, 60) s` with value 2, next reading at 100 s, `maxGapSeconds=30`
gives `HELD` `[0, 20)` with integral 40 (`seconds`) and `UNKNOWN` `[20, 60)`. `HELD` is an estimate: no
later reading confirms the value.

**Read-limited source.** When the source carries `READ_LIMIT_REACHED` or is `PARTIAL`, the `HELD` stretch
after the last reading is cancelled and becomes `UNKNOWN`, because unread data may follow.
`metrics.cancelledTailHoldSeconds` says how much. `HELD` stretches between two readings are unaffected. An
`UNKNOWN` source without a reason is estimated normally and labelled.

**Totals.** `observedIntegral` sums `OBSERVED` segments, `estimatedIntegral` sums `HELD` segments, and
`integral` is their sum. Whether the result contains an estimate is decided by `estimatedSeconds > 0`
(`metrics.containsEstimate`), never by `estimatedIntegral`: a held 0, or holds that cancel out, integrate
to 0 over a non-zero estimated time. `observedSeconds + estimatedSeconds + unknownSeconds = windowSeconds`,
exact to the millisecond. `timeWeightedMean` is the integral divided by the covered time
(`observedSeconds + estimatedSeconds`); with no covered time the integrals and the mean are absent, not 0.
`coverage` is covered time over window time. `scope` is always `observed_span`.

**Numeric method.** Segment areas are formed from the two original readings and the time offsets and are
accumulated in value × milliseconds as **exact rationals**: an unclipped trapezoid is `(va + vb) × E ÷ 2`
and needs no division by the elapsed time; only the at most two segments clipped by a window edge keep
their elapsed time as a denominator. Nothing is rounded before the sum, so large segments that cancel keep
their exact residual (`A`, `1`, `−A` one second apart integrate to 1 with mean 0.5 for every admitted `A`).
Each published number is formed by one division of the exact total (50 significant digits, then binary64)
and is rounded once. `timeWeightedMean` is computed from the unscaled area and the covered milliseconds, so
it is identical in the three units, and an integral that rounds to 0 in a coarse unit still has a mean.
`startValue` and `endValue` are display cells and are not inputs of the area. There is no minimum
magnitude. A non-finite result fails with `NUMERIC_RESULT_UNSUPPORTED`.

**Output table.** One row per segment, tiling the whole window, adjacent `UNKNOWN` stretches merged:
`segmentStart`, `segmentEnd` (clipped), `startValue`, `endValue` (both the held value for `step_hold`),
`seconds`, `supportedDuration` (duration in `timeUnit`), `integral`, `coverage`. On `UNKNOWN` rows the two
values, `supportedDuration` and `integral` are empty. The table is always published, an all-`UNKNOWN` one
included. It carries no subject identity, so it cannot be fed back into this mode.

**Envelope.** `SUCCESS` when any time is covered, also when the window contains no reading and is covered
only from its two anchors (readings (0 s, 0) and (10 s, 10), window `[2, 8) s`, `trapezoid`: integral 30,
mean 5, 6 s covered). Otherwise `INSUFFICIENT_EVIDENCE`: `NO_READINGS` when there is no reading in the
window and no non-conflicting anchor, `NO_COVERED_SEGMENT` otherwise (for example a single reading with
`trapezoid`).

**Warnings, in order.** (1) `SCOPE_OBSERVED_SPAN` with covered and unknown seconds, always. (2) Number and
total seconds of unknown segments. (3) The estimate: its seconds and `estimatedIntegral` when
`estimatedSeconds > 0`, and the cancelled seconds when the tail hold was cancelled. (4) Excluded rows,
collapsed duplicates and conflicting instants, anchors included.

**Limits.** At most 100,000 readings inside the window (`INPUT_TOO_LARGE`); the anchors are not counted.
Single linear pass; at most twice the readings plus two output segments.

Worked examples (reference data): 0 and 10 over 10 s give `trapezoid` 50 / mean 5 and `step_hold` 0 / mean
0. The §3.3 case with `step_hold`, `timeUnit=hours`, `maxGapSeconds=15600` and readings every ten minutes
from 06:00 to 09:40: with `READ_LIMIT_REACHED`, `observedIntegral` 11/3 kWh, `estimatedIntegral` 0,
`unknownSeconds` 15,600, coverage 11/24; without the reason, `observedIntegral` 11/3, `estimatedIntegral`
13/3, `integral` 8 kWh, `estimatedSeconds` 15,600.

## 7. CE-4 — `calendar_bucket` (CF-03): local calendar day and hour labels

Labels every row of a cached table with the local calendar day or hour its time falls in. It labels and
does not aggregate: aggregation is the existing `group_metric` grouped by `bucketLabel`, so counting events
per start day and summing readings per day use the same existing path. It is not a single-window series
mode. Contract: TABULAR_INSIGHT §6.6.

**Arguments.** Required: `cacheId`, `timeColumn`, `timeZone`, `calendarBucket` (`day` or `hour`). A missing
`timeColumn` is `TIME_AXIS_MISSING`, other missing arguments `ARGUMENT_MISSING`; invalid values are
`TIME_ZONE_INVALID` or `CALENDAR_BUCKET_INVALID`. `valueColumn`, `entityColumn`, `windowStart` and
`windowEnd` are refused (`ARGUMENT_UNSUPPORTED`), because silently ignoring them would let the caller
believe rows were filtered or partitioned.

**Time zone.** An IANA region id (`Europe/Berlin`) or `UTC`, with no default: the zone decides which day a
row belongs to, so neither the server zone nor a guess from data is used. Fixed offsets (`+02:00`,
`GMT+2`) are refused because they carry no daylight-saving rule, and so are ids that only name one
non-zero fixed offset (`Etc/GMT-2`, `Etc/GMT+5`, `SystemV/EST5`). Zero-offset UTC aliases (`Etc/UTC`,
`GMT`) and geographical ids are admitted, including places without clock changes (`Asia/Tokyo`,
`America/Phoenix`).

**Source.** Any cached table. The mode computes nothing across rows and claims no continuity or coverage,
so the `time_weighted` source restriction does not apply.

**Assignment.** A row is assigned by the instant in its own `timeColumn` only, parsed by
`TimeAxisNormalizer.toUtcInstant` (DATETIME, epoch-millisecond number, ISO-8601 text). A non-finite numeric
time (`NaN`, `±Infinity`) is treated as no usable time, never as epoch 0. Instants whose calendar
arithmetic falls outside the supported date range fail with `TIMESTAMP_UNSUPPORTED`.

**Calendar-day column for event rows.** Called on an event table's **start** column with `day`,
`bucketLabel` is the event's start day and an event that crosses midnight counts wholly on its start day.
The tool cannot know that the column is a start time, so it is documented as "assigned by the time in
`timeColumn`". The column is not duration allocated per day and not daily utilization; labelling the same
table by its end column gives a different grouping.

**Bucket definition.** A bucket is the maximal contiguous interval of instants that share one local key:
the local date for `day`, and (local date, local hour, UTC offset) for `hour`. Buckets are half-open; an
instant on an edge belongs to the later bucket. Four properties hold for both kinds: the bucket contains
its instant, every instant in it has the same key, buckets do not overlap, and buckets of one zone tile the
time axis.

- *Hour algorithm.* Take the offset `o` and local time `L` of instant `t`; the nominal start is `L`
  truncated to the hour, converted back with `o`, and the nominal end is 3,600 s later. The start is
  clamped to the latest offset transition at or before `t`, the end to the first transition after `t`.
- *Day algorithm.* Let `D` be the local date of `t`. Backwards: within the offset segment containing `t`,
  convert local midnight of `D` with that segment's offset; if it is strictly later than the segment's
  starting transition, it is the start. Otherwise look at the local date just before the transition: if it
  is still `D`, continue in the previous segment, else the start is the transition. Forwards symmetrically
  with local midnight of `D+1`, which must be strictly earlier than the next transition. The strictness
  matters for rollbacks exactly at local midnight (`America/Cuiaba` 1950-04-16, `Asia/Aden` 1947-03-14).

Consequences: `bucketSeconds` is the real length. A day is 82,800 or 90,000 s on switch days, or other
values in historic zones; an hour is shorter than 3,600 s where a sub-hour transition cuts it
(`Australia/Lord_Howe`: two 1,800 s buckets). A nonexistent local hour has no bucket; a repeated local hour
gives two buckets with different offsets and labels. A rollback after midnight makes a local date occur
twice (`America/Goose_Bay`, 1988-10-30: intervals of 86,400, 60, 7,140 and 86,400 s alternating between
October 29 and 30); each occurrence is its own interval and both carry the same `bucketLabel`.

The mode does not use `transform/time/BucketAssigner`. Its `calendarHour` (truncate to the hour, add one
elapsed hour) is wrong in zones with sub-hour transitions, and its `calendarDay` (midnight of the day to
midnight of the next) misses dates that recur after a post-midnight rollback. Those methods have no callers
in main code, so no shipped behavior depends on them.

**Output table.** Every source column in its order and type and every row in its order, followed by
`bucketStart` (DATETIME), `bucketEnd` (DATETIME, exclusive), `bucketLabel` (STRING) and `bucketSeconds`
(NUMBER). `bucketLabel` is the ISO local date (`2026-03-29`) for `day` and the local hour with its offset
(`2026-10-25T02:00+02:00`) for `hour`, also for a bucket whose start was clipped. The label is the grouping
key; the interval columns describe the contiguous stretch the row lies in, so one row's `bucketSeconds` is
not the label's total length when a label recurs. A source column already named like one of the four new
columns is refused with `COLUMN_NAME_CONFLICT`.

Source rows come from the ordinary cache read of the decoded InfoTable (`TabularArtifactHub.lookup`), not
from the typed stream reader, which reads JSON object and array cells as NULL.
Every source cell therefore equals the cell an ordinary cache read returns, LOCATION, TAGS and nested
INFOTABLE cells included. The mode adds no loss and repairs none that the cache representation already has
(for example integers stored as doubles, §2.2).

**Unassigned rows.** Rows with an empty or unparseable time, including non-finite numeric times, are kept
with the four bucket cells empty and counted in `unassignedRows`. Rows are never dropped. `group_metric`
gathers such rows in one group with an empty key.

**No empty buckets.** A day or hour without rows does not appear in the output; it is absent, not 0.
Because the output never has more buckets than rows, there is no separate bucket-count limit.

**Envelope.** `SUCCESS` when at least one row received a bucket; otherwise `INSUFFICIENT_EVIDENCE` with
`outcome` `NO_ROWS` or `NO_ASSIGNED_ROW`. The table is published whenever the source has rows. Its
descriptor carries no column roles or subject identity, so it cannot be used as `time_weighted` input.

**Metrics.** `timeZone`, `calendarBucket`, `rows`, `assignedRows`, `unassignedRows`, `distinctBuckets`
(intervals, counted by start), `distinctLabels`, `recurringLabels` (labels with more than one interval),
`firstBucketStart`, `lastBucketEnd`, `nonStandardDayBuckets` (day buckets that are not 86,400 s; `day` only),
`tzdbVersion` (the rules version of `java.time.zone.ZoneRulesProvider`), `bytesMetered` (`false`: the
ordinary cache read meters no bytes, so the consumed-bytes figure is not a claim of zero).

**Warnings, in order.** (1) `SCOPE_OBSERVED_SPAN`, always: a label describes the row's own instant, a bucket
with rows is not thereby fully observed, a bucket without rows is absent and not 0; with a limited source,
counts and sums per bucket are not that day's (hour's) total. (2) Rows without a usable time, when any.
(3) Always: the zone and bucket kind, plus the number of non-standard day buckets and of recurring labels
when non-zero.

**Limits.** At most 100,000 rows (`INPUT_TOO_LARGE`), judged on the row count before any work; bucket
edges outside years 0001–9999 fail with `TIMESTAMP_UNSUPPORTED`. Single pass with the shared operation
budget. Source rows may carry sub-millisecond times because the mode does not republish them.

## 8. Combining the operators

The operators publish ordinary derived tables, so they compose with the existing cached-table modes:

- **Daily totals of a counter.** `counter_delta` → `calendar_bucket` on `segmentEnd` → `group_metric`
  `sum(delta)` grouped by `bucketLabel`. The result is the sum of known increments per day, not a daily
  total; lower bounds are visible through the `lowerBound` column.
- **Events per start day.** `calendar_bucket` on the event start column with `day` → `group_metric` count
  grouped by `bucketLabel`.
- **Comparing several series with `time_weighted`.** Call the mode once per series with the same
  `timeUnit`, combine the **output tables** with `union_rows` and a label column, then `group_metric` by the
  label with `sum` of `integral`, `sum` of `supportedDuration` and `derived` `ratio` of the two. Both columns
  are empty on `UNKNOWN` rows, so unknown time never enters the denominator, and an all-unknown series has
  an empty mean, not 0. A mean recomposed this way carries the rounding of the published cells.
- **Per-partition totals.** `group_metric` over `counter_delta` output grouped by `entity`.

`time_weighted` cannot consume derived tables (§6), so computed quantities such as a power derived row by
row cannot be integrated with it.

## 9. Code, tests and reference data

| Mode | Kernel | Cache runner | Executor |
| --- | --- | --- | --- |
| `counter_delta` | `transform/time/CounterDelta` | `transform/time/CounterDeltaCacheRunner` | `tools/CounterDeltaCachedResultExecutor` |
| `rolling_stats` | `transform/time/RollingStats` | `transform/time/RollingStatsCacheRunner` | `tools/RollingStatsCachedResultExecutor` |
| `time_weighted` | `transform/time/TimeWeightedIntegral` | `transform/time/TimeWeightedCacheRunner` | `tools/TimeWeightedCachedResultExecutor` |
| `calendar_bucket` | `transform/time/CalendarBucketLabeler` | `transform/time/CalendarBucketCacheRunner` | `tools/CalendarBucketCachedResultExecutor` |

Paths are relative to `parler-agent/src/main/java/com/thingworx/things/agent/`. Kernels have no cache or
LLM dependency. Shared pieces: `MeasurementSeriesReader` (streamed read, row handling, the anchored read
`readWithAnchors`, guarded publication), `OperationGuard`, `MeasurementException` (with the counter-specific
subclass `CounterDelta.CounterDeltaException` for rule and domain failures), `MeasurementRunResult` and `tools/MeasurementToolResultJson`. Dispatch is
in `CachedTabularToolsExecutor.doTabulate`, the schema in `TabulateCachedResultToolSchema`, admission in
`analysis/ComputingOperationAdmission`, the operations in `AnalysisOperation` (`COUNTER_DELTA`,
`ROLLING_STATS`, `TIME_WEIGHTED`, `CALENDAR_BUCKET`, class `MEASUREMENT`), and the discovery-only demo App
surface in `analysis/config/ComputingDemoAppProfiles`.

Tests (under `parler-agent/src/test/java/…/agent/`):

- Kernels: `transform/time/CounterDeltaTest`, `RollingStatsTest`, `TimeWeightedIntegralTest`,
  `CalendarBucketLabelerTest` (includes a property test over every explicit transition of every zone in the
  local JDK rules, with no exemption list, plus minute sampling around 2026 rule-based transitions).
- Budgets: `CounterDeltaCacheRunnerBudgetTest`, `RollingStatsCacheRunnerBudgetTest`,
  `TimeWeightedCacheRunnerBudgetTest`, `CalendarBucketCacheRunnerBudgetTest` (deadline at every clock read,
  interruption during output creation, no visible derived artifact on failure).
- Tool path: `tools/TabulateCounterDeltaModeTest`, `TabulateRollingStatsModeTest`,
  `TabulateTimeWeightedModeTest`, `TabulateCalendarBucketModeTest` — from a valid cache through the real
  schema, admission and dispatcher to the published table, descriptor and envelope, and through the real
  `compactForLlmAppend` last-resort compaction to check that `completeness` and all warnings stay visible.
- Validator: `MeasurementEnvelopeValidationTest`. Source-cell preservation for `calendar_bucket`:
  `cache/CalendarBucketSourceCellPreservationTest` (STRING, NUMBER, INTEGER, BOOLEAN, DATETIME, LOCATION,
  TAGS, nested INFOTABLE). LONG columns are not covered by the local automated tests because the local test
  classpath cannot construct `LongPrimitive`.
- Tool description budget: `TabulateCalendarBucketModeTest.descriptionAndSchemaBudgets_hold` caps the
  editable description text of the computing modes in `tabulate_cached_result` (all admissions on minus all
  off) at 2,500 characters; `ComputingOperationAdmission` provides the all-off helper used for that
  measurement.

Reference data is in `parler-agent/src/test/resources/computing-enhancement/` (`counter-delta-reference.json`,
`rolling-stats-reference.json`, `time-weighted-reference.json`, `calendar-bucket-reference.json`); its
README describes the case format. The files are hand-checkable and need no network, platform or external
library.

Java changes are verified with:

```bash
cd parler-agent
./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
```
