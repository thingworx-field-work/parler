# Large table replay control

**Status:** implemented (`FetchCachedReplayGuard`, split-lane `fetch_cached_result`, compact persist/rehydrate — see **`context-compaction.md`**).

> **Unified egress cluster SoT:** **`tool-result-egress-control.md`** §6.1. **§4.2+** here owns compact-envelope specifics (`$format`, `sampleOnly` / `rowsOmitted`, capped sample evidence, rehydrate annotations). Base **`fetch_cached_result`** success shell (paging fields, no `resultKind` / `sourceCacheId`, `columns[]`, `returnedRows`): **`CONTRACTS/TABLE_CONTRACT.md`** preamble, **`fetch_cached_result` tool JSON** paragraph (line 13).

Scope: Parler agent LLM tool-result payloads, table wire side effects, and model-facing routing for large cached tables.

## 1. Problem

Large tables are currently visible through two different surfaces:

1. UI tables should show useful rows and support paging/download.
2. LLM replay should carry enough evidence to answer correctly without blowing the provider token budget.

The observed failure came from a utilization query that produced 554 rows. The model then called `fetch_cached_result`
three times for the same cache:

- `offset=0`, `limit=200`, `returnedRows=200`
- `offset=200`, `limit=200`, `returnedRows=200`
- `offset=400`, `limit=200`, `returnedRows=154`

Those page payloads entered LLM replay and pushed a later GPT-5.4 call to:

```text
estimatedInputTokens=55072
reservedTokens=72754
tokensPerMinuteLimit=50000
reason=single_request_too_large
```

The CSV export failure was cosmetic. The replay growth was the real provider-limit problem.

### 1.1 Manual baseline runs

These four utilization prompts are the reference scenarios for this topic. The numbers were read from `ApplicationLog`
and `AgentMessageStream` on a GPT-5.4 deployment with a 50K TPM local gate, before the replay guard existed.

| Case | Prompt | Outcome | Key numbers |
| --- | --- | --- | --- |
| Healthy baseline A | `/utilization_summary Show overall utilization from 2025-09-03 to 2025-09-26.` | Success in 26.7s, 2 LLM rounds | `utilization_records` cached `40931` rows; tool result to LLM was sample/meta around `8531` chars; final round `rawReplayChars=10235`, `replayChars=3495`, `compactRatio=0.3415`; total prompt tokens `33471`, output tokens `706`; no local wait or reject. |
| Healthy baseline B | `/machine_utilization_summary Show utilization for SE.CellFab.Model.Workunit.ORD-JetDryer-01 from 2025-09-03 to 2025-09-26.` | Success in 41.0s, 3 LLM rounds | `utilization_records_by_machine` cached `219` rows; initial tool result around `8627` chars; model used `tabulate_cached_result` twice instead of paging the whole cache; final-round compaction reached `rawReplayChars=1210`, `replayChars=1210`; total prompt tokens `57066`, output tokens `1196`; two normal TPM waits (`1848ms`, `18150ms`), no reject. |
| Healthy baseline C | `/utilization_overview Show utilization overview across machines from 2025-09-03 to 2025-09-26.` | Success in 26.2s, 2 LLM rounds | `utilization_records` cached `40931` rows; tool result to LLM was sample/meta around `8531` chars; final round `rawReplayChars=10235`, `replayChars=3495`, `compactRatio=0.3415`; total prompt tokens `40550`, output tokens `752`; no local wait or reject. |
| Failure reproduction | `which assets have utilization below 30% in 2025/09/10?` | Failed before the 4th LLM call | The first utilization query cached `554` rows (`cacheId=d34b221f-...`), with sample/meta around `8560` chars. The model then called `fetch_cached_result` for pages `0..199`, `200..399`, and `400..553`; page payloads were about `77232`, `77719`, and `59906` chars. Before the next LLM call, replay had grown to `rawReplayChars=214857`, `replayChars=98762`, and the gate rejected with `estimatedInputTokens=55072`, `reservedTokens=72754`, `tokensPerMinuteLimit=50000`, `reason=single_request_too_large`. |

Interpretation:

