#!/usr/bin/env -S uv run --quiet python
"""Tier D live smoke: `/document_search` KBM prompt with latency + D2 evidence checks.

Extends Tier C content checks with turn wall time, agent iteration count, and
ApplicationLog proof that document-turn tool narrowing ran (tools=3 / narrowing log)
**for the same conversation**.

Usage (from repo root, requires .env DEV_SERVER + DEV_KEY):

    uv run test_scripts/run_kbm_tier_d_smoke.py
    uv run test_scripts/run_kbm_tier_d_smoke.py --agent SCPA_Demo_Agent

Deploy D2 bytecode first (extension upload + RestartThing or load-ext-cloud -e).
See docs/operations/doc-index-enhance.md.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any

from test_scripts.GetApplicationLog import (
    build_query_url,
    default_payload,
    post_json as post_log_json,
    slim_rows,
    utc_now_iso_z,
    format_iso_z,
)
from test_scripts.agent_eval import (
    STREAM_BUFFER_WAIT_DEFAULT_S,
    build_thing_service_url,
    get_or_create_conversation_id,
    parse_llm_usage_json_cell,
    post_json,
    query_stream_data,
    random_title_suffix,
    run_turn_chat,
)
from test_scripts.dev_import_control import load_dotenv, require_env
from test_scripts.run_kbm_tier_c_smoke import (
    KBM_PROMPT,
    analyze_turn,
    format_check,
    tier_c_passed,
)

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_AGENT = "SCPA_Demo_Agent"
DOCUMENT_SKILL_SLASH = "/document_search"

# Incident runaway: turnWallMs≈486s, agentIterations=10, tools=33, toolSchemaChars=68220.
MAX_TURN_WALL_MS = 180_000
MAX_AGENT_ITERATIONS = 6
INCIDENT_TOOL_SCHEMA_CHARS = 68_220
NARROWED_TOOL_COUNT = 3

_CONTEXT_PLAN_RE = re.compile(
    r"LLM_CONTEXT_PLAN[^\n]*conversationId=(?P<cid>[^\s]+)[^\n]*tools=(?P<tools>\d+)[^\n]*toolSchemaChars=(?P<schema>\d+)"
)
_NARROWING_ACTIVE_RE = re.compile(
    r"Document-turn tool narrowing: \d+ -> \d+ tools[^\n]*conversationId=(?P<cid>[^\s]+)"
)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--agent", default=DEFAULT_AGENT, help=f"AgentThing name (default: {DEFAULT_AGENT})")
    p.add_argument("--timeout-s", type=float, default=600.0, help="Chat HTTP timeout")
    p.add_argument(
        "--max-turn-wall-ms",
        type=int,
        default=MAX_TURN_WALL_MS,
        help=f"Fail if turnWallMs exceeds this (default: {MAX_TURN_WALL_MS})",
    )
    p.add_argument(
        "--log-window-minutes",
        type=int,
        default=15,
        help="ApplicationLog look-back window after the turn (default: 15)",
    )
    p.add_argument(
        "--attempts",
        type=int,
        default=2,
        help="Max Chat attempts when Tier C content fails (default: 2; latency+D2 checked per attempt)",
    )
    p.add_argument(
        "--skip-pre-smoke-snapshot",
        action="store_true",
        help="Skip GetAgentRuntimeSnapshot served-bytecode gate (not for acceptance)",
    )
    return p.parse_args(argv)


def tier_d_message() -> str:
    return f"{DOCUMENT_SKILL_SLASH}\n{KBM_PROMPT}"


def fetch_runtime_snapshot(*, server: str, key: str, agent: str) -> dict[str, Any]:
    url = build_thing_service_url(server, agent, "GetAgentRuntimeSnapshot")
    opts = json.dumps({"refresh": True, "includeTools": True})
    data = post_json(url, key, {"options": opts}, timeout_s=120)
    rows = data.get("rows") if isinstance(data, dict) else None
    if not isinstance(rows, list) or not rows:
        raise RuntimeError(f"GetAgentRuntimeSnapshot returned no rows: {str(data)[:500]}")
    raw = rows[0].get("result")
    if not isinstance(raw, str) or not raw.strip():
        raise RuntimeError("GetAgentRuntimeSnapshot missing result string")
    return json.loads(raw)


def verify_served_bytecode(snapshot: dict[str, Any]) -> tuple[bool, str]:
    tools = snapshot.get("tools")
    if not isinstance(tools, dict):
        return False, "tools section missing (stale bytecode or cache not loaded)"
    if "documentTurnToolNarrowingDisabled" not in tools:
        return False, "documentTurnToolNarrowingDisabled absent (stale bytecode)"
    ext = (snapshot.get("agent") or {}).get("extensionVersion")
    disabled = tools.get("documentTurnToolNarrowingDisabled")
    if disabled is True:
        return False, f"documentTurnToolNarrowingDisabled=true (narrowing off; extensionVersion={ext!r})"
    return True, f"extensionVersion={ext!r}, documentTurnToolNarrowingDisabled=false"


def extract_turn_perf(rows: list[dict[str, Any]]) -> dict[str, Any]:
    best: dict[str, Any] = {}
    for row in rows:
        if str(row.get("role") or "").lower() != "assistant":
            continue
        parsed, _ = parse_llm_usage_json_cell(row.get("llmUsageJson"))
        if not parsed:
            continue
        wall = parsed.get("turnWallMs")
        iters = parsed.get("agentIterations")
        if wall is not None or iters is not None:
            best = parsed
    return best


def fetch_recent_log_rows(
    *,
    server: str,
    key: str,
    window_minutes: int,
    max_items: int = 500,
) -> list[dict[str, str]]:
    end_date = utc_now_iso_z()
    end_dt = datetime.fromisoformat(end_date.replace("Z", "+00:00"))
    start_date = format_iso_z(end_dt - timedelta(minutes=max(1, window_minutes)))
    url = build_query_url(server)
    payload = default_payload(end_date, start_date, max_items)
    data = post_log_json(url, key, payload)
    return slim_rows(data)


def _rows_for_conversation(log_rows: list[dict[str, str]], conversation_id: str) -> list[dict[str, str]]:
    if not conversation_id:
        return []
    return [r for r in log_rows if conversation_id in r.get("content", "")]


def analyze_d2_log_evidence(log_rows: list[dict[str, str]], *, conversation_id: str) -> dict[str, Any]:
    scoped = _rows_for_conversation(log_rows, conversation_id)
    context_tools: list[int] = []
    context_schema: list[int] = []
    narrowing_hits: list[str] = []
    skip_hits: list[str] = []

    for row in scoped:
        content = row.get("content", "")
        for m in _CONTEXT_PLAN_RE.finditer(content):
            if m.group("cid") == conversation_id:
                context_tools.append(int(m.group("tools")))
                context_schema.append(int(m.group("schema")))
        if "Document-turn tool narrowing:" in content and "skipped" not in content:
            m = _NARROWING_ACTIVE_RE.search(content)
            if m is None or m.group("cid") == conversation_id:
                if conversation_id in content or m is not None:
                    narrowing_hits.append(content)
        if "Document-turn tool narrowing skipped:" in content and conversation_id in content:
            skip_hits.append(content)

    min_tools = min(context_tools) if context_tools else None
    min_schema = min(context_schema) if context_schema else None
    narrowing_log_seen = len(narrowing_hits) > 0
    d2_tools_ok = min_tools is not None and min_tools <= NARROWED_TOOL_COUNT

    return {
        "conversation_scoped_rows": len(scoped),
        "narrowing_log_seen": narrowing_log_seen,
        "narrowing_log_lines": len(narrowing_hits),
        "narrowing_skip_log_lines": len(skip_hits),
        "narrowing_skip_reasons": skip_hits[:3],
        "context_plan_min_tools": min_tools,
        "context_plan_min_tool_schema_chars": min_schema,
        "context_plan_samples": len(context_tools),
        "d2_evidence_ok": len(scoped) > 0 and (narrowing_log_seen or d2_tools_ok),
        "d2_fail_reason": (
            None
            if len(scoped) > 0 and (narrowing_log_seen or d2_tools_ok)
            else (
                "no ApplicationLog rows for conversation"
                if len(scoped) == 0
                else f"no narrowing log and context tools={min_tools} (expected <={NARROWED_TOOL_COUNT})"
            )
        ),
    }


def tier_d_passed(
    *,
    tier_c_ok: bool,
    turn_wall_ms: int | None,
    agent_iterations: int | None,
    d2_evidence: dict[str, Any],
    max_turn_wall_ms: int,
) -> bool:
    if not tier_c_ok:
        return False
    if turn_wall_ms is None or agent_iterations is None:
        return False
    if turn_wall_ms > max_turn_wall_ms:
        return False
    if agent_iterations > MAX_AGENT_ITERATIONS:
        return False
    return bool(d2_evidence.get("d2_evidence_ok"))


def run_one_attempt(
    *,
    server: str,
    key: str,
    agent: str,
    timeout_s: float,
    log_window_minutes: int,
    max_turn_wall_ms: int,
    attempt: int,
    attempts: int,
) -> tuple[bool, str, dict[str, Any]]:
    title = f"doc-index-kbm-tierd-{random_title_suffix()}"
    cid = get_or_create_conversation_id(server, key, agent, title=title, timeout_s=120)
    message = tier_d_message()
    print(f"\n=== attempt {attempt}/{attempts} ===")
    print(f"conversation: {cid}")

    t0 = time.perf_counter()
    final = run_turn_chat(
        server,
        key,
        agent,
        message=message,
        conversation_id=cid,
        host_context_obj=None,
        system_prompt=None,
        timeout_s=timeout_s,
    )
    chat_wall_ms = int((time.perf_counter() - t0) * 1000)
    print(f"chat_wall_ms: {chat_wall_ms}")
    print("\n--- FINAL (first 1200 chars) ---")
    print(final[:1200])

    time.sleep(STREAM_BUFFER_WAIT_DEFAULT_S + 3)
    rows = query_stream_data(server, key, source=cid, max_items=2000, timeout_s=120)
    tier_c_checks = analyze_turn(final=final, rows=rows)
    perf = extract_turn_perf(rows)
    turn_wall_ms = perf.get("turnWallMs")
    agent_iterations = perf.get("agentIterations")

    log_rows = fetch_recent_log_rows(
        server=server,
        key=key,
        window_minutes=log_window_minutes,
    )
    d2_evidence = analyze_d2_log_evidence(log_rows, conversation_id=cid)

    tier_c_ok = tier_c_passed(tier_c_checks)
    latency_ok = (
        turn_wall_ms is not None
        and agent_iterations is not None
        and int(turn_wall_ms) <= max_turn_wall_ms
        and int(agent_iterations) <= MAX_AGENT_ITERATIONS
    )
    d2_ok = bool(d2_evidence.get("d2_evidence_ok"))

    print("\n--- TIER C CHECKS ---")
    for name, ok in tier_c_checks.items():
        print(format_check(name, ok))

    print("\n--- TIER D LATENCY ---")
    print(f"{'OK' if latency_ok else 'FAIL'}  turnWallMs={turn_wall_ms} (max {max_turn_wall_ms})")
    print(f"{'OK' if agent_iterations is not None and agent_iterations <= MAX_AGENT_ITERATIONS else 'FAIL'}  "
          f"agentIterations={agent_iterations} (max {MAX_AGENT_ITERATIONS})")
    print(f"info rateWaitMs={perf.get('rateWaitMs')} toolCallCount={perf.get('toolCallCount')}")

    print("\n--- TIER D D2 EVIDENCE (conversation-scoped ApplicationLog) ---")
    print(f"info conversation_scoped_rows={d2_evidence['conversation_scoped_rows']}")
    print(f"{'OK' if d2_ok else 'FAIL'}  d2_evidence ({d2_evidence.get('d2_fail_reason')})")
    print(f"info narrowing_log_seen={d2_evidence['narrowing_log_seen']} "
          f"({d2_evidence['narrowing_log_lines']} lines)")
    print(f"info context_plan_min_tools={d2_evidence['context_plan_min_tools']} "
          f"(expect <={NARROWED_TOOL_COUNT}; incident 33)")
    print(f"info context_plan_min_toolSchemaChars={d2_evidence['context_plan_min_tool_schema_chars']} "
          f"(incident {INCIDENT_TOOL_SCHEMA_CHARS})")
    for reason in d2_evidence.get("narrowing_skip_reasons") or []:
        print(f"info skip_reason: {reason[:220]}")

    ok = tier_d_passed(
        tier_c_ok=tier_c_ok,
        turn_wall_ms=int(turn_wall_ms) if turn_wall_ms is not None else None,
        agent_iterations=int(agent_iterations) if agent_iterations is not None else None,
        d2_evidence=d2_evidence,
        max_turn_wall_ms=max_turn_wall_ms,
    )

    print("\n--- TIER D SUMMARY ---")
    print(f"{'OK' if tier_c_ok else 'FAIL'}  tier_c_content")
    print(f"{'OK' if latency_ok else 'FAIL'}  tier_d_latency")
    print(f"{'OK' if d2_ok else 'FAIL'}  tier_d_d2_evidence")

    summary = {
        "conversation_id": cid,
        "tier_c_ok": tier_c_ok,
        "latency_ok": latency_ok,
        "d2_ok": d2_ok,
        "turn_wall_ms": turn_wall_ms,
        "agent_iterations": agent_iterations,
        "d2_evidence": d2_evidence,
    }
    return ok, cid, summary


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(REPO_ROOT / ".env")
    server = require_env("DEV_SERVER")
    key = require_env("DEV_KEY")
    agent = args.agent.strip()
    attempts = max(1, min(int(args.attempts), 5))

    print(f"agent: {agent}")
    print(f"message prefix: {DOCUMENT_SKILL_SLASH}")

    if not args.skip_pre_smoke_snapshot:
        print("\n--- PRE-SMOKE RUNTIME SNAPSHOT ---")
        snapshot = fetch_runtime_snapshot(server=server, key=key, agent=agent)
        ok, detail = verify_served_bytecode(snapshot)
        print(f"{'OK' if ok else 'FAIL'}  served bytecode gate: {detail}")
        if not ok:
            print(
                "\nFAIL pre-smoke: deploy extension (load-ext-cloud -e) and confirm "
                "GetAgentRuntimeSnapshot.tools.documentTurnToolNarrowingDisabled is present and false.",
                file=sys.stderr,
            )
            return 1

    last_summary: dict[str, Any] | None = None
    for attempt in range(1, attempts + 1):
        ok, cid, summary = run_one_attempt(
            server=server,
            key=key,
            agent=agent,
            timeout_s=args.timeout_s,
            log_window_minutes=args.log_window_minutes,
            max_turn_wall_ms=args.max_turn_wall_ms,
            attempt=attempt,
            attempts=attempts,
        )
        last_summary = summary
        if ok:
            print(f"\nOK   Tier D KBM smoke passed (conversation {cid})")
            return 0
        if summary.get("tier_c_ok") and summary.get("latency_ok") and not summary.get("d2_ok"):
            print("\ninfo: Tier C + latency passed but D2 evidence missing; not retrying for Tier C.", file=sys.stderr)
            break

    print("\nFAIL Tier D KBM smoke", file=sys.stderr)
    if last_summary:
        if not last_summary.get("tier_c_ok"):
            print("  reason: Tier C content checks failed on all attempts", file=sys.stderr)
        elif not last_summary.get("d2_ok"):
            print(f"  reason: {last_summary['d2_evidence'].get('d2_fail_reason')}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
