# PDF search and document-grounded recommendations

Status: implemented. The runtime is described in `docs/agent/document-chunk-tools.md`.

This document defines the Parler-side contract for using already-converted
manuals, runbooks, and PDF-derived documents in agent answers. The motivating
use case is data-insight health diagnosis:

1. The user asks for health status.
2. Parler observes live ThingWorx evidence and identifies health issues.
3. Parler searches parsed document knowledge for relevant manual guidance.
4. Parler composes a final recommendation that cites the original document,
   section, and page.

A concrete fixture that follows this document is stored at:

`dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/`

The manual is synthetic (see `dev_data/future_repo/README.md`). Treat
`dev_data/future_repo/` as the root of a document repository. It contains the
original PDF copy (`source/original.pdf`), converted markdown, retrieval chunks,
manifest, and rendered page previews.

## 1. Scope

- PDF extraction is implemented outside the Parler runtime, in a separate project/service
  (the repository's `test_scripts/pdf-conversion/` is an offline tool, not part of the extension).
- Parler consumes an already-converted document knowledge package.
- Parler defines the common package format, stable link format, tool contract,
  and answer behavior needed to use the converted content correctly.
- When Parler talks about a document, it must cite a stable link back to the
  original document location, preferably narrowed to the relevant section or page.

The extraction pipeline is summarized (§10) only to explain what Parler expects as
input; Parler consumes the package contract in §3–§5 and does not own the pipeline.

## 2. Problem shape

Industrial troubleshooting answers need two evidence streams:

| Evidence stream | Example | Role in final answer |
| --- | --- | --- |
| Live ThingWorx evidence | Active alarms, current values, recent history, maintenance counters | What is happening now |
| Document evidence | Operations manual, troubleshooting table, maintenance procedure | What the manufacturer or operator guide says to check |

The agent should not answer from manual text alone, and should not invent repair
steps from a health status alone. The useful product behavior is:

> "I see this live condition. It matches this manual section. The manual says to
> check these causes. Given the live evidence, start with these steps. Here are
> the source links."

## 3. Document Knowledge Pack

The external extraction pipeline must produce a directory package with a stable
layout. It is intentionally file-based so it can be stored in a
ThingWorx repository and served through normal ThingWorx services.

Recommended package layout:

```text
document-knowledge/
  <docId>/
    manifest.json
    source/
      original.pdf
    markdown/
      manual.md
    chunks/
      chunks.jsonl
    pages/
      page-0001.png
      page-0002.png
    figures/
      <optional extracted or rendered figures>
```

Only `manifest.json`, `source/original.pdf`, `markdown/manual.md`, and
`chunks/chunks.jsonl` are required. Rendered pages and
figures are recommended because they make page-accurate links and visual review
possible.

### 3.1 `docId`

`docId` is the stable logical identifier for the document package.

Rules:

- Lowercase ASCII.
- Use letters, digits, and hyphens.
- Do not include file extensions.
- Do not include spaces.
- Keep it stable across re-ingestion of the same logical document.

Example:

```text
fernwick-carbaq-ops-v2
```

### 3.2 `manifest.json`

`manifest.json` is the package entry point. It tells Parler what the document is,
where the source and converted artifacts are located, and how stable links should
be formed.

Required fields:

```json
{
  "contractVersion": "0.1",
  "docId": "fernwick-carbaq-ops-v2",
  "title": "Fernwick Labs CarbaQ CO2 Capture Solution Operations Manual",
  "sourcePath": "source/original.pdf",
  "markdownPath": "markdown/manual.md",
  "chunksPath": "chunks/chunks.jsonl",
  "pageCount": 28,
  "sourceRepository": "AIDocRepository",
  "sourceRepositoryPath": "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
  "sourceHref": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
  "sourceSha256": "<sha256 of source/original.pdf>",
  "convertedAt": "2026-06-15T00:00:00Z"
}
```

Recommended fields:

```json
{
  "sourceFileName": "Fernwick Labs - CarbaQ - V.2.0.pdf",
  "documentVersion": "2.0",
  "documentType": "operations_manual",
  "assetModels": ["CarbaQ CO2 Capture Solution"],
  "languages": ["en"],
  "artifacts": {
    "renderedPagesPattern": "pages/page-{page:04d}.png",
    "figuresPath": "figures/"
  },
  "conversionQuality": {
    "textLayer": "present",
    "ocrUsed": false,
    "tablesDetected": 1,
    "tablesStructured": 1,
    "manualReview": "recommended"
  },
  "chunkCount": 45,
  "chunkStrategies": ["page", "semantic-section", "troubleshooting-row"]
}
```

`conversionQuality` is informational. It helps a tool or prompt decide whether to
state that a document-derived recommendation may need manual verification.

### 3.3 Stable document links

PDF/manual source links use the same ThingWorx FileRepository path style as
table CSV downloads. Do not introduce a Parler-specific URI scheme for PDF
sources.

The source file is identified by:

```json
{
  "repository": "AIDocRepository",
  "path": "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
  "page": 25
}
```

The chunk contract uses the same stable path-style rule as table downloads
(`CONTRACTS/TABLE_CONTRACT.md` §4):

```text
/Thingworx/FileRepositories/{encodeURIComponent(repository)}{encodeURI(path)}#page={page}
```

Example:

```text
/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25
```

If the source path contains `?` or `#`, use the table-export downloader query
form for the repository/path portion (`download-repository`, `download-path` with
URL encoding), then append the PDF page fragment after the query string:

```text
/Thingworx/FileRepositoryDownloader?download-repository={repository}&download-path={pathWithoutLeadingSlash}&directRender=true#page={page}
```

For rendered assistant markdown, `<parler-ui>` rewrites FileRepository PDF links
to the downloader form with `directRender=true` and preserves `#page=N`. This is
a UI rendering behavior; the stored `sourceLinks[].href` may remain the stable
path-style link above.

Rules:

- Every chunk must include at least one `sourceLinks[]` entry.
- `sourceLinks[]` entries should carry `repository`, `path`, `page`, and a
  prebuilt `href` string when the tool can build it.
- `repository` has the same meaning as table `exportRepository`.
- `path` has the same FileRepository-relative semantics as table `exportFile`.
- `page` is the PDF page to open; omit `#page=...` when missing or invalid.
- `href` is the mashup-relative link for Markdown responses.
- Normalize paths per `docs/agent/document-chunk-tools.md` §13 before building
  `href`.

## 4. Markdown contract

`markdown/manual.md` is the canonical human-readable converted document.

It should preserve:

- title and document metadata;
- section hierarchy;
- page anchors;
- tables as markdown tables when reliable;
- warning/caution blocks as visible markdown blocks;
- figure references and captions when available;
- source page markers.

Recommended page anchor format:

```markdown
<!-- page: 25 -->
<a id="page-25"></a>

## 9. Troubleshooting Tips
```

Recommended section anchor format:

```markdown
<a id="section-9-troubleshooting-tips"></a>
## 9. Troubleshooting Tips
```

Troubleshooting tables should be normalized into a format that retrieval can use.
For the Fernwick Labs sample, the PDF table has the columns `Problem`, `Possible
cause`, and `Remedy`. The markdown should keep the table and may also emit
expanded subsections:

```markdown
### Chiller High Pressure Shutdown

Probable causes:

- Moisture reached the activated carbon bed and the chiller.
- The back pressure regulator was moved.

Recommended actions:

- Depressurize the unit, then dry or replace the carbon bed before restarting.
- Adjust the back pressure regulator about 1/8 of a turn at a time until Receiver
  Pressure reads 140 psi, then lock the adjusting screw.
- Allow up to 15 minutes for the pressure to settle before adjusting again.

Source: [Fernwick Labs manual, section 9, page 25](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25)
```

## 5. Chunk contract

`chunks/chunks.jsonl` is the runtime retrieval format. Each line is one JSON
object. Chunks should be short enough to fit comfortably in an LLM answer context
with several neighboring chunks.

Required fields:

```json
{
  "contractVersion": "0.1",
  "docId": "fernwick-carbaq-ops-v2",
  "chunkId": "troubleshooting-chiller-high-pressure-shutdown",
  "heading": "Chiller High Pressure Shutdown",
  "sectionPath": ["9. Troubleshooting Tips"],
  "pageStart": 25,
  "pageEnd": 25,
  "markdown": "### Chiller High Pressure Shutdown\n\nProbable causes:\n- ...",
  "sourceLinks": [
    {
      "label": "Fernwick Labs manual, section 9, page 25",
      "repository": "AIDocRepository",
      "path": "/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf",
      "page": 25,
      "href": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25"
    }
  ]
}
```

Recommended fields:

```json
{
  "contentType": "troubleshooting",
  "tags": ["chiller", "high pressure", "shutdown", "receiver pressure"],
  "assetModels": ["CarbaQ CO2 Capture Solution"],
  "signals": [
    {
      "kind": "alarm",
      "name": "Chiller High Pressure Shutdown"
    },
    {
      "kind": "property",
      "name": "Receiver Pressure"
    }
  ],
  "summary": "Manual guidance for chiller high pressure shutdown and BPR/receiver pressure checks."
}
```

Chunking guidance:

- Page-level chunks may be included to guarantee full-document coverage.
- Semantic chunks should be added for important sections, procedures, and
  troubleshooting rows.
- One troubleshooting table row should normally become one chunk.
- A maintenance procedure can be split by task or heading.
- Keep warnings/cautions with the step they constrain.
- Do not split a cause from its recommended action.
- Include page ranges even when the markdown spans multiple pages.
- Preserve enough surrounding heading context for citation and answer wording.

## 6. Parler tools

The Parler-side tool surface is small. The tools below are Java built-ins,
advertised when `documentKnowledgeBuiltinsEnabled` is `true`; extended tools backed
by ThingWorx services may use the same names (see `docs/agent/document-chunk-tools.md`).
The built-ins also include `resolve_document_set` (document scoping, see
`docs/operations/knowledge-retrieval-pipeline.md`).

Normative implementation detail, registration semantics, output budgets, and
tests live in `docs/agent/document-chunk-tools.md`. This section keeps the
cross-cutting package + answer contract aligned with that agent design.

### 6.1 `search_document_chunks`

Purpose: find manual chunks relevant to a health issue, alarm, component, or user
question.

Input schema:

```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "description": "Natural-language search query built from the health issue or user question."
    },
    "signals": {
      "type": "array",
      "description": "Optional alarms, properties, components, or symptoms to match.",
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
      "description": "Optional asset/model/site context from the health-status workflow."
    },
    "documentTypes": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Optional document type filters such as operations_manual or troubleshooting_guide."
    },
    "documentIds": {
      "type": "array",
      "items": {"type": "string"},
      "description": "Optional explicit document id filter (for example from resolve_document_set)."
    },
    "limit": {
      "type": "integer",
      "description": "Maximum number of chunks to return. Clamped to configured bounds."
    }
  },
  "required": []
}
```

`query` is optional when useful `signals` or `assetContext` fields are present.
If all are empty, return an empty success with a warning.

Expected result:

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
  "skippedChunks": 0
}
```

Degraded empty success example:

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

### 6.2 `get_document_chunk`

Purpose: fetch the full markdown and provenance for a selected chunk.

Input schema:

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

Expected success result:

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
  "markdown": "## Chiller High Pressure Shutdown - back pressure regulator\n\nCause: the back pressure regulator (BPR) was moved. Remedy: adjust the BPR...",
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

Not-found example:

```json
{
  "status": "error",
  "code": "CHUNK_NOT_FOUND",
  "message": "Document chunk was not found.",
  "docId": "fernwick-carbaq-ops-v2",
  "chunkId": "missing"
}
```

## 7. Health recommendation flow

The recommended workflow is:

```text
User asks health-status question
  -> get live health evidence
  -> normalize health issues
  -> search_document_chunks for each issue
  -> get_document_chunk for the best matches
  -> compose final answer with live evidence + document evidence + citations
