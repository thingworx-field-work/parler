# User baseline: ten everyday prompts with implicit time

This suite sends ten fixed, conversational prompts through the existing ten-turn
driver, unchanged. It tests what a real user conversation needs: asset-name
resolution and continuity, calendar-day windows, a year the user never states and
later corrects, charts, and a Pearson correlation with its pairing explained.

| Turn | Exercises |
|---|---|
| 1–2 | Utilization-state pie charts for one, then two, assets on a local calendar day |
| 3–5 | Alert history over a night window with no year, then the same window for two other assets |
| 6 | "Oh, sorry, I meant that time window in 2026": the correction applied to all three assets |
| 7–8 | `contactForce` and `operationalVoltage` trends over the corrected window |
| 9–10 | Pearson correlation on each asset, with alignment, missing values and pair count, then a comparison |

The prompts deliberately omit the time zone, and turns 3–5 also omit the year. The
suite submits the user time zone `America/New_York` through `variables.userTimezone`;
the current date comes from the agent runtime. Keep every prompt string unchanged,
including turn 6's "sorry" correction: do not add a time zone, a year or UTC bounds.
The intended UTC windows (`datasetManifest.expectedUtcWindows`) and the per-turn
`judgmentFocus` are evaluator metadata only; they never reach the prompt or host
context. Record each run's actual date, because it can change the year the agent
infers in turns 3–5.

It is a behavioural baseline, not a numeric golden. `reference-manifest.json` holds
`reviewCriteria` only, so the automatic judge reports
`insufficient_evidence/no_reference_evidence`. Never read a transport `done` as a task
pass: check the saved `events.jsonl` against the criteria.

## Run

Plan only (no platform request):

```bash
uv run --no-project --offline test_scripts/run_context_compaction_eval.py \
  --suite test_scripts/context_compaction/user_baseline/suite.json \
  --output-dir /absolute/path/outside/repo/user-baseline-plan
```

Live:

```bash
uv run --no-project test_scripts/run_context_compaction_eval.py \
  --suite test_scripts/context_compaction/user_baseline/suite.json \
  --golden-manifest test_scripts/context_compaction/user_baseline/reference-manifest.json \
  --env-file /absolute/path/to/deployment.env \
  --output-dir /absolute/path/outside/repo/<run-name> \
  --agent-thing <AgentThing> \
  --helper-thing <UsageHelperThing> \
  --execute
```

- The env file supplies `DEV_SERVER` and `DEV_KEY` for the target deployment. Do not
  commit it or print its values. `--golden-manifest` is required with `--execute`.
- The driver refuses an `--output-dir` inside the repository. Keep all results in a
  directory outside the repository; never commit credentials or business exports.
- Without `--gateway-thing` the driver creates a fresh conversation through
  `GetOrCreateConversationId`; record the returned ID from `run_identity`. Passing
  `--gateway-thing` reuses an existing conversation and does not clear its history.
- Do not connect the widget to the conversation while the driver runs, and do not
  rerun a partly used conversation: a timeout can leave work running, and a resubmit
  adds history. Each trial needs a fresh conversation and its own output directory.
- `datasetManifest.expectedExtensionVersion` is descriptive. Update it, with the data
  snapshot, in a run-local copy of `suite.json` rather than the tracked file.

## Comparing configurations

To compare agent configurations (for example two models, or `toolAdmission` off
versus lazy), run one group per configuration, serially, each in a fresh
conversation with the identical suite. For every group, record the model and
provider, `AgentSettings` (reasoning, output limit, rate control, admission mode,
tool catalog, skills and policies), helper prices and the dataset identity and
coverage. Do not change settings during a group.

To pin down the prompt a group actually ran with, invoke `RefreshPromptContextCache`
on the agent, then `GetAgentRuntimeSnapshot` with STRING `options`
`{"includePrompt":true,"includeTools":true,"includeSkills":true,"includePolicies":true,"includeTaxonomy":true,"includePlaybooks":true}`,
and save the response. For a default-path run, require `stablePromptSource=default`,
`externalSystemPromptPath=null` and `externalSystemPromptFallback=null`; a non-null
fallback means an invalid external file is still configured, which is a setup
failure. Save the assembled `stableSystemPrompt` text and its size as the run's
prompt identity.

Limits to state alongside any comparison:

- Provider prompt caches may still be warm after a platform reset; keep the cache
  token fields and the run order.
- Usage reports are two observations 30 seconds apart (`helperCapture`). Their cost is
  an observed lower bound, not a reconciled final cost or proof of savings. Report
  unpriced usage as N/A, never as free.
- Latency from driver timestamps is observed client time; identify backend or
  rate-limit measurements separately.
- One ten-turn run per configuration does not establish statistical significance.

## Reviewing the evidence

Review `events.jsonl` and `turns.json` against `reviewCriteria` and `judgmentFocus`,
using full returned data rather than previews. With each verdict, record the
conversation ID, request ID, resolved year and window, and data limitations.

- Keep delivery separate from correctness: an honest "cannot compute" is not a
  completed Pearson calculation, and an unknown value is not zero.
- Turns 3–5: record the year chosen and whether it stayed consistent; a clarification
  question is a valid outcome. Do not retroactively apply turn 6's correction.
- Turns 9–10: recompute Pearson independently from the complete series, using the
  alignment, tolerance and missing-value policy the agent stated, and compare the
  pair counts.
- Zero rows are not proof of healthy operation and never justify changing the time
  zone. Honor each service's end-bound semantics when checking windows.

For fuller evidence, also capture `AgentMessageStream` (filter `source` by the
conversation ID) and `AgentLlmCallStream` (filter the `conversationId` field; its
`source` is an event ID), with bounded start and end times. If a query reaches its
`maxItems` cap, partition it rather than treating the capture as complete.

## Data the prompts rely on

- Assets `SE.CellFab.Model.Workunit.ORD-Contacting-01`, `ORD-Contacting-02` and
  `MUC BenchScale 02`, resolvable from those short names.
- Utilization records on 2025-09-10 (ORD-Contacting-01) and 2025-09-14 (both ORD
  assets), local days in America/New_York.
- Alert history and `contactForce` / `operationalVoltage` history for
  2026-09-14 22:00 to 2026-09-15 02:00 America/New_York
  (`2026-09-15T02:00:00Z` to `2026-09-15T06:00:00Z`). MUC BenchScale 02 logs
  `operationalVoltage` but not `contactForce`, which is why turn 8 pairs it only
  with voltage.
