# Context compaction

Status: **implemented** (`ContextBudgetPlanner`, Tier B promotion, storage trim, compact fetch rehydrate, always-on replay gate). §16 lists each component under the slice label (A–G) that code comments cite.

Scope: Parler Java agent LLM context assembly, in-memory conversation replay, Stream rehydration, tool-result evidence lanes, and provider request budgeting.

## 1. Problem

Parler now has several partial context controls:

- Tier A / Tier 0 replay shaping and Tier B eligibility are always-on for normal AgentThings (Slice F); only the JVM diagnostic bypass can disable them. Tier B mutation itself is pressure-gated at the post-turn storage boundary (§8), so an ordinary within-cap turn does not rewrite already-sent history.
- `fetch_cached_result` has a compact LLM lane for large pages, but that only covers one sharp table path.
- Stream rehydration restores a bounded transcript after memory loss, but it intentionally skips historical tool rows.
- Provider token and rate telemetry exists, but there is no single request planner that decides what must fit before each LLM call.

These pieces reduce some failures, but they do not create a comprehensive guarantee. A normal AgentThing can still carry an unbounded `_conversations` list with historic tool calls, tool results, assistant text, and per-turn context growth until the provider rejects a request or quality degrades.

The product goal is stronger:

> Every outbound LLM request must be assembled through a bounded, deterministic context plan.

Compaction should be a normal runtime invariant, not an optional experiment.

## 2. Goals

- Make LLM context bounded for every Parler turn and every provider.
- Keep UI, audit, export, and LLM evidence lanes separate.
- Preserve provider-required message ordering and assistant-tool-call / tool-result pairing.
- Preserve enough evidence for grounded answers, follow-up routing, and cache-aware recovery.
- Keep stable prompt prefixes cacheable.
- Rehydrate after restart from bounded transcript and compact evidence, not raw historic payloads.
- Make trimming and promotion observable in Application Log and `done.llm_usage` where already supported.
- ~~Remove `enableLlmReplayCompaction` as a normal product setting after migration.~~ **Done (Slice F).**

## 3. Non-goals

- No lossy compression of the visible UI transcript.
- No physical deletion of `AgentMessageStream` rows.
- No hidden LLM summarization of old user data in v1 — **superseded in part by `advanced-compact` (shipped).**
  This remains true of everything in this document: Tier A/0/B promotion, the storage-budget trim, and per-round
  planner trimming are deterministic drop/promote operations that never send old user data to a model to be
  rewritten, and that is still the baseline. `advanced-compact` adds one narrowly bounded exception: after a
  successful turn, when a deterministic trim is about to delete a transcript row, the server may make **one**
  tool-free summary call over the covered historical prefix using the turn's own client, and persist only the
  validated semantic output as a non-authoritative navigation aid. It is bounded, never silent (`advanced-compact` §12
  telemetry), never a chat availability gate (`advanced-compact` §8.4), and never evidence. See
  `docs/core/advanced-compact.md`.
- No provider-specific retry or failover behavior in this topic.
- No arbitrary SQL or script execution over cached tables.
- No change to PASSWORD or protected-value boundaries.
- No version-coordinate bump unless the User explicitly commands a version cut.

## 4. Design principle

Use separate lanes:

| Lane | Consumer | Payload rule |
| --- | --- | --- |
| Stable prompt lane | Provider prompt cache and LLM | Current system prompt, stable routing guide, prompt-context snapshot. Kept as the leading system row and refreshed each turn. |
| Ephemeral turn lane | LLM only | Time anchor, host context meta, task state, slash skill blocks. Inserted for the API round or current turn and removed afterward. |
| LLM evidence lane | LLM replay | Compact tool evidence only: matrix, summary, cache pointer, small sample, error shell, or cohort pointer. |
| UI data lane | Parler wire/UI | Rows and chart/table blocks needed for live rendering, subject to existing UI/export limits. |
| Audit lane | `AgentMessageStream` | Durable stream rows. Today most non-`fetch_cached_result` tool rows are persisted in raw object-row shape because table/chart/export consumers read that shape before compaction. `fetch_cached_result` already persists compact LLM content plus export sidecars for large pages. Other tools do not persist compact audit rows; this design does not assume them. |

The LLM evidence lane is the only lane that may enter `_conversations` and later provider requests. UI and audit lanes must not force LLM replay to carry row-level data.

## 5. Target architecture

Introduce a single request assembly boundary:

```text
AgentThing turn setup
  -> build leading stable system row
  -> inject ephemeral rows
  -> append current user row
  -> ContextBudgetPlanner.plan(messages, tools, providerBudget)
  -> llmClient.chat(plannedRequest)
```

`ContextBudgetPlanner` is the owner of outbound request size policy. It should be deterministic and side-effect-free except for structured telemetry.

The planner does not execute tools and does not rewrite UI/audit data. It operates only on the model-facing message list and tool definitions for the next request.

Integration point: `ContextBudgetPlanner.plan(messages, tools, providerBudget)` runs inside `AgentLoop.run` immediately before each per-round `llmClient.chat(...)` invocation. It runs once per LLM round; in a multi-round tool loop it runs once for each provider call in that turn.

The planner returns a fresh request payload, for example `PlannedRequest{messages, tools}`, for one provider call. The input `messages` list and its existing `ChatMessage` instances MUST NOT be mutated by per-round planning. Per-round trimming affects only the outbound request body. The live `_conversations` list is mutated only by existing round-boundary/Tier B compaction and by the post-turn normalization pass described in §8.

## 6. Always-on replay compaction

The retired `enableLlmReplayCompaction` AgentSetting is removed from the product configuration surface (Slice F). Compaction is on unless the unsafe diagnostic JVM/system property disables it.

The end state:

- Tier A matrix sealing is always applied after completed tool-result batches.
- Tier 0 cohort merge is always applied where semantics allow.
- Tier B post-turn promotion remains always eligible after successful final answers but is applied only when the pre-normalization replay exceeds the existing `_conversations` storage cap.
- `LLM_USAGE rawReplayChars/replayChars/compactRatio` remains emitted.
- Existing tests that rely on raw replay should opt into a test-only bypass rather than use product config.

