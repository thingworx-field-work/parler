#!/usr/bin/env -S uv run --with reportlab --with pypdf --with pdfplumber --quiet python
"""Build the synthetic document-knowledge sample set.

Every manual here is fictional and was written for Parler's document-retrieval
fixtures. The script renders the source PDFs into dev_data/pdf-sample/ (the
converter's input directory) and builds the hand-curated CarbaQ package
directly. Run convert_pdf.py afterwards to produce the converted packages:

    ./test_scripts/pdf-conversion/synthetic/build_samples.py
    ./test_scripts/pdf-conversion/convert_pdf.py --all

Requires poppler (pdftotext, pdfinfo, pdftoppm) on PATH.
"""

from __future__ import annotations

import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from pypdf import PdfReader, PdfWriter
from reportlab import rl_config

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent))

rl_config.invariant = 1  # byte-identical PDFs across runs

import content_accessories as accessories  # noqa: E402
import content_capture_ops as capture  # noqa: E402
import content_coupling as coupling  # noqa: E402
import content_install_spec as install  # noqa: E402
import content_operating as operating  # noqa: E402
import convert_pdf as conv  # noqa: E402
from render import render_pdf  # noqa: E402

PDF_SAMPLE_DIR = conv.PDF_SAMPLE_DIR
BUNDLE_SOURCE_NAME = "7.318.042 Manual.pdf"
PNG_DPI = "40"


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def build_converter_sources() -> dict[str, int]:
    counts = {}
    for mod in (coupling, install, operating):
        out = PDF_SAMPLE_DIR / mod.SOURCE_NAME
        counts[mod.SOURCE_NAME] = render_pdf(mod.PAGES, out, mod.LETTERHEAD, mod.DOC_CODE)
    return counts


def build_bundle() -> int:
    """Compilation bundle: a cover page, the operating manual, the installation
    requirements, the coupling manual, and accessory documentation (Folder 2)
    that no sub-manual contains."""
    with tempfile.TemporaryDirectory() as tmp:
        cover = Path(tmp) / "cover.pdf"
        render_pdf([[
            ("title", "Turbo-generator Set CB 24 GT4"),
            ("lines", ["Complete Manual", "Order 7.318.042",
                       "Folder 1: Operating Manual with drawings",
                       "Folder 2: Installation requirements, coupling manual and "
                       "accessory documentation"]),
        ]], cover, operating.LETTERHEAD, "Order 7.318.042 - Complete Manual")
        accessory_pdf = Path(tmp) / "accessories.pdf"
        render_pdf(accessories.PAGES, accessory_pdf, accessories.LETTERHEAD, accessories.DOC_CODE)
        writer = PdfWriter()
        for part in (cover, PDF_SAMPLE_DIR / operating.SOURCE_NAME,
                     PDF_SAMPLE_DIR / install.SOURCE_NAME, PDF_SAMPLE_DIR / coupling.SOURCE_NAME,
                     accessory_pdf):
            for page in PdfReader(str(part)).pages:
                writer.add_page(page)
        writer.add_metadata({"/Title": "Order 7.318.042 - Complete Manual",
                             "/Producer": "Parler synthetic sample"})
        out = PDF_SAMPLE_DIR / BUNDLE_SOURCE_NAME
        with out.open("wb") as fh:
            writer.write(fh)
    return conv.pdf_page_count(out)


def _capture_source_link(page: int, label: str) -> dict:
    return {
        "label": f"{capture.TITLE}, {label}, page {page}",
        "repository": conv.SOURCE_REPOSITORY,
        "path": f"/document-knowledge/{capture.DOC_ID}/source/original.pdf",
        "page": page,
        "href": f"{conv.TW_REPO_PREFIX}/{capture.DOC_ID}/source/original.pdf#page={page}",
    }


