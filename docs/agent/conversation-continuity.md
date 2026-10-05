# Conversation continuity

Status: **implemented** — canonical SoT for conversation continuity after AgentThing restart / memory loss.

## 1. Goal

Parler should keep the LLM-side conversation usable after an AgentThing restart, edit/save, enable/disable, or in-memory `_conversations` loss.

The user-facing UI hydrates visible history from `AgentMessageStream`. LLM continuity is the matching server side: when the next user turn starts, `AgentThing` rebuilds enough recent `ChatMessage` history from persisted Stream rows before calling the model.

This solves the practical mismatch where:

- the widget shows prior conversation history
- the server-side agent has no in-memory LLM history
- the user asks a follow-up that depends on prior turns

## 2. Two history paths

There are two different history paths:

1. UI history:
   - `ParlerGateway.GetConversationHistoryJson`
   - reads `AgentMessageStream`
   - builds `ai-parler-history-v1`
   - optimized for visible transcript hydrate

2. LLM history:
   - `AgentThing._conversations`
   - in-memory `List<ChatMessage>` per conversation id
   - passed to `AgentLoop`
   - lost when the AgentThing lifecycle clears memory

Rehydration (below) bridges them by rebuilding the in-memory list from Stream rows. `ClearConversation` does not delete `AgentThreadDataTable` rows or `AgentMessageStream` rows, so clear semantics are an explicit `historyClearedAt` marker (§7) rather than missing thread rows.

## 3. Target behavior

When a user submits a new turn through a rehydrate-enabled Parler AlwaysOn entry point and `_conversations` does not contain that conversation id:

1. Verify that this is a rehydratable conversation.
2. Read the conversation metadata from `AgentThreadDataTable`.
3. Query recent sanitized rows from `AgentMessageStream`, respecting the conversation's `historyClearedAt` marker.
4. Rebuild a bounded, coherent LLM transcript.
5. Store that transcript in `_conversations` without overwriting a newer in-memory list.
6. Continue normal turn assembly:
   - refresh or use prompt-context cache
   - compose the current leading system row
   - inject per-turn dynamic rows
   - append the current user message
   - run `AgentLoop`

The user should not need to know whether the history came from memory or Stream.

## 4. Transcript-first rehydration

> **Superseded in part by `advanced-compact` (shipped).** The rules in this section remain the **baseline and
> fallback** behaviour and are still what runs whenever no checkpoint applies. On top of them, `advanced-compact`
> added a bounded, model-generated **conversation checkpoint**: a validated `context_checkpoint` Stream row can now
> lead the rebuilt transcript with its semantic prose plus its own exact retained tail, followed by the rows
> appended after it. Any validation failure falls back to exactly the transcript-first path described here. See
> `docs/core/advanced-compact.md` §9.1–§9.2 for the row shape, selection filters, watermark walk, and liveness
> rules; §13–§14 below carry the resulting boundary and budget changes.

Rehydration restores a visible transcript history:

- user messages
- final assistant text messages

It does not restore old assistant tool-call rows or tool result rows by default.

This is a deliberate scope choice. Replaying old tool rows costs more than it is worth:

- provider APIs require strict assistant-tool-call / tool-result pairing
- old `cacheId` values may no longer be live after JVM or AgentThing lifecycle changes
- old tool result JSON can be large and token-expensive
- tool rows are internal evidence, not always necessary for ordinary follow-up language

Transcript-first rehydration is enough to fix the visible-history mismatch for normal follow-ups.

## 5. Scope

In scope:

- Parler AlwaysOn conversations first
- `ParlerGateway.SubmitUserPrompt`
- `AIAgent.ParlerStreamToRemoteThing`
- lazy rehydrate on first resumed turn
- bounded user/final-assistant transcript reconstruction, optionally led by a validated conversation checkpoint
- `ClearConversation` marker semantics for Parler conversations

Out of scope:

