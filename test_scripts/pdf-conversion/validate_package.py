#!/usr/bin/env -S uv run --quiet python
"""Validate a document-knowledge package against the playbook §9 checks.

Usage:
    ./validate_package.py <path-to-package-dir>
    ./validate_package.py --all      # validate every package under document-knowledge
    ./validate_package.py --all-fallback  # validate _fallback/document-knowledge packages

Exit code is non-zero if any check fails. Designed to be run after convert_pdf.py.
"""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
DOCUMENT_KNOWLEDGE_ROOT = REPO_ROOT / "dev_data" / "future_repo" / "document-knowledge"
FALLBACK_KNOWLEDGE_ROOT = REPO_ROOT / "dev_data" / "future_repo" / "_fallback" / "document-knowledge"
MASTER_DOC_ID = "rk-t-turbogenerator-master-7318042"
LIVE_SUB_MANUAL_DOC_IDS = (
    "kbm-coupling-manual-7318042",
    "rk-t-install-spec-7318042",
    "rk-t-operating-manual-7318042",
)

REQUIRED_CHUNK_KEYS = [
    "contractVersion", "docId", "chunkId", "contentType", "heading",
    "sectionPath", "pageStart", "pageEnd", "summary", "markdown", "sourceLinks",
]

KBM_DOC_ID = "kbm-coupling-manual-7318042"
LIVE_INDEX_SUB_MANUALS = {
    KBM_DOC_ID,
    "rk-t-install-spec-7318042",
    "rk-t-operating-manual-7318042",
}
RKT_REGISTER_DOC_IDS: set[str] = set()
RKT_OPS_ID = "rk-t-operating-manual-7318042"
RKT_INSTALL_SPEC_ID = "rk-t-install-spec-7318042"
MAIN_PART_A_CHAPTERS = tuple(f"{i:02d}" for i in range(1, 9))
REGISTER_FALLBACK_HEADING = re.compile(r"^Register chapter \d{2}$")
_PART_B_DRAWINGS = re.compile(r"Part B Drawings", re.IGNORECASE)
_MAX_PART_A_CHAPTER_SPAN = 40

# Keep aligned with convert_pdf.is_weak_heading_candidate (A3 class gate).
_LIST_MARKER = re.compile(r"^[-*•]\s+|^\d+[.)]\s+[a-z]")
_CAPTION_FRAGMENT = re.compile(r"^(Picture|Table|Tabelle|Fig\.?|Figure)\s+\d", re.IGNORECASE)
_TABLE_ROW = re.compile(r"^\|")


def _is_markdown_table_line(s: str) -> bool:
    if not _TABLE_ROW.match(s):
        return False
    if re.match(r"^\|\s*:?-{2,}", s):
        return True
    return s.count("|") >= 2


def is_weak_heading(heading: str) -> bool:
    s = (heading or "").strip()
    if not s:
        return False
    if _is_markdown_table_line(s):
        return True
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
    if len(s) <= 10 and not re.search(r"[aeiouAEIOU]", s):
        return True
    return False


def _load_chunks(pkg: Path) -> list[dict]:
    chunks: list[dict] = []
    for line in (pkg / "chunks" / "chunks.jsonl").read_text(encoding="utf-8").splitlines():
        if line.strip():
            chunks.append(json.loads(line))
    return chunks


def _chunk_by_id(chunks: list[dict], chunk_id: str) -> dict | None:
    for c in chunks:
        if c.get("chunkId") == chunk_id:
            return c
    return None