- Large initial table tools are not the immediate failure source when they return sample/meta plus `cacheId`.
- The failure starts when `fetch_cached_result` allows hundreds of cached rows to enter the LLM replay lane.
- Normal TPM waiting is acceptable; `single_request_too_large` is not a waitable condition and should remain fail-fast.
- The desired fix is to prevent full cached pages from becoming LLM replay content and to steer full-table questions toward
  deterministic cached-table operations.

### 1.2 Utilization prompt coverage

`dev_data/scpa_utilization/utilization_prompts.txt` shows that the utilization use case is not only "show me a page".
It includes several distinct families:

| Prompt family | Examples | Covered by this topic? | Notes |
| --- | --- | --- | --- |
| Large table display | "show all asset data ... in a table", "List all data ..." | Partially | V1 keeps UI display/page/download useful while preventing page rows from entering LLM replay. It does not make the LLM inspect every row. |
| Threshold / predicate | "above 60%", "below 30%", "positive but below 30%" | Replay-safe only | This topic prevents `fetch_cached_result` scans from blowing up. Exact answers come from the deterministic `tabulate_cached_result` filter modes (`filter_count`, `filter_rows`). |
| Ranking / Top-N | "top 3 by idle hours", "lowest utilization", "4th most utilized" | Partially | `filter_sort_topn` answers when the target metric already exists as a column; derived metrics need `group_metric` first. |
| Aggregation by state/shift | "state wise breakdown", "best and worst utilized assets for each shift" | Mostly | Existing `tabulate_cached_result` group modes cover simple state aggregates; shift-level derived answers may require more specific cached-table operations. |
| Charts | "plot a bar chart", "pie chart comparing utilization" | Partially | Chart inputs should come from compact deterministic aggregate/filter outputs, not from paging raw rows through the LLM. |
| Specific machine comparison | "compare A to B", "downtime for machine X" | Mostly outside this topic | These are better handled by the utilization skill/playbook workflow and deterministic aggregation over scoped data. |
| Reformulation / "above" follow-up | "Summarize the above data with fewer columns", "Based on the above, which is the most productive machine?", "using the table earlier..." | Replay-safe only | The model can no longer narrate from raw row text in replay. It must re-derive with cached-table tools when possible; unsupported reformulations such as column projection fall back to displayed-table wording (there is no projection mode). |

This topic is therefore the replay-safety layer. Threshold and ranking prompts are answered by the deterministic
cached-table modes in [`cached-table-decision-tools.md`](cached-table-decision-tools.md). When no mode can express a
predicate, the answer must fail safe: show/browse a table or explain that an exact all-row predicate was not run, rather
than paging all rows into the model and pretending the answer is complete.

## 2. Goal

For large cached tables:

- UI may display pages and downloads.
- LLM should receive only compact state, sample, schema, and cache handles.
- The model should not page through the entire table just to perform computations.
- Follow-up computations over the full table should use deterministic cached-table tools.

Replay growth must stop even when the model chooses the wrong paging strategy. This layer does not by itself make every
utilization prompt answerable: row-level threshold predicates such as "below 30%" are answered by the companion
deterministic cached-table operations.

## 3. Design Principle

Separate the table into two lanes:

### UI data lane

Used for:

- visible table rendering
- paging
- CSV export
- chart data when the chart explicitly needs rows

This lane may carry rows to the browser, subject to existing UI row caps and export behavior.

### LLM evidence lane

Used for:

- later LLM rounds in the same user turn
- later replay from `_conversations`
- Stream rehydrate into LLM context

This lane must carry compact evidence only:

- `cacheId`
- `totalRows`
- `returnedRows`
- `offset`
- `limit`
- `columns`
- short sample rows
- warnings such as `sampleOnly`
- instructions to use deterministic transforms for full-table work

It must not carry hundreds of rows.

## 4. Target Behavior

### 4.1 Large initial table result

Existing `INFOTABLE_LARGE` / entity-query LARGE behavior is mostly aligned:

- cache full table server-side
- return sample + `cacheId`
- emit UI table with sample

Keep this behavior.

### 4.2 `fetch_cached_result`

`fetch_cached_result` is the sharp edge. It is currently a row-paging tool for the model. In practice, the model can use it
to pull an entire table into replay.

Behavior:

- The UI wire may still receive the requested page rows.
- The LLM tool result should be compact when the page is large.
- A compact result includes page metadata and a small sample, not every returned row.
- The exact large page should not be appended to `_conversations`.

Compact LLM result (example):

```json
{
  "status": "success",
  "cacheId": "...",
  "offset": 200,
  "limit": 200,
  "returnedRows": 200,
  "hasMore": true,
  "totalRows": 554,
  "sampleOnly": true,
  "rowsOmitted": true,
  "columns": [
    {"name": "machine", "baseType": "THINGNAME"},
    {"name": "utilizationState", "baseType": "STRING"},
    {"name": "durationPercentage", "baseType": "NUMBER"}
  ],
  "rows": [
    {"machine": "A", "utilizationState": "Running", "durationPercentage": 42.1}
  ],
  "hint": "Use tabulate_cached_result / summarize_cached_result for full-table computations; do not page all rows into the LLM."
}
```

**Persisted Stream bodies (compact lane):** when this compact shape is written to **`AgentMessageStream`** tool rows from the **fetch-cached split lane** (a full-page body is registered for the tool call id in **`AgentToolContext`**, not arbitrary tools), the JSON **SHOULD** carry top-level **`"$format": "parler.fetch_cached_result.compact.v1"`** so rehydration and diagnostics can recognize compact persisted fetch evidence without inferring from heuristics alone (`FetchCachedStreamLaneHelper` peek branch / `FetchCachedCompactPersistFormat`). Legacy rows without the marker may still match the structural detector at read time.

**Stream rehydrate (Stage 2):** accepted compact bodies may gain **`parlerRehydratedCacheHistorical`** (boolean `true` when the `cacheId` is absent from the live JVM per-conversation cache) or **`parlerRehydratedCacheLive`** (boolean `true` when the entry is still present). These keys are **rehydration annotations only** — they are not part of the live tool executor contract and are stripped/normalized only on the read path (`CompactFetchStreamRehydrate`). **BP9:** rehydrate MUST NOT recreate a live `ArtifactCache` entry from compact/`chartBlock` evidence; `parlerRehydratedCacheLive` is annotation-only for a pre-existing live hit. For provider pairing safety, the agent restores accepted evidence as **assistant prose** prefixed by `STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX`, not as a standalone `role=tool` `ChatMessage` after rehydrate.

This compact shape intentionally stays inside the existing `fetch_cached_result` contract:

- do not add `resultKind`;
- do not add `sourceCacheId`;
- keep `rows`, but when `sampleOnly=true` treat it as a capped evidence sample, not the full requested page;
- `returnedRows` remains the number of rows read for the requested page in the UI/cache lane, so `rows.length` may be
  smaller than `returnedRows` in the compact LLM lane.

The UI table wire must not consume this compact LLM payload as its page source. It should receive the separate UI data-lane
payload that still contains the rows the browser is allowed to render.

Small pages can stay inline when they are below a conservative threshold, for example:

- `returnedRows <= 20`, and
- serialized row payload is below a small byte/char cap, initially `4096` chars.

Both conditions must pass. A 20-row page can still be too large if the table is wide. The thresholds should be
implementation constants, not configurable in v1.

### 4.3 Model-facing tool text

Update the `fetch_cached_result` tool description and routing guide:

- `fetch_cached_result` is for showing/browsing a page, not for full-table analysis.
- For sort, top N, group, aggregate, summarize, threshold, or filter work, use cached-table deterministic tools.
- Do not call `fetch_cached_result` repeatedly to scan all pages.

Embedded hints in LARGE or cached-table results follow the same rule. A hint such as "Use `fetch_cached_result` with
this `cacheId`, offset, and limit to read more rows" directly encourages the failure mode, so no model-visible source that
emits large-table guidance uses it. When adding an emitter, grep for `fetch_cached_result` / `Use fetch_cached_result`;
the known sites are:

