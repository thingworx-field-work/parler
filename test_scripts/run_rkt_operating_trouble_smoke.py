#!/usr/bin/env -S uv run --quiet python
"""RK&T operating-trouble live smoke (document-retrieval-stability §11.6).

Exercises the trigger prompt without `/document_search` and checks bounded turn
behavior, document-tool usage, RK&T source dominance, and optional search
diagnostics.

Usage (from repo root, requires .env DEV_SERVER + DEV_KEY):

    uv run test_scripts/run_rkt_operating_trouble_smoke.py
    uv run test_scripts/run_rkt_operating_trouble_smoke.py --agent SCPA_Demo_Agent

After deploying the extension build from this branch, re-run with diagnostics required
for topic closure (§11.6 "when available"):

    uv run test_scripts/run_rkt_operating_trouble_smoke.py --require-search-diagnostics

Deploy the extension build first. Re-extract `dev_data/future_repo` with the
updated converter before expecting weak-identity paraphrase acceptance.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any

from test_scripts.agent_eval import (
    STREAM_BUFFER_WAIT_DEFAULT_S,
    get_or_create_conversation_id,
    parse_llm_usage_json_cell,
    query_stream_data,
    random_title_suffix,
    run_turn_chat,
)
from test_scripts.dev_import_control import load_dotenv, require_env

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_AGENT = "SCPA_Demo_Agent"

RKT_PROMPT = (
    "Search the RK&T operating manual for safety-relevant operating trouble such as "
    "overspeed, steam pressure, bearing temperature, lube oil pressure, and "
    "vibrations. What does the manual say to do before restarting the turbine? "
    "Cite the original PDF page link."
)

MAX_TURN_WALL_MS = 180_000
MAX_AGENT_ITERATIONS = 8
RKT_DOC_ID = "rk-t-operating-manual-7318042"
FERNWICK_DOC_FRAGMENT = "fernwick-carbaq"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--agent", default=DEFAULT_AGENT)
    p.add_argument("--timeout-s", type=float, default=600.0)
    p.add_argument("--max-turn-wall-ms", type=int, default=MAX_TURN_WALL_MS)
    p.add_argument("--max-agent-iterations", type=int, default=MAX_AGENT_ITERATIONS)
    p.add_argument(
        "--require-search-diagnostics",
        action="store_true",
        help="Fail when search selectionMode diagnostics are absent (post-deploy closure bar).",
    )
    return p.parse_args(argv)


def analyze_turn(*, final: str, rows: list[dict[str, Any]]) -> dict[str, Any]:
    blob = json.dumps(rows, ensure_ascii=False)
    search_calls = blob.count("search_document_chunks")
    get_chunk_calls = blob.count("get_document_chunk")
    search_diag_modes: list[str] = []
    top_doc_ids: list[str] = []
    perf: dict[str, Any] = {}

    for row in rows:
        role = str(row.get("role") or "").lower()
        if role == "assistant":
            parsed, _ = parse_llm_usage_json_cell(row.get("llmUsageJson"))
            if parsed:
                perf = parsed
        raw = row.get("toolResult") or row.get("tool_result") or ""
        if isinstance(raw, str) and raw.strip().startswith("{"):
            try:
                body = json.loads(raw)
                mode = body.get("selectionMode")
                if isinstance(mode, str):
                    search_diag_modes.append(mode)
                matches = body.get("matches") or []
                if matches and isinstance(matches[0], dict):
                    doc_id = matches[0].get("docId")
                    if isinstance(doc_id, str):
                        top_doc_ids.append(doc_id)
            except json.JSONDecodeError:
                pass

    if not search_diag_modes and '"selectionMode"' in blob:
        for token in ("diversified", "hard-single", "documentIds-filter"):
            if token in blob:
                search_diag_modes.append(token)

    return {
        "search_calls": search_calls,
        "get_chunk_calls": get_chunk_calls,
        "selection_modes": search_diag_modes,
        "top_doc_ids": top_doc_ids,
        "final_text": final,
        "perf": perf,
    }


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(REPO_ROOT / ".env")
    server = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")

    conversation_id = get_or_create_conversation_id(
        server,
        key,
        args.agent,
        title=f"rkt-operating-trouble-smoke {random_title_suffix()}",
        timeout_s=120,
    )
    t0 = time.time()
    final = run_turn_chat(
        server,
        key,
        args.agent,
        message=RKT_PROMPT,
        conversation_id=conversation_id,
        host_context_obj=None,
        system_prompt=None,
        timeout_s=args.timeout_s,
    )
    time.sleep(STREAM_BUFFER_WAIT_DEFAULT_S + 3)
    rows = query_stream_data(
        server, key, source=conversation_id, max_items=2000, timeout_s=120,
    )
    analysis = analyze_turn(final=final, rows=rows)
    perf = analysis.get("perf") or {}
    turn_wall = int(perf.get("turnWallMs") or 0)
    iterations = int(perf.get("agentIterations") or 0)
    final_text = analysis.get("final_text") or ""

    checks = {
        "not_max_iterations": "max iterations" not in final_text.lower(),
        "has_get_document_chunk": analysis.get("get_chunk_calls", 0) >= 1,
        "rkt_source_in_answer": RKT_DOC_ID in final_text or "rk-t-operating" in final_text.lower(),
        "no_fernwick_top_rank": not any(FERNWICK_DOC_FRAGMENT in d for d in analysis.get("top_doc_ids", [])[:1]),
        "bounded_wall": turn_wall <= args.max_turn_wall_ms if turn_wall else True,
        "bounded_iterations": iterations <= args.max_agent_iterations if iterations else True,
    }
    diagnostics_seen = len(analysis.get("selection_modes") or []) >= 1
    if args.require_search_diagnostics:
        checks["search_diagnostics_seen"] = diagnostics_seen

    print(json.dumps({
        "conversationId": conversation_id,
        "elapsed_s": round(time.time() - t0, 1),
        "requireSearchDiagnostics": args.require_search_diagnostics,
        "searchDiagnosticsSeen": diagnostics_seen,
        "analysis": analysis,
        "checks": checks,
    }, indent=2))

    failed = [name for name, ok in checks.items() if not ok]
    if failed:
        print(f"FAIL: {', '.join(failed)}", file=sys.stderr)
        return 1
    print("PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
