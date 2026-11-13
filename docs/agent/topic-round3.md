# Topic round 3 — the method must not depend on document-level ranking

Topic: `topic-round3`

This document is the proposed design and implementation guide for the third round of method loading in the agent extension (`parler-agent/`). **Current baseline.** Rounds 1 and 2 are shipped (agent 0.1.251 and 0.1.252; `docs/agent/topic-round1.md`, `docs/agent/topic-round2.md`). Their background sections describe the tree *before* those changes: `load_topic`, `ActiveMethodHooks`, the carried active-method snapshot, topic retention, `ConversationEntityScope`, the origin marker, `methodOffered`, `loadWith` and four `features` names exist. Only R11–R15 below are new work. Read round 1 sections 2–3 and round 2 sections 2–3 for background this document does not repeat. Where an earlier process section (worktree names) differs from section 7 here, section 7 wins. Examples use invented names (`Demo_Unit_1`, `demo-guide`); the numbers in section 1 come from runs of a synthetic document package.

**Design agreement gate:** the Implementor MUST NOT start coding until every User/orchestrator-designated Reviewer agrees with this design in the latest pushed design-agreement round's `### Direction Verdict`, each stating `Fresh-thread readiness: PASS` or `REDIRECT` and an explicit `continue`, `redirect` or `stop`. Absence of a review is not agreement. This design proposes one implementation phase covering R11–R15 together. The Implementor reads repo-root `AGENTS.md` and `docs/review-framework.md` first.

## Fresh-thread implementation context

Before changing files, the agent MUST read repo-root `AGENTS.md` and `docs/review-framework.md`.

- **BUSINESS_CONTEXT:** Runtime users ask what to collect for a symptom on a named unit, and to collect it. The method for that task exists as a generated topic in the unit's document set. Rounds 1 and 2 made a loaded topic work and made a topic that reaches the model keep the operational tools. Live runs then showed that the topic row itself does not reach the model when the search carries a hint matching a large manual's identity, so the model answers that it has no tool for the request (section 1). This topic fixes that class for any unit and any topic. It does not tune for the synthetic demo.
- **CURRENT_IMPLEMENTATION:** Rounds 1 and 2 are shipped in agent 0.1.252: `load_topic`, `ActiveMethodHooks`, `ConversationEntityScope`, `AgentTaskState.methodOffered`, the `loadWith` field on topic matches, and four `features` names in `RuntimeFeatures`. `DocumentKnowledgeRuntime.searchIndex` builds the search JSON from `DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics`, where every chunk inherits a document-level score. No result field exists that is independent of that ranking. `ActiveMethodHooks.hasTopicMatch` reads only `matches`. Section 2 gives the verified facts this design relies on.
- **TERMS_AND_CONTRACTS:** The *topic lane* is the new top-level `topics` array; *topic-local score*, *structured* and *weak* evidence are defined in R11; *offer* keeps its round-2 meaning with one more source. Section 10 is the glossary. `topics`, `DOCUMENT_TYPE_FILTER_EMPTY` and `topic-lane/1` are model-facing or diagnostic JSON. No `CONTRACTS/*.md` file mentions `search_document_chunks` or `documentScopeSource`, so no contract edit or `CONTRACT_VERSION.md` bump is expected. If implementation finds that a normative contract must change, that edit and the bump land in one commit. `docs/agent/document-chunk-tools.md` owns document tool semantics, `docs/agent/configuration-repository.md` owns `features`, and `docs/agent/task-state.md` owns the offer.
- **DEPENDENCIES_AND_HANDOFFS:** The predecessors are rounds 1 and 2, already on this branch (`docs/agent/topic-round1.md`, `docs/agent/topic-round2.md`). The lane depends on a document scope reaching the search; R6 of round 2 provides it, and that still needs a `ResolveDocumentSet` override or an exact `assetModels` match in the deployment. The topic row format (`chunks/chunks.jsonl`, `tags[]`, `signals[]`) is owned by the external generator and is unchanged; topic rows conventionally carry a tag naming the unit family, as in round 1 section 5.1. The generator may consume `topic-lane/1`. No later topic consumes other output. No prior conversation, review packet or driver file supplies implementation knowledge.
- **SCOPE_AND_BOUNDARIES:** Implement R11–R15 and P12–P18 as one phase. Keep `scoreChunk`, `scoreDocument`, BM25, the selection plan, `resolveAllowedDocIds`, the egress gateway, `DocumentSearchProgressGuard`, narrowing, `limit` defaults and the UI unchanged. Section 1 lists what is out of scope. Do not add a setting, a pagination or scope token, a turn-start probe, or a product version or changelog edit.
- **CARRIED_DECISIONS:** A separate lane with its own scorer, not a topic bonus in `scoreChunk`. The lane runs only inside a real document scope and ignores `documentTypes` for itself. A topic outside an injected scope is excluded because `load_topic` would refuse it. The projection is compact so the lane survives egress without touching the shared gateway. Asset identity earns no structured evidence in any branch, so it cannot qualify a topic (R11.3). The lane lists only topics whose ids `load_topic` accepts (R11.2). Scope stays a default that an explicit `documentIds` suppresses, as in round 2. Section 9 gives the reasons; the mixed document-plus-action gap and the other deferrals in section 4 stay deferred with their reopen evidence.
- **IMPLEMENTATION_LANDING:** Under `parler-agent/src/main/java/com/thingworx/things/agent/`: `tools/DocumentKnowledgeSearchScorer` (the lane and the R14 helper), `tools/DocumentKnowledgeRuntime` (`searchIndex`), `tools/DocumentKnowledgeToolSchemas`, `taskstate/ActiveMethodHooks`, `RuntimeFeatures`. Fixtures land in `parler-agent/src/test/resources/document-knowledge/topic-round3/`; tests land in `parler-agent/src/test/java/com/thingworx/things/agent/tools/` (new `TopicLaneTest`, additions to `TopicOfferFlowTest`, and the deliberate `ActiveMethodLifecycleTest` update). Sections 5 and 7 give the order and the documentation paths.
- **VERIFICATION_AND_CLOSE:** Local acceptance is P12–P18 plus `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`, using only the User-maintained `twx-lib/all` set and a compatible local Java; a missing prerequisite is reported, not replaced. The User alone imports the ZIP and runs the section 8 live check; agents prepare the checklist and record supplied outcomes. Push to `origin/topic-round3` and submit for review per the framework. The User or orchestrator merges into the integration branch `nearterm`, cuts the version and archives.

