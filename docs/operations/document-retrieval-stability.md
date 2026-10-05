# Document Retrieval Stability

**Status:** Implemented. This document describes document-aware ranking, the no-progress search
loop guard and post-first-document-tool narrowing that keep multi-document retrieval stable when
unrelated manuals coexist in the same `AIDocRepository` index. The examples use the synthetic sample
corpus in `dev_data/future_repo/document-knowledge/`.

## 1. Boundary

[`doc-index-enhance.md`](./doc-index-enhance.md) covers conversion quality, corpus layout and the
first runtime safeguards. This document covers multi-document retrieval stability:

- document identity aware ranking;
- conversion metadata that makes on-topic chunks competitive;
- a no-progress document-search loop guard;
- a conservative revision to document-turn tool narrowing after the model has
  already chosen a document tool.

## 2. Hard Constraints

1. `fernwick-carbaq-ops-v2` MUST remain in the live index.
2. The solution MUST NOT remove, hide, de-index, or down-scope documents to make
   a prompt pass.
3. The solution MUST NOT hard-code single-prompt or single-document branches
   such as `if query contains RK&T then boost doc X`.
4. The server MUST NOT invoke `search_document_chunks` or `get_document_chunk`
   based on user-message keywords.
5. The maximum allowed runtime routing intervention is post-first-document-tool
   narrowing: after the model has already called a document tool in the current
   turn, and no non-document tool has run, subsequent LLM rounds MAY be narrowed
   to the document tool set. Implementations MUST NOT exceed this limit.

## 3. Failure Shape

A prompt such as:

```text
Search the RK&T operating manual for safety-relevant operating trouble such as
overspeed, steam pressure, bearing temperature, lube oil pressure, and
vibrations. What does the manual say to do before restarting the turbine? Cite
the original PDF page link.
```

can run repeated `search_document_chunks` calls with no `get_document_chunk` until
`Agent reached max iterations (10)` when the top results are dominated by the
signal-rich off-subject `fernwick-carbaq-ops-v2`, while the correct RK&T content
(`rk-t-operating-manual-7318042 /
section-0080-part-a-08-trouble-causes-and-their-elimination`) is typed as a general
`section` with no signals. This is a retrieval stability failure, not a tool-exposure
failure.

## 4. Design Principle

The old model is global chunk competition:

```text
query -> score all chunks -> return global top-k
```

That is unstable when a signal-rich off-subject document has stronger chunk
metadata than the right document.

The new model is document-aware retrieval:

```text
query + assetContext + signals
  -> score documents
  -> score chunks with document score as a first-class factor
  -> diversify by document unless one document is unambiguously selected
```

This design intentionally separates query classes:

1. **Document identity queries** contain explicit document, asset, title,
   filename, or alias evidence, such as "RK&T operating manual" or
   "CB 24 GT4". Lexical document scoring should handle these deterministically.
2. **Paraphrased information-need queries** describe a concept without reliable
   document identity tokens, such as "what trips the turbine and how do I bring
   it back online". Semantic synonym expansion is not attempted at the
   document-identity layer. These queries rely on diversified chunk recall,
   improved conversion metadata, and later model reasoning over returned
   snippets.
3. **Mixed queries** contain both document identity and information need. The
   document score should make the right document competitive, while chunk score
   should still decide the best section inside that document.

Hard single-document restriction is not the default. A wrong hard selection can
hide the correct document and produce a silent "not found" failure. The stable
default is:

- score every document;
- boost chunks by document identity/domain evidence;
- cap how many top-k rows one `docId` can occupy when multiple documents remain
  plausible;
- use hard single-document restriction only for explicit `documentIds` or very
  high-confidence identity matches.

The per-document cap is the weight-independent safety net. It prevents one
signal-rich off-subject document from filling all top-k rows while scoring
weights are calibrated. Document-score boosts are an optimization for ordering;
the cap is the recall guarantee when more than one document remains plausible.