- `adhoc-*` single-turn sources
- named `Chat` / `ChatAsync` conversation rehydrate
- restoring old system prompt rows
- restoring old tool-call/tool-result rows
- restoring cache contents
- treating old `cacheId` values as live
- rebuilding pending approvals from Stream alone
- LLM summarization of old history — **superseded**: `advanced-compact` added bounded checkpoint generation and
  restore. It remains out of scope *for this document's transcript-first path*, which never summarizes; the
  checkpoint is a separate, validated envelope with its own generation, persistence, and fail-closed rules
- physical cleanup of old Stream rows
- long-term memory
- scheduler or background task behavior

## 6. Source of truth and service boundary

Use two platform stores:

1. `AgentThreadDataTable`
   - verifies the conversation row exists
   - verifies expected agent Thing where applicable
   - stores the conversation history marker, `historyClearedAt`

2. `AgentMessageStream`
   - stores sanitized message rows
   - Stream metadata `source` is the conversation identity
   - row field `agentThing` records the AIAgent Thing name
   - row field `role` records transcript roles (`user`, `assistant`, `tool`, `system`) and may also record internal
     roles: `ui_feedback` (side-channel thumbs state) and `context_checkpoint` (a conversation continuity envelope,
     `docs/core/advanced-compact.md` §9.1). `context_checkpoint` is **not** an ordinary unknown row — it is a
     selected-and-validated candidate with its own rules below
   - row fields `content`, `toolCallId`, and `toolCalls` hold message payloads

This topic does not define the caller policy for `ClearConversation`. Service visibility, caller eligibility, and administrator-only restrictions are controlled by the ThingWorx permission model and the deployment's ThingWorx permission settings. This design only defines the functional behavior after the service is allowed to run.

Rehydration must not become a new external history-read path. It should run inside the existing submit path after the conversation has already been resolved by the normal Parler flow.

If the `AgentThreadDataTable` row is missing, do not rehydrate even if Stream rows still exist.

If `historyClearedAt` is present on the thread row, the effective conversation history starts at that marker. Rows before the marker are retained in the Stream for audit/debug/history-retention purposes, but they are not returned to the UI history hydrate and are not restored into LLM context.

`historyClearedAt` is a nullable `DATETIME` field on the `AgentThreadDataTable` DataShape. If the field is absent or empty on an existing row, treat it as the time origin, meaning the conversation has never been cleared and all Stream rows remain eligible subject to the normal bounds and filters.

`historyClearedAt` is sufficient. The marker and Stream row timestamps are produced by the same server-side platform runtime, and clear/submit paths are serialized by the same conversation lock. No generation counter is used.

A service that reuses `historyClearedAt` as a per-turn cutoff marker (`SetConversationHistoryCutoff`) only advances the marker. It must not move
the effective history floor backward and thereby widen visible or LLM-rehydrated history. Undoing a cutoff is a separate
product feature, not part of the continuity marker semantics.

## 7. ClearConversation semantics

`ClearConversation` is a server-authoritative operation over the thread row:

1. Validate `conversationId`.
2. Read the `AgentThreadDataTable` row and fail if it does not exist.
3. Acquire the same conversation lock used by submit/continuation paths, specifically the existing `AgentThing.parlerConversationLock(conversationId)` helper.
4. If the conversation has active pending HITL approvals, fail with a clear error and make no changes.
5. Set `AgentThreadDataTable.historyClearedAt = now`.
6. Clear `_conversations[conversationId]`.
7. Clear known conversation-scoped cache mirrors:
   - `AgentToolContext.removeLastQualifyingTabularCacheMirrorForConversationId(conversationId)`
8. Log the clear event with agent Thing, conversation id, and timestamp.

The `historyClearedAt` write must validate that the thread row exists and (for correctness) that its `agentName` matches the invoking Agent Thing. It must **not** require that the row's `username` match the caller — caller eligibility is **only** ThingWorx service permissions and deployment configuration (§6).

The service does not delete `AgentMessageStream` rows.

The service does not delete the `AgentThreadDataTable` row either. The row remains the authoritative conversation metadata and the place where history-clear state is stored.

The durable marker write must happen before `_conversations` removal and cache mirror cleanup. If a crash happens after the marker write, the next turn still observes the cleared history boundary. If the operation removed in-memory state before writing the marker, the next turn could incorrectly rehydrate pre-clear rows.

