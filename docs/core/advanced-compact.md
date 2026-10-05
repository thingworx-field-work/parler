# Advanced context compaction: conversation checkpoints

**Scope:** long-running conversation continuity in the Parler Java agent — working-context compaction,
Stream-based recovery, provider request budgets, and how these relate to the ArtifactCache, tool evidence and
`AgentTaskState`.

This document describes the conversation checkpoint as implemented. It does not change the UI wire contract or the
industrial-fact and protected-value contracts, and it adds no ArtifactCache operations.

## 1. Overview

Parler already controls context size reliably: large tool results are compacted into structured shapes or turned into
ArtifactCache handles before they reach the model; historical evidence is promoted or dropped deterministically by
complete tool batch; a fail-closed outbound planner runs before every provider round; and the Stream is separate from
the model's working set.

Size control alone does not keep task meaning. When old transcript is deleted deterministically, or after a JVM
restart, the model could lose the conversation's goal, constraints, progress, key decisions, blockers and next steps.

The checkpoint adds exactly one capability: **when the deterministic storage trim is about to delete complete
historical transcript, the agent generates and persists a bounded, versioned, non-authoritative
`ConversationCheckpoint`, and from then on rebuilds the working context from "latest checkpoint + exact recent
tail".**

The resulting request:

```text
stable prompt
+ latest ConversationCheckpoint (model-generated semantic navigation)
+ exact recent tail
+ current-turn ephemerals
+ current user / active tool batch
+ server-authored compact evidence
```

The fact boundary does not change: the checkpoint helps the model remember *what it is doing*, but it cannot prove
*what state a device is in*. Industrial facts, counts, completeness, sample status, errors, lineage and cache
liveness are still decided by server-authored evidence.

## 2. Terms and boundaries

- **`AgentMessageStream`** is the durable audit and history source.
- **`_conversations`** is the JVM working set.
- An ArtifactCache **`cacheId`** is live only after the current JVM's index validates it.
- A **checkpoint** is model-generated navigation, not evidence.

The checkpoint preserves provider tool-call/result pairing, the `PASSWORD` preflight, the separation of the UI, LLM,
audit and cache lanes, and `historyClearedAt` semantics.

Carried decisions:

- A checkpoint is generated on demand, only after a successfully completed turn.
- Semantic state is kept separate from the exact evidence manifest.
- The working set holds only the latest checkpoint.
- Original Stream messages are never deleted.
- On failure, the existing deterministic trim and fail-closed behavior apply.
- An old `cacheId` is not revived just because its file still exists.
- The summary call reuses the Provider client the turn already resolved. It is called once, with no business tools,
  no `AgentLoop` and no automatic retry.
- `source.throughAssistantMessageId` is the current turn's final-assistant Stream row id passed in by the calling
  path (§6.1.1).
- Planner checkpoint omission clears a `keep` bit over original indices and runs ahead of the `OVERHEAD_EXCEEDS_CAP`
  failure (§10.1).
- The protected-value check scans model-generated `semantic` only, with a fixed three-rule reject set (§8.3).

Related documents: [`../agent/context-compaction.md`](../agent/context-compaction.md),
[`../agent/conversation-continuity.md`](../agent/conversation-continuity.md),
[`../agent/task-state.md`](../agent/task-state.md).

## 3. Context-control mechanisms the checkpoint builds on

### 3.1 Context lanes

[`context-compaction.md`](../agent/context-compaction.md) separates content into the stable prompt, ephemeral turn,
LLM evidence, UI data and audit lanes. Only bounded LLM evidence and the transcript working set enter provider replay;
UI and Stream data never force the model to carry row-level payload. The checkpoint relies on this separation and does
not change it.

### 3.2 Same-turn egress and artifact spill

`ToolResultEgressGateway` compacts tool results to an 8192-character soft cap before they enter the model's message
list. It keeps status/code/resultKind, identifiers, counts, columns, aggregates, warnings, sample/completeness markers
and `cacheId` first. Large InfoTables go to `TabularArtifactHub`; oversized JSON/TEXT goes to `JsonArtifactHub`; the
model fetches, inspects and analyzes them through cache-aware tools within bounds.

### 3.3 Replay promotion and outbound planning

Tiers A/0/B compact completed tool evidence deterministically and shrink-only for replay. Before every provider round,
`ContextBudgetPlanner` builds a fresh outbound copy and protects:

- the leading stable system row;
- the current user row;
- current-turn ephemeral rows;
- the active assistant tool call and its complete tool-result batch.

Over budget, it first drops the oldest complete historical evidence batch, then the oldest transcript pair; if the
request still cannot fit, it fails closed before the provider call. The planner does not modify audit or UI data.

### 3.4 Post-turn storage trim

Before a successful turn saves `_conversations`, `ConversationsStorageBudgetTrimmer` enforces the same
`llmContextMaxChars` cap: it drops the oldest historical evidence batch first, then the oldest adjacent
`USER` + final prose `ASSISTANT` pair. It is skipped while HITL approval is pending.

This step can permanently remove historical task meaning from the working set. It is the checkpoint's only automatic
trigger, so there is no separate threshold system.

### 3.5 Stream rehydrate and task state

`AgentConversationRehydrator` restores a bounded user/final-assistant transcript from `AgentMessageStream` and accepts
only approved compact evidence shapes; it does not restore raw historical tool rows, assistant tool-call rows or old
system rows. `historyClearedAt` bounds the effective history for both UI and LLM.

`AgentTaskState` lives within one user turn and injects that turn's server-authored evidence and progress. It does not
write `_conversations`, the Stream or the HITL snapshot. The checkpoint does not change its lifecycle and does not
serialize it.

### 3.6 FileRepository-backed ArtifactCache boundary

Artifact payload bytes can outlive the JVM, but a handle's logical availability is still decided only by the current
JVM's index, principal/scope, TTL, invalidation and completeness checks:

```text
physical file retention != logical cache liveness
```

Two consequences matter here:

1. Within one JVM lifetime, a published handle is not evicted by heap-capacity LRU, so context pressure can be treated
   purely as a provider working-set problem.
2. Protected data that wrongly entered an artifact would persist, so a checkpoint carries only opaque handles and
   server-authored metadata and never inlines payload fields.

Whether a file is still in the repository never enters the model context and does not create a third availability
state. For a checkpoint there are only:

- `live`: the current JVM lookup succeeds within the current conversation/principal scope;
- `historical-recompute`: the current JVM lookup fails, so the data must be fetched or recomputed from a safe source.

## 4. What the checkpoint solves

Without a semantic layer, long-term task meaning is not preserved across these boundaries:

1. the post-turn storage trim deletes an old transcript pair;
2. the per-round planner deletes a historical pair to fit;
3. after a JVM restart only bounded recent Stream rows are restored;
4. after repeated deterministic compaction, the final assistant prose is not enough to reconstruct why a decision was
   made;
5. `AgentTaskState` ends with the turn by design.

The model could then repeat completed actions, forget user constraints, return to a rejected approach, or be unable
to explain the current blocker. Treating a free-text summary as fact would break Parler's evidence discipline, so the
checkpoint has a narrow job: keep task meaning, and reference facts without copying them.

## 5. Invariants

1. **Audit immutable:** a checkpoint never deletes or rewrites original `AgentMessageStream` rows.
2. **One working checkpoint:** `_conversations` and each provider request contain at most one checkpoint, the latest;
   older checkpoints remain only in the Stream audit.
