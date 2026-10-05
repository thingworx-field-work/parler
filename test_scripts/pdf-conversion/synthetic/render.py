"""Render page-structured synthetic manuals to PDF with reportlab.

Every manual is a list of pages; every page is a list of blocks. One page in
the list becomes exactly one PDF page (content is shrunk to fit when needed),
so page numbers in the authored content stay stable for tests and citations.

Block kinds:
    ("title", text)          large bold line
    ("h", text)              section heading
    ("p", text)              wrapped paragraph
    ("bullets", [text, ...]) dash-prefixed list
    ("toc", [(title, page)]) dotted-leader table-of-contents lines
    ("table", rows)          ruled table (first row is the header)
    ("lines", [text, ...])   short unwrapped lines
"""

from __future__ import annotations

from pathlib import Path

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.platypus import (
    KeepInFrame,
    PageBreak,
    Paragraph,
    SimpleDocTemplate,
    Spacer,
    Table,
    TableStyle,
)

PAGE_W, PAGE_H = A4
MARGIN = 18 * mm
FRAME_W = PAGE_W - 2 * MARGIN
FRAME_H = PAGE_H - 2 * MARGIN - 14 * mm

BODY = ParagraphStyle("body", fontName="Helvetica", fontSize=9.5, leading=12.5, spaceAfter=5)
TITLE = ParagraphStyle("title", fontName="Helvetica-Bold", fontSize=17, leading=22, spaceAfter=10)
HEADING = ParagraphStyle("h", fontName="Helvetica-Bold", fontSize=11, leading=14, spaceBefore=4, spaceAfter=5)
LINE = ParagraphStyle("line", fontName="Helvetica", fontSize=9.5, leading=12.5)
CELL = ParagraphStyle("cell", fontName="Helvetica", fontSize=8.5, leading=10.5)
CELL_HEAD = ParagraphStyle("cellhead", fontName="Helvetica-Bold", fontSize=8.5, leading=10.5)


def _esc(text: str) -> str:
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


def _toc_line(title: str, page: int) -> str:
    dots = "." * max(3, 78 - len(title) - len(str(page)))
    return f"{title}{dots}{page}"


def _table(rows: list[list[str]]) -> Table:
    ncols = max(len(r) for r in rows)
    data = []
    for i, row in enumerate(rows):
        style = CELL_HEAD if i == 0 else CELL
        data.append([Paragraph(_esc(c), style) for c in row] + [""] * (ncols - len(row)))
    col_w = FRAME_W / ncols
    table = Table(data, colWidths=[col_w] * ncols, repeatRows=0)
    table.setStyle(TableStyle([
        ("GRID", (0, 0), (-1, -1), 0.6, colors.black),
        ("VALIGN", (0, 0), (-1, -1), "TOP"),
        ("BACKGROUND", (0, 0), (-1, 0), colors.HexColor("#e6e6e6")),
    ]))
    return table


def _flowables(blocks: list[tuple]) -> list:
    out: list = []
    for block in blocks:
        kind = block[0]
        if kind == "title":
            out.append(Paragraph(_esc(block[1]), TITLE))
        elif kind == "h":
            out.append(Paragraph(_esc(block[1]), HEADING))
        elif kind == "p":
            out.append(Paragraph(_esc(block[1]), BODY))
        elif kind == "bullets":
            for item in block[1]:
                out.append(Paragraph("- " + _esc(item), BODY))
        elif kind == "lines":
            for item in block[1]:
                out.append(Paragraph(_esc(item), LINE))
            out.append(Spacer(1, 5))
        elif kind == "toc":
            for title, page in block[1]:
                out.append(Paragraph(_esc(_toc_line(title, page)), LINE))
            out.append(Spacer(1, 5))
        elif kind == "table":
            out.append(_table(block[1]))
            out.append(Spacer(1, 6))
        else:
            raise ValueError(f"unknown block kind: {kind}")
    return out


def render_pdf(pages: list[list[tuple]], out: Path, letterhead: str, doc_code: str) -> int:
    """Write the manual to ``out`` and return the page count."""
    out.parent.mkdir(parents=True, exist_ok=True)
    total = len(pages)

    def decorate(canvas, doc):
        canvas.saveState()
        canvas.setFont("Helvetica", 8)
        canvas.drawString(MARGIN, PAGE_H - MARGIN + 4 * mm, f"{letterhead} - {doc_code}")
        canvas.drawRightString(PAGE_W - MARGIN, MARGIN - 8 * mm, f"Page {doc.page} / {total}")
        canvas.restoreState()

    story: list = []
    for i, blocks in enumerate(pages):
        story.append(KeepInFrame(FRAME_W, FRAME_H, _flowables(blocks), mode="shrink"))
        if i + 1 < total:
            story.append(PageBreak())
    doc = SimpleDocTemplate(
        str(out), pagesize=A4,
        leftMargin=MARGIN, rightMargin=MARGIN, topMargin=MARGIN + 6 * mm, bottomMargin=MARGIN,
        title=doc_code, author="Parler synthetic sample", creator="Parler synthetic sample",
        producer="Parler synthetic sample",
    )
    doc.build(story, onFirstPage=decorate, onLaterPages=decorate)
    return total
