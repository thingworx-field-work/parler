# Future document repository fixture

This directory is a concrete example of the proposed Parler document-knowledge repository layout. It is not the PDF extraction implementation. It is a review fixture that shows how an external extraction service could publish a converted package for Parler to consume.

All packages are **synthetic**: the manuals are fictional and were written for Parler's retrieval tests. Rebuild them with `test_scripts/pdf-conversion/synthetic/build_samples.py` followed by `test_scripts/pdf-conversion/convert_pdf.py --all` (see `test_scripts/pdf-conversion/README.md`).

Sample packages:

- `document-knowledge/fernwick-carbaq-ops-v2/manifest.json`
- `document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf`
- `document-knowledge/fernwick-carbaq-ops-v2/markdown/manual.md`
- `document-knowledge/fernwick-carbaq-ops-v2/chunks/chunks.jsonl`
- `document-knowledge/fernwick-carbaq-ops-v2/pages/page-0001.png` ... `page-0028.png`
- `document-knowledge/rk-t-install-spec-7318042/`
- `document-knowledge/rk-t-operating-manual-7318042/`
- `document-knowledge/kbm-coupling-manual-7318042/`

Non-indexed fallback (stress tests only):

- `_fallback/document-knowledge/rk-t-turbogenerator-master-7318042/` — 177-page
  compilation bundle; **not** uploaded to live `/document-knowledge`.

FileRepository assumption:

- Thing name: `AIDocRepository`
- Repository root represented by this directory: `dev_data/future_repo/`
- Original PDF path: `/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf`
- Example page link: `/Thingworx/FileRepositories/AIDocRepository/document-knowledge/fernwick-carbaq-ops-v2/source/original.pdf#page=25`

Original source PDFs:

`dev_data/pdf-sample/` is an ignored local intake folder. `synthetic/build_samples.py`
writes the source PDFs there; they are not expected in a fresh checkout. Each
committed fixture package carries its own `source/original.pdf` copy.

PNG policy:

- The hand-curated Fernwick Labs fixture keeps low-resolution page PNGs rendered
  by `synthetic/build_samples.py`.
- The converted RK&T/KBM fixtures do not include page PNGs; their manifests record
  that page rendering was skipped.

## Smoke-test prompts

These prompts assume the AgentThing exposes the document chunk tools and points
at `AIDocRepository`. They are intentionally explicit about document search so a
manual test can verify that the model calls `search_document_chunks`, follows up
with `get_document_chunk` for the best match, and cites the returned PDF page
link in the final answer.

### Fernwick Labs CarbaQ troubleshooting

```text
A CarbaQ unit reports "Chiller High Pressure Shutdown" and the receiver pressure is
high. Search the document knowledge repository before answering. What does the
manual recommend for the back pressure regulator / BPR adjustment? Cite the
original PDF page link.
```

Expected retrieval target: `fernwick-carbaq-ops-v2`, troubleshooting rows on page
25, especially `troubleshooting-chiller-high-pressure-bpr`.

### Fernwick Labs negative temperature

```text
Search the document knowledge repository for the Fernwick Labs CarbaQ case
"Negative temperature but no liquid in the receiver". Summarize the probable
causes and recommended actions, and include the PDF page link.
```

Expected retrieval target: `fernwick-carbaq-ops-v2`, troubleshooting page 26.

### KBM coupling operation

```text
For the KBM flexible pin type coupling, search the document knowledge repository
and explain what the manual says about shaft misalignment compensation and what
to do if the coupling may be damaged before commissioning. Cite the PDF page
link you used.
```

Expected retrieval target: `kbm-coupling-manual-7318042`, operation/function
content around page 6.

### RK&T installation specification

```text
We are preparing the surrounding area for a RK&T CB 24 GT4 steam turbine. Search
the installation specification and summarize the guidance for foundation design
and turbine/driven-machine alignment. Include the source PDF page link.
```

Expected retrieval target: `rk-t-install-spec-7318042`, page 6.

### RK&T back-pressure start-up

```text
Search the RK&T installation specification for start-up with back pressure.
What changes when back pressure is below 6 bar(g), between 6 and 11 bar(g), and
above 11 bar(g)? Cite the relevant PDF page link.
```

Expected retrieval target: `rk-t-install-spec-7318042`, page 12.

### RK&T operating safety

```text
Search the RK&T operating manual for safety-relevant operating trouble such as
overspeed, steam pressure, bearing temperature, lube oil pressure, and
vibrations. What does the manual say to do before restarting the turbine? Cite
the original PDF page link.
```

Expected retrieval target: `rk-t-operating-manual-7318042`, safety notes around
page 21.

### Master manual fallback

```text
Search document knowledge for "lube oil purifier bowl cleaning interval" in the
RK&T documents. If both the compilation bundle and a smaller sub-manual match,
prefer the more specific sub-manual when it contains the same topic; otherwise
use the master manual and cite the PDF page link.
```

Expected retrieval target: `rk-t-turbogenerator-master-7318042`, the lube oil
purifier pages in Folder 2. No sub-manual covers the purifier, so the bundle must
supply the answer despite its −40 bundle penalty; for topics the sub-manuals do
cover (for example 7.7 Oil mist separator), the sub-manual should win. **Note:** as of Phase 3 Part B, the master package lives under
`_fallback/` and is not indexed in production; this prompt applies only when the
master is deliberately loaded for stress testing.

## Not covered by these prompts: cross-cutting `alwaysInclude`

The M4 cross-cutting document union (a custom `ResolveDocumentSet` override flagging
rows `alwaysInclude`, which the runtime unions into the scoped `documentIds`) is **not**
exercised by any prompt above, and intentionally so. It is reachable only when the
AgentThing has a **custom `ResolveDocumentSet` override** configured — the built-in
matcher never synthesizes the flag (the anti-pollution guarantee), so a plain user
prompt cannot trigger it. Validating it requires a custom-resolver fixture, not a smoke
prompt; that path is already gated deterministically offline by
`DocumentKnowledgeGoldSetTest#cross_cutting_alwaysInclude_unions_into_scope`. The §5
live evidence these prompts back is the **round-count** leg only (the RK&T
operating-safety restart prompt and the within-document trouble prompts).