Because clear uses the same lock as submit/continuation, a clear issued while a turn is running may block until that turn finishes. Clear does not use `tryLock`/retry semantics.

## 8. UI and server-side clear behavior

Do not require `ClearConversation` to originate only from UI.

Behavior:

- UI clear button:
  - calls the authoritative server clear service
  - after success, clears local visible transcript state immediately

- non-UI service invocation:
  - calls the same authoritative clear service
  - does not guarantee that an already-open UI tab updates immediately
  - guarantees that the next reconnect/history fetch returns only post-clear rows
  - guarantees that the next model turn does not rehydrate pre-clear transcript

This keeps correctness anchored on server state while allowing the UI path to provide immediate local feedback.

## 9. Trigger point

Rehydrate lazily inside the turn assembly path.

Rehydration is gated by entry point, not by `_conversations` emptiness alone.

Rehydrate-enabled entry points:

- `ParlerGateway.SubmitUserPrompt`
- `AIAgent.ParlerStreamToRemoteThing`

Disabled entry points:

- `Chat`
- `ChatAsync`

For `Chat` / `ChatAsync`, existing in-memory conversation behavior remains unchanged. If `_conversations` is empty, v1 must not consult `AgentMessageStream` to rebuild prior turns for these paths; they continue with a fresh per-turn list.

Recommended integration:

1. `buildLlmTurnContext(...)` calls `resolveConversation(...)`.
2. `resolveConversation(...)` checks `_conversations`.
3. If an in-memory list exists, return it.
4. If no in-memory list exists and the entry point is rehydrate-enabled, call `AgentConversationRehydrator`.
5. If rehydrate returns a list, insert it with `putIfAbsent`.
6. If another thread already populated `_conversations`, use the existing list and discard the rebuilt one.
7. If rehydrate fails, log and continue with a fresh per-turn list, but do not insert an empty list into `_conversations`.

Do not run this during `initializeThing`. Lazy first use avoids lifecycle ordering problems and startup failures.

On rehydrate failure, leaving `_conversations` without an entry for that conversation allows a later turn to retry after a transient Stream or DataTable problem.

Rehydration must run inside the same `parlerConversationLock(conversationId)` critical section as turn assembly. This serializes rehydrate, submit/continuation, and `ClearConversation`; without that lock scope, a clear could arrive between Stream read and `_conversations.putIfAbsent(...)`.

Current callers already hold `parlerConversationLock(conversationId)` before `resolveConversation(...)` runs. `AgentConversationRehydrator` should not add its own `synchronized(parlerConversationLock(...))` block and should not be invoked directly from call sites that do not already hold the lock.

## 10. Query strategy

Use `QueryStreamData` on `AgentMessageStream`.

Recommended query:

- `source = conversationId`
- `oldestFirst = false`
- `maxItems = maxRehydrateMessages`
- `startDate = historyClearedAt` when the marker is present
- no `startDate` when `historyClearedAt` is absent or empty; this is equivalent to time-origin semantics

This asks for the most recent effective rows. The helper should then reverse the returned list into chronological order before reconstructing messages.

`ParlerGateway.GetConversationHistoryJson` should apply the same `historyClearedAt` marker so UI hydrate and LLM rehydrate share the same effective-history boundary.

Stream `startDate` is treated as inclusive, so post-clear reads use `historyClearedAt + 1ms` (`StreamHistoryBounds`); rows at the exact clear timestamp are not returned as effective history.

Rehydration uses `QueryStreamData` because it already returns rows shaped as `AgentMessageData` and matches the existing UI history exporter pattern.

## 11. Row selection

Transcript-first rehydration restores only:

- `role=user`
- `role=assistant` with final assistant text
- accepted **Stage 2** compact `fetch_cached_result` evidence from persisted `role=tool` rows (marker `parler.fetch_cached_result.compact.v1` or legacy structural compact shape with `columns[]`; see `CompactFetchStreamRehydrate` and `docs/agent/context-compaction.md` §9), emitted in-memory as **`ChatMessage.assistant`** with `STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX` + annotated JSON — **not** as `ChatMessage.Role.TOOL` (provider pairing). All other tool rows remain skipped.

