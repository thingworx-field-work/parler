# Data operations (write path) — HITL narrative

**Status:** describes the shipped behavior of `parler-agent` and the reference `<parler-ui>` widget.
[`CONTRACTS/API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) and
[`CONTRACTS/UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) cite this file by
section number (§2.1, §2.2, §2.4, §2.5, §3, §3.1–§3.3). Where this narrative and a contract
overlap, the contract is normative; this file explains how the pieces fit together.

---

## 0. Purpose and design stance

The agent can change platform state through three tool families: the built-in
`set_property_value`, the built-in `invoke_service`, and configuration-repository extended tools.
Unless configuration explicitly exempts a call (§5), a Parler AlwaysOn turn **pauses** before such a
call runs and asks the user to confirm it (human-in-the-loop, HITL).

HITL confirms **intent and the exact proposed change**; it is not an authorization system.
Authorization stays with the ThingWorx `SecurityContext` / RBAC, and a second-person approval, if an
application needs one, belongs in a platform workflow layered on top. Consequences for the
implementation:

- The approval card text is built by the server from the structured tool arguments, never from
  model prose (§3.1).
- The decision must come from the same principal that started the turn (§2.1).
- The approved call executes under the submitting user's security context (§2.2), through the
  same permission-checked platform entry as every other agent call (§1.4). An approval never lets
  the agent do what that user could not do through ThingWorx REST.

## 1. Tool surface

### 1.1 `set_property_value`

Arguments: `thing_name`, `property_name`, `value` (required) and `base_type` (optional hint).
Legacy camelCase aliases `thingName`, `propertyName`, `baseType` are accepted.

Before any approval is requested, `SetPropertyValueExecutor.gateSetPropertyValueForHitl` resolves the
Thing through the application-Thing name gate, reads the property's **actual** base type from
metadata, and canonicalizes the requested value (§1.3). The canonical Thing name, property name,
base type, and value are written back into the gated tool call, so the approval card and the
eventual write use exactly the same value. Failures are returned to the model as tool errors and no
pending approval is created: `MISSING_PROPERTY_NAME`, `MISSING_VALUE`, `PROPERTY_NOT_FOUND`,
`BASETYPE_HINT_MISMATCH`, `INVALID_*_VALUE`, `UNSUPPORTED_BASE_TYPE`, or a Thing-name gate error.

**Protected values.** `PASSWORD` properties cannot be written: the gate and a second metadata
preflight (`ProtectedValuePolicy.setPropertyValuePreflightBlockedJson`) return
`PROTECTED_VALUE_WRITE_BLOCKED` before any pending is created. See [`protection.md`](./protection.md).

**Path restriction.** `set_property_value` is always gated; there is no bypass setting. The pause is
only possible on the Parler AlwaysOn turn path (§2.1). Anywhere else — for example the synchronous
`Chat` / `ChatAsync` services — the tool returns `status: "blocked"`,
`code: "PROPERTY_WRITE_REQUIRES_PARLER_CONTEXT"` and does not write.

**Read-only properties.** After approval the write invokes the target Thing's
`UpdatePropertyValues` through `processAPIServiceRequest` (§1.4). The platform checks the approving
user's ServiceInvoke on `UpdatePropertyValues` and PropertyWrite on the property, but that Service
writes with the platform's read-only bypass. The agent therefore enforces the flag itself: a
read-only property returns `PROPERTY_READ_ONLY` from the gate, before any pending is created, and
again from the approved write if the definition changed in between.

### 1.2 Other gated tools

| Tool | Gate |
|------|------|
| `invoke_service` | Paused for HITL unless `/policies/invoke_service.json` has a matching allow rule. Missing, invalid, or unmatched policy means HITL; a policy-evaluation exception also means HITL. |
| Extended tools (`/tools/extended_tools.json`) | Paused for HITL unless the entry sets `hitl: false`. Capability-bearing `MUTATING` tools always require HITL; disabled and `DESTRUCTIVE` / `ADMIN` capabilities are refused with `CAPABILITY_POLICY_BLOCKED` before any pending is created. |

