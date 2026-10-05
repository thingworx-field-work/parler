# Playbook tool artifact wire emission

Status: implemented (`ParlerToolArtifactWireEmitter`, `PlaybookArtifactEmitter`, internal Playbook tool rows in `AgentMessageStream`).

## Normative references

Playbook artifact emission adds **no** wire shapes; emission matches existing contracts:

- `CONTRACTS/CHART_CONTRACT.md` — `type: "chart"` envelope and nested `ChartBlock`.
- `CONTRACTS/TABLE_CONTRACT.md` — `type: "table"` and `TableBlock`.
- `CONTRACTS/UI_CLIENT_PROTOCOL.md` — how `chart` / `table` / `tabular.tool_success` attach to the assistant row for a `request_id`.
- `CONTRACTS/API_CONTRACT.md` — AlwaysOn stream semantics around tool rows, `done`, and history replay where relevant.

**Contract version:** Bump `CONTRACTS/CONTRACT_VERSION.md` only if normative wire or taxonomy-facing behavior changes. Reusing existing frames from validated tool JSON should not require a contract bump by itself.

## Repository anchors

- **Playbook execution:** `parler-agent/.../playbook/PlaybookRunner.java` — `executeToolCallNode` (success path after `PlaybookToolExecutionResult` / `parseToolJson`) and `executeFanOut` (recursive child `nodeId + "[" + i + "]"`). Failed / approval-pending nodes return without a successful tool envelope; the artifact callback must mirror that (no emission from failures).
- **Shared emitter:** `parler-agent/.../ParlerToolArtifactWireEmitter.java` — `emitAfterResolvedToolUi` emits pending charts, numeric-history charts (`ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult`), the `maybeSend*TableDownlink` family, and `tabular.tool_success` for one resolved tool message. The `AgentThing` `streamSink` `TOOL` branch and the Playbook callback both call it.
- **Chart builders / validation:** `ParlerChartWireSupport.java`, table helpers colocated with `AgentThing` / fetch-lane code (e.g. `FetchCachedStreamLaneHelper`).
- **History replay:** `AgentMessageStreamHistoryExporter.java` — rebuilds `type: "chart"` / tables from persisted tool JSON; Playbook persistence feeds the same shapes.

## Request id and turn scope

Internal Playbook tool calls run **inside** the same AlwaysOn turn as the outer `start_playbook` tool call. Any `chart` / `table` / `tabular.tool_success` frames emitted while the Playbook runs **must** use the **same** `request_id` (and `conversation_id`) as the rest of that turn so the UI reducer attaches artifacts to the correct assistant row, matching §9 and `UI_CLIENT_PROTOCOL` behavior for top-level tools.

## 1. Why

Playbooks drive data-insight workflows such as:

```text
Please compare the health of Contacting assets ORD Contacting 02 and ORD Contacting 01 over the past 24 hours.
```

The model calls `start_playbook`, and the Playbook runs `query_property_history` internally. Without artifact emission,
the internal tool returns chart-capable trend points and the final answer can cite them, but the UI receives no
`type: "chart"` frame (`parlerChartWireEmittedCount=0`). A Playbook that reasons over tables and trends but cannot show
the chart/table evidence is not acceptable for data-insight use.

## 2. Authoring note: chart-capable history

Numeric history emits a chart only when it returns rows. A `query_property_history` call with aggregate `actions`
returns aggregate evidence (`chartEmitted=false`); for a trend chart, call it without aggregate `actions`. Trend
selection that depends on a name heuristic (for example `trend_targets` gated on
`primary_property.output.isNumeric=true`) can skip the history call entirely.

## 3. Why the Playbook path needs its own hook

In the normal chat path each model tool call becomes a top-level
`ChatMessage.toolResult`. The AlwaysOn stream sink handles that top-level tool result:

- persists / augments the tool row,
- resolves table downlinks,
- calls `ParlerChartWireSupport.chartBlockFromNumericHistoryToolResult(...)`,
- emits `type: "chart"`,
- emits table frames for qualifying tabular tools,
- updates turn telemetry such as `parlerChartWireEmittedCount`.

The Playbook path is different:

