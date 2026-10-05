# Turn Cancellation Control

Status: implemented — gateway `CancelUserPrompt` for parked HITL turns and cooperative cancellation of running
`AgentLoop` turns, terminal `session.cancelled`, and the `parler-ui` Stop control.  
Scope: `parler-ui`, `parler-ui-widget`, `parler-agent`, and normative wire / UI contracts.

Normative wire and UI rules live in `CONTRACTS/API_CONTRACT.md` (`GetConnectionInfo`, `CancelUserPrompt`,
`session.cancelled`, `approval.resolved.hitl_resolution_source`) and `CONTRACTS/UI_CLIENT_PROTOCOL.md`; any edit to them
bumps `CONTRACTS/CONTRACT_VERSION.md` in the same change.

## Design rules (normative)

1. **Fourth terminal:** **`session.cancelled`** (wire **`type: "session.cancelled"`**, adapter → **`session.cancelled`**) is a **first-class terminal sibling** of **`session.done`**, **`session.error`**, and **`session.superseded`** for clearing **`busy`**, **`activeRequestId`**, **`approvalGate`**, assistant **`activity`**, and **`rateControlWaiting`**, without the global error banner.
2. **Gateway stop vs in-card HITL:** **Not** identical. In-card **`cancelled`** / **`reject_with_comment`** / **`approve`** follow the post-**`approval.resolved`** **AgentLoop** continuation rules. **Gateway `CancelUserPrompt`** on **`approval_parked`** invalidates the pending approval, appends synthetic tool rows, emits **`approval.resolved`** (with **`hitl_resolution_source: "gateway_user_stop"`** per **`API_CONTRACT.md`**), then terminal **`session.cancelled`**, with **no** post-approval LLM stream. Contracts carry the discriminant; implementors **must not** conflate the two **`cancelled`** meanings. Servers **must not** emit the literal **`approval_card`** on **`hitl_resolution_source`**; the in-card path **omits** the field (**`CONTRACTS/API_CONTRACT.md`** / **`UI_CLIENT_PROTOCOL.md`**).
3. **Capability gating:** Runtime Stop visibility is driven by **`GetConnectionInfo`** → **`parler.connection-info.v1`** optional **`capabilities.supportsCancellation`** (boolean; absence = false). The current agent advertises **`true`**; an agent and widget pair that predates Stop must be upgraded together.
4. **Numeric defaults:** Registry terminal tombstone TTL **5 minutes**; UI bounded wait for terminal after accepted **`CancelUserPrompt`**: **15 seconds**; pre-**`session.ack`** **`not_active`**: **retry `CancelUserPrompt` exactly once** after **300 ms**, then remain in **`stopping`** until a terminal or the **15 s** fallback.
5. **Locking:** the running-turn cancel path **never** acquires the long-held per-conversation execution lock. **`PendingApprovalStore.compareAndRemove(pendingId, expectedRecord)`** is the only way **`CancelUserPrompt`** and **`SubmitApprovalDecision`** consume pending approvals. User cancellation never uses `Thread.interrupt()` (ThingWorx may interrupt threads for its own reasons).
6. **`rate_control.status`:** After **`waiting`**, allowed completions include **`resumed`**, terminal **`session.cancelled`**, **`session.done`**, **`session.error`**, or **`session.superseded`** (same **`request_id`** when the turn ends).
7. **Synthetic `TURN_CANCELLED`:** Tool rows keep **`status:"error"`** for replay pairing; **`code:"TURN_CANCELLED"`** **must not** drive the **global** chat error banner; **`UI_CLIENT_PROTOCOL.md`** requires **neutral “stopped”** row treatment.
8. **Parked live frames:** **`CancelUserPrompt`** **must** enqueue **`approval.resolved`** + **`session.cancelled`** through the **same** **`ParlerReceiveMessageSupport.send(RemoteThing, …)`** path (bound gateway **RemoteThing**) the active turn uses — **not** persist-only without live downlink.
9. **Multi-connection / legacy receivers:** Clients that do **not** implement **`session.cancelled`** can remain **`busy`** after **`approval.resolved`** if they ignore unknown wire types. **Mitigation:** capability-gated Stop on senders; upgrade **all** mashup instances bound to a shared live conversation together (no mixed-version attach for gateway cancel).