The emergency bypass MUST NOT be a persisted AgentSetting. A persisted setting recreates the product-config drift that this topic is removing. If retained, the bypass should be a JVM/system property, for example `-Dcom.thingworx.parler.llmReplayCompaction.disableUnsafe=true`, read at process start, default `false`, excluded from product documentation, and logged in every `LLM_USAGE` / `LLM_CONTEXT_PLAN` line while active.

## 7. Planner budgets

V1 should use deterministic character budgets plus provider-reported token telemetry. Exact tokenizers are not required for the first planner, but every request must expose its component sizes.

Recommended initial caps:

| Component | V1 policy |
| --- | --- |
| Stable system row | No truncation in planner; failures here are configuration errors. |
| Ephemeral rows | Bounded by each source's existing caps. |
| Current user message | No truncation in planner. |
| Recent transcript | Keep newest coherent user/assistant pairs after the last history-clear marker. |
| LLM evidence summaries | Keep compact summaries newest-first within evidence budget. |
| Tool schemas | Not trimmed by the planner; the advertised set is decided by tool admission (`toolAdmissionMode`). |
| Total model-facing request | Apply `effectiveRequestCapChars` to the assembled outbound request (Slice C enforcement). |

The planner must never drop the current user message, the leading stable system row, or required provider tool-pairing rows for an active tool batch.

If the planner cannot produce a provider-safe request without dropping required rows, it should fail before the provider call with a structured internal error and log the component breakdown.

Slice A is telemetry-only and should not enforce a hard cap. Slice C introduces enforcement. Its first cap should be derived per provider rather than as one global literal:

```text
effectiveRequestCapChars = min(providerInputTokenLimit * 3.5, llmContextMaxChars, providerRateSingleRequestInputCapChars)
historyBudgetChars = effectiveRequestCapChars
    - stableSystemChars
    - toolSchemaChars
    - ephemeralSystemChars
    - currentUserChars
    - activeBatchReserveChars
    - checkpointChars
```

`llmContextMaxChars` should be a single AgentSetting, default `750000`, that caps the total model-facing request. The default is deliberately high enough that the provider-derived term constrains common 128k-token providers, while still blocking pathological growth on larger windows. `LLM_CONTEXT_PLAN` must emit the configured cap, effective request cap, stable-prefix/tool/ephemeral/current-user/active-batch/checkpoint overhead, and derived history budget. The `3.5` multiplier is a conservative v1 approximation, not a tokenizer.

This cap protects against context-window overflow, unbounded replay growth, and provider rate-control single-request reservation overflow when the active `LlmClient` can expose a rate-control-derived `providerRateSingleRequestInputCapChars`. The rate-control term is computed from the resolved provider max-output setting, token reserve strategy, estimate safety multiplier, `maxSingleRequestTokens` / TPM fallback, and a fixed safety margin when rate-control mode is `enforce`. If the provider cannot expose the term, or rate-control is `disabled` / `observe`, the planner falls back to the model/config caps only.

Formula terms:

- `providerInputTokenLimit` comes from the resolved provider/model for the turn. For v1, this is a static code-owned registry keyed by normalized provider and model name, for example entries for common Anthropic and OpenAI/Azure model ids. It is not a new AgentSetting, and the planner must not add a live provider metadata call to discover it. If no provider/model input limit is known, the provider term is omitted and `effectiveRequestCapChars = llmContextMaxChars`.
- `providerRateSingleRequestInputCapChars` is optional and comes from Provider-backed `LlmClient` implementations before each provider round when rate-control mode is `enforce`. It is not a new AgentSetting. It prevents a prompt that fits the model context window from reaching `LLMAPIProviderRateGate` with a `reservedTokens` value larger than the local single-request budget.
- `stableSystemChars` is the leading stable system row content length in the outbound request copy.
- `toolSchemaChars` is the serialized model-facing tool-definition character estimate for the provider request.
- `ephemeralSystemChars` is the sum of per-turn and per-round dynamic system rows in the outbound request copy.
- `currentUserChars` is the length of the most recent user message for the current submitted turn and is reserved on every provider round in that turn, including later tool-loop rounds.
- `activeBatchReserveChars` is the sum of non-droppable active assistant tool-call / tool-result batch rows already present for the current in-flight batch before planner trim runs. It is `0` when no active batch is in progress. On the first LLM round of a turn it is normally `0`; on later tool-loop rounds it covers the current in-flight batch only, not older retained tool batches.
- `checkpointChars` is the model-facing size of the **newest** injected conversation checkpoint in the outbound request copy (`docs/core/advanced-compact.md` §10.1). It is `0` when the conversation has no checkpoint, when the planner omits one that does not fit, and when the provider's request contract cannot carry it at all (`advanced-compact` §9.2) — the value always describes the request actually sent. It is derived from the message list under consideration rather than passed in, so every nested recomputation stays self-consistent with the subset it was handed. Older markers, if any are ever present, are cleared before this term is computed.

`historyBudgetChars` is the actual computed value and may be negative. The internal trim budget is `max(0, historyBudgetChars)`. When `historyBudgetChars <= 0`, the planner MUST trim transcript and evidence to zero retained historical rows beyond the current user message and active batch. `LLM_CONTEXT_PLAN.historyBudgetChars` MUST still emit the actual computed value; `historyClampedToZero` MUST be `1` only when the computed value is negative and the internal trim budget was clamped to zero. If the non-history components alone exceed `effectiveRequestCapChars`, the planner MUST first attempt the `docs/core/advanced-compact.md` §10.1 recovery when an injected checkpoint is present — clear that row's `keep` bit over the original indices, recompute metrics over the materialized candidate, and report `CONVERSATION_CHECKPOINT_SKIP reason=CHECKPOINT_CANNOT_FIT` — because a non-authoritative continuity enhancement must never turn a serviceable request into a hard failure. Only if the budget is still negative once the checkpoint is out does the planner fail closed before the provider call with a structured internal error and component breakdown.

The v1 provider/model registry should start with at least these limits and aliases. The implementation may add exact dated snapshot ids used by deployments in the same families. Unknown names still fall back to `llmContextMaxChars`.

