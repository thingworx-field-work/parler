# Topic round 1 — loading a generated method by reference

Superseded in one detail by topic-round5: from the agent version that ships topic-round5 the working file is topics/<chunkId>.md, not .txt.

Topic: `topic-round1`

This document is the proposed design and implementation guide for loading generated methods in the agent extension (`parler-agent/`). Section 2 describes the existing implementation; sections 3–6 specify the proposed behavior, landing paths, and tests. Symbol names are the implementation anchors; approximate line numbers are navigation aids only. Examples use invented names (`Demo_Unit_1`, `demo-guide`) except real repository classes and files.

**Design agreement gate:** The Implementor MUST NOT start coding until every User/orchestrator-designated Reviewer agrees with this design in the latest pushed design-agreement round's `### Direction Verdict`. Each Reviewer MUST state `Fresh-thread readiness: PASS` or `Fresh-thread readiness: REDIRECT` and an explicit `continue`, `redirect`, or `stop` direction. Implementation requires unanimous readiness PASS and agreement to continue; absence of a review is not agreement. This design proposes one implementation phase covering R1–R5 together. The change-list groups are an implementation order, not separately approved phases. Splitting the scope into phases requires an agreed design revision identifying each phase's guarantees and tests. Once agreement is complete, the existing topic launch authorizes implementation without another approval request, subject to the User's pause instructions and repository integration rules.

## Fresh-thread implementation context

Before changing files, the agent MUST read repo-root `AGENTS.md` and `docs/review-framework.md`.

- **BUSINESS_CONTEXT:** An external authoring tool produces task-sized methods from manuals. A runtime user needs to discover one through asset-scoped document search, load its complete instructions without registering thousands of skills, and execute already-registered tools across human approvals. The pilot failure in §2.11 motivates a class-level fix for any non-document method, not a rule for the synthetic demo tools.
- **CURRENT_IMPLEMENTATION:** `AgentThing.newAgentLoopForTurn` applies tool admission, then `DocumentTurnToolNarrowing`, then message budgeting. Document calls can therefore hide operational tools even after a checklist-less skill is loaded. `TaskProgressV1bDynamicMerge` tracks successfully merged checklists, not all loaded skills. `get_document_chunk` returns truncated JSON evidence; it cannot carry the complete method. `DocumentKnowledgeIndexCache.LoadResult.empty()` currently discards whether an index rebuild was denied or failed otherwise; `DocumentKnowledgeRuntime.resolveIndex` then collapses it to an empty Optional. The loader needs the bounded classified-result path in R2 rather than the sibling tools' repository-resolvability fallback. `AgentTaskState`, `PendingApprovalRecord`, and `ContextBudgetPlanner` are the state, approval, and retention landing points described in §2.
- **TERMS_AND_CONTRACTS:** A topic is an indexed `contentType: "topic"` chunk plus one plain-text file; `(docId, chunkId)` is its reference. An active method is turn-local recognition state, distinct from a checklist. `docs/agent/document-chunk-tools.md` owns document settings and registration semantics; `docs/agent/configuration-repository.md` describes the diagnostic runtime snapshot; `docs/agent/LLM_CONTEXT.md` separates routing guidance from tool call semantics. This topic adds no UI/AlwaysOn wire shape. Any necessary normative `CONTRACTS/*.md` edit still requires its same-commit bundle-version bump and aligned implementation/tests.
- **DEPENDENCIES_AND_HANDOFFS:** The runtime consumes the existing document index, configured FileRepository, current-user reader, and registered tool definitions. The external generator owns the chunk row and `topics/<chunkId>.txt` as one package revision (§5); no generator checkout, driver configuration, new platform service, or automated live gate is required. The producer/consumer handoff is this package layout plus `GetAgentRuntimeSnapshot.features`; these MUST be documented in the canonical paths listed in §7 before implementation closes. No prior conversation or review packet supplies implementation knowledge.
- **SCOPE_AND_BOUNDARIES:** Implement R1–R5 and P1–P6 as one phase. Preserve upstream admission modes, extended-tool registration, permission/capability/HITL gates, checklist parsing, provider adapters, and approval-batch behavior. Retain topic bodies, not dynamically loaded skill bodies. No generator, UI, release infrastructure, ranking adjustment, or product-version/changelog work is part of this topic.
- **CARRIED_DECISIONS:** Use a separate `load_topic` built-in under the existing document-tool switch; return bounded plain text; keep active-method ids separate from dynamic checklist ids; protect the newest successful load for each active topic in the current turn; carry state in every pending-record copy. §3 defines validation, failure behavior, scope, and budget exhaustion. §8 resolves the draft's implementation choices.
- **IMPLEMENTATION_LANDING:** State and hooks land under `parler-agent/src/main/java/com/thingworx/things/agent/taskstate/`; loader/schema/registration/narrowing/pending code under `tools/`. `tools/DocumentKnowledgeIndexCache` MUST retain per-call index failure classification in `LoadResult` through `rebuildIndex`/`getOrLoad`; a new `DocumentKnowledgeRuntime.resolveIndexForTopic` helper MUST consume it without the lossy sibling `resolveIndex` adapter or a diagnostic re-probe. Dispatch, continuation seeding, and snapshot wiring land in `AgentThing`; retention in `compaction/ContextBudgetPlanner`; routing in the existing resource file. Tests land under matching `src/test/java` packages with a synthetic document package under `src/test/resources/document-knowledge/topic-round1/`. §4 gives symbols and ordering; §7 names the canonical documentation handoff.
- **VERIFICATION_AND_CLOSE:** Local acceptance is P1–P6 plus `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`, using only the User-maintained libraries and compatible local Java. Missing prerequisites are reported, not replaced. The User alone imports the ZIP and performs the §9 live checklist; pending or supplied live outcomes are recorded separately from local acceptance. Implementation changes MUST be pushed and submitted for review per the framework before stopping. Topic archival and integration/version cutting remain User/orchestrator operations.

## 1 What this change is for

A development-side tool generates **topics**: each topic is a task-sized piece of method (what to read, which tools to call in which order, how to answer) derived from a manual page. The agent must be able to find a topic with the existing scoped document search, **load it by reference**, and follow it, using tools that are already registered, including tools that need a human approval.

A pilot of this runs today with a generated **skill** and three **extended tools**. The pilot exposed two defects in the run side, and this change fixes them while adding the loader:

1. **The tools vanish.** After the model searches documents and reads a page, the model's tool list is narrowed to four document tools (`DocumentTurnToolNarrowing`, section 2.6). A skill without a checklist fence is not even recorded as active, so the narrowing does not see it. In live runs the model then answered that "the log tools are not available". A run only passed when the model happened to call `resolve_thing` first in the turn (section 2.11).
2. **No loader for a method that is not a registered skill.** A skill must be a registered file, at most 100 of them, listed in every prompt (`SkillRegistryCatalogFormatter`). Thousands of generated topics cannot be skills.

### In scope (round 1)

- **R1** A search hit for a topic chunk can be loaded: the hit already carries `docId` and `chunkId`; nothing new is invented for the reference.
- **R2** A new built-in tool `load_topic(docId, chunkId)` returns the topic's working text **whole**, as plain text.
- **R3** Loading records an **active method** for the turn. While a method is active, document-turn narrowing does not apply; the newest successful body for each active topic stays in outbound requests, subject to the existing explicit budget-failure path; and recognition survives approval pause/resume. Skill-body retention is excluded (§3 R4).
- **R4** A loaded registered skill other than `document_search` is an active method too, whether or not it has a checklist fence (this is the minimal fix of the defect above). A non-document skill loaded by slash command counts as well.
- **R5** `GetAgentRuntimeSnapshot` returns a top-level `features` array so that the development side can tell what this runtime does.

### Out of scope (do not do these)

