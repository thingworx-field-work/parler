# Document Retrieval Convergence

**Status:** Implemented (C1 saturation guard, C2 signal-chunk demotion). This document describes how
document-knowledge turns converge on a grounded answer when a broad, multi-intent question keeps
surfacing the same content. The examples use the synthetic sample corpus in
`dev_data/future_repo/document-knowledge/`.

Related documents:

- [`document-retrieval-stability.md`](./document-retrieval-stability.md) §8 — the no-progress
  document search loop guard that C1 extends.
- [`knowledge-retrieval-pipeline.md`](./knowledge-retrieval-pipeline.md) — resolver, BM25/IDF scorer
  and acceptance gold set.
- `docs/agent/document-chunk-tools.md` — tool contract (`search_document_chunks`,
  `get_document_chunk`, `resolve_document_set`).

---

## 1. Background

Each document package holds chunk records (`chunkId`, `contentType`, `heading`, `sectionPath`,
`pageStart`, `pageEnd`, `tags`, `signals`, `summary`, `markdown`, `sourceLinks`) of three kinds:
`page-*` (one per PDF page), `section-*` (logical sections, capped ~4.1 KB markdown) and `signal-*`
(heading/marker chunks — a section title plus a little context, low remedial content).

The agent loop is capped at **`MAX_AGENT_ITERATIONS = 10`**. If it never emits a final answer it
terminates with `Agent reached max iterations (10)` and the user gets **no answer**. Per-turn perf
telemetry (`llmUsageJson`) includes `agentIterations`, `finalAnswerRoundIndex` (`-1` = never
finalized), `repetitionBlockedCount`, `noToolFinalAnswerApplied`, `multiToolCallRoundsCount`.

Existing guards: the `document-retrieval-stability` §8 guard forces a tool-none summary round after
either (a) **four zero-progress `search_document_chunks` calls**, or (b) **three stable result
fingerprints _without_ an intervening `get_document_chunk`**. `D1` blocks byte-identical repeated
tool calls; `D2` (`DocumentTurnToolNarrowing`) narrows the tool surface on document-skill turns
(`doc-index-enhance.md`).

## 2. Failure shape these changes address

A broad, multi-intent within-document prompt (for example low oil pressure *and* high bearing
temperature *and* oil mist around the bearing housing, in the RK&T operating manual) can drive the
agent to many paraphrased searches and repeated fetches of the same trouble chunk until it hits the
iteration cap. Ranking is not the problem — the right chunk ranks #1 — but:

- **No saturation signal (primary).** Nothing tells the model "you have already seen the top
  chunk(s) with no new content; answer from what exists and state what the manual does/does not
  cover." The §8 guard misses this because paraphrased queries are not byte-identical (D1 and the
  fingerprint trigger mostly do not fire) and interleaved `get_document_chunk` calls keep
  resetting the "stable fingerprint _without_ fetch" condition.
- **`signal-*` heading chunks pollute the result set (secondary).** They score like content on
  these queries and crowd the top-k with signposts, reinforcing the model's sense that "the answer
  must be nearby — search again."

Search matches already carry `pageStart` / `pageEnd` and a `snippet`, so citation data is not the
driver.

---

## 3. Mechanisms

### C1 — Retrieval-saturation convergence guard

Extends the `document-retrieval-stability` §8 guard so that *repeated retrieval of the same top
chunk(s) with no new content* — even when `get_document_chunk` is interleaved and the search query
is paraphrased — forces a **grounded tool-none answer** that states coverage. The trigger is content
saturation, not a blind "summarize at `MAX-1`".

- **Saturation tracker** lives alongside the fingerprint/zero-fetch guard in
  `DocumentSearchProgressGuard`, but is **not reset by `get_document_chunk`**. It advances on
  *content actually surfaced*, kept as two identity sets — `searchSurfacedKeys` (a chunk appeared in
  a search top-set) and `fetchedKeys` (its full body was fetched) — because a snippet and a full
  body are different modalities of "surfaced": either being new is progress, so a legitimate
  multi-chunk read never trips the guard.
- **Content identity = substantive `chunkId`.** Heading-only `signal-*` chunks are excluded from
  progress accounting, so a `signal-*` heading and its parent section do not count as two
  independent steps. No scoring path changes — this is loop accounting only.