| Provider | Normalized model ids / aliases | Input token limit |
| --- | --- | --- |
| Anthropic | `claude-opus-4*`, `claude-sonnet-4*`, `claude-haiku-4*`, `claude-3-7-sonnet*`, `claude-3-5-haiku*` | `200000` |
| OpenAI / Azure OpenAI | `gpt-5.2`, `gpt-5.1`, `gpt-5`, `gpt-5-mini`, `gpt-5-nano` | `400000` |
| OpenAI / Azure OpenAI | `gpt-4.1`, `gpt-4.1-mini`, `gpt-4.1-nano` | `1047576` |
| OpenAI / Azure OpenAI | `gpt-4o`, `gpt-4o-mini`, `gpt-4-turbo` | `128000` |

Registry lookup normalizes provider and model ids to lowercase and strips deployment-specific Azure suffixes before lookup. Claude Sonnet 4 1M-context beta behavior is not enabled in v1; if needed, it requires a later provider-budget override design.

## 8. Conversation memory policy

`_conversations` should stop being a raw append-only transcript.

After each successful turn, store a normalized replay state:

1. Leading stable system row, refreshed on the next turn.
2. Recent user/final-assistant transcript rows.
3. Provider-required assistant tool-call / tool-result pairs still needed for immediate continuity.
4. Compact evidence summaries for prior completed tool work.
5. No per-turn ephemeral system rows.
6. No large raw table/page payloads.

When the post-turn conversation exceeds the `_conversations` storage budget before historic rows are mutated, normalize in this order:

1. Promote every eligible old evidence body to summary-only form in one Tier B pass.
2. Recompute the storage-boundary dry run on the promoted list and install a checkpoint only when transcript rows would otherwise be removed.
3. Drop oldest historic evidence batches still required to satisfy the cap.
4. Drop oldest transcript pairs, preserving a coherent boundary that starts with a user row.

If the pre-normalization replay is already at or below the cap, skip Tier B as well as the no-op storage trim. This keeps stored replay byte-identical except for appends between bounded-memory epochs, preserving provider prompt-cache prefixes. Crossing the cap is one intentional cache epoch: Tier B, optional checkpoint replacement, and any remaining deterministic trim occur in the same post-turn normalization before `_conversations.put`.

The post-turn normalization operation may mutate `_conversations`; it must be deterministic and log counts by category. The per-round planner is different: it is drop-only for outbound copies and MUST NOT perform Tier-B-like promotion or mutate the live message list.

Post-turn storage order MUST be fixed:

1. `applySkillTurnMutationFinish` strips ephemeral system rows.
2. Append the final assistant message.
3. In `compaction/ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore`, compare the unmodified list with the same `llmContextMaxChars` cap used by the storage trimmer.
4. Only when that list exceeds the cap, apply Tier B once via `applyTierBReplayPromotionBeforeStore`; it still defers when `PendingApprovalStore.hasPendingForConversationId` is true and preserves the existing success/compaction gates.
5. Recompute the checkpoint boundary on the post-Tier-B list and optionally install/persist a checkpoint when the resulting deterministic trim would remove transcript.
6. Apply post-turn in-memory storage trim via `compaction/ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget` (v1: historic evidence batches before last `USER`, then historic `USER`+prose `ASSISTANT` pairs; same `llmContextMaxChars` cap as configured outbound ceiling; skips when active pending; compaction-gated; `INFO` `CONVERSATIONS_STORAGE_TRIM` on drops; `WARN` if still over cap — no throw).
7. Store the normalized list with `_conversations.put(conversationId, messages)`.

The post-turn normalization pass must be idempotent. Running it again on an already normalized list must produce a byte-equivalent message list and no new promotions.

Active HITL pending records require one extra invariant. When post-turn normalization would run on a conversation that has an active `PendingApprovalRecord` (`PendingApprovalStore.hasPendingForConversationId`), normalization is skipped for that conversation until the pending is resolved by approval, rejection, cancellation, or expiry. The pending record is never re-snapshotted to a normalized shape.

Skipping post-turn normalization does not relax bounded-request guarantees. The planner's per-round drop-only trim still bounds every outbound provider request while storage normalization is deferred. Silent drop of an expired-pending tool result is not acceptable. A later expiry delivery must still append the synthetic expired tool result for the gated call id and any interrupted sibling tool calls in original order, and the resulting `_conversations` list must not contain orphaned assistant tool calls.

## 9. Stream rehydration policy

Current rehydration is transcript-first with **Stage 2 compact `fetch_cached_result` tool evidence** layered on (`AgentConversationRehydrator` + `CompactFetchStreamRehydrate`): user rows, final assistant rows, and accepted compact tool rows; other tool rows remain skipped.

The comprehensive design extends rehydration in two stages:

### Stage 1: bounded transcript rehydration

Keep the current behavior:

- verify `AgentThreadDataTable` row and agent binding
- respect `historyClearedAt`
- query `AgentMessageStream`
- restore user/final-assistant text (unchanged)
- apply message and character caps
- refresh the leading stable system row after rehydrate

### Stage 2: compact evidence rehydration

Implemented (`CompactFetchStreamRehydrate`, `AgentConversationRehydrator`): replay-only acceptance for persisted compact tool JSON. **Provider safety:** accepted compact `fetch_cached_result` evidence is restored as **`ChatMessage.assistant`** prose prefixed by `CompactFetchStreamRehydrate.STAGE2_REHYDRATED_FETCH_EVIDENCE_PREFIX`, wrapping the annotated JSON — **not** as `ChatMessage.Role.TOOL`, so Anthropic/OpenAI serializers never emit orphan `tool_result` / `tool` rows (§11 pairing invariant).

