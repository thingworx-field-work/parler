# Document Index Enhancement

**Status:** Implemented. This document describes the conversion rules, corpus layout,
validation and runtime safeguards that keep document-knowledge turns answerable and bounded.
The corpus examples use the synthetic sample set in `dev_data/future_repo/` (built by
`test_scripts/pdf-conversion/synthetic/`).

---

## 1. Problem

A document question (for example a KBM coupling prompt about shaft misalignment and damage before
commissioning) can loop to the iteration limit without an answer when several defects combine:

| Id | Layer | Issue |
|----|-------|-------|
| **C1** | Conversion | Raw `pdftotext` delaminates tables, so table values are unreadable |
| **C2** | Conversion | Section chunks misaligned with their headings |
| **C3** | Conversion | Weak headings (`!`/`?` callouts, lowercase fragments) |
| **C4** | Corpus / deploy | A large compilation "master" manual indexed as co-equal with the sub-manuals it contains, polluting ranking |
| **R** | Runtime | No finalize after `REPETITION_BLOCKED`; the full tool schema attached every round under a tight provider rate gate |

Clean content (Part A) and correct ranking (Parts B, C) are both needed for the right chunks to
surface; the runtime safeguards (Part D) keep the loop bounded and the rounds cheap.

**Non-goals:** OCR; a general PDF SaaS pipeline; changing the provider rate gate.

---

## 5. Artifact map

| Artifact | Path |
|----------|------|
| Converter | `test_scripts/pdf-conversion/convert_pdf.py` |
| Validator | `test_scripts/pdf-conversion/validate_package.py` |
| Answerability tests | `test_scripts/pdf-conversion/test_answerability.py` |
| Converter README | `test_scripts/pdf-conversion/README.md` |
| Fixture root (live index) | `dev_data/future_repo/document-knowledge/` |
| Master (non-indexed) | `dev_data/future_repo/_fallback/document-knowledge/` |
| Sync | `uv run load-file-tree` (`test_scripts/load_file_tree.py`) uploads `dev_data/future_repo/` to `AIDocRepository` |
| FileRepository | Thing `AIDocRepository` — mirrors the `dev_data/future_repo/` layout |
| Agent document settings | `documentKnowledgeRepository`, `documentKnowledgeRootPath` (`/document-knowledge`) |
| Index TTL | `documentKnowledgeIndexTtlSeconds` (default 300) — lazy rebuild after deploy |
| Scorer | `parler-agent/.../DocumentKnowledgeSearchScorer.java` |
| Smoke prompts | `dev_data/future_repo/README.md` |

**Chunks to open after every re-extract:**

- KBM: `page-0006`, `page-0009`, `section-0010-5-commissioning`, `signal-0011`
- RK&T: one register `section` chunk per manual

## 5.0 Rule — the regenerated corpus goes with every converter change

Any change to `convert_pdf.py`, `validate_package.py`, or conversion heuristics must regenerate
every affected package under `dev_data/future_repo/document-knowledge/` and commit
`manifest.json`, `chunks/chunks.jsonl`, `markdown/manual.md`, and the `source/` layout in the same
change as the script. Stale fixtures mean the tests no longer describe the converter.

---

## 6. Design

### Part A — Conversion (`test_scripts/pdf-conversion/convert_pdf.py`)

#### A1. Table-aware extraction (C1)

- `pdfplumber` per page; `find_tables()`.
- **No tables:** `pdftotext` raw text + boilerplate strip.
- **Tables:** interleave prose blocks + Markdown tables by top-y; tables never pass through prose dedent.
- **Detection gate:** treat as table only when ≥2 columns, ≥2 rows, and ≥2 data rows (exclude header-only false positives). Single-column ruled lists remain prose.
- Threaded through `manual.md` and page chunks.

#### A2. Register-code sections for RK&T (C2)

Chapter-level spans from footer `(\d{2})\s*-\s*(\d{3})` + Part A TOC. Bounded ~4 KB per section chunk.

**Operating-manual deviation:** footer register prefixes recur across Part B / appendices and are unreliable for chapter boundaries on `rk-t-operating-manual-7318042`. Part A chapters **01–10** use printed TOC titles plus first body heading per chapter number (`part-a-section` chunks). The install spec uses dotted-leader TOC sections (constant `20-001` footer). Register-footer grouping remains available in the converter but is not used for these two live-index packages.

#### A3. Heading filter (C3)

Reject `!`/`?` endings, bare callouts, lowercase-fragment headings. Target: zero weak headings.

#### A4. KBM section boundaries (C2)