```text
model tool call: start_playbook
  PlaybookRunner
    internal tool_call: query_alert_history
    internal tool_call: query_alert_summary
    internal tool_call: query_property_history
    ...
  returns assistantText as the start_playbook tool result
```

The top-level stream sink only sees the `start_playbook` result. It does not see the
internal `query_property_history` tool JSON as a top-level tool result, so the chart/table
wire hooks need a separate callback from the Playbook runner.

## 4. Rule

When a Playbook internal `tool_call` produces a UI-worthy artifact, Parler MUST emit the
same UI wire artifact that an equivalent top-level chat tool call would emit:

- numeric history inside a Playbook emits `type: "chart"` when the internal tool result is
  chart-capable, and `parlerChartWireEmittedCount` counts it;
- Playbook internal tabular/list tool results emit `type: "table"` (and `tabular.tool_success`
  where applicable) where the same top-level tool would;
- history replay hydrates those charts and tables from persisted Stream rows.

## 5. Non-goals

- Do not invent new chart/table wire shapes. Reuse existing `type: "chart"` and
  `type: "table"` contracts.
- Do not require the LLM to call `build_chart_from_tabular_result` for numeric history
  charts already emitted by the agent.
- Do not use prose as chart data. Coordinates must still come from validated tool JSON.
- Do not make Playbook authors add UI-specific nodes just to preserve normal tool artifacts.
- Generic Playbook statistics are handled by the generic derive ops, not here.

## 6. Design principle

Top-level chat and Playbook-internal tool calls must converge on one artifact-emission
pipeline.

There is no copy of the `maybeSend...Downlink` chain inside `PlaybookRunner`. The shared
`ParlerToolArtifactWireEmitter` is called from:

1. the normal `AgentThing` stream sink for top-level tool results (both AlwaysOn `streamSink`
   paths: `ParlerStreamToRemoteThing` and `runParlerPostToolAgentLoop`),
2. Playbook internal tool-call execution after each `tool_call` / `fan_out` child succeeds.

It owns the chart/table/insight emission decisions for one tool-result JSON envelope.

## 7. Flow

### 7.1 Artifact emission callback

Playbook execution takes an optional callback (`playbook/PlaybookArtifactEmitter.java`):

```java
interface PlaybookArtifactEmitter {
    void onToolResult(
        String playbookId,
        String nodeId,
        String toolName,
        String toolCallId,
        String toolResultJson
    );
}
```

The callback is supplied by `AgentThing` when running a Playbook for AlwaysOn. Offline tests may
use a no-op or capturing implementation.

`PlaybookRunner.executeToolCallNode` invokes the callback after parsing a successful tool
result and before returning the node result, passing the full tool JSON envelope (the same
string the persistence layer sees), not a lossy summary, so chart/table builders and Stream
rows stay consistent with the chat path.

For `fan_out`, each child has a stable synthetic node id such as `trends_by_asset[0]`, used for
diagnostics and artifact provenance. The internal `ToolCall` id (`pb-<uuid>`, from
`AgentThing.executePlaybookToolCall`) keys chart/table history without collisions.

### 7.2 Shared wire emitter

`ParlerToolArtifactWireEmitter` responsibilities:

- accept `toolName`, `toolCallId`, `toolResultJson`, `requestId`, `conversationId`,
  `remoteConversation`, and `downlinkOk`;
- emit pending chart blocks from `AgentToolContext`;
- emit numeric-history charts via `ParlerChartWireSupport`;
- emit tables for the same tool-result envelopes that currently produce top-level tables;
- update telemetry (`markParlerChartWireEmitted`);
- avoid emitting anything when `remoteConversation` is null or `downlinkOk` is false.

The `AgentThing` top-level stream sink and the Playbook callback both call this emitter.

**Persistence vs live wire:** internal Playbook rows are **appended to `AgentMessageStream` before**
gating live `ReceiveMessage` sends on `downlinkOk`, matching the top-level TOOL path so replay
survives early downlink failure; live emission strips `_parlerPlaybookInternalOmitFromLlmRehydrate` /
`playbookNodeId` before chart/table builders run (live/replay input parity).