Skip:

- `role=system`
- `role=tool` rows that are not accepted compact fetch evidence (raw tool bodies, HITL synthetic outcomes such as `status: skipped` / `code` prefix `HITL`, malformed JSON, PASSWORD column metadata, or oversize `rows` samples)
- `role=ui_feedback`
- `role=context_checkpoint` — never mapped as a transcript row. It is instead handled ahead of this selection
  (§9.2 of `docs/core/advanced-compact.md`): the newest candidate for this `agentThing` is validated, and on
  success its semantic prose plus its own exact retained tail replace the rows up to it, with the rows appended
  after it mapped by the rules here. On any validation failure the whole window is mapped by these rules alone
  and `CONVERSATION_CHECKPOINT_SKIP reason=WATERMARK_UNVERIFIED` is reported
- `role=assistant` rows whose `toolCalls` field is non-empty
- rows with mismatched `agentThing`
- rows before `historyClearedAt`
- rows with unknown roles
- rows with missing required fields

HITL-synthesized tool rows (approve / reject / cancel / expire / interrupted-batch siblings) are persisted to `AgentMessageStream` as normal `role=tool` rows. Stage 2 skips synthetic / non-compact-fetch tool bodies (see `CompactFetchStreamRehydrate#shouldSkipToolRowForStreamRehydrate`); they are never used to reconstruct `PendingApprovalStore` state.

Assistant rows with `toolCalls` are internal intermediate rows. Final assistant text rows are the user-visible completion and are the continuity unit.

`toolCalls` is persisted as a string. The row-level predicate for "has tool calls" is `toolCalls != null && !toolCalls.isEmpty()`. Empty-string `toolCalls` is the explicit persisted signal that the assistant row has no pending tool calls and may be considered final-text-eligible.

If any rows are skipped due to `agentThing` mismatch, log one aggregate warning per rehydrate attempt with counts. Do not log per row.

## 12. Message reconstruction

Mapping:

- user row -> `ChatMessage.user(content)`
- final assistant row -> `ChatMessage.assistant(content)`
- accepted compact `fetch_cached_result` Stream `role=tool` row -> `ChatMessage.assistant(STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX + annotatedJson)` where `annotatedJson` includes `parlerRehydratedCacheHistorical` / `parlerRehydratedCacheLive` when applicable (`CompactFetchStreamRehydrate`)

Use empty string only when the original row legitimately has empty content. Skip null or malformed content when it would produce a confusing transcript.

Do not restore system rows. The current leading system prompt must be freshly composed for every turn from current AgentThing settings and the current prompt-context cache.

## 13. Coherent transcript boundary

After selecting rows:

1. Sort or reverse into oldest-to-newest order.
2. Drop leading assistant or orphan compact-tool rows until the first restored row is a user row — **except** an injected conversation checkpoint, which is deliberately placed at the head as an assistant row and is skipped over, with the leading-assistant strip resuming at the row after it (`docs/core/advanced-compact.md` §9.2 step 6).
3. Keep chronological order for user, assistant, and framed compact-evidence assistant rows where possible.
4. If consecutive assistant rows or consecutive user rows appear, keep them in order but log a low-severity warning; Stream history can contain unusual shapes after interrupted turns. (Two prose assistant rows in a row may include one framed compact-evidence recap.)
5. If the resulting list is empty, treat rehydrate as no-op.

Strict provider tool-call / tool-result pairing is not restored from Stream; compact fetch evidence is prose-only.

## 14. Windowing and size limits

Rehydration must be bounded.

Suggested settings:

- `enableConversationRehydration`, default `true` only for rehydrate-enabled Parler AlwaysOn entry points
- `maxRehydrateMessages`, default `300`
- `maxRehydrateChars`, default `200000`
- `rehydrateWarnOnSkippedRows`, default `true`

Use a simple character budget in v1. Provider-specific tokenizers are not used.