def validate_content(pkg: Path, doc_id: str, chunks: list[dict]) -> list[str]:
    """C1/C2 content checks from doc-index-enhance §7.2."""
    errors: list[str] = []

    if doc_id in LIVE_INDEX_SUB_MANUALS:
        for c in chunks:
            if c.get("contentType") not in ("page", "troubleshooting"):
                continue
            h = (c.get("heading") or "").strip()
            if is_weak_heading(h):
                errors.append(f"weak callout heading in chunk {c.get('chunkId')}: {h!r}")

    if doc_id in RKT_REGISTER_DOC_IDS:
        register_sections = [
            c for c in chunks
            if c.get("contentType") == "section" and "-register-" in (c.get("chunkId") or "")
        ]
        if not register_sections:
            errors.append(f"{doc_id}: expected at least one register-section chunk (A2)")
        chapter_ids = []
        for c in register_sections:
            m = re.search(r"-register-(\d{2})-", c.get("chunkId") or "")
            if not m:
                continue
            ch = m.group(1)
            chapter_ids.append(ch)
            if ch in MAIN_PART_A_CHAPTERS:
                heading = (c.get("heading") or "").strip()
                if REGISTER_FALLBACK_HEADING.match(heading):
                    errors.append(
                        f"{doc_id}: register section {c.get('chunkId')} "
                        f"uses fallback heading {heading!r}; expected Part A chapter title"
                    )
        if len(chapter_ids) != len(set(chapter_ids)):
            errors.append(f"{doc_id}: duplicate register-section chapter labels detected")

    if doc_id == RKT_OPS_ID:
        part_a_sections = [
            c for c in chunks
            if c.get("contentType") in ("section", "troubleshooting")
            and "-part-a-" in (c.get("chunkId") or "")
        ]
        if len(part_a_sections) < len(MAIN_PART_A_CHAPTERS):
            errors.append(
                f"{doc_id}: expected at least {len(MAIN_PART_A_CHAPTERS)} Part A section chunks"
            )
        part_a_main = sorted(
            [
                c for c in part_a_sections
                if re.search(r"-part-a-(\d{2})-", c.get("chunkId") or "")
                and re.search(r"-part-a-(\d{2})-", c.get("chunkId") or "").group(1) in MAIN_PART_A_CHAPTERS
            ],
            key=lambda c: c.get("pageStart") or 0,
        )
        prev_end = 0
        for c in part_a_main:
            start = int(c.get("pageStart") or 0)
            end = int(c.get("pageEnd") or 0)
            heading = (c.get("heading") or "").strip()
            if REGISTER_FALLBACK_HEADING.match(heading):
                errors.append(
                    f"{doc_id}: Part A section {c.get('chunkId')} uses fallback heading {heading!r}"
                )
            if start <= prev_end:
                errors.append(
                    f"{doc_id}: Part A sections overlap or are out of order "
                    f"({c.get('chunkId')} starts at {start}, previous ended at {prev_end})"
                )
            span = end - start + 1
            if span > _MAX_PART_A_CHAPTER_SPAN:
                errors.append(
                    f"{doc_id}: Part A section {c.get('chunkId')} span {span} pages exceeds "
                    f"{_MAX_PART_A_CHAPTER_SPAN}"
                )
            body_head = (c.get("markdown") or "")[:800]
            if _PART_B_DRAWINGS.search(body_head):
                errors.append(
                    f"{doc_id}: Part A section {c.get('chunkId')} ({heading!r}) "
                    f"body opens with Part B material"
                )
            if heading.lower().startswith("general") and "preface" not in body_head.lower():
                errors.append(
                    f"{doc_id}: General-notes section {c.get('chunkId')} missing Preface lead-in"
                )
            prev_end = end

        trouble = _chunk_by_id(chunks, "section-0080-part-a-08-trouble-causes-and-their-elimination")
        if trouble is None:
            errors.append(f"{doc_id}: missing Part A trouble-causes/elimination section chunk")
        else:
            if trouble.get("contentType") != "troubleshooting":
                errors.append(
                    f"{doc_id}: trouble section must be contentType troubleshooting, "
                    f"got {trouble.get('contentType')!r}"
                )
            if not trouble.get("signals"):
                errors.append(f"{doc_id}: trouble section must carry heading-derived signals/tags")

    if doc_id == RKT_INSTALL_SPEC_ID:
        toc_sections = [
            c for c in chunks
            if c.get("contentType") == "section"
            and "-register-" not in (c.get("chunkId") or "")
        ]
        if len(toc_sections) < 3:
            errors.append(f"{doc_id}: expected multiple TOC semantic-section chunks")
        titled = [
            c for c in toc_sections
            if (c.get("heading") or "").strip()
            and not REGISTER_FALLBACK_HEADING.match((c.get("heading") or "").strip())
        ]
        if len(titled) < 3:
            errors.append(f"{doc_id}: expected titled TOC semantic-section chunks")

    if doc_id != KBM_DOC_ID:
        return errors

    def body(cid: str) -> str:
        c = _chunk_by_id(chunks, cid)
        return (c.get("markdown") or "") if c else ""

    damage = body("page-0006") + body("section-0006-3-function")
    if "may not be put into operation" not in damage.lower():
        errors.append("KBM page-0006 / section-0006-3-function missing damage ban sentence")

    p9 = body("page-0009")
    if "|" not in p9 or "10" not in p9 or "0,3" not in p9:
        errors.append("KBM page-0009 missing Markdown Table 4 row with nominal size 10 / Kr 0,3")
    elif "0,7" not in p9:
        errors.append("KBM page-0009 Table 4 missing Kw' 0,7 tolerance row")

    comm = body("section-0010-5-commissioning")
    if comm and "4.2.2. aligning by means of a dial gauge" in comm.lower()[:400]:
        errors.append("KBM section-0010-5-commissioning body still starts with §4.2.2 alignment text")

    return errors