## 1 What this change is for

After round 2 the route to a method works when the topic reaches the model: scope comes from the resolved unit, an offered topic keeps the tools, and a topic hit says how to load it. Live runs then showed the route fails, repeatably, one step earlier: **the topic row does not reach the model when the search carries a hint that matches the large manuals' identity.**

Evidence (all with the unit's document set as the scope, so the topic's document was always searched):

| Searches that carried `assetContext.assetModel` (for example the model family) | Topic row in the matches the model saw |
| --- | --- |
| the original failing conversation, `limit` 3 | no |
| a second conversation, `limit` 3 | no |
| exact-argument probe, `limit` 3 | no (top three: pages of a large manual, score 319 each) |
| exact-argument probe, `limit` 10 | no (ten matches, then reduced to five by result egress, all manual pages) |

| Searches without `assetModel` | Topic row |
| --- | --- |
| twelve searches in twelve conversations (with or without the unit name in `assetContext`, `limit` 3 or 5) | first in all twelve, scores 203 to 311 where recorded |

When the topic is missing, no offer is recorded, document-turn narrowing stays on, and the model answers that it has no tool for the operational request: the failure round 1 and round 2 set out to remove.

**Mechanism.** A match's score is the chunk-level score plus the **document-level score** plus a BM25 boost (`DocumentKnowledgeSearchScorer.scoreAndRankWithDiagnostics`: `chunkScore + boost + bm25Boost`). The document-level score rewards documents whose id, filename, title or alias contains tokens of the query or of the asset hint (`alias:`, `title:`, `docId:`, `filename:` evidence; 390 for a large manual in the failing run, while the short article's identity contains no token of the hint). Every chunk of a document inherits it. A topic row is authored for one narrow task and lives in a small article, so it is structurally outranked by any large manual that shares the hint, whatever the topic's own relevance. Without the hint the topic wins by a thin margin (273 against 236 in one run), which is luck, not design.

Round 3 therefore stops asking document ranking to surface methods. It adds a separate, deterministic **topic lane**, and fixes the hazards of the same class that can be fixed locally; the rest are recorded in section 4.

### In scope (round 3)

- **R11** Topic lane: a top-level `topics` array in the search result with the best topic chunks of the searched documents, ranked by a **topic-local scorer** that uses no document-level evidence, and carried as a compact projection.
- **R12** The lane feeds the topic offer; the search description mentions the lane.
- **R13** The three scope instructions in the tool schemas stop telling the model to copy `documents[]` when a unit scope is already injected, and keep explicit handoff otherwise.
- **R14** A `documentTypes` filter that removes every document is reported, not silent.
- **R15** Feature name `topic-lane/1`.

### Out of scope (do not do these)

Changing `scoreChunk`, `scoreDocument`, `scoreDocIdTitleTokenAffinity`, document-level weights, `CONTENT_TYPE_RANK`, BM25 or the selection plan (`hard-single`, `diversified`); changing `resolveAllowedDocIds` (its unknown-id behaviour is a separate defect, section 4); changing `limit` defaults, the egress gateway or its priority fields; making the lane a setting; a lane for searches that have no document scope; any change to narrowing (the mixed document-plus-action gap is recorded, not fixed here); any change to `DocumentSearchProgressGuard`; per-topic tool admission; a turn-start topic probe; ranking changes for non-topic content types; UI or wire changes; version or changelog edits on the topic branch.

## 2 Background added for round 3

### 2.1 How a match is scored