```

For a playbook implementation, the document-search nodes should run after the
health issue has been normalized. For a skill implementation, the skill must
explicitly tell the model not to search documents until it has live health
evidence or a user-provided issue.

## 8. Prompt and answer rules

A document-grounded recommendation must follow these rules:

- State the live observed issue before citing manual guidance.
- Name the matched manual section or heading.
- Separate probable causes from recommended checks/actions.
- Use cautious wording when multiple causes match.
- Do not imply that the manual content was live telemetry.
- Do not invent page numbers, sections, or document titles.
- If the search result has no useful match, say that no relevant manual section
  was found and answer from live evidence only.
- Include source links returned by the tools.

Recommended final answer shape:

```markdown
## Health status

I found one active issue: Chiller High Pressure Shutdown.

Observed evidence:
- Chiller pressure is above the expected operating range.
- Receiver pressure is also elevated.

## Recommendation

This matches the manual troubleshooting entry "Chiller High Pressure Shutdown".
The manual lists likely causes including moisture buildup in the carbon bed or
chiller, and back-pressure-regulator/receiver-pressure issues.

Recommended next checks:
1. Check for moisture at the relevant relief point.
2. Verify the CO2 out/receiver valve path and confirm the lines are clear.
3. If receiver pressure is high, adjust the BPR gradually and wait for HMI readings
   to settle before making another adjustment.

