# Knowledge Search Trade-off — Parler knowledge base storage & retrieval

**Status:** Design rationale for the implemented document retrieval approach. The runtime is
described in [`docs/operations/knowledge-retrieval-pipeline.md`](../operations/knowledge-retrieval-pipeline.md)
and the tools in `docs/agent/document-chunk-tools.md`.

The examples below use the sample corpus in `dev_data/future_repo/document-knowledge/`
(built by `test_scripts/pdf-conversion/synthetic/`): an off-domain chiller manual
(`fernwick-carbaq-ops-v2`) next to RK&T turbine manuals and a KBM coupling manual.

## 1. What makes Parler's situation specific

Retrieval choices that are obvious for a generic web SaaS are often wrong here.
Four properties shape the design.

### 1.1 ThingWorx platform characteristics

- Parler runs **in-process** as a Java extension inside the ThingWorx server. It is
  not a standalone service with its own datastore.
- The natural document store is a **FileRepository** (`AIDocRepository`) — a file
  tree, not a search engine. There is **no native vector index** and no
  first-class full-text engine guaranteed across deployments.
- ThingWorx's real strength is its **entity model and services**: Things,
  ThingTemplates, ThingShapes, relationships, and App-developer-authored services.
  Structured context is cheap and reliable to obtain; semantic compute is not.
- A turn already runs under a tight **provider rate gate**. Anything that adds
  rounds or tokens is expensive in wall-clock, not just dollars.
- **host-context** already delivers the bound Mashup / page / asset scope into the
  turn. The platform usually *knows what the user is looking at*.

### 1.2 Division of responsibility — Agent developer vs App developer

This split is load-bearing for the whole design.

- **Agent developer (Parler):** the `parler-agent` runtime, the tool *contracts*
  (`search_document_chunks`, `get_document_chunk`, the resolver), the ranking
  algorithm, the storage/chunk format, the loop guards, and the diagnostics. Parler
  owns *mechanism*, and keeps it generic — no customer-specific branches.
- **App developer (the customer):** the corpus content, which Things exist, which
  equipment a Thing represents, and — critically — **which documents apply to which
  Thing/asset**. They own *domain knowledge and mapping*. ThingWorx already expects
  App developers to encode this kind of logic in services (the same
  delegate-to-a-service pattern Parler uses for `invoke_service`/wrapper tools).

The design pushes *domain mapping* to the App developer and keeps *retrieval
mechanism* in Parler, so the runtime never hard-codes domain knowledge (e.g.
"RK&T → turbine docs").

### 1.3 Industrial application *platform* characteristics

- Parler runs **inside the ThingWorx JVM** (on-prem or cloud). That JVM does **not**
  host a full industrial-grade retrieval service — no co-located embedding model, no
  full-scale vector store. What Parler can do *in-process* is bounded to entity
  lookups, file reads, and lightweight lexical ranking; anything heavier is an
  **external integration**, not an in-JVM capability.
- Output is expected to be **deterministic, explainable, and auditable** —
  maintenance decisions are safety-relevant. A black-box reranker that cannot
  explain *why* a chunk was chosen is a harder sell than in consumer search.
- Configuration is **version-controlled and reviewed**; surprise model updates that
  silently change retrieval behavior are a liability.

### 1.4 Industrial application *domain* characteristics

- The documents are **equipment manuals**: operating procedures, maintenance,
  troubleshooting/fault tables, installation specs, commissioning, component
  manuals. The query distribution is narrow and technical.
- Queries are overwhelmingly **about a specific piece of equipment**: a symptom, a
  procedure, a setpoint, a part. The user is usually an operator or maintenance
  engineer **standing at (or monitoring) a known asset**.
- The corpus is **asset-scoped**: most documents belong to an asset family /
  manufacturer / model. A few may be cross-cutting (safety standards, general
  procedures) — the exception, not the rule.

**Implication of 1.1–1.4:** Parler's advantage is not that it can build a better
retriever than the search industry — it cannot, in-process. It is that **the
platform already holds the structured asset context that turns most "open"
questions into scoped ones.** The design leans on that.

## 2. Open-question retrieval is out of scope in the JVM

"Open question" here = a **context-free symptom / triage query**: the user
describes a problem with no document, asset, manufacturer, or bound Thing — e.g.
*"something is vibrating and tripping, what should I check?"* — and expects the
system to find the right manual across an unrelated multi-document corpus.