- Accept only known compact formats. In the current implementation, the only broadly available compact Stream evidence is compact `fetch_cached_result` sample payloads and error shells. `parler.infotable.summary.v1` exists in in-memory Tier B replay, but it is not generally written back to `AgentMessageStream` today.
- Newly persisted compact `fetch_cached_result` bodies carry `"$format": "parler.fetch_cached_result.compact.v1"`. The Stage 2 reader MUST accept that marker as the positive signal for compact cached-page evidence.
- For legacy Stream rows written before the marker exists, the Stage 2 reader MAY accept the structural compact-fetch shape: `status="success"`, non-empty `cacheId`, boolean `sampleOnly`, boolean `rowsOmitted`, `columns[]`, and capped `rows[]`. It MUST reject raw object-row fetch bodies that do not carry either the marker or the compact structural shape.
- Rejected raw tool rows are skipped, not treated as fatal rehydrate errors.
- Skip raw tool rows and assistant tool-call rows by default.
- Treat `cacheId` as historical unless the current JVM cache lookup confirms it is live (`InvokeServiceExecutor.lookupCachedInfotableForConversation`); annotate JSON with `parlerRehydratedCacheHistorical` / `parlerRehydratedCacheLive` per `large-table-replay-control.md` §4.2.
- Never reconstruct pending HITL approvals from Stream alone.
- HITL-synthesized tool rows (approve / reject / cancel / expire / interrupted-batch siblings) are persisted to `AgentMessageStream` as normal `role=tool` rows; Stage 2 skips synthetic / non-evidence tool bodies (e.g. `status: skipped`, `code` prefix `HITL`) rather than treating them as compact fetch evidence.
- Never rehydrate raw PASSWORD/protected columns.

Stage 1 restores visible transcript continuity after restart. Stage 2 covers compact `fetch_cached_result` evidence only; other tools do not write compact summaries to Stream.

The compact-fetch marker is a top-level JSON field alongside `status` and `cacheId`, matching the existing top-level `$format` convention for matrix, cohort, and summary replay formats. The marker is an additive compact-payload schema field documented in `large-table-replay-control.md` §4.2.

## 10. Large tables and cached results

The existing split for `fetch_cached_result` should become the general table rule:

- UI may receive display rows, sampled rows, chart/table metadata, and export sidecars.
- LLM replay receives only compact evidence: page metadata, columns, total counts, small sample, `sampleOnly`, `rowsOmitted`, and a hint to use deterministic cached-table tools.
- Stream persistence stores the compact LLM content plus export sidecar metadata when needed.
- Repeated page fetches in the same user turn force compact LLM content even if a later page is small.

No tool should encourage the model to page all rows into memory for computation. Full-table work must use deterministic cached-table operations such as `tabulate_cached_result`, `summarize_cached_result`, and approved filter/ranking modes.

## 11. Tool-call pairing and provider safety

Compaction must preserve provider protocol rules:

- Do not remove an assistant tool-call row unless all paired tool-result rows are also removed or summarized in a provider-accepted way.
- Do not compact an incomplete active batch while awaiting HITL approval.
- Do not rewrite current-batch tool result bodies before raw consumers have run: task-state evidence merge, table/chart wire, cache mirrors, stream append, and logs.
- Do not mutate ephemeral API-round rows.
- Preserve tool call ids for any retained provider-paired rows.

Pairing invariant:

- Every retained assistant message with non-empty `tool_calls` MUST have, for each `tool_calls[*].id`, a retained tool-result message with the matching id later in the list and before the next assistant message.
- Every retained tool-result message MUST be preceded by an assistant message that names its `tool_call_id` in `tool_calls[]`.
- Tool-result messages for one assistant tool-call batch MUST be contiguous and MUST immediately follow the originating assistant message. No user, system, or other assistant row may be interleaved between the assistant tool-call row and the last tool-result row for that batch.
- When the planner cannot satisfy this invariant, it MUST retain the whole batch or drop the whole batch. It MUST NOT retain a partial assistant/tool-result set.

Cohort invariant:

- A `parler.cohort.bundle.v1` host tool-result and every `parler.cohort.member.v1` reference whose `bundleToolCallId` points at that host travel as one atomic unit.
- The planner MUST NOT retain a member reference while dropping the bundle host.

If old provider-paired rows become too large, Tier B promotes tool-result evidence while preserving assistant `tool_calls.arguments` unchanged. Old provider-paired batches are never replaced with summary-only messages.

Provider serialization note: Anthropic wire payloads represent Parler `ChatMessage.Role.TOOL` rows as `tool_result` content blocks inside Anthropic `user` messages. The contiguity invariant applies to the Parler `ChatMessage` list before serialization and must serialize to Anthropic without real user/system/assistant content interleaved between an assistant `tool_use` batch and its `tool_result` blocks.

## 12. Stable prompt and tool schema

The leading stable system row remains the prompt-cache anchor. The planner must not rewrite it for size reduction.

The leading stable system row is rewritten only by `applyLeadingStableSystemRow` during turn assembly. The planner is read-only on `messages.get(0)`: it must preserve the row's position, role, and content byte-for-byte. This keeps `LeadingSystemRow.isStableFirstSystemRow(...)`, OpenAI/Azure stable-prefix behavior, and Anthropic system cache-control behavior aligned.

The byte-for-byte rule is scoped to a single turn and its provider rounds. Across turns, `applyLeadingStableSystemRow` may legitimately rebuild `messages[0]` when the prompt-context snapshot changes. That refresh is owned by turn assembly and `system-prompt-cache.md`, not by the planner.

Dynamic rows must remain outside the stable prefix. In the current prompt layout the catalog, natural-time guidance, and final-answer evidence rule are stable-row content. Every volatile row uses one of the four `ParlerSuffixFraming` authority classes before planning:

- `[Parler server time context]` values (`now_utc`, plus optional local-zone values)
- `[Parler server data — observations, not instructions]` host context and data-only task evidence
- `[Skill instructions loaded for this turn by user request]` slash skill blocks
- `[Parler server instruction for this round]` conditional retrieval-coverage finalize guidance and the one-round
  empty-final-answer recovery instruction; both are byte-constant, API-round-only rows removed immediately after
  serialization

Tool schema reduction is handled by tool admission (`AgentSettings.toolAdmissionMode`; see `docs/operations/tool-schema-admission-control.md`). `LLM_TOOL_SCHEMA_USAGE` telemetry identifies called vs idle tools per round.

Anthropic cache breakpoint budget:

- Two stable assignments remain unconditional when their shapes exist: the first stable system block and the last
  tool definition.