No other built-in tool is gated. Like `set_property_value`, the pause needs the Parler turn context.
On the synchronous `Chat` / `ChatAsync` services there is no approval channel, so a non-bypassed
`invoke_service` or a gated extended-tool call is refused instead: the tool result is
`status: "blocked"`, `code: "APPROVAL_REQUIRES_PARLER_CONTEXT"`, and nothing executes. Calls that
configuration exempts from HITL (§5) still run there. Static
Playbook runs do not support approval continuation: a node that reaches the gate fails with
`PLAYBOOK_HITL_REQUIRED` and its pending record is discarded.

### 1.3 Value and `base_type` rules the model must follow

The tool description asks the model to always send `base_type` when known (from
`discover_thing_members` or `get_property_values`). The server still uses property metadata as the
source of truth; a hint that disagrees fails with `BASETYPE_HINT_MISMATCH`.

| Property base type | Accepted `value` | Canonical form |
|--------------------|------------------|----------------|
| `STRING`, `TEXT`, `HTML`, `HYPERLINK`, `GUID`, `IMAGELINK`, `JSON`, `XML`, `TAGS` | JSON string (any other JSON is serialized to its text) | string |
| `BOOLEAN` | JSON `true` / `false`, or exactly the text `"true"` / `"false"` | boolean |
| `NUMBER` | JSON number or numeric string; must be finite | number |
| `INTEGER`, `LONG` | integral JSON number or parseable integer string | integer |
| `DATETIME` | ISO-8601 string | string |
| `PASSWORD` | — | refused, `PROTECTED_VALUE_WRITE_BLOCKED` |
| anything else (e.g. `INFOTABLE`, `LOCATION`) | — | refused, `UNSUPPORTED_BASE_TYPE`; use `invoke_service` |

### 1.4 Permission checks on every platform call

ThingWorx is the only authority. The agent adds no permission logic of its own; it chooses the
platform entry that applies the right principal. `PlatformAccess` forwards to those entries, and
the permission-free platform APIs (`EntityUtilities.findEntityDirect`,
`processServiceRequestDirect`) appear nowhere else (`PlatformAccessGuardTest`). There are three
modes:

| Mode | Platform calls | Checks | Used for |
|------|----------------|--------|----------|
| As user | `findEntity`, `processAPIServiceRequest` | Current user's Visibility and ServiceInvoke (REST semantics) | Every target the user or the model names and every Service a tool call reaches: `invoke_service`, extended tools, property history, stream queries, key resolution, discovery, alert and search Resources, `GetPropertyDefinitions` for the entity tools, the Agent's `ResolveDocumentSet` (tool and host-context scoping), document-repository reads, skill bodies for `get_agent_skill`, the approved write, and the Agent Thing named by a Gateway request |
| Programmatic | `findEntityDirect` followed by the platform's `isVisible(true)`, `processServiceRequest` | The same permissions, satisfied by the current user **or** the System user (the rule ThingWorx applies to server-side scripts) | Parler's own infrastructure that no tool argument selects: its message and LLM-call streams, thread DataTable, configuration and export FileRepositories, LLM provider and connection Things, `GetAlertPrompt` while the prompt is built, and the targets that configuration loads validate (below) |
| Policy check | `findEntityDirect` | none | Only the PASSWORD guards (`ProtectedValuePolicy`, `InvokeServiceNamedVtqProtection`, `TaxonomyPropertyProjection`). They read metadata to mask or block a value and never return data or call a Service, so they can only tighten a decision |

**Property values.** `get_property_values`, the taxonomy projection and lookup, `resolve_thing`
identity matching and the `set_property_value` stale snapshot read current values with
`Thing.getNamedPropertyValuesAsVTQInfoTable`, which checks the access modifier and PropertyRead for
the current user with no System fallback and leaves out what the user may not read. A left-out
property is reported as unreadable; the agent never retries it through `getPropertyValue`, whose
PropertyRead check would accept the System user.

**Document repository.** Document tools load their index through the repository's own services
(`BrowseDirectory`, `LoadText`) as the calling user, so ThingWorx authorizes every read the agent
makes. The index is then shared by everyone using the same settings until its TTL expires: a cache
hit reuses content an earlier load read, possibly for another user, without a new repository call.
A rebuild the repository refuses returns nothing and leaves the shared index untouched
([`document-chunk-tools.md`](./document-chunk-tools.md) §7 and §10).