3. **Completed-turn cutoff:** a checkpoint covers only complete, successfully finished historical turns. It never
   cuts into the current user row, the active tool batch, pending HITL or an unfinished continuation.
4. **Tool pairing:** the cutoff is validated by tool-call ids and complete batches, not by physical adjacency alone;
   any incomplete batch makes the boundary unusable.
5. **Semantic is non-authoritative:** model-generated fields cannot prove values, device state, completeness,
   authorization or error class.
6. **Evidence is server-authored:** `evidenceRefs` are built only from validated compact evidence still in the covered
   prefix, never accepted from summary model output.
7. **No protected values:** a checkpoint contains no `PASSWORD` value, raw rows, raw JSON/TEXT payload,
   FileRepository path, cache index internals or hidden chain-of-thought (scope: §8.3).
8. **Bounded output:** model-facing semantic text is capped at 8000 UTF-16 characters; the server-authored retained
   tail at 100 messages / 100000 characters; the whole persisted checkpoint JSON at 250000 characters. Arrays and
   single items have fixed caps too. Over-cap content is shrunk deterministically by whole rows or rejected; it never
   enters the Stream or a provider request unbounded.
9. **Failure preserves availability:** a failure in summary generation, validation or Stream append never turns an
   already produced final answer into a failure; the existing deterministic normalization runs and explicit telemetry
   is recorded.
10. **Clear wins:** a checkpoint before `historyClearedAt` never takes part in rehydrate.
11. **Handle honesty:** each `cacheId` is re-evaluated at rehydrate; a physical file never upgrades
    `historical-recompute` to `live`.
12. **No new artifact lifecycle:** there is no catalog, startup scan, sidecar, quota, sweeper, retention job or
    automatic delete.

## 6. ConversationCheckpoint v1

### 6.1 Persisted shape

```json
{
  "$format": "parler.conversation_checkpoint.v1",
  "source": {
    "conversationId": "...",
    "agentThing": "...",
    "throughAssistantMessageId": "..."
  },
  "semantic": {
    "goal": "...",
    "constraints": ["..."],
    "progress": {
      "done": ["..."],
      "inProgress": ["..."],
      "blocked": ["..."]
    },
    "decisions": [
      {
        "decision": "...",
        "rationale": "...",
        "rejectedAlternatives": ["..."]
      }
    ],
    "nextSteps": ["..."],
    "criticalContext": ["..."]
  },
  "evidenceRefs": [
    {
      "toolCallId": "...",
      "tool": "...",
      "evidenceFormat": "infotable-summary",
      "resultKind": "...",
      "cacheId": "...",
      "completeness": "...",
      "sampleOnly": false,
      "liveness": "live",
      "recomputeTool": "..."
    }
  ],
  "retainedTail": [
    {
      "role": "user",
      "content": "...",
      "provenance": "transcript"
    },
    {
      "role": "assistant",
      "content": "...",
      "provenance": "final-assistant"
    }
  ],
  "generated": {
    "at": "...",
    "provider": "...",
    "model": "...",
    "modelGenerated": true
  }
}
```

This is an internal persisted shape, not a UI wire contract. `ConversationCheckpointCodec` rejects an unknown
`$format`, wrong types, invalid enum values, a wrong conversation/agent identity and over-cap content.

Fixed caps in the codec, besides the envelope caps of §5 invariant 8: `goal` 600 characters, other items 300
characters each; at most 12 constraints, 12 progress items, 8 decisions (4 rejected alternatives each), 8 next steps,
12 critical-context items and 64 evidence refs. `retainedTail.provenance` is `transcript`, `final-assistant` or
`compact-evidence`.

The envelope is a self-contained working checkpoint: `semantic` replaces the covered prefix, and `retainedTail`
freezes the exact rehydrate-safe view that passed the Stage-2 acceptance policy when it was generated. Re-querying
those historical rows after a restart would re-interpret them under the *current* acceptance policy, which can differ
when the policy changes or legacy rows lack compact markers. Embedding the tail also lets the codec validate the
role, coherence and caps of the whole envelope without a second Stream range query.

#### 6.1.1 `throughAssistantMessageId` is the envelope watermark

`source.throughAssistantMessageId` has exactly one meaning: **the `assistantMessageId` of the final assistant Stream
row of the turn that generated this checkpoint.** It is defined by turn identity, not by position — the checkpoint row
is appended after that row but not necessarily adjacent to it. The envelope covers all accepted history up to and
including that row: `semantic` replaces the covered prefix, and `retainedTail` carries the exact rehydrate-safe rows
from the end of that prefix through the watermark row. It is a watermark, not a semantic-prefix cutoff, and no
consumer treats it as the Stream id of the older prefix boundary.

This follows from the data model. `ChatMessage` carries role, content, tool-call id, tool calls and executed-tool
name, but no `assistantMessageId`, so a boundary selector working on the in-memory `messages` list cannot know the
Stream id of an older cutoff row. No id field is added to `ChatMessage`: every provider adapter serializes that type
and every pairing check walks it.

**Data flow.** Each of the four success paths (§8.1.1) mints `assistantMessageId = UUID.randomUUID()` and passes it to
`AgentMessageStreamAppender.append(...)` for the final assistant row just before post-turn normalization, and then
passes the same id into the post-turn normalization entry together with the turn's `LlmClient`. The server writes it
into `source`; it is never taken from model output, reconstructed from content, or matched against prose.

**Rehydrate validation is an allowlisted backward walk, not an adjacency check.** The watermark row usually
immediately precedes the checkpoint row, but that is not guaranteed. The remote streaming (AlwaysOn) path and the
approval-resolved continuation send `wireDone(..., assistantMessageId, ...)` to the client *before* post-turn
normalization runs, and the checkpoint entry then makes the summary Provider call before appending its row. Once
`wireDone` is visible, the widget can call `RecordAssistantFeedback`, which appends `role=ui_feedback` rows anchored on
that `assistantMessageId` (`AgentMessageStreamAppender.appendUiFeedback`). Feedback is an append-only, last-wins
history, so several such rows can land between the watermark row and the checkpoint row while the summary call is in
flight. That is normal product behavior.

A "nearest preceding `role=assistant`" rule would be too broad: it could walk back across a later user row, a tool row,
an unknown internal row, or an assistant from another turn and still find a matching id. The validator therefore
encodes the allowed row class explicitly:

1. Walk backward from the checkpoint row within the bounded window.
2. Skip rows whose role is `ui_feedback` — **the entire inert set**. Any number of them may be skipped, including
   feedback anchored on a different assistant.
3. The first non-inert row must be a final assistant row: `role=assistant`, blank `toolCalls`, `agentThing` equal to
   the current AgentThing, and `assistantMessageId` equal to `source.throughAssistantMessageId`.
4. Anything else — a `user` row, a `tool` row, a tool-calling assistant, a different final assistant, an unknown role,
   a missing row, or an id/agent mismatch — yields `reason=WATERMARK_UNVERIFIED`.

The inert set is closed: an unrecognized row never silently authorizes a watermark.

**Bounded window.** `AgentMessageStreamReader.queryChronologicalRows` issues a newest-first `QueryStreamData` capped
at `maxItems` and reverses it, so the window is a contiguous newest-N slice. The watermark can fall outside it in two
ways: the checkpoint row is itself the oldest row of the window, or enough inert rows separate the two that the
watermark is pushed past the boundary. Both fall back the same way, and both are benign: a watermark outside the
window means the window is already full of rows *newer* than the checkpoint, so the exact recent tail alone is rich
and only navigation is lost, not evidence.

