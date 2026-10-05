# Document chunk tools

This document describes the Java `parler-agent` built-in tools for
document-grounded recommendations over already-converted PDF/manual packages.
It is the Parler-side runtime for `docs/core/pdf-search.md` and consumes packages
shaped by that document's package, Markdown, and chunk contracts (§3–§5).

The typical flow is:

```text
health-status evidence
  -> normalize issue / alarm / component
  -> search_document_chunks
  -> get_document_chunk
  -> final answer with FileRepository PDF page links
```

Parler does not extract PDFs. The extraction pipeline is outside Parler.

## 1. Placement

This document belongs under `docs/agent/` because it covers the ThingWorx Java
agent extension:

- Java built-in tool schemas.
- Java execution and FileRepository reading.
- JVM cache/index behavior.
- LLM routing and final-answer rules.
- Agent tests and fault-tolerance behavior.

Related documents:

- `docs/core/pdf-search.md` - package and answer contract.
- `dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/` - concrete
  fixture package.

## 2. Scope

The agent provides three built-in tools:

```text
search_document_chunks
get_document_chunk
resolve_document_set
```

`resolve_document_set` scopes a search to the documents that apply to an asset
(§6.5). These tools are exposed only when the AgentThing option
`documentKnowledgeBuiltinsEnabled` is `true`. The default is `false`.

These tools read document packages from a ThingWorx FileRepository-compatible
layout:

```text
document-knowledge/
  <docId>/
    manifest.json
    source/original.pdf
    markdown/manual.md
    chunks/chunks.jsonl
    pages/page-0001.png
```

The implementation is deterministic and self-contained:

- no embeddings;
- no external search system;
- no UI artifact lane;
- no new AlwaysOn frame type;
- no PDF parsing;
- no OCR;
- no extraction code.

The switch is off by default. When it is off, the tool names stay free (§4.1), so
the same tool contract can instead be served by extended tools backed by ThingWorx
JavaScript services that call an external document search service.

### 2.1 Supported retrieval contract — asset-type context is a precondition

A document-symptom turn is supported **only when an asset-type context is
available**. Users are not expected to name the specific manual, but every
such question must carry an asset-type context — one of:

- explicitly stated in the user message ("the RK&T steam turbine …"); or
- supplied via **host-context** (the bound Mashup / page asset scope); or
- readily derivable from the bound Thing's **identity** (thingName, ThingTemplate,
  asset model).

The context-free generic symptom question ("how do I respond to symptom X?" with
no asset type at all) is **out of supported scope**, not a defect.

Consequences:

1. **Asset-type context should reach document ranking from the first search of the
   turn**, not on a retry. Without it, a symptom-only search can rank the wrong
   document family first. Two paths carry it: host-context scoping resolves the
   document set at turn start and scopes the first search (§6.5), and the model
   passes asset-type terms in `query` / `assetContext`, which feed document scoring
   (§11).
2. **"Weak identity" means weak *document* identity *with* asset-type context** — the
   user did not name the manual, but the turn still carries asset type. It does
   **not** mean zero context. Evaluation cases exercise the supported contract
   (asset-type context present); a zero-context symptom prompt is out of scope, not
   a passing or failing case.
3. Because the context-free case is out of scope, deterministic identity-aware
   ranking is sufficient and there is no embedding-based retrieval (§12).

## 3. UI and wire contract impact

The tools need no dedicated UI element.

The final answer uses ordinary Markdown links. `<parler-ui>` already renders
Markdown links, and the source link uses the same ThingWorx FileRepository path
style as existing table CSV download links:

```text
/Thingworx/FileRepositories/{repository}{path}#page={page}
```

Example:

```text
/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25
```

No `CONTRACTS/UI_CLIENT_PROTOCOL.md` or `CONTRACTS/API_CONTRACT.md` shape is
involved because:

- document citations are assistant Markdown, not a new wire artifact;
- no `type: "document"` frame is emitted;
- no reducer state is added;
- no chart/table contract is changed.

`<parler-ui>` rewrites rendered FileRepository PDF links to an inline-render form
(§13.3).

## 4. Configuration

The settings live on `AgentSettings`.

