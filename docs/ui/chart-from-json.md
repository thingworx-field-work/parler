# JSON tool results as chart sources

This document describes how a tool that returns JSON (rather than a native INFOTABLE) feeds the existing
chart pipeline, how that source survives a human approval pause, and the model guidance and approval UI
behavior that make chart requests with approvals and multiple sources reliable.

Normative wire shapes stay in [`CONTRACTS/CHART_CONTRACT.md`](../../CONTRACTS/CHART_CONTRACT.md)
(`ChartBlock`, the optional top-level `cacheId` on a qualifying tabular success envelope, §2.5) and
[`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) (approval gate, rule 10b).
The tool-author convention is in [`docs/agent/CUSTOMIZED-TOOLS.md`](../agent/CUSTOMIZED-TOOLS.md)
("JSON return conventions"); `last_invoke` semantics are in
[`docs/agent/prompt-to-chart.md`](../agent/prompt-to-chart.md) §7.6.

## 1. Scope

- Extended tools and `invoke_service` may return a JSON business object instead of an INFOTABLE. A small,
  complete single table in that object can be charted with the existing
  `build_chart_from_tabular_result(source="last_invoke")` or by its own `cacheId`.
- Nothing about tool registration, arguments, permissions or returned business content changes. Manifests
  need no new fields.
- The convention is guidance, not validation. A JSON result that does not meet it is still a valid tool
  result: it is read as plain JSON, the service call does not fail, and the result simply does not become a
  chart source.
- Native INFOTABLE results keep working. There is no need to rewrite INFOTABLE services as JSON.

## 2. Convention for tool authors

The full convention is in `CUSTOMIZED-TOOLS.md`. In short:

1. **Return one JSON object with a status.** Use `status` = `success`, `empty` or `error`, with a short
   `code` and `message` on failure. Keep "no data" distinct from "failed". `status` may be omitted for older
   tools; when present, only the string `success` can become a chart source.
2. **Put the single main table in a root `rows` array** of flat objects with the same keys and consistent
   value types. Use JSON numbers, ISO-8601 timestamps with a zone, and `null` for unknown values. No nested
   objects or arrays in cells.
3. **Keep descriptive fields next to `rows`** (`scope`, equipment, time range, `stats`, `evidenceGaps`).
   State units and the denominator of ratios; do not fill unknowns with zero.
4. **Declare paging or truncation** with `totalRows`, `returnedRows`, and `hasMore` or `truncated`; keep
   `offset` for offset paging. A page must never be described as the whole data set.
5. **Composite results** may use named sub-objects (for example `machineCoverage`, `stateSummary`), each with
   its own status and `rows`, `included: false` for parts not requested, and a root `status` of `partial`.
   Composite results are for reading; to chart, call a tool that returns the needed single table. The agent
   does not guess a main table or merge sub-tables.
6. **Use JSON only when it adds something.** When changing a return format, check every downstream use:
   text answers, tables and charts.

Minimal single table:

```json
{
  "status": "success",
  "rows": [
    {"state": "Running", "durationSeconds": 1200},
    {"state": "Down", "durationSeconds": 2400}
  ],
  "stats": {},
  "evidenceGaps": []
}
```

For proportions over large detail sets, return an aggregated single table instead of a first page.

## 3. Worked example: the utilization sample tools

The sample configuration in `dev_data/sample_scpa_utilization_agent_configuration/tools/extended_tools.json` maps four model-visible
tools to services on the Thing `SCPA_Utilization_helper` (implementation in
`dev_data/Parler_SCPA_Guidance.xml`). All four are `READ_ONLY`, not approval-gated, playbook-safe, and
return JSON.

| Tool / service | Returned structure | Chart use |
|---|---|---|
| `list_utilization_machines` / `ListUtilizationMachines` | root `rows`; `machineCount`, `returnedRows`, `truncated`; dates and fallback info | A machine list. It can qualify as a table source, but it carries no durations or utilization, so it is not a basis for utilization charts. `truncated: true` does not qualify. |
| `get_utilization_records` / `GetUtilizationRecords` | root `rows`; `totalRows`, `returnedRows`, `offset`, `limit`, `hasMore`; equipment and time range | Event detail. An explicit partial page does not qualify. For duration shares, call the summary tool. |
| `get_utilization_state_summary` / `GetUtilizationStateSummary` | root `rows`; `stats`, `evidenceGaps`, equipment and time range | Chart the state rows directly; no re-query or re-aggregation. |
| `get_utilization_overview` / `GetUtilizationOverview` | `machineCoverage.rows`, `stateSummary.rows`; `stats`, `evidenceGaps` | A composite overview; never a chart source. Call the summary tool for a state chart. |

**Machine list.** Rows hold `machineName`, `displayName`, `description`, `effectiveStartDate`,
`effectiveEndDate`. `MaxItems` defaults to 50 (maximum 500). When dates are incomplete the service may fall
back to the base list and report it in `evidenceGaps`; `dateAwareReturnedRows = -1` is the service's own
"not executed" marker.

**Event records.** Rows hold `equipmentId`, `equipmentDescription`, `eventStart`, `utilizationState`,
`reasonGroup`, `reason`, `shiftId`, `durationSeconds`. `Limit` defaults to 50 (maximum 200). A result with
`hasMore: true`, `offset > 0` or `totalRows != returnedRows` is readable but is not a chart source. There is
no automatic paging or cross-page aggregation.

**State summary.** Rows are already aggregated by `utilizationState`:

```json
{
  "status": "success",
  "rows": [
    {"utilizationState": "Down", "sumDurationSeconds": 51368, "sumDurationMinutes": 856.13, "percentage": 59.45, "count": 3},
    {"utilizationState": "Unavailable", "sumDurationSeconds": 34251, "sumDurationMinutes": 570.85, "percentage": 39.64, "count": 1},
    {"utilizationState": "Running", "sumDurationSeconds": 780, "sumDurationMinutes": 13, "percentage": 0.9, "count": 2}
  ],
  "stats": {"utilizationPercent": 1.5},
  "evidenceGaps": []
}
```

Use `utilizationState` as the category and `sumDurationSeconds` as the value. Minutes and hours are unit
variants of the same quantity; `count` is the number of events, not duration. The pie's duration share and
`stats.utilizationPercent` do not necessarily share a denominator (0.9 % and 1.5 % above are different
metrics). `IncludeStats = false` or a failed statistics add-on does not prevent valid rows from charting.

**Overview.** A result such as the one below is valid and useful for reading, but has no root table and is
never charted. A parent `success` does not prove a child `success`, and `included: false`, `empty` and
`error` are different states.

```json
{
  "status": "partial",
  "machineCoverage": {"included": false},
  "stateSummary": {"included": true, "status": "success", "rows": [{"utilizationState": "Running", "sumDurationSeconds": 1200}]},
  "stats": {},
  "evidenceGaps": ["Machine coverage unavailable"]
}
```

## 4. How a JSON result becomes a chart source

### 4.1 Two layers of JSON

The helper returns a business object; the agent wraps it in a tool-execution envelope. A typical input to the
chart hook is:

```json
{
  "status": "success",
  "resultKind": "JSON",
  "result": "{\"status\":\"success\",\"rows\":[{\"utilizationState\":\"Running\",\"sumDurationSeconds\":780}],\"stats\":{},\"evidenceGaps\":[]}"
}
```

`result` is usually a string (`InvokeServiceExecutor` serializes a JSONObject result with `String.valueOf`);
an object is accepted as well. The outer `success` only means the tool executed; it never overrides a
business `error` or `empty`. Business status and paging fields are always read from the decoded `result`
object, never from the envelope or from sub-objects.

### 4.2 Call chain

| Step | Responsibility |
|---|---|
| `AgentThing.executeExtendedToolOnThing` | Builds arguments from the service metadata, calls the service through `processAPIServiceRequest` (which checks the current user's permissions), formats the result with `formatDirectServiceResultForLlm`. |
| `InvokeServiceExecutor.formatToolResult` | INFOTABLE results go inline or to the cache by row threshold; JSON goes into `result`. |
| `TabularChartRoundHooks.afterBuiltInToolResult` | Runs in `AgentThing` tool dispatch (extended-tool branch and generic built-in branch), before `ToolResultEgressGateway.compactForLlmAppend`, so it sees the full raw result. Tool-name dispatch (`parseQualifying`) comes first; extended tools fall through to `tryGenericInvokeShapedInfotable`, which requires outer `status: success` and calls `parseInvokeService`. `invoke_service` also reaches `parseInvokeService`, so one JSON branch serves both. |
| `TabularChartRoundState.recordQualifyingTabular` | Holds the latest qualifying source of the current request. |
| `TabularChartSourceResolver.resolveOrError` | `last_invoke` reads the cached table or passes inline rows to `ParlerTabularChartBuilder.infoTableFromJsonRows`. |
| `ParlerTabularChartBuilder` → `ChartBlock` → wire → widget | Existing column mapping, numeric validation and chart construction. |

Request-local state lives in `AgentToolContext` thread-locals; `AgentLoop.runInner` resets it with
`resetTabularChartRound()` at the start of every request.

### 4.3 Qualification rules

`TabularChartRoundHooks.parseInvokeService` keeps its INFOTABLE / INFOTABLE_LARGE branches and handles an
envelope with outer `status: success` and `resultKind: JSON` as follows:

1. Take `result`: an object, or a string that parses to an object in one pass. Anything else does not
   qualify; the result is unchanged.
2. The business `status` must be absent or the string `success`. Root `rows` must be a non-empty array whose
   elements are all objects. The hook does not check key consistency or cell types, does not search
   sub-objects for tables, and does not filter rows.
3. An explicit partial-result signal disqualifies: boolean `hasMore: true` or `truncated: true`, numeric
   `offset > 0`, or numeric `totalRows` and `returnedRows` that differ. Strings are never coerced; numbers are
   compared by magnitude.
4. **Inline tier:** up to `InvokeServiceExecutor.LARGE_TABLE_ROW_THRESHOLD` rows (from
   `ToolResultEgressGateway.llmArraySampleLimit()`, currently **20**). The rows are registered as the inline
   `last_invoke` source (no `cacheId`, no column metadata, rescue-complete flag `true`).
5. **Promoted tier:** more than 20 rows qualify only with positive paging evidence (`hasMore: false`,
   `truncated: false`, or equal numeric `totalRows` and `returnedRows`). Every paging field that is present
   must have its declared type, and every present count must equal the delivered row count; `returnedRows`
   alone proves nothing. The whole envelope must fit the invoke character cap
   (`InvokeServiceExecutor.INVOKE_SERVICE_RESULT_CHAR_CAP`, the same cap that turns a larger `invoke_service`
   result into `LARGE_JSON`; enforced here for extended tools too), and the rows must fit the tabular cell
   budget (`CachedTabularToolsExecutor.MAX_CELLS_FOR_TABULAR_TRANSFORM`) and the cache storage byte and
   time budget (`TabularExpansionBudget`). A promoted table is stored in the conversation cache and registered
   cache-backed, like INFOTABLE_LARGE: `last_invoke`, `cache_id` and the last-tabular `TOKEN` all name the same
   stored table. The model still sees at most 20 sample rows after egress compaction. A promoted table's cache
   descriptor stays `UNKNOWN` completeness; passing these checks is not a completeness claim.
6. A JSON result that does not qualify leaves the previous qualifying source unchanged.

These checks are independent of the INFOTABLE completeness function, so INFOTABLE behavior is unaffected.
`chartRescueDataCompleteEnough` controls only automatic chart rescue; the explicit resolver never reads it,
which is why partial results are skipped outright rather than registered with a flag.

### 4.4 Conversion and chart call

`TabularChartSourceResolver` hands inline rows to `ParlerTabularChartBuilder.infoTableFromJsonRows`. The
first row's keys define the columns; a later row missing a key yields `null`; keys that first appear in later
rows are not added. Nested cells are carried as STRING text. ISO timestamp strings stay STRING (no DATETIME
inference). Whether the chosen x/y columns can be charted is decided by the builder; negative pie values,
duplicate categories and non-numeric values are rejected there as for any other source.

```json
{
  "source": "last_invoke",
  "kind": "pie",
  "xColumn": "utilizationState",
  "yColumn": "sumDurationSeconds",
  "pieSliceMode": "all_nonzero"
}
```

### 4.5 Failure messages

`SOURCE_RESULT_NOT_TABULAR` keeps its code. Its two "no source" sites in `resolveOrError` — the one with
`details.reason = no_qualifying_tabular_tool` and the fallback when a source has neither a `cacheId` nor
inline rows — tell the model to call an existing tool that returns a single table (an INFOTABLE, or a JSON
object whose root `rows` is a complete small table) or to present the values as a text table, and not to
guess service names or call platform services to create an InfoTable. The `cache_entry_empty` site of the
`cache_id` branch is a different failure and says only "Cached table has no rows."

### 4.6 Model-visible descriptions

`llm_tool_routing_guide.txt` (tabular charts section) and the `build_chart_from_tabular_result` description in
`BuiltInTools.buildChartFromTabularResultDef()` both state that a qualifying JSON result from
`invoke_service` or an extended tool is itself a `last_invoke` source, so the model does not re-query the same
service through another entry point to obtain an INFOTABLE. A regression test reads the real definition from
`ToolRegistry` and asserts that both texts carry the same key phrase. Neither text names a specific tool,
device, date or prompt.

## 5. Source handles for JSON tables

A qualifying inline JSON table also receives its own `cacheId`, so two JSON tables fetched one after the other
in the same request can each be charted later with `source: "cache_id"`:

- After the inline-tier table is registered, `TabularChartRoundHooks.withJsonSourceHandle` converts the rows
  with `infoTableFromJsonRows`, stores the table with `InvokeServiceExecutor.storeInfotableInConversationCache`
  (the existing conversation cache with its isolation, TTL, capacity and source-descriptor rules), and adds
  the returned `cacheId` to the **outer** tool envelope. The decoded business `result` is untouched.
- `afterBuiltInToolResult` returns the possibly augmented body; the `AgentThing` call sites use it. If
  conversion, storage or augmentation fails, the original body is returned and the tool status is unchanged.
  No handle is returned for a table that was not stored.
- Request-local state stays on the inline path for the inline tier (`hasCacheId = false`). `last_invoke`
  therefore still resolves to the newest table from rows, keeps working if the cache entry is evicted, and
  `TabularCacheHandleMirror.clearInline()` behaves as before (see §7).
- Both orders work: "fetch A → chart A → fetch B → chart B" (`last_invoke`) and "fetch A → fetch B → chart
  each" (each table's `cacheId`).
- Non-qualifying JSON (error, empty, partial page, over budget) receives no handle.

The top-level `cacheId` on a qualifying tabular success envelope is the optional field allowed by
`CHART_CONTRACT.md` §2.5: it must name the table the agent actually stored and is never derived from a tool
call id. `ParlerInvokeServiceInfotableTableWire` emits a table only for `INFOTABLE` / `INFOTABLE_LARGE`, and
`TaskStateInvokeFetchParsers` reads `cacheId` only for `INFOTABLE_LARGE` and `CACHED_PAGE`, so the added field
on a `JSON` envelope does not trigger a table frame or change task-state parsing.

## 6. Approval continuation

### 6.1 Carrying the source across the pause

`AgentToolContext.TABULAR_CHART_ROUND` is thread-local. The first part of a request runs on the always-on
thread; the continuation after approval runs on the approval thread and starts a fresh loop, which resets
request state. The approved tool's result bypasses normal dispatch and is appended to history by
`HitlSyntheticToolResultAppender.appendDurable`. Rebuilding the source from history is not possible: history
is compacted by `ToolResultEgressGateway` (long strings may no longer parse, larger tables may be sampled), so
the exact source is carried instead:

1. `ParlerHitlStreamScopedEnqueue.enqueueOrThrow` captures `AgentToolContext.tabularChartRoundState().snapshot()`
   at the existing pending-creation point and stores it in `PendingApprovalRecord`. The snapshot holds the
   qualifying count, the latest `cacheId` or inline rows (deep-copied, immutable), column metadata, the
   rescue-complete flag, the chart id counter and the chart group state. A cached table is held by handle only.
2. `PendingApprovalRecord.withInterruptedBatchSiblings` keeps the same snapshot; pending identity, TTL,
   consumption and cleanup are unchanged. A record without a snapshot restores empty state; history is never
   scanned for a source.
3. `AgentThing.runParlerPostToolAgentLoop` calls
   `AgentLoop.runAfterApproval(messages, tools, streamSink, pending, completedGatedResult)` with the consumed
   pending record.
4. Inside the loop, after its own reset, the snapshot is restored as a private copy and
   `afterBuiltInToolResult` runs once on the newly completed gated result. A newer qualifying approved table
   replaces the old source; a non-tabular, failed, rejected or cancelled result keeps the old one. An approved
   inline source clears the TOKEN mirror; an approved cache-backed source updates it. Restoring does not
   replay history, so a legitimate earlier `summarize_cached_result` update of the mirror is kept.
5. A later pause captures the updated state again. A new user request uses the normal `run` path and always
   starts with no source, even if the history it receives contains older tool rows.

Before the approved result is appended, `AgentThing` calls `TabularChartRoundHooks.augmentJsonSourceHandle`
on it, so a qualifying approved JSON table gains its `cacheId` before the model's next request. That call does
not touch request state. When the continuation loop later runs `afterBuiltInToolResult` on the same body, it
finds the handle already present and neither stores nor counts the table twice; a handle that is no longer
live in the conversation cache is not re-created from transcript rows.

Rejecting or cancelling an approval does not produce a new successful tool result. Requiring approval when an
`invoke_service` call matches no policy is the intended protection; approvals are never skipped or relaxed to
make a chart work.

### 6.2 Chart ids across the pause

The chart id sequence (`c1`, `c2`, …) lives in `TabularChartRoundState` (`nextChartId()`, reached through
`AgentToolContext.nextParlerChartId()`), so the snapshot captures it and `restore` brings it back. Within one
user request, charts keep distinct, consecutive ids across one or more approval pauses. `reset()` still
restarts the sequence, so a new user request starts again at `c1`. This matters because `parler-ui` builds
the artifact key from `chartId` (`parler-ui/lib/artifactPresentation.js`) and tracks chart state per
conversation, request and artifact key.

## 7. Compatibility and deliberate limits

- Manifests, helper JavaScript, service arguments, business JSON, `status` / `resultKind`, `stats` and
  evidence gaps are unchanged. No manifest field, strict loading rule or wire field is added.
- INFOTABLE, INFOTABLE_LARGE, existing cache behavior and the UI are unchanged by JSON qualification.
- JSON sources are request-scoped. Composite overviews, `LARGE_JSON` results and cross-request reuse of an
  inline JSON source are not supported; a JSON result already classified as `LARGE_JSON` is not rebuilt.
- Partial pages never qualify. A result without explicit truncation signals still does not prove the
  underlying data set is complete.
- The row and budget limits restrict only chart conversion. They do not address memory or byte size of the
  original tool JSON.
- Registering an inline JSON source calls `TabularCacheHandleMirror.clearInline()`, which clears the
  conversation mirror for `__PARLER_LAST_QUALIFYING_TABULAR_CACHE__` (see
  [`docs/agent/p2_last_tabular_cache.md`](../agent/p2_last_tabular_cache.md)). After an inline JSON table,
  `tabulate_cached_result(cacheId=TOKEN)` returns `LAST_TABULAR_CACHE_UNAVAILABLE` instead of resolving to an
  older cached table. Inline INFOTABLE results behave the same way. Use the table's own `cacheId` instead.

## 8. Model guidance and approval UI

### 8.1 Approval decision submit state (UI)

An approved service may run for many seconds before `approval.resolved` arrives. The client therefore shows
local progress for the decision upload and prevents a second submission.

- `approvalGate` carries two **client-only** fields that never go on the wire: `submitState`
  (`"idle" | "submitting" | "submitted"`, absent read as `idle`) and `submitError` (`string | null`). A new
  `approval.required` opens the gate in `idle`.
- Three local UI events drive the state: `approval.decisionSubmitting`, `approval.decisionAccepted` and
  `approval.decisionSubmitFailed`. Each matches on `pendingId`; a mismatch leaves state unchanged, so a late
  callback from an earlier approval cannot touch a newer gate.
- While `submitState !== "idle"`, the approve, cancel and reject buttons of that pending approval are
  disabled. The submit entry point (`parler-ui/lib/approvalDecisionSubmit.js`, used by
  `#invokeSubmitParlerApprovalDecision()` in `parler-ui/parler-ui.js`) also returns early when the state is
  not `idle`, so keyboard or programmatic triggers cannot send a second uplink.
- Text shown in the existing `approval-panel` part: "Submitting decision…" while the uplink is in flight,
  then the neutral "Decision accepted. Waiting for result." for all three decisions. Acceptance proves only that the
  server received the decision, not that any business operation started or succeeded; a reject or cancel is
  never described as executing. No new `part` or CSS custom property is added; disabled buttons use the
  existing `approval-primary-action` / `approval-secondary-action` / `approval-danger-action` disabled styling
  (see [`docs/ui/theme-api.md`](theme-api.md)).
- A failed upload sets `submitError`, returns `submitState` to `idle` and re-enables the buttons for a
  controlled retry. It does **not** synthesize `session.error`, set the global error banner, or clear `busy`,
  `activeRequestId` or the gate, because the request may still be running on the server. The text says only
  that the submission was not confirmed.
- Terminal state still comes from the server: `approval.resolved` (whose `executed` / `error` report the real
  execution result) followed by `session.done` / `session.error` / `session.cancelled` /
  `session.superseded`. The one-hour `TURN_TIMEOUT_MS` watchdog remains the coarse last resort.
- The server is unchanged: a second submission for a consumed pending id still gets `UNKNOWN_PENDING`, which is
  the correct pending-lifecycle protection.

The state lives in the reducer (`parler-ui/lib/chatSession.js`, typedef in `parler-ui/lib/types.js`) rather
than in a private widget field, so it shares the gate lifecycle and is covered by the node tests
(`parler-ui/lib/approvalDecisionSubmit.test.mjs`, `parler-ui/lib/parlerUiThemeState.test.mjs`).

### 8.2 `invoke_service`: discover inputs, bind stated constraints

Knowing a service name does not mean knowing its inputs. `InvokeServiceToolSchemaFragment.invokeServiceDescription()`
and the `invoke_service` section of `llm_tool_routing_guide.txt` state two class-level rules:

- **Discover unknown input definitions first, with the tool that fits the target.** A concrete Thing →
  `discover_thing_members` (`thingName`, `facet: "service"`, `memberName`); a ThingTemplate or ThingShape →
  `describe_entity_schema` (`entityType`, `entityName`, `facet: "service"`, `memberName`), which returns
  parameter names, types and required flags. Other targets are not sent to these tools as if they were
  Things; when no visible tool or context can supply the input definition, the model states the gap and asks
  instead of guessing parameters or running a broader substitute query. When the definition of that service
  is already known, it is not fetched again, and a legitimate call without parameters is still allowed.
- **Bind the constraints the caller stated.** Keep the named `entityType` / `entityName` / `serviceName` as
  the target and map the stated business entity, time range and filters onto that service's real input names
  in this call's `parameters`. When the mapping or the business time zone is unclear, ask. Omitting an
  optional scope or filter parameter can widen the query, so omit it only when a wider scope is intended. Do
  not switch to another entity because it exposes a same-named service, and do not split the constraint into
  an extra query.

There is no executor check that rejects empty `parameters`: the generic executor cannot know which parameters
a customer service needs, and many services legitimately take none.

### 8.3 What a `cacheId` is

- `last_invoke` always resolves to the **most recent** qualifying table of the current request. Several
  qualifying results in one request do not make it ambiguous. `source: "cache_id"` is for an **earlier** table,
  and only with a `cacheId` that a tabular result envelope actually returned.
- Evidence ids (`e1`, `e2`, …), tool call ids and `chartId` values are not cache handles. Without a handle,
  use `last_invoke`.
- The `CACHE_MISS` message of the `cache_id` branch in `TabularChartSourceResolver.resolveOrError()` says so
  and names the recovery: the id is not a cache handle in this conversation, a `cacheId` only comes from a
  tabular result envelope, use `last_invoke` for the most recent qualifying table, and do not retry the same
  id. The code stays `CACHE_MISS` with no `details`. The `last_invoke` cache arm keeps its own message ("Last
  tabular result cache is no longer available."), and other tools' `CACHE_MISS` messages are unchanged.
- `REPETITION_BLOCKED` still stops identical repeated calls.

### 8.4 Several charts in one request

The routing guide and the tool description state that one request may emit several charts, for example
"fetch A → chart A → fetch B → chart B", with each `last_invoke` resolving to the newest table at that point.
A success with `code: CHART_EMITTED` and a `chartId` means that chart was delivered; a later failed call in
the same request is not a reason to draw it again. The final answer must follow the actual tool results: no
invented "one chart per turn" limit and no claim of background drawing still in progress. There is no
server-side chart de-duplication, because users may legitimately ask for a repeated or variant chart.

### 8.5 `last_invoke` belongs to the current request

A new user request starts with no chart source (`AgentLoop.runInner` resets request state). Numbers from an
earlier request that are still readable in history are not a chart source. With only earlier inline data, run
a query that matches the current request and then use `last_invoke`; a still-valid explicit `cacheId` may be
used through the normal cache path. Both model-visible texts say this. The same-request approval snapshot of
§6 does not restore sources for a new request.

### 8.6 Dates without a time zone

`ParlerTimeAnchor.STABLE_TIME_GUIDANCE` (mirrored in
[`docs/agent/time-interpretation.md`](../agent/time-interpretation.md)) makes date windows consistent:

- A date or time with no explicit zone, including a bare absolute date such as `2025-09-10`, is interpreted in
  `user_timezone`; an explicit zone (including UTC) is honored.
- Decide what the window means first, then convert to UTC for the call. Conversion changes the representation,
  not the day boundary; the UTC day is never substituted for the local day.
- Use the target date's actual offset in that IANA zone, never a fixed offset.
- Keep the same interpretation for the same date across related queries in a conversation.
- When `user_timezone` is absent and no documented fallback applies, state the zone used or ask.
- Whether the end bound is inclusive or exclusive follows the target service's own contract.

`InvokeServiceArgumentCoercion` parses explicit DATETIME strings as given and never shifts a valid explicit
UTC time.

## 9. Tests

| Area | Tests |
|---|---|
| JSON qualification, handles, promotion | `parler-agent/src/test/java/com/thingworx/things/agent/tools/TabularChartRoundHooksTest.java` and neighbouring source tests |
| `last_invoke` latest-wins | `TabularChartSourceResolverLastInvokeLatestTest` |
| End-to-end chart from JSON | `BuildChartFromTabularResultExecutorSuccessJsonTest` (calls `BuildChartFromTabularResultExecutor.execute` and drains the `ChartBlock` with `AgentToolContext.drainPendingParlerChartBlocks()`) |
| Approval continuation (real loop, egress, enqueue, pending record) | `parler-agent/src/test/java/com/thingworx/things/agent/AgentLoopHitlContinuationChartSourceTest.java` |
| Approval submit state | `parler-ui/lib/approvalDecisionSubmit.test.mjs` (listed in the `test` script of `parler-ui/package.json`) |

Commands: `cd parler-agent && ./gradlew test --no-daemon -PuseLocalTwxLib=true` and `cd parler-ui && npm test`.
Description-text assertions prove only that the guidance is published, not that a model follows it.