**Entry-path boundary (Stream durability):** internal tool rows are appended only when the active
turn has **non-empty Parler stream wire ids** (`request_id` + remote gateway name on
`AgentToolContext`). **AlwaysOn slash** binds those ids (and the live `downlinkOk` handle) before
`tryExecutePlaybookSlashTurn`. **Composer Chat / ChatAsync slash** passes null stream ids by design,
so internal Playbook tool rows are **not** written to `AgentMessageStream` on those paths — they
do not participate in AlwaysOn history replay. Do not read the durability fix as “every Playbook
entry mode persists internal artifacts.”

### 7.3 Persistence and history replay

Charts/tables shown live are replayable after reload. Internal tool results are persisted as
Stream `tool` rows, one per internal Playbook tool call, with the internal `toolCallId`,
`executedToolName=<inner tool>`, a `playbookNodeId` marker, and the compact/augmented JSON the
history exporters need. There is no new role. This keeps history replay on the existing
tool-row model and helps debugging.

**LLM rehydration:** internal tool rows that carry chart/table-capable JSON **must** include
`_parlerPlaybookInternalOmitFromLlmRehydrate` (see `ParlerPlaybookArtifactWireConstants`) so
`AgentConversationRehydrator` does not expand them into synthetic assistant evidence rows and blow
LLM context budgets. `AgentMessageStreamHistoryExporter` strips the same keys before building
`charts[]` / `tables[]` for `ai-parler-history-v1`.

**`llm_summary` evidence bridge:** final-summary evidence must stay **aligned with UI artifacts**
without sending chart series or images into the LLM. `PlaybookEvidenceFormatter` expands `fan_out` child
`tool_call` rows using the nested node's `includeToolOutputRootFields` / `evidence.table`, appends compact
`uiArtifact.chart` lines from root scalars (`chartEmitted`, `chartBlockPersisted`, `chartBlockPointCount`,
`chart_kind` / `resultKind`), and reconciles `primary_property` **name-heuristic** `numericNameHeuristicMatched`
against successful numeric-history / trend evidence elsewhere in the same `evidenceRefs` bundle so the model
is not handed a false “non-numeric” contradiction when internal tools already proved numeric samples.

A `fan_out` child surfaces its `uiArtifact.chart` line **even when the nested `tool_call` has no
`includeToolOutputRootFields` / `evidence.table` config** — mirroring the top-level `tool_call` `uiOnly`
fallback — so a Playbook that fans out chart-emitting internal tools never loses artifact awareness for
authoring reasons. **Authoring dependency:** the `primary_property` reconciliation only fires when the
`llm_summary` `evidenceRefs` list **both** the `primary_property` node **and** the node that proves numeric
history (trend summary or the numeric-history `fan_out`); a Playbook that references only one can still
surface an unqualified heuristic line, so list both when the summary discusses the primary property.

**Evidence wording:** engine lines for `primary_property` must state **alert-driven selection**
when `alertGroups` is present, describe **`numericNameHeuristicMatched`** only as a **legacy trend-planning name
hint** (not the basis for which property alerts chose), and prefer **numeric history observed** when later tool
rows prove samples for the same property — so the final LLM is not steered toward “primary property selection
is based on a name heuristic.”

Guarantees:

- a live chart appears during the Playbook turn;
- the chart appears after conversation reload;
- artifact order is stable relative to final assistant text.

## 8. Table support

Data-insight Playbooks also need table evidence. Internal tool results that wire tables:

- `query_entities_by_taxonomy`
- `list_entities_by_type`
- `query_entities`
- `tabulate_cached_result`
- `fetch_cached_result`
- `analyze_entity_set`
- `invoke_service` / playbook-safe extended tools returning `INFOTABLE`

The shared emitter reuses the existing table builders:

- taxonomy/entity list table wire helpers,
- cached result table wire helpers,
- invoke-service infotable table wire helpers,
- tabular tool success / insight downlinks where already supported.

No Playbook needs a separate "show table" node: if the internal tool result would have
emitted a table at top level, it emits inside the Playbook as well.

## 9. Ordering and UX

Artifact wire frames may be emitted before the final `assistantText`, just like normal tool
results may send charts before the final summary.