| Field | Type | Default | Bounds | Role |
| --- | --- | --- | --- | --- |
| `documentKnowledgeBuiltinsEnabled` | boolean | `false` | — | When `true`, advertise the internal Java `search_document_chunks`, `get_document_chunk`, and `resolve_document_set` tools. When `false`, do not expose them to the model. |
| `documentKnowledgeRepository` | `THINGNAME` | empty | — | FileRepository Thing containing document packages. |
| `documentKnowledgeRootPath` | string | `/document-knowledge` | — | Root folder below the repository. |
| `documentKnowledgeIndexTtlSeconds` | integer | `300` | 30–86400 | JVM cache TTL. |
| `documentKnowledgeMaxDocuments` | integer | `100` | 1–10000 | Scan cap for package manifests. |
| `documentKnowledgeMaxChunks` | integer | `10000` | 1–1000000 | Cap for total indexed chunks. |
| `documentKnowledgeSearchDefaultLimit` | integer | `5` | 1–100 | Default search result count. |
| `documentKnowledgeSearchMaxLimit` | integer | `10` | default limit–100 | Maximum search result count. |
| `documentKnowledgeSearchSnippetMaxChars` | integer | `400` | 50–10000 | Maximum chars per search-match `snippet`. |
| `documentKnowledgeChunkMaxChars` | integer | `6000` | 500–500000 | Maximum markdown chars returned by `get_document_chunk`. |

Out-of-bounds numeric values are clamped to the nearest bound and reported as a
`CONFIG_VALUE_CLAMPED` warning. A `documentKnowledgeSearchMaxLimit` below the
effective default limit falls back to that default limit.

If the repository field is empty, the tools return a structured degraded
result rather than throwing.

### 4.1 Built-in registration and name reservation

`documentKnowledgeBuiltinsEnabled` controls **whether the Java extension registers
these tools at all**, not merely whether they appear on the merged LLM tool list.

This is **not** the legacy-discovery executor-only pattern
(`advertiseLegacyServiceDiscoveryTools`). Executor-only registration still reserves
names through `AgentThing.builtinToolDefinitionNames()` via
`ToolRegistry.getExecutorOnlyAliases()`, which would block
`/tools/extended_tools.json` wrappers from using the same tool names.

When `documentKnowledgeBuiltinsEnabled` is `false`:

- do **not** call `ToolRegistry.register` for any of the document tools;
- do **not** call `ToolRegistry.registerExecutorOnly` for them;
- do **not** add any of those names to the built-in name-reservation set;
- leave `search_document_chunks`, `get_document_chunk`, and `resolve_document_set`
  available for extended tools backed by ThingWorx JavaScript services or an
  external document service.

When `documentKnowledgeBuiltinsEnabled` is `true`:

- register the document tools with `ToolRegistry.register` like other model-facing
  built-ins;
- include their names in the merged LLM tool list;
- reserve their names through `builtinToolDefinitionNames()` for extended-tool
  collision checks.

`BuiltInTools.registerAll` branches on the setting and **skips the document tools
entirely** when disabled. Tests cover both states.

## 5. Tool: `search_document_chunks`

### 5.1 Purpose

Find document chunks relevant to a user question or normalized health issue.

This tool returns matching metadata and snippets. It never returns full chunk
markdown. The model should call `get_document_chunk` for the
best 1-3 matches it intends to cite.

