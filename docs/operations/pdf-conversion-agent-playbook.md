# PDF conversion agent playbook

This document tells a coding agent (or a person) how to convert one input PDF into
a Parler document-knowledge package like the existing fixture:

`dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/`

This is not a production extraction pipeline. It is the operating procedure for
sample PDF conversion; `test_scripts/pdf-conversion/convert_pdf.py` scripts the
mechanical part of it (see `test_scripts/pdf-conversion/README.md`).

## 1. Inputs and outputs

Given:

```text
INPUT_PDF=<path to one PDF>
OUTPUT_REPO_ROOT=dev_data/future_repo
DOCUMENT_ROOT=$OUTPUT_REPO_ROOT/document-knowledge
SOURCE_REPOSITORY=AIDocRepository
```

Produce:

```text
dev_data/future_repo/document-knowledge/<docId>/
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
    ...
  figures/
    .gitkeep
```

Required artifacts:

- `manifest.json`
- `source/original.pdf`
- `markdown/manual.md`
- `chunks/chunks.jsonl`

Recommended artifacts:

- `pages/page-0001.png` ... `pages/page-NNNN.png`
- `figures/.gitkeep`

## 2. Current sample set

The current input folder is:

```text
dev_data/pdf-sample/
```

`dev_data/pdf-sample/` is intentionally gitignored. It is a local intake folder:
agents may read PDFs from it when present, but a fresh checkout is not expected
to contain those source files. Committed fixture packages under
`dev_data/future_repo/document-knowledge/` carry their own
`source/original.pdf` copies.

The sample PDFs are synthetic and are rendered into this folder by
`test_scripts/pdf-conversion/synthetic/build_samples.py`:

```text
Fernwick Labs - CarbaQ - V.2.0.pdf
7.318.042 Manual.pdf
7.318.042 Manual_RKT_Install_Spec.pdf
7.318.042 Manual_RKT_OperatingManual.pdf
7.318.042 Manual_KBM.pdf
```

`fernwick-carbaq-ops-v2` is the hand-curated reference package. The other PDFs
convert into sibling packages under `dev_data/future_repo/document-knowledge/`.

## 3. Choose stable metadata first

Before creating files, inspect the PDF enough to decide:

- `docId`
- `title`
- `documentVersion`, when visible
- `documentType`
- `assetModels`
- language codes

### 3.1 `docId` rules

`docId` must be stable and filesystem-safe:

- lowercase ASCII
- letters, digits, and hyphens only
- no spaces
- no file extension
- stable across repeated conversions of the same logical document

Recommended derivation:

1. Start from the product/equipment family.
2. Add document role/type.
3. Add visible part number or version if it distinguishes the document.
4. Normalize to lowercase hyphenated ASCII.

Examples:

```text
fernwick-carbaq-ops-v2
rk-t-install-spec-7318042
rk-t-operating-manual-7318042
kbm-coupling-manual-7318042
```

If the title or equipment family is unclear, use a conservative `docId` derived
from the filename and record the uncertainty in `manifest.json`
`conversionQuality.warnings`.

### 3.2 `documentType` values

Use one of these when it fits:

```text
operations_manual
installation_spec
operating_manual
maintenance_manual
troubleshooting_guide
technical_manual
specification
unknown
```

Do not over-classify. If a document contains mixed content, choose the dominant
use case and let chunks carry more specific `contentType` values.

## 4. Create the package skeleton

For a chosen `docId`:

```text
mkdir -p dev_data/future_repo/document-knowledge/<docId>/source
mkdir -p dev_data/future_repo/document-knowledge/<docId>/markdown
mkdir -p dev_data/future_repo/document-knowledge/<docId>/chunks
mkdir -p dev_data/future_repo/document-knowledge/<docId>/pages
mkdir -p dev_data/future_repo/document-knowledge/<docId>/figures
cp "$INPUT_PDF" dev_data/future_repo/document-knowledge/<docId>/source/original.pdf
touch dev_data/future_repo/document-knowledge/<docId>/figures/.gitkeep
```

Do not store converted artifacts outside the package unless they are temporary
working files. Temporary files should stay under `tmp/`.

## 5. Inspect and extract the PDF

Use available tools in this order. Prefer structured extraction, but keep the
process pragmatic.

### 5.1 Basic PDF metadata

Collect page count and metadata:

```text
pdfinfo "$INPUT_PDF"
```

If `pdfinfo` is unavailable, use a Python library such as `pypdf` to read page
count and metadata. Record the page count in `manifest.json`.

### 5.2 Render pages

Render pages for review:

```text
pdftoppm -png -r 150 "$INPUT_PDF" pages/page
```

Then rename outputs to the required form if needed:

```text
pages/page-0001.png
pages/page-0002.png
...
```

Every PDF page should have one rendered page image. If rendering fails, still
produce the required text artifacts and record the rendering issue in
`conversionQuality.warnings`.