- `InvokeServiceExecutor` LARGE `InfoTable` output;
- `ListEntitiesByTypeExecutor` LARGE output;
- `QueryEntitiesExecutor` LARGE output;
- `QueryEntitiesByTaxonomyExecutor` LARGE output;
- `TaxonomyIdentifierResolver` candidate-page hint;
- `CachedTabularToolsExecutor` LARGE transformed-table output;
- `InvokeServiceToolSchemaFragment`;
- `BuiltInTools` tool definitions, especially the `fetch_cached_result` description and nearby cached-table tool text;
- `parler-agent/src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt`, especially the
  `fetch_cached_result` bullet. It should say this tool is for displaying/browsing a page and is not the path for
  full-table computation.

Preferred hint shape:

```text
Use fetch_cached_result only to display or browse a page. For full-table computations, use deterministic cached-table
tools such as tabulate_cached_result / summarize_cached_result and any available filter modes. Do not page all rows into
the LLM.
```

If no deterministic cached-table operation can express the user's predicate, the model should say so or display a page; it
must not repeatedly page the cache and infer from partial memory.

Do not over-edit descriptive cache-namespace text that is not steering the model toward paging. For example, chart-tool
arguments that say a `cache_id` reads from the same conversation cache as `fetch_cached_result` are acceptable if they do
not recommend scanning pages.

Implementation acceptance for hint cleanup:

- no model-visible LARGE-result hint may say or imply "use `fetch_cached_result` to read/page all rows for computation";
- descriptive references to the shared cache namespace may stay when they do not recommend paging;
- tests should include at least one resource-file assertion for `llm_tool_routing_guide.txt` and Java-output assertions for
  representative LARGE emitters.

### 4.4 Turn guard

Add one runtime guard against runaway paging:

- Track `fetch_cached_result` calls per `cacheId` in the current user turn.
- Use a fixed v1 threshold: after **two** `fetch_cached_result` calls for the same `cacheId` in one user turn, the third
  and later calls must force compact LLM content even if the returned page would otherwise be small, and should include a
  compact warning such as `repeatedPageFetch: true`.
- The counter scope is the whole submitted user turn, including all LLM rounds and any HITL continuation for that turn. It
  resets only when a new user prompt / request starts.
- Keep serving UI frames if needed; the guard controls only the LLM evidence lane.
- Do not make this a hard user-visible error unless the model asks for an impossible full data dump.

This is a safety belt, not the primary mechanism. The primary mechanism is compact LLM replay.

## 5. Relationship to Existing Compaction

**Tier A / Tier B replay compaction** (`LlmReplayCompactionGate`, always-on unless the JVM diagnostic bypass is set) reduces already-built tool result bodies after the fact. This topic prevents large row pages from entering LLM replay in the first place.

Both should coexist:

- pre-replay split: compact `fetch_cached_result` tool result before appending to active conversation
- existing compaction: still handles other structured payloads and older rows

Do not rely only on post-turn compaction. The observed failure happened inside a multi-round turn, before a final assistant
response could trigger post-turn promotion.

## 6. Table Wire and Stream Persistence

The agent needs to build different payloads for different consumers:

| Consumer | Payload |
| --- | --- |
| LLM active message list | compact evidence lane |
| UI wire table | page rows or sample rows, depending on existing UI limits |
| AgentMessageStream audit row | compact LLM content only in V1; do not store huge page rows as ordinary tool content |
| History hydrate | V1 does not restore **full** fetched page rows across reload (no durable 200-row replay). **`AgentMessageStream`** may still carry **compact** `fetch_cached_result` tool JSON (`sampleOnly` / capped `rows`); history reconstruction **MAY** rebuild a **small** sample table (same row cap as the LLM evidence lane) so the user sees what the model paged through — not a silent omission. Full-page CSV paths on hydrate align with the **`_parlerTableExport`** sidecar copied from the single export pass on the full live body. Cache-backed affordances may appear only when the current process/cache still supports them; no automatic re-fetch of unpersisted pages in V1. |

If this requires a helper abstraction, introduce one narrow split point rather than duplicating every table executor:

```text
ToolExecutionResult
  llmContentJson
  uiTableBlockJson?
```

V1 should not introduce a new `AgentMessageStream` sidecar schema. Paged table rows are a live UI concern, not durable LLM
replay content.

`ParlerFetchCachedResultTableWire` builds the UI table block from rows in the UI data lane, while the LLM content and
persisted tool content stay compact. The table-wire helper must not serialize large page rows back into the LLM/tool
replay body.