### 5.2 Input schema

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "Natural-language query built from the user question or normalized health issue."
    },
    "signals": {
      "type": "array",
      "description": "Optional alarms, properties, symptoms, or components.",
      "items": {
        "type": "object",
        "properties": {
          "kind": {"type": "string"},
          "name": {"type": "string"},
          "value": {"type": "string"}
        }
      }
    },
    "assetContext": {
      "type": "object",
      "description": "Optional asset context such as asset model, component, or document type hints."
    },
    "documentTypes": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Optional document type filters such as operations_manual or troubleshooting_guide."
    },
    "documentIds": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Optional explicit document id filter. When resolve_document_set returns a non-empty documents[], pass those documents[].documentId values here to scope this search to the resolved set (selectionMode documentIds-filter)."
    },
    "limit": {
      "type": "integer",
      "description": "Maximum matches to return. Clamped to configured bounds."
    }
  },
  "required": []
}
```

`query` is optional when useful `signals` or `assetContext` fields are present.
If all are empty, return an empty success with a warning. `documentIds` is the
schema-backed handoff for the explicit resolver-tool path: when `resolve_document_set`
returns a non-empty `documents[]`, pass those ids here to scope the search to that set
(see §3.4 rung 2 of `docs/operations/knowledge-retrieval-pipeline.md`).

### 5.3 Success result

```json
{
  "status": "success",
  "degraded": false,
  "matches": [
    {
      "docId": "fernwick-carbaq-ops-v2",
      "chunkId": "troubleshooting-chiller-high-pressure-bpr",
      "heading": "Chiller High Pressure Shutdown - back pressure regulator",
      "sectionPath": ["9. Troubleshooting Tips"],
      "contentType": "troubleshooting",
      "pageStart": 25,
      "pageEnd": 25,
      "score": 91,
      "snippet": "Cause: the back pressure regulator (BPR) was moved. Remedy: adjust the BPR...",
      "sourceLinks": [
        {
          "label": "Fernwick Labs manual, 9. Troubleshooting Tips, page 25",
          "repository": "AIDocRepository",
          "path": "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
          "page": 25,
          "href": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25"
        }
      ]
    }
  ],
  "warnings": [],
  "searchedDocuments": 1,
  "searchedChunks": 45,
  "skippedDocuments": 0,
  "skippedChunks": 0,
  "documentScores": [
    {
      "docId": "fernwick-carbaq-ops-v2",
      "score": 45,
      "matchedEvidence": ["manufacturer:Fernwick Labs", "asset:CarbaQ CO2 Capture Solution"]
    }
  ],
  "selectedDocIds": ["fernwick-carbaq-ops-v2"],
  "selectionMode": "hard-single"
}
```

The diagnostic fields (`documentScores`, `selectedDocIds`, `selectionMode`) are
emitted by the Java scorer. They are bounded (at most 12 documents and 8 evidence
strings per document) and safe for clients to ignore. `selectionMode` is one of:

| `selectionMode` | Meaning |
| --- | --- |
| `documentIds-filter` | The search was scoped by `documentIds` (from the call or from host-context scoping, §6.5). |
| `hard-single` | One document had hard identity evidence and a clear lead (§11); only its chunks are ranked. |
| `diversified` | Several documents remain plausible; results are capped per document (§11). |
| `empty` | The index holds no chunks. |

When the search was scoped by host context (§6.5), the response also carries
`documentScopeSource: "host-context-resolver"` and `documentScopeResolverSource`.

When `documentIds` is supplied, unknown ids are omitted from `selectedDocIds` and
may yield empty `matches` with a success envelope (not a turn failure).

### 5.3.1 Manifest `documentProfile` (optional)

Packages MAY include a `documentProfile` object on `manifest.json` for deterministic
document-level scoring (`docs/operations/document-retrieval-stability.md`):

```json
{
  "documentProfile": {
    "aliases": ["RK&T operating manual"],
    "manufacturers": ["RK&T"],
    "assetModels": ["CB 24 GT4"],
    "documentKinds": ["operating_manual"],
    "domainTerms": ["trouble", "elimination", "bearing"],
    "profileSource": {
      "aliases": "curated-identity",
      "domainTerms": "derived-from-toc-headings-and-section-headings"
    }
  }
}
```

When `documentProfile` is absent, the scorer falls back to `docId`, `title`,
`documentType`, `assetModels`, and `sourceFileName`. Conversion tooling should
derive `domainTerms` from headings — not from evaluation prompts.

Search snippets are bounded to `documentKnowledgeSearchSnippetMaxChars`. The
snippet is built from the chunk `summary` (or `markdown` when there is no
summary). A longer snippet is cut to a prefix ending in `…`, without a per-match
warning.

### 5.4 Output budgets

To avoid quiet token amplification:

- `snippet` length is capped by `documentKnowledgeSearchSnippetMaxChars`.
- `get_document_chunk` `markdown` is capped by `documentKnowledgeChunkMaxChars`
  (see §6.3).
- `warnings` are compact and aggregated per §8, never hundreds of per-line
  warnings.
- `matches` length is clamped to the effective search limit.

### 5.5 Degraded success

Most failures return success with no matches and warnings:

```json
{
  "status": "success",
  "degraded": true,
  "matches": [],
  "warnings": [
    {
      "code": "DOCUMENT_REPOSITORY_NOT_CONFIGURED",
      "message": "Document knowledge repository is not configured."
    }
  ],
  "searchedDocuments": 0,
  "searchedChunks": 0,
  "skippedDocuments": 0,
  "skippedChunks": 0
}
```

The user-facing agent answer can continue from live health evidence even when
document search is unavailable.

## 6. Tool: `get_document_chunk`

### 6.1 Purpose

Fetch the full markdown and source provenance for one chunk selected by search.

### 6.2 Input schema

```json
{
  "type": "object",
  "properties": {
    "docId": {"type": "string"},
    "chunkId": {"type": "string"}
  },
  "required": ["docId", "chunkId"]
}
```

Lookup is by `docId` + `chunkId` only; there is no lookup by page. Page-level
chunks also carry stable `chunkId` values.

### 6.3 Success result

```json
{
  "status": "success",
  "degraded": false,
  "docId": "fernwick-carbaq-ops-v2",
  "chunkId": "troubleshooting-chiller-high-pressure-bpr",
  "heading": "Chiller High Pressure Shutdown - back pressure regulator",
  "sectionPath": ["9. Troubleshooting Tips"],
  "contentType": "troubleshooting",
  "pageStart": 25,
  "pageEnd": 25,
  "markdown": "## Chiller High Pressure Shutdown - back pressure regulator\n\nCause: the back pressure regulator (BPR) was moved. Remedy: adjust BPR...",
  "sourceLinks": [
    {
      "label": "Fernwick Labs manual, 9. Troubleshooting Tips, page 25",
      "repository": "AIDocRepository",
      "path": "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
      "page": 25,
      "href": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25"
    }
  ],
  "warnings": []
}
```

If `markdown` exceeds `documentKnowledgeChunkMaxChars`, the tool returns a bounded
prefix and includes a warning:

```json
{
  "code": "CHUNK_MARKDOWN_TRUNCATED",
  "message": "Chunk markdown was truncated to the configured maximum."
}
```

### 6.4 Not found result

`get_document_chunk` returns a structured error because the model asked for a
specific object. It never throws an unhandled exception through the agent loop.

```json
{
  "status": "error",
  "code": "CHUNK_NOT_FOUND",
  "message": "Document chunk was not found.",
  "docId": "fernwick-carbaq-ops-v2",
  "chunkId": "missing"
}
```

## 6.5 Tool: `resolve_document_set`, the overridable resolver, and host-context scoping

Background: `docs/operations/knowledge-retrieval-pipeline.md` §3. Gated by
`documentKnowledgeBuiltinsEnabled` with the other document tools.

**`resolve_document_set(key STRING)`** returns the bounded document set that applies to
an asset/Thing `key`:
`{ status, resolverSource, documents: [ { documentId, alwaysInclude, appliesToMany } ] }`.
The model passes the resolved `documents[].documentId` into `search_document_chunks`'s
`documentIds` (§5.2) to scope retrieval. Empty `documents` (`resolverSource:
default-empty`) ⇒ no confident scope; proceed with a normal search.

**`resolverSource` diagnostic:** `custom | default-match | default-empty | none`.

**Cross-cutting documents (`alwaysInclude` / `appliesToMany`).** Each row carries two
boolean flags (`docs/operations/knowledge-retrieval-pipeline.md` §3.5). `alwaysInclude` marks a cross-cutting safety/general
document that applies regardless of the asset key; the runtime **unions** every
`alwaysInclude` document into the scoped `documentIds` even when it sits outside the
key-matched set, so it is always retrievable — sourced from the resolver, **never** from
unbounded global search. `appliesToMany` is parsed and surfaced on the wire but is
**inert** in the current runtime (a classifier with no separate effect yet). Cross-cutting
docs are **resolver-sourced only**: the built-in default matcher never sets either flag,
so they enter scope solely through a custom `ResolveDocumentSet` override. Built-in
`default-match`/`default-empty` rows always report both flags `false`.

**Overridable `ResolveDocumentSet(key STRING): INFOTABLE<ResolvedDocument>` service**
(on the AgentThing, `isAllowOverride = true`), invoked with `processAPIServiceRequest` as the
current user, so that user needs ServiceInvoke on it. App developers override it to map a key
(typically a bound ThingName) to documents. The Java default returns an **empty
`ResolvedDocument` table** — the sentinel for "no custom mapping." The runtime invokes
it first; a non-empty result (≥ 1 valid `documentId`) ⇒ `resolverSource: custom`, else
the agent falls through to its built-in high-confidence matcher (`default-match` when
the key exactly matches a manifest `documentProfile.assetModels[]`/`assetModels[]`
entry, else `default-empty`). An override that returns no usable rows is treated as
"no custom mapping" and reported `default-*`, not `custom`.

**Host-context server-side scoping** (`docs/operations/knowledge-retrieval-pipeline.md` §3.2). When a turn's host context was accepted via a
**registered template** (`outcome: ACCEPTED` only — **not**
`UNREGISTERED_GENERIC_FALLBACK` / `genericFallback: true`) and carries
`context.thingName`, the runtime resolves the document set at turn start (same
override-first → built-in matcher) and, on a non-empty result, **auto-defaults**
`search_document_chunks.documentIds` for that turn — so the *first* search is already
scoped without the model calling `resolve_document_set`. This is a **default, not a hard
filter**: it applies only when the model omits `documentIds` from the call (the model
supplying `documentIds` at all, including `[]`, suppresses it), and it never turns an
intent-less search into a scoped one. Scoped searches report
`documentScopeSource: host-context-resolver` plus the carried
`documentScopeResolverSource`. Everything fails open: absent/rejected host-context,
blank `thingName`, tools disabled, an unavailable index, or an empty resolve all leave
the turn unscoped. Because the built-in matcher compares the key by exact string
equality, host-context `default-match` only fires when `thingName` happens to equal a
curated `assetModels` entry; the **custom override** is the intended ThingName→documents
path, with fail-open `default-empty` otherwise.

## 7. FileRepository package discovery

Discovery is deliberately simple:

1. Read `documentKnowledgeRepository`.
2. Read `documentKnowledgeRootPath`, default `/document-knowledge`.
3. List immediate child folders under the root.
4. For each child folder, try `<root>/<docId>/manifest.json`.
5. If the manifest is usable, read the configured `chunksPath`.
6. Parse `chunks.jsonl` line by line.

The implementation tolerates partial packages. One bad package does not
block all document search.

Every read goes through the repository's own services (`BrowseDirectory`,
`LoadText`) with `processAPIServiceRequest`, as the user making the tool call,
so ThingWorx authorizes each call. The repository Thing must be visible to that
user. A refusal is not a partial package: it ends the load and the tool returns
no document content (§10).

The fixture maps as follows:

| Concept | Value |
| --- | --- |
| Repository Thing | `AIDocRepository` |
| Repository root | `dev_data/future_repo/` in git, `/` in the FileRepository |
| Package root | `/document-knowledge/fernwick-carbaq-ops-v2/` |
| Source PDF path | `/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf` |
| Page 25 link | `/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25` |

## 8. Fault tolerance

The tools are highly tolerant. File, repository, JSON, and shape errors do not
stop the agent from answering.

| Failure | Behavior |
| --- | --- |
| Repository not configured | `search`: empty degraded success. `get`: structured error `DOCUMENT_REPOSITORY_NOT_CONFIGURED`. |
| Repository Thing cannot be resolved | Degraded result with `DOCUMENT_REPOSITORY_UNAVAILABLE`. |
| Root path missing | Empty degraded success with `DOCUMENT_ROOT_NOT_FOUND`. |
| Manifest missing | Skip that package and add `MANIFEST_MISSING`. |
| Manifest JSON invalid | Skip that package and add `MANIFEST_INVALID_JSON`. |
| Required manifest fields missing | Skip that package and add `MANIFEST_INVALID_SHAPE`. |
| `chunks.jsonl` missing | Skip that package and add `CHUNKS_FILE_MISSING`. |
| `chunks.jsonl` cannot be read | Skip that package and add `CHUNKS_FILE_READ_ERROR`. |
| One JSONL line invalid | Skip that line and add/increment `CHUNK_LINE_INVALID_JSON`. |
| One chunk shape invalid | Skip that line and add/increment `CHUNK_INVALID_SHAPE`. |
| Source PDF missing | Not checked. The chunk result and its source link are returned from the manifest path (§13.4). |
| Query empty and no signals/context | Empty success with `EMPTY_SEARCH_INPUT`. |
| Limit invalid | Clamp to configured bounds and add `LIMIT_CLAMPED`. |
| AgentThing numeric setting out of bounds | Clamp and add `CONFIG_VALUE_CLAMPED` (§4). |
| Index build exceeds max docs/chunks | Build partial index, set `degraded: true`, add `INDEX_LIMIT_REACHED`. |
| TTL rebuild fails with a previous index | Serve the previous index with `INDEX_REBUILD_FAILED_USING_STALE` (§10). |
| Unexpected exception | Catch at tool boundary, log, return degraded empty search or structured get error `DOCUMENT_TOOL_INTERNAL_ERROR`. |

Warnings are compact and bounded, never hundreds of per-line warnings. Repeated
warnings are aggregated by code (at most 20 distinct codes per response):

```json
{
  "code": "CHUNK_LINE_INVALID_JSON",
  "message": "Some chunk lines were skipped because they were invalid JSON.",
  "count": 3
}
```

## 9. Java implementation components

The classes live in `parler-agent/src/main/java/com/thingworx/things/agent/tools/`:

| Class | Responsibility |
| --- | --- |
| `DocumentKnowledgeToolSchemas` | Provider tool parameter schemas. |
| `DocumentKnowledgeToolsExecutor` | Dispatch `search_document_chunks`, `get_document_chunk`, and `resolve_document_set`. |
| `DocumentKnowledgeRuntime` | Index resolution, host-context scoping, and tool result envelopes. |
| `DocumentKnowledgeSettings` | Resolve and clamp the `AgentSettings` fields (§4). |
| `DocumentKnowledgeRepositoryReader` | FileRepository listing and text reads. |
| `DocumentKnowledgePackageManifest` | Parse and validate `manifest.json`. |
| `DocumentKnowledgeDocumentProfile` | Parse the optional manifest `documentProfile` (§5.3.1). |
| `DocumentKnowledgeChunk` | Parse and validate chunk JSONL rows. |
| `DocumentKnowledgeIndex` | Loaded manifests, chunks, and lookup maps. |
| `DocumentKnowledgeIndexCache` | JVM cache with TTL rebuild and stale fallback (§10). |
| `DocumentKnowledgeSearchScorer` | Deterministic document selection and chunk scoring (§11). |
| `DocumentKnowledgeBm25` | Capped BM25 boost over the selected documents' chunks (§11). |
| `DocumentSetResolver`, `ResolvedDocumentSet`, `ResolverSource` | Built-in document-set matcher and resolver result (§6.5). |
| `DocumentKnowledgeLinkBuilder` | Build FileRepository PDF links with `#page=N` (§13). |
| `DocumentKnowledgeTextBounds` | Snippet and chunk-markdown budgets (§5.4). |
| `DocumentKnowledgeWarnings` | Bounded warning aggregation. |