In every rejecting case rehydrate falls back to the transcript-first path. It never widens the query, never issues a
second or unbounded range query, and never accepts an unverified watermark.

**Candidate selection versus validation — an invalid newest checkpoint is fail-closed.** "Latest valid checkpoint
wins" (§9.2) describes filtering, not retrying:

- **Selection filters** decide which rows are candidates at all: rows at or before `historyClearedAt` are outside the
  query bound, rows whose `agentThing` differs belong to another agent, and rows that are not
  `role=context_checkpoint` are not checkpoints. These are skipped and selection moves to the next-newest row.
- **Validation** then runs on the single newest surviving candidate: `$format`, identity, caps, envelope size,
  `retainedTail` shape and the watermark walk. If the candidate fails any of these, rehydrate falls back to
  transcript-first. It does not walk further back for an older checkpoint.

An older checkpoint was superseded because the task moved on; resurrecting it would reinstate task state the system
already decided was out of date, which is worse than no checkpoint and invisible to the user. It would also turn one
bounded validation into an unbounded backward search. This matches §5 invariant 2.

**Persistence cost.** Each checkpoint event stores up to 100000 characters of tail, and a whole row up to 250000
characters, so repeated compaction keeps several historical tail copies in the immutable audit Stream. This cost is
accepted in exchange for version-stable recovery semantics; the Stream backend must accept TEXT rows near the upper
limit (§14.2).

### 6.2 Ownership of fields

The summary model writes only `semantic`. After parsing, the server builds `source`, `evidenceRefs`, `retainedTail`
and `generated` and applies caps and protected-value checks. A model cannot forge evidence, identity or liveness by
emitting fields with those names.

`semantic`:

- `goal`: the user goal the conversation is working toward;
- `constraints`: user boundaries and preferences that still apply;
- `progress`: high-level done / in-progress / blocked items only, never raw tool output;
- `decisions`: important decisions, a short rationale and rejected alternatives;
- `nextSteps`: concrete actions for the next turn;
- `criticalContext`: necessary identifiers and short semantic notes, with no protected values.

`evidenceRefs`:

- optionally record which server-authored evidence in the covered prefix supported task progress; evidence already
  removed by an earlier storage trim is never inferred from semantic prose or forged;
- carry the bounded completeness/sample/error metadata and cache lineage already present in an accepted success
  envelope;
- give a safe `recomputeTool` name for when a live handle has expired;
- do not carry full arguments. Arguments for a re-fetch are rebuilt from the current user request, permitted Host
  Context or fresh discovery, never replayed from checkpoint text.

`ConversationCheckpointEvidenceManifest` uses a closed allowlist, not a "looks like compact JSON" heuristic. A new ref
must come from a complete `Role.TOOL` row in the covered prefix whose tool-call id pairs successfully; `toolCallId` and
`tool` come from `ChatMessage` pairing and executed-tool metadata, never from the body or a model field. Accepted
sources:

1. `parler.infotable.summary.v1`;
2. `parler.entity.metadata.summary.v1`;
3. `parler.infotable.matrix.v1` that passes the existing matrix shape, `PASSWORD` and bounded-row checks;
4. `parler.cohort.bundle.v1` whose `result` is a validated matrix or InfoTable summary (an isolated
   `parler.cohort.member.v1` produces no ref);
5. the success families already accepted by `CompactFetchStreamRehydrate.acceptsStreamCompactFetchEvidence(...)`:
   `parler.fetch_cached_result.compact.v1`, `parler.numeric_history.compact.v1`,
   `parler.value_stream_history.compact.v1`, the matching numeric/value-stream matrix shapes, and the legacy
   structural compact-fetch success with `columns[]`.

Unknown `$format`, unmarked generic JSON, raw rows/pages, HITL/Playbook internal rows, `PASSWORD`-bearing bodies and
error shells produce no `evidenceRefs`. Accepting a format only authorizes extracting the bounded metadata of its
schema; the body is never copied. Stage-2 rehydrated compact evidence is already framed assistant provenance: without
its original paired `Role.TOOL` row it can enter the retained tail and summary input, but it cannot create a new ref.
Refs of the previous validated checkpoint can carry forward on repeated compaction, but they are re-checked for
identity, caps, format/tool allowlist and current-JVM liveness. `recomputeTool` comes from the server tool allowlist,
never from model output.

**`evidenceFormat` records which family admitted a ref.** A ref that records only `tool` and `cacheId` could not be
re-checked on carry-forward, because the body it came from is gone by then. The ref therefore records the admitting
family as a closed token — `infotable-summary`, `entity-metadata-summary`, `infotable-matrix`, `cohort-bundle` or
`compact-fetch`, one per accepted source above. A token outside this set is rejected on parse, on serialization and on
carry-forward.

The token is a server-authored record of an admission decision, not an authenticator: revalidation checks membership
and caps but cannot prove historical body provenance. That is sufficient because a `context_checkpoint` row lives in
`AgentMessageStream` under the same entity permissions as every other Stream row (§9.3). An actor who can rewrite those
rows out of band can equally rewrite a final answer or a `role=tool` evidence row; detecting tampered Stream rows is an
integrity concern for the Stream as a whole. What the token defends against is a *model-authored* eligibility claim,
which §6.2's ownership rule already excludes.

**Accepting a `$format` is not accepting a body.** Each family also validates its success envelope: a success status,
the bounded fields that family's producer always writes, no `PASSWORD` column, and a bounded row count for row-bearing
shapes. A marker-only object, an error shell, or raw page-shaped content relabelled with an accepted `$format`
produces no ref. A cohort bundle's inner `result` is held to the same standard as a standalone body of that family.

**Model-facing names only.** `recomputeTool` must name a tool the model can actually call, so the allowlist is drawn
from the currently advertised tool definitions and checked against the advertised registry. Executor-only names —
demoted aliases, replay-only executors, tools withdrawn by an admission gate — never appear, even though they can
produce accepted evidence.

`retainedTail` accepts only working rows that the current rehydrate policy accepts:

- user transcript;
- final prose assistant;
- approved compact evidence converted into assistant provenance.

It never accepts system rows, assistant tool calls, `role=tool`, raw audit payload, HITL synthetic rows or Playbook
internal artifacts. "Exact" means the summary model does not rewrite the role/content of these rows; it does not mean
a copy of the whole raw provider history. Above 100 messages / 100000 characters, the oldest complete
user/final-assistant pair is removed first; a single row is never cut and no leading assistant is left. A serialized
envelope above 250000 characters makes the checkpoint invalid.

### 6.3 Repeated compaction

The input for a new checkpoint is:

```text
previous validated checkpoint semantic
+ complete historical turns selected for removal
+ server-rendered evidence lines for those turns
```

The model updates rather than appends: completed items move from in-progress to done, obsolete blockers are removed,
still-valid constraints and decisions are kept, and new decisions and next steps are added. The server regenerates
all `evidenceRefs` and `retainedTail`, keeping only references and rehydrate-safe rows that are still relevant and
within caps.

The Stream can keep several checkpoint events; the working context uses only the latest valid one.

## 7. Trigger and cutoff

### 7.1 Single automatic trigger