def build_capture_package() -> dict:
    """Hand-curated package: page chunks from the text layer plus curated
    section / maintenance / troubleshooting-row chunks."""
    pkg = conv.OUTPUT_ROOT / capture.DOC_ID
    if pkg.exists():
        shutil.rmtree(pkg)
    for sub in ("source", "markdown", "chunks", "figures", "pages"):
        (pkg / sub).mkdir(parents=True, exist_ok=True)
    (pkg / "figures" / ".gitkeep").touch()

    src = PDF_SAMPLE_DIR / capture.SOURCE_NAME
    page_count = render_pdf(capture.PAGES, src, capture.LETTERHEAD, capture.DOC_CODE)
    pdf = pkg / "source" / "original.pdf"
    shutil.copyfile(src, pdf)

    boiler = conv.compute_boilerplate(conv.extract_pages(pdf))
    bodies, tables_detected, tables_structured = conv.extract_pages_table_aware(pdf, boiler)

    class _Cfg:
        doc_id = capture.DOC_ID
        title = capture.TITLE
        short_label = ""

    (pkg / "markdown" / "manual.md").write_text(conv.build_markdown_from_bodies(_Cfg, bodies),
                                                encoding="utf-8")

    chunks = []
    for i, body in enumerate(bodies, 1):
        heading = conv.first_heading(body, boiler) or f"Page {i:02d}"
        chunks.append({
            "chunkId": f"page-{i:04d}", "contentType": "page", "heading": heading,
            "sectionPath": ["Full manual page"], "pageStart": i, "pageEnd": i,
            "tags": ["full-page"], "signals": [],
            "summary": f"Full converted markdown for page {i}.",
            "markdown": conv.page_anchor_block(_Cfg, i) + "\n\n" + body + "\n",
        })
    chunks.extend(capture.CURATED)
    with (pkg / "chunks" / "chunks.jsonl").open("w", encoding="utf-8") as fh:
        for c in chunks:
            label = c["sectionPath"][-1] if c["sectionPath"] else c["heading"]
            row = {"contractVersion": conv.CONTRACT_VERSION, "docId": capture.DOC_ID, **c,
                   "sourceLinks": [_capture_source_link(c["pageStart"], label)]}
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")

    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run(["pdftoppm", "-r", PNG_DPI, "-png", str(pdf), f"{tmp}/p"], check=True)
        for png in sorted(Path(tmp).glob("p-*.png")):
            num = int(png.stem.split("-")[-1])
            shutil.copyfile(png, pkg / "pages" / f"page-{num:04d}.png")

    manifest = {
        "contractVersion": conv.CONTRACT_VERSION,
        "docId": capture.DOC_ID,
        "title": capture.TITLE,
        "sourcePath": "source/original.pdf",
        "markdownPath": "markdown/manual.md",
        "chunksPath": "chunks/chunks.jsonl",
        "pageCount": page_count,
        "sourceSha256": _sha256(pdf),
        "convertedAt": capture.CONVERTED_AT,
        "sourceFileName": capture.SOURCE_NAME,
        "documentVersion": capture.DOCUMENT_VERSION,
        "documentType": "operations_manual",
        "assetModels": [capture.ASSET_MODEL],
        "languages": ["en"],
        "artifacts": {"renderedPagesPattern": "pages/page-{page:04d}.png", "figuresPath": "figures/"},
        "conversionQuality": {
            "textLayer": "present",
            "ocrUsed": False,
            "tablesDetected": tables_detected,
            "tablesStructured": tables_structured,
            "pageAnchorsGenerated": page_count,
            "manualReview": "completed",
            "warnings": [
                "Synthetic sample built by test_scripts/pdf-conversion/synthetic/build_samples.py; "
                "section, maintenance and troubleshooting-row chunks are hand-curated.",
            ],
        },
        "chunkCount": len(chunks),
        "chunkStrategies": ["page", "semantic-section", "troubleshooting-row"],
        "sourceRepository": conv.SOURCE_REPOSITORY,
        "sourceRepositoryPath": f"/document-knowledge/{capture.DOC_ID}/source/original.pdf",
        "sourceHref": f"{conv.TW_REPO_PREFIX}/{capture.DOC_ID}/source/original.pdf",
    }
    (pkg / "manifest.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n",
                                       encoding="utf-8")
    return {"docId": capture.DOC_ID, "pageCount": page_count, "chunkCount": len(chunks)}


def main() -> int:
    counts = build_converter_sources()
    expected = {coupling.SOURCE_NAME: len(coupling.PAGES), install.SOURCE_NAME: len(install.PAGES),
                operating.SOURCE_NAME: len(operating.PAGES)}
    for name, n in counts.items():
        actual = conv.pdf_page_count(PDF_SAMPLE_DIR / name)
        if actual != expected[name]:
            raise SystemExit(f"{name}: expected {expected[name]} pages, rendered {actual}")
    counts[BUNDLE_SOURCE_NAME] = build_bundle()
    print(json.dumps({"sources": counts, "capture": build_capture_package()}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