## 1. Goal

Parler supports the familiar chat control: while a turn is running, the primary send button becomes a stop button.
Clicking it ends the active turn without refreshing the mashup.

This is not just a UI affordance. The design defines:

- what "stop" means for a Parler turn;
- how the agent stops scheduling LLM rounds, tools, playbook nodes, charts, and final synthesis;
- what happens to an in-flight provider call and to rate-gate accounting;
- how persisted conversation history remains replay-safe;
- how late frames are prevented from mutating a stopped assistant row.

The semantics are the same for Azure Foundry deployments of `gpt-5.4`, `claude-sonnet-4-6`, and `claude-opus-4-8` under
Parler's synchronous provider clients.

## 2. Runtime Baseline

Parler streams from the agent to the UI. The AlwaysOn wire path emits `activity`, `content.delta`,
`chart`, `table`, `tabular.tool_success`, `task.state`, `rate_control.status`, `done`, and `error`.

The provider side is different. LLM provider clients are synchronous, non-streaming HTTP calls:

- `AzureOpenAILlmClient` posts to `/openai/deployments/{deployment}/chat/completions`.
- `AnthropicMessagesLlmClient` posts to `/anthropic/v1/messages`.
- `LlmClient.chat(LlmChatRequest)` has no cancellation parameter.
- `AgentLoop.run()` executes on the calling ThingWorx service thread and blocks through provider calls and tool calls.
- `LLMAPIProviderRateGate` uses local RPM / TPM / concurrency accounting and monitor `wait(slice)` for local waits.
- `PlaybookActiveRunTracker.cancel` only clears the active-run reservation; it does not interrupt the running playbook
  thread.
- HITL approval is not a blocked thread. When a gated tool needs approval, `AgentLoop` returns
  `AgentResult.awaitingApproval(...)`, the service call unwinds, and the pending record is stored in
  `PendingApprovalStore`.

Cancellation is therefore cooperative Parler turn control observed at `AgentLoop` checkpoints. It does not require
provider-token streaming, does not abort in-flight provider HTTP calls, and does not promise provider-side compute or
billing rollback.

## 3. Provider Semantics

The relevant provider behavior is the API shape used by Parler, not only the model name.

### 3.1 Azure OpenAI / GPT-5.4

Azure OpenAI documents a cancel endpoint for background Responses API work, and documents terminating the connection for
synchronous responses. Parler currently uses synchronous Chat Completions, not background Responses API.

Contract for `gpt-5.4`:

- If the next LLM round has not started, cancellation prevents it from being sent.
- A request already waiting in Parler's local rate gate or already in the synchronous HTTP POST runs to completion (or
  its socket timeout); the turn stops at the next checkpoint and the response is discarded (§10.1).
- Parler does not claim that Azure stopped model-side compute or avoided billing.

Sources:

- Azure OpenAI Responses API: <https://learn.microsoft.com/en-us/azure/foundry/openai/how-to/responses>
- Azure AI Model Inference REST API: <https://learn.microsoft.com/en-us/rest/api/aifoundry/modelinference/>

### 3.2 Claude Sonnet 4.6 / Opus 4.8 on Azure Foundry

Microsoft documents Claude in Foundry as using the Claude Messages API at Azure-hosted endpoints such as
`https://<resource-name>.services.ai.azure.com/anthropic/v1/messages`. The examples are request/response calls with
`stream:false`. Anthropic's streaming documentation describes SSE stream events and `message_stop`, but does not define
a separate synchronous "cancel this message id" control endpoint for the Messages API path Parler currently uses.

Contract for Sonnet / Opus: the same as for `gpt-5.4` — no new round is sent after cancellation, an in-flight request
runs to completion and its response is discarded, and Parler does not claim provider-side compute or billing rollback.

Sources:

- Microsoft Foundry Claude guide: <https://learn.microsoft.com/en-us/azure/foundry/foundry-models/how-to/use-foundry-models-claude>
- Anthropic Claude in Microsoft Foundry: <https://platform.claude.com/docs/en/build-with-claude/claude-in-microsoft-foundry>
- Anthropic streaming messages: <https://platform.claude.com/docs/en/build-with-claude/streaming>

## 4. Product Semantics