There is no percentage, message-count or AgentSettings threshold. Tier B runs under the existing storage cap: after
the successful final assistant is added and before old history is mutated, Tier B is invoked once only when the
aggregate replay characters exceed `llmContextMaxChars`; within the cap it is skipped to keep the append-only prefix
that provider caching needs. The storage dry run is then recomputed.

The only automatic trigger for the checkpoint model call is:

> After the successful turn's conditional Tier B normalization, the post-turn storage-trim dry run shows that at least
> one historical transcript row must be deleted to meet `llmContextMaxChars`.

If only historical tool evidence needs deleting, the existing deterministic evidence compaction applies and no summary
call is made. The checkpoint's cost is therefore paid only when semantic transcript is about to be lost.

**Inherited gates.** The trigger is never wider than the trimmer. Checkpoint generation is skipped whenever
`ConversationsStorageBudgetTrimmer.maybeTrimForStorageBudget` would itself return without removing rows:

- `LlmReplayCompactionGate.isReplayCompactionEffective()` is false — the diagnostic JVM property that suppresses
  Tiers A/0/B also suppresses checkpoints, so there is one coherent off switch;
- `PendingApprovalStore.hasPendingForConversationId(conversationId)` is true — the same pending-approval deferral that
  skips Tier B and the trim;
- the aggregate estimate is already at or below the cap.

The boundary selector (§7.2) asks the same helpers as the trimmer rather than re-deriving these conditions, so the two
cannot disagree about whether a turn is trimming.

**One additional gate: blank conversation identity.** The trimmer's pending-approval guard skips the pending check for
a blank id and still trims. A checkpoint cannot: it is persisted against a conversation identity and re-selected by
that identity at rehydrate. The selector therefore reports `NO_CONVERSATION_ID` and declines, while the trimmer
proceeds as usual. This is the only gate on which the two differ, and it can only suppress a checkpoint — it never
changes what the trimmer removes.

### 7.2 Cutoff selection

`ConversationCompactionBoundarySelector` is a pure, non-mutating dry run that reuses the storage trim order and returns
a plan:

```text
coveredPrefix + retainedRehydrateSafeTail + removal counters
```

The boundary:

- falls between complete user turns;
- validates assistant/tool batch completeness by tool-call id;
- keeps the last user row and every current-turn row after it;
- fails closed as "no usable boundary" on pending HITL, an orphan tool row, an interleaved batch or an unknown role;
- must let the materialized `checkpoint semantic + retainedRehydrateSafeTail` shrink within the same storage cap;
  otherwise the checkpoint is refused and the trimmer behaves as it always does.

**The semantic cutoff is not "the rows the trimmer removed".** The trimmer drains every historical evidence batch
before it removes any transcript pair, so once a transcript drop is needed, evidence from a newer turn whose
`USER` + final-assistant rows survive has already been removed. Summarizing "all removed rows" would hand the model
evidence from a turn whose transcript is presented later as exact tail, and the checkpoint would straddle its own
boundary. The plan is built from a cutoff instead:

- **cutoff** = the highest original index among the transcript rows the dry run removed (the newest removed
  `USER` + final-assistant pair);
- **`coveredPrefix`** = every non-`SYSTEM` original row at or before the cutoff, in document order — a genuine prefix of
  complete turns, including rows in that span the trimmer did not remove (for example Stage-2 rehydrated
  compact-evidence assistant rows, which no trim pass touches);
- **`retainedTail`** = the surviving non-`SYSTEM` rows after the cutoff;
- evidence the trimmer removed from turns after the cutoff is in neither list; that is ordinary deterministic
  evidence compaction, not content a checkpoint narrates.

The trimmer's own counters and full materialized survivor view are kept separately, so equivalence with the trimmer
can be checked independently of where the semantic cutoff fell.

