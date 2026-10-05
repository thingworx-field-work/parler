# Computing ops: ten prompts for the computing-enhancement operators

This suite drives the `tabulate_cached_result` operators delivered by
computing-enhancement CE-1 through CE-4, plus the read-limit marking delivered by
tabular-reach TR-0, through the existing ten-turn driver, unchanged:

| Turn | Exercises |
|---|---|
| 1 | `counter_delta` with no rule stated: increments between real readings, head and tail time left uncovered |
| 2 | `counter_delta` with a modulus and a max rate the user states, and rollover classification |
| 3 | `rolling_stats` `mean`, `OBSERVATION_COUNT`, window 5, with `warmedUp` reporting |
| 4 | `rolling_stats` `max`, `ELAPSED_DURATION`, 1800 s, with the `[t-D, t]` endpoints echoed |
| 5 | `time_weighted` `step_hold`, `maxGapSeconds` 600, `timeUnit` hours: integral, time mean, covered and unknown time |
| 6 | `time_weighted` `trapezoid` on the same cache, compared against turn 5 |
| 7 | `calendar_bucket` `day` in `America/New_York` on `eventStart`, then a grouping step |
| 8 | `calendar_bucket` `hour` on the same cached records, filtered to one state |
| 9 | A history read that reaches its row limit: `readLimitReached` and the note |
| 10 | Whether the caveats of turns 1, 5, 6, 7 and 9 survive into a summary |

It is a functional regression aid, not a numeric golden. `reference-manifest.json`
holds review criteria only, so the automatic judge reports
`insufficient_evidence/no_reference_evidence`. Never read a transport `done` as a
task pass: check the saved `events.jsonl` against the criteria.

## Run

Plan only (no platform request):

```bash
uv run --no-project --offline test_scripts/run_context_compaction_eval.py \
  --suite test_scripts/context_compaction/computing_ops/suite.json \
  --output-dir ../parler-eval-results/computing-ops-plan
```

Live, User-operated: add `--golden-manifest
test_scripts/context_compaction/computing_ops/reference-manifest.json`, `--agent-thing`,
`--helper-thing AgentLlmUsageCalculator` and `--execute`, with `--output-dir` outside
the repository. Omit `--gateway-thing` to bootstrap a fresh conversation, or pass
`<AgentThing>_ID` to use the reset conversation; the widget must not be connected to
whichever conversation the driver uses.

## Why the prompts state the parameters

Three of these operators refuse to guess, by design, so a prompt that does not state
the rule cannot reach them:

- `counter_delta` takes a modulus or a reset baseline **only** when the user or the App
  states it, so turn 1 deliberately states none and turn 2 states both.
- `time_weighted` requires `integrationMethod`, `maxGapSeconds` and `timeUnit`.
- `calendar_bucket` requires `timeZone`; there is no default.

The prompts therefore name the rule, the zone, the window and the method in ordinary
user words. They are not tool-call instructions: the mode, the column bindings and the
argument names are still the agent's to choose.

## Dataset facts the prompts rely on

Confirmed live on 2026-09-21 against the restored snapshot:

- `ORD-Contacting-01` logs `contactForce`, `operationalVoltage`, `currentDraw`,
  `num_GoodProductionCount` and others. Note the lower-case `currentDraw`.
- `contactForce` has 37 samples in 2026-09-14 22:00 to 2026-09-15 02:00
  America/New_York, about one per six minutes, first `2026-09-15T02:01:36.925Z`, last
  `2026-09-15T05:37:36.920Z`; 147 samples over 2026-09-01 to 2026-09-20.
- `num_GoodProductionCount` rises monotonically over that window, 311.81 to 382.97.
- A numeric property-history cache has exactly two columns, `timestamp` and `value`.
- The utilization records carry `equipmentId`, `equipmentDescription`, `eventStart`,
  `durationSeconds`, `utilizationState`, `reason`, `reasonGroup` and `shiftId`;
  `ORD-Contacting-01` has 25 records over 2025-09-03 to 2025-09-14.

## What this suite does not cover

- **A real rollover.** `num_GoodProductionCount` never decreases in the window, so turn
  2 proves that a user-stated modulus and max rate reach the operator and that every
  segment is classified, but it does not exercise the rollover branch itself. That
  branch stays covered by `CounterDeltaTest`.
- **A read that reaches a default limit.** The largest series here is 147 rows against a
  default of 1000 and a cap of 5000, so turn 9 has to ask for at most 20 rows. A live
  check of the default path needs a denser property than this snapshot has.
- **Rendering.** The driver is headless. Chart geometry and axis labels stay with the
  widget check.
