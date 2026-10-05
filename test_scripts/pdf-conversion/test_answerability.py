#!/usr/bin/env -S uv run --quiet python
"""C2 answerability gold tests for doc-index-enhance (design doc Part C).

Usage (from repo root):
    ./test_scripts/pdf-conversion/test_answerability.py
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import convert_pdf  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
FIXTURE_ROOT = REPO_ROOT / "dev_data/future_repo/document-knowledge"

KBM_DOC_ID = "kbm-coupling-manual-7318042"
RKT_INSTALL = "rk-t-install-spec-7318042"
RKT_OPS = "rk-t-operating-manual-7318042"
CAPTURE_DOC_ID = "fernwick-carbaq-ops-v2"
BUNDLE_ROOT = REPO_ROOT / "dev_data/future_repo/_fallback/document-knowledge/rk-t-turbogenerator-master-7318042"
BUNDLE_ONLY_TERMS = ("purifier", "turning gear", "steam trap station")


def _load_chunks(doc_id: str, root: Path = FIXTURE_ROOT) -> dict[str, dict]:
    path = root / doc_id / "chunks" / "chunks.jsonl"
    chunks: dict[str, dict] = {}
    with path.open(encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            row = json.loads(line)
            chunks[row["chunkId"]] = row
    return chunks


def _body(chunks: dict[str, dict], chunk_id: str) -> str:
    chunk = chunks.get(chunk_id)
    if not chunk:
        return ""
    return chunk.get("markdown") or ""


def test_kbm_damage_ban_present() -> None:
    chunks = _load_chunks(KBM_DOC_ID)
    damage = _body(chunks, "page-0006") + _body(chunks, "section-0006-3-function")
    assert "may not be put into operation" in damage.lower(), (
        "KBM damage ban sentence missing from page-0006 / section-0006-3-function"
    )


def test_kbm_table4_misalignment_values() -> None:
    chunks = _load_chunks(KBM_DOC_ID)
    p9 = _body(chunks, "page-0009")
    assert "|" in p9 and "10" in p9 and "0,3" in p9, (
        "KBM page-0009 missing Markdown Table 4 row with nominal size 10 / Kr 0,3"
    )
    assert "0,7" in p9, "KBM page-0009 Table 4 missing Kw' 0,7 tolerance row"


def test_rkt_operating_part_a_sections() -> None:
    chunks = _load_chunks(RKT_OPS)
    part_a = [
        c for c in chunks.values()
        if c.get("contentType") == "section" and "-part-a-" in (c.get("chunkId") or "")
    ]
    assert len(part_a) >= 8, f"{RKT_OPS}: expected Part A chapter sections"
    general = [c for c in part_a if "general" in (c.get("heading") or "").lower()]
    assert general, f"{RKT_OPS}: expected a General notes Part A section"
    body = general[0].get("markdown") or ""
    assert "Part B Drawings" not in body[:800]
    assert "preface" in body.lower()


def test_rkt_install_spec_toc_sections() -> None:
    chunks = _load_chunks(RKT_INSTALL)
    sections = [
        c for c in chunks.values()
        if c.get("contentType") == "section" and "-register-" not in (c.get("chunkId") or "")
    ]
    assert len(sections) >= 3, f"{RKT_INSTALL}: expected TOC semantic-section chunks"


def test_live_index_layout() -> None:
    live_root = FIXTURE_ROOT
    fallback_master = REPO_ROOT / "dev_data/future_repo/_fallback/document-knowledge/rk-t-turbogenerator-master-7318042"
    assert not (live_root / "rk-t-turbogenerator-master-7318042").exists(), (
        "master must not be in live document-knowledge index root"
    )
    assert fallback_master.is_dir(), "master must be under _fallback/document-knowledge"
    for doc_id in (KBM_DOC_ID, RKT_INSTALL, RKT_OPS):
        assert (live_root / doc_id).is_dir(), f"live index missing {doc_id}"


def _page_texts(chunks: dict[str, dict]) -> dict[int, str]:
    return {c["pageStart"]: c["markdown"] for c in chunks.values() if c["contentType"] == "page"}


def test_capture_curated_chunks_grounded_in_cited_pages() -> None:
    """Every number and content word of a hand-curated chunk is printed on the pages it cites."""
    chunks = _load_chunks(CAPTURE_DOC_ID)
    pages = _page_texts(chunks)
    for chunk in chunks.values():
        if chunk["contentType"] == "page":
            continue
        cited = " ".join(pages[p] for p in range(chunk["pageStart"], chunk["pageEnd"] + 1)).lower()
        body = "\n".join(line for line in chunk["markdown"].splitlines() if not line.startswith("## "))
        body = body.lower()
        missing_numbers = [n for n in re.findall(r"\d+(?:[./,]\d+)?", body) if n not in cited]
        cited_words = set(re.findall(r"[a-z]{4,}", cited))
        missing_words = sorted({w for w in re.findall(r"[a-z]{4,}", body) if w not in cited_words})
        assert not missing_numbers and not missing_words, (
            f"{chunk['chunkId']} states content not on its cited pages "
            f"{chunk['pageStart']}-{chunk['pageEnd']}: numbers {missing_numbers}, words {missing_words}"
        )


def test_bundle_has_content_no_sub_manual_has() -> None:
    bundle = _page_texts(_load_chunks(BUNDLE_ROOT.name, BUNDLE_ROOT.parent))
    bundle_text = " ".join(bundle.values()).lower()
    for term in BUNDLE_ONLY_TERMS:
        assert term in bundle_text, f"bundle is missing its accessory topic '{term}'"
        for doc_id in (KBM_DOC_ID, RKT_INSTALL, RKT_OPS, CAPTURE_DOC_ID):
            text = " ".join(c["markdown"] for c in _load_chunks(doc_id).values()).lower()
            assert term not in text, f"'{term}' must exist only in the bundle, found in {doc_id}"


def test_bundle_signal_cap_selects_by_richness_with_page_fallback() -> None:
    chunks = _load_chunks(BUNDLE_ROOT.name, BUNDLE_ROOT.parent)
    pages = _page_texts(chunks)
    richness = {p: len({tag for kw, tag in convert_pdf.KEYWORD_SIGNALS if kw in body.lower()})
                for p, body in pages.items()}
    flagged = {p: r for p, r in richness.items() if r >= 2}
    selected = {c["pageStart"] for c in chunks.values() if c["chunkId"].startswith("signal-")}
    dropped = {p: r for p, r in flagged.items() if p not in selected}
    assert len(flagged) > 30, f"bundle must exceed the 30-signal cap; flagged only {len(flagged)}"
    assert len(selected) == 30, f"expected 30 signal chunks, got {len(selected)}"
    assert min(flagged[p] for p in selected) >= max(dropped.values()), "cap must keep the richest pages"
    assert any(p < max(selected) for p in dropped), "a richer later page must displace a poorer earlier one"
    assert all(p in pages for p in dropped), "dropped signal pages must keep their page chunk"
    manifest = json.loads((BUNDLE_ROOT / "manifest.json").read_text(encoding="utf-8"))
    warnings = " ".join(manifest["conversionQuality"]["warnings"])
    assert f"flagged {len(flagged)} pages but only the top 30" in warnings, "cap warning missing"


def main() -> int:
    tests = [
        test_kbm_damage_ban_present,
        test_kbm_table4_misalignment_values,
        test_rkt_operating_part_a_sections,
        test_rkt_install_spec_toc_sections,
        test_live_index_layout,
        test_capture_curated_chunks_grounded_in_cited_pages,
        test_bundle_has_content_no_sub_manual_has,
        test_bundle_signal_cap_selects_by_richness_with_page_fallback,
    ]
    for fn in tests:
        fn()
        print(f"OK   {fn.__name__}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