**Configuration loads.** Extended tools (`/tools/extended_tools.json`) and taxonomy parents
(`identity-types.json`) are validated when the shared prompt-context snapshot is built: lazily on
the first turn after the Agent Thing starts, under that turn's user, or at once by
`RefreshPromptContextCache`, under the caller. The check is programmatic: the
target Thing, ThingTemplate or ThingShape must be visible to that user **or** to the System user.
Permission on the configuration FileRepository alone is not enough. If neither can see a target,
the extended tool is skipped or the taxonomy row is left unresolved (logged at load), and the
snapshot every user shares keeps that result until it is refreshed; if only some users can see it,
the result depends on who triggered the load. To make the snapshot independent of the first user,
grant the System user Visibility on those targets, then rebuild the snapshot with
`RefreshPromptContextCache`. Once a snapshot exists, `RefreshTaxonomyCache` reloads only the taxonomy
and does not bring back skipped extended tools; it builds the whole snapshot only when none exists
yet. Visibility is all the System user needs here: executing an extended tool
still goes through the calling user's ServiceInvoke.

Deployment consequence: a user who chats with an agent needs Visibility on the Things, Resources and
templates the agent touches for them, ServiceInvoke on the Services it calls, PropertyRead on the
properties it reads and PropertyWrite on those it writes, exactly as through REST. Parler
infrastructure works when either that user or the System user holds the permission; granting it to
the System user once is the usual setup.

A thread the platform did not start has no security context and sees nothing. The TTL sweep (§2.4)
therefore runs each expiry under the security context captured when the pending was created, and
async and continuation threads carry the caller's context with them.

One push is outside `PlatformAccess`: frames to the conversation's own `ParlerGateway` RemoteThing
(`ParlerReceiveMessageSupport.send`) use `RemoteThing.callService`, the platform's native way to
reach a bound edge. The Service name is fixed (`ReceiveMessage`), and the target is the connection
the current user's thread is bound to, checked by the ownership gate before any turn starts.

## 2. Server-side pending approval

### 2.1 Where the pending record lives and how it is bound

When a gated call is reached during a Parler AlwaysOn turn (`ParlerStreamToRemoteThing`, its approval
continuations, or a Playbook bound to that stream), `ParlerHitlStreamScopedEnqueue` stores a
`PendingApprovalRecord` in `PendingApprovalStore` and throws `ApprovalPendingException`; the agent
loop stops and the turn ends with `approval.required` (§3.1). If the Parler turn context is absent
the enqueue is a no-op (see §1.1–§1.2 for what happens then).

The store is an in-memory `ConcurrentHashMap` keyed by `pending_id` (a random UUID). It is not
persisted and not shared across JVMs; a process restart drops every pending approval. Each record
binds:

- `pending_id`, `request_id` (the assistant turn that produced the call), `conversation_id` (the
  `ParlerGateway` name), and the **principal** that started the turn;
- the Agent Thing name, the gated tool call with its canonical arguments, and a transcript snapshot;
- for `set_property_value`, a snapshot of the current property value (§2.5);
- an expiry time of **15 minutes** after creation (`PendingApprovalRecord.DEFAULT_TTL_MILLIS`; not
  configurable).

A decision is accepted only when every binding matches. Under a per-conversation lock the server
checks, in order: `pending_id` known, `request_id` equal, `conversation_id` equal, principal equal,
not expired. The `ParlerGateway` path additionally requires `conversationId` to equal the Gateway
name and the caller to own the conversation's thread row. **`request_id` is mandatory:** a missing or
mismatched `request_id` makes the Service call fail, **no tool executes**, and the pending record
stays in place. Every rejection writes a `PARLER_HITL event=decision_rejected` log line with a stable
`reason` (`MISSING_REQUEST_ID`, `REQUEST_ID_MISMATCH`, `CONVERSATION_ID_MISMATCH`,
`PRINCIPAL_MISMATCH`, `UNKNOWN_PENDING`, …; §6). A matching decision removes the record with an
atomic compare-and-remove, so exactly one of decision, expiry sweep, or user stop wins.

### 2.2 Relationship with the agent loop