`BuiltInTools.registerAll` applies §4.1: when
`documentKnowledgeBuiltinsEnabled` is `false`, none of the document-knowledge
classes above register tool names in `ToolRegistry`.

## 10. Cache and index lifecycle

The agent does not read every file on every tool call. It keeps a JVM cache:

```text
key: AgentThing name + repository Thing name + root path
value:
  loadedAt
  expiresAt
  manifestsByDocId
  chunksByDocAndChunkId
  searchableChunks
  warning summary from last load
```

Behavior:

- Lazy-load on first search/get.
- Reuse until TTL expires. The index is shared by every user of the same
  AgentThing and settings, and a hit makes no repository call.
- On TTL expiry, rebuild synchronously. The rebuild reads as the calling user
  (§7).
- If the repository refuses a rebuild read, return no index and keep the shared
  entry unchanged.
- If rebuild fails for another reason and a previous index exists, return
  results from the stale index with warning `INDEX_REBUILD_FAILED_USING_STALE`.
- If rebuild fails and no previous index exists, return degraded empty search.
- Bound documents and chunks using configuration.

ThingWorx authorizes each repository read the agent actually makes. A cache hit
reuses an index that an earlier (re)build loaded, possibly for another user,
and is not re-authorized for the current user.

There is no refresh service; the index changes only through a TTL rebuild. The
cache logs misses and rebuilds at `info` (hits at `debug`), refusals and stale
serves at `warn`, and the index load logs its start, its result, and a summary
of skipped packages.