def validate_document_profile(manifest: dict) -> list[str]:
    errors: list[str] = []
    profile = manifest.get("documentProfile")
    if not isinstance(profile, dict):
        return errors
    domain_terms = profile.get("domainTerms") or []
    if not isinstance(domain_terms, list):
        errors.append("documentProfile.domainTerms must be an array when documentProfile is present")
        return errors
    overlap = [t for t in domain_terms if isinstance(t, str) and len(t) >= 4
               and t.lower() in {
                   "manual", "operating", "trouble", "restarting", "turbine", "online",
                   "trips", "bring", "chiller", "pressure", "shutdown",
               }]
    # Warn-level threshold: implausible prompt-only seeding when many short fixture tokens appear.
    fixture_like = [t for t in domain_terms if isinstance(t, str) and t.lower() in {
        "restarting", "trips", "online", "shutdown", "pressure",
    }]
    if len(fixture_like) >= 4:
        errors.append(
            "documentProfile.domainTerms overlaps too many acceptance-fixture tokens; "
            f"suspect prompt seeding: {fixture_like}"
        )
    if overlap and len(overlap) >= 8:
        errors.append(
            f"documentProfile.domainTerms has implausible overlap with query tokens: {overlap}"
        )
    return errors


def validate_live_index_layout() -> list[str]:
    """Part B: live index root holds sub-manuals only; master lives under _fallback."""
    errors: list[str] = []
    if (DOCUMENT_KNOWLEDGE_ROOT / MASTER_DOC_ID).exists():
        errors.append(
            f"{MASTER_DOC_ID} must not be under {DOCUMENT_KNOWLEDGE_ROOT.relative_to(REPO_ROOT)}"
        )
    for doc_id in LIVE_SUB_MANUAL_DOC_IDS:
        if not (DOCUMENT_KNOWLEDGE_ROOT / doc_id).is_dir():
            errors.append(f"live index missing required sub-manual package: {doc_id}")
    fallback_master = FALLBACK_KNOWLEDGE_ROOT / MASTER_DOC_ID
    if not fallback_master.is_dir():
        errors.append(
            f"{MASTER_DOC_ID} must be present under "
            f"{FALLBACK_KNOWLEDGE_ROOT.relative_to(REPO_ROOT)}"
        )
    return errors


def pdf_page_count(pdf: Path) -> int:
    out = subprocess.run(["pdfinfo", str(pdf)], capture_output=True, text=True).stdout
    for line in out.splitlines():
        if line.startswith("Pages:"):
            return int(line.split(":", 1)[1].strip())
    return -1