Heading-to-next-heading span; drop the section chunk if the boundary is not confident.

#### A5. Dependency

Pinned `pdfplumber` in `uv run` inline deps.

---

### Part B — Corpus layout (C4)

- `rk-t-turbogenerator-master-7318042` lives in `dev_data/future_repo/_fallback/document-knowledge/`.
- The live index root contains **the sub-manuals only**.
- The converter may still emit the master into `_fallback` for stress tests.
- Manifests may carry `documentRole: bundle`; the scorer down-ranks such documents (D3).

### Part B-live — Server deploy and index refresh

Local fixture changes do **not** change a live agent until deployed.

| Step | Action |
|------|--------|
| 1 | Sync packages from `dev_data/future_repo/document-knowledge/` to the server `AIDocRepository` at `/document-knowledge/{docId}/…` (same paths as manifests). |
| 2 | Remove the master tree from the server index path if present. |
| 3 | **Index refresh:** the agent rebuilds the document index on TTL expiry (`documentKnowledgeIndexTtlSeconds`, default 300 s) or AgentThing restart. There is no separate refresh service. |
| 4 | Verify via `GetAgentRuntimeSnapshot` / a smoke search. |

Sync procedure: upload `dev_data/future_repo/` to `AIDocRepository` with `uv run load-file-tree`.

---

### Part C — Validation and testing

#### C1. `validate_package.py` content checks

Table fidelity, heading sanity, section heading↔body agreement (the §7.2 checks), and the corpus
layout rule of Part B (master absent from the live root, present under `_fallback`).

#### C2. Answerability tests

**Python gold** (`test_scripts/pdf-conversion/test_answerability.py`):

| Intent | docId | chunk | must contain |
|--------|-------|-------|--------------|
| Misalignment values | `kbm-coupling-manual-7318042` | `page-0009` | Markdown row: `10` with `0,3` |
| Damage before operation | `kbm-coupling-manual-7318042` | `page-0006` or `section-0006-3-function` | `may not be put into operation` |

**Java scorer tests** assert, for KBM damage + misalignment query fixtures:

- a `kbm-coupling-manual-7318042` chunk outranks any `bundle`-role document;
- `page-0006` or the function section appears in top-k for the damage query;
- `page-0009` appears in top-k for the misalignment/tolerance query.

Content-only Python tests can pass on a corpus whose live ranking still fails, which is why both
layers exist.

---

### Part D — Runtime (`parler-agent`)

| Change | Location |
|--------|----------|
| D1 forced tool-none round | `AgentLoop.runInner` — `pendingForcedSummaryToolNoneRound`, `defsForRound` empty, `LlmChatRequest.copyWithToolPolicy` |
| D1 repetition intercept | `ConsecutiveIdenticalToolCallTracker.interceptThirdIdentical` |
| D1 dispatch | `AgentThing.executeToolCall` |
| D2 tool narrowing | `DocumentTurnToolNarrowing`; `AgentThing.getMergedToolDefinitions` |
| D3 scoring | `DocumentKnowledgeSearchScorer.scoreChunk` |

#### D1. Forced finalize after `REPETITION_BLOCKED`

When a turn accumulates ≥2 `REPETITION_BLOCKED`, the next round is a forced tool-none summary
round (the existing forced-summary path).

#### D2. Document-turn tool narrowing

When the active turn skill is `document_search` only **and** the turn has not yet invoked any
non-document tool, `AgentLoop` attaches a narrowed tool list: a small utility core
(`get_agent_skill`) plus the document built-ins. Narrowing never keys on user prompt wording.
**`AgentSettings.documentTurnToolNarrowingDisabled`** (default **false** — narrowing on) reverts to
the full tool list; mis-detection falls back to the full list, never a broken turn.

#### D3. Generic bundle-role down-rank

Chunks of manifests with `documentRole: bundle` receive a penalty — a generic role rule, not a
penalty keyed to a specific document id.

---

## 7.2 Content checks

Checked on regenerated artifacts (by `validate_package.py` and by opening the chunks):

- KBM `page-0006` or `section-0006-3-function` contains `may not be put into operation`.
- KBM `page-0009` Table 4 is a Markdown table with `10` / `0,3` / `0,7` (or equivalent row).
- KBM section chunks: `section-0010-5-commissioning` body does not start with §4.2.2 alignment text.
- No chunk heading `the manufacturer!` (or similar weak callout).
- RK&T register sections present with correct chapter titles.
- Master absent from the `document-knowledge/` index root; present only under `_fallback` if retained.
