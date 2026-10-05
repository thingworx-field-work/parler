# UI client protocol — transport vs. presentation state

**Goal:** The **chat UI** must depend only on a **small, stable set of UI events and state** — not on raw `WebSocket` APIs. For Thingworx, you will receive similar payloads through **AlwaysOn / local WebSocket libraries**; only the **transport adapter** changes.

**Normative implementation:** **`parler-ui/lib/`** — `chatSession.js` (reducer), `wireAdapter.js`, `types.js` (JSDoc shapes). **Wire JSON** matches **[`API_CONTRACT.md`](./API_CONTRACT.md)**; this file defines the **UI layer** on top. (Older Vue/React/Lit lab apps were removed from the repo.)

**Further Insight / `insightEnvelope` (D7):** Agent tool success payloads may include **`insightEnvelope`** per **`CONTRACTS/TABULAR_INSIGHT.md`**. The optional wire frame **`tabular.tool_success`** (see **`API_CONTRACT.md`**) carries a **compact** projection; the adapter maps it to **`assistant.insightEnvelope`**, and the reducer stores **`readInsightEnvelopeLoose(payload).insightEnvelope`** on the assistant row as **`insightEnvelopeLoose`** (presentation **must not** branch on column semantics until the D7 gate). **`ChatUiState` / `UiEvent`** otherwise do not normatively embed the full tool success root. Until the **passive or active gate** in **§View — `insightEnvelopeLoose` (D7)** below fires, UI code must not branch on `insightEnvelope` field semantics except via **`readInsightEnvelopeLoose`**. When a gate fires, update this protocol, **`TABULAR_INSIGHT.md`**, every other affected contract, **`CONTRACT_VERSION.md`**, the corresponding implementation, and tests in the same commit; the reviewed topic slice contains that atomic commit.

---

## 1. Layers

| Layer | Responsibility | Thingworx migration |
|-------|----------------|---------------------|
| **Transport** | Connect, send bytes/text, parse JSON frames | Swap `ParlerWebSocketTransport` for AlwaysOn adapter that yields the same parsed objects |
| **Wire adapter** | `unknown` → typed **wire messages** (`WireServerMessage`) | Reuse; implement `parseServerMessage(raw)` for your bus |
| **UI reducer** | `WireServerMessage` → **immutable `ChatUiState`** | **Keep as-is** (or port reducer line-for-line) |
| **View (`<parler-ui>`)** | Renders from internal state driven by the same reducer | ThingWorx Mashup binds properties / services; no raw `WebSocket` in views |

**Rule:** Keep **transport** behind the element’s AlwaysOn adapter; views depend on **state + callbacks**, not sockets.

### 1.1 Composer primary control — Send vs Stop (`<parler-ui>`)

When **`GetConnectionInfo`** reports **`capabilities.supportsCancellation === true`** (strict JSON boolean per **`API_CONTRACT.md`** **2.4.38** — coordinated **`parler-agent` `0.1.187`** + **`parler-ui-widget` `0.1.80`**), **`parler-ui`** **MUST** show a **Stop** primary control while a user turn is active (**`busy`** with a known **`activeRequestId`**, and not in the pre-**`request_id`** invoke-wait window). **Stop** invokes **`ParlerGateway.CancelUserPrompt`** on the bound Gateway (**same **`entityName`** as **`SubmitUserPrompt`**), using **`buildCancelUserPromptParams`**. After a successful **`accepted`**, or after the single **300 ms** **`not_active`** retry yields a second **`not_active`**, the client **MUST** arm the **15 s** bounded fallback timer (same as post-**`accepted`** wait). Invoke failure, unparseable **`CancelUserPrompt`** JSON, and unexpected **`status`** values **MUST** clear **stopping** only (clear the disabled Stop / ellipsis state) without injecting a local terminal — the user **MAY** press Stop again while the turn remains **`busy`**. **`CancelUserPrompt`** **`unsupported`** **MUST** synthesize **`session.cancel_unsupported_local`** (not **`session.cancelled`**) so **`cancelledRequestIds`** is not populated and a late real **`session.done`** may still merge into the assistant row (**`UI_CLIENT_PROTOCOL.md`** rules **7** / **7c**). After **`accepted`** (or while the bounded fallback timer is pending), the primary control **MUST** remain in **stopping** until a matching fourth terminal (**`session.cancelled`**, **`session.done`**, **`session.error`**, **`session.superseded`**) clears **`busy`**, or until the **bounded local fallback** applies **`session.cancelled`** per **`docs/agent/turn-cancellation-control.md`**. When **`supportsCancellation`** is absent or **`false`**, the widget **MUST NOT** show Stop.

---

## 2. Wire messages (server → client)

The **core shape** of each variant matches **`API_CONTRACT.md`** § WebSocket (server → client). **Additional top-level fields** are allowed when a **transport profile** requires them — see **`API_CONTRACT.md`** (“`conversation_id` on other frames”) and **[`agent-alwayson.md`](../docs/architecture/agent-alwayson.md)** §6.2 (**AlwaysOn `ReceiveMessage` profile**: **`conversation_id` required on every** downstream object). Reference TypeScript union (optional extension keys not exhaustively listed):

