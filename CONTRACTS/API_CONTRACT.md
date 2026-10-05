# API contract — Parler wire JSON

**Contract bundle:** [`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md) (**current `CONTRACT_VERSION.md` header**).  
**Wire document revision:** 2.4.40 — **Artifact Cache readiness terminal:** after synchronous address/ownership/connection validation, AlwaysOn sends `session.ack`, evaluates the mandatory Agent cache repository, and on rejection sends one same-request terminal `error` carrying `ARTIFACT_CACHE_NOT_CONFIGURED` or `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE`; no LLM/Playbook frame and no following `done`. A repository-wide runtime failure and post-HITL continuation use the same error-only terminal behavior. Prior **2.4.39** — **`query_entities_by_taxonomy` completeness (U1B E1):** non-intersect success **MAY** emit **`truncated`** / **`totalUnderlyingCount`** / **`hasMore`** per the honesty tables below; malformed/missing QIT listing → **`TAXONOMY_LISTING_MALFORMED`**; intersect **`hasMore`** **MUST** OR completeness-unknown. Field names reuse the taxonomy-resolver pair but **listed-page match meaning** here is distinct from [`TAXONOMY_RESOLVER.md`](./TAXONOMY_RESOLVER.md) §4 (implementor-*scope* truncation for identifier resolution). Prior **2.4.38** — **`ParlerGateway.GetConnectionInfo`:** the coordinated **`parler-agent` `0.1.187`** + **`parler-ui-widget` `0.1.80`** import pair **MUST** emit **`capabilities.supportsCancellation: true`** (strict JSON boolean) when **`capabilities`** is present — end-to-end **Stop** + **`CancelUserPrompt`** + **`parler-ui`** reducer/tests (**`CONTRACT_VERSION.md` `0.1.130`**). Implementations **without** that path **MUST NOT** emit **`true`**. Prior **2.4.37** — **`UI_CLIENT_PROTOCOL.md`:** **`session.error`** / **`session.superseded`** **MUST** remove the matching **`requestId`** from **`unsupportedLocalRequestIds`** when applied (terminal symmetry, so a later stale **`session.done`** cannot merge after a real error/supersede). Prior **2.4.36** — **`parler-ui`** / **`UI_CLIENT_PROTOCOL.md`:** late **`session.done`** row-metadata merge after **`session.cancel_unsupported_local`** **MUST** be gated on **`ChatUiState.unsupportedLocalRequestIds`** (bounded set) so idle rows after **`session.error`** or **`session.superseded`** cannot accept a stale **`session.done`**. **`GetConnectionInfo`** may still emit **`supportsCancellation: false`** until agreed E2E verification — gate unchanged (**2.4.28**). Prior **2.4.35** — **`parler-ui`** Stop / **`CancelUserPrompt`** client state machine: after the single **`not_active`** retry, another **`not_active`** **MUST** arm the same **15 s** bounded fallback as **`accepted`**; invoke failure, unparseable JSON, and unexpected statuses **MUST** clear **stopping** without forcing a local terminal (user may retry Stop); **`unsupported`** **MUST** map to client-only **`session.cancel_unsupported_local`** (**no** **`cancelledRequestIds`** tombstone; a late real **`session.done`** **MAY** still merge per **`UI_CLIENT_PROTOCOL.md`**). Stop visibility remains gated on **`GetConnectionInfo.capabilities.supportsCancellation === true`**. **`GetConnectionInfo`** may still emit **`supportsCancellation: false`** until agreed end-to-end verification — the agent advertisement gate is unchanged (**2.4.28**). Prior **2.4.34** — **`parler-ui`** ships **`ParlerGateway.CancelUserPrompt`** uplink (**`buildCancelUserPromptParams`** / **`cancelUserPromptInfoTable`**) and composer **Stop** / **`stopping`** / bounded-terminal UX per **`docs/agent/turn-cancellation-control.md`**; Stop visibility remains gated on **`GetConnectionInfo.capabilities.supportsCancellation === true`** (strict JSON boolean). **`GetConnectionInfo`** may still emit **`supportsCancellation: false`** until agreed end-to-end verification — the agent advertisement gate is unchanged (**2.4.28**). Prior **2.4.33** — **Post-tool running cancel (transcript accuracy):** On the cooperative cancel checkpoint **after** a tool returns, that tool **MUST** persist its **real** **`role: tool`** result (egress-compacted as on the non-cancel path); synthetic **`TURN_CANCELLED`** rows apply only to **`tool_call` ids** in the same assistant batch that **never began execution** — the prior text-only synthetic for an executed tool misrepresented side effects (**`docs/agent/turn-cancellation-control.md`** §10.2). Prior **2.4.32** — **`AgentLoop`** running-turn cancel **safe checkpoints (v1.1):** After each **`llmClient.chat`** return, **`parler-agent`** **MUST** observe the cooperative cancel flag **before** treating a no-tool response as **`session.done`** success (including chart-rescue scheduling), **before** starting each tool execution for a tool-call batch, and **after** each tool returns **before** appending tool results or continuing the batch; when cancel is set with an assistant message that still has unmatched **`tool_call` ids**, synthetic **`role: tool`** rows with **`code: TURN_CANCELLED`** **MUST** be appended for every unmatched id (replay pairing — same shell as parked gateway user-stop) before the turn ends with **`session.cancelled`**. Cancel during an in-flight synchronous **`llmClient.chat`** **MAY** still be observed only after that call returns (**`docs/agent/turn-cancellation-control.md`**). Prior **2.4.31** — **`ParlerGateway.CancelUserPrompt`** **running-turn cooperative stop (v1):** While **`ParlerStreamToRemoteThing`** holds an active registered LLM turn for **`(conversation_id, request_id, principal, agentThingName)`**, **`CancelUserPrompt`** **MUST** return **`accepted`** without taking the per-conversation execution lock — first stop with **`alreadyRequested: false`**, idempotent repeat with **`alreadyRequested: true`** — by recording an in-memory cancel flag consumed by **`AgentLoop`** (initial between-iteration observation; superseded by **2.4.32** checkpoints for final-terminal honesty). The worker **MUST** emit terminal **`session.cancelled`** (**no** **`approval.resolved`** on this path), note the gateway user-stop tombstone for the tuple (same **5-minute** registry as parked terminals), and clear the running registration when the turn worker ends. Prior **2.4.30** — **`ParlerGateway.CancelUserPrompt`** parked path: server **MUST** record the gateway user-stop tombstone **immediately after** **`PendingApprovalStore.compareAndRemove`** wins, **before** transcript mutation or live **`ReceiveMessage`** frames for that stop, so a concurrent **`CancelUserPrompt`** lock-free pre-check observes **`already_terminal`** (not **`not_active`**); tombstone map **MUST** evict expired keys on read/write (no unbounded growth from idle stops — **`docs/agent/turn-cancellation-control.md`** registry tombstone TTL). Prior **2.4.29** — **`already_terminal`** for a **repeat** invoke after a successful gateway user-stop terminal on the same **`(conversation_id, request_id, principal, agentThingName)`** within the **5-minute** in-memory tombstone window (**`alreadyRequested: true`**); **`not_active`** remains when no pending exists and no tombstone matches (**`docs/agent/turn-cancellation-control.md`** §7). Prior **2.4.28** — **capability advertisement gating:** **`GetConnectionInfo.capabilities.supportsCancellation`** stays **`false`** until the **end-to-end client Stop path** ships (widget **`CancelUserPrompt`** invoke + Stop control, running-turn cancellation, and tests). The parked-HITL **`ParlerGateway.CancelUserPrompt`** service from **2.4.27** is **implemented but not yet advertised** as a general client Stop capability; **`supportsCancellation`** is the gate clients use to show Stop, so it MUST NOT be **`true`** while ordinary running turns return **`not_active`** and no widget Stop invoke exists. Prior **2.4.27** — normative **`ParlerGateway.CancelUserPrompt`** (parked HITL user stop — parameters, **`result`** JSON, CAS consume, synthetic **`TURN_CANCELLED`**, **`approval.resolved`** + **`hitl_resolution_source`**, terminal **`session.cancelled`**). Prior **2.4.26** — **`hitl_resolution_source`** wire closure (v1 servers **MUST NOT** emit the literal **`approval_card`**; only **`gateway_user_stop`** may appear when the field is present — **`UI_CLIENT_PROTOCOL.md`** §2 union). Prior **2.4.25** — terminal **`type: "session.cancelled"`** (user stop — fourth **`busy`‑clearing** terminal alongside **`session.done`**, **`session.error`**, **`session.superseded`**), **`GetConnectionInfo`** optional **`capabilities.supportsCancellation`**, **`approval.resolved`** optional **`hitl_resolution_source`** (`gateway_user_stop` parked gateway cancel vs default in-card HITL), and **`rate_control.status`** completion set ( **`waiting`** may end in **`resumed`**, **`session.cancelled`**, **`session.done`**, **`session.error`**, or **`session.superseded`** ). Prior **2.4.24** — normative **`ParlerGateway.GetConnectionInfo`** (Phase F connection version handshake): **`result`** **STRING** JSON **`parler.connection-info.v1`**, **`schemaVersion`** strict equality, **`stringFromInvokeResult`** + **`buildGetConnectionInfoParams`**, bound-**`agentThingName`** row match, log-only sanitized **`widgetPackageVersion`**. Prior **2.4.23** — optional additive **`reasoningTokensTotal`** on terminal **`done.llm_usage`** / history **`llmUsage`** (turn-level sum of provider **`usage.completion_tokens_details.reasoning_tokens`** when exposed; **additive** to **`completionTokensTotal`**, which remains the provider-reported total output count — see **`docs/agent/llm-usage-stream-telemetry.md`** § Reasoning Tokens). Prior **2.4.22** — optional **`presentationPhaseEntered`**, **`presentationActionsRequested`**, **`presentationActionsExecuted`**, **`presentationActionsBlocked`** on terminal **`done.llm_usage`** / history **`llmUsage`** (Answer Presentation Phase — **`docs/agent/multi-chart-and-thrashing-safeguards.md`** §4.4.1). Prior **2.4.21** — optional **`parlerChartWireEmittedCount`** on terminal **`done.llm_usage`** / history **`llmUsage`** (Parler stream **`type: "chart"`** downlink count — **`docs/agent/multi-chart-and-thrashing-safeguards.md`** §4 Slice B); **`CHART_CONTRACT.md`** §**2.4–2.5** (multi-chart frames + optional **`cacheId`** on tabular tool envelopes). Prior **2.4.20** — documents **`build_chart_from_tabular_result`** v1 **`CHART_EMITTED`** tool success JSON provenance mirrors (**`source`**, **`truncationApplied`** / **`truncated`**) aligned with **`CHART_CONTRACT.md`** §3.0d; wire frames unchanged from **2.4.19**. Prior **2.4.19** — optional live-only **`type: "rate_control.status"`** (provider-local rate-gate **waiting** / **resumed** UI tone) on the same AlwaysOn profile as **2.4.18**; **2.4.18** (**`type: "task.state"`** v1b / **v1b.2** / **playbook**) unchanged in substance — same JSON object shape as **2.4.17**; **v1b.2** allows the **`items`** array to **grow mid-turn** after **`get_agent_skill`** dynamic checklist merge. **Playbook (V1a Slice D):** during an active **`start_playbook`** / structured slash playbook turn, servers **MAY** emit full-replacement snapshots whose **`items[].source`** is **`playbook`** and whose turn-level **`summary`** **MAY** include **`playbookId`** (string) plus node counts — see **[`docs/agent/playbook-engine.md`](../docs/agent/playbook-engine.md)** §7 and **[`docs/agent/task-state.md`](../docs/agent/task-state.md)** § *Playbook progress*. Skill checklist **`task.state`** **MUST NOT** compete on the same turn while playbook progress is active. Prior **2.4.15** (**`query_entities*`** hierarchy intersect precedence + Mashup **`note`**) unchanged otherwise; optional **`type: "table"`** + **`tabular.tool_success`**.

**Scope change (v2):** The **Python FastAPI** REST and WebSocket server that previously lived under `backend/` has been **removed** from this repository. **Normative wire shapes** below still describe the **streaming chat JSON** consumed by **`ai-parler`** and by ThingWorx **AlwaysOn `ReceiveMessage`** (see **`agent-alwayson.md`**). **REST `/api/v1/*` and `/ws/v1/chat` endpoints** are no longer implemented here.

**Related (must stay aligned):**

- [`CHART_CONTRACT.md`](./CHART_CONTRACT.md) — `type: "chart"` + `ChartBlock`.
- [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md) — `type: "table"` + `TableBlock`.
- [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) — reducer + `ChatUiState` on top of these wires.
- [`agent-alwayson.md`](../docs/architecture/agent-alwayson.md) — AlwaysOn profile (`conversation_id` on every downstream frame).
- [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) — write-path approval gate (`approval.*`, **`approval.decision`**).

All JSON is UTF-8. **Timestamps in examples are UTC** (ISO 8601 with `Z`).

---

## WebSocket-style streaming (conceptual)

Clients may receive these objects over a dedicated socket or **embedded in AlwaysOn** — same JSON shape.

### Client → server (historical / reference)

```json
{
  "type": "chat.request",
  "request_id": "uuid-or-opaque-string",
  "payload": {
    "messages": [
      { "role": "user", "content": "Plot temperature for line-1" }
    ],
    "model": null,
    "conversation_id": "optional-thread-id-for-routing-or-single-live",
    "user_timezone": "America/New_York"
  }
}
```

#### `payload.user_timezone` (IANA, optional)

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `user_timezone` | string | No | **IANA Time Zone Database** id (e.g. `America/New_York`, `Asia/Shanghai`). Reference **`ai-parler`:** SHOULD send on every logical `chat.request` when `Intl` (or host) exposes a zone. |

**Semantics**

- Used **only** to interpret **ambiguous** user time phrases (`today`, `8am`, etc.) before converting to **UTC** for ThingWorx / history / stream queries. Wire timestamps and chart canonical time values remain **UTC** (see [`CHART_CONTRACT.md`](./CHART_CONTRACT.md)); execution-layer UTC is normative in [`times-solution.md`](../docs/architecture/times-solution.md).
- **Precedence:** explicit timezone in the user message (UTC, named zone, offset / `Z` in ISO) **overrides** `user_timezone`.
- **If `user_timezone` is absent** and the user relies on ambiguous relative time without explicit zone: the Agent **MUST NOT** fall back to the **server host** timezone silently. Implementations **SHOULD** ask for clarification or return a structured error (exact `code` / message may be extension-specific until standardized).

**ThingWorx AlwaysOn (Phase F):** the same logical field is passed as optional Service parameter **`userTimezone`** (camelCase) on **`ParlerGateway.SubmitUserPrompt`** and **`AIAgent.ParlerStreamToRemoteThing`**, after required turn fields. Invalid IANA strings are ignored server-side (logged); omission is allowed.

#### `hostContext` (optional STRING, ThingWorx AlwaysOn)

**Services:** **`ParlerGateway.SubmitUserPrompt`**, **`AIAgent.ParlerStreamToRemoteThing`**. **Parameter name:** **`hostContext`** (camelCase). **Type:** **`STRING`**. **Required:** no — omit or empty string means **no host-scope sideband** for this turn.

| Rule | Normative text |
|------|----------------|
| **Payload** | **UTF-8 JSON text** with required **`key`** (template selector) and **`context`** (structured page state object), per **[`docs/architecture/host-context.md`](../docs/architecture/host-context.md)** (Mashup **`HostScopeJson`**). |
| **Template rendering** | Server loads **ConfigurationRepository** **`host-contexts/*.json`** templates by **`key`** only (no classpath built-in runtime fallbacks). Registered key → bounded formatter render into per-turn **ephemeral system** prompt fragment. Unregistered parseable key → **generic fenced-JSON fallback** (`outcome: UNREGISTERED_GENERIC_FALLBACK`, `genericFallback: true`) per **`host-context-generic-fallback.md`**. Fail-open on invalid/oversize/schema errors. |
| **Wire bytes** | The **`STRING` value** **MUST** carry the **exact UTF-8 byte sequence** the client read from the bound Mashup Property for this turn. Clients **MUST NOT** round-trip through **`JSON.parse` → `JSON.stringify`** unless the output is **byte-for-byte identical** to that source. |
| **vs user message** | **`hostContext` MUST NOT replace** the user **`message`** / prompt text; explicit user text wins over rendered host blocks. |
| **Server inject** | **No** server-side auto-binding of host context into tool arguments (no v1 **`hierarchy_scope.id`** inject into **`query_entities*`**). Hierarchy scope is **advisory** via rendered template guidance + LLM tool args only. Generic fallback **MUST NOT** trigger registered-template side effects (`requiredTools`, document-scope injection). |
| **Limits & observability** | Whole-document UTF-8 ingress cap **`16384`**; generic-fallback render cap **`MAX_FALLBACK_RENDER_CHARS`** (4000); per-formatter caps per **host-context.md** §9; **`AgentThing.ValidateHostContext(hostScopeJson)`** returns **`templateFound`**, **`genericFallback`**, **`outcome`**, and rendered preview when applicable. On hard reject, **omit** host scope for the turn (fail-open) and **SHOULD** emit structured **`hostScope*=`** warn logs via **`ParlerHostScopeLogFormatter`**. Unregistered key with generic fallback **SHOULD** log a warning. |

**Implementation note:** **`parler-agent`** **`ParlerGateway.SubmitUserPrompt`** and **`AgentThing.ParlerStreamToRemoteThing`** accept optional **`hostContext`**. **`HostContextUplink`** evaluates **`key + context`**, renders via **`HostContextTemplateRegistry`**, inserts rendered prompt on accepted turns. **`parler-ui`** binds **`HostScopeJson`** → **`host-scope-json`** and copies the bound value into the **`hostContext`** InfoTable column on Send **without** trimming (see **`UI_CLIENT_PROTOCOL.md`** — Host context). Clients **MAY** omit; servers **MUST** tolerate absence.

#### History export — user row `hostContext` (`ai-parler-history-v1`)

**Source:** Stream field **`hostContextSnapshotJson`** on **`role=user`** rows (**`AgentMessageStreamAppender`**). **`GetConversationHistoryJson`** / **`AgentMessageStreamHistoryExporter`** map it to nested **`hostContext`** on each **`kind: "user"`** history row (same object shape; do not rename fields at the wire layer).

**Schema:** **`parler-host-context-snapshot-v1`** — see **[`docs/architecture/host-context-turn-state.md`](../docs/architecture/host-context-turn-state.md)** §3.1 / §4.1. Key fields: **`accepted`**, **`outcome`**, **`key`**, **`hash`**, **`utf8Bytes`**, **`changedFromPreviousUserTurn`**, **`rawJsonStored`**, optional **`rawJson`** (anchor rows only when **`changedFromPreviousUserTurn=true`** or first accepted snapshot in range), optional **`genericFallback`** / **`templateFound`** on unregistered-key fallback, optional **`rejectCode`** / **`rejectDetail`** on rejected uplink.

**UI / collection:** **`parler-ui`** hydrates optional **`hostContext`** on user rows; **`parler-collect-live`** surfaces **`hostContextSnapshotJson`** on normalized stream rows for support bundles. Product display rules: **`UI_CLIENT_PROTOCOL.md`**.

### Client → server — `approval.decision` (HITL gate)

Used after the server emits **`approval.required`**. **Transport (v1):** same uplink path as **`chat.request`** — **AlwaysOn** to the Agent / Parler gateway (**not** a separate REST endpoint).

**`request_id` is REQUIRED.** It **MUST** equal **`approval.required.request_id`** (the assistant turn that produced the pending tool call). Servers **MUST** reject the frame (stable error `code`, **no** tool execution) if `request_id` is missing or does not match the stored pending record — same hardness as [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §2.1 / §3.1.

```json
{
  "type": "approval.decision",
  "request_id": "same-as-approval.required.request_id",
  "conversation_id": "thread-or-conversation-key",
  "pending_id": "pending-uuid",
  "decision": "approve",
  "comment": ""
}
```

| Field | Rule |
|--------|------|
| `decision` | `approve` \| `cancel` \| `reject_with_comment` |
| `comment` | Use when `decision` is `reject_with_comment`; otherwise omit or empty string |

Normative narrative: [`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §3.

**ThingWorx AlwaysOn (Phase F) encoding:** The logical frame above is carried on the same WebSocket AlwaysOn uplink as user turns. The reference widget invokes **`SubmitApprovalDecision`** on the bound **`ParlerGateway`** Thing (whose name equals `conversation_id`), with service parameters **`pendingId`**, **`decision`**, **`requestId`**, **`conversationId`**, **`comment`** — one-to-one with the JSON fields (`pending_id`, `decision`, `request_id`, `conversation_id`, `comment`). This is the same invoke locus as **`SubmitUserPrompt`** (not a separate REST hop). **`AIAgent.SubmitParlerApprovalDecision`** remains an optional direct-invoke path for tooling.

**`ParlerGateway` conversation services (same invoke locus, Phase F):** **`GetConversationHistoryJson`**, **`CancelUserPrompt`** (user stop — parked HITL + cooperative running-turn v1; see **§ `ParlerGateway.CancelUserPrompt`** below), **`GetConnectionInfo`** (post-bind agent/widget display versions — see **§ GetConnectionInfo** below), **`SetConversationHistoryCutoff`** (`cutoffAtIso` **STRING**, ISO-8601 instant written to **`AgentThreadDataTable.historyClearedAt`**), **`RecordAssistantFeedback`** (`conversationId`, `assistantMessageId`, `rating`, optional **`requestId`**, optional **`previousRating`**) — on the **Gateway** path, the caller **MUST** own the **`AgentThreadDataTable`** row for that Gateway name (see Gateway implementation); **`SetConversationHistoryCutoff`** / **`RecordAssistantFeedback`** then delegate to the bound **`AIAgent`**. Direct invokes on **`AIAgent.SetConversationHistoryCutoff`** / **`AIAgent.RecordAssistantFeedback`** do **not** repeat row-ownership or thread-**`agentName`** checks: ThingWorx service visibility / permissions govern whether those calls are allowed; the services still require a valid thread row, pending-HITL rules, and (for feedback) a bounded **`assistantMessageId`** match on the conversation stream. **Alignment:** direct **`SetConversationHistoryCutoff`** persists the cutoff with **no** expected-thread-agent binding on the data-table write (no row **`agentName`** equality check against the invoking Thing). **`RecordAssistantFeedback`** persists **`ui_feedback`** with **`agentThing`** taken from the matched assistant stream row when non-blank (otherwise the invoking Agent Thing name). **`SetConversationHistoryCutoff`** may fail with **`CUTOFF_BLOCKED_HITL_PENDING`** when HITL approvals are still open. **`RecordAssistantFeedback`** may fail with **`FEEDBACK_PERSIST_FAILED`** when the **`ui_feedback`** stream append does not complete (UI should not treat the invoke as success).

#### `ParlerGateway.GetConnectionInfo` (Phase F — connection version handshake)

**Purpose:** After **`ConnectAndBind`**, the shipped **`parler-ui`** widget invokes this **synchronous** service on the bound **`ParlerGateway`** (Thing name = **`conversation_id`**) to obtain a **narrow** JSON document with the **Composer / import** agent extension version string (from build-generated **`artifactVersion`**, not broad operator snapshots). The widget displays **`agent:widget`** on the transport line; the **widget** side uses the **local** package version generated from **`parler-ui-widget/input/widgets.json`** — the optional **`widgetPackageVersion`** parameter is **log-only** (sanitized server-side for **`PARLER_CONNECTION_INFO`**); it is **not** echoed in the JSON response.

**ThingWorx shape:** **`@ThingworxServiceResult(name = "result", baseType = "STRING")`** — same **`result`** / **`STRING`** column contract as **`GetConversationHistoryJson`** and **`SubmitUserPrompt`**. Clients **MUST** extract the payload with **`stringFromInvokeResult(result)`** (INFOTABLE shim) before **`JSON.parse`**. Invoke parameters **MUST** be built as a single-row **InfoTable** JSON (codec shape) via **`buildGetConnectionInfoParams`** (**`parler-ui/lib/alwaysOnInvokeParams.js`**): columns **`agentThingName`** (**STRING**, required), **`widgetPackageVersion`** (**STRING**, optional / may be empty).

**Parameters:**

| Parameter | Required | Rule |
|-----------|----------|------|
| **`agentThingName`** | Yes | **MUST** equal the **`AgentThreadDataTable`** row’s **`agentName`** for this Gateway name (trimmed equality). The server resolves the **`AgentThing`** from that bound name only — caller-controlled names are **not** used as lookup keys when they differ from the row. |
| **`widgetPackageVersion`** | No | Sanitized (trim, collapse whitespace, strip ISO controls, length cap) for **`PARLER_CONNECTION_INFO`** logging only. |

**Response JSON** (UTF-8 text in **`result`**):

| Field | Rule |
|-------|------|
| **`schemaVersion`** | **MUST** be exactly **`parler.connection-info.v1`**. Clients **MUST** treat any other value as **failure** for connection-info UI state (log the observed string; do not parse unknown major versions as compatible). |
| **`conversationId`** | Gateway Thing name (echo). |
| **`agent`** | Object with **`thingName`**, **`extensionVersion`** (Composer / import display string), optional **`implementationVersion`** when it differs from **`extensionVersion`**. |
| **`serverTime`** | UTC ISO-8601 instant string. |
| **`capabilities`** | **Optional** object. When present, fields are **additive** booleans (absence of the object or of a key means **false** / unsupported). **`supportsCancellation`**: clients **SHOULD** use this (not semver alone) to decide whether to show Stop. **`parler-agent` `0.1.187`**+ coordinated with **`parler-ui-widget` `0.1.80`**+ **MUST** emit strict JSON **`true`** here when **`capabilities`** is present (**`ParlerGateway.GetConnectionInfo`** — **`2.4.38`**). Older agent builds or forks **without** the shipped widget Stop path **MUST** emit **`false`** or omit **`capabilities`**. While **`false`** or absent, clients **MUST NOT** show Stop and **MUST NOT** infer Stop from semver alone. |

**Forbidden in the JSON body:** prompt fragments, skills, playbooks, tools, taxonomy, configuration-repository metadata, or **`widget.packageVersionEcho`** (removed — widget truth stays client-local).

**Semantics:** **`GetAgentRuntimeSnapshot.agent.extensionVersion`** remains manifest-oriented for operator forensics; **`GetConnectionInfo.agent.extensionVersion`** owns the **display / import** line for this topic.

#### `ParlerGateway.CancelUserPrompt` (Phase F — user stop, parked HITL v1 + running-turn cooperative v1)

**Purpose:** User-initiated stop on the bound **`ParlerGateway`**.

**Parked HITL path:** When a non-expired in-memory **`PendingApprovalStore`** entry exists for the caller’s **`(conversation_id, request_id, principal)`** with **`agentThingName`** matching the thread binding, **`parler-agent`** **atomically** removes it (**`compareAndRemove`**, same primitive as **`SubmitApprovalDecision`**), appends durable synthetic **`role: tool`** rows carrying **`code: TURN_CANCELLED`** where tool results are required for replay pairing, emits **`approval.resolved`** with **`outcome: "cancelled"`** and **`hitl_resolution_source: "gateway_user_stop"`**, then terminal **`session.cancelled`** on the same **`ReceiveMessage`** path — **no** post-approval LLM stream (**`UI_CLIENT_PROTOCOL.md`** / **`docs/agent/turn-cancellation-control.md`** §10.3).

**Running-turn cooperative path (v1):** When **`ParlerStreamToRemoteThing`** has registered the same tuple for an active **`AgentLoop`** turn (after playbook short-circuit, through normal completion or cooperative cancel), **`CancelUserPrompt`** returns **`accepted`** immediately (see **`not_active`** below) and the turn **MUST** end with terminal **`session.cancelled`** on the live stream — **without** **`approval.resolved`** when no HITL gate was open. When the model returned **`tool_calls`** but cancel wins before all tool results are appended, **`parler-agent`** **MUST** append synthetic **`TURN_CANCELLED`** tool rows for every **`tool_call` id** that **never started execution**; a tool that **already returned** **MUST** keep its **real** tool result row (see **2.4.33**). Cooperative cancel is observed at **`AgentLoop`** safe checkpoints (**`API_CONTRACT.md`** wire revisions **2.4.32** / **2.4.33**): after each **`llmClient.chat`** return (before success / chart-rescue / tool batch), before each tool execution, and after each tool returns (before continuing the batch); an in-flight synchronous **`llmClient.chat`** **MAY** still complete before the flag is read (**`docs/agent/turn-cancellation-control.md`**).

**ThingWorx shape:** **`@ThingworxServiceResult(name = "result", baseType = "STRING")`**. Clients **MUST** parse **`result`** as UTF-8 JSON with **`schemaVersion: 1`**, **`status`** (**`accepted`** | **`not_active`** | **`already_terminal`** | **`wrong_agent`**), **`conversationId`**, **`requestId`**, optional **`alreadyRequested`**.

**Parameters (v1):**

| Parameter | Required | Rule |
|-----------|----------|------|
| **`requestId`** | Yes | Active assistant **`request_id`** — for the parked gate, **must** match **`approval.required.request_id`**; for the running-turn path, **must** match the live **`ParlerStreamToRemoteThing`** turn’s **`request_id`**. |
| **`agentThingName`** | Yes | **MUST** equal the **`AgentThreadDataTable`** row **`agentName`** for this Gateway (same defense-in-depth as **`GetConnectionInfo`**). |
| **`reason`** | No | When non-empty, echoed as wire **`reason`** on **`session.cancelled`** (e.g. **`user_stop`**). |

**ThingWorx AlwaysOn uplink (reference `parler-ui`):** Invoke on the bound **`ParlerGateway`** (**`entityName`** = Gateway Thing name = **`conversation_id`**), same locus as **`SubmitUserPrompt`**. Clients **MUST** build the parameter row as codec **InfoTable** JSON via **`buildCancelUserPromptParams`** (**`parler-ui/lib/alwaysOnInvokeParams.js`**) → **`cancelUserPromptInfoTable`** (**`parler-ui/lib/parlerInfotableJson.js`**): columns **`requestId`**, **`agentThingName`**, **`reason`** (camelCase **`STRING`** values; row field order matches **`ParlerGateway.CancelUserPrompt`** Java parameters).

**Ownership:** **`AgentThreadDataTableSupport.ensureConversationOwnedByCurrentUser`** for the Gateway thing name (**`conversation_id`**).

**`not_active`:** No matching non-expired pending **and** no active running-turn registration for **`(conversation_id, request_id, principal, agentThingName)`** (wrong phase, wrong id, wrong principal, completed turn, or not yet registered after **`session.ack`**). The service answers **`not_active`** **promptly** via lock-free checks — it does **not** block on the per-conversation execution lock the active turn may hold across a provider **`chat`** call (**`docs/agent/turn-cancellation-control.md`** §6.1). When a cooperative running turn **is** registered, **`CancelUserPrompt`** **MUST** return **`accepted`** instead (first cooperative request with **`alreadyRequested: false`**, idempotent repeat with **`alreadyRequested: true`**), not **`not_active`**.

**`already_terminal`:** The **`request_id`** already ended with a successful gateway user-stop terminal for this tuple within the **5-minute** tombstone window (repeat / racing **`CancelUserPrompt`**); **`alreadyRequested`** **SHOULD** be **`true`** on this idempotent path. The tombstone **MUST** become visible to other **`CancelUserPrompt`** callers **immediately after** the parked-path **`compareAndRemove`** wins (same ordering as **`parler-agent`** — before wire/transcript work for that stop). Also used when **`compareAndRemove`** loses a race with another consumer of the same pending (concurrent **`CancelUserPrompt`** / **`SubmitApprovalDecision`**).

**Advertisement (v1, coordinated flip — 2.4.38):** **`GetConnectionInfo.capabilities.supportsCancellation`** **MUST** be **`true`** for **`parler-agent` `0.1.187`**+ when paired with **`parler-ui-widget` `0.1.80`**+. Prior **2.4.28**/**2.4.37** staging: agent **MUST NOT** have emitted **`true`** without that end-to-end path.

### Server → client

**`chart`** payloads **MUST** match [`CHART_CONTRACT.md`](./CHART_CONTRACT.md).  
**`table`** payloads **MUST** match [`TABLE_CONTRACT.md`](./TABLE_CONTRACT.md).

| `type` | Purpose |
|--------|---------|
| `session.ack` | Request accepted |
| `activity` | Ephemeral status line (`message`; empty clears) |
| `content.delta` | Markdown fragment; concatenate in order |
| `chart` | Server-built visualization |
| `table` | Server-built tabular block (list-class rows + optional export metadata) |
| `chart_group` | **Optional (C3b-1).** Full `ChartGroupManifest` revision for the one chart group the request declared with `declare_chart_group`: identity, ordered member slots, member states and the terminal summary; resent whole with `revision + 1` on every member change, last one `final: true` (see [`CHART_CONTRACT.md`](./CHART_CONTRACT.md) §2.6 / §3.5). Clients that do not know the frame ignore it and draw the member charts as single cards. |
| `tabular.tool_success` | **Optional.** Further Insight compact metadata for a cached tabular tool success (see § **`tabular.tool_success`** below). |
| `task.state` | **Optional (v1b).** Full current-turn structured progress snapshot for the active assistant request (see § **`task.state`** below). |
| `rate_control.status` | **Optional.** Live-only UI hint when the provider rate gate enters or leaves a bounded **wait** for the active assistant **`request_id`** (see § **`rate_control.status`** below). **Not** persisted to **`AgentMessageStream`** / history. |
| `done` | Turn complete — **optional** `assistant_message_id` (stable final-assistant Stream id) and `completed_at` (ISO-8601 UTC server instant when that id is present); **optional** `llm_usage` (sanitized object: provider/model identity strings, per-round numeric token/cache fields, optional **turn-level** performance integers and booleans per **`UI_CLIENT_PROTOCOL.md`** §3 — including e.g. **`toolExecutionMaxConcurrency`**, **`multiToolCallRoundsCount`**, optional **`repetitionBlockedCount`** (synthetic **`REPETITION_BLOCKED`** tool envelopes returned this turn — **`docs/agent/multi-chart-and-thrashing-safeguards.md`** §3), optional **`parlerChartWireEmittedCount`** (count of **`type: "chart"`** frames successfully downlinked on the Parler AlwaysOn stream this turn — **`docs/agent/multi-chart-and-thrashing-safeguards.md`** §4), optional **`chartExpectedButMissing`** (true when `build_chart_from_tabular_result` was invoked this turn and **`parlerChartWireEmittedCount`** is still zero — i.e. no successful **`type: "chart"`** downlink yet; **no** user-message keyword heuristic), optional **`chartRescueAttempted`** (a one-shot end-of-turn singleton `build_chart_from_tabular_result` rescue round was scheduled after prose without a chart — see **`docs/agent/prompt-to-chart.md`** §7.6), optional **`presentationPhaseEntered`** (post-marker Answer Presentation Phase exposed `build_chart_from_tabular_result` for registered complete chartable tabular artifacts — **`docs/agent/multi-chart-and-thrashing-safeguards.md`** §4.4.1), optional **`presentationActionsRequested`** / **`presentationActionsExecuted`** / **`presentationActionsBlocked`** (presentation-phase `build_chart_from_tabular_result` telemetry), and optional **`toolProtocolViolation`** when a post-marker tool-none violation occurs — no prompts, tool bodies, or headers). Omitted when usage is unknown or empty. **Shipped ThingWorx extension:** when a final assistant round has persisted usage telemetry, the server **SHOULD** include non-empty sanitized **`llm_usage`** on the same terminal **`done`** frame (normal **`ParlerStreamToRemoteThing`** path and post-HITL continuation), not only on history hydrate. See **`UI_CLIENT_PROTOCOL.md`** §3. |
| `error` | Failure |
| `session.superseded` | Another client owns **live** for this `conversation_id` |
| `session.cancelled` | **User stop** — terminal turn cancellation (**`CancelUserPrompt`**); clears **`busy`** per **`UI_CLIENT_PROTOCOL.md`** §4; **not** a product failure (no stable **`session.error`** **`code`** requirement). AlwaysOn profile — **`conversation_id`** required. See § **`session.cancelled`**. |
| `approval.required` | Gated tool waiting for human approval (`pending_id`, `summary`, TTL, …) |
| `approval.resolved` | Final outcome for that `pending_id` (`outcome` = user decision; `executed` + `error` = write result — see § **`approval.resolved` — `outcome`, `executed`, and `error`**) |

#### Artifact Cache readiness and repository-failure terminal (v1)

Every production AIAgent requires a non-blank `AgentSettings.artifactCacheFileRepository` that
resolves as a FileRepository. After synchronous malformed-input, ownership, target, and connection
validation, the AlwaysOn worker **MUST** send `session.ack` and then evaluate this readiness before
Provider/LLM, structured Playbook, user/tool history, task-state, approval, or cache work.

| `error.code` | Meaning and action |
|---|---|
| `ARTIFACT_CACHE_NOT_CONFIGURED` | The setting is missing, empty, or whitespace-only. Configure a dedicated FileRepository and edit/save the AgentThing. |
| `ARTIFACT_CACHE_REPOSITORY_UNAVAILABLE` | The configured Thing is missing/wrong-type, resolution failed, or a required runtime repository operation became unavailable. Repair or replace the FileRepository; edit/save when configuration changed. |

For either admission rejection, the server **MUST** emit exactly one same-`request_id` terminal
`error` after the acknowledgement and **MUST NOT** emit `content.delta`, `task.state`,
`approval.required`, or `done`. A repository-wide failure after tool execution began uses the same
public terminal and **MUST NOT** call the LLM again to compose it. Post-HITL failure retains the
original approval request id. Messages and logs **MUST NOT** expose repository-relative/physical
paths, payload values, namespace keys, credentials, or stack traces.

#### Post-approval LLM stream — `request_id` (v1, normative)

When the server runs a **follow-up LLM completion** after **`approval.resolved`** for a user decision (**`outcome`** **`approved`**, **`cancelled`**, or **`rejected`**), any further server → client frames (**`activity`**, **`content.delta`**, **`chart`**, **`table`**, **`tabular.tool_success`**, **`task.state`**, **`rate_control.status`**, terminal **`error`**) **MUST** use the **same `request_id`** as **`approval.required.request_id`** (equivalently **`approval.resolved.request_id`**). The terminal **`session.done`** (or the terminal **`session.error`** that ends the turn) **MUST** also use that **`request_id`**. **Exception:** when **`hitl_resolution_source`** is **`gateway_user_stop`**, the server **MUST NOT** run a post-approval LLM completion; see **`approval.resolved` and ending the assistant turn — `busy`** below. Servers **MUST NOT** mint a **new** `request_id` for that continuation in v1 — the UI treats it as the **same assistant turn** ([`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §2.2, [`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md) §4). A **new** `request_id` after **`approval.resolved`** would require **`ChatUiState` / `busy` / `approvalGate`** rules beyond this contract (**out of scope for v1**).

**Shipped ThingWorx extension (v1):** **`approve`**, **`cancel`**, and **`reject_with_comment`** all inject the synthetic or executed **tool result** into the in-memory transcript and invoke the **same** post-**`approval.resolved`** **AgentLoop** path, so each **may** emit that follow-up stream before **`session.done`** — **except** when **`hitl_resolution_source`** is **`gateway_user_stop`** (gateway **`CancelUserPrompt`** parked cancel; **no** post-approval LLM — see table above). **`expired`** remains **no** LLM follow-up by default ( **`approval.resolved`** then **`session.done` / `session.error`** ).

#### `approval.resolved` and ending the assistant turn — `busy` (v1, normative)

The reference reducer (**[`UI_CLIENT_PROTOCOL.md`](./UI_CLIENT_PROTOCOL.md)** §4) sets **`busy=false`** and clears **`activeRequestId`** on **`session.done`**, **`session.error`**, **`session.superseded`**, or **`session.cancelled`** — **not** on **`approval.resolved`** alone.

Therefore, after **`approval.resolved`**, the server **MUST** emit a **terminal that clears `busy`** for that **`request_id`**: **`session.done`**, **`session.error`**, **`session.superseded`**, or **`session.cancelled`** — **after** any post-**`approval.resolved`** LLM stream that runs under the **shipped extension default** (including optional **`tabular.tool_success`** frames), **unless** the **`gateway_user_stop`** row below applies (no LLM stream; **`session.cancelled`** suffices).

| `approval.resolved.outcome` | **`hitl_resolution_source`** | End the turn (`busy`, `activeRequestId`) |
|-----------------------------|-------------------------------|------------------------------------------|
| **`approved`**, **`cancelled`**, **`rejected`** | omitted (in-card / **`approval.decision`** path — **no** literal **`approval_card`** on the wire in v1) | **Shipped extension default:** **MAY** run the **post-approval LLM stream** (same **`request_id`**) before ending the turn; **`session.done`** (or terminal **`session.error`**) **MUST** arrive **after** that stream completes (or **immediately** if the implementation skips the LLM round — non-default). **Recommend** **`session.done`** for **`cancelled` / `rejected`** when the turn ends successfully. |
| **`cancelled`** (visible HITL gate) | **`gateway_user_stop`** | **MUST NOT** run post-approval LLM. **MUST** emit **`session.cancelled`** (same **`request_id`**) **after** **`approval.resolved`**. **`session.done`** **not** required on this path. |
| **`expired`** | — | **Typically** **no** post-approval stream; **MUST** emit **`session.done`** or **`session.error`** (same **`request_id`**) **after** **`approval.resolved`**. **`session.error`** with a stable **`code`** is allowed if the product wants visibility. |

**Ordering (extension default):** Emit **`approval.resolved`** first (gate result); then either **optional** post-approval **`activity` / `content.delta` / `chart` / `table` / `tabular.tool_success` / `task.state` / `rate_control.status`** and final **`session.done` / `session.error`**, or **immediately** **`session.done` / `session.error`** when no stream runs ([`data-operation-solution.md`](../docs/agent/data-operation-solution.md) §3.3).

**Ordering (`gateway_user_stop`):** Emit **`approval.resolved`** (with **`hitl_resolution_source: "gateway_user_stop"`**) then **`session.cancelled`**; **no** intervening LLM stream.

#### `conversation_id` (AlwaysOn profile)

Per **`agent-alwayson.md`**, every downstream object on **`ReceiveMessage`** **MUST** include **`conversation_id`** (snake_case) in addition to the fields for that `type`.

`session.superseded` example:

```json
{
  "type": "session.superseded",
  "conversation_id": "thread-uuid",
  "message": "Another window is now live for this conversation.",
  "request_id": "optional-in-flight-turn-id",
  "code": "live_replaced"
}
```

#### `session.cancelled` (user stop — v1, normative)

**AlwaysOn profile:** **`conversation_id`** and **`request_id`** are **required** on the same object. **`reason`** and **`message`** are optional human-readable strings (English product default allowed).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | `"session.cancelled"` | Yes | Discriminator |
| `conversation_id` | string | Yes | Same AlwaysOn routing as other downstream frames |
| `request_id` | string | Yes | Assistant turn id being stopped |
| `reason` | string | No | Closed enum recommended: **`user_stop`** (extension v1) |
| `message` | string | No | Short user-facing text (e.g. **Stopped.**) |

Example (illustrative):

```json
{
  "type": "session.cancelled",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "reason": "user_stop",
  "message": "Stopped."
}
```

`activity` example (AlwaysOn profile — **`conversation_id` required** on the same object):

```json
{
  "type": "activity",
  "conversation_id": "thread-uuid",
  "request_id": "<uuid>",
  "message": "Calling read device history…"
}
```

#### `rate_control.status` (live UI — provider rate gate)

Normative product rules: **[`docs/agent/rate-control-ui-status.md`](../docs/agent/rate-control-ui-status.md)**. **Live-only:** servers **MUST NOT** persist this frame to **`AgentMessageStream`** or history rows. AlwaysOn profile — **`conversation_id`** required on the same object:

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | `"rate_control.status"` | Yes | Discriminator |
| `conversation_id` | string | Yes | Same AlwaysOn routing as other downstream frames |
| `request_id` | string | Yes | Active assistant turn id |
| `status` | string | Yes | **`waiting`** before a bounded local wait, or **`resumed`** when admission proceeds or the wait path ends with a terminal local rejection after a prior **`waiting`** |
| `reason` | string | No | When **`status`** is **`waiting`** and known: closed admission reason (e.g. **`tokens_per_minute`**, **`requests_per_minute`**, **`concurrency`**, **`upstream_blocked`**) — see **`docs/agent/rate-control.md`** |
| `wait_ms` | number | No | Non-negative wait estimate or slice (UI **MUST NOT** rely on precision) |
| `retry_after_ms` | number | No | Non-negative retry hint (UI **MUST NOT** rely on precision) |

**Terminal completion after `waiting` (v1):** For a given **`request_id`**, a **`waiting`** frame **MUST** eventually be followed by one of: **`resumed`**, **`session.cancelled`**, **`session.done`**, **`session.error`**, or **`session.superseded`** (same **`request_id`** when the turn ends). The UI clears assistant-row **`rateControlWaiting`** on any of these terminals — see **`UI_CLIENT_PROTOCOL.md`** §4.

Example **`waiting`** (illustrative):

```json
{
  "type": "rate_control.status",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "status": "waiting",
  "reason": "tokens_per_minute",
  "wait_ms": 20500,
  "retry_after_ms": 20500
}
```

Example **`resumed`**:

```json
{
  "type": "rate_control.status",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "status": "resumed"
}
```

#### `task.state` (v1b structured progress)

Normative product rules and budgets: **[`docs/agent/task-state.md`](../docs/agent/task-state.md)** § *v1b Structured Progress*. Wire shape (AlwaysOn profile — **`conversation_id`** required on the same object):

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | `"task.state"` | Yes | Discriminator |
| `conversation_id` | string | Yes | Same AlwaysOn routing as other downstream frames |
| `request_id` | string | Yes | Active assistant turn id |
| `schemaVersion` | number | Yes | Must be **`1`** in v1b |
| `status` | string | Yes | Turn-level status (e.g. `executing`, `completed`, `failed`, `blocked-by-approval`) — see formal doc |
| `title` | string | No | Checklist title; server applies caps per formal doc |
| `summary` | object | Yes | Counts object (`satisfied`, `total`, `inProgress`, `failed`, `blocked`, …). Playbook turns **MAY** add **`playbookId`** (string). Semantics per formal doc |
| `items` | array | Yes | Progress items (`source`: `skill`, `tool`, or **`playbook`**); element shape per formal doc |

**Security (normative):** Servers **MUST NOT** put raw tool arguments, full tool-result JSON, tabular row payloads, property values, hidden prompts, or chain-of-thought in **`task.state`**. Labels and **`summary`** strings are **metadata-only** (skill text, compact structural summaries, stable error codes). Do **not** use **`task.state`** to widen PASSWORD or protection semantics beyond **[`docs/agent/protection.md`](../docs/agent/protection.md)** and **[`docs/agent/task-state.md`](../docs/agent/task-state.md)**.

**v1b.2 (normative):** Each **`task.state`** frame remains a **full replacement** snapshot for the active assistant **`request_id`**. After **`get_agent_skill`** merges an embedded **`parler-task-checklist-v1`** fence, a later frame **MAY** include **more** `items` rows than an earlier frame in the **same** turn; clients **MUST** replace prior task-state with the latest snapshot (**[`docs/agent/task-state.md`](../docs/agent/task-state.md)** § *v1b.2 Wire and UI Semantics*).

Example (illustrative):

```json
{
  "type": "task.state",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "schemaVersion": 1,
  "status": "executing",
  "title": "Cross-region Stacking Robot diagnosis",
  "summary": { "satisfied": 2, "total": 5, "inProgress": 1, "failed": 0, "blocked": 0 },
  "items": []
}
```

**`approval.required`** (AlwaysOn profile: include **`conversation_id`** on the same object):

```json
{
  "type": "approval.required",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "pending_id": "pending-uuid",
  "expires_at": "2026-04-05T12:00:00.000Z",
  "tool_name": "set_property_value",
  "summary": { "title": "Confirm property write", "lines": [{ "label": "Thing", "value": "MyThing" }] },
  "actions": ["approve", "cancel", "reject_with_comment"]
}
```

**`tool_name`:** identifies the gated tool the user is approving (ThingWorx extension v1: at minimum **`set_property_value`** and **`invoke_service`**; both use the same `approval.*` / `SubmitApprovalDecision` flow).

**`approval.resolved`** (AlwaysOn profile: **`conversation_id`** on the same object):

```json
{
  "type": "approval.resolved",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "pending_id": "pending-uuid",
  "outcome": "approved",
  "executed": true
}
```

**Optional `hitl_resolution_source` (v1, additive):** When present on **`approval.resolved`**, the value **MUST** be exactly **`gateway_user_stop`** (gateway **`CancelUserPrompt`** parked cancel — closes the visible HITL card with **no** post-approval LLM — see **`approval.resolved` and ending the assistant turn — `busy`**). When **omitted**, the source is the in-card / uplink **`approval.decision`** path (shipped extension default). Servers **MUST NOT** emit the literal string **`approval_card`** for this field in v1 — clients **MUST NOT** branch on a **`approval_card`** wire token that never appears.

#### `approval.resolved` — `outcome`, `executed`, and `error` (v1, normative)

**`outcome` encodes the user’s decision** for this `pending_id`, **not** whether the platform write succeeded. Do **not** overload **`expired` / `rejected` / `cancelled`** for compare-and-stale after the user clicked **Approve**.

| Situation | `outcome` | `executed` | `error` |
|-----------|-----------|------------|---------|
| User **Approve**, write succeeds | `approved` | `true` | omit or empty |
| User **Approve**, **`set_property_value`** compare-and-set fails (current value ≠ snapshot at pending time) | **`approved`** | **`false`** | **`code`**: **`STALE_TARGET_VALUE`** or **`VALUE_CHANGED_SINCE_REQUEST`** (implementation chooses **one** stable code per deployment; document in release notes) + human-readable **`message`** |
| User **Cancel** | `cancelled` | `false` | optional short message |
| User **Reject with comment** | `rejected` | `false` | optional |
| Server-side TTL expiry, including a decision that arrives after it (see solution §2.4); a transport disconnect does **not** by itself expire the pending | `expired` | `false` | optional |

**`MUST NOT` (v1):** emit **`outcome: "expired"`**, **`"rejected"`**, or **`"cancelled"`** for **compare-and-stale** after **Approve** — use **`approved` + `executed: false` + `error`** as in the second row.

**Implementation note (ThingWorx):** **`parler-agent`** uses stable code **`STALE_TARGET_VALUE`** for this row. If a property snapshot could not be taken when the pending record was created, compare-and-stale is **skipped** and the approve path performs the write without that guard.

**`set_property_value` stale example** (same profile):

```json
{
  "type": "approval.resolved",
  "conversation_id": "thread-uuid",
  "request_id": "assistant-turn-uuid",
  "pending_id": "pending-uuid",
  "outcome": "approved",
  "executed": false,
  "error": {
    "code": "STALE_TARGET_VALUE",
    "message": "Target value changed since request; approve again from current state."
  }
}
```

Clients **SHOULD** ignore unknown `type` values. **`tool.*`** events are optional.

#### `tabular.tool_success` (optional, server → client, Further Insight)

**Emitters (v1):** The ThingWorx **`parler-agent`** extension **MAY** emit zero or more of these frames per qualifying **TOOL** result when the tool success JSON includes an **`insightEnvelope`** object (see [`TABULAR_INSIGHT.md`](./TABULAR_INSIGHT.md)). Typical ordering for that tool round: any related **`chart`** / **`table`** frames first (product-defined), then **`tabular.tool_success`**, then the usual **`activity`** line that echoes a **truncated** tool result string.

**AlwaysOn profile:** the object **MUST** include **`conversation_id`** and **`request_id`** (same as other downstream frames).

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `type` | string | yes | Literal **`tabular.tool_success`**. |
| `request_id` | string | yes | Assistant turn id. |
| `conversation_id` | string | yes | Conversation / gateway Thing name. |
| `payload` | object | yes | **Compact projection** of the tool success root (see below). |

**`payload` object**

- **MUST** include **`insightEnvelope`** (object) — same semantics as [`TABULAR_INSIGHT.md`](./TABULAR_INSIGHT.md).  
- **SHOULD** include **`resultKind`** and **`sourceCacheId`** when those fields exist on the full tool success JSON.  
- **MUST NOT** duplicate full **LARGE** inline row arrays or other bulk tabular bodies; clients use **`fetch_cached_result`** (or equivalent) for row data. The wire frame exists so UIs can attach **metadata** without parsing the truncated **`activity`** line.

---

## Hierarchy host scope — stable `code` values (v1, ThingWorx extension)

Normative **user-visible semantics** for invalid / unsupported scope are defined in **[`docs/architecture/entity-hierarchy.md`](../docs/architecture/entity-hierarchy.md)** §5. The following **`code`** strings **MAY** appear on terminal **`session.error`** frames or in structured tool / gateway errors when the hierarchy path is exercised. **Extend the table** in the same commit as new Java paths ship; do not invent parallel spellings in product UI.

**Client surfacing:** When the same **`code`** is available on both **`session.error`** and another structured channel, **`parler-ui` SHOULD** show the user-visible error from **`session.error`** once (or de-duplicate), so the user does not receive two identical toasts.

| `code` | Meaning |
|--------|---------|
| **`NOT_FOUND`** | Hierarchy target is missing or not visible under the current user (**unified** UX: no separate "permission denied" product branch). |
| **`UNSUPPORTED_ID_KIND`** | **`idKind`** is not supported in v1 (SoT: **entity-hierarchy** §4). |

---

## LLM tools

Tool definitions and execution for production chat are **ThingWorx Agent Extension** concerns (**`parler-agent`**), not this repo. Historical notes about CSV-backed tools lived in the removed Python backend; **`docs/operations/use-cases.md`** may still list example prompts for design discussion.

### `query_entities` / `query_entities_by_taxonomy` — expand intersect (v1, normative)

Optional **hierarchy expand ∩ tool row-set** (see **[`docs/architecture/entity-hierarchy.md`](../docs/architecture/entity-hierarchy.md)** §6). Both tools accept the same optional arguments:

| Argument | Type | Rule |
|----------|------|------|
| **`hierarchyNodeId`** | `string` | Optional **hierarchy node id** (NetworkID) — **direct path** from Host Context or the model. When non-blank after trim, **`parler-agent`** **MUST** call **`GetAssetList(hierarchyNodeId)`** to build **`intersectThingNames`** (no **`ResolveNetworkID`**). On service failure → **`HIERARCHY_ASSET_LIST_FAILED`**; zero usable Thing **`name`** rows → **`HIERARCHY_SCOPED_EMPTY`**; more than **5000** names after expand → **`INTERSECT_LIST_TOO_LARGE`** (same cap as explicit **`intersectThingNames`**). When **`hierarchyNodeId`** is selected, implementations **MUST NOT** fallback to **`hierarchyNodeName`** on failure. Implementations **MAY** strip **`hierarchyNodeId`** from executed tool input after a successful inject. |
| **`hierarchyNodeName`** | `string` | Optional **hierarchy display-name fragment** from the **user / dialog** (model-supplied). When non-blank after trim (and neither explicit **`intersectThingNames`** nor **`hierarchyNodeId`** applies), **`parler-agent`** **MUST** use **`ResolveNetworkID(hierarchyNodeName)`** → **exactly one** row → **`GetAssetList`**, building **`intersectThingNames`** before QIT / taxonomy listing. On resolve failure (**0** / **2+** rows or service error) return the corresponding **`HIERARCHY_RESOLVE_*`** error — **no** hidden fallback from Mashup **`hostContext`**. When **`hierarchyNodeName`** is **absent or blank**, intersect applies **only** if the model supplied explicit **`intersectThingNames`**. Implementations **MAY** strip **`hierarchyNodeName`** from the executed tool input after a successful inject. |
| **`intersectThingNames`** | `string[]` | Max **5000** entries. When non-empty, the tool keeps only rows whose **Thing `name`** is in this set using **`String.equals`** (no case folding). Non-text, JSON **`null`**, or empty-string elements are **dropped**; when any were dropped, implementations **MAY** append a short sentence to success **`note`** (preserving any existing **`note`** text). |
| **`intersectExpandHasMore`** | `boolean` | Default **false**. When **`intersectThingNames`** is used, sets **`expandHasMore`** on success (expand side may still list more Things). |

**`query_entities` success additions** (when **`intersectThingNames`** is non-empty):

| Field | Meaning |
|-------|---------|
| **`preIntersectMatchCount`** | Rows on **this** QIT page **before** ∩. |
| **`intersectedRowCount`** | Rows **after** ∩ (same as **`returnedRows`** when inline / same page). |
| **`queryHasMore`** | **Query-side** continuation: same predicate the tool would use for **`hasMore`** **without** intersect — computed from the **pre-∩** QIT page row count vs **`totalRows`** / **`maxItems`** (not from post-∩ **`returnedRows`**). |
| **`expandHasMore`** | Copy of tool argument **`intersectExpandHasMore`**. |
| **`hasMore`** | **`queryHasMore OR expandHasMore`** (§6.4). |

**`query_entities_by_taxonomy` success additions** (when **`intersectThingNames`** is non-empty):

| Field | Meaning |
|-------|---------|
| **`preIntersectMatchCount`** | Rows after **`LookupProperties`** filtering **before** ∩. |
| **`intersectedRowCount`** | Rows **after** ∩ (same as **`totalCount`**). |
| **`queryHasMore`** | **`true`** when the query-side candidate scan is not **complete** (usable truncated platform total; missing total on a full **5000**-row page; inconsistent/malformed total; or other unknown candidate-scan state). |
| **`expandHasMore`** | Copy of **`intersectExpandHasMore`**. |
| **`hasMore`** | **`queryHasMore OR expandHasMore OR completenessUnknown`**, where **`completenessUnknown`** is true when overall intersect completeness is unknown (listed-page unknown/truncated, evaluation loss, inconsistent total, or **`expandHasMore`** feeding that derivation). Never emit non-intersect **`truncated`** / **`totalUnderlyingCount`** on the intersect path. |

**Error:** more than **5000** names → **`status`:** **`error`**, **`code`:** **`INTERSECT_LIST_TOO_LARGE`**. **`parler-agent`** **SHOULD** validate this cap **before** invoking QIT when **`thingTemplate`** / **`thingShape`** and other prerequisites are already valid, so oversized lists do not waste a platform round-trip.

**Server-side intersect augment (v2):** When the model **omits** **`intersectThingNames`** or supplies a **JSON-empty** array **`[]`** (intersect off per element rules above), **`parler-agent`** **MAY** build expand set **B** from a non-blank tool argument **`hierarchyNodeId`** (**direct **`GetAssetList`**) or, when **`hierarchyNodeId`** is absent/blank, from non-blank **`hierarchyNodeName`** via **`ResolveNetworkID`** → **`GetAssetList`**. **Precedence:** explicit non-empty model **`intersectThingNames`** **>** **`hierarchyNodeId`** **>** **`hierarchyNodeName`** **>** unscoped. **Mashup `hostContext` MUST NOT** auto-populate **`intersectThingNames`** — host context is **rendered prompt only** (see **`hostContext`** wire rules above). When none of **`intersectThingNames`**, **`hierarchyNodeId`**, nor **`hierarchyNodeName`** applies, **no** hierarchy-scoped intersect runs.

If the model supplied a JSON array with **one or more** **`intersectThingNames`** elements (even if all are later dropped as invalid), implementations **MUST NOT** replace that list in v1 (**explicit model list wins**).

**Augment errors (tool `status`:** **`error`**): **`HIERARCHY_SCOPED_EMPTY`** when **`GetAssetList`** returns no usable Thing **`name`** rows; **`HIERARCHY_ASSET_LIST_FAILED`** when **`GetAssetList`** throws / fails; **`HIERARCHY_RESOLVE_NOT_FOUND`** when **`ResolveNetworkID`** returns **0** rows; **`HIERARCHY_RESOLVE_AMBIGUOUS`** when **`ResolveNetworkID`** returns **2+** rows; **`HIERARCHY_RESOLVE_FAILED`** when **`ResolveNetworkID`** throws / fails. See **[`hierarchy-network-services.md`](../docs/architecture/hierarchy-network-services.md)** §6.

When **`intersectThingNames`** is absent (empty or omitted) **and** no augment applies: success JSON **MUST NOT** include **`preIntersectMatchCount`**, **`intersectedRowCount`**, **`queryHasMore`**, or **`expandHasMore`**; **`hasMore`** keeps the non-intersect tool semantics.

**`query_entities_by_taxonomy` empty listing:** When **`intersectThingNames`** is non-empty, success responses **MUST** still include the intersect field group above even if the taxonomy listing is empty (no implementors, zero **`LookupProperties`** matches, or no usable **`name`** column) — so **`expandHasMore`** is not lost when the expand side may still have more Things.

### `query_entities_by_taxonomy` — bounded completeness (v1, normative, U1B E1)

Applies to **`query_entities_by_taxonomy`** only. Internal page size remains **`QIT_MAX_ITEMS = 5000`**. Implementations **MUST NOT** add a taxonomy offset total probe. Output **`totalCount`** remains the returned match/output row count after **`LookupProperties`** and any intersect (unchanged).

**Listing validity:** A recognized optimized-total shape with a nested implementor table present is a **valid table** (nested table **MAY** have zero rows — genuine empty page → success). Null/empty outer result, null wrapper row, or unrecognized wrapper (no nested listing identified) **MUST** return **`status`:** **`error`**, **`code`:** **`TAXONOMY_LISTING_MALFORMED`** — not success with **`totalCount: 0`**.

**Platform total carrier:** Implementations distinguish **`ABSENT`** vs **`PRESENT`+`OK`** vs **`PRESENT`+`MALFORMED`**. A present total is **`OK`** only when finite, mathematically integral (e.g. **`10.0`** OK; **`10.5`** malformed), non-negative, and within **`long`** range. Only **`PRESENT`+`OK`** with **`parsedTotal >= listingRows`** is usable for **`truncated: true`**.

**Non-intersect public vocabulary** (field *names* may match [`TAXONOMY_RESOLVER.md`](./TAXONOMY_RESOLVER.md) §4, but meaning here is **listed-page match completeness**, not identifier-scope truncation):

| Field | Rule |
|-------|------|
| **`totalCount`** | Returned match rows (unchanged). |
| **`truncated`** | **`true`** only for unfiltered + usable truncated candidate scan + zero evaluation loss; otherwise omit. |
| **`totalUnderlyingCount`** | Emit only with **`truncated: true`**; the usable **`parsedTotal`**. |
| **`hasMore`** | Continuation/unknown hint: omit when listed-page match completeness is complete; **`true`** when truncated or unknown. When **`hasMore`** is true and **`truncated`** is absent, match completeness is unknown. |

Filtered (**`LookupProperties`**) truncated/unknown candidate scans **MUST NOT** claim **`truncated: true`** (listed-page = unknown → **`hasMore: true`** only). Evaluation losses (unusable name, failed resolve, etc.) force listed-page unknown.

---

## Versioning

Any commit that edits a normative **`CONTRACTS/*.md`** file other than the version ledger must
bump **[`CONTRACT_VERSION.md`](./CONTRACT_VERSION.md)** (patch) in the same commit, including
wording-only edits. A wire or UI/wire semantic change also updates the aligned implementation
and tests in that commit. This contract-bundle bump is independent of receiving-branch product
version and changelog integration. Prefer additive fields and new optional event types.

- **2.4.40 (this document)** — mandatory Artifact Cache readiness and runtime repository failure use stable same-request terminal `error` codes; no LLM composition or following `done`; bundle **`0.1.150`**.
- **2.4.39 (this document)** — **`query_entities_by_taxonomy`** bounded completeness (U1B E1): **`TAXONOMY_LISTING_MALFORMED`**; non-intersect **`truncated`** / **`totalUnderlyingCount`** / **`hasMore`**; intersect **`hasMore`** OR completeness-unknown; distinct from **`TAXONOMY_RESOLVER.md`** §4; bundle **`0.1.144`**.
- **2.4.17 (this document)** — Host Context: repository-only template registration (no classpath built-in runtime fallbacks); unregistered parseable key → **`UNREGISTERED_GENERIC_FALLBACK`** / **`genericFallback`** generic fenced-JSON fallback; **`ValidateHostContext`** diagnostic fields; turn-state snapshot shape; bundle **`0.1.140`**. *(See **`docs/architecture/host-context-generic-fallback.md`**.)*
- **2.4.16 (this document)** — **`hierarchyNodeId`** direct **`GetAssetList`** intersect augment; precedence **`intersectThingNames` > `hierarchyNodeId` > `hierarchyNodeName`**; history export nested **`hostContext`** on **`ai-parler-history-v1`** user rows; bundle **`0.1.134`**. *(See **`docs/architecture/host-context-turn-state.md`**.)*
- **2.4.15 (this document)** — intersect augment **precedence**: **`hierarchyNodeName`** (session) **before** **`hostContext`**; no fallback to **`hostContext`** on **`ResolveNetworkID`** failure; **`note`** when scope is Mashup-sourced; **`revisionVersion` 55**. *(Superseded: Host Context v2 — Mashup `hostContext` no longer auto-injects intersect; see **`docs/architecture/host-context.md`**, bundle **`0.1.131`**+.)*
- **2.4.14 (this document)** — **`hierarchyNodeName`** → **`ResolveNetworkID`** → **`GetAssetList`** intersect augment; **`HIERARCHY_RESOLVE_*`** codes; **`revisionVersion` 54** (precedence in **2.4.15** supersedes this bullet’s ordering). *(Still normative for explicit **`hierarchyNodeName`** augment; Mashup uplink inject removed in v2 — **`0.1.132`**.)*
- **2.4.13 (this document)** — **`hierarchy_scope`** → **`query_entities*`** intersect augment; **`HIERARCHY_SCOPED_EMPTY`** / **`HIERARCHY_ASSET_LIST_FAILED`**; explicit non-empty model **`intersectThingNames`** wins; **`revisionVersion` 53**. *(Superseded: v1 **`kind: hierarchy_scope`** host inject — Host Context v2 **`key + context`** rendered prompt only; **`0.1.131`**+.)*
- **2.4.12 (this document)** — **`query_entities_by_taxonomy`** empty paths + **`intersectThingNames`**: always emit intersect fields; **`note`** MAY report dropped intersect array entries; **`BuiltInTools`** per-tool **`preIntersectMatchCount`** schema hint; **`writeIntersectSuccessFields`**; **`revisionVersion` 47**.
- **2.4.11 (this document)** — intersect success adds **`queryHasMore`** + **`expandHasMore`**; **`hasMore`** = OR; **`query_entities`** **`queryHasMore`** uses **pre-∩** QIT page size; early **`INTERSECT_LIST_TOO_LARGE`**; **`ParlerHostScopeLogFormatter`**; **`ParlerEphemeralSystemIndices.NONE`**; **`revisionVersion` 46**.
- **2.4.10 (this document)** — **`query_entities`** + **`query_entities_by_taxonomy`** **`intersectThingNames`** / **`intersectExpandHasMore`** + **`preIntersectMatchCount`** / **`intersectedRowCount`** / **`hasMore`** merge; **`EntityHierarchyIntersectHelper`**; **`revisionVersion` 45**.
- **2.4.9 (this document)** — **`AgentThing`** fielded **`hostScope*=`** logs; **`kv`** ACCEPTED + **`RejectReason`**; **`ParlerHitlPendingSnapshotHelper`** defensive copy; **`RejectReason.allHostScopeRejectCodes()`** + **`RejectReasonTest`**; **`revisionVersion` 44**. *(Superseded: v1 **`kind`** / **`kv`** Host Scope model — v2 **`key + context`**; **`0.1.131`**+.)*
- **2.4.8 (this document)** — **`RejectReason`** + **`Decision.rejectReason`**; **`ParlerHitlPendingSnapshotHelper`**; **`ParlerEphemeralSystemIndices`** **`equals` / `hashCode` / `toString`**; **`revisionVersion` 43**. *(Historical; host-scope reject codes evolved in v2 — see **`HostContextUplink`**.)*
- **2.4.7 (this document)** — **`ParlerEphemeralSystemIndices`**; **`rejectDetail`** as log text; **`kv`** vs **Wire bytes** / **`AgentToolContext`** note; **`revisionVersion` 42**. *(Superseded: v1 **`kv`** uplink — v2 templates; **`0.1.131`**+.)*
- **2.4.6 (this document)** — **`HostScopeJsonUplink`** **`rejectDetail`**; **`kv`** pair-type drops; **`EphemeralSystemStripHelper`**; **`revisionVersion` 41**. *(Superseded: **`HostScopeJsonUplink`** removed — **`HostContextUplink`**; **`0.1.131`**+.)*
- **2.4.5 (this document)** — **HITL** pending snapshots strip ephemeral system injections; **`revisionVersion` 40**. *(Still normative for HITL strip behavior; host-scope ephemeral content is now rendered template prompt in v2.)*
- **2.4.4 (this document)** — **`parler-agent`** **`HostScopeJsonUplink`** validation + **`ParlerHostScopeSystemMeta`** LLM line; **`parler-ui`** **`npm test`** locks **`SubmitUserPrompt`** hostContext column to wire read. *(Superseded: **`HostScopeJsonUplink`** / **`ParlerHostScopeSystemMeta`** removed — **`HostContextUplink`** + rendered prompt; **`0.1.131`**+.)*
- **2.4.3 (this document)** — **`parler-ui`** **`hostContext`** uplink read path: **Wire bytes** / no trim on **`HostScopeJson`** bind read; **`UI_CLIENT_PROTOCOL`** host-context bullet. *(Still normative for wire-byte preservation; v2 payload shape in **`0.1.131`**.)*
- **2.4.2 (this document)** — **`hostContext`** on ThingWorx **SubmitUserPrompt** / **ParlerStreamToRemoteThing** + **`parler-ui`** **`HostScopeJson`** property (implementation note). *(Still normative for service surface; v2 **`key + context`** in **`0.1.131`**.)*
- **2.4.1 (this document)** — **`hostContext`**: closed **`kind`** row, **wire-byte** preservation rule, validation / observability anchors to **mashup-host-context** §3/§5/§7; hierarchy **`code`** client surfacing note. *(Superseded: v1 **`kind`** enum and **`mashup-host-context.md`** — **`docs/architecture/host-context.md`**; **`0.1.131`**+.)*
- **2.4.0 (this document)** — optional ThingWorx **`hostContext`** on **`SubmitUserPrompt` / `ParlerStreamToRemoteThing`**; § **Hierarchy host scope — stable `code` values** (`NOT_FOUND`, `UNSUPPORTED_ID_KIND`).
- **2.2.0 (this document)** — optional **`tabular.tool_success`** Further Insight wire frame (compact **`insightEnvelope`** projection); see § **`tabular.tool_success`** above.
- **2.1.0 (this document)** — optional `chat.request.payload.user_timezone` (logical JSON) and ThingWorx **`userTimezone`** service parameter on AlwaysOn user-turn invokes; see [`times-solution.md`](../docs/architecture/times-solution.md).