- **K = 3** consecutive document-retrieval rounds with no new substantive content → fire, **gated**
  on at least one substantive `get_document_chunk` fetch having occurred, so search snippets alone
  never force a premature finalize (the pure-search loop stays the §8 zero-fetch path's job).
- **Grounded-coverage finalize.** When the saturation path fires it raises a dedicated flag; the
  tool-none round then injects a one-round ephemeral framed instruction
  (`DocumentCoverageSummaryInjector`) requiring the answer to (1) cite the chunks/PDF page links
  already surfaced, (2) answer every covered sub-intent, and (3) **explicitly state which requested
  symptoms/causes/remedies the manual does NOT cover**. When the manual does cover everything, a
  complete grounded answer is the expected outcome.
- **Context-planner integration.** The row is materialized as
  `ParlerSuffixFraming.SERVER_INSTRUCTION + "\n" + DocumentCoverageSummaryInjector.PREFIX` and is
  recognized by `ContextBudgetPlanner.isFramedEphemeralSystem` (the single chokepoint for both
  ephemeral-char accounting and the active assistant/tool batch scan). On a saturation finalize
  round the trailing coverage `SYSTEM` row is skipped, so the surfaced evidence the coverage
  instruction must cite stays protected under trimming pressure. Provider serialization moves the
  row into the terminal suffix without weakening its server-instruction authority.
- **Forced-summary composition.** The post-tool-batch checkpoint resolves through
  `AgentToolContext.resolveForcedSummaryDecision(...)`: the repetition-blocked
  (`repetitionBlockedCount >= 2`) and document-search saturation paths **compose**, so a pending
  grounded-coverage request rides whichever forced-summary path fires.
- **Code:** `DocumentSearchProgressGuard` (tracker + `recordSearchSaturation` /
  `recordFetchSaturation` + saturation wiring in `interceptSearchLoop` / `onGetDocumentChunk`),
  `AgentToolContext` (grounded-coverage flag + `resolveForcedSummaryDecision`), `AgentThing`
  (`onGetDocumentChunk` receives the fetch result JSON), `AgentLoop` (coverage-round plumbing +
  injector insert/remove + compose decision), `ContextBudgetPlanner`,
  `DocumentCoverageSummaryInjector`. Tests: `DocumentSearchProgressGuardTest` (saturation +
  composition) and `ContextBudgetPlannerTest` (coverage-round evidence protection).

### C2 — Demote `signal-*` heading chunks

Heading/marker chunks are demoted in the `search_document_chunks` result so the top-k is
substantive content.

**Semantics: global demotion.** `DocumentKnowledgeSearchScorer.comparator()` has a PRIMARY key
`isSignalChunk` (substantive `0` before `signal-*` `1`), then the reversed-score / content-type /
semantic / docId / chunkId keys. A single global total order stays transitive (a per-document
"substantive before signal" rule combined with cross-document score order can form a cycle). With
the global partition, a small top-k over a well-populated document is **entirely** substantive
content — signals only appear once the substantive candidates are exhausted.

**Ranking only:** `scoreChunk` scores, `scoreDocument` / `documentScores`, `buildSelectionPlan`,
BM25, and the exposed score arithmetic are untouched, so document identity/selection and every
gold-set selection dimension are unaffected. Document selection reads the manifest, not chunk-level
signal content; only the per-chunk answer ranking (`allScored` → `matches[]`) changes.

**Discriminator = the `signal-` chunkId prefix** (constant `SIGNAL_CHUNK_PREFIX`, the class C1
uses). Body length is not usable: `signal-*` chunks carry a median ~1 KB of markdown (vs ~1.2 KB
for `page-*`) — a signpost with context, not an empty heading. A `trouble-*` chunk with a
`signals[]` entry is **not** a `signal-*` chunk and is unaffected.

**Tests:** `DocumentKnowledgeSearchScorerTest#c2_signal_chunk_demoted_below_substantive_even_with_higher_raw_score`
(a `signal-*` chunk with a *higher* raw score still ranks below substantive content) and
`DocumentKnowledgeGoldSetTest#c2_signal_chunks_do_not_outrank_substantive_content` (over
`rk-t-operating-manual-7318042`, all substantive content partitions before every `signal-*`, and
the substantive `section-0080` trouble chunk ranks above them).

---

## 4. Acceptance

- **Primary gate = JUnit in `parler-agent`** against committed offline fixtures:
  - a paraphrased-search + interleaved-fetch loop over a coverage-gap query terminates in a
    **grounded final answer**, not `max iterations`;
  - C2: top-k for the trouble queries contains no heading-only chunk above a content chunk; the
    `knowledge-retrieval-pipeline` gold set still passes unchanged.
- **Live smoke** (evidence, not the gate): the broad multi-intent prompt finalizes within the loop
  budget with a PDF-page-cited answer; `test_scripts/run_rkt_operating_trouble_smoke.py` covers the
  within-document case.

## 5. Out of scope

- The resolver/scorer behavior of `knowledge-retrieval-pipeline` and the trade-off in
  `docs/core/knowledge-search-tradeoff.md`.
- External/embedding retrieval.
- Row-level chunking of troubleshooting tables.