## 5. Document Profile

Each package manifest exposes a `documentProfile` used for deterministic
document scoring.

The profile MUST distinguish two kinds of data:

1. **Identity fields**: stable labels that identify the document or asset.
2. **Derived domain terms**: terms generated from the document itself, not from
   known acceptance prompts.

Shape:

```json
{
  "docId": "rk-t-operating-manual-7318042",
  "title": "RK&T Operating Manual - Turbo-generator Set CB 24 GT4 (7.318.042)",
  "documentType": "operations_manual",
  "assetModels": ["CB 24 GT4"],
  "documentProfile": {
    "aliases": [
      "RK&T operating manual",
      "CB 24 GT4 operating manual",
      "turbo-generator operating manual"
    ],
    "manufacturers": ["RK&T"],
    "assetModels": ["CB 24 GT4", "steam turbine", "turbo-generator"],
    "documentKinds": ["operating_manual"],
    "domainTerms": [
      "trouble",
      "causes",
      "elimination",
      "commissioning",
      "operation",
      "bearing",
      "lube oil",
      "vibration"
    ],
    "profileSource": {
      "aliases": "curated-identity",
      "manufacturers": "curated-identity",
      "assetModels": "manifest-and-title",
      "documentKinds": "manifest-documentType",
      "domainTerms": "derived-from-toc-headings-and-section-headings"
    }
  }
}
```

### 5.1 Existing Manifest Fields

The scorer MUST use existing fields before requiring new ones:

- `docId`
- `title`
- `documentType`
- `assetModels`
- source filename/path when present

`documentProfile` extends those fields. It MUST NOT become a disconnected
second source of truth.

When `documentProfile` is absent, fallback scoring MUST still use `docId`,
`title`, `documentType`, `assetModels`, and source filename tokens. A thin or
missing profile MUST NOT make a newly added document unreachable.

### 5.2 Derived Domain Terms

`domainTerms` SHOULD be produced by conversion from the document itself:

- title;
- table of contents;
- chapter and section headings;
- table captions and row labels when available;
- major troubleshooting/fault sections.

`domainTerms` MUST NOT be hand-seeded from the exact acceptance prompt. This
prevents moving over-fitting from Java code into manifest data.

Curated identity fields such as `aliases` and `manufacturers` are allowed
because they identify the document, not a specific answer prompt.

Package validation SHOULD include an over-fit check: compare `domainTerms`
against the frozen acceptance fixture prompts and fail or warn when one document
shares an implausibly large number of prompt-only tokens with a single fixture.
Legitimate heading-derived terms such as `trouble`, `vibration`, or `restart`
remain allowed when they are present in the source document.

## 6. Document Scoring

`search_document_chunks` threads the `assetContext` object fields into document
scoring as evidence (not only as a "search input is non-empty" check).

### 6.1 Inputs

Build scoring tokens from:

- `query`;
- `signals[].name`;
- all scalar/string values inside `assetContext`;
- `documentTypes`;
- `documentIds` (exact include filter);
- manifest fields and `documentProfile`.

Normalize with stable lexical rules:

- lowercase;
- split on non-alphanumeric boundaries;
- preserve meaningful multi-token phrases for exact phrase matching;
- normalize singular/plural only if implemented deterministically.

### 6.2 Evidence Classes

Deterministic evidence classes, from strongest to weakest:

| Evidence | Example | Weight class |
|----------|---------|--------------|
| Explicit `documentIds` include | `rk-t-operating-manual-7318042` | hard filter |
| Exact alias/title phrase | "RK&T operating manual" | very strong |
| Source filename/docId token phrase | `rk-t-operating-manual` | strong |
| Document kind | operating manual vs installation specification | strong for same-manufacturer split |
| Asset model phrase | `CB 24 GT4`, `KBM` | medium |
| Manufacturer | `RK&T`, `Fernwick Labs` | medium, never decisive alone |
| Derived domain heading terms | trouble, causes, foundation, back pressure | medium/weak |
| `documentType` enum | `operations_manual` | weak, because multiple docs share it |