def validate(pkg: Path) -> list[str]:
    errors: list[str] = []

    def need(p: Path):
        if not p.exists():
            errors.append(f"missing required file: {p.relative_to(pkg)}")

    need(pkg / "manifest.json")
    need(pkg / "source" / "original.pdf")
    need(pkg / "markdown" / "manual.md")
    need(pkg / "chunks" / "chunks.jsonl")
    need(pkg / "figures" / ".gitkeep")
    if errors:
        return errors

    # manifest JSON well-formed
    try:
        manifest = json.loads((pkg / "manifest.json").read_text(encoding="utf-8"))
    except Exception as e:
        return [f"manifest.json invalid JSON: {e}"]

    doc_id = manifest.get("docId")
    page_count = manifest.get("pageCount")
    pdf_pages = pdf_page_count(pkg / "source" / "original.pdf")
    if pdf_pages != page_count:
        errors.append(f"manifest.pageCount={page_count} != pdf pages={pdf_pages}")

    # Final repository path prefix shared by manifest.sourceHref and every chunk
    # href (the deployed /Thingworx/FileRepositories/.../<docId>/source path).
    href_prefix = f"/Thingworx/FileRepositories/AIDocRepository/document-knowledge/{doc_id}/source/original.pdf"
    if not str(manifest.get("sourceHref", "")).startswith(href_prefix):
        errors.append(f"manifest.sourceHref does not use final repo path prefix {href_prefix}")

    # manual.md must carry page anchors for the first and last page, and its
    # source links must use the same final PDF path as the manifest.
    md_text = (pkg / "markdown" / "manual.md").read_text(encoding="utf-8")
    for pg in {1, page_count} if isinstance(page_count, int) and page_count >= 1 else set():
        if f'<a id="page-{pg}"></a>' not in md_text:
            errors.append(f"manual.md missing page anchor for page {pg}")
    if href_prefix not in md_text:
        errors.append("manual.md does not link the final manifest PDF path")

    # chunk validation
    seen = set()
    count = 0
    chunks: list[dict] = []
    for i, line in enumerate((pkg / "chunks" / "chunks.jsonl").read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            obj = json.loads(line)
        except Exception as e:
            errors.append(f"chunk line {i}: invalid JSON: {e}")
            continue
        chunks.append(obj)
        for k in REQUIRED_CHUNK_KEYS:
            if k not in obj:
                errors.append(f"chunk line {i}: missing key {k}")
        if not str(obj.get("markdown", "")).strip():
            errors.append(f"chunk line {i}: empty chunk markdown")
        cid = (obj.get("docId"), obj.get("chunkId"))
        if cid in seen:
            errors.append(f"chunk line {i}: duplicate chunkId {cid}")
        seen.add(cid)
        if obj.get("docId") != doc_id:
            errors.append(f"chunk line {i}: docId {obj.get('docId')} != manifest {doc_id}")
        sl = obj.get("sourceLinks")
        if not isinstance(sl, list) or not sl:
            errors.append(f"chunk line {i}: missing sourceLinks")
            continue
        href = sl[0].get("href", "")
        # Strict: every chunk href must point at THIS doc's final PDF path with
        # only a numeric #page= fragment (catches wrong-doc / malformed hrefs that
        # a substring check would miss).
        expected_start = f"{href_prefix}#page="
        frag = href[len(expected_start):] if href.startswith(expected_start) else None
        if frag is None or not frag.isdigit():
            errors.append(f"chunk line {i}: href must be {href_prefix}#page=<n>, got {href}")
        else:
            # Link integrity: the href page, sourceLinks[0].page, and pageStart
            # must all agree and be a real page of this PDF — a link to page 999
            # of a 21-page PDF is a broken source link even with the right path.
            href_page = int(frag)
            link_page = sl[0].get("page")
            ps0 = obj.get("pageStart")
            if isinstance(page_count, int) and not (1 <= href_page <= page_count):
                errors.append(f"chunk line {i}: href page {href_page} out of range 1..{page_count}")
            if not isinstance(link_page, int):
                errors.append(f"chunk line {i}: sourceLinks[0].page must be an integer")
            else:
                if isinstance(page_count, int) and not (1 <= link_page <= page_count):
                    errors.append(f"chunk line {i}: sourceLinks[0].page {link_page} out of range 1..{page_count}")
                if link_page != href_page:
                    errors.append(f"chunk line {i}: sourceLinks[0].page {link_page} != href page {href_page}")
            if isinstance(ps0, int) and href_page != ps0:
                errors.append(f"chunk line {i}: href page {href_page} != pageStart {ps0}")
        if sl[0].get("repository") != "AIDocRepository":
            errors.append(f"chunk line {i}: repository != AIDocRepository")
        if sl[0].get("path") != f"/document-knowledge/{doc_id}/source/original.pdf":
            errors.append(f"chunk line {i}: unexpected path {sl[0].get('path')}")
        ps, pe = obj.get("pageStart"), obj.get("pageEnd")
        if not (isinstance(ps, int) and ps >= 1):
            errors.append(f"chunk line {i}: pageStart must be >= 1")
        elif isinstance(pe, int) and pe < ps:
            errors.append(f"chunk line {i}: pageEnd < pageStart")
        elif isinstance(page_count, int) and isinstance(ps, int) and ps > page_count:
            errors.append(f"chunk line {i}: pageStart {ps} > pageCount {page_count}")
        count += 1

    if manifest.get("chunkCount") != count:
        errors.append(f"manifest.chunkCount={manifest.get('chunkCount')} != actual {count}")

    if doc_id and chunks:
        errors.extend(validate_content(pkg, doc_id, chunks))
    errors.extend(validate_document_profile(manifest))

    return errors


def main() -> int:
    if len(sys.argv) == 2 and sys.argv[1] == "--all":
        pkgs = sorted(p for p in DOCUMENT_KNOWLEDGE_ROOT.iterdir() if p.is_dir()) if DOCUMENT_KNOWLEDGE_ROOT.exists() else []
        layout_errs = validate_live_index_layout()
    elif len(sys.argv) == 2 and sys.argv[1] == "--all-fallback":
        pkgs = sorted(p for p in FALLBACK_KNOWLEDGE_ROOT.iterdir() if p.is_dir()) if FALLBACK_KNOWLEDGE_ROOT.exists() else []
        layout_errs = []
    elif len(sys.argv) == 2:
        pkgs = [Path(sys.argv[1]).resolve()]
        layout_errs = []
    else:
        print(__doc__)
        return 2

    failed = 0
    if layout_errs:
        failed += 1
        print("FAIL live-index-layout:")
        for e in layout_errs:
            print(f"  - {e}")
    for pkg in pkgs:
        errs = validate(pkg)
        if errs:
            failed += 1
            print(f"FAIL {pkg.name}:")
            for e in errs:
                print(f"  - {e}")
        else:
            print(f"OK   {pkg.name}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