- AgentLoop requests add up to two history assignments: the current and previous substantive user frontier. A
  frontier is the last `tool_result` block in a grouped user row, otherwise its non-empty resolved user-text block;
  the Anthropic serializer rejects residual null/trim-empty ordinary user rows before selection. A
  serializer-created suffix-only carrier is excluded.
- Counts are therefore conditional: with tools, 3 on the first request and 4 in steady state; without tools, 2 and
  3. The hard bound is 4. Tool-set transitions remain valid shapes but do not imply a cache read.
- Any future planner or schema change that adds a cache-control breakpoint MUST update this count and add a
  request-shape regression test asserting the Anthropic request has no more than 4 breakpoints.

## 13. Observability

Every LLM call should log:

- provider/model/deployment
- message count
- tool definition count
- stable system chars
- ephemeral system chars
- transcript chars
- evidence chars before compaction
- evidence chars after compaction
- dropped transcript rows
- dropped evidence rows
- promoted evidence rows
- estimated total request chars
- configured and effective context caps
- stable-prefix/tool/ephemeral/current-user/active-batch/checkpoint overhead
- derived history budget
- unsafe diagnostic compaction-disable flag when active
- provider token usage when available

Existing `LLM_USAGE` fields should be preserved. Planner-specific telemetry can use a new prefix:

```text
LLM_CONTEXT_PLAN providerThingName=... provider=... model=... conversationId=... requestId=... messages=... tools=... stableChars=... toolSchemaChars=... ephemeralChars=... currentUserChars=... activeBatchReserveChars=... transcriptChars=... evidenceRawChars=... evidenceChars=... droppedTranscript=... droppedEvidence=... droppedAssistantBatches=... configuredCapChars=... effectiveRequestCapChars=... historyBudgetChars=... historyClampedToZero=0|1 unsafeDisable=0|1 checkpointChars=...
```

When the planner fails closed before a provider call (non-history overhead exceeds the effective cap, or drop-only trimming cannot fit), it MUST emit one **`LLM_CONTEXT_PLAN_FAIL`** line at **`ERROR`** with the same field tail as **`LLM_CONTEXT_PLAN`** plus a stable operator filter:

```text
LLM_CONTEXT_PLAN_FAIL reason=OVERHEAD_EXCEEDS_CAP|CANNOT_FIT_AFTER_TRIM providerThingName=... (same fields as LLM_CONTEXT_PLAN)
```

For **`reason=CANNOT_FIT_AFTER_TRIM`**, `droppedTranscript`, `droppedEvidence`, and `droppedAssistantBatches` MUST reflect **cumulative** rows removed during the attempted trim pass (not the all-zero defaults from a bare `Metrics.compute` on the partial outbound alone). `messages=` remains the full pre-trim conversation length for correlation.

Field names are fixed for v1; a new component is **appended** after the existing tail rather than inserted among it, so downstream parsing stays positionally stable. `checkpointChars` (appended by `docs/core/advanced-compact.md` §10.1) is the injected working checkpoint's characters, counted as fixed non-history overhead: it is subtracted from `historyBudgetChars` and excluded from `transcriptChars`, and it is `0` for every conversation without a checkpoint. A checkpoint omitted from a request because the fixed overhead leaves no room reports `checkpointChars=0` — the metrics describe the request that was actually sent — and emits a separate `CONVERSATION_CHECKPOINT_SKIP reason=CHECKPOINT_CANNOT_FIT`. `activeBatchReserveChars`, `configuredCapChars`, `effectiveRequestCapChars`, and `historyBudgetChars` are integer character counts with no units or separators. `historyClampedToZero` and `unsafeDisable` are always emitted as `0` or `1`, not omitted when false, so log parsers do not need absence-vs-zero handling. `conversationId` matches between `LLM_CONTEXT_PLAN` and `LLM_USAGE`. `LLM_CONTEXT_PLAN.requestId` is the Parler turn request id (`AgentToolContext.getParlerRequestId()`), stable across all provider rounds of one user turn; it correlates with `LLM_USAGE.parlerRequestId` (added for this join), **not** with `LLM_USAGE.requestId`, which carries the per-round provider HTTP response id when present.

Assistant `AgentMessageStream` rows that persist `llmUsageJson` include the same `parlerRequestId` string (from `AgentToolContext.getParlerRequestId()` at append time, empty when unknown) so stream-only joins align with `LLM_USAGE` / `LLM_CONTEXT_PLAN` without requiring Application Log ingestion.

When `requestId` is unavailable, such as `Chat` / `ChatAsync` paths that use a per-turn nonce instead of a Parler request id, `LLM_CONTEXT_PLAN` MUST emit the same fallback value that `LLM_USAGE` uses for the Parler turn id field (`LLM_CONTEXT_PLAN.requestId` and `LLM_USAGE.parlerRequestId`); `LlmUsageTelemetry` is the canonical implementation reference for that fallback. It must not omit `requestId` on the planner line or `parlerRequestId` on the usage line (empty string when unknown).

Planner telemetry semantics:

- `activeBatchReserveChars` is the non-droppable active assistant tool-call / tool-result batch reserve used in the budget formula for this provider call.
- `evidenceRawChars` is the character size of model-facing evidence rows in the outbound request copy before per-round drop-only trimming.
- `evidenceChars` is the character size of those evidence rows after per-round drop-only trimming.
- These evidence fields are scoped to one provider call. They are not the same scope as `LLM_USAGE.rawReplayChars` / `replayChars`, which describe the most recent replay compaction batch.
- `historyBudgetChars` is the actual computed value before internal clamping and may be negative.
- `historyClampedToZero` is `1` only when `historyBudgetChars < 0` and the planner used an internal retained-history budget of zero; exact zero emits `historyBudgetChars=0 historyClampedToZero=0`.
- `droppedTranscript` counts dropped user messages and assistant-text messages only. Assistant messages with non-empty `tool_calls` are excluded.
- `droppedEvidence` counts dropped tool-result, compact-summary, cohort-bundle, and cohort-member evidence messages.
- `droppedAssistantBatches` counts fully dropped provider-paired assistant tool-call batches. An assistant tool-call message dropped as part of an atomic batch increments only `droppedAssistantBatches`, not `droppedTranscript`. Dropping one batch also increments `droppedEvidence` by the number of paired tool-result/evidence rows removed.
- **Row vs char partition:** total dropped provider-paired evidence **rows** on one line is `droppedEvidence` (historic **`TOOL`** rows removed) plus `droppedAssistantBatches` (one per fully dropped assistant **`tool_calls`** header). **`evidenceRawChars`** / **`evidenceChars`** include both **`TOOL`** rows and historic assistant **`tool_calls`** rows (outside the active batch), so char buckets and row counters are intentionally different views of the same traffic.

