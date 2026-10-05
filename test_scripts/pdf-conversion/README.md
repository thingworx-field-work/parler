# PDF → document-knowledge conversion (test_scripts/pdf-conversion)

Scripted backbone for the procedure in
[`docs/operations/pdf-conversion-agent-playbook.md`](../../docs/operations/pdf-conversion-agent-playbook.md).
It turns a source PDF into a Parler **document-knowledge package** (manifest +
markdown + retrieval chunks) like the reference fixture
`dev_data/future_repo/document-knowledge/fernwick-carbaq-ops-v2/`.

The fixture set is **synthetic**: every manual is fictional and was written for
Parler's retrieval tests. `synthetic/build_samples.py` renders the source PDFs
into `dev_data/pdf-sample/` (gitignored) and builds the hand-curated
`fernwick-carbaq-ops-v2` package directly; `convert_pdf.py` then converts the
other four:

```bash
./test_scripts/pdf-conversion/synthetic/build_samples.py
./test_scripts/pdf-conversion/convert_pdf.py --all
```

The synthetic content lives in `synthetic/content_*.py`, one module per manual,
with one list entry per PDF page so page numbers stay stable.

## Why a script

The conversion is mostly mechanical (text extraction, page anchors, chunk
records, manifest, sha256, validation). The script makes it **deterministic and
reproducible** — a fixed `convertedAt` and no random ids — so re-running yields a
byte-identical package. The judgment-heavy part (semantic sections, signal rows)
is best-effort and every package is flagged `manualReview: "required"`.

## Prerequisites