Manufacturer or asset-model evidence alone MUST NOT hard-select a document when
multiple documents share that manufacturer or asset family.

Implementations SHOULD normalize equivalent document-kind spellings before
scoring, for example `operating_manual` and `operations_manual`. Raw
`documentType` enum equality is not stable enough across packages.

### 6.3 Selection And Diversification

The default result construction is diversified chunk ranking:

1. Compute document scores.
2. Compute chunk scores.
3. Add document score as a first-class boost to each chunk.
4. Build top-k with a per-document cap when more than one document remains
   plausible.

Constants:

- `perDocCap = max(1, ceil(limit / 2))` when more than one document is selected.
- Hard single-document restriction only when:
  - `documentIds` is explicitly supplied and valid; or
  - top document has exact alias/title/docId/source-filename phrase evidence,
    score >= `T_high` (80), and lead over second document >= `T_margin` (30).

Hard selection is a high-confidence optimization, not the normal path.

Calibration MUST follow invariants rather than prompt-specific tuning:

- an exact alias/title/docId/source-filename identity match MUST outscore the
  maximum achievable chunk-metadata score of a non-matching document;
- a wrong document's chunk-level troubleshooting/alarm bonuses MUST NOT be able
  to fill the entire result set when another plausible document has matching
  identity or competitive chunk evidence;
- same-manufacturer or same-asset evidence alone MUST NOT satisfy the
  high-confidence hard-selection gate;
- the diversified cap MUST still surface at least one competitive result from a
  second plausible document even if score calibration is imperfect.

Diversification uses a three-pass top-k build: the primary pass enforces the
per-document cap on the score-sorted list; a capped backfill pass adds rows from
documents still under cap; when no other document can improve diversity, a final
backfill pass fills remaining slots from the best available chunks so the result
count reaches `limit` without letting one document occupy every row.

The invariants are scenario tests: they compute or fixture the wrong document's
maximum achievable chunk score for the request and assert that right-document
identity plus document boost beats that wrong-document maximum, so corpus growth
fails tests when the inequality no longer holds.

### 6.4 Debug Output

Search results include bounded debug fields so a ranking outcome can be explained
without dumping prompts. These fields are for tool diagnostics and logs; the UI does
not render them.

```json
{
  "documentScores": [
    {
      "docId": "rk-t-operating-manual-7318042",
      "score": 145,
      "matchedEvidence": ["alias:RK&T operating manual", "kind:operating_manual"]
    }
  ],
  "selectedDocIds": ["rk-t-operating-manual-7318042"],
  "selectionMode": "diversified"
}
```

These fields are diagnostic. They should be bounded and safe for tool output:

- cap the number of reported documents;
- cap or summarize `matchedEvidence`;
- do not include raw prompt text beyond already-returned query/result snippets;
- make the fields optional so older clients can ignore them.

A scorer/unit test serializes these diagnostics for a fixture query so live smoke
scripts can assert `documentScores`, `selectedDocIds`, and `selectionMode` without
scraping prompt text.

## 7. Chunk Scoring And Conversion

Document-aware retrieval does not remove the need for better chunk metadata.
After the right document is favored, the right chunk must still compete inside
that document.

Conversion promotes operational trouble chapters into troubleshooting chunks:

- headings containing `trouble`, `fault`, `failure`, `emergency`, `alarm`,
  `cause`, `elimination`, `shutdown`, or `restart` SHOULD become
  `contentType: troubleshooting`;
- tags/signals SHOULD be derived from heading text, table labels, and row
  labels;
- original PDF page links and section paths MUST be preserved.