Reload behavior is intentionally simple in V1:

- while the cache is warm, existing cache-backed affordances may work if the current UI/table path already has enough
  metadata;
- after process restart, cache eviction, or history-only hydrate, the user sees the final answer and any **compact**
  sample table blocks reconstructed from persisted tool JSON (when `fetch_cached_result` carried `sampleOnly` /
  `rowsOmitted`, expect **at most** the capped evidence rows — not the full paged grid);
- full fetched page rows from `fetch_cached_result` are **not** reconstructed row-for-row, and V1 does not add a "paged view not preserved" placeholder.

The split is implemented only for `fetch_cached_result`, the tool that caused the replay growth.

## 7. Acceptance Criteria

The utilization 554-row "below 30%" scenario should no longer produce `single_request_too_large` because of paged table
rows in replay.

Concrete acceptance:

- Run the same utilization query that yields hundreds of rows.
- If the model calls `fetch_cached_result`, the UI can still show a table page.
- ApplicationLog must not show a later LLM request with replay inflated by hundreds of row JSON objects.
- `LLM_REPLAY_PENDING_COMPACTION rawReplayChars` should stay bounded compared with the current 212k-char case.
- The final answer can say rows are displayed or summarize from deterministic transforms, but it must not pretend it inspected every row unless a deterministic full-table operation ran.
- The three healthy baseline prompts in §1.1 must still succeed.
- The one-machine baseline must continue to prefer deterministic cached-table operations (`tabulate_cached_result`) instead
  of switching to raw `fetch_cached_result` scans.

With the cached-table decision modes available, the "below 30%" prompt should produce an exact result via a
deterministic filter operation; without a matching mode, the target remains replay safety plus honest final-answer
wording.

## 8. Tests

Unit tests:

- `fetch_cached_result` small page: LLM content remains inline.
- `fetch_cached_result` large page: LLM content uses compact result, omits `resultKind` / `sourceCacheId`, keeps only
  capped sample rows with `sampleOnly=true`, and the UI table block still has row data from the UI lane.
- repeated page fetch same cache: third and later calls force compact LLM content and include the compact warning path.
- repeated page fetch counter spans all LLM rounds and HITL continuation inside one user turn; it resets on the next user
  prompt.
- `AgentMessageStream` persistence does not store large page rows as ordinary LLM replay content.
- Java executor LARGE-result hints (`InvokeServiceExecutor`, `ListEntitiesByTypeExecutor`, `QueryEntitiesExecutor`,
  `QueryEntitiesByTaxonomyExecutor`, `TaxonomyIdentifierResolver`, `CachedTabularToolsExecutor`) no longer steer toward
  `fetch_cached_result` for full-table work.
- `llm_tool_routing_guide.txt`: the **`fetch_cached_result`** bullet is covered by a **classpath resource assertion**
  (e.g. `LlmToolRoutingGuideChartWordingTest`) so rewrites cannot regress to “page all rows for computation” steering
  without failing CI.
- `BuiltInTools` built-in definitions / schema text align with the same display-vs-deterministic scope as the routing guide.
- `ParlerFetchCachedResultTableWire` still builds a UI table from the UI/page payload after the LLM content is compacted.
- Compact threshold uses `returnedRows <= 20 AND serializedSize <= 4096`, not one condition alone.

Eval / manual:

- utilization all-records query with a 500+ row table
- follow-up asking for a page to display
- follow-up asking for a full-table count/summary
- the four §1.1 baseline prompts
- representative prompts from `dev_data/scpa_utilization/utilization_prompts.txt`:
  - threshold (`below 30%`, `above 60%`)
  - ranking (`top 3`, `lowest utilization`)
  - large display (`show all ... in a table`)
  - reformulation (`based on the above`, `summarize with fewer columns`)
  - chart from table

## 9. Non-goals

- General SQL over cached tables.
- Filter / threshold cached-table primitives. This layer makes the "below 30%" prompt class replay-safe; exact all-row
  predicate answers come from the `tabulate_cached_result` modes in `cached-table-decision-tools.md`.
- Arbitrary user-defined JavaScript/Python transforms.
- Removing `fetch_cached_result`.
- Server-side pagination UI redesign.
- Provider-specific token estimator changes.