For each searched document the scorer computes a **document score** from identity and asset evidence (`scoreDocument`: exact alias phrase 100, id/filename phrase 60, document kind 40, asset model 25, manufacturer 20, domain term 10, asset-context value 15, doc-id/title token affinity 15) and adds it to every chunk of that document. `scoreChunk` is the chunk-level part: signal-name equality 50 each, query token matching a signal name 25, heading containing the whole query 35, tag or component match 25 per token, heading token 8, summary or tag token 5, markdown tokens up to 20 in total, plus small bundle/troubleshooting adjustments. `scoreChunk` is **not purely chunk-level**: it also adds `scoreDocIdTitleTokenAffinity` (15 per request token, asset-context tokens included, found in the document id, title or filename), a document-type bonus (15) and a bundle-role penalty (up to 40). Summary and tag tokens add 5 **per token**, without a cap. A candidate must have `chunkScore > 0` or a BM25 boost. Matches are sorted, then (when more than one document is searched) `diversify` caps matches per document at `(limit + 1) / 2`.

Facts about the helpers the lane reuses: `tokenize` lower-cases, splits on anything outside `a-z0-9`, drops parts shorter than 2 and keeps duplicates, so a repeated word scores twice in `scoreChunk`. If no part survives (a query entirely in Chinese, for example) it returns the whole trimmed text as one token. Blank input returns an empty list. `containsToken` is a **substring** test, so the token `on` is found in `operation`. The whole-query heading test in `scoreChunk` is also a substring test. `queryTokenMatchesSignalName` accepts equality, or a prefix in either direction when both sides have at least 4 characters, so `internal` matches the signal `internal control`.

### 2.2 Which documents are searched

`documentIds` in the request (including the injected default scope) selects exactly those documents (`selectionMode` `documentIds-filter`). Without it, `buildSelectionPlan` may narrow to one document (`hard-single`: a hard identity match with a margin of 30) or search all (`diversified`). `documentTypes` is a **hard filter** applied before scoring (`passesDocumentTypeFilter`): a document with another or no type is dropped; a filter that matches nothing yields an empty result with `selectionMode` `empty` and no warning. `document-chunk-tools.md` still describes `empty` as "the index holds no chunks"; that row is corrected with this change.

When `documentIds` is non-empty and **none** of the ids exists, `resolveAllowedDocIds` returns an empty set, which inside that method means "no restriction", so `documentScores` is computed for every document. `buildSelectionPlan` then selects no document (`selectionMode` `documentIds-filter`, empty `selectedDocIds`), so `matches` is empty. The unknown ids cost diagnostics, not results.

### 2.3 What the model sees of a search result

`ToolResultEgressGateway` reduces large results. In the runs a result of 10,849 characters was reduced to five matches (`_egress.reducedFields.matches` `originalCount 10 returnedCount 5`); a result of any size has arrays of more than 20 elements sampled to 20 (`resolve_document_set` returned 20 of 32 documents at 3,204 characters, with `sampleOnly`/`rowsOmitted`). The reduction keeps the first elements and reports what it dropped. A thing that must be seen therefore has to be early in the result and small.

The gateway has three passes, and stops at the first whose JSON is at most 8,192 characters: arrays to 20 and text to 2,000; arrays to 5 and text to 512; then a last resort that writes priority fields first, then the other fields in result order, until 6,000 characters are used, and omits what no longer fits. `topics` is not a priority field and does not need to be: at most two entries, every text value within the 512 limit, and placed before `matches`, so it is written before the large fields in every pass. The index does not provide that bound: `DocumentKnowledgeChunk.parseJsonLine` accepts any non-blank text as an id, and the 256-unit, path and control-character limits are in `TopicMethodText.validId`, which runs only when `load_topic` is called. The lane establishes the bound itself (R11.2), and the heading is cut to 80.

### 2.4 The three instructions that steer the model