### 5.3 Extract text

Try text-layer extraction first:

```text
pdftotext -layout "$INPUT_PDF" tmp/<docId>.layout.txt
pdftotext "$INPUT_PDF" tmp/<docId>.plain.txt
```

If the text layer is missing, scrambled, or mostly empty:

- inspect rendered page PNGs;
- use OCR if available;
- record `conversionQuality.textLayer` and `conversionQuality.ocrUsed`.

When a document has tables, use `pdfplumber` or another page-aware extractor if
available. Raw text is acceptable only when the final markdown remains readable.

## 6. Build `markdown/manual.md`

`manual.md` should be readable by a human and useful to an agent.

Required conventions:

- Start with a document title heading.
- Preserve document version, part number, product family, and document role when
  visible.
- Insert a page marker before content from each PDF page:

  ```markdown
  <!-- page: 25 -->
  <a id="page-25"></a>

  Page source: [page 25](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf#page=25)
  ```

- Use markdown headings for real section headings.
- Use markdown tables only when the table is reliable.
- For unreliable tables, use row-expanded subsections or bullet lists.
- Preserve warnings, cautions, procedures, setpoints, part numbers, alarms, and
  troubleshooting rows.
- Remove repeated headers/footers only when they pollute reading or retrieval.
- Do not invent content that is not visible in the PDF.

Recommended page structure:

```markdown
# <Document title>

Source PDF: [original.pdf](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf)

<!-- page: 1 -->
<a id="page-1"></a>

Page source: [page 1](/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf#page=1)

...
```

## 7. Build `chunks/chunks.jsonl`

`chunks.jsonl` is the retrieval artifact. It must contain one JSON object per
line. Each line must parse independently.

Every chunk must include:

```json
{
  "contractVersion": "0.1",
  "docId": "<docId>",
  "chunkId": "<stable chunk id>",
  "contentType": "page",
  "heading": "Page 01",
  "sectionPath": ["Full manual page"],
  "pageStart": 1,
  "pageEnd": 1,
  "tags": [],
  "signals": [],
  "summary": "Short retrieval summary.",
  "markdown": "Chunk markdown...",
  "sourceLinks": [
    {
      "label": "<document title>, <section>, page 1",
      "repository": "AIDocRepository",
      "path": "/document-knowledge/<docId>/source/original.pdf",
      "page": 1,
      "href": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf#page=1"
    }
  ]
}
```

### 7.1 Required chunk families

Create at least these chunk families:

1. **Page chunks**
   - One chunk per PDF page.
   - `chunkId`: `page-0001`, `page-0002`, ...
   - `contentType`: `page`
   - `heading`: best page heading, otherwise `Page NN`
   - Purpose: recall and page-level fallback.

2. **Semantic section chunks**
   - One chunk per major procedure/section when meaningful.
   - `chunkId`: slug from section path, such as
     `installation-receiver-bpr`.
   - `contentType`: `section`, `semantic-section`, `installation`,
     `operation`, `maintenance`, or `specification`.
   - Purpose: better retrieval than page chunks for user questions.

3. **Troubleshooting/procedure row chunks**
   - One chunk per alarm, fault, symptom, procedure, or setpoint row when the
     document contains such content.
   - `contentType`: `troubleshooting`, `procedure`, `setpoint`, or
     `maintenance`.
   - Purpose: high-signal answers for health-status follow-up questions.

Page chunks alone are not enough unless the PDF is extremely short. If the
document has procedures, alarms, tables, or settings, create targeted chunks for
them.

### 7.2 Chunk quality rules

Chunks should be:

- source-grounded;
- short enough to fit into an answer turn;
- complete enough to cite without reading the whole PDF;
- stable across repeated conversions;
- rich in domain terms users might ask about.

Good chunk markdown includes:

- heading;
- relevant procedure or table row;
- units and values;
- cautions when present;
- page source link.

Avoid:

- giant whole-document chunks;
- repeated boilerplate-only chunks;
- chunks with no page link;
- chunk ids based on timestamps or random ids;
- content copied from another document package.

### 7.3 Tags and signals

Use `tags` for normalized retrieval terms:

```json
"tags": ["bpr", "back-pressure-regulator", "receiver-pressure", "shutdown"]
```

Use `signals` for terms that resemble live health evidence:

```json
"signals": [
  {"kind": "alarm", "name": "Chiller High Pressure Shutdown"},
  {"kind": "component", "name": "Back Pressure Regulator"},
  {"kind": "property", "name": "Receiver Pressure"}
]
```

Do not force signals when the document does not contain operational evidence
terms. In that case, tags are enough.

## 8. Build `manifest.json`

Required shape:

```json
{
  "contractVersion": "0.1",
  "docId": "<docId>",
  "title": "<document title>",
  "sourcePath": "source/original.pdf",
  "markdownPath": "markdown/manual.md",
  "chunksPath": "chunks/chunks.jsonl",
  "pageCount": 1,
  "sourceSha256": "<sha256>",
  "convertedAt": "2026-06-25T00:00:00Z",
  "sourceFileName": "<original filename>",
  "documentVersion": "<version if visible>",
  "documentType": "operations_manual",
  "assetModels": ["<asset model if known>"],
  "languages": ["en"],
  "artifacts": {
    "renderedPagesPattern": "pages/page-{page:04d}.png",
    "figuresPath": "figures/"
  },
  "conversionQuality": {
    "textLayer": "present",
    "ocrUsed": false,
    "tablesDetected": 0,
    "tablesStructured": 0,
    "pageAnchorsGenerated": 1,
    "manualReview": "recommended",
    "warnings": []
  },
  "chunkCount": 1,
  "chunkStrategies": ["page"],
  "sourceRepository": "AIDocRepository",
  "sourceRepositoryPath": "/document-knowledge/<docId>/source/original.pdf",
  "sourceHref": "/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf"
}
```

Compute SHA-256 from the copied `source/original.pdf`, not from the source file
path if they differ:

```text
shasum -a 256 source/original.pdf
```

Set `chunkCount` to the number of JSONL lines that parse as chunks.

## 9. Validate the package

Before handing off, run these checks.

### 9.1 File presence

Verify:

```text
manifest.json exists
source/original.pdf exists
markdown/manual.md exists
chunks/chunks.jsonl exists
figures/.gitkeep exists when no figures were extracted
pages/page-0001.png exists when rendering succeeded
```

### 9.2 JSON validation

Validate `manifest.json`:

```text
python3 -m json.tool manifest.json >/dev/null
```

Validate JSONL:

```text
python3 - <<'PY'
import json
from pathlib import Path
p = Path("chunks/chunks.jsonl")
seen = set()
count = 0
for i, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1):
    if not line.strip():
        continue
    obj = json.loads(line)
    for k in ["contractVersion", "docId", "chunkId", "contentType", "heading",
              "sectionPath", "pageStart", "pageEnd", "summary", "markdown", "sourceLinks"]:
        assert k in obj, (i, k)
    key = (obj["docId"], obj["chunkId"])
    assert key not in seen, (i, "duplicate chunkId", key)
    seen.add(key)
    assert isinstance(obj["sourceLinks"], list) and obj["sourceLinks"], (i, "missing sourceLinks")
    href = obj["sourceLinks"][0].get("href", "")
    assert "/Thingworx/FileRepositories/" in href, (i, "bad href", href)
    assert "#page=" in href, (i, "missing page fragment", href)
    count += 1
print("chunks", count)
PY
```

### 9.3 Count consistency

Check:

- `manifest.pageCount` equals the PDF page count.
- rendered page PNG count equals `manifest.pageCount` when rendering succeeded.
- `manifest.chunkCount` equals JSONL chunk count.
- every chunk has `pageStart >= 1`.
- every chunk has `pageEnd >= pageStart`.
- every chunk page is within the PDF page count.

### 9.4 Link consistency

For every chunk:

- `sourceLinks[0].repository` is `AIDocRepository`;
- `sourceLinks[0].path` is `/document-knowledge/<docId>/source/original.pdf`;
- `sourceLinks[0].href` is
  `/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/source/original.pdf#page=<pageStart>`;
- final markdown source examples use `[label](href)`, not plain text.

### 9.5 Search sanity

Read the generated chunks and make sure obvious document terms are findable:

- title words;
- equipment model;
- section names;
- alarm/fault names;
- procedure names;
- important setpoints and units.

If the PDF has troubleshooting content, create at least one test prompt that
should hit a targeted chunk.

## 10. Conversion report

When the conversion is done, report:

- input PDF path;
- output package path;
- `docId`;
- title;
- page count;
- chunk count;
- chunk strategy summary;
- warnings or uncertain extraction areas;
- 3-5 example chunks worth reviewing;
- one or two sample prompts that should retrieve the document.

Do not claim the package is production-quality unless a human has checked the
markdown against the original PDF. "Review fixture quality" is acceptable when:

- the package follows the contract;
- important sections are searchable;
- source PDF links are present;
- extraction uncertainties are documented.

## 11. Convert one document at a time

Do not batch several PDFs before checking the first. After each package:

1. validate files and JSON;
2. inspect several rendered pages;
3. inspect `manual.md` around the most important sections;
4. inspect top targeted chunks;
5. commit only the completed package when asked.

## 12. What not to do

- Do not change Parler Java runtime code while converting PDFs.
- Do not change the package contract for one difficult PDF without documenting
  the reason.
- Do not invent procedures, alarms, values, or setpoints.
- Do not omit `sourceLinks`.
- Do not create links without `#page=` when page numbers are known.
- Do not rely on page chunks alone for a procedure-heavy manual.
- Do not clean or remove existing document packages unless explicitly asked.