Cancellation is a user request to stop the active Parler turn for the current conversation and request id.

It is not:

- a request to delete the already submitted user prompt;
- a guarantee that the upstream provider stopped compute or billing;
- a Java thread kill;
- a rollback of completed ThingWorx side effects;
- the same control as an **in-card** HITL approval **`cancel`** decision (gateway **`CancelUserPrompt`** is a **turn-level** stop — see §10.3 and **`API_CONTRACT.md`** **`hitl_resolution_source`**).

It is:

- a deterministic UI transition out of active generation;
- a deterministic agent decision to stop scheduling more LLM calls, tools, and final synthesis for the cancelled
  request;
- replay-safe transcript pairing (synthetic `TURN_CANCELLED` tool rows, no final assistant row);
- a late-frame guard for the cancelled `requestId`.

Normal user cancellation must be logged as an expected control path, not an ERROR-level failure path.

## 5. UI Behavior

The input control exposes one primary button:

- idle: send icon, tooltip `Send`;
- active: red stop-square icon, tooltip `Stop generating`;
- stopping: disabled stop-square or spinner-stop while the cancellation request is in flight.

State model:

| UI state | Button | Input | Meaning |
| --- | --- | --- | --- |
| `idle` | send | enabled | No active request. |
| `submitting` | stop | disabled or read-only | `SubmitUserPrompt` invoke is in flight. |
| `running` | stop | disabled or read-only | Server accepted the turn and may stream frames. |
| `stopping` | disabled stop | disabled or read-only | UI sent `CancelUserPrompt` and is waiting for terminal acknowledgement. |
| `idle` after stop | send | enabled | Terminal **`session.cancelled`**, **`session.done`**, **`session.error`**, **`session.superseded`**, or bounded local fallback ended the visible turn. |

The UI does not allow a second prompt in the same conversation until the old request is terminal. This avoids
out-of-order transcript mutations.

Cancellation before `session.ack` is allowed from the UI if the outbound `requestId` already exists. If the server has
not registered that request yet, `CancelUserPrompt` may return `not_active`; the UI **must** remain in **`stopping`**,
**retry `CancelUserPrompt` exactly once** after **300 ms**, then keep **`stopping`** until a terminal frame or the
**bounded fallback** timer (**15 s** after first successful cancel accept — see §12) fires. It must not bounce visibly
between `running` and `idle`.

If `CancelUserPrompt` returns `unsupported` (an agent build that does not implement it), the UI ends the visible turn
locally (`session.cancel_unsupported_local`), returns to `idle`, and shows a short non-fatal message such as "Stop is not
supported by this agent version." It does not leave the input disabled.

While a HITL approval card is open, the approval card remains the primary user control for the approval. The global stop
button may still issue `CancelUserPrompt`; server semantics are defined in §10. The UI must not locally reinterpret the
global stop button as an approval-card decision.

## 6. Wire And Service Contract

Surfaces: `CONTRACTS/API_CONTRACT.md` (**`GetConnectionInfo`** / **`parler.connection-info.v1`**, **`session.cancelled`**,
**`approval.resolved`** **`hitl_resolution_source`**, **`rate_control.status`** terminal completions),
`CONTRACTS/UI_CLIENT_PROTOCOL.md`, `docs/architecture/agent-alwayson.md`, and in `parler-ui`: `lib/types.js`,
`lib/wireAdapter.js`, `lib/chatSession.js`, `lib/alwaysOnInvokeParams.js`, `lib/parlerInfotableJson.js`, and the
connection-info codec / handshake (`getConnectionInfoInfoTable`, `connectionInfoHandshake`).

### 6.0 Connection info capability (`GetConnectionInfo`)

Normative surface: **`ParlerGateway.GetConnectionInfo`**, response **`schemaVersion`** **`parler.connection-info.v1`** (strict equality), **`stringFromInvokeResult`**, per **`API_CONTRACT.md`**. **Do not** invent **`GetParlerConnectionInfo`** or alternate schema names — those are incorrect.

The JSON body carries an optional **`capabilities`** object:

- **`capabilities.supportsCancellation`** (boolean): when **`true`**, the agent implements **`CancelUserPrompt`** and terminal **`session.cancelled`**. When absent or **`false`**, the widget **must not** show the Stop control.