`DocumentKnowledgeToolSchemas` carries the scope instruction in three places: the `search_document_chunks` description ("When resolve_document_set returned a non-empty documents[], pass those ids as documentIds …"), the `documentIds` parameter description ("When resolve_document_set returns a non-empty documents[], pass those documents[].documentId values here …"), and the `resolve_document_set` description ("When documents is non-empty, pass documents[].documentId as the documentIds argument to search_document_chunks …"). `executeResolveDocumentSet` only returns a set; it injects nothing. Where a unit scope **is** injected (host context, or round 2's resolved unit), search already defaults to it and a copied, sampled `documents[]` suppresses it: the default applies only when the `documentIds` field is absent, so any value, including `[]`, replaces it. The model cannot know a scope is injected before its first search result (`documentScopeSource`) arrives, and `resolve_document_set` does not report it. Where none is injected, `resolve_document_set` followed by explicit `documentIds` is the working handoff (`DocumentKnowledgeResolverHandoffTest`, the gold-set named-asset case) and must stay.

### 2.5 Narrowing, and the mixed-task gap (unchanged, restated)

`DocumentTurnToolNarrowing.shouldNarrow` narrows a turn from round 2 on once a document tool has run and no non-document tool has, unless an active method is present or a topic was offered. A request that mixes a document lookup with an operational action and offers no topic is narrowed and loses the operational tools (four conversations answered that no log-collection tool was available). A resolved unit does not establish operational intent ("explain this unit's maintenance procedure" is document Q&A), so this round does not change narrowing; the gap is recorded in section 4.

### 2.6 Who reads the search result

`ActiveMethodHooks` (offer), `DocumentSearchProgressGuard` (fingerprints and saturation read only `matches`), the model, and the egress gateway (keeps priority fields such as warnings first and can omit any other field that exceeds its remaining budget). `selectedDocIds` and `selectionMode` describe the selection behind `matches`. No other runtime code reads this result: the remaining readers of a `matches` field in the agent read Thing-resolution output. No test pins the three sentences of section 2.4, and `docs/agent/document-chunk-tools.md` and `docs/agent/all-tools.md` carry copies of them. `llm_tool_routing_guide.txt` and `LLM_CONTEXT.md` say that a search hit with `contentType: "topic"` is loaded with `load_topic`; a lane entry has that field, so they stay correct and unchanged.

## 3 Required behaviour

### R11 Topic lane

In `DocumentKnowledgeSearchScorer` add a topic-local scorer and a method (for example `topicLane(chunks, manifestFor, request, injectedScopeIds)`) returning at most `TOPIC_LANE_MAX = 2` entries; `DocumentKnowledgeRuntime.searchIndex` puts them in the result as a top-level array `topics`. `manifestFor` is used only for the asset identity exclusion in step 3.

1. **Lane documents.** Only when the request carries a non-empty `documentIds` list (the injected default scope arrives as one): the ids that exist in the index. If there is no list, or none of the ids exists in the index, the lane is **empty**; it never falls back to all documents, and it must not reuse `resolveAllowedDocIds`' empty-set sentinel (which means "no restriction" inside that method, section 2.2). The lane ignores `documentTypes`, the selection plan and `diversify`; `matches` keeps all of them. When an injected scope exists, a topic whose document is outside it is excluded, because `load_topic` would refuse it; the test is the one `outsideInjectedScope` applies, so `loadWith` and the loader agree. An explicit `documentIds: []` suppresses the default scope (section 2.4), so the list is empty and so is the lane.
2. **Candidates.** Chunks whose `contentType` equals the exact string `topic` (the string the loader and `loadWith` use; no case folding) and whose `docId` and `chunkId` both pass `TopicMethodText.validId`. The check comes before ranking and capping, so an invalid row never takes a lane slot from a valid one. An id is never cut to fit. The index, the parser, the loader and `matches` are unchanged. This covers the id rules only: a valid reference whose body file is missing or too large still fails in `load_topic` with its typed error.
3. **Topic-local score.** Computed from the chunk and the request's query tokens, query text and signal names **only**. It uses no document-identity points, no document-type bonus, no bundle penalty, no BM25, and no asset-context tokens. *Request tokens* are the **distinct** query tokens plus the distinct tokens of the signal names, so repeating a word earns nothing. Strong terms (their sum is `structured`):
   - signal-name equality (ignoring case) 50 for each distinct request signal name a chunk signal equals;
   - a distinct query token matching a chunk signal name by `queryTokenMatchesSignalName` 25 each;
   - the whole query contained in the heading 35, compared trimmed and lower-cased, and only when the query has at least two distinct tokens, at least one of them not an asset token: a one-word query is a heading token, not a phrase, and as a substring test it would otherwise qualify any heading containing those letters;
   - a tag match 25 each, where a tag matches when **every token of the tag** (from `tokenize`) is among the request tokens, so a multi-word tag such as `internal control` matches a query that contains both words. A blank tag has no tokens and never matches. The existing `matchesTagOrComponent` compares a single query token with a whole tag and never matches a multi-word tag; it would miss the failing queries of section 1.

   **Asset identity cannot qualify a topic.** The *asset tokens* of a chunk's document are the `tokenize` tokens of `manifest.assetModels()` and `manifest.documentProfile().assetModels()`. A text is *identity-only* when it has tokens and all of them are asset tokens. All four structured branches apply this rule, so structured evidence always contains something other than asset identity:
   - an identity-only tag (`demo unit`, the documented convention in round 1 section 5.1) is not a match;
   - an identity-only chunk signal earns nothing in either signal branch;
   - a query token that is an asset token earns no signal-token points;
   - an identity-only query earns no whole-query heading points, whatever its token count.

   All the topics of a unit's document set share that identity, so it says nothing about the task, and a family name in the query is the same evidence as the hint in section 1. The manifest only removes evidence here; it never adds any. A missing manifest removes nothing. The rule is general: it reads the manifest of the chunk's document, never a named family, and changes neither `tokenize` nor `scoreChunk`.

   Weak evidence (`weak`): for each distinct query token, by `containsToken`, 8 if in the heading, 5 if in the summary and 1 if in the markdown, each counted independently, **capped at 20 in total**. Family words count here. Weak evidence cannot meet the `structured >= 25` gate, so this can raise the score of a topic with task evidence but cannot qualify one without it.
4. **Qualification.** `structured >= 25` **and** `structured + weak >= TOPIC_LANE_FLOOR = 30`. Heading words and text alone are capped at 20, so a topic that merely shares vocabulary, or sits under a parent title that contains the family name, does not qualify. The score is `structured + weak`. The floor is a heuristic starting point, not a proof of relevance; it is tested on the fixtures of section 6. A single structured match of 25 needs 5 more weak points: one heading or summary token is enough, while markdown alone needs five tokens. A tag's own word usually appears in the heading or summary, so in practice one non-identity tag match plus that word qualifies; the score order, not the floor, is what puts a better topic first.

   Worked example, query `Family Turbine Internal Control failing collect logs` with `assetModel` `Family Turbine`. A topic with the heading `Collect logs for an Internal Control failure` and the tags `internal control`, `collect logs`, `family turbine`: tags 50 (the third is asset identity), heading tokens over the cap, weak 20, score 70, qualifies. A topic with the heading `Replace the control panel` and the tags `panel replacement`, `family turbine`: structured 0, weak 8, does not qualify, even if its document's title names the family. With the query `Family Turbine`, a topic headed `Family Turbine maintenance` earns no phrase points (identity-only query): structured 0, weak 16, does not qualify. With the query `Family Turbine maintenance`, the same heading earns 35 plus weak 20, score 55, and qualifies, because a task word remains.
5. **Order and size.** Highest score first, then `chunkId`, then `docId` (a chunk id is unique only within a document); at most two. The lane does not depend on `limit`.
6. **Compact projection.** Each entry has exactly `docId`, `chunkId`, `contentType` (`topic`), `heading` (cut to 80 characters without splitting a surrogate pair; an absent heading is the empty string), `score` (the topic-local score, on a different scale from `matches[].score`) and `loadWith` (`load_topic`). No snippet, section path, page numbers or source links: the method text comes from `load_topic`, citations from the loaded text or `get_document_chunk`. The array is inserted right after `degraded` and before `matches` and is **omitted when empty**, so a search with no qualifying topic produces exactly today's JSON. Topics that are also in `matches` stay there unchanged. The raw `matches` are unchanged; what the model sees of them still depends on egress, and the guide states both.
7. **Empty input and failure.** The existing empty-input guard applies first; the lane never creates a search. A search with no query text and no signal names has no request tokens, so its lane is empty. An exception in the lane is logged at warn level, omits `topics` and does not change `matches`. The early-return results (`emptySearchSuccess`, `resolveFailureSearch`) have no lane.

### R12 The lane feeds the offer and the description

`ActiveMethodHooks.hasTopicMatch` (round 2) records `methodOffered` when the built-in search result has a non-empty `topics` array **or** a `matches` element with `contentType` equal to `topic`; everything else about the offer is unchanged. As in round 2 the hook reads the JSON the built-in returned, before egress. The wording of the `search_document_chunks` description and the `documentTypes` parameter description is given in R13.

### R13 Scope instructions, conditional

Update the three surfaces of section 2.4 together so they agree: when a search result carries `documentScopeSource`, the runtime already scoped the search, so omit `documentIds`; otherwise pass a **complete** `documents[]` as `documentIds`; a result marked `sampleOnly` or `rowsOmitted` is a sample and is not passed. The first instruction can only be followed after a first search (section 2.4), so the sampled rule is what protects a first search: omitting `documentIds` uses an injected scope when there is one, and searches all documents when there is none. This round adds no pagination or scope token for the combination "sampled result and no default scope"; the description states that limit plainly.

The implementation uses these strings. Changing them changes this design.

- `search_document_chunks` description, replacing its sentence on `documents[]`: `When a search result carries documentScopeSource, the runtime already scoped the search to the unit: omit documentIds. Otherwise pass the ids of a complete resolve_document_set result as documentIds. A resolve_document_set result marked sampleOnly or rowsOmitted lists only part of the set: do not pass it; omit documentIds, which uses the runtime scope when there is one and otherwise searches all documents.`
- The same description, appended at the end (R12): `A non-empty topics array lists methods that match the request whatever the document ranking: load the first relevant one with load_topic.`
- `documentIds` parameter description: `Optional explicit document id filter. Omit it when a search result carried documentScopeSource. Otherwise pass the documents[].documentId values of a complete resolve_document_set result to scope this search to that set (selectionMode documentIds-filter); a result marked sampleOnly or rowsOmitted is not complete. Any value here, including an empty array, replaces the runtime scope.`
- `documentTypes` parameter description, appended (R12): `Applies to matches, not to topics.`
- `resolve_document_set` description, replacing its sentence beginning `When documents is non-empty`: `When a search result already carried documentScopeSource, the runtime has scoped search to the unit and this call is not needed for scope. When documents is non-empty and the result is not marked sampleOnly or rowsOmitted, pass documents[].documentId as the documentIds argument to search_document_chunks. A marked result lists only part of the set: do not pass it; omit documentIds.`

No existing test pins the replaced sentences (section 2.6). `DocumentKnowledgeResolverHandoffTest` and the gold-set cases stay unchanged and green.

### R14 An empty type filter says so

When `documentTypes` is non-empty and the filter leaves no document although the documents the request may search are not empty, add a warning `DOCUMENT_TYPE_FILTER_EMPTY` to the search result. The documents the request may search are the documents with searchable chunks, limited to the `documentIds` that exist when the list is non-empty. If the list is non-empty and none of its ids exists, that set is empty and no warning is added, because the cause is the ids, not the filter (section 2.2). The message is `documentTypes filter [<requested values>] matched no document; available types: [<values>]`, where the values are up to 10 distinct non-blank `documentType` values of those documents, sorted. Nothing else about filtering changes, and the warning is a priority field for egress. The lane still runs under its own rules.

### R15 Feature

Add `topic-lane/1` to `RuntimeFeatures` after the round-2 names, listed when the Java `load_topic` is registered; same deterministic ordering and always-present array rules.

## 4 Scan: hazards of the same class

Each row is a way a model choice, a shared heuristic or a result cap hides the method or the tools. "Evidence" is from live runs or code reading.

| # | Hazard | Evidence | Disposition |
| --- | --- | --- | --- |
| 1 | Document-level score outranks the topic whenever the hint matches a manual's identity | 4 of 4 searches with `assetModel` missed; 0 of 12 without | R11 (topic-local scorer) |
| 2 | Raising `limit` does not help; egress cuts 10 matches to 5 | probe with `limit` 10 | R11 (small lane, early, independent of `limit`) |
| 3 | `documentTypes` hard filter, empty result without warning | probe with a wrong type | R11.1 (lane ignores it), R14 |
| 4 | `resolve_document_set` sampled to 20 of 32 while three descriptions say to copy it | result with `sampleOnly`; models passed 20 ids | R13 (conditional wording; a sampled result is not passed); sampled-and-no-default combination stated, not solved. If a model still passes sampled ids, row 14 applies |
| 5 | Narrowing hides operational tools when a document lookup comes first and no topic is offered | four conversations answered "no tool available" | **Recorded, unresolved.** A resolved unit is not operational intent; a later agreed change must keep an explicit document-only path and treat host and conversation identities alike |
| 6 | Entity scope only for `THING_RESOLVED_INLINE` with one match | every run so far resolved inline | Deferred. An ambiguous result must not be promoted to one unit because a sample shows one candidate |
| 7 | Unscoped `hard-single` selection searches one document only | unscoped control runs | Out of scope (scoped retrieval is the premise); the lane is omitted without a scope |
| 8 | Index TTL (300 s) delays a new topic | round 1 design | Accepted, documented |
| 9 | Tool admission modes `narrow`/`lazy` can defer a method's tools | default is `off` | Unchanged; deployment prerequisite |
| 10 | An irrelevant topic could be offered and lift narrowing | not observed | Topic-local scorer, `structured >= 25`, P12 |
| 11 | `DocumentSearchProgressGuard` reads only `matches` and `load_topic` is not a progress event: a later search can be judged no-progress after a method was offered, and the guard can force a tool-none summary | code reading (Reviewers) | Deferred for the one-search demonstration; do not disable the guard for offers or resolved units. Revisit when a run shows it |
| 12 | `selectedDocIds`/`selectionMode` describe `matches`, not the lane | code | The lane runs only with `documentIds`, so `hard-single` cannot occur with it; the difference appears with `documentTypes`, where a `topics` entry can be outside `selectedDocIds`. Documented in `document-chunk-tools.md`: that is not a scope violation |
| 13 | When none of the ids exist, `resolveAllowedDocIds` returns its "no restriction" sentinel, so `documentScores` covers every document while `matches` is empty | code reading | Recorded as an existing diagnostic defect; unchanged here; the lane does not copy it and R14 does not fire for it |
| 14 | An explicit `documentIds` replaces the injected scope, so ids that omit the topic's document (a passed sample, or `[]`) hide the topic from the lane | code; models passed 20 of 32 ids | Recorded. Explicit ids stay authoritative, as in round 2; R13 removes the instruction that causes it. Reopen if a run shows sampled ids after R13 |
| 15 | `tokenize` is ASCII-only: a query entirely in Chinese is one token, so a tag can match by whole-token equality and a signal by equality or the length-qualified prefix of `queryTokenMatchesSignalName`; arbitrary non-prefix overlap cannot match, and the whole-query heading term does not apply | code reading (Reviewers) | Recorded; the lane inherits it, as `matches` does. A lane miss is no worse than today. Tokenizer changes are out of scope |
| 16 | A query token that prefixes an unrelated signal, or one non-identity generic tag plus its own word in the heading, qualifies a topic | code reading | Accepted heuristic, tested as stated in P12. It costs a wrong entry and a lifted narrowing, not a wrong action: a loaded method grants no tool, permission or approval |
| 17 | `loadWith` on `matches` rows and the offer from `matches` check `contentType` and scope, not id validity, so a topic row with an invalid id can still appear there, offer, and fail in `load_topic` | code reading (Reviewers) | Recorded; unchanged here because `matches` is out of scope. The lane does not copy it (R11.2) |

## 5 Change list

1. `DocumentKnowledgeSearchScorer`: constants, the topic-local scorer, `topicLane`, and a helper returning the available types for R14. `scoreChunk` and the manual path are untouched.
2. `DocumentKnowledgeRuntime.searchIndex`: build and insert the compact `topics` after `degraded`; add the R14 warning before `warnings` is serialized.
3. `taskstate/ActiveMethodHooks.hasTopicMatch`: read `topics`; update the method's comment.
4. `DocumentKnowledgeToolSchemas`: the three scope surfaces (R13), the lane sentence and the `documentTypes` note (R12).
5. `RuntimeFeatures` (+ the `ActiveMethodLifecycleTest` pin): `topic-lane/1`.
6. Tests (section 6) and documentation (section 7).

## 6 Tests

Follow earlier rounds for harnesses: `TopicOfferFlowTest` has the scripted model, the real `AgentLoop` and egress. `TopicTestFixtures` serves one document, so the round-3 tests need a reader that lists several packages; extend it or add a sibling, without changing what existing tests read. Synthetic fixtures under `src/test/resources/document-knowledge/topic-round3/`: a large manual whose id, title, filename and `assetModels` contain the family tokens and whose pages match the symptom; a short article with a `topic` chunk (tags and a signal for the symptom, plus a family tag) and ordinary pages; an unrelated topic; a **family-hint distractor** (a topic whose content does not match the task, whose parent document's title contains the family tokens, and whose only tag matching the request is the family tag); a row with `contentType` `Topic` (wrong case).

| Case | What it checks |
| --- | --- |
| **P12 lane ranking** | A multi-word tag matches by token set (the tag `internal control` qualifies the query "Internal Control failing collect logs", which has no whole-tag token); a single-word generic tag alone stays below the floor, also with markdown tokens only, and qualifies with one heading or summary token. A blank tag never matches. A chunk with a valid id behind an invalid higher-scoring row still gets its lane slot; an overlong id and a path-invalid id are each kept out of the lane, in either `docId` or `chunkId`, and `matches` still lists them as before; a document whose only topic has an invalid id gives no `topics`. A repeated query word earns nothing (`logs logs` against a signal prefix scores as `logs`). A one-word query contained in a heading earns no whole-query points; a multi-word query does. With an `assetModel` hint and `limit` 3 and 10, and with the family tokens in the query text, the relevant topic is in `topics` while `matches` is exactly what it was before the change. The distractor and the unrelated topic do not qualify, also with the family words in the query; a family word that prefixes a signal name earns nothing; an identity-only chunk signal earns nothing, also when a request signal equals it; a multi-token asset-only query contained in a topic heading does not qualify, and the same heading qualifies when the query adds a task word; text-only matches and heading token matches do not qualify; a topic whose document has no manifest keeps its tags; two relevant topics give two ordered by score, then `chunkId`, then `docId`, with one equal-score pair in different documents; a third is cut; `Topic` (wrong case) is not offered; a query entirely in Chinese does not fail: it matches a tag by whole-token equality, and a signal by equality or the length-qualified prefix (`发电机故障` against the signal `发电机故障诊断`), but not by non-prefix overlap (`发电机故障` against `备用发电机故障记录`). Lane documents: with explicit `documentIds` that include the article, the lane runs; with ids that exclude it, it does not; with ids none of which exist in the index, the lane is empty (not all documents) and `matches` is unchanged; with no ids at all the lane is empty; `documentIds: []` beside an injected scope gives an empty lane; a topic outside an injected scope is excluded; `documentTypes` that excludes the article's type does not remove the topic from `topics` but still filters `matches`. `scoreChunk` results for the existing gold-set and affinity cases are unchanged |
| **P13 shape and egress** | `topics` has exactly the six projected fields, precedes `matches`, is absent when empty (the rest of the JSON is byte-identical to the previous round for fixtures without topics). Real runtime-produced lane output plus page results large enough for each of the three egress passes (section 2.3) go through the real egress: the usable `docId`/`chunkId` and `loadWith` survive in each, and two entries with 256-unit valid ids come through every pass exactly, not excerpted. A heading over 80 characters is cut without splitting a surrogate pair; an absent heading is the empty string |
| **P14 offer** | The hook records `methodOffered` for a result with `topics` only, with a topic only in `matches`, and not for neither. Round-2 cases stay green |
| **P15 instructions** | All five strings of R13 are present verbatim and none of the three surfaces still carries the unconditional sentence; the resolver handoff tests stay green; other asserted phrases unchanged |
| **P16 type filter** | A filter matching nothing adds `DOCUMENT_TYPE_FILTER_EMPTY` with at most 10 distinct types of the documents the request may search; a filter that matches does not; with explicit ids the list is limited to those documents; with ids none of which exist no warning is added; the warning survives egress |
| **P17 features** | `topic-lane/1` listed last with the Java loader, absent without it; deterministic order (the `ActiveMethodLifecycleTest` list assertion) |
| **P18 end-to-end shape** | One scripted conversation in the failing shape: turn 1 resolves a unit; turn 2 (no new resolver call) searches with `assetModel` and `limit` 3. The `topics` array is **produced by the production runtime and scorer** on the fixture package (not hand-written), and the scripted model consumes it; the next round's list contains the operational tool |

The only existing assertion expected to change is the exact feature list in `ActiveMethodLifecycleTest`; no test pins the replaced description sentences. One existing fixture has a topic row (`document-knowledge/topic-round1/chunks.jsonl`), and scoped searches of it will now also carry `topics`. The existing tests assert fields of match rows, not the top-level field set, so they are unaffected. All other fixtures contain no `contentType: topic`, so the omitted-when-empty lane leaves their results untouched.

## 7 Build, verify, version, process

As earlier rounds: `cd parler-agent && ./gradlew test assemble --no-daemon -PuseLocalTwxLib=true`; live work is the User's; no version, `metadata.xml` or `CHANGELOG.md` edits on the topic branch; work on the branch the User or orchestrator creates (`topic-round3`), never on `nearterm`. Documentation to update with the code: `docs/agent/document-chunk-tools.md` (the `topics` array and its projection, lane rules and scope, the `documentTypes` exception, `DOCUMENT_TYPE_FILTER_EMPTY`, the instruction wording in the schema copy and the prose of sections 5.2 and 6.5, the `empty` row of the `selectionMode` table, a test row in its coverage table, and that `selectedDocIds`/`selectionMode` describe `matches` only), `docs/agent/all-tools.md` (the three description copies), `docs/agent/configuration-repository.md` (the feature row and the exact list), `docs/agent/task-state.md` (the offer sources), and `docs/agent/README.md` (index this design).

## 8 Done means

Local acceptance: P12–P18 and the full command pass, canonical docs match the code, the slice is pushed and submitted for review.

The User's live check, after importing the ZIP, with narrowing enabled and admission `off`:

1. Repeat the failing conversation in the wording that produced the `assetModel` hint (turn 1 names the unit; turn 2 asks what to collect for the Internal Control symptom and to collect it). Whatever arguments the model chooses, as long as the search carries the unit's scope, the result has `topics[0]` with the topic and `loadWith`.
2. If the model loads it: the earlier checklist (tools stay, approvals, continuation, nine values, both text searches, source link). If it does not load it: the registered operational tools are still in its next round.
3. A search with a wrong `documentTypes` returns the warning with the available types.
4. `GetAgentRuntimeSnapshot.features` lists `topic-lane/1`.
5. Not claimed: that a mixed document-plus-action request without a topic keeps its tools (section 4, row 5), or that a search passing sampled ids finds the topic (row 14).

As in round 2, mechanism acceptance (1, 2's second sentence, 3, 4) is recorded separately from the completed demonstration (2's first sentence); the model's choice to load is behaviour, not a guarantee.

## 9 Decisions proposed for reviewer agreement, and what is deferred

- A separate lane with its own scorer, rather than a topic bonus in `scoreChunk` or reuse of `scoreChunk` without the document term: the bonus would have to beat an unbounded document score and would distort every query; `scoreChunk` still contains document identity (section 2.1).
- The lane exists only inside a real document scope and ignores `documentTypes` for itself. A topic is addressed to the scope the unit resolved.
- The projection is compact (six fields) so the lane survives egress without touching the shared gateway.
- Asset identity earns no structured evidence in any branch (R11.3). Every topic of a unit's set shares it, so a family tag, signal or query would otherwise make every such topic qualify, which is section 1's failure in a smaller form. Weak word overlap still counts family words, because it cannot qualify a topic. This is the only use of the manifest in the lane. A generator that writes a family name not listed in `assetModels` escapes the rule; the score order still favours the relevant topic.
- The lane admits only ids that `TopicMethodText.validId` accepts (R11.2), so an entry's `loadWith` and its bounded size are established by the lane, not assumed from the generator. `matches` keeps today's behaviour (row 17).
- The whole-query heading term needs at least two distinct tokens, because a one-word query is a substring test on letters and P12 requires that heading-only matches do not qualify.
- The R13 strings are normative, because the rule that protects a first search (a sampled set is not passed) has to reach the model verbatim.
- Floor 30, `structured >= 25`, caps and two entries are constants, heuristics for a first round, calibrated on the synthetic fixtures and the runs in section 1. Make them settings only if a deployment shows a need.
- **Deferred:** the mixed document-plus-action gap (row 5); progress-guard handling of offered methods (row 11); entity derivation beyond a single inline match; changing `resolveAllowedDocIds`; a sampled-and-no-default handoff; a turn-start topic probe; changes to document-level scoring or egress limits; explicit ids that omit the topic's document (row 14); non-Latin tokenization (row 15). Reopen the progress guard when a run shows a forced summary after an offer; the mixed-task gap when an agreed way to tell document-only from operational requests exists.

## 10 Glossary

- **topic lane** — the top-level `topics` array: the best `contentType: topic` chunks of the scoped documents, ranked by a topic-local score, as a compact projection.
- **request tokens** — the distinct query tokens plus the distinct tokens of the signal names. Asset-context tokens are not request tokens.
- **structured / weak evidence** — the two parts of the topic-local score (R11.3): matches on authored signals, tags and the whole query, and capped word overlap with heading, summary and markdown.
- **asset tokens** — the tokens of a document's `assetModels`. A text made only of them is **identity-only** and earns no structured evidence.
- **document-level score** — the part of a match's score that every chunk of a document inherits from the document's identity and asset evidence.
- **offer** — as in round 2: a built-in search result that contains a topic; now also a non-empty `topics` array.