- [`uv`](https://docs.astral.sh/uv/) on PATH. The scripts use a `uv run`
  shebang and declare `pypdf` inline, so no manual venv is needed.
- Poppler CLI tools `pdftotext`, `pdfinfo` and `pdftoppm` on PATH (`brew install poppler`).
- `synthetic/build_samples.py` declares `reportlab`, `pypdf` and `pdfplumber` inline for `uv run`.

## Files

| File | Purpose |
|------|---------|
| `synthetic/build_samples.py` | Render the synthetic source PDFs and build the hand-curated CarbaQ package. |
| `synthetic/content_*.py` | Page-by-page content of the synthetic manuals and the bundle-only accessory folder. |
| `convert_pdf.py` | Convert one or all configured PDFs into staged packages. |
| `validate_package.py` | Run the playbook §9 checks on a package (or `--all`). |

## Usage

```bash
# from repo root
./test_scripts/pdf-conversion/convert_pdf.py --list          # show configured docIds
./test_scripts/pdf-conversion/convert_pdf.py <docId>         # convert one
./test_scripts/pdf-conversion/convert_pdf.py --all           # convert all four

./test_scripts/pdf-conversion/validate_package.py --all      # validate every final package
./test_scripts/pdf-conversion/validate_package.py dev_data/future_repo/document-knowledge/<docId>
```

Output is written to the fixture repository root:

```
dev_data/future_repo/document-knowledge/<docId>/
  manifest.json
  source/original.pdf      # copy of the source PDF
  markdown/manual.md
  chunks/chunks.jsonl
  figures/.gitkeep
```

No `pages/` directory is emitted: page PNG rendering was skipped for this batch
(operator decision), and the converter does not create an empty `pages/`. The
manifest's `artifacts.renderedPagesPattern` documents the naming convention for
when rendering is enabled later.

## The four documents

| docId | source PDF | pages | type |
|-------|-----------|------:|------|
| `kbm-coupling-manual-7318042` | `7.318.042 Manual_KBM.pdf` | 13 | operating_manual |
| `rk-t-install-spec-7318042` | `7.318.042 Manual_RKT_Install_Spec.pdf` | 21 | installation_spec |
| `rk-t-operating-manual-7318042` | `7.318.042 Manual_RKT_OperatingManual.pdf` | 110 | operating_manual |
| `rk-t-turbogenerator-master-7318042` | `7.318.042 Manual.pdf` | 177 | technical_manual ( **`_fallback/`** — not live index) |

`7.318.042 Manual.pdf` is a **compilation bundle**: a cover page followed by the
operating manual (with its Part B drawings), the installation requirements, the
coupling manual and a Folder 2 of accessory documentation (lube oil purifier,
turning gear, steam trap station, vibration rack and other accessories). It
duplicates every sub-manual page for page and adds topics only the bundle
contains. Its 43 keyword-signal pages exceed the 30-chunk signal cap, so it also
exercises selection by signal richness and the page-chunk fallback for the
dropped pages (`test_answerability.py` checks both).

**Corpus policy:** treat the three sub-manuals as the higher-resolution primary
sources for their topics. The master bundle should remain a fallback /
stress-test package, or be split later, unless the User explicitly chooses to
include it as an ordinary searchable document — keeping master and sub-manuals
side by side without that policy produces duplicate search hits and ambiguous
final citations. This is recorded in the master manifest `conversionQuality.warnings`.

## This is a fixture converter, not a general PDF pipeline

`convert_pdf.py` is a **deterministic batch converter for the configured
PDFs**, not a "given any PDF, produce production-ready document-knowledge"
implementation. It is reliable here precisely because the per-document metadata
is supplied by a human/agent in the `CONFIGS` table at the top of the script, not
inferred from the PDF. The converter only derives page text, page/section/signal
chunks, page count, and the sha256.

Human/agent-supplied per document (in `CONFIGS`):

- `doc_id`
- `title`
- `document_type`
- `asset_models`
- `document_version`
- `source_name` (source filename)
- `short_label` (used in `sourceLinks[].label`)
- `manufacturers` (becomes `documentProfile.manufacturers`)
- `extra_warnings` (e.g. the master overlap/policy note)

Edit `CONFIGS` to re-derive any of these.

## How extraction works

1. **Text** — `pdftotext` in natural reading order (not `-layout`), with
   `pdfplumber` recovering ruled tables as Markdown tables. Reading order gives
   clean paragraphs and bullet lists for prose manuals.
2. **Boilerplate** — lines recurring on ≥40 % of pages (and short) plus
   `Page X / Y` footers are stripped as letterhead/footer noise.
3. **Page chunks** — one per page (`page-NNNN`), the retrieval recall floor.
4. **Semantic-section chunks** — only when a *linear* dotted-leader table of
   contents is present and its page numbers are non-decreasing and in range
   (true for the KBM manual and the RK&T installation requirements). The RK&T
   operating manual lists its Part A chapters with `NN-NNN` register codes that do
   not map to PDF pages, so its chapter sections come from the Part A listing plus
   the first body heading of each chapter (`part_a_toc_sections`).
5. **Keyword-signal chunks** — pages mentioning ≥2 operational topics (alarm,
   trip, shutdown, maintenance, torque, alignment, …) become targeted
   `troubleshooting` chunks, capped at 30 per document. Under the cap, pages are
   ranked by **signal richness** (distinct-signal count desc, page number asc as
   tiebreaker) so the densest operational pages win the budget rather than
   whichever pages appear first; the selected set is then emitted in page order.
   When the cap drops pages, the manifest warnings say how many (no silent
   truncation).

## What the validator checks (and what it does not)

`validate_package.py` is **structural validation only**. It checks: required
files present; `manifest.json` is valid JSON; each chunk line is valid JSON with
the required keys and non-empty markdown; chunkIds are unique; each
`sourceLinks[0]` href is exactly this doc's final PDF path with a numeric
`#page=<n>` fragment and the `AIDocRepository` repository/path; **link
integrity** — the href page, `sourceLinks[0].page`, and `pageStart` agree and lie
in `1..pageCount` (so a link to page 999 of a 21-page PDF fails); `pageCount`
equals the PDF page count; `chunkCount` equals the JSONL line count; page ranges
are in bounds; `manifest.sourceHref` uses the final repo path prefix; and
`manual.md` carries page-1 and last-page anchors plus the final manifest PDF
path.

It does **not** check content fidelity: markdown accuracy vs the PDF, search
quality, heading quality, table extraction, or whether links resolve in a live
ThingWorx page. Those require human review — which is why every package is
`manualReview: "required"`.

## Known limitations (why `manualReview: "required"`)

- Section boundaries in section/signal chunks are page-granular, not exact text
  spans.
- Headings are heuristic (first plausible line; fragments, bare `Part X`
  labels, and numeric codes are rejected with a `Page NN` fallback) — not a true
  document-structure parse.
- No OCR; scanned-only pages would need it (the synthetic PDFs all carry a text
  layer).
- `convert_pdf.py` renders no page PNGs; only the hand-curated CarbaQ package has
  `pages/*.png` (rendered by `synthetic/build_samples.py`).
- Master document signal coverage is capped; flagged pages beyond the cap keep
  only page chunks (count stated in the master manifest warnings).

## Source and link caveats

Generated links intentionally target the deployment path
`/Thingworx/FileRepositories/AIDocRepository/document-knowledge/<docId>/...`.
The local fixture under `dev_data/future_repo/document-knowledge/` mirrors that
repository layout, but link resolution still requires the package to be
published under an `AIDocRepository` file repository in a running ThingWorx
environment.

The source input directory `dev_data/pdf-sample/` is gitignored. It is a local
intake folder, not part of the committed fixture repository. The committed
fixture packages carry their own `source/original.pdf` copies.

## Reproducibility & git

- Re-running `convert_pdf.py` is deterministic (fixed `convertedAt`, no random
  ids).
- `dev_data/pdf-sample/` is intentionally gitignored; keep source inputs there
  only for local conversion runs.
- The final packages under `dev_data/future_repo/document-knowledge/` are the
  review fixture artifacts. The copied `source/original.pdf` files are part of
  each package so source links can be tested against the same layout.