The paused turn does not receive a placeholder tool result. After a valid decision the server
continues **the same turn**:

1. **Approve** — the gated call executes (for `set_property_value`, after the stale check in §2.5;
   for extended tools, after re-resolving the tool from the active registry and reapplying its
   capability policy). **Cancel** and **reject with comment** execute nothing and produce a
   synthetic tool result (`{"status":"cancelled",…}` or `{"status":"rejected","comment":…}`).
2. `approval.resolved` is sent.
3. The executed or synthetic result is appended as the `role: tool` row for the gated call, both in
   memory and in the durable message stream (`HitlSyntheticToolResultAppender`). Parallel tool calls
   that were queued behind the paused one receive synthetic results too.
4. The agent loop runs again (post-approval LLM completion) and streams its output under the **same
   `request_id`** as `approval.required`, ending with `done` (or `error` then `done`). It may pause
   again, emitting a new `approval.required` with the same `request_id`.

The continuation runs on its own thread with `ParlerHitlContinuationContext` rebinding the turn's
conversation and request scope, and under the security context of the user who submitted the
decision, so every call it makes is checked against that user's permissions (§1.4). Keeping one `request_id` for the whole turn is normative
([`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) § Post-approval LLM stream).

### 2.3 Other pending effects

While a conversation has an unexpired pending approval, `ClearConversation` and
`SetConversationHistoryCutoff` are refused (the latter with `CUTOFF_BLOCKED_HITL_PENDING`), and
history compaction and storage trimming for that conversation are deferred.

### 2.4 Approval principal and connection lifecycle

- **Who may decide:** only the principal recorded at enqueue time (§2.1). Another user, even one
  with access to the Gateway, is rejected with `PRINCIPAL_MISMATCH`.
- **Expiry:** `ParlerApprovalExpiryScheduler` sweeps the store every 30 seconds. Each expired record
  is handled under the security context captured when it was created (§1.4); it is removed and `approval.resolved` (`outcome: "expired"`, `executed: false`,
  `error.code: "PENDING_EXPIRED"`) followed by `done` is sent under the pending's `request_id`; a
  synthetic expired tool result is appended to the transcript. A decision that arrives after expiry
  but before the sweep takes the same path and the Service call fails.
- **Transport changes:** a pending approval does not survive a disconnect, reconnect, or session
  rebuild from the client's point of view. The widget closes the gate locally and never submits a
  stale `pending_id` on a new transport (normative:
  [`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) § `approvalGate` (HITL) —
  invalidation). The server does not detect the disconnect: the record is not tied to a transport
  connection. It ends when it is consumed (a decision or a user stop) or when its TTL expires; an
  expired record may stay in the store until the next sweep removes it, and a decision that arrives
  in between is refused as expired. A process restart drops every record. The platform's unbind
  callback does not identify which connection ended, so the extension does not expire pendings on
  it. Until the pending is consumed or its TTL expires, the conversation keeps the §2.3 restrictions
  (`ClearConversation`, history cutoff, compaction), even though the client has already closed its
  gate; an expired record no longer blocks them while it waits for the sweep. Frames are delivered only while the
  conversation's RemoteThing is connected, so after a disconnect `approval.resolved(expired)` may
  never reach the client — which is why the UI must not wait for it. A process restart loses all
  records; a later decision fails with `UNKNOWN_PENDING`.

### 2.5 Late approval and concurrent writes