`promotedEvidence` is intentionally not part of the v1 `LLM_CONTEXT_PLAN` line. The planner is drop-only; promotion remains covered by existing replay compaction / Tier B telemetry unless a later design adds a separate planner-correlated promotion scope.

Telemetry bucket classification must be reproducible. Row sizes are model-facing character estimates for the row as it will be serialized for the provider; for plain text rows this is the message content length, and for tool-call/tool-result rows it includes the provider-visible tool-call ids, arguments, and result payload fields used by the provider serializer.

| Bucket | Rule |
| --- | --- |
| `stableChars` | Size of `messages[0]` when it is the leading stable system row; otherwise `0`. |
| `ephemeralChars` | Sum of subsequent `SYSTEM` rows that are not the leading stable system row and are stripped after the turn. |
| `toolSchemaChars` | Serialized model-facing `tools[]` definition size for the active provider request body. |
| `currentUserChars` | Size of the last `USER` role message at planner invocation time. |
| `activeBatchReserveChars` | Size of the in-flight assistant tool-call row plus already-appended tool-result rows for the batch in progress this round. `0` on the first round of a turn or when no assistant batch is being processed. |
| `checkpointChars` | Size of the newest injected conversation checkpoint row (`ConversationCheckpointCodec.isInjectedCheckpoint`). `0` when absent, when the planner omits it for budget, or when the provider cannot carry it (`advanced-compact` §9.2). Fixed non-history overhead, never a transcript pair or an evidence batch. |
| `transcriptChars` | Sum of historical `USER` rows excluding the current user plus `ASSISTANT` rows with empty `tool_calls`, **excluding** any injected checkpoint row — it is counted once, in `checkpointChars`. |
| `evidenceRawChars` | Sum of historical `TOOL` rows plus historical `ASSISTANT` rows with non-empty `tool_calls`, excluding the active batch, before per-round drop-only trimming. |

The private lifecycle predicate is `isFramedEphemeralSystem` and delegates to the shared four-class classifier. It feeds both ephemeral-character accounting and active assistant/tool-batch scanning, so it deliberately recognizes turn-level slash/host rows as well as round-level evidence/coverage/time rows. This changes no planner indices, protection policy, or drop order; explicit insertion indices remain in original-list coordinates, preserving the `advanced-compact` checkpoint omission invariant.
| `evidenceChars` | Same evidence bucket after per-round drop-only trimming. Equals `evidenceRawChars` in Slice A. |

When a provider call throws before `LLM_USAGE`, preserve `LLM_REPLAY_PENDING_COMPACTION` so failure logs still show the last compaction outcome.

## 14. ClearConversation semantics

`ClearConversation` remains marker-based:

- write `AgentThreadDataTable.historyClearedAt`
- clear `_conversations[conversationId]`
- clear known conversation-scoped cache mirrors
- do not delete `AgentMessageStream` rows
- block only active pending HITL approvals

Both UI history hydrate and LLM rehydrate must respect the marker.

Rows before the marker may remain in audit storage but must not enter model-facing context. This is enforced upstream by clear, UI hydrate, and LLM rehydrate. The planner operates on the already-active in-memory message list and should not add a second independent `historyClearedAt` reader.

## 15. Failure behavior

Compaction and planning failures should not corrupt conversation state.

Rules:

- Fail closed before provider call if a required active tool-pairing shape cannot fit.
- On optional evidence compaction failure, keep the existing compact form or drop that old evidence with a warning.
- On Stream rehydrate failure, continue with fresh context and do not insert an empty `_conversations` entry.
- Do not synthesize a model-visible apology or context-loss note in v1.
- Log enough structured detail for operators to diagnose which component exceeded budget.

## 16. Components by slice

The runtime was delivered in slices A–G; code comments cite these labels, so each component is listed under its
slice.

### Slice A: context planner telemetry

`ContextBudgetPlanner` emits the full `LLM_CONTEXT_PLAN` field set from §13 inside `AgentLoop.run` immediately before
each per-round `llmClient.chat(...)` call: the overhead breakdown (`stableChars`, `toolSchemaChars`, `ephemeralChars`,
`currentUserChars`, `activeBatchReserveChars`, `checkpointChars`), evidence sizes (`evidenceRawChars`,
`evidenceChars`), drop counters, configured/effective caps, `historyBudgetChars`, and `historyClampedToZero`.
`providerInputTokenLimit` comes from the static provider/model limit registry; unknown model names fall back to
`llmContextMaxChars`.

### Slice B: make replay compaction effectively always-on

`LlmReplayCompactionGate` centralizes the JVM diagnostic disable (`com.thingworx.parler.llmReplayCompaction.disableUnsafe`),
test-only overrides, and effective compaction for `AgentLoop` / Tier B. Replay compaction is on by default.
`ContextBudgetPlanner.planAndLog` emits **WARN** when the diagnostic property suppresses compaction (in addition to
`LLM_CONTEXT_PLAN unsafeDisable=1`). Test-only hooks on `LlmReplayCompactionGate` (`setReplayCompactionEffectiveForTest`,
`setUnsafeDiagnosticsDisableForcedForTest`, `clearAllTestHooks`) require an `@AfterEach` reset; tests must not toggle
the JVM/system property in a shared JVM.

### Slice C: planner-enforced memory budgets