## 11. Scoring

Document search uses a two-phase deterministic scorer
(`DocumentKnowledgeSearchScorer`):

1. **Document score** — identity evidence from query, `signals`, `assetContext`,
   manifest fields, and optional `documentProfile`.
2. **Chunk score** — lexical/metadata scoring (below), plus the parent document
   score as a first-class boost, plus a BM25 boost.

Document selection:

- With `documentIds` (from the call or host-context scoping), only those
  documents are ranked (`documentIds-filter`).
- Otherwise the top document is selected alone (`hard-single`) when it has hard
  identity evidence (alias, title, `docId`, or filename), scores at least 80, and
  leads the second document by at least 30.
- Otherwise all documents are ranked and results are **diversified** with a
  per-document cap of `max(1, ceil(limit/2))` (`diversified`). If the cap leaves
  the result short and no remaining chunk belongs to a document still under its
  cap, the cap is lifted to fill the limit.

The BM25 boost is computed over the markdown of all chunks in the selected
documents, so term rarity is local to that set. Query terms are the query tokens
plus signal-name and asset-context tokens (k1 = 1.2, b = 0.75). The boost is capped
at 10 points so it reorders within a band without overturning strong additive
signals, and it never takes part in document selection. A chunk with a zero
additive score is still a candidate when its BM25 boost is positive.