**Pairing is validated across the whole candidate list, not only the removed span.** §5 invariant 4 makes any
incomplete batch disqualifying, and a batch that survives the trim (including the current turn's) can still be
incomplete without being an orphan row. Every assistant tool-call batch in the list must have declared ids and
observed `TOOL` result ids forming the same non-empty set, with no missing, extra, duplicate or blank id. The
additional rule that a batch intersecting the cutoff must be removed whole applies only at the cutoff, the only place
where partial removal could split a batch.

The selector's equivalence target is `ConversationsStorageBudgetTrimmer` only: both remove evidence in the same order,
then choose the surviving transcript pair, and yield the same materialized rows and removal counters for the same
input. `ContextBudgetPlanner` trims only each round's outbound copy, does not cause permanent working-set loss, and is
not a dependency of the checkpoint cutoff; planner pairing, drop order and fail-closed behavior are unchanged.

### 7.3 Current-turn overflow

The checkpoint never compresses the current user row or the active tool batch, and adds no provider error
classification or automatic retry. When the current turn cannot fit, the existing mechanisms apply: tool-result
egress, the active-batch reserve, `ContextBudgetPlanner` preflight, and the fail-closed
`ContextBudgetExceededException`.

## 8. Generation and validation

### 8.1 Generation point

After the successful final assistant is produced, the pre-normalization storage pressure is computed and Tier B has
run if needed, but before the storage trim deletes rows:

1. the boundary selector runs its dry run;
2. if no transcript would be deleted, generation is skipped;
3. the summary request is built from the covered prefix, the previous checkpoint and bounded server evidence;
4. one tool-free internal LLM call is made with the current resolved provider/model;
5. the semantic JSON is parsed, validated and capped;
6. the server assembles `evidenceRefs` and the exact rehydrate-safe `retainedTail` into a complete checkpoint;
7. the checkpoint semantic assistant row + `retainedTail` replace the covered prefix in the `_conversations` working
   set;
8. the checkpoint event is appended to the Stream after the already persisted final assistant row;
9. the storage budget assertion/trim runs and `_conversations` is saved.

This happens only for successful final turns. Cancelled and error turns do not generate a checkpoint; with pending
HITL the existing normalization deferral applies and the approval-resolved success path evaluates it later.

### 8.1.1 Integration surface

The single entry point is `ConversationsReplayNormalization.applyPostTurnNormalizationBeforeStore`. It receives two
values from the calling path that it cannot derive itself:

1. the `LlmClient` the turn already resolved, so the Provider Thing, effective model, rate control and HTTP timeout
   stay the same within one turn (the checkpoint never calls `AgentThing.llmClientForTurn()` a second time);
2. the `assistantMessageId` the path just minted for the turn's final assistant Stream row, which becomes
   `source.throughAssistantMessageId` (§6.1.1).

Inside the entry, the pre-mutation storage-pressure check conditionally invokes Tier B, then the checkpoint selector
and the deterministic trim operate on the same resulting `messages` list. `AgentThing` routes every post-turn
normalization through this entry and has no direct call to `applyTierBReplayPromotionBeforeStore` or
`maybeTrimForStorageBudget`; a static integration test enforces this.

The four success paths, each with a terminal call and a success call:

| Path | `AgentThing` method |
|---|---|
| sync chat | `Chat` |
| async chat | `ChatAsync` |
| remote streaming / AlwaysOn | `ParlerStreamToRemoteThing` |
| approval-resolved continuation | `runParlerPostToolAgentLoop` |

The terminal calls sit inside `isArtifactCacheTerminal(result)` branches, which require `AgentResult.Status.ERROR`.
The entry keeps its `Status.SUCCESS` gate, so those calls never generate a checkpoint.

**Playbook slash turns are not covered.** `AgentThing.finishPlaybookSlashTurn` writes `_conversations` directly and
runs neither Tier B promotion nor the storage budget trimmer; it has no `AgentLoop.AgentResult` and no `LlmClient`.
Because the only trigger (§7.1) is "the storage-trim dry run would drop a transcript row", and that trimmer does not
run on this path, no checkpoint is generated there. A conversation that grows only through Playbook slash turns
accumulates working-set rows that no post-turn trim reduces and receives no checkpoint.

### 8.2 Summary request

The internal summary request:

- exposes no business tools;
- carries no stable system prompt, UI data, raw audit rows or raw artifact payload;
- asks only for `semantic` JSON;
- forbids inferring facts that do not appear in the input;
- asks the model to keep user constraints, decision rationale, rejected alternatives, blockers and next steps;
- forbids hidden reasoning / chain-of-thought output; `rationale` may only hold a short business reason already stated
  in the conversation.

`ConversationCheckpointGenerator` calls `turnLlm.chat(...)` directly, without an `AgentLoop`. The request is fixed:

```text
messages = [checkpoint-only fixed SYSTEM instruction, server-rendered USER payload]
tools = []
toolChoiceNone = true
temperature = 0.0
requestedMaxOutputTokens = 2048
modelOverride = null
reasoningEffort = null
enableCacheControl = false
rateControlStatusSink = null
usageWireIds = turnLlm.usageWireIdsForEffectiveModel(null)
```

`modelOverride=null` makes the Provider bridge use the turn's effective model; the bridge still applies its
max-output resolution, reasoning default, rate control and configured HTTP timeout. The generator makes no retry, no
fallback-provider call, no second summary call and exposes no business tool; any exception or HTTP timeout goes
straight to the failure fallback (§8.4).

The summary input has a code-owned hard cap of 100000 estimated characters (not an AgentSetting). The effective cap:

```text
min(100000,
    ContextBudgetPlanner.Metrics.compute(
      empty messages,
      empty tools,
      turnLlm.usageWireIdsForEffectiveModel(null),
      llmContextMaxChars,
      turnLlm.contextPlanningInputCapChars(2048, null).orElse(0)
    ).effectiveRequestCapChars)
```

`MessageCharEstimator` measures the fixed instruction + previous checkpoint semantic + server-rendered covered turns
+ evidence lines. The boundary selector picks only the oldest complete-turn prefix that fits as a whole; it never cuts a
tool batch, a single row or arbitrary characters. If the fixed overhead already exceeds the cap, no complete prefix
fits, or the materialized checkpoint + tail cannot shrink the working set to the storage cap, the reason is
`SUMMARY_INPUT_TOO_LARGE` or `NO_SHRINK`, the model is not called, and the deterministic trim applies. There is no
segmented or multi-call summarization.

### 8.3 Validation and shrink

Validation runs in a fixed order:

1. JSON parse and schema/type check;
2. only `semantic` fields are accepted; identity/evidence/generated fields from the model are discarded;
3. protected-value scan;
4. per-item, array-length and 8000-character semantic caps;
5. conversation/agent/source written by the server;
6. `evidenceRefs` built from validated compact evidence in the covered prefix;
7. `retainedTail` role/provenance, the 100-message / 100000-character cap and the coherent boundary check;
8. the 250000-character persisted envelope and the shrink-only check of the materialized working set.

Over cap, a deterministic reduction removes items stage by stage — `criticalContext`, then `decisions`,
`constraints`, `nextSteps`, `progress`, and `goal` last — instead of asking the model to "make it shorter". `goal`
must not be empty; if no valid, shrinking checkpoint results, generation has failed.

**What step 3 is, and what it is not.** The existing `ProtectedValuePolicy` surface is driven by key names and schema
(`SENSITIVE_KEY_NAMES`, `isSensitiveKeyName`, `isProtectedProperty`, `redactPersistedToolArgumentsJson`) and works on
structured JSON or a live `ServiceDefinition`. `semantic` is free prose written by a model, so none of those can prove
a protected value is absent.

**Scope of invariant 7.** It covers this repository's protected-data boundary — typed `PASSWORD` values, raw
tool/service payloads, raw rows and pages, FileRepository paths and cache index internals — and only that. Two input
classes carry different guarantees:

- **Tool and service data** reaches the summary input only after `ToolResultEgressGateway` compaction and the
  `PASSWORD` preflight, and only in the accepted compact formats of §6.2. Invariant 7 rests on those mechanisms.
- **Ordinary user and final-assistant prose** passes through none of them. If a user pastes a credential into the chat
  box, that string is already persisted as a `role=user` Stream row and already re-enters provider context through
  transcript-first rehydrate. The checkpoint does not detect it and does not claim to; that would be an input-side DLP
  concern.

What the checkpoint guarantees structurally, independent of any scanner:

- `evidenceRefs`, `source` and `generated` are server-assembled, so a model string never becomes a handle, an identity
  or a liveness claim;
- accepted evidence formats contribute bounded metadata only; bodies are never copied;
- the public `cacheId` is the bare lowercase UUID of `ArtifactRef.artifactId()` with no path, principal or kind
  encoding (`ArtifactCacheIds`), so a `cacheId` exposes nothing beyond the opaque id.

**What step 3 scans: model-generated `semantic` strings only.** `retainedTail` is exact transcript prose already
persisted verbatim in the same Stream, under the same authorization, and already replayed by rehydrate; duplicating it
adds no new exposure, and rejecting a checkpoint for text the platform already stores would lose continuity without
removing anything. `semantic` is newly generated text found in no other durable record, so that is where the check
runs.

The fixed reject rules, complete:

1. the `ProtectedValuePolicy.MASK` sentinel `***` appears in any `semantic` string — a masked value reaching prose
   means an upstream masking path was traversed, and the checkpoint must not carry that trace forward;
2. any token from `ProtectedValuePolicy.SENSITIVE_KEY_NAMES` (`password`, `passcode`, `apikey`, `api_key`, `token`,
   `secret`, `credential`, `accesskey`, `access_key`, `clientsecret`, `client_secret`, `privatekey`, `private_key`)
   appears case-insensitively, immediately followed by `:` or `=` and then a non-whitespace character — a key/value
   shape, not a mention. "The user asked about token expiry" passes; `token=abc123` does not;
3. an `ArtifactPathLayout`-shaped repository-relative path appears: a hex segment, then an ISO `yyyy-MM-dd` segment,
   then a segment ending in the `.payload` suffix (`<hex-username>/<yyyy-MM-dd>/<artifact-id>.payload`).

Rejection is whole-envelope: `reason=PROTECTED_VALUE` and the §8.4 fallback. The scanner never redacts or partially
rewrites, so a half-scrubbed semantic state is never persisted as if it were clean. False positives are acceptable: a
rejected checkpoint costs continuity for one compaction and nothing else.

### 8.4 Failure behavior

The checkpoint is a continuity enhancement, not a chat availability gate:

- a failed model call, parse, validation or Provider HTTP timeout logs `CONVERSATION_CHECKPOINT_SKIP` and runs the
  normal storage trim; there is no separate checkpoint wall-clock budget, timer executor or retry policy;
- a failed Stream append keeps the working checkpoint in the current JVM and records the persistence failure; after a
  restart the bounded transcript fallback applies as if the checkpoint did not exist;
- the main provider request is still protected independently by the planner's fail-closed behavior;
- no fabricated "fallback semantic summary" is ever produced.

The direct summary call can delay the return of a successful turn by up to the Provider's configured HTTP timeout.
This bounded cost is accepted to keep the conversation lock, the final-assistant/checkpoint Stream order and the atomic
publication of `_conversations`. Generation is not moved to the background. `summaryDurationMs` (§12) records the
actual cost.

## 9. Persistence and rehydration

### 9.1 Stream representation

A checkpoint is an internal row in the existing `AgentMessageStream`:

```text
role = context_checkpoint
content = parler.conversation_checkpoint.v1 JSON
agentThing = current AgentThing
toolCallId/toolCalls/executedToolName = empty
promptTokens/completionTokens = 0
```

It is written through the dedicated `AgentMessageStreamAppender.appendContextCheckpoint(...)`, not disguised as an
ordinary `ChatMessage`. `promptTokens` / `completionTokens` are always `0` so the row is never confused with a
user-visible assistant round; the internal call's usage appears only in checkpoint telemetry. No Stream Thing,
DataTable or FileRepository artifact is added. The `AgentMessageData` DataShape `role` description lists
`ui_feedback` and `context_checkpoint`.

**Why a dedicated appender.** `ChatMessage.Role` has exactly four constants — `SYSTEM`, `USER`, `ASSISTANT`, `TOOL` —
and `AgentMessageStreamAppender.toValueRow` derives the persisted `role` from `msg.getRole()`, so the `ChatMessage`
appender cannot write `role=context_checkpoint`. A new `ChatMessage.Role` constant would leak an unroutable role into
every provider adapter, planner branch and pairing check. `appendContextCheckpoint` follows the structure of
`appendUiFeedback`: it resolves the Stream Thing and the row `DataShapeDefinition`, builds the `ValueCollection` with
the literal role string, guards optional fields with `shapeHasField` so older deployed shapes still accept the row,
returns a boolean and never throws into the caller.

**Size cap.** `toValueRow` and `appendUiFeedback` truncate content above `LargeJsonCaps.STREAM_PERSISTENCE_CHAR_CAP`
(500000 UTF-16 characters) by appending `"\n...[truncated]"`. For checkpoint JSON that would produce an unparseable
row. The 250000-character envelope cap (§5 invariant 8) is half the persistence cap, so a validated checkpoint never
reaches truncation; `appendContextCheckpoint` re-asserts `length() <= 250000` just before building the row and skips
the append with `reason=STREAM_APPEND_FAILED` rather than persist a truncated envelope.

The UI history exporter (`AgentMessageStreamHistoryExporter`) skips the role explicitly before turn segmentation, so
a checkpoint never lands in a pending tail or creates a spurious segment, and the UI wire shape is unchanged. A
diagnostics collector can see the row through raw Stream access, but must not treat the semantic JSON as industrial
evidence.

Every Stream-based usage consumer identifies billable/evaluable rounds by `role=assistant`, not by the presence of
token columns. A `context_checkpoint` row has empty `llmUsageJson` and zero legacy token columns; it never creates an
assistant round, triggers a missing-usage error, becomes the final-assistant usage or enters usage totals.

Order-sensitive consumers account for the row as well. Because the checkpoint row is appended after the final
assistant, the evaluation harness (`test_scripts/agent_eval.py`) skips only trailing `role=context_checkpoint` rows
when checking trace completeness and then requires the preceding row to be the final assistant with empty
`toolCalls` and content matching the Chat answer. The raw delta keeps the checkpoint for audit; any other unknown
trailing role still fails.

### 9.2 Rehydrate order

`AgentConversationRehydrator`, after `historyClearedAt` and within the same `agentThing` scope:

1. selects the newest candidate row by the §6.1.1 selection filters (after `historyClearedAt`, matching `agentThing`,
   `role=context_checkpoint`);
2. validates that single candidate — `$format`, identity, caps, envelope size, `retainedTail` shape and the §6.1.1
   watermark walk; any failure is fail-closed and never falls back to an older checkpoint;
3. restores the exact rehydrate-safe tail from the checkpoint's own `retainedTail`, then appends the user,
   final-assistant and approved compact-evidence rows written after the checkpoint row;
4. looks up each `cacheId` in `evidenceRefs` in the current JVM and rewrites it as `live` or `historical-recompute`;
5. places the checkpoint semantic before `retainedTail` as a `ChatMessage.assistant` prose row with the fixed prefix
   `[parler:conversation-checkpoint]`;
6. applies the existing coherent-boundary and character-budget rules while protecting the latest checkpoint;
7. if the checkpoint is invalid or not found, uses the transcript-first fallback unchanged.

The checkpoint is injected with assistant provenance, not as a system or user row: it gains no system authority and
cannot pose as the user's own words. A provider that does not accept this shape skips checkpoint injection rather than
promoting it to a system message.

**Anthropic does not accept it.** The Anthropic Messages API requires the first message to have the `user` role. The
leading stable row is serialized in the top-level `system` field and classified volatile rows are relocated to a
terminal user-content suffix, so neither path creates a user row before an injected checkpoint; the checkpoint would
be the first API message with role `assistant`, and the request would be rejected.

**Carriage is affirmative.** `ContextBudgetPlanner.providerCarriesInjectedCheckpoint` returns true only for the Chat
Completions shape families known to accept it — `openai-chat-completions-*` and `azure-openai-chat-completions-*`.
Every other API shape, including a blank one and any adapter added later, omits the checkpoint. Omitting costs one
conversation its navigation aid; carrying it wrongly would cost the user their chat, which §8.4 forbids.

**The omission is a planning decision, not a serialization one.** The planner clears the checkpoint's `keep` bit before
any metric is computed, so `LLM_CONTEXT_PLAN.checkpointChars` is `0` (§10.1) and describes the request actually sent.
The event is `CONVERSATION_CHECKPOINT_SKIP reason=PROVIDER_SHAPE_UNSUPPORTED` (§12). This is per request, not per
conversation: the working set, the Stream row and the JVM working checkpoint are provider-independent, so the same
conversation moved to an accepting provider gets its continuity back without regeneration.

**Step 6 protects the checkpoint in two helpers.** In `AgentConversationRehydrator`:

- `applyCoherentTranscriptBoundary` strips leading assistant rows so a restored transcript never starts with an
  assistant; step 5 places the checkpoint at index 0 as exactly such a row, and `rehydrateTranscript` calls this
  helper twice (before and after the character budget);
- `applyCharBudget` evicts from the head, so the checkpoint would be the first row it discards.

Both helpers recognize the injected checkpoint with `ConversationCheckpointCodec.isInjectedCheckpoint(...)` and skip
it; the boundary helper resumes its normal leading-assistant strip at the first row after the checkpoint. If the
character budget cannot fit the checkpoint plus at least one complete user-led pair, rehydrate drops the checkpoint
explicitly and logs it, rather than returning a checkpoint-only history with no transcript.

### 9.3 Clear and agent ownership

- A checkpoint before `historyClearedAt` becomes ineffective together with the messages; Stream rows are not deleted.
- The checkpoint row's `agentThing` must match the current AgentThing; a mismatch is logged and skipped.
- Thread ownership and ThingWorx permissions are enforced by the existing DataTable and Stream entry points; the
  checkpoint adds no second authorization layer.
- An ad hoc single-turn source generates no checkpoint, because there is no conversation identity to continue.

## 10. Planner and storage integration

### 10.1 Working representation

`ConversationCheckpointCodec.isInjectedCheckpoint(ChatMessage)` recognizes the injected checkpoint by its fixed prefix.
The planner, the storage trimmer and replay normalization:

- protect the latest checkpoint;
- delete any older working checkpoint;
- never count the checkpoint as a transcript pair or evidence batch;
- count its characters as fixed non-history overhead;
- when the checkpoint cannot fit together with the stable/current/active overhead, omit it from that provider request
  and log `CHECKPOINT_CANNOT_FIT`, then let the planner handle the remaining rows. A non-authoritative continuity
  enhancement never blocks an otherwise serviceable user request, and it is never dropped silently while continuity is
  claimed.

**How this lands in `ContextBudgetPlanner`.**

1. `Metrics.compute(...)` derives
   `historyBudgetChars = effectiveRequestCapChars - stableChars - toolSchemaChars - ephemeralChars
   - currentUserChars - activeBatchReserveChars - checkpointChars`, and `computeTranscriptChars` excludes the
   checkpoint from `transcriptChars`.
2. `buildProtectedMask(...)` marks the injected checkpoint index protected, together with the leading stable system
   row, the last user row, the active tool batch and the ephemeral bundle indices.

Both parts are needed: a protected row that still counted as transcript could never be dropped and could never fit,
and the planner would end in `ContextBudgetExceededException` (`CANNOT_FIT_AFTER_TRIM`).

`LLM_CONTEXT_PLAN` is emitted from the same `Metrics` fields; `checkpointChars` is appended after the existing fields so
positional log parsing stays stable (see [`../agent/context-compaction.md`](../agent/context-compaction.md)).

**Omission control flow.** `buildPlannedOutbound`:

1. computes `m0`;
2. if `m0.historyBudgetChars >= 0`, proceeds as usual — no change for a conversation without a checkpoint or with a
   checkpoint that fits;
3. if `m0.historyBudgetChars < 0` and the list contains an injected checkpoint, clears every injected checkpoint index
   in the `keep` mask, recomputes metrics over `materialize(messages, keep)`, logs `CHECKPOINT_CANNOT_FIT`, and
   continues into the normal fit check and drop passes with that mask;
4. if `historyBudgetChars` is still negative without the checkpoint — ordinary overhead genuinely exceeds the cap —
   throws `OVERHEAD_EXCEEDS_CAP` with the recomputed metrics.

**Index discipline.** The `keep` mask is authoritative over original indices; nothing is rebased.
`buildProtectedMask`, `tryDropOldestEvidenceBatch`, `tryDropOldestTranscriptPair`, `activeRange`, `lastUser`,
`taskStateInsertedIdx` and `utcClockInsertedIdx` all use coordinates of the original `messages` list, and
`materialize(...)` projects that list through the mask. Omission clears a bit in `keep`; it never removes the row from
`messages`, splices the list or recomputes an inserted index. The drop helpers already skip `!keep[i]`.

`checkpointChars` is derived from the message list under consideration (via `isInjectedCheckpoint`), not passed in, so
the recomputation in step 3 yields `checkpointChars = 0` for the materialized candidate and every nested
`Metrics.compute` call stays consistent with the subset it receives.

Normalization guarantees at most one working checkpoint. If more than one injected checkpoint were present, the
planner treats the newest as the candidate and clears the older ones first.

### 10.2 Post-turn ordering

The success path runs in this fixed order:

1. finish and strip per-turn mutations;
2. append the final assistant to the local message list and persist its Stream row;
3. compare the still-unmodified replay characters with the `llmContextMaxChars` storage cap;
4. only when over the cap, run one all-candidate Tier B replay-promotion pass;
5. run the checkpoint boundary dry run on the post-Tier-B list;
6. optionally generate, validate and install a checkpoint;
7. optionally append the checkpoint Stream row;
8. run the storage trim/assertion;
9. `_conversations.put`.

The checkpoint row always follows the final assistant row. All four paths share this policy through
`ConversationsReplayNormalization`; callers pass only their path-specific Stream parameters.

### 10.3 Per-round planning

The checkpoint does not replace the per-round planner. The planner still runs before every main model call, can still
drop historical evidence and transcript, and keeps its structured failure.

If the planner first needs to drop transcript in a later round of the same turn, no checkpoint is generated mid-turn.
An extremely tool-heavy current turn can therefore still fail closed; that is safer than compressing an unfinished
industrial tool batch.

## 11. Token accounting

The planner converts `providerInputTokenLimit` to characters with a fixed factor of 3.5. That approximation is not
stable for Chinese text, JSON/schema, UUIDs/timestamps or code, but no tokenizer is used; the existing character cap,
provider registry, rate-control cap and fail-closed behavior remain authoritative.

Checkpoint telemetry records the summary call's prompt and completion tokens and the before/after/checkpoint character
sizes (§12); `LLM_CONTEXT_PLAN` and `LLM_USAGE` record the main provider request's estimated characters and actual
tokens, so estimate accuracy can be compared from logs.

## 12. Telemetry

Bounded single-line events (`ConversationCheckpointEvents`):

```text
CONVERSATION_CHECKPOINT_CREATED conversationId=... requestId=... coveredRows=...
  retainedRows=... beforeChars=... afterChars=... checkpointChars=...
  evidenceRefs=... summaryCalls=... promptTokens=... completionTokens=...
  summaryDurationMs=...

CONVERSATION_CHECKPOINT_SKIP conversationId=... requestId=...
  reason=NO_TRANSCRIPT_DROP|PENDING_HITL|NO_SAFE_BOUNDARY|SUMMARY_INPUT_TOO_LARGE|
         MODEL_ERROR|INVALID_JSON|PROTECTED_VALUE|NO_SHRINK|STREAM_APPEND_FAILED|
         CHECKPOINT_CANNOT_FIT|WATERMARK_UNVERIFIED|NO_CONVERSATION_ID|
         PROVIDER_SHAPE_UNSUPPORTED
  summaryDurationMs=...
```

The events never contain checkpoint semantic text, raw payload, `PASSWORD` values, a cache repository path or full
tool arguments. `LLM_CONTEXT_PLAN` / `LLM_USAGE` remain the telemetry of record for the main provider request.

The skip event is emitted by three components: post-turn normalization for every generation-side reason,
`ContextBudgetPlanner` for `CHECKPOINT_CANNOT_FIT` and `PROVIDER_SHAPE_UNSUPPORTED`, and
`ConversationCheckpointRehydrate` for `WATERMARK_UNVERIFIED`.

**`summaryDurationMs`** is the wall-clock time of the single `turnLlm.chat(...)` call only — not boundary selection,
validation or the Stream append. It carries the measured duration on every event where the summary call started,
whatever followed: `CREATED`, `MODEL_ERROR`, and equally `INVALID_JSON`, `PROTECTED_VALUE`, a post-call `NO_SHRINK` and
`STREAM_APPEND_FAILED`, each of which cost the user the same latency as a success. It is `0` only for reasons that skip
before the call: `NO_TRANSCRIPT_DROP`, `PENDING_HITL`, `NO_SAFE_BOUNDARY`, `SUMMARY_INPUT_TOO_LARGE`,
`NO_CONVERSATION_ID`, `CHECKPOINT_CANNOT_FIT`, `PROVIDER_SHAPE_UNSUPPORTED` and `WATERMARK_UNVERIFIED` (the last three
are planner- and rehydrate-side). The field is always present, so the line stays positionally stable. It is the
measurement behind the §8.4 latency trade-off.

Distinct reasons for distinct causes:

- `PROVIDER_SHAPE_UNSUPPORTED` versus `CHECKPOINT_CANNOT_FIT`: the latter means the checkpoint did not fit a budget
  (look at caps and sizes); the former means the provider's request contract cannot carry the shape at all, which no
  cap change fixes.
- `NO_CONVERSATION_ID` versus `NO_SAFE_BOUNDARY`: the former is a missing identity (check how the turn was invoked);
  the latter is a malformed or unverifiable message shape.

## 13. Implementation map

All classes are in `parler-agent/src/main/java/com/thingworx/things/agent/` unless noted.

| Concern | Class |
|---|---|
| Envelope model, liveness and `evidenceFormat` tokens | `compaction/ConversationCheckpoint` |
| Codec, caps, injected prefix, semantic parsing, protected-value scan | `compaction/ConversationCheckpointCodec` |
| Semantic model and deterministic shrink | `compaction/ConversationCheckpointSemantic` |
| Evidence manifest (§6.2 allowlist) | `compaction/ConversationCheckpointEvidenceManifest` |
| Summary request and call (§8.2) | `compaction/ConversationCheckpointGenerator` |
| Boundary dry run (§7.2) | `compaction/ConversationCompactionBoundarySelector` |
| Post-turn entry (§8.1.1, §10.2) | `compaction/ConversationsReplayNormalization` |
| Working-set installation | `compaction/ConversationCheckpointInstaller`, `compaction/ConversationCheckpointWorkingSet` |
| Stream persistence | `compaction/ConversationCheckpointPersistence`, `AgentMessageStreamAppender.appendContextCheckpoint` |
| Rehydrate selection and watermark walk (§6.1.1, §9.2) | `compaction/ConversationCheckpointRehydrate`, `AgentConversationRehydrator` |
| Planner accounting and omission (§10.1) | `compaction/ContextBudgetPlanner` |
| Events (§12) | `compaction/ConversationCheckpointEvents` |
| UI history skip (§9.1) | `AgentMessageStreamHistoryExporter` |
| Cache liveness re-check | `cache/ArtifactCacheLiveness` |

## 14. Verification

### 14.1 Behavior covered by tests

The focused tests (`parler-agent/src/test/java/.../compaction/ConversationCheckpoint*Test`,
`ConversationCompactionBoundarySelectorTest`, `ConversationCheckpointIntegrationTest` and the planner and rehydrator
tests) cover:

- no transcript drop → no summary call; evidence-only drop → no summary call; transcript drop → one valid checkpoint +
  exact tail;
- the summary request uses the turn's `LlmClient`, empty tools / tool choice none, temperature 0, null model override,
  2048 requested output tokens and exactly one direct `chat` call; Provider failure or timeout is not retried;
- a payload at or below the effective 100000-character cap runs; over-cap fixed overhead or complete prefix skips
  before the model call with `SUMMARY_INPUT_TOO_LARGE`;
- repeated compaction updates the prior checkpoint and keeps one working copy;
- the model cannot forge `source`, `evidenceRefs` or `generated`;
- the evidence manifest accepts only the §6.2 allowlist, derives tool identity from paired messages, revalidates
  carried refs, and rejects unknown, unmarked, raw, error, `PASSWORD`-bearing and member-only inputs;
- invalid JSON, timeout, oversize, protected value and no-shrink all fall back without losing the final answer;
- assistant/tool batches stay atomic by id; pending HITL and an incomplete active turn are never summarized;
- a Stream append failure keeps current-JVM continuity, and a restart falls back;
- the newest post-clear, agent-matching `context_checkpoint` row is the sole candidate; pre-clear and mismatched rows
  are filtered before selection; a candidate that fails validation falls back to transcript-first without trying an
  older checkpoint;
- the watermark walk accepts across zero, one and several `ui_feedback` rows; it rejects with `WATERMARK_UNVERIFIED` on
  each non-inert interleaving (`user`, `tool`, tool-calling assistant, a different final assistant, unknown role), on
  `agentThing` mismatch, and when inert rows push the watermark outside the bounded window — without a widened or
  second query;
- cache liveness is recomputed from the current-JVM lookup only;
- UI history JSON contains no checkpoint row or new wire field, and segmentation creates no spurious turn or pending
  tail;
- Stream usage export, collection and evaluation ignore the checkpoint role; the evaluation trace-completeness check
  passes with a trailing checkpoint row and still fails on a missing or mismatched final assistant or an unknown
  trailing role;
- all four §8.1.1 paths use the same normalization, and `AgentThing` has no direct `maybeTrimForStorageBudget` call;
- a within-cap turn leaves every historical Tier B-eligible tool-result body byte-identical; an over-cap turn runs Tier
  B before the checkpoint/trim recomputation;
- the `isArtifactCacheTerminal` (`Status.ERROR`) calls generate no checkpoint; `finishPlaybookSlashTurn` performs no Tier
  B, no storage trim and no checkpoint;
- a conversation at the cap with a checkpoint still plans; the checkpoint is omitted with `CHECKPOINT_CANNOT_FIT` only
  when the fixed overhead leaves no room; overhead that exceeds the cap without the checkpoint still fails with
  `OVERHEAD_EXCEEDS_CAP`; omission leaves all original indices valid;
- `applyCoherentTranscriptBoundary` and `applyCharBudget` each preserve the checkpoint and drop it with a log when no
  complete user-led pair fits beside it;
- generation is skipped when `isReplayCompactionEffective()` is false;
- `source.throughAssistantMessageId` equals the id minted for the turn's final assistant row;
- `summaryDurationMs` carries a real duration after the call started and `0` for pre-call skips;
- each §8.3 reject rule fires on a positive fixture and stays silent on its near miss; `retainedTail` prose is not
  scanned;
- the boundary selector and the storage trimmer produce the same evidence-first removal plan and materialized rows for
  the same input.

### 14.2 Operator checks on a ThingWorx server

After installing the extension:

1. A long conversation that crosses the storage cap still continues its earlier goal, constraints and decisions.
2. UI history looks the same as before and shows no internal row.
3. After a restart, the latest checkpoint + recent tail is used.
4. After `ClearConversation`, the old checkpoint no longer applies.
5. A live handle keeps working in the same JVM; after a restart an old handle asks for a re-fetch or recompute.
6. A pending approval is never skipped by a checkpoint.
7. `CONVERSATION_CHECKPOINT_CREATED` / `_SKIP` lines in the Application Log can be correlated with the existing context
   telemetry and contain no semantic or protected payload.
8. The Stream backend can write and read back a checkpoint TEXT row near the 250000-character limit; if it cannot, the
   completed answer is unaffected and a restart uses the bounded transcript fallback.
9. `summaryDurationMs` on the first checkpointing turns shows an acceptable added latency for the deployment.

## 15. Out of scope

- ArtifactCache quota, capacity eviction, sweeper, retention automation, persistent catalog, startup scan, sidecar
  recovery or durable handle identity.
- Exposing FileRepository paths, or letting the model read arbitrary raw history or artifacts.
- Persisting live `AgentTaskState`.
- A single free-text summary replacing server-authored evidence.
- Compressing the current user row, the active tool batch or pending HITL.
- A model-callable compaction tool.
- Cross-conversation or per-user memory, profiles, vector stores or knowledge bases.
- A provider-specific opaque checkpoint as the source of truth.
- Tool-schema lazy loading, sub-agent context architecture, provider overflow retry.
- UI checkpoint rendering, a new wire event or a contract bundle change.