`ContextBudgetPlanner.planForProviderRound` builds a **fresh outbound copy** of the in-memory message list immediately before each `llmClient.chat` in `AgentLoop.run`, after `TaskStateLlmInjector` / `LlmUtcClockInjector` insertions. Trimming is **drop-only** (no Tier B / promotion inside planning); the live `messages` list and `ChatMessage` instances are **not** mutated by planning. Protected rows: leading stable system, indexed ephemeral injections (task state / UTC when indices are passed), current user, the **active** assistant `tool_calls` batch plus trailing paired `TOOL` rows, and the **newest** injected conversation checkpoint **when the provider can carry it** (older markers are cleared from the `keep` mask before budgeting; the checkpoint is neither a transcript pair nor an evidence batch, and is excluded from `transcriptChars` and from dropped-transcript counts). Protection is not unconditional: a provider whose request contract cannot accept a leading assistant row — today Anthropic Messages, which requires the first message to be `user` — has the checkpoint's `keep` bit cleared **before** any metric is computed, reported as `CONVERSATION_CHECKPOINT_SKIP reason=PROVIDER_SHAPE_UNSUPPORTED`. That is distinct from budget omission (`CHECKPOINT_CANNOT_FIT`): no cap change fixes a contract mismatch. Carriage is affirmative — only shape families with a fixture and a vendor-contract check are carried; see `advanced-compact` §9.2. Trim order: drop **oldest complete historic evidence batches** (assistant with `tool_calls` + contiguous trailing `TOOL` rows, outside the active range) **before** **oldest historic transcript pairs** (historic `USER` + the next **kept** prose `ASSISTANT` without tools — rows already dropped by the evidence-first pass do not separate the pair, while any kept row between them still blocks it; the last user is never dropped). If non-history overhead exceeds `effectiveRequestCapChars` (`historyBudgetChars < 0` in the sense of impossible fit), the planner first omits an injected checkpoint by clearing its `keep` bit and recomputing — reporting `CONVERSATION_CHECKPOINT_SKIP reason=CHECKPOINT_CANNOT_FIT` with `checkpointChars=0` in the recomputed metrics; a checkpoint already omitted for provider shape is not reported twice — and only a budget that is still negative afterwards, or drop-only trimming that cannot fit the required rows, **fails closed** with `ContextBudgetExceededException` (`Reason` enum) before the provider call, preceded by an **`LLM_CONTEXT_PLAN_FAIL`** `ERROR` line mirroring the §13 field tail; **`CANNOT_FIT_AFTER_TRIM`** logs **cumulative** `droppedTranscript` / `droppedEvidence` / `droppedAssistantBatches` from the attempted trim pass (not the all-zero defaults from `Metrics.compute` alone). `LLM_CONTEXT_PLAN` / unsafe **WARN** behavior uses **post-trim** outbound size for `messages=` where applicable. `planAndLog` is **telemetry-only** (Slice A) (untrimmed `Metrics.compute` + one `LLM_CONTEXT_PLAN`; no throw). `droppedTranscript` / `droppedEvidence` on `LLM_CONTEXT_PLAN` are **dropped row counts** per §13 (tool-result rows only in `droppedEvidence`; assistant tool-call batches use `droppedAssistantBatches`). Tests cover overhead fail logging, `CANNOT_FIT_AFTER_TRIM` cumulative counters, ephemeral preservation, outbound tool-batch contiguity, and logical-adjacency pairing of a tool-backed turn's `USER`/final-prose pair after its evidence batch is removed.

### Slice D: normalized `_conversations` storage

`compaction/ConversationsReplayNormalization` centralizes post-turn replay normalization immediately before every `AgentThing` `_conversations.put` on successful sync `Chat`, `ChatAsync`, `ParlerStreamToRemoteThing` (non-awaiting), and `runParlerPostToolAgentLoop` success paths. `applyTierBReplayPromotionBeforeStore` takes `conversationId` and defers Tier B when `PendingApprovalStore.hasPendingForConversationId` is true (§8 option (b)); class javadoc covers the §8 active-pending invariant and local-list vs `put` publish. `compaction/ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget` runs after Tier B on the same paths: v1 subset of §8 step 4 — drop oldest historic assistant tool batches (before last `USER`), then oldest historic `USER`+prose `ASSISTANT` pairs; cap is `AgentSettings.llmContextMaxChars` (same configured bound as outbound planning); compaction-gated and pending-skipped like Tier B; `INFO` line `CONVERSATIONS_STORAGE_TRIM` on drops; `WARN` if still over cap (no fail-closed throw). **Pressure gate (Bug 014):** the unified entry calls Tier B only when the pre-normalization list exceeds that cap. Within-cap successful turns preserve old replay bytes; over-cap turns perform one all-candidate Tier B pass, then recompute checkpoint eligibility and trim on the promoted list. The existing `isLlmReplayCompactionEffective()` / `AgentResult.Status.SUCCESS` / pending gates remain unchanged. Per-turn ephemeral rows remain `applySkillTurnMutationFinish` / playbook finalize + stripped HITL snapshots; HITL continuation omits `setParlerEphemeralSystemIndices`. Storage normalization never fails closed.

### Slice E: compact evidence rehydration

**`FetchCachedCompactPersistFormat`** stamps **`$format=parler.fetch_cached_result.compact.v1`** on the fetch-cached
**peek** branch only (null-safe stamp comparison). **`CompactFetchStreamRehydrate`** + **`AgentConversationRehydrator`**
Stage 2 accept the marker **or** a legacy structural compact fetch (with **`columns[]`**); skip other tool rows and all
assistant **`toolCalls`** rows; apply the **`agentThing`** mismatch check to **all** Stream roles including tool;
annotate cache continuity; skip HITL synthetic tool JSON and PASSWORD columns; and never rebuild
**`PendingApprovalStore`**. Accepted compact fetch evidence is restored as **framed assistant prose** (not
**`Role.TOOL`**); see §9 and **`docs/agent/large-table-replay-control.md`** §4.2. Cache ids are historical unless a
live lookup succeeds. Stream rehydrate does not accept **`parler.infotable.summary.v1`** or other
non-`fetch_cached_result` compact rows, because those shapes are not persisted to Stream.

### Slice F: remove product config flag

There is no `enableLlmReplayCompaction` AgentSetting. `LlmReplayCompactionGate.isReplayCompactionEffective()` is
always-on unless JVM `com.thingworx.parler.llmReplayCompaction.disableUnsafe=true` or a test hook suppresses
compaction. `AgentLoop` has no per-instance compaction flag; it consults the gate at batch boundaries.
`initializeThing` logs one startup `INFO` when a stale ConfigurationTable key `enableLlmReplayCompaction` is still
present (value ignored).

