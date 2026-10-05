# Chart kinds: ten prompts for the chart-enhancement features

This suite drives the chart kinds and group behaviours delivered by chart-enhancement
L1 through C3b-2a through the existing ten-turn driver, unchanged: histogram (equal
width, explicit edges, density), boxplot (single group and grouped), heatmap with
missing cells, stacked and percent bars, a three-member chart group with shared
colours, the empty-result guard, and a two-category bar.

It is a functional regression aid, not a numeric golden. `reference-manifest.json`
holds review criteria only, so the automatic judge reports
`insufficient_evidence/no_reference_evidence`. Never read a transport `done` as a
task pass: check the saved `events.jsonl` against the criteria.

## Run

Plan only (no platform request):

```bash
uv run --no-project --offline test_scripts/run_context_compaction_eval.py \
  --suite test_scripts/context_compaction/chart_kinds/suite.json \
  --output-dir ../parler-eval-results/chart-kinds-plan
```

Live, User-operated: add `--golden-manifest
test_scripts/context_compaction/chart_kinds/reference-manifest.json`, `--agent-thing`,
`--gateway-thing <AgentThing>_ID`, `--helper-thing AgentLlmUsageCalculator` and
`--execute`, with `--output-dir` outside the repository. The widget must not be
connected to the same conversation while the driver runs; a second connection is closed
before any turn is sent.

## What to check in `events.jsonl`

- Histogram: `sum(counts) = validCount - belowRangeCount - aboveRangeCount`, and
  `sum(density * binWidth) = 1`.
- Boxplot: `min <= whiskerLow <= q1 <= median <= q3 <= whiskerHigh <= max` per group.
- Heatmap: the number of `null` cells equals `missingCount`; no zero-filling.
- Stacked bars: `stackMode` present, two or more series, no negative value under
  `percent`.
- Chart group: a mapping revision precedes each member's chart frame, keys are
  append-only, and the last revision is `final`.

Layout, colours on screen, focus linkage, replay after reconnect and print remain
visual checks.

## Data assumptions and known limits

- Time series: `contactForce` and `operationalVoltage` for ORD-Contacting-01/02,
  2026-09-14 22:00 to 2026-09-15 02:00 America/New_York, about 37 samples per series.
  MUC BenchScale 02 has `operationalVoltage` but no `contactForce`.
- Utilization: 2025-09-03 to 2025-09-14 for the Contacting assets.
- Since Agent 0.1.245 the suite asks the natural questions that used to fail (design
  `docs/agent/nearterm/tabular-reach.md` §3), so it is also the acceptance run of that topic:
  - Turns 4 to 6 use the full twelve-day records window. `get_utilization_records` returns 25
    JSON rows with `hasMore: false`; the result is promoted to a cached table and its envelope
    carries a `cacheId`.
  - Turn 3 compares three devices. MUC BenchScale 02 has no `contactForce`, so its history
    query must answer `PROPERTY_NOT_FOUND`; the other two histories are appended with
    `union_rows` and drawn as one grouped boxplot.
  - Turn 10 needs one state summary per day for seven days, appended with `union_rows` and
    drawn as a day by state heatmap.
- Grouped and stacked bars accept at most 6 series; the records window holds 8 distinct
  reasons, so the stacked turns use `utilizationState` as the series.
- Nothing pages or re-queries automatically: a result that does not prove it is complete is
  not promoted, and the honest outcome is then a refusal that says so.