### 2.1 The industry toolkit for open retrieval (and its cost *here*)

| Approach | What it buys | Cost in the ThingWorx/industrial context |
|----------|--------------|------------------------------------------|
| Dense retrieval (embeddings + vector search) | Synonym/paraphrase recall ("overspeed" → "trip") | Needs an embedding model + vector store — not hostable in the ThingWorx JVM at scale; only viable as an external service; re-embed on corpus change |
| Hybrid (BM25 + dense, RRF fusion) | Exact-token precision + semantic recall | Same embedding dependency, plus a full-text engine; two systems to operate |
| Cross-encoder rerank | Best precision; fixes off-domain pollution holistically | Extra model + latency per query; opaque (hard to explain/audit); rate-gate cost |
| Query understanding / rewriting / multi-query | Infers missing context, expands terms | Extra LLM round(s) → more rate-gate wait; can hallucinate intent |
| Knowledge graph / entity linking | Maps query entities → asset → documents | Heavy to build/maintain; §4 Tier 2 is the cheap ThingWorx-native version |
| Clarifying question | Honest disambiguation when uncertain | A UX round; correct but not "retrieval" |

### 2.2 Verdict

A robust answer to a *truly* context-free query needs a capable model plus
embedding/vector retrieval at real scale, which cannot run in-process. Parler
therefore does **not** implement full-open retrieval; it would require an external
retrieval/embedding service. The cheap, ThingWorx-native, *in-process* retrieval is
**lexical + metadata** — and a naive additive lexical scorer lets an off-domain,
signal-rich document (`fernwick-carbaq-ops-v2` troubleshooting chunks) score high on
*any* operational query and bury the right manual.

So the design question is **"how small can the truly-open residue be made by
exploiting structured context?"**

### 2.3 Two axes — document selection vs within-document recall

| Axis | Question | Typical failure | What addresses it |
|------|----------|-----------------|-------------------|
| **Cross-document selection** | Which manual(s) apply? | A symptom-only search ranks `fernwick-carbaq-ops-v2` over the RK&T manuals | Tier 2 scoping, Tier 1 identity scoring, BM25/IDF (§2.4) |
| **Within-document recall** | Which chunk inside a large manual? | The right RK&T operating manual is already selected, but distributed oil-mist-separator content takes many fetches to find | Chunk typing/signals (conversion side), in-doc ranking (BM25/IDF) |

**Tier 2 is the spine for cross-document selection; it does not, by itself, solve
within-document recall.** Thing↔document is many-to-many: one turbine Thing maps to
many manuals; one manual covers many components (turbine, bearings, oil system, oil
mist separator). A "bounded document set" can still be large, and a single
resolved manual can be hundreds of pages.

### 2.4 BM25 / IDF — between naive lexical and embeddings

A **hand-weighted additive scorer** (signal +50, troubleshooting +15, …) fails on
cross-domain pollution; that is not a failure of lexical retrieval in general.

A **BM25 / IDF** ranker — deterministic, in-process, no embedding model — targets
**common operational terms** ("pressure", "shutdown", "alarm", "vibration") that
appear in every troubleshooting chunk. IDF **down-weights** corpus-common terms and
**up-weights** rare, discriminating tokens, closing much of the weak-identity /
symptom-only gap **without** embeddings or a hosted vector store.

| | Additive scorer | BM25 / IDF | Embeddings |
|--|-----------------|------------|------------|
| Cross-doc pollution | Weak — shared ops vocabulary scores high everywhere | Stronger — IDF penalizes common terms | Strong — semantic paraphrase |
| Within-doc recall | Depends on signals/chunk typing | Stronger term discrimination inside one doc | Strong paraphrase |
| Infra | None | In-process Java; no external service | Model + index |
| Explainability | High (`documentScores`, signal breakdown) | High (term weights auditable) | Lower |
| Runs in ThingWorx JVM | Yes | Yes | No — needs an external model integration |

BM25/IDF is an **enhancement to Tier 1 and Tier 2 ranking** — it runs over the
scoped chunk set after the resolver or identity evidence narrows the documents.

## 3. How ThingWorx approximates open-question retrieval

The approximation is **not a smarter retriever; it uses the entity graph and
host-context to supply the missing context for free.**

- **host-context → scope:** the bound Mashup/page already names the asset the user
  is looking at. A "context-free" question typed on an asset page is not actually
  context-free.