Corpus target: `rk-t-operating-manual-7318042 /
section-0080-part-a-08-trouble-causes-and-their-elimination` is a troubleshooting
chunk with signals. The RK&T weak-identity paraphrase and stability fixtures depend
on this typing, so conversion or validation changes require re-extracting
`dev_data/future_repo` (see `doc-index-enhance.md` §5.0).

## 8. No-Progress Document Search Loop Guard

D1 catches repeated identical tool calls. It does not catch paraphrased search
loops, so a separate document-search progress guard (`DocumentSearchProgressGuard`)
runs as well.

Triggers:

```text
same turn:
  4 consecutive search_document_chunks calls
  AND no get_document_chunk since the latest user turn
```

Additional early trigger:

```text
same turn:
  3 consecutive search_document_chunks calls
  AND no get_document_chunk since the latest user turn
  AND top result fingerprint has not meaningfully changed
```

The zero-fetch trigger is required because a model can evade a strict
fingerprint-stability rule by paraphrasing enough to churn result membership
while still making no useful progress. The fingerprint trigger catches the
stronger "same results again" case earlier.

Fingerprint:

- ordered list of the first `K` `(docId, chunkId)` pairs;
- recommended `K = min(limit, 6)`;
- ignore score-only changes unless they change ordering or membership.

Reset/disable rules:

- `get_document_chunk` resets the counter;
- a non-document tool disables document-only assumptions for that turn;
- if D1 and this guard would both fire, emit only one forced tool-none
  finalization path.

Action:

- force a tool-none finalization round;
- tell the model to answer from available search snippets or state that the
  retrieved evidence is insufficient.

The guard never calls `get_document_chunk` on the model's behalf.

An insufficient-evidence terminal is acceptable; the guarantee is bounded behavior
instead of `Agent reached max iterations (10)`. The saturation variant that also
covers interleaved fetches is described in
[`document-retrieval-convergence.md`](./document-retrieval-convergence.md) (C1).

## 9. Conservative Post-First-Document-Tool Narrowing

This extends the D2 gate from `doc-index-enhance` (slash-skill narrowing) so that a
document prompt without `/document_search` does not keep the full tool schema.

Rule:

```text
If, in the current turn:
  the model has already called search_document_chunks or get_document_chunk
  AND no non-document tool has run
then:
  subsequent LLM rounds MAY expose only:
    get_agent_skill
    search_document_chunks
    get_document_chunk
    resolve_document_set
```

Clarifications:

- there are two lawful narrowing paths:
  - legacy slash-skill narrowing from round 1 when the user explicitly
    slash-loads `/document_search`;
  - post-first-document-tool narrowing from round 2 or later after the model has
    already chosen a document tool;
- the server MUST NOT inspect keywords and call tools;
- this rule only narrows future tool schemas after the model has already chosen
  the document path;
- `get_agent_skill` remains available as the escape hatch for a mixed turn that
  needs to route back out of document-only work;
- `documentTurnToolNarrowingDisabled=true` remains the one-flag revert for the
  entire mechanism.

## 10. Search Tool Contract

- Document hints are extracted from `query`;
- `assetContext` scalar/string values are used in document scoring;
- `documentTypes` is a weak signal only;
- `documentIds` is an exact include filter; ids normally come from prior search results,
  `resolve_document_set`, or exact user-supplied ids. Unknown `documentIds` return empty
  success with a warning, not a turn failure.

The manifest schema and search-result diagnostic fields are specified in
`docs/agent/document-chunk-tools.md`.

## 11. Acceptance Tests

Tests avoid merely repeating manually seeded profile terms; they include
paraphrases and adversarial near-collisions.

### 11.1 Retrieval Fixtures