Order for a Playbook turn:

1. activity/task progress frames,
2. internal tool artifacts as each node completes,
3. final assistant content,
4. done frame.

This is acceptable because the UI already attaches chart/table frames to the assistant row for
the same `request_id`.

If a Playbook emits multiple charts from a fan-out, each chart must receive a distinct
`chartId` from `AgentToolContext.nextParlerChartId()`.

## 10. Guardrails

- Emit artifacts only from successful tool JSON. Do not emit charts/tables from failed nodes.
- Use existing chart/table validation. Invalid chart/table candidates must be dropped, not
  partially rendered.
- Respect existing row/sample/cache budgets. Playbook emission must not bypass egress hardening.
- Do not emit aggregate-only numeric history as a live chart unless the result contains a
  validated `chartBlock` explicitly marked `chartEmitted=true`.
- Do not duplicate artifacts. A given internal tool result should produce at most one numeric
  history chart through the numeric-history path and at most the existing applicable table frame.
- Preserve current permission semantics. Internal Playbook tools are still executed through the
  same first-party executors and visibility-aware gates.

## 10.1 Risks and interactions

- **Stream volume:** persisting one Stream `tool` row per internal call multiplies rows for busy
  Playbooks; payloads stay within the egress / sampling rules in
  `docs/agent/tool-result-egress-control.md`.
- **Ordering:** `fan_out` children run sequentially, which is what makes the §9 ordering
  guarantee hold.
- **Generic Playbook ops:** statistics / compaction / generic ops are separate; this doc only
  covers **artifact parity** with the chat tool path.

## 11. Telemetry

`parlerChartWireEmittedCount` is the **single wire-visible** chart counter and includes Playbook-emitted charts, so UI /
harness expectations stay meaningful. There are no Playbook-specific counters in `done.llm_usage` / persisted `llmUsage`;
adding any would need `ParlerLlmUsageWireSanitizer` allowlisting plus `API_CONTRACT` / `UI_CLIENT_PROTOCOL` /
`CONTRACT_VERSION` updates.

Each internal tool result logs a compact line:

```text
PLAYBOOK_ARTIFACT_WIRE internalTool playbookId=... nodeId=trends_by_asset[0] tool=query_property_history toolCallId=pb-... parlerChartWireEmittedCount=1
```

Failures log `PLAYBOOK_ARTIFACT_WIRE persist/emit failed playbookId=... tool=...` at WARN.

## 12. Tests

### Unit tests

- `PlaybookRunner` invokes artifact callback for a direct `tool_call`.
- `PlaybookRunner` invokes artifact callback for each `fan_out` child.
- Callback is not invoked for failed tool nodes.
- Synthetic node ids for fan-out children are stable.

### Wire emitter tests

- Numeric `query_property_history` inline JSON emits a chart through the shared emitter.
- Aggregate-only numeric history does not emit a chart.
- `query_entities_by_taxonomy` / cached result / `invoke_service` INFOTABLE success emits the
  same table block as top-level tool result.
- Duplicate calls do not duplicate the same artifact.
- **Parity:** `ParlerToolArtifactWireEmitter` is the single implementation backing both AlwaysOn
  `streamSink` paths (`ParlerStreamToRemoteThing` and `runParlerPostToolAgentLoop`) plus Playbook-internal emission.

### Agent integration tests

- `start_playbook` with an internal numeric history call emits `type: "chart"` before final
  `done`.
- `LLM_TURN_PERFORMANCE` has `parlerChartWireEmittedCount > 0`.
- Stream history exporter hydrates the Playbook-emitted chart after reload.
- Internal table-producing tool call emits and rehydrates a table.

### Manual check on a ThingWorx server

With a Playbook that calls `query_property_history` without aggregate `actions`:

```text
Please compare the health of Contacting assets ORD Contacting 02 and ORD Contacting 01 over the past 24 hours.
```

Expected:

- `start_playbook` is the only model-facing tool call.
- Playbook internal `query_property_history` produces trend evidence.
- UI receives one or more trend charts.
- final answer cites the same evidence without claiming unavailable aggregate statistics.