`LlmToolResultTierBPromoter` is **shrink-only** for matrix and cohort-bundle inner promotion: when the promoted JSON
length is not strictly smaller than the original tool-result `content`, the row is left unchanged, `promoted` is not
incremented, and **`LLM_TIER_B_SKIP reason=no_shrink toolCallId=… beforeChars=… afterChars=…`** is logged. The
**`LLM_TIER_B_PROMOTED`** summary line includes **`skippedNoShrink=<n>`**. This applies to the Tier B path used by
Slice D normalization (`ConversationsReplayNormalization`); per-round Slice C planning remains drop-only.

### Slice G: entity-metadata replay compaction

`EntityMetadataSummaryCodec` + Tier B wiring in `LlmToolResultTierBPromoter` compact historic **`get_entity`** tool
results stamped **`parler.entity.metadata.v1`** (messages before the last user line) to
**`parler.entity.metadata.summary.v1`**. Same gating as Tier B (`LlmReplayCompactionGate`, HITL pending skip via
`ConversationsReplayNormalization`). The **shrink-only** guard applies. **`LLM_TIER_B_PROMOTED`** includes
**`entityMetadataPromoted=<n>`**. The routing guide documents the summary format.

**Scope — stamped `get_entity` only.** Promotion runs only when the tool-result body carries
**`"$format": "parler.entity.metadata.v1"`** (emitted by **`GetEntityExecutor`** for **ThingTemplate**,
**ThingShape**, or **DataShape** — **not** for **Thing** instances; **`get_entity`** rejects **Thing** input).
Slim `discover_properties` / `discover_services` / `get_service_definition` envelopes and list/query tools are never
promoted. Stream Stage 2 does not rehydrate `parler.entity.metadata.summary.v1`.

**Summary shape (`parler.entity.metadata.summary.v1`):**

- Top level: `entityType`, `entityName`, optional `template`, `tags[]`, `description` (verbatim when present).
- `properties[]`: `{ name, baseType }` only.
- `services[]` / `events[]`: `{ name }` only.
- `originalToolName`, `originalToolCallId` for replay routing.
- **No `cacheId` in v1** — re-fetch via `get_entity` with the same **ThingTemplate** / **ThingShape** / **DataShape** `entityType` / `entityName` when a full stamped **`parler.entity.metadata.v1`** body is required; for facet-bounded rehydration prefer **`describe_entity_schema`**. For **Thing**-instance properties use **`discover_thing_members`** + **`get_property_values`**. **`get_entity`** is omitted from the merged LLM tool list but remains executable for replay via **`ToolRegistry.registerExecutorOnly`** — see **`docs/agent/tool-surface-simplification.md`**.
- **No minimum size threshold** — recognize + shrink guard only.

## 17. Tests

Unit test coverage:

- default settings apply replay compaction
- diagnostic disable bypasses compaction and logs warning
- planner keeps stable system and current user rows
- planner returns a fresh planned request and does not mutate the input `messages` list or existing `ChatMessage` instances
- planner preserves the leading stable system row byte-for-byte
- planner removes ephemeral rows after turn
- planner telemetry uses the provider/model input-limit registry for known model names and falls back to `configuredCapChars` for unknown model names
- planner telemetry bucket-classification fixture assigns stable, ephemeral, current-user, transcript, active-batch, checkpoint, and evidence rows according to §13
- planner telemetry reports negative `historyBudgetChars` with `historyClampedToZero=1` when non-history overhead exceeds `effectiveRequestCapChars`
- planner omits an injected checkpoint and still plans the request when the fixed overhead leaves no room for it, and fails closed only when ordinary overhead exceeds the cap on its own
- planner trims oldest transcript pairs only at coherent boundaries
- planner drops old evidence before dropping recent transcript in per-round outbound copies
- planner telemetry for an atomically dropped paired batch emits `droppedAssistantBatches=1`, counts paired tool-result rows in `droppedEvidence`, and does not count the assistant tool-call row in `droppedTranscript`
- within-cap post-turn normalization leaves historic tool-result bytes unchanged; an over-cap turn promotes every eligible old evidence body before dropping transcript pairs, matching §8 ordering while preserving the assistant tool-call message for promoted retained batches
- planner drop-only trim does not invoke Tier B promotion and does not mutate live messages
- planner preserves active and retained historical assistant tool-call / tool-result pairing
- planner preserves provider-paired tool-result contiguity immediately after the originating assistant tool-call row
- planner drops provider-paired batches atomically when it cannot keep them
- planner preserves cohort bundle/member-reference atomicity
- planner refuses impossible active-batch requests before provider call
- Tier B promotion is idempotent when storage pressure invokes it
- post-turn normalization is idempotent across repeated turns
- compact `fetch_cached_result` still persists compact content while UI downlink sees full page body
- Stream persistence of a compact `fetch_cached_result` body writes top-level `"$format": "parler.fetch_cached_result.compact.v1"`
- `FetchCachedReplayGuard` compact fetch bodies are retained before larger older evidence
- Stream rehydrate respects `historyClearedAt`
- Stream rehydrate skips raw tool rows
- compact evidence rehydrate accepts `parler.fetch_cached_result.compact.v1`
- compact evidence rehydrate accepts legacy compact-fetch structural bodies and rejects raw object-row bodies
- PASSWORD/protected omissions remain enforced after promotion and rehydrate
- Anthropic request-shape fixtures assert tool-use/tool-result pairing, contiguous tool-result placement, stable system byte preservation, cache-control breakpoints <= 4, and tool-result block ordering within a batch
- OpenAI/Azure request-shape fixtures assert assistant `tool_calls[].id` / tool-result `tool_call_id` pairing and contiguous tool-result placement
- expired HITL pending snapshot restore cannot overwrite newer normalized conversation state with an older raw-shape list
- normalization followed by pending expiry still appends the synthetic expired tool result for the gated call id and leaves no orphaned assistant tool calls
- normalization followed by pending expiry appends synthetic results for interrupted sibling tool calls in original order