```ts
// AlwaysOn ReceiveMessage profile: every downstream object includes conversation_id (see API_CONTRACT.md, agent-alwayson.md §6.2).
export type WireServerMessage =
  | { type: "session.ack"; conversation_id: string; request_id: string }
  | { type: "activity"; conversation_id: string; request_id: string; message: string }
  | { type: "content.delta"; conversation_id: string; request_id: string; delta: string }
  | { type: "chart"; conversation_id: string; request_id: string; chart: ChartBlock }
  | { type: "table"; conversation_id: string; request_id: string; table: TableBlock }
  | { type: "chart_group"; conversation_id: string; request_id: string; group: ChartGroupManifest }
  | {
      type: "tabular.tool_success";
      conversation_id: string;
      request_id: string;
      payload: Record<string, unknown>;
    }
  | { type: "done"; conversation_id: string; request_id: string }
  | { type: "error"; conversation_id: string; request_id: string; message: string; code?: string }
  | {
      type: "session.superseded";
      conversation_id: string;
      message: string;
      request_id?: string;
      code?: string;
    }
  | {
      type: "approval.required";
      conversation_id: string;
      request_id: string;
      pending_id: string;
      expires_at: string;
      tool_name: string;
      summary: { title: string; lines: { label: string; value: string }[] };
      actions: ("approve" | "cancel" | "reject_with_comment")[];
    }
  | {
      type: "approval.resolved";
      conversation_id: string;
      request_id: string;
      pending_id: string;
      outcome: "approved" | "cancelled" | "expired" | "rejected";
      executed?: boolean;
      error?: { code?: string; message: string };
      hitl_resolution_source?: "gateway_user_stop";
    }
  | {
      type: "task.state";
      conversation_id: string;
      request_id: string;
      schemaVersion: 1;
      status: string;
      title?: string;
      summary: Record<string, unknown>;
      items: unknown[];
    }
  | {
      type: "rate_control.status";
      conversation_id: string;
      request_id: string;
      status: "waiting" | "resumed";
      reason?: string;
      wait_ms?: number;
      retry_after_ms?: number;
    }
  | {
      type: "session.cancelled";
      conversation_id: string;
      request_id: string;
      reason?: string;
      message?: string;
    };
```

**`approval.resolved` semantics:** **`outcome`** is the **user’s** decision, not whether the platform side-effect succeeded. **`tool_name`** on the prior **`approval.required`** may be **`set_property_value`**, **`invoke_service`**, or another gated tool (extension-defined); the widget treats **`tool_name`** as opaque except for display. If the user **Approve**d but **`set_property_value`** compare-and-stale blocked the write, **`outcome` is still `"approved"`** with **`executed: false`** and **`error.code`** **`STALE_TARGET_VALUE`** or **`VALUE_CHANGED_SINCE_REQUEST`** — see **`API_CONTRACT.md`** (normative table + example). For **`invoke_service`**, **`executed`** reflects whether the service call returned a **success** tool result (**`status":"success"`**) vs **error** after approval (v1: **no** compare-and-stale for services).

(`tool.*` events are optional on the wire; the UI reducer ignores unknown `type` values. **`tabular.tool_success`**, **`task.state`**, and **`rate_control.status`** are handled when present — see §3 event table.)

`ChartBlock` and the **`type: "chart"`** wire frame are defined in [`CHART_CONTRACT.md`](./CHART_CONTRACT.md). **`TableBlock`** and **`type: "table"`** are defined in [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md). See also `parler-ui/lib/types.js`.

**Table disclosure headers (reference `parler-ui`):** When **`TableBlock.presentationTitle`** is a non-empty string, collapsed table disclosure **SHOULD** show that label instead of the column-list / row-count fallback (**`artifactPresentation.tableDisclosureSummaryLabel`**). The label **MUST** be treated as a **single-line** string (no intentional line breaks); clients **SHOULD NOT** insert hard line wraps inside the disclosure summary. **`presentationTitle`** is server-built per [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md) §3.1.1; it does not affect chart pairing or row data.

---

## 3. UI events (internal, transport-agnostic)

The adapter maps each **wire message** to zero or more **UiEvents**:

| Wire `type` | UiEvent(s) |
|-------------|------------|
| `session.ack` | `{ type: "session.ack", requestId }` — if `request_id` is missing on the wire, the reference client falls back to the outbound turn id so `session.done` still clears **busy**. |
| `activity` | `{ type: "assistant.activity", requestId, text }` — maps from `message`; empty string clears ephemeral **activity** on the assistant tail. |
| `content.delta` | `{ type: "assistant.append", requestId, text }` |
| `chart` | `{ type: "assistant.chart", requestId, chart }` |
| `chart_group` | `{ type: "assistant.chartGroup", requestId, group }` — **`group`** is a validated **`ChartGroupManifest`** (`asChartGroupManifest`; **`CHART_CONTRACT.md`** §3.5); an invalid manifest is dropped with diagnostic **`CHART_GROUP_INVALID`** and the member charts stay single cards. |
| `table` | `{ type: "assistant.table", requestId, table }` — **`table`** is a validated **`TableBlock`** (see [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md)); invalid payloads and **unsupported `kind`** values **MUST** be dropped by the adapter (no `UiEvent`). |
| `tabular.tool_success` | `{ type: "assistant.insightEnvelope", requestId, toolSuccessPayload }` — **`toolSuccessPayload`** is the wire **`payload`** object; reducer **only** passes it to **`readInsightEnvelopeLoose`** (Further Insight / D7). |
| `done` | `{ type: "session.done", requestId, assistantMessageId?, completedAt?, llmUsage? }` — when the wire frame carries **`assistant_message_id`**, the adapter **MUST** forward it as **`assistantMessageId`**; when **`completed_at`** is present, forward as **`completedAt`** (ISO-8601 string). When **`llm_usage`** is present, the adapter **MUST** forward a **whitelisted** subset as **`llmUsage`** (same key set as history **`llmUsage`**); unknown keys **MUST** be dropped. Reducer rule 7 copies these onto the matching assistant row when ending the turn. |
| `error` | `{ type: "session.error", requestId, message, code? }` |
| `session.superseded` | `{ type: "session.superseded", requestId, message, code?, conversationId }` — same end state as a session error for the UI (clears **busy**, sets **`error`** to `message`). |
| `session.cancelled` | `{ type: "session.cancelled", requestId, reason?, message?, conversationId }` — user stop (**`CancelUserPrompt`**); clears **busy** and **`activeRequestId`** like **`session.done`**, clears **`approvalGate`** when open for the same **`requestId`**, clears tail **`activity`** / **`rateControlWaiting`**; **does not** set the global **`error`** banner. |
| `approval.required` | `{ type: "approval.required", requestId, conversationId, pendingId, expiresAt, toolName, summary, actions }` — opens **approval gate** UI (see [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §3). |
| `approval.resolved` | `{ type: "approval.resolved", requestId, conversationId, pendingId, outcome, executed?, error?, hitlResolutionSource? }` — **`hitlResolutionSource`** maps from wire **`hitl_resolution_source`** when present; **only** **`"gateway_user_stop"`** is valid (omit for in-card HITL). Closes the gate for that `pendingId`; **does not** clear **busy** alone (reducer rule 10). **`expired`:** server **must** follow with **`session.done`** or **`session.error`** (same `requestId`) — typically **no** post-approval LLM stream. **`approved` / `cancelled` / `rejected`:** server **may** reuse **this `requestId`** for the **post-approval LLM stream** before **`session.done`** / terminal **`session.error`** **unless** **`hitlResolutionSource === "gateway_user_stop"`** (then terminal **`session.cancelled`** ends the turn per **`API_CONTRACT.md`**). **`approved` + `executed === false` + STALE codes:** see [`API_CONTRACT.md`](./API_CONTRACT.md), [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §2.5. |
| `task.state` | `{ type: "assistant.taskState", requestId, snapshot }` — **`snapshot`** is the validated wire payload minus **`type`** / **`conversation_id`** / **`request_id`** ( **`schemaVersion`**, **`status`**, **`title`**, **`summary`**, **`items`** ). **`items[].source`** may be **`skill`**, **`tool`**, or **`playbook`** (Playbook V1a — **`API_CONTRACT.md`** 2.4.18). Replaces the prior full snapshot on that assistant row (see §4). |
| `rate_control.status` | `{ type: "assistant.rateControlStatus", requestId, waiting, reason?, waitMs?, retryAfterMs? }` — live-only provider rate-gate UI hint (**`API_CONTRACT.md`** § **`rate_control.status`**). Reference **`parler-ui`** sets assistant-row **`rateControlWaiting`** when **`waiting`** is true for the active **`request_id`**; **`resumed`** clears it. Stale **`waiting`** events (wrong **`activeRequestId`** or unknown row) **MUST** be ignored. |

**Client-only `UiEvent` (never emitted by `wireToUiEvent`):** **`{ type: "session.cancel_unsupported_local", requestId, conversationId }`** — synthesized by **`<parler-ui>`** when **`ParlerGateway.CancelUserPrompt`** returns **`unsupported`**. Reducer rule **7c** applies.

The reducer **only** consumes `UiEvent`, never raw WebSocket objects.

**`<parler-ui>`:** before `wireToUiEvent`, drop frames whose `conversation_id` does not match the widget’s bound `conversationId` when both are non-empty — at minimum **`session.superseded`**, and **also `approval.required` / `approval.resolved`** (same rationale: shared AlwaysOn topics).

---

## 4. `ChatUiState` (what the view binds to)

Logical shape (see `parler-ui/lib/chatSession.js`):

| Field | Meaning |
|-------|---------|
| `rows[]` | Conversation rows: `user` text or `assistant` `{ markdown, charts[], tables[], groups[]?, activity?, insightEnvelopeLoose?, taskState?, rateControlWaiting?, assistantMessageId?, completedAt?, feedbackRating?, llmUsage? }` — **`assistantMessageId`** / **`completedAt`** are optional stable anchors for feedback and history cutoff (see **`API_CONTRACT.md`** **`done`**). **`llmUsage`** is optional sanitized token/provider and **turn-level performance** telemetry from terminal **`done.llm_usage`** or **`ai-parler-history-v1`** assistant rows (subset of persisted Stream `llmUsageJson`; whitelist in **`parler-agent`** **`ParlerLlmUsageWireSanitizer`** and **`parler-ui`** **`llmUsageWire.js`** — e.g. **`turnWallMs`**, **`rateWaitMs`**, **`agentIterations`**, **`toolCallCount`**, **`llmWallMs`**, **`toolWallMs`**, token totals (optional **`reasoningTokensTotal`** — additive provider reasoning subset; **`completionTokensTotal`** unchanged as provider total output), **`fetchAfterCompleteAnswerSetCount`**, **`roundsHitMaxOutput`**, **`firstToolCallCacheHit`**, **`markerEmitterEnabled`**, **`noToolFinalAnswerApplied`**, **`toolExecutionMaxConcurrency`**, **`multiToolCallRoundsCount`**, **`repetitionBlockedCount`**, **`parlerChartWireEmittedCount`**, **`chartExpectedButMissing`** (chart builder invoked this turn with zero chart wires downlinked; not from user-message keywords), **`chartRescueAttempted`**, **`presentationPhaseEntered`**, **`presentationActionsRequested`**, **`presentationActionsExecuted`**, **`presentationActionsBlocked`**, **`toolProtocolViolation`**). **`feedbackRating`** (`up` \| `down`) **MAY** appear on assistant rows after **`ai-parler-history-v1`** hydration when the server replays last persisted thumbs state (not emitted on live **`session.done`**). **`taskState`** holds the latest v1b **`task.state`** snapshot for that row (see **`API_CONTRACT.md`** **`task.state`**). **`rateControlWaiting`** is live-only (not hydrated from history): when **`true`**, the active-turn working pulse uses the rate-gate wait tone until **`assistant.rateControlStatus`** with **`waiting: false`**, terminal **`session.done`**, **`session.error`**, **`session.superseded`**, **`session.cancelled`**, or forward assistant progress clears it (reducer rules 3–7, 6b–6c, 7a). **Gateway UI (client-only):** when **`SubmitUserPrompt`** on **`ParlerGateway`** is enabled, **`parler-ui`** **MAY** render compact icon row actions (copy, print, thumbs, **turn details**, cutoff) on eligible completed assistant rows; the **turn details** affordance **MUST** surface only fields already present on the row / bind context (no extra wire, no LLM, no raw tool payloads or secrets). See **View — `insightEnvelopeLoose` (D7)** below. |
| `busy` | Outbound request in flight |
| `error` | Last session error string, if any |
| `activeRequestId` | Active streaming request, if any |
| `approvalGate` | (Planned) `null` or open gate: summary, `pendingId`, **`requestId`** (echo on **`approval.decision`** uplink), expiry — see [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §3.2. Also carries the **client-only** decision-submit substate **`submitState`** (**`"idle"`** | **`"submitting"`** | **`"submitted"`**, absent read as **`"idle"`**) and **`submitError`** (**`string | null`**) — see rule **10b**. Neither is ever sent or received on the wire. |
| `cancelledRequestIds` | **Set** of **`request_id`** values the UI has ended with **`session.cancelled`** (user stop). **Bounded** (v1 cap **32**, FIFO evict oldest). Rule **7b** uses it to ignore **late** frames for those ids — non-terminal assistant / rate-gate (rules **2**–**6c**), late **`session.done`**, **`session.error`**, **`session.superseded`**, and late **`approval.required`** (`docs/agent/turn-cancellation-control.md` §6.2 / §12). Cleared when the widget resets conversation state to empty. |
| `unsupportedLocalRequestIds` | **Set** of **`request_id`** values for which the UI cleared **`busy`** via client-only **`session.cancel_unsupported_local`** (rule **7c**), allowing **at most one** subsequent late real **`session.done`** to merge **`assistantMessageId`** / **`completedAt`** / **`llmUsage`** onto that assistant row (rule **7**) **while the id remains in this set**. **Bounded** (v1 cap **32**, FIFO). **`session.error`** / **`session.superseded`** (rules **8** / **9**) **also remove** the matching id — terminal symmetry. **Not** a replay tombstone like **`cancelledRequestIds`**. Cleared on widget reset. |

### Reducer rules

1. On **user send**: append a `user` message; set `busy=true`, clear `error`, set `activeRequestId` to the outbound id.
2. On **`assistant.activity`**: set ephemeral `activity` on the assistant tail (empty clears). Shown like “Working…” until real output arrives.
3. On **`assistant.append`**: append `text` to `markdown`; if appended text is non-whitespace, clear `activity` and clear **`rateControlWaiting`** on that row.
4. On **`assistant.chart`**: append `chart`; clear `activity` and **`rateControlWaiting`**.
5b. On **`assistant.chartGroup`**: upsert **`group`** into that row's **`groups[]`** by **`groupId`** — apply it only when the row holds no entry for that id, or the held entry is not **`final`** and has a lower **`revision`**; otherwise return the prior state unchanged (duplicates, out-of-order and post-final revisions are ignored). Clear `activity` and **`rateControlWaiting`** when applied. Rule **7b** applies.
5. On **`assistant.table`**: append `table` to that row’s **`tables[]`**; clear `activity` and **`rateControlWaiting`** (same presentation rule as **`assistant.chart`**).
6. On **`assistant.insightEnvelope`**: set **`insightEnvelopeLoose`** on the matching assistant row from **`readInsightEnvelopeLoose(toolSuccessPayload)`**; **no** branching on envelope child fields (D7). Omit update when the helper returns **`null`**.
6b. On **`assistant.taskState`**: replace **`taskState`** on the matching assistant row with **`snapshot`** (full replace — server-authored metadata only per **`API_CONTRACT.md`** / **`docs/agent/task-state.md`**). **Do not** merge with prior snapshots client-side; clear **`rateControlWaiting`** on that row.
6c. On **`assistant.rateControlStatus`**: when **`waiting`** is **`true`**, set **`rateControlWaiting: true`** on the matching assistant row **only** if **`busy`** is **`true`**, **`activeRequestId`** equals the event’s **`requestId`**, and the row exists. Otherwise ignore the event (stale **`waiting`**). When **`waiting`** is **`false`** (**`resumed`**), clear **`rateControlWaiting`** on the matching row when present.
7. On **`session.done`**: set `busy=false`, clear `activeRequestId`, clear `activity` and **`rateControlWaiting`** on that assistant row; when the event carries **`assistantMessageId`** / **`completedAt`** / **`llmUsage`**, set those properties on the row (overwrite / add). **Remove** the event’s **`requestId`** from **`unsupportedLocalRequestIds`** whenever this rule applies a **`session.done`** merge for that id (normal active turn **or** the late-metadata path below). **Late metadata merge (v1, narrow):** when **`activeRequestId`** is already **`null`**, **`busy`** is **`false`**, the event’s **`requestId`** is still present in **`unsupportedLocalRequestIds`**, and the **last** assistant row for that id exists with **no** **`assistantMessageId`** yet, the same property merge **still applies** — this path exists **only** after **`session.cancel_unsupported_local`** (rule **7c**) cleared **`busy`** without a cancel tombstone so a real server **`session.done`** may still arrive (**`API_CONTRACT.md`** **2.4.36**). **MUST NOT** use this late path when the id is **absent** from **`unsupportedLocalRequestIds`** (e.g. after **`session.error`** or **`session.superseded`**), so stale success terminals cannot mutate an already-ended turn.
7a. On **`session.cancelled`**: same **`busy` / `activeRequestId` / `activity` / `rateControlWaiting`** clearing as **`session.done`** for the matching **`requestId`**, and clear **`approvalGate`** when it is non-null **and** **`approvalGate.requestId`** equals the event’s **`requestId`**; **do not** set the global **`error`** string. Mark the assistant row as **user-stopped** (**`turnCancelled: true`**) when that row exists; preserve partial **`markdown`**, **`charts[]`**, **`tables[]`**. **Always** record the event’s **`requestId`** in **`cancelledRequestIds`** (rule **7b**), including when no assistant row is found for that id.
7b. **Cancelled-request tombstone:** **`cancelledRequestIds`** holds **`request_id`** values the UI has terminated with **`session.cancelled`**. For any **`requestId`** in that set, the reducer **MUST** return the prior state unchanged for rules **2**–**6c** (**`assistant.activity`**, **`assistant.append`**, **`assistant.replace`**, **`assistant.chart`**, **`assistant.table`**, **`assistant.insightEnvelope`**, **`assistant.taskState`**, **`assistant.rateControlStatus`**) — late non-terminal frames must not mutate the stopped turn — **and** for **`session.done`**, **`session.error`**, **`session.superseded`**, and **`approval.required`** when the event’s **`requestId`** matches that id, so a late terminal or gate-opening frame cannot set global **`error`**, mutate a user-stopped turn, or reopen **`approvalGate`** (rule **7a**). **`session.ack`** is not evaluated against this set. **`approval.resolved`** (rule **10**) only clears **`approvalGate`** on **`pendingId`** match and does not open a gate. The first matching **`session.cancelled`** (rule **7a**) remains as written; duplicate **`session.cancelled`** for the same id is a no-op when **`activeRequestId`** no longer matches.
7c. On **`session.cancel_unsupported_local`** (client-only — **`CancelUserPrompt`** **`unsupported`**): same **`busy` / `activeRequestId` / `activity` / `rateControlWaiting`** / **`approvalGate`** clearing as **`session.done`** for the matching **`requestId`** when that id is still the active turn; **do not** set **`turnCancelled`**, **do not** add **`requestId`** to **`cancelledRequestIds`**, and **do not** set the global **`error`** string. When an assistant row exists for that **`requestId`**, **MUST** add the id to **`unsupportedLocalRequestIds`** (bounded FIFO per the **`ChatUiState`** table) so rule **7** may apply **one** late real **`session.done`** for terminal metadata. The shell **MAY** show a brief non-fatal banner (reference **`parler-ui`** uses the same strip as other gateway invoke hints).
8. On **`session.error`**: set `error`, `busy=false`, **`approvalGate = null`**; when an assistant row matches, clear tail **`activity`** and **`rateControlWaiting`**; **remove** the matching **`requestId`** from **`unsupportedLocalRequestIds`** (terminal symmetry with rule **7**).
9. On **`session.superseded`**: same as **`session.error`** for presentation (set `error` to the server `message`, `busy=false`, clear tail **`activity`** and **`rateControlWaiting`** when an assistant row matches); **`approvalGate = null`**; **remove** the matching **`requestId`** from **`unsupportedLocalRequestIds`**.
10. On **`approval.resolved`**: **only** clear **`approvalGate`** when **`pendingId`** matches (see table below). **Do not** set **`busy=false`** or clear **`activeRequestId`** from this event alone — v1 relies on **`session.done`**, **`session.error`**, **`session.superseded`**, or **`session.cancelled`** to end the turn ([API_CONTRACT.md](./API_CONTRACT.md) § **approval.resolved** and ending the assistant turn).
10b. **Decision-submit substate (client-only, v1, normative).** One **`approval.required`** accepts **one** **`approval.decision`** uplink. Three **client-only** events drive the open gate's **`submitState`** / **`submitError`**; they are **never** produced by **`wireToUiEvent`** and **never** sent on the wire, and each **MUST** match on **`pendingId`** alone so a late callback from a superseded gate cannot mutate the current one (non-matching → return prior state unchanged):

| Client-only event | Effect on the matching gate |
|---|---|
| **`approval.decisionSubmitting`** `{ type, pendingId }` | **`submitState = "submitting"`**, **`submitError = null`** — the uplink is in flight |
| **`approval.decisionAccepted`** `{ type, pendingId }` | **`submitState = "submitted"`**, **`submitError = null`** — the server **accepted the decision**; this says **nothing** about whether any gated operation began or succeeded (**`executed`** on **`approval.resolved`** remains the only statement about that) |
| **`approval.decisionSubmitFailed`** `{ type, pendingId, message }` | **`submitState = "idle"`**, **`submitError = message`** — the uplink failed; the user **may** retry |

**`approval.required`** opens every gate at **`submitState: "idle"`**, **`submitError: null`**. The client **MUST NOT** send a second **`approval.decision`** for a gate whose **`submitState`** is not **`"idle"`**, and **MUST** disable that gate's decision controls while it is not **`"idle"`**.

A **failed decision uplink is not a turn terminal.** The client **MUST NOT** synthesize **`session.error`** (or any other terminal) from it: the server may already be executing the decision, and rule **8** would clear **`busy`**, **`activeRequestId`**, and **`approvalGate`** on a live turn. **`busy`**, **`activeRequestId`**, the global **`error`**, and the gate itself **MUST** be left unchanged; only **`submitError`** is set. The turn still ends on **`approval.resolved`** plus a server terminal per rule **10**. Rules **7a**–**10** are otherwise unchanged, and clearing the gate discards both substate fields with it.

### Tool row `TURN_CANCELLED` (user stop — v1, normative)

When a **`role: tool`** row (or hydrated equivalent) carries structured **`code: "TURN_CANCELLED"`** (synthetic cancellation plumbing for replay pairing), the shell **MUST** render it as a **neutral stopped** outcome — **not** a red global failure, **not** the same presentation as uncaught tool **`error`**. The authoritative “stopped generating” signal for the assistant turn remains **`session.cancelled`** / stopped assistant row state.

### View — `insightEnvelopeLoose` (D7)

The shell **may** treat **`insightEnvelopeLoose`** as an opaque attachment: e.g. show a **presence-only** line when the property is truthy, **without** reading **`.schemaVersion`**, **`.rowEstimate`**, **`.columns`**, **`.sourceCacheId`**, or any other child field. **Any** view or adapter code that branches on or renders those child values **promotes** the Further Insight D7 gate — update **`UI_CLIENT_PROTOCOL.md`**, **`TABULAR_INSIGHT.md`**, **`CONTRACTS/CONTRACT_VERSION.md`**, the aligned implementation, and tests together in the same commit.

**Passive gate (grep-detectable):** If **`parler-ui/lib/`** (except **`insightEnvelopeRead.js`** exports such as **`readInsightEnvelopeLoose`**) contains **branch logic** that depends on **`insightEnvelope`** child fields — **`schemaVersion`**, **`rowEstimate`**, **`columns`**, **`columns[].name`**, **`columns[].baseType`**, etc. — including conditional rendering, **`chatSession.js`** reducer branches, or wire-adapter **`if`** tests on those fields, the UI is deemed to **substantively consume** **`insightEnvelope`**. **Passthrough only** (store the JSON blob without UI branching on child fields) **does not** trigger the gate.

**Active gate and release window:** When **P2** makes **`insightEnvelope`** **required** on the **LARGE** path (no longer optional), the affected **`parler-agent`**, **`CONTRACTS/*`**, **`parler-ui`**, and tests (when shipped) **must** change in the same commit so agent **`MUST`** and UI protocol **optional** cannot drift. Product/deliverable version coordinates and applicable product changelogs are then synchronized at the authorized receiving-branch integration/version cut.

**Loose-read single entry (pre-gate):** Before either gate fires, any UI read of **`insightEnvelope`** **must** go through **`parler-ui/lib/insightEnvelopeRead.js`** (**`readInsightEnvelopeLoose`**); **do not** scatter field access in reducers or main render paths (enables a single strict-read migration post-gate).

**Current baseline (informational):** **`insightEnvelopeLoose`** is **presence-only** in product UI; **`llm_tool_routing_guide.txt`** treats **`tabular.tool_success`** / **`insightEnvelope`** as opaque tool metadata for the LLM — **not** equivalent to UI field consumption.

### `approvalGate` (HITL) — invalidation (v1, normative)

Aligns with [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §2.4: after **disconnect / reconnect / session rebuild**, the client treats the `pending` as gone and **`approval.resolved` may not arrive**. The server **does not** detect the disconnect: its `pending` record stays until the TTL expiry, a decision, a user stop, or a process restart retires it, and until then the conversation keeps the pending-approval restrictions (for example on clearing history). The UI **must** still close the gate locally — implementers **must not** leave **Approve** active while guessing whether the server will follow up.

| Trigger | Client behavior |
|--------|-----------------|
| **Transport disconnect, reconnect, or transport-level reset** (socket closed, AlwaysOn session rebuilt, adapter “hard reset”) | Set **`approvalGate = null` immediately**. **Do not** wait for **`approval.resolved`**. **Do not** send **`approval.decision`** for a stale `pending_id` on the new transport unless a fresh **`approval.required`** was received on that transport. |
| **`session.error`** | **`approvalGate = null`** (see reducer rule **8**). Clears matching assistant **`activity`** / **`rateControlWaiting`**; removes matching **`requestId`** from **`unsupportedLocalRequestIds`**. |
| **`session.superseded`** | **`approvalGate = null`** (see reducer rule **9**). Clears matching assistant **`activity`** / **`rateControlWaiting`**; removes matching **`requestId`** from **`unsupportedLocalRequestIds`**. |
| **`session.cancelled`** | **`approvalGate = null`** when **`approvalGate` is non-null** and **`approvalGate.requestId`** equals the event’s **`requestId`**. Clears matching assistant **`activity`** / **`rateControlWaiting`**; clears **`busy`** / **`activeRequestId`** (reducer rule 7a). **Does not** set global **`error`**. |
| **`session.cancel_unsupported_local`** | Same gate + **`busy` / `activeRequestId`** / tail **`activity`** / **`rateControlWaiting`** clearing as **`session.cancelled`** for the matching **`requestId`** when that turn is still active (reducer rule **7c**). **Does not** tombstone **`requestId`** in **`cancelledRequestIds`**; **MUST** record the id in **`unsupportedLocalRequestIds`** when an assistant row exists so rule **7** may apply **one** late real **`session.done`** for **`assistantMessageId`** / **`completedAt`** / **`llmUsage`**, then drop the id from the set. |
| **`approval.resolved`** | **`approvalGate = null`** when the event’s **`pendingId`** matches the open gate (v1 single gate). **Does not** clear **`busy`** — the server **must** eventually emit **`session.done`**, **`session.error`**, **`session.superseded`**, or **`session.cancelled`** (same **`request_id`**) after any post-approval stream that will run under the extension default, **or** follow the **`gateway_user_stop`** path (**`session.cancelled`** only) per **`API_CONTRACT.md`**. |
| **Failed `approval.decision` uplink** | **`approvalGate` unchanged** — set only **`submitError`** and **`submitState = "idle"`** (rule **10b**). **Do not** synthesize **`session.error`**: the server may already be executing the decision, and a terminal would clear **busy**, **`activeRequestId`**, and the gate on a live turn. |
| **`session.done`** | **`approvalGate = null`** only when **`approvalGate` is non-null** and **`approvalGate.requestId` equals** the event’s `requestId` (that assistant turn ended). Other **`done`** events **must not** clear a gate bound to a different turn. |

**Server ordering (recommended, same doc §3.3):** While a **`PENDING`** approval is shown, **avoid** emitting **`session.done`** for that assistant **`request_id`** until **`approval.resolved`** (or equivalent cancel path), to reduce races with the last row in the table.

**Post-approval LLM stream (`request_id`, v1, normative):** After **`approval.resolved`**, when the server runs a **follow-up LLM completion** on the same turn, all **`activity` / `content.delta` / `chart` / `table` / `tabular.tool_success` / `task.state` / `rate_control.status`** and the terminal **`session.done` / `session.error`** **must** carry the **same `request_id`** as **`approval.required`** / **`approval.resolved`**. That path applies to **`approved`**, **`cancelled`**, and **`rejected`** under the shipped ThingWorx extension default — **not** when **`hitl_resolution_source`** is **`gateway_user_stop`** (no LLM continuation). The reducer continues to append to the **same assistant row** and keeps **`activeRequestId`** / **busy** consistent with **one turn**. **Do not** switch to a new `request_id` for that summary unless the contract is extended ([`API_CONTRACT.md`](./API_CONTRACT.md) § **Post-approval LLM stream**, [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §2.2).

**Turn completion after `approval.resolved` (v1, normative):** Because reducer rule **10** leaves **`busy`** set until **`session.done`**, **`session.error`**, **`session.superseded`**, or **`session.cancelled`**, the server **must** emit one of those terminals (same **`request_id`**) **after** any post-approval stream that will run under the extension default, or **immediately** when no such stream runs (e.g. **`expired`**, **`gateway_user_stop`**, or a minimal implementation). See **`API_CONTRACT.md`** § **`approval.resolved` and ending the assistant turn**.

**Ordering:** The server may emit `chart` before or between `content.delta`; the UI must show charts attached to the **same assistant turn** for that `request_id`.

---

## 5. Client → server (wire)

Single envelope per user turn (see **`API_CONTRACT.md`**):

```json
{
  "type": "chat.request",
  "request_id": "<uuid>",
  "payload": {
    "messages": [ { "role": "user", "content": "..." } ],
    "model": null,
    "conversation_id": "optional-thread-id",
    "user_timezone": "Europe/Berlin"
  }
}
```

Optional **`conversation_id`** (non-empty string) should be set when the UI has a stable thread key (e.g. ThingWorx `conversationId`) so the server can implement **single-live** or routing. Omit when unused.

The **transport** sends this string; the **session service** builds `payload.messages` from `ChatUiState` (full thread + new user line).

### User timezone (`user_timezone` / `userTimezone`)

**Normative for `parler-ui`:**

1. **Source:** `Intl.DateTimeFormat().resolvedOptions().timeZone` (or equivalent). If unavailable, omit (empty string on ThingWorx **InfoTable** columns is allowed).
2. **When to send:** every outbound user turn **SHOULD** include the zone when known, so reconnects and multi-tab use the **current** browser zone (see [`API_CONTRACT.md`](./API_CONTRACT.md), [`times-solution.md`](../docs/architecture/times-solution.md)).
3. **ThingWorx:** **`SubmitUserPrompt`** / **`ParlerStreamToRemoteThing`** use parameter **`userTimezone`** (camelCase); semantics match logical **`user_timezone`** on `chat.request.payload`.
4. **Display vs query:** local axis formatting does **not** replace `user_timezone` for interpretation.
5. **Reducer:** no `ChatUiState` field is required for v1.

### Host context (`hostContext`, ThingWorx uplink)

**Normative for `parler-ui` (when Mashup supplies `HostScopeJson`):**

1. **Source:** the **bound Mashup Property** value (UTF-8 JSON **string**), whether populated by **design-time binding** or **Composer script**. If the bound value is **absent** or **byte-empty** (UTF-8 length **0**), omit **`hostContext`** on the invoke (empty STRING column is allowed where the platform sends it). **Whitespace-only** payloads (e.g. three spaces) **MUST** be passed through unchanged so the server can apply fail-open validation. **Wire read:** the value placed in the **`hostContext`** column **MUST** be copied **without** trimming leading or trailing whitespace (byte-for-byte identical to the bound source for that Send — see **`API_CONTRACT.md`** § **`hostContext`** — **Wire bytes**).
2. **When to send:** each outbound user turn **SHOULD** include **`hostContext`** when the host UI has a **current host-context snapshot** (**`key`** + **`context`** — see **[`host-context.md`](../docs/architecture/host-context.md)** §4). Purpose: let the server render audited template context without inflating the user prompt (see **[`API_CONTRACT.md`](./API_CONTRACT.md)** § **`hostContext`**).
3. **Read timing:** the value sent **MUST** be read **synchronously when the user commits the Send** (same instant as **`SubmitUserPrompt`** / **`ParlerStreamToRemoteThing`**), per **[`host-context.md`](../docs/architecture/host-context.md)** — **not** driven by asynchronous Property-change subscriptions that could race ahead of or behind the turn.
4. **ThingWorx:** **`SubmitUserPrompt`** / **`ParlerStreamToRemoteThing`** optional parameter **`hostContext`** (camelCase); semantics match **`API_CONTRACT.md`** (including **wire-byte** preservation).
5. **History / live UI rows:** optional nested **`hostContext`** on **`ChatRowUser`** / **`ai-parler-history-v1`** user rows (**`API_CONTRACT.md`** — History export). **`hydrateHistoryFromJsonString`** copies it into **`ChatUiState.rows[]`**. On Send, **`parler-ui`** **MAY** attach a **best-effort** live snapshot from the same wire read (authoritative metadata remains on the server Stream row). When **`rawJsonStored`** is **`false`**, the UI **MUST** forward-scan prior user rows in the loaded thread for the newest row with matching **`hash`** and **`rawJsonStored=true`** to obtain expandable raw JSON; when none is found, show **`raw JSON unavailable in loaded history`** and **MUST NOT** show the raw-json copy icon (**`docs/architecture/host-context-turn-state.md`** §4.2).
6. **Disclosure UI:** when **`hostContext.outcome`** is present and not **`ABSENT`**, render a **collapsed-by-default** summary above the user prompt (`Host context: {key} · changed|unchanged · {bytes} bytes`). User prompt text and expanded raw JSON each get a compact copy icon only (**no** assistant/tool copy extension in this milestone).

### `approval.decision` (AlwaysOn uplink, v1)

After **`approval.required`**, the client sends a decision on the **same AlwaysOn uplink** used for **`chat.request`** (not a separate REST call). Shape matches **`API_CONTRACT.md`**:

```json
{
  "type": "approval.decision",
  "request_id": "<must equal approval.required.request_id>",
  "conversation_id": "<thread>",
  "pending_id": "<uuid>",
  "decision": "approve",
  "comment": ""
}
```

**`request_id` is required** on the wire and **must** match the paired **`approval.required.request_id`**. Servers **must** reject mismatches (**MUST**, not optional) — see **`API_CONTRACT.md`** § `approval.decision`.

---

## 6. Consistency checklist (code ↔ docs)

When changing behavior:

1. Any edit to a normative **`CONTRACTS/*.md`** file other than the version ledger bumps **`CONTRACTS/CONTRACT_VERSION.md`** in the same commit; any normative behavior change also updates the aligned implementation and tests in that commit.
2. Update **`CONTRACTS/API_CONTRACT.md`** if JSON on the wire changes.
3. Update **`CONTRACTS/CHART_CONTRACT.md`** if `ChartBlock` or the wire `chart` frame changes.
4. Update **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** if `ChatUiState` or `UiEvent` changes, with **`parler-ui/lib/*`** and aligned tests in the same commit.

---

## 7. Scope reminder (lab / single user)

This project targets **local, single-user** iteration and a **polished chat window**. **Do not** add multi-tenant isolation, deployment hardening, or Thingworx abstraction layers unless explicitly requested.