The widget **may** still read local **`widgets.json`** **`version`** for logging, but **must not** rely on semver alone to infer cancellation support.

### 6.1 Uplink

A gateway service is invoked through the same AlwaysOn path as `SubmitUserPrompt`:

`ParlerGateway.CancelUserPrompt`

Parameters (the conversation id is the bound Gateway Thing name):

```json
{
  "requestId": "<active request id>",
  "agentThingName": "<same agent binding used for SubmitUserPrompt>",
  "reason": "user_stop"
}
```

Rules:

- `requestId` is mandatory. Cancelling "whatever is active" without a request id is rejected.
- The gateway verifies the same caller / binding context used for `SubmitUserPrompt`; guessing a valid `requestId` is not
  enough.
- The gateway verifies that the request id belongs to the bound conversation and target AgentThing.
- The service returns quickly. It does not wait for the provider call, tool return, playbook return, or history persistence.
- The cancellation path must not acquire the long-held per-conversation execution lock used by the active
  `SubmitUserPrompt` turn. If it queues behind the active turn, the stop button is cosmetic.

Synchronous invoke result:

```json
{
  "schemaVersion": 1,
  "status": "accepted",
  "alreadyRequested": false,
  "conversationId": "<conversation id>",
  "requestId": "<request id>"
}
```

`alreadyRequested` is only meaningful for idempotent repeated stop clicks: the first acceptance returns `false`; a later
cancellation request for the same still-running turn returns `true`.

Other statuses:

- `not_active`: no matching parked or running request for `(conversationId, requestId, principal, agentThingName)`.
- `already_terminal`: the request already ended with a gateway user-stop terminal within the tombstone window, or a
  concurrent consumer won the pending-approval `compareAndRemove`.
- `wrong_agent`: the parked pending approval belongs to a different AgentThing.

Clients also handle `unsupported`, which an agent that does not implement the service may return (§5).

Repeated cancellation is idempotent:

- If cancellation is already requested for a running request, return `accepted` with `alreadyRequested:true`.
- If the request is already terminal, return `already_terminal`.
- Never write duplicate synthetic tool rows or emit duplicate terminal **`session.cancelled`** frames for the same request id.

### 6.2 Downlink

Dedicated terminal wire frame aligned with the **`session.*`** family (same AlwaysOn profile — **`conversation_id`** required):

```json
{
  "type": "session.cancelled",
  "conversation_id": "<conversation id>",
  "request_id": "<request id>",
  "reason": "user_stop",
  "message": "Turn stopped."
}
```

The UI adapter maps wire **`session.cancelled`** to **`UiEvent`** **`session.cancelled`** (same naming as **`session.done`** / **`session.error`**).

Terminal-emission ownership:

- For `running` turns, `CancelUserPrompt` only marks cancellation and returns. The active turn thread emits terminal
  **`session.cancelled`** when it reaches the next cancellation checkpoint.
- For `approval_parked` turns, there is no active turn thread. `CancelUserPrompt` performs the pending-store cleanup,
  synthetic tool-result persistence, and **live** terminal frame emission via **`ParlerReceiveMessageSupport.send`** on the bound gateway **RemoteThing** (same path as the active turn — see §7.1).
- Exactly one path sends terminal **`session.cancelled`** for a request: the running path returns `AgentResult.CANCELLED`
  once, and the parked path is gated by the pending-approval `compareAndRemove`.

Reducer semantics:

- If `activeRequestId !== requestId`, ignore the terminal frame.
- Clear `busy`, `activeRequestId`, `approvalGate`, assistant `activity`, and `rateControlWaiting`.
- Mark the matching assistant row as cancelled without setting the global error banner.
- Preserve partial markdown, charts, tables, and artifacts already rendered before cancellation.
- Add a cancelled tombstone keyed by `requestId`.
- Ignore all later `activity`, `content.delta`, `chart`, `table`, `tabular.tool_success`, `task.state`, and
  `rate_control.status` frames for tombstoned request ids.

**`session.cancelled`** is preferred over **`error`** with `code:"TURN_CANCELLED"` because user stop is not a product
failure. **`done`** with `status:"cancelled"` would be less clear because **`session.done`** / wire **`done`** means successful turn completion.

Forward compatibility:

- A new widget must understand **`session.cancelled`** (after **`capabilities.supportsCancellation`** is true).
- An old widget talking to a new agent **ignores** unknown **`session.cancelled`** safely but **must not** show Stop without capability — see §6.0 and **Design rules** item 9.
- Unknown frame types must never throw from the wire adapter; they should be ignored safely.

### 6.3 Multi-Connection And Reconnect

Multiple mashup instances may attach to the same conversation, and AlwaysOn reconnect can deliver stale frames. The
server-side cancellation registries are keyed by `(conversationId, requestId, principal, agentThingName)`. The UI-side tombstone is
keyed by `requestId` within the active conversation. Together they provide:

- authorization and routing protection on uplink;
- deterministic local suppression of stale downlink frames;
- no reliance on connection identity alone for correctness.

The gateway name and wire `conversation_id` are bound together by the AlwaysOn profile. `agentThingName` in the registry
key is primarily an authorization / binding check for `wrong_agent`; `(conversationId, requestId)` should already be
unique for one live conversation.

## 7. Runtime Registries And Locking

Two JVM-wide registries (static, so a gateway `CancelUserPrompt` call on a different service thread sees the active
turn) are keyed by `(conversationId, requestId, principal, agentThingName)`:

- **`ParlerRunningTurnCancelRegistry`** — one cancel flag per running AlwaysOn turn. `ParlerStreamToRemoteThing`
  registers the turn before `AgentLoop.run` and clears it on every terminal path; `CancelUserPrompt` sets the flag
  (`tryRequestCancel`: newly requested vs already requested); `AgentLoop` reads it at checkpoints (§10.1).
- **`ParlerGatewayUserStopTombstoneRegistry`** — lightweight terminal tombstones that expire after **5 minutes** and hold
  no handles, thread references, request bodies, or payloads. A stop click that races just after a user-stop terminal
  returns `already_terminal` instead of `not_active`.

Parked HITL approvals live in `PendingApprovalStore` (§10.3).

`ParlerCancelUserPromptGatewayOrchestration` decides the outcome: it checks for a parked pending approval first (so the
running→parked transition window cannot be mis-handled), then the running-turn registry, then the pending store again,
then the tombstones.

Locking rules:

- Conversation execution stays serialized by the per-conversation lock (`ParlerConversationLocks`).
- The running-turn path of `CancelUserPrompt` uses only lock-free registry checks, so it answers promptly while the
  active turn holds the conversation lock across a provider call, rate-gate wait, or tool execution.
- The parked path takes the conversation lock briefly (no turn thread holds it while parked) and consumes the pending
  record with `compareAndRemove`, writing the tombstone immediately after it wins.
- The active turn observes cancellation only at checkpoints; the cancel service acknowledges the user without waiting
  for them.

### 7.1 Parked-path live downlink

`CancelUserPrompt` runs on a **gateway service thread**, not the parked **`AgentLoop`** thread. For **`approval_parked`**
cancellation it **must** still deliver **`approval.resolved`** + **`session.cancelled`** to all live mashup attaches on the
same AlwaysOn binding. **Normative:** resolve the same bound gateway **`RemoteThing`** the active turn uses for
**`ParlerReceiveMessageSupport.send`**, and enqueue frames through **`ParlerReceiveMessageSupport`** (never “persist stream
rows only” without a matching live **`ReceiveMessage`** for this path).

Terminal ownership rule:

- `running` state: the cancel service sets the flag and returns. It does not emit terminal **`session.cancelled`**.
- `approval_parked` state: the cancel service owns terminal emission because no turn thread remains.
- Duplicate Stop clicks, late checkpoint cancellation, and parked-turn races cannot double-emit (§6.2).

## 8. Provider Calls

There is no cancellation token at the provider boundary and no provider HTTP abort. `LlmClient.chat` has no cancellation
parameter; an in-flight synchronous HTTP call (Azure OpenAI Chat Completions or Anthropic Messages on Azure Foundry)
runs to completion or to its socket timeout. The turn then stops at the next `AgentLoop` checkpoint and the response is
not persisted as an assistant answer (§10.1).

## 9. Rate Gate Semantics