`set_property_value` uses a lightweight compare-and-set. At enqueue time the server snapshots the
property's current value as canonical JSON. On **Approve** it reads the value again; if both reads
succeed and differ, the write is skipped and the result is `approval.resolved` with
`outcome: "approved"`, `executed: false`, `error.code: "STALE_TARGET_VALUE"`. The outcome stays
`approved` because the user did approve; `expired` / `rejected` / `cancelled` are never used for a
stale target (normative table in [`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md)
§ `approval.resolved` — `outcome`, `executed`, and `error`). If either snapshot cannot be taken, the
check is skipped and the write proceeds.

`invoke_service` and extended tools have no stale check: the approved arguments execute as captured.
Applications that need optimistic concurrency must implement it in the platform Service.

For every approved call, `executed` is `false` exactly when the tool result has `"status": "error"`
(its `code` is copied into `approval.resolved.error`); otherwise `executed` is `true`.

## 3. Protocol and UI

### 3.1 Protocol layer

**`approval.required`** (server → client) carries `request_id`, `conversation_id`, `pending_id`,
`expires_at` (UTC ISO-8601, 15 minutes after sending), `tool_name`, `summary`
(`{ title, lines: [{ label, value }] }`), and `actions` (always `approve`, `cancel`,
`reject_with_comment`). The summary is built by the server from the redacted structured arguments:
`invoke_service` shows entity type, entity name, Service, and a parameter preview;
a gated extended tool shows the tool name, target Thing, Service, and a redacted parameter preview;
`set_property_value` shows Thing, property, base type, new value, and the value observed at request
time. No `done` is sent for the turn while the gate is open.

**`approval.decision`** (client → server) carries `request_id`, `conversation_id`, `pending_id`,
`decision` (`approve` | `cancel` | `reject_with_comment`), and `comment`. On ThingWorx AlwaysOn the
widget sends it by invoking **`SubmitApprovalDecision`** on the bound `ParlerGateway` (parameters
`pendingId`, `decision`, `requestId`, `conversationId`, `comment`) — the same invoke locus as
`SubmitUserPrompt`. `AIAgent.SubmitParlerApprovalDecision` is an alternate direct path for tooling;
it checks that the pending belongs to that Agent Thing but does not repeat the thread-ownership
check.

**`approval.resolved`** (server → client) carries `request_id`, `conversation_id`, `pending_id`,
`outcome` (`approved` | `cancelled` | `rejected` | `expired`), optional `executed`, and optional
`error { code, message }`. Optional **`hitl_resolution_source`** is present only with the value
`gateway_user_stop`, when the user pressed Stop (`ParlerGateway.CancelUserPrompt`) while the gate
was open; see [`turn-cancellation-control.md`](./turn-cancellation-control.md). Field-level rules:
[`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md).

### 3.2 UI state

The reference widget keeps the gate in `ChatUiState.approvalGate`
([`UI_CLIENT_PROTOCOL.md`](../../CONTRACTS/UI_CLIENT_PROTOCOL.md) §4; `parler-ui/lib/chatSession.js`):

- `approval.required` opens the gate with `requestId`, `conversationId`, `pendingId`, `expiresAt`,
  `toolName`, `summary`, `actions`, and `submitState: "idle"`. `requestId` is echoed on the
  decision uplink.
- The client-only `submitState` (`idle` → `submitting` → `submitted`) and `submitError` allow one
  decision per gate. A failed uplink resets `submitState` to `idle` and sets `submitError`; it is not
  a turn terminal and must not synthesize `session.error` (reducer rule 10b).
- `approval.resolved` clears the gate only when `pendingId` matches and does **not** clear `busy`.
- The gate is also cleared by `session.error`, `session.superseded`, `session.cancelled`, a matching
  `session.done`, transport loss, and a new successful bind.
- The widget's **Reject** control requires a non-empty comment.

### 3.3 Turn ending and ordering

`approval.resolved` never ends the turn by itself; the server must follow with a terminal that
clears `busy`. The shipped extension orders frames as follows
([`API_CONTRACT.md`](../../CONTRACTS/API_CONTRACT.md) § `approval.resolved` and ending the assistant
turn — `busy`, normative):

| Case | After `approval.resolved` |
|------|---------------------------|
| `approved`, `cancelled`, `rejected` via `approval.decision` (`hitl_resolution_source` omitted) | Post-approval LLM stream under the same `request_id`, then `done` (or `error`, then `done`). |
| `cancelled` with `hitl_resolution_source: "gateway_user_stop"` | **No** post-approval LLM. Terminal `session.cancelled` (same `request_id`); `done` is not sent. |
| `expired` | No LLM follow-up; `done` immediately (`session.error` is also permitted by the contract). |

On the UI side `done` maps to `session.done` and `error` to `session.error`.

## 4. Reject with comment

`reject_with_comment` is a first-class decision. The server trims the comment and places it in the
gated call's synthetic tool result, `{"status":"rejected","comment":"…"}`, so the post-approval LLM
round sees the user's reason as tool output (not as system text) and can propose a corrected call,
which would go through a new approval. The comment body is not written to the audit log; only its
length is (`comment_chars`). The server accepts an empty comment; the reference widget does not send
one.

## 5. Configuration entry points

Both files live in the Agent's configuration repository
([`configuration-repository.md`](./configuration-repository.md)):

- **`/tools/extended_tools.json`** — per entry, `hitl` is an optional boolean defaulting to `true`;
  only an explicit `hitl: false` bypasses HITL for that tool. An entry with a non-boolean `hitl` is
  skipped with a warning, so the tool is not registered at all. `playbookSafe: true` takes effect
  only together with `hitl: false`, and `hitl: false` does not grant `invoke_service` bypass for the
  same Service.
- **`/policies/invoke_service.json`** — an allow-only list of rules matched against the resolved
  entity type, entity name, and Service name (glob `*` only). A match bypasses HITL for that
  `invoke_service` call; no match, a missing file, or an invalid file means HITL. See also
  [`invoke_service_design.md`](./invoke_service_design.md).

`set_property_value` has no configuration: it is always gated. Protection rules
([`protection.md`](./protection.md)) apply regardless of either file.

## 6. Audit logging

`ParlerHitlAuditLog` writes single-line application-log events prefixed `PARLER_HITL`:
`pending_enqueued`, `decision_consumed`, `continuation_outcome`, `pending_expired`, and
`decision_rejected`. Lines carry `pending_id`, `principal`, `conversation_id`, `request_id`,
`tool_name`, the decision or outcome, `executed`, `code`, and the Agent Thing (plus the resolved
target for `invoke_service`). `decision_rejected` logs at `WARN`, except client-style mistakes
(`MISSING_*`, `INVALID_DECISION`, `GATEWAY_SUBMIT_CONV_MISMATCH`), which log at `DEBUG` unless the
Agent's `AgentSettings.hitlAuditDebugAll` is `true` (restart the Agent Thing after changing it). See
[`agent-alwayson.md`](../architecture/agent-alwayson.md) §0.5. There is no DataTable-based audit
store.

## 7. Source locations

Paths are under `parler-agent/src/main/java/com/thingworx/things/agent/`.

| Concern | Source |
|---------|--------|
| Pending record, TTL, store | `tools/PendingApprovalRecord.java`, `tools/PendingApprovalStore.java`, `tools/ParlerHitlStreamScopedEnqueue.java` |
| Expiry sweep | `ParlerApprovalExpiryScheduler.java`; `AgentThing.deliverParlerApprovalExpired` |
| Gate decision per tool | `AgentThing.dispatchExecuteToolCallWithoutRepetitionGuard`; `configrepo/ServiceCapabilityRuntimePolicy.java`; `configrepo/InvokeServiceAllowPolicy.java`; `configrepo/ExtendedToolsManifest.java` |
| `set_property_value` gate, canonicalization, stale check, write | `tools/SetPropertyValueExecutor.java`; `tools/ProtectedValuePolicy.java` |
| Decision uplink and validation | `ParlerGateway.SubmitApprovalDecision`; `AgentThing.completeParlerApprovalFromParlerGateway`, `AgentThing.consumePendingParlerApprovalRecord`, `AgentThing.SubmitParlerApprovalDecision` |
| Continuation | `AgentThing.runParlerApprovalContinuation`, `AgentThing.runParlerPostToolAgentLoop`; `ParlerHitlContinuationContext.java`; `HitlSyntheticToolResultAppender.java` |
| Wire frames | `ParlerReceiveMessageSupport.wireApprovalRequired`, `wireApprovalResolved`, `sendParkedGatewayUserStopLiveWirePair` |
| User stop while the gate is open | `AgentThing.cancelUserPromptFromParlerGateway`; `ParlerCancelUserPromptGatewayOrchestration.java` |
| Permission-checked platform calls | `PlatformAccess.java`; guard test `PlatformAccessGuardTest` |
| Audit | `tools/ParlerHitlAuditLog.java` |
| Widget | `parler-ui/lib/chatSession.js`, `parler-ui/lib/approvalDecisionSubmit.js`, `parler-ui/lib/wireAdapter.js` |