Candidate fields:

- `heading`
- `sectionPath`
- `contentType`
- `tags`
- `signals[].name`
- `summary`
- `markdown`
- `assetModels` / manifest `documentProfile`
- `documentType`
- `assetContext` scalar/string values (threaded into document scoring)

Main additive chunk scores:

| Match | Score |
| --- | ---: |
| Exact signal name match | +50 |
| Exact heading phrase match | +35 |
| Query token matches a chunk signal name | +25 each |
| Tag/component exact match | +25 |
| Document type match | +15 |
| Content type `troubleshooting` for alarm/shutdown or operational-trouble queries | +15 |
| Query token in heading | +8 each |
| Query token in summary/tags | +5 each |
| Query token in markdown | +1 each, capped at 20 |
| Package with manifest `documentRole: bundle` | −40 |

Document-level evidence weights (representative):

| Evidence | Weight class |
| --- | ---: |
| Exact alias/title phrase | +100 |
| docId / filename token phrase | +60 |
| Document kind | +40 |
| Asset model phrase | +25 |
| Manufacturer | +20 |
| `assetContext` value | +15 |
| Derived domain heading term | +10 |
| Document type enum | +5 |

Sort order:

1. substantive chunks before heading/marker chunks (`chunkId` starting with
   `signal-`), so a signpost never outranks answer content;