The local rate gate is not cancellation-aware. A request that is waiting for local RPM / TPM / concurrency capacity
keeps waiting, is admitted, and is sent; normal reservation and `inFlight` accounting apply. Cancellation stops the
turn before the *next* round's rate-gate admission. `rate_control.status waiting` is still followed by `resumed` or by
one of the terminals listed in **Design rules** item 6.

## 10. Agent Loop, Tools, HITL, And Playbook

### 10.1 Agent Loop Checkpoints

`AgentLoop` checks the running-turn cancel flag at these points:

1. Before each LLM round (before rate-gate admission and the provider call).
2. After an LLM response with tool calls, before any tool runs: the assistant `toolCalls` row is appended, then a
   synthetic `TURN_CANCELLED` result for every tool call.
3. After an LLM response without tool calls: the final answer is discarded (no assistant row is appended).
4. Before each tool call in a batch: synthetic results for this and every remaining tool call.
5. After each tool call returns: the executed tool keeps its **real** result row; only not-yet-run siblings get
   synthetic results (the transcript must not claim a side-effecting tool never ran).
6. When a tool call enters HITL while cancellation is requested: the pending approval is consumed under the same
   conversation lock as the gateway parked path.

At a checkpoint the loop returns `AgentResult.CANCELLED`. The AlwaysOn turn handler (`ParlerStreamToRemoteThing`) then
emits terminal **`session.cancelled`** (reason `user_stop`), records the user-stop tombstone, clears the playbook
active-run reservation and per-turn guard registries, stores the conversation, and ends task-state progress as not
successful. Turn performance telemetry records that cancellation was observed.

### 10.2 Tool Calls

- A tool call that has started is not interrupted (built-in or generic ThingWorx service invocation). If cancellation
  arrives while it runs, the turn stops after it returns and its real result is kept (§10.1 item 5).
- `set_property_value`, `acknowledge_alerts`, and `invoke_service` side effects that already completed are not rolled
  back.

If cancellation occurs after an assistant tool-call row was persisted but before all tool results are persisted,
synthetic tool result rows are appended for every missing tool call id, preserving id and order.

### 10.3 HITL

HITL approval is a parked turn, not a blocked thread.

When a gated tool requests approval:

- `AgentLoop` has already persisted the assistant tool-call row.
- It returns `AgentResult.awaitingApproval(...)`.
- The active service thread unwinds and the per-conversation execution lock is released.
- `PendingApprovalStore` owns the pending record and interrupted sibling tool calls.
- The running-turn registration ends; the turn is `approval_parked`.

Cancellation of a parked HITL request:

- `CancelUserPrompt` finds the pending approval for `(conversationId, requestId, principal)`.
- It consumes the `PendingApprovalStore` record **only** through the atomic
  **`compareAndRemove(pendingId, expectedRecord)`** shared with **`SubmitApprovalDecision`** — **no** **`get` then
  `remove`** race.
- It appends a synthetic tool result for the gated tool call with `TURN_CANCELLED`.
- It appends synthetic sibling tool results for interrupted sibling tool calls.
- It emits `approval.resolved` with outcome `cancelled`, **`hitl_resolution_source: "gateway_user_stop"`** (per
  **`API_CONTRACT.md`**), then emits terminal **`session.cancelled`** for the same `requestId`. **No** post-approval LLM
  stream.
- It does not attempt to abort a non-existent running thread.

Synthetic rows go through `HitlSyntheticToolResultAppender`; there is no parallel persistence path.

Approval decision concurrency:

- `CancelUserPrompt` and `SubmitApprovalDecision` may race for the same pending approval.
- The two paths use the **same** atomic **`compareAndRemove`** on **`pendingId`**.
- Exactly one path wins. If approval wins, the gated tool may execute and cancellation becomes `already_terminal` or a
  no-op. If cancellation wins, synthetic `TURN_CANCELLED` tool results are appended and later approval submission is
  rejected as already resolved.
- A gated tool must never both execute for approval and receive a synthetic `TURN_CANCELLED` result.

Wire ordering for visible approval cards (gateway stop, parked):

1. `approval.resolved` with `outcome:"cancelled"` and **`hitl_resolution_source:"gateway_user_stop"`**.
2. terminal **`session.cancelled`** for the same `requestId`.

