# Knowledge Retrieval Pipeline — implementation

**Status:** Implemented. This document describes how document retrieval is built on the design in
[`docs/core/knowledge-search-tradeoff.md`](../core/knowledge-search-tradeoff.md) (the "what/why").
The tool contract is `docs/agent/document-chunk-tools.md` (`search_document_chunks`,
`get_document_chunk`, `resolve_document_set`).

The pipeline has four parts: (1) the per-document metadata schema, (2) the resolver, (3) BM25/IDF
ranking over the scoped set, and (4) the offline acceptance gold set. The metadata schema is
consumed by the default resolver, Tier 1 identity scoring and BM25/IDF, so all three read the same
per-document fields.

## 2. Document-metadata schema (the existing manifest)

`docs/agent/document-chunk-tools.md` §5.3.1 defines a per-document `documentProfile` object,
stored once at the manifest root (`document-knowledge/{docId}/manifest.json`), and
`DocumentKnowledgeSearchScorer` consumes it. This object is the single source of truth; there are
**no parallel scalar fields** and **no sidecar file**. The manifest is the on-disk source of
truth and the in-process index is a cache keyed by `(docId, chunk set)`.

| Field | Type | Location | Consumed by |
|-------|------|----------|-------------|
| `docId` | string | manifest root | doc lookup, identity tokens |
| `title` | string | manifest root | identity (fallback when profile absent) |
| `documentType` | string | manifest root | doc-kind (normalized), fallback |
| `assetModels` | string[] | manifest root | identity (fallback to profile); resolver join fallback |
| `documentRole` | string | manifest root | `bundle` → chunk penalty |
| `sourceFileName` | string | manifest root | filename identity token |
| `documentProfile.aliases` | string[] | profile | exact alias-phrase match (+100) |
| `documentProfile.manufacturers` | string[] | profile | manufacturer identity (+20) |
| `documentProfile.assetModels` | string[] | profile | asset-model identity (+25) + family detection; resolver join key |
| `documentProfile.documentKinds` | string[] | profile | normalized kind match (+40), normalized by `DocumentKnowledgeDocumentProfile.normalizeDocumentKind()` |
| `documentProfile.domainTerms` | string[] | profile | rare-term match (+10) |
| `documentProfile.profileSource` | object | profile | derivation provenance (metadata only) |

**Consumers:**

- **Default resolver / Tier 2 join:** the *document* side of the join is
  `documentProfile.assetModels[]` (+ manifest `assetModels` fallback); the *Thing* side is the
  asset identity carried by host-context (§3.2).
- **Tier 1 identity scoring:** aliases / manufacturers / assetModels / documentKinds / domainTerms.
- **BM25/IDF:** chunk text; it computes its own IDF from the scoped chunk corpus.

Cross-cutting `alwaysInclude` / `appliesToMany` are **resolver output** (§3.5), not document fields.

## 3. Resolver: `String key → DocumentSet`

**Contract (trade-off §4 Tier 2):** input a single `String` key, output a `DocumentSet`. The key is
*usually* a ThingName but the contract does not require it — the App developer may map an asset
type or any domain keyword, and owns the strictness. Parler owns the generic mechanism only.

### 3.1 Output shape

Two layers, because the resolver is both an in-process call and an LLM tool:

- **App-developer service boundary → `InfoTable` with a DataShape.** Not `JSON` BaseType, not
  stringified. The runtime's result serializer (`InvokeServiceExecutor.formatToolResult`) projects
  an `INFOTABLE` result into a **compact JSON array of row objects** + minimal `columns` metadata
  and auto-pages large tables, while a `JSON`-BaseType output is emitted **stringified**. InfoTable
  is the only BaseType that serializes cleanly *and* is a native object in-process (zero
  serialization on server-inject).

  DataShape `ResolvedDocument` (document ids only; per-doc keywords/type live in the §2 metadata):
  ```
  ResolvedDocument {
    documentId    STRING   // required
    alwaysInclude BOOLEAN  // optional — cross-cutting (safety/general) docs
    appliesToMany BOOLEAN  // optional
  }
  ```

- **Model-facing tool result → compact JSON array of objects.** `resolve_document_set` **strips**
  the service's InfoTable result to this array itself — it does not forward the generic
  `resultKind:"INFOTABLE"` `{columns, rows}` envelope that `invoke_service` emits — like the other
  document-knowledge tools, which return hand-built JSON:
  `[{ "documentId": "...", "alwaysInclude": true }, ...]`.

- **Ingestion robustness:** InfoTable is canonical; a native JSON array is tolerated, and
  stringified JSON is tolerated only defensively.

### 3.2 Resolution paths (one mechanism, two entry points)

- **Server-injected from host-context at turn start:** when the accepted host-context carries
  `context.thingName`, the runtime resolves the document set at turn start and uses it as the
  default `search_document_chunks.documentIds` for that turn (Chat / ChatAsync /
  `ParlerStreamToRemoteThing`), so the first search is already scoped. It is a default, not a hard
  filter — applied only when the model omits `documentIds`; such searches report
  `documentScopeSource: host-context-resolver` and `documentScopeResolverSource`. It fails open
  (no host-context / blank thingName / tools off / unavailable index / empty resolve ⇒ unscoped).
- **Explicit resolver tool** `resolve_document_set` when the user names a Thing/asset or
  diagnostics must explain absent scope. When `documents[]` is non-empty the model passes the ids
  as `documentIds`; when it is empty (`default-empty`) the model runs a normal search.

