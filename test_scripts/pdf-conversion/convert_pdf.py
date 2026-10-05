#!/usr/bin/env -S uv run --with pypdf --with pdfplumber --quiet python
"""Convert one source PDF into a Parler document-knowledge package.

Implements the manual/semi-automated procedure in
docs/operations/pdf-conversion-agent-playbook.md. This is the *mechanical*
backbone: it produces contract-valid manifest/markdown/chunks grounded on the
PDF text layer. Semantic-section and signal chunking is best-effort (TOC parse
+ keyword scan) and every conversion sets manualReview="required".

Page PNG rendering is intentionally NOT performed (operator decision for this
batch — see test_scripts/pdf-conversion/README.md). The package therefore omits
pages/*.png and records the skip in conversionQuality.warnings.

Usage:
    ./convert_pdf.py <docId>            # convert one configured doc
    ./convert_pdf.py --all             # convert every configured doc
    ./convert_pdf.py --list            # list configured docIds

Dependencies: pypdf (declared inline for `uv run`), plus poppler's
`pdftotext` / `pdfinfo` on PATH.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
PDF_SAMPLE_DIR = REPO_ROOT / "dev_data" / "pdf-sample"
# Final fixture repository. Source PDFs are read from dev_data/pdf-sample/, which
# is intentionally gitignored; provide those local inputs before re-running.
OUTPUT_ROOT = REPO_ROOT / "dev_data" / "future_repo" / "document-knowledge"
FALLBACK_OUTPUT_ROOT = REPO_ROOT / "dev_data" / "future_repo" / "_fallback" / "document-knowledge"
SOURCE_REPOSITORY = "AIDocRepository"
CONTRACT_VERSION = "0.1"
MASTER_DOC_ID = "rk-t-turbogenerator-master-7318042"
# Stable timestamp so repeated conversions are byte-identical (no random/now()).
CONVERTED_AT = "2026-06-25T00:00:00Z"

# ThingWorx FileRepository path uses the document-knowledge/ repository root.
TW_REPO_PREFIX = f"/Thingworx/FileRepositories/{SOURCE_REPOSITORY}/document-knowledge"

# Phase 1 live-index sub-manuals (master is Phase 3 Part B).
SUB_MANUAL_DOC_IDS = [
    "kbm-coupling-manual-7318042",
    "rk-t-install-spec-7318042",
    "rk-t-operating-manual-7318042",
]


@dataclass
class DocConfig:
    source_name: str
    doc_id: str
    title: str
    document_type: str
    asset_models: list[str]
    document_version: str = ""
    languages: list[str] = field(default_factory=lambda: ["en"])
    short_label: str = ""  # used in sourceLinks[].label
    manufacturers: list[str] = field(default_factory=list)
    extra_warnings: list[str] = field(default_factory=list)
    register_sections: bool = False  # A2: register-code sections (install-spec: unused)
    part_a_toc_sections: bool = False  # A2: operating-manual Part A chapters from body TOC
    fallback_package: bool = False  # Part B: emit to _fallback/document-knowledge (not live index)


# --- Per-file metadata (synthetic sample set; see synthetic/build_samples.py) ---
CONFIGS: dict[str, DocConfig] = {
    cfg.doc_id: cfg
    for cfg in [
        DocConfig(
            source_name="7.318.042 Manual_KBM.pdf",
            doc_id="kbm-coupling-manual-7318042",
            title="KBM Flexible Pin Type Coupling Operation Manual (KBN 31016, Edition g)",
            document_type="operating_manual",
            asset_models=["KBM FLEXO-N flexible pin type coupling (KBN 21011)"],
            document_version="Edition g",
            short_label="KBM coupling manual",
            manufacturers=["KBM"],
        ),
        DocConfig(
            source_name="7.318.042 Manual_RKT_Install_Spec.pdf",
            doc_id="rk-t-install-spec-7318042",
            title="RK&T Site Installation Requirements for Steam Turbines (7.318.042)",
            document_type="installation_spec",
            asset_models=["RK&T CB 24 GT4 steam turbine"],
            short_label="RK&T install spec",
            manufacturers=["RK&T"],
            register_sections=False,
        ),
        DocConfig(
            source_name="7.318.042 Manual_RKT_OperatingManual.pdf",
            doc_id="rk-t-operating-manual-7318042",
            title="RK&T Operating Manual — Turbo-generator Set CB 24 GT4 (7.318.042)",
            document_type="operating_manual",
            asset_models=["RK&T CB 24 GT4 turbo-generator set"],
            document_version="04.2019",
            short_label="RK&T operating manual",
            manufacturers=["RK&T"],
            part_a_toc_sections=True,
        ),
        DocConfig(
            source_name="7.318.042 Manual.pdf",
            doc_id="rk-t-turbogenerator-master-7318042",
            title="RK&T Turbo-generator Set CB 24 GT4 — Complete Manual (7.318.042)",
            document_type="technical_manual",
            asset_models=["RK&T CB 24 GT4 turbo-generator set"],
            document_version="04.2019",
            short_label="RK&T complete manual",
            manufacturers=["RK&T"],
            fallback_package=True,
            extra_warnings=[
                "This is a compilation bundle (cover, operating manual with drawings, installation "
                "requirements, coupling manual and a Folder 2 of accessory documentation), so it "
                "duplicates the three sub-manuals page for page and adds accessory topics that only "
                "the bundle contains.",
                "Corpus policy: treat the three sub-manuals (rk-t-operating-manual-7318042, "
                "rk-t-install-spec-7318042, kbm-coupling-manual-7318042) as the primary sources for "
                "their topics. This bundle stays a fallback / stress-test package; keeping it alongside "
                "the sub-manuals in the live index produces duplicate search hits and ambiguous "
                "citations.",
            ],
        ),
    ]
}

KEYWORD_SIGNALS = [
    ("alarm", "alarm"),
    ("fault", "fault"),
    ("trip", "trip"),
    ("shutdown", "shutdown"),
    ("malfunction", "malfunction"),
    ("trouble", "troubleshooting"),
    ("warning", "warning"),
    ("caution", "caution"),
    ("danger", "danger"),
    ("lubricat", "lubrication"),
    ("maintenance", "maintenance"),
    ("torque", "torque"),
    ("setpoint", "setpoint"),
    ("set point", "setpoint"),
    ("alignment", "alignment"),
    ("commissioning", "commissioning"),
    ("damage", "damage"),
]

DAMAGE_OPERATION_BAN_PHRASE = "may not be put into operation"

TROUBLESHOOTING_HEADING_HINTS = (
    "trouble",
    "fault",
    "failure",
    "emergency",
    "alarm",
    "cause",
    "elimination",
    "shutdown",
    "restart",
)

# Frozen acceptance fixture prompts — domainTerms overlap guard (§5.2).
_OVERFIT_FIXTURE_PROMPT_TOKENS = frozenset(
    token
    for phrase in (
        "RK&T operating manual trouble before restarting",
        "what trips the turbine and how do I bring it back online",
        "CarbaQ chiller high pressure shutdown",
    )
    for token in re.findall(r"[a-z0-9]{4,}", phrase.lower())
)


def infer_section_content_type(heading: str) -> str:
    low = (heading or "").lower()
    if any(hint in low for hint in TROUBLESHOOTING_HEADING_HINTS):
        return "troubleshooting"
    return "section"


def trouble_signals_for_heading(heading: str) -> list[dict]:
    low = (heading or "").lower()
    names = sorted({hint for hint in TROUBLESHOOTING_HEADING_HINTS if hint in low})
    return [{"kind": "topic", "name": name} for name in names]


# Single-token domainTerms dropped when they come from a multi-word heading phrase.
_DOMAIN_TERM_FRAGMENT_DENYLIST: dict[str, frozenset[str]] = {
    "back": frozenset({"back pressure", "back-pressure"}),
}


def derive_domain_terms(headings: list[str]) -> list[str]:
    terms: set[str] = set()
    for heading in headings:
        low = (heading or "").lower()
        tokens = re.findall(r"[a-z]{4,}", low)
        filtered = []
        for token in tokens:
            deny_phrases = _DOMAIN_TERM_FRAGMENT_DENYLIST.get(token)
            if deny_phrases and any(phrase in low for phrase in deny_phrases):
                continue
            filtered.append(token)
        terms.update(filtered)
    return sorted(terms)


def build_document_profile(cfg: DocConfig, chunks: list[dict]) -> dict:
    headings = [c.get("heading", "") for c in chunks if c.get("heading")]
    aliases: list[str] = []
    if cfg.short_label:
        aliases.append(cfg.short_label)
    manufacturers = list(cfg.manufacturers)
    document_kinds = [cfg.document_type] if cfg.document_type else []
    return {
        "aliases": aliases,
        "manufacturers": manufacturers,
        "assetModels": list(cfg.asset_models),
        "documentKinds": document_kinds,
        "domainTerms": derive_domain_terms(headings),
        "profileSource": {
            "aliases": "curated-identity",
            "manufacturers": "curated-config",
            "assetModels": "manifest-and-title",
            "documentKinds": "manifest-documentType",
            "domainTerms": "derived-from-toc-headings-and-section-headings",
        },
    }


def domain_terms_overlap_fixture_tokens(domain_terms: list[str], threshold: int = 6) -> list[str]:
    overlap = [t for t in domain_terms if t in _OVERFIT_FIXTURE_PROMPT_TOKENS]
    return overlap if len(overlap) >= threshold else []

def run(cmd: list[str]) -> str:
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\n{res.stderr}")
    return res.stdout


def pdf_page_count(pdf: Path) -> int:
    out = run(["pdfinfo", str(pdf)])
    for line in out.splitlines():
        if line.startswith("Pages:"):
            return int(line.split(":", 1)[1].strip())
    raise RuntimeError("could not read page count from pdfinfo")


def extract_pages(pdf: Path) -> list[str]:
    """Return per-page text in natural reading order (no -layout).

    Raw reading order is markedly more readable than -layout on prose manuals
    (real paragraphs and bullet lists instead of right-shifted columns); tables are
    recovered separately by pdfplumber. pdftotext separates pages with form-feed.
    """
    raw = run(["pdftotext", str(pdf), "-"])
    pages = raw.split("\f")
    if pages and pages[-1].strip() == "":
        pages = pages[:-1]
    return pages


def resolve_source_pdf(cfg: DocConfig) -> Path:
    """Prefer gitignored pdf-sample; fall back to committed fixture source."""
    primary = PDF_SAMPLE_DIR / cfg.source_name
    if primary.exists():
        return primary
    for root in (package_output_root(cfg), OUTPUT_ROOT):
        fallback = root / cfg.doc_id / "source" / "original.pdf"
        if fallback.exists():
            return fallback
    raise FileNotFoundError(primary)


def _table_qualifies(rows: list[list]) -> bool:
    if not rows or len(rows) < 2:
        return False
    max_cols = max(len(r) for r in rows)
    if max_cols < 2:
        return False
    data_rows = sum(
        1 for row in rows
        if sum(1 for c in row if c and str(c).strip()) >= 2
    )
    return data_rows >= 2


def rows_to_markdown_table(rows: list[list]) -> str:
    norm = [[(c or "").strip().replace("\n", " ") for c in row] for row in rows]
    if not norm:
        return ""
    ncols = max(len(r) for r in norm)
    norm = [r + [""] * (ncols - len(r)) for r in norm]
    norm = [r for r in norm if any(c for c in r)]
    if len(norm) < 2:
        return ""
    header, body = norm[0], norm[1:]
    lines = [
        "| " + " | ".join(header) + " |",
        "| " + " | ".join("---" for _ in header) + " |",
    ]
    for row in body:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)


def interleaved_page_markdown(page, boiler: set[str]) -> tuple[str, int, int]:
    """Interleave prose blocks and Markdown tables by top-y; return (markdown, detected, structured)."""
    import pdfplumber  # noqa: F811 — declared in shebang for uv run

    tables = sorted(
        [t for t in page.find_tables() if _table_qualifies(t.extract())],
        key=lambda t: t.bbox[1],
    )
    if not tables:
        raw = page.extract_text() or ""
        return clean_page_markdown(raw, boiler), 0, 0

    parts: list[str] = []
    y_cursor = 0.0
    page_height = page.height
    structured = 0
    for t in tables:
        top, bottom = t.bbox[1], t.bbox[3]
        if top > y_cursor + 2:
            crop = page.crop((0, y_cursor, page.width, top))
            prose = clean_page_markdown(crop.extract_text() or "", boiler)
            if prose.strip():
                parts.append(prose)
        md = rows_to_markdown_table(t.extract())
        if md:
            parts.append(md)
            structured += 1
        y_cursor = bottom
    if y_cursor < page_height - 2:
        crop = page.crop((0, y_cursor, page.width, page_height))
        prose = clean_page_markdown(crop.extract_text() or "", boiler)
        if prose.strip():
            parts.append(prose)
    return "\n\n".join(parts), len(tables), structured


def extract_pages_table_aware(pdf: Path, boiler: set[str]) -> tuple[list[str], int, int]:
    """Per-page markdown via pdfplumber table interleave with pdftotext fallback per page."""
    import pdfplumber

    fallback_pages = extract_pages(pdf)
    page_count = len(fallback_pages)
    out: list[str] = []
    tables_detected = 0
    tables_structured = 0
    with pdfplumber.open(pdf) as doc:
        for i, page in enumerate(doc.pages):
            md, detected, structured = interleaved_page_markdown(page, boiler)
            if not md.strip() and i < len(fallback_pages):
                md = clean_page_markdown(fallback_pages[i], boiler)
            out.append(md)
            tables_detected += detected
            tables_structured += structured
    if len(out) < page_count:
        out.extend(fallback_pages[len(out):])
    elif len(out) > page_count:
        out = out[:page_count]
    return out, tables_detected, tables_structured


def compute_boilerplate(pages: list[str]) -> set[str]:
    """Lines that recur on a large fraction of pages are letterhead/footer noise
    (e.g. a company letterhead, 'Page 5 / 16'). Strip them so prose and
    retrieval are not polluted. Conservative: short lines only, high recurrence."""
    from collections import Counter
    counts: Counter[str] = Counter()
    for text in pages:
        for ln in {l.strip() for l in text.splitlines() if l.strip()}:
            counts[ln] += 1
    n = len(pages)
    threshold = max(3, int(0.4 * n))
    boiler = set()
    for line, c in counts.items():
        if c >= threshold and len(line) <= 60:
            boiler.add(line)
    # Always treat 'Page X / Y' / 'Page X of Y' footers as boilerplate.
    return boiler


PAGE_FOOTER = re.compile(r"^Page\s+\d+\s*(/|of)\s*\d+\b", re.IGNORECASE)


def clean_page_markdown(text: str, boiler: set[str] | None = None) -> str:
    boiler = boiler or set()
    lines = [ln.rstrip() for ln in text.splitlines()]
    kept = [ln for ln in lines
            if ln.strip() not in boiler and not PAGE_FOOTER.match(ln.strip())]
    # Strip a shared left margin if any remains.
    indents = [len(ln) - len(ln.lstrip(" ")) for ln in kept if ln.strip()]
    margin = min(indents) if indents else 0
    if margin:
        kept = [ln[margin:] if ln.strip() else ln for ln in kept]
    # Collapse 3+ blank lines to a single blank; trim leading/trailing blanks.
    out: list[str] = []
    blanks = 0
    for ln in kept:
        if ln.strip() == "":
            blanks += 1
            if blanks <= 1:
                out.append("")
        else:
            blanks = 0
            out.append(ln)
    while out and out[0] == "":
        out.pop(0)
    while out and out[-1] == "":
        out.pop()
    return "\n".join(out)


def source_link(cfg: DocConfig, page: int, section_label: str) -> dict:
    href = f"{TW_REPO_PREFIX}/{cfg.doc_id}/source/original.pdf#page={page}"
    label_section = section_label or f"page {page}"
    return {
        "label": f"{cfg.short_label or cfg.title}, {label_section}, page {page}",
        "repository": SOURCE_REPOSITORY,
        "path": f"/document-knowledge/{cfg.doc_id}/source/original.pdf",
        "page": page,
        "href": href,
    }


def page_anchor_block(cfg: DocConfig, page: int) -> str:
    href = f"{TW_REPO_PREFIX}/{cfg.doc_id}/source/original.pdf#page={page}"
    return (
        f"<!-- page: {page} -->\n"
        f'<a id="page-{page}"></a>\n\n'
        f"---\n\n"
        f"Page source: [page {page}]({href})\n"
    )


def build_markdown_from_bodies(cfg: DocConfig, page_bodies: list[str]) -> str:
    src_href = f"{TW_REPO_PREFIX}/{cfg.doc_id}/source/original.pdf"
    parts = [f"# {cfg.title}", "", f"Source PDF: [original.pdf]({src_href})", ""]
    for i, body in enumerate(page_bodies, 1):
        parts.append(page_anchor_block(cfg, i))
        parts.append("")
        parts.append(body if body else "_(no extractable text on this page)_")
        parts.append("")
    return "\n".join(parts).rstrip() + "\n"


def build_markdown(cfg: DocConfig, pages: list[str], boiler: set[str]) -> str:
    src_href = f"{TW_REPO_PREFIX}/{cfg.doc_id}/source/original.pdf"
    parts = [f"# {cfg.title}", "", f"Source PDF: [original.pdf]({src_href})", ""]
    for i, text in enumerate(pages, 1):
        parts.append(page_anchor_block(cfg, i))
        parts.append("")
        body = clean_page_markdown(text, boiler)
        parts.append(body if body else "_(no extractable text on this page)_")
        parts.append("")
    return "\n".join(parts).rstrip() + "\n"


_LETTERHEAD = re.compile(r"\b(?:AG|GmbH|Ltd\.?|Inc\.?|LLC)\s*$|Page \d+ (of|/)")
# Generic structural labels that carry no topic on their own (e.g. "Part A").
_GENERIC_LABEL = re.compile(r"^(Part|Folder|Appendix|Pos\.?|Rev\.?)\s+[A-Za-z0-9.]+$", re.IGNORECASE)
# Pure register / numeric codes such as "00 - 007" or "7.318.042".
_NUMERIC_CODE = re.compile(r"^[\d.\-/\s]+$")
# Bulleted list lines and caption fragments are not section headings (A3).
_LIST_MARKER = re.compile(r"^[-*•]\s+|^\d+[.)]\s+[a-z]")
_CAPTION_FRAGMENT = re.compile(r"^(Picture|Table|Tabelle|Fig\.?|Figure)\s+\d", re.IGNORECASE)
# Markdown table header / data / separator rows are not section headings (A3).
_TABLE_ROW = re.compile(r"^\|")


def _is_markdown_table_line(s: str) -> bool:
    if not _TABLE_ROW.match(s):
        return False
    # Separator row: | --- | --- | or |:---:|
    if re.match(r"^\|\s*:?-{2,}", s):
        return True
    return s.count("|") >= 2


def is_weak_heading_candidate(s: str) -> bool:
    """True when a line must not become a chunk heading (shared with validate_package.py)."""
    if not s:
        return True
    if _is_markdown_table_line(s):
        return True
    # Reversed sidebar/footer stamp fragments (e.g. ".devreser", "-orp").
    if re.match(r"^[.\-][A-Za-z]", s):
        return True
    if s.endswith("!") or s.endswith("?"):
        return True
    if _LIST_MARKER.match(s):
        return True
    if _CAPTION_FRAGMENT.match(s):
        return True
    if s[0].islower():
        return True
    # Rotated margin stamp fragments (e.g. reversed "Dresden") — no vowels, short.
    if len(s) <= 10 and not re.search(r"[aeiouAEIOU]", s):
        return True
    return False


def first_heading(text: str, boiler: set[str] | None = None) -> str:
    """Pick the first line that reads like a real section heading.

    Rejects boilerplate/letterhead, sentence fragments ending in punctuation
    (e.g. "ozone."), bare structural labels without a topic (e.g. "Part A"), and
    numeric/register codes. Returns "" so the caller falls back to "Page NN".
    """
    boiler = boiler or set()
    for ln in text.splitlines():
        s = ln.strip()
        if not s or s in boiler:
            continue
        if _LETTERHEAD.search(s):
            continue
        # Sentence fragment / prose line — headings do not end in these.
        if s[-1] in ".,;:":
            continue
        # Callout fragments (C3): reject ! / ? endings, list markers, captions, lowercase-leading.
        if is_weak_heading_candidate(s):
            continue
        if _GENERIC_LABEL.match(s) or _NUMERIC_CODE.match(s):
            continue
        # Must contain a real word (>=3 letters) and be short enough to be a heading.
        if not re.search(r"[A-Za-z]{3,}", s):
            continue
        words = s.split()
        if 1 <= len(words) <= 9 and len(s) <= 70:
            return s
    return ""


TOC_LINE = re.compile(r"^(?P<title>.+?)\.{3,}\s*(?P<page>\d{1,3})\s*$")


def parse_toc(pages: list[str]) -> list[tuple[str, int]]:
    """Best-effort dotted-leader TOC parse. Returns (title, printed_page).

    Only trustworthy when the doc's printed page numbers are linear PDF pages
    (verified by the caller); otherwise the caller should discard the result.
    """
    entries: list[tuple[str, int]] = []
    toc_page_idx = None
    for idx, text in enumerate(pages[:6]):
        if re.search(r"table\s+of\s+cont", text, re.IGNORECASE):
            toc_page_idx = idx
            break
    if toc_page_idx is None:
        return entries
    # TOC may span a couple of pages.
    for text in pages[toc_page_idx : toc_page_idx + 3]:
        for ln in text.splitlines():
            m = TOC_LINE.match(ln.strip())
            if not m:
                continue
            title = re.sub(r"\s+", " ", m.group("title")).strip(" .")
            page = int(m.group("page"))
            if title and 1 <= len(title) <= 90:
                entries.append((title, page))
    return entries


def _normalize_heading_key(text: str) -> str:
    return re.sub(r"\s+", " ", text.strip().lower())


def _split_at_heading(page_md: str, title: str) -> str:
    """Return page markdown starting at the first line matching title (A4)."""
    key = _normalize_heading_key(title)
    lines = page_md.splitlines()
    for idx, ln in enumerate(lines):
        s = ln.strip()
        if not s:
            continue
        if _normalize_heading_key(s) == key:
            return "\n".join(lines[idx:]).strip()
        # TOC titles are often ALL CAPS while body uses mixed case (e.g. "5. COMMISSIONING" vs "5. Commissioning").
        if key in _normalize_heading_key(s) or _normalize_heading_key(s) in key:
            if re.search(r"\d", s) or len(s.split()) <= 6:
                return "\n".join(lines[idx:]).strip()
    return page_md


def _truncate_at_heading(page_md: str, next_title: str) -> str:
    """Return page markdown before next_title when both sections share a page."""
    key = _normalize_heading_key(next_title)
    lines = page_md.splitlines()
    for idx, ln in enumerate(lines):
        s = ln.strip()
        if not s:
            continue
        if idx == 0:
            continue
        if _normalize_heading_key(s) == key:
            return "\n".join(lines[:idx]).strip()
        if key in _normalize_heading_key(s) or _normalize_heading_key(s) in key:
            if re.search(r"\d", s) or len(s.split()) <= 6:
                return "\n".join(lines[:idx]).strip()
    return page_md


def _section_boundary_confident(title: str, body: str, start_page: int, end_page: int) -> bool:
    if start_page > end_page:
        return False
    if not body.strip():
        return False
    key = _normalize_heading_key(title)
    body_key = _normalize_heading_key(body[:400])
    if key in body_key:
        return True
    # Accept when the first non-empty line of the span plausibly matches the TOC title.
    for ln in body.splitlines():
        s = ln.strip()
        if not s or s.startswith("<!--") or s.startswith("<a id"):
            continue
        sk = _normalize_heading_key(s)
        if sk == key or key in sk or sk in key:
            return True
        break
    return start_page == end_page


def _section_body_from_span(
    page_bodies: list[str], start: int, end: int, title: str, next_title: str | None
) -> str:
    parts: list[str] = []
    for p in range(start, end + 1):
        text = page_bodies[p - 1]
        if p == start:
            text = _split_at_heading(text, title)
        if next_title and p == end:
            text = _truncate_at_heading(text, next_title)
        if text.strip():
            parts.append(text)
    return "\n\n".join(parts)


REGISTER_CODE = re.compile(r"(\d{2})\s*-\s*(\d{3})")
REGISTER_SECTION_MAX_CHARS = 4096
TOP_LEVEL_CHAPTER_LINE = re.compile(r"^(\d{1,2})\s+([A-Za-z].*)$")
TOC_REGISTER_LINE = re.compile(r"^(\d{1,2})\s+(.+?)\s+(\d{2})-(\d{3})\s*$")
REGISTER_FALLBACK_HEADING = re.compile(r"^Register chapter \d{2}$")


def extract_page_register(text: str) -> str | None:
    """Footer register code NN-NNN from a page text layer (A2)."""
    lines = text.splitlines()
    for ln in reversed(lines[-12:]):
        if re.search(r"Page\s+\d+", ln, re.I):
            m = REGISTER_CODE.search(ln)
            if m:
                return f"{m.group(1)}-{m.group(2)}"
    matches = REGISTER_CODE.findall(text)
    if not matches:
        return None
    a, b = matches[-1]
    return f"{a}-{b}"


def parse_part_a_chapter_titles(pages: list[str]) -> dict[str, str]:
    """Map chapter prefix NN -> title from Part A numbered TOC (best effort).

    Handles inline TOC lines such as ``1 General notes`` (not bare chapter numbers
    on separate lines). Subsection lines (``2.1 General 02-001``) are skipped.
    """
    titles: dict[str, str] = {}
    in_part_a = False
    for page_text in pages[:40]:
        if re.search(r"Part A", page_text, re.I) and re.search(r"Operating", page_text, re.I):
            in_part_a = True
        if not in_part_a and not titles:
            # Install-spec and front-matter: still harvest top-level numbered titles.
            pass
        for ln in page_text.splitlines():
            s = ln.strip()
            if re.match(r"^\d+\.\d", s):
                continue
            m = TOC_REGISTER_LINE.match(s)
            if m:
                ch = m.group(1).zfill(2)
                title = re.sub(r"\s+", " ", m.group(2)).strip(" .")
                if len(title) >= 4 and ch not in titles:
                    titles[ch] = title
                continue
            m = TOP_LEVEL_CHAPTER_LINE.match(s)
            if not m:
                continue
            ch = m.group(1).zfill(2)
            title = re.sub(r"\s+\d{2}-\d{3}\s*$", "", m.group(2))
            title = re.sub(r"\s+", " ", title).strip(" .")
            if len(title) < 4 or REGISTER_CODE.search(title):
                continue
            if re.search(r"^Page\b", title, re.I):
                continue
            if ch not in titles:
                titles[ch] = title
    return titles


def build_register_chapter_spans(
    page_registers: list[str | None],
) -> list[tuple[str, str, list[int]]]:
    """Group all pages sharing a register chapter prefix NN (non-contiguous OK)."""
    by_ch: dict[str, list[int]] = {}
    sample_reg: dict[str, str] = {}
    for i, reg in enumerate(page_registers, 1):
        if not reg:
            continue
        ch = reg.split("-", 1)[0]
        by_ch.setdefault(ch, []).append(i)
        sample_reg.setdefault(ch, reg)
    spans: list[tuple[str, str, list[int]]] = []
    for ch in sorted(by_ch.keys()):
        pages = sorted(by_ch[ch])
        spans.append((ch, sample_reg[ch], pages))
    return spans


def _section_body_from_pages(
    page_bodies: list[str],
    page_nums: list[int],
    title: str,
) -> str:
    parts: list[str] = []
    for p in page_nums:
        if p < 1 or p > len(page_bodies):
            continue
        text = page_bodies[p - 1]
        if p == page_nums[0]:
            text = _split_at_heading(text, title)
        if text.strip():
            parts.append(text)
    return "\n\n".join(parts)


def _truncate_section_body(body: str, max_chars: int) -> str:
    if len(body) <= max_chars:
        return body
    return body[: max_chars - 40].rstrip() + "\n\n_(section truncated at extraction budget)_\n"


def build_register_section_chunks(
    cfg: DocConfig,
    page_bodies: list[str],
    boiler: set[str],
) -> tuple[list[dict], int]:
    """A2: chapter-level register-code section chunks for register-coded manuals."""
    page_registers = [extract_page_register(p) for p in page_bodies]
    chapter_titles = parse_part_a_chapter_titles(page_bodies)
    spans = build_register_chapter_spans(page_registers)
    chunks: list[dict] = []
    for chapter, sample_reg, page_nums in spans:
        if not chapter or not page_nums:
            continue
        start, end = page_nums[0], page_nums[-1]
        title = chapter_titles.get(chapter) or f"Register chapter {chapter}"
        body = _truncate_section_body(
            _section_body_from_pages(page_bodies, page_nums, title),
            REGISTER_SECTION_MAX_CHARS,
        )
        if not body.strip():
            continue
        slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:50] or f"ch-{chapter}"
        chunk_id = f"section-{start:04d}-register-{chapter}-{slug}"
        md = f"## {title}\n\nRegister: {sample_reg or chapter}\n\n{body}\n"
        chunks.append(make_chunk(
            cfg, chunk_id, "section", title, [title, f"Register {sample_reg or chapter}"],
            start, end, f"{title} (register chapter {chapter}).", md,
            _keyword_tags(body), [], title))
    return chunks, len(chunks)


def detect_part_a_content_start(page_bodies: list[str]) -> int | None:
    """First PDF page where Part A chapter 1 body text begins (not the TOC listing)."""
    for i, text in enumerate(page_bodies, 1):
        if "1.1 Preface" in text and re.search(r"^\s*1\s+General notes\s*$", text, re.M):
            return i
    return None


def detect_part_a_chapter_starts(
    page_bodies: list[str], min_page: int,
) -> dict[str, tuple[int, str]]:
    """Map chapter prefix NN -> (start_page, heading_line) from Part A body pages."""
    starts: dict[str, tuple[int, str]] = {}
    for i in range(min_page, len(page_bodies) + 1):
        text = page_bodies[i - 1]
        for ln in text.splitlines():
            s = ln.strip()
            if not s or len(s) < 4:
                continue
            if re.match(r"^\d+\.\d", s):
                continue
            m = re.match(r"^(\d{1,2})\s+([A-Za-z].*)$", s)
            if not m:
                continue
            ch_num = int(m.group(1))
            if ch_num < 1 or ch_num > 10:
                continue
            tail = m.group(2)
            if re.search(r"Part [AB]|Folder|Page\b", tail, re.I):
                continue
            key = f"{ch_num:02d}"
            if key not in starts:
                starts[key] = (i, s)
            break
    return starts


def build_part_a_toc_section_chunks(
    cfg: DocConfig,
    page_bodies: list[str],
    boiler: set[str],
) -> tuple[list[dict], int]:
    """A2: Part A chapter sections from printed TOC titles + body start pages.

    Register footer codes are unreliable for boundaries on this manual (prefixes
    recur in Part B / appendices), so chapter spans are derived from the Part A
    TOC listing plus the first body heading for each chapter number.
    """
    content_start = detect_part_a_content_start(page_bodies)
    if not content_start:
        return [], 0
    chapter_titles = parse_part_a_chapter_titles(page_bodies)
    raw_starts = detect_part_a_chapter_starts(page_bodies, content_start)
    if len(raw_starts) < 3:
        return [], 0

    ordered = sorted(
        [(ch, raw_starts[ch][0], raw_starts[ch][1]) for ch in raw_starts],
        key=lambda x: x[1],
    )
    chunks: list[dict] = []
    n = len(page_bodies)
    for idx, (chapter, start, start_line) in enumerate(ordered):
        if idx + 1 < len(ordered):
            end = ordered[idx + 1][1] - 1
            next_title = chapter_titles.get(ordered[idx + 1][0]) or ordered[idx + 1][2]
        else:
            end = n
            next_title = None
        title = chapter_titles.get(chapter) or start_line
        split_title = start_line if start_line else title
        body = _truncate_section_body(
            _section_body_from_span(page_bodies, start, end, split_title, next_title),
            REGISTER_SECTION_MAX_CHARS,
        )
        if not body.strip():
            continue
        slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:50] or f"ch-{chapter}"
        chunk_id = f"section-{start:04d}-part-a-{chapter}-{slug}"
        md = f"## {title}\n\n{body}\n"
        content_type = infer_section_content_type(title)
        signals = trouble_signals_for_heading(title) if content_type == "troubleshooting" else []
        chunks.append(make_chunk(
            cfg, chunk_id, content_type, title, [title],
            start, end, f"{title} (Part A chapter {chapter}).", md,
            _keyword_tags(body), signals, title))
    return chunks, len(chunks)


def make_chunk(cfg: DocConfig, chunk_id: str, content_type: str, heading: str,
               section_path: list[str], page_start: int, page_end: int,
               summary: str, markdown: str, tags: list[str],
               signals: list[dict], section_label: str) -> dict:
    return {
        "contractVersion": CONTRACT_VERSION,
        "docId": cfg.doc_id,
        "chunkId": chunk_id,
        "contentType": content_type,
        "heading": heading,
        "sectionPath": section_path,
        "pageStart": page_start,
        "pageEnd": page_end,
        "tags": tags,
        "signals": signals,
        "summary": summary,
        "markdown": markdown,
        "sourceLinks": [source_link(cfg, page_start, section_label)],
    }


def _damage_ban_section_body(body: str) -> str | None:
    if DAMAGE_OPERATION_BAN_PHRASE not in body.lower():
        return None
    parts = re.split(
        r"\n(?=\d+\.\s+(?:Function|Assembly|Commissioning)\b)",
        body,
        maxsplit=1,
    )
    prefix = parts[0].strip() if parts else body.strip()
    if DAMAGE_OPERATION_BAN_PHRASE not in prefix.lower():
        prefix = body.strip()
    return prefix or None


def build_damage_ban_section_chunks(cfg: DocConfig, page_bodies: list[str]) -> list[dict]:
    chunks: list[dict] = []
    for i, body in enumerate(page_bodies, 1):
        section_body = _damage_ban_section_body(body)
        if not section_body:
            continue
        heading = "Damage before operation"
        md = f"## {heading}\n\n{section_body}\n"
        chunks.append(make_chunk(
            cfg,
            f"section-{i:04d}-damage-before-operation",
            "troubleshooting",
            heading,
            [heading],
            i,
            i,
            f"{heading}: possible damage before commissioning.",
            md,
            ["damage", "commissioning", "damage-before-operation"],
            [
                {"kind": "topic", "name": "damage-before-operation"},
                {"kind": "symptom", "name": "coupling damaged"},
            ],
            heading,
        ))
    return chunks


def build_chunks(cfg: DocConfig, page_bodies: list[str], boiler: set[str]) -> tuple[list[dict], dict]:
    chunks: list[dict] = []
    n = len(page_bodies)

    # 1) Page chunks (one per page) — recall floor.
    for i, body in enumerate(page_bodies, 1):
        heading = first_heading(body, boiler) or f"Page {i:02d}"
        md = page_anchor_block(cfg, i) + "\n\n" + (body if body else "_(no extractable text)_") + "\n"
        chunks.append(make_chunk(
            cfg, f"page-{i:04d}", "page", heading, ["Full manual page"], i, i,
            f"Full converted markdown for page {i}.", md, _page_chunk_tags(body),
            _damage_before_operation_signals(body),
            "Full manual page"))

    chunks.extend(build_damage_ban_section_chunks(cfg, page_bodies))

    # 2) Semantic-section chunks from a linear dotted-leader TOC (best effort).
    #    A4: heading-to-next-heading span; drop when boundary is not confident.
    raw_pages = page_bodies  # headings for TOC parse still use cleaned text
    toc = parse_toc(raw_pages)
    toc_used = False
    register_sections_emitted = 0
    part_a_sections_emitted = 0
    if cfg.part_a_toc_sections:
        pa_chunks, part_a_sections_emitted = build_part_a_toc_section_chunks(cfg, page_bodies, boiler)
        chunks.extend(pa_chunks)
    elif cfg.register_sections:
        reg_chunks, register_sections_emitted = build_register_section_chunks(cfg, page_bodies, boiler)
        chunks.extend(reg_chunks)
    elif toc and all(1 <= p <= n for _, p in toc) and len(toc) >= 3:
        ascending = all(toc[i][1] <= toc[i + 1][1] for i in range(len(toc) - 1))
        if ascending:
            toc_used = True
            sorted_toc = sorted(toc, key=lambda x: x[1])
            for idx, (title, start) in enumerate(sorted_toc):
                if idx + 1 < len(sorted_toc):
                    next_start = sorted_toc[idx + 1][1]
                    end = (next_start - 1) if next_start > start else start
                else:
                    end = n
                next_title = sorted_toc[idx + 1][0] if idx + 1 < len(sorted_toc) else None
                body = _section_body_from_span(page_bodies, start, end, title, next_title)
                if not _section_boundary_confident(title, body, start, end):
                    continue
                slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:60] or f"section-{start}"
                md = f"## {title}\n\n{body}\n"
                chunks.append(make_chunk(
                    cfg, f"section-{start:04d}-{slug}", "section", title, [title],
                    start, end, f"{title} (document section).", md,
                    _keyword_tags(body), [], title))

    # 3) Keyword-targeted signal chunks (capped) for procedure/fault content.
    #    Under a fixed budget, prefer the densest operational pages: rank by
    #    number of distinct signals (desc), page number (asc) as tiebreaker — so
    #    a high-signal maintenance page late in the manual is not dropped in favor
    #    of a front-matter safety page. Emit the selected set in page order.
    signal_pages = _scan_signal_pages(page_bodies)
    cap = 30
    selected = sorted(signal_pages, key=lambda x: (-len(x[1]), x[0]))[:cap]
    selected.sort(key=lambda x: x[0])
    for page, hits in selected:
        text = page_bodies[page - 1]
        heading = first_heading(text, boiler) or f"Operational notes (page {page})"
        body = text
        md = f"## {heading}\n\n{body}\n"
        signals = [{"kind": "topic", "name": h} for h in sorted(hits)]
        chunks.append(make_chunk(
            cfg, f"signal-{page:04d}", "troubleshooting", heading,
            ["Operational / maintenance content"], page, page,
            f"High-signal operational content on page {page}: {', '.join(sorted(hits))}.",
            md, sorted(hits), signals, "Operational / maintenance content"))

    stats = {
        "tocSectionsEmitted": sum(1 for c in chunks if c["contentType"] == "section"),
        "tocUsed": toc_used,
        "registerSectionsEmitted": register_sections_emitted,
        "partASectionsEmitted": part_a_sections_emitted,
        "signalChunksEmitted": min(len(signal_pages), cap),
        "signalPagesDetected": len(signal_pages),
    }
    return chunks, stats


def _keyword_tags(text: str) -> list[str]:
    low = text.lower()
    tags = sorted({tag for kw, tag in KEYWORD_SIGNALS if kw in low})
    return tags[:8]


def _page_chunk_tags(body: str) -> list[str]:
    tags = set(_keyword_tags(body))
    tags.add("full-page")
    if DAMAGE_OPERATION_BAN_PHRASE in body.lower():
        tags.add("damage-before-operation")
    return sorted(tags)[:10]


def _damage_before_operation_signals(body: str) -> list[dict]:
    if DAMAGE_OPERATION_BAN_PHRASE in body.lower():
        return [{"kind": "topic", "name": "damage-before-operation"}]
    return []


def _scan_signal_pages(pages: list[str]) -> list[tuple[int, set[str]]]:
    result: list[tuple[int, set[str]]] = []
    for i, text in enumerate(pages, 1):
        low = text.lower()
        hits = {tag for kw, tag in KEYWORD_SIGNALS if kw in low}
        # Require at least two distinct high-signal topics to avoid noise.
        if len(hits) >= 2:
            result.append((i, hits))
    return result


def sha256_of(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for block in iter(lambda: fh.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def package_output_root(cfg: DocConfig) -> Path:
    if cfg.fallback_package:
        return FALLBACK_OUTPUT_ROOT
    return OUTPUT_ROOT


def convert(cfg: DocConfig) -> dict:
    src = resolve_source_pdf(cfg)
    if not src.exists():
        raise FileNotFoundError(src)
    pkg = package_output_root(cfg) / cfg.doc_id
    for sub in ("source", "markdown", "chunks", "figures"):
        (pkg / sub).mkdir(parents=True, exist_ok=True)
    dst_pdf = pkg / "source" / "original.pdf"
    if src.resolve() != dst_pdf.resolve():
        shutil.copyfile(src, dst_pdf)
    (pkg / "figures" / ".gitkeep").touch()

    page_count = pdf_page_count(src)
    boiler = compute_boilerplate(extract_pages(src))
    page_bodies, tables_detected, tables_structured = extract_pages_table_aware(src, boiler)
    if len(page_bodies) != page_count:
        if len(page_bodies) < page_count:
            page_bodies = page_bodies + [""] * (page_count - len(page_bodies))
        else:
            page_bodies = page_bodies[:page_count]

    md = build_markdown_from_bodies(cfg, page_bodies)
    (pkg / "markdown" / "manual.md").write_text(md, encoding="utf-8")

    chunks, stats = build_chunks(cfg, page_bodies, boiler)
    with (pkg / "chunks" / "chunks.jsonl").open("w", encoding="utf-8") as fh:
        for c in chunks:
            fh.write(json.dumps(c, ensure_ascii=False) + "\n")

    text_layer_present = sum(1 for p in page_bodies if p.strip()) >= max(1, page_count // 2)
    warnings = [
        "Generated by test_scripts/pdf-conversion/convert_pdf.py (pdfplumber table interleave "
        "+ pdftotext prose fallback); suitable for review-fixture quality, not production publishing.",
        "Page PNG rendering was intentionally skipped for this batch (operator decision); "
        "no pages/ directory is emitted.",
        "Semantic-section and troubleshooting chunks are heuristic (TOC parse + keyword "
        "scan). Manual review of section boundaries and signal pages is required.",
    ]
    if stats["signalPagesDetected"] > stats["signalChunksEmitted"]:
        warnings.append(
            f"Keyword signal scan flagged {stats['signalPagesDetected']} pages but only the "
            f"top {stats['signalChunksEmitted']} by signal richness (distinct-signal count, "
            f"then page order) became dedicated signal chunks (cap). The remaining "
            f"{stats['signalPagesDetected'] - stats['signalChunksEmitted']} flagged pages are "
            f"still covered by their page chunks but have no targeted chunk.")
    if not stats["tocUsed"]:
        warnings.append(
            "No linear dotted-leader TOC was usable for section chunks; the printed page "
            "numbering (e.g. a '00-007' register code) does not map linearly to PDF pages, so "
            "section retrieval leans on page chunks plus keyword signal chunks.")
    if not text_layer_present:
        warnings.append("Text layer appears sparse on many pages; OCR may be needed for full fidelity.")
    warnings.extend(cfg.extra_warnings)

    chunk_strategies = ["page"]
    if stats["tocUsed"]:
        chunk_strategies.append("semantic-section")
    if stats.get("registerSectionsEmitted", 0):
        chunk_strategies.append("register-section")
    if stats.get("partASectionsEmitted", 0):
        chunk_strategies.append("part-a-section")
    if stats["signalChunksEmitted"]:
        chunk_strategies.append("keyword-signal")

    manifest = {
        "contractVersion": CONTRACT_VERSION,
        "docId": cfg.doc_id,
        "title": cfg.title,
        "sourcePath": "source/original.pdf",
        "markdownPath": "markdown/manual.md",
        "chunksPath": "chunks/chunks.jsonl",
        "pageCount": page_count,
        "sourceSha256": sha256_of(dst_pdf),
        "convertedAt": CONVERTED_AT,
        "sourceFileName": cfg.source_name,
        "documentVersion": cfg.document_version,
        "documentType": cfg.document_type,
        "assetModels": cfg.asset_models,
        "languages": cfg.languages,
    }
    if cfg.fallback_package:
        manifest["documentRole"] = "bundle"
    manifest["documentProfile"] = build_document_profile(cfg, chunks)
    manifest.update({
        "artifacts": {
            "renderedPagesPattern": "pages/page-{page:04d}.png",
            "figuresPath": "figures/",
        },
        "conversionQuality": {
            "textLayer": "present" if text_layer_present else "sparse",
            "ocrUsed": False,
            "tablesDetected": tables_detected,
            "tablesStructured": tables_structured,
            "pageAnchorsGenerated": page_count,
            "manualReview": "required",
            "warnings": warnings,
        },
        "chunkCount": len(chunks),
        "chunkStrategies": chunk_strategies,
        "sourceRepository": SOURCE_REPOSITORY,
        "sourceRepositoryPath": f"/document-knowledge/{cfg.doc_id}/source/original.pdf",
        "sourceHref": f"{TW_REPO_PREFIX}/{cfg.doc_id}/source/original.pdf",
    })
    (pkg / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    return {
        "docId": cfg.doc_id,
        "pkg": str(pkg.relative_to(REPO_ROOT)),
        "pageCount": page_count,
        "chunkCount": len(chunks),
        "strategies": chunk_strategies,
        **stats,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("doc_id", nargs="?", help="configured docId to convert")
    ap.add_argument("--all", action="store_true", help="convert every configured doc")
    ap.add_argument("--sub-manuals", action="store_true",
                    help="convert the three live-index sub-manuals only (Phase 1 default)")
    ap.add_argument("--list", action="store_true", help="list configured docIds")
    args = ap.parse_args()

    if args.list:
        for did, cfg in CONFIGS.items():
            print(f"{did}\t<- {cfg.source_name}")
        return 0

    if args.all:
        targets = list(CONFIGS.values())
    elif args.sub_manuals:
        targets = [CONFIGS[did] for did in SUB_MANUAL_DOC_IDS]
    elif args.doc_id:
        if args.doc_id not in CONFIGS:
            print(f"unknown docId: {args.doc_id}", file=sys.stderr)
            return 2
        targets = [CONFIGS[args.doc_id]]
    else:
        ap.print_help()
        return 2

    for cfg in targets:
        result = convert(cfg)
        print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