The reducer should still be idempotent if frames are retried or duplicated, but the server must send this order.
**Normative:** in-card **`cancelled`** / **`approve`** / **`reject_with_comment`** paths keep the extension default
(post-**`approval.resolved`** **AgentLoop** continuation). Gateway stop is **not** the same product path — see **Design
rules** item 2.

### 10.4 Playbook

`start_playbook` runs as one tool call inside `AgentLoop`. The playbook runner does not check the cancel flag between
nodes or fan-out children; cancellation is observed when the `start_playbook` tool call returns (§10.1 item 5). On a
cancelled turn, `PlaybookActiveRunTracker.cancel` clears the active-run reservation for the conversation. Playbook
task-state progress has a `cancelled` status value.

## 11. Persistence And Rehydration

The user prompt row is kept. Do not delete or rewrite it.

### 11.1 Status Field

There is no dedicated terminal-status column: `AgentMessageData` has no `metadata`, `status`, or `code` field, and
cancellation does not overload `content` or `llmUsageJson`.

### 11.2 Assistant Rows

A cancelled turn appends no final assistant row. Partial content, charts, and tables already sent live stay on the
client's row; in stored history the turn consists of the user prompt plus any assistant tool-call rows and their real or
synthetic tool results.

### 11.3 Tool-Call Pairing

Provider replay requires every assistant tool call to have a matching tool result. Therefore:

- The assistant `toolCalls` row is durably appended before any tool execution begins. Cancellation correctness
  depends on this ordering: synthetic result rows need a durable assistant row to pair against.
- Synthetic tool result rows align with the already persisted assistant `toolCalls` row by tool call id and order.
- The synthetic result payload is stable:

```json
{
  "status": "error",
  "code": "TURN_CANCELLED",
  "message": "Turn was stopped by the user before this tool call ran."
}
```

**UI (`UI_CLIENT_PROTOCOL.md`):** rows carrying **`code:"TURN_CANCELLED"`** **must** render as a **neutral stopped** tool outcome
and **must not** promote to the **global** session error banner (user stop is not a product failure; the authoritative
user-facing stop signal is **`session.cancelled`** / assistant stopped state).

### 11.4 Rehydration

`AgentConversationRehydrator` skips assistant rows with `toolCalls` and skips empty assistant content. For cancelled
turns:

- no cancelled prose is replayed into the next LLM request (no final assistant row exists);
- synthetic tool rows preserve transcript pairing where they are needed for durable replay;
- a provider response that arrives after cancellation is discarded, never appended to `AgentMessageStream`;
- history hydrate has no persisted stopped marker; the live UI tombstone is not persisted.

## 12. UI Implementation Notes

**`parler-ui`:** `buildCancelUserPromptParams` / `cancelUserPromptInfoTable`, composer **Send**/**Stop**/**stopping**, **`not_active`** single retry (**300 ms**) then bounded **15 s** fallback (including second **`not_active`**), transient Stop failures clear **stopping** without local terminal, **`unsupported`** → **`session.cancel_unsupported_local`** (no cancel tombstone) with **`unsupportedLocalRequestIds`** gating **one** late real **`session.done`** for row metadata (scoped so **`session.error`** / **`session.superseded`** idle rows do not accept stale **`session.done`**), **`parseCancelUserPromptResult`** / **`planCancelUserPromptDispatch`** — all gated on **`GetConnectionInfo.capabilities.supportsCancellation === true`** (see **`CONTRACTS/UI_CLIENT_PROTOCOL.md`** §1.1, **`API_CONTRACT.md`** **2.4.38**).

- `session.cancelled` is handled in `types.js`, `wireAdapter.js`, `chatSession.js`, and reducer tests.
- Request-id tombstones (`cancelledRequestIds`, FIFO-capped at 32) stop late non-terminal assistant frames, late
  matching terminals (**`done`** / **`error`** / **`superseded`**), and late **`approval.required`** (same
  **`request_id`**) from mutating stopped rows or reopening HITL after user stop.
- The input button switches between send and stop states.
- A bounded local fallback (**15 seconds** after the first successful **`CancelUserPrompt`** accept) ends the visible turn
  if terminal **`session.cancelled`** is not received.

The reducer ignores stale `done` / `error` for non-active request ids, but non-terminal frames find old assistant rows by
`requestId`, so the tombstone is required.