- **Entity graph → documents:** a Thing resolves (via App-developer service or
  the default resolver) to the documents that apply to it. This converts "which
  manual?" from a semantic guess into a deterministic lookup.
- **Identity tokens in the query:** when the user *does* name a manufacturer/model
  ("RK&T", "CB 24 GT4"), deterministic identity scoring ranks the right document
  first (`documentScores` shows the flip).

The result: **resolve context cheaply, then do scoped deterministic retrieval.** It
does not answer the user who has *no* asset and *no* bound Thing — but §1.4 says
that user is rare in industrial maintenance.

When no Thing is bound and no identity is present, a **clarifying question**
("Which asset — the turbine or the coupling?") is cheaper, more deterministic, and
more honest than an unscoped retrieval guess. The runtime does not force that
question; it exposes the scope diagnostics (§4) so the answer can say when retrieval
was unscoped.

## 3.5 Storage axis — corpus scale and why Tier 2 helps

- Chunks live in a **FileRepository** (`AIDocRepository`) and are loaded into an
  **in-process index** at search time. There is no external search engine.
- A customer may have **thousands of manuals**. A global in-memory index of the
  whole corpus strains the ThingWorx JVM heap and startup/rebuild time.
- **Tier 2 is also a storage win:** when resolver scope narrows to a bounded document
  set *first*, the runtime **lazily loads only those documents' chunks** instead of
  indexing the entire corpus on every turn. Ranking then runs over a small, bounded
  set — cheaper in memory and faster to warm.
- **Within a scoped document set**, chunk JSON on disk is the source of truth; the
  in-process index is a cache keyed by `(documentId, chunk set)`.

## 4. Retrieval tiers

The tiers are cumulative; the runtime falls back down the ladder.

### Tier 1 — asset identity is available

The turn carries asset-type/identity tokens (typed by the user, or from
host-context); deterministic identity-aware document scoring + per-doc-cap
diversification ranks within the corpus, with BM25/IDF term weighting (§2.4).

- **Gain:** cheap, deterministic, explainable (`documentScores`/`selectionMode`),
  ThingWorx-native, no new infra. With identity present, ranking flips to the right
  document.
- **Limits:**
  - **Weak-identity symptom recall is fragile.** A symptom-only search with *no*
    identity token can still rank an off-domain doc first; BM25/IDF reduces but does
    not remove this.
  - **"Asset type" can be insufficient.** With multiple turbine vendors, "steam
    turbine" maps to several document families; asset *identity* (type +
    manufacturer + model) is needed, and even that can tie.
  - **Cross-cutting documents** (safety standards) have no clean asset binding.
  - **Within-document recall** (§2.3) is out of Tier 1's scope.

### Tier 2 — the App developer maps a context key → a bounded document set

A resolver (App-developer-authored service, or the built-in default) takes a
**context key** and returns the documents that apply to it. The contract is generic —
**input: a single `String` key; output: a `DocumentSet` (a set of document IDs).**
The key is *usually* a ThingName, but the App developer may map an **asset type** or
any other domain keyword to a document set. Parler owns the generic
`String → DocumentSet` mechanism; the App developer owns what the key means and what
it resolves to. Retrieval is then scoped to that set; chunk ranking runs inside it.
Resolution is **server-injected from host-context at turn start** when a bound Thing
is available (so the *first* search is already scoped), **and** exposed as an
**explicit resolver tool** for when the user names a Thing/asset or diagnostics need
to explain absent scope — both paths use the same mechanism.

- **DocumentSet shape:** the resolver returns **document IDs**, plus optional
  cross-cutting classes (`alwaysInclude` / `appliesToMany`). Per-document
  keywords/type (troubleshooting vs spec, `assetModels`, etc.) live in the
  **manifest metadata**, keyed by document ID — not in the resolver output — so
  there is one source of truth.
- **When the resolver yields nothing** (`default-empty`): the search proceeds without
  a document filter and falls to the lower ladder rungs below.
- **Zero-config default resolver:** deployments may ship with no custom resolver
  service. The built-in default resolver is a **conservative high-confidence
  matcher**: it joins the bound Thing's asset identity against manifest
  `documentProfile.assetModels[]`, **scopes only on a high-confidence exact match**,
  and **returns empty on no/ambiguous match** (falling through the ladder rather than
  mis-scoping). Diagnostics: **`resolverSource: custom | default-match |
  default-empty | none`**.