Sources:
- [Fernwick Labs manual, section 9, page 25](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25)
```

## 9. Skill guidance

A customer configuration can add a skill such as
`HealthStatusManualRecommendation`.

When to use:

- The user asks what is wrong with an asset and what to do next.
- The user asks for health status and there are active issues.
- The user asks for recommendations tied to alarms, abnormal readings, shutdowns,
  maintenance warnings, or operator procedures.

Workflow:

1. Gather live health evidence using the application's health/status tools.
2. Normalize the issue into component, symptom, alarm, properties, and severity.
3. Search document chunks using those normalized fields.
4. Fetch the best matching chunk(s).
5. Compose an answer that clearly separates live evidence from manual guidance.
6. Include source links exactly as returned by the document tools.

Guardrails:

- Do not use document search as a substitute for health evidence when the user
  asked about current asset status.
- Do not cite a document chunk that was not returned by a tool.
- Do not omit citations when document content affects the recommendation.
- Prefer a short source list over inline citation noise in every sentence.

## 10. External extraction pipeline summary

The external pipeline is expected to:

1. Accept original PDFs and related source files.
2. Produce `manifest.json`, `markdown/manual.md`, and `chunks/chunks.jsonl`.
3. Preserve page and section provenance.
4. Render page images when practical.
5. Normalize troubleshooting and maintenance tables into chunk-friendly
   markdown.
6. Report conversion quality so downstream tools can communicate uncertainty.

This pipeline is outside the Parler runtime; Parler treats the resulting package as
input. The offline converter used for the sample corpus is described in
`docs/operations/pdf-conversion-agent-playbook.md`.

## 11. Acceptance criteria

- The concrete package at
  `dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/` validates
  against this contract and can be searched by the document-search tool.
- A health-status answer can retrieve the `Troubleshooting Tips` content for a
  matching issue such as `Chiller High Pressure Shutdown`.
- The final answer includes the live issue, the manual-derived recommendation,
  and a stable FileRepository PDF source link with a `#page=...` suffix.
- The answer does not expose local filesystem paths as user-facing links.
- The answer is useful even when no document match is found.