The bounded local fallback exists because running cancellation is cooperative. A running turn may be inside a provider
call or a non-interruptible ThingWorx service call and cannot emit terminal **`session.cancelled`** until the next
checkpoint. The fallback is UI recovery, not proof that server-side cleanup has already completed.

After fallback, the UI may allow another prompt. `ParlerStreamToRemoteThing` can return a new `requestId` quickly while
the new background turn thread still waits for the per-conversation lock held by the old draining turn, so the new row
may show no `session.ack` or stream frames for a while. There is no dedicated "finishing previous turn" indicator.

## 13. Agent Implementation

- `ParlerGateway.CancelUserPrompt` → `AgentThing.cancelUserPromptFromParlerGateway` → thread ownership / agent
  resolution → `ParlerCancelUserPromptGatewayOrchestration.orchestrateAfterResolve`.
- `ParlerRunningTurnCancelRegistry` and `ParlerGatewayUserStopTombstoneRegistry` (§7).
- `AgentLoop` checkpoints and `AgentResult.userCancelled` (§10.1); synthetic rows via
  `HitlSyntheticToolResultAppender`.
- Parked HITL cancellation through `PendingApprovalStore.compareAndRemove` (§10.3).
- `ParlerReceiveMessageSupport.wireSessionCancelled` builds the terminal frame.
- `ParlerGateway.GetConnectionInfo` advertises `capabilities.supportsCancellation`.

## 14. Observability

Expected user cancellation is INFO-level or DEBUG-level, not ERROR-level.

The running-turn path logs `ParlerStreamToRemoteThing user cancel remoteThing=... requestId=...` at INFO; the turn
performance line records that cancellation was observed.

Do not expose raw provider request bodies or secrets.

`conversationId` and `requestId` follow the existing Application Log and live collection policy. They are operational
correlation identifiers for internal training diagnostics; do not add special redaction beyond the current log policy.

The live collection tool needs no special mode; it captures these logs and stream rows like other diagnostics.

## 15. Tests

Java tests:

- Cancel before the next LLM round does not call the provider.
- Cancel after a provider response but before persistence does not append the provider response.
- Assistant `toolCalls` row is durably appended before any tool execution starts.
- Cancel after assistant tool-call row appends synthetic tool results for every missing tool call id in order.
- Cancel an approval-parked HITL request invalidates pending approval, appends gated and sibling synthetic tool rows, and
  emits terminal **`session.cancelled`**.
- Racing `CancelUserPrompt` and `SubmitApprovalDecision` for the same pending id is atomic: exactly one wins, and the
  gated tool is never both executed and synthetically cancelled.
- Running cancellation terminal frame is emitted by the active turn thread; parked cancellation terminal frame is emitted
  by `CancelUserPrompt`; duplicate terminal emission is impossible.
- Repeated `CancelUserPrompt` calls are idempotent and do not duplicate synthetic tool rows.
- User-stop tombstones make a repeated or racing stop return `already_terminal`; tombstones expire without retaining
  heavy state.
- Registry cleanup covers success, error, timeout, awaiting approval, and cancelled paths.

UI tests:

- Send button switches to stop while busy.
- Stop invokes `CancelUserPrompt` with active `conversationId`, `requestId`, and `agentThingName`.
- **`session.cancelled`** wire clears busy without setting an error banner.
- Partial content / charts remain visible with a stopped marker.
- Late non-terminal frames for a tombstoned request are ignored.
- Cancellation before ack handles `not_active` without visible state flicker.
- `unsupported` returns the UI to `idle` with a non-fatal message.
- Old/unsupported widget versions do not show the stop control and ignore unknown frames safely.

Manual checks on a ThingWorx server (the turn stops at the next checkpoint in each case):

1. Long GPT-5.4 prompt; stop during provider call.
2. Long Sonnet 4.6 prompt; stop during provider call.
3. Long Opus 4.8 prompt; stop during provider call.
4. Prompt waiting in local provider rate gate; stop during wait.
5. Multi-tool prompt; stop after first tool.
6. Playbook prompt; stop while the playbook runs.
7. HITL prompt; stop while approval card is open.
8. Reconnect / reload after a stopped turn; verify history and late-frame behavior.