Both call the same `String → DocumentSet` mechanism.

### 3.3 Default resolver — overridable service; built-in default = conservative high-confidence matcher

The resolver is an **overridable ThingWorx service** on the AgentThing template —
`ResolveDocumentSet(key STRING): INFOTABLE<ResolvedDocument>` (`isAllowOverride`) — which the App
developer **overrides** to supply the real key→documents mapping. It is invoked as the current
user through `processAPIServiceRequest("ResolveDocumentSet", …)` (`PlatformAccess.invokeAsUser`),
for both the tool and host-context scoping. When the override returns at least one valid
`documentId` the result is `custom`; an empty or unusable override means "no custom mapping" and
the built-in matcher runs.

The built-in default is a **conservative high-confidence matcher**:

- Join the asset identity (the key) against manifest `documentProfile.assetModels[]` (plus root
  `assetModels[]`) by normalized exact equality.
- **Scope only on a high-confidence exact match.** **Return empty on no/ambiguous match**, falling
  through the §3.4 ladder rather than mis-scoping.
- It is **generic mechanism** — an exact join on App-developer-curated `assetModels`, inventing no
  domain knowledge.

Diagnostics: **`resolverSource: custom | default-match | default-empty | none`**
(`custom` = override produced the set; `default-match` = built-in join scoped; `default-empty` =
built-in ran, no confident match; `none` = resolver not invoked / tools off).

| Tools exposed | Resolver | Behavior |
|---------------|----------|----------|
| off (default) | — | no document capability; no LLM-context cost |
| on | built-in default | high-confidence `assetModels` match scopes (`default-match`); else empty (`default-empty`) → §3.4 ladder |
| on | overridden | App-developer mapping scopes (`custom`) |

### 3.4 Fallback ladder (each rung visible in diagnostics)

1. Scoped resolver result (server-injected or explicit tool) → `selectionMode: documentIds-filter`.
2. Explicit `documentIds` in the model's search args → same filter.
3. Tier 1 identity scoring + BM25/IDF: when hard identity evidence (alias / title / docId /
   filename) puts one document clearly ahead, only that document is searched
   (`selectionMode: hard-single`).
4. Otherwise all documents are ranked with a per-document cap (`selectionMode: diversified`).

The runtime does not force a clarifying question; an unscoped result is distinguishable in the
diagnostics above.

### 3.5 Cross-cutting documents

Safety standards / general procedures are returned as explicit resolver output
(`alwaysInclude` / `appliesToMany` columns), not re-entered via unbounded global search.

A custom `ResolveDocumentSet` override may flag rows `alwaysInclude`. The resolver parses the flag
and **unions** those documents into the scoped `documentIds` — even when a cross-cutting doc sits
outside the key-matched set — so it is always retrievable. The union is **resolver-sourced only**:
the built-in default matcher never synthesizes `alwaysInclude`, so cross-cutting docs cannot leak in
via the matcher or via global search. The model-facing `documents[]` rows expose `alwaysInclude`
and `appliesToMany` so the model hands the union off correctly. `appliesToMany` is parsed and
carried but has no separate runtime effect. Contract: `docs/agent/document-chunk-tools.md` §6.5.

Diagnostics: `resolverSource` on the tool body; `documentScopeSource` /
`documentScopeResolverSource` on host-context-scoped searches; `bm25Boost` per match.

### 3.6 Exposure gate

Tool exposure is gated by the boolean **`documentKnowledgeBuiltinsEnabled`** on the AgentThing
`AgentSettings` ConfigurationTable (**default false**), applied in `BuiltInTools.registerAll`.
`resolve_document_set`, `search_document_chunks` and `get_document_chunk` are exposed or hidden as
**one capability unit**, so deployments that do not use document knowledge pay **no LLM-context
cost** for these schemas. The **server-side resolver injection** (§3.2) is gated on the same flag:
when tools are off, no scope is injected.

## 4. BM25/IDF over the scoped set

`DocumentKnowledgeBm25` is a light, deterministic, in-process BM25/IDF ranker (no Lucene, no
embedding model). It targets common-operational-term pollution: IDF down-weights
"pressure"/"shutdown"/"alarm"/"vibration" and up-weights rare discriminating tokens, which the
hand-weighted additive scorer cannot do.

- The BM25 corpus is **all chunks of the selected documents** (not only the additive-positive
  ones), so document frequencies are correct and a chunk that only BM25 finds is still eligible.
- It **augments** the additive `DocumentKnowledgeSearchScorer`; the additive scorer is unchanged,
  and a conservative boost cap keeps BM25 from overturning hard identity/signal scores. Each match
  reports its `bm25Boost`.

## 5. Acceptance gold set

The trade-off §7 table is materialized as **JUnit tests in `parler-agent`** against **committed
offline fixtures** built from the `dev_data` corpus manifests (`DocumentKnowledgeGoldSetTest`, same
pattern as `DocumentRetrievalStabilityScorerTest`). Round count is the stable signal (wall time is
rate-gate-dominated):

- Cross-document prompts: finalize in **≤ 4 rounds**.
- Within-document prompts (oil-mist class): **≤ 6 rounds**, no multi-page fetch wander.

Each §7 dimension (host-bound scope, user-named scope, missing custom mapping, cross-cutting
inclusion, within-document recall, out-of-scope context-free, weak-identity symptom) must pass or
explicitly fail with diagnostics.

## 6. Out of scope

- Full-open retrieval and embedding-based recall (trade-off §2.2).
- Corpus content / customer mappings (App-developer responsibility).