Per-topic admission or removal of tools (the registered tool set is not changed by loading); changing `toolAdmissionMode` or existing admission policy/signals/core sets (the new loader's `DOCUMENTS` membership is in scope); the approval-batch behaviour (siblings of a gated call are skipped by design, see 2.8; leave it); using the task checklist as a completion guarantee; comparing capability versions; enforcing the source link in the answer; multi-part loading; durable active-method state across process restarts or new turns; playbook orchestration of topic methods; a durable investigation record; any UI or wire change; any new setting.

## 2 Background you need

### 2.1 What the extension is

`parler-agent/` is a Java ThingWorx extension (Gradle with a Java 21 toolchain, ThingWorx 10.x libraries from `parler-agent/twx-lib/all`). The Thing `AgentThing` (`src/main/java/com/thingworx/things/agent/AgentThing.java`, with base class `AgentBaseThing`) is the agent: it receives a user message, runs a loop of model calls and tool calls, and returns an answer. All paths below are relative to `parler-agent/src/main/java/com/thingworx/things/agent/` unless they start with `src/test` or `docs`.

Two ways in:

- **AlwaysOn / Parler gateway** (`AgentThing.ParlerStreamToRemoteThing`): the interactive UI path. It streams, has a host context, and supports **human-in-the-loop approval (HITL)**.
- **`Chat` / `ChatAsync`**: synchronous service paths with no UI. They never pause for approval; a tool that needs approval is refused there.

### 2.2 One turn, step by step

1. A user turn begins. In each path the code sets `AgentToolContext` fields, calls `primeAgentTaskStateForTurn` (creates the per-turn `AgentTaskState`), calls `DocumentTurnToolNarrowing.resetTurnState()`, then `loop.run(...)` (`AgentThing.java` lines about 467 for `Chat`, 642 for `ChatAsync`, 1056 for the gateway).
2. `getMergedToolDefinitions()` (AgentThing, about line 3916) builds the full tool list **once per turn**: every registered built-in (`BuiltInTools.registerAll`), `start_playbook` if a playbook is loaded, and every extended tool from `/tools/extended_tools.json` in the configuration repository that is model-advertisable. `get_agent_skill` is left out when no skill is registered.
3. `AgentLoop.runInner` (`AgentLoop.java`, about lines 600-700) repeats **rounds**. Each round starts by deriving the list for that round:
   `merged` → `applyToolAdmissionForRound` (modes `off`/`narrow`/`lazy`; see §2.3 for unmapped names) → `DocumentTurnToolNarrowing.filterForRound` → (`ContextBudgetPlanner` prunes **messages**, not tools) → `LlmChatRequest.forAgentRound`. The wiring is in `AgentThing.newAgentLoopForTurn` (about line 3996).
4. The model answers either with text (the turn ends) or with tool calls. Each call is dispatched by `AgentThing.dispatchExecuteToolCallWithoutRepetitionGuard` (about line 4256). Its first statement is `DocumentTurnToolNarrowing.recordToolInvocation(fn)`. It then applies HITL gating, runs the tool (`_toolRegistry.executeTool` for built-ins; the extended-tool path for extended tools) and runs post-hooks (`AgentTaskStateHooks`, `TaskProgressV1bHooks`, and for `get_agent_skill` `TaskProgressV1bDynamicMerge.afterGetAgentSkill`; two call sites, about lines 4296 and 4407).
5. The tool result goes through `AgentLoop.appendCompactedExecutedToolResultToMessages` → `ToolResultEgressGateway.compactForLlmAppend` (section 2.7) and is appended as a `TOOL` message (`ChatMessage.toolResult(id, content, executedToolName)`).
6. Next round.

### 2.3 Tools

- **Built-in tools** are registered in `tools/BuiltInTools.registerAll(registry, advertiseLegacyDiscovery, documentKnowledgeBuiltinsEnabled)` (about lines 85-176). The three document tools are registered only `if (documentKnowledgeBuiltinsEnabled)` (about line 166).
- **Extended tools** are service calls on other Things, declared in `/tools/extended_tools.json` of the agent's configuration repository (`configrepo/ExtendedToolsManifest`). Each entry has `name`, `title`, `whenToUse`, `target {entityName, serviceName}`, `hitl` (default true: approval needed; `false` bypasses). They are registered when the agent refreshes its prompt-context cache and are then in the merged list on **every** turn. An entry that fails validation is skipped with a log line.
- Names of built-ins are reserved automatically (`tools/ReservedBuiltinToolNames.fromRegistry`), so an extended tool cannot reuse a built-in's name.
- `tools/ToolBuckets` maps tool names to admission buckets; an unmapped name falls into `OTHER`, which `narrow` admission keeps. `lazy` admission can defer even an unmapped tool unless it is core, required, or already schema-loaded. This topic does not change either policy (`docs/operations/tool-schema-admission-control.md`).

### 2.4 Skills

- A skill is a file `/skills/<shortId>/SKILL.md` in the configuration repository: an optional leading key/value front matter (`name`, `title`, `when_to_use` or `description` are read; other keys are **ignored**) and a body (`skillregistry/SkillMarkdownParser`). At most 100 skills, each at most 100,000 characters.
- Every registered skill is listed in the stable prompt as metadata only (`SkillRegistryCatalogFormatter`). The body is loaded when the model calls the built-in `get_agent_skill {"skill_name": "<shortId>"}` or the user types `/SkillName`.
- `tools/GetAgentSkillExecutor` returns the body as **plain text** on success and a JSON error `{status:"error", code, message}` on failure.
- After a successful `get_agent_skill`, `TaskProgressV1bDynamicMerge.afterGetAgentSkill` records the skill in `AgentTaskState.dynamicSkillShortNamesInOrder` **only if the body has exactly one valid `parler-task-checklist-v1` fence** (zero fences: log `DYNAMIC_SKILL_NO_CHECKLIST` and return). A skill without a fence is therefore invisible to code that asks which skills are active.

### 2.5 Document knowledge

- A document package lives in the FileRepository named by the agent setting `documentKnowledgeRepository`, under `documentKnowledgeRootPath` (default `/document-knowledge`), as `<root>/<docId>/`. Files: `manifest.json`, `source/original.pdf`, `markdown/manual.md`, `chunks/chunks.jsonl`, optional figures and page images (`tools/DocumentKnowledgePaths`, `tools/DocumentKnowledgePackageManifest`).
- Only `manifest.chunksPath` (normally `chunks/chunks.jsonl`) is read by the indexer. One JSON object per line. Required: `docId` (must equal the manifest's), `chunkId`. Optional: `contentType`, `heading`, `sectionPath[]`, `pageStart`, `pageEnd`, `tags[]`, `signals[{kind,name}]`, `summary`, `markdown` (`tools/DocumentKnowledgeChunk.parseJsonLine`). Existing content types: `page` (ids like `page-0001`), `section`, `troubleshooting`; the field is free text.
- `tools/DocumentKnowledgeIndex.load` builds the in-memory index; `DocumentKnowledgeIndexCache` keeps it for `documentKnowledgeIndexTtlSeconds` (default 300) and rebuilds lazily. There is no refresh service: a new chunk is searchable after the TTL.
- **Index failure information today.** `DocumentKnowledgeIndexCache.getOrLoad` returns the same `LoadResult.empty()` for a permission-denied rebuild and for another failure without a stale candidate. A denied rebuild returns no index, does not serve stale data, and leaves any shared cache entry untouched. A non-permission failure may serve an existing stale index. `DocumentKnowledgeRuntime.resolveIndex` loses the no-index failure reason; sibling tools fall back to `repositoryUnavailable`, which checks repository Thing resolution, not permission to invoke `BrowseDirectory`/`LoadText`. A resolvable repository can therefore yield `DOCUMENT_TOOL_INTERNAL_ERROR` after a denied index read. R2 preserves the loader's distinct denial mapping through an internal classified result; it does not change those siblings' existing error behavior.
- Ranking (`DocumentKnowledgeSearchScorer`, `DocumentKnowledgeBm25`) is lexical: signal names, heading phrases, tags, tokens in heading/summary/markdown. Ties are broken by `CONTENT_TYPE_RANK` (troubleshooting 0, maintenance 1, section 2, page 3, unknown 50).
- Built-in tools: `resolve_document_set {key}` (the documents that apply to an asset), `search_document_chunks {query, signals, documentIds, limit, ...}`, `get_document_chunk {docId, chunkId}` (`tools/DocumentKnowledgeToolSchemas`, `DocumentKnowledgeToolsExecutor`, logic and result JSON in `DocumentKnowledgeRuntime`). A search match has `docId, chunkId, heading, sectionPath, contentType, pageStart, pageEnd, score, snippet, sourceLinks[{label, repository, path, page, href}]`. `get_document_chunk` returns `{status, docId, chunkId, heading, ..., markdown, sourceLinks}` with `markdown` cut to `chunkMaxChars` (default 6000).
- **Scope.** On the gateway path, when the host context names a Thing, `AgentThing.maybeInjectHostContextDocumentScope` stores the applicable document ids in `AgentToolContext.setInjectedDocumentScope(ids, source)` (ThreadLocals). `search_document_chunks` uses it as a **default** `documentIds` filter. `get_document_chunk` does **not** check it.
- `DocumentSearchProgressGuard` is a per-turn loop breaker for document search; any tool name outside `DocumentTurnToolNarrowing.NARROWED_TOOL_NAMES` calls `DocumentSearchProgressGuard.onNonDocumentTool()` and disables it for the turn.

### 2.6 Document-turn narrowing (the defect)

`tools/DocumentTurnToolNarrowing` exists to keep a pure document Q&A turn small. Per round, `filterForRound` keeps only `NARROWED_TOOL_NAMES = {get_agent_skill, search_document_chunks, get_document_chunk, resolve_document_set}` when `shouldNarrow` is true (lines about 115-126):

```java
if (!narrowingEnabled) return false;                    // setting documentTurnToolNarrowingDisabled
if (nonDocumentToolInvokedThisTurn()) return false;     // ThreadLocal latch
if (isOnlyDocumentSkillActive()) return true;           // active set is exactly {document_search}
return iterationOneBased >= 2 && documentToolInvokedThisTurn();   // ThreadLocal latch
```

The two latches are `ThreadLocal`s cleared by `resetTurnState()` at turn entry. `recordToolInvocation` flips them: `search_document_chunks` and `get_document_chunk` set the document latch; a name **outside** `NARROWED_TOOL_NAMES` sets the non-document latch; `get_agent_skill` and `resolve_document_set` are members of that set and set **neither** latch (this is why loading a skill does not by itself stop the narrowing). The last line is the problem: from round 2, once a document tool has run and no other tool has, the list is narrowed **whatever skill is loaded**, and the model can no longer see registered extended tools. `isOnlyDocumentSkillActive` reads the slash skills plus `AgentTaskState.getDynamicSkillShortNamesInOrder()` (see 2.4 for why a skill without a checklist is not there).

### 2.7 Result egress

`ToolResultEgressGateway.compactForLlmAppend` (lines about 100-130) decides what the model sees of a tool result, by shape, not by tool name:

- Result of 8,192 characters or fewer with **no `[` character**: unchanged.
- Otherwise it tries to parse JSON. JSON: every string value longer than 2,000 characters is excerpted to 2,000 (so a JSON field with a 3,500-character text is cut, even if the whole result is under 8,192, as soon as the result contains a `[`; every `get_document_chunk` result does, through `sectionPath[]` and `sourceLinks[]`); arrays are sampled to 20; if still over 8,192 further passes shorten more.
- Not JSON: up to 8,192 characters unchanged; **over 8,192** it is replaced by `{"status":"success","resultKind":"LARGE_TEXT_COMPACT","contentExcerpt":<first 2000>,"truncated":true,"rawChars":N}`.

So `get_document_chunk` cannot carry a method text of a few thousand characters whole, and a plain-text result under 8,192 characters can. `get_agent_skill` works for that reason. The loader in this change returns plain text under that cap.

### 2.8 Human approval (HITL) and resume

- A gated call (extended tool with `hitl` not false; `invoke_service`; `set_property_value`) on the gateway path runs `tryEnqueueParlerHitlPending` → `ParlerHitlStreamScopedEnqueue.enqueueOrThrow`, which stores a `PendingApprovalRecord` (`tools/PendingApprovalRecord`, 15-minute TTL) in `PendingApprovalStore` and throws `ApprovalPendingException`; `AgentLoop` catches it and ends the turn with `AWAITING_APPROVAL`. Other tool calls of the same model step are **skipped by design** (result `HITL_PAUSE_INTERRUPTED_BATCH`, "reissue after the approval outcome if still needed"); do not change this.
- The record keeps: a copy of the messages (`messagesCopy`, from `ParlerHitlPendingSnapshotHelper.messagesForPendingSnapshot`, ephemeral system rows stripped), the gated call, `slashSkillShortNamesSnapshot`, `dynamicSkillShortNamesSnapshot`, ids, principal, timezone and a chart-state snapshot.
- On approve/reject/cancel, `AgentThing.runParlerApprovalContinuation` (about line 1987) starts on a **new thread**. It appends the gated tool's result to a copy of `messagesCopy`, then `runParlerPostToolAgentLoop` (about line 1770) rebuilds everything: a **new** merged tool list and a new `AgentLoop` with the same filter, `ParlerHitlContinuationContext.bindForGatedToolExecution` (restores slash skills), a **new** `AgentTaskState` via `primeAgentTaskStateFromHistory` (about line 4170) which re-adds the dynamic skill names, then `loop.runAfterApproval`.
- **Lost on resume:** both `DocumentTurnToolNarrowing` ThreadLocals (not restored; `resetTurnState` is deliberately not called there), the document-search guard state, and everything in `AgentTaskState` except the dynamic skill names. Anything new that must survive a pause has to be added to `PendingApprovalRecord` and re-primed in `primeAgentTaskStateFromHistory`.

### 2.9 History budget

`compaction/ContextBudgetPlanner.planForProviderRound` prunes messages each round. It drops the **oldest evidence batch** (an assistant message with tool calls plus its `TOOL` messages) first (`tryDropOldestEvidenceBatch`), then the oldest user/assistant prose pair. Protection (`buildProtectedMask`, about lines 516-553) covers: the stable first system row, the last user message, the **active** (latest) assistant tool batch, the task-state and clock injections, the newest checkpoint and the ephemeral system rows. A batch is dropped only if none of its messages is protected. There is **no protection by tool name**: a `get_agent_skill` or `load_topic` result is an ordinary tool message and can be dropped once a later tool batch exists and the budget is tight.

### 2.10 Runtime snapshot

The service `GetAgentRuntimeSnapshot` (`AgentThing`, about line 2750; builder `buildAgentRuntimeSnapshotJson`, about lines 2978-3266) returns one JSON object with the top-level keys `agent`, `configurationRepository`, `prompt`, `skills`, `tools`, `policies`, `taxonomy`, `playbooks`, `playbookRuntime`. It is operator and authoring diagnostics. `docs/agent/configuration-repository.md` (about lines 344-346) says additive fields do not need a `CONTRACTS` bump. No unit test asserts its keys (it needs a ThingWorx runtime).

### 2.11 The observation behind this change

In the pilot, turn 1 of a conversation resolved the unit. In turn 2 the model loaded the skill, called `resolve_document_set`, `search_document_chunks`, `get_document_chunk` — and then answered without calling any of three registered extended tools, saying they were not available in its tool set. The runtime snapshot listed the tools as registered. Cause: the document latch was set, the non-document latch was not (turn 2 never called a non-document tool; the skill had no checklist fence), so from round 2 the list was narrowed to four tools. Runs that happened to call `resolve_thing` in turn 2 before the document tools passed, because that call sets the non-document latch. A workaround exists in the skill text (call `resolve_thing` first); this change makes it unnecessary.

## 3 Required behaviour

### R1 The hit is loadable

A topic is stored as one chunk of an existing document package with `contentType: "topic"` (section 5.1). The existing search finds it and returns `docId` and `chunkId`; that pair is the reference. No change to the search result shape is required. Keep the existing lexical scoring and unknown-content-type tie rank; ranking changes are outside this phase. A synthetic retrieval test MUST prove a relevant topic is searchable with the current scorer, without promising it will outrank every article page.

### R2 `load_topic(docId, chunkId)`

A new built-in tool, registered next to the other document tools (same `documentKnowledgeBuiltinsEnabled` flag, within the existing `enableBuiltInTools` registration gate). Its parameter schema is an object with required string fields `docId` and `chunkId`, matching the sibling tool-schema conventions. It MUST implement the following order:

1. Parse an object containing both non-blank strings; malformed JSON, a non-object, missing/null/non-string fields, or invalid ids yield JSON `BAD_REQUEST` before any index or file access. Each id MUST be 1–256 Java UTF-16 code units, contain no `..`, `/`, `\`, ISO control characters, or Unicode line/paragraph separator, differ from `.`, and have no leading/trailing whitespace. Use exact ids without trimming, case folding, URL decoding, or path normalization of the ids. These are loader-local constraints; do not change the existing chunk parser or other document tools.
2. If the injected document scope is non-empty (`AgentToolContext.getInjectedDocumentScopeIds()`) and does not contain `docId`, return `TOPIC_OUT_OF_SCOPE` before lookup or file access. A null/empty scope means no restriction. This is a deliberate loader-specific restriction: search scope remains an overridable default and `get_document_chunk` remains unchanged. The injected scope is not a replacement for FileRepository permissions.
3. Resolve settings and the existing document index through the classified loader-specific path below. If no index is available, map the retained failure reason to the error table; do not treat a denied index read as an unknown failure or re-probe repository resolution to guess its cause. With an index, require an exact `(docId, chunkId)` match and `contentType == "topic"`; otherwise return `TOPIC_NOT_FOUND`. An arbitrary file that lacks the indexed topic row is never loadable. The loader uses existing document-tool error-code names with the explicit classification below; sibling tools' legacy failure mapping remains unchanged.
4. Read `<documentKnowledgeRootPath>/<docId>/topics/<chunkId>.txt` through `DocumentKnowledgeRepositoryReader.loadText` with a freshly resolved current-user `RepositoryReader`, including on an index-cache hit. Never fall back to the configuration/System reader, chunk `markdown`, or an earlier body when the read fails. `DocumentKnowledgePaths.join` handles only the configured root and already-validated path components.
5. Reject null/empty/whitespace-only text with `TOPIC_LOAD_FAILED`. If the text is longer than **7,000 Java UTF-16 code units** (`String.length()`), return `TOPIC_TOO_LARGE`. Preserve successful text exactly as returned by `LoadText`, including newlines and non-ASCII characters; never excerpt or trim it. The character limit bounds the returned text, not the platform service's read allocation; no new streaming/FileRepository subsystem is authorized.
6. Returns **plain text** (not JSON):

   ```text
   topic: <chunkId>
   document: <docId>
   sha256: <64 lowercase hex digits>   chars: <String.length() of working text>
   complete: true
   ---
   <the working text>
   ```

   Compute SHA-256 over the UTF-8 encoding of the exact decoded working text; this is a diagnostic of delivered text, not a raw-file byte hash or a revision-validation mechanism. The header uses LF separators. With both ids bounded to 256 code units, the fixed header plus body stays below 8,192 code units. Assert this bound before reporting success; any violation returns `TOPIC_TOO_LARGE`. The leading `topic:` text makes the response non-JSON even when the body contains `[` or JSON, so existing egress leaves it unchanged (§2.7). No egress special case is needed.
7. Only after all checks succeed, the built-in dispatch hook records the **active method** `topic:<docId>/<chunkId>` (R3). Runtime lookup and file reading MUST be testable with the existing index fixture and an injected `RepositoryReader`; that seam MUST NOT change production authorization.

All loader failures MUST return the existing document-style JSON shape `{status:"error", code, message, docId, chunkId}` with safe messages and nullable ids when input cannot be parsed. They MUST NOT record a method, replace a retained body, or expose repository exception details. The error code is deterministic at the first failing step:

| Condition | Code |
| --- | --- |
| Invalid arguments or ids | `BAD_REQUEST` |
| Valid id excluded by a non-empty injected scope | `TOPIC_OUT_OF_SCOPE` |
| Document repository not configured | `DOCUMENT_REPOSITORY_NOT_CONFIGURED` |
| Repository cannot be resolved/read as current user, including permission denial | `DOCUMENT_REPOSITORY_UNAVAILABLE` |
| Index cannot be obtained for another reason | `DOCUMENT_TOOL_INTERNAL_ERROR` |
| Indexed chunk absent or not exactly `topic` | `TOPIC_NOT_FOUND` |
| Indexed topic file missing, empty, or another non-permission read failure | `TOPIC_LOAD_FAILED` |
| Body or complete response exceeds its bound | `TOPIC_TOO_LARGE` |

#### Classified index loading (loader only)

The following internal changes are in scope solely to make the loader's promised error contract implementable:

1. Extend `DocumentKnowledgeIndexCache.LoadResult` with a per-call `failureReason()` enum: `NONE`, `PERMISSION_DENIED`, `REPOSITORY_UNAVAILABLE`, or `INTERNAL_ERROR`. A result containing an index, whether fresh or stale, has `NONE`; stale use remains indicated separately by `staleRebuildFailed()`. A result without an index has one of the three failure reasons. Preserve existing `index()`, `staleRebuildFailed()`, and `fresh`/`stale`/`empty` factory signatures; legacy `empty()` means `INTERNAL_ERROR`. Add a classified failure factory. The reason is response metadata, not a cached field, exception payload, tool result field, setting, or wire contract.
2. Make the private `rebuildIndex` path return a classified `LoadResult` to `getOrLoad`: an absent current-user reader yields `REPOSITORY_UNAVAILABLE`; a successful rebuild yields the index; an existing test rebuild hook returning null yields `INTERNAL_ERROR`. Classify caught exceptions at the existing catch boundary with `PlatformAccess.isPermissionDenial`, not exception-message matching. Denial yields `PERMISSION_DENIED`; another rebuild exception yields `INTERNAL_ERROR`. Preserve successful/degraded index construction, parsing warnings, TTL, keys, and rebuild-hook semantics; an index with no matching topic is still a successful index, not an index-load failure.
3. Keep cache behavior unchanged: a fresh hit returns its index without a repository read; a successful rebuild replaces the entry as today. A permission-denied rebuild MUST return no index immediately, even if an expired entry exists, with `staleRebuildFailed=false`; it MUST NOT serve, replace, delete, or negative-cache that shared entry. Other rebuild failures retain the existing stale fallback when a candidate exists; without a candidate, return the retained classified reason. Failure metadata MUST NOT leak into another user's later lookup.
4. Add `DocumentKnowledgeRuntime.resolveIndexForTopic(settings, agentName)`, returning the classified `LoadResult` from the same `getOrLoad` call. It MUST NOT pass through the existing Optional-based `resolveIndex`/`IndexResolution.of` adapter, which discards no-index reasons. If an exception escapes the cache call, classify permission denial the same way and another exception as `INTERNAL_ERROR`, without mutating the cache. R2 checks the unconfigured-repository condition before this helper. With no index, the loader maps `PERMISSION_DENIED` and `REPOSITORY_UNAVAILABLE` to `DOCUMENT_REPOSITORY_UNAVAILABLE`, and `INTERNAL_ERROR` to `DOCUMENT_TOOL_INTERNAL_ERROR`.
5. Keep the existing `resolveIndex`, `IndexResolution.of`, `repositoryUnavailable`, and sibling document-tool call/error paths unchanged. They may ignore the additional `LoadResult` metadata; this topic does not correct their historical denial/error mapping. The loader MUST NOT call `repositoryUnavailable` or perform another repository read/resolve probe to reconstruct a lost cause. Its R2 step 4 current-user body read remains required after a successful indexed match, including on cache hits; that actual content read is not a diagnostic probe.

Reuse existing stale-index behavior: a stale indexed topic may be loaded only if the current-user working-file read succeeds. A permission-denied rebuild cannot reach that read through stale fallback. Log stale-index use without changing the fixed success header. A successful search or an earlier user's cache entry never authorizes reading the topic body.

`load_topic` is repeatable: with unchanged package files it returns the same text and hash. The loader reads the working file on each call; the index TTL controls discovery, not a body cache. The reference is not a content-addressed snapshot. The external generator MUST publish a new chunk id for a changed revision and make the working file available before exposing the chunk row. Reloading after a new turn or process restart creates fresh turn state; it does not recover an expired approval.

### R3 The active method

Add to `AgentTaskState` a small ordered set `activeMethodIds` with a snapshot/restore API like the dynamic-skill one (`snapshotDynamicSkillShortNamesForPending`, `recordMergedDynamicSkillShortName`). Use `topic:<docId>/<chunkId>` and `skill:<shortId>` namespaces. Recording is idempotent, ignores null/blank ids, and preserves first-success order; getters/snapshots return defensive immutable lists. A restore replays ids into a fresh state, not a shared mutable collection. Do **not** reuse the dynamic-skill list: that list feeds the checklist merge on resume (`SkillChecklistContinuationMerge.unionForHitlContinuation`) and a topic or checklist-less skill in it would be treated as having a checklist. Do not serialize the new ids into task-progress UI/wire, checklists, checkpoints, or durable conversation state.

While `activeMethodIds` is non-empty, or a non-`document_search` slash skill is loaded, (a) and (c) hold for every kind of active method; (b) is limited to the loaded topic:

- (a) `DocumentTurnToolNarrowing.shouldNarrow` returns false. Put the check first after the `narrowingEnabled` test, so that it covers both the "sole document skill" branch and the "round ≥ 2 after a document tool" branch. This preserves the list supplied by the upstream admission stage; it MUST NOT re-admit tools hidden by `toolAdmissionMode=narrow/lazy`, expose executor-only/capability-suppressed tools, or bypass approval. With admission `off`, the supplied list is the full model-advertisable merged set. P1 runs with admission `off`; admission-policy regressions are checked separately in P5.
- (b) **Each active topic's complete working text** stays in every subsequent request of this turn, including approval continuations. In `ContextBudgetPlanner.buildProtectedMask`, protect the newest successful `load_topic` `TOOL` result **per active topic id**, together with the containing assistant/tool batch. Match `executedToolName`, a complete success header matching that topic id, and the assistant call's arguments/tool-call id. Consider only calls after the current turn's user message. A later failed load MUST NOT displace an earlier success. Repeated successful loads of one id replace only its retention selection; loading a different topic keeps both topics protected. Earlier-turn bodies MUST NOT become protected merely because a skill or the same topic is active now. Preserve assistant/tool pairing even when other results share the protected batch. No new provider-message role or metadata is introduced. Dynamically loaded `get_agent_skill` bodies remain ordinary evidence; slash-body injection keeps its own mechanism.
- (c) Recognition survives every approval pause: add an immutable id snapshot to `PendingApprovalRecord`, snapshot at enqueue in `ParlerHitlStreamScopedEnqueue.enqueueOrThrow`, and restore into the fresh `AgentTaskState` in `primeAgentTaskStateFromHistory` **before** the first continuation round filter and budget plan. Keep all existing public constructor signatures delegating with an empty list. `withInterruptedBatchSiblings` MUST preserve the new snapshot, record timestamps, chart snapshot, and all other existing fields. A continuation that pauses again MUST capture the restored ids. The protected topic batches already travel in `messagesCopy`; do not reload the file on approval or infer recognition from transcript text. Approve/reject/cancel retain their existing execution policy; recognition never turns a refused tool into an authorized one.
- (d) Recognition ends with the logical user turn: it is not reconstructed from earlier history on a new user message. Existing entry-point priming and `AgentToolContext.clear()` MUST prevent cross-turn/thread leakage. An approval continuation belongs to the same turn even though it uses a fresh thread/state. The model reloads by reference in a later turn. Existing narrowing/guard latches and their continuation behavior are unchanged; this fix MUST work with those latches initially false.

Protection is not an unlimited context guarantee. If the remaining protected batches and mandatory request content cannot fit, retain the existing `CANNOT_FIT_AFTER_TRIM` / `OVERHEAD_EXCEEDS_CAP` failure paths; do not silently drop/excerpt a topic, raise configured caps, or send an oversized provider request. Multiple loaded topics may reach that limit sooner. Existing timeouts, iteration/repetition guards, tool failures, and cancellation remain in force.

### R4 A loaded skill is an active method (recognition and tool preservation only)

After a successful `get_agent_skill` for any id other than `document_search`, record `skill:<id>` in `activeMethodIds`. Do it in a new small hook called from the same two dispatch sites as `TaskProgressV1bDynamicMerge.afterGetAgentSkill` (about lines 4296 and 4407), before or independent of its checklist early returns, and only for non-error, non-null results. Reuse the existing normalized skill-id extraction and explicit JSON-error-envelope classification; do not change skill loading/parsing semantics. A loaded body with no fence or an invalid/duplicate checklist still counts as a loaded method; checklist merge success is independent. A refused, failed, skipped, or repetition-blocked call does not count. This fixes activity recognition for skills, so document narrowing stops; it adds no retention guarantee for the skill body (R3(b)). A non-document slash skill is already visible through `AgentToolContext.snapshotSlashSkillShortNamesForPending()`; the narrowing check must treat any non-blank entry other than `document_search` as an active method. Unknown slash text that was not resolved as a registered skill does not count. Loading only `document_search`, dynamically or by slash, MUST preserve pure-document narrowing.

### R5 Features

`GetAgentRuntimeSnapshot` gets an unconditional top-level array `features` (strings). Put the computation in a small static class (for example `RuntimeFeatures`) so it can be unit-tested without a ThingWorx runtime. Names for this round:

| Feature | Publish when |
| --- | --- |
| `topic-load/1` | The Java built-in `load_topic` is actually registered (both built-in registration gates enabled). A same-named extended tool does not qualify |
| `active-method-tools/1` | R3(a)–(d) and R4 are implemented: document narrowing preserves the upstream-admitted tools for active methods, recognition survives approval, and active topic bodies are retained or the existing context-budget failure is reported. It does not promise skill-body retention or override admission/approval policies |

A feature is listed only if its guarantee is implemented. Emit deterministic ordering: `topic-load/1` when registered, then `active-method-tools/1` unconditionally in the completed implementation. The latter describes installed behavior for any successfully loaded method, even when document built-ins are disabled or no method is currently active. The top-level array is always present regardless of snapshot include-options, prompt-cache readiness, repository configuration, or whether any topic file exists. Derive built-in availability from the actual Java registration, not merely the document setting or a model-facing extended-tool name. Unknown skill front-matter keys (for example `requires_tools`) remain ignored and MUST NOT be advertised as features.

## 4 Change list

After design agreement, do the work in this order as one functional phase. Steps 1–5 are dependent; do not advertise either feature until its whole guarantee is implemented. Keep related code/tests/docs in coherent slices, run focused tests while iterating, and run the full local command in §7 on the finished phase. Fix affected registration assertions with their change. Submit every pushed unreviewed slice per the framework; implementation grouping does not create an approval gate beyond the agreed design.

1. **State API first.** `taskstate/AgentTaskState.java`: `activeMethodIds` (LinkedHashSet), `recordActiveMethod(String)`, `getActiveMethodIds()`, `snapshotActiveMethodIdsForPending()`, and a restore method used by production continuation seeding and unit tests. Restoring MUST initialize the fresh state from a defensive snapshot without touching checklist ids.
2. **Narrowing.** `tools/DocumentTurnToolNarrowing.java`: add `load_topic` to `NARROWED_TOOL_NAMES` (so it stays visible in a sole `/document_search` turn and sets neither latch). Add `activeMethodPresent()` (reads `AgentToolContext.getAgentTaskState().getActiveMethodIds()` and the slash skills; any slash skill other than `document_search` counts) and call it at the top of `shouldNarrow`, after the `narrowingEnabled` test. Update `logNarrowingSkipped` with the new reason.
3. **Loader, registered, with its assertions.** `tools/DocumentKnowledgeToolSchemas.java` (`loadTopicDef()`; schema with `docId` and `chunkId`, both required; the description states that it loads the complete method text of a search hit whose `contentType` is `topic`, the size/id constraints, and that loading grants no tool permission), `tools/DocumentKnowledgeToolsExecutor.java` (`executeLoadTopic`), `tools/DocumentKnowledgeRuntime.java` (R2, with an injected-reader unit-test seam), `tools/BuiltInTools.java` (conditional registration; update Javadoc). Reuse `DocumentKnowledgeIndex.findChunk`, `DocumentKnowledgePaths.join`, `DocumentKnowledgeRepositoryReader.loadText`, and the `getError`-style JSON shape. Resolve `FileRepositoryThingResolver.resolveForCurrentUser` and use `FileRepositoryRepositoryReader.forCurrentUser` as the existing index reader does; handle an empty Optional rather than calling `.get()` unchecked. No platform shim/service/REST probe is required. In the same step update registration assertions (section 6, last paragraph).
   In `tools/DocumentKnowledgeIndexCache.java`, extend `LoadResult` and its private rebuild/result propagation as R2 specifies; preserve shared-cache/stale behavior and all existing public signatures. In `DocumentKnowledgeRuntime.java`, add `resolveIndexForTopic` to carry that classification directly to the loader without changing sibling `resolveIndex`/`IndexResolution.of`/`repositoryUnavailable`. Extend `tools/DocumentKnowledgeIndexCacheTest` and the loader's runtime tests with the P3 index-failure matrix below. This is internal error-cause propagation, not a global change to document-tool authorization or error contracts.
4. **Record and carry the active method.** A new hook class (for example `taskstate/ActiveMethodHooks`) owns recognition independent of `TaskProgressV1bDynamicMerge`. Call `afterGetAgentSkill(toolCall, result)` beside the existing two skill post-hook sites, before checklist early returns can affect recognition. Call `afterLoadTopic(toolCall, result)` only on the Java built-in registry dispatch path after successful execution; validate the complete success header against the arguments, not an arbitrary `topic:` prefix alone. A same-named extended tool when document built-ins are disabled MUST NOT claim Java-loader success or feature support. Then add the immutable snapshot/getter and compatible constructor overload to `tools/PendingApprovalRecord.java`, preserve it in `withInterruptedBatchSiblings`, snapshot in `tools/ParlerHitlStreamScopedEnqueue.java`, pass it from `runParlerPostToolAgentLoop` into `AgentThing.primeAgentTaskStateFromHistory`, and restore through the state API before planning. Keep existing chart/checklist/approval behavior intact.
5. **History protection.** `compaction/ContextBudgetPlanner.buildProtectedMask`: implement R3(b)'s current-turn, success-only, newest-per-topic selection. The planner already reads `AgentToolContext` for ephemeral indices. Protect complete matching batches for every active topic, and retain the existing budget-failure behavior. Do not protect all historical rows merely by tool name.
6. **Features.** New `RuntimeFeatures` class; wire its deterministic list into `buildAgentRuntimeSnapshotJson` before the return, outside include-option branches. The helper takes the actual built-in registration availability as input and can be tested without constructing an `AgentThing`.
7. **Prompt text.** `src/main/resources/com/thingworx/things/agent/llm_tool_routing_guide.txt` (the "Document knowledge" section, lines about 42-46): one sentence — when a search hit has `contentType: "topic"`, load it with `load_topic` and follow its steps. Per `docs/agent/LLM_CONTEXT.md`, call semantics belong in the tool description; keep the guide sentence to routing. `DocumentKnowledgeRoutingGuideTest` asserts exact existing phrases: add, do not alter.
8. **Admission classification only.** Add `m.put("load_topic", ToolBucket.DOCUMENTS)` in `tools/ToolBuckets.java` so the new document tool follows the existing document admission signals rather than falling through `OTHER`. This is the only admission mapping addition; do not change policy, signals, core sets, or lazy schema loading. Under `narrow`, document tools still require a document signal; under `lazy`, deployment-required buckets/tools or existing schema loading must make them available. The pure-document D2 set alone cannot revive an upstream-deferred loader. Do not change `DocumentKnowledgeSearchScorer.CONTENT_TYPE_RANK` in this phase.
9. **Tests and docs** (sections 6 and 7).

## 5 Data formats (synthetic)

### 5.1 A topic in a document package

`chunks/chunks.jsonl` gets one line (here wrapped for reading; it is one JSON object per line):

```json
{"docId":"demo-guide","chunkId":"topic-demo-evidence-r1","contentType":"topic",
 "heading":"Collect the evidence for a demo ticket","sectionPath":["Topics"],
 "pageStart":1,"pageEnd":1,
 "tags":["demo ticket","collect evidence","demo unit"],
 "signals":[{"kind":"symptom","name":"demo ticket evidence"}],
 "summary":"Use when the user asks what to collect for a demo ticket on a named unit.",
 "markdown":"Use when the user asks what to collect for a demo ticket on a named unit. Load with load_topic."}
```

and the package gets the file `topics/topic-demo-evidence-r1.txt` (non-empty UTF-8 plain text, at most 7,000 Java UTF-16 code units when decoded). Both ids obey R2's loader limits. The revision is the `-r1` suffix of the chunk id. The indexer does not read `topics/`; only `load_topic` does. Existing package scan/chunk limits and lexical retrieval bounds still apply; the design does not promise unlimited indexed topics.

The runtime does not generate or validate tool registrations from this file. Authors MUST name tools already available to the deployment and treat read/approval errors as errors, not permission to bypass policy. A topic is repository-authored method guidance at ordinary tool-result authority; it does not become a system prompt or override user instructions, permissions, admission, or HITL. The generator owns revision consistency and source metadata; missing or unreadable body files fail loading as R2 specifies. The example's page/source metadata supports existing citations and does not add citation enforcement.

### 5.2 A working text

Plain text with headings and numbered steps, like a skill body without front matter, naming registered tools:

```text
### Purpose
Answer a demo ticket question for one named unit: say what the article asks to collect, then obtain what the unit's logs can show and report each value with where it came from.

### Steps
Do these in order, **one tool call at a time**, because each log request needs the engineer's approval.
1. **Retrieve the article from the unit's scoped documents** ... keep the page link; the answer must cite it.
2. Call `request_demo_log` for `Events`. Wait for the `ready` result.
3. **Before writing any answer**, call `read_demo_values` **once** with ...
4. Answer.
```

### 5.3 What `load_topic` returns

```text
topic: topic-demo-evidence-r1
document: demo-guide
sha256: 3f1c...   chars: 1840
complete: true
---
### Purpose
...
```

## 6 Tests

All tests are unit tests in `src/test/java/com/thingworx/things/agent/`. Run with the command in section 7. Patterns to copy:

- **Narrowing logic**: `tools/DocumentTurnToolNarrowingTest`. It sets `AgentToolContext.setParlerSlashSkillShortNamesForTurn(...)` and `AgentToolContext.setAgentTaskState(new AgentTaskState("", "conv", "goal"))`, calls `DocumentTurnToolNarrowing.filterForRoundInternal(merged, iteration, true)` on a tool list and asserts the names; `@AfterEach` calls `AgentToolContext.clear()` and `resetTurnState()`.
- **Loop-level with a scripted model**: there is no shared fake. `AgentLoopTest` has `RecordingLlmClient`; `AgentLoopHitlContinuationChartSourceTest` has `ScriptedLlmClient` (keeps `seenRequests()`), plus `textResponse(...)` and `toolCallsResponse(...)` builders. Both implement `llm.LlmClient` (`chat(LlmChatRequest)`, `usageWireIds()`, `healthCheck()`). Build the loop with the constructor that takes a `ToolDefinitionsRoundFilter` (`AgentLoop.java` lines about 120-165). **Turn the narrowing on explicitly.** The public `DocumentTurnToolNarrowing.filterForRound` reads its enable flag from `AgentToolContext.getAgentThing()` and returns the full list when no agent is bound, and these harnesses bind none (also not on the continuation), so a test that uses it would pass without the fix. Put the loop test in the `tools` package and pass `(merged, iter) -> DocumentTurnToolNarrowing.filterForRoundInternal(merged, iter, true)` (package-private), on both sides of the pause; or bind an agent whose `documentTurnToolNarrowingDisabled` setting is false. Assert `llm.requests.get(n).getTools()` and `.getMessages()`. Both classes need `ArtifactCacheTestFixtures.installFreshInMemoryCache()` in the constructor and `LlmCallRecorder.resetForTest()` before and after each test. The nested clients are private to their test classes and live in the base test package, so a loop test placed in `tools` needs its own copy or a public fixture: consider moving the scripted client and the response builders into `AgentLoopTestFixtures` (currently almost empty) and making them public.
- **Pause and resume**: copy `pauseWithResults` and the continuation code of `AgentLoopHitlContinuationChartSourceTest` (lines about 104-170): set `AgentToolContext` conversation and Parler stream ids and active messages, make the tool executor call `ParlerHitlStreamScopedEnqueue.enqueueOrThrow(...)`, take the record from `PendingApprovalStore.remove(...)`, then `AgentToolContext.clear()`, `HitlSyntheticToolResultAppender.appendDurable(...)`, `ParlerHitlContinuationContext.bindForGatedToolExecution(...)`, a new loop and `runAfterApproval`. Use the production state-restore API wired into `primeAgentTaskStateFromHistory`; do not hand-populate a replacement active id in the test. Explicitly reset the narrowing latches after clearing the context, or resume on a clean thread, so an earlier gated call's non-document latch cannot make the test pass accidentally. Assert the captured record, restored state, and actual outbound requests, not only the final answer.

Synthetic topic/index/body fixtures MUST live under `src/test/resources/document-knowledge/topic-round1/`. A reader double MUST record requested paths and simulate current-user read denial independently of a warm index cache. Avoid modifying the production demonstration package or installing test data on a live server. Reuse scripted-loop patterns locally; extract only the shared fixtures actually needed by these tests, without unrelated test refactoring.

Cases (names invented; the tool names are stand-ins for registered tools):

| Case | What it checks |
| --- | --- |
| **P1 flow and approvals** | With admission `off` and narrowing explicitly enabled: search → actual loader/read/hook → gated `request_demo_log` (pauses) → clean-thread resume → `read_demo_values` → answer. Before load, the list **is narrowed** and includes `load_topic`; after load, both registered demo tools are present in every later request. Exact full loader text and assistant/tool pairing survive a forced-budget trim and resume. Enqueue snapshots, production restore, nested approval, and `withInterruptedBatchSiblings` retain ids without aliasing. Cover topic and checklist-less skill recognition across approve/reject/cancel continuations; existing decision results and skipped siblings stay unchanged. Legacy constructors yield empty snapshots. The scripted executor MUST invoke the production recognition hook, not duplicate it |
| **P2 method recognition** | Without a prior non-document tool: a successful non-document skill with no checklist fence, an invalid checklist, or a duplicate checklist id disables document narrowing independently of checklist merge; a topic does likewise; a resolved non-document slash skill counts. A sole `document_search` skill, failed/null load, malformed call, repetition-blocked call, or skipped sibling does not activate a method. Repeated success deduplicates ids. An earlier turn's history alone does not activate the next turn |
| **P3 discovery, validation, and loader** | The current scorer finds a synthetic topic and its returned ids load the full fixture. Cover every R2 error row and validation before I/O: malformed/non-object JSON, missing/null/non-string/blank ids, traversal/separator/control/overlong ids, unknown/non-topic chunk, scope rejection, missing/empty body, repository/index failure, and current-user read denial with a warm index. Null/empty injected scope allows a read; explicit in-scope allows it. Boundary body lengths 7,000/7,001 and id lengths 256/257 are deterministic. Success has the exact header/hash/count and unchanged non-ASCII/newline/body content; include a `[`/JSON-looking body and maximum ids to prove complete-result bounds and egress identity. All failures leave existing recognition/retention unchanged |
| **P4 features** | `RuntimeFeatures` emits the exact deterministic names for registered/unregistered Java loader states; `active-method-tools/1` does not depend on an active turn or document setting. Include `enableBuiltInTools=false` and a same-named extended tool as non-registration cases. Inspect the service wiring to ensure the top-level array is outside snapshot include-option branches; the User verifies actual service output in §9 |
| **P5 no regression and admission boundary** | A sole `/document_search` turn narrows to the existing four names plus `load_topic` when all are registered; dynamically loading only `document_search` keeps this behavior. A prior non-document invocation and the narrowing-disabled setting remain unaffected. `load_topic` maps to `DOCUMENTS`; conditional registration/reservation is present only when enabled. Admission `off/narrow/lazy` still determines its own output; with active methods the D2 filter preserves exactly that supplied list, including pre-existing omissions. No new tool appears merely because the method named it. Existing permission/capability/approval tests remain green |
| **P6 retention and budget** | Put successful loads in **older**, non-active batches so the existing latest-batch protection cannot mask a failure. Under a feasible tight budget, keep the newest successful batch per active topic; keep two distinct active topics; a later failed load does not displace success; repeated successful loads allow the older duplicate to drop. Preserve all paired assistant/tool rows for a protected mixed batch. With no matching active topic, an earlier-turn topic, or only a skill active, topic history receives no new protection. A dynamic `get_agent_skill` body remains droppable. An impossible protected footprint uses the existing budget exception without a provider call; no truncation or cap override |

P3 MUST also cover the following classified-index cases through the production cache-to-loader result path, using reader/rebuild doubles without live calls. A resolvable repository whose service read is denied MUST be distinguished from an unresolvable repository; a warm-cache body-denial case alone cannot establish rebuild-denial behavior. Parameterize index-read denial over both listing (`BrowseDirectory`) and text (`LoadText`) stages, using the existing typed permission-denial test pattern in `DocumentKnowledgeIndexCacheTest` rather than matching an exception message.

| Cache/read condition | Required result and cache behavior |
| --- | --- |
| Cold cache; repository resolvable; index read denied | `PERMISSION_DENIED` reaches the loader and becomes `DOCUMENT_REPOSITORY_UNAVAILABLE`; no index, no stale fallback, no body read, and no cache entry created |
| Expired cache; repository resolvable; index read denied | Same classification/code; return no index with `staleRebuildFailed=false`, never read the topic through the stale candidate, and preserve the old shared entry for a later lookup by another permitted reader |
| Cold cache; non-permission rebuild exception or null test-hook result | `INTERNAL_ERROR` becomes `DOCUMENT_TOOL_INTERNAL_ERROR`; no body read or cache entry created |
| Expired cache; non-permission rebuild failure | Existing stale-index success remains possible with `staleRebuildFailed=true` and `failureReason=NONE`; current-user topic-body read is still required |
| No current-user repository reader and no stale candidate | `REPOSITORY_UNAVAILABLE` becomes `DOCUMENT_REPOSITORY_UNAVAILABLE` without a second diagnostic resolve/read probe |
| Fresh cached index; current-user topic-body read denied | The index hit makes no rebuild read, but the required body read fails with `DOCUMENT_REPOSITORY_UNAVAILABLE`; preserve the warm index |

For each failure, assert unchanged active-method ids and retained successful topic bodies. Assert read/resolve counts to exclude diagnostic re-probes and body reads after an index denial. Keep the existing cache regression assertions for untouched shared entries, ordinary stale fallback, and fresh-hit no-I/O behavior. Existing sibling-tool tests MUST keep enforcing their current behavior; do not change those assertions to the loader's stronger denial classification.

Existing tests that pin counts will fail when `load_topic` is registered; update them deliberately: `tools/BuiltInToolMergedDefinitionFootprintTest` (28, flag off, unaffected), `tools/ToolRegistryExecutorOnlyTest` (28, unaffected), `tools/DocumentKnowledgeToolRegistrationTest` (31 with the flag on → 32), and `tools/ToolBucketsTest` (new `DOCUMENTS` entry). `src/test/resources/context-compaction/m1a/census-baseline.json` is pinned with the flag off and should not change. `ReservedBuiltinToolNamesTest` or the registration test MUST assert the new name is reserved only when the Java document built-ins are enabled, not unconditionally.

## 7 Build, verify, version, process

- **Build and test (local only):**

  ```bash
  cd parler-agent
  ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true
  ```

  `-PuseLocalTwxLib=true` is mandatory for any manual Gradle call; it needs the User-maintained `parler-agent/twx-lib/all` set and a compatible local Java 21 JDK. If either prerequisite is unavailable, report the blocker; do not download/copy replacements, use remote ThingWorx dependencies, redesign Gradle, or add preflight infrastructure. Use Gradle only through `test` and `assemble`; never any publication task. A focused command MAY add `--tests 'com.thingworx.things.agent.tools.DocumentTurnToolNarrowingTest'` to `./gradlew test --no-daemon -PuseLocalTwxLib=true`; this does not replace the full local command on the completed phase.
- **Live work is the User's.** Do not call ThingWorx services, `parler-collect-live`, or any live helper. The User imports `parler-agent/build/parler-agent.zip` (per `AGENTS.md`) and replays. If live evidence is needed, prepare a checklist per `docs/agent/live-diagnostics.md`.
- **Versions.** Do not bump `revisionVersion` in `parler-agent/build.gradle`, `packageVersion` in `metadata.xml`, or edit `parler-agent/CHANGELOG.md` on a topic branch; that happens at the User-authorized integration cut. `CONTRACTS/CONTRACT_VERSION.md` must be bumped (patch) in the same commit as any edit to a normative `CONTRACTS/*.md` file. This change adds only an additive snapshot field and a tool, which `docs/agent/configuration-repository.md` says needs no contract bump; if you find a normative file that lists built-in tool names or the snapshot shape and must change, bump atomically with the code and tests.
- **Branch and review.** This worktree is on `topic-round1`, with upstream `origin/topic-round1`. Keep all Git operations on that branch; MUST NOT check out, commit to, push, or modify base branch `nearterm`. Do not create branches/worktrees or perform integration yourself. Before commits, check/classify `git status --short`, confirm the current branch and upstream, and use explicit `git add <path>` only. Push the canonical design before deriving a review packet's upstream baseline; commit/push the packet alone. Follow `docs/review-framework.md` and the design agreement gate above. Do not record an inferred User Ruling or treat the Implementor's proposed decisions as reviewer agreement.
- **Documentation to update with implementation:** `docs/agent/AGENT-CONTEXT.md` (§5.1 built-in tool table), `docs/agent/all-tools.md` (population counts, `DOCUMENTS` bucket, schema), `docs/agent/document-chunk-tools.md` (loader's full input/result/errors, conditional registration/reservation, package layout and current-user reads), `docs/agent/configuration-repository.md` (unconditional `features`, names and registration matrix), `docs/agent/task-state.md` (recognition separate from checklist state, turn/approval lifecycle), `docs/agent/context-compaction.md` (§16 Slice C: topic retention and existing budget-failure limits), `docs/operations/tool-schema-admission-control.md` (new tool's `DOCUMENTS` membership only), and `docs/agent/README.md` (index this design under "Tools and platform semantics"). Update `docs/agent/LLM_CONTEXT.md` if its routing ownership description changes; no new routing-guide section is planned. Do not leave shipped handoff details solely in a review packet.

## 8 Proposed decisions for reviewer agreement

These decisions complete the draft's open choices; they are proposals until the design agreement gate passes.

- Keep a separate loader because plain text below the existing egress cap preserves a complete method while `get_document_chunk` JSON does not. No egress or provider-adapter change is planned.
- Carry classified index failure metadata to the loader so permission denial is reported as repository unavailable on cold/expired rebuilds as well as warm-cache body reads. Extend only the internal cache-result path and add the loader-specific resolution helper; preserve sibling error mapping, shared cache contents, and no-stale-on-denial behavior. Do not guess a lost cause with another repository probe.
- Keep `<root>/<docId>/topics/<chunkId>.txt`, exact indexed ids, a 7,000-code-unit body bound, and bounded single-line ids. No manifest extension, additional setting, or new search-result field is needed.
- Set `load_topic`'s `playbookSafe` flag to `false`: this phase supports method guidance in the agent loop and does not add topic orchestration to deterministic playbook nodes. Existing document-tool eligibility stays unchanged. The new tool remains read-only and needs no new HITL gate; methods cannot waive the existing gates on the tools they name.
- Map the loader to `DOCUMENTS`, preserve existing admission semantics, and make no ranking adjustment. The supported P1 baseline is admission `off`; other modes retain their existing configuration/schema-loading prerequisites.
- Retain ordinary tool-result batches in the budget planner rather than reinjecting method text as a system row. Protect the newest successful body **per active topic**, not the latest result of any status/tool identity. Several topics may remain active; the existing hard budget failure bounds aggregate retained context.
- Keep method recognition independent of checklist parsing and task-progress wire output. Dynamic skill bodies remain ordinary evidence; recognition alone is what survives HITL for those skills.
- The external generator owns topic-package production and immutable revision conventions. The runtime owns discovery, validated/current-user loading, turn/approval recognition, and topic retention. No external orchestration/driver input is a product dependency.

## 9 Done means

Local implementation acceptance requires P1–P6 and the full §7 command to pass, canonical handoff docs to match the code, and the implementation slice to be pushed and submitted for review. Record the test result and assembled ZIP path without claiming a live outcome. Pending User-operated live checks do not justify an empty successor packet or a new infrastructure gate.

The User's live checklist, after manually importing `parler-agent/build/parler-agent.zip`, is:

1. Configure document built-ins and the synthetic/revisioned package with a non-empty applicable document scope, the already-registered gated/read tools, and admission `off`. No generated topic is added to the skill registry. If using `narrow/lazy`, satisfy their existing document/tool admission prerequisites separately.
2. In turn 1 resolve the test unit; in turn 2 ask for the method's task. Observe a topic search hit and `load_topic` with that hit's exact ids; no non-document tool is called merely to disable narrowing.
3. Observe admitted registered tools remain available after loading, a real approval card appears for the gated call, and continuation follows the loaded steps. A later gated step checks a second pause/resume without reloading the topic.
4. Observe the answer uses the obtained evidence and cites the existing source link. Citation correctness is an observed model behavior, not a new runtime-enforced gate.
5. Call `GetAgentRuntimeSnapshot` manually and confirm `features` contains `topic-load/1` and `active-method-tools/1` with the Java loader registered. With document built-ins disabled, confirm only `active-method-tools/1` remains; restore the test configuration afterward.
6. Separately replay a checklist-less registered skill and a sole `document_search` turn to confirm method recognition fixes the former while pure-document narrowing remains for the latter. Record pass/fail observations and supplied diagnostics; agents do not execute these live steps.

## 10 Glossary

- **topic** — a generated unit of method for one task, stored as a document chunk of `contentType: "topic"` plus a text file.
- **working text** — the content of the topic's text file: what the model reads and follows.
- **active method** — the loaded topic or non-document skill, held in `AgentTaskState` for the turn.
- **registered tool** — a built-in or extended tool in the merged tool list.
- **narrowing** — `DocumentTurnToolNarrowing`: reducing the per-round tool list to the document tools.
- **gated / HITL** — a tool call that needs a human approval before it runs.
- **egress** — `ToolResultEgressGateway`: what part of a tool result the model sees.
