"""
Live smoke for the training-stage configuration contracts (docs/agent/training-stage-configuration-contracts.md).

Runs ``docs/agent/evals/utilization_stage_contracts_v1.yaml`` via ``agent-eval``. Without
``DEV_SERVER`` / ``DEV_KEY`` the script exits 0 after printing ``SKIP`` unless
``--require-live`` is set.

Run from repository root::

    uv run utilization-stage-smoke
    uv run utilization-stage-smoke --require-live
    uv run utilization-stage-smoke --case nl_overview
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from test_scripts.agent_eval import EXIT_OK, main as agent_eval_main
from test_scripts.dev_import_control import load_dotenv

REPO_ROOT = Path(__file__).resolve().parents[1]
ENV_PATH = REPO_ROOT / ".env"
DEFAULT_SUITE_REL = Path("docs/agent/evals/utilization_stage_contracts_v1.yaml")
DEFAULT_AGENT_FILTER = "gpt_5_5"
DEFAULT_AGENT_ENV_KEY = "AGENT_EVAL_AGENT_GPT_5_5"
DEFAULT_AGENT_THING = "SCPA_Demo_Agent"


def resolve_suite_path() -> Path:
    return REPO_ROOT / DEFAULT_SUITE_REL


def has_dev_credentials() -> bool:
    return bool(os.environ.get("DEV_SERVER", "").strip() and os.environ.get("DEV_KEY", "").strip())


def ensure_agent_matrix_env(agent_thing: str | None) -> None:
    """Map the suite label to a live AgentThing via agent-eval env matrix."""
    if agent_thing and agent_thing.strip():
        os.environ[DEFAULT_AGENT_ENV_KEY] = agent_thing.strip()
        return
    if os.environ.get(DEFAULT_AGENT_ENV_KEY, "").strip():
        return
    for fallback in ("PARLER_E2E_AGENT_THING", "PARLER_DIAG_AGENT_THING"):
        value = os.environ.get(fallback, "").strip()
        if value:
            os.environ[DEFAULT_AGENT_ENV_KEY] = value
            return
    os.environ[DEFAULT_AGENT_ENV_KEY] = DEFAULT_AGENT_THING


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument(
        "--require-live",
        action="store_true",
        help="Fail when DEV_SERVER/DEV_KEY or the suite file is missing.",
    )
    p.add_argument("--case", dest="case_id", default=None, help="Run only this eval case id")
    p.add_argument(
        "--agent",
        default=None,
        help=f"AgentThing name for {DEFAULT_AGENT_FILTER} (default: env or {DEFAULT_AGENT_THING})",
    )
    p.add_argument(
        "--agent-filter",
        default=DEFAULT_AGENT_FILTER,
        help=f"agent-eval agentMatrix label filter (default: {DEFAULT_AGENT_FILTER})",
    )
    p.add_argument(
        "--suite",
        default=None,
        help="Override suite YAML path (default: docs/agent/evals/utilization_stage_contracts_v1.yaml)",
    )
    p.add_argument("--timeout", type=float, default=None, help="Per-service HTTP timeout seconds")
    p.add_argument("--fail-fast", action="store_true", help="Stop after first failure")
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    load_dotenv(ENV_PATH)

    suite_path = Path(args.suite).resolve() if args.suite else resolve_suite_path()

    if not has_dev_credentials():
        msg = f"SKIP utilization stage smoke — set DEV_SERVER and DEV_KEY in {ENV_PATH}"
        if args.require_live:
            print(msg, file=sys.stderr)
            return 1
        print(msg)
        return 0

    if not suite_path.is_file():
        msg = (
            f"Utilization stage eval suite not found: {suite_path}"
        )
        if args.require_live:
            print(msg, file=sys.stderr)
            return 1
        print(f"SKIP {msg}")
        return 0

    ensure_agent_matrix_env(args.agent)

    eval_argv = [
        "--suite",
        str(suite_path),
        "--agent-matrix",
        "env",
        "--agent-filter",
        args.agent_filter,
    ]
    if args.case_id:
        eval_argv.extend(["--case", args.case_id])
    if args.timeout is not None:
        eval_argv.extend(["--timeout", str(args.timeout)])
    if args.fail_fast:
        eval_argv.append("--fail-fast")

    print(f"suite: {suite_path}")
    print(f"agent-filter: {args.agent_filter}")
    print(f"agent-thing: {os.environ.get(DEFAULT_AGENT_ENV_KEY, DEFAULT_AGENT_THING)}")
    try:
        agent_eval_main(eval_argv)
    except SystemExit as exc:
        code = exc.code
        if code is None:
            return 1
        if isinstance(code, int):
            return code
        return 1
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main())