2. score descending;
3. `troubleshooting` before `maintenance` before `section` / `semantic-section`
   before `page`;
4. semantic chunks before page chunks for equal score;
5. stable `docId`, then `chunkId`.

Every chunk is its own row: same-page matches are not collapsed, and page chunks
get no special rule beyond the scoring and tie-breakers above.

Search success responses include bounded `documentScores`, `selectedDocIds`, and
`selectionMode` for diagnostics (§5.3).

## 12. Deterministic retrieval, no embeddings

Retrieval uses deterministic metadata and lexical scoring only. There is no
embedding model, vector index, or semantic recall.

A health-status turn usually provides strong structured signals:

```text
alarm: Chiller High Pressure Shutdown
component: chiller
property: Receiver Pressure
assetModel: CarbaQ CO2 Capture Solution
documentType: operations_manual
contentType: troubleshooting
```

Those signals are better handled by deterministic matching than by broad vector
similarity:

- Exact alarm names dominate.
- Component/tag matches are explainable.
- Troubleshooting sections outrank general page chunks for alarm/shutdown
  questions.
- Heading and section matches are testable.

Properties of deterministic retrieval:

- **Explainable:** `documentScores[].matchedEvidence` and the scoring rules say why a
  result matched an alarm, tag, heading, or section type.
- **Testable:** fixture tests assert that `Chiller High Pressure Shutdown`
  returns the page 25 troubleshooting chunks.
- **Stable:** results do not drift with an embedding model or vector store.
- **Small:** a FileRepository-backed JVM index and a scoring function.
- **Industrial-friendly:** equipment names, alarm names, property names, and
  maintenance terms are often exact identifiers, not fuzzy prose.

Known limitation: when the user describes a condition in words that do not
overlap with the manual, lexical matching can be weak. For example, "Why is the
unit cold but not making liquid?" shares few terms with the manual row "Negative
temperature but no liquid in the receiver". The supported contract (§2.1)
requires asset-type context, which keeps such searches scoped to the right
documents; within them, the BM25 boost (§11) helps with partial term overlap.

## 13. Link generation

The link builder (`DocumentKnowledgeLinkBuilder`) uses the same FileRepository
download rules as table CSV export (`CONTRACTS/TABLE_CONTRACT.md` §4,
`docs/ui/table-view-solution.md` §5.5; the UI counterparts in
`parler-ui/parler-ui.js` are `thingworxFileRepositoriesHref`,
`thingworxFileRepositoryDownloaderHref`, and `fileRepoPathNeedsDownloaderQuery`).

Agent `href` values are mashup-relative platform paths (no browser origin
prefix), matching existing table export examples.

### 13.1 Path normalization

Before choosing the link form, the builder:

- trims `repository` and `path`;
- normalizes backslashes to `/` in `path`;
- treats `path` as FileRepository-relative (same semantics as table
  `exportFile`);
- for path-style links, ensures `path` begins with `/`;
- for the downloader query form, strips a leading `/` from `download-path` (same
  as `parler-ui`).

If `repository` or `path` is empty, `href` is empty. `path` points to the
original PDF, not markdown or chunk files.

### 13.2 Path-style link

Used when `path` does **not** contain `?` or `#`:

```text
/Thingworx/FileRepositories/{encodeURIComponent(repository)}{encodeURI(path)}
```

The PDF page fragment is appended only when `page` is a positive integer:

```text
#page={page}
```

Example:

```text
/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25
```

### 13.3 Downloader query link

When `path` matches `/[?#]/`, a path-style URL would mis-parse. The builder uses
the table-export downloader form for the repository/path portion, then appends
the page fragment:

```text
/Thingworx/FileRepositoryDownloader?download-repository={repository}&download-path={pathWithoutLeadingSlash}#page={page}
```

Query parameters are encoded like `URLSearchParams`
(`application/x-www-form-urlencoded`, space as `+`). Unencoded `?` / `#` are
never joined into a path-style URL.

When rendering the answer, `<parler-ui>` rewrites same-origin FileRepository PDF
links — both the path-style link and the downloader query link — to the
downloader form with `directRender=true`, preserving `#page=N`, so the browser
renders the PDF inline at that page
(`parler-ui/lib/thingworxDocumentLinks.js`). The tool output keeps the stable
`sourceLinks[].href` described above.

### 13.4 Page and verification rules

- `page` is the chunk's `pageStart`.
- If `page` is missing, zero, or negative, `#page=...` is omitted but the file
  link is kept.
- The agent does **not** verify that the source PDF exists in the FileRepository
  when building links. It trusts the manifest/configured paths and makes no extra
  repository reads.

## 14. LLM routing and answer rules

The routing guide (`llm_tool_routing_guide.txt`, "Document knowledge" block) and
the tool descriptions teach:

- Use live health/status tools first when the user asks about current state.
- Use `search_document_chunks` after a concrete issue, alarm, component, or
  symptom is known.
- Use `get_document_chunk` for the top matches that will affect the answer.
- Do not cite document text unless it came from `get_document_chunk` or a
  returned search snippet.
- Do not invent source links.
- When citing document sources, render each cited source as a markdown link using
  `sourceLinks[].href` from tool results (copy href exactly; include `#page=` when
  present). Plain-text source lines are not clickable in the UI.
- If document search is degraded or empty, say that no matching manual section
  was found and continue from live evidence.

When both live status and manual guidance apply, the answer distinguishes them.
Typical final answer structure:

```markdown
## Observed status

...

## Manual guidance

...

## Recommendation

...

Sources:
- [Fernwick Labs manual, section 9, page 25](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25)
```

## 15. Tests

JUnit coverage (`parler-agent/src/test/java/com/thingworx/things/agent/tools/DocumentKnowledge*Test.java`)
includes:

| Test | Expected |
| --- | --- |
| `documentKnowledgeBuiltinsEnabled=false` | None of the document tools registered in `ToolRegistry`; names absent from `builtinToolDefinitionNames()`; extended tools may use the same names. |
| `documentKnowledgeBuiltinsEnabled=true` | All three document tools (`search_document_chunks`, `get_document_chunk`, `resolve_document_set`) registered with `ToolDefinition`s; names present in merged LLM list and `builtinToolDefinitionNames()`. |
| Generated `search_document_chunks` schema | Provider-compatible parameters object (no invalid array `items` omissions). |
| Good fixture search for `Chiller High Pressure Shutdown` | Returns troubleshooting chunk on page 25. |
| Search with exact alarm signal | Signal match outranks broad page chunk. |
| `get_document_chunk` for known chunk | Returns full markdown and FileRepository link. |
| Missing repository config | Search returns degraded empty success. |
| Missing manifest | Package skipped; search still succeeds. |
| Invalid manifest JSON | Package skipped with warning. |
| Missing chunks file | Package skipped with warning. |
| Invalid JSONL line | Bad line skipped; good lines indexed. |
| Invalid chunk shape | Bad chunk skipped. |
| Empty query with no signals/context | Empty success with warning. |
| Limit too large | Limit clamped with `LIMIT_CLAMPED`. |
| Snippet over budget | `snippet` truncated to `documentKnowledgeSearchSnippetMaxChars`. |
| Repeated load warnings | Aggregated warning with `count` (bounded list). |
| `get_document_chunk` over budget | `CHUNK_MARKDOWN_TRUNCATED` at `documentKnowledgeChunkMaxChars`. |
| Link builder path with spaces | Encodes path consistently with table download style (`encodeURI`). |
| Link builder path without leading `/` | Normalized to leading `/` for path-style links. |
| Link builder path with `?` or `#` | Uses downloader query form; leading `/` stripped from `download-path`. |
| Link builder missing/invalid page | `href` omits `#page=...`. |
| Cache rebuild failure with stale index | Uses stale index with warning. |

Fixture:

```text
dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/
```

The live agent eval suite `docs/agent/evals/document_knowledge_v1.yaml` covers
search/get, no-match, and live-status-first + manual cases. It runs only with
`AGENT_EVAL_HAS_DOCUMENT_KNOWLEDGE=1`; the health-status case also requires
`AGENT_EVAL_DOCUMENT_KNOWLEDGE_LIVE_STATUS=1`.