- **Cross-cutting documents** (safety, general procedures) are returned as explicit
  resolver output (`alwaysInclude` / `appliesToMany`), not found through unbounded
  global search.
- **Limits:** mapping quality is partly the customer's job when they override the
  default; Tier 2 does not fix within-document recall; it needs a bound Thing (or a
  Thing named in text).

**Fallback ladder** (each rung visible in diagnostics — `documentScopeSource`,
`resolverSource`, `selectionMode`, `documentScores`):

1. **Scoped resolver result** — server-injected from host-context or explicit tool
   (`selectionMode: documentIds-filter`).
2. **Explicit `documentIds`** in the model's search args (same filter).
3. **Tier 1 identity scoring** (+ BM25/IDF) — when hard identity evidence (alias,
   title, docId, filename) puts one document clearly ahead, only that document is
   searched (`selectionMode: hard-single`).
4. **Unscoped diversified ranking** — otherwise all documents are ranked with a
   per-document cap (`selectionMode: diversified`), visibly distinct from a scoped
   result.

### Not implemented: semantic recall

Embedding-based recall (over the whole corpus, or only inside a Tier 2 scoped set)
is not implemented. Scoping would shrink the vector index, but the query still needs
an embedding model, which cannot live in the ThingWorx JVM.

## 6. Chosen design

- **Tier 2 spine for cross-document selection:** a generic resolver — **`String key
  → DocumentSet`** (key usually a ThingName, but may be an asset type or other
  keyword) → bounded document set; mapping owned by the App developer;
  **server-injected first** from host-context; **explicit resolver tool** for
  named-Thing / diagnostic cases; **zero-config default resolver** = a
  **conservative high-confidence matcher** (exact join on manifest
  `documentProfile.assetModels[]`; empty on no/ambiguous match) with
  `resolverSource` diagnostics (`custom | default-match | default-empty | none`).
- **BM25/IDF ranking** (§2.4) inside Tier 1/2 scoped sets.
- **Tier 1 as fallback rung** on the ladder when resolver and explicit identity are
  absent.
- **Within-document recall is a separate axis** (§2.3): chunk typing + in-doc BM25/IDF.
- **No full-open retrieval**; an unscoped search is visible as such in diagnostics
  (ladder rung 4) so the model can ask which asset instead of guessing.
- **Cross-cutting documents** via resolver output (`alwaysInclude` /
  `appliesToMany`), not unbounded global search.
- **Lazy-load scoped chunks** (§3.5): scales with resolved set size, not whole corpus.

## 7. Acceptance bar

**Gold query dimensions** (each must pass or explicitly fail with diagnostics
explaining why):

| Dimension | Example | Pass criterion |
|-----------|---------|----------------|
| Host-bound Thing scope | RK&T prompt on an asset Mashup | First search scoped; right doc ranked; **≤ 4 rounds** |
| User-named Thing scope | User names a Thing not in host-context | Resolver tool scopes before symptom search; **≤ 4 rounds** |
| Missing custom mapping | Deployment with no App-developer resolver | Default resolver scopes via a **high-confidence** `assetModels` match (`resolverSource: default-match`); empty + ladder fall-through when no confident match (`default-empty`) |
| Cross-cutting inclusion | Safety standard applies to all turbines | Resolver returns `alwaysInclude` doc; not found via global pollution |
| Within-document recall | Oil-mist component query | Right chunk surfaced; **≤ 6 rounds**, no multi-page fetch wander |
| Out-of-scope context-free | Pure symptom, no Thing, no identity | Clarify-or-honest-degrade; no silent wrong-manual answer |
| Weak-identity symptom | Symptom terms only, no manufacturer token | BM25/IDF or a high-confidence `default-match` ranks in-domain doc in top-k |

**Latency budget:** **round count** is the stable acceptance signal (rate-gate wall
time is tunable per deployment):

- **Cross-document prompts:** finalize in **≤ 4 rounds**.
- **Within-document prompts (oil-mist class):** finalize in **≤ 6 rounds**, no
  multi-page fetch wander.

Storage and retrieval stay paired: the per-document manifest metadata
(`assetModels`, manufacturer, title, `documentProfile`, derived terms) is consumed by
the default resolver, Tier 1 identity scoring, and BM25/IDF alike, which is what
keeps the retrieval tiers cheap and consistent.
