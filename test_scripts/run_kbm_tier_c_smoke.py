#!/usr/bin/env -S uv run --quiet python
"""Run Tier C KBM coupling smoke on a document-knowledge-enabled AgentThing.

Uses the prompt from dev_data/future_repo/README.md and prints pass/fail checks
against stream evidence (KBM hits, damage carrier, no master signal-* pollution).

Usage (from repo root, requires .env DEV_SERVER + DEV_KEY):

    uv run test_scripts/run_kbm_tier_c_smoke.py
    uv run test_scripts/run_kbm_tier_c_smoke.py --agent SCPA_Demo_Agent

Default agent is SCPA_Demo_Agent, the sample AgentThing with document knowledge configured.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

from test_scripts.agent_eval import (
    STREAM_BUFFER_WAIT_DEFAULT_S,
    get_or_create_conversation_id,
    query_stream_data,
    random_title_suffix,
    run_turn_chat,
)
from test_scripts.dev_import_control import load_dotenv, require_env

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_AGENT = "SCPA_Demo_Agent"
KBM_PROMPT = (
    "For the KBM flexible pin type coupling, search the document knowledge repository "
    "and explain what the manual says about shaft misalignment compensation and what "
    "to do if the coupling may be damaged before commissioning. Cite the PDF page "
    "link you used."
)
DAMAGE_CARRIER_CHUNK_IDS = (
    "page-0006",
    "signal-0006",
    "section-0006-damage-before-operation",
    "section-0006-3-function",
)
DAMAGE_BAN_PHRASE = "may not be put into operation"


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--agent", default=DEFAULT_AGENT, help=f"AgentThing name (default: {DEFAULT_AGENT})")
    p.add_argument("--timeout-s", type=float, default=600.0, help="Chat HTTP timeout")
    return p.parse_args(argv)


def stream_has_damage_carrier(blob: str) -> bool:
    if any(chunk_id in blob for chunk_id in DAMAGE_CARRIER_CHUNK_IDS):
        return True
    return DAMAGE_BAN_PHRASE.lower() in blob.lower()


def final_has_damage_ban(final: str) -> bool:
    return DAMAGE_BAN_PHRASE.lower() in final.lower()


def final_has_page6_kbm_link(final: str) -> bool:
    return bool(
        re.search(
            r"(page\s*6|page=6|#page=6).*kbm-coupling-manual|kbm-coupling-manual.*(page\s*6|page=6|#page=6)",
            final,
            re.I | re.S,
        )
    )


def analyze_turn(*, final: str, rows: list[dict]) -> dict[str, bool]:
    blob = json.dumps(rows, ensure_ascii=False)
    return {
        "search_document_chunks": "search_document_chunks" in blob,
        "get_document_chunk": "get_document_chunk" in blob,
        "kbm_hits": "kbm-coupling-manual" in blob.lower(),
        "damage_carrier_in_stream": stream_has_damage_carrier(blob),
        "final_damage_ban_phrase": final_has_damage_ban(final),
        "final_page6_kbm_link": final_has_page6_kbm_link(final),
        "final_cites_link": bool(re.search(r"(sourceLinks|sourceHref|document-knowledge|\.pdf)", final, re.I)),
        "final_misalign": bool(re.search(r"misalign", final, re.I)),
        "master_absent": "rk-t-turbogenerator-master" not in blob.lower(),
        "no_master_signal_pollution": not bool(
            re.search(r"rk-t-turbogenerator-master", blob, re.I)
        ),
        "no_max_iterations": "max iterations" not in final.lower() and "MAX_ITERATIONS" not in blob,
    }


def tier_c_passed(checks: dict[str, bool]) -> bool:
    required = (
        "search_document_chunks",
        "kbm_hits",
        "final_cites_link",
        "final_misalign",
        "master_absent",
        "no_max_iterations",
    )
    if not all(checks[k] for k in required):
        return False
    damage_proven = checks["damage_carrier_in_stream"] or (
        checks["final_damage_ban_phrase"] and checks["final_page6_kbm_link"]
    )
    return damage_proven


def format_check(name: str, value: bool) -> str:
    negative_desired_false = {
        "master_absent",
        "no_master_signal_pollution",
        "no_max_iterations",
    }
    if name in negative_desired_false:
        return f"{'OK' if value else 'FAIL'}  {name} (want absent/false)"
    return f"{'OK' if value else 'FAIL'}  {name}"


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(REPO_ROOT / ".env")
    server = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")
    agent = args.agent.strip()

    title = f"doc-index-kbm-tierc-{random_title_suffix()}"
    cid = get_or_create_conversation_id(server, key, agent, title=title, timeout_s=120)
    print(f"agent: {agent}")
    print(f"conversation: {cid}")

    final = run_turn_chat(
        server,
        key,
        agent,
        message=KBM_PROMPT,
        conversation_id=cid,
        host_context_obj=None,
        system_prompt=None,
        timeout_s=args.timeout_s,
    )
    print("\n--- FINAL (first 1200 chars) ---")
    print(final[:1200])

    time.sleep(STREAM_BUFFER_WAIT_DEFAULT_S + 3)
    rows = query_stream_data(server, key, source=cid, max_items=2000, timeout_s=120)
    checks = analyze_turn(final=final, rows=rows)

    print("\n--- TIER C CHECKS ---")
    for name, ok in checks.items():
        print(format_check(name, ok))

    if tier_c_passed(checks):
        print("\nOK   Tier C KBM smoke passed")
        return 0

    print("\nFAIL Tier C KBM smoke", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