| Intent | Expected |
|--------|----------|
| RK&T operating trouble with explicit identity, such as "RK&T operating manual trouble before restarting" | `rk-t-operating-manual-7318042`, trouble-causes/elimination content in top results through identity-aware scoring |
| RK&T operating trouble paraphrase with weak identity, such as "what trips the turbine and how do I bring it back online" | trouble-causes/elimination content surfaces through diversified chunk recall; no hard document-identity gate is required |
| RK&T installation foundation and turbine/driven-machine alignment | `rk-t-install-spec-7318042` |
| RK&T start-up with back pressure below 6, 6-11, and above 11 bar(g) | `rk-t-install-spec-7318042` back-pressure sections |
| KBM coupling misalignment and damage before commissioning | `kbm-coupling-manual-7318042` |
| CarbaQ chiller high pressure shutdown | `fernwick-carbaq-ops-v2` |

### 11.2 Stability Fixture

Use a family of phrasing variants for the RK&T operating-trouble intent. They
should vary wording for trip/trouble/restart/bring online without copying the
exact `domainTerms` list.

Minimum fixture set:

- at least 2 identity-bearing variants, including the trigger shape "RK&T
  operating manual";
- 5 weak-identity paraphrases that do not repeat the exact profile/domain-term
  wording.

Pass bar:

- all identity-bearing variants MUST select or strongly favor
  `rk-t-operating-manual-7318042`;
- at least 4 of 5 weak-identity paraphrases MUST surface the RK&T
  trouble-causes/elimination chunk in top-k via diversified recall;
- zero variants may hard-select or top-rank `fernwick-carbaq-ops-v2` or
  `rk-t-install-spec-7318042` as the favored document for the operating-trouble
  intent;
- failures MUST report the returned top-k document ids and `selectionMode` so a
  reviewer can distinguish scoring drift from conversion metadata defects.

### 11.3 Adversarial Fixtures

| Case | Expected |
|------|----------|
| RK&T operating-vs-install near collision | operating queries select/favor operating manual; installation queries select/favor install spec |
| No document identity, e.g. "what is the alignment tolerance?" | no hard single-doc gate; diversified corpus results |
| Thin or missing `documentProfile` on a newly added doc | document remains reachable through title/docId/chunk scoring |
| Shared manufacturer/asset model only | manufacturer or asset model alone does not hard-select between same-family docs |
| Strong Fernwick identity, e.g. "CarbaQ chiller high pressure" | `fernwick-carbaq-ops-v2` ranks above RK&T; diversification must not invert the intended document |
| Wrong document has maximum troubleshooting/alarm chunk boost | exact document identity on the right document still dominates the wrong document's chunk metadata |

### 11.4 Loop Guard Fixtures

- 3 equivalent `search_document_chunks` result fingerprints with no
  `get_document_chunk` trigger the guard.
- 4 `search_document_chunks` calls with no `get_document_chunk` trigger the
  guard even when paraphrases churn top-result membership.
- `get_document_chunk` resets the guard.
- D1 and this guard do not stack duplicate forced-finalization paths.

### 11.5 Narrowing Fixtures

- Without `/document_search`, the first LLM round is not narrowed solely by
  keywords.
- With `/document_search`, legacy slash-skill narrowing still applies from the
  first round.
- After the model calls `search_document_chunks`, later rounds narrow to document
  tools when no non-document tool has run.
- If a non-document tool has run, post-first-document-tool narrowing does not
  apply.
- A mixed turn can still route back through `get_agent_skill` rather than being
  trapped in document tools.

### 11.6 Live Smoke

`test_scripts/run_rkt_operating_trouble_smoke.py` runs the RK&T operating-trouble
prompt against a live agent. It asserts:

- final answer, not max iterations;
- at least one `get_document_chunk`;
- retrieved/final cited source is `rk-t-operating-manual-7318042`;
- no `fernwick-carbaq` dominance for the RK&T prompt;
- bounded iterations and wall time;
- diagnostic evidence for document scores / selected docs when available
  (`--require-search-diagnostics`).

## 12. Non-Goals

- Removing `fernwick-carbaq-ops-v2`.
- Keyword-triggered server-side tool calls.
- Embeddings as the first fix.
- Single-prompt or single-document accommodations.
- A general-purpose RAG system.