Character counting uses Java `String.length()` UTF-16 code units. This matches the existing `AgentMessageStreamAppender.MAX_CONTENT_CHARS` truncation style and avoids introducing a second counting rule.

Budget application order:

1. Fetch most recent rows from Stream.
2. Reverse or sort to chronological order.
3. Apply coherent transcript boundary rules.
4. If over `maxRehydrateChars`, drop rows from the front until under budget, **protecting** an injected checkpoint at the head. That protection is conditional: when the budget cannot hold the checkpoint plus at least one complete user-led pair — a user row with a later *final assistant* row, which framed compact evidence is not — the checkpoint is dropped explicitly, reported as `CONVERSATION_CHECKPOINT_SKIP reason=CHECKPOINT_CANNOT_FIT`, and its characters returned to the transcript. A history of nothing but a checkpoint is worse than no checkpoint, because the model is told where the work stands with nothing to continue from.
5. Re-apply coherent transcript boundary rules.
6. Ensure the final restored list starts with a user message, or with the injected checkpoint when one survived the two steps above.

Do not enforce a combined provider-token budget in v1. Instead, log approximate component sizes for the restored transcript, current system prompt, stable prompt-context cache, dynamic rows, and current user message so operators can see when history was trimmed.

## 15. Current system prompt

The rehydrated list contains user rows, final assistant rows, optionally additional **assistant** rows carrying framed compact `fetch_cached_result` evidence from Stream (Stage 2), and — when a persisted `context_checkpoint` row validated — one leading **assistant** row carrying the checkpoint's semantic prose. That row is assistant provenance deliberately: it gets no system authority and does not impersonate the user's own words. It does **not** restore assistant `tool_calls` rows from Stream; it does **not** insert orphan `Role.TOOL` rows into restored history.

After rehydration, normal turn assembly must add or replace the leading system row using current runtime state:

- `AgentSettings.systemPrompt` or per-call override
- optional built-in tool routing guide
- stable prompt-context cache snapshot

This preserves current prompt behavior and avoids replaying stale prompt text from old turns.

Do not persist this leading system row to `AgentMessageStream`.

## 16. Cache continuity

Conversation rehydration does not restore in-memory table caches as live `InfoTable` instances.

Stage 2 may reintroduce **accepted** compact `fetch_cached_result` tool JSON (see §11 / §12). Those bodies carry **`parlerRehydratedCacheHistorical: true`** when the `cacheId` is not present in the JVM conversation cache at rehydrate time, or **`parlerRehydratedCacheLive: true`** when it is. The model must treat `cacheId` as historical unless the live marker is present.

Until task-state surfaces those flags more broadly, if the user asks for follow-up work that requires full data, the safer path is to re-run the source query or ask for clarification rather than assuming an old `cacheId` is still valid.

## 17. HITL continuity and clear behavior

Pending approval continuation is not reconstructed from Stream.

Rules:

- if a pending approval is still active in memory, continue using the existing `PendingApprovalRecord` snapshot
- Stream rehydration is for new user turns
- if pending approval state is lost after restart, old pending ids are not valid (there is no persistent pending store)
- do not replay a gated write from Stream history alone

`ClearConversation` must not be blocked by **expired** pendings (TTL elapsed): only **active** pendings block clear, via `PendingApprovalStore.hasPendingForConversationId(...)` (or equivalent).

When the scheduler delivers an **expired** pending result and JVM memory for the thread is empty, `applyParlerApprovalExpiredToConversation` must **not** restore the pre-clear message snapshot if durable `historyClearedAt` is strictly after `PendingApprovalRecord.createdAtEpochMillis`. Otherwise a clear followed by expiry delivery could repopulate `_conversations` and suppress Stream rehydration on the next user turn.

This v1 rule is intentionally simple: clear does not cancel **active** pending approvals, does not synthesize HITL resolution events, and does not attempt to rebuild approvals from Stream rows.

## 18. Failure behavior

Rehydration failure should not make AgentThing unusable.

If Stream query, DataTable lookup, or parsing fails:

- log `WARN` with conversation id, agent Thing, compact reason, rows considered, rows accepted, and skip counts where available
- do not throw from the user turn
- do not insert an empty list into `_conversations`
- continue with a fresh per-turn list
- let the model ask for clarification if the new user message depends on missing prior context

Do not inject a dynamic system note saying prior context was unavailable. That kind of note changes model behavior and is hard to test; logs and telemetry are the better first-version observability path.

## 19. Protection and replay boundaries

Rehydration is LLM context construction, so it should replay only rows that are valid to put back into the model prompt.

Rules:

- read only persisted `AgentMessageStream` rows
- never read live platform property values during rehydration
- never restore system rows
- never restore **raw** tool rows; restore accepted Stage 2 compact `fetch_cached_result` evidence **only** as framed **assistant** prose (`CompactFetchStreamRehydrate` + `AgentConversationRehydrator`), never as orphan `Role.TOOL`
- skip malformed rows rather than guessing
- respect `docs/agent/protection.md` exactly
- do not broaden PASSWORD protection boundaries

Rows whose `toolCalls` or **non-accepted** `tool` content predate the persistence-redaction lifecycle are not restored. Accepted compact fetch tool rows use the same PASSWORD column rejection as live compaction. Other historical tool shapes are not rehydrated.

If a row contains data that should have been sanitized but was not, rehydration should not try to repair it by reading metadata. It should either skip the row or rely on the same observability redaction helpers used at persistence boundaries.

## 20. Implementation pieces

Classes and helpers:

- `AgentConversationRehydrator`
- `AgentMessageStreamReader`
- `ConversationRehydrateSettings`
- `AgentThreadDataTableSupport.loadConversationMetadata(...)` / `loadConversationMetadataForCurrentUser(...)`
- `AgentThreadDataTableSupport.markHistoryCleared(...)`
- `PendingApprovalStore.hasPendingForConversationId(...)`
- `AgentMessageStreamHistoryExporter.exportHistoryJsonString(..., historyClearedAtOrNull)`

`ConversationRehydrateSettings` is resolved per entry point. It enables Stream rehydration only for `ParlerGateway.SubmitUserPrompt` and `AIAgent.ParlerStreamToRemoteThing`; `Chat` and `ChatAsync` must pass disabled settings.

Rehydrate helper:

```java
Optional<Rehydrated> AgentConversationRehydrator.rehydrateTranscript(
        String conversationId,
        String agentThingName,
        ConversationMetadata metadata,
        ConversationRehydrateSettings settings,
        Logger log)
```

`Rehydrated` carries the rebuilt `List<ChatMessage>` **and** the working-checkpoint reconciliation that belongs
with it. The caller applies that reconciliation only for the list that wins `_conversations.putIfAbsent`, because a
losing list must not decide JVM working state; every route that publishes no rehydrated list instead reaches one
clearing exit, so a fresh thread never inherits a previous instance's checkpoint
(`docs/core/advanced-compact.md` §9.2).

The helper returns user/final-assistant messages, optionally led by one injected conversation-checkpoint assistant
row when a persisted candidate validated. It never returns system rows: `AgentThing` remains responsible for
applying the current leading system row and per-turn dynamic context.

`AgentThing.ClearConversation` performs, under `parlerConversationLock(conversationId)`, the pending-HITL failure (no side effects), the `historyClearedAt` update, in-memory conversation removal, checkpoint working-set and host-context carry cleanup, and cache mirror cleanup as one authoritative operation.

Clear does not implement its own caller-authorization model. ThingWorx service permissions and deployment configuration decide who may call it.

## 21. Tests

Unit-level tests:

- user + final assistant rows rebuild into `ChatMessage` list
- assistant tool-call rows are skipped
- tool rows are skipped
- system rows are skipped
- mismatched `agentThing` rows are skipped and counted
- rows before `historyClearedAt` are skipped
- restored list starts with a user row, or with the injected checkpoint when one validated and survived shaping
- a rejected, budget-dropped, or absent checkpoint leaves no working-checkpoint state behind for the next turn
- over-budget history drops oldest rows and re-validates boundary
- malformed rows do not throw
- rehydrate failure does not insert an empty `_conversations` entry
